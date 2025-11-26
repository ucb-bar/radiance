package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

case class FPPipeParams (val numFP32Lanes: Int = 8,
                         val numFPDivLanes: Int = 8)

trait HasFPPipeParams extends HasMuonCoreParameters {
  def numFP32Lanes = muonParams.fpPipe.numFP32Lanes
  def numFP16Lanes = muonParams.fpPipe.numFP32Lanes * 2
  def numFPDivLanes = muonParams.fpPipe.numFPDivLanes
  def fStatusBits = 5
}

class FpOpBundle extends Bundle {
  val op = FPUOp()
  val srcFmt = FPFormat()
  val dstFmt = FPFormat()
  val roundingMode = FPRoundingMode()
}

object FpOpDecoder {
  def decode(opcode: UInt, f3: UInt, f7: UInt, rs2: UInt): FpOpBundle = {
    val fpOpW = FPUOp.getWidth.W
    val table = Seq[(BitPat, BitPat)](
      (BitPat(MuOpcode.MADD)   ## BitPat("b???") ## BitPat("b?????") ## BitPat("b?????")) -> BitPat(FPUOp.FMADD.litValue.U(fpOpW)),
      (BitPat(MuOpcode.MSUB)   ## BitPat("b???") ## BitPat("b?????") ## BitPat("b?????")) -> BitPat(FPUOp.FMSUB.litValue.U(fpOpW)),
      (BitPat(MuOpcode.NM_SUB) ## BitPat("b???") ## BitPat("b?????") ## BitPat("b?????")) -> BitPat(FPUOp.FMNSUB.litValue.U(fpOpW)),
      (BitPat(MuOpcode.NM_ADD) ## BitPat("b???") ## BitPat("b?????") ## BitPat("b?????")) -> BitPat(FPUOp.FMNADD.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00000") ## BitPat("b?????")) -> BitPat(FPUOp.ADD.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00001") ## BitPat("b?????")) -> BitPat(FPUOp.SUB.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00010") ## BitPat("b?????")) -> BitPat(FPUOp.MUL.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00011") ## BitPat("b?????")) -> BitPat(FPUOp.DIV.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00100") ## BitPat("b?????")) -> BitPat(FPUOp.SGNJ.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b00101") ## BitPat("b?????")) -> BitPat(FPUOp.MINMAX.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b01011") ## BitPat("b?????")) -> BitPat(FPUOp.SQRT.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b10100") ## BitPat("b?????")) -> BitPat(FPUOp.CMP.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b001") ## BitPat("b11100") ## BitPat("b?????")) -> BitPat(FPUOp.CLASSIFY.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b01000") ## BitPat("b?????")) -> BitPat(FPUOp.F2F.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b11000") ## BitPat("b000?0")) -> BitPat(FPUOp.F2SI.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b11000") ## BitPat("b000?1")) -> BitPat(FPUOp.F2UI.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b11010") ## BitPat("b000?0")) -> BitPat(FPUOp.SI2F.litValue.U(fpOpW)),
      (BitPat(MuOpcode.OP_FP)  ## BitPat("b???") ## BitPat("b11010") ## BitPat("b000?1")) -> BitPat(FPUOp.UI2F.litValue.U(fpOpW))
    )

    val f5 = f7(6,2)
    val fmt = f7(1,0)
    val result = Wire(new FpOpBundle)
    val decodedOp = decoder(Cat(opcode(6,0), f3, f5, rs2(4,0)), TruthTable(table, BitPat(FPUOp.ADD.litValue.U(fpOpW))))
    result.op := FPUOp.safe(decodedOp)._1
    val fmtBits = Cat(0.U(1.W), fmt)
    val srcFmtBits = Mux(result.op === FPUOp.F2F, rs2(2,0), fmtBits)
    result.srcFmt := FPFormat.safe(srcFmtBits)._1
    result.dstFmt := FPFormat.safe(fmtBits)._1
    result.roundingMode := FPRoundingMode.safe(f3)._1
    result
  }
}

class FPPipeBase(fmt: FPFormat.Type, outLanes: Int)
  (implicit p: Parameters)
  extends ExPipe(
    decomposerTypes = Some(Seq(
        UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W), Bool()
    )),
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W))),
    outLanes = Some(outLanes),
    writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W), UInt(archLen.W), Bool())
  implicit val recomposerTypes =
    Seq(UInt(archLen.W))

  val cvFPUIF = IO(new Bundle {
    val req = Decoupled(new CVFPUReq(numFP32Lanes * 2, Isa.regBits))
    val resp = Flipped(Decoupled(new CVFPUResp(numFP32Lanes * 2, Isa.regBits, fStatusBits)))
  })

  val fCSRIO = IO(new Bundle {
    val regData = Input(csrDataT)
    val setFStatus = Output(Valid(UInt(fStatusBits.W)))
  })

  val ioFpOp = FpOpDecoder.decode(inst(Opcode), inst(F3), inst(F7), inst(Rs2))
  val req = Reg(new FpOpBundle)

  val cvFPUReq = WireInit(req)
  cvFPUReq := Mux(io.req.fire, ioFpOp, req)

  val operands = decomposer.get.io.out.bits.data
  val shiftOperands = cvFPUReq.op === FPUOp.ADD || cvFPUReq.op === FPUOp.SUB

  // assume same fpconv across all lanes
  val fpu_out = recomposer.get.io.out.bits.data(0)

  io.req.ready := (!busy || io.resp.fire) && decomposer.get.io.in.ready
  decomposer.get.io.in.valid := io.req.fire
  decomposer.get.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.get.io.in.bits.data(1) := io.req.bits.rs2Data.get
  decomposer.get.io.in.bits.data(2) := io.req.bits.rs3Data.getOrElse(VecInit(Seq.fill(numLanes)(0.U(archLen.W))))
  decomposer.get.io.in.bits.data(3) := VecInit(io.req.bits.uop.tmask.asBools)
  decomposer.get.io.out.ready := cvFPUIF.req.ready

  cvFPUIF.req.valid := decomposer.get.io.out.valid
  cvFPUIF.req.bits.roundingMode := Mux(cvFPUReq.roundingMode === FPRoundingMode.DYN,
                                       fCSRIO.regData(7,5).asTypeOf(FPRoundingMode()),
                                       cvFPUReq.roundingMode)
  cvFPUIF.req.bits.op := cvFPUReq.op
  cvFPUIF.req.bits.srcFormat := cvFPUReq.srcFmt
  cvFPUIF.req.bits.dstFormat := cvFPUReq.dstFmt
  cvFPUIF.req.bits.intFormat := IntFormat.INT32
  cvFPUIF.req.bits.tag := Mux(io.req.fire, inst(Rd), reqRd)
  cvFPUIF.resp.ready := recomposer.get.io.in.fire

  fCSRIO.setFStatus.valid := cvFPUIF.resp.valid
  fCSRIO.setFStatus.bits := cvFPUIF.resp.bits.status

  when (io.req.fire) {
    req := ioFpOp
  }
}

class FP32Pipe(implicit p: Parameters)
  extends FPPipeBase(FPFormat.FP32, p(MuonKey).fpPipe.numFP32Lanes) {
  val expandedLaneMask = Cat(decomposer.get.io.out.bits.data(3).reverse.map(b => Cat(0.U(1.W), b.asUInt)))
  cvFPUIF.req.bits.operands(0) := Mux(shiftOperands, 0.U, operands(0).asUInt)
  cvFPUIF.req.bits.operands(1) := Mux(shiftOperands, operands(0).asUInt, operands(1).asUInt)
  cvFPUIF.req.bits.operands(2) := Mux(shiftOperands, operands(1).asUInt, operands(2).asUInt)
  cvFPUIF.req.bits.simdMask := expandedLaneMask

  val respIsFp32 = cvFPUIF.resp.bits.tag === reqRd
  //dumb hack for cvfpu fp16 conversion
  val respIsFP16Cvt = cvFPUReq.dstFmt === FPFormat.FP16
  val chunks = VecInit.tabulate(numFP32Lanes)(idx => cvFPUIF.resp.bits.result(16 * (idx + 1) - 1, 16 * idx))
  val signExtcvFPURes = Cat(chunks.map(reg => Cat(Mux(reg(15), 0xffff.U(16.W), 0.U(16.W)), reg)).reverse)
  recomposer.get.io.in.valid := cvFPUIF.resp.valid && respIsFp32
  recomposer.get.io.in.bits.data(0) := Mux(respIsFP16Cvt,
    signExtcvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0)),
    cvFPUIF.resp.bits.result.asTypeOf(recomposer.get.io.in.bits.data(0))
  )
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := reqRd
  io.resp.bits.reg.get.bits.data := fpu_out.asTypeOf(io.resp.bits.reg.get.bits.data)
  io.resp.bits.reg.get.bits.tmask := reqTmask

  when (io.req.fire) {
    cvFPUReq := ioFpOp
  }
}

class FP16Pipe(implicit p: Parameters)
  extends FPPipeBase(FPFormat.FP16, p(MuonKey).fpPipe.numFP32Lanes * 2) {
  val operandsLower = operands.take(3).map(operand => VecInit(operand.map(reg => reg.asUInt(15, 0))))
  cvFPUIF.req.bits.operands(0) := Mux(shiftOperands, 0.U, operandsLower(0).asUInt)
  cvFPUIF.req.bits.operands(1) := Mux(shiftOperands, operandsLower(0).asUInt, operandsLower(1).asUInt)
  cvFPUIF.req.bits.operands(2) := Mux(shiftOperands, operandsLower(1).asUInt, operandsLower(2).asUInt)
  cvFPUIF.req.bits.simdMask := decomposer.get.io.out.bits.data(3).asUInt

  val respIsFp16 = cvFPUIF.resp.bits.tag === reqRd
  recomposer.get.io.in.valid := cvFPUIF.resp.valid && respIsFp16
  val chunks = VecInit.tabulate(numFP32Lanes * 2)(idx => cvFPUIF.resp.bits.result(16 * (idx + 1) - 1, 16 * idx))
  val signExtcvFPURes = Cat(chunks.map(reg => Cat(Mux(reg(15), 0xffff.U(16.W), 0.U(16.W)), reg)).reverse)
  recomposer.get.io.in.bits.data(0) := signExtcvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0))
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := reqRd
  io.resp.bits.reg.get.bits.data := fpu_out.asTypeOf(io.resp.bits.reg.get.bits.data)
  io.resp.bits.reg.get.bits.tmask := reqTmask

  when (io.req.fire) {
    cvFPUReq := ioFpOp
  }
}

class FPPipe(implicit p: Parameters)
  extends ExPipe(writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {

  val fCSRIO = IO(new Bundle {
    val regData = Output(csrDataT)
    val regWrite = Flipped(Valid(csrDataT))
  })
  val fCSR = RegInit(0.U.asTypeOf(csrDataT))

  val FP16Pipe = Module(new FP16Pipe)
  val FP32Pipe = Module(new FP32Pipe)
  val CVFPU = Module(new CVFPU(numFp16Lanes = numFP32Lanes * 2, tagWidth = Isa.regBits))

  CVFPU.io.clock := clock
  CVFPU.io.reset := reset
  CVFPU.io.flush := false.B

  val isFP32 = io.req.bits.uop.inst.b(UseFP32Pipe)
  val isFP16 = io.req.bits.uop.inst.b(UseFP16Pipe)

  FP16Pipe.io.req.valid := io.req.valid && isFP16
  FP16Pipe.io.req.bits := io.req.bits
  FP32Pipe.io.req.valid := io.req.valid && isFP32
  FP32Pipe.io.req.bits := io.req.bits
  io.req.ready := Mux1H(Seq((isFP32, FP32Pipe.io.req.ready), (isFP16, FP16Pipe.io.req.ready)))

  val rr = Module(new RRArbiter(
    new CVFPUReq(numFP32Lanes * 2, Isa.regBits), 2))
  rr.io.in(0) <> FP32Pipe.cvFPUIF.req
  rr.io.in(1) <> FP16Pipe.cvFPUIF.req
  CVFPU.io.req <> rr.io.out

  FP32Pipe.cvFPUIF.resp.bits  := CVFPU.io.resp.bits
  FP16Pipe.cvFPUIF.resp.bits  := CVFPU.io.resp.bits
  FP32Pipe.cvFPUIF.resp.valid := CVFPU.io.resp.valid
  FP16Pipe.cvFPUIF.resp.valid := CVFPU.io.resp.valid
  CVFPU.io.resp.ready := FP32Pipe.cvFPUIF.resp.ready || FP16Pipe.cvFPUIF.resp.ready

  fCSR := MuxCase(fCSR, Seq(
    fCSRIO.regWrite.valid -> fCSRIO.regWrite.bits,
    FP16Pipe.fCSRIO.setFStatus.valid -> Cat(fCSR(archLen, fStatusBits), FP16Pipe.fCSRIO.setFStatus.bits),
    FP32Pipe.fCSRIO.setFStatus.valid -> Cat(fCSR(archLen, fStatusBits), FP32Pipe.fCSRIO.setFStatus.bits)
  ))
  fCSRIO.regData := fCSR
  FP16Pipe.fCSRIO.regData := fCSR
  FP32Pipe.fCSRIO.regData := fCSR


  // if both ready, prioritize fp32
  FP32Pipe.io.resp.ready := io.resp.ready
  FP16Pipe.io.resp.ready := io.resp.ready && !FP32Pipe.io.resp.valid
  io.resp.valid := FP32Pipe.io.resp.valid || FP16Pipe.io.resp.valid
  io.resp.bits := Mux(FP32Pipe.io.resp.valid, FP32Pipe.io.resp.bits, FP16Pipe.io.resp.bits)
}
