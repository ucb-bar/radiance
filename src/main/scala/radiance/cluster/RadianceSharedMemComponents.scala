package radiance.cluster

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, BufferParams}
import freechips.rocketchip.tilelink._
import gemmini._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.DisableMonitors
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory._
import radiance.subsystem._

// virgo-specific tilelink nodes
// generic smem implementation is in RadianceSharedMem.scala
class RadianceSharedMemComponents(
  clusterParams: RadianceClusterParams,
  gemminiTiles: Seq[GemminiTileLike],
  muonTiles: Seq[MuonTileLike],
  extClients: Seq[TLNode] = Seq(),
)(implicit p: Parameters) extends RadianceSmemNodeProvider  {
  val smemKey = clusterParams.smemConfig

  val wordSize = smemKey.wordSize
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
      smemFanoutXbar.node :=* DisableMonitors { implicit p =>
        AddressOrNode(clusterParams.baseAddr) :=* m
      }
      smemFanoutXbar.node
    }.toList
  }.toList
  // (lane, core) = rw node
  val fanoutTransposed = muonSmemFanout.transpose

  val muonClcBusXbar = LazyModule(new TLXbar()).suggestName("muon_clc_xbar").node
  val muonClcBusClient = TLEphemeralNode()
  muonSmemFanout.flatten.foreach(muonClcBusXbar := _)
  (muonClcBusClient
    := TLFragmenter(8, 8)
    := TLWidthWidget(4) // muons see 4B managers instead of 8B for cbus
    := TLSourceShrinker(smemKey.controlInFlights) // cap sources for cbus ops
    := muonClcBusXbar)

  val unalignedClients = extClients.map(connectOne(_, () => TLFragmenter(wordSize, 128)))

  val (prealignNodes, laneSerialXbars, coreSerialPolicy) = serialization match {
    case CoreSerialized =>
      // uniform mux select for selecting lanes from a single core in unison
      val components = fanoutTransposed.zipWithIndex.map { case (coresRW, lid) =>
        val prealignBuffer = TLBuffer(BufferParams(smemKey.prealignBufDepth, false, false))

        val (xbar, xi, xo) = XbarWithExtPolicyNoFallback(Some(f"lane_${lid}_serial_in_xbar"))
        coresRW.foreach(xi := _)
        val policyNode = ExtPolicyMasterNode(numCores)
        xbar.policySlaveNode := policyNode
        prealignBuffer := xo

        (prealignBuffer, (xbar, xi, xo), policyNode)
      }
      // coalesce single addresses
      val prealignNodes = if (true) {
        val (coalescerAdapter, coalescerClient) = SingleAddrCoalescer(3)
        components.foreach(x => coalescerAdapter := x._1)
        Seq(coalescerAdapter, coalescerClient)
      } else {
        components.map(_._1)
      }
      (prealignNodes, Some(components.map(_._2)), Some(components.map(_._3)))

    case FullySerialized =>
      val fullSerializationXbar = LazyModule(new TLXbar()).suggestName("serial_xbar").node
      fanoutTransposed.flatten.foreach(fullSerializationXbar := _)
      (List(connectIdentity(fullSerializationXbar)), None, None)

    case NotSerialized =>
      (fanoutTransposed.flatten.map(connectEphemeral(_)), None, None)
  }

  val alignmentXbar = LazyModule(new TLXbar()).suggestName("alignment_xbar").node
  guardMonitors { implicit p =>
    prealignNodes.foreach(alignmentXbar :=* _)
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
    distAndDuplicate(gemminis.map(g => (g.spad_read_nodes, g.config.sp_width_projected / 8)), "gemmini_r")
  }
  // TODO: these nodes probably dont do anything, eliminate?
  val spadWriteNodes = Seq.fill(smemBanks) {
    distAndDuplicate(gemminis.map(g => (g.spad_write_nodes, g.config.sp_width_projected / 8)), "gemmini_w")
  }
  val spadSpWriteNodesSingleBank = distAndDuplicate(
    gemminis.map { g =>
      val shrunk = TLWidthWidget(smemWidth)
      shrunk := TLFragmenter(smemWidth, smemWidth) := g.spad.spad_writer.get.node
      (shrunk, g.config.max_spad_writer_bytes min smemWidth)
    }, "gemmini_ws")
  val spadSpWriteNodes = Seq.fill(smemBanks)(spadSpWriteNodesSingleBank) // executed only once

  val preSplitterNodes = Seq.fill(smemSubbanks)(connectIdentity(alignmentXbar))
  val muonSplitterNodes = preSplitterNodes
    .map(connectOne(_, () => RWSplitterNode(f"muon_aligned_splitter")))
  val muonAligned = Seq.fill(2)(muonSplitterNodes.map(connectXbarName(_, Some("muon_aligned_fanout"))))

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

  outer.serialization match {
    case CoreSerialized =>
      val xbars = outer.laneSerialXbars.get // (xbar, xi, xo)

      val xbarInNodes = xbars.map(_._2.in.map(_._1)) // (lane, core) => xi
      val xbarOutNodes = xbars.map(_._3.in.map(_._1)) // (lane, core) => xo

      // the shared memory supports random subbank access for 1 core at a time
      // (i.e. core-serial access)

      // for each core, if any lane is valid, that core is up for arbitration
      val anyLaneValidInCore = WireInit(VecInit(xbarInNodes.transpose // (core, lane) => xi
        .map(lanes => VecInit(lanes.map(_.a.valid)).asUInt.orR)))

      dontTouch(anyLaneValidInCore)

      // a core-serial crossbar fires if any core sent something through
      val laneOutFire = WireInit(VecInit(xbarOutNodes // (lane, ??) => xo
        .map(laneOuts => VecInit(laneOuts.map(_.a.fire)).asUInt.orR)))
      dontTouch(laneOutFire)

      require(anyLaneValidInCore.length == outer.numCores)
      require(laneOutFire.length == outer.numLanes)

      // we consider any lane fire as arbiter fire (at least one lane of the chosen core fired)
      val coreSelect = TLArbiter.roundRobin(
        outer.numCores,
        anyLaneValidInCore.asUInt,
        laneOutFire.asUInt.orR)

      // TODO: roll this into XbarWithExtPolicy
      xbars.foreach { lane =>
        (lane._2.in.map(_._1) lazyZip lane._2.out.map(_._1) lazyZip coreSelect.asBools).foreach { case (li, lo, cs) =>
          lo.a.valid := li.a.valid && cs
        }
      }
      outer.coreSerialPolicy.get.foreach { _.out.head._1.hint := coreSelect }
    case _ =>
  }
}
