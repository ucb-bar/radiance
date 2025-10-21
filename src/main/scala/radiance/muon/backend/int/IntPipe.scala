package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import freechips.rocketchip.rocket.{ALU, MulDivParams}
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

class IntOpBundle extends Bundle {
  val fn = UInt(ALU.SZ_ALU_FN.W)
  val isMulDiv = Bool()
  val isBr = Bool()
  val isJ = Bool()
}

object IntOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt): IntOpBundle = {
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
    val result = Wire(new IntOpBundle)
    result.fn := decoder(Cat(opcode7, f3, f7), TruthTable(table, BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W))))
    result.isMulDiv := (opcode7 === MuOpcode.OP) && (f7 === "b0000001".U)
    result.isBr := opcode7 === MuOpcode.BRANCH
    result.isJ := opcode7 === MuOpcode.JAL || opcode7 === MuOpcode.JALR
    result
  }
}

case class IntPipeParams (val numALULanes: Int = 8,
                          val numMulDivLanes: Int = 8,
                          val mulDivParams: MulDivParams = MulDivParams())

trait HasIntPipeParams extends HasMuonCoreParameters {
  def numALULanes = muonParams.intPipe.numALULanes
  def numMulDivLanes = muonParams.intPipe.numMulDivLanes
  def mulDivParams = muonParams.intPipe.mulDivParams
}

class IntPipeReq(implicit val p: Parameters)
  extends Bundle with HasIntPipeParams with HasCoreBundles {
  val uop = uopT
  val in1 = Vec(numLanes, UInt(archLen.W))
  val in2 = Vec(numLanes, UInt(archLen.W))
}

class IntPipeResp(implicit val p: Parameters)
  extends Bundle with HasIntPipeParams {
  val tmask = UInt(numLanes.W)
  val pc_w_en = Bool()
  val rd = UInt(Isa.regBits.W)
  val data = Vec(numLanes, UInt(archLen.W))
}

abstract class IntPipe(implicit p: Parameters)
  extends CoreModule with HasIntPipeParams {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new IntPipeReq))
    val resp = Decoupled(new IntPipeResp)
  })

  val inst = io.req.bits.uop.inst
  val ioIntOp = IntOpDecoder.decode(inst(Opcode), inst(F3), inst(F7))
  val req_op = Reg(new IntOpBundle)
  val req_pc = Reg(UInt(archLen.W))
  val req_tmask = Reg(UInt(numLanes.W))
  val req_rd = Reg(UInt(Isa.regBits.W))
  val resp_valid = RegInit(false.B)
}