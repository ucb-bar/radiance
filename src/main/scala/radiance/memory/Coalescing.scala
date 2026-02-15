// See LICENSE.SiFive for license details.

package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressRange, AddressSet, IdRange}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.unittest._
import freechips.rocketchip.util.{BundleField, MultiPortQueue}
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.subsystem.SIMTCoreKey

case class CoalXbarParam()

case object CoalescerKey
    extends Field[Option[CoalescerConfig]](None /*default*/ )
case object CoalXbarKey extends Field[Option[CoalXbarParam]](None /*default*/ )

// Simplified config for spatial-only coalescing
case class CoalescerConfig(
  enable: Boolean,        // globally enable or disable coalescing
  numLanes: Int,          // number of lanes (or threads) in a warp
  addressWidth: Int,      // assume <= 32
  dataBusWidth: Int,      // memory-side downstream TileLink data bus size
  coalLogSize: Int,       // coalescer size (log of byte size)
  wordSizeInBytes: Int,   // word size of the request that each lane makes
  numOldSrcIds: Int,      // num of outstanding requests per lane, from processor
  numNewSrcIds: Int,      // num of outstanding coalesced requests
  respQueueDepth: Int,    // depth of the response fifo queues
  numCoalReqs: Int,       // total number of coalesced requests we can generate in one cycle
) {
  def maxCoalLogSize: Int = {
    require(
      coalLogSize <= dataBusWidth,
      "multi-beat coalesced reads/writes are currently not supported"
    )
    if (coalLogSize < dataBusWidth) {
      println(
        "======== Warning: coalescer's max coalescing size is set to " +
          s"${coalLogSize}, which is narrower than data bus width " +
          s"${dataBusWidth}.  This might indicate misconfiguration."
      )
    }
    coalLogSize
  }
  def wordSizeWidth: Int = {
    require(isPow2(wordSizeInBytes))
    log2Ceil(log2Ceil(wordSizeInBytes) + 1)
  }
}

object DefaultCoalescerConfig extends CoalescerConfig(
  enable = true,
  numLanes = 4,
  addressWidth = 24,
  dataBusWidth = 4,      // if "4": 2^4=16 bytes, 128 bit bus
  coalLogSize = 4,       // if "4": 2^4=16 bytes, 128 bit bus
  wordSizeInBytes = 4,
  numOldSrcIds = 8,
  numNewSrcIds = 8,
  respQueueDepth = 4,
  numCoalReqs = 1,
)

class CoalescingUnit(config: CoalescerConfig)(implicit p: Parameters) extends LazyModule {
  // NexusNode: n inputs (CPU lanes) -> n+1 outputs (n non-coal + 1 coal)
  val nexusNode = TLNexusNode(
    clientFn = { seq =>
      require(seq.length == config.numLanes, 
        s"Expected ${config.numLanes} client ports, got ${seq.length}")

      TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
        name = "coalesced_node",
        sourceId = IdRange(0, config.numNewSrcIds)
      )))
    },
    managerFn = { seq =>
      val addrRanges = AddressRange.fromSets(seq.flatMap(_.slaves.flatMap(_.address)))
      require(addrRanges.length == 1)
      seq.head.v1copy(
        responseFields = BundleField.union(seq.flatMap(_.responseFields)),
        beatBytes = 4, // hardcoded to be word-sized
        requestKeys = seq.flatMap(_.requestKeys).distinct,
        minLatency = seq.map(_.minLatency).min,
        endSinkId = 0, // dont support TLC
        managers = Seq(TLSlaveParameters.v2(
          name = Some(s"coalescer_manager"),
          address = addrRanges.map(r => AddressSet(r.base, r.size - 1)),
          // supports = seq.map(_.anySupportClaims).reduce(_ mincover _),
          supports = TLMasterToSlaveTransferSizes(
            get = seq.flatMap(_.slaves.map(_.supportsGet)).reduce(_ mincover _),
            putFull = seq.flatMap(_.slaves.map(_.supportsPutFull)).reduce(_ mincover _),
            putPartial = seq.flatMap(_.slaves.map(_.supportsPutPartial)).reduce(_ mincover _),
          ),
          fifoId = Some(0),
        )),
      )
    }
  )

  // using a single nexus node unfortunately necessitates one of two things:
  // 1. assign per-lane source ranges and then doing manual routing for non coalesced lanes
  //    in the nexus node imp for responses, like a xbar, and exposing just one master
  // 2. declare disjoint source ranges but don't actually assign disjoint source ids, instead
  //    relying on a subsequent tlxbar to handle routing. this will duplicate bits in the
  //    source id that pertains to lane id
  // this is because we have to use single port multiple masters, instead of multiple ports
  // single master with nexusnode. we prioritize a narrower source id here, so the workaround
  // is to provide multiple ports each with a single master by creating a bunch of clientnodes.
  val passthroughNodes = Seq.tabulate(config.numLanes) { i =>
    TLClientNode(Seq(TLMasterPortParameters.v2(
      masters = Seq(TLMasterParameters.v2(
        name = s"noncoalesced_lane_$i",
        sourceId = IdRange(0, config.numOldSrcIds)
      ))
    )))
  }

  lazy val module = new CoalescingUnitImp(this, config)
}

// Protocol-agnostic bundles
class Request(
    sourceWidth: Int,
    sizeWidth: Int,
    addressWidth: Int,
    dataWidth: Int
) extends Bundle {
  require(
    dataWidth % 8 == 0,
    s"dataWidth (${dataWidth} bits) is not multiple of 8"
  )
  val op = Bool() // 0=READ 1=WRITE
  val address = UInt(addressWidth.W)
  val size = UInt(sizeWidth.W)
  val source = UInt(sourceWidth.W)
  val mask = UInt((dataWidth / 8).W) // write only
  val data = UInt(dataWidth.W) // write only

  def toTLA(edgeOut: TLEdgeOut): (Bool, TLBundleA) = {
    val (plegal, pbits) = edgeOut.Put(
      fromSource = this.source,
      toAddress = this.address,
      lgSize = this.size,
      data = this.data,
      mask = this.mask
    )
    val (glegal, gbits) = edgeOut.Get(
      fromSource = this.source,
      toAddress = this.address,
      lgSize = this.size
    )
    val legal = Mux(this.op.asBool, plegal, glegal)
    val bits = Mux(this.op.asBool, pbits, gbits)
    (legal, bits)
  }
}

case class NonCoalescedRequest(config: CoalescerConfig)
    extends Request(
      sourceWidth = log2Ceil(config.numOldSrcIds),
      sizeWidth = config.wordSizeWidth,
      addressWidth = config.addressWidth,
      dataWidth = config.wordSizeInBytes * 8
    )

case class CoalescedRequest(config: CoalescerConfig)
    extends Request(
      sourceWidth = log2Ceil(config.numNewSrcIds),
      sizeWidth = log2Ceil(config.maxCoalLogSize + 1),
      addressWidth = config.addressWidth,
      dataWidth = (8 * (1 << config.maxCoalLogSize))
    )

class Response(sourceWidth: Int, sizeWidth: Int, dataWidth: Int)
    extends Bundle {
  require(
    dataWidth % 8 == 0,
    s"dataWidth (${dataWidth} bits) is not multiple of 8"
  )
  val op = UInt(1.W) // 0=READ 1=WRITE
  val size = UInt(sizeWidth.W)
  val source = UInt(sourceWidth.W)
  val data = UInt(dataWidth.W) // read only
  val error = Bool()

  def toTLD(edgeIn: TLEdgeIn): TLBundleD = {
    val apBits = edgeIn.AccessAck(
      toSource = this.source,
      lgSize = this.size
    )
    val agBits = edgeIn.AccessAck(
      toSource = this.source,
      lgSize = this.size,
      data = this.data
    )
    Mux(this.op.asBool, apBits, agBits)
  }

  def fromTLD(bundle: TLBundleD, checkOpcode: Bool): Unit = {
    this.source := bundle.source
    this.op := TLUtils.DOpcodeIsStore(bundle.opcode, checkOpcode)
    this.size := bundle.size
    this.data := bundle.data
    this.error := bundle.denied
  }
}

case class NonCoalescedResponse(config: CoalescerConfig)
    extends Response(
      sourceWidth = log2Ceil(config.numOldSrcIds),
      sizeWidth = config.wordSizeWidth,
      dataWidth = config.wordSizeInBytes * 8
    )

case class CoalescedResponse(config: CoalescerConfig)
    extends Response(
      sourceWidth = log2Ceil(config.numNewSrcIds),
      sizeWidth = log2Ceil(config.maxCoalLogSize),
      dataWidth = (8 * (1 << config.maxCoalLogSize))
    )

class SourceGenerator[T <: Data](
    sourceWidth: Int,
    metadata: Option[T] = None,
    ignoreInUse: Boolean = false
) extends Module {
  val io = IO(new Bundle {
    val gen = Input(Bool())
    val reclaim = Input(Valid(UInt(sourceWidth.W)))
    val id = Output(Valid(UInt(sourceWidth.W)))
    val meta = metadata.map(Input(_))
    val peek = metadata.map(Output(_))
    val inflight = Output(Bool())
  })
  val head = RegInit(UInt(sourceWidth.W), 0.U)
  head := Mux(io.gen, head + 1.U, head)

  val outstanding = RegInit(UInt((sourceWidth + 1).W), 0.U)
  io.inflight := (outstanding > 0.U) || io.gen

  val numSourceId = 1 << sourceWidth
  val occupancyTable = Mem(numSourceId, Bool())
  val metadataTable = metadata.map(Mem(numSourceId, _))
  
  when(reset.asBool) {
    (0 until numSourceId).foreach { occupancyTable(_) := false.B }
  }
  
  val frees = (0 until numSourceId).map(!occupancyTable(_))
  val lowestFree = PriorityEncoder(frees)
  val lowestFreeValid = occupancyTable(lowestFree)

  io.id.valid := (if (ignoreInUse) true.B else !lowestFreeValid)
  io.id.bits := lowestFree
  
  when(io.gen && io.id.valid) {
    when (!io.reclaim.valid || io.reclaim.bits =/= io.id.bits) {
      occupancyTable(io.id.bits) := true.B
      metadataTable.foreach { table =>
        table(io.id.bits) := io.meta.get
      }
    }
  }
  
  when(io.reclaim.valid) {
    occupancyTable(io.reclaim.bits) := false.B
  }


  metadataTable.foreach { table =>
    io.peek.get := table(io.reclaim.bits)
  }

  when(io.gen && io.id.valid) {
    when(!io.reclaim.valid) {
      assert(outstanding < (1 << sourceWidth).U)
      outstanding := outstanding + 1.U
    }
  }.elsewhen(io.reclaim.valid) {
    assert(outstanding > 0.U,
           "Over-reclaim. Did some responses get dropped?")
    outstanding := outstanding - 1.U
  }
  dontTouch(outstanding)
}

object SourceGenerator {
  def apply(node: TLBundle) = {
    val sourceGen = Module(new SourceGenerator(node.params.sourceBits))
    sourceGen.io.gen := node.a.fire
    node.a.bits.source := sourceGen.io.id.bits
    sourceGen.io.reclaim.valid := node.d.fire
    sourceGen.io.reclaim.bits := node.d.bits.source

    (sourceGen.io.id.valid, sourceGen.io.inflight)
  }
}

// Simplified spatial-only coalescer - processes one warp per cycle
class Coalescer(config: CoalescerConfig) extends Module {
  val io = IO(new Bundle {
    val requests = Flipped(Vec(config.numLanes, Valid(new NonCoalescedRequest(config))))
    val coalReq = DecoupledIO(new CoalescedRequest(config))
    val consumed = Output(Vec(config.numLanes, Bool()))
  })

  val size = config.coalLogSize
  val addrMask = (((1L << config.addressWidth) - 1) - ((1 << size) - 1)).U

  def canMatch(req0: Request, req0v: Bool, req1: Request, req1v: Bool): Bool =
    (req0.op === req1.op) &&
    (req0v && req1v) &&
    ((req0.address & addrMask) === (req1.address & addrMask))

  val matchTablePerLane = io.requests.map { leader =>
    io.requests.map { follower =>
      canMatch(follower.bits, follower.valid, leader.bits, leader.valid)
    }
  }

  val matchCounts = matchTablePerLane.map(table => PopCount(table))
  val canCoalesce = matchCounts.map(_ > 1.U)

  val chosenLeaderIdx = PriorityEncoder(canCoalesce)
  val chosenLeader = VecInit(io.requests.map(_.bits))(chosenLeaderIdx)
  val chosenMatches = VecInit(matchTablePerLane.map(VecInit(_).asUInt))(chosenLeaderIdx)
  val chosenMatchCount = VecInit(matchCounts)(chosenLeaderIdx)

  // Build coalesced request
  def getOffsetSlice(addr: UInt) = addr(size - 1, config.wordSizeWidth)
  
  val maxWords = 1 << (config.maxCoalLogSize - config.wordSizeWidth)
  val data = Wire(Vec(maxWords, UInt((config.wordSizeInBytes * 8).W)))
  val mask = Wire(Vec(maxWords, UInt(config.wordSizeInBytes.W)))

  for (i <- 0 until maxWords) {
    val sel = (io.requests zip chosenMatches.asBools).map { case (req, m) =>
      m && ((req.bits.address(config.maxCoalLogSize - 1, config.wordSizeWidth)) === i.U)
    }
    data(i) := MuxCase(DontCare, (io.requests zip sel).map { case (req, s) => s -> req.bits.data })
    mask(i) := MuxCase(0.U, (io.requests zip sel).map { case (req, s) => s -> req.bits.mask })
  }

  val shouldCoalesce = chosenMatchCount > 1.U && config.enable.B && io.requests.map(_.valid).reduce(_ || _)
  
  // Output coalesced request directly (no register - downstream queue handles it)
  io.coalReq.valid := shouldCoalesce
  io.coalReq.bits.source := DontCare
  io.coalReq.bits.mask := mask.asUInt
  io.coalReq.bits.data := data.asUInt
  io.coalReq.bits.size := size.U
  io.coalReq.bits.address := chosenLeader.address & addrMask
  io.coalReq.bits.op := chosenLeader.op

  // Consumed signal: only assert when coalescing actually happens (fires)
  io.consumed := VecInit(chosenMatches.asBools.map(_ && shouldCoalesce && io.coalReq.ready))

  if (!config.enable) {
    io.coalReq.valid := false.B
    io.consumed.foreach(_ := false.B)
  }
}

class CoalescerSourceGen(
    config: CoalescerConfig,
    coalReqT: CoalescedRequest,
    respT: TLBundleD
) extends Module {
  val io = IO(new Bundle {
    val inReq = Flipped(Decoupled(coalReqT.cloneType))
    val outReq = Decoupled(coalReqT.cloneType)
    val inResp = Decoupled(respT.cloneType)
    val outResp = Flipped(Decoupled(respT.cloneType))
  })
  
  val sourceGen = Module(
    new SourceGenerator(log2Ceil(config.numNewSrcIds), ignoreInUse = false)
  )
  sourceGen.io.gen := io.outReq.fire
  sourceGen.io.reclaim.valid := io.outResp.fire
  sourceGen.io.reclaim.bits := io.outResp.bits.source

  io.outReq <> io.inReq
  io.inResp <> io.outResp

  io.inReq.ready := io.outReq.ready && sourceGen.io.id.valid
  io.outReq.valid := io.inReq.valid && sourceGen.io.id.valid
  io.outReq.bits.source := sourceGen.io.id.bits
}

class CoalescingUnitImp(outer: CoalescingUnit, config: CoalescerConfig)
    extends LazyModuleImp(outer) {
  println(s"CoalescingUnit instantiated with config: {")
  println(s"    enable: ${config.enable}")
  println(s"    numLanes: ${config.numLanes}")
  println(s"    wordSizeInBytes: ${config.wordSizeInBytes}")
  println(s"    coalLogSize: ${config.coalLogSize}")
  println(s"    numOldSrcIds: ${config.numOldSrcIds}")
  println(s"    numNewSrcIds: ${config.numNewSrcIds}")
  println(s"    respQueueDepth: ${config.respQueueDepth}")
  println(s"    addressWidth: ${config.addressWidth}")
  println(s"}")

  require(
    outer.nexusNode.in.length == config.numLanes,
    s"number of incoming edges (${outer.nexusNode.in.length}) is not the same as " +
      s"config.numLanes (${config.numLanes})"
  )

  val oldSourceWidth = outer.nexusNode.in.head._1.params.sourceBits
  val nonCoalReqT = new NonCoalescedRequest(config)
  val coalReqT = new CoalescedRequest(config)
  val coalRespT = new CoalescedResponse(config)

  // Spatial-only coalescer
  val coalescer = Module(new Coalescer(config))
  val inflightTable = Module(
    new InFlightTable(config, nonCoalReqT, coalReqT, coalRespT)
  )
  val uncoalescer = Module(new Uncoalescer(config, inflightTable.entryT))

  // Request flow - buffer requests per lane
  val reqBuffers = Seq.fill(config.numLanes)(Module(new Queue(nonCoalReqT, 2, pipe=true)))
  
  // INPUT: Receive from nexusNode inputs - NO source ID remapping needed
  (outer.nexusNode.in zip reqBuffers).zipWithIndex.foreach {
    case (((tlIn, edgeIn), reqBuf), lane) =>
      val req = Wire(nonCoalReqT)
      req.op := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode, tlIn.a.fire)
      // Use local source ID directly - no remapping
      req.source := tlIn.a.bits.source
      req.address := tlIn.a.bits.address
      req.data := tlIn.a.bits.data
      req.size := tlIn.a.bits.size
      req.mask := tlIn.a.bits.mask

      reqBuf.io.enq.valid := tlIn.a.valid
      reqBuf.io.enq.bits := req
      tlIn.a.ready := reqBuf.io.enq.ready
  }
  
  // Present all buffered requests to coalescer
  reqBuffers.zipWithIndex.foreach { case (reqBuf, lane) =>
    coalescer.io.requests(lane).valid := reqBuf.io.deq.valid
    coalescer.io.requests(lane).bits := reqBuf.io.deq.bits
  }
  
  // OUTPUT: Non-coalesced passthrough to nexusNode outputs (lanes 0 to n-1)
  // NO source ID remapping - use local IDs
  (outer.passthroughNodes.flatMap(_.out) zip reqBuffers).zipWithIndex.foreach {
    case (((tlOut, edgeOut), reqBuf), lane) =>
      val shouldPassthrough = reqBuf.io.deq.valid && !coalescer.io.consumed(lane)
      
      tlOut.a.valid := shouldPassthrough
      val (legal, tlBits) = reqBuf.io.deq.bits.toTLA(edgeOut)
      tlOut.a.bits := tlBits
      
      reqBuf.io.deq.ready := coalescer.io.consumed(lane) || (shouldPassthrough && tlOut.a.ready)
      
      when(tlOut.a.fire) {
        assert(legal, "unhandled illegal TL req gen")
      }
  }

  // OUTPUT: Coalesced requests to nexusNode output (lane n)
  val (tlCoal, edgeCoal) = outer.nexusNode.out.head

  val coalSourceGen = Module(
    new CoalescerSourceGen(config, coalReqT, tlCoal.d.bits)
  )
  coalSourceGen.io.inReq <> coalescer.io.coalReq

  inflightTable.io.inCoalReq <> coalSourceGen.io.outReq
  inflightTable.io.requests := coalescer.io.requests
  inflightTable.io.consumed := coalescer.io.consumed

  val coalReqQueue = Module(new Queue(coalReqT, 2))
  coalReqQueue.io.enq <> inflightTable.io.outCoalReq
  
  val coalReq = coalReqQueue.io.deq
  coalReq.ready := tlCoal.a.ready
  tlCoal.a.valid := coalReq.valid
  val (legal, tlBits) = coalReq.bits.toTLA(edgeCoal)
  tlCoal.a.bits := tlBits
  
  when(tlCoal.a.fire) {
    assert(legal, "unhandled illegal TL req gen")
  }

  tlCoal.b.ready := true.B
  tlCoal.c.valid := false.B
  tlCoal.e.valid := false.B

  // Response flow - NO source ID remapping
  val respQueueEntryT = new Response(
    oldSourceWidth,
    log2Ceil(config.maxCoalLogSize),
    (1 << config.maxCoalLogSize) * 8
  )
  
  val respQueues = Seq.tabulate(config.numLanes) { _ =>
    Module(new MultiPortQueue(respQueueEntryT, 2, 1, 2, config.respQueueDepth, flow = false))
  }

  // INPUT: Responses from nexusNode outputs (non-coalesced, lanes 0 to n-1)
  // NO source ID remapping - responses use local IDs
  (outer.nexusNode.in zip outer.passthroughNodes.flatMap(_.out)).zipWithIndex.foreach {
    case (((tlIn, edgeIn), (tlOut, _)), lane) =>
      val respQueue = respQueues(lane)
      val resp = Wire(respQueueEntryT)
      // Use local source ID directly - no remapping
      resp.fromTLD(tlOut.d.bits, tlOut.d.fire)

      respQueue.io.enq(0).valid := tlOut.d.valid
      respQueue.io.enq(0).bits := resp
      respQueue.io.deq.head.ready := tlIn.d.ready

      tlIn.d.valid := respQueue.io.deq.head.valid
      tlIn.d.bits := respQueue.io.deq.head.bits.toTLD(edgeIn)
      tlOut.d.ready := respQueue.io.enq(0).ready
  }

  // INPUT: Coalesced responses from nexusNode output (lane n)
  // Use local source ID for coalesced responses too
  uncoalescer.io.coalResp.valid := coalSourceGen.io.inResp.valid
  uncoalescer.io.coalResp.bits.fromTLD(coalSourceGen.io.inResp.bits, coalSourceGen.io.inResp.fire)
  coalSourceGen.io.inResp.ready := uncoalescer.io.coalResp.ready

  uncoalescer.io.inflightLookup <> inflightTable.io.lookupResult
  inflightTable.io.lookupSourceId.valid := coalSourceGen.io.inResp.valid
  inflightTable.io.lookupSourceId.bits := coalSourceGen.io.inResp.bits.source

  (respQueues zip uncoalescer.io.respQueueIO).foreach {
    case (q, uncoalResp) =>
      q.io.enq(1) <> uncoalResp.head
  }

  coalSourceGen.io.outResp <> tlCoal.d
}

class Uncoalescer(
    config: CoalescerConfig,
    inflightEntryT: InFlightTableEntry
) extends Module {
  val io = IO(new Bundle {
    val inflightLookup = Flipped(Decoupled(inflightEntryT))
    val coalResp = Flipped(Decoupled(new CoalescedResponse(config)))
    val respQueueIO = Vec(config.numLanes, Vec(1, Decoupled(new NonCoalescedResponse(config))))
  })

  def getCoalescedDataChunk(
      data: UInt,
      dataWidth: Int,
      offset: UInt,
      logSize: UInt
  ): UInt = {
    val sizeInBits = ((1.U << logSize) << 3.U).asUInt
    assert(
      (dataWidth > 0).B && (dataWidth.U % sizeInBits === 0.U),
      cf"coalesced data width ($dataWidth) not evenly divisible by core req size ($sizeInBits)"
    )

    val numChunks = dataWidth / 32
    val chunks = Wire(Vec(numChunks, UInt(32.W)))
    (chunks.zipWithIndex).foreach { case (c, o) =>
      c := data(32 * (o + 1) - 1, 32 * o)
    }
    chunks(offset)
  }

  val coalRespPipeRegDeq = Queue(io.coalResp, 1, pipe = true)
  val tablePipeRegDeq = Queue(io.inflightLookup, 1, pipe = true)

  val uncoalPipeRegs = Seq.fill(config.numLanes)(
    Seq.fill(1)(Module(new Queue(new NonCoalescedResponse(config), 1, pipe = true)))
  )
  
  val allUncoalPipelineRegsReady =
    uncoalPipeRegs.map(_.map(_.io.enq.ready).reduce(_ && _)).reduce(_ && _)

  tablePipeRegDeq.ready := allUncoalPipelineRegsReady
  coalRespPipeRegDeq.ready := allUncoalPipelineRegsReady

  assert(
    io.coalResp.fire === io.inflightLookup.fire,
    "enqueue timing for uncoalescer pipeline registers out-of-sync!"
  )
  assert(
    tablePipeRegDeq.fire === coalRespPipeRegDeq.fire,
    "dequeue timing for uncoalescer pipeline registers out-of-sync!"
  )

  val tableRow = tablePipeRegDeq
  (uncoalPipeRegs zip tableRow.bits.lanes).foreach {
    case (laneRegs, tableLane) =>
      val pipeReg = laneRegs.head
      val tableReq = tableLane
      val enqIO = pipeReg.io.enq

      enqIO.valid := tableRow.fire && tableReq.valid
      enqIO.bits.op := tableReq.op
      enqIO.bits.source := tableReq.source
      enqIO.bits.size := tableReq.size
      enqIO.bits.data := getCoalescedDataChunk(
        coalRespPipeRegDeq.bits.data,
        coalRespPipeRegDeq.bits.data.getWidth,
        tableReq.offset,
        tableReq.size
      )
      enqIO.bits.error := DontCare
  }

  (io.respQueueIO zip uncoalPipeRegs).foreach {
    case (laneQueue, laneRegs) =>
      (laneQueue zip laneRegs).foreach {
        case (respQIO, reg) => respQIO <> reg.io.deq
      }
  }
}

class InFlightTable(
    config: CoalescerConfig,
    nonCoalReqT: NonCoalescedRequest,
    coalReqT: CoalescedRequest,
    coalRespT: CoalescedResponse
) extends Module {
  val offsetBits = config.maxCoalLogSize - config.wordSizeWidth
  val entryT = new InFlightTableEntry(
    config.numLanes,
    log2Ceil(config.numOldSrcIds),
    log2Ceil(config.numNewSrcIds),
    config.maxCoalLogSize,
    config.wordSizeWidth
  )
  val entries = config.numNewSrcIds
  val newSourceWidth = log2Ceil(config.numNewSrcIds)

  val io = IO(new Bundle {
    val inCoalReq = Flipped(Decoupled(coalReqT))
    val requests = Input(Vec(config.numLanes, Valid(nonCoalReqT)))
    val consumed = Input(Vec(config.numLanes, Bool()))
    val outCoalReq = Decoupled(coalReqT)
    val lookupSourceId = Input(Valid(UInt(newSourceWidth.W)))
    val lookupResult = Decoupled(entryT)
  })

  val table = Mem(entries, new Bundle {
    val valid = Bool()
    val bits = entryT.cloneType
  })

  when(reset.asBool) {
    (0 until entries).foreach { i =>
      table(i).valid := false.B
      table(i).bits.lanes.foreach { l =>
        l := 0.U.asTypeOf(l.cloneType)
      }
    }
  }

  val full = Wire(Bool())
  full := (0 until entries).map(table(_).valid).reduce(_ && _)
  dontTouch(full)

  def generateInflightTableEntry: InFlightTableEntry = {
    val newEntry = Wire(entryT)
    newEntry.source := io.inCoalReq.bits.source
    
    (newEntry.lanes zip io.consumed).zipWithIndex.foreach {
      case ((laneEntry, consumed), lane) =>
        val req = io.requests(lane).bits
        laneEntry.valid := consumed
        laneEntry.op := req.op
        laneEntry.source := req.source
        laneEntry.offset := ((req.address % (1 << config.maxCoalLogSize).U) >> config.wordSizeWidth)
        laneEntry.size := req.size
    }
    dontTouch(newEntry)
    newEntry
  }

  io.outCoalReq <> io.inCoalReq

  val enqReady = !full
  val enqFire = enqReady && io.inCoalReq.valid && io.outCoalReq.ready
  val enqSource = io.inCoalReq.bits.source
  
  when(enqFire) {
    val entryToWrite = table(enqSource)
    assert(!entryToWrite.valid, "tried to enqueue to an already occupied entry")
    entryToWrite.valid := true.B
    entryToWrite.bits := generateInflightTableEntry
  }

  io.lookupResult.valid := io.lookupSourceId.valid && table(io.lookupSourceId.bits).valid
  io.lookupResult.bits := table(io.lookupSourceId.bits).bits
  
  when(io.lookupSourceId.valid) {
    assert(
      table(io.lookupSourceId.bits).valid === true.B,
      "table lookup with a valid sourceId failed"
    )
    assert(
      !(enqFire && io.lookupResult.fire && (enqSource === io.lookupSourceId.bits)),
      "inflight table: enqueueing and looking up the same srcId at the same cycle is not handled"
    )
  }
  
  when(io.lookupResult.fire) {
    table(io.lookupSourceId.bits).valid := false.B
  }

  dontTouch(io.lookupResult)
}

class InFlightTableEntry(
    val numLanes: Int,
    val oldSourceWidth: Int,
    val newSourceWidth: Int,
    val offsetBits: Int,
    val sizeBits: Int,
) extends Bundle {
  class PerLane extends Bundle {
    val valid = Bool()
    val op = Bool() // 0=READ 1=WRITE
    val source = UInt(oldSourceWidth.W)
    val offset = UInt(offsetBits.W)
    val size = UInt(sizeBits.W)
  }
  
  val source = UInt(newSourceWidth.W)
  val lanes = Vec(numLanes, new PerLane)
}

object TLUtils {
  def AOpcodeIsStore(opcode: UInt, checkOpcode: Bool): Bool = {
    // 0: PutFullData, 1: PutPartialData, 4: Get
    when(checkOpcode) {
      assert(
        opcode === TLMessages.PutFullData || opcode === TLMessages.PutPartialData ||
          opcode === TLMessages.Get,
        "unhandled TL A opcode found"
      )
    }
    Mux(
      opcode === TLMessages.PutFullData || opcode === TLMessages.PutPartialData,
      true.B,
      false.B
    )
  }
  def DOpcodeIsStore(opcode: UInt, checkOpcode: Bool): Bool = {
    when(checkOpcode) {
      assert(
        opcode === TLMessages.AccessAck || opcode === TLMessages.AccessAckData,
        "unhandled TL D opcode found"
      )
    }
    Mux(opcode === TLMessages.AccessAck, true.B, false.B)
  }
}

/* TESTING / DEBUGGING */

// `traceHasSource` is true if the input trace file has an additional source
// ID column.  This is useful for feeding back the output trace file genereated
// by MemTraceLogger as the input to the driver.
class MemTraceDriver(
    config: CoalescerConfig,
    filename: String,
    traceHasSource: Boolean = false
)(implicit p: Parameters)
    extends LazyModule {
  // Create N client nodes together
  val laneNodes = Seq.tabulate(config.numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, config.numOldSrcIds)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module =
    new MemTraceDriverImp(this, config, filename, traceHasSource)
}

trait HasTraceLine {
  val source: UInt
  val address: UInt
  val is_store: UInt
  val size: UInt
  val data: UInt
}

// Used for both request and response.  Response had address set to 0
// NOTE: these widths have to agree with what's hardcoded in Verilog.
class TraceLine extends Bundle with HasTraceLine {
  val source = UInt(32.W)
  val address = UInt(64.W)
  val is_store = Bool()
  val size = UInt(8.W) // this is log2(bytesize) as in TL A bundle
  val data = UInt(64.W)
}

class MemTraceDriverImp(
    outer: MemTraceDriver,
    config: CoalescerConfig,
    filename: String,
    traceHasSource: Boolean
) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val finished = Output(Bool())
  })

  // Current cycle mark to read from trace
  val traceReadCycle = RegInit(1.U(64.W))

  // A decoupling queue to handle backpressure from downstream.  We let the
  // downstream take requests from the queue individually for each lane,
  // but do synchronized enqueue whenever all lane queue is ready to prevent
  // drifts between the lane.
  val reqQueues = Seq.fill(config.numLanes)(Module(new Queue(Valid(new TraceLine), 2)))
  // Are we safe to read the next warp?
  val reqQueueAllReady = reqQueues.map(_.io.enq.ready).reduce(_ && _)

  val sim = Module(new SimMemTrace(filename, config.numLanes, traceHasSource))
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  // 'sim.io.trace_ready.ready' is a ready signal going into the DPI sim,
  // indicating this Chisel module is ready to read the next line.
  sim.io.trace_read.ready := reqQueueAllReady
  sim.io.trace_read.cycle := traceReadCycle

  // Read output from Verilog BlackBox
  // Split output of SimMemTrace, which is flattened across all lanes,back to each lane's.
  val laneReqs = Wire(Vec(config.numLanes, Valid(new TraceLine)))
  val addrW = laneReqs(0).bits.address.getWidth
  val sizeW = laneReqs(0).bits.size.getWidth
  val dataW = laneReqs(0).bits.data.getWidth
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := sim.io.trace_read.valid(i)
    req.bits.source := 0.U // driver trace doesn't contain source id
    req.bits.address := sim.io.trace_read.address(addrW * (i + 1) - 1, addrW * i)
    req.bits.is_store := sim.io.trace_read.is_store(i)
    req.bits.size := sim.io.trace_read.size(sizeW * (i + 1) - 1, sizeW * i)
    req.bits.data := sim.io.trace_read.data(dataW * (i + 1) - 1, dataW * i)
  }

  // Not all fire because trace cycle has to advance even when there is no valid
  // line in the trace.
  when(reqQueueAllReady) {
    traceReadCycle := traceReadCycle + 1.U
  }

  // Enqueue traces to the request queue
  (reqQueues zip laneReqs).foreach { case (reqQ, req) =>
    // Synchronized enqueue
    reqQ.io.enq.valid := reqQueueAllReady && req.valid
    reqQ.io.enq.bits := req // FIXME duplicate valid
  }

  // Issue here is that Vortex mem range is not within Chipyard Mem range
  // In default setting, all mem-req for program data must be within
  // 0X80000000 -> 0X90000000
  def hashToValidPhyAddr(addr: UInt): UInt = {
    Cat(8.U(4.W), addr(27, 0))
  }

  val sourceGens = Seq.fill(config.numLanes)(
    Module(
      new SourceGenerator(
        log2Ceil(config.numOldSrcIds),
        ignoreInUse = false
      )
    )
  )

  // Advance source ID for all lanes in synchrony
  val syncedSourceGenValid = sourceGens.map(_.io.id.valid).reduce(_ && _)

  // Take requests off of the queue and generate TL requests
  (outer.laneNodes zip reqQueues).zipWithIndex.foreach {
    case ((node, reqQ), lane) =>
      val (tlOut, edge) = node.out(0)

      val req = reqQ.io.deq.bits
      // backpressure from downstream propagates into the queue
      reqQ.io.deq.ready := tlOut.a.ready && syncedSourceGenValid

      // Core only makes accesses of granularity larger than a word, so we want
      // the trace driver to act so as well.
      // That means if req.size is smaller than word size, we need to pad data
      // with zeros to generate a word-size request, and set mask accordingly.
      val offsetInWord = req.bits.address % config.wordSizeInBytes.U
      val subword = req.bits.size < log2Ceil(config.wordSizeInBytes).U

      // `mask` is currently unused
      // val mask = Wire(UInt(config.wordSizeInBytes.W))
      val wordData = Wire(UInt((config.wordSizeInBytes * 8 * 2).W))
      val sizeInBytes = Wire(UInt((sizeW + 1).W))
      sizeInBytes := (1.U) << req.bits.size
      // mask := Mux(subword, (~((~0.U(64.W)) << sizeInBytes)) << offsetInWord, ~0.U)
      wordData := Mux(subword, req.bits.data << (offsetInWord * 8.U), req.bits.data)
      val wordAlignedAddress =
        req.bits.address & (~((1 << log2Ceil(config.wordSizeInBytes)) - 1).U(addrW.W)).asUInt
      val wordAlignedSize = Mux(subword, 2.U, req.bits.size)

      val sourceGen = sourceGens(lane)
      sourceGen.io.gen := tlOut.a.fire
      // assert(sourceGen.io.id.valid)
      sourceGen.io.reclaim.valid := tlOut.d.fire
      sourceGen.io.reclaim.bits := tlOut.d.bits.source

      val (plegal, pbits) = edge.Put(
        fromSource = sourceGen.io.id.bits,
        toAddress = hashToValidPhyAddr(wordAlignedAddress),
        lgSize = wordAlignedSize, // trace line already holds log2(size)
        // data should be aligned to beatBytes
        data =
          (wordData << (8.U * (wordAlignedAddress % edge.manager.beatBytes.U))).asUInt
      )
      val (glegal, gbits) = edge.Get(
        fromSource = sourceGen.io.id.bits,
        toAddress = hashToValidPhyAddr(wordAlignedAddress),
        lgSize = wordAlignedSize
      )
      val legal = Mux(req.bits.is_store, plegal, glegal)
      val bits = Mux(req.bits.is_store, pbits, gbits)

      tlOut.a.valid := reqQ.io.deq.valid && syncedSourceGenValid
      when(tlOut.a.fire) {
        assert(legal, "illegal TL req gen")
      }
      tlOut.a.bits := bits
      tlOut.b.ready := true.B
      tlOut.c.valid := false.B
      tlOut.d.ready := true.B
      tlOut.e.valid := false.B

      // debug
      dontTouch(reqQ.io.enq)
      dontTouch(reqQ.io.deq)
      when(tlOut.a.valid) {
        TLPrintf(
          "MemTraceDriver",
          tlOut.a.bits.source,
          tlOut.a.bits.address,
          tlOut.a.bits.size,
          tlOut.a.bits.mask,
          req.bits.is_store,
          tlOut.a.bits.data,
          req.bits.data
        )
      }
      dontTouch(tlOut.a)
      dontTouch(tlOut.d)
  }

  val traceFinished = RegInit(false.B)
  when(sim.io.trace_read.finished) {
    traceFinished := true.B
  }

  // ensure no more new requests OR inflight requests are remaining
  val noValidReqs = sim.io.trace_read.valid === 0.U
  val allReqReclaimed = !(sourceGens.map(_.io.inflight).reduce(_ || _))

  io.finished := traceFinished && allReqReclaimed && noValidReqs

  // FIXME
  when(io.finished) {
    assert(
      false.B,
      "\n\n\nsimulation Successfully finished\n\n\n (this assertion intentional fail upon MemTracer termination)"
    )
  }
}

class SimMemTrace(filename: String, numLanes: Int, traceHasSource: Boolean)
    extends BlackBox(
      Map(
        "FILENAME" -> filename,
        "NUM_LANES" -> numLanes,
        "HAS_SOURCE" -> (if (traceHasSource) 1 else 0)
      )
    )
    with HasBlackBoxResource {
  val traceLineT = new TraceLine
  val addrW = traceLineT.address.getWidth
  val sizeW = traceLineT.size.getWidth
  val dataW = traceLineT.data.getWidth

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    // These names have to match declarations in the Verilog code, eg.
    // trace_read_address.
    val trace_read =
      new Bundle { // can't use HasTraceLine because this doesn't have source
        val ready = Input(Bool())
        val valid = Output(UInt(numLanes.W))
        // Chisel can't interface with Verilog 2D port, so flatten all lanes into
        // single wide 1D array.
        // TODO: assumes 64-bit address.
        val cycle = Input(UInt(64.W))
        val address = Output(UInt((addrW * numLanes).W))
        val is_store = Output(UInt(numLanes.W))
        val size = Output(UInt((sizeW * numLanes).W))
        val data = Output(UInt((dataW * numLanes).W))
        val finished = Output(Bool())
      }
  })

  addResource("/vsrc/SimDefaults.vh")
  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}

class MemTraceLogger(
    numLanes: Int,
    // base filename for the generated trace files. full filename will be
    // suffixed depending on `reqEnable`/`respEnable`/`loggerName`.
    filename: String,
    reqEnable: Boolean = true,
    respEnable: Boolean = true,
    // filename suffix that is unique to this logger module.
    // This will be appended to the filename of the generated trace.
    loggerName: String = ".logger"
)(implicit
    p: Parameters
) extends LazyModule {
  val node = TLIdentityNode()

  // Copied from freechips.rocketchip.trailingZeros which only supports Scala
  // integers
  def trailingZeros(x: UInt): UInt = {
    Mux(x === 0.U, x.widthOption.get.U, Log2(x & -x))
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val numReqs = Output(UInt(64.W))
      val numResps = Output(UInt(64.W))
      val reqBytes = Output(UInt(64.W))
      val respBytes = Output(UInt(64.W))
    })

    val numReqs = RegInit(0.U(64.W))
    val numResps = RegInit(0.U(64.W))
    val reqBytes = RegInit(0.U(64.W))
    val respBytes = RegInit(0.U(64.W))
    io.numReqs := numReqs
    io.numResps := numResps
    io.reqBytes := reqBytes
    io.respBytes := respBytes

    val simReq =
      if (reqEnable)
        Some(Module(new SimMemTraceLogger(false, s"${filename}", s".${loggerName}.req", numLanes)))
      else None
    val simResp =
      if (respEnable)
        Some(Module(new SimMemTraceLogger(true, s"${filename}", s".${loggerName}.resp", numLanes)))
      else None
    if (simReq.isDefined) {
      simReq.get.io.clock := clock
      simReq.get.io.reset := reset.asBool
    }
    if (simResp.isDefined) {
      simResp.get.io.clock := clock
      simResp.get.io.reset := reset.asBool
    }

    val laneReqs = Wire(Vec(numLanes, Valid(new TraceLine)))
    val laneResps = Wire(Vec(numLanes, Valid(new TraceLine)))

    assert(
      numLanes == node.in.length,
      "`numLanes` does not match the number of TL edges connected to the MemTraceLogger"
    )

    // snoop on the TileLink edges to log traffic
    ((node.in zip node.out) zip (laneReqs zip laneResps)).foreach {
      case (((tlIn, _), (tlOut, _)), (req, resp)) =>
        tlOut.a <> tlIn.a
        tlIn.d <> tlOut.d

        // requests on TL A channel
        //
        // Only log trace when fired, e.g. both upstream and downstream is ready
        // and transaction happened.
        req.valid := tlIn.a.fire
        req.bits.size := tlIn.a.bits.size
        req.bits.is_store := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode, tlIn.a.fire)
        req.bits.source := tlIn.a.bits.source
        // TL always carries the exact unaligned address that the client
        // originally requested, so no postprocessing required
        req.bits.address := tlIn.a.bits.address

        when(req.valid) {
          TLPrintf(
            s"MemTraceLogger (${loggerName}:downstream)",
            tlIn.a.bits.source,
            tlIn.a.bits.address,
            tlIn.a.bits.size,
            tlIn.a.bits.mask,
            req.bits.is_store,
            tlIn.a.bits.data,
            req.bits.data
          )
        }

        // TL data
        //
        // When tlIn.a.bits.size is smaller than the data bus width, need to
        // figure out which byte lanes we actually accessed so that
        // we can write that to the memory trace.
        // See Section 4.5 Byte Lanes in spec 1.8.1

        // This assert only holds true for PutFullData and not PutPartialData,
        // where HIGH bits in the mask may not be contiguous.
        when(tlIn.a.valid) { // && tlIn.a.bits.opcode === TLMessages.PutFullData
          assert(
            PopCount(tlIn.a.bits.mask) === (1.U << tlIn.a.bits.size).asUInt,
            "mask HIGH popcount do not match the TL size. " +
              "Partial masks are not allowed for PutFull"
          )
        }
        val trailingZerosInMask = trailingZeros(tlIn.a.bits.mask)
        val dataW = tlIn.params.dataBits
        val sizeInBits = ((1.U(1.W) << tlIn.a.bits.size) << 3.U).asUInt
        val mask = (~(~(0.U(dataW.W)) << sizeInBits)).asUInt
        req.bits.data := mask & (tlIn.a.bits.data >> (trailingZerosInMask * 8.U)).asUInt
        // when (req.bits.valid) {
        //   printf("trailingZerosInMask=%d, mask=%x, data=%x\n", trailingZerosInMask, mask, req.bits.data)
        // }

        // responses on TL D channel
        //
        // Only log trace when fired, e.g. both upstream and downstream is ready
        // and transaction happened.
        resp.valid := tlOut.d.fire
        resp.bits.size := tlOut.d.bits.size
        resp.bits.is_store := TLUtils.DOpcodeIsStore(
          tlOut.d.bits.opcode,
          tlOut.d.fire
        )
        resp.bits.source := tlOut.d.bits.source
        // NOTE: TL D channel doesn't carry address nor mask, so there's no easy
        // way to figure out which bytes the master actually use.  Since we
        // don't care too much about addresses in the trace anyway, just store
        // the entire bits.
        resp.bits.address := 0.U
        resp.bits.data := tlOut.d.bits.data
    }

    // stats
    val numReqsThisCycle =
      laneReqs.map { l => Mux(l.valid, 1.U(64.W), 0.U(64.W)) }.reduce {
        (v0, v1) => v0 + v1
      }
    val numRespsThisCycle =
      laneResps.map { l => Mux(l.valid, 1.U(64.W), 0.U(64.W)) }.reduce {
        (v0, v1) => v0 + v1
      }
    val reqBytesThisCycle =
      laneReqs
        .map { l => Mux(l.valid, (1.U(64.W) << l.bits.size).asTypeOf(UInt(64.W)), 0.U(64.W)) }
        .reduce { (b0, b1) =>
          b0 + b1
        }
    val respBytesThisCycle =
      laneResps
        .map { l => Mux(l.valid, (1.U(64.W) << l.bits.size).asTypeOf(UInt(64.W)), 0.U(64.W)) }
        .reduce { (b0, b1) =>
          b0 + b1
        }
    numReqs := numReqs + numReqsThisCycle
    numResps := numResps + numRespsThisCycle
    reqBytes := reqBytes + reqBytesThisCycle
    respBytes := respBytes + respBytesThisCycle

    // Flatten per-lane signals to the Verilog blackbox input.
    //
    // This is a clunky workaround of the fact that Chisel doesn't allow partial
    // assignment to a bitfield range of a wide signal.
    if (simReq.isDefined) {
      simReq.get.io.trace_log.valid := VecInit(laneReqs.map(_.valid)).asUInt
      simReq.get.io.trace_log.source := VecInit(laneReqs.map(_.bits.source)).asUInt
      simReq.get.io.trace_log.address := VecInit(laneReqs.map(_.bits.address)).asUInt
      simReq.get.io.trace_log.is_store := VecInit(laneReqs.map(_.bits.is_store)).asUInt
      simReq.get.io.trace_log.size := VecInit(laneReqs.map(_.bits.size)).asUInt
      simReq.get.io.trace_log.data := VecInit(laneReqs.map(_.bits.data)).asUInt
      assert(
        simReq.get.io.trace_log.ready === true.B,
        "MemTraceLogger is expected to be always ready"
      )
    }
    if (simResp.isDefined) {
      simResp.get.io.trace_log.valid := VecInit(laneResps.map(_.valid)).asUInt
      simResp.get.io.trace_log.source := VecInit(laneResps.map(_.bits.source)).asUInt
      simResp.get.io.trace_log.address := VecInit(laneResps.map(_.bits.address)).asUInt
      simResp.get.io.trace_log.is_store := VecInit(laneResps.map(_.bits.is_store)).asUInt
      simResp.get.io.trace_log.size := VecInit(laneResps.map(_.bits.size)).asUInt
      simResp.get.io.trace_log.data := VecInit(laneResps.map(_.bits.data)).asUInt
      assert(
        simResp.get.io.trace_log.ready === true.B,
        "MemTraceLogger is expected to be always ready"
      )
    }
  }
}

// MemTraceLogger is bidirectional, and `isResponse` is how the DPI module tells
// itself whether it's logging the request stream or the response stream.  This
// is necessary because we have to generate slightly different trace format
// depending on this, e.g. response trace will not contain an address column.
class SimMemTraceLogger(
    isResponse: Boolean,
    filenameBase: String, // usually the same as `filename` of SimMemTrace
    filenameSuffix: String, // can be ".req", ".resp", .etc
    numLanes: Int
) extends BlackBox(
      Map(
        "IS_RESPONSE" -> (if (isResponse) 1 else 0),
        "FILENAME_BASE" -> filenameBase,
        "FILENAME_SUFFIX" -> filenameSuffix,
        "NUM_LANES" -> numLanes
      )
    )
    with HasBlackBoxResource {
  val traceLineT = new TraceLine
  val sourceW = traceLineT.source.getWidth
  val addrW = traceLineT.address.getWidth
  val sizeW = traceLineT.size.getWidth
  val dataW = traceLineT.data.getWidth

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_log = new Bundle {
      val valid = Input(UInt(numLanes.W))
      val source = Input(UInt((sourceW * numLanes).W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Input(UInt((addrW * numLanes).W))
      val is_store = Input(UInt(numLanes.W))
      val size = Input(UInt((sizeW * numLanes).W))
      val data = Input(UInt((dataW * numLanes).W))
      val ready = Output(Bool())
    }
  })

  addResource("/vsrc/SimDefaults.vh")
  addResource("/vsrc/SimMemTraceLogger.v")
  addResource("/csrc/SimMemTraceLogger.cc")
  addResource("/csrc/SimMemTrace.h")
}

class TLPrintf {}

object TLPrintf {
  def apply(
      printer: String,
      source: UInt,
      address: UInt,
      size: UInt,
      mask: UInt,
      is_store: Bool,
      tlData: UInt,
      reqData: UInt
  ) = {
    printf(
      s"${printer}: TL source=%d, addr=%x, size=%d, mask=%x, store=%d",
      source,
      address,
      size,
      mask,
      is_store
    )
    when(is_store) {
      printf(", tlData=%x, reqData=%x", tlData, reqData)
    }
    printf("\n")
  }
}

class MemFuzzer(
    numLanes: Int,
    numSrcIds: Int,
    wordSizeInBytes: Int,
)(implicit p: Parameters)
    extends LazyModule {
  val laneNodes = Seq.tabulate(numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemFuzzer" + i.toString,
        sourceId = IdRange(0, numSrcIds)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  val node = TLIdentityNode()
  laneNodes.foreach(node := _)

  lazy val module = new MemFuzzerImp(this, numLanes, numSrcIds, wordSizeInBytes)
}

class MemFuzzerImp(
    outer: MemFuzzer,
    numLanes : Int,
    numSrcIds: Int,
    wordSizeInBytes: Int,
) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val finished = Output(Bool())
  })
  val sim = Module(new SimMemFuzzer(numLanes))
  sim.io.clock := clock
  sim.io.reset := reset.asBool

  sim.io.a.ready := VecInit(outer.laneNodes.map { node =>
    val (tlOut, _) = node.out(0)
    tlOut.a.ready
  }).asUInt

  io.finished := sim.io.finished

  // connect Verilog <-> Chisel IO
  // Verilog IO flattened across all lanes
  val laneReqs = Wire(Vec(numLanes, Decoupled(new TraceLine)))
  val addrW = laneReqs(0).bits.address.getWidth
  val sizeW = laneReqs(0).bits.size.getWidth
  val dataW = laneReqs(0).bits.data.getWidth
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := sim.io.a.valid(i)
    req.bits.source := 0.U // DPI fuzzer doesn't generate contain source id
    req.bits.address := sim.io.a.address(addrW * (i + 1) - 1, addrW * i)
    req.bits.is_store := sim.io.a.is_store(i)
    req.bits.size := sim.io.a.size(sizeW * (i + 1) - 1, sizeW * i)
    req.bits.data := sim.io.a.data(dataW * (i + 1) - 1, dataW * i)
  }
  sim.io.a.ready := VecInit(laneReqs.map(_.ready)).asUInt

  val laneResps = Wire(Vec(numLanes, Flipped(Decoupled(new TraceLine))))
  laneResps.zipWithIndex.foreach { case (resp, i) =>
    resp.ready := sim.io.d.ready(i)
    // TODO: not handled in DPI
    resp.bits.source := DontCare
    resp.bits.address := DontCare
    resp.bits.data := DontCare
  }
  sim.io.d.valid := VecInit(laneResps.map(_.valid)).asUInt
  sim.io.d.is_store := VecInit(laneResps.map(_.bits.is_store)).asUInt
  sim.io.d.size := VecInit(laneResps.map(_.bits.size)).asUInt

  val sourceGens = Seq.fill(numLanes)(
    Module(
      new SourceGenerator(
        log2Ceil(numSrcIds),
        ignoreInUse = false
      )
    )
  )
  val anyInflight = sourceGens.map(_.io.inflight).reduce(_ || _)
  sim.io.inflight := anyInflight

  // Take requests off of the queue and generate TL requests
  (outer.laneNodes zip (laneReqs zip laneResps)).zipWithIndex.foreach {
    case ((node, (req, resp)), lane) =>
      val (tlOut, edge) = node.out(0)

      // Requests --------------------------------------------------------------
      //
      // Core only makes accesses of granularity larger than a word, so we want
      // the trace driver to act so as well.
      // That means if req.size is smaller than word size, we need to pad data
      // with zeros to generate a word-size request, and set mask accordingly.
      val offsetInWord = req.bits.address % wordSizeInBytes.U
      val subword = req.bits.size < log2Ceil(wordSizeInBytes).U

      // `mask` is currently unused
      // val mask = Wire(UInt(wordSizeInBytes.W))
      val wordData = Wire(UInt((wordSizeInBytes * 8 * 2).W))
      val sizeInBytes = Wire(UInt((sizeW + 1).W))
      sizeInBytes := (1.U) << req.bits.size
      // mask := Mux(subword, (~((~0.U(64.W)) << sizeInBytes)) << offsetInWord, ~0.U)
      wordData := Mux(subword, req.bits.data << (offsetInWord * 8.U), req.bits.data)
      val wordAlignedAddress =
        req.bits.address & (~((1 << log2Ceil(wordSizeInBytes)) - 1).U(addrW.W)).asUInt
      val wordAlignedSize = Mux(subword, 2.U, req.bits.size)

      val sourceGen = sourceGens(lane)
      sourceGen.io.gen := tlOut.a.fire
      sourceGen.io.reclaim.valid := tlOut.d.fire
      sourceGen.io.reclaim.bits := tlOut.d.bits.source

      val (plegal, pbits) = edge.Put(
        fromSource = sourceGen.io.id.bits,
        toAddress = wordAlignedAddress,
        lgSize = wordAlignedSize, // trace line already holds log2(size)
        // data should be aligned to beatBytes
        data =
          (wordData << (8.U * (wordAlignedAddress % edge.manager.beatBytes.U))).asUInt
      )
      val (glegal, gbits) = edge.Get(
        fromSource = sourceGen.io.id.bits,
        toAddress = wordAlignedAddress,
        lgSize = wordAlignedSize
      )
      val legal = Mux(req.bits.is_store, plegal, glegal)
      val bits = Mux(req.bits.is_store, pbits, gbits)

      tlOut.a.valid := req.valid && sourceGen.io.id.valid
      req.ready := tlOut.a.ready && sourceGen.io.id.valid

      when(tlOut.a.fire) {
        assert(legal, "illegal TL req gen")
      }
      tlOut.a.bits := bits

      // Responses -------------------------------------------------------------
      //
      tlOut.d.ready := resp.ready
      resp.valid := tlOut.d.valid
      resp.bits.is_store := !edge.hasData(tlOut.d.bits)
      resp.bits.size := tlOut.d.bits.size

      tlOut.b.ready := true.B
      tlOut.c.valid := false.B
      tlOut.e.valid := false.B

      // debug
      dontTouch(req)
      when(tlOut.a.valid) {
        printf(s"Lane ${lane}: ");
        TLPrintf(
          "MemFuzzer",
          tlOut.a.bits.source,
          tlOut.a.bits.address,
          tlOut.a.bits.size,
          tlOut.a.bits.mask,
          req.bits.is_store,
          tlOut.a.bits.data,
          req.bits.data
        )
      }
      dontTouch(tlOut.a)
      dontTouch(tlOut.d)
  }
}

class SimMemFuzzer(numLanes: Int)
    extends BlackBox(Map("NUM_LANES" -> numLanes))
    with HasBlackBoxResource {
  val traceLineT = new TraceLine
  val addrW = traceLineT.address.getWidth
  val sizeW = traceLineT.size.getWidth
  val dataW = traceLineT.data.getWidth
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val inflight = Input(Bool())
    val finished = Output(Bool())

    val a =
      new Bundle {
        val ready = Input(UInt(numLanes.W))
        val valid = Output(UInt(numLanes.W))
        // Chisel can't interface with Verilog 2D port, so flatten all lanes into
        // single wide 1D array.
        val address = Output(UInt((addrW * numLanes).W))
        val is_store = Output(UInt(numLanes.W))
        val size = Output(UInt((sizeW * numLanes).W))
        val data = Output(UInt((dataW * numLanes).W))
      }
    val d =
      new Bundle {
        val ready = Output(UInt(numLanes.W))
        val valid = Input(UInt(numLanes.W))
        val is_store = Input(UInt(numLanes.W))
        val size = Input(UInt((sizeW * numLanes).W))
      }
  })

  addResource("/vsrc/SimDefaults.vh")
  addResource("/vsrc/SimMemFuzzer.v")
  addResource("/csrc/SimMemFuzzer.cc")
}

// Synthesizable unit tests

class DummyDriver(config: CoalescerConfig)(implicit p: Parameters)
    extends LazyModule {
  val laneNodes = Seq.tabulate(config.numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "dummy-core-node-" + i.toString,
        sourceId = IdRange(0, config.numOldSrcIds)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new DummyDriverImp(this, config)
}

class DummyDriverImp(outer: DummyDriver, config: CoalescerConfig)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sourceIdCounter = RegInit(0.U(log2Ceil(config.numOldSrcIds).W))
  sourceIdCounter := sourceIdCounter + 1.U

  val finishCounter = RegInit(10000.U(64.W))
  finishCounter := finishCounter - 1.U
  io.finished := (finishCounter === 0.U)

  outer.laneNodes.zipWithIndex.foreach { case (node, lane) =>
    assert(node.out.length == 1)

    // generate dummy traffic to coalescer to prevent it from being optimized
    // out during synthesis
    val address = Wire(UInt(config.addressWidth.W))
    address := Cat(
      (finishCounter + (lane.U % 3.U)),
      0.U(config.wordSizeWidth.W)
    )
    val (tl, edge) = node.out(0)
    val (legal, bits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = address,
      lgSize = 2.U,
      data = finishCounter + (lane.U % 3.U)
    )
    assert(legal, "illegal TL req gen")
    tl.a.valid := true.B
    tl.a.bits := bits
    tl.b.ready := true.B
    tl.c.valid := false.B
    tl.d.ready := true.B
    tl.e.valid := false.B
  }

  val dataSum = outer.laneNodes
    .map { node =>
      val tl = node.out(0)._1
      val data = Mux(tl.d.valid, tl.d.bits.data, 0.U)
      data
    }
    .reduce(_ +& _)
  // this doesn't make much sense, but it prevents the entire uncoalescer from
  // being optimized away
  finishCounter := finishCounter + dataSum
}

// A dummy harness around the coalescer for use in VLSI flow.
// Should not instantiate any memtrace modules.
// Test 2: DummyCoalescer
class DummyCoalescer(implicit p: Parameters) extends LazyModule {
  val xbar = LazyModule(new TLXbar)
  val numLanes = p(SIMTCoreKey).get.numLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val driver = LazyModule(new DummyDriver(config))
  val ram = LazyModule(
    new TLRAM(
      address = AddressSet(0x0000, 0xffffff),
      beatBytes = (1 << config.dataBusWidth)
    )
  )

  val coal = LazyModule(new CoalescingUnit(config))

  coal.nexusNode :=* driver.node
  
  (0 until config.numLanes).foreach { i =>
    xbar.node := TLWidthWidget(config.wordSizeInBytes) := coal.passthroughNodes(i)
  }
  xbar.node := coal.nexusNode
  
  ram.node := xbar.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    io.finished := driver.module.io.finished
  }
}

class DummyCoalescerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new DummyCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

class TLRAMCoalescerLogger(filename: String)(implicit p: Parameters)
    extends LazyModule {
  val xbar = LazyModule(new TLXbar)
  val numLanes = p(SIMTCoreKey).get.numLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val driver = LazyModule(new MemTraceDriver(config, filename))
  val coreSideLogger = LazyModule(
    new MemTraceLogger(numLanes, filename, loggerName = "coreside")
  )
  val coal = LazyModule(new CoalescingUnit(config))
  val memSideLogger = LazyModule(
    new MemTraceLogger(numLanes + 1, filename, loggerName = "memside")
  )
  val ram = LazyModule(
    new TLRAM(
      address = AddressSet(0x0000, 0xffffff),
      beatBytes = (1 << config.dataBusWidth)
    )
  )

  coal.nexusNode :=* coreSideLogger.node :=* driver.node
  
  // Non-coalesced outputs (need width widget)
  (0 until config.numLanes).foreach { i =>
    xbar.node := TLWidthWidget(config.wordSizeInBytes) := memSideLogger.node := coal.passthroughNodes(i)
  }
  // Coalesced output (already full width)
  xbar.node := memSideLogger.node := coal.nexusNode
  
  ram.node := xbar.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    io.finished := driver.module.io.finished

    when(io.finished) {
      printf(
        "numReqs=%d, numResps=%d, reqBytes=%d, respBytes=%d\n",
        coreSideLogger.module.io.numReqs,
        coreSideLogger.module.io.numResps,
        coreSideLogger.module.io.reqBytes,
        coreSideLogger.module.io.respBytes
      )
      assert(
        (coreSideLogger.module.io.numReqs === coreSideLogger.module.io.numResps) &&
          (coreSideLogger.module.io.reqBytes === coreSideLogger.module.io.respBytes),
        "FAIL: requests and responses traffic to the coalescer do not match"
      )
      printf("SUCCESS: coalescer response traffic matched requests!\n")
    }
  }
}

class TLRAMCoalescerLoggerTest(filename: String, timeout: Int = 500000)(implicit
    p: Parameters
) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescerLogger(filename)).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

class TLRAMCoalescer(implicit p: Parameters) extends LazyModule {
  val xbar = LazyModule(new TLXbar)
  val numLanes = p(SIMTCoreKey).get.numLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val filename = "vecadd.core1.thread4.trace"
  val coal = LazyModule(new CoalescingUnit(config))
  val driver = LazyModule(new MemTraceDriver(config, filename))
  val ram = LazyModule(
    new TLRAM(
      address = AddressSet(0x0000, 0xffffff),
      beatBytes = (1 << config.dataBusWidth)
    )
  )

  coal.nexusNode :=* driver.node
  
  (0 until config.numLanes).foreach { i =>
    xbar.node := TLWidthWidget(config.wordSizeInBytes) := coal.nexusNode
  }
  xbar.node := coal.nexusNode
  
  ram.node := xbar.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    io.finished := driver.module.io.finished
  }
}

class TLRAMCoalescerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
