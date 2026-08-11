// Parameterized copy of rocket-chip's SimpleHellaCacheIF.
//
// See LICENSE.SiFive / LICENSE.Berkeley -- derived from
// rocket-chip/src/main/scala/rocket/SimpleHellaCacheIF.scala.
//
// The upstream shim hardcodes its replay queue to depth 3, which caps the number
// of outstanding requests through a HellaCache at 3 regardless of nMSHRs. That is
// fine for a scalar RoCC client but throttles memory-level parallelism when the
// cache is used as a GPU L0d/L1: at a ~144-cycle round trip, 3 x 64 B in flight
// pins delivered bandwidth near 1.4 B/cycle. This copy makes the depth a
// parameter; everything else is unchanged.

package radiance.memory

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket.{HasL1HellaCacheParameters, HellaCacheReq, HellaCacheResp, HellaCacheIO}
import freechips.rocketchip.util._

class MuonHellaCacheIFReplayQueue(depth: Int)
    (implicit val p: Parameters) extends Module
    with HasL1HellaCacheParameters {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new HellaCacheReq))
    val nack = Flipped(Valid(Bits(coreParams.dcacheReqTagBits.W)))
    val resp = Flipped(Valid(new HellaCacheResp))
    val replay = Decoupled(new HellaCacheReq)
  })

  val inflight = RegInit(0.U(depth.W))
  val reqs = Reg(Vec(depth, new HellaCacheReq))

  val nackq = Module(new Queue(UInt(log2Up(depth).W), depth))
  val replaying = RegInit(false.B)

  val next_inflight_onehot = PriorityEncoderOH(~inflight)
  val next_inflight = OHToUInt(next_inflight_onehot)

  val next_replay = nackq.io.deq.bits
  val next_replay_onehot = UIntToOH(next_replay)
  val next_replay_req = reqs(next_replay)

  io.replay.valid := nackq.io.deq.valid && !replaying
  io.replay.bits := next_replay_req
  io.req.ready := !inflight.andR && !nackq.io.deq.valid && !io.nack.valid

  val nack_onehot = Cat(reqs.map(_.tag === io.nack.bits).reverse) & inflight
  val resp_onehot = Cat(reqs.map(_.tag === io.resp.bits.tag).reverse) & inflight

  val replay_complete = io.resp.valid && replaying && io.resp.bits.tag === next_replay_req.tag
  val nack_head = io.nack.valid && nackq.io.deq.valid && io.nack.bits === next_replay_req.tag

  nackq.io.enq.valid := io.nack.valid && !nack_head
  nackq.io.enq.bits := OHToUInt(nack_onehot)
  assert(!nackq.io.enq.valid || nackq.io.enq.ready,
    "MuonHellaCacheIF: ReplayQueue nack queue overflow")

  nackq.io.deq.ready := replay_complete
  assert(!nackq.io.deq.ready || nackq.io.deq.valid,
    "MuonHellaCacheIF: ReplayQueue nack queue underflow")

  inflight := (inflight | Mux(io.req.fire, next_inflight_onehot, 0.U)) &
                          ~Mux(io.resp.valid, resp_onehot, 0.U)

  when (io.req.fire) {
    reqs(next_inflight) := io.req.bits
  }

  when (io.replay.fire) { replaying := true.B }
  when (nack_head || replay_complete) { replaying := false.B }
}

class MuonHellaCacheIF(replayDepth: Int = 3)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val requestor = Flipped(new HellaCacheIO())
    val cache = new HellaCacheIO
  })
  io <> DontCare

  val replayq = Module(new MuonHellaCacheIFReplayQueue(replayDepth))
  val req_arb = Module(new Arbiter(new HellaCacheReq, 2))

  val req_helper = DecoupledHelper(
    req_arb.io.in(1).ready,
    replayq.io.req.ready,
    io.requestor.req.valid)

  req_arb.io.in(0) <> replayq.io.replay
  req_arb.io.in(1).valid := req_helper.fire(req_arb.io.in(1).ready)
  req_arb.io.in(1).bits := io.requestor.req.bits
  io.requestor.req.ready := req_helper.fire(io.requestor.req.valid)
  replayq.io.req.valid := req_helper.fire(replayq.io.req.ready)
  replayq.io.req.bits := io.requestor.req.bits

  val s0_req_fire = io.cache.req.fire
  val s1_req_fire = RegNext(s0_req_fire)
  val s2_req_fire = RegNext(s1_req_fire)
  val s1_req_tag = RegNext(io.cache.req.bits.tag)
  val s2_req_tag = RegNext(s1_req_tag)

  assert(!RegNext(io.cache.s2_nack) || !s2_req_fire || io.cache.s2_nack)
  assert(!io.cache.s2_nack || !io.cache.req.ready)

  io.cache.req <> req_arb.io.out
  io.cache.s1_kill := false.B
  io.cache.s1_data := RegEnable(req_arb.io.out.bits, s0_req_fire)
  io.cache.s2_kill := false.B

  replayq.io.nack.valid := io.cache.s2_nack && s2_req_fire
  replayq.io.nack.bits := s2_req_tag
  replayq.io.resp := io.cache.resp
  io.requestor.resp := io.cache.resp

  assert(!s2_req_fire || !io.cache.s2_xcpt.asUInt.orR, "MuonHellaCacheIF exception")
}
