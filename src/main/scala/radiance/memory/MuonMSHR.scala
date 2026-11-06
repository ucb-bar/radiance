package radiance.memory

// rocket's NBD cache MSHR and MSHR file (freechips.rocketchip.rocket.NBDcache.scala, MSHR and MSHRFile), copied
// to allow several misses per cache set (multiMiss), for the incoherent cluster L1 only.
//
// rocket allows one live MSHR per set: a miss to a set with a live MSHR of another line is nacked, and the
// cache nacks a hit to any way of such a set. In memstress on 4 SMs the cluster L1 nacked 30 % of its requests
// for these rules (S18 trace), and a larger set count (fewer same-set misses) gained 10-17 %.
//
// With multiMiss:
//  * a request matches an MSHR by line (set and tag), not by set; a miss to a line held by an MSHR joins it as
//    before, any other miss allocates a new MSHR if its victim way is not one a live MSHR of the set is
//    filling (the cache picks an unreserved victim: MuonNonBlockingDCacheModule "victim choice");
//  * io.secondary_miss means "the line has a live MSHR";
//  * the cache nacks a hit only on a reserved way (preciseVictimNack, required).
// Hazards: two MSHRs of one set own different ways, and every metadata and data write names its way. A victim's
// old tag stays valid until its MSHR's final metadata write (incoherent: no clear), so a request to it hits a
// reserved way and is nacked until then; a dirty victim's release is acknowledged before that write
// (s_wb_resp waits for the ack), so a later miss to it reads the written-back data. A way stays reserved from
// allocation until its MSHR idles, at least two cycles after its last metadata write
// (s_meta_write_resp, s_drain_rpq), so a request whose metadata read preceded that write cannot pick the way.
// The rest of the code is unchanged from rocket.

import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import freechips.rocketchip.rocket._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config.Parameters

class MuonMSHR(id: Int, multiMiss: Boolean)(implicit edge: TLEdgeOut, p: Parameters) extends L1HellaCacheModule()(p) {
  val io = IO(new Bundle {
    val req_pri_val    = Input(Bool())
    val req_pri_rdy    = Output(Bool())
    val req_sec_val    = Input(Bool())
    val req_sec_rdy    = Output(Bool())
    val req_bits       = Input(new MSHRReqInternal())

    val idx_match       = Output(Bool())
    // live and holding the line of io.req_bits.addr (set and tag)
    val line_match      = Output(Bool())
    val tag             = Output(Bits(tagBits.W))

    val mem_acquire  = Decoupled(new TLBundleA(edge.bundle))
    val mem_grant = Flipped(Valid(new TLBundleD(edge.bundle)))
    val mem_finish = Decoupled(new TLBundleE(edge.bundle))

    val refill = Output(new L1RefillReq()) // Data is bypassed
    val meta_read = Decoupled(new L1MetaReadReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val replay = Decoupled(new ReplayInternal)
    val wb_req = Decoupled(new WritebackReq(edge.bundle))
    val probe_rdy = Output(Bool())
  })

  val s_invalid :: s_wb_req :: s_wb_resp :: s_meta_clear :: s_refill_req :: s_refill_resp :: s_meta_write_req :: s_meta_write_resp :: s_drain_rpq :: Nil = Enum(9)
  val state = RegInit(s_invalid)

  val req = Reg(new MSHRReqInternal)
  val req_idx = req.addr(untagBits-1,blockOffBits)
  val req_tag = req.addr >> untagBits
  val req_block_addr = (req.addr >> blockOffBits) << blockOffBits
  val idx_match = req_idx === io.req_bits.addr(untagBits-1,blockOffBits)
  val line_match = idx_match && req_tag === (io.req_bits.addr >> untagBits)
  // multiMiss: a secondary miss joins only the MSHR of its own line (other MSHRs may hold the set)
  val join_match = if (multiMiss) line_match else idx_match

  val new_coh = RegInit(ClientMetadata.onReset)
  val (_, shrink_param, coh_on_clear)    = req.old_meta.coh.onCacheControl(M_FLUSH)
  val grow_param                                  = new_coh.onAccess(req.cmd)._2
  val coh_on_grant                                = new_coh.onGrant(req.cmd, io.mem_grant.bits.param)
  // We only accept secondary misses if we haven't yet sent an Acquire to outer memory
  // or if the Acquire that was sent will obtain a Grant with sufficient permissions
  // to let us replay this new request. I.e. we don't handle multiple outstanding
  // Acquires on the same block for now.
  val (cmd_requires_second_acquire, is_hit_again, _, dirtier_coh, dirtier_cmd) =
    new_coh.onSecondaryAccess(req.cmd, io.req_bits.cmd)

  val states_before_refill = Seq(s_wb_req, s_wb_resp, s_meta_clear)
  val (_, _, refill_done, refill_address_inc) = edge.addr_inc(io.mem_grant)
  val sec_rdy = join_match &&
                  (state.isOneOf(states_before_refill) ||
                    (state.isOneOf(s_refill_req, s_refill_resp) &&
                      !cmd_requires_second_acquire && !refill_done))

  val rpq = Module(new Queue(new ReplayInternal, cfg.nRPQ))
  rpq.io.enq.valid := (io.req_pri_val && io.req_pri_rdy || io.req_sec_val && sec_rdy) && !isPrefetch(io.req_bits.cmd)
  rpq.io.enq.bits := io.req_bits
  rpq.io.deq.ready := (io.replay.ready && state === s_drain_rpq) || state === s_invalid

  val acked = Reg(Bool())
  when (io.mem_grant.valid) { acked := true.B }

  when (state === s_drain_rpq && !rpq.io.deq.valid) {
    state := s_invalid
  }
  when (state === s_meta_write_resp) {
    // this wait state allows us to catch RAW hazards on the tags via nack_victim
    state := s_drain_rpq
  }
  when (state === s_meta_write_req && io.meta_write.ready) {
    state := s_meta_write_resp
  }
  when (state === s_refill_resp && refill_done) {
    new_coh := coh_on_grant
    state := s_meta_write_req
  }
  when (io.mem_acquire.fire) { // s_refill_req
    state := s_refill_resp
  }
  when (state === s_meta_clear && io.meta_write.ready) {
    state := s_refill_req
  }
  when (state === s_wb_resp && io.wb_req.ready && acked) {
    state := s_meta_clear
  }
  when (io.wb_req.fire) { // s_wb_req
    state := s_wb_resp
  }
  when (io.req_sec_val && io.req_sec_rdy) { // s_wb_req, s_wb_resp, s_refill_req
    //If we get a secondary miss that needs more permissions before we've sent
    //  out the primary miss's Acquire, we can upgrade the permissions we're 
    //  going to ask for in s_refill_req
    req.cmd := dirtier_cmd
    when (is_hit_again) {
      new_coh := dirtier_coh
    }
  }
  when (io.req_pri_val && io.req_pri_rdy) {
    req := io.req_bits
    acked := false.B
    val old_coh = io.req_bits.old_meta.coh
    val needs_wb = old_coh.onCacheControl(M_FLUSH)._1
    val (is_hit, _, coh_on_hit) = old_coh.onAccess(io.req_bits.cmd)
    when (io.req_bits.tag_match) {
      when (is_hit) { // set dirty bit
        new_coh := coh_on_hit
        state := s_meta_write_req
      }.otherwise { // upgrade permissions
        new_coh := old_coh
        state := s_refill_req
      }
    }.otherwise { // writback if necessary and refill
      new_coh := ClientMetadata.onReset
      state := Mux(needs_wb, s_wb_req, s_meta_clear)
    }
  }

  val grantackq = Module(new Queue(new TLBundleE(edge.bundle), 1))
  val can_finish = state.isOneOf(s_invalid, s_refill_req)
  grantackq.io.enq.valid := refill_done && edge.isRequest(io.mem_grant.bits)
  grantackq.io.enq.bits := edge.GrantAck(io.mem_grant.bits)
  io.mem_finish.valid := grantackq.io.deq.valid && can_finish
  io.mem_finish.bits := grantackq.io.deq.bits
  grantackq.io.deq.ready := io.mem_finish.ready && can_finish

  io.idx_match := (state =/= s_invalid) && idx_match
  io.line_match := (state =/= s_invalid) && line_match
  io.refill.way_en := req.way_en
  io.refill.addr := req_block_addr | refill_address_inc
  io.tag := req_tag 
  io.req_pri_rdy := state === s_invalid
  io.req_sec_rdy := sec_rdy && rpq.io.enq.ready

  val meta_hazard = RegInit(0.U(2.W))
  when (meta_hazard =/= 0.U) { meta_hazard := meta_hazard + 1.U }
  when (io.meta_write.fire) { meta_hazard := 1.U }
  io.probe_rdy := !idx_match || (!state.isOneOf(states_before_refill) && meta_hazard === 0.U)

  io.meta_write.valid := state.isOneOf(s_meta_write_req, s_meta_clear)
  io.meta_write.bits.idx := req_idx
  io.meta_write.bits.tag := io.tag
  io.meta_write.bits.data.coh := Mux(state === s_meta_clear, coh_on_clear, new_coh)
  io.meta_write.bits.data.tag := io.tag
  io.meta_write.bits.way_en := req.way_en

  io.wb_req.valid := state === s_wb_req
  io.wb_req.bits.source := id.U
  io.wb_req.bits.tag := req.old_meta.tag
  io.wb_req.bits.idx := req_idx
  io.wb_req.bits.param := shrink_param
  io.wb_req.bits.way_en := req.way_en
  io.wb_req.bits.voluntary := true.B

  io.mem_acquire.valid := state === s_refill_req && grantackq.io.enq.ready
  io.mem_acquire.bits := edge.AcquireBlock(
                                fromSource = id.U,
                                toAddress = Cat(io.tag, req_idx) << blockOffBits,
                                lgSize = lgCacheBlockBytes.U,
                                growPermissions = grow_param)._2

  io.meta_read.valid := state === s_drain_rpq
  io.meta_read.bits.idx := req_idx
  io.meta_read.bits.tag := io.tag
  io.meta_read.bits.way_en := ~(0.U(nWays.W))

  io.replay.valid := state === s_drain_rpq && rpq.io.deq.valid
  io.replay.bits := rpq.io.deq.bits
  io.replay.bits.phys := true.B
  io.replay.bits.addr := Cat(io.tag, req_idx, rpq.io.deq.bits.addr(blockOffBits-1,0))

  when (!io.meta_read.ready) {
    rpq.io.deq.ready := false.B
    io.replay.bits.cmd := M_FLUSH_ALL /* nop */
  }
}

class MuonMSHRFile(val multiMiss: Boolean = false)(implicit edge: TLEdgeOut, p: Parameters)
    extends L1HellaCacheModule()(p) {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new MSHRReq))
    val resp = Decoupled(new HellaCacheResp)
    val secondary_miss = Output(Bool())

    val mem_acquire  = Decoupled(new TLBundleA(edge.bundle))
    val mem_grant = Flipped(Valid(new TLBundleD(edge.bundle)))
    val mem_finish = Decoupled(new TLBundleE(edge.bundle))

    val refill = Output(new L1RefillReq())
    val meta_read = Decoupled(new L1MetaReadReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val replay = Decoupled(new Replay)
    val wb_req = Decoupled(new WritebackReq(edge.bundle))

    val probe_rdy = Output(Bool())
    val fence_rdy = Output(Bool())
    val replay_next = Output(Bool())
    val store_pending = Output(Bool())
  })

  // determine if the request is cacheable or not
  val cacheable = edge.manager.supportsAcquireBFast(io.req.bits.addr, lgCacheBlockBytes.U)

  val sdq_val = RegInit(0.U(cfg.nSDQ.W))
  val sdq_alloc_id = PriorityEncoder(~sdq_val(cfg.nSDQ-1,0))
  val sdq_rdy = !sdq_val.andR
  val sdq_enq = io.req.valid && io.req.ready && cacheable && isWrite(io.req.bits.cmd)
  val sdq = Mem(cfg.nSDQ, UInt(coreDataBits.W))
  when (sdq_enq) { sdq(sdq_alloc_id) := io.req.bits.data }

  val idxMatch = Wire(Vec(cfg.nMSHRs, Bool()))
  val lineMatch = Wire(Vec(cfg.nMSHRs, Bool()))
  val tagList = Wire(Vec(cfg.nMSHRs, Bits(tagBits.W)))
  // rocket: at most one live MSHR per set, so its tag decides; multiMiss: any live MSHR holding the line
  val tag_match = if (multiMiss) lineMatch.asUInt.orR else Mux1H(idxMatch, tagList) === io.req.bits.addr >> untagBits
  // ways of the requested set that live MSHRs are filling; multiMiss allocates only an unreserved victim way
  val wayList = Wire(Vec(cfg.nMSHRs, UInt(nWays.W)))
  val reservedWaysOfReq = idxMatch.zip(wayList).map { case (m, w) => Mux(m, w, 0.U) }.reduce(_ | _)
  val victimFree = (reservedWaysOfReq & io.req.bits.way_en) === 0.U

  val wbTagList = Wire(Vec(cfg.nMSHRs, Bits()))
  val refillMux = Wire(Vec(cfg.nMSHRs, new L1RefillReq))
  val meta_read_arb = Module(new Arbiter(new L1MetaReadReq, cfg.nMSHRs))
  val meta_write_arb = Module(new Arbiter(new L1MetaWriteReq, cfg.nMSHRs))
  val wb_req_arb = Module(new Arbiter(new WritebackReq(edge.bundle), cfg.nMSHRs))
  val replay_arb = Module(new Arbiter(new ReplayInternal, cfg.nMSHRs))
  val alloc_arb = Module(new Arbiter(Bool(), cfg.nMSHRs))
  alloc_arb.io.in.foreach(_.bits := DontCare)

  var idx_match = false.B
  var pri_rdy = false.B
  var sec_rdy = false.B

  io.fence_rdy := true.B
  io.probe_rdy := true.B

  val mshrs = (0 until cfg.nMSHRs) map { i =>
    val mshr = Module(new MuonMSHR(i, multiMiss))

    idxMatch(i) := mshr.io.idx_match
    lineMatch(i) := mshr.io.line_match
    wayList(i) := mshr.io.refill.way_en
    tagList(i) := mshr.io.tag
    wbTagList(i) := mshr.io.wb_req.bits.tag

    alloc_arb.io.in(i).valid := mshr.io.req_pri_rdy
    mshr.io.req_pri_val := alloc_arb.io.in(i).ready

    mshr.io.req_sec_val := io.req.valid && sdq_rdy && tag_match && (if (multiMiss) lineMatch(i) else true.B)
    mshr.io.req_bits.viewAsSupertype(new HellaCacheReqInternal) := io.req.bits.viewAsSupertype(new HellaCacheReqInternal)
    mshr.io.req_bits.tag_match := io.req.bits.tag_match
    mshr.io.req_bits.old_meta := io.req.bits.old_meta
    mshr.io.req_bits.way_en := io.req.bits.way_en
    mshr.io.req_bits.sdq_id := sdq_alloc_id

    meta_read_arb.io.in(i) <> mshr.io.meta_read
    meta_write_arb.io.in(i) <> mshr.io.meta_write
    wb_req_arb.io.in(i) <> mshr.io.wb_req
    replay_arb.io.in(i) <> mshr.io.replay

    mshr.io.mem_grant.valid := io.mem_grant.valid && io.mem_grant.bits.source === i.U
    mshr.io.mem_grant.bits := io.mem_grant.bits
    refillMux(i) := mshr.io.refill

    pri_rdy = pri_rdy || mshr.io.req_pri_rdy
    sec_rdy = sec_rdy || mshr.io.req_sec_rdy
    idx_match = idx_match || mshr.io.idx_match

    when (!mshr.io.req_pri_rdy) { io.fence_rdy := false.B }
    when (!mshr.io.probe_rdy) { io.probe_rdy := false.B }

    mshr
  }


  alloc_arb.io.out.ready := io.req.valid && sdq_rdy && cacheable &&
    (if (multiMiss) !tag_match && victimFree else !idx_match)

  io.meta_read <> meta_read_arb.io.out
  io.meta_write <> meta_write_arb.io.out
  io.wb_req <> wb_req_arb.io.out

  val mmio_alloc_arb = Module(new Arbiter(Bool(), nIOMSHRs))
  mmio_alloc_arb.io.in.foreach(_.bits := DontCare)
  val resp_arb = Module(new Arbiter(new HellaCacheResp, nIOMSHRs))

  var mmio_rdy = false.B
  io.replay_next := false.B

  val mmios = (0 until nIOMSHRs) map { i =>
    val id = cfg.nMSHRs + i
    val mshr = Module(new IOMSHR(id))

    mmio_alloc_arb.io.in(i).valid := mshr.io.req.ready
    mshr.io.req.valid := mmio_alloc_arb.io.in(i).ready
    mshr.io.req.bits := io.req.bits

    mmio_rdy = mmio_rdy || mshr.io.req.ready

    mshr.io.mem_ack.bits := io.mem_grant.bits
    mshr.io.mem_ack.valid := io.mem_grant.valid && io.mem_grant.bits.source === id.U

    resp_arb.io.in(i) <> mshr.io.resp

    when (!mshr.io.req.ready) { io.fence_rdy := false.B }
    when (mshr.io.replay_next) { io.replay_next := true.B }

    mshr
  }

  mmio_alloc_arb.io.out.ready := io.req.valid && !cacheable

  TLArbiter.lowestFromSeq(edge, io.mem_acquire, mshrs.map(_.io.mem_acquire) ++ mmios.map(_.io.mem_access))
  TLArbiter.lowestFromSeq(edge, io.mem_finish,  mshrs.map(_.io.mem_finish))

  io.store_pending := sdq_val =/= 0.U || mmios.map(_.io.store_pending).orR

  io.resp <> resp_arb.io.out
  io.req.ready := Mux(!cacheable,
                    mmio_rdy,
                    if (multiMiss) sdq_rdy && Mux(tag_match, sec_rdy, pri_rdy && victimFree)
                    else sdq_rdy && Mux(idx_match, tag_match && sec_rdy, pri_rdy))
  // multiMiss: a miss "joins" only the MSHR of its line; other live MSHRs on the set do not block it
  io.secondary_miss := (if (multiMiss) tag_match else idx_match)
  io.refill := refillMux(io.mem_grant.bits.source)

  val free_sdq = io.replay.fire && isWrite(io.replay.bits.cmd)
  io.replay.bits.data := sdq(RegEnable(replay_arb.io.out.bits.sdq_id, free_sdq))
  io.replay.bits.mask := 0.U
  io.replay.valid := replay_arb.io.out.valid
  replay_arb.io.out.ready := io.replay.ready
  io.replay.bits.viewAsSupertype(new HellaCacheReqInternal) <> replay_arb.io.out.bits.viewAsSupertype(new HellaCacheReqInternal)

  when (io.replay.valid || sdq_enq) {
    sdq_val := sdq_val & ~(UIntToOH(replay_arb.io.out.bits.sdq_id) & Fill(cfg.nSDQ, free_sdq)) |
               PriorityEncoderOH(~sdq_val(cfg.nSDQ-1,0)) & Fill(cfg.nSDQ, sdq_enq)
  }
}
