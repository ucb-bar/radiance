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
import radiance.cluster.SoftResetFinishNode
import radiance.memory._
import radiance.subsystem._
import radiance.unittest.{Profiler, CyclotronDiffTest}

case class MuonTileParams(
  core: MuonCoreParams = MuonCoreParams(),
  tileId: Int = 0,
  coreId: Int = 0,
  clusterId: Int = 0,
  icache: Option[ICacheParams] = None,
  icacheUsingD: Option[DCacheParams] = None,
  dcache: Option[DCacheParams] = None,
  peripheralAddr: BigInt = 0,
  disabled: Boolean = false,
  btb: Option[BTBParams] = None,
  beuAddr: Option[BigInt] = None,
  blockerCtrlAddr: Option[BigInt] = None,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  boundaryBuffers: Option[RocketTileBoundaryBufferParams] = None,
  l1CacheLineBytes: Int = 32,
) extends InstantiableTileParams[MuonTile] {
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

  // see comment below about innerLsuNodes / lsuNodes
  val innerSmemNodes = Seq.tabulate(muonParams.core.lsu.numLsuLanes) { i =>
    TLClientNode(
      Seq(
        TLMasterPortParameters.v1(
          clients = Seq(
            TLMasterParameters.v1(
              sourceId = IdRange(0, 1 << muonParams.core.logSMEMInFlights),
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

  def connectBuf(node: TLNode, n: Int): TLNode = {
    val cacheBuf = TLBuffer(ace = BufferParams(n), bd = BufferParams(0))
    cacheBuf := node
  }

  val (l0iOut, l0iIn) = muonParams.icacheUsingD.map { l0iParams =>
    val l0i = LazyModule(new TLULNBDCache(TLNBDCacheParams(
      id = tileId,
      cache = l0iParams,
      cacheTagBits = muonParams.core.l0iReqTagBits,
      overrideDChannelSize = Some(3)
    )))
    (connectBuf(l0i.outNode, 4), l0i.inNode)
  }.getOrElse {
    val passthru = TLEphemeralNode()
    (passthru, passthru)
  }
  val icacheNode = TLIdentityNode()
  icacheNode := l0iOut
  l0iIn :=
    TLWidthWidget(muonParams.core.instBytes) :=
    ResponseFIFOFixer() :=
    icacheWordNode

  val lsuDerived = new LoadStoreUnitDerivedParams(q, muonParams.core)
  val lsuSourceIdBits = lsuDerived.sourceIdBits
  
  // LSU expects all-lanes-at-once requests, so request valid is dependent on whether all lanes are ready
  // This interacts poorly with downstream request arbitration (e.g. XBar), so we need buffer to decouple
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

  
  val coalescedReqWidth = muonParams.core.numLanes * muonParams.core.archLen / 8

  val (l0dOut, l0dIn, flushRegNode) = muonParams.dcache.map { l0dParams =>
    require(muonParams.dcache.map(_.blockBytes).getOrElse(coalescedReqWidth) == coalescedReqWidth)
    println(f"l0d flush address is ${muonParams.peripheralAddr}%x")
    val l0d = LazyModule(new TLULNBDCache(TLNBDCacheParams(
      id = tileId,
      cache = l0dParams,
      cacheTagBits = muonParams.core.l0dReqTagBits,
      flushAddr = Some(muonParams.peripheralAddr),
    )))
    (l0d.outNode, l0d.inNode, l0d.flushRegNode)
  }.getOrElse {
   val passthru = TLEphemeralNode()
   (passthru, passthru, None)
  }

  val dcacheNode = visibilityNode

  val coalescer = LazyModule(new CoalescingUnit(CoalescerConfig(
    enable = true,
    numLanes = muonParams.core.numLanes,
    addressWidth = muonParams.core.archLen,
    dataBusWidth = log2Ceil(coalescedReqWidth),
    coalLogSize = log2Ceil(coalescedReqWidth),
    wordSizeInBytes = muonParams.core.archLen / 8,
    numOldSrcIds = 1 << lsuSourceIdBits,
    numNewSrcIds = 1 << muonParams.core.logCoalGMEMInFlights,
    respQueueDepth = 4,
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

//  val muon = Module(new Muon()(newP))
  override lazy val module = new MuonTileModuleImp(this)

  override def makeMasterBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = TLBuffer(BufferParams.none)

  override def makeSlaveBoundaryBuffers(
      crossing: ClockCrossingType
  )(implicit p: Parameters) = TLBuffer(BufferParams.none)
}

class MuonTileModuleImp(outer: MuonTile) extends BaseTileModuleImp(outer) {
  val muon = Module(new MuonCore(test = false))

  MuonMemTL.connectTL(muon.io.imem.req, muon.io.imem.resp, outer.icacheWordNode)

  MuonMemTL.multiConnectTL(muon.io.dmem.req, muon.io.dmem.resp, outer.innerLsuNodes)
  MuonMemTL.multiConnectTL(muon.io.smem.req, muon.io.smem.resp, outer.innerSmemNodes)

  val (barrier, _) = outer.barrierMaster.out.head
  barrier.req <> muon.io.barrier.req
  barrier.resp <> muon.io.barrier.resp

  muon.io.coreId := outer.muonParams.coreId.U
  muon.io.clusterId := outer.muonParams.clusterId.U
  outer.reportCease(None)
  outer.reportWFI(None)

  muon.io.softReset := outer.softResetFinishSlave.in.head._1.softReset
  outer.softResetFinishSlave.in.head._1.finished := muon.io.finished

  if (outer.muonParams.disabled) {
    muon.io.imem.req.ready := false.B
    muon.io.imem.resp.valid := false.B
  }

  val isSim = p(RadianceSimArgs)
  if (isSim) {
    val cperf = Module(new Profiler)
    cperf.io.perf <> muon.io.perf
    cperf.io.finished := muon.io.finished
  }

  if (muon.test) {
    assert(isSim, "MuonCore.test cannot be true in non-sim mode!")
    val cdiff = Module(new CyclotronDiffTest(tick = true))
    cdiff.io.trace <> muon.io.trace.get
  }
}
