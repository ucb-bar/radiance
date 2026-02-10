package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class FpExOpBundle extends Bundle {
  val neg = Bool()
  val roundingMode = FPRoundingMode()
}

object FpExOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt, rs2: UInt): FpExOpBundle = {
    val table = Seq[(BitPat, BitPat)](
      // fpexp.h
      (BitPat(MuOpcode.CUSTOM2) ## BitPat("b???") ## BitPat("b0101110") ## BitPat("b00001")) -> BitPat("b0"),
      // fpnexp.h
      (BitPat(MuOpcode.CUSTOM2) ## BitPat("b???") ## BitPat("b0101110") ## BitPat("b00010")) -> BitPat("b1")
    )

    val result = Wire(new FpExOpBundle)
    val decodedNeg = decoder(
      Cat(opcode(6, 0), f3, f7, rs2(4, 0)),
      TruthTable(table, BitPat("b0"))
    )
    result.neg := decodedNeg.asBool
    result.roundingMode := FPRoundingMode.safe(f3)._1
    result
  }
}