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
  reservStation.io.admit <> hazard.io.rsAdmit
  hazard.io.writeback <> reservStation.io.writebackHazard

  // TODO bogus
  val fakeExPipe = Module(new FakeWriteback)
  fakeExPipe.io.issue <> reservStation.io.issue
  reservStation.io.writeback <> fakeExPipe.io.writeback

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

  val execute = Module(new Execute())
  execute.io.req.valid := false.B
  execute.io.req.bits := DontCare // TODO
  execute.io.resp.ready := true.B
  dontTouch(execute.io.req)
  dontTouch(execute.io.resp)

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
