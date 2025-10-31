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
    val schedWb = Output(schedWritebackT)
  })

  val bypass = false

  val issued = if (bypass) {
    val issueArb = Module(new RRArbiter(uopT, io.ibuf.length))
    (issueArb.io.in zip io.ibuf).foreach { case (a, b) => a <> b }
    issueArb.io.out
  } else {
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

    reservStation.io.issue
  }

  // TODO: Collector
  // temporary placeholders to generate reg file banks for par
  val rfBanks = Seq.fill(3)(Seq.fill(muonParams.numRegBanks)(SRAM(
    size = muonParams.numPhysRegs / muonParams.numRegBanks,
    tpe = Vec(numLanes, UInt(archLen.W)),
    numReadPorts = 1,
    numWritePorts = 1,
    numReadwritePorts = 0
  )))

  // read registers for execute
  def prAddresses(pr: UInt) = {
    (pr(7, 6), pr(5, 0)) // im lazy TODO
  }

  val executeIn = Wire(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = true))
  val haves = Seq(HasRs1, HasRs2, HasRs3)
  val regs = Seq(Rs1, Rs2, Rs3)
  val dests = Seq(executeIn.rs1Data, executeIn.rs2Data, executeIn.rs3Data).map(_.get)

  (haves lazyZip dests lazyZip regs lazyZip rfBanks).foreach { case (has, dest, reg, banks) =>
    val pr = issued.bits.inst(reg)
    val bankReads = VecInit(banks.map(_.readPorts.head))
    val bankId = prAddresses(pr)._1

    bankReads.foreach(_.address := prAddresses(pr)._2)
    bankReads.foreach(_.enable := false.B)
    bankReads(bankId).enable := issued.valid && issued.bits.inst.b(has)
    dest := Mux(RegNext(pr === 0.U),
      VecInit.fill(numLanes)(0.U(archLen.W)),
      VecInit(bankReads.map(_.data))(RegNext(bankId)))
  }

  executeIn.uop := RegNext(issued.bits, 0.U.asTypeOf(executeIn.uop.cloneType))

  val execute = Module(new Execute())
  execute.io.req.valid := RegNext(issued.valid)
  execute.io.req.bits := executeIn

  // handle execute writeback
  val exSchedWb = execute.io.resp.bits.sched.get
  io.schedWb.valid := execute.io.resp.valid && exSchedWb.valid
  io.schedWb.bits := exSchedWb.bits
  execute.io.resp.ready := true.B // scheduler writeback is valid only

  val exRegWb = execute.io.resp.bits.reg.get
  rfBanks.foreach { case (banks) =>
    val prd = prAddresses(exRegWb.bits.rd)
    val bankWrites = VecInit(banks.map(_.writePorts.head))
    bankWrites.foreach { b =>
      b.address := prd._2
      b.data := exRegWb.bits.data
      b.enable := false.B
    }
    bankWrites(prd._1).enable := execute.io.resp.fire && exRegWb.valid
  }

  if (bypass) {
    val inFlight = RegInit(false.B)
    when (issued.fire) {
      inFlight := true.B
    }
    when (execute.io.resp.fire) {
      inFlight := false.B
    }

    issued.ready := !inFlight
    assert(RegNext(issued.fire) === execute.io.req.fire)
  }


  io.dmem.req.foreach(_.valid := false.B)
  io.dmem.req.foreach(_.bits := DontCare)
  io.dmem.resp.foreach(_.ready := false.B)
  io.smem.req.foreach(_.valid := false.B)
  io.smem.req.foreach(_.bits := DontCare)
  io.smem.resp.foreach(_.ready := false.B)
}
