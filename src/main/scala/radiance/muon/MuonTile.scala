package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy.{AddressSet, BufferParams, IdRange, TransferSizes}
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.resources._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.{CacheBlockBytes, HasTilesExternalResetVectorKey, HierarchicalElementCrossingParamsLike}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import radiance.cluster.{FakeRadianceClusterTileParams, SoftResetFinishNode}
import radiance.memory._
import radiance.subsystem._

case object NumMuonCores extends Field[Int](0)

case class MuonTileParams(
  core: MuonCoreParams = MuonCoreParams(),
  tileId: Int = 0,
  coreId: Int = 0,
  clusterId: Int = 0,
  icache: Option[ICacheParams] = None,
  dcache: Option[DCacheParams] = None,
  btb: Option[BTBParams] = None,
  beuAddr: Option[BigInt] = None,
  blockerCtrlAddr: Option[BigInt] = None,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  boundaryBuffers: Option[RocketTileBoundaryBufferParams] = None,
  cacheLineBytes: Int = 32,
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
    
    // don't check full mask when valid is low. 
    // TODO: fix this (e.g. address is 0x02, size is 1, mask is 0b1100)
    // val fullMaskUnshifted = ((1.U << (1.U << m.size).asUInt).asUInt - 1.U)
    // assert(!valid || m.mask === fullMaskUnshifted,
    //  cf"full mask required for now (mask = ${m.mask}, size = ${m.size})")
    
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
                             tl_bundle: TLBundle,
                             tl_edge: TLEdgeOut): Unit = {
    tl_bundle.a.bits := MuonMemTL.toTLA(mreq.bits, mreq.valid, tl_edge)
    tl_bundle.a.valid := mreq.valid
    mreq.ready := tl_bundle.a.ready

    mresp.valid := tl_bundle.d.valid
    mresp.bits := MuonMemTL.fromTLD(tl_bundle.d.bits, mresp.bits)
    tl_bundle.d.ready := mresp.ready
  }

  def connectTL[T <: Bundle](mreq: DecoupledIO[MemRequest[T]],
                             mresp: DecoupledIO[MemResponse[T]],
                             tl: TLClientNode): Unit = {
    val (in, ie) = tl.out.head
    connectTL(mreq, mresp, in, ie)
  }

  def multiConnectTL[T <: Bundle](mreq: Vec[DecoupledIO[MemRequest[T]]],
                                mresp: Vec[DecoupledIO[MemResponse[T]]],
                                tl_client: TLClientNode) = {
    require(mreq.length == tl_client.out.length, f"length mismatch (core = ${mreq.length}, tilelink = ${tl_client.out.length})")
    require(mresp.length == tl_client.out.length, f"length mismatch (core = ${mresp.length}, tilelink = ${tl_client.out.length})")
    for ((req, resp, (tl_bundle, tl_edge)) <- mreq lazyZip mresp lazyZip tl_client.out) {
      connectTL(req, resp, tl_bundle, tl_edge)
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
  with SourcesExternalNotifications {

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

  val smemNodes = Seq.tabulate(muonParams.core.lsu.numLsuLanes) { i =>
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

// TODO
//  // Conditionally instantiate memory coalescer
//  val coalescerNode = p(CoalescerKey) match {
//    case Some(coalParam) => {
//      val coal = LazyModule(
//        new CoalescingUnit(coalParam)
//      )
//      coal.cpuNode :=* dmemAggregateNode
//      coal.aggregateNode // N+1 lanes
//    }
//    case None => dmemAggregateNode
//  }

  val icacheWordNode = muonParams.icache match {
    case _ => TLClientNode(Seq(TLMasterPortParameters.v2(
      masters = Seq(TLMasterParameters.v2(
        name = s"muon${muonParams.coreId}_i_word",
        requestFifo = true,
        emits = TLMasterToSlaveTransferSizes(
          get = TransferSizes(1, muonParams.core.instBytes)
        ),
        sourceId = IdRange(0, muonParams.core.numWarps * muonParams.core.ibufDepth)
      )),
      channelBytes = TLChannelBeatBytes(muonParams.core.instBytes),
    )))
  }

  val l0i = LazyModule(new TLULNBDCache(muonParams.coreId, Some(3))(
    p
    // p.alterMap(Map(
    //   TileKey -> FakeRadianceClusterTileParams(
    //     cache = Some(muonParams.dcache.get),
    //     muonCore = muonParams.core,
    //     clusterId = 0
    //   ),
    //   CacheBlockBytes -> muonParams.dcache.get.blockBytes,
    //   // TileVisibilityNodeKey -> visibilityNode,
    // ))
  ))
  val icacheNode = TLIdentityNode()
  icacheNode := l0i.outNode
  l0i.inNode :=
    TLWidthWidget(muonParams.core.instBytes) :=
    ResponseFIFOFixer() :=
    icacheWordNode

  // TODO: source id bits is actually determined by the coalescer
  val lsuDerived = new LoadStoreUnitDerivedParams(q, muonParams.core)
  val sourceIdBits = lsuDerived.sourceIdBits
  val dcacheNode_ = muonParams.dcache match {
    case _ => TLClientNode(Seq(TLMasterPortParameters.v2(
        Seq(TLMasterParameters.v1(
          name = s"muon_tile${muonParams.coreId}_l0d",
          sourceId = IdRange(0, 1 << sourceIdBits)
        )),
    )))
  }

  val dcacheNode = visibilityNode
  dcacheNode := dcacheNode_

  val softResetFinishSlave = SoftResetFinishNode.Slave()

  override protected def visibleManagers = Seq()
  // this overrides the reset vector nexus node to be consistent with the other tiles (gemmini tile)
  // otherwise it results in a really obscure diplomacy error
  override protected def visiblePhysAddrBits = 33

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

  val muon = Module(new MuonCore())
  MuonMemTL.connectTL(muon.io.imem.req, muon.io.imem.resp, outer.icacheWordNode)

  // TODO: both dmem and smem should be a vector of bundles
//  MuonMemTL.connectTL(muon.io.dmem.req, muon.io.dmem.resp, outer.dcacheNode_)
//  MuonMemTL.connectTL(muon.io.smem.req, muon.io.smem.resp, outer.smemNodes)

//  muon.io.imem.req
  muon.io.dmem <> DontCare
  muon.io.smem <> DontCare
  muon.io.coreId := outer.muonParams.coreId.U
  muon.io.clusterId := outer.muonParams.clusterId.U
  outer.reportCease(None)
  outer.reportWFI(None)

  muon.io.softReset := outer.softResetFinishSlave.in.head._1.softReset
  outer.softResetFinishSlave.in.head._1.finished := muon.io.finished
}
