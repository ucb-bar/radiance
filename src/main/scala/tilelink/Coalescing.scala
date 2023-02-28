// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.unittest._

class CoalescingUnit(txns: Int = 5000)(implicit p: Parameters)
    extends LazyModule {
  val fuzz = LazyModule(new TLFuzzer(txns))
  val model = LazyModule(new TLRAMModel("Xbar"))
  val xbar = LazyModule(new TLXbar)

  xbar.node := TLDelayer(0.1) := model.node := fuzz.node
  (0 until 1) foreach { n =>
    val ram = LazyModule(new TLRAM(AddressSet(0x0 + 0x400 * n, 0x3ff)))
    ram.node := TLFragmenter(4, 256) := TLDelayer(0.1) := xbar.node
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // io.finished := fuzz.module.io.finished
  }
}

class MemTraceDriver(implicit p: Parameters) extends LazyModule {
  // TODO: generate TL request here
  // testchipip provides TLHelper.makeClientNode convenience wrapper for this.
  val clients = Seq(
    TLMasterParameters.v1(
      name = "MemTraceDriver",
      sourceId = IdRange(0, 1 /*FIXME*/ )
    )
  )
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(clients)))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) with UnitTestModule {
    val sim = Module(new SimMemTrace)
    sim.io.clock := clock
    sim.io.reset := reset.asBool
    sim.io.trace_read.ready := true.B

    when(sim.io.trace_read.valid) {
      println("sim.io.valid!")
    }

    // we're finished when there is no more memtrace to read
    io.finished := !sim.io.trace_read.valid
  }
}

class SimMemTrace extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val trace_read = new Bundle {
      val valid = Output(Bool())
      val ready = Input(Bool())
      val cycle = Output(UInt(64.W))
      val address = Output(UInt(64.W))
    }
  })

  addResource("/vsrc/SimMemTrace.v")
  addResource("/csrc/SimMemTrace.cc")
}

class CoalescingUnitTest(txns: Int = 5000, timeout: Int = 500000)(implicit
    p: Parameters
) extends UnitTest(timeout) {
  val coal = Module(LazyModule(new CoalescingUnit(txns)).module)
  val driver = Module(LazyModule(new MemTraceDriver).module)
  driver.io.start := io.start

  io.finished := driver.io.finished
}
