package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.ALU
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.{LaneDecomposer, LaneRecomposer}

class ALUPipe(implicit p: Parameters)
  extends IntPipe {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W))
  val aluDecomposer = Module(new LaneDecomposer(
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
  val aluRecomposer = Module(new LaneRecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = recomposerTypes,
  ))

  val alu_out = Reg(Vec(numLanes, UInt(archLen.W)))
  val cmp_out = Reg(Vec(numLanes, Bool()))
  val busy = RegInit(false.B)

  io.req.ready := !busy || io.resp.fire
  aluDecomposer.io.in.valid := io.req.valid && !ioIntOp.isMulDiv
  aluDecomposer.io.in.bits.data(0) := io.req.bits.in1
  aluDecomposer.io.in.bits.data(1) := io.req.bits.in2
  aluDecomposer.io.in.bits.tmask := io.req.bits.tmask
  aluDecomposer.io.out.ready := true.B

  for (i <- 0 until numALULanes) {
    vecALU(i).io.dw := archLen.U
    vecALU(i).io.fn := Mux(io.req.fire, ioIntOp.fn, req_op.fn)
    vecALU(i).io.in1 := aluDecomposer.io.out.bits.data(0)(i)
    vecALU(i).io.in2 := aluDecomposer.io.out.bits.data(1)(i)

    aluRecomposer.io.in.bits.data(0)(i) := vecALU(i).io.out
    aluRecomposer.io.in.bits.data(1)(i) := vecALU(i).io.cmp_out
  }
  aluRecomposer.io.in.valid := aluDecomposer.io.out.valid
  aluRecomposer.io.in.bits.tmask := aluDecomposer.io.out.bits.tmask
  aluRecomposer.io.out.ready := true.B

  io.resp.valid := resp_valid
  io.resp.bits.rd := req_rd
  io.resp.bits.data := Mux(req_op.isBr, VecInit(Seq.fill(numLanes)(req_pc)), alu_out)
  io.resp.bits.tmask := Mux(req_op.isBr,
    cmp_out.asUInt,
    req_tmask
  )
  io.resp.bits.pc_w_en := req_op.isBr || req_op.isJ

  when (io.resp.fire) {
    busy := false.B
    resp_valid := false.B
  }

  when (aluRecomposer.io.out.fire) {
    alu_out := aluRecomposer.io.out.bits.data(0)
    cmp_out := aluRecomposer.io.out.bits.data(1)
    resp_valid := true.B
  }

  when (io.req.fire) {
    busy := true.B
    req_op := ioIntOp
    req_pc := io.req.bits.pc
    req_tmask := io.req.bits.tmask
    req_rd := io.req.bits.rd
  }
}
