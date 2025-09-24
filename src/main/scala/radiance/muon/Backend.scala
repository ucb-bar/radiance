package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Backend(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val ibuf = Vec(muonParams.numWarps, Flipped(Decoupled(new InstBufferEntry)))
  })

  io.ibuf.foreach(_.ready := true.B)

  // TODO: Scoreboard
  // TODO: Issue queue
  // TODO: Collector
  // TODO: FPU/INT/SFU
  // TODO: LSU
  io.dmem.req.valid := false.B
  io.dmem.req.bits := DontCare
  io.dmem.resp.ready := false.B
  io.smem.req.valid := false.B
  io.smem.req.bits := DontCare
  io.smem.resp.ready := false.B
  // TODO: Writeback
}
