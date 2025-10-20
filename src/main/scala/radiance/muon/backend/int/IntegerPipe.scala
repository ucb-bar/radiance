package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import freechips.rocketchip.rocket.ALU
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.{LaneDecomposer, LaneRecomposer}
import radiance.muon.{CoreModule, HasMuonCoreParameters, Isa, MuOpcode}

class IntegerOpBundle extends Bundle {
  val fn = UInt(ALU.SZ_ALU_FN.W)
  val isMulDiv = Bool()
  val isBr = Bool()
  val isJ = Bool()
}

object IntegerOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt): IntegerOpBundle = {
    val table = Seq[(BitPat, BitPat)](
      (BitPat(MuOpcode.OP)     ## BitPat("b000") ## BitPat("b0000000")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b000") ## BitPat("b0100000")) -> BitPat(ALU.FN_SUB.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b001") ## BitPat("b0000000")) -> BitPat(ALU.FN_SL.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b010") ## BitPat("b0000000")) -> BitPat(ALU.FN_SLT.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b011") ## BitPat("b0000000")) -> BitPat(ALU.FN_SLTU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b100") ## BitPat("b0000000")) -> BitPat(ALU.FN_XOR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b101") ## BitPat("b0000000")) -> BitPat(ALU.FN_SR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b101") ## BitPat("b0100000")) -> BitPat(ALU.FN_SRA.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b110") ## BitPat("b0000000")) -> BitPat(ALU.FN_OR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b111") ## BitPat("b0000000")) -> BitPat(ALU.FN_AND.litValue.U(ALU.SZ_ALU_FN.W)),

      (BitPat(MuOpcode.OP)     ## BitPat("b000") ## BitPat("b0000001")) -> BitPat(ALU.FN_MUL.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b001") ## BitPat("b0000001")) -> BitPat(ALU.FN_MULH.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b010") ## BitPat("b0000001")) -> BitPat(ALU.FN_MULHSU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b011") ## BitPat("b0000001")) -> BitPat(ALU.FN_MULHU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b100") ## BitPat("b0000001")) -> BitPat(ALU.FN_DIV.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b101") ## BitPat("b0000001")) -> BitPat(ALU.FN_DIVU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b110") ## BitPat("b0000001")) -> BitPat(ALU.FN_REM.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP)     ## BitPat("b111") ## BitPat("b0000001")) -> BitPat(ALU.FN_REMU.litValue.U(ALU.SZ_ALU_FN.W)),

      (BitPat(MuOpcode.OP_IMM) ## BitPat("b000") ## BitPat("b???????")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b010") ## BitPat("b???????")) -> BitPat(ALU.FN_SLT.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b011") ## BitPat("b???????")) -> BitPat(ALU.FN_SLTU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b100") ## BitPat("b???????")) -> BitPat(ALU.FN_XOR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b110") ## BitPat("b???????")) -> BitPat(ALU.FN_OR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b111") ## BitPat("b???????")) -> BitPat(ALU.FN_AND.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b001") ## BitPat("b???????")) -> BitPat(ALU.FN_SL.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b101") ## BitPat("b0??????")) -> BitPat(ALU.FN_SR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b101") ## BitPat("b1??????")) -> BitPat(ALU.FN_SRA.litValue.U(ALU.SZ_ALU_FN.W)),

      (BitPat(MuOpcode.LUI)    ## BitPat("b???") ## BitPat("b???????")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.AUIPC)  ## BitPat("b???") ## BitPat("b???????")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),

      (BitPat(MuOpcode.BRANCH) ## BitPat("b000") ## BitPat("b???????")) -> BitPat(ALU.FN_SEQ.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.BRANCH) ## BitPat("b001") ## BitPat("b???????")) -> BitPat(ALU.FN_SNE.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.BRANCH) ## BitPat("b100") ## BitPat("b???????")) -> BitPat(ALU.FN_SLT.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.BRANCH) ## BitPat("b101") ## BitPat("b???????")) -> BitPat(ALU.FN_SGE.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.BRANCH) ## BitPat("b110") ## BitPat("b???????")) -> BitPat(ALU.FN_SLTU.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.BRANCH) ## BitPat("b111") ## BitPat("b???????")) -> BitPat(ALU.FN_SGEU.litValue.U(ALU.SZ_ALU_FN.W)),

      (BitPat(MuOpcode.JAL)    ## BitPat("b???") ## BitPat("b???????")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.JALR)   ## BitPat("b000") ## BitPat("b???????")) -> BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W)),
    )

    val opcode7 = opcode(6,0)
    val result = Wire(new IntegerOpBundle)
    result.fn := decoder(Cat(opcode7, f3, f7), TruthTable(table, BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W))))
    result.isMulDiv := (opcode7 === MuOpcode.OP) && (f7 === "b0000001".U)
    result.isBr := opcode7 === MuOpcode.BRANCH
    result.isJ := opcode7 === MuOpcode.JAL || opcode7 === MuOpcode.JALR
    result
  }
}

case class IntegerPipeParams (val numALULanes: Int = 8)

class IntegerPipeReq(implicit val p: Parameters)
  extends Bundle with HasMuonCoreParameters {
  val op = UInt(Isa.opcodeBits.W)
  val f3 = UInt(3.W)
  val f7 = UInt(7.W)
  val in1 = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
  val in2 = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
  val pc = UInt(muonParams.archLen.W) // for b type
  val rd = UInt(Isa.regBits.W)
  val tmask = UInt(muonParams.numLanes.W)
}

class IntegerPipeResp(implicit val p: Parameters)
  extends Bundle with HasMuonCoreParameters {
  val tmask = UInt(muonParams.numLanes.W)
  val pc_w_en = Bool()
  val rd = UInt(Isa.regBits.W)
  val data = Vec(muonParams.numLanes, UInt(muonParams.archLen.W))
}

class IntegerPipe(implicit p: Parameters) extends CoreModule {
  val numLanes = muonParams.numLanes
  val numALULanes = muonParams.integerPipe.numALULanes
  val archLen = muonParams.archLen

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new IntegerPipeReq))
    val resp = Decoupled(new IntegerPipeResp)
  })

  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W))
  val aluDecomposer = Module(new LaneDecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = decomposerTypes
  ))

  val vecALU = Seq.fill(numALULanes)(Module(new ALU))
  vecALU.foreach(alu => alu.io.dw := archLen.U)

  implicit val recomposerTypes =
    Seq(chiselTypeOf(vecALU.head.io.out),
        chiselTypeOf(vecALU.head.io.cmp_out)
    )
  val aluRecomposer = Module(new LaneRecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = recomposerTypes,
  ))

  val ioIntOp = IntegerOpDecoder.decode(io.req.bits.op, io.req.bits.f3, io.req.bits.f7)

  val req_op = Reg(new IntegerOpBundle)
  val req_pc = Reg(UInt(archLen.W))
  val req_tmask = Reg(UInt(numLanes.W))
  val req_rd = Reg(UInt(Isa.regBits.W))
  val resp_valid = RegInit(false.B)
  val alu_out = Reg(Vec(numLanes, UInt(archLen.W)))
  val cmp_out = Reg(Vec(numLanes, Bool()))
  val busy = RegInit(false.B)

  io.req.ready := !busy || io.resp.fire
  aluDecomposer.io.in.valid := io.req.valid && !ioIntOp.isMulDiv
  aluDecomposer.io.in.bits.data(0) := io.req.bits.in1
  aluDecomposer.io.in.bits.data(1) := io.req.bits.in2
  aluDecomposer.io.in.bits.tmask := io.req.bits.tmask
  aluDecomposer.io.out.ready := true.B

  for (i <- 0 until numALULanes) {
    vecALU(i).io.dw := archLen.U
    vecALU(i).io.fn := Mux(io.req.fire, ioIntOp.fn, req_op.fn)
    vecALU(i).io.in1 := aluDecomposer.io.out.bits.data(0)(i)
    vecALU(i).io.in2 := aluDecomposer.io.out.bits.data(1)(i)

    aluRecomposer.io.in.bits.data(0)(i) := vecALU(i).io.out
    aluRecomposer.io.in.bits.data(1)(i) := vecALU(i).io.cmp_out
  }
  aluRecomposer.io.in.valid := aluDecomposer.io.out.valid
  aluRecomposer.io.in.bits.tmask := aluDecomposer.io.out.bits.tmask
  aluRecomposer.io.out.ready := true.B

  io.resp.valid := resp_valid
  io.resp.bits.rd := req_rd
  io.resp.bits.data := Mux(req_op.isBr, VecInit(Seq.fill(numLanes)(req_pc)), alu_out)
  io.resp.bits.tmask := Mux(req_op.isBr,
    cmp_out.asUInt,
    req_tmask
  )
  io.resp.bits.pc_w_en := req_op.isBr || req_op.isJ

  when (io.resp.fire) {
    busy := false.B
    resp_valid := false.B
  }

  when (aluRecomposer.io.out.fire) {
    alu_out := aluRecomposer.io.out.bits.data(0)
    cmp_out := aluRecomposer.io.out.bits.data(1)
    resp_valid := true.B
  }

  when (io.req.fire) {
    busy := true.B
    req_op := ioIntOp
    req_pc := io.req.bits.pc
    req_tmask := io.req.bits.tmask
    req_rd := io.req.bits.rd
  }
}
