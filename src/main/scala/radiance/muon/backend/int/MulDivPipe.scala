package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.MulDiv
import freechips.rocketchip.util.SeqToAugmentedSeq
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class MulDivPipe(implicit p: Parameters)
  extends ExPipe(
    decomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W), Bool())),
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W))),
    outLanes = Some(p(MuonKey).intPipe.numMulDivLanes),
    writebackSched = false, writebackReg = true) with HasIntPipeParams {

  val vecMulDiv = Seq.fill(numMulDivLanes)(Module(
    new MulDiv(mulDivParams, archLen, Isa.regBits))
  )

  val ioReqOp = IntOpDecoder.decode(inst(Opcode), inst(F3), inst(F7))
  val reqOp = RegEnable(ioReqOp, 0.U.asTypeOf(aluOpT), io.req.fire)

  val sliceTMask = Reg(UInt(numMulDivLanes.W))
  val vecMulDivRespValid = vecMulDiv.map(_.io.resp.valid).asUInt
  val unmaskedMulDivsDone = (vecMulDivRespValid & sliceTMask) === sliceTMask
  val allMulDivReqReady = vecMulDiv.map(_.io.req.ready).reduce(_ && _)

  io.req.ready := (!busy || io.resp.fire) && decomposer.get.io.in.ready
  decomposer.get.io.in.valid := io.req.fire
  decomposer.get.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.get.io.in.bits.data(1) := io.req.bits.rs2Data.get
  decomposer.get.io.in.bits.data(2) := VecInit(io.req.bits.uop.tmask.asBools)
  decomposer.get.io.out.ready := allMulDivReqReady

  for (i <- 0 until numMulDivLanes) {
    vecMulDiv(i).io.req.valid := decomposer.get.io.out.valid && decomposer.get.io.out.bits.data(2).asUInt(i) && allMulDivReqReady
    vecMulDiv(i).io.req.bits.fn := Mux(io.req.fire, ioReqOp, reqOp)
    vecMulDiv(i).io.req.bits.dw := (archLen == 64).B
    vecMulDiv(i).io.req.bits.in1 := decomposer.get.io.out.bits.data(0)(i)
    vecMulDiv(i).io.req.bits.in2 := decomposer.get.io.out.bits.data(1)(i)
    vecMulDiv(i).io.req.bits.tag := reqRd
    vecMulDiv(i).io.kill := false.B

    vecMulDiv(i).io.resp.ready := sliceTMask(i) && unmaskedMulDivsDone && recomposer.get.io.in.ready
    recomposer.get.io.in.bits.data(0)(i) := vecMulDiv(i).io.resp.bits.data
  }
  // signal should not go high when pipe is idle, if tmask=zeroes, immediately enqueue to recomposer
  recomposer.get.io.in.valid := unmaskedMulDivsDone && busy
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.bits.rd := reqRd
  io.resp.bits.reg.get.bits.data := recomposer.get.io.out.bits.data(0)
  io.resp.bits.reg.get.bits.tmask := reqTmask

  when (decomposer.get.io.out.fire) {
    // assumes 1 cycle minimum muldiv latency
    sliceTMask := decomposer.get.io.out.bits.data(2).asUInt
  }
}
