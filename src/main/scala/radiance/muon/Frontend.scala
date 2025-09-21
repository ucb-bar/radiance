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
  // TODO: Decode
  // TODO: Rename

  // IBuffer
  val ibuffer = Module(new InstBuffer)
  // TODO: enq
  (io.ibuf zip ibuffer.io.deq).foreach { case (i, ib) => i <> ib }
}
