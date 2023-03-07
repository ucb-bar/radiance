// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.tilelink.TLTestRAM
import freechips.rocketchip.unittest._

class CoalescingUnit(numThreads: Int = 1)(implicit p: Parameters)
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
  val clientParam = Seq(
    TLMasterParameters.v1(
      name = "CoalescerNode",
      sourceId = IdRange(0, 1),
      visibility = Seq(AddressSet(0x0000, 0xffffff))
    )
  )
  val coalescerNode = TLClientNode(
    Seq(TLMasterPortParameters.v1(clientParam))
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((tlIn, _), (tlOut, edgeOut)) =>
      // out.a <> in.a
      // out.a.bits.data := in.a.bits.data + 0xFF.U
      // out.a.bits.data := 0xFF.U
      // dontTouch(out.a.bits.data)
      tlOut.a.bits := edgeOut
        .Put(
          fromSource = 0.U,
          toAddress = 0.U,
          // 64 bits = 8 bytes = 2**(3) bytes
          lgSize = 3.U,
          // data = (i + 100).U
          data = tlIn.a.bits.data + 0xFF.U
        )
        ._2
      tlIn.d <> tlOut.d
    }
    node.out.foreach { case (tl, _) =>
      dontTouch(tl.a)
    }
    val (tlCoal, _) = coalescerNode.out(0)
    dontTouch(tlCoal.a)
  }
}

class MemTraceDriver(numThreads: Int = 1)(implicit p: Parameters)
    extends LazyModule {
  // Create N client nodes together
  val threadNodes = Seq.tabulate(numThreads) { i =>
    val clientParam = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, numThreads),
        visibility = Seq(AddressSet(0x0000, 0xffffff))
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clientParam)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  threadNodes.foreach { threadNode =>
    node := threadNode
  }

  lazy val module = new MemTraceDriverImp(this, numThreads)
}

class MemTraceDriverImp(outer: MemTraceDriver, numThreads: Int)
    extends LazyModuleImp(outer)
    with UnitTestModule {
  val sim = Module(
    new SimMemTrace(filename = "vecadd.core1.thread4.trace", numThreads)
  )
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  // Split output of SimMemTrace, which is flattened across all threads,
  // back to each thread's.
  val threadReqs = Wire(Vec(numThreads, new TraceReq))
  threadReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := (sim.io.trace_read.valid >> i)
    req.address := (sim.io.trace_read.address >> (64 * i))
  }

  // Connect each thread to its respective TL node.
  (outer.threadNodes zip threadReqs).foreach { case (node, req) =>
    val (tlOut, edge) = node.out(0)
    tlOut.a.valid := req.valid
    // TODO: placeholders, use actual value from trace
    tlOut.a.bits := edge
      .Put(
        fromSource = 0.U,
        toAddress = 0.U,
        // 64 bits = 8 bytes = 2**(3) bytes
        lgSize = 3.U,
        // data = (i + 100).U
        data = req.address
      )
      ._2
    // tl_out.a.bits.mask := 0xf.U
    dontTouch(tlOut.a)
    tlOut.d.ready := true.B
  }

  io.finished := sim.io.trace_read.finished
}

class TraceReq extends Bundle {
  val valid = Bool()
  val address = UInt(64.W)
}

class SimMemTrace(val filename: String, numThreads: Int)
    extends BlackBox(
      Map("FILENAME" -> filename, "NUM_THREADS" -> numThreads)
    )
    with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_read = new Bundle {
      val ready = Input(Bool())
      val valid = Output(UInt(numThreads.W))
      // Chisel can't interface with Verilog 2D port, so flatten all lanes into
      // single wide 1D array.
      // TODO: assumes 64-bit address.
      val address = Output(UInt((64 * numThreads).W))
      val finished = Output(Bool())
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
  addResource("/csrc/SimMemTrace.h")
}

class CoalConnectTrace(implicit p: Parameters) extends LazyModule {
  // TODO: use parameters for numThreads
  val numThreads = 4
  val coal = LazyModule(new CoalescingUnit(numThreads))
  val driver = LazyModule(new MemTraceDriver(numThreads))

  coal.node :=* driver.node

  // Use TLTestRAM as bogus downstream TL manager nodes
  // TODO: swap this out with a memtrace logger
  val rams = Seq.tabulate(numThreads + 1) { _ =>
    LazyModule(
      // TODO: properly propagate beatBytes?
      new TLTestRAM(address = AddressSet(0x0000, 0xffffff), beatBytes = 8)
    )
  }
  // Connect all (N+1) outputs of coal to separate TestRAM modules
  (0 until numThreads).foreach { i => rams(i).node := coal.node }
  rams(numThreads).node := coal.coalescerNode

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
