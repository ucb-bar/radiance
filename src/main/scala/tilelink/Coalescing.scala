// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.unittest._

class CoalescingUnit(numThreads: Int = 1)(implicit p: Parameters)
    extends LazyModule {
  val beatBytes = 8
  val seqParam = Seq(
    TLSlaveParameters.v1(
      address = Seq(AddressSet(0x0000, 0xffffff)),
      // resources = device.reg,
      regionType = RegionType.UNCACHED,
      executable = true,
      supportsArithmetic = TransferSizes(1, beatBytes),
      supportsLogical = TransferSizes(1, beatBytes),
      supportsGet = TransferSizes(1, beatBytes),
      supportsPutFull = TransferSizes(1, beatBytes),
      supportsPutPartial = TransferSizes(1, beatBytes),
      supportsHint = TransferSizes(1, beatBytes),
      fifoId = Some(0)
    )
  )
  val entryNodes = Seq.tabulate(numThreads) { _ =>
    TLManagerNode(Seq(TLSlavePortParameters.v1(seqParam, beatBytes)))
  }

  val node = TLIdentityNode()
  entryNodes.foreach { n => n := node }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // Example 1: accessing the entire A channel data for Thread 0
    val (tlIn0, edge0) = entryNodes(0).in(0)
    dontTouch(tlIn0.a)

    // Example 2: accssing the entire A channel data for Thread 1
    val (tlIn1, edge1) = entryNodes(1).in(0)
    dontTouch(tlIn1.a)
  }
}

class MemTraceDriver(numThreads: Int = 1)(implicit p: Parameters)
    extends LazyModule {
  // Create N client nodes together
  val threadNodes = Seq.tabulate(numThreads) { i =>
    val clients = Seq(
      TLMasterParameters.v1(
        name = "MemTraceDriver" + i.toString,
        sourceId = IdRange(0, 4)
      )
    )
    TLClientNode(Seq(TLMasterPortParameters.v1(clients)))
  }

  // Combine N outgoing client node into 1 idenity node for diplomatic
  // connection.
  val node = TLIdentityNode()
  threadNodes.foreach { threadNode =>
    node := threadNode
  }

  lazy val module = new MemTraceDriverImp(this, numThreads)
}

class TraceReq extends Bundle {
  val valid = Bool()
  val address = UInt(64.W)
}

class MemTraceDriverImp(
    outer: MemTraceDriver,
    numThreads: Int
) extends LazyModuleImp(outer)
    with UnitTestModule {
  val sim = Module(
    new SimMemTrace(filename = "vecadd.core1.thread4.trace", numThreads)
  )
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  // Split output of SimMemTrace, which is flattened across all lanes,
  // back to each thread's.
  val threadReqs = Wire(Vec(numThreads, new TraceReq))
  threadReqs.zipWithIndex.foreach { case (req, i) =>
    req.valid := (sim.io.trace_read.valid >> i)
    req.address := (sim.io.trace_read.address >> (64 * i))
  }

  // Connect each sim module to its respective TL connection
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
  val coal = LazyModule(new CoalescingUnit(numThreads = 4))
  val driver = LazyModule(new MemTraceDriver(numThreads = 4))

  coal.node :=* driver.node

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    driver.module.io.start := io.start
    io.finished := driver.module.io.finished
  }
}

class CoalescingUnitTest(timeout: Int = 500000)(implicit
    p: Parameters
) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new CoalConnectTrace).module)
  dut.io.start := io.start
  io.finished := dut.io.finished
}
