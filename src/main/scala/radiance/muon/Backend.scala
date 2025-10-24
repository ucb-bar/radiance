package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.fp.CVFPU

class Backend(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(ibufEntryT)))
  })

  // temporary placeholders to generate reg file banks for par
  val rfBanks = Seq.fill(muonParams.numRegBanks)(SRAM(
    size = muonParams.numPhysRegs / muonParams.numRegBanks,
    tpe = UInt((muonParams.archLen * muonParams.numLanes).W),
    numReadPorts = 1,
    numWritePorts = 1,
    numReadwritePorts = 0
  ))

  rfBanks.foreach { b =>
    b.readPorts.head.enable := false.B
    b.readPorts.head.address := 0.U
    b.writePorts.head.enable := false.B
    b.writePorts.head.address := 0.U
    b.writePorts.head.data := 0.U

    dontTouch(b.readPorts.head)
    dontTouch(b.writePorts.head)
  }

  val fpu = Module(new CVFPU)
  fpu.io.clock := clock
  fpu.io.reset := reset
  fpu.io.req.bits := DontCare
  fpu.io.req.valid := false.B
  fpu.io.resp.ready := false.B
  fpu.io.flush := false.B
  dontTouch(fpu.io)

  io.ibuf.foreach(_.ready := true.B)

  val scoreboard = Module(new Scoreboard)
  // TODO only looking at warp 0 ibuf
  // TODO merge scoreboard updates from RS admit + writeback
  scoreboard.io.readRs1.enable := io.ibuf(0).valid
  scoreboard.io.readRs1.pReg := io.ibuf(0).bits.inst.rs1
  scoreboard.io.readRs2.enable := false.B
  scoreboard.io.readRs2.pReg := DontCare
  scoreboard.io.readRs3.enable := false.B
  scoreboard.io.readRs3.pReg := DontCare
  scoreboard.io.readRd.enable := false.B
  scoreboard.io.readRd.pReg := DontCare
  // TODO rs2-rd

  scoreboard.io.update.enable := true.B
  scoreboard.io.update.pReg := 1.U
  scoreboard.io.update.readInc := true.B
  scoreboard.io.update.readDec := false.B
  scoreboard.io.update.writeInc := false.B
  scoreboard.io.update.writeDec := true.B
  dontTouch(scoreboard.io)

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
