// See LICENSE.SiFive for license details.
// See LICENSE.Berkeley for license details.

package radiance.unittest

import chisel3._
import chisel3.util._
import freechips.rocketchip.unittest.{UnitTest, UnitTestModule}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.muon._

/** Testbench for Muon with the test signals */
class MuonTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val muon = Module(new MuonCore()(p))
  muon.io.imem.resp.valid := false.B
  muon.io.imem.resp.bits := DontCare
  muon.io.imem.req.ready := false.B
  muon.io.dmem.resp.valid := false.B
  muon.io.dmem.resp.bits := DontCare
  muon.io.dmem.req.ready := false.B
  muon.io.smem.resp.valid := false.B
  muon.io.smem.resp.bits := DontCare
  muon.io.smem.req.ready := false.B

  io.finished := true.B
}

/** Testbench for Muon backend */
class MuonBackendTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val be = Module(new Backend()(p))
  be.io.dmem.resp.valid := false.B
  be.io.dmem.resp.bits := DontCare
  be.io.dmem.req.ready := false.B
  be.io.smem.resp.valid := false.B
  be.io.smem.resp.bits := DontCare
  be.io.smem.req.ready := false.B

  val cfe = Module(new CyclotronFrontend()(p))
  be.io.ibuf <> cfe.io.ibuf

  dontTouch(be.io)

  // TODO: connect finished from the backend
  io.finished := cfe.io.finished
}

class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val ibuf = Vec(muonParams.numWarps, Decoupled(new InstBufferEntry))
    val finished = Output(Bool())
  })

  val bbox = Module(new CyclotronBlackBox()(p))
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  // connect flattened Verilog IO to Chisel
  io.ibuf.zipWithIndex.foreach { case (ib, i) =>
    ib.valid   := bbox.io.ibuf.valid(i)
    ib.bits    := DontCare // default
    ib.bits.pc := bbox.io.ibuf.pc(i)
    ib.bits.op := bbox.io.ibuf.op(i)
    ib.bits.rd := bbox.io.ibuf.rd(i)
  }

  // TODO: correct ready
  val alltrue = VecInit((0 to muonParams.numWarps).map(_ => true.B)).asUInt
  bbox.io.ibuf.ready := alltrue
  io.finished := bbox.io.finished
}

class CyclotronBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"  -> p(MuonKey).archLen,
      "NUM_WARPS" -> p(MuonKey).numWarps,
      "NUM_LANES" -> p(MuonKey).numLanes,
      "OP_BITS"   -> Isa.opcodeBits,
      "REG_BITS"  -> Isa.regBits,
      "IMM_BITS"  -> Isa.immBits,
      "PRED_BITS" -> Isa.predBits,
    ))
    with HasBlackBoxResource with HasMuonCoreParameters {
  val numWarps = muonParams.numWarps

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val ibuf = new Bundle {
      val ready = Input(UInt(numWarps.W))
      val valid = Output(UInt(numWarps.W))
      val pc = Output(UInt((addressBits * numWarps).W))
      val op = Output(UInt((Isa.opcodeBits * numWarps).W))
      val rd = Output(UInt((Isa.regBits * numWarps).W))
    }
    val finished = Output(Bool())
  })

  addResource("/vsrc/Cyclotron.v")
  addResource("/csrc/Cyclotron.cc")
}

// UnitTest harnesses
// ------------------

class MuonTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonTestbench()(p))
  io.finished := dut.io.finished
}

class MuonBackendTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonBackendTestbench()(p))
  io.finished := dut.io.finished
}
