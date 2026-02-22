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
import freechips.rocketchip.diplomacy.BufferParams
import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule.LazyModule
import radiance.cluster._
import radiance.memory._
import radiance.muon._
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

  val testType = "dmem"
  val reqSourceBits = 4
  val reqsPerPattern = 4096

  private val patterns = testType match {
    case "smem" => TrafficPatterns.smemPatterns(muonParams.clusterId)
    case "dmem" => TrafficPatterns.dmemPatterns(muonParams.clusterId)
    case _ => throw new IllegalArgumentException(s"Unknown test type: $testType")
  }

  val trafficGenerators = Seq.tabulate(muonParams.core.numLanes) { lane =>
    val lanePatterns = patterns.map { case (name, pattern) =>
      (name, pattern(_, lane))
    }
    LazyModule(new TLTrafficGen(
      nodeName = s"${testType}_$lane",
      sourceBits = reqSourceBits,
      n = reqsPerPattern,
      patterns = lanePatterns,
    )).suggestName(s"traffic_${testType}_$lane")
  }

  val icacheNode: TLNode = idleMaster()
  val softResetFinishSlave = SoftResetFinishNode.Slave()
  val softResetFinishMaster = SoftResetFinishNode.Master()
  softResetFinishSlave := softResetFinishMaster

  // Define smemNodes and dcacheNode based on testType
  val (smemNodes: Seq[TLNode], dcacheNode: TLNode) = if (testType == "smem") {
    val smem = trafficGenerators.map(x => connectOne(x.node, TLEphemeralNode.apply)(p, false))
    val dcache = visibilityNode
    visibilityNode := idleMaster(sourceBits = 6)
    (smem, dcache)
  } else {
    val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams.core)
    val lsuSourceIdBits = lsuDerived.sourceIdBits
    val coalescedReqWidth = muonParams.core.numLanes * muonParams.core.archLen / 8

    // Traffic generator nodes (replacing innerLsuNodes from MuonTile)
    val trafficGenNodes = trafficGenerators.map { gen =>
      (TLBuffer()
        := TLSourceShrinker(1 << muonParams.core.logGMEMInFlights)
        := gen.node)
    }

    // Coalescer setup (from MuonTile)
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

    // L0D cache setup (from MuonTile)
    val (l0dOut, l0dIn, l0dFlushRegNode) = muonParams.dcache.map { l0dParams =>
      require(muonParams.dcache.map(_.blockBytes).getOrElse(coalescedReqWidth) == coalescedReqWidth)
      val l0d = LazyModule(new TLULNBDCache(TLNBDCacheParams(
        id = tileId,
        cache = l0dParams,
        cacheTagBits = muonParams.core.l0dReqTagBits,
        flushAddr = None, // Disable flush register to avoid unconnected node
      ))(
        p.alterMap(Map(
          TileVisibilityNodeKey -> visibilityNode
        ))
      ))
      (l0d.outNode, l0d.inNode, None)
    }.getOrElse {
      val passthru = TLEphemeralNode()
      (passthru, passthru, None)
    }

    val dcache = visibilityNode

    dcache :=
      TLBuffer(
        a = BufferParams(0),
        b = BufferParams(0),
        c = BufferParams(0),
        d = BufferParams(5),
        e = BufferParams(0)
      ) :=
      ResponseFIFOFixer() :=
      TLFragmenter(muonParams.l1CacheLineBytes, coalescedReqWidth, alwaysMin = true) :=
      TLWidthWidget(coalescedReqWidth) :=
      l0dOut

    // Coalescer crossbar setup (from MuonTile)
    val coalXbar = LazyModule(new TLXbar).suggestName("coal_out_agg_xbar").node
    val nonCoalXbar = LazyModule(new TLXbar).suggestName("coal_out_nc_xbar").node
    l0dIn := coalXbar

    coalXbar := coalescer.nexusNode
    coalescer.passthroughNodes.foreach(nonCoalXbar := _)
    (coalXbar
      := TLWidthWidget(muonParams.core.archLen / 8)
      := TLSourceShrinker(1 << muonParams.core.logNonCoalGMEMInFlights)
      := nonCoalXbar)

    // Connect traffic generators directly to coalescer
    trafficGenNodes.foreach(coalescer.nexusNode := _)

    // Return smem (idle masters) and dcache nodes
    val smem = Seq.fill(muonParams.core.lsu.numLsuLanes)(idleMaster())
    (smem, dcache)
  }

  lazy val module = new BaseTileModuleImp[MemPerfMuonTile](this) {
    val time = Counter(true.B, Int.MaxValue)._1

    // Drive softResetFinish signals (not used for testing, but required by trait)
    outer.softResetFinishMaster.out.head._1.softReset := false.B
    
    val allLanesFinished = VecInit(outer.trafficGenerators.map(_.module.io.finished)).asUInt.andR
    outer.trafficGenerators.foreach(_.module.io.start := allLanesFinished)

    val allPatternsDone = VecInit(outer.trafficGenerators.map(_.module.io.allFinished)).asUInt.andR
    
    val finishPulse = allLanesFinished && !RegNext(allLanesFinished)
    val patternCount = Counter(finishPulse, patterns.length)._1
    val testsComplete = RegInit(false.B)
    
    when (finishPulse) {
      outer.patterns.map(_._1).zipWithIndex.foreach { case (name, i) =>
        when (patternCount === i.U) {
          printf(cf"[TRAFFIC] core ${muonParams.coreId} ${name} finished at time $time\n")
          if (i == outer.patterns.length - 1) {
            printf(cf"[TRAFFIC] core ${muonParams.coreId} all done!\n")
            testsComplete := true.B
          }
        }
      }
    }
    
    // Force simulation exit when all patterns are done
    when (testsComplete && RegNext(testsComplete)) {
      chisel3.stop()
    }

    // Drive finished signal
    outer.softResetFinishMaster.out.head._1.finished := testsComplete

    outer.reportCease(Some(RegNext(allPatternsDone)))
    outer.reportWFI(None)
  }
}
