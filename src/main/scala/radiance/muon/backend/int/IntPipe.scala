package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import freechips.rocketchip.rocket.{ALU, MulDivParams}
import radiance.muon._

object IntOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt): UInt = {
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
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b101") ## BitPat("b?0?????")) -> BitPat(ALU.FN_SR.litValue.U(ALU.SZ_ALU_FN.W)),
      (BitPat(MuOpcode.OP_IMM) ## BitPat("b101") ## BitPat("b?1?????")) -> BitPat(ALU.FN_SRA.litValue.U(ALU.SZ_ALU_FN.W)),

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
    decoder(Cat(opcode(6, 0), f3, f7), TruthTable(table, BitPat(ALU.FN_ADD.litValue.U(ALU.SZ_ALU_FN.W))))
  }
}

case class IntPipeParams(numALULanes: Int = 8,
                         numMulDivLanes: Int = 8,
                         mulDivParams: MulDivParams = MulDivParams())

trait HasIntPipeParams extends HasCoreParameters {
  def numALULanes = muonParams.intPipe.numALULanes
  def numMulDivLanes = muonParams.intPipe.numMulDivLanes
  def mulDivParams = muonParams.intPipe.mulDivParams
}
