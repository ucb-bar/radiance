// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.unittest._

// TODO: find better place for these

// Note: numNewSrcId is not a part of CoreParam, because the SIMT core should be agnostic to how inflight coalesced request can be genertated
case class SIMTCoreParams(nLanes: Int = 4, nSrcIds: Int = 8)
case class MemtraceCoreParams(tracefilename: String = "undefined", traceHasSource: Boolean = false)
case class CoalXbarParam()

case object SIMTCoreKey extends Field[Option[SIMTCoreParams]](None /*default*/)
case object MemtraceCoreKey extends Field[Option[MemtraceCoreParams]](None /*default*/)
case object CoalescerKey extends Field[Option[CoalescerConfig]](None /*default*/)
case object CoalXbarKey extends Field[Option[CoalXbarParam]](None /*default*/)

trait InFlightTableSizeEnum extends ChiselEnum {
  val INVALID: Type
  val FOUR: Type
  def logSizeToEnum(x: UInt): Type
  def enumToLogSize(x: Type): UInt
}

object DefaultInFlightTableSizeEnum extends InFlightTableSizeEnum {
  val INVALID = Value(0.U)
  val FOUR = Value(1.U)

  def logSizeToEnum(x: UInt): Type = {
    MuxCase(INVALID, Seq(
      (x === 2.U) -> FOUR
    ))
  }

  def enumToLogSize(x: Type): UInt = {
    MuxCase(0.U, Seq(
      (x === FOUR) -> 2.U
    ))
  }
}

// Mapping to reference model param names
//  numLanes: Int, <-> config.NUM_LANES
//  numPerLaneReqs: Int, <-> config.DEPTH
//  sourceWidth: Int, <-> log2ceil(config.NUM_OLD_IDS)
//  sizeWidth: Int, <-> config.sizeEnum.width
//  maxCoalLogSize: Int, <-> (1 << config.MAX_SIZE)
//  numInflightCoalRequests: Int <-> config.NUM_NEW_IDS
case class CoalescerConfig(
  enable: Boolean,        // globally enable or disable coalescing
  numLanes: Int,          // number of lanes (or threads) in a warp
  reqQueueDepth: Int,     // request window per lane
  waitTimeout: Int,       // max cycles to wait before forced fifo dequeue, per lane
  addressWidth: Int,      // assume <= 32
  dataBusWidth: Int,      // memory-side downstream TileLink data bus size.  Nominally, this has
                          // to be the maximum coalLogSizes.
                          // This data bus carries the data bits of coalesced request/responses,
                          // and so it has to be wider than wordSizeInBytes for the coalescer
                          // to perform well.
  coalLogSizes: Seq[Int], // list of coalescer sizes to try in the MonoCoalescers
                          // each size is log(byteSize)
                          // max value should match dataBusWidth as the largest-possible
                          // single-beat coealsced size.
  // watermark = 2,       // minimum buffer occupancy to start coalescing
  wordSizeInBytes: Int,   // word size of the request that each lane makes
  numOldSrcIds: Int,      // num of outstanding requests per lane, from processor
  numNewSrcIds: Int,      // num of outstanding coalesced requests
  respQueueDepth: Int,    // depth of the response fifo queues
  sizeEnum: InFlightTableSizeEnum,
  numCoalReqs: Int,       // total number of coalesced requests we can generate in one cycle
  numArbiterOutputPorts: Int, // total of output ports the arbiter will arbitrate into.
                              // this has to match downstream cache's configuration
  bankStrideInBytes: Int,  // cache line strides across the different banks
) {
  // maximum coalesced size
  def maxCoalLogSize: Int = {
    require(coalLogSizes.max <= dataBusWidth,
      "multi-beat coalesced reads/writes are currently not supported")
    if (coalLogSizes.max < dataBusWidth) {
      println("======== Warning: coalescer's max coalescing size is set to " +
        s"${coalLogSizes.max}, which is narrower than data bus width " +
        s"${dataBusWidth}.  This might indicate misconfiguration.")
    }
    coalLogSizes.max
  }
  def wordSizeWidth: Int = {
    val w = log2Ceil(wordSizeInBytes)
    require(wordSizeInBytes == 1 << w,
      s"wordSizeInBytes (${wordSizeInBytes}) is not power of two")
    w
  }
}


object DefaultCoalescerConfig extends CoalescerConfig(
  enable = true,
  numLanes = 4,
  reqQueueDepth = 1, // 1-deep request queues
  waitTimeout = 8,
  addressWidth = 24,
  dataBusWidth = 4,      // if "4": 2^4=16 bytes, 128 bit bus
  coalLogSizes = Seq(4), // if "4": 2^4=16 bytes, 128 bit bus
  // watermark = 2,
  wordSizeInBytes = 4,
  // when attaching to SoC, 16 source IDs are not enough due to longer latency
  numOldSrcIds = 8,
  numNewSrcIds = 8,
  respQueueDepth = 4,
  sizeEnum = DefaultInFlightTableSizeEnum,
  numCoalReqs = 1,
  numArbiterOutputPorts = 4,
  bankStrideInBytes = 64  //Current L2 is strided by 512 bits
)

class CoalescingUnit(config: CoalescerConfig)(implicit p: Parameters) extends LazyModule {
  // WIP:
  // Nexus node that captures the incoming TL requests, rewrites coalescable requests,
  // and arbitrates between non-coalesced and coalesced requests to a fix number of outputs
  // before sending it out to memory. This node is what's visible to upstream and downstream nodes.
  //
  //  val node = TLNexusNode(
  //    clientFn  = c => c.head,
  //    managerFn = m => m.head  // assuming arbiter generated ids are distinct between edges
  //  )
  //  node.in.map(_._2).foreach(edge => require(edge.manager.beatBytes == config.wordSizeInBytes,
  //    s"input edges into coalescer node does not have beatBytes = ${config.wordSizeInBytes}"))
  //  node.out.map(_._2).foreach(edge => require(edge.manager.beatBytes == config.maxCoalLogSize,
  //    s"output edges into coalescer node does not have beatBytes = ${config.maxCoalLogSize}"))

  val aggregateNode = TLIdentityNode()
  val cpuNode = TLIdentityNode()

  // Number of maximum in-flight coalesced requests.  The upper bound of this
  // value would be the sourceId range of a single lane.
  val numInflightCoalRequests = config.numNewSrcIds

  // Master node that actually generates coalesced requests.
  protected val coalParam = Seq(
    TLMasterParameters.v1(
      name = "CoalescerNode",
      sourceId = IdRange(0, numInflightCoalRequests)
    )
  )
  val coalescerNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(coalParam))
  )

  // Merge coalescerNode and cpuNode
  //
  // Expand per-lane requests to the wide coalesced size.  As a result, the
  // output edges of the coalescer all have the same wide width.  This
  // simplifies cache interface where the cache can always serve same-sized
  // wide requests, and the coalescer handles taking the right bytes.
  aggregateNode :=* coalescerNode
  aggregateNode :=* TLWidthWidget(config.wordSizeInBytes) :=* cpuNode

  lazy val module = new CoalescingUnitImp(this, config)
}

// Protocol-agnostic bundles that represent a request and a response to the
// coalescer.

class Request(sourceWidth: Int, sizeWidth: Int, addressWidth: Int, dataWidth: Int)
    extends Bundle {
  require(dataWidth % 8 == 0, s"dataWidth (${dataWidth} bits) is not multiple of 8")
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
      // FIXME: set size to actual size that corresponds mask
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
  require(dataWidth % 8 == 0, s"dataWidth (${dataWidth} bits) is not multiple of 8")
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

// `metadata` is an extra field in the sourceId table that can be used for
// storing e.g. the UUID originally attached to a request.  This is useful for
// using this module as a source ID converter / compressor.  If `None`, this
// field is not instantiated.
//
// If `ignoreInUse`, just keep giving out new IDs without any collision checking.
// This might result in TL violation.
class SourceGenerator[T <: Data](
  sourceWidth: Int,
  metadata: Option[T] = None,
  ignoreInUse: Boolean = false
) extends Module {
  def getMetadataType = metadata match {
    case Some(gen) => gen.cloneType
    case None => UInt(0.W)
  }
  val io = IO(new Bundle {
    val gen = Input(Bool())
    val reclaim = Input(Valid(UInt(sourceWidth.W)))
    val id = Output(Valid(UInt(sourceWidth.W)))
    // Below are used only when metadata is not None
    //
    // `meta` is used as input when a request succeeds id generation to store
    // its value to the table.
    // `peek` is the retrieved metadata saved for the request when
    // corresponding request has come back, setting `reclaim`.
    // Although these do not use ValidIO, it is safe because any in-flight
    // response coming back should have allocated a valid entry in the table
    // when it went out.
    val meta = Input(getMetadataType)
    val peek = Output(getMetadataType)
    // for debugging; indicates whether there is at least one inflight request
    // that hasn't been reclaimed yet
    val inflight = Output(Bool())
  })
  val head = RegInit(UInt(sourceWidth.W), 0.U)
  head := Mux(io.gen, head + 1.U, head)

  val outstanding = RegInit(UInt((sourceWidth + 1).W), 0.U)
  io.inflight := (outstanding > 0.U) || io.gen

  val numSourceId = 1 << sourceWidth
  val row = new Bundle {
    val meta = getMetadataType
    val id = Valid(UInt(sourceWidth.W))
  }
  // valid: in use, invalid: available
  // val occupancyTable = Mem(numSourceId, Valid(UInt(sourceWidth.W)))
  val occupancyTable = Mem(numSourceId, row)
  when(reset.asBool) {
    (0 until numSourceId).foreach { occupancyTable(_).id.valid := false.B }
  }
  val frees = (0 until numSourceId).map(!occupancyTable(_).id.valid)
  val lowestFree = PriorityEncoder(frees)
  val lowestFreeRow = occupancyTable(lowestFree)

  io.id.valid := (if (ignoreInUse) true.B else !lowestFreeRow.id.valid)
  io.id.bits := lowestFree
  when(io.gen && io.id.valid /* fire */ ) {
    occupancyTable(io.id.bits).id.valid := true.B // mark in use
    if (metadata.isDefined) {
      occupancyTable(io.id.bits).meta := io.meta
    }
  }
  when(io.reclaim.valid) {
    // @perf: would this require multiple write ports?
    occupancyTable(io.reclaim.bits).id.valid := false.B // mark freed
  }
  io.peek := {
    if (metadata.isDefined) occupancyTable(io.reclaim.bits).meta else 0.U
  }

  when(io.gen && io.id.valid) {
    when (!io.reclaim.valid) {
      assert(outstanding < (1 << sourceWidth).U)
      outstanding := outstanding + 1.U
    }
  }.elsewhen(io.reclaim.valid) {
    assert(outstanding > 0.U)
    outstanding := outstanding - 1.U
  }
  dontTouch(outstanding)
}

class CoalShiftQueue[T <: Data](gen: T, entries: Int, config: CoalescerConfig)
    extends Module {
  val io = IO(new Bundle {
    val queue = new Bundle {
      val enq = Vec(config.numLanes, DeqIO(gen.cloneType))
      val deq = Vec(config.numLanes, EnqIO(gen.cloneType))
    }
    val invalidate = Input(Valid(Vec(config.numLanes, UInt(entries.W))))
    val coalescable = Input(Vec(config.numLanes, Bool()))
    val mask = Output(Vec(config.numLanes, UInt(entries.W)))
    val elts = Output(Vec(config.numLanes, Vec(entries, gen)))
  })

//  val eltPrototype = Wire(Valid(gen))
//  eltPrototype.bits := DontCare
//  eltPrototype.valid := false.B

  val elts = Reg(Vec(config.numLanes, Vec(entries, Valid(gen))))
  val writePtr = RegInit(
    VecInit(Seq.fill(config.numLanes)(0.asUInt(log2Ceil(entries + 1).W)))
  )
  val deqDone = RegInit(VecInit(Seq.fill(config.numLanes)(false.B)))

  private def resetElts = {
    elts.foreach { laneQ =>
      laneQ.foreach { entry =>
        entry.valid := false.B
        entry.bits := DontCare
      }
    }
  }
  when(reset.asBool) {
    resetElts
  }

  val controlSignals = Wire(Vec(config.numLanes, new Bundle {
    val shift = Bool()
    val full = Bool()
    val empty = Bool()
  }))

  // io.coalescable will first turn on for all coalescable chunks, and turn off
  // incrementally as time goes on.  Therefore, when io.coalescable is all
  // turned off, that means we have processed all coalescable chunks at the
  // current cycle.
  //
  // shift hint is when the heads have no more coalescable left this or next cycle
  val shiftHint = !(io.coalescable zip io.invalidate.bits.map(_(0)))
    .map { case (c, inv) =>
      c && !(io.invalidate.valid && inv)
    }
    .reduce(_ || _)
  val syncedEnqValid = io.queue.enq.map(_.valid).reduce(_ || _)
  // valid && !ready means we enable enqueueing to a full queue, provided the
  // arbiter is taking away all remaining valid queue heads in the next cycle so
  // that we make space for the entire next warp.
  val syncedDeqValidNextCycle =
    io.queue.deq.map(x => x.valid && !x.ready).reduce(_ || _)

  for (i <- 0 until config.numLanes) {
    val enq = io.queue.enq(i)
    val deq = io.queue.deq(i)
    val ctrl = controlSignals(i)

    ctrl.full := writePtr(i) === entries.U
    ctrl.empty := writePtr(i) === 0.U
    // shift when no outstanding dequeue, no more coalescable chunks, and not empty
    ctrl.shift := !syncedDeqValidNextCycle && shiftHint && !ctrl.empty

    // dequeue is valid when:
    // head entry is valid, has not been processed by downstream, and is not coalescable
    deq.bits := elts.map(_.head.bits)(i)
    deq.valid := elts.map(_.head.valid)(i) && !deqDone(i) && !io.coalescable(i)

    // can take new entries if not empty, or if full but shifting
    enq.ready := (!ctrl.full) || ctrl.shift

    when(ctrl.shift) {
      // shift, invalidate tail, invalidate coalesced requests
      elts(i).zipWithIndex.foreach { case (elt, j) =>
        if (j == entries - 1) { // tail
          elt.valid := false.B
        } else {
          elt.bits := elts(i)(j + 1).bits
          elt.valid := elts(i)(
            j + 1
          ).valid && !(io.invalidate.valid && io.invalidate.bits(i)(j + 1))
        }
      }
      // reset dequeue mask when new entries are shifted in
      deqDone(i) := false.B
      // enqueue
      when(enq.ready && syncedEnqValid) { // to allow drift, swap for enq.fire
        elts(i)(writePtr(i) - 1.U).bits := enq.bits
        elts(i)(writePtr(i) - 1.U).valid := enq.valid
      }.otherwise {
        writePtr(i) := writePtr(i) - 1.U
      }
    }.otherwise {
      // invalidate coalesced requests
      when(io.invalidate.valid) {
        (elts(i) zip io.invalidate.bits(i).asBools).map { case (elt, inv) =>
          elt.valid := elt.valid && !inv
        }
      }
      // enqueue
      when(enq.ready && syncedEnqValid) {
        elts(i)(writePtr(i)).bits := enq.bits
        elts(i)(writePtr(i)).valid := enq.valid
        writePtr(i) := writePtr(i) + 1.U
      }
      deqDone(i) := deqDone(i) || deq.fire
    }
  }

  // When doing spatial-only coalescing, queues should never drift from each
  // other, i.e. the queue heads should always contain mem requests from the
  // same instruction.
  val queueInSync =
    controlSignals.map(_ === controlSignals.head).reduce(_ && _) &&
      writePtr.map(_ === writePtr.head).reduce(_ && _)
  assert(queueInSync, "shift queue lanes are not in sync")

  io.mask := elts.map(x => VecInit(x.map(_.valid)).asUInt)
  io.elts := elts.map(x => VecInit(x.map(_.bits)))
}

// Main coalescing logic that finds which lanes with valid requests can be coalesced
// into a wider request.  This works for a single given coalescing size `coalLogSize`,
// and MultiCoalescer will choose the best size between the multiple options given by
// multiple MonoCoalescers.
//
// See coalescer.py for the software model implementation.
class MonoCoalescer(
    config: CoalescerConfig,
    coalLogSize: Int,
    queueT: CoalShiftQueue[NonCoalescedRequest]
) extends Module {
  val io = IO(new Bundle {
    val window = Input(queueT.io.cloneType)
    val results = Output(new Bundle {
      val leaderIdx = Output(UInt(log2Ceil(config.numLanes).W))
      val baseAddr = Output(UInt(config.addressWidth.W))
      val matchOH = Output(Vec(config.numLanes, UInt(config.reqQueueDepth.W)))
      // number of entries matched with this leader lane's head.
      // maximum is numLanes * queueDepth
      val matchCount =
        Output(UInt(log2Ceil(config.numLanes * config.reqQueueDepth + 1).W))
      val coverageHits =
        Output(UInt((config.maxCoalLogSize - config.wordSizeWidth + 1).W))
      val canCoalesce = Output(Vec(config.numLanes, Bool()))
    })
  })

  io := DontCare

  // Combinational logic to drive output from window contents.
  // The leader lanes only compare their heads against all entries of the
  // follower lanes.
  val leaders = io.window.elts.map(_.head)
  val leadersValid = io.window.mask.map(_.asBools.head)

  def printQueueHeads = {
    leaders.zipWithIndex.foreach { case (head, i) =>
      printf(
        s"ReqQueueEntry[${i}].head = v:%d, source:%d, addr:%x\n",
        leadersValid(i),
        head.source,
        head.address
      )
    }
  }
  // when (leadersValid.reduce(_ || _)) {
  //   printQueueHeads
  // }

  val size = coalLogSize
  // NOTE: be careful with Scala integer overflow when addressWidth >= 32
  val addrMask = (((1L << config.addressWidth) - 1) - ((1 << size) - 1)).U
  def canMatch(req0: Request, req0v: Bool, req1: Request, req1v: Bool): Bool = {
    (req0.op === req1.op) &&
    (req0v && req1v) &&
    ((req0.address & this.addrMask) === (req1.address & this.addrMask))
  }

  // Gives a 2-D table of Bools representing match at every queue entry,
  // for each lane (so 3-D in total).
  // dimensions: (leader lane, follower lane, follower entry)
  val matchTablePerLane = (leaders zip leadersValid).map {
    case (leader, leaderValid) =>
      (io.window.elts zip io.window.mask).map {
        case (followers, followerValids) =>
          // compare leader's head against follower's every queue entry
          (followers zip followerValids.asBools).map {
            case (follower, followerValid) =>
              canMatch(follower, followerValid, leader, leaderValid)
            // FIXME: disabling halving optimization because it does not give the
            // correct per-lane coalescable indication to the shift queue
            // // match leader to only followers at lanes >= leader idx
            // // this halves the number of comparators
            // if (followerIndex < leaderIndex) false.B
            // else canMatch(follower, followerValid, leader, leaderValid)
          }
      }
  }

  val matchCounts = matchTablePerLane.map(table =>
    table
      .map(PopCount(_)) // sum up each column
      .reduce(_ +& _)
  )
  val canCoalesce = matchCounts.map(_ > 1.U)

  // Elect the leader that has the most match counts.
  // TODO: potentially expensive: magnitude comparator
  def chooseLeaderArgMax(matchCounts: Seq[UInt]): UInt = {
    matchCounts.zipWithIndex
      .map { case (c, i) =>
        (c, i.U)
      }
      .reduce[(UInt, UInt)] { case ((c0, i), (c1, j)) =>
        (Mux(c0 >= c1, c0, c1), Mux(c0 >= c1, i, j))
      }
      ._2
  }
  // Elect leader by choosing the smallest-index lane that has a valid
  // match, i.e. using priority encoder.
  def chooseLeaderPriorityEncoder(matchCounts: Seq[UInt]): UInt = {
    PriorityEncoder(matchCounts.map(_ > 1.U))
  }
  val chosenLeaderIdx = chooseLeaderPriorityEncoder(matchCounts)

  val chosenLeader = VecInit(leaders)(chosenLeaderIdx) // mux
  // matchTable for the chosen lane, but each column converted to bitflags,
  // i.e. Vec[UInt]
  val chosenMatches = VecInit(matchTablePerLane.map { table =>
    VecInit(table.map(VecInit(_).asUInt))
  })(chosenLeaderIdx)
  val chosenMatchCount = VecInit(matchCounts)(chosenLeaderIdx)

  // coverage calculation
  def getOffsetSlice(addr: UInt) = addr(size - 1, config.wordSizeWidth)
  // 2-D table flattened to 1-D
  val offsets =
    io.window.elts.flatMap(_.map(req => getOffsetSlice(req.address)))
  val valids = chosenMatches.flatMap(_.asBools)
  // indicates for each word in the coalesced chunk whether it is accessed by
  // any of the requests in the queue. e.g. if [ 1 1 1 1 ], all of the four
  // words in the coalesced data coming back will be accessed by some request
  // and we've reached 100% bandwidth utilization.
  val hits = Seq.tabulate(1 << (size - config.wordSizeWidth)) { target =>
    (offsets zip valids)
      .map { case (offset, valid) => valid && (offset === target.U) }
      .reduce(_ || _)
  }

  // debug prints
  when(leadersValid.reduce(_ || _)) {
    matchCounts.zipWithIndex.foreach { case (count, i) =>
      printf(s"lane[${i}] matchCount = %d\n", count);
    }
    printf("chosenLeader = lane %d\n", chosenLeaderIdx)
    printf("chosenLeader matches = [ ")
    chosenMatches.foreach { m => printf("%d ", m) }
    printf("]\n")
    printf("chosenMatchCount = %d\n", chosenMatchCount)

    printf("hits = [ ")
    hits.foreach { m => printf("%d ", m) }
    printf("]\n")
  }

  io.results.leaderIdx := chosenLeaderIdx
  io.results.baseAddr := chosenLeader.address & addrMask
  io.results.matchOH := chosenMatches
  io.results.matchCount := chosenMatchCount
  io.results.coverageHits := PopCount(hits)
  io.results.canCoalesce := canCoalesce
}

// Combinational logic that generates a coalesced request given a request
// window, and a selection of possible coalesced sizes.  May utilize multiple
// MonoCoalescers and apply size-choosing policy to determine the final
// coalesced request out of all possible combinations.
//
// Software model: coalescer.py
class MultiCoalescer(
    config: CoalescerConfig,
    queueT: CoalShiftQueue[NonCoalescedRequest],
    coalReqT: CoalescedRequest,
) extends Module {
  val invalidateT = Valid(Vec(config.numLanes, UInt(config.reqQueueDepth.W)))
  val io = IO(new Bundle {
    // coalescing window, connected to the contents of the request queues
    val window = Input(queueT.io.cloneType)
    // generated coalesced request
    val coalReq = DecoupledIO(coalReqT.cloneType)
    // invalidate signals going into each request queue's head.  Lanes with
    // high invalidate bits are what became coalesced into the new request.
    val invalidate = Output(invalidateT)
    // whether a lane is coalescable.  This is used to output non-coalescable
    // lanes to the arbiter so they can be flushed to downstream.
    val coalescable = Output(Vec(config.numLanes, Bool()))
  })

  val coalescers = config.coalLogSizes.map(size =>
    Module(new MonoCoalescer(config, size, queueT))
  )
  coalescers.foreach(_.io.window := io.window)

  def normalize(valPerSize: Seq[UInt]): Seq[UInt] = {
    (valPerSize zip config.coalLogSizes).map { case (hits, size) =>
      (hits << (config.maxCoalLogSize - size).U).asUInt
    }
  }

  def argMax(x: Seq[UInt]): UInt = {
    x.zipWithIndex.map {
      case (a, b) => (a, b.U)
    }.reduce[(UInt, UInt)] { case ((a, i), (b, j)) =>
      (Mux(a > b, a, b), Mux(a > b, i, j)) // > instead of >= here; want to use largest size
    }._2
  }

  // normalize to maximum coalescing size so that we can do fair comparisons
  // between coalescing results of different sizes
  val normalizedMatches = normalize(coalescers.map(_.io.results.matchCount))
  val normalizedHits = normalize(coalescers.map(_.io.results.coverageHits))

  val chosenSizeIdx = Wire(UInt(log2Ceil(config.coalLogSizes.size).W))
  val chosenValid = Wire(Bool())
  // minimum 25% coverage
  val minCoverage =
    1.max(1 << ((config.maxCoalLogSize - config.wordSizeWidth) - 2))

  // when(normalizedHits.map(_ > minCoverage.U).reduce(_ || _)) {
  //   chosenSizeIdx := argMax(normalizedHits)
  //   chosenValid := true.B
  //   printf("coalescing success by coverage policy\n")
  // }.else
  when(normalizedMatches.map(_ > 1.U).reduce(_ || _)) {
    chosenSizeIdx := argMax(normalizedMatches)
    chosenValid := true.B
    printf("coalescing success by matches policy\n")
  }.otherwise {
    chosenSizeIdx := DontCare
    chosenValid := false.B
  }

  def debugPolicyPrint() = {
    printf("matchCount[0]=%d\n", coalescers(0).io.results.matchCount)
    printf("normalizedMatches[0]=%d\n", normalizedMatches(0))
    printf("coverageHits[0]=%d\n", coalescers(0).io.results.coverageHits)
    printf("normalizedHits[0]=%d\n", normalizedHits(0))
    printf("minCoverage=%d\n", minCoverage.U)
  }

  // create coalesced request
  val chosenBundle = VecInit(coalescers.map(_.io.results))(chosenSizeIdx)
  val chosenSize = VecInit(coalescers.map(_.size.U))(chosenSizeIdx)

  // flatten requests and matches
  val flatReqs = io.window.elts.flatten
  val flatMatches = chosenBundle.matchOH.flatMap(_.asBools)

  // check for word alignment in addresses
  assert(
    io.window.elts
      .flatMap(_.map(req => req.address(config.wordSizeWidth - 1, 0) === 0.U))
      .zip(io.window.mask.flatMap(_.asBools))
      .map { case (aligned, valid) => (!valid) || aligned }
      .reduce(_ || _),
    "one or more addresses used for coalescing is not word-aligned"
  )

  // note: this is word-level coalescing. if finer granularity is needed, need to modify code
  val numWords = (1.U << (chosenSize - config.wordSizeWidth.U)).asUInt
  val maxWords = 1 << (config.maxCoalLogSize - config.wordSizeWidth)
  val addrMask = Wire(UInt(config.maxCoalLogSize.W))
  addrMask := (1.U << chosenSize).asUInt - 1.U

  val data = Wire(Vec(maxWords, UInt((config.wordSizeInBytes * 8).W)))
  val mask = Wire(Vec(maxWords, UInt(config.wordSizeInBytes.W)))

  // Reconstruct data and mask bit of the coalesced request;
  // important for coalesced writes
  for (i <- 0 until maxWords) {
    // Construct select bits that represent per-lane requests that actually got
    // coalesced into the current request, AND occupies the current i-th
    // word-slot in the data/mask bits
    val sel = (flatReqs zip flatMatches).map { case (req, m) =>
      // note: ANDing against addrMask is to conform to active byte lanes requirements
      // if aligning to LSB suffices, we should add the bitwise AND back
      m && ((req.address(
        config.maxCoalLogSize - 1,
        config.wordSizeWidth
      ) /* & addrMask*/ ) === i.U)
    }
    // TODO: SW uses priority encoder, not sure about behavior of MuxCase
    data(i) := MuxCase(
      DontCare,
      (flatReqs zip sel).map { case (req, s) =>
        s -> req.data
      }
    )
    mask(i) := MuxCase(
      0.U,
      (flatReqs zip sel).map { case (req, s) =>
        s -> req.mask
      }
    )
  }

  val coalesceValid = chosenValid

  // setting source is deferred, because in order to do proper source ID
  // generation we also have to look at the responses coming back, which
  // is easier to do at the toplevel.
  io.coalReq.bits.source := DontCare
  // Flatten data and mask Vecs into wide UInt
  io.coalReq.bits.mask := mask.asUInt
  io.coalReq.bits.data := data.asUInt
  io.coalReq.bits.size := chosenSize
  io.coalReq.bits.address := chosenBundle.baseAddr
  io.coalReq.bits.op := io.window.elts(chosenBundle.leaderIdx).head.op
  io.coalReq.valid := coalesceValid

  io.invalidate.bits := chosenBundle.matchOH
  io.invalidate.valid := io.coalReq.fire // invalidate only when fire

  io.coalescable := coalescers
    .map(_.io.results.canCoalesce.asUInt)
    .reduce(_ | _)
    .asBools

  dontTouch(io.invalidate) // debug

  def disable = {
    io.coalReq.valid := false.B
    io.invalidate.valid := false.B
    io.coalescable.foreach { _ := false.B }
  }
  if (!config.enable) disable
}

// This module mostly handles the correct ready/valid handshake depending on
// sourceId availability.  Actual generation logic is done by the
// SourceGenerator module.
class CoalescerSourceGen(
    config: CoalescerConfig,
    coalReqT: CoalescedRequest,
    respT: TLBundleD
) extends Module {
  val io = IO(new Bundle {
    // in/out means upstream/downstream
    val inReq = Flipped(Decoupled(coalReqT.cloneType))
    val outReq = Decoupled(coalReqT.cloneType)
    // outResp is only needed for telling the downstream TL node that this
    // sourcegen module is always ready to take in responses.
    // No need for inResp, since coalescerNode is directly replied by the
    // outResp TileLink bundle.
    val outResp = Flipped(Decoupled(respT.cloneType))
  })
  val sourceGen = Module(
    new SourceGenerator(log2Ceil(config.numNewSrcIds), ignoreInUse = false)
  )
  sourceGen.io.gen := io.outReq.fire // use up a source ID only when request is created
  sourceGen.io.reclaim.valid := io.outResp.fire
  sourceGen.io.reclaim.bits := io.outResp.bits.source
  sourceGen.io.meta := DontCare
  // TODO: make sourceGen.io.reclaim Decoupled?

  // passthrough logic
  io.outReq <> io.inReq
  // "man-in-the-middle"
  io.inReq.ready := io.outReq.ready && sourceGen.io.id.valid
  // overwrite bits affected by sourcegen backpressure
  io.outReq.valid := io.inReq.valid && sourceGen.io.id.valid
  io.outReq.bits.source := sourceGen.io.id.bits
  io.outResp.ready := true.B // should be always ready to reclaim old ID
}

class CoalescingUnitImp(outer: CoalescingUnit, config: CoalescerConfig)
    extends LazyModuleImp(outer) {
  println(s"CoalescingUnit instantiated with config: {")
  println(s"    enable: ${config.enable}")
  println(s"    numLanes: ${config.numLanes}")
  println(s"    coalLogSizes: ${config.coalLogSizes}")
  println(s"    numOldSrcIds: ${config.numOldSrcIds}")
  println(s"    numNewSrcIds: ${config.numNewSrcIds}")
  println(s"    reqQueueDepth: ${config.reqQueueDepth}")
  println(s"    respQueueDepth: ${config.respQueueDepth}")
  println(s"    addressWidth: ${config.addressWidth}")
  println(s"}")

  require(
    outer.cpuNode.in.length == config.numLanes,
    s"number of incoming edges (${outer.cpuNode.in.length}) is not the same as " +
      s"config.numLanes (${config.numLanes})"
  )
  require(
    outer.cpuNode.in.head._1.params.sourceBits == log2Ceil(config.numOldSrcIds),
    s"TL param sourceBits (${outer.cpuNode.in.head._1.params.sourceBits}) " +
      s"mismatch with log2(config.numOldSrcIds) (${log2Ceil(config.numOldSrcIds)})"
  )
  require(
    outer.cpuNode.in.head._1.params.addressBits == config.addressWidth,
    s"TL param addressBits (${outer.cpuNode.in.head._1.params.addressBits}) " +
      s"mismatch with config.addressWidth (${config.addressWidth})"
  )

  val oldSourceWidth = outer.cpuNode.in.head._1.params.sourceBits
  val nonCoalReqT = new NonCoalescedRequest(config)
  val reqQueues = Module(
    new CoalShiftQueue(nonCoalReqT, config.reqQueueDepth, config)
  )

  val coalReqT = new CoalescedRequest(config)
  val coalescer = Module(new MultiCoalescer(config, reqQueues, coalReqT))
  coalescer.io.window := reqQueues.io
  reqQueues.io.coalescable := coalescer.io.coalescable
  reqQueues.io.invalidate := coalescer.io.invalidate

  val inflightTable = Module(new InFlightTable(config, nonCoalReqT, coalReqT))
  val uncoalescer = Module(new Uncoalescer(config, inflightTable.entryT))

  // ===========================================================================
  // Request flow
  // ===========================================================================
  //
  // Override IdentityNode implementation so that we can instantiate
  // queues between input and output edges to buffer requests and responses.
  // See IdentityNode definition in `diplomacy/Nodes.scala`.
  //
  (outer.cpuNode.in zip outer.cpuNode.out).zipWithIndex.foreach {
    case (((tlIn, _), (tlOut, edgeOut)), lane) =>
      // Request queue
      val req = Wire(nonCoalReqT)

      req.op := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode, tlIn.a.fire)
      req.source := tlIn.a.bits.source
      req.isStore := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode)
      req.address := tlIn.a.bits.address
      req.size := tlIn.a.bits.size
      req.mask := tlIn.a.bits.mask

      val enq = reqQueues.io.queue.enq(lane)
      val deq = reqQueues.io.queue.deq(lane)
      enq.valid := tlIn.a.valid
      enq.bits := req
      // Respect arbiter and uncoalescer backpressure
      // deq.ready := tlOut.a.ready && uncoalescer.io.coalReq.ready
      deq.ready := tlOut.a.ready
      // Stall upstream core or memtrace driver when shiftqueue is not ready
      tlIn.a.ready := enq.ready
      tlOut.a.valid := deq.valid
      val (legal, tlBits) = deq.bits.toTLA(edgeOut)
      tlOut.a.bits := tlBits
      when(tlOut.a.fire) {
        assert(legal, "unhandled illegal TL req gen")
      }

      // debug
      // when (tlIn.a.valid) {
      //   TLPrintf(s"tlIn(${lane}).a",
      //     tlIn.a.bits.address,
      //     tlIn.a.bits.size,
      //     tlIn.a.bits.mask,
      //     TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode),
      //     tlIn.a.bits.data,
      //     0.U
      //   )
      // }
      // when (tlOut.a.valid) {
      //   TLPrintf(s"tlOut(${lane}).a",
      //     tlOut.a.bits.address,
      //     tlOut.a.bits.size,
      //     tlOut.a.bits.mask,
      //     TLUtils.AOpcodeIsStore(tlOut.a.bits.opcode),
      //     tlOut.a.bits.data,
      //     0.U
      //   )
      // }
  }

  val (tlCoal, edgeCoal) = outer.coalescerNode.out.head

  // The request coming out of MultiCoalescer still needs to go through source
  // ID generation.
  // We pull the sourcegen part out of MultiCoalescer to a separate Module to
  // reduce IO bloat in the coalescer and top-level clutter.
  //
  // The overall flow looks like:
  // ┌────────────────┐ ┌─────────────────────┐ ┌────────────────────┐
  // │ CoalShiftQueue ├─┤ Mono/MultiCoalescer ├─┤ CoalescerSourceGen ├── TileLink req
  // └────────────────┘ └─────────────────────┘ └────────────────────┘
  //                                                             ^
  //                             ┌────────────┐ ┌─────────────┐  │
  //                             │ RespQueues ├─┤ Uncoalescer ├──┴────── TileLink resp
  //                             └────────────┘ └─────────────┘
  //
  val coalSourceGen = Module(new CoalescerSourceGen(config, coalReqT, tlCoal.d.bits))
  coalSourceGen.io.inReq <> coalescer.io.coalReq
  coalSourceGen.io.outResp <> tlCoal.d

  // InflightTable IO
  //
  // Connect coalesced request to be recorded in the uncoalescer table.
  inflightTable.io.inCoalReq <> coalSourceGen.io.outReq
  inflightTable.io.invalidate := coalescer.io.invalidate
  inflightTable.io.windowElts := reqQueues.io.elts

  // This is the final coalesced request.
  val coalReq = inflightTable.io.outCoalReq
  // downstream backpressure on the coalesced edge
  // TODO: custom <>?
  inflightTable.io.outCoalReq.ready := tlCoal.a.ready
  tlCoal.a.valid := coalReq.valid
  val (legal, tlBits) = coalReq.bits.toTLA(edgeCoal)
  tlCoal.a.bits := tlBits
  when(tlCoal.a.fire) {
    assert(legal, "unhandled illegal TL req gen")
  }
  dontTouch(coalReq)

  tlCoal.b.ready := true.B
  tlCoal.c.valid := false.B
  // tlCoal.d.ready should be connected to uncoalescer's ready, done below.
  tlCoal.e.valid := false.B

  require(
    tlCoal.params.sourceBits == log2Ceil(config.numNewSrcIds),
    s"tlCoal param `sourceBits` (${tlCoal.params.sourceBits}) mismatches coalescer constant"
      + s" (${log2Ceil(config.numNewSrcIds)})"
  )
  require(
    tlCoal.params.dataBits == (1 << config.dataBusWidth) * 8,
    s"tlCoal param `dataBits` (${tlCoal.params.dataBits}) mismatches coalescer constant"
      + s" (${(1 << config.dataBusWidth) * 8})"
  )

  // ===========================================================================
  // Response flow
  // ===========================================================================
  //
  // Connect uncoalescer output and noncoalesced response ports to the response
  // queues.

  // The maximum number of requests from a single lane that can go into a
  // coalesced request.
  val numPerLaneReqs = config.reqQueueDepth

  // FIXME: no need to contain maxCoalLogSize data
  val respQueueEntryT = new Response(
    oldSourceWidth,
    log2Ceil(config.maxCoalLogSize),
    (1 << config.maxCoalLogSize) * 8
  )
  val respQueues = Seq.tabulate(config.numLanes) { _ =>
    Module(
      new MultiPortQueue(
        respQueueEntryT,
        // enq_lanes = 1 + M, where 1 is the response for the original per-lane
        // requests that didn't get coalesced, and M is the maximum number of
        // single-lane requests that can go into a coalesced request.
        // (`numPerLaneReqs`).
        // TODO: potentially expensive, because this generates more FFs.
        // Rather than enqueueing all responses in a single cycle, consider
        // enqueueing one by one (at the cost of possibly stalling downstream).
        1 + numPerLaneReqs,
        // deq_lanes = 1 because we're serializing all responses to 1 port that
        // goes back to the core.
        1,
        // lanes. Has to be at least max(enq_lanes, deq_lanes)
        1 + numPerLaneReqs,
        // Depth of each lane queue.
        // XXX queue depth is set to an arbitrarily high value that doesn't
        // make queue block up in the middle of the simulation.  Ideally there
        // should be a more logical way to set this, or we should handle
        // response queue blocking.
        config.respQueueDepth
      )
    )
  }
  val respQueueNoncoalPort = 0
  val respQueueUncoalPortOffset = 1


  (outer.node.in zip outer.node.out).zipWithIndex.foreach {
    case (((tlIn, edgeIn), (tlOut, _)), 0) => // TODO: not necessarily 1 master edge
      assert(
        edgeIn.master.masters(0).name == "CoalescerNode",
        "First edge is not connected to the coalescer master node"
      )
      // Edge from the coalescer TL master node should simply bypass the identity node,
      // except for connecting the outgoing edge to the inflight table, which is done
      // down below.
      tlIn.d <> tlOut.d
    case (((tlIn, edgeIn), (tlOut, _)), i) =>
      // Response queue
      //
      // This queue will serialize non-coalesced responses along with
      // coalesced responses and serve them back to the core side.
      val respQueue = respQueues(lane)
      val resp = Wire(respQueueEntryT)
      resp.fromTLD(tlOut.d.bits, tlOut.d.fire)

      // Queue up responses that didn't get coalesced originally ("noncoalesced" responses).
      // Coalesced (but uncoalesced back) responses will also be enqueued into the same queue.
      assert(
        respQueue.io.enq(respQueueNoncoalPort).ready,
        "respQueue: enq port for noncoalesced response is blocked"
      )
      respQueue.io.enq(respQueueNoncoalPort).valid := tlOut.d.valid
      respQueue.io.enq(respQueueNoncoalPort).bits := resp
      assert(respQueue.io.deq.length == 1,
        "respQueue should have only one dequeue port to the upstream")
      respQueue.io.deq.head.ready := tlIn.d.ready

      tlIn.d.valid := respQueue.io.deq.head.valid
      tlIn.d.bits := respQueue.io.deq.head.bits.toTLD(edgeIn)
      // Stall downstream when respQueue is full of entries waiting to enter core
      tlOut.d.ready := respQueue.io.enq(respQueueNoncoalPort).ready

      // Debug only
      val inflightCounter = RegInit(UInt(32.W), 0.U)
      when(tlOut.a.valid) {
        // don't inc/dec on simultaneous req/resp
        when(!tlOut.d.valid) {
          inflightCounter := inflightCounter + 1.U
        }
      }.elsewhen(tlOut.d.valid) {
        inflightCounter := inflightCounter - 1.U
      }
    }.elsewhen(tlOut.d.valid) {
      inflightCounter := inflightCounter - 1.U
    }

    dontTouch(inflightCounter)
    dontTouch(tlIn.a)
    dontTouch(tlIn.d)
    dontTouch(tlOut.a)
    dontTouch(tlOut.d)
  }

  // Uncoalescer IO
  //
  // Connect coalesced response
  uncoalescer.io.coalResp.valid := tlCoal.d.valid
  uncoalescer.io.coalResp.bits.fromTLD(tlCoal.d.bits, tlCoal.d.fire)
  // Connect lookup result from InflightTable
  uncoalescer.io.inflightLookup <> inflightTable.io.lookup
  // InflightTable IO: Look up the table with incoming coalesced responses
  // FIXME: this should be done inside uncoalescer
  inflightTable.io.lookupSourceId := tlCoal.d.bits.source

  // Connect uncoalescer results back into response queue
  (respQueues zip uncoalescer.io.respQueueIO).foreach { case (q, uncoalEnqs) =>
    require(q.io.enq.length == config.reqQueueDepth + respQueueUncoalPortOffset,
      s"wrong number of enq ports for MultiPort response queue")
    // slice the ports reserved for uncoalesced response
    val qUncoalEnqs = q.io.enq.slice(respQueueUncoalPortOffset, q.io.enq.length)
    (qUncoalEnqs zip uncoalEnqs).foreach {
      case (enq, uncoalEnq) => {
        enq <> uncoalEnq
      }
    }
  }
  // Uncoalescer backpressure: stall downstream when uncoalescer is not ready
  // to accept new coalesced response
  tlCoal.d.ready := uncoalescer.io.coalResp.ready

  // Debug
  dontTouch(coalescer.io.coalReq)
  val coalRespData = tlCoal.d.bits.data
  dontTouch(coalRespData)

  dontTouch(tlCoal.a)
  dontTouch(tlCoal.d)
}

class Uncoalescer(
    config: CoalescerConfig,
    inflightEntryT: InFlightTableEntry
) extends Module {
  val io = IO(new Bundle {
    val inflightLookup = Flipped(Decoupled(inflightEntryT))
    val coalResp = Flipped(Decoupled(new CoalescedResponse(config)))
    val respQueueIO = Vec(config.numLanes,
      // FIXME: does this have to be respQueueDepth?
      Vec(config.reqQueueDepth, Decoupled(new NonCoalescedResponse(config)))
    )
  })

  io.coalResp.ready := true.B // FIXME, see sw model implementation
  // Lookup table whenever coalesced response is valid
  io.inflightLookup.ready := io.coalResp.valid

  // Un-coalescing logic
  //
  def getCoalescedDataChunk(data: UInt, dataWidth: Int, offset: UInt, logSize: UInt): UInt = {
    assert(logSize === 2.U, "currently only supporting 4-byte accesses. TODO")

    // sizeInBits should be simulation-only construct
    val sizeInBits = ((1.U << logSize) << 3.U).asUInt
    assert(
      (dataWidth > 0).B && (dataWidth.U % sizeInBits === 0.U),
      cf"coalesced data width ($dataWidth) not evenly divisible by core req size ($sizeInBits)"
    )

    val numChunks = dataWidth / 32
    val chunks = Wire(Vec(numChunks, UInt(32.W)))
    val offsets = (0 until numChunks)
    (chunks zip offsets).foreach { case (c, o) =>
      // NOTE: Should take offset starting from LSB
      c := data(32 * (o + 1) - 1, 32 * o)
    }
    chunks(offset) // MUX
  }

  // Un-coalesce responses back to individual lanes
  // Connect uncoalesced results back into each lane's response queue
  val foundRow = io.inflightLookup.bits
  (foundRow.lanes zip io.respQueueIO).zipWithIndex.foreach { case ((foundLane, enqIOs), lane) =>
    foundLane.reqs.zipWithIndex.foreach { case (foundReq, depth) =>
      val enqIO = enqIOs(depth)
      // spatial-only coalescing: only looking at 0th srcId entry
      enqIO.valid := false.B
      enqIO.bits := DontCare
      // debug
      // when (resp.valid) {
      //   printf(s"${i}-th uncoalesced response came back from lane ${lane}\n")
      // }
      // dontTouch(q.io.enq(respQueueCoalPortOffset))

      when(io.inflightLookup.valid && foundReq.valid) {
        enqIO.valid := io.coalResp.valid && foundReq.valid
        enqIO.bits.op := foundReq.op
        enqIO.bits.source := foundReq.source
        val logSize = foundRow.sizeEnumT.enumToLogSize(foundReq.sizeEnum)
        enqIO.bits.size := logSize
        enqIO.bits.data :=
          getCoalescedDataChunk(
            io.coalResp.bits.data,
            io.coalResp.bits.data.getWidth,
            foundReq.offset,
            logSize
          )
        // is this necessary?
        enqIO.bits.error := DontCare
      }
    }
  }
}

// InflightCoalReqTable is a table structure that records for each unanswered
// coalesced request which lanes the request originated from, what their
// original TileLink sourceId were, etc.  We use this info to split the
// coalesced response back to individual per-lane responses with the right
// metadata.
class InFlightTable(
    config: CoalescerConfig,
    nonCoalReqT: NonCoalescedRequest,
    coalReqT: CoalescedRequest,
) extends Module {
  val offsetBits = config.maxCoalLogSize - config.wordSizeWidth // assumes word offset
  val entryT = new InFlightTableEntry(
    config.numLanes,
    config.reqQueueDepth,
    log2Ceil(config.numOldSrcIds),
    log2Ceil(config.numNewSrcIds),
    config.maxCoalLogSize, // FIXME: offsetBits?
    config.sizeEnum
  )

  val entries = config.numNewSrcIds
  val sourceWidth = log2Ceil(config.numOldSrcIds)

  println(s"CoalescingUnit InFlightTable config: {")
  println(s"    sourceWidth: ${sourceWidth}")
  println(s"    offsetBits: ${offsetBits}")
  println(s"    sizeEnumBits: ${entryT.sizeEnumT.getWidth}")
  println(s"}")

  val io = IO(new Bundle {
    // Enqueue IO
    //
    // generated coalesced request, connected to the output of the coalescer.
    // val coalReq = Flipped(DecoupledIO(coalReqT.cloneType))
    // Valid instead of Flipped(Decoupled), because we have to worry about setting
    // the ready bit right, which is better done from CoalSourceGen.
    val inCoalReq = Flipped(Decoupled(coalReqT))
    // invalidate signal coming out of coalescer.  Needed to generate new entry
    // for the table.
    val invalidate = Input(Valid(Vec(config.numLanes, UInt(config.reqQueueDepth.W))))
    // coalescing window, connected to the contents of the request queues.
    // Need this to generate new entry for the table.
    // TODO: duplicate type construction
    val windowElts = Input(Vec(config.numLanes, Vec(config.reqQueueDepth, nonCoalReqT)))
    // InflightTable simply passes through the inCoalReq to outCoalReq, only snooping
    // on its data to record what's necessary.
    val outCoalReq = Decoupled(coalReqT)

    // Lookup outputs
    //
    // TODO: return actual stuff
    val lookup = Decoupled(entryT)
    // TODO: put this inside decoupledIO
    val lookupSourceId = Input(UInt(sourceWidth.W))
  })

  val table = Mem(
    entries,
    new Bundle {
      val valid = Bool()
      val bits = entryT.cloneType
    }
  )

  when(reset.asBool) {
    (0 until entries).foreach { i =>
      table(i).valid := false.B
      table(i).bits.lanes.foreach { l =>
        l.reqs.foreach { r =>
          r.valid := false.B
          r.op := false.B
          r.source := 0.U
          r.offset := 0.U
          r.sizeEnum := config.sizeEnum.INVALID
        }
      }
    }
  }

  val full = Wire(Bool())
  full := (0 until entries).map(table(_).valid).reduce(_ && _)
  dontTouch(full)

  // Enqueue logic
  //
  // Construct a new entry for the inflight table using the coalesced request
  def generateInflightTableEntry: InFlightTableEntry = {
    val newEntry = Wire(entryT)
    newEntry.source := io.inCoalReq.bits.source
    // Do a 2-D copy from every (numLanes * queueDepth) invalidate output of the
    // coalescer to every (numLanes * queueDepth) entry in the inflight table.
    (newEntry.lanes zip io.invalidate.bits).zipWithIndex
      .foreach { case ((laneEntry, laneInv), lane) =>
        (laneEntry.reqs zip laneInv.asBools).zipWithIndex
          .foreach { case ((reqEntry, inv), i) =>
            val req = io.windowElts(lane)(i)
            when((io.invalidate.valid && inv)) {
              printf(
                s"coalescer: reqQueue($lane)($i) got invalidated (source=%d)\n",
                req.source
              )
            }
            reqEntry.valid := (io.invalidate.valid && inv)
            reqEntry.op := req.op
            reqEntry.source := req.source
            reqEntry.offset := ((req.address % (1 << config.maxCoalLogSize).U) >> config.wordSizeWidth)
            reqEntry.sizeEnum := config.sizeEnum.logSizeToEnum(req.size)
            // TODO: load/store op
          }
      }
    dontTouch(newEntry)

    newEntry
  }

  io.outCoalReq <> io.inCoalReq

  val enqReady = !full
  // Make sure to respect downstream ready here as well; otherwise inCoalReq.valid
  // might be up for multiple cycles waiting for downstream and write bogus
  // data to the row
  val enqFire = enqReady && io.inCoalReq.valid && io.outCoalReq.ready
  val enqSource = io.inCoalReq.bits.source
  when(enqFire) {
    // Inflight table is indexed by coalReq's source id
    val entryToWrite = table(enqSource)
    assert(
      !entryToWrite.valid,
      "tried to enqueue to an already occupied entry"
    )
    entryToWrite.valid := true.B
    entryToWrite.bits := generateInflightTableEntry
  }

  // Lookup logic
  io.lookup.valid := table(io.lookupSourceId).valid
  io.lookup.bits := table(io.lookupSourceId).bits
  // under normal circumstances, every lookup to the table should succeed
  // as long as the request gets recorded earlier than the response
  when(io.lookup.ready) {
    assert(table(io.lookupSourceId).valid === true.B,
      "table lookup with a valid sourceId failed")
  }
  // Dequeue as soon as lookup succeeds
  when(io.lookup.fire) {
    table(io.lookupSourceId).valid := false.B
  }
  assert(
    !((enqFire === true.B) && (io.lookup.fire === true.B) &&
      (enqSource === io.lookupSourceId)),
    "inflight table: enqueueing and looking up the same srcId at the same cycle is not handled"
  )

  dontTouch(io.lookup)
}

class InFlightTableEntry(
    val numLanes: Int,
    // Maximum number of requests from a single lane that can get coalesced into a single request
    val numPerLaneReqs: Int,
    val oldSourceWidth: Int,
    val newSourceWidth: Int,
    val offsetBits: Int,
    val sizeEnumT: InFlightTableSizeEnum
) extends Bundle {
  class PerSingleReq extends Bundle {
    val valid = Bool() // FIXME: delete this
    val op = Bool() // 0=READ 1=WRITE
    val source = UInt(oldSourceWidth.W)
    val offset = UInt(offsetBits.W)
    val sizeEnum = sizeEnumT()
  }
  class PerLane extends Bundle {
    val reqs = Vec(numPerLaneReqs, new PerSingleReq)
  }
  // sourceId of the coalesced response that just came back.  This will be the
  // key that queries the table.
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
    Mux(opcode === TLMessages.PutFullData || opcode === TLMessages.PutPartialData,
      true.B, false.B)
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
  val valid: UInt
  val source: UInt
  val address: UInt
  val is_store: UInt
  val size: UInt
  val data: UInt
}

// Used for both request and response.  Response had address set to 0
// NOTE: these widths have to agree with what's hardcoded in Verilog.
class TraceLine extends Bundle with HasTraceLine {
  val valid = Bool()
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
  val reqQueues = Seq.fill(config.numLanes)(Module(new Queue(new TraceLine, 2)))
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
  val laneReqs = Wire(Vec(config.numLanes, new TraceLine))
  val addrW = laneReqs(0).address.getWidth
  val sizeW = laneReqs(0).size.getWidth
  val dataW = laneReqs(0).data.getWidth
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := sim.io.trace_read.valid(i)
    req.source := 0.U // driver trace doesn't contain source id
    req.address := sim.io.trace_read.address(addrW * (i + 1) - 1, addrW * i)
    req.is_store := sim.io.trace_read.is_store(i)
    req.size := sim.io.trace_read.size(sizeW * (i + 1) - 1, sizeW * i)
    req.data := sim.io.trace_read.data(dataW * (i + 1) - 1, dataW * i)
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

  val sourceGens = Seq.fill(config.numLanes)(Module(
    new SourceGenerator(
      log2Ceil(config.numOldSrcIds),
      ignoreInUse = false
    )
  ))

  // Advance source ID for all lanes in synchrony
  val syncedSourceGenValid = sourceGens.map(_.io.id.valid).reduce(_ && _)

  // Take requests off of the queue and generate TL requests
  (outer.laneNodes zip reqQueues).zipWithIndex.foreach { case ((node, reqQ), lane) =>
    val (tlOut, edge) = node.out(0)

    val req = reqQ.io.deq.bits
    // backpressure from downstream propagates into the queue
    reqQ.io.deq.ready := tlOut.a.ready && syncedSourceGenValid

    // Core only makes accesses of granularity larger than a word, so we want
    // the trace driver to act so as well.
    // That means if req.size is smaller than word size, we need to pad data
    // with zeros to generate a word-size request, and set mask accordingly.
    val offsetInWord = req.address % config.wordSizeInBytes.U
    val subword = req.size < log2Ceil(config.wordSizeInBytes).U

    // `mask` is currently unused
    // val mask = Wire(UInt(config.wordSizeInBytes.W))
    val wordData = Wire(UInt((config.wordSizeInBytes * 8 * 2).W))
    val sizeInBytes = Wire(UInt((sizeW + 1).W))
    sizeInBytes := (1.U) << req.size
    // mask := Mux(subword, (~((~0.U(64.W)) << sizeInBytes)) << offsetInWord, ~0.U)
    wordData := Mux(subword, req.data << (offsetInWord * 8.U), req.data)
    val wordAlignedAddress =
      req.address & ~((1 << log2Ceil(config.wordSizeInBytes)) - 1).U(addrW.W)
    val wordAlignedSize = Mux(subword, 2.U, req.size)

    val sourceGen = sourceGens(lane)
    sourceGen.io.gen := tlOut.a.fire
    // assert(sourceGen.io.id.valid)
    sourceGen.io.reclaim.valid := tlOut.d.fire
    sourceGen.io.reclaim.bits := tlOut.d.bits.source
    sourceGen.io.meta := DontCare

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
    val legal = Mux(req.is_store, plegal, glegal)
    val bits = Mux(req.is_store, pbits, gbits)

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
        req.is_store,
        tlOut.a.bits.data,
        req.data
      )
    }
    dontTouch(tlOut.a)
    dontTouch(tlOut.d)
  }

  // Give some slack time after trace EOF to get some outstanding responses
  // back.
  val traceFinished = RegInit(false.B)
  when(sim.io.trace_read.finished) {
    traceFinished := true.B
  }
  io.finished := traceFinished

  //currently the .cc file ouptuts finished=true while it still need to issue one more request
  val noValidReqs     = sim.io.trace_read.valid === 0.U     
  val allReqReclaimed = !(sourceGens.map(_.io.inflight).reduce(_ || _))

  when(traceFinished && allReqReclaimed && noValidReqs) {
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

  // val beatBytes = 8 // FIXME: hardcoded
  // val node = TLManagerNode(Seq.tabulate(numLanes) { _ =>
  //   TLSlavePortParameters.v1(
  //     Seq(
  //       TLSlaveParameters.v1(
  //         address = List(AddressSet(0x0000, 0xffffff)), // FIXME: hardcoded
  //         supportsGet = TransferSizes(1, beatBytes),
  //         supportsPutPartial = TransferSizes(1, beatBytes),
  //         supportsPutFull = TransferSizes(1, beatBytes)
  //       )
  //     ),
  //     beatBytes = beatBytes
  //   )
  // })

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

    val laneReqs = Wire(Vec(numLanes, new TraceLine))
    val laneResps = Wire(Vec(numLanes, new TraceLine))

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
        req.size := tlIn.a.bits.size
        req.is_store := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode, tlIn.a.fire)
        req.source := tlIn.a.bits.source
        // TL always carries the exact unaligned address that the client
        // originally requested, so no postprocessing required
        req.address := tlIn.a.bits.address

        when(req.valid) {
          TLPrintf(
            s"MemTraceLogger (${loggerName}:downstream)",
            tlIn.a.bits.source,
            tlIn.a.bits.address,
            tlIn.a.bits.size,
            tlIn.a.bits.mask,
            req.is_store,
            tlIn.a.bits.data,
            req.data
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
        when(tlIn.a.valid) {
          assert(
            PopCount(tlIn.a.bits.mask) === (1.U << tlIn.a.bits.size),
            "mask HIGH popcount do not match the TL size. " +
              "Partial masks are not allowed for PutFull"
          )
        }
        val trailingZerosInMask = trailingZeros(tlIn.a.bits.mask)
        val dataW = tlIn.params.dataBits
        val sizeInBits = (1.U(1.W) << tlIn.a.bits.size) << 3.U
        val mask = ~(~(0.U(dataW.W)) << sizeInBits)
        req.data := mask & (tlIn.a.bits.data >> (trailingZerosInMask * 8.U))
        // when (req.valid) {
        //   printf("trailingZerosInMask=%d, mask=%x, data=%x\n", trailingZerosInMask, mask, req.data)
        // }

        // responses on TL D channel
        //
        // Only log trace when fired, e.g. both upstream and downstream is ready
        // and transaction happened.
        resp.valid := tlOut.d.fire
        resp.size := tlOut.d.bits.size
        resp.is_store := TLUtils.DOpcodeIsStore(tlOut.d.bits.opcode, tlOut.d.fire)
        resp.source := tlOut.d.bits.source
        // NOTE: TL D channel doesn't carry address nor mask, so there's no easy
        // way to figure out which bytes the master actually use.  Since we
        // don't care too much about addresses in the trace anyway, just store
        // the entire bits.
        resp.address := 0.U
        resp.data := tlOut.d.bits.data
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
        .map { l => Mux(l.valid, 1.U(64.W) << l.size, 0.U(64.W)) }
        .reduce { (b0, b1) =>
          b0 + b1
        }
    val respBytesThisCycle =
      laneResps
        .map { l => Mux(l.valid, 1.U(64.W) << l.size, 0.U(64.W)) }
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
    def flattenTrace(
        simIO: Bundle with HasTraceLine,
        perLane: Vec[TraceLine]
    ) = {
      // these will get optimized out
      val vecValid = Wire(Vec(numLanes, chiselTypeOf(perLane(0).valid)))
      val vecSource = Wire(Vec(numLanes, chiselTypeOf(perLane(0).source)))
      val vecAddress = Wire(Vec(numLanes, chiselTypeOf(perLane(0).address)))
      val vecIsStore = Wire(Vec(numLanes, chiselTypeOf(perLane(0).is_store)))
      val vecSize = Wire(Vec(numLanes, chiselTypeOf(perLane(0).size)))
      val vecData = Wire(Vec(numLanes, chiselTypeOf(perLane(0).data)))
      perLane.zipWithIndex.foreach { case (l, i) =>
        vecValid(i) := l.valid
        vecSource(i) := l.source
        vecAddress(i) := l.address
        vecIsStore(i) := l.is_store
        vecSize(i) := l.size
        vecData(i) := l.data
      }
      simIO.valid := vecValid.asUInt
      simIO.source := vecSource.asUInt
      simIO.address := vecAddress.asUInt
      simIO.is_store := vecIsStore.asUInt
      simIO.size := vecSize.asUInt
      simIO.data := vecData.asUInt
    }

    if (simReq.isDefined) {
      flattenTrace(simReq.get.io.trace_log, laneReqs)
      assert(
        simReq.get.io.trace_log.ready === true.B,
        "MemTraceLogger is expected to be always ready"
      )
    }
    if (simResp.isDefined) {
      flattenTrace(simResp.get.io.trace_log, laneResps)
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
    filenameBase: String,   // usually the same as `filename` of SimMemTrace
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

    val trace_log = new Bundle with HasTraceLine {
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
class DummyCoalescer(implicit p: Parameters) extends LazyModule {
  val numLanes = p(SIMTCoreKey).get.nLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val driver = LazyModule(new DummyDriver(config))
  val rams = Seq.fill(config.numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(
        address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << config.dataBusWidth)
      )
    )
  )

  val coal = LazyModule(new CoalescingUnit(config))

  coal.cpuNode :=* driver.node
  rams.foreach(_.node := coal.aggregateNode)

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

// tracedriver --> coalescer --> tracelogger --> tlram
class TLRAMCoalescerLogger(filename: String)(implicit p: Parameters)
    extends LazyModule {
  val numLanes = p(SIMTCoreKey).get.nLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val driver = LazyModule(new MemTraceDriver(config, filename))
  val coreSideLogger = LazyModule(
    new MemTraceLogger(numLanes, filename, loggerName = "coreside")
  )
  val coal = LazyModule(new CoalescingUnit(config))
  val memSideLogger = LazyModule(
    new MemTraceLogger(numLanes + 1, filename, loggerName = "memside")
  )
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(
        address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << config.dataBusWidth)
      )
    )
  )

  memSideLogger.node :=* coal.aggregateNode
  coal.cpuNode :=* coreSideLogger.node :=* driver.node
  rams.foreach { r => r.node := memSideLogger.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    // io.start is unused since MemTraceDriver doesn't accept io.start
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

// tracedriver --> coalescer --> tlram
class TLRAMCoalescer(implicit p: Parameters) extends LazyModule {
  val numLanes = p(SIMTCoreKey).get.nLanes
  val config = DefaultCoalescerConfig.copy(numLanes = numLanes)

  val filename = "vecadd.core1.thread4.trace"
  val coal = LazyModule(new CoalescingUnit(config))
  val driver = LazyModule(new MemTraceDriver(config, filename))
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(
        address = AddressSet(0x0000, 0xffffff),
        beatBytes = (1 << config.dataBusWidth)
      )
    )
  )

  coal.cpuNode :=* driver.node
  rams.foreach { r => r.node := coal.aggregateNode }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    // io.start is unused since MemTraceDriver doesn't accept io.start
    io.finished := driver.module.io.finished
  }
}

class TLRAMCoalescerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

////////////
////////////
////////////
////////////  Code for CoalescerXbar
////////////
////////////

// Lazy Module is needed to instantiate outgoing node
// I think the following implementation of Coalescer CrossBar is not going to be useful anytime soon
class CoalescerXbar(config: CoalescerConfig) (implicit p: Parameters) extends LazyModule {
    // Let SIMT's word size be 32, and read/write granularity be 256 


    // 32 client nodes of edge size 32 for non-coalesced reqs
    // And attaching them wigets
    val nonCoalNarrowNodes = Seq.tabulate(config.numLanes){i =>
        val nonCoalNarrowParam = Seq(
          TLMasterParameters.v1(
          name = "NonCoalNarrowNode" + i.toString,
          sourceId = IdRange(0, config.numOldSrcIds)
          )
        )
        TLClientNode(Seq(TLMasterPortParameters.v1(nonCoalNarrowParam)))
    }
    val nonCoalWidgets = Seq.tabulate(config.numLanes){ _=>
        TLWidthWidget(config.wordSizeInBytes)
    }

    (nonCoalWidgets zip nonCoalNarrowNodes).foreach{
      case(wgt,node)=> wgt := node
    }

    //Creating a round robin cross tilelink xbar for the un-coalesced
    //and connect them to the widgets
    val nonCoalXbar = LazyModule(new TLXbar(TLArbiter.roundRobin))
    nonCoalWidgets.foreach{nonCoalXbar.node:=_}



    // K client nodes of edge size 256 for the coalesced reqs
    val coalReqNodes = Seq.tabulate(config.numCoalReqs){ i =>
        val coalParam = Seq(
          TLMasterParameters.v1(
          name = "CoalReqNode" + i.toString,
          sourceId = IdRange(0, config.numNewSrcIds)
          )
        )
        TLClientNode(Seq(TLMasterPortParameters.v1(coalParam)))
    }
    // Create a RR Xbar for the coalesced request
    val coalXbar = LazyModule(new TLXbar(TLArbiter.roundRobin))
    coalReqNodes.foreach{coalXbar.node:=_}

    //Create a Priority XBar between Coalesced and Uncoalesced Request
    val outputXbar = LazyModule(new TLXbar(TLArbiter.lowestIndexFirst))
    outputXbar.node :=* coalXbar.node
    outputXbar.node :=* nonCoalXbar.node

    //express output crossbar as an idenity node for simpler downstream connection
    val node = TLIdentityNode()
    node :=* outputXbar.node

    val nonCoalEntryT = new NonCoalescedRequest(config)
    val coalEntryT    = new CoalescedRequest(config)
    val respNonCoalEntryT = new NonCoalescedResponse(config)
    val respCoalBundleT   = new CoalescedResponse(config)

    lazy val module = new CoalescerXbarImpl(
      this, config, nonCoalEntryT, coalEntryT, respNonCoalEntryT, respCoalBundleT)



}

class CoalescerXbarImpl(outer: CoalescerXbar, 
                      config: CoalescerConfig,
                      nonCoalEntryT: Request, 
                      coalEntryT: Request,
                      respNonCoalEntryT: Response, 
                      respCoalBundleT: CoalescedResponse
      ) extends LazyModuleImp(outer){


    val io =IO(new Bundle {
      val nonCoalReqs   = Vec(config.numLanes, Flipped(Decoupled(nonCoalEntryT)))
      val coalReqs      = Vec(config.numCoalReqs, Flipped(Decoupled(coalEntryT)))
      val nonCoalResps  = Vec(config.numLanes, Decoupled(respNonCoalEntryT))
      val coalResp      = Decoupled(respCoalBundleT)
      }
    )

    //Create Queues to receive data from upstream
    //Stage 1: Create Queue for nonCoalReqs and CoalReqs 
    val nonCoalReqsQueues = Seq.tabulate(config.numLanes){_=>
      Module(new Queue(nonCoalEntryT.cloneType, 1, true, false))
    }
    val coalReqsQueues = Seq.tabulate(config.numCoalReqs){_=>
      Module(new Queue(coalEntryT.cloneType, 1, true, false))
    }
    //Stage 1a: connect two Queue groups to the input
    (io.nonCoalReqs++io.coalReqs zip nonCoalReqsQueues++coalReqsQueues).foreach{
      case (req, q) => q.io.enq <> req
    }

    //Stage 2: connect output of the queue to the respective Node
    (nonCoalReqsQueues++coalReqsQueues zip outer.nonCoalNarrowNodes++outer.coalReqNodes).foreach{
      case(q, node) => 
        val (tlOut, edgeOut)  = node.out(0)
        q.io.deq.ready := tlOut.a.ready
        tlOut.a.valid  := q.io.deq.valid
        val (legal, tlBits) = q.io.deq.bits.toTLA(edgeOut)
        tlOut.a.bits   := tlBits
        when(tlOut.a.fire) {
          assert(legal, "unhandled illegal TL req gen")
        }
    }
    //The XBar will take care of the rest


    //
    // Inward data handling
    //

    // For the uncoalesced data response
    (outer.nonCoalNarrowNodes zip io.nonCoalResps).foreach{
      case(node,resp) => 
        val (tlOut, _)  = node.out(0)
        val nonCoalResp = Wire(respNonCoalEntryT)
        nonCoalResp.fromTLD(tlOut.d.bits, tlOut.d.fire)
        tlOut.d.ready  := resp.ready
        resp.valid     := tlOut.d.valid
        resp.bits      := nonCoalResp
    }

    //For the coalesced data response
    //Have an RR arbiter that holds the response data
    val coalRespRRArbiter = Module(new RRArbiter(
                                  outer.node.in(0)._1.d.bits.cloneType, 
                                  config.numCoalReqs)
                                  )
    outer.coalReqNodes.zipWithIndex.foreach{
      case(node, idx) =>
        val (tlOut, _)  = node.out(0)
        coalRespRRArbiter.io.in(idx) <> tlOut.d
    }
    //Connect output of arbiter to coalesced reponse output
    io.coalResp.valid := coalRespRRArbiter.io.out.valid
    coalRespRRArbiter.io.out.ready := io.coalResp.ready
    val coalRespBundle = Wire(respCoalBundleT)
    coalRespBundle.fromTLD(coalRespRRArbiter.io.out.bits, coalRespRRArbiter.io.out.fire)
    io.coalResp.bits  := coalRespBundle


  }


  //The current TLPrirotyXBar has a few workaround
  //1. it doesn't support temporal coalescing (it doesn't allow drift)
  //2. it's only a a dummy object for testing purpose, we need our own XBar (or Topology) for future L1
  class CoalescerTLPriortyXBar (implicit p: Parameters) extends LazyModule {

    val coalescerOutputNode = TLIdentityNode()
    val outputXbar          = LazyModule(new TLXbar(TLArbiter.lowestIndexFirst))
    val node                = TLIdentityNode()

    outputXbar.node  :=* TLBuffer(BufferParams.pipe, BufferParams.pipe) :=* coalescerOutputNode
    node             :=* outputXbar.node

    lazy val module = new Impl
    class Impl extends LazyModuleImp(this) {
      //Nonthing
    }

  }
