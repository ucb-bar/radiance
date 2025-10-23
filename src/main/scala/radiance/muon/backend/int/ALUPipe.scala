package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.ALU
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class ALUPipe(implicit p: Parameters)
  extends ExPipe(true, true) with HasIntPipeParams {
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

  val ioIntOp = IntOpDecoder.decode(inst(Opcode), inst(F3), inst(F7))
  val req_op = Reg(new IntOpBundle)
  val alu_out = Reg(Vec(numLanes, UInt(archLen.W)))
  val cmp_out = Reg(Vec(numLanes, Bool()))
  val busy = RegInit(false.B)

  io.req.ready := !busy || io.resp.fire
  decomposer.io.in.valid := io.req.valid && !ioIntOp.isMulDiv
  decomposer.io.in.bits.data(0) := io.req.bits.rs1Data.get
  decomposer.io.in.bits.data(1) := io.req.bits.rs2Data.get
  decomposer.io.out.ready := true.B

  for (i <- 0 until numALULanes) {
    vecALU(i).io.dw := archLen.U
    vecALU(i).io.fn := Mux(io.req.fire, ioIntOp.fn, req_op.fn)
    vecALU(i).io.in1 := decomposer.io.out.bits.data(0)(i)
    vecALU(i).io.in2 := decomposer.io.out.bits.data(1)(i)

    recomposer.io.in.bits.data(0)(i) := vecALU(i).io.out
    recomposer.io.in.bits.data(1)(i) := vecALU(i).io.cmp_out
  }
  recomposer.io.in.valid := decomposer.io.out.valid
  recomposer.io.out.ready := busy

  io.resp.valid := resp_valid
  io.resp.bits.reg.get.valid := resp_valid && !req_op.isBr && !req_op.isJ
  io.resp.bits.reg.get.bits.rd := req_rd
  io.resp.bits.reg.get.bits.data := Mux(req_op.isBr, VecInit(Seq.fill(numLanes)(req_pc)), alu_out)
  io.resp.bits.reg.get.bits.tmask := Mux(req_op.isBr, cmp_out.asUInt, req_tmask)

  val schedResp = io.resp.bits.sched.get
  schedResp.valid := false.B
  schedResp.bits.wid := req_wid
  schedResp.bits.pc := req_pc
  schedResp.bits.setPC.valid := false.B
  schedResp.bits.setPC.bits := 0.U
  schedResp.bits.setTmask.valid := false.B
  schedResp.bits.setTmask.bits := 0.U
  schedResp.bits.ipdomPush.valid := false.B
  schedResp.bits.ipdomPush.bits.restoredMask := 0.U
  schedResp.bits.ipdomPush.bits.elseMask := 0.U
  schedResp.bits.ipdomPush.bits.elsePC := 0.U
  schedResp.bits.wspawn.valid := false.B
  schedResp.bits.wspawn.bits.count := 0.U
  schedResp.bits.wspawn.bits.pc := 0.U

  val branchTakenMask = req_tmask & cmp_out.asUInt
  val setPcValid = req_op.isJ || (req_op.isBr && branchTakenMask.orR)

  schedResp.valid := resp_valid && setPcValid
  schedResp.bits.setPC.valid := setPcValid
  schedResp.bits.setPC.bits := Mux(req_op.isBr, req_pc, PriorityMux(req_tmask, alu_out))
  schedResp.bits.setTmask.valid := req_op.isBr
  schedResp.bits.setTmask.bits := branchTakenMask

  when (io.resp.fire) {
    busy := false.B
    resp_valid := false.B
  }

  when (recomposer.io.out.fire) {
    alu_out := recomposer.io.out.bits.data(0)
    cmp_out := recomposer.io.out.bits.data(1)
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
