package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.MulDiv
import freechips.rocketchip.util.SeqToAugmentedSeq
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend.{LaneDecomposer, LaneRecomposer}

class MulDivPipe(implicit p: Parameters)
  extends IntPipe(false) {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W), Bool())
  val decomposer = Module(new LaneDecomposer(
    inLanes = numLanes,
    outLanes = numMulDivLanes,
    elemTypes = decomposerTypes
  ))

  val vecMulDiv = Seq.fill(numMulDivLanes)(Module(
    new MulDiv(mulDivParams, archLen, Isa.regBits))
  )

  implicit val recomposerTypes = Seq(UInt(archLen.W))
  val recomposer = Module(new LaneRecomposer (
    inLanes = numLanes,
    outLanes = numMulDivLanes,
    elemTypes = recomposerTypes
  ))

  val mul_out = Reg(Vec(numLanes, UInt(archLen.W)))
  val sliceTMask = Reg(UInt(numMulDivLanes.W))
  val busy = RegInit(false.B)
  val vecMulDivRespValid = vecMulDiv.map(_.io.resp.valid).asUInt
  val unmaskedMulDivsDone = (vecMulDivRespValid & sliceTMask) === sliceTMask
  val allMulDivReqReady = vecMulDiv.map(_.io.req.ready).reduce(_ && _)

  io.req.ready := !busy || io.resp.fire
  decomposer.io.in.valid := !busy && io.req.valid && ioIntOp.isMulDiv
  decomposer.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.io.in.bits.data(1) := io.req.bits.rs2Data.get
  decomposer.io.in.bits.data(2) := VecInit(io.req.bits.uop.tmask.asBools)
  decomposer.io.out.ready := allMulDivReqReady

  for (i <- 0 until numMulDivLanes) {
    vecMulDiv(i).io.req.valid := decomposer.io.out.valid && decomposer.io.out.bits.data(2).asUInt(i) && allMulDivReqReady
    vecMulDiv(i).io.req.bits.fn := Mux(io.req.fire, ioIntOp.fn, req_op.fn)
    vecMulDiv(i).io.req.bits.dw := (archLen == 64).B
    vecMulDiv(i).io.req.bits.in1 := decomposer.io.out.bits.data(0)(i)
    vecMulDiv(i).io.req.bits.in2 := decomposer.io.out.bits.data(1)(i)
    vecMulDiv(i).io.req.bits.tag := req_rd
    vecMulDiv(i).io.kill := false.B

    vecMulDiv(i).io.resp.ready := sliceTMask(i) && unmaskedMulDivsDone && recomposer.io.in.ready
    recomposer.io.in.bits.data(0)(i) := vecMulDiv(i).io.resp.bits.data
  }
  // signal should not go high when pipe is idle, if tmask=zeroes, immediately enqueue to recomposer
  recomposer.io.in.valid := unmaskedMulDivsDone && busy
  recomposer.io.out.ready := busy

  io.resp.valid := resp_valid
  io.resp.bits.reg.get.valid := resp_valid
  io.resp.bits.reg.get.bits.rd := req_rd
  io.resp.bits.reg.get.bits.data := mul_out
  io.resp.bits.reg.get.bits.tmask := req_tmask

  when (io.resp.fire) {
    busy := false.B
    resp_valid := false.B
  }

  when (decomposer.io.out.fire) {
    // assumes 1 cycle minimum muldiv latency
    sliceTMask := decomposer.io.out.bits.data(2).asUInt
  }

  when (recomposer.io.out.fire) {
    mul_out := recomposer.io.out.bits.data(0)
    resp_valid := true.B
  }

  when (io.req.fire) {
    busy := true.B
    req_op := ioIntOp
    req_pc := io.req.bits.uop.pc
    req_tmask := io.req.bits.uop.tmask
    req_rd := io.req.bits.uop.inst(Rd)
    req_wid := io.req.bits.uop.wid
  }
}
