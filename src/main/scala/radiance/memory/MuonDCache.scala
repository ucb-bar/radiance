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

// =====================================================================================
/* this design is a modified version of rocket's NBDcache, with a few notable changes:
 * - support for wider partial store data with a wider amoalu
 * - support for external write masks from cpu
 * - support for a full cache flush
 * more below.*/
// =====================================================================================

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

// cache flush is started after all other operations cease
// (i.e. no pending commands in pipeline, no mshrs, shutdown ready for all interfaces)
class CacheFlushUnit(implicit edge: TLEdgeOut, p: Parameters) extends L1HellaCacheModule()(p) {
  require(isPow2(nWays), "for simplicity")

  val io = IO(new Bundle {
    val flush = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())

    val meta_read = Decoupled(new L1MetaReadReq)
    val meta_write = Decoupled(new L1MetaWriteReq)
    val meta_resp = Input(Vec(nWays, new L1Metadata))
    val wb_req = Decoupled(new WritebackReq(edge.bundle))
  })

  val flushing = RegInit(false.B)
  val flushCounter = Counter(nSets * nWays)
  // val respPending = RegInit(false.B)
  // val respCounter = Counter(nSets * nWays)

  io.busy := flushing
  io.done := false.B

  when (io.flush) {
    assert(!flushing, "already flushing")
    flushing := true.B
  }

  // when (io.wbReq.fire || io.meta_write.fire) {
  //   val wrap = flushCounter.inc()
  //   when (wrap) {
  //     flushing := false.B
  //     io.done := true.B
  //   }
  // }
  when (io.meta_read.fire) {
    // TODO: increment a pending requests counter and only assert done when responses are back
    val wrap = flushCounter.inc()
    when (wrap) {
      flushing := false.B
      io.done := true.B
    }
  }

  io.meta_read.bits.idx := flushCounter.value >> log2Ceil(nWays)
  io.meta_read.bits.tag := DontCare
  // io.meta_read.bits.way_en := ~0.U(nWays.W) // doesnt matter either (???)
  io.meta_read.bits.way_en := UIntToOH(flushCounter.value(log2Ceil(nWays) - 1, 0))

  val metaReadFired = RegNext(io.meta_read.fire)
  val meta = RegEnable(
    io.meta_resp(RegNext(flushCounter.value(log2Ceil(nWays) - 1, 0))), metaReadFired)
  val metaReq = RegEnable(RegNext(io.meta_read.bits), metaReadFired) // corresponds to resp
  val metaValid = RegInit(false.B)

  val clearInvalid = metaValid && !meta.coh.isValid()
  val clearClean = io.meta_write.fire
  val clearDirty = io.wb_req.fire
  val anyClear = clearInvalid || clearClean || clearDirty // TODO: response counter

  val readyForMeta = anyClear || (!metaValid)
  io.meta_read.valid := flushing && readyForMeta

  when (RegNext(io.meta_read.fire)) {
    metaValid := true.B
  }.elsewhen(anyClear) {
    metaValid := false.B
  }

  val (isDirty, respType, nextCoh) = meta.coh.onCacheControl(M_FLUSH)
  // invalid line gets automatically skipped

  // clean line: invalidate in tag array only
  io.meta_write.valid := metaValid && meta.coh.isValid() && !isDirty
  io.meta_write.bits.way_en := metaReq.way_en
  io.meta_write.bits.idx := metaReq.idx
  io.meta_write.bits.tag := meta.tag
  io.meta_write.bits.data.tag := meta.tag
  io.meta_write.bits.data.coh := nextCoh

  // dirty line: send a writeback request with voluntary release
  io.wb_req.valid := metaValid && meta.coh.isValid() && isDirty
  io.wb_req.bits.idx := metaReq.idx
  io.wb_req.bits.tag := meta.tag
  io.wb_req.bits.source := 0.U // TODO: does this work??
  io.wb_req.bits.param := respType
  io.wb_req.bits.way_en := metaReq.way_en
  io.wb_req.bits.voluntary := true.B
}

class MuonNonBlockingDCache(staticIdForMetadataUseOnly: Int)(implicit p: Parameters)
  extends HellaCache(staticIdForMetadataUseOnly) {

  override lazy val module = new MuonNonBlockingDCacheModule(this)
}

class MuonNonBlockingDCacheModule(outer: MuonNonBlockingDCache) extends HellaCacheModule(outer) {
  /* val s1_flush_line = s1_req.cmd === M_FLUSH_ALL && s1_req.size(0)
  val flushed = RegInit(true.B)
  val flushing = RegInit(false.B)
  val flushing_req = Reg(chiselTypeOf(s1_req))
  val flushCounter = RegInit((nSets * (nWays-1)).U(log2Ceil(nSets * nWays).W))
  metaArb.io.in(5).valid := flushing && !flushed
  metaArb.io.in(5).bits.write := false.B
  metaArb.io.in(5).bits.idx := flushCounter(idxBits-1, 0)
  metaArb.io.in(5).bits.addr := Cat(io.cpu.req.bits.addr >> untagBits, metaArb.io.in(5).bits.idx << blockOffBits)
  metaArb.io.in(5).bits.way_en := metaArb.io.in(4).bits.way_en
  metaArb.io.in(5).bits.data := metaArb.io.in(4).bits.data */

  require(isPow2(nWays)) // TODO: relax this
  require(dataScratchpadSize == 0)
  require(!usingVM || untagBits <= pgIdxBits, s"untagBits($untagBits) > pgIdxBits($pgIdxBits)")
  require(!cacheParams.separateUncachedResp)

  // ECC is only supported on the data array
  require(cacheParams.tagCode.isInstanceOf[IdentityCode])
  val dECC = cacheParams.dataCode

  io.cpu := DontCare
  io.errors := DontCare

  val wb = Module(new WritebackUnit)
  val prober = Module(new ProbeUnit)
  val mshrs = Module(new MaskMSHRFile)
  val flush = Module(new CacheFlushUnit)

  io.tlb_port.req.ready := true.B
  io.cpu.req.ready := true.B
  val s1_valid = RegNext(io.cpu.req.fire, false.B)
  val s1_tlb_req_valid = RegNext(io.tlb_port.req.fire, false.B)
  val s1_tlb_req = RegEnable(io.tlb_port.req.bits, io.tlb_port.req.fire)
  val s1_req = Reg(new HellaCacheReq)
  val s1_valid_masked = s1_valid && !io.cpu.s1_kill
  val s1_replay = RegInit(false.B)
  val s1_clk_en = Reg(Bool())
  val s1_sfence = s1_req.cmd === M_SFENCE

  val s2_valid = RegNext(s1_valid_masked && !s1_sfence, false.B) && !io.cpu.s2_xcpt.asUInt.orR
  val s2_tlb_req_valid = RegNext(s1_tlb_req_valid, false.B)
  val s2_req = Reg(new HellaCacheReq)
  val s2_replay = RegNext(s1_replay, false.B) && s2_req.cmd =/= M_FLUSH_ALL
  val s2_recycle = Wire(Bool())
  val s2_valid_masked = Wire(Bool())

  val s3_valid = RegInit(false.B)
  val s3_req = Reg(new HellaCacheReq)
  val s3_way = Reg(Bits())

  val s1_recycled = RegEnable(s2_recycle, false.B, s1_clk_en)
  val s1_read  = isRead(s1_req.cmd)
  val s1_write = isWrite(s1_req.cmd)
  val s1_readwrite = s1_read || s1_write || isPrefetch(s1_req.cmd)
  // check for unsupported operations
  assert(!s1_valid || !s1_req.cmd.isOneOf(M_PWR))

  val dtlb = Module(new TLB(false, log2Ceil(coreDataBytes), TLBConfig(nTLBSets, nTLBWays)))
  io.ptw <> dtlb.io.ptw
  dtlb.io.kill := io.cpu.s2_kill
  dtlb.io.req.valid := s1_valid && !io.cpu.s1_kill && s1_readwrite
  dtlb.io.req.bits.passthrough := s1_req.phys
  dtlb.io.req.bits.vaddr := s1_req.addr
  dtlb.io.req.bits.size := s1_req.size
  dtlb.io.req.bits.cmd := s1_req.cmd
  dtlb.io.req.bits.prv := s1_req.dprv
  dtlb.io.req.bits.v := s1_req.dv
  when (s1_tlb_req_valid) { dtlb.io.req.bits := s1_tlb_req }
  when (!dtlb.io.req.ready && !io.cpu.req.bits.phys) { io.cpu.req.ready := false.B }

  dtlb.io.sfence.valid := s1_valid && !io.cpu.s1_kill && s1_sfence
  dtlb.io.sfence.bits.rs1 := s1_req.size(0)
  dtlb.io.sfence.bits.rs2 := s1_req.size(1)
  dtlb.io.sfence.bits.addr := s1_req.addr
  dtlb.io.sfence.bits.asid := io.cpu.s1_data.data
  dtlb.io.sfence.bits.hv := s1_req.cmd === M_HFENCEV
  dtlb.io.sfence.bits.hg := s1_req.cmd === M_HFENCEG

  when (io.cpu.req.valid) {
    s1_req := io.cpu.req.bits
  }
  when (wb.io.meta_read.valid) {
    s1_req.addr := Cat(wb.io.meta_read.bits.tag, wb.io.meta_read.bits.idx) << blockOffBits
    s1_req.phys := true.B
  }
  when (prober.io.meta_read.valid) {
    s1_req.addr := Cat(prober.io.meta_read.bits.tag, prober.io.meta_read.bits.idx) << blockOffBits
    s1_req.phys := true.B
  }
  when (mshrs.io.replay.valid) {
    s1_req := mshrs.io.replay.bits
  }
  when (s2_recycle) {
    s1_req := s2_req
  }
  val s1_addr = Mux(s1_req.phys, s1_req.addr, dtlb.io.resp.paddr)

  io.tlb_port.s1_resp := dtlb.io.resp

  when (s1_clk_en) {
    s2_req.size := s1_req.size
    s2_req.signed := s1_req.signed
    s2_req.phys := s1_req.phys
    s2_req.addr := s1_addr
    s2_req.no_resp := s1_req.no_resp
    when (s1_write) {
      s2_req.mask := Mux(s1_replay, mshrs.io.replay.bits.mask, io.cpu.s1_data.mask)
      s2_req.data := Mux(s1_replay, mshrs.io.replay.bits.data, io.cpu.s1_data.data)
    }
    when (s1_recycled) {
      s2_req.data := s1_req.data
      s2_req.mask := s1_req.mask
    }
    s2_req.tag := s1_req.tag
    s2_req.cmd := s1_req.cmd
  }

  // tags
  def onReset = L1Metadata(0.U, ClientMetadata.onReset)
  val meta = Module(new L1MetadataArray(() => onReset ))
  val metaReadArb = Module(new Arbiter(new L1MetaReadReq, 6))
  val metaWriteArb = Module(new Arbiter(new L1MetaWriteReq, 3))
  meta.io.read <> metaReadArb.io.out
  meta.io.write <> metaWriteArb.io.out

  // data
  val data = Module(new DataArray)
  val readArb = Module(new Arbiter(new L1DataReadReq, 4))
  val writeArb = Module(new Arbiter(new L1DataWriteReq, 2))
  data.io.write.valid := writeArb.io.out.valid
  writeArb.io.out.ready := data.io.write.ready
  data.io.write.bits := writeArb.io.out.bits
  val wdata_encoded = (0 until rowWords).map(i => dECC.encode(writeArb.io.out.bits.data(coreDataBits*(i+1)-1,coreDataBits*i)))
  data.io.write.bits.data := wdata_encoded.asUInt

  // tag read for new requests
  metaReadArb.io.in(4).valid := io.cpu.req.valid
  metaReadArb.io.in(4).bits.idx := io.cpu.req.bits.addr >> blockOffBits
  metaReadArb.io.in(4).bits.tag := io.cpu.req.bits.addr >> untagBits
  metaReadArb.io.in(4).bits.way_en := ~0.U(nWays.W)
  when (!metaReadArb.io.in(4).ready) { io.cpu.req.ready := false.B }

  // data read for new requests
  readArb.io.in(3).valid := io.cpu.req.valid
  readArb.io.in(3).bits.addr := io.cpu.req.bits.addr
  readArb.io.in(3).bits.way_en := ~0.U(nWays.W)
  when (!readArb.io.in(3).ready) { io.cpu.req.ready := false.B }

  // recycled requests
  metaReadArb.io.in(0).valid := s2_recycle
  metaReadArb.io.in(0).bits.idx := s2_req.addr >> blockOffBits
  metaReadArb.io.in(0).bits.way_en := ~0.U(nWays.W)
  metaReadArb.io.in(0).bits.tag := s2_req.tag
  readArb.io.in(0).valid := s2_recycle
  readArb.io.in(0).bits.addr := s2_req.addr
  readArb.io.in(0).bits.way_en := ~0.U(nWays.W)

  // tag check and way muxing
  def wayMap[T <: Data](f: Int => T) = VecInit((0 until nWays).map(f))
  val s1_tag_eq_way = wayMap((w: Int) => meta.io.resp(w).tag === (s1_addr >> untagBits)).asUInt
  val s1_tag_match_way = wayMap((w: Int) => s1_tag_eq_way(w) && meta.io.resp(w).coh.isValid()).asUInt
  s1_clk_en := metaReadArb.io.out.valid //TODO: should be metaReadArb.io.out.fire, but triggers Verilog backend bug
  val s1_writeback = s1_clk_en && !s1_valid && !s1_replay
  val s2_tag_match_way = RegEnable(s1_tag_match_way, s1_clk_en)
  val s2_tag_match = s2_tag_match_way.orR
  val s2_hit_state = Mux1H(s2_tag_match_way, wayMap((w: Int) => RegEnable(meta.io.resp(w).coh, s1_clk_en)))
  val (s2_has_permission, _, s2_new_hit_state) = s2_hit_state.onAccess(s2_req.cmd)
  val s2_hit = s2_tag_match && s2_has_permission && s2_hit_state === s2_new_hit_state

  // load-reserved/store-conditional
  val lrsc_count = RegInit(0.U)
  val lrsc_valid = lrsc_count > lrscBackoff.U
  val lrsc_addr = Reg(UInt())
  val (s2_lr, s2_sc) = (s2_req.cmd === M_XLR, s2_req.cmd === M_XSC)
  val s2_lrsc_addr_match = lrsc_valid && lrsc_addr === (s2_req.addr >> blockOffBits)
  val s2_sc_fail = s2_sc && !s2_lrsc_addr_match
  when (lrsc_count > 0.U) { lrsc_count := lrsc_count - 1.U }
  when (s2_valid_masked && s2_hit || s2_replay) {
    when (s2_lr) {
      lrsc_count := lrscCycles.U - 1.U
      lrsc_addr := s2_req.addr >> blockOffBits
    }
    when (lrsc_count > 0.U) {
      lrsc_count := 0.U
    }
  }
  when (s2_valid_masked && !(s2_tag_match && s2_has_permission) && s2_lrsc_addr_match) {
    lrsc_count := 0.U
  }

  val s2_data = Wire(Vec(nWays, Bits(encRowBits.W)))
  for (w <- 0 until nWays) {
    val regs = Reg(Vec(rowWords, Bits(encDataBits.W)))
    val en1 = s1_clk_en && s1_tag_eq_way(w)
    for (i <- 0 until regs.size) {
      val en = en1 && (((i == 0).B || !doNarrowRead.B) || s1_writeback)
      when (en) { regs(i) := data.io.resp(w) >> encDataBits*i }
    }
    s2_data(w) := regs.asUInt
  }
  val s2_data_muxed = Mux1H(s2_tag_match_way, s2_data)
  val s2_data_decoded = (0 until rowWords).map(i => dECC.decode(s2_data_muxed(encDataBits*(i+1)-1,encDataBits*i)))
  val s2_data_corrected = s2_data_decoded.map(_.corrected).asUInt
  val s2_data_uncorrected = s2_data_decoded.map(_.uncorrected).asUInt
  val s2_word_idx = if(doNarrowRead) 0.U else s2_req.addr(log2Up(rowWords*coreDataBytes)-1,log2Up(wordBytes))
  val s2_data_correctable = s2_data_decoded.map(_.correctable).asUInt(s2_word_idx)

  // store/amo hits
  s3_valid := (s2_valid_masked && s2_hit || s2_replay) && !s2_sc_fail && isWrite(s2_req.cmd)
  val amoalu = Module(new AMOALU(wordBits))
  when ((s2_valid || s2_replay) && (isWrite(s2_req.cmd) || s2_data_correctable)) {
    s3_req := s2_req
    s3_req.data := Mux(s2_data_correctable, s2_data_corrected, amoalu.io.out)
    s3_way := s2_tag_match_way
  }

  writeArb.io.in(0).bits.addr := s3_req.addr
  writeArb.io.in(0).bits.wmask := UIntToOH(s3_req.addr.extract(rowOffBits-1,offsetlsb))
  writeArb.io.in(0).bits.data := Fill(rowWords, s3_req.data)
  writeArb.io.in(0).valid := s3_valid
  writeArb.io.in(0).bits.way_en :=  s3_way

  // replacement policy
  val replacer = cacheParams.replacement
  val s1_replaced_way_en = UIntToOH(replacer.way)
  val s2_replaced_way_en = UIntToOH(RegEnable(replacer.way, s1_clk_en))
  val s2_repl_meta = Mux1H(s2_replaced_way_en, wayMap((w: Int) => RegEnable(meta.io.resp(w), s1_clk_en && s1_replaced_way_en(w))).toSeq)

  // miss handling
  mshrs.io.req.valid := s2_valid_masked && !s2_hit && (isPrefetch(s2_req.cmd) || isRead(s2_req.cmd) || isWrite(s2_req.cmd))
  mshrs.io.req.bits.viewAsSupertype(new Replay) := s2_req.viewAsSupertype(new HellaCacheReq)
  mshrs.io.req.bits.tag_match := s2_tag_match
  mshrs.io.req.bits.old_meta := Mux(s2_tag_match, L1Metadata(s2_repl_meta.tag, s2_hit_state), s2_repl_meta)
  mshrs.io.req.bits.way_en := Mux(s2_tag_match, s2_tag_match_way, s2_replaced_way_en)
  mshrs.io.req.bits.data := s2_req.data
  when (mshrs.io.req.fire) { replacer.miss }
  tl_out.a <> mshrs.io.mem_acquire

  // replays
  readArb.io.in(1).valid := mshrs.io.replay.valid
  readArb.io.in(1).bits.addr := mshrs.io.replay.bits.addr
  readArb.io.in(1).bits.way_en := ~0.U(nWays.W)
  mshrs.io.replay.ready := readArb.io.in(1).ready
  s1_replay := mshrs.io.replay.valid && readArb.io.in(1).ready
  metaReadArb.io.in(1) <> mshrs.io.meta_read
  metaWriteArb.io.in(0) <> mshrs.io.meta_write

  // probes and releases
  prober.io.req.valid := tl_out.b.valid && !lrsc_valid
  tl_out.b.ready := prober.io.req.ready && !lrsc_valid
  prober.io.req.bits := tl_out.b.bits
  prober.io.way_en := s2_tag_match_way
  prober.io.block_state := s2_hit_state
  metaReadArb.io.in(2) <> prober.io.meta_read
  metaWriteArb.io.in(1) <> prober.io.meta_write
  prober.io.mshr_rdy := mshrs.io.probe_rdy

  // refills
  val grant_has_data = edge.hasData(tl_out.d.bits)
  mshrs.io.mem_grant.valid := tl_out.d.fire
  mshrs.io.mem_grant.bits := tl_out.d.bits
  tl_out.d.ready := writeArb.io.in(1).ready || !grant_has_data
  /* The last clause here is necessary in order to prevent the responses for
   * the IOMSHRs from being written into the data array. It works because the
   * IOMSHR ids start right the ones for the regular MSHRs. */
  writeArb.io.in(1).valid := tl_out.d.valid && grant_has_data &&
                               tl_out.d.bits.source < cfg.nMSHRs.U
  writeArb.io.in(1).bits.addr := mshrs.io.refill.addr
  writeArb.io.in(1).bits.way_en := mshrs.io.refill.way_en
  writeArb.io.in(1).bits.wmask := ~0.U(rowWords.W)
  writeArb.io.in(1).bits.data := tl_out.d.bits.data(encRowBits-1,0)
  data.io.read <> readArb.io.out
  readArb.io.out.ready := !tl_out.d.valid || tl_out.d.ready // insert bubble if refill gets blocked
  tl_out.e <> mshrs.io.mem_finish

  // writebacks
  val wbArb = Module(new Arbiter(new WritebackReq(edge.bundle), 3))
  wbArb.io.in(0) <> prober.io.wb_req
  wbArb.io.in(1) <> mshrs.io.wb_req
  wbArb.io.in(2) <> flush.io.wb_req
  wb.io.req <> wbArb.io.out
  metaReadArb.io.in(3) <> wb.io.meta_read
  readArb.io.in(2) <> wb.io.data_req
  wb.io.data_resp := s2_data_corrected
  TLArbiter.lowest(edge, tl_out.c, wb.io.release, prober.io.rep)

  // store->load bypassing
  val s4_valid = RegNext(s3_valid, false.B)
  val s4_req = RegEnable(s3_req, s3_valid && metaReadArb.io.out.valid)
  val bypasses = List(
    ((s2_valid_masked || s2_replay) && !s2_sc_fail, s2_req, amoalu.io.out),
    (s3_valid, s3_req, s3_req.data),
    (s4_valid, s4_req, s4_req.data)
  ).map(r => (r._1 && (s1_addr >> wordOffBits === r._2.addr >> wordOffBits) && isWrite(r._2.cmd), r._3))
  val s2_store_bypass_data = Reg(Bits(coreDataBits.W))
  val s2_store_bypass = Reg(Bool())
  when (s1_clk_en) {
    s2_store_bypass := false.B
    when (bypasses.map(_._1).reduce(_||_)) {
      s2_store_bypass_data := PriorityMux(bypasses)
      s2_store_bypass := true.B
    }
  }

  // load data subword mux/sign extension
  val s2_data_word_prebypass = s2_data_uncorrected >> Cat(s2_word_idx, 0.U(log2Up(coreDataBits).W))
  val s2_data_word = Mux(s2_store_bypass, s2_store_bypass_data, s2_data_word_prebypass)
  val loadgen = new LoadGen(s2_req.size, s2_req.signed, s2_req.addr, s2_data_word, s2_sc, wordBytes)

  amoalu.io.mask := s2_req.mask // no StoreGen
  amoalu.io.cmd := s2_req.cmd
  amoalu.io.lhs := s2_data_word
  amoalu.io.rhs := s2_req.data

  // nack it like it's hot
  val s1_nack = dtlb.io.req.valid && dtlb.io.resp.miss || io.cpu.s2_nack || s1_tlb_req_valid ||
                s1_req.addr(idxMSB,idxLSB) === prober.io.meta_write.bits.idx && !prober.io.req.ready
  val s2_nack_hit = RegEnable(s1_nack, s1_valid || s1_replay)
  when (s2_nack_hit) { mshrs.io.req.valid := false.B }
  val s2_nack_victim = s2_hit && mshrs.io.secondary_miss
  val s2_nack_miss = !s2_hit && !mshrs.io.req.ready
  val s2_nack = s2_nack_hit || s2_nack_victim || s2_nack_miss
  s2_valid_masked := s2_valid && !s2_nack && !io.cpu.s2_kill

  val s2_recycle_ecc = (s2_valid || s2_replay) && s2_hit && s2_data_correctable
  val s2_recycle_next = RegInit(false.B)
  when (s1_valid || s1_replay) { s2_recycle_next := s2_recycle_ecc }
  s2_recycle := s2_recycle_ecc || s2_recycle_next

  // after a nack, block until nack condition resolves to save energy
  val block_miss = RegInit(false.B)
  block_miss := (s2_valid || block_miss) && s2_nack_miss
  when (block_miss || s1_nack) {
    io.cpu.req.ready := false.B
  }

  val cache_resp = Wire(Valid(new HellaCacheResp))
  cache_resp.valid := (s2_replay || s2_valid_masked && s2_hit) && !s2_data_correctable
  cache_resp.bits.addr := s2_req.addr
  cache_resp.bits.idx.foreach(_ := s2_req.idx.get)
  cache_resp.bits.tag := s2_req.tag
  cache_resp.bits.cmd := s2_req.cmd
  cache_resp.bits.size := s2_req.size
  cache_resp.bits.signed := s2_req.signed
  cache_resp.bits.dprv := s2_req.dprv
  cache_resp.bits.dv := s2_req.dv
  cache_resp.bits.data_word_bypass := loadgen.wordData
  cache_resp.bits.data_raw := s2_data_word
  cache_resp.bits.mask := s2_req.mask
  cache_resp.bits.has_data := isRead(s2_req.cmd)
  cache_resp.bits.data := loadgen.data | s2_sc_fail
  cache_resp.bits.store_data := s2_req.data
  cache_resp.bits.replay := s2_replay

  val uncache_resp = Wire(Valid(new HellaCacheResp))
  uncache_resp.bits := mshrs.io.resp.bits
  uncache_resp.valid := mshrs.io.resp.valid
  mshrs.io.resp.ready := RegNext(!(s1_valid || s1_replay))

  io.cpu.s2_nack := s2_valid && s2_nack
  io.cpu.resp := Mux(mshrs.io.resp.ready, uncache_resp, cache_resp)
  io.cpu.resp.bits.data_word_bypass := loadgen.wordData
  io.cpu.resp.bits.data_raw := s2_data_word
  io.cpu.ordered := mshrs.io.fence_rdy && !s1_valid && !s2_valid
  io.cpu.store_pending := mshrs.io.store_pending
  io.cpu.replay_next := (s1_replay && s1_read) || mshrs.io.replay_next

  val s1_xcpt_valid = dtlb.io.req.valid && !s1_nack
  val s1_xcpt = dtlb.io.resp
  io.cpu.s2_xcpt := Mux(RegNext(s1_xcpt_valid), RegEnable(s1_xcpt, s1_clk_en), 0.U.asTypeOf(s1_xcpt))
  io.cpu.s2_uncached := false.B
  io.cpu.s2_paddr := s2_req.addr

  // cache flush
  // TODO: preflush
  flush.io.flush := false.B // TODO
  flush.io.meta_resp := meta.io.resp
  metaReadArb.io.in(5) <> flush.io.meta_read
  metaWriteArb.io.in(2) <> flush.io.meta_write

  // performance events
  io.cpu.perf.acquire := edge.done(tl_out.a)
  io.cpu.perf.release := edge.done(tl_out.c)
  io.cpu.perf.tlbMiss := io.ptw.req.fire

  // no clock-gating support
  io.cpu.clock_enabled := true.B

  /* when ((s2_valid || s2_replay) && (rocket.isWrite(s2_req.cmd) || s2_data_correctable)) {
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
  } */

  /* wideAmoalu.io.mask := s2_req.mask // no StoreGen
  wideAmoalu.io.cmd := s2_req.cmd
  wideAmoalu.io.lhs := s2_data_word
  wideAmoalu.io.rhs := s2_req.data */

  // mshr overrides
  /* when (maskMshrs.io.replay.valid) {
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
  io.cpu.replay_next := (s1_replay && s1_read) || maskMshrs.io.replay_next */
}