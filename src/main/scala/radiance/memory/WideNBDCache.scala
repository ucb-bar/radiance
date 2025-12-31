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

/* the rocket NBDcache does not really support mask input from the coreside; in stage 2,
 * it tries to regenerate the request mask based on the input address and size. this works
 * but not for us; the reasons are twofold. first, tilelink requires active byte lanes, so
 * the address field is bus-width-aligned, instead of byte-aligned. second, coalescer outputs
 * may leave holes in the mask or write a non-power-of-2 number of bytes, so we cannot
 * "recover" the rocket-style inputs from the given address/size/mask. thus, we must override
 * the mask generation by flopping the input. this presents a challenge with MSHRs when
 * requests are replayed. in the original design, the mask for the replayed request is simply
 * set to 0, since it will be regenerated on use. we however must store the mask alongside
 * the data, which is the purpose of the MaskMSHRFile, and all the additional logic. */

class MaskMSHRFile(implicit edge: TLEdgeOut, p: Parameters) extends MSHRFile {
  val sdqMask = Mem(cfg.nSDQ, UInt(coreDataBytes.W))
  when (sdq_enq) {
    sdqMask(sdq_alloc_id) := io.req.bits.mask
  }

  val replay_sdq_addr = RegEnable(replay_arb.io.out.bits.sdq_id, free_sdq)
  io.replay.bits.data := sdq(replay_sdq_addr)
  io.replay.bits.mask := sdqMask(replay_sdq_addr)
}

class WideNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters) extends NonBlockingDCache(staticIdForMetadataUseOnly) {
    override lazy val module = new WideNonBlockingDCacheModule(this)
}

class WideNonBlockingDCacheModule(outer: NonBlockingDCache) extends NonBlockingDCacheModule(outer) {
  val maskMshrs = Module(new MaskMSHRFile)
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
      s2_req.mask := Mux(s1_replay, maskMshrs.io.replay.bits.mask, io.cpu.s1_data.mask)
      s2_req.data := Mux(s1_replay, maskMshrs.io.replay.bits.data, io.cpu.s1_data.data)
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
  when (maskMshrs.io.replay.valid) {
    s1_req := maskMshrs.io.replay.bits
  }
  maskMshrs.io.req.valid := s2_valid_masked && !s2_hit && (isPrefetch(s2_req.cmd) || isRead(s2_req.cmd) || isWrite(s2_req.cmd))
  maskMshrs.io.req.bits.viewAsSupertype(new Replay) := s2_req.viewAsSupertype(new HellaCacheReq)
  maskMshrs.io.req.bits.tag_match := s2_tag_match
  maskMshrs.io.req.bits.old_meta := Mux(s2_tag_match, L1Metadata(s2_repl_meta.tag, s2_hit_state), s2_repl_meta)
  maskMshrs.io.req.bits.way_en := Mux(s2_tag_match, s2_tag_match_way, s2_replaced_way_en)
  maskMshrs.io.req.bits.data := s2_req.data
  when (maskMshrs.io.req.fire) { replacer.miss }
  tl_out.a <> maskMshrs.io.mem_acquire
  readArb.io.in(1).valid := maskMshrs.io.replay.valid
  readArb.io.in(1).bits.addr := maskMshrs.io.replay.bits.addr
  maskMshrs.io.replay.ready := readArb.io.in(1).ready
  s1_replay := maskMshrs.io.replay.valid && readArb.io.in(1).ready
  metaReadArb.io.in(1) <> maskMshrs.io.meta_read
  metaWriteArb.io.in(0) <> maskMshrs.io.meta_write
  prober.io.mshr_rdy := maskMshrs.io.probe_rdy
  maskMshrs.io.mem_grant.valid := tl_out.d.fire
  maskMshrs.io.mem_grant.bits := tl_out.d.bits
  writeArb.io.in(1).bits.addr := maskMshrs.io.refill.addr
  writeArb.io.in(1).bits.way_en := maskMshrs.io.refill.way_en
  tl_out.e <> maskMshrs.io.mem_finish
  wbArb.io.in(1) <> maskMshrs.io.wb_req
  when (s2_nack_hit) { maskMshrs.io.req.valid := false.B }

  val s2_nack_victim_ = s2_hit && maskMshrs.io.secondary_miss
  val s2_nack_miss_ = !s2_hit && !maskMshrs.io.req.ready
  val s2_nack_ = s2_nack_hit || s2_nack_victim_ || s2_nack_miss_
  s2_valid_masked := s2_valid && !s2_nack_ && !io.cpu.s2_kill
  block_miss := (s2_valid || block_miss) && s2_nack_miss_
  io.cpu.s2_nack := s2_valid && s2_nack_

  uncache_resp.bits := maskMshrs.io.resp.bits
  uncache_resp.valid := maskMshrs.io.resp.valid
  maskMshrs.io.resp.ready := RegNext(!(s1_valid || s1_replay))
  io.cpu.resp := Mux(maskMshrs.io.resp.ready, uncache_resp, cache_resp)
  io.cpu.ordered := maskMshrs.io.fence_rdy && !s1_valid && !s2_valid
  io.cpu.store_pending := maskMshrs.io.store_pending
  io.cpu.replay_next := (s1_replay && s1_read) || maskMshrs.io.replay_next
}