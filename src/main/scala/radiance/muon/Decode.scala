package radiance.muon

import chisel3._

object MuOpcode {
  val LOAD = "b0000011".U
  val LOAD_FP = "b0000111".U
  val CUSTOM0 = "b0001011".U
  val MISC_MEM = "b0001111".U
  val OP_IMM = "b0010011".U
  val AUIPC = "b0010111".U
  // val OpImm32 = "b0011011".U
  val STORE = "b0100011".U
  val STORE_FP = "b0100111".U
  val CUSTOM1 = "b0101011".U
  // val Amo = "b0101111".U
  val OP = "b0110011".U
  val LUI = "b0110111".U
  val OP32 = "b0111011".U
  val MADD = "b1000011".U
  val MSUB = "b1000111".U
  val NM_SUB = "b1001011".U
  val NM_ADD = "b1001111".U
  val OP_FP = "b1010011".U
  // val OpV = "b1010111".U
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
}

object Decoded {
  def apply(inst: UInt): Decoded = {
    new Decoded(inst)
  }
}


