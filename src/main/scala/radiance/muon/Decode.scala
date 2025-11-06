package radiance.muon

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode.TruthTable

object MuOpcode {
  val LOAD = "b000000011"
  // val LOAD_FP = "b0000111"
  val CUSTOM0 = "b000001011"
  val MISC_MEM = "b000001111"
  val OP_IMM = "b000010011"
  val AUIPC = "b000010111"
  // val OP_IMM32 = "b0011011" // e.g. addiw
  val STORE = "b000100011"
  // val STORE_FP = "b0100111"
  val CUSTOM1 = "b000101011"
  // val AMO = "b0101111"
  val OP = "b000110011"
  val LUI = "b000110111"
  // val OP32 = "b0111011" // e.g. addw
  val MADD = "b001000011"
  val MSUB = "b001000111"
  val NM_SUB = "b001001011"
  val NM_ADD = "b001001111"
  val OP_FP = "b001010011"
  // val OP_V = "b1010111"
  val CUSTOM2 = "b001011011"
  val BRANCH = "b001100011"
  val JALR = "b001100111"
  val JAL = "b001101111"
  val SYSTEM = "b001110011"
  val CUSTOM3 = "b001111011"

  val NU_INVOKE = "b001011011"
  val NU_INVOKE_IMM = "b001111011"
  val NU_PAYLOAD = "b011011011"
  val NU_COMPLETE = "b101011011"
}

abstract class DecodeField(
  val width: Int = 1,
  val essential: Boolean = false // default not stored in ibuffer
)

case object Opcode           extends DecodeField(9, true)
case object F3               extends DecodeField(3, true)
case object F7               extends DecodeField(7, true)
case object Rd               extends DecodeField(8, true)
case object Rs1              extends DecodeField(8, true)
case object Rs2              extends DecodeField(8, true)
case object Rs3              extends DecodeField(8, true)
case object Pred             extends DecodeField(4)
case object IsTMC            extends DecodeField
case object IsWSpawn         extends DecodeField
case object IsSplit          extends DecodeField
case object IsJoin           extends DecodeField
case object IsBar            extends DecodeField
case object IsPred           extends DecodeField
case object IsToHost         extends DecodeField
case object IsCSR            extends DecodeField(1, true)
case object IsCSRRW          extends DecodeField
case object IsCSRRS          extends DecodeField
case object IsCSRRC          extends DecodeField
case object IsCSRRWI         extends DecodeField
case object IsCSRRSI         extends DecodeField
case object IsCSRRCI         extends DecodeField
case object IsRType          extends DecodeField(1, true)
case object IsIType          extends DecodeField(1, true)
case object IsSType          extends DecodeField(1, true)
case object IsBType          extends DecodeField(1, true)
case object IsUJType         extends DecodeField(1, true)
case object UseALUPipe       extends DecodeField(1, true)
case object UseMulDivPipe    extends DecodeField(1, true)
case object UseFPPipe        extends DecodeField(1, true)
case object UseFP32Pipe      extends DecodeField(1, true)
case object UseFP16Pipe      extends DecodeField(1, true)
case object UseLSUPipe       extends DecodeField(1, true)
case object UseSFUPipe       extends DecodeField(1, true)
case object HasRd            extends DecodeField(1, true)
case object HasRs1           extends DecodeField(1, true)
case object HasRs2           extends DecodeField(1, true)
case object HasRs3           extends DecodeField(1, true)
case object HasControlHazard extends DecodeField
case object Rs1IsPC          extends DecodeField(1, true)
case object Rs1IsZero        extends DecodeField(1, true)
case object Rs2IsImm         extends DecodeField(1, true)
case object IsBranch         extends DecodeField
case object IsJump           extends DecodeField
case object ImmH8            extends DecodeField(8, true)
case object Imm24            extends DecodeField(24, true)
case object Imm32            extends DecodeField(32)
case object CsrAddr          extends DecodeField(32)
case object CsrImm           extends DecodeField(8, true)
case object ShAmt            extends DecodeField(7)
case object ShOp             extends DecodeField(5)
case object LuiImm           extends DecodeField(32)
case object Raw              extends DecodeField(64)

class Decoded(full: Boolean = true) extends Bundle {

  val essentials = MixedVec(Decoder.essentialFields.map(f => UInt(f.width.W)))
  val optionals = Option.when(full)(MixedVec(Decoder.optionalFields.map(f => UInt(f.width.W))))

  def decode(field: DecodeField, signalIdx: Option[Int] = None)
            (implicit inst: UInt): UInt = {
    if (!full && field.essential) {
      // this should be pre-assigned in `shrink()`, short circuit to prevent decoding from false inst
      this(field)
    } else {
      val value = field match {
        case Opcode =>    inst(8, 0)   // TODO: opcode, f3 and f7 should not be in the bundle!
        case F3 =>        inst(19, 17)
        case F7 =>        inst(58, 52)
        case Rd =>        inst(16, 9)
        case Rs1 =>       inst(27, 20)
        case Rs2 =>       inst(35, 28)
        case Rs3 =>       inst(43, 36)
        case Pred =>      inst(63, 60)
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
        case LuiImm => (decode(Imm24) << 12.U)(31, 0)
        case UseFP32Pipe => decodeB(UseFPPipe) && (decode(F7)(inst)(1, 0) === "b00".U)
        case UseFP16Pipe => decodeB(UseFPPipe) && (decode(F7)(inst)(1, 0) === "b10".U)
        case Raw   => Cat(decode(Pred), decode(Imm24), decode(Rs2), decode(CsrImm), decode(F3), decode(Rd), decode(Opcode))
        case _ =>
          chisel3.util.experimental.decode.decoder(
            Cat(decode(Opcode), decode(F3), decode(F7)),
            Decoder.table
          )(Decoder.tableIndices(field))
      }

      if (field.essential) {
        essentials(signalIdx.getOrElse(Decoder.essentialFields.indexOf(field))) := value
      } else {
        optionals.foreach(_(signalIdx.getOrElse(Decoder.optionalFields.indexOf(field))) := value)
      }

      value
    }
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
        require(false, s"ERROR\n===============\nOptional field $field is extracted")
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
        expanded.optionals.get(i) := this.decode(f, Some(i))(0.U)

//        expanded.decode(f, Some(i))(0.U(64.W), from) // TODO
      }
      expanded
    }
  }
}

object Decoder {
  def allOpcodes: Seq[String] = {
    Seq(
      MuOpcode.LOAD, MuOpcode.CUSTOM0, MuOpcode.MISC_MEM, MuOpcode.OP_IMM, MuOpcode.AUIPC,
      MuOpcode.STORE, MuOpcode.CUSTOM1, MuOpcode.OP, MuOpcode.LUI, MuOpcode.MADD, MuOpcode.MSUB,
      MuOpcode.NM_SUB, MuOpcode.NM_ADD, MuOpcode.OP_FP, MuOpcode.CUSTOM2, MuOpcode.BRANCH,
      MuOpcode.JALR, MuOpcode.JAL, MuOpcode.SYSTEM, MuOpcode.CUSTOM3
    )
  }

  def allDecodeFields: Seq[DecodeField] = {
    Seq(
      Opcode, F3, F7, Rd, Rs1, Rs2, Rs3, Pred,
      IsTMC, IsWSpawn, IsSplit, IsJoin, IsBar, IsPred, IsToHost, IsCSR,
      IsCSRRW, IsCSRRS, IsCSRRC, IsCSRRWI, IsCSRRSI, IsCSRRCI,
      IsRType, IsIType, IsSType, IsBType, IsUJType,
      UseALUPipe, UseMulDivPipe, UseFPPipe, UseFP32Pipe, UseFP16Pipe, UseLSUPipe, UseSFUPipe,
      HasRd, HasRs1, HasRs2, HasRs3, HasControlHazard,
      Rs1IsPC, Rs1IsZero, Rs2IsImm, IsBranch, IsJump,
      ImmH8, Imm24, Imm32, CsrAddr, CsrImm, ShAmt, ShOp, LuiImm, Raw
    )
  }

  def essentialFields: Seq[DecodeField] = {
    allDecodeFields.filter(_.essential)
  }

  def optionalFields: Seq[DecodeField] = {
    allDecodeFields.filter(!_.essential)
  }

  def staticDecode(field: DecodeField, op: String,
                   f3: LazyField, f7: LazyField): Option[Boolean] = {
    def sd(f: DecodeField) = staticDecode(f, op, f3, f7).get
    field match {
      case IsRType =>
        Some(Seq(
          MuOpcode.CUSTOM0,
          MuOpcode.CUSTOM1,
          MuOpcode.CUSTOM2,
          MuOpcode.CUSTOM3,
          MuOpcode.OP,
          MuOpcode.OP_FP,
        ).contains(op))
      case IsIType =>
        Some(Seq(
          MuOpcode.LOAD,
    //    MuOpcode.LOAD_FP, // not used
          MuOpcode.MISC_MEM, // fence
          MuOpcode.OP_IMM,
          MuOpcode.SYSTEM,
          MuOpcode.JALR,
        ).contains(op))
      case IsSType =>
        Some(Seq(
          MuOpcode.STORE,
    //    MuOpcode.STORE_FP, // not used
        ).contains(op))
      case IsBType =>
        Some(Seq(
          MuOpcode.BRANCH,
        ).contains(op))
      case IsUJType =>
        Some(Seq(
          MuOpcode.LUI, // should not be generated
          MuOpcode.AUIPC,
          MuOpcode.JAL,
        ).contains(op))
      case UseALUPipe =>
        Some(Seq(
          MuOpcode.OP,
          MuOpcode.OP_IMM,
          MuOpcode.AUIPC,
          MuOpcode.BRANCH,
          MuOpcode.LUI,
          MuOpcode.JALR,
          MuOpcode.JAL,
        ).contains(op) &&
          ((op != MuOpcode.OP) || (f7 == "??????0")) // mul/div
        )
      case UseMulDivPipe =>
        Some((op == MuOpcode.OP) && (f7 == "??????1"))
      case UseFPPipe =>
        Some(Seq(
          MuOpcode.OP_FP,
          MuOpcode.MADD,
          MuOpcode.MSUB,
          MuOpcode.NM_SUB,
          MuOpcode.NM_ADD,
        ).contains(op))
      case UseLSUPipe =>
        Some(Seq(
          MuOpcode.LOAD,
          MuOpcode.STORE,
          MuOpcode.MISC_MEM
        ).contains(op))
      case UseSFUPipe =>
        Some(Seq(
          MuOpcode.CUSTOM0,
          MuOpcode.CUSTOM1,
          MuOpcode.CUSTOM2,
          MuOpcode.CUSTOM3,
          MuOpcode.SYSTEM,
          MuOpcode.NU_INVOKE,
          MuOpcode.NU_INVOKE_IMM,
          MuOpcode.NU_PAYLOAD,
          MuOpcode.NU_COMPLETE,
        ).contains(op))
      case HasRd =>
        Some(!sd(IsBType) && !sd(IsSType))
      case HasRs1 =>
        Some(!sd(IsUJType))
      case HasRs2 =>
        Some(sd(IsRType) || sd(IsSType) || sd(IsBType))
      case HasRs3 =>
        Some(Seq(
          MuOpcode.MADD,
          MuOpcode.MSUB,
          MuOpcode.NM_ADD,
          MuOpcode.NM_SUB,
          // TODO: maybe amo's here as well
        ).contains(op))
      case HasControlHazard =>
        Some(Seq(
          MuOpcode.JALR,
          MuOpcode.JAL,
          MuOpcode.SYSTEM,
          MuOpcode.BRANCH
        ).contains(op) ||
          sd(IsTMC) || sd(IsSplit) || sd(IsPred) || sd(IsWSpawn) || sd(IsBar)
        )
      case Rs1IsPC =>
        Some(Seq(
          MuOpcode.AUIPC,
          // MuOpcode.BRANCH,
          MuOpcode.JAL,
          // MuOpcode.JALR,
        ).contains(op))
      case Rs1IsZero => Some(op == MuOpcode.LUI)
      case Rs2IsImm =>
        Some(Seq(
          MuOpcode.LOAD,
          MuOpcode.STORE,
          MuOpcode.JAL,
          MuOpcode.JALR,
          MuOpcode.OP_IMM,
          MuOpcode.AUIPC,
          // MuOpcode.LUI,
          // MuOpcode.BRANCH,
        ).contains(op))
      case IsBranch =>  Some(op == MuOpcode.BRANCH)
      case IsJump =>    Some(op == MuOpcode.JAL || op == MuOpcode.JALR)
      // important: when writing these rules, make sure opcode check short circuits f3
      case IsTMC =>     Some(op == MuOpcode.CUSTOM0 && f3 == 0)
      case IsWSpawn =>  Some(op == MuOpcode.CUSTOM0 && f3 == 1)
      case IsSplit =>   Some(op == MuOpcode.CUSTOM0 && f3 == 2)
      case IsJoin =>    Some(op == MuOpcode.CUSTOM0 && f3 == 3)
      case IsBar =>     Some(op == MuOpcode.CUSTOM0 && f3 == 4)
      case IsPred =>    Some(op == MuOpcode.CUSTOM0 && f3 == 5)
      case IsToHost =>  Some(op == MuOpcode.SYSTEM  && f3 == "??0")
      case IsCSR =>     Some(op == MuOpcode.SYSTEM  && f3 == "??1")
      case IsCSRRW =>   Some(op == MuOpcode.SYSTEM  && f3 == 1)
      case IsCSRRS =>   Some(op == MuOpcode.SYSTEM  && f3 == 2)
      case IsCSRRC =>   Some(op == MuOpcode.SYSTEM  && f3 == 3)
      case IsCSRRWI =>  Some(op == MuOpcode.SYSTEM  && f3 == 5)
      case IsCSRRSI =>  Some(op == MuOpcode.SYSTEM  && f3 == 6)
      case IsCSRRCI =>  Some(op == MuOpcode.SYSTEM  && f3 == 7)
      case _ => None
    }
  }

  class LazyField(val value: Int, val width: Int = 3) {
    require(width < 32)

    private val checked = scala.collection.mutable.Set[BitPat]()

    def ==(pat: BitPat): Boolean = {
      checked.add(pat)
      (value & pat.mask) == pat.value
    }

    def ==(other: Int): Boolean = {
      this.==(BitPat(other.asUInt(width.W)))
    }

    def ==(other: String): Boolean = {
      this.==(BitPat("b" + other))
    }

    // TODO: add warnings for != ops

    // generate a minimal sequence of matching bitpat and concrete values
    def genBitPats: Seq[(BitPat, Int)] = {
      if (checked.isEmpty) {
        Seq((BitPat.dontCare(width), 0))
      } else {
        val mask = checked.map(_.mask).reduce(_ | _)
        val tests = (0 until (1 << width)).filter(n => (n & ~mask) == 0)
        tests.map(x => (new BitPat(x, mask, width), x))
      }
      // // if nothing is checked or if whitelist is exhaustive: generate ???;
      // // follow with union (whitelist, blacklist) checks
      // ((if (checked.isEmpty || (checked.size >= (1 << width))) {
      //   Seq(("?" * width, dontCareValue))
      // } else {
      //   Seq()
      // }) ++ checked.union(reverseChecked).map { x =>
      //   (("0" * width + x.toBinaryString) takeRight width, x) // left pad 0
      // }).map(x => (BitPat("b" + x._1), x._2))
    }
  }

  implicit class LiteralLazyField(value: UInt) extends LazyField(value.litValue.toInt, value.getWidth) {
    override def ==(pat: BitPat): Boolean = (value.litValue & pat.mask) == pat.value
  }

  // this converts field to the index in the bitpat
  val tableIndices = allDecodeFields
    .flatMap(f => staticDecode(f, "", 0.U(3.W), 0.U(7.W)).map(_ => f)).zipWithIndex.toMap

  val _tableData = allOpcodes.flatMap { op =>
    // for every field, there's always a 2-step process:
    // 1. symbolic evaluation for usage, 2. concrete (+recursive) evaluation for signals
    val f3 = new LazyField(0, 3)
    allDecodeFields.foreach(staticDecode(_, op, f3, 0.U(7.W)))
    f3.genBitPats.flatMap { case (bp3, val3) =>
      val f7 = new LazyField(0, 7)
      allDecodeFields.foreach(staticDecode(_, op, val3.U(3.W), f7))
      f7.genBitPats.map { case (bp7, val7) =>
        val signals = allDecodeFields.flatMap(staticDecode(_, op, val3.U(3.W), val7.U(7.W)))
        val sigStr = signals.map(b => if (b) '1' else '0').mkString.reverse // str is big endian
        (BitPat(op) ## bp3 ## bp7, BitPat("b" + sigStr))
      }
    }
  }
  _tableData.foreach(println)

  val table = TruthTable(
    table = _tableData,
      // def getBitPat(f3: => Int, f7: => Int) = {
      //   BitPat("b" + allDecodeFields.flatMap {
      //     staticDecode(_, op, f3)
      //   }.map(b => if (b) '1' else '0').mkString.reverse) // string is big endian
      // }
      // val f0Signals = getBitPat({ f3Used = true; 0 }, { f7Used = true; 0 })
      // if (f3Used) {
      //   Seq.tabulate(8) { f3 =>
      //     (BitPat(op + (("000" + f3.toBinaryString) takeRight 3)), getBitPat(f3))
      //   }
      // } else {
      //   Seq((BitPat(op + "???"), f0Signals))
      // }
    default = BitPat.dontCare(tableIndices.size)
  )

  def decode(inst: UInt): Decoded = {
    val dec = Wire(new Decoded(full = true))
    (essentialFields.zipWithIndex ++ optionalFields.zipWithIndex).foreach { case (f, i) =>
      dec.decode(f, Some(i))(inst)
    }
    dec
  }
}

