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
  val cbe = Module(new CyclotronBackendBlackBox)

  cbe.io.clock := clock
  cbe.io.reset := reset

  // fe csr, hartid
  fe.io.csr.read := 0.U.asTypeOf(fe.io.csr.read)
  fe.io.hartId := 0.U

  // fe decode -> cyclotron back end
  // note issue logic is simple pass-through of decode
  cbe.io.issue.bits.fromUop(fe.io.ibuf.bits)
  cbe.io.issue.valid := fe.io.ibuf.valid
  fe.io.ibuf.ready := cbe.io.issue.ready

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

class MuonLSUTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  // TODO: get instruction trace from cyclotron
  
  val lsu = Module(new LoadStoreUnit()(p))

  // TODO: connect lsu to CyclotronMemBlackbox

  io.finished := true.B
}

class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val ibuf = Decoupled(new InstBufferEntry)
    val finished = Output(Bool())
  })

  val bbox = Module(new CyclotronBackendBlackBox()(p)) // FIXME Frontend
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
  bbox.io.issue.ready := alltrue
  io.finished := bbox.io.finished
}

class CyclotronBackendBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"     -> p(MuonKey).archLen,
      "INST_BITS"    -> p(MuonKey).instBits,
      "NUM_WARPS"    -> p(MuonKey).numWarps,
      "NUM_LANES"    -> p(MuonKey).numLanes,
      "OP_BITS"      -> Isa.opcodeBits,
      "REG_BITS"     -> Isa.regBits,
      "IMM_BITS"     -> 32,
      "CSR_IMM_BITS" -> Isa.csrImmBits,
      "PRED_BITS"    -> Isa.predBits,
    ))
    with HasBlackBoxResource with HasMuonCoreParameters with HasCoreBundles {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    val issue = Flipped(Decoupled(new InstBufferEntry))
    val commit = Flipped(commitIO)

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronBackend.v")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronMemBlackBox(implicit val p: Parameters) extends Module {
  class CyclotronMemBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"  -> p(MuonKey).archLen,
      "NUM_WARPS" -> p(MuonKey).numWarps,
      "NUM_LANES" -> p(MuonKey).numLanes,
      "OP_BITS"   -> Isa.opcodeBits,
      "REG_BITS"  -> Isa.regBits,
      "IMM_BITS"  -> 32,
      "PRED_BITS" -> Isa.predBits,
      "TAG_BITS"  -> p(MuonKey).dcacheReqTagBits,
      "LSU_LANES" -> p(MuonKey).lsu.numLsuLanes,
    )) 
    with HasBlackBoxResource {
  
    val archLen = p(MuonKey).archLen
    val tagBits = p(MuonKey).dcacheReqTagBits
    val lsuLanes = p(MuonKey).lsu.numLsuLanes
    val dataWidth = lsuLanes * archLen
    val maskWidth = lsuLanes
    

    val io = IO(new Bundle {
      val clock = Input(Clock())
      val reset = Input(Bool())

      val req_ready = Output(Bool())
      val req_valid = Input(Bool())

      val req_store = Input(Bool())
      val req_address = Input(UInt(archLen.W))
      val req_tag = Input(UInt(tagBits.W))
      val req_data = Input(UInt(dataWidth.W))
      val req_mask = Input(UInt(maskWidth.W))

      val resp_ready = Input(Bool())
      val resp_valid = Output(Bool())

      val resp_tag = Output(UInt(tagBits.W))
      val resp_data = Output(UInt(dataWidth.W))
    })

    addResource("/vsrc/CyclotronMem.v")
    addResource("/csrc/Cyclotron.cc")
  }
  
  val io = IO(new Bundle {
    val dataMemIO = Flipped(new DataMemIO)
  })

  val inner = Module(new CyclotronMemBlackBox);

  inner.io.clock := clock
  inner.io.reset := reset.asBool

  io.dataMemIO.req.ready := inner.io.req_ready
  inner.io.req_valid := io.dataMemIO.req.valid
  inner.io.req_store := io.dataMemIO.req.bits.store
  inner.io.req_address := io.dataMemIO.req.bits.address
  inner.io.req_tag := io.dataMemIO.req.bits.tag
  inner.io.req_data := io.dataMemIO.req.bits.data
  inner.io.req_mask := io.dataMemIO.req.bits.mask

  inner.io.resp_ready := io.dataMemIO.resp.ready
  io.dataMemIO.resp.valid := inner.io.resp_valid
  io.dataMemIO.resp.bits.tag := inner.io.resp_tag
  io.dataMemIO.resp.bits.data := inner.io.resp_data
  
  io.dataMemIO.resp.bits.metadata := DontCare
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

class MuonLSUTest(timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(new MuonLSUTestbench()(p))
  io.finished := dut.io.finished
}
