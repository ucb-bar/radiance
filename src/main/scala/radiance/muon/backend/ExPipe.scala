package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

abstract class ExPipe(
  writebackSched: Boolean = true,
  writebackReg: Boolean = true)
(implicit p: Parameters) extends CoreModule with HasCoreBundles {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(fuInT(hasRs1 = true, hasRs2 = true)))
    val resp = Decoupled(writebackT(writebackSched, writebackReg))
  })

  val inst = io.req.bits.uop.inst
  val req_pc = Reg(UInt(archLen.W))
  val req_tmask = Reg(UInt(numLanes.W))
  val req_rd = Reg(UInt(Isa.regBits.W))
  val req_wid = RegInit(0.U(log2Ceil(muonParams.numWarps).W))
  val resp_valid = RegInit(false.B)
}
