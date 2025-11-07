package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.fp.CVFPU

class Backend(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val feCSR = Flipped(feCSRIO)
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(uopT)))
    val schedWb = Output(schedWritebackT)
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val coreId = Input(UInt(muonParams.coreIdBits.W))
    val softReset = Input(Bool())
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
  reservStation.io.collector.readReq.ready := false.B
  reservStation.io.collector.readResp.ports.foreach(_.valid := false.B)
  reservStation.io.collector.readResp.ports.foreach(_.bits := DontCare)

  val fakeExPipe = Module(new FakeWriteback)
  fakeExPipe.io.issue <> reservStation.io.issue
  reservStation.io.writeback <> fakeExPipe.io.writeback

  val bypass = true
  val issued = if (bypass) {
    hazard.reset := true.B
    scoreboard.reset := true.B
    reservStation.reset := true.B
    fakeExPipe.reset := true.B

    val issueArb = Module(new RRArbiter(uopT, io.ibuf.length))
    (issueArb.io.in zip io.ibuf).foreach { case (a, b) => a <> b }
    issueArb.io.out
  } else {
    reservStation.io.issue
  }

  val execute = Module(new Execute())
  execute.io.id.clusterId := io.clusterId
  execute.io.id.coreId := io.coreId
  execute.io.softReset := io.softReset
  execute.io.feCSR := io.feCSR

  val executeIn = WireInit(0.U.asTypeOf(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = true)))

  val haves = Seq(HasRs1, HasRs2, HasRs3)
  val regs = Seq(Rs1, Rs2, Rs3)
  val operands = Seq(executeIn.rs1Data, executeIn.rs2Data, executeIn.rs3Data).map(_.get)

  val collector = Module(new DuplicatedCollector)
  collector.io.readReq.valid := collector.io.readReq.bits.anyEnabled()

  (haves lazyZip regs lazyZip collector.io.readReq.bits.regs).foreach { case (has, reg, collReq) =>
    val pReg = issued.bits.inst(reg)
    collReq.enable := issued.valid && issued.bits.inst.b(has)
    collReq.pReg := pReg
  }

  // TODO: connect with executeIn ready
  collector.io.readResp.ports.foreach(_.ready := true.B)
  collector.io.operands.collEntry.foreach(_ := 0.U) // DuplicatedCollector has 1 entry
  (operands zip collector.io.operands.data).foreach { case (opnd, cData) =>
    opnd := cData
  }

  // signal execute on collector finish
  // TODO: relax 1-cycle delay
  execute.io.req.valid := RegNext(issued.valid)
  execute.io.req.bits := executeIn
  executeIn.uop := RegNext(issued.bits, 0.U.asTypeOf(executeIn.uop.cloneType))

  // execute-to-issue writeback
  val exRegWb = execute.io.resp.bits.reg.get
  collector.io.writeReq.bits.regs.head.enable := execute.io.resp.fire && exRegWb.valid
  collector.io.writeReq.bits.regs.head.pReg := exRegWb.bits.rd
  collector.io.writeReq.bits.regs.head.data.get := exRegWb.bits.data
  collector.io.writeReq.valid := collector.io.writeReq.bits.anyEnabled()
  // TODO: tmask
  collector.io.writeResp.ports.foreach(_.ready := true.B)
  dontTouch(collector.io)

  reservStation.io.writeback <> execute.io.resp.bits.reg.get

  // execute-to-schedule writeback
  val exSchedWb = execute.io.resp.bits.sched.get
  io.schedWb.valid := execute.io.resp.valid && exSchedWb.valid
  io.schedWb.bits := exSchedWb.bits
  // scheduler writeback is valid only; TODO: consider collector writeback ready
  execute.io.resp.ready := true.B

  // Fallback: stall every instruction until writeback
  if (bypass) {
    val inFlight = RegInit(false.B)
    when (issued.fire) {
      inFlight := true.B
    }
    issued.ready := !inFlight
    execute.io.req.valid := RegNext(issued.fire)
    assert(RegNext(issued.fire) === execute.io.req.fire)
    when (execute.io.resp.fire) {
      inFlight := false.B
    }
  }

  when (execute.io.req.fire) {
    val e = execute.io.req.bits
    printf(cf"[ISSUE]     clid=${io.clusterId} cid=${io.coreId} wid=${e.uop.wid} " +
      cf"pc=${e.uop.pc}%x inst=${e.uop.inst.expand()(Raw)}%x " +
      cf"tmask=${e.uop.tmask}%b rd=${e.uop.inst(Rd)} rs1=[" +
      e.rs1Data.get.map(x => cf"$x%x ").reduce(_ + _) +
      "] rs2=[" +
      e.rs2Data.get.map(x => cf"$x%x ").reduce(_ + _) +
      cf"]\n")
  }
  when (execute.io.resp.fire) {
    val r = execute.io.resp.bits.reg.get.bits
    val s = execute.io.resp.bits.sched.get.bits
    printf(cf"[WRITEBACK] clid=${io.clusterId} cid=${io.coreId} wid=${s.wid} pc=${s.pc}%x " +
      cf"scheduler wb=${execute.io.resp.bits.sched.get.valid} " +
      cf"setPC=${s.setPC.valid} ${s.setPC.bits}%x " +
      cf"setTmask=${s.setTmask.valid} ${s.setTmask.bits}%b " +
      cf"wspawn=${s.wspawn.valid} pc=${s.wspawn.bits.pc}%x count=${s.wspawn.bits.count} " +
      cf"ipdom=${s.ipdomPush.valid} else mask=${s.ipdomPush.bits.elseMask}%x else pc=${s.ipdomPush.bits.elsePC} " +
      cf"\n")
    printf(cf"reg wb=${execute.io.resp.bits.reg.get.valid} " +
      cf"rd=${r.rd} data=[" +
      r.data.map(x => cf"$x%x ").reduce(_ + _) +
      cf"] mask=${r.tmask}%b" +
      cf"\n")
  }

  io.dmem.req.foreach(_.valid := false.B)
  io.dmem.req.foreach(_.bits := DontCare)
  io.dmem.resp.foreach(_.ready := false.B)
  io.smem.req.foreach(_.valid := false.B)
  io.smem.req.foreach(_.bits := DontCare)
  io.smem.resp.foreach(_.ready := false.B)
}
