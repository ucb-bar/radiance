package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{DCacheParams, HellaCacheResp, PRV, SimpleHellaCacheIF}
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.cluster.CacheFlushNode
import radiance.subsystem.{DummyTileParams, GPUMemory, PhysicalCoreParams}

case class TLNBDCacheParams(
  id: Int,
  cache: DCacheParams,
  overrideDChannelSize: Option[Int] = None,
  flushAddr: Option[BigInt] = None,
  makeLandingPads: Boolean = false,
  // Requests in flight the input adapter may hold.  rocket's SimpleHellaCacheIF hardcodes 3; the
  // default here keeps that, and a port whose round trip exceeds it should raise it.  See
  // DepthHellaCacheIF.scala for why the depth is the outstanding-request limit.  Set from
  // MemParallelismKey where the caches are built.
  inFlightReqs: Int = TLNBDCacheParams.defaultInFlightReqs,
  // A pending nack blocks only new requests to the nacked cache set, instead of every new request.
  // See DepthHellaCacheIFReplayQueue for the ordering argument.
  nackBlocksSetOnly: Boolean = false,
  // Evicting a valid clean line sends a Release (no data) to the outer cache instead of dropping it
  // silently, so an inclusive outer cache does not later probe it. For a coherent (TL-C) client; see
  // MuonNonBlockingDCacheModule "clean releases".
  cleanRelease: Boolean = false,
  // The cache is a TL-UL client behind TLCToTLULNode (TLULNBDCache): no probe reaches it, so a miss does not
  // clear its victim's metadata first. See MuonNonBlockingDCacheModule "incoherent".
  incoherent: Boolean = false,
  // Line buffers that answer replayed reads from the refill (0: off). See "refill forwarding".
  refillForwardSlots: Int = 0,
  // Tagged sequential prefetch distance in cache lines (0: off). See TLNBDCacheModule "prefetch".
  prefetchDistance: Int = 0,
  // Prefetch requests waiting to enter the cache.
  prefetchQueueDepth: Int = 4,
  // Metadata array with separate read and write ports (TwoPortL1MetadataArray).
  twoPortMeta: Boolean = false,
  // One of several banks interleaved by line (LineInterleave): the cache sees its own lines only, in a dense
  // address space of GPU memory size / banks.
  interleave: Option[LineInterleave] = None,
  // Nack a hit only on a way a live MSHR is filling, not on any way of its set (incoherent caches only).
  preciseVictimNack: Boolean = false,
  // Several misses per set (MuonMSHR.scala); needs incoherent and preciseVictimNack.
  multiMissPerSet: Boolean = false,
  // A core starts this cache's flush through a CacheFlushNode. Without it the flush register at flushAddr
  // is the only way to start a flush.
  coreFlush: Boolean = true,
) {
  // the response landing pad must be able to absorb every outstanding request
  def landingPadDepth: Int = (cache.nMSHRs max inFlightReqs) + 1
}

object TLNBDCacheParams {
  val defaultInFlightReqs = 3
}

/** `reqTagBits` is read only while the module elaborates, after diplomacy has sized the cache's
  * input edge; the tag width is that edge's width, never an independent parameter. */
case class DummyCacheCoreParams(
  cacheLineBytes: Int = 32,
  reqTagBits: () => Int,
) extends PhysicalCoreParams {
  override val useVector: Boolean = true // for cache line size
  override val vLen: Int = 32
  override val eLen: Int = 32
  override def vMemDataBits: Int = cacheLineBytes * 8
  override def dcacheReqTagBits: Int = reqTagBits()
}

case class DummyCacheTileParams(
  params: TLNBDCacheParams,
  reqTagBits: () => Int,
) extends DummyTileParams {
  val core = DummyCacheCoreParams(
    cacheLineBytes = params.cache.blockBytes,
    reqTagBits = reqTagBits,
  )
  override val tileId = params.id
  override val dcache: Option[DCacheParams] = Some(params.cache)
}

class TLNBDCache(val params: TLNBDCacheParams)
                (implicit p: Parameters) extends LazyModule {

  val beatBytes = params.cache.blockBytes
  require(params.cache.blockBytes == (params.cache.rowBits / 8))
  /** size of the address space the cache sees (a bank's dense share of GPU memory) */
  val nBanks: Int = params.interleave.fold(1)(_.nBanks)
  val addressSize: BigInt = p(GPUMemory).get.size / nBanks

  val inNode = TLManagerNode(Seq(
    TLSlavePortParameters.v1(
      managers = Seq(
        TLSlaveParameters.v2(
          address = Seq(AddressSet(0, addressSize - 1)),
          name = Some("radiance_l1"),
          fifoId = Some(1),
          supports = TLMasterToSlaveTransferSizes(
            get = TransferSizes(1, beatBytes),
            putFull = TransferSizes(1, beatBytes),
            putPartial = TransferSizes(1, beatBytes),
            // b and c are ignored, but this is passed down to clients
            acquireB = TransferSizes(1, beatBytes),
            acquireT = TransferSizes(1, beatBytes),
          ),
          regionType = RegionType.CACHED
        )
      ),
      beatBytes = beatBytes,
      endSinkId = 4, // hardcoded 4 sink ids for $ to ack E
    )
  ))

  /** The request tag carries the TileLink source, plus the request size when the D-channel size is
    * not fixed. Taken from the resolved input edge, so it follows whatever the clients declare. */
  lazy val reqTagBits: Int = {
    val bundle = inNode.edges.in.head.bundle
    bundle.sourceBits + (if (params.overrideDChannelSize.isDefined) 0 else bundle.sizeBits)
  }

  val flushRegNode = params.flushAddr.map { addr =>
    TLRegisterNode(
      address = Seq(AddressSet(addr, 0xff)),
      device = new SimpleDevice(f"muon-cache-flush", Seq(s"muon-cache-flush")),
      beatBytes = 8,
      concurrency = 1,
    )
  }

  val flushNode = Option.when(params.flushAddr.isDefined && params.coreFlush)(CacheFlushNode.Slave())

  implicit val q: Parameters = p.alterMap(Map(
    TileKey -> DummyCacheTileParams(params, () => reqTagBits),
    CacheBlockBytes -> params.cache.blockBytes,
    // TileVisibilityNodeKey -> visibilityNode,
  ))

  val nbdCache = LazyModule(new MuonNonBlockingDCache(params.id, params.flushAddr.isDefined, params.cleanRelease,
    params.incoherent, params.refillForwardSlots, params.prefetchDistance > 0, params.twoPortMeta,
    params.preciseVictimNack && params.incoherent, params.multiMissPerSet)(q))

  val outNode = nbdCache.node
  override lazy val module = new TLNBDCacheModule(this)(q)
}

class TLNBDCacheModule(outer: TLNBDCache)(implicit p: Parameters) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  val (tlIn, _) = outer.inNode.in.head
  val inIF = Module(new DepthHellaCacheIF(outer.params.inFlightReqs,
    Option.when(outer.params.nackBlocksSetOnly)(
      (log2Ceil(outer.params.cache.blockBytes), log2Ceil(outer.params.cache.nSets)))))

//  assert(!tlIn.a.valid || (tlIn.a.bits.size === log2Ceil(outer.beatBytes).U),
//    "only cache line size accesses supported")

  // tl <-> simple if
  // ================
  {
    val req = inIF.io.requestor.req
    val resp = inIF.io.requestor.resp

    inIF.io.requestor <> 0.U.asTypeOf(inIF.io.requestor)
    // following example of Saturn's HellaInterface
    inIF.io.requestor.s1_kill := false.B
    inIF.io.requestor.s1_data := DontCare
    inIF.io.requestor.s2_kill := false.B
    inIF.io.requestor.keep_clock_enabled := true.B

    val dChSize = outer.params.overrideDChannelSize
    val sizeTagWidth = dChSize match {
      case Some(_) => 0
      case None => tlIn.params.sizeBits
    }

    val respBufReady = WireInit(true.B)

    // A
    req.valid := tlIn.a.valid && respBufReady
    tlIn.a.ready := req.ready && respBufReady
    req.bits := 0.U.asTypeOf(req.bits.cloneType)
    assert(req.bits.tag.getWidth == tlIn.a.bits.source.getWidth + sizeTagWidth,
      s"cache tag bits doesnt match source:" +
      s"${req.bits.tag.getWidth} != ${tlIn.a.bits.source.getWidth} + $sizeTagWidth")

    // every read is cache line sized. this is because the output always starts at lsb
    // regardless of address. i.e. this logic does not use active byte lanes that
    // tilelink uses, so we have to either read the whole line, or re-shift the result.
    req.bits.size := log2Ceil(outer.beatBytes).U // tlIn.a.bits.size
    req.bits.cmd := MuxCase(M_XRD, Seq(
      (tlIn.a.bits.opcode === TLMessages.Get) -> M_XRD,
      (tlIn.a.bits.opcode === TLMessages.PutFullData) -> M_XWR,
      (tlIn.a.bits.opcode === TLMessages.PutPartialData) -> M_XWR,
    )) // TODO: ability to flush
    req.bits.addr := tlIn.a.bits.address & (-outer.beatBytes).S(tlIn.params.addressBits.W).asUInt
    req.bits.data := tlIn.a.bits.data
    req.bits.mask := tlIn.a.bits.mask
    req.bits.signed := false.B
    req.bits.dprv := PRV.M.U
    req.bits.dv := false.B // virtualization
    req.bits.phys := true.B
    req.bits.no_resp := false.B
    req.bits.no_alloc := false.B // <- might be able to imp writethrough
    req.bits.no_xcpt := true.B // no vm/dp, so no page faults etc

    // on the other hand, stores need to have the real size / address so that NBDCache can 
    // generate the correct byte mask when storing to its internal data array
    // (i have no idea what req.bits.mask is actually even being used for? agent claims
    // it is for AMOALU operations or something)
    when (tlIn.a.bits.opcode === TLMessages.PutFullData || tlIn.a.bits.opcode === TLMessages.PutPartialData) {
      req.bits.addr := tlIn.a.bits.address
      // @richard: do we need to actually do the shift here?
      req.bits.data := tlIn.a.bits.data 
      req.bits.size := tlIn.a.bits.size
    }

//    when (req.fire) {
//      when (req.bits.cmd === M_XRD) {
//        printf(" load-req 0x%x", req.bits.addr)
//      }
//      when (req.bits.cmd === M_XWR) {
//        printf(" store-req %d 0x%x", req.bits.data, req.bits.addr)
//      }
//      printf(" #%d", req.bits.tag)
//    }

    // D
    val (deqBits: HellaCacheResp, deqValid) = if (outer.params.makeLandingPads) {
      // One slot per request the input adapter can hold in flight, so a stalled D channel never
      // closes the A gate below while requests are still admissible.
      val mshrs = outer.params.landingPadDepth
      val respBuf = Module(new Queue(resp.bits.cloneType, mshrs))
      respBuf.io.enq.valid := resp.valid
      respBuf.io.enq.bits := resp.bits
      assert(!resp.valid || respBuf.io.enq.ready, "response must be ready!")
      respBuf.io.deq.ready := tlIn.d.ready

      val inFlights = RegInit(0.U(log2Ceil(mshrs + 1).W))
      when (tlIn.a.fire && !tlIn.d.fire) {
        inFlights := inFlights + 1.U
      }.elsewhen (!tlIn.a.fire && tlIn.d.fire) {
        inFlights := inFlights - 1.U
      }
      respBufReady := inFlights < mshrs.U

      // assert(respBufReady || !tlIn.a.valid, "this assertion can be safely commented but lmk")

      (respBuf.io.deq.bits, respBuf.io.deq.valid)
    } else {
      assert(!resp.valid || tlIn.d.ready, "response must be ready!")
      (resp.bits, resp.valid)
    }
    // assert(!resp.valid || !resp.bits.replay, "cannot replay requests")
    tlIn.d.valid := deqValid
    tlIn.d.bits.data := deqBits.data
    tlIn.d.bits.opcode := MuxCase(TLMessages.AccessAckData, Seq(
      (deqBits.cmd === M_XRD) -> TLMessages.AccessAckData,
      (deqBits.cmd === M_XWR) -> TLMessages.AccessAck,
    ))
    assert(!tlIn.d.fire || deqBits.cmd === M_XRD || deqBits.cmd === M_XWR)
    assert(!tlIn.d.fire || (deqBits.has_data === (deqBits.cmd === M_XRD)))
    // tlIn.d.bits.user := ???

    outer.params.overrideDChannelSize match {
      case Some(size) =>
        req.bits.tag := tlIn.a.bits.source
        tlIn.d.bits.size := size.U
        tlIn.d.bits.source := deqBits.tag
      case None =>
        req.bits.tag := Cat(tlIn.a.bits.size, tlIn.a.bits.source)
        tlIn.d.bits.size := deqBits.tag >> tlIn.params.sourceBits
        tlIn.d.bits.source := deqBits.tag(tlIn.params.sourceBits - 1, 0)
    }

  }

  // simple if <-> cache
  // ================
  val cacheIO = outer.nbdCache.module.io

  val releaseMask = IO(Output(UInt(outer.beatBytes.W)))
  releaseMask := outer.nbdCache.module.release_mask

  cacheIO.cpu <> inIF.io.cache
  
  inIF.io.cache.resp := cacheIO.cpu.resp
  inIF.io.cache.perf := cacheIO.cpu.perf

  // prefetch (TLNBDCacheParams.prefetchDistance)
  //
  // The cache reports demand reads that miss, and demand reads that hit a prefetched line for the first time
  // (MuonNonBlockingDCacheModule "prefetch triggers"); each asks for the line prefetchDistance lines ahead.
  // A target equal to one of the last 8 is dropped. Prefetches (M_PFR: allocate an MSHR on a miss, no replay,
  // no response) enter the cache ahead of the input adapter's requests; the adapter does not see their nacks
  // or their hit responses. A nacked prefetch is dropped.
  // FF cost: 8 x block address + the queue.
  outer.nbdCache.module.pfTrigger.foreach { trig =>
    val blockOff = log2Ceil(outer.beatBytes)
    val nBlocks = outer.addressSize >> blockOff
    require(isPow2(nBlocks))
    val blockW = log2Ceil(nBlocks)
    val target = ((trig.bits >> blockOff) + outer.params.prefetchDistance.U)(blockW - 1, 0)
    val recent = Reg(Vec(8, UInt(blockW.W)))
    val recentValid = RegInit(0.U(8.W))
    val recentPtr = RegInit(0.U(3.W))
    val dup = (recent zip recentValid.asBools).map { case (r, v) => v && r === target }.reduce(_ || _)
    val pfq = Module(new Queue(UInt(blockW.W), outer.params.prefetchQueueDepth))
    pfq.io.enq.valid := trig.valid && !dup
    pfq.io.enq.bits := target
    when (trig.valid && !dup) {
      recent(recentPtr) := target
      recentValid := recentValid | UIntToOH(recentPtr)
      recentPtr := recentPtr + 1.U
    }

    val pf = Wire(new freechips.rocketchip.rocket.HellaCacheReq)
    pf := 0.U.asTypeOf(pf)
    pf.cmd := M_PFR
    pf.addr := pfq.io.deq.bits << blockOff
    pf.size := blockOff.U
    pf.dprv := PRV.M.U
    pf.phys := true.B
    pf.no_xcpt := true.B
    cacheIO.cpu.req.valid := pfq.io.deq.valid || inIF.io.cache.req.valid
    cacheIO.cpu.req.bits := Mux(pfq.io.deq.valid, pf, inIF.io.cache.req.bits)
    pfq.io.deq.ready := cacheIO.cpu.req.ready
    inIF.io.cache.req.ready := cacheIO.cpu.req.ready && !pfq.io.deq.valid

    val s2Prefetch = RegNext(RegNext(pfq.io.deq.fire, false.B), false.B)
    inIF.io.cache.s2_nack := cacheIO.cpu.s2_nack && !s2Prefetch
    inIF.io.cache.resp.valid := cacheIO.cpu.resp.valid && cacheIO.cpu.resp.bits.cmd =/= M_PFR
  }
//  inIF.io.cache.s2_nack := cacheIO.cpu.s2_nack
//  cacheIO.cpu.perf.
//  DontCare <> cacheIO.cpu.perf
  cacheIO.ptw <> DontCare
  cacheIO.ptw.req.ready := false.B
  cacheIO.ptw.resp.bits := DontCare
  cacheIO.ptw.resp.valid := false.B

  cacheIO.tlb_port.req <> DontCare
  cacheIO.tlb_port.req.valid := false.B
  cacheIO.tlb_port.s2_kill := false.B

  val fio = outer.nbdCache.module.flush_io
  fio.foreach(_.start := false.B)

  // FIX 4: a flush request (MMIO write, core fence, or core finish) is latched here and started only
  // once the tile reports that no memory request is in flight above this cache and the cache is not
  // already flushing.  The old code started the sweep immediately: stores still travelling through
  // the LSU / coalescer landed after the sweep and stayed dirty until the next flush -- which for the
  // finish-triggered flush never comes.  The cache's own `ready_to_flush` only covers its MSHRs.
  // FF cost: 1 (flushPending).
  fio.foreach { f =>
    val flushPending = RegInit(false.B)
    val flushReq = WireInit(false.B)
    val quiescent = outer.flushNode.map(_.in.head._1.quiescent).getOrElse(true.B)
    when (flushReq) { flushPending := true.B }
    when (flushPending && quiescent && !f.busy) {
      f.start := true.B
      flushPending := false.B
    }

    // flush mmio
    outer.flushRegNode.foreach { node =>
      node.regmap(
        0x0 -> Seq(RegField.w(32, (valid: Bool, _: UInt) => {
          when (valid) {
            flushReq := true.B
          }
          true.B
        })),
      )
    }

    outer.flushNode.map(_.in.head._1).foreach { node =>
      when (node.start) {
        flushReq := true.B
      }
      // done on the falling edge of busy, and only when no further flush is waiting behind it, so a
      // fence that queued behind a running flush is released by its own sweep and not the earlier one
      node.done := RegNext(f.busy) && !f.busy && !flushPending
    }
  }

}


/** Whether the cluster L1 sends Releases for clean victims (TLNBDCacheParams.cleanRelease). */
case object L1CleanReleaseKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** The cluster L1 is a TL-UL client (TLULNBDCache): no coherence with the GPU L2, no probes, dirty lines
  * written back on eviction or by its flush register (cluster peripheral offset 0x2000). */
case object L1IncoherentKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** Refill forwarding line buffers in the cluster L1 (TLNBDCacheParams.refillForwardSlots). */
case object L1RefillForwardKey extends org.chipsalliance.cde.config.Field[Int](0)

/** Tagged sequential prefetch distance for the cluster L1, in lines of the address space (each of L1BanksKey
  * banks gets distance / banks of its own lines; TLNBDCacheParams.prefetchDistance). */
case object L1PrefetchDistanceKey extends org.chipsalliance.cde.config.Field[Int](0)

/** Cluster L1 metadata array with separate read and write ports (TLNBDCacheParams.twoPortMeta). */
case object L1TwoPortMetaKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** Cluster L1 banks interleaved by line (1: one cache). More than one requires L1IncoherentKey. */
case object L1BanksKey extends org.chipsalliance.cde.config.Field[Int](1)

/** A nack in the cluster L1's input adapter blocks only new requests to the nacked set
  * (TLNBDCacheParams.nackBlocksSetOnly), as in the L0d. */
case object L1NackBlocksSetOnlyKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** The L0d takes the cluster L1's miss-path changes: no victim metadata clear and refill forwarding with
  * L1RefillForwardKey line buffers (TLNBDCacheParams.incoherent / refillForwardSlots). */
case object L0dFastMissKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** The cluster L1 nacks a hit only on a way a live MSHR is filling (TLNBDCacheParams.preciseVictimNack). */
case object L1PreciseVictimNackKey extends org.chipsalliance.cde.config.Field[Boolean](false)

/** The cluster L1 holds several misses per set (TLNBDCacheParams.multiMissPerSet). */
case object L1MultiMissKey extends org.chipsalliance.cde.config.Field[Boolean](false)
