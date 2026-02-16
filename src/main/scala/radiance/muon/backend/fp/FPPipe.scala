package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

case class FPPipeParams (val numFP32Lanes: Int = 8,
                         val numFP32DivLanes: Int = 2,
                         val numFP16ExpLanes: Int = 4)

trait HasFPPipeParams extends HasCoreParameters {
  def numFP32ALULanes = muonParams.fpPipe.numFP32Lanes
  def numFP16ALULanes = muonParams.fpPipe.numFP32Lanes * 2
  def numFP32DivLanes = muonParams.fpPipe.numFP32DivLanes
  def numFP16DivLanes = muonParams.fpPipe.numFP32DivLanes * 2
  def numFP16ExpLanes = muonParams.fpPipe.numFP16ExpLanes
  def numFP32ExpLanes = muonParams.fpPipe.numFP16ExpLanes * 2
  def fStatusBits = 5

  def fpEXTagBits = Isa.regBits
  def cvFPUTagBits(numFP16Lanes: Int) = 1 + numFP16Lanes + Isa.regBits // FP32? + TMask + Rd
  def signExtendFp16Lanes(numLanes: Int, data: UInt): Vec[UInt] = {
    val lanes = VecInit.tabulate(numLanes) { idx =>
      data(16 * (idx + 1) - 1, 16 * idx)
    }
    VecInit(lanes.map { lane =>
      Cat(Mux(lane(15), 0xffff.U(16.W), 0.U(16.W)), lane)
    })
  }

  def fpWordBits(fmt: FPFormat.Type) : Int = {
    fmt match {
      case FPFormat.FP32 => 32
      case _ => 16
    }
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

class FPPipeBase(fmt: FPFormat.Type, isDivSqrt: Boolean = false, outLanes: Int)
  (implicit p: Parameters)
  extends ExPipe(
    decomposerTypes = Some(Seq(
        UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W), Bool()
    )),
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), Bool())),
    outLanes = Some(outLanes),
    writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {
  implicit val numFP16Lanes = if (isDivSqrt) numFP16DivLanes else numFP16ALULanes

  val cvFPUIF = IO(new Bundle {
    val req = Decoupled(new CVFPUReq(numFP16Lanes, cvFPUTagBits(numFP16Lanes)))
    val resp = Flipped(Decoupled(new CVFPUResp(numFP16Lanes, cvFPUTagBits(numFP16Lanes), fStatusBits)))
  })

  val fCSRIO = IO(new Bundle {
    val regData = Input(csrDataT)
    val setFStatus = Output(Valid(UInt(fStatusBits.W)))
  })

  val ioFpOp = FpOpDecoder.decode(inst(Opcode), inst(F3), inst(F7), inst(Rs2))
  val req = RegEnable(ioFpOp, 0.U.asTypeOf(new FpOpBundle), io.req.fire)
  val cvFPUReq = Mux(io.req.fire, ioFpOp, req)
  val isFP16 = fmt === FPFormat.BF16

  val operands = decomposer.get.io.out.bits.data.take(3)
                 .map(operand => VecInit(operand.map(reg => reg.asUInt(fpWordBits(fmt) - 1, 0))))
  val shiftOperands = cvFPUReq.op === FPUOp.ADD || cvFPUReq.op === FPUOp.SUB
  val respIsMine = cvFPUIF.resp.bits.tag(cvFPUTagBits(numFP16Lanes) - 1) === isFP16
  val signExtFP16cvFPURes = signExtendFp16Lanes(outLanes, cvFPUIF.resp.bits.result).asUInt
  val cvFPURespRd = RegEnable(cvFPUIF.resp.bits.tag(Isa.regBits - 1, 0), 0.U(Isa.regBits.W),
                              recomposer.get.io.in.fire)

  io.req.ready := decomposer.get.io.in.ready
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
  cvFPUIF.req.bits.tag := Cat(isFP16,
                              0.U((numFP16Lanes - outLanes).W), decomposer.get.io.out.bits.data(3).asUInt,
                              Mux(io.req.fire, inst(Rd), reqRd))
  cvFPUIF.req.bits.operands(0) := Mux(shiftOperands, 0.U, operands(0).asUInt)
  cvFPUIF.req.bits.operands(1) := Mux(shiftOperands, operands(0).asUInt, operands(1).asUInt)
  cvFPUIF.req.bits.operands(2) := Mux(shiftOperands, operands(1).asUInt, operands(2).asUInt)
  cvFPUIF.resp.ready := respIsMine && recomposer.get.io.in.ready

  recomposer.get.io.in.valid := cvFPUIF.resp.valid && respIsMine
  recomposer.get.io.in.bits.data(1) := VecInit(cvFPUIF.resp.bits.tag(Isa.regBits + outLanes - 1, Isa.regBits).asBools)
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := cvFPURespRd
  io.resp.bits.reg.get.bits.data := recomposer.get.io.out.bits.data(0)
  io.resp.bits.reg.get.bits.tmask := recomposer.get.io.out.bits.data(1).asUInt

  val fStatusAcc = RegInit(0.U(fStatusBits.W))
  fStatusAcc := Mux(io.resp.fire,
    Mux(cvFPUIF.resp.fire && respIsMine, cvFPUIF.resp.bits.status, 0.U(fStatusBits.W)),
    Mux(cvFPUIF.resp.fire && respIsMine, cvFPUIF.resp.bits.status | fStatusAcc, fStatusAcc)
  )
  fCSRIO.setFStatus.bits := fStatusAcc
  fCSRIO.setFStatus.valid := io.resp.fire
}

class FP32Pipe(isDivSqrt: Boolean)(implicit p: Parameters)
  extends FPPipeBase(FPFormat.FP32, isDivSqrt,
    if (isDivSqrt) p(MuonKey).fpPipe.numFP32DivLanes else p(MuonKey).fpPipe.numFP32Lanes) {
  val expandedLaneMask = Cat(decomposer.get.io.out.bits.data(3).reverse.map(b => Cat(0.U(1.W), b.asUInt)))
  cvFPUIF.req.bits.simdMask := expandedLaneMask

  //dumb hack for cvfpu fp16 conversion
  val respIsFP16Cvt = (cvFPUReq.op === FPUOp.UI2F || cvFPUReq.op === FPUOp.SI2F || cvFPUReq.op === FPUOp.F2F) &&
                       cvFPUReq.dstFmt === FPFormat.BF16
  recomposer.get.io.in.bits.data(0) := Mux(respIsFP16Cvt,
    signExtFP16cvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0)),
    cvFPUIF.resp.bits.result.asTypeOf(recomposer.get.io.in.bits.data(0))
  )
}

class FP16Pipe(isDivSqrt: Boolean)(implicit p: Parameters)
  extends FPPipeBase(FPFormat.BF16, isDivSqrt,
    if (isDivSqrt) p(MuonKey).fpPipe.numFP32DivLanes * 2 else p(MuonKey).fpPipe.numFP32Lanes * 2) {
  cvFPUIF.req.bits.simdMask := decomposer.get.io.out.bits.data(3).asUInt

  recomposer.get.io.in.bits.data(0) := signExtFP16cvFPURes.asTypeOf(recomposer.get.io.in.bits.data(0))
}

class FPPipe(isDivSqrt: Boolean = false)(implicit p: Parameters)
  extends ExPipe(writebackSched = false, writebackReg = true, requiresRs3 = true)
    with HasFPPipeParams {

  val fCSRIO = IO(new Bundle {
    val regData = Output(csrDataT)
    val regWrite = Flipped(Valid(csrDataT))
  })
  val fCSR = RegInit(0.U.asTypeOf(csrDataT))

  val numFP16Lanes = if (isDivSqrt) numFP16DivLanes else numFP16ALULanes
  val pipes = Seq(Module(new FP16Pipe(isDivSqrt)), Module(new FP32Pipe(isDivSqrt)))
  val isPipes = Seq(io.req.bits.uop.inst.b(UseFP16Pipe), io.req.bits.uop.inst.b(UseFP32Pipe))
  val CVFPU = Module(new CVFPU(numFP16Lanes, cvFPUTagBits(numFP16Lanes), isDivSqrt))
  val rr = Module(new RRArbiter(
    new CVFPUReq(numFP16Lanes, cvFPUTagBits(numFP16Lanes)), pipes.length))

  CVFPU.io.clock := clock
  CVFPU.io.reset := reset
  CVFPU.io.flush := false.B

  pipes.zip(isPipes).zip(rr.io.in).foreach{ case ((pipe, isPipe), rrIn) =>
    pipe.io.req.valid := io.req.valid && isPipe
    pipe.io.req.bits := io.req.bits
    pipe.cvFPUIF.resp.valid := CVFPU.io.resp.valid
    pipe.cvFPUIF.resp.bits  := CVFPU.io.resp.bits
    pipe.fCSRIO.regData := fCSR
    pipe.io.resp.ready := io.resp.ready
    rrIn <> pipe.cvFPUIF.req
  }
  io.req.ready := Mux1H(isPipes.zip(pipes.map(_.io.req.ready)))
  CVFPU.io.req <> rr.io.out
  CVFPU.io.resp.ready := VecInit(pipes.map(_.cvFPUIF.resp.ready)).asUInt.orR

  fCSR := MuxCase(fCSR, Seq(fCSRIO.regWrite.valid -> fCSRIO.regWrite.bits) ++
      pipes.map(pipe => pipe.fCSRIO.setFStatus.valid ->
          Cat(fCSR(archLen, fStatusBits), pipe.fCSRIO.setFStatus.bits)
      )
  )
  fCSRIO.regData := fCSR

  // ordered priority ready
  val respValids = pipes.map(_.io.resp.valid)
  pipes.zipWithIndex.foreach { case (pipe, i) =>
    val higherValid = respValids.drop(i + 1).foldLeft(false.B)(_ || _)
    pipe.io.resp.ready := io.resp.ready && !higherValid
  }
  io.resp.valid := VecInit(pipes.map(_.io.resp.valid)).asUInt.orR
  io.resp.bits := pipes.drop(1).foldLeft(pipes(0).io.resp.bits) { case (bits, pipe) =>
    Mux(pipe.io.resp.valid, pipe.io.resp.bits, bits)
  }
}
