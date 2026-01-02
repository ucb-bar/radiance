package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

class RegWriteback(implicit p: Parameters) extends CoreBundle()(p) {
  val rd = pRegT
  val data = Vec(muonParams.numLanes, regDataT)
  val tmask = tmaskT
}

class IssueIF(implicit p: Parameters) extends CoreBundle()(p) {
  val regAddr = UInt(Isa.regBits.W)
  val data = Vec(numLanes, UInt(archLen.W))
  val wmask = UInt(numLaneBytes.W)
}

class SchedIF(implicit p: Parameters) extends CoreBundle()(p) {
  val pc = UInt(muonParams.archLen.W)
}

class Writeback(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(writebackT()))
    val rfWIssueIF = Output(Valid(new IssueIF))
    val schedIF = Output(Valid(new SchedIF))
  })

  // TODO optimize for multi bank write if we have time
  val regRF    = RegInit(0.U.asTypeOf(Valid(new IssueIF)))
  val regSched = RegInit(0.U.asTypeOf(Valid(new SchedIF)))
  io.rfWIssueIF := regRF
  io.schedIF := regSched
  
  when (io.req.fire) {
    regRF.valid := io.req.bits.reg.get.valid
    regRF.bits.regAddr := io.req.bits.reg.get.bits.rd
    regRF.bits.data := io.req.bits.reg.get.bits.data
    regRF.bits.wmask := VecInit(io.req.bits.reg.get.bits.tmask.asBools.map(t => Fill(archLen/8, t.asUInt)))
    regSched.valid := io.req.bits.reg.get.valid && io.req.bits.sched.get.bits.setPC.valid
    regSched.bits.pc := io.req.bits.sched.get.bits.setPC.bits
  }
}
