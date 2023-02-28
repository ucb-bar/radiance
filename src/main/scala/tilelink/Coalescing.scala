// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.unittest._

class CoalescingUnit(txns: Int = 5000)(implicit p: Parameters) extends LazyModule {
  val fuzz = LazyModule(new TLFuzzer(txns))
  val model = LazyModule(new TLRAMModel("Xbar"))
  val xbar = LazyModule(new TLXbar)

  xbar.node := TLDelayer(0.1) := model.node := fuzz.node
  (0 until 1) foreach { n =>
    val ram  = LazyModule(new TLRAM(AddressSet(0x0+0x400*n, 0x3ff)))
    ram.node := TLFragmenter(4, 256) := TLDelayer(0.1) := xbar.node
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    // io.finished := fuzz.module.io.finished
  }
}

class SimMemTrace()(implicit p: Parameters) extends BlackBox
with HasBlackBoxResource {
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

class CoalescingUnitTest(txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters)
extends UnitTest(timeout) {
  val dut = Module(LazyModule(new CoalescingUnit(txns)).module)
  // dut.io.start := io.start

  val sim = Module(new SimMemTrace)
  sim.io.clock := clock
  sim.io.reset := reset.asBool
  sim.io.trace_read.ready := true.B

  io.finished := !sim.io.trace_read.valid
}
