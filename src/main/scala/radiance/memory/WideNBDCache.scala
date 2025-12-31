package radiance.memory

import freechips.rocketchip.rocket._
import chisel3._
import chisel3.util._
import chisel3.experimental.dataview._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket

/* The AMOALU in NBDCache is responsible for generating partial store data to be written into data array
   Unfortunately it is hardcoded to be xLen wide; we can't fake this with DummyCoreParams because too many
   things depend on it being 32 or 64, so we have to do this (somewhat hacky) workaround where we make
   a wider AMOALU then replace every output of the old, narrow AMOALU with this wide AMOALU. 
*/

class MaskMSHRFile(implicit edge: TLEdgeOut, p: Parameters) extends MSHRFile {
  val sdqMask_ = Mem(cfg.nSDQ, UInt(coreDataBytes.W))
  when (sdq_enq) {
    sdqMask_(sdq_alloc_id) := io.req.bits.mask
  }

  val replay_sdq_addr = RegEnable(replay_arb.io.out.bits.sdq_id, free_sdq)
  io.replay.bits.data := sdq(replay_sdq_addr)
  io.replay.bits.mask := sdqMask_(replay_sdq_addr)
}

class WideNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends NonBlockingDCache(staticIdForMetadataUseOnly) {
    override lazy val module = new WideNonBlockingDCacheModule(this)
}

class WideNonBlockingDCacheModule(outer: NonBlockingDCache) extends NonBlockingDCacheModule(outer) {
  // val maskMshrs = Module(new MaskMSHRFile)
  val wideAmoalu = Module(new AMOALU(wordBits))

  when ((s2_valid || s2_replay) && (rocket.isWrite(s2_req.cmd) || s2_data_correctable)) {
    s3_req.data := Mux(s2_data_correctable, s2_data_corrected, wideAmoalu.io.out)
  }

  val wideBypasses = List(
    ((s2_valid_masked || s2_replay) && !s2_sc_fail, s2_req, wideAmoalu.io.out),
    (s3_valid, s3_req, s3_req.data),
    (s4_valid, s4_req, s4_req.data)
  ).map(r => (r._1 && (s1_addr >> wordOffBits === r._2.addr >> wordOffBits) && rocket.isWrite(r._2.cmd), r._3))

  when (s1_clk_en) {
    when (wideBypasses.map(_._1).reduce(_||_)) {
      s2_store_bypass_data := PriorityMux(wideBypasses)
    }
  }

  when (s1_clk_en) {
    when (s1_write) {
      s2_req.mask := Mux(s1_replay, mshrs.io.replay.bits.mask, io.cpu.s1_data.mask)
      s2_req.data := Mux(s1_replay, mshrs.io.replay.bits.data, io.cpu.s1_data.data)
    }
    when (s1_recycled) {
      s2_req.mask := s1_req.mask
    }
  }

  wideAmoalu.io.mask := s2_req.mask // no StoreGen
  wideAmoalu.io.cmd := s2_req.cmd
  wideAmoalu.io.lhs := s2_data_word
  wideAmoalu.io.rhs := s2_req.data

  // mshr overrides
  /* when (maskMshrs.io.replay.valid) {
    s1_req := maskMshrs.io.replay.bits
  }
  mshrs.io.req.valid := s2_valid_masked && !s2_hit && (isPrefetch(s2_req.cmd) || isRead(s2_req.cmd) || isWrite(s2_req.cmd))
  mshrs.io.req.bits.viewAsSupertype(new Replay) := s2_req.viewAsSupertype(new HellaCacheReq)
  mshrs.io.req.bits.tag_match := s2_tag_match
  mshrs.io.req.bits.old_meta := Mux(s2_tag_match, L1Metadata(s2_repl_meta.tag, s2_hit_state), s2_repl_meta)
  mshrs.io.req.bits.way_en := Mux(s2_tag_match, s2_tag_match_way, s2_replaced_way_en)
  mshrs.io.req.bits.data := s2_req.data
  when (mshrs.io.req.fire) { replacer.miss }
  tl_out.a <> mshrs.io.mem_acquire
  readArb.io.in(1).valid := mshrs.io.replay.valid
  readArb.io.in(1).bits.addr := mshrs.io.replay.bits.addr
  readArb.io.in(1).bits.way_en := ~0.U(nWays.W)
  mshrs.io.replay.ready := readArb.io.in(1).ready
  s1_replay := mshrs.io.replay.valid && readArb.io.in(1).ready
  metaReadArb.io.in(1) <> mshrs.io.meta_read
  metaWriteArb.io.in(0) <> mshrs.io.meta_write
  prober.io.mshr_rdy := mshrs.io.probe_rdy
  mshrs.io.mem_grant.valid := tl_out.d.fire
  mshrs.io.mem_grant.bits := tl_out.d.bits
  writeArb.io.in(1).valid := tl_out.d.valid && grant_has_data &&
                               tl_out.d.bits.source < cfg.nMSHRs.U
  writeArb.io.in(1).bits.addr := mshrs.io.refill.addr
  writeArb.io.in(1).bits.way_en := mshrs.io.refill.way_en
  wbArb.io.in(1) <> mshrs.io.wb_req
  when (s2_nack_hit) { mshrs.io.req.valid := false.B }
  val s2_nack_victim = s2_hit && mshrs.io.secondary_miss
  val s2_nack_miss = !s2_hit && !mshrs.io.req.ready
  uncache_resp.bits := mshrs.io.resp.bits
  uncache_resp.valid := mshrs.io.resp.valid
  mshrs.io.resp.ready := RegNext(!(s1_valid || s1_replay))
  io.cpu.resp := Mux(mshrs.io.resp.ready, uncache_resp, cache_resp)
  io.cpu.ordered := mshrs.io.fence_rdy && !s1_valid && !s2_valid
  io.cpu.store_pending := mshrs.io.store_pending
  io.cpu.replay_next := (s1_replay && s1_read) || mshrs.io.replay_next */
}