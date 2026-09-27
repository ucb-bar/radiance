package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{BufferParams, IdRange, TransferSizes}
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.resources._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import radiance.cluster.{CacheFlushNode, SoftResetFinishNode}
import radiance.memory._
import radiance.subsystem._
import radiance.unittest.{CyclotronTile, CyclotronDiffTest, Tracer, Profiler}

case class MuonTileParams(
  core: MuonCoreParams = MuonCoreParams(),
  tileId: Int = 0,
  coreId: Int = 0,
  clusterId: Int = 0,
  icache: Option[ICacheParams] = None,
  icacheUsingD: Option[DCacheParams] = None,
  dcache: Option[DCacheParams] = None,
  peripheralAddr: BigInt = 0,
  cyclotronCore: Boolean = false,
  cyclotronMem: Boolean = false,
  disabled: Boolean = false,
  btb: Option[BTBParams] = None,
  beuAddr: Option[BigInt] = None,
  blockerCtrlAddr: Option[BigInt] = None,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  boundaryBuffers: Option[RocketTileBoundaryBufferParams] = None,
  l1CacheLineBytes: Int = 32,
) extends InstantiableTileParams[MuonTile] {
  // No l1ReqTagBits here.  The L1's request tag is derived from the resolved TileLink edge in
  // TLNBDCache.reqTagBits, so it follows whatever the L0 clients actually declare -- including
  // the doubled source space Fix 12 gives voluntary releases, which a hand-written formula over
  // nMSHRs + nMMIOs has no term for.


  def instantiate(
    crossing: HierarchicalElementCrossingParamsLike,
    lookup: LookupByHartIdImpl
  )(implicit p: Parameters): MuonTile = {
    new MuonTile(this, crossing, lookup)
  }
  val baseName = "muon_tile"
  val uniqueName = s"${baseName}_${clusterId}_$coreId"
}

object MuonMemTL {
  def toTLA[T <: Bundle](m: MemRequest[T], valid: Bool, edge: TLEdgeOut): TLBundleA = {
    val tla = Mux(m.store,
      edge.Put(m.tag, m.address, m.size, m.data, m.mask)._2,
      edge.Get(m.tag, m.address, m.size)._2
    )
    tla
  }

  def fromTLD[T <: Bundle](tld: TLBundleD, mT: MemResponse[T]): MemResponse[T] = {
    val muonResp = Wire(mT.cloneType)
    muonResp.tag := tld.source
    muonResp.data := tld.data // TODO: sub-bus-width responses
    muonResp
  }

  def connectTL[T <: Bundle](mreq: DecoupledIO[MemRequest[T]],
                             mresp: DecoupledIO[MemResponse[T]],
                             tlBundle: TLBundle,
                             tlEdge: TLEdgeOut,
                             normalizeStores: Boolean = false): Unit = {
    tlBundle.a.bits := MuonMemTL.toTLA(mreq.bits, mreq.valid, tlEdge)
    if (normalizeStores) {
      when (mreq.bits.store) {
        tlBundle.a.bits.address := mreq.bits.address & (-4.S).asTypeOf(tlBundle.a.bits.address)
        tlBundle.a.bits.size := 2.U
      }
    }
    tlBundle.a.valid := mreq.valid
    mreq.ready := tlBundle.a.ready

    mresp.valid := tlBundle.d.valid
    mresp.bits := MuonMemTL.fromTLD(tlBundle.d.bits, mresp.bits)
    tlBundle.d.ready := mresp.ready
  }

  def connectTL[T <: Bundle](mreq: DecoupledIO[MemRequest[T]],
                             mresp: DecoupledIO[MemResponse[T]],
                             tl: TLClientNode): Unit = {
    val (in, ie) = tl.out.head
    connectTL(mreq, mresp, in, ie)
  }

  def multiConnectTL[T <: Bundle](mreq: Vec[DecoupledIO[MemRequest[T]]],
                                  mresp: Vec[DecoupledIO[MemResponse[T]]],
                                  tlClients: Seq[TLClientNode],
                                  normalizeStores: Boolean = false) = {
    require(mreq.length == tlClients.length,
      f"length mismatch (core = ${mreq.length}, tilelink = ${tlClients.length})")
    require(mresp.length == tlClients.length,
      f"length mismatch (core = ${mresp.length}, tilelink = ${tlClients.length})")
    for ((req, resp, (tlBundle, tlEdge)) <- mreq lazyZip mresp lazyZip tlClients.flatMap(_.out)) {
      connectTL(req, resp, tlBundle, tlEdge)
    }
  }
}

class MuonTile(
  val muonParams: MuonTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters
) extends BaseTile(muonParams, crossing, lookup, q)
  with SinksExternalInterrupts
  with SourcesExternalNotifications
  with MuonTileLike {

  // Private constructor ensures altered LazyModule.p is used implicitly
  def this(
      params: MuonTileParams,
      crossing: HierarchicalElementCrossingParamsLike,
      lookup: LookupByHartIdImpl
  )(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = None
  val slaveNode = TLIdentityNode()
  val masterNode = TLIdentityNode()

  val lsuDerived = new LoadStoreUnitDerivedParams(q, muonParams.core)
  val lsuSourceIdBits = lsuDerived.sourceIdBits

  private def cacheMissSourceIds(cache: DCacheParams): Int =
    (1 max cache.nMSHRs) + cache.nMMIOs

  // ===========
  // smem
  // ===========

  // see comment below about innerLsuNodes / lsuNodes
  val innerSmemNodes = Seq.tabulate(muonParams.core.lsu.numLsuLanes) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << lsuSourceIdBits),
              name = s"muon_${muonParams.coreId}_smem_$i",
              requestFifo = true,
              supportsProbe =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsGet = TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutFull =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes),
              supportsPutPartial =
                TransferSizes(1, lazyCoreParamsView.coreDataBytes)
            )
          )
        )
      )
    )
  }

  val smemNodes = innerSmemNodes.map(node => {
    DisableMonitors { implicit p =>
      TLBuffer() := node
    }
  })


  // ===========
  // l0i
  // ===========

  val iFlushMaster = CacheFlushNode.Master()

  def connectBuf(node: TLNode, n: Int): TLNode = {
    val cacheBuf = TLBuffer(ace = BufferParams(n), bd = BufferParams(0))
    cacheBuf := node
  }

  val icacheWordNode = muonParams.icache match {
    case _ => TLClientNode(Seq(TLMasterPortParameters.v2(
      masters = Seq(TLMasterParameters.v2(
        name = s"muon${muonParams.coreId}_i_word",
        requestFifo = true,
        emits = TLMasterToSlaveTransferSizes(
          get = TransferSizes(1, muonParams.core.instBytes)
        ),
        sourceId = IdRange(0, 1 << muonParams.core.l0iReqTagBits)
      )),
      channelBytes = TLChannelBeatBytes(muonParams.core.instBytes),
    )))
  }

  val (l0iOut, l0iIn, l0iFlushRegNode): (TLNode, TLNode, Option[TLRegisterNode]) =
    if (muonParams.cyclotronMem) {
      val l0i = LazyModule(new CyclotronTLInstMem(CyclotronTLInstMemParams(
        name = s"muon_${muonParams.clusterId}_${muonParams.coreId}_cyclotron_l0i",
        flushAddr = Some(muonParams.peripheralAddr),
      )))
      l0i.flushNode.get := iFlushMaster

      val quietSourceBits = muonParams.icacheUsingD
        .map(cache => log2Ceil(cacheMissSourceIds(cache)))
        .getOrElse(0)
      val quietOut = idleMaster(
        sourceBits = quietSourceBits,
        name = s"muon_${muonParams.clusterId}_${muonParams.coreId}_quiet_l0i"
      )
      (quietOut, l0i.inNode, l0i.flushRegNode)
    } else {
      muonParams.icacheUsingD.map { l0iParams =>
        val l0i = LazyModule(new TLULNBDCache(TLNBDCacheParams(
          id = tileId,
          cache = l0iParams,
          overrideDChannelSize = Some(3),
          flushAddr = Some(muonParams.peripheralAddr),
          inFlightReqs = p(MemParallelismKey).l0iInFlight,
        )))
        l0i.flushNode.get := iFlushMaster
        (connectBuf(l0i.outNode, 4), l0i.inNode, l0i.flushRegNode)
      }.getOrElse {
        CacheFlushNode.Slave() := iFlushMaster // TODO: might have to tie off
        val passthru = TLEphemeralNode()
        (passthru, passthru, None)
      }
    }
  val icacheNode = TLIdentityNode()
  icacheNode := l0iOut
  l0iIn :=
    TLWidthWidget(muonParams.core.instBytes) :=
    ResponseFIFOFixer() :=
    icacheWordNode


  // ===========
  // l0d
  // ===========

  val dFlushMaster = CacheFlushNode.Master()
  
  // LSU expects all-lanes-at-once requests, so request valid is dependent on
  // whether all lanes are ready.  This interacts poorly with downstream request
  // arbitration (e.g. XBar), so we need a TLbuffer to decouple
  val innerLsuNodes = Seq.tabulate(muonParams.core.numLanes) { lid =>
    TLClientNode(Seq(TLMasterPortParameters.v2(
      Seq(TLMasterParameters.v1(
        name = s"muon_tile${muonParams.coreId}_lsu_$lid",
        sourceId = IdRange(0, 1 << lsuSourceIdBits)
      )),
    )))
  }

  val lsuNodes = innerLsuNodes.map(node => {
    (TLBuffer()
      := TLSourceShrinker(1 << muonParams.core.logGMEMInFlights)
      := node)
  })

  
  val warpBytes = muonParams.core.numLanes * muonParams.core.archLen / 8
  val coalescedReqWidth = muonParams.dcache.map(_.blockBytes).getOrElse(warpBytes)

  // visibility node that cluster-level l1 sees coming out of tile-local l0d.
  // icacheNode is also exposed with dcacheNode
  val dcacheNode = visibilityNode

  val l0dFlushRegNode: Option[TLRegisterNode] = if (muonParams.cyclotronMem) {
    // When using cyclotron mem, we replace both coalescer+L0D with the cyclotron
    // mem.  This is because the Cyclotron memory has per-lane DataMemIO
    // interface, and supports full throughput regardless of coalesce-ability

    val l0d = LazyModule(new CyclotronTLDataMem(CyclotronTLDataMemParams(
      name = s"muon_${muonParams.clusterId}_${muonParams.coreId}_cyclotron_l0d",
      flushAddr = Some(muonParams.peripheralAddr + 0x100),
    )))
    l0d.flushNode.get := dFlushMaster
    require(l0d.inNodes.length == lsuNodes.length,
      s"CyclotronTLDataMem lanes (${l0d.inNodes.length}) must match LSU nodes (${lsuNodes.length})")
    (l0d.inNodes zip lsuNodes).foreach { case (memNode, lsuNode) =>
      memNode := lsuNode
    }

    // re-play l0->l1 source bit transforms done elsewhere in the fabric so
    // that we set correct source bits for l0's l1-facing downstream node.
    // FIXME; brittle
    val fragmenterAddedBits =
      if (coalescedReqWidth == muonParams.l1CacheLineBytes) 0
      else log2Ceil(coalescedReqWidth / muonParams.l1CacheLineBytes) + 1
    val quietSourceBits = muonParams.dcache
      .map(cache => log2Ceil(cacheMissSourceIds(cache) << fragmenterAddedBits))
      .getOrElse {
        // no L0d to ask: the tag is the coalescer's source space plus the TileLink size field
        val srcBits = (log2Ceil(p(MemParallelismKey).coalSrcIds) max
          muonParams.core.logNonCoalGMEMInFlights) + 1
        srcBits + log2Ceil(log2Ceil(coalescedReqWidth) + 1)
      }
    //
    dcacheNode := idleMaster(
      sourceBits = quietSourceBits,
      name = s"muon_${muonParams.clusterId}_${muonParams.coreId}_quiet_l0d"
    )
    l0d.flushRegNode
  } else {
    val (l0dOut, l0dIn, l0dFlushRegNode) = muonParams.dcache.map { l0dParams =>
      require(l0dParams.blockBytes == coalescedReqWidth)
      require(l0dParams.blockBytes >= warpBytes)
      println(f"l0d flush address is ${muonParams.peripheralAddr}%x")
      val l0d = LazyModule(new TLULNBDCache(TLNBDCacheParams(
        id = tileId,
        cache = l0dParams,
        flushAddr = Some(muonParams.peripheralAddr + 0x100),
        inFlightReqs = p(MemParallelismKey).l0dInFlight,
      )))
      l0d.flushNode.get := dFlushMaster
      (l0d.outNode, l0d.inNode, l0d.flushRegNode)
    }.getOrElse {
      CacheFlushNode.Slave() := dFlushMaster // TODO: tie off
      val passthru = TLEphemeralNode()
      (passthru, passthru, None)
    }

    // ===========
    // coalescer
    // ===========

    val coalescer = LazyModule(new CoalescingUnit(CoalescerConfig(
      enable = true,
      numLanes = muonParams.core.numLanes,
      addressWidth = muonParams.core.archLen,
      dataBusWidth = log2Ceil(coalescedReqWidth),
      coalLogSize = log2Ceil(coalescedReqWidth),
      wordSizeInBytes = muonParams.core.archLen / 8,
      numOldSrcIds = 1 << lsuSourceIdBits,
      numNewSrcIds = p(MemParallelismKey).coalSrcIds,
      // FIX 13: how many coalesced responses the core can have outstanding.  Uncoalescing one
      // 64-byte response puts an entry in every lane's queue, so this depth IS the core's
      // memory-level parallelism for coalesced traffic.  At 2 a fully occupied core sustained only
      // 1.7 loads in flight and retired one every 69 cycles whatever the working set
      // (runs/mlp2_*, runs/ms_fix_v2).  Affordable now that an entry is 41 bits instead of 521.
      respQueueDepth = p(MemParallelismKey).coalRespDepth,
      numCoalReqs = 1,
    )))

    dcacheNode :=
      ResponseFIFOFixer() :=
      TLFragmenter(muonParams.l1CacheLineBytes, coalescedReqWidth, alwaysMin = true) :=
      TLWidthWidget(coalescedReqWidth) :=
      l0dOut
    val coalXbar = LazyModule(new TLXbar).suggestName("coal_out_agg_xbar").node
    val nonCoalXbar = LazyModule(new TLXbar).suggestName("coal_out_nc_xbar").node
    l0dIn := coalXbar

    // (0 until muonParams.core.numLanes).foreach(_ => nonCoalXbar := coalescer.nexusNode)
    coalXbar := coalescer.nexusNode
    coalescer.passthroughNodes.foreach(nonCoalXbar := _)
    (coalXbar
      := TLWidthWidget(muonParams.core.archLen / 8)
      := TLSourceShrinker(1 << muonParams.core.logNonCoalGMEMInFlights)
      := nonCoalXbar)

    lsuNodes.foreach(coalescer.nexusNode := _)
    l0dFlushRegNode
  }

  // ===========
  // misc
  // ===========

  val softResetFinishSlave = SoftResetFinishNode.Slave()

  val barrierMaster = BarrierNode.Master(log2Ceil(muonParams.core.numWarps))

  override protected def visibleManagers = Seq()
  // this overrides the reset vector nexus node to be consistent with the other tiles (gemmini tile)
  // otherwise it results in a really obscure diplomacy error
  override protected def visiblePhysAddrBits = if (p(RadianceSimArgs)) 33 else 34

  org.chipsalliance.diplomacy.DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }
  val dtimProperty = Nil
  val itimProperty = Nil

  val cpuDevice: SimpleDevice = new SimpleDevice(
    "gpu",
    Seq(s"sifive,muon${tileParams.tileId}", "riscv")
  ) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(
        name,
        mapping ++ cpuProperties ++ nextLevelCacheProperty
          ++ tileProperties ++ dtimProperty ++ itimProperty /*++ beuProperty*/
      )
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(tileId))
  }

  override lazy val module = if (muonParams.cyclotronCore) {
    new CyclotronTileModuleImp(this)
  } else {
    new MuonTileModuleImp(this)
  }

  override def makeMasterBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = TLBuffer(BufferParams.none)

  override def makeSlaveBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = TLBuffer(BufferParams.none)
}

class MuonTileModuleImp(outer: MuonTile) extends BaseTileModuleImp(outer) {
  val core = Module(new MuonCore)

  MuonMemTL.connectTL(core.io.imem.req, core.io.imem.resp, outer.icacheWordNode)

  MuonMemTL.multiConnectTL(core.io.dmem.req, core.io.dmem.resp, outer.innerLsuNodes)
  MuonMemTL.multiConnectTL(core.io.smem.req, core.io.smem.resp, outer.innerSmemNodes)

  val (barrier, _) = outer.barrierMaster.out.head
  barrier.req <> core.io.barrier.req
  barrier.resp <> core.io.barrier.resp

  // FIX 4 (tile side): count requests in flight on the lane links (one D beat answers each A beat on
  // these TL-UL links) and tell the L0d when the memory path above it is quiescent, i.e. nothing that
  // could still dirty a line is on its way.  The L0d defers every flush start until then.
  // FF cost: log2(16 lanes x 2^lsuSourceIdBits) + 1 bits for the counter.
  val laneLinks = outer.innerLsuNodes.map(_.out.head._1)
  val laneAFires = PopCount(laneLinks.map(_.a.fire))
  val laneDFires = PopCount(laneLinks.map(_.d.fire))
  val memOutstanding = RegInit(0.U((log2Ceil(laneLinks.length << outer.lsuSourceIdBits) + 1).W))
  memOutstanding := memOutstanding + laneAFires - laneDFires
  assert(memOutstanding + laneAFires >= laneDFires, "more lane D beats than outstanding A beats")
  val memQuiescent = core.io.lsuQueuesEmpty.globalQueuesEmpty && (memOutstanding === 0.U)

  val iFlush = outer.iFlushMaster.out.head._1
  val dFlush = outer.dFlushMaster.out.head._1
  iFlush.start := core.io.flush.i.start
  dFlush.start := core.io.flush.d.start
  core.io.flush.i.done := iFlush.done
  core.io.flush.d.done := dFlush.done
  iFlush.quiescent := true.B      // the instruction cache holds nothing dirty
  dFlush.quiescent := memQuiescent

  core.io.coreId := outer.muonParams.coreId.U
  core.io.clusterId := outer.muonParams.clusterId.U

  val softReset = outer.softResetFinishSlave.in.head._1.softReset
  core.io.softReset := softReset
  outer.softResetFinishSlave.in.head._1.finished := core.io.finished

  // finish-triggered flush: the L0d latches the request and starts it once the tile is quiescent
  val justFinished = core.io.finished && !RegNext(core.io.finished)
  when (justFinished && !softReset) { // override only when finish not caused by reset
    iFlush.start := true.B
    dFlush.start := true.B
  }

  outer.reportCease(None)
  outer.reportWFI(None)

  when (outer.muonParams.disabled.B || softReset) {
    core.io.imem.req.ready := false.B
    core.io.imem.resp.valid := false.B // responses will be dropped
    outer.icacheWordNode.out.foreach(_._1.a.valid := false.B)
    outer.icacheWordNode.out.foreach(_._1.a.ready := true.B) // drain downstream
  }

  // inst/mem traces
  if (core.muonParams.trace) {
    val ctrace = Module(new Tracer(
      clusterId = outer.muonParams.clusterId,
      coreId = outer.muonParams.coreId,
    ))
    ctrace.io.inst <> core.io.trace.get
    ctrace.connectDmem(core.io.dmem)
    ctrace.connectSmem(core.io.smem)
  }

  // performance counters
  if (core.muonParams.profiler) {
    val cperf = Module(new Profiler(
      clusterId = outer.muonParams.clusterId,
      coreId = outer.muonParams.coreId,
    ))
    cperf.io.perf <> core.io.perf
    cperf.io.finished := core.io.finished
  }

  // RTL-model difftest
  if (core.muonParams.difftest) {
    val cdiff = Module(new CyclotronDiffTest(
      clusterId = outer.muonParams.clusterId,
      coreId = outer.muonParams.coreId,
      tick = true
    ))
    cdiff.io.trace <> core.io.trace.get
  }
}

class CyclotronTileModuleImp(outer: MuonTile) extends BaseTileModuleImp(outer) {
  val cyclotron = Module(new CyclotronTile)

  MuonMemTL.connectTL(cyclotron.io.imem.req, cyclotron.io.imem.resp, outer.icacheWordNode)

  MuonMemTL.multiConnectTL(cyclotron.io.dmem.req, cyclotron.io.dmem.resp, outer.innerLsuNodes)
  // TODO: smem
  // MuonMemTL.multiConnectTL(cyclotron.io.smem.req, cyclotron.io.smem.resp, outer.innerSmemNodes)

  // TODO: barrier
  // val (barrier, _) = outer.barrierMaster.out.head
  // barrier.req <> cyclotron.io.barrier.req
  // barrier.resp <> cyclotron.io.barrier.resp

  // TODO: flush
  // outer.iFlushMaster.out.head._1 <> cyclotron.io.flush.i
  // outer.dFlushMaster.out.head._1 <> cyclotron.io.flush.d

  // TODO: core/clusterId
  // cyclotron.io.coreId := outer.cyclotronParams.coreId.U
  // cyclotron.io.clusterId := outer.cyclotronParams.clusterId.U

  // TODO: softReset
  val softReset = outer.softResetFinishSlave.in.head._1.softReset
  // cyclotron.io.softReset := softReset
  outer.softResetFinishSlave.in.head._1.finished := cyclotron.io.finished

  outer.reportCease(None)
  outer.reportWFI(None)

  when (outer.muonParams.disabled.B /* TODO: || softReset */) {
    cyclotron.io.imem.req.ready := false.B
    cyclotron.io.imem.resp.valid := false.B // responses will be dropped
    outer.icacheWordNode.out.foreach(_._1.a.valid := false.B)
  }

  // TODO: perf
}
