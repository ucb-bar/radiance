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

/** Testbench for Muon frontend pipe */
class MuonFrontendTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  val m = p(MuonKey)

  val fe = Module(new Frontend()(p))
  val cbe = Module(new CyclotronBackendBlackBox)

  cbe.io.clock := clock
  cbe.io.reset := reset.asBool

  // fe csr, hartid
  fe.io.csr.read := 0.U.asTypeOf(fe.io.csr.read)
  fe.io.hartId := 0.U

  // fe decode -> cyclotron back end
  // note issue logic is simple pass-through of decode
  (cbe.io.issue zip fe.io.ibuf).foreach { case (b, f) =>
    b.bits.fromIBufT(f.bits)
    b.valid := f.valid
    f.ready := b.ready
  }

  // cyclotron back end -> fe commit
  fe.io.commit := cbe.io.commit

  // fe imem <> cyclotron back end
  cbe.io.imem.req <> fe.io.imem.req
  fe.io.imem.resp <> cbe.io.imem.resp

  io.finished := cbe.io.finished

  dontTouch(fe.io)
}

/** Testbench for Muon backend pipe */
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
  // Imem in the ISA model is not used
  cfe.io.imem.req.valid := false.B
  cfe.io.imem.req.bits := DontCare
  cfe.io.imem.resp.ready := false.B

  (be.io.ibuf zip cfe.io.ibuf).foreach { case (b, f) =>
    b.valid := f.valid
    f.ready := b.ready
    b.bits := f.bits.toIBufT()
  }
  dontTouch(be.io)

  // TODO: connect finished from the backend
  io.finished := cfe.io.finished
}

/** Testbench for Muon backend LSU pipe */
class MuonLSUTestbench(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val finished = Bool()
  })

  // TODO: get instruction trace from cyclotron
  
  val lsu = Module(new LoadStoreUnit()(p))
  
  val coreGmem = Wire(new DataMemIO)
  val coreShmem = Wire(new SharedMemIO)
  
  val cyclotronGmem = Module(new CyclotronMemBlackBox(coreGmem))
  val cyclotronShmem = Module(new CyclotronMemBlackBox(coreShmem))

  io.finished := false.B
}

/** Wraps Verilog shim to convert messy flattened 1-D arrays into
 *  Chisel-friendly IOs. */
class CyclotronFrontend(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = Flipped(new InstMemIO)
    val ibuf = Vec(muonParams.numWarps, Decoupled(new InstBufferEntry))
    val finished = Output(Bool())
  })

  val cfbox = Module(new CyclotronFrontendBlackBox()(p))
  cfbox.io.clock := clock
  cfbox.io.reset := reset.asBool

  // helpers for connecting flattened Verilog IO to Chisel
  def splitUInt(flattened: UInt, wordBits: Int): Vec[UInt] = {
    require(flattened.getWidth % wordBits == 0)
    val numWords = flattened.getWidth / wordBits
    VecInit.tabulate(numWords)(i => flattened((i + 1) * wordBits - 1, i * wordBits))
  }
  def connectSplit(destWord: UInt, flattened: UInt, index: Int) = {
    require(destWord.widthKnown)
    destWord := splitUInt(flattened, destWord.getWidth)(index)
  }

  // IMem connection
  cfbox.io.imem.req <> io.imem.req
  io.imem.resp <> cfbox.io.imem.resp

  // InstBuf connection
  //
  // @cleanup: prob better to make an accessor method that extracts a single
  // IBufEntry from all the flattened arrays with a given index
  cfbox.io.ibuf.ready := VecInit(io.ibuf.map(_.ready)).asUInt
  io.ibuf.zipWithIndex.foreach { case (ib, i) =>
    ib.valid := cfbox.io.ibuf.valid(i)
    connectSplit(ib.bits.pc, cfbox.io.ibuf.pc, i)
    connectSplit(ib.bits.wid, cfbox.io.ibuf.wid, i)
    connectSplit(ib.bits.op, cfbox.io.ibuf.op, i)
    connectSplit(ib.bits.rd, cfbox.io.ibuf.rd, i)
    connectSplit(ib.bits.rs1, cfbox.io.ibuf.rs1, i)
    connectSplit(ib.bits.rs2, cfbox.io.ibuf.rs2, i)
    connectSplit(ib.bits.rs3, cfbox.io.ibuf.rs3, i)
    connectSplit(ib.bits.imm32, cfbox.io.ibuf.imm32, i)
    connectSplit(ib.bits.imm24, cfbox.io.ibuf.imm24, i)
    connectSplit(ib.bits.csrImm, cfbox.io.ibuf.csrImm, i)
    connectSplit(ib.bits.f3, cfbox.io.ibuf.f3, i)
    connectSplit(ib.bits.f7, cfbox.io.ibuf.f7, i)
    connectSplit(ib.bits.pred, cfbox.io.ibuf.pred, i)
    connectSplit(ib.bits.tmask, cfbox.io.ibuf.tmask, i)
    connectSplit(ib.bits.raw, cfbox.io.ibuf.raw, i)
  }

  io.finished := cfbox.io.finished
}

class CyclotronFrontendBlackBox(implicit val p: Parameters) extends BlackBox(Map(
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
    with HasBlackBoxResource with HasMuonCoreParameters {

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())

    val imem = Flipped(new InstMemIO)
    // flattened for all numWarps
    val ibuf = new Bundle with HasInstBufferEntryFields {
      val ready = Input(UInt(numWarps.W))
      val valid = Output(UInt(numWarps.W))
      val pc = Output(UInt((numWarps * addressBits).W))
      val wid = Output(UInt((numWarps * log2Ceil(muonParams.numWarps)).W))
      val op = Output(UInt((numWarps * Isa.opcodeBits).W))
      val rd = Output(UInt((numWarps * Isa.regBits).W))
      val rs1 = Output(UInt((numWarps * Isa.regBits).W))
      val rs2 = Output(UInt((numWarps * Isa.regBits).W))
      val rs3 = Output(UInt((numWarps * Isa.regBits).W))
      val imm32 = Output(UInt((numWarps * 32).W))
      val imm24 = Output(UInt((numWarps * 24).W))
      val csrImm = Output(UInt((numWarps * Isa.csrImmBits).W))
      val f3 = Output(UInt((numWarps * 3).W))
      val f7 = Output(UInt((numWarps * 7).W))
      val pred = Output(UInt((numWarps * Isa.predBits).W))
      val tmask = Output(UInt((numWarps * muonParams.numLanes).W))
      val raw = Output(UInt((numWarps * muonParams.instBits).W))
    }

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronFrontend.v")
  addResource("/csrc/Cyclotron.cc")
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
    val issue = Flipped(Vec(muonParams.numWarps, Decoupled(new InstBufferEntry)))
    val commit = schedWritebackT

    val finished = Output(Bool())
  })

  addResource("/vsrc/CyclotronBackend.v")
  addResource("/csrc/Cyclotron.cc")
}

class CyclotronMemBlackBox(interface: MemInterface)(implicit val p: Parameters) extends Module {
  class CyclotronMemBlackBox(implicit val p: Parameters) extends BlackBox(Map(
      "ARCH_LEN"  -> p(MuonKey).archLen,
      "NUM_WARPS" -> p(MuonKey).numWarps,
      "NUM_LANES" -> p(MuonKey).numLanes,
      "OP_BITS"   -> Isa.opcodeBits,
      "REG_BITS"  -> Isa.regBits,
      "IMM_BITS"  -> 32,
      "PRED_BITS" -> Isa.predBits,
      "TAG_BITS"  -> interface.getTagBits,
      "LSU_LANES" -> p(MuonKey).lsu.numLsuLanes,
    )) 
    with HasBlackBoxResource {
  
    val archLen = p(MuonKey).archLen
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
      val req_tag = Input(UInt(interface.getTagBits.W))
      val req_data = Input(UInt(dataWidth.W))
      val req_mask = Input(UInt(maskWidth.W))

      val resp_ready = Input(Bool())
      val resp_valid = Output(Bool())

      val resp_tag = Output(UInt(interface.getTagBits.W))
      val resp_data = Output(UInt(dataWidth.W))
    })

    addResource("/vsrc/CyclotronMem.v")
    addResource("/csrc/Cyclotron.cc")
  }
  
  val io = IO(new Bundle {
    val req = Flipped(chiselTypeOf(interface.getReq))
    val resp = Flipped(chiselTypeOf(interface.getResp))
  })

  val inner = Module(new CyclotronMemBlackBox);

  inner.io.clock := clock
  inner.io.reset := reset.asBool

  io.req.ready := inner.io.req_ready
  inner.io.req_valid := io.req.valid
  inner.io.req_store := io.req.bits.store
  inner.io.req_address := io.req.bits.address
  inner.io.req_tag := io.req.bits.tag
  inner.io.req_data := io.req.bits.data
  inner.io.req_mask := io.req.bits.mask

  inner.io.resp_ready := io.resp.ready
  io.resp.valid := inner.io.resp_valid
  io.resp.bits.tag := inner.io.resp_tag
  io.resp.bits.data := inner.io.resp_data
  
  io.resp.bits.metadata := DontCare
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
