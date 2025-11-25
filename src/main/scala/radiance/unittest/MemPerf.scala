package radiance.unittest

import freechips.rocketchip.interrupts.IntOutwardNode
import freechips.rocketchip.prci._
import freechips.rocketchip.resources.{Device, SimpleDevice}
import freechips.rocketchip.rocket.{BTBParams, DCacheParams, ICacheParams}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import radiance.cluster.SoftResetFinishNode
import radiance.memory.idleMaster
import radiance.muon.MuonTileParams
import radiance.subsystem.{MuonTileAttachParams, MuonTileLike}

class WithMemPerfMuonTileReplacement(
  location: HierarchicalLocation,
) extends Config((_, _, up) => {
  case TilesLocated(`location`) =>
    // hijack existing muon tile instantiations
    val prev = up(TilesLocated(`location`))
    prev.map {
      case MuonTileAttachParams(tile, crossing) =>
        new CanAttachTile {
          type TileType = MemPerfMuonTile
          def tileParams = MemPerfMuonTileParams(tile)
          def crossingParams = crossing
        }
      case t => t
    }
})

case class MemPerfMuonTileParams(
  m: MuonTileParams,
) extends InstantiableTileParams[MemPerfMuonTile] {

  override val core: CoreParams = m.core
  override val icache: Option[ICacheParams] = m.icache
  override val dcache: Option[DCacheParams] = m.dcache
  override val btb: Option[BTBParams] = m.btb
  override val tileId: Int = m.tileId
  override val blockerCtrlAddr: Option[BigInt] = m.blockerCtrlAddr
  override val baseName: String = m.baseName + "_mem"
  override val uniqueName: String = m.uniqueName
  override val clockSinkParams: ClockSinkParameters = m.clockSinkParams

  def instantiate(
    crossing: HierarchicalElementCrossingParamsLike,
    lookup: LookupByHartIdImpl
  )(implicit p: Parameters): MemPerfMuonTile = {
    new MemPerfMuonTile(this, crossing.crossingType, lookup)(p)
  }
}

abstract class BaseTileBase(
  tileParams: TileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
)(implicit p: Parameters)
  extends BaseTile(tileParams, crossing, lookup, p)
  with SinksExternalInterrupts
  with SourcesExternalNotifications {

  override def intOutwardNode: Option[IntOutwardNode] = None
  override def cpuDevice: Device = new SimpleDevice("gpu", Seq())
  override def masterNode: TLOutwardNode = TLIdentityNode()
  override def slaveNode: TLInwardNode = TLIdentityNode()

  override protected def visibleManagers = Seq()
  override protected def visiblePhysAddrBits = 33
}

class MemPerfMuonTile(
  memMuonParams: MemPerfMuonTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
)(implicit p: Parameters)
  extends BaseTileBase(memMuonParams, crossing, lookup) with MuonTileLike {

  val muonParams: MuonTileParams = memMuonParams.m

  // private val patterns = TrafficPatterns.smemPatterns(muonParams.clusterId).head

  val smemNodes: Seq[TLNode] = Seq.tabulate(muonParams.core.numLanes) { i =>
    LazyModule(new TLTrafficGen(
      nodeName = s"smem_$i",
      sourceBits = 4,
      n = 1,
      reqFn = new TrafficPatterns.Strided(1, 1).getSmem(muonParams.clusterId)(_, i),
    )).node
  }
  val icacheNode: TLNode = idleMaster()
  val dcacheNode: TLNode = visibilityNode
  val softResetFinishSlave = SoftResetFinishNode.Slave()

  visibilityNode := idleMaster(sourceBits = 6)

  lazy val module = new BaseTileModuleImp[MemPerfMuonTile](this) {

  }
}
