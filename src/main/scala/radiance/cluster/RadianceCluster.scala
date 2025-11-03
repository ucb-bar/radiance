// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.rocket.{BTBParams, DCacheParams, ICacheParams, NonBlockingDCache}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.{CoreParams, TileKey, TileParams, TileVisibilityNodeKey}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.DisableMonitors
import radiance.memory._
import radiance.muon._
import radiance.subsystem._

case class FakeRadianceClusterTileParams(
  cache: Option[DCacheParams],
  muonCore: MuonCoreParams,
  clusterId: Int,
) extends TileParams {
  val core: MuonCoreParams = muonCore.copy(
    xLen = 32,
    cacheLineBytes = cache.map(_.rowBits / 8).getOrElse(0)
  )
  val icache: Option[ICacheParams] = None
  val dcache: Option[DCacheParams] = cache
  val btb: Option[BTBParams] = None
  val tileId: Int = -1
  val blockerCtrlAddr: Option[BigInt] = None
  val baseName: String = "fake_radiance_cluster_tile"
  val uniqueName: String = s"fake_radiance_cluster_tile_$clusterId"
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
}

case class RadianceClusterParams(
  clusterId: Int,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  baseAddr: BigInt,
  smemConfig: RadianceSharedMemKey,
  l1Config: DCacheParams,
) extends InstantiableClusterParams[RadianceCluster] {
  val baseName = "radiance_cluster"
  val uniqueName = s"${baseName}_$clusterId"
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByClusterIdImpl)
                 (implicit p: Parameters): RadianceCluster = {
    new RadianceCluster(this, crossing.crossingType, lookup)
  }
}

class RadianceCluster (
  thisClusterParams: RadianceClusterParams,
  crossing: ClockCrossingType,
  lookup: LookupByClusterIdImpl
)(implicit p: Parameters) extends Cluster(thisClusterParams, crossing, lookup) {
  val clcbus = tlBusWrapperLocationMap(CLCBUS(clusterId))
  clcbus.clockGroupNode := allClockGroupsNode
  val clsbus = tlBusWrapperLocationMap(CLSBUS(clusterId))
  clsbus.clockGroupNode := allClockGroupsNode

  println(f"clcbus width in bytes ${clcbus.beatBytes}")
  println(f"clsbus width in bytes ${clsbus.beatBytes}")

  // make the shared memory srams and interconnects
  val gemminiTiles = leafTiles.values.filter(_.isInstanceOf[GemminiTile]).toSeq.asInstanceOf[Seq[GemminiTile]]
  val muonTiles = leafTiles.values.filter(_.isInstanceOf[MuonTile]).toSeq.asInstanceOf[Seq[MuonTile]]

  val extReqXbar = TLXbar()
  val disableMonitors = true

  // TODO: new shared mem components gen
  def radianceSharedMemComponentsGen() = new RadianceSharedMemComponents(
    thisClusterParams,
    gemminiTiles,
    muonTiles,
    extClients = Seq(extReqXbar))

  def radianceSharedMemComponentsImpGen(outer: RadianceSharedMemComponents) = new RadianceSharedMemComponentsImp(outer)
  LazyModule(new RadianceSharedMem(
    thisClusterParams.smemConfig,
    radianceSharedMemComponentsGen,
    Some(radianceSharedMemComponentsImpGen(_)),
    clcbus)).suggestName("shared_mem")

  // clcbus -> gemmini mmio
  gemminiTiles.foreach(_.slaveNode := TLFragmenter(4, 8) := HackAtomicNode(8) := clcbus.outwardNode)

  if (gemminiTiles.isEmpty) {
    // make sure even without a gemmini in the cluster, clc node still finds a manager
    val dummySinkNode = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v2(
      address = Seq(AddressSet(thisClusterParams.baseAddr + thisClusterParams.smemConfig.size, 0xff)),
      supports = TLMasterToSlaveTransferSizes(
        get = TransferSizes(1, 4),
        putFull = TransferSizes(1, 4)),
      fifoId = Some(0),
    )), beatBytes = 4)))
    dummySinkNode := TLFragmenter(4, 8) := HackAtomicNode(8) := clcbus.outwardNode
  }

  val GPUMemParams(gmemAddr, gmemSize) = p(GPUMemory).get

  // cbus -> clcbus/smem
  clcbus.inwardNode := TLFragmenter(4, 128) := extReqXbar
  extReqXbar :=  ccbus.outwardNode
  // ccbus is connected to cbus automatically

  // clsbus -> csbus -> sbus
  val scopeNode = AddressScopeNode(AddressSet(0, gmemSize - 1))
  val orNode = AddressOrNode(gmemAddr)
  val csBusXbar = TLXbar()
  val clsBusXbar = TLXbar()

  csbus.inwardNode :=* csBusXbar
  clsBusXbar :=* clsbus.outwardNode

  DisableMonitors { implicit p =>
    csBusXbar :=* orNode :=* scopeNode :=* clsBusXbar
  }

  val visibilityNode = TLEphemeralNode()
  // TODO: inflights should be ibuf depth!
  val l1cache = LazyModule(new TLNBDCache(clusterId)(
    p.alterMap(Map(
      // a bit hacky, but required to instantiate dcache outside a tile
      TileKey -> FakeRadianceClusterTileParams(
        cache = Some(thisClusterParams.l1Config),
        muonCore = muonTiles.head.muonParams.core,
        clusterId = clusterId
      ),
      TileVisibilityNodeKey -> visibilityNode,
    ))
  ))

  clsbus.inwardNode := visibilityNode := l1cache.outNode

  // connect barriers
  val numCoresInCluster = muonTiles.length

  // val barrierSlaveNode = BarrierSlaveNode(numCoresInCluster)
  // muonTiles.foreach { tile =>
  //   barrierSlaveNode := tile.barrierMasterNode
  // }

//  val l1InNodes = muonTiles.map(_.dcacheNode)
  val l1InNodes = muonTiles.flatMap(t => Seq(t.icacheNode, t.dcacheNode))
  val l1InXbar = LazyModule(new TLXbar()).suggestName("radiance_l1_in_xbar").node
  l1cache.inNode := TLFIFOFixer() := l1InXbar
  l1InNodes.foreach(l1InXbar := _)

  val softResetFinishMasters = muonTiles.map { m =>
    val master = SoftResetFinishNode.Master()
    m.softResetFinishSlave := master
    master
  }

  override lazy val module = new RadianceClusterModuleImp(this)
}

class RadianceClusterModuleImp(outer: RadianceCluster) extends ClusterModuleImp(outer) {
  println(s"======= RadianceCluster: clcbus inward edges = ${outer.clcbus.inwardNode.inward.inputs.length}")
  println(s"======= RadianceCluster: clcbus name = ${outer.clcbus.busName}")
  println(s"======= RadianceCluster: csbus outward edges = ${outer.csbus.outwardNode.outward.outputs.length}")
  println(s"======= RadianceCluster: csbus name = ${outer.csbus.busName}")

  // TODO: do we want to aggregate across all clusters
  val finished = VecInit(outer.softResetFinishMasters.map(_.out.head._1.finished)).asUInt.orR
  val (_, stopSim) = Counter(0 until 8192, finished, !finished)
  when (stopSim) {
    stop("no more active warps for 8k cycles\n")
  }

  outer.softResetFinishMasters.foreach(_.out.head._1.softReset := false.B) // TODO: MMIO

//  dontTouch(outer.l1cache.module.cacheIO)

  // @cleanup: This assumes barrier params on all edges are the same, i.e. all
  // cores are configured to have the same barrier id range.  While true, might
  // be better to actually assert this
  // val barrierParam = outer.barrierSlaveNode.in.head._2
  // val synchronizer = Module(new BarrierSynchronizer(barrierParam))
  // (synchronizer.io.reqs zip outer.barrierSlaveNode.in).foreach { case (req, (b, _)) =>
  //   req <> b.req
  //   b.resp <> synchronizer.io.resp // broadcast
  // }
}
