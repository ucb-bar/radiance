package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

case class FPPipeParams (val numFP32Lanes: Int = 8,
                         val numFPDivLanes: Int = 8,
                         )

trait HasFPPipeParams extends HasCoreParameters {
  def numFP32Lanes = muonParams.fpPipe.numFP32Lanes
  def numFP16Lanes = muonParams.numFP16Lanes
  def numFPDivLanes = muonParams.fpPipe.numFPDivLanes
  def fStatusBits = 5
  def cvFPUTagBits = muonParams.cvFPUTagBits

  def signExtendFp16Lanes(numLanes: Int, data: UInt): UInt = {
    val lanes = VecInit.tabulate(numLanes) { idx =>
      data(16 * (idx + 1) - 1, 16 * idx)
    }
    Cat(lanes.map { lane =>
      Cat(Mux(lane(15), 0xffff.U(16.W), 0.U(16.W)), lane)
    }.reverse)
  }

  def fpWordBits(fmt: FPFormat.Type) : Int = {
    fmt match {
      case FPFormat.FP32 => 32
      case _ => 16
    }
  }
  def CVFPUIF = new Bundle {
    val req = Decoupled(new CVFPUReq(numFP16Lanes, cvFPUTagBits))
    val resp = Flipped(Decoupled(new CVFPUResp(numFP16Lanes, cvFPUTagBits, fStatusBits)))
  }
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
    val fmtBits = FPFormat.safe(Cat(0.U(1.W), fmt))._1
    val srcFmtBits = Mux(result.op === FPUOp.F2F, FPFormat.safe(rs2(2,0))._1, fmtBits)
    result.srcFmt := Mux(srcFmtBits === FPFormat.FP16, FPFormat.BF16, srcFmtBits)
    result.dstFmt := Mux(fmtBits === FPFormat.FP16, FPFormat.BF16, fmtBits)
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
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), Bool())),
    outLanes = Some(outLanes),
    writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W), UInt(archLen.W), Bool())
  implicit val recomposerTypes =
    Seq(UInt(archLen.W))

  val fCSRIO = IO(new Bundle {
    val regData = Input(csrDataT)
    val setFStatus = Output(Valid(UInt(fStatusBits.W)))
  })

  val CVFPUIO = IO(CVFPUIF)

  val ioFpOp = FpOpDecoder.decode(inst(Opcode), inst(F3), inst(F7), inst(Rs2))
  val req = RegEnable(ioFpOp, 0.U.asTypeOf(new FpOpBundle), io.req.fire)
  val cvFPUReq = Mux(io.req.fire, ioFpOp, req)
  val isFP16 = fmt === FPFormat.BF16

  val operands = decomposer.get.io.out.bits.data.take(3)
                 .map(operand => VecInit(operand.map(reg => reg.asUInt(fpWordBits(fmt) - 1, 0))))
  val shiftOperands = cvFPUReq.op === FPUOp.ADD || cvFPUReq.op === FPUOp.SUB
  val respIsMine = CVFPUIO.resp.bits.tag(cvFPUTagBits - 2) === isFP16
  val signExtFP16cvFPURes = signExtendFp16Lanes(outLanes, CVFPUIO.resp.bits.result)
  val cvFPURespRd = RegEnable(CVFPUIO.resp.bits.tag(Isa.regBits - 1, 0), 0.U(Isa.regBits.W),
                              CVFPUIO.resp.valid && respIsMine)

  io.req.ready := decomposer.get.io.in.ready
  decomposer.get.io.in.valid := io.req.fire
  decomposer.get.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.get.io.in.bits.data(1) := io.req.bits.rs2Data.get
  decomposer.get.io.in.bits.data(2) := io.req.bits.rs3Data.getOrElse(VecInit(Seq.fill(numLanes)(0.U(archLen.W))))
  decomposer.get.io.in.bits.data(3) := VecInit(io.req.bits.uop.tmask.asBools)
  decomposer.get.io.out.ready := CVFPUIO.req.ready

  CVFPUIO.req.valid := decomposer.get.io.out.valid
  CVFPUIO.req.bits.roundingMode := Mux(cvFPUReq.roundingMode === FPRoundingMode.DYN,
                                       fCSRIO.regData(7,5).asTypeOf(FPRoundingMode()),
                                       cvFPUReq.roundingMode)
  CVFPUIO.req.bits.op := cvFPUReq.op
  CVFPUIO.req.bits.srcFormat := cvFPUReq.srcFmt
  CVFPUIO.req.bits.dstFormat := cvFPUReq.dstFmt
  CVFPUIO.req.bits.intFormat := IntFormat.INT32
  CVFPUIO.req.bits.tag := Cat(isFP16,
                              0.U((numFP16Lanes - outLanes).W), decomposer.get.io.out.bits.data(3).asUInt,
                              Mux(io.req.fire, inst(Rd), reqRd))
  CVFPUIO.req.bits.operands(0) := Mux(shiftOperands, 0.U, operands(0).asUInt)
  CVFPUIO.req.bits.operands(1) := Mux(shiftOperands, operands(0).asUInt, operands(1).asUInt)
  CVFPUIO.req.bits.operands(2) := Mux(shiftOperands, operands(1).asUInt, operands(2).asUInt)
  CVFPUIO.resp.ready := recomposer.get.io.in.fire

  recomposer.get.io.in.valid := CVFPUIO.resp.valid && respIsMine
  recomposer.get.io.in.bits.data(1) := VecInit(CVFPUIO.resp.bits.tag(Isa.regBits + outLanes - 1, Isa.regBits).asBools)
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := cvFPURespRd
  io.resp.bits.reg.get.bits.data := recomposer.get.io.out.bits.data(0)
  io.resp.bits.reg.get.bits.tmask := recomposer.get.io.out.bits.data(1).asUInt

  val fStatusAcc = RegInit(0.U(fStatusBits.W))
  fStatusAcc := Mux(io.resp.fire,
    Mux(CVFPUIO.resp.fire, CVFPUIO.resp.bits.status, 0.U(fStatusBits.W)),
    Mux(CVFPUIO.resp.fire, CVFPUIO.resp.bits.status | fStatusAcc, fStatusAcc)
  )
  fCSRIO.setFStatus.bits := fStatusAcc
  fCSRIO.setFStatus.valid := io.resp.fire
}

class FP32Pipe(implicit p: Parameters)
  extends FPPipeBase(FPFormat.FP32, p(MuonKey).fpPipe.numFP32Lanes) {
  val expandedLaneMask = Cat(decomposer.get.io.out.bits.data(3).reverse.map(b => Cat(0.U(1.W), b.asUInt)))
  CVFPUIO.req.bits.simdMask := expandedLaneMask

  //dumb hack for cvfpu fp16 conversion
  val respIsFP16Cvt = (cvFPUReq.op === FPUOp.UI2F || cvFPUReq.op === FPUOp.SI2F || cvFPUReq.op === FPUOp.F2F) &&
                       cvFPUReq.dstFmt === FPFormat.BF16
  recomposer.get.io.in.bits.data(0) := Mux(respIsFP16Cvt,
    signExtFP16cvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0)),
    CVFPUIO.resp.bits.result.asTypeOf(recomposer.get.io.in.bits.data(0))
  )
}

class FP16Pipe(implicit p: Parameters)
  extends FPPipeBase(FPFormat.BF16, p(MuonKey).fpPipe.numFP32Lanes * 2) {
  CVFPUIO.req.bits.simdMask := decomposer.get.io.out.bits.data(3).asUInt

  recomposer.get.io.in.bits.data(0) := signExtFP16cvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0))
}

class FPPipe(isDivSqrt: Boolean = false)(implicit p: Parameters)
  extends ExPipe(writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {

  val fCSRIO = IO(new Bundle {
    val regData = Output(csrDataT)
    val regWrite = Flipped(Valid(csrDataT))
  })
  val CVFPUIO = IO(CVFPUIF)

  val fCSR = RegInit(0.U.asTypeOf(csrDataT))
  val FP16Pipe = Module(new FP16Pipe)
  val FP32Pipe = Module(new FP32Pipe)
  val rr = Module(new RRArbiter(new CVFPUReq(numFP16Lanes, cvFPUTagBits), 2))
  val cvFPURespIsMine = CVFPUIO.resp.bits.tag(cvFPUTagBits - 1) === isDivSqrt.asBool
  val pipes = Seq(FP32Pipe, FP16Pipe)
  val uses = Seq(io.req.bits.uop.inst.b(UseFP32Pipe), io.req.bits.uop.inst.b(UseFP16Pipe))
  val cvFPUReqBitsPatch = Wire(chiselTypeOf(rr.io.out.bits))

  pipes.zip(uses).zipWithIndex.foreach {
    case ((pipe, isMine), idx) => {
      pipe.io.req.valid := io.req.valid && isMine
      pipe.io.req.bits := io.req.bits
      rr.io.in(idx) <> pipe.CVFPUIO.req
      pipe.CVFPUIO.resp.bits  := CVFPUIO.resp.bits
      pipe.CVFPUIO.resp.valid := CVFPUIO.resp.valid && cvFPURespIsMine
      pipe.fCSRIO.regData := fCSR
    }
  }
  io.req.ready := Mux1H(uses.zip(pipes.map(_.io.req.ready)))

  cvFPUReqBitsPatch := rr.io.out.bits
  cvFPUReqBitsPatch.tag := Cat(isDivSqrt.asBool, rr.io.out.bits.tag(cvFPUTagBits - 2, 0))
  CVFPUIO.req.valid := rr.io.out.valid
  CVFPUIO.req.bits := cvFPUReqBitsPatch
  rr.io.out.ready := CVFPUIO.req.ready
  CVFPUIO.resp.ready := VecInit(pipes.map(_.CVFPUIO.resp.ready)).asUInt.orR && cvFPURespIsMine

  fCSR := MuxCase(fCSR, Seq(fCSRIO.regWrite.valid -> fCSRIO.regWrite.bits) ++
  pipes.map(
    pipe => pipe.fCSRIO.setFStatus.valid -> Cat(fCSR(archLen, fStatusBits), pipe.fCSRIO.setFStatus.bits)
  ))
  fCSRIO.regData := fCSR

  // if both ready, prioritize fp32
  FP32Pipe.io.resp.ready := io.resp.ready
  FP16Pipe.io.resp.ready := io.resp.ready && !FP32Pipe.io.resp.valid
  io.resp.valid := VecInit(pipes.map(_.io.resp.valid)).asUInt.orR
  io.resp.bits := Mux(FP32Pipe.io.resp.valid, FP32Pipe.io.resp.bits, FP16Pipe.io.resp.bits)
}
