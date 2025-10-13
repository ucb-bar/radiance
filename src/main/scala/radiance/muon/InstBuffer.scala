package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class InstBufferEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val pc = UInt(addressBits.W)
  val op = UInt(Isa.opcodeBits.W)
  val rd = UInt(Isa.regBits.W)
  val rs1 = UInt(Isa.regBits.W)
  val rs2 = UInt(Isa.regBits.W)
  val rs3 = UInt(Isa.regBits.W)
  val imm = UInt(Isa.immBits.W)
  val pred = UInt(Isa.predBits.W)
  val tmask = UInt(muonParams.numLanes.W)
  // TODO: op ext, pipe-specific args e.g. fpu rm
}

class InstBuffer(implicit p: Parameters) extends CoreModule()(p) with HasFrontEndBundles {
  val io = IO(new Bundle {
    val enq = Flipped(ibufEnqIO)
    val deq = Vec(muonParams.numWarps, Decoupled(new InstBufferEntry))
  })

  // TODO: fifo logic
  io.enq.count.foreach(_ := 0.U) // TODO
  io.deq.foreach(_.valid := false.B)
  io.deq.foreach(_.bits := DontCare)
}
