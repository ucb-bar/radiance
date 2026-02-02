// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.rocket.DCacheParams
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile.TileVisibilityNodeKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory.BoolArrayUtils.BoolSeqUtils
import radiance.memory._
import radiance.muon.{BarrierJunction, MuonTile, Synchronizer}
import radiance.subsystem._

case class RadianceClusterParams(
  clusterId: Int,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  baseAddr: BigInt,
  smemConfig: RadianceSharedMemKey,
  l1Config: DCacheParams,
  peripheralAddrOffset: Int = 0x80000,
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
  val gemminiTiles = leafTiles.values.filter(_.isInstanceOf[GemminiTileLike])
    .toSeq.asInstanceOf[Seq[GemminiTileLike]]
  val muonTiles = leafTiles.values.filter(_.isInstanceOf[MuonTileLike])
    .toSeq.asInstanceOf[Seq[MuonTileLike]]

  val extReqXbar = TLXbar() // this is in 8 bytes
  val extReqForSmem = connectOne(extReqXbar, () => TLWidthWidget(8))(p, false)
  val disableMonitors = true

  // TODO: new shared mem components gen
  def radianceSharedMemComponentsGen() = new RadianceSharedMemComponents(
    thisClusterParams,
    gemminiTiles,
    muonTiles,
    extClients = Seq(extReqForSmem))

  def radianceSharedMemComponentsImpGen(outer: RadianceSharedMemComponents) = new RadianceSharedMemComponentsImp(outer)
  LazyModule(new RadianceSharedMem(
    thisClusterParams.smemConfig,
    radianceSharedMemComponentsGen,
    Some(radianceSharedMemComponentsImpGen(_)),
    clcbus)).suggestName("shared_mem")

  // clcbus -> gemmini mmio
  // this tells the atomic automata to back off for this register node
  // bad things will happen however if we actually do amo on this region.
  // also: fragmenter does not expand arithmetic/logical ops, so the hack node
  // must be upwards of the fragmenter to hack in beat bytes = 8
  gemminiTiles.foreach(_.slaveNode := HackAtomicNode(8) := clcbus.outwardNode)

  if (gemminiTiles.isEmpty) {
    // make sure even without a gemmini in the cluster, clc node still finds a manager
    val dummySinkNode = TLManagerNode(Seq(TLSlavePortParameters.v1(Seq(TLSlaveParameters.v2(
      address = Seq(AddressSet(thisClusterParams.baseAddr + thisClusterParams.smemConfig.size, 0xff)),
      supports = TLMasterToSlaveTransferSizes(
        get = TransferSizes(1, 8),
        putFull = TransferSizes(1, 8)),
      fifoId = Some(0),
    )), beatBytes = 8)))
    dummySinkNode := HackAtomicNode(8) := clcbus.outwardNode
  }

  // clcbus -> print buffer TODO: move to MuonTile
  val printBuf = TLRAM(
    address = AddressSet(thisClusterParams.baseAddr +
      thisClusterParams.peripheralAddrOffset, 0x100 * (muonTiles.length max 1) - 1),
    cacheable = false,
    atomics = true,
    beatBytes = 8
  )
  printBuf := clcbus.outwardNode

  val GPUMemParams(gmemAddr, gmemSize) = p(GPUMemory).get

  // cbus -> clcbus/smem
  (clcbus.inwardNode
    := TLFragmenter(8, 128, alwaysMin = true)
    := TLSourceShrinker(thisClusterParams.smemConfig.controlInFlights)
    := extReqXbar)
  extReqXbar := ccbus.outwardNode
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
  val l1cache = LazyModule(new TLNBDCache(TLNBDCacheParams(
    id = clusterId,
    cache = thisClusterParams.l1Config,
    cacheTagBits = muonTiles.head.muonParams.core.l1ReqTagBits,
    overrideDChannelSize = Some(log2Ceil(thisClusterParams.l1Config.blockBytes)),
  ))(
    p.alterMap(Map(
      TileVisibilityNodeKey -> visibilityNode,
    ))
  ))

  clsbus.inwardNode := visibilityNode := l1cache.outNode

  // connect barriers & flushes
  val realMuons = muonTiles.filter(_.isInstanceOf[MuonTile]).map(_.asInstanceOf[MuonTile])
  if (realMuons.nonEmpty) {
    val barrierJunction = LazyModule(new BarrierJunction())
    val barrierSynchronizer = LazyModule(new Synchronizer())

    realMuons.foreach(barrierJunction.node := _.barrierMaster)
    barrierSynchronizer.node := barrierJunction.node

    realMuons.flatMap(m => m.l0iFlushRegNode.toSeq ++ m.l0dFlushRegNode.toSeq)
      .foreach(_ := HackAtomicNode(8) := clcbus.outwardNode)
  }

//  val l1InNodes = muonTiles.map(_.dcacheNode)
  val l1InNodes = muonTiles.flatMap(t => Seq(t.icacheNode, t.dcacheNode))
  val l1InXbar = LazyModule(new TLXbar()).suggestName("radiance_l1_in_xbar").node
  l1cache.inNode := TLFIFOFixer() := l1InXbar
  l1InNodes.foreach(l1InXbar := _)

  val softResetFinishMasters = p(GPUResetKey).fold(muonTiles.map { m =>
    val master = SoftResetFinishNode.Master()
    m.softResetFinishSlave := master
    master
  })(_ => Seq())

  override lazy val module = new RadianceClusterModuleImp(this)
}

class RadianceClusterModuleImp(outer: RadianceCluster) extends ClusterModuleImp(outer) {
  println(s"======= RadianceCluster: clcbus inward edges = ${outer.clcbus.inwardNode.inward.inputs.length}")
  println(s"======= RadianceCluster: clcbus name = ${outer.clcbus.busName}")
  println(s"======= RadianceCluster: csbus outward edges = ${outer.csbus.outwardNode.outward.outputs.length}")
  println(s"======= RadianceCluster: csbus name = ${outer.csbus.busName}")

  if (outer.softResetFinishMasters.nonEmpty) {
    val finished = VecInit(outer.softResetFinishMasters.map(_.out.head._1.finished)).andR
    val (_, stopSim) = Counter(0 until 8192, finished, !finished)
    when (stopSim) {
      stop("no more active warps for 8k cycles\n")
    }

    outer.softResetFinishMasters.foreach(_.out.head._1.softReset := false.B)
  }

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
