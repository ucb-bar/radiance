package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._

class SFUPipe(implicit p: Parameters) extends ExPipe(true, false) with HasCoreBundles {
  // TODO: piping, csr

  val firstLidOH = PriorityEncoderOH(uop.tmask)
  val firstRs1 = Mux1H(firstLidOH, io.req.bits.rs1Data.get)
  val firstRs2 = Mux1H(firstLidOH, io.req.bits.rs2Data.get)
  val rs1Mask = VecInit(io.req.bits.rs1Data.get.map(_(0))).asUInt

  val writeback = io.resp.bits.sched.get

  writeback.bits.setTmask.bits := DontCare
  writeback.bits.setTmask.valid := inst.b(IsTMC) || inst.b(IsSplit) || inst.b(IsPred) || inst.b(IsToHost)

  writeback.bits.setPC.bits := DontCare
  writeback.bits.setPC.valid := false.B

  writeback.bits.ipdomPush.bits := DontCare
  writeback.bits.ipdomPush.valid := inst.b(IsSplit)

  writeback.bits.wspawn.bits := DontCare
  writeback.bits.wspawn.valid := inst.b(IsWSpawn)

  writeback.bits.pc := uop.pc
  writeback.bits.wid := uop.wid

  // TODO: handshake, pipeline
  writeback.valid := io.req.fire
  io.req.ready := io.resp.ready

  when (inst.b(IsTMC)) {
    writeback.bits.setTmask.bits := firstRs1
  }

  when (inst.b(IsWSpawn)) {
    writeback.bits.wspawn.bits.count := firstRs1
    writeback.bits.wspawn.bits.pc := firstRs2
  }

  when (inst.b(IsSplit)) {
    // vortex specifies rs2 addr = 1, but this might get renamed.
    // however, this logic still holds because x0 always gets renamed to 0;
    // furthermore, x0 also does not have a wid prefix.
    val invert = inst(Rs2) =/= 0.U

    val thenMask = uop.tmask & rs1Mask
    val elseMask = uop.tmask & (~rs1Mask).asUInt
    val divergent = thenMask.orR && elseMask.orR

    writeback.bits.ipdomPush.bits.restoredMask := uop.tmask
    writeback.bits.ipdomPush.bits.elseMask := Mux(invert, thenMask, elseMask)
    writeback.bits.ipdomPush.bits.elsePC := uop.pc + 8.U
    // this signals to scheduler if branch is non-divergent
    writeback.bits.setTmask.valid := divergent
    writeback.bits.setTmask.bits := Mux(invert, elseMask, thenMask)
  }

  when (inst.b(IsPred)) {
    val invert = inst(Rd) =/= 0.U
    val newTmask = uop.tmask & Mux(invert, (~rs1Mask).asUInt, rs1Mask)
    // vortex logic: if resultant mask is 0, set to first lane's rs2
    writeback.bits.setTmask.bits := Mux(newTmask.orR, newTmask, firstRs2)
  }

  when (io.req.fire) {
    when (inst.b(IsToHost)) {
      when (firstRs1 === 0.U) {
        printf("test passed!")
      }.otherwise {
        printf("test failed with tohost=%d", firstRs1)
      }
      writeback.bits.setTmask.bits := 0.U
    }.elsewhen (inst.b(IsCSR)) {
      assert(false.B, "i dont have csrs yet")
    }
  }

  io.req.ready := !busy || io.resp.fire
  io.resp.valid := busy
}
