package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, BufferParams}
import freechips.rocketchip.subsystem.BaseClusterParams
import freechips.rocketchip.tilelink._
import gemmini._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.{DisableMonitors, ValName}
import radiance.memory._
import radiance.muon.MuonTile
import radiance.subsystem._

import scala.collection.mutable.ArrayBuffer

// virgo-specific tilelink nodes
// generic smem implementation is in RadianceSharedMem.scala
class RadianceSharedMemComponents(
  clusterParams: RadianceClusterParams,
  gemminiTiles: Seq[GemminiTile],
  muonTiles: Seq[MuonTile],
  extClients: Seq[TLNode] = Seq(),
)(implicit p: Parameters) extends RadianceSmemNodeProvider  {
  val smemKey = clusterParams.smemConfig

  val wordSize = smemKey.wordSize
  val smemBase = smemKey.address
  val smemBanks = smemKey.numBanks
  val smemWidth = smemKey.numWords * smemKey.wordSize
  val smemDepth = smemKey.size / smemWidth / smemBanks
  val smemSubbanks = smemWidth / wordSize
  val smemSize = smemWidth * smemDepth * smemBanks

  val numCores = muonTiles.length
  val numLanes = p(SIMTCoreKey).get.numLsuLanes

  val gemminis = gemminiTiles.map(_.gemmini)
  val gemminiConfigs = gemminis.map(_.config)
  gemminiConfigs.foreach { config =>
    assert(smemBanks == config.sp_banks && isPow2(smemBanks / config.sp_banks))
    assert(smemWidth >= (config.sp_width / 8) && isPow2(smemWidth / (config.sp_width / 8)))
    assert(smemSize == config.sp_capacity.asInstanceOf[CapacityInKilobytes].kilobytes * 1024)
  }
  if (gemminiConfigs.length > 1) {
    if (!(gemminiConfigs.tail.map(_.inputType == gemminiConfigs.head.inputType).reduce(_ && _))) {
      println("******** WARNING ********\n******** gemmini data types do not match\n******** WARNING ********")
    }
  }

  val strideByWord = smemKey.strideByWord
  val filterAligned = smemKey.filterAligned
  val serialization = smemKey.serialization
  implicit val disableMonitors: Boolean = smemKey.disableMonitors // otherwise it generate 1k+ different tl monitors

  assert(strideByWord, "radiance smem must stride by word")
  assert(!filterAligned, "this feature is only for virgo")

  // (core, lane) = rw node
  val muonSmemFanout: List[List[TLNode]] = muonTiles.zipWithIndex.map { case (tile, cid) =>
    tile.smemNodes.zipWithIndex.map { case (m, lid) =>
      val smemFanoutXbar = LazyModule(new TLXbar())
      smemFanoutXbar.suggestName(f"rad_smem_fanout_cl${clusterParams.clusterId}_c${cid}_l${lid}_xbar")
      smemFanoutXbar.node :=* AddressOrNode(clusterParams.baseAddr) :=* m
      smemFanoutXbar.node
    }.toList
  }.toList
  // (lane, core) = rw node
  val fanoutTransposed = muonSmemFanout.transpose

  val muonClcBusXbar = LazyModule(new TLXbar()).suggestName("muon_clc_xbar").node
  val muonClcBusClient = TLEphemeralNode()
  muonSmemFanout.flatten.foreach(muonClcBusXbar := _)
  muonClcBusClient := TLFragmenter(8, 8) := TLWidthWidget(4) := muonClcBusXbar

  val unalignedClients = extClients.map(connectOne(_, () => TLFragmenter(wordSize, 128)))

  // uniform mux select for selecting lanes from a single core in unison
  val prealignBufComponents = fanoutTransposed.zipWithIndex.map { case (coresRW, lid) =>
    val (xbar, xi, xo) = XbarWithExtPolicyNoFallback(Some(f"lane_${lid}_serial_in_xbar"))
    coresRW.foreach(xi := _)
    val policyNode = ExtPolicyMasterNode(numCores)
    xbar.policySlaveNode := policyNode
    val prealignBuffer = TLBuffer(BufferParams(smemKey.prealignBufDepth, false, false))
    prealignBuffer := xo

    (prealignBuffer, (xbar, xi, xo), policyNode)
  }

  val prealignBuffers = prealignBufComponents.map(_._1)
  val laneSerialXbars = prealignBufComponents.map(_._2)
  val coreSerialPolicy = prealignBufComponents.map(_._3)

  val alignmentXbar = LazyModule(new TLXbar()).suggestName("alignment_xbar").node
  guardMonitors { implicit p =>
    prealignBuffers.foreach(alignmentXbar := _)
  }

  def distAndDuplicate(nodesAndWidths: Seq[(TLNode, Int)], suffix: String): Seq[Seq[TLNexusNode]] = {
    val wordFanoutNodes = nodesAndWidths.zipWithIndex.map { case ((node, width), i) =>
      val spSubbanks = width / wordSize
      val dist = DistributorNode(from = width, to = wordSize)
      guardMonitors { implicit p =>
        dist := node
      }
      val fanout = Seq.tabulate(spSubbanks) { w =>
        // TODO: do we need this skid buffer
        val buf = TLBuffer(BufferParams(2, false, false), BufferParams(0))
        buf := dist
        connectXbarName(buf, Some(s"dist_fanout_$suffix${i}w${w}"))
      }
      Seq.fill(smemWidth / width)(fanout).flatten // smem wider than spad, duplicate masters
    }
    if (nodesAndWidths.isEmpty) {
      Seq.fill(smemSubbanks)(Seq())
    } else {
      // (gemmini, word) => (word, gemmini)
      wordFanoutNodes.transpose
    }
  }

  gemminis.foreach(g => require(g.spad.spad_writer.isDefined))

  // (banks, subbanks, gemminis)
  val spadReadNodes = Seq.fill(smemBanks) {
    distAndDuplicate(gemminis.map(g => (g.spad_read_nodes, g.config.sp_width / 8)), "gemmini_r")
  }
  // TODO: these nodes probably dont do anything, eliminate?
  val spadWriteNodes = Seq.fill(smemBanks) {
    distAndDuplicate(gemminis.map(g => (g.spad_write_nodes, g.config.sp_width / 8)), "gemmini_w")
  }
  val spadSpWriteNodesSingleBank = distAndDuplicate(
    gemminis.map(g => (g.spad.spad_writer.get.node, g.config.sp_width / 8)), "gemmini_ws")
  val spadSpWriteNodes = Seq.fill(smemBanks)(spadSpWriteNodesSingleBank) // executed only once

  val muonSplitterNodes = Seq.tabulate(smemSubbanks)(wid =>
    connectOne(alignmentXbar, () => RWSplitterNode(f"muon_aligned_splitter_$wid")))
  val muonAligned = Seq.tabulate(2)(_ => muonSplitterNodes.map(connectXbarName(_, Some("muon_aligned_fanout"))))

  val quantOutputWidth = gemminiTiles.flatMap(_.gemminiParams.requantizer
    .map(q => q.numOutputLanes * q.maxOutputBits / 8))
  val quantOutputNodesSingleBank = distAndDuplicate(
    gemminiTiles.flatMap(_.requantizerSmemClient).map(x =>
      (connectOne(x, () => AddressOrNode(clusterParams.baseAddr)), quantOutputWidth.head)
    ), "quant_w")
  val quantOutputNodes = Seq.fill(smemBanks)(quantOutputNodesSingleBank)

  // connect requantizer managers directly here TODO: move outside, make smemNodes xbars
  gemminiTiles.flatMap(_.requantizerMuonManager).foreach { qm =>
    val destBytes = qm.portParams.head.beatBytes
    require(2 * numLanes == destBytes, "requantizer input width mismatch: not lanes * 2B")
    // pack lanes into a single wide request per core
    val collectors = muonSmemFanout.map { lanesInCore =>
      val quantCollector = CollectorNode(2, destBytes)
      lanesInCore.foreach(quantCollector := TLWidthWidget(4) := _)
      quantCollector
    }
    // multiple cores, but only one requantizer
    val collectedXbar = LazyModule(new TLXbar()).suggestName("collected_xbar").node
    collectors.foreach(collectedXbar := _)
    qm := collectedXbar
  }

  val smemBusSplitterNodes = unalignedClients.map(connectOne(_, () => RWSplitterNode(f"smem_splitter")))

  // these nodes access an entire line simultaneously
  override val uniformRNodes: Seq[Seq[Seq[TLNexusNode]]] = spadReadNodes.map(grb => {
    (grb zip muonAligned.head).map { case (grw, mrw) => Seq(mrw) ++ grw }
  })
  override val uniformWNodes: Seq[Seq[Seq[TLNexusNode]]] =
    (spadWriteNodes lazyZip spadSpWriteNodes lazyZip quantOutputNodes).map { case (gwb, gwsb, qb) =>
      (gwb lazyZip gwsb lazyZip muonAligned.last lazyZip qb).map { case (gww, gwsw, mww, qw) =>
        Seq(mww) ++ gww ++ gwsw ++ qw
      }
    }

  // these nodes are random access
  override val nonuniformRNodes: Seq[TLNode] = smemBusSplitterNodes.map(connectXbarName(_, Some("rad_unaligned_r")))
  override val nonuniformWNodes: Seq[TLNode] = smemBusSplitterNodes.map(connectXbarName(_, Some("rad_unaligned_w")))
  override val clcbusClients: Seq[TLNode] = Seq(muonClcBusClient)
}

class RadianceSharedMemComponentsImp[T <: RadianceSharedMemComponents]
  (override val outer: T) extends RadianceSmemNodeProviderImp[T](outer) {

  val xbars = outer.laneSerialXbars
  val policies = outer.coreSerialPolicy
  // for each lane, if any core is valid
  val coreValids = xbars.map(_._2.in.map(_._1)).transpose.map { core => VecInit(core.map(_.a.valid)).asUInt.orR }
  val select = xbars.map(_._3.in.map(_._1)).transpose.map { core => VecInit(core.map(_.a.fire)).asUInt.orR }
  val coreSelect = TLArbiter.roundRobin(outer.numCores, VecInit(coreValids).asUInt, VecInit(select).asUInt.orR)
  // TODO: roll this into XbarWithExtPolicy
  xbars.foreach { lane =>
    (lane._2.in.map(_._1) lazyZip lane._2.out.map(_._1) lazyZip coreSelect.asBools).foreach { case (li, lo, cs) =>
      lo.a.valid := li.a.valid && cs
    }
  }
  policies.foreach { _.out.head._1.hint := coreSelect }
}
