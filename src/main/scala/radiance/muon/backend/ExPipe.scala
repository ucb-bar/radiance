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
  val latchedUop = RegEnable(io.req.bits.uop, 0.U.asTypeOf(uopT), io.req.fire)

  def reqInst = latchedUop.inst.expand()
  def reqPC = latchedUop.pc
  def reqTmask = latchedUop.tmask
  def reqWid = latchedUop.wid
  def reqRd = reqInst(Rd)

  val respValid = RegInit(false.B)
}
