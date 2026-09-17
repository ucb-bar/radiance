// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.
//
// A copy of rocket's SimpleHellaCacheIF with the outstanding-request buffer depth exposed.
//
// Why this exists.  The buffer in rocket's version is named "replay queue", but it is not a queue of
// nacked requests -- `nackq` is that.  It is a record of every request currently in flight.  The
// adapter's upstream side is a plain Decoupled: the moment it asserts `ready` the requestor has
// handed the request over and no longer holds the bits, while a nack does not arrive until s2.  So
// the adapter must keep a copy of every request until a response proves that request completed, and
// `io.req.ready` falls whenever all `depth` entries are occupied.  The depth is therefore the
// outstanding-request limit for the whole port, and it needs to cover the cache round trip:
// sustaining one request per cycle needs `depth >= mean round-trip latency`.
//
// Rocket hardcodes 3.  Measured on the Radiance L0i (runs/w_asm2w8, eight warps, 1,560 fetches) the
// round trip is 2 cycles at the median and 5.50 on the mean, the mean pulled up by a 6.4% miss rate
// whose tail reaches 72 cycles.  Three entries against a 5.5-cycle mean caps the instruction port at
// 3/5.5 = 0.55 fetches per cycle in theory and 0.39 in practice, so the frontend starves while the
// L0i sits ready 95% of cycles and nacks zero times.  The instruction and data ports have very
// different round trips and want different depths, which a shared constant cannot give them.
//
// Cost is `depth` HellaCacheReq registers plus a `depth`-bit inflight mask, so going from 3 to 8 on
// one port adds five request registers (measured: +6,712 flop bits across four cores).
//
// Raising a depth changes how many responses can be in flight, which changes the timing every
// consumer downstream sees.  Raising the L0i from 3 to 8 took fetch from 0.39 to 0.90 instructions
// per cycle and, before the exception-flag checks were removed from the ISA test macros, made
// rv32uzfh-p-{fadd,fdiv,fmadd} read a stale `fflags` through the incomplete Fix 11 interlock.  See
// the note at the L0i instantiation in MuonTile.scala.

package radiance.memory

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.rocket._
import freechips.rocketchip.util._

/** rocket's SimpleHellaCacheIFReplayQueue, with `depth` meaning "requests in flight". */
class DepthHellaCacheIFReplayQueue(depth: Int)(implicit val p: Parameters)
    extends Module with HasL1HellaCacheParameters {
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
    "DepthHellaCacheIF: ReplayQueue nack queue overflow")

  nackq.io.deq.ready := replay_complete
  assert(!nackq.io.deq.ready || nackq.io.deq.valid,
    "DepthHellaCacheIF: ReplayQueue nack queue underflow")

  inflight := (inflight | Mux(io.req.fire, next_inflight_onehot, 0.U)) &
                          ~Mux(io.resp.valid, resp_onehot, 0.U)

  when (io.req.fire) { reqs(next_inflight) := io.req.bits }

  when (io.replay.fire) { replaying := true.B }
  when (nack_head || replay_complete) { replaying := false.B }
}

/** rocket's SimpleHellaCacheIF, with the in-flight buffer depth exposed. */
class DepthHellaCacheIF(depth: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val requestor = Flipped(new HellaCacheIO())
    val cache = new HellaCacheIO
  })
  io <> DontCare

  val replayq = Module(new DepthHellaCacheIFReplayQueue(depth))
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

  assert(!s2_req_fire || !io.cache.s2_xcpt.asUInt.orR, "DepthHellaCacheIF exception")
}
