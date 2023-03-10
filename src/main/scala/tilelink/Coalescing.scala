// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.util.ShiftQueue
import freechips.rocketchip.unittest._

class CoalRegEntry(val sourceWidth: Int, val addressWidth: Int) extends Bundle {
  val source = UInt(sourceWidth.W)
  val address = UInt(addressWidth.W)
  val data = UInt(64.W /* FIXME hardcoded */ )
}

class CoalescingUnit(numLanes: Int = 1)(implicit p: Parameters)
    extends LazyModule {
  // val beatBytes = 8
  // val seqParam = Seq(
  //   TLSlaveParameters.v1(
  //     address = Seq(AddressSet(0x0000, 0xffffff)),
  //     // resources = device.reg,
  //     regionType = RegionType.UNCACHED,
  //     executable = true,
  //     supportsArithmetic = TransferSizes(1, beatBytes),
  //     supportsLogical = TransferSizes(1, beatBytes),
  //     supportsGet = TransferSizes(1, beatBytes),
  //     supportsPutFull = TransferSizes(1, beatBytes),
  //     supportsPutPartial = TransferSizes(1, beatBytes),
  //     supportsHint = TransferSizes(1, beatBytes),
  //     fifoId = Some(0)
  //   )
  // )

  // Identity node that captures the incoming TL requests and passes them
  // through the other end, dropping coalesced requests.  This is what the
  // upstream node will connect to.
  val node = TLIdentityNode()

  // Master node that actually generates coalesced requests.
  // This and the IdentityNode will be the two outward-facing nodes that the
  // downstream, either L1 or the system bus, will connect to.
  protected val clientParam = Seq(
    TLMasterParameters.v1(
      name = "CoalescerNode",
      sourceId = IdRange(0, 0xffff)
      // visibility = Seq(AddressSet(0x0000, 0xffffff))
    )
  )
  protected val coalescerNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(clientParam))
  )

  // Connect master node as the first of the N+1-th inward edges of the
  // IdentityNode
  node :=* coalescerNode

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // Per-lane FIFO that buffers incoming requests
    val sourceWidth = node.in(0)._1.params.sourceBits
    val addressWidth = node.in(0)._1.params.addressBits
    val coalRegEntry = new CoalRegEntry(sourceWidth, addressWidth)
    val fifos = Seq.tabulate(numLanes) { _ =>
      Module(
        new ShiftQueue(coalRegEntry, 4 /* FIXME hardcoded */ )
      )
    }

    println(s"============= node edges: ${node.in.length}")

    // Override IdentityNode implementation so that we wire node output to the
    // FIFO output, instead of directly passing through node input.
    // See IdentityNode definition in `diplomacy/Nodes.scala`.
    (node.in zip node.out).zipWithIndex.foreach {
      case (((_, edgeIn), _), 0) =>
        // No need to do anything on the edge from coalescerNode
        assert(
          edgeIn.master.masters(0).name == "CoalescerNode",
          "First edge is not connected to the coalescer master node"
        )
      case (((tlIn, _), (tlOut, edgeOut)), i) =>
        val fifo = fifos(i - 1)
        val newReq = Wire(coalRegEntry)
        newReq.source := tlIn.a.bits.source
        newReq.address := tlIn.a.bits.address
        newReq.data := tlIn.a.bits.data

        fifo.io.enq.valid := tlIn.a.valid
        fifo.io.enq.bits := newReq
        // FIXME: deq.ready should respect the ready state of the downstream
        // module, e.g. Xbar or NoC.
        fifo.io.deq.ready := true.B
        val head = fifo.io.deq.bits

        tlOut.a.valid := fifo.io.deq.valid
        // FIXME: generate Get or Put according to read/write
        val (legal, bits) = edgeOut.Get(
          fromSource = head.source,
          // `toAddress` should be aligned to 2**lgSize
          toAddress = head.address,
          // 64 bits = 8 bytes = 2**(3) bytes
          lgSize = 0.U
          // data = (i + 100).U
          // data = tlIn.a.bits.data + 0xFF.U
        )
        assert(legal, "unhandled illegal TL req gen")
        tlOut.a.bits := bits
        tlIn.d <> tlOut.d

        dontTouch(tlIn.a)
        dontTouch(tlOut.a)
        dontTouch(tlOut.d)
    }

    val (tlCoal, edgeCoal) = coalescerNode.out(0)

    // FIXME: currently generating bogus coalesced requests
    tlCoal.a.valid := true.B
    val (legal, bits) = edgeCoal.Get(
      fromSource = 0.U,
      // `toAddress` should be aligned to 2**lgSize
      toAddress = 0xabcd00.U,
      // 64 bits = 8 bytes = 2**(3) bytes
      lgSize = 3.U
    )
    assert(legal, "unhandled illegal TL req gen")
    tlCoal.a.bits := bits

    val coalRespValid = Wire(Bool())
    coalRespValid := tlCoal.a.valid
    val coalRespData = Wire(UInt(tlCoal.params.dataBits.W))
    coalRespData := tlCoal.d.bits.data
    dontTouch(coalRespValid)
    dontTouch(coalRespData)
  }
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
  val sourceIdCounter = Reg(UInt(64.W))
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

    // tl_out.a.bits.mask := 0xf.U
    dontTouch(tlOut.a)
    tlOut.d.ready := true.B
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
