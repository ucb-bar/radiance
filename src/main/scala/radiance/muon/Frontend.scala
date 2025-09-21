package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Frontend(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val ibuf = Vec(muonParams.numWarps, Decoupled(new InstBufferEntry))
    // TODO: writeback
  })

  // TODO: Scheduler
  // TODO: Fetch/I$
  io.imem.req.valid := false.B
  io.imem.req.bits := DontCare
  io.imem.resp.ready := true.B
  // TODO: Decode
  // TODO: Rename

  // IBuffer
  val ibuffer = Module(new InstBuffer)
  // TODO: enq
  ibuffer.io.enq.foreach(_.valid := false.B)
  ibuffer.io.enq.foreach(_.bits := DontCare)

  io.ibuf <> ibuffer.io.deq
}
