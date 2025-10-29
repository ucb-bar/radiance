package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.ALU
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class ALUPipe(implicit p: Parameters)
  extends ExPipe(true, true) with HasIntPipeParams with HasCoreBundles {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W))
  val decomposer = Module(new LaneDecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = decomposerTypes
  ))

  val vecALU = Seq.fill(numALULanes)(Module(new ALU))
  vecALU.foreach(alu => alu.io.dw := archLen.U)

  implicit val recomposerTypes =
    Seq(chiselTypeOf(vecALU.head.io.out),
      chiselTypeOf(vecALU.head.io.cmp_out)
    )
  val recomposer = Module(new LaneRecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = recomposerTypes,
  ))

  val ioReqOp = IntOpDecoder.decode(inst(Opcode), inst(F3), inst(F7))
  val reqOp = RegEnable(ioReqOp, 0.U.asTypeOf(aluOpT), io.req.fire)

  val aluOut = Reg(Vec(numLanes, UInt(archLen.W)))
  val cmpOut = Reg(Vec(numLanes, Bool()))
  val busy = RegInit(false.B)

  io.req.ready := !busy || io.resp.fire
  decomposer.io.in.valid := io.req.valid
  decomposer.io.in.bits.data(0) := MuxCase(
    io.req.bits.rs1Data.get,
    Seq(
      inst.b(Rs1IsPC) -> VecInit.fill(numLanes)(uop.pc),
      inst.b(Rs1IsZero) -> VecInit.fill(numLanes)(0.U(archLen.W)),
    )
  )
  decomposer.io.in.bits.data(1) := Mux(
    inst.b(Rs2IsImm),
    VecInit.fill(numLanes)(inst(Imm32)),
    io.req.bits.rs2Data.get
  )
  decomposer.io.out.ready := true.B

  for (i <- 0 until numALULanes) {
    vecALU(i).io.dw := archLen.U
    vecALU(i).io.fn := Mux(io.req.fire, ioReqOp, reqOp)
    vecALU(i).io.in1 := decomposer.io.out.bits.data(0)(i)
    vecALU(i).io.in2 := decomposer.io.out.bits.data(1)(i)

    recomposer.io.in.bits.data(0)(i) := vecALU(i).io.out
    recomposer.io.in.bits.data(1)(i) := vecALU(i).io.cmp_out
  }
  recomposer.io.in.valid := decomposer.io.out.valid
  recomposer.io.out.ready := busy

  io.resp.valid := respValid
  io.resp.bits.reg.get.valid := respValid && !reqInst.b(IsBranch) && !reqInst.b(IsJump)
  io.resp.bits.reg.get.bits.rd := reqRd
  io.resp.bits.reg.get.bits.data := Mux(reqInst.b(IsBranch), VecInit(Seq.fill(numLanes)(reqPC)), aluOut)
  io.resp.bits.reg.get.bits.tmask := Mux(reqInst.b(IsBranch), cmpOut.asUInt, reqTmask)

  val schedResp = io.resp.bits.sched.get
  schedResp := 0.U.asTypeOf(schedResp)

  val branchTakenMask = reqTmask & cmpOut.asUInt
  val setPcValid = reqInst.b(IsJump) || (reqInst.b(IsBranch) && branchTakenMask.orR)

  schedResp.valid := respValid && setPcValid
  schedResp.bits.setPC.valid := setPcValid
  schedResp.bits.setPC.bits := Mux(reqInst.b(IsBranch),
    (reqPC + reqInst(Imm32)).asTypeOf(pcT), // to shashank: cannot add at req fire, that causes race for reqPC value
    PriorityMux(reqTmask, aluOut).asTypeOf(pcT))
  schedResp.bits.setTmask.valid := reqInst.b(IsBranch)
  schedResp.bits.setTmask.bits := branchTakenMask

  when (io.resp.fire) {
    busy := false.B
    respValid := false.B
  }

  when (recomposer.io.out.fire) {
    aluOut := recomposer.io.out.bits.data(0)
    cmpOut := recomposer.io.out.bits.data(1)
    respValid := true.B
  }

  when (io.req.fire) {
    busy := true.B
  }
}
