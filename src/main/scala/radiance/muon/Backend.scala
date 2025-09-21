package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Backend(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val dmem = new DataMemIO
    val ibuf = Vec(muonParams.numWarps, Flipped(Decoupled(new InstBufferEntry)))
  })

  // TODO: Scoreboard
  // TODO: Issue queue
  // TODO: Collector
  // TODO: FPU/INT/SFU
  // TODO: LSU
  // TODO: Writeback
}
