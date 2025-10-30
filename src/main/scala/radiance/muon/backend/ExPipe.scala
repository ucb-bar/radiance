package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

class ExIOBundle(
  writebackSched: Boolean = true,
  writebackReg: Boolean = true,
  requiresRs3: Boolean = false
)(implicit val p: Parameters)
  extends Bundle with HasCoreBundles {
  val req = Flipped(Decoupled(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = requiresRs3)))
  val resp = Decoupled(writebackT(writebackSched, writebackReg))
}

abstract class ExPipe(
  writebackSched: Boolean = true,
  writebackReg: Boolean = true,
  decomposerTypes: Option[Seq[Data]] = None,
  recomposerTypes: Option[Seq[Data]] = None,
  outLanes: Option[Int] = None,
  requiresRs3: Boolean = false,
)(implicit p: Parameters) extends CoreModule with HasCoreBundles {
  val io = IO(new ExIOBundle(writebackSched, writebackReg, requiresRs3))

  val decomposer = decomposerTypes.map { et =>
    Module(new LaneDecomposer(
      inLanes = numLanes,
      outLanes = outLanes.get,
      elemTypes = et
    ))
  }

  val recomposer = recomposerTypes.map { et =>
    Module(new LaneRecomposer(
      inLanes = numLanes,
      outLanes = outLanes.get,
      elemTypes = et
    ))
  }


  val busy = RegInit(false.B)

  val uop = io.req.bits.uop
  val inst = uop.inst.expand()
  val latchedUop = RegEnable(io.req.bits.uop, 0.U.asTypeOf(uopT), io.req.fire)

  def reqInst = latchedUop.inst.expand()
  def reqPC = latchedUop.pc
  def reqTmask = latchedUop.tmask
  def reqWid = latchedUop.wid
  def reqRd = reqInst(Rd)

  val respValid = RegInit(false.B)

  // req fire has precedence
  when (io.req.fire) {
    busy := true.B
  }.elsewhen (io.resp.fire) {
    busy := false.B
  }
}
