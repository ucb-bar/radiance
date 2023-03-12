// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.ShiftQueue
import freechips.rocketchip.unittest._

class CoalescingUnit(numLanes: Int = 1)(implicit p: Parameters)
    extends LazyModule {

  // Describes original, uncoalesced memory requests on each lane
  class UncoalReq(val sourceWidth: Int, val addressWidth: Int) extends Bundle {
    val source = UInt(sourceWidth.W)
    val address = UInt(addressWidth.W)
    val data = UInt(64.W /* FIXME hardcoded */ )
  }

  // Identity node that captures the incoming TL requests and passes them
  // through the other end, dropping coalesced requests.  This node is what
  // will be visible from the external nodes.
  val node = TLIdentityNode()

  // Number of maximum in-flight coalesced requests.  The upper bound of this
  // value would be the sourceId range of a single lane.
  val numInflightCoalRequests = 4

  // Master node that actually generates coalesced requests.
  protected val coalParam = Seq(
    TLMasterParameters.v1(
      name = "CoalescerNode",
      sourceId = IdRange(0, numInflightCoalRequests)
    )
  )
  protected val coalescerNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(coalParam))
  )

  // Connect master node as the first of the N+1-th inward edges of the
  // IdentityNode
  node :=* coalescerNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // Instantiate per-lane queue that buffers incoming requests.
    val sourceWidth = node.in(0)._1.params.sourceBits
    val addressWidth = node.in(0)._1.params.addressBits
    val coalRegEntry = new UncoalReq(sourceWidth, addressWidth)
    val queues = Seq.tabulate(numLanes) { _ =>
      Module(
        new ShiftQueue(coalRegEntry, 4 /* FIXME hardcoded */ )
      )
    }

    println(s"============= node edges: ${node.in.length}")

    // Override IdentityNode implementation so that we wire node output to the
    // queue output, instead of directly passing through node input.
    // See IdentityNode definition in `diplomacy/Nodes.scala`.
    (node.in zip node.out).zipWithIndex.foreach {
      case (((_, edgeIn), _), 0) =>
        // No need to do anything on the edge from coalescerNode
        assert(
          edgeIn.master.masters(0).name == "CoalescerNode",
          "First edge is not connected to the coalescer master node"
        )
      case (((tlIn, _), (tlOut, edgeOut)), i) =>
        val queue = queues(i - 1)
        val newReq = Wire(coalRegEntry)
        newReq.source := tlIn.a.bits.source
        newReq.address := tlIn.a.bits.address
        newReq.data := tlIn.a.bits.data

        queue.io.enq.valid := tlIn.a.valid
        queue.io.enq.bits := newReq
        // FIXME: deq.ready should respect the ready state of the downstream
        // module, e.g. Xbar or NoC.
        queue.io.deq.ready := true.B
        val head = queue.io.deq.bits

        tlOut.a.valid := queue.io.deq.valid
        // FIXME: generate Get or Put according to read/write
        val (legal, bits) = edgeOut.Get(
          fromSource = head.source,
          // `toAddress` should be aligned to 2**lgSize
          toAddress = head.address,
          lgSize = 0.U
        )
        assert(legal, "unhandled illegal TL req gen")
        tlOut.a.bits := bits
        tlIn.d <> tlOut.d

        dontTouch(tlIn.a)
        dontTouch(tlOut.a)
        dontTouch(tlOut.d)
    }

    // Generate coalesced requests
    // FIXME: currently generating bogus coalesced requests
    val coalSourceId = RegInit(0.U(2.W /* FIXME hardcoded */ ))
    coalSourceId := coalSourceId + 1.U

    val (tlCoal, edgeCoal) = coalescerNode.out(0)
    val coalReqAddress = Wire(UInt(tlCoal.params.addressBits.W))
    // TODO: bogus address
    coalReqAddress := (0xabcd.U + coalSourceId) << 4
    val coalReqValid = Wire(Bool())
    // FIXME: copy lane 1's valid signal
    coalReqValid := node.in(1)._1.a.valid

    val (legal, bits) = edgeCoal.Get(
      fromSource = coalSourceId,
      // `toAddress` should be aligned to 2**lgSize
      toAddress = coalReqAddress,
      // 64 bits = 8 bytes = 2**(3) bytes
      lgSize = 3.U
    )
    assert(legal, "unhandled illegal TL req gen")
    tlCoal.a.valid := coalReqValid
    tlCoal.a.bits := bits
    tlCoal.b.ready := true.B
    tlCoal.c.valid := false.B
    tlCoal.d.ready := true.B
    tlCoal.e.valid := false.B

    // Populate inflight coalesced request table for use in un-coalescing
    // responses back to the individual lanes that they originated from.
    val inflightCoalReqTableEntry =
      new InflightCoalReqTableEntry(numLanes, sourceWidth)
    val inflightCoalReqTable =
      Module(
        new InflightCoalReqTable(numLanes, sourceWidth, numInflightCoalRequests)
      )
    val tableEntry = Wire(inflightCoalReqTableEntry)
    tableEntry.respSourceId := coalSourceId
    // TODO: bogus fromLane.  Take the lowest numLane bits off of coalSourceId
    tableEntry.fromLane := coalSourceId & ((2 << numLanes) - 1).U
    // FIXME: I'm positive this is not the right way to do this
    tableEntry.reqSourceIds(0) := 0.U
    tableEntry.reqSourceIds(1) := 0.U
    tableEntry.reqSourceIds(2) := 0.U
    tableEntry.reqSourceIds(3) := 0.U
    dontTouch(tableEntry)

    inflightCoalReqTable.io.enq.valid := coalReqValid
    inflightCoalReqTable.io.enq.bits := tableEntry

    // Look up the table with incoming coalesced responses
    inflightCoalReqTable.io.lookup.ready := tlCoal.d.valid
    inflightCoalReqTable.io.lookupSourceId := tlCoal.d.bits.source

    (node.in zip node.out)(0) match {
      case ((tlIn, edgeIn), (tlOut, _)) =>
        assert(
          edgeIn.master.masters(0).name == "CoalescerNode",
          "First edge is not connected to the coalescer master node"
        )

        tlOut.a <> tlIn.a
        // No need to drop any incoming coalesced responses, so just passthrough
        // to master node
        tlIn.d <> tlOut.d
        dontTouch(tlIn.d)
        dontTouch(tlOut.d)
    }

    // Debug
    dontTouch(coalReqValid)
    dontTouch(coalReqAddress)
    val coalRespData = Wire(UInt(tlCoal.params.dataBits.W))
    coalRespData := tlCoal.d.bits.data
    dontTouch(coalRespData)

    dontTouch(tlCoal.a)
    dontTouch(tlCoal.d)
  }
}

// InflightCoalReqTable is a reservation station-like structure that records
// for each unanswered coalesced request which lane the request originated
// from, what their original sourceId were, etc.  We use this info to split
// the coalesced response back to individual responses for each lanes with
// the right metadata.
class InflightCoalReqTable(
    val numLanes: Int,
    val sourceWidth: Int,
    val entries: Int
) extends Module {
  private val inflightCoalReqEntryT =
    new InflightCoalReqTableEntry(numLanes, sourceWidth)

  val io = IO(new Bundle {
    val enq = Flipped(EnqIO(inflightCoalReqEntryT))
    val lookup = Decoupled(UInt(sourceWidth.W))
    // TODO: put this inside decoupledIO
    val lookupSourceId = Input(UInt(sourceWidth.W))
  })

  val table = Mem(
    entries,
    new Bundle {
      val valid = Bool()
      val bits = new InflightCoalReqTableEntry(numLanes, sourceWidth)
    }
  )

  when(reset.asBool) {
    (0 until entries).foreach(i => table(i).valid := false.B)
  }

  val full = Wire(Bool())
  full := (0 until entries)
    .map { i => table(i).valid }
    .reduce { (v0, v1) => v0 && v1 }

  // Enqueue logic
  //
  // Instantiate simple cascade of muxes that indicate what is the current
  // minimum index that has an empty spot in the table.
  val cascadeEmptyIndex = Seq.tabulate(entries) { i => WireInit(i.U) }
  (0 until entries - 1).reverse.foreach { i =>
    val empty = !table(i).valid
    assert(i + 1 < entries)
    // If entry with a lower index is empty, it always takes priority
    cascadeEmptyIndex(i) := Mux(empty, i.U, cascadeEmptyIndex(i + 1))
  }
  val chosenEmptyIndex = cascadeEmptyIndex(0)
  dontTouch(chosenEmptyIndex)
  dontTouch(full)

  val enqFire = io.enq.ready && io.enq.valid
  when(enqFire) {
    val entry = table(chosenEmptyIndex)
    entry.valid := true.B
    entry.bits := io.enq.bits
  }

  io.enq.ready := !full

  // Currently, we assume coalescer never blocks generating coalesced requests.
  // If this ever happens, it means the table is insufficiently large to keep
  // track of the maximum number of in-flight requests and should be enlarged
  // in size.
  // assert(!full, "coalescer is blocking responses")

  // Lookup logic
  //
  // Same deal as cascadeEmptyIndex, but for finding a respSourceId match
  // FIXME: tree structure may be better. Any library for instantiating CAM?
  val cascadeMatchIndex = Seq.tabulate(entries) { i => WireInit(i.U) }
  (0 until entries - 1).reverse.foreach { i =>
    val match_ = table(i).bits.respSourceId === io.lookupSourceId
    assert(i + 1 < entries)
    // If entry with a lower index is empty, it always takes priority
    cascadeMatchIndex(i) := Mux(match_, i.U, cascadeMatchIndex(i + 1))
  }
  val matchIndex = cascadeMatchIndex(0)
  val matchValid = Wire(Bool())
  matchValid := table(matchIndex).bits.respSourceId === io.lookupSourceId
  io.lookup.valid := matchValid
  // TODO: return something actually useful
  io.lookup.bits := table(matchIndex).bits.respSourceId

  val lookupFire = io.lookup.ready && io.lookup.valid
  when(lookupFire) {
    // As soon as a lookup returns a match, dequeue that entry
    table(matchIndex).valid := false.B
  }
  dontTouch(io.lookup)
  dontTouch(matchIndex)
  dontTouch(matchValid)
}

class InflightCoalReqTableEntry(val numLanes: Int, val sourceWidth: Int)
    extends Bundle {
  // sourceId of the coalesced response that just came back.  This will be the
  // key that queries the table.
  val respSourceId = UInt(sourceWidth.W)
  // Bit flags that show which lanes got coalesced into this request
  val fromLane = UInt(numLanes.W)
  // sourceId of the original requests before getting coalesced.  We need to
  // remember this in order to answer the right outstanding TL request on each
  // lane.
  val reqSourceIds = Vec(numLanes, UInt(sourceWidth.W))
}

class MemTraceDriver(numLanes: Int = 1)(implicit p: Parameters)
    extends LazyModule {
  // Create N client nodes together
  val laneNodes = Seq.tabulate(numLanes) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, 0xffff)
        // visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  laneNodes.foreach { l => node := l }

  lazy val module = new MemTraceDriverImp(this, numLanes)
}

class TraceReq extends Bundle {
  val valid = Bool()
  val address = UInt(64.W)
  val is_store = Bool()
  val mask = UInt(8.W)
  val data = UInt(64.W)
}

class MemTraceDriverImp(outer: MemTraceDriver, numLanes: Int)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sim = Module(
    new SimMemTrace(filename = "vecadd.core1.thread4.trace", numLanes)
  )
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  // Split output of SimMemTrace, which is flattened across all lanes,
  // back to each lane's.

  // Maybe this part can be improved, since now we are still mannually shifting everything
  val laneReqs = Wire(Vec(numLanes, new TraceReq))
  laneReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := (sim.io.trace_read.valid >> i)
    req.address := (sim.io.trace_read.address >> (64 * i))
    req.is_store := (sim.io.trace_read.is_store >> i)
    req.mask := (sim.io.trace_read.store_mask >> (8 * i))
    req.data := (sim.io.trace_read.data >> (64 * i))

  }

  // To prevent collision of sourceId with a current in-flight message,
  // just use a counter that increments indefinitely as the sourceId of new
  // messages.
  val sourceIdCounter = RegInit(0.U(64.W))
  sourceIdCounter := sourceIdCounter + 1.U

  // Connect each lane to its respective TL node.
  (outer.laneNodes zip laneReqs).foreach { case (node, req) =>
    val (tlOut, edge) = node.out(0)
    tlOut.a.valid := req.valid

    val (plegal, pbits) = edge.Put(
      fromSource = sourceIdCounter,
      toAddress = req.address,
      // Memory trace addresses are not aligned in word addresses (e.g.
      // read of size 1 at 0x1007) so leave lgSize to 0.
      // TODO: We need to build an issue logic that aligns addresses at
      // word boundaries and uses masks.
      // NOTE: this is in byte size, not bits
      lgSize = 0.U,
      data = req.data
    )
    val (glegal, gbits) = edge.Get(
      fromSource = sourceIdCounter,
      toAddress = req.address,
      lgSize = 0.U
    )
    val legal = Mux(req.is_store, plegal, glegal)
    val bits = Mux(req.is_store, pbits, gbits)
    assert(legal, "unhandled illegal TL req gen")
    tlOut.a.bits := bits
    tlOut.b.ready := true.B
    tlOut.c.valid := false.B
    tlOut.d.ready := true.B
    tlOut.e.valid := false.B

    dontTouch(tlOut.a)
  }

  io.finished := sim.io.trace_read.finished

  // Clock Counter, for debugging purpose
  val clkcount = RegInit(0.U(64.W))
  clkcount := clkcount + 1.U
  dontTouch(clkcount)
}

class SimMemTrace(val filename: String, numLanes: Int)
    extends BlackBox(
      Map("FILENAME" -> filename, "NUM_LANES" -> numLanes)
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    // These names have to match declarations in the Verilog code, eg.
    // trace_read_address.
    val trace_read = new Bundle {
      val ready = Input(Bool())
      val valid = Output(UInt(numLanes.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Output(UInt((64 * numLanes).W))
      val is_store = Output(UInt(numLanes.W))
      val store_mask = Output(UInt((8 * numLanes).W))
      val data = Output(UInt((64 * numLanes).W))
      val finished = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}

class CoalConnectTrace(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numLanes
  val numLanes = 4
  val coal = LazyModule(new CoalescingUnit(numLanes))
  val driver = LazyModule(new MemTraceDriver(numLanes))

  coal.node :=* driver.node

  // Use TLTestRAM as bogus downstream TL manager nodes
  // TODO: swap this out with a memtrace logger
  val rams = Seq.tabulate(numLanes + 1) { _ =>
    LazyModule(
      // TODO: properly propagate beatBytes?
      new TLRAM(address = AddressSet(0x0000, 0xffffff), beatBytes = 8)
    )
  }
  // Connect all (N+1) outputs of coal to separate TestRAM modules
  rams.foreach { r => r.node := coal.node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class CoalescingUnitTest(timeout: Int = 500000)(implicit p: Parameters)
    extends UnitTest(timeout) {
  val dut = Module(LazyModule(new CoalConnectTrace).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
