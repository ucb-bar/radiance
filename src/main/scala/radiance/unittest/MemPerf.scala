package radiance.unittest

import chisel3._
import chisel3.util._

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
import radiance.memory.{connectOne, idleMaster}
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
  val core: CoreParams = m.core
  val icache: Option[ICacheParams] = m.icache
  val dcache: Option[DCacheParams] = m.dcache
  val btb: Option[BTBParams] = m.btb
  val tileId: Int = m.tileId
  val blockerCtrlAddr: Option[BigInt] = m.blockerCtrlAddr
  val baseName: String = m.baseName + "_mem"
  val uniqueName: String = m.uniqueName
  val clockSinkParams: ClockSinkParameters = m.clockSinkParams

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

  val intOutwardNode: Option[IntOutwardNode] = None
  val cpuDevice: Device = new SimpleDevice("gpu", Seq())
  val masterNode: TLOutwardNode = TLIdentityNode()
  val slaveNode: TLInwardNode = TLIdentityNode()

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

  val reqSourceBits = 4
  val reqsPerPattern = 4096

  private val patterns = TrafficPatterns.smemPatterns(muonParams.clusterId)

  val trafficGenerators = Seq.tabulate(muonParams.core.numLanes) { lane =>
    patterns.map { case (name, pattern) =>
      LazyModule(new TLTrafficGen(
        nodeName = s"smem_${lane}_$name",
        sourceBits = reqSourceBits,
        n = reqsPerPattern,
        reqFn = pattern(_, lane),
      ))
    }
  }

  val trafficXbars = trafficGenerators.map { gens =>
    // lowest index so that all patterns go in order
    val xbar = LazyModule(new TLXbar(TLArbiter.lowestIndexFirst)).node
    gens.foreach(xbar := _.node)
    xbar
  }

  val smemNodes: Seq[TLNode] = trafficXbars.map(connectOne(_, TLEphemeralNode.apply)(p, false))
  val icacheNode: TLNode = idleMaster()
  val dcacheNode: TLNode = visibilityNode
  val softResetFinishSlave = SoftResetFinishNode.Slave()

  visibilityNode := idleMaster(sourceBits = 6)

  lazy val module = new BaseTileModuleImp[MemPerfMuonTile](this) {

    val time = Counter(true.B, Int.MaxValue)._1

    val patternLanes = outer.trafficGenerators.transpose

    def allFinished(lanes: Seq[TLTrafficGen]) =
      VecInit(lanes.map(_.module.io.finished)).asUInt.andR

    // valid sequencing
    patternLanes.head.foreach(_.module.io.started := true.B)
    patternLanes.reduceLeft { (prevPattern, currPattern) =>
      val prevFinished = allFinished(prevPattern)
      currPattern.foreach(_.module.io.started := RegNext(prevFinished))

      // rising edge
      when (prevFinished && !RegNext(prevFinished)) {
        printf(cf"[MEM PERF] ${prevPattern.head.nodeName} finished at time $time\n")
        printf(cf"[MEM PERF] ${currPattern.head.nodeName} starting\n")
      }
      currPattern
    }

    val lastFinished = allFinished(patternLanes.last)
    when (RegNext(lastFinished)) {
      printf(cf"[MEM PERF] ${patternLanes.last.head.nodeName} finished at time $time\n")
      stop()
    }

    outer.reportCease(None)
    outer.reportWFI(None)
  }
}
