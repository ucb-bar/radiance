package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._
import fpex._

class FpExOpBundle extends Bundle {
  val neg = Bool()
  val roundingMode = FPRoundingMode()
}

object FpExOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt, rs2: UInt): FpExOpBundle = {
    val table = Seq[(BitPat, BitPat)](
      // fpexp.h
      (BitPat("b1111011") ## BitPat("b???") ## BitPat("b0101110") ## BitPat("b00001")) -> BitPat("b0"),
      // fpnexp.h
      (BitPat("b1111011") ## BitPat("b???") ## BitPat("b0101110") ## BitPat("b00010")) -> BitPat("b1")
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

class FPExPipe(fmt: FPFormat.Type)
  (implicit p: Parameters)
  extends ExPipe(
    decomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), Bool())),
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), Bool())),
    outLanes = Some(p(MuonKey).fpPipe.numFP16ExpLanes),
    writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {
  val fpEX = Module(new FPEX(FPType.BF16T, numFP16ExpLanes, fpEXTagBits))

  val fCSRIO = IO(new Bundle {
    val regData = Input(csrDataT)
  })

  val ioFpExOp = FpExOpDecoder.decode(inst(Opcode), inst(F3), inst(F7), inst(Rs2))
  val req = RegEnable(ioFpExOp, 0.U.asTypeOf(new FpExOpBundle), io.req.fire)
  val fpExReq = Mux(io.req.fire, ioFpExOp, req)

  val fpEXRespRd = RegEnable(fpEX.io.resp.bits.tag(Isa.regBits - 1, 0), 0.U(Isa.regBits.W),
    fpEX.io.resp.valid)
  val signExtFP16FpEXRes = signExtendFp16Lanes(numFP16ExpLanes, fpEX.io.resp.bits.result.asUInt)

  io.req.ready := decomposer.get.io.in.ready
  decomposer.get.io.in.valid := io.req.fire
  decomposer.get.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.get.io.in.bits.data(1) := VecInit(io.req.bits.uop.tmask.asBools)
  decomposer.get.io.out.ready := fpEX.io.req.ready

  fpEX.io.req.valid := decomposer.get.io.out.valid
  fpEX.io.req.bits.roundingMode := Mux(fpExReq.roundingMode === FPRoundingMode.DYN,
    fCSRIO.regData(7, 5).asTypeOf(FPRoundingMode()),
    fpExReq.roundingMode
  ).asUInt
  fpEX.io.req.bits.tag := Mux(io.req.fire, inst(Rd), reqRd)
  fpEX.io.req.bits.neg := fpExReq.neg
  fpEX.io.req.bits.xVec := VecInit(decomposer.get.io.out.bits.data(0).map(reg =>
      reg.asUInt(fpWordBits(fmt) - 1, 0)))
  fpEX.io.req.bits.laneMask := decomposer.get.io.out.bits.data(1).asUInt
  fpEX.io.resp.ready := recomposer.get.io.in.ready

  recomposer.get.io.in.valid := fpEX.io.resp.valid
  recomposer.get.io.in.bits.data(0) := signExtFP16FpEXRes
  recomposer.get.io.in.bits.data(1) := VecInit(fpEX.io.resp.bits.laneMask.asBools)
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := fpEXRespRd
  io.resp.bits.reg.get.bits.data := recomposer.get.io.out.bits.data(0)
  io.resp.bits.reg.get.bits.tmask := recomposer.get.io.out.bits.data(1).asUInt
}
