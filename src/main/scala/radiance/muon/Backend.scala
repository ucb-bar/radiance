package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.fp.CVFPU

class Backend(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(uopT)))
  })

  val hazard = Module(new Hazard)
  hazard.io.ibuf <> io.ibuf

  val scoreboard = Module(new Scoreboard)
  scoreboard.io <> hazard.io.scb
  dontTouch(scoreboard.io)

  val reservStation = Module(new ReservationStation)
  reservStation.io.enq <> hazard.io.rsEnq
  reservStation.io.issue.ready := true.B // TODO bogus

  // TODO: Collector
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

  // TODO: INT/SFU
  // TODO: LSU
  io.dmem.req.foreach(_.valid := false.B)
  io.dmem.req.foreach(_.bits := DontCare)
  io.dmem.resp.foreach(_.ready := false.B)
  io.smem.req.foreach(_.valid := false.B)
  io.smem.req.foreach(_.bits := DontCare)
  io.smem.resp.foreach(_.ready := false.B)
  // TODO: Writeback
}
