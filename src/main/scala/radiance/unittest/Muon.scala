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

class MuonFrontendTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val m = p(MuonKey)

  val fe = Module(new Frontend()(p))
  val cbe = Module(new CyclotronBlackBox)

  cbe.io.clock := clock
  cbe.io.reset := reset

  // fe csr, hartid
  fe.io.csr.read := 0.U.asTypeOf(fe.io.csr.read)
  fe.io.hartId := 0.U

  // fe ibuf -> cyclotron back end
  cbe.io.ibuf.bits.fromUop(fe.io.ibuf.bits)
  cbe.io.ibuf.valid := fe.io.ibuf.valid
  fe.io.ibuf.ready := cbe.io.ibuf.ready

  // cyclotron back end -> fe commit
  fe.io.commit := cbe.io.commit

  // fe imem <> cyclotron back end
  cbe.io.imem.req <> fe.io.imem.req
  fe.io.imem.resp <> cbe.io.imem.resp

  io.finished := cbe.io.finished

  dontTouch(fe.io)
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
  be.io.ibuf.valid := cfe.io.ibuf.valid
  be.io.ibuf.bits := cfe.io.ibuf.bits.toUop()
  cfe.io.ibuf.ready := be.io.ibuf.ready

//  (be.io.ibuf zip cfe.io.ibuf).foreach { case (b, f) =>
//    f.ready := b.ready
//    b.valid := f.valid
//    b.bits := f.bits.toUop()
//  }

  dontTouch(be.io)

  // TODO: connect finished from the backend
  io.finished := cfe.io.finished
}

class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val ibuf = Decoupled(new InstBufferEntry)
    val finished = Output(Bool())
  })

  val bbox = Module(new CyclotronBlackBox()(p))
  bbox.io.clock := clock
  bbox.io.reset := reset.asBool

  // connect flattened Verilog IO to Chisel

  io.ibuf.valid := false.B
  io.ibuf.bits := DontCare
//  io.ibuf.zipWithIndex.foreach { case (ib, i) =>
//    ib.valid   := bbox.io.ibuf.valid(i)
//    ib.bits    := DontCare // default
////    ib.bits.pc := bbox.io.ibuf.pc(i)
////    ib.bits.op := bbox.io.ibuf.op(i)
////    ib.bits.rd := bbox.io.ibuf.rd(i)
//  }

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
      "IMM_BITS"  -> 32,
      "PRED_BITS" -> Isa.predBits,
    ))
    with HasBlackBoxResource with HasMuonCoreParameters with HasFrontEndBundles {
  val numWarps = muonParams.numWarps

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    val ibuf = Flipped(Decoupled(new InstBufferEntry))
    val commit = Flipped(commitIO)

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

class MuonFrontendTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonFrontendTestbench()(p))
  io.finished := dut.io.finished
}

class MuonBackendTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonBackendTestbench()(p))
  io.finished := dut.io.finished
}
