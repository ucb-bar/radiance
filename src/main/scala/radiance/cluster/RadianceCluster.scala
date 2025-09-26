// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.{ClockCrossingType, ClockSinkParameters}
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import midas.targetutils.SynthesizePrintf
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import radiance.cluster._
import radiance.memory._
import radiance.muon._
import radiance.subsystem._

case class RadianceClusterParams(
  clusterId: Int,
  clockSinkParams: ClockSinkParameters = ClockSinkParameters(),
  baseAddr: BigInt,
  smemConfig: RadianceSharedMemKey,
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
  gemminiTiles.foreach(_.slaveNode := TLWidthWidget(4) := TLFragmenter(4, 8) := clcbus.outwardNode)

  // cbus -> clcbus/smem
  clcbus.inwardNode := TLFragmenter(4, 128) := extReqXbar
  extReqXbar := ccbus.outwardNode
  // ccbus is connected to cbus automatically

  // clsbus -> csbus -> sbus
  p(GPUMemory).get match {
    case GPUMemParams(address, _) =>
      csbus.inwardNode :=* AddressOrNode(address) :=* clsbus.outwardNode
  } // TODO: the l1 cache has to live between the rewriter and the clsbus

  // connect barriers
  val numCoresInCluster = muonTiles.length

  // val barrierSlaveNode = BarrierSlaveNode(numCoresInCluster)
  // muonTiles.foreach { tile =>
  //   barrierSlaveNode := tile.barrierMasterNode
  // }

  override lazy val module = new RadianceClusterModuleImp(this)
}

class RadianceClusterModuleImp(outer: RadianceCluster) extends ClusterModuleImp(outer) {
  println(s"======= RadianceCluster: clcbus inward edges = ${outer.clcbus.inwardNode.inward.inputs.length}")
  println(s"======= RadianceCluster: clcbus name = ${outer.clcbus.busName}")
  println(s"======= RadianceCluster: csbus outward edges = ${outer.csbus.outwardNode.outward.outputs.length}")
  println(s"======= RadianceCluster: csbus name = ${outer.csbus.busName}")

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
