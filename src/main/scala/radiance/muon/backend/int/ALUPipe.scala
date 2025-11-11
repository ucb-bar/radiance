package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.ALU
import freechips.rocketchip.tile.HasNonDiplomaticTileParameters
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class ALUPipe(implicit p: Parameters)
  extends ExPipe(
    decomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), UInt(p(MuonKey).archLen.W))),
    recomposerTypes = Some(Seq(UInt(p(MuonKey).archLen.W), Bool())),
    outLanes = Some(p(MuonKey).intPipe.numALULanes),
    writebackSched = true, writebackReg = true)
    with HasIntPipeParams with HasCoreBundles with HasNonDiplomaticTileParameters {

  assert(xLen == 32, "alu requires 32 bit xlen")

  val vecALU = Seq.fill(numALULanes)(Module(new ALU))
  vecALU.foreach(alu => alu.io.dw := archLen.U)

  val ioReqOp = IntOpDecoder.decode(inst(Opcode), inst(F3), inst(F7))
  val reqOp = RegEnable(ioReqOp, 0.U.asTypeOf(aluOpT), io.req.fire)

  val aluOut = recomposer.get.io.out.bits.data(0)
  val cmpOut = recomposer.get.io.out.bits.data(1)

  io.req.ready := !busy || io.resp.fire
  decomposer.get.io.in.valid := io.req.valid
  decomposer.get.io.in.bits.data(0) := MuxCase(
    io.req.bits.rs1Data.get,
    Seq(
      inst.b(Rs1IsPC) -> VecInit.fill(numLanes)(uop.pc),
      inst.b(Rs1IsZero) -> VecInit.fill(numLanes)(0.U(archLen.W)),
    )
  )
  decomposer.get.io.in.bits.data(1) := MuxCase(
    io.req.bits.rs2Data.get,
    Seq(
      inst.b(Rs2IsImm) -> VecInit.fill(numLanes)(inst(Imm32)),
      inst.b(Rs1IsZero) -> VecInit.fill(numLanes)(inst(LuiImm)) // LUI
    )
  )
  decomposer.get.io.out.ready := true.B

  for (i <- 0 until numALULanes) {
    vecALU(i).io.dw := archLen.U
    vecALU(i).io.fn := Mux(io.req.fire, ioReqOp, reqOp)
    vecALU(i).io.in1 := decomposer.get.io.out.bits.data(0)(i)
    vecALU(i).io.in2 := decomposer.get.io.out.bits.data(1)(i)

    recomposer.get.io.in.bits.data(0)(i) := vecALU(i).io.out
    recomposer.get.io.in.bits.data(1)(i) := vecALU(i).io.cmp_out
  }
  recomposer.get.io.in.valid := decomposer.get.io.out.valid
  recomposer.get.io.out.ready := io.resp.ready

  io.resp.valid := recomposer.get.io.out.valid
  io.resp.bits.reg.get.valid := recomposer.get.io.out.valid && reqInst.b(HasRd)
  io.resp.bits.reg.get.bits.rd := reqRd
  io.resp.bits.reg.get.bits.data := Mux(reqInst.b(IsJump),
    VecInit(Seq.fill(numLanes)(reqPC + m.instBytes.U)),
    aluOut)
  io.resp.bits.reg.get.bits.tmask := Mux(reqInst.b(IsBranch), cmpOut.asUInt, reqTmask)

  val schedResp = io.resp.bits.sched.get
  schedResp := 0.U.asTypeOf(schedResp)

  val branchTakenMask = reqTmask & cmpOut.asUInt
  schedResp.valid := reqInst.b(IsJump) || reqInst.b(IsBranch)
  schedResp.bits.setPC.valid := reqInst.b(IsJump) || (reqInst.b(IsBranch) && branchTakenMask.orR)
  schedResp.bits.setPC.bits := Mux(reqInst.b(IsBranch),
    (reqPC + reqInst(Imm32)).asTypeOf(pcT), // to shashank: cannot add at req fire, that causes race for reqPC value
    PriorityMux(reqTmask, aluOut).asTypeOf(pcT))
  schedResp.bits.pc := latchedUop.pc
  schedResp.bits.wid := latchedUop.wid
}
