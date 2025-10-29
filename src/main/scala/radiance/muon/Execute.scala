package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.int._
import radiance.muon.backend.fp._

class Execute(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {

  val io = IO(new Bundle {
    val req = Flipped(Decoupled(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = true)))
    val resp = Decoupled(writebackT())
  })

  val aluPipe = Module(new ALUPipe())
  val fp32Pipe = Module(new FP32Pipe())
  val fp16Pipe = Module(new FP16Pipe())
  val mulDivPipe = Module(new MulDivPipe())
  val lsuPipe = Module(new ALUPipe()) // TODO: should be lsu pipe
  val sfuPipe = Module(new SFU()) // TODO: doesn't work yet

  val inst = io.req.bits.uop.inst

  // val pipes = Seq(aluPipe, fp32Pipe, fp16Pipe, mulDivPipe, lsuPipe, aluPipe)
  val pipes = Seq(aluPipe, aluPipe, aluPipe, aluPipe, aluPipe, aluPipe)
  val uses = Seq(UseALUPipe, UseFP32Pipe, UseFP16Pipe, UseMulDivPipe, UseLSUPipe, UseSFUPipe)

  (pipes zip uses).foreach { case (pipe, use) =>
    pipe.io.req.valid := io.req.valid && inst.b(use)
    pipe.io.req.bits.uop := io.req.bits.uop
    pipe.io.req.bits.rs1Data.foreach(_ := io.req.bits.rs1Data.get)
    pipe.io.req.bits.rs2Data.foreach(_ := io.req.bits.rs2Data.get)
    pipe.io.req.bits.rs3Data.foreach(_ := io.req.bits.rs3Data.get)
  }
  io.req.ready := Mux1H(VecInit(uses.map(inst.b)), pipes.map(_.io.req.ready))

  val respArbiter = Module(new RRArbiter(writebackT(), pipes.length))
  (respArbiter.io.in zip pipes).foreach { case (arbIn, pipe) =>
    arbIn.bits.sched.get := pipe.io.resp.bits.sched.getOrElse(DontCare)
    arbIn.bits.reg.get := pipe.io.resp.bits.reg.getOrElse(DontCare)
    arbIn.valid := pipe.io.resp.valid
    pipe.io.resp.ready := arbIn.ready
  }
}
