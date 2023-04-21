// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.MultiPortQueue
import freechips.rocketchip.unittest._

import org.chipsalliance.tilelink.OpCode

object CoalescerConsts {
  val MAX_SIZE = 6       // maximum burst size (64 bytes)
  val DEPTH = 1          // request window per lane
  val WAIT_TIMEOUT = 8   // max cycles to wait before forced fifo dequeue, per lane
  val ADDR_WIDTH = 32    // assume <= 32
  val DATA_BUS_SIZE = 4  // 2^4=16 bytes, 128 bit bus
  val NUM_LANES = 4
  // val WATERMARK = 2      // minimum buffer occupancy to start coalescing
  val WORD_SIZE = 4      // 32-bit system
  val WORD_WIDTH = 2     // log(WORD_SIZE)
  val NUM_OLD_IDS = 8    // num of outstanding requests per lane, from processor
  val NUM_NEW_IDS = 4    // num of outstanding coalesced requests
}

class CoalescingUnit(numLanes: Int = 1)(implicit p: Parameters) extends LazyModule {
  // Identity node that captures the incoming TL requests and passes them
  // through the other end, dropping coalesced requests.  This node is what
  // will be visible to upstream and downstream nodes.
  val node = TLIdentityNode()

  // Number of maximum in-flight coalesced requests.  The upper bound of this
  // value would be the sourceId range of a single lane.
  val numInflightCoalRequests = CoalescerConsts.NUM_NEW_IDS

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

  // Connect master node as the first inward edge of the IdentityNode
  node :=* coalescerNode

  lazy val module = new CoalescingUnitImp(this, numLanes)
}

// FIXME: this overlaps a lot with HasTraceLine
class ReqQueueEntry(val sourceWidth: Int, val addressWidth: Int, val sizeWidth: Int)
    extends Bundle {
  val source = UInt(sourceWidth.W)
  val isStore = Bool()
  val address = UInt(addressWidth.W)
  val size = UInt(sizeWidth.W) // log(sizeInBytes)
  val data = UInt(64.W /* FIXME hardcoded */ ) // write data
}

class RespQueueEntry(val sourceWidth: Int, val dataWidthInBits: Int, val sizeWidth: Int)
    extends Bundle {
  val source = UInt(sourceWidth.W)
  val isStore = Bool()
  val size = UInt(sizeWidth.W) // log(sizeInBytes)
  val data = UInt(dataWidthInBits.W) // read data
}


class CoalescingUnitImp(outer: CoalescingUnit, numLanes: Int) extends LazyModuleImp(outer) {
  // Make sure IdentityNode is connected to an upstream node, not just the
  // coalescer TL master node
  assert(outer.node.in.length >= 2)

  // 32-bit system. FIXME hardcoded
  val wordSize = 4

  val reqQueueDepth = 1
  val respQueueDepth = 4 // FIXME test

  val sourceWidth = outer.node.in(1)._1.params.sourceBits
  val addressWidth = outer.node.in(1)._1.params.addressBits
  val sizeWidth = outer.node.in(1)._1.params.sizeBits
  val reqQueueEntryT = new ReqQueueEntry(sourceWidth, addressWidth, sizeWidth)
  val reqQueues = Seq.tabulate(numLanes) { _ =>
    Module(new CoalShiftQueue(reqQueueEntryT, reqQueueDepth))
  }

  // The maximum number of requests from a single lane that can go into a
  // coalesced request.  Upper bound is request queue depth.
  val numPerLaneReqs = 1

  val respQueueEntryT = new RespQueueEntry(sourceWidth, wordSize * 8, sizeWidth)
  val respQueues = Seq.tabulate(numLanes) { _ =>
    Module(
      new MultiPortQueue(
        respQueueEntryT,
        // enq_lanes = 1 + M, where 1 is the response for the original per-lane
        // requests that didn't get coalesced, and M is the maximum number of
        // single-lane requests that can go into a coalesced request.
        // (`numPerLaneReqs`).
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
        respQueueDepth
      )
    )
  }
  val respQueueNoncoalPort = 0
  val respQueueCoalPortOffset = 1


  // Per-lane request and response queues
  //
  // Override IdentityNode implementation so that we can instantiate
  // queues between input and output edges to buffer requests and responses.
  // See IdentityNode definition in `diplomacy/Nodes.scala`.
  (outer.node.in zip outer.node.out).zipWithIndex.foreach {
    case (((tlIn, edgeIn), (tlOut, _)), 0) => // TODO: not necessarily 1 master edge
      assert(
        edgeIn.master.masters(0).name == "CoalescerNode",
        "First edge is not connected to the coalescer master node"
      )
      // Edge from the coalescer TL master node should simply bypass the identity node,
      // except for connecting the outgoing edge to the inflight table, which is done
      // down below.
      tlOut.a <> tlIn.a
      tlIn.d <> tlOut.d
    case (((tlIn, edgeIn), (tlOut, edgeOut)), i) =>
      // Request queue
      //
      val lane = i - 1
      val reqQueue = reqQueues(lane)
      val req = Wire(reqQueueEntryT)
      req.source := tlIn.a.bits.source
      req.isStore := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode)
      req.address := tlIn.a.bits.address
      req.size := tlIn.a.bits.size
      req.data := tlIn.a.bits.data

      assert(reqQueue.io.enq.ready, "reqQueue is supposed to be always ready")
      reqQueue.io.enq.valid := tlIn.a.valid
      reqQueue.io.enq.bits := req
      // TODO: deq.ready should respect downstream ready
      reqQueue.io.deq.ready := true.B
      reqQueue.io.invalidate := 0.U

      // Invalidate coalesced requests
      // FIXME: hardcoded lanes
      // val invalidate = coalReqValid && (lane == 0 || lane == 2).B
      val invalidate = coalReqValid
      tlOut.a.valid := reqQueue.io.deq.valid && !invalidate

      val reqHead = reqQueue.io.deq.bits
      val (plegal, pbits) = edgeOut.Put(
        fromSource = reqHead.source,
        toAddress = reqHead.address,
        lgSize = reqHead.size,
        // data is already aligned by MemTraceDriver
        // NOTE: if tlIn has different parameters, this will no longer be the
        // case
        data = reqHead.data
      )
      val (glegal, gbits) = edgeOut.Get(
        fromSource = reqHead.source,
        toAddress = reqHead.address,
        lgSize = reqHead.size
      )
      val legal = Mux(reqHead.isStore, plegal, glegal)
      val bits = Mux(reqHead.isStore, pbits, gbits)
      assert(legal, "unhandled illegal TL req gen")
      tlOut.a.bits := bits

      // Response queue
      //
      // This queue will serialize non-coalesced responses along with
      // coalesced responses and serve them back to the core side.
      val respQueue = respQueues(lane)
      val resp = Wire(respQueueEntryT)
      resp.source := tlOut.d.bits.source
      resp.isStore := TLUtils.DOpcodeIsStore(tlOut.d.bits.opcode)
      resp.size := tlOut.d.bits.size
      resp.data := tlOut.d.bits.data

      // Queue up responses that didn't get coalesced originally ("noncoalesced" responses).
      // Coalesced (but uncoalesced back) responses will also be enqueued into the same queue.
      assert(
        respQueue.io.enq(respQueueNoncoalPort).ready,
        "respQueue: enq port for noncoalesced response is blocked"
      )
      respQueue.io.enq(respQueueNoncoalPort).valid := tlOut.d.valid
      respQueue.io.enq(respQueueNoncoalPort).bits := resp
      // TODO: deq.ready should respect upstream ready
      respQueue.io.deq(respQueueNoncoalPort).ready := true.B

      tlIn.d.valid := respQueue.io.deq(respQueueNoncoalPort).valid
      val respHead = respQueue.io.deq(respQueueNoncoalPort).bits
      val apBits = edgeIn.AccessAck(
        toSource = respHead.source,
        lgSize = respHead.size
      )
      val agBits = edgeIn.AccessAck(
        toSource = respHead.source,
        lgSize = respHead.size,
        data = respHead.data
      )
      val respBits = Mux(respHead.isStore, apBits, agBits)
      tlIn.d.bits := respBits

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

  // Generate coalesced requests
  val coalSourceId = RegInit(0.U(2.W /* FIXME hardcoded */ ))
  coalSourceId := coalSourceId + 1.U

  val (tlCoal, edgeCoal) = outer.coalescerNode.out(0)
  val coalReqAddress = Wire(UInt(tlCoal.params.addressBits.W))
  // FIXME: bogus address
  coalReqAddress := (0xabcd.U + coalSourceId) << 4
  // FIXME: bogus coalescing logic: coalesce whenever all 4 lanes have valid
  // queue head
  coalReqValid := reqQueues(0).io.deq.valid && reqQueues(1).io.deq.valid &&
    reqQueues(2).io.deq.valid && reqQueues(3).io.deq.valid
  // coalReqValid := false.B
  when(coalReqValid) {
    // invalidate original requests due to coalescing
    // FIXME: bogus
    reqQueues(0).io.invalidate := 0x1.U
    reqQueues(1).io.invalidate := 0x1.U
    reqQueues(2).io.invalidate := 0x1.U
    reqQueues(3).io.invalidate := 0x1.U
    printf("coalescing succeeded!\n")
  }

  // TODO: write request
  val (legal, bits) = edgeCoal.Get(
    fromSource = coalSourceId,
    // `toAddress` should be aligned to 2**lgSize
    toAddress = coalReqAddress,
    // 64 bits = 8 bytes = 2**(3) bytes
    // TODO: parameterize to eg. cache line size
    lgSize = 3.U
  )
  assert(legal, "unhandled illegal TL req gen")
  tlCoal.a.valid := coalReqValid
  tlCoal.a.bits := bits
  tlCoal.b.ready := true.B
  tlCoal.c.valid := false.B
  tlCoal.d.ready := true.B
  tlCoal.e.valid := false.B

  // Construct new entry for the inflight table
  // FIXME: don't instantiate inflight table entry type here.  It leaks the table's impl
  // detail to the coalescer
  val offsetBits = 4 // FIXME hardcoded
  val sizeBits = 2 // FIXME hardcoded
  val newEntry = Wire(
    new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)
  )

  println(s"=========== table sourceWidth: ${sourceWidth}")

  newEntry.source := coalSourceId
  newEntry.lanes.foreach { l =>
    l.reqs.zipWithIndex.foreach { case (r, i) =>
      // TODO: this part needs the actual coalescing logic to work
      r.valid := false.B
      r.source := i.U // FIXME bogus
      r.offset := 1.U
      r.size := 2.U
    }
  }
  newEntry.lanes(0).reqs(0).valid := true.B
  newEntry.lanes(1).reqs(0).valid := true.B
  newEntry.lanes(2).reqs(0).valid := true.B
  newEntry.lanes(3).reqs(0).valid := true.B
  dontTouch(newEntry)

  // Uncoalescer module sncoalesces responses back to each lane
  val coalDataWidth = tlCoal.params.dataBits
  val uncoalescer = Module(
    new UncoalescingUnit(
      numLanes,
      numPerLaneReqs,
      sourceWidth,
      sizeWidth,
      coalDataWidth,
      outer.numInflightCoalRequests
    )
  )

  uncoalescer.io.coalReqValid := coalReqValid
  uncoalescer.io.newEntry := newEntry
  uncoalescer.io.coalRespValid := tlCoal.d.valid
  uncoalescer.io.coalRespSrcId := tlCoal.d.bits.source
  uncoalescer.io.coalRespData := tlCoal.d.bits.data

  println(s"=========== coalRespData width: ${tlCoal.d.bits.data.widthOption.get}")

  // Queue up synthesized uncoalesced responses into each lane's response queue
  (respQueues zip uncoalescer.io.uncoalResps).foreach { case (q, lanes) =>
    lanes.zipWithIndex.foreach { case (resp, i) =>
      // TODO: rather than crashing, deassert tlOut.d.ready to stall downtream
      // cache.  This should ideally not happen though.
      assert(
        q.io.enq(respQueueCoalPortOffset + i).ready,
        s"respQueue: enq port for 0-th coalesced response is blocked"
      )
      q.io.enq(respQueueCoalPortOffset + i).valid := resp.valid
      q.io.enq(respQueueCoalPortOffset + i).bits := resp.bits
      // dontTouch(q.io.enq(respQueueCoalPortOffset))
    }
  }

  // Debug
  dontTouch(coalReqValid)
  dontTouch(coalReqAddress)
  val coalRespData = tlCoal.d.bits.data
  dontTouch(coalRespData)

  dontTouch(tlCoal.a)
  dontTouch(tlCoal.d)
}

class UncoalescingUnit(
    val numLanes: Int,
    val numPerLaneReqs: Int,
    val sourceWidth: Int,
    val sizeWidth: Int,
    val coalDataWidth: Int,
    val numInflightCoalRequests: Int
) extends Module {
  val inflightTable = Module(
    new InflightCoalReqTable(numLanes, numPerLaneReqs, sourceWidth, numInflightCoalRequests)
  )
  val wordSize = 4 // FIXME duplicate

  val io = IO(new Bundle {
    val coalReqValid = Input(Bool())
    val newEntry = Input(inflightTable.entryT)
    val coalRespValid = Input(Bool())
    val coalRespSrcId = Input(UInt(sourceWidth.W))
    val coalRespData = Input(UInt(coalDataWidth.W))
    val uncoalResps = Output(
      Vec(
        numLanes,
        Vec(numPerLaneReqs, ValidIO(new RespQueueEntry(sourceWidth, wordSize * 8, sizeWidth)))
      )
    )
  })

  // Populate inflight table
  inflightTable.io.enq.valid := io.coalReqValid
  inflightTable.io.enq.bits := io.newEntry

  // Look up the table with incoming coalesced responses
  inflightTable.io.lookup.ready := io.coalRespValid
  inflightTable.io.lookupSourceId := io.coalRespSrcId

  assert(
    !((io.coalReqValid === true.B) && (io.coalRespValid === true.B) &&
      (io.newEntry.source === io.coalRespSrcId)),
    "inflight table: enqueueing and looking up the same srcId at the same cycle is not handled"
  )

  // Un-coalescing logic
  //
  // FIXME: `size` should be UInt, not Int
  def getCoalescedDataChunk(data: UInt, dataWidth: Int, offset: UInt, byteSize: Int): UInt = {
    val bitSize = byteSize * 8
    val sizeMask = (1.U << bitSize) - 1.U
    assert(
      dataWidth > 0 && dataWidth % bitSize == 0,
      s"coalesced data width ($dataWidth) not evenly divisible by core req size ($bitSize)"
    )
    val numChunks = dataWidth / bitSize
    val chunks = Wire(Vec(numChunks, UInt(bitSize.W)))
    val offsets = (0 until numChunks)
    (chunks zip offsets).foreach { case (c, o) =>
      // Take [(off-1)*size:off*size] starting from MSB
      c := (data >> (dataWidth - (o + 1) * bitSize)) & sizeMask
    }
    chunks(offset) // MUX
  }

  // Un-coalesce responses back to individual lanes
  val found = inflightTable.io.lookup.bits
  (found.lanes zip io.uncoalResps).foreach { case (perLane, ioPerLane) =>
    perLane.reqs.zipWithIndex.foreach { case (oldReq, i) =>
      val ioOldReq = ioPerLane(i)

      // FIXME: only looking at 0th srcId entry

      ioOldReq.valid := false.B
      ioOldReq.bits := DontCare

      when(inflightTable.io.lookup.valid) {
        ioOldReq.valid := oldReq.valid
        ioOldReq.bits.source := oldReq.source
        // FIXME: disregard size enum for now
        val byteSize = 4
        ioOldReq.bits.data :=
          getCoalescedDataChunk(io.coalRespData, coalDataWidth, oldReq.offset, byteSize)
      }
    }
  }
}

// InflightCoalReqTable is a table structure that records
// for each unanswered coalesced request which lane the request originated
// from, what their original TileLink sourceId were, etc.  We use this info to
// split the coalesced response back to individual per-lane responses with the
// right metadata.
class InflightCoalReqTable(
                            val numLanes: Int,
                            val numPerLaneReqs: Int,
                            val sourceWidth: Int,
                            val entries: Int
                          ) extends Module {
  val offsetBits = 4 // FIXME hardcoded
  val sizeBits = 2 // FIXME hardcoded
  val entryT =
    new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)

  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(entryT))
    // TODO: return actual stuff
    val lookup = Decoupled(entryT)
    // TODO: put this inside decoupledIO
    val lookupSourceId = Input(UInt(sourceWidth.W))
  })

  val table = Mem(
    entries,
    new Bundle {
      val valid = Bool()
      val bits =
        new InflightCoalReqTableEntry(numLanes, numPerLaneReqs, sourceWidth, offsetBits, sizeBits)
    }
  )

  when(reset.asBool) {
    (0 until entries).foreach { i =>
      table(i).valid := false.B
      table(i).bits.lanes.foreach { l =>
        l.reqs.foreach { r =>
          r.valid := false.B
          r.source := 0.U
          r.offset := 0.U
          r.size := 0.U
        }
      }
    }
  }

  val full = Wire(Bool())
  full := (0 until entries)
    .map { i => table(i).valid }
    .reduce { (v0, v1) => v0 && v1 }
  // Inflight table should never be full.  It should have enough number of
  // entries to keep track of all outstanding core-side requests, i.e.
  // (2 ** oldSrcIdBits) entries.
  assert(!full, "inflight table is full and blocking coalescer")
  dontTouch(full)

  // Enqueue logic
  io.enq.ready := !full
  val enqFire = io.enq.ready && io.enq.valid
  when(enqFire) {
    // TODO: handle enqueueing and looking up the same entry in the same cycle?
    val entryToWrite = table(io.enq.bits.source)
    assert(
      !entryToWrite.valid,
      "tried to enqueue to an already occupied entry"
    )
    entryToWrite.valid := true.B
    entryToWrite.bits := io.enq.bits
  }

  // Lookup logic
  io.lookup.valid := table(io.lookupSourceId).valid
  io.lookup.bits := table(io.lookupSourceId).bits
  val lookupFire = io.lookup.ready && io.lookup.valid
  // Dequeue as soon as lookup succeeds
  when(lookupFire) {
    table(io.lookupSourceId).valid := false.B
  }

  dontTouch(io.lookup)
}

class InflightCoalReqTableEntry(
    val numLanes: Int,
    // Maximum number of requests from a single lane that can get coalesced into a single request
    val numPerLaneReqs: Int,
    val sourceWidth: Int,
    val offsetBits: Int,
    val sizeBits: Int
) extends Bundle {
  class PerCoreReq extends Bundle {
    val valid = Bool()
    // FIXME: oldId and newId shares the same width
    val source = UInt(sourceWidth.W)
    val offset = UInt(offsetBits.W)
    val size = UInt(sizeBits.W)
  }
  class PerLane extends Bundle {
    // FIXME: if numPerLaneReqs != 2 ** sourceWidth, we need to store srcId as well
    val reqs = Vec(numPerLaneReqs, new PerCoreReq)
  }
  // sourceId of the coalesced response that just came back.  This will be the
  // key that queries the table.
  val source = UInt(sourceWidth.W)
  val lanes = Vec(numLanes, new PerLane)
}

// A shift-register queue implementation that supports invalidating entries
// and exposing queue contents as output IO. (TODO: support deadline)
// Initially copied from freechips.rocketchip.util.ShiftQueue.
// If `pipe` is true, support enqueueing to a full queue when also dequeueing.
class CoalShiftQueue[T <: Data](
                                 gen: T,
                                 val entries: Int,
                                 pipe: Boolean = true,
                                 flow: Boolean = false
                               ) extends Module {
  val io = IO(new Bundle {
    val queue = new QueueIO(gen, entries)
    val invalidate = Input(Valid(UInt(entries.W)))
    val mask = Output(UInt(entries.W))
    val elts = Output(Vec(entries, gen))
    // 'QueueIO' provides io.count, but we might not want to use it in the
    // coalescer because it has potentially expensive PopCount
  })

  private val valid = RegInit(VecInit(Seq.fill(entries) { false.B }))
  // "Used" flag is 1 for every entry between the current queue head and tail,
  // even if that entry has been invalidated:
  //
  //  used: 000011111
  // valid: 000011011
  //            │ │ └─ head
  //            │ └────invalidated
  //            └──────tail
  //
  // Need this because we can't tell where to enqueue simply by looking at the
  // valid bits.
  private val used = RegInit(UInt(entries.W), 0.U)
  private val elts = Reg(Vec(entries, gen))

  // Indexing is tail-to-head: i=0 equals tail, i=entries-1 equals topmost reg
  def pad(mask: Int => Bool) = { i: Int =>
    if (i == -1) true.B else if (i == entries) false.B else mask(i)
  }
  def paddedUsed = pad({ i: Int => used(i) })
  def validAfterInv(i: Int) = valid(i) && !io.invalidate.bits(i)

  val shift = (used =/= 0.U) && (io.deq.ready || !validAfterInv(0))
  for (i <- 0 until entries) {
    val wdata = if (i == entries - 1) io.queue.enq.bits else Mux(!used(i + 1), io.queue.enq.bits, elts(i + 1))
    val wen = Mux(
      shift,
      // enqueue to the top entry which will be shifted down and make space
      (io.enq.fire && !paddedUsed(i + 1) && used(i)) || pad(validAfterInv)(i + 1),
      // enqueue to the first empty slot above the top
      (io.queue.enq.fire && paddedUsed(i - 1) && !used(i)) || !validAfterInv(i)
    )
    when(wen) { elts(i) := wdata }

    valid(i) := Mux(
      shift,
      (io.queue.enq.fire && !paddedUsed(i + 1) && used(i)) || pad(validAfterInv)(i + 1),
      (io.queue.enq.fire && paddedUsed(i - 1) && !used(i)) || validAfterInv(i)
    )
  }

  when(io.queue.enq.fire) {
    when(!io.queue.deq.fire) {
      used := (used << 1.U) | 1.U
    }
  }.elsewhen(io.queue.deq.fire) {
    used := used >> 1.U
  }

  io.queue.enq.ready := !valid(entries - 1)
  // We don't want to invalidate deq.valid response right away even when
  // io.invalidate(head) is true.
  // Coalescing unit consumes queue head's validity, and produces its new
  // validity.  Deasserting deq.valid right away will result in a combinational
  // cycle.
  io.queue.deq.valid := valid(0)
  io.queue.deq.bits := elts.head

  assert(!flow, "flow-through is not implemented")
  if (flow) {
    when(io.queue.enq.valid) { io.queue.deq.valid := true.B }
    when(!valid(0)) { io.queue.deq.bits := io.queue.enq.bits }
  }

  if (pipe) {
    when(io.queue.deq.ready) { io.queue.enq.ready := true.B }
  }

  io.mask := valid.asUInt
  io.elts := elts
  io.queue.count := PopCount(io.mask)
}

object TLUtils {
  def AOpcodeIsStore(opcode: UInt): Bool = {
    assert(
      opcode === TLMessages.PutFullData || opcode === TLMessages.Get,
      "unhandled TL A opcode found"
    )
    opcode === TLMessages.PutFullData
  }
  def DOpcodeIsStore(opcode: UInt): Bool = {
    assert(
      opcode === TLMessages.AccessAck || opcode === TLMessages.AccessAckData,
      "unhandled TL D opcode found"
    )
    opcode === TLMessages.AccessAck
  }
}

class MemTraceDriver(numLanes: Int = 4, filename: String = "vecadd.core1.thread4.trace")(implicit
    p: Parameters
) extends LazyModule {
  // Create N client nodes together
  val laneNodes = Seq.tabulate(numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, 0x10)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new MemTraceDriverImp(this, numLanes, filename)
}

trait HasTraceLine {
  val valid: UInt
  val source: UInt
  val address: UInt
  val is_store: UInt
  val size: UInt
  val data: UInt
}

// used for both request and response.  response had address set to 0
class TraceLine extends Bundle with HasTraceLine {
  val valid = Bool()
  val source = UInt(32.W)
  val address = UInt(64.W)
  val is_store = Bool()
  val size = UInt(32.W) // this is log2(bytesize) as in TL A bundle
  val data = UInt(64.W)
}

class MemTraceDriverImp(outer: MemTraceDriver, numLanes: Int, traceFile: String)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sim = Module(
    new SimMemTrace(traceFile, numLanes)
  )
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  // Split output of SimMemTrace, which is flattened across all lanes,
  // back to each lane's.

  val laneReqs = Wire(Vec(numLanes, new TraceLine))
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := sim.io.trace_read.valid(i)
    // TODO: don't take source id from the original trace for now
    req.source := 0.U
    req.address := sim.io.trace_read.address(64 * i + 63, 64 * i)
    req.is_store := sim.io.trace_read.is_store(i)
    req.size := sim.io.trace_read.size(32 * i + 31, 32 * i)
    req.data := sim.io.trace_read.data(64 * i + 63, 64 * i)
  }

  // To prevent collision of sourceId with a current in-flight message,
  // just use a counter that increments indefinitely as the sourceId of new
  // messages.
  val sourceIdCounter = RegInit(0.U(64.W))
  sourceIdCounter := sourceIdCounter + 1.U

  // Issue here is that Vortex mem range is not within Chipyard Mem range
  // In default setting, all mem-req for program data must be within
  // 0X80000000 -> 0X90000000
  def hashToValidPhyAddr(addr: UInt): UInt = {
    Cat(8.U(4.W), addr(27, 0))
  }

  // Generate TL requests according to the trace line.
  (outer.laneNodes zip laneReqs).foreach { case (node, req) =>
    val (tlOut, edge) = node.out(0)

    val (plegal, pbits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = hashToValidPhyAddr(req.address),
      lgSize = req.size, // trace line already holds log2(size)
      // data should be aligned to beatBytes
      data = (req.data << (8.U * (req.address % edge.manager.beatBytes.U)))
    )
    val (glegal, gbits) = edge.Get(
      fromSource = sourceIdCounter,
      toAddress = hashToValidPhyAddr(req.address),
      lgSize = req.size
    )
    val legal = Mux(req.is_store, plegal, glegal)
    val bits = Mux(req.is_store, pbits, gbits)

    when(tlOut.a.valid) {
      TracePrintf(
        "MemTraceDriver",
        tlOut.a.bits.address,
        tlOut.a.bits.size,
        tlOut.a.bits.mask,
        req.is_store,
        tlOut.a.bits.data,
        req.data
      )
    }

    assert(legal, "illegal TL req gen")
    tlOut.a.valid := req.valid
    tlOut.a.bits := bits
    tlOut.b.ready := true.B
    tlOut.c.valid := false.B
    tlOut.d.ready := true.B
    tlOut.e.valid := false.B

    println(s"======= MemTraceDriver: TL data width: ${tlOut.params.dataBits}")

    dontTouch(tlOut.a)
    dontTouch(tlOut.d)
  }

  // Give some slack time after trace EOF to the downstream system so that we
  // make sure to receive all outstanding responses.
  val finishCounter = RegInit(200.U(64.W))
  when(sim.io.trace_read.finished) {
    finishCounter := finishCounter - 1.U
  }
  io.finished := (finishCounter === 0.U)
  // when(io.finished) {
  //   assert(
  //     false.B,
  //     "\n\n\nsimulation Successfully finished\n\n\n (this assertion intentional fail upon MemTracer termination)"
  //   )
  // }
}

class SimMemTrace(filename: String, numLanes: Int)
  extends BlackBox(
    Map("FILENAME" -> filename, "NUM_LANES" -> numLanes)
  )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    // These names have to match declarations in the Verilog code, eg.
    // trace_read_address.
    val trace_read = new Bundle { // FIXME: can't use HasTraceLine because this doesn't have source
      val ready = Input(Bool())
      val valid = Output(UInt(numLanes.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Output(UInt((64 * numLanes).W))
      val is_store = Output(UInt(numLanes.W))
      val size = Output(UInt((32 * numLanes).W))
      val data = Output(UInt((64 * numLanes).W))
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
    filename: String = "vecadd.core1.thread4.trace",
    reqEnable: Boolean = true,
    respEnable: Boolean = true,
    // filename suffix that is unique to this logger module.
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
        Some(Module(new SimMemTraceLogger(false, s"${filename}.${loggerName}.req", numLanes)))
      else None
    val simResp =
      if (respEnable)
        Some(Module(new SimMemTraceLogger(true, s"${filename}.${loggerName}.resp", numLanes)))
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
        req.valid := tlIn.a.valid
        req.size := tlIn.a.bits.size
        req.is_store := TLUtils.AOpcodeIsStore(tlIn.a.bits.opcode)
        req.source := tlIn.a.bits.source
        // TL always carries the exact unaligned address that the client
        // originally requested, so no postprocessing required
        req.address := tlIn.a.bits.address

        // TL data
        //
        // When tlIn.a.bits.size is smaller than the data bus width, need to
        // figure out which byte lanes we actually accessed so that
        // we can write that to the memory trace.
        // See Section 4.5 Byte Lanes in spec 1.8.1

        // This assert only holds true for PutFullData and not PutPartialData,
        // where HIGH bits in the mask may not be contiguous.
        assert(
          PopCount(tlIn.a.bits.mask) === (1.U << tlIn.a.bits.size),
          "mask HIGH bits do not match the TL size.  This should have been handled by the TL generator logic"
        )
        val trailingZerosInMask = trailingZeros(tlIn.a.bits.mask)
        val mask = ~((~0.U) << (trailingZerosInMask * 8.U))
        req.data := mask & (tlIn.a.bits.data >> (trailingZerosInMask * 8.U))

        when(req.valid) {
          TracePrintf(
            "MemTraceLogger",
            tlIn.a.bits.address,
            tlIn.a.bits.size,
            tlIn.a.bits.mask,
            req.is_store,
            tlIn.a.bits.data,
            req.data
          )
        }

        // responses on TL D channel
        //
        resp.valid := tlOut.d.valid
        resp.size := tlOut.d.bits.size
        resp.is_store := TLUtils.DOpcodeIsStore(tlOut.d.bits.opcode)
        resp.source := tlOut.d.bits.source
        // NOTE: TL D channel doesn't carry address nor mask, so there's no easy
        // way to figure out which bytes the master actually use.  Since we
        // don't care too much about addresses in the trace anyway, just store
        // the entire bits.
        resp.address := 0.U
        resp.data := tlOut.d.bits.data

        // stats
        when(req.valid) {
          numReqs := numReqs + 1.U
          reqBytes := reqBytes + (1.U << tlIn.a.bits.size)
        }
        when(resp.valid) {
          numResps := numResps + 1.U
          respBytes := respBytes + (1.U << tlOut.d.bits.size)
        }
    }

    // Flatten per-lane signals to the Verilog blackbox input.
    //
    // This is a clunky workaround of the fact that Chisel doesn't allow partial
    // assignment to a bitfield range of a wide signal.
    def flattenTrace(traceLogIO: Bundle with HasTraceLine, perLane: Vec[TraceLine]) = {
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
      traceLogIO.valid := vecValid.asUInt
      traceLogIO.source := vecSource.asUInt
      traceLogIO.address := vecAddress.asUInt
      traceLogIO.is_store := vecIsStore.asUInt
      traceLogIO.size := vecSize.asUInt
      traceLogIO.data := vecData.asUInt
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
class SimMemTraceLogger(isResponse: Boolean, filename: String, numLanes: Int)
    extends BlackBox(
      Map(
        "IS_RESPONSE" -> (if (isResponse) 1 else 0),
        "FILENAME" -> filename,
        "NUM_LANES" -> numLanes
      )
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_log = new Bundle with HasTraceLine {
      val valid = Input(UInt(numLanes.W))
      val source = Input(UInt((32 * numLanes).W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Input(UInt((64 * numLanes).W))
      val is_store = Input(UInt(numLanes.W))
      val size = Input(UInt((32 * numLanes).W))
      val data = Input(UInt((64 * numLanes).W))
      val ready = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTraceLogger.v")
  addResource("/csrc/SimMemTraceLogger.cc")
  addResource("/csrc/SimMemTrace.h")
}

class TracePrintf {}

object TracePrintf {
  def apply(
      printer: String,
      address: UInt,
      size: UInt,
      mask: UInt,
      is_store: Bool,
      tlData: UInt,
      reqData: UInt
  ) = {
    printf(s"${printer}: TL addr=%x, size=%d, mask=%x, store=%d", address, size, mask, is_store)
    when(is_store) {
      printf(", tlData=%x, reqData=%x", tlData, reqData)
    }
    printf("\n")
  }
}

// Synthesizable unit tests

// tracedriver --> coalescer --> tracelogger --> tlram
class TLRAMCoalescerLogger(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numLanes
  val numLanes = 4
  val driver = LazyModule(new MemTraceDriver(numLanes))
  val coreSideLogger = LazyModule(
    new MemTraceLogger(numLanes, loggerName = "coreside")
  )
  val coal = LazyModule(new CoalescingUnit(numLanes))
  val memSideLogger = LazyModule(new MemTraceLogger(numLanes + 1, loggerName = "memside"))
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(address = AddressSet(0x0000, 0xffffff), beatBytes = 8)
    )
  )

  memSideLogger.node :=* coal.node :=* coreSideLogger.node :=* driver.node
  rams.foreach { r => r.node := memSideLogger.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
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
        coreSideLogger.module.io.numReqs === coreSideLogger.module.io.numResps,
        "FAIL: number of requests and responses to the coalescer do not match"
      )
    }
  }
}

class TLRAMCoalescerLoggerTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescerLogger).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}

// tracedriver --> coalescer --> tlram
class TLRAMCoalescer(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numLanes
  val numLanes = 4
  val coal = LazyModule(new CoalescingUnit(numLanes))
  val driver = LazyModule(new MemTraceDriver(numLanes))
  val rams = Seq.fill(numLanes + 1)( // +1 for coalesced edge
    LazyModule(
      // NOTE: beatBytes here sets the data bitwidth of the upstream TileLink
      // edges globally, by way of Diplomacy communicating the TL slave
      // parameters to the upstream nodes.
      new TLRAM(address = AddressSet(0x0000, 0xffffff), beatBytes = 8)
    )
  )

  coal.node :=* driver.node
  rams.foreach { r => r.node := coal.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class TLRAMCoalescerTest(timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMCoalescer).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
