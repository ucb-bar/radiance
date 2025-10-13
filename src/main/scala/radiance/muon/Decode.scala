package radiance.muon

import chisel3._
import freechips.rocketchip.util.UIntIsOneOf

object MuOpcode {
  val LOAD = "b0000011".U
  // val LOAD_FP = "b0000111".U
  val CUSTOM0 = "b0001011".U
  val MISC_MEM = "b0001111".U
  val OP_IMM = "b0010011".U
  val AUIPC = "b0010111".U
  // val OP_IMM32 = "b0011011".U // e.g. addiw
  val STORE = "b0100011".U
  // val STORE_FP = "b0100111".U
  val CUSTOM1 = "b0101011".U
  // val AMO = "b0101111".U
  val OP = "b0110011".U
  val LUI = "b0110111".U
  // val OP32 = "b0111011".U // e.g. addw
  val MADD = "b1000011".U
  val MSUB = "b1000111".U
  val NM_SUB = "b1001011".U
  val NM_ADD = "b1001111".U
  val OP_FP = "b1010011".U
  // val OP_V = "b1010111".U
  val CUSTOM2 = "b1011011".U
  val BRANCH = "b1100011".U
  val JALR = "b1100111".U
  val JAL = "b1101111".U
  val SYSTEM = "b1110011".U
  val CUSTOM3 = "b1111011".U

  val NU_INVOKE = "b001011011".U
  val NU_INVOKE_IMM = "b001111011".U
  val NU_PAYLOAD = "b011011011".U
  val NU_COMPLETE = "b101011011".U
}


class Decoded(inst: UInt) {
  def opcode = inst(8, 0)

  def f3 = inst(19, 17)
  def f7 = inst(58, 52)

  def rd = inst(16, 9)
  def rs1 = inst(27, 20)
  def rs2 = inst(35, 28)
  def rs3 = inst(43, 36)

  def pred = inst(63, 60)

  // TODO immediates

  def isTMC: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 0.U}
  def isWSpawn: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 1.U}
  def isSplit: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 2.U}
  def isJoin: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 3.U}
  def isBar: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 4.U}
  def isPred: Bool = {opcode === MuOpcode.CUSTOM0 && f3 === 5.U}
  def isToHost: Bool = {opcode === MuOpcode.SYSTEM && f3 === 0.U}
  def isCSR: Bool = {opcode === MuOpcode.SYSTEM && f3 =/= 0.U}

  def isRType: Bool = {
    opcode.isOneOf(
      MuOpcode.CUSTOM0,
      MuOpcode.CUSTOM1,
      MuOpcode.CUSTOM2,
      MuOpcode.CUSTOM3,
      MuOpcode.OP,
      MuOpcode.OP_FP,
    )
  }

  def isIType: Bool = {
    opcode.isOneOf(
      MuOpcode.LOAD,
//    MuOpcode.LOAD_FP, // not used
      MuOpcode.MISC_MEM, // fence
      MuOpcode.OP_IMM,
      MuOpcode.SYSTEM,
      MuOpcode.JALR,
    )
  }

  def isBType: Bool = {
    opcode.isOneOf(
      MuOpcode.BRANCH,
    )
  }

  def isSType: Bool = {
    opcode.isOneOf(
      MuOpcode.STORE,
//    MuOpcode.STORE_FP, // not used
    )
  }

  def isUJType: Bool = {
    opcode.isOneOf(
      MuOpcode.LUI, // should not be generated
      MuOpcode.AUIPC,
      MuOpcode.JAL,
    )
  }

  def hasRd: Bool = {
    !isBType && !isSType
  }

  def hasRs1: Bool = {
    !isUJType
  }

  def hasRs2: Bool = {
    isRType || isSType || isBType
  }

  def hasRs3: Bool = {
    opcode.isOneOf(
      MuOpcode.MADD,
      MuOpcode.MSUB,
      MuOpcode.NM_ADD,
      MuOpcode.NM_SUB,
      // TODO: maybe amo's here as well
    )
  }
}

object Decoded {
  def apply(inst: UInt): Decoded = {
    new Decoded(inst)
  }
}


