package radiance.muon

import chisel3._
import chisel3.util._
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

abstract class DecodeField(
  val width: Int = 1,
  val essential: Boolean = false // default not stored in ibuffer
)

case object Opcode   extends DecodeField(9, true)
case object F3       extends DecodeField(3, true)
case object F7       extends DecodeField(7, true)
case object Rd       extends DecodeField(8, true)
case object Rs1      extends DecodeField(8, true)
case object Rs2      extends DecodeField(8, true)
case object Rs3      extends DecodeField(8, true)
case object Pred     extends DecodeField(4)
case object IsTMC    extends DecodeField
case object IsWSpawn extends DecodeField
case object IsSplit  extends DecodeField
case object IsJoin   extends DecodeField
case object IsBar    extends DecodeField
case object IsPred   extends DecodeField
case object IsToHost extends DecodeField
case object IsCSR    extends DecodeField(1, true)
case object IsRType  extends DecodeField(1, true)
case object IsIType  extends DecodeField(1, true)
case object IsSType  extends DecodeField(1, true)
case object IsBType  extends DecodeField(1, true)
case object IsUJType extends DecodeField(1, true)
case object HasRd    extends DecodeField(1, true)
case object HasRs1   extends DecodeField(1, true)
case object HasRs2   extends DecodeField(1, true)
case object HasRs3   extends DecodeField(1, true)
case object ImmH8    extends DecodeField(8, true)
case object Imm24    extends DecodeField(24, true)
case object Imm32    extends DecodeField(32)
case object CsrAddr  extends DecodeField(32)
case object CsrImm   extends DecodeField(8, true)
case object ShAmt    extends DecodeField(7)
case object ShOp     extends DecodeField(5)
case object Raw      extends DecodeField(64)

class Decoded(full: Boolean = true) extends Bundle {

  val essentials = MixedVec(Decoder.essentialFields.map(f => UInt(f.width.W)))
  val optionals = Option.when(full)(MixedVec(Decoder.optionalFields.map(f => UInt(f.width.W))))

  def decode(field: DecodeField, signalIdx: Option[Int] = None)(implicit inst: UInt): UInt = {
    val value = field match {
      case Opcode =>    inst(8, 0)
      case F3 =>        inst(19, 17)
      case F7 =>        inst(58, 52)
      case Rd =>        inst(16, 9)
      case Rs1 =>       inst(27, 20)
      case Rs2 =>       inst(35, 28)
      case Rs3 =>       inst(43, 36)
      case Pred =>      inst(63, 60)
      case IsTMC =>     {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 0.U}
      case IsWSpawn =>  {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 1.U}
      case IsSplit =>   {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 2.U}
      case IsJoin =>    {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 3.U}
      case IsBar =>     {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 4.U}
      case IsPred =>    {decode(Opcode) === MuOpcode.CUSTOM0 && decode(F3) === 5.U}
      case IsToHost =>  {decode(Opcode) === MuOpcode.SYSTEM  && decode(F3) === 0.U}
      case IsCSR =>     {decode(Opcode) === MuOpcode.SYSTEM  && decode(F3) =/= 0.U}
      case IsRType =>
        decode(Opcode).isOneOf(
          MuOpcode.CUSTOM0,
          MuOpcode.CUSTOM1,
          MuOpcode.CUSTOM2,
          MuOpcode.CUSTOM3,
          MuOpcode.OP,
          MuOpcode.OP_FP,
        )
      case IsIType =>
        decode(Opcode).isOneOf(
          MuOpcode.LOAD,
    //    MuOpcode.LOAD_FP, // not used
          MuOpcode.MISC_MEM, // fence
          MuOpcode.OP_IMM,
          MuOpcode.SYSTEM,
          MuOpcode.JALR,
        )
      case IsSType =>
        decode(Opcode).isOneOf(
          MuOpcode.STORE,
    //    MuOpcode.STORE_FP, // not used
        )
      case IsBType =>
        decode(Opcode).isOneOf(
          MuOpcode.BRANCH,
        )
      case IsUJType =>
        decode(Opcode).isOneOf(
          MuOpcode.LUI, // should not be generated
          MuOpcode.AUIPC,
          MuOpcode.JAL,
        )
      case HasRd =>  !decodeB(IsBType) && !decodeB(IsSType)
      case HasRs1 => !decodeB(IsUJType)
      case HasRs2 => decodeB(IsRType) || decodeB(IsSType) || decodeB(IsBType)
      case HasRs3 =>
        opcode.isOneOf(
          MuOpcode.MADD,
          MuOpcode.MSUB,
          MuOpcode.NM_ADD,
          MuOpcode.NM_SUB,
          // TODO: maybe amo's here as well
        )
      case CsrAddr => decode(Imm32)
      case CsrImm  => inst(27, 20) // separate from rs1 since that'll be renamed
      case ImmH8 => Mux(decodeB(HasRd),
          inst(35, 28), // i2 type
          inst(16, 9),  // s/b type
        )
      case Imm24 => inst(59, 36)
      case Imm32 => Cat(decode(ImmH8), decode(Imm24))
      case ShAmt => decode(Imm24).asUInt(6, 0)
      case ShOp  => decode(Imm24).asUInt(11, 7)
      case Raw   => inst
    }

    if (field.essential) {
      essentials(signalIdx.getOrElse(Decoder.essentialFields.indexOf(field))) := value
    } else {
      optionals.foreach(_(signalIdx.getOrElse(Decoder.optionalFields.indexOf(field))) := value)
    }

    value
  }

  def decodeB(field: DecodeField)(implicit inst: UInt): Bool = decode(field)(inst)(0)

  def apply(field: DecodeField): UInt = {
    if (field.essential) {
      val index = Decoder.essentialFields.indexOf(field)
      require(index >= 0, s"Field $field not decoded here")
      essentials(index)
    } else {
      if (full) {
        val index = Decoder.optionalFields.indexOf(field)
        require(index >= 0, s"Field $field not decoded here")
        optionals.get(index)
      } else {
        println(s"WARNING\n===============\nOptional field $field is extracted " +
          s"but the decoded bundle is not full. \nIt will be recomputed, but " +
          s"it's best to call expand() to get the full bundle")
        decode(field)(0.U(64.W))
      }
    }
  }

  def b(field: DecodeField): Bool = this(field)(0)

  def opcode = this(Opcode)
  def rs1    = this(Rs1)
  def rs2    = this(Rs2)
  def rs3    = this(Rs3)
  def rd     = this(Rd)
  def f3     = this(F3)
  def f7     = this(F7)

  def shrink(): Decoded = {
    if (full) {
      val shrunk = Wire(new Decoded(full = false))
      shrunk.essentials := this.essentials
      shrunk
    } else {
      this
    }
  }

  def expand(): Decoded = {
    if (full) {
      this
    } else {
      val expanded = Wire(new Decoded(full = true))
      expanded.essentials := this.essentials
      Decoder.optionalFields.zipWithIndex.foreach { case (f, i) =>
        expanded.decode(f, Some(i))(0.U(64.W))
      }
      expanded
    }
  }
}

object Decoder {
  def allDecodeFields: Seq[DecodeField] = {
    Seq(
      Opcode, F3, F7, Rd, Rs1, Rs2, Rs3, Pred,
      IsTMC, IsWSpawn, IsSplit, IsJoin, IsBar, IsPred, IsToHost, IsCSR,
      IsRType, IsIType, IsSType, IsBType, IsUJType,
      HasRd, HasRs1, HasRs2, HasRs3,
      ImmH8, Imm24, Imm32, CsrAddr, CsrImm, ShAmt, ShOp, Raw
    )
  }

  def essentialFields: Seq[DecodeField] = {
    allDecodeFields.filter(_.essential)
  }

  def optionalFields: Seq[DecodeField] = {
    allDecodeFields.filter(!_.essential)
  }

  def decode(inst: UInt): Decoded = {
    val dec = Wire(new Decoded(full = true))
    (essentialFields.zipWithIndex ++ optionalFields.zipWithIndex).foreach { case (f, i) =>
      dec.decode(f, Some(i))(inst)
    }
    dec
  }
}

