package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.fp.CVFPU
import radiance.unittest.RegTraceIO
import radiance.muon.backend.int.LsuOpDecoder

class Backend(
  /** backend-as-top testbench config with register IOs */
  test: Boolean = false
)(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val lsuReserve = reservationIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val feCSR = Flipped(feCSRIO)
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(uopWithTokenT)))
    val schedWb = Output(schedWritebackT)
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val coreId = Input(UInt(muonParams.coreIdBits.W))
    val softReset = Input(Bool())
    val regTrace = Option.when(test)(Valid(new RegTraceIO))
  })

  // -----
  // issue
  // -----

  val hazard = Module(new Hazard)
  // @hansung TODO: hazard -> reservation station path needs to be updated to 
  // include token also
  // hazard.io.ibuf <> io.ibuf
  (hazard.io.ibuf zip io.ibuf).foreach {case (hazard_in, ibuf_out) =>
    hazard_in.bits := ibuf_out.bits.uop
    hazard_in.valid := ibuf_out.valid
    ibuf_out.ready := hazard_in.ready
  }

  val scoreboard = Module(new Scoreboard)
  scoreboard.io.updateRS <> hazard.io.scb.updateRS
  scoreboard.io.updateWB <> hazard.io.scb.updateWB
  scoreboard.io.readRd <> hazard.io.scb.readRd
  scoreboard.io.readRs1 <> hazard.io.scb.readRs1
  scoreboard.io.readRs2 <> hazard.io.scb.readRs2
  scoreboard.io.readRs3 <> hazard.io.scb.readRs3
  dontTouch(scoreboard.io)

  val reservStation = Module(new ReservationStation(test = test))
  reservStation.io.admit <> hazard.io.rsAdmit
  scoreboard.io.updateColl <> reservStation.io.scb.updateColl
  hazard.io.writeback <> reservStation.io.writebackHazard // TODO remove
  io.regTrace.foreach(_ <> reservStation.io.regTrace.get)

  val bypass = true
  val issued = if (bypass) {
    hazard.reset := true.B
    scoreboard.reset := true.B
    reservStation.reset := true.B
    reservStation.io.issue.ready := false.B

    val issueArb = Module(new RRArbiter(uopWithTokenT, io.ibuf.length))
    (issueArb.io.in zip io.ibuf).foreach { case (a, b) => a <> b }
    issueArb.io.out
  } else {
    // @hansung TODO: hazard -> reservation station path needs to be updated to 
    // include token also

    // reservStation.io.issue
    ???
  }

  // -----------------
  // operand collector
  // -----------------

  val collector = Module(new DuplicatedCollector)
  collector.io.readReq.valid := collector.io.readReq.bits.anyEnabled()
  if (bypass) {
    // on bypass, manage collector entirely after issue
    val haves = Seq(HasRs1, HasRs2, HasRs3)
    val regs = Seq(Rs1, Rs2, Rs3)
    (haves lazyZip regs lazyZip collector.io.readReq.bits.regs).foreach { case (has, reg, collReq) =>
      val pReg = issued.bits.uop.inst(reg)
      collReq.enable := issued.valid && issued.bits.uop.inst.b(has)
      collReq.pReg := pReg
    }
    collector.io.readData.regs.foreach(_.enable := true.B)
    collector.io.readData.regs.foreach(_.collEntry := 0.U) // DuplicatedCollector has 1 entry

    reservStation.io.collector.readReq.ready := false.B
    reservStation.io.collector.readResp.ports.foreach(_.valid := false.B)
    reservStation.io.collector.readResp.ports.foreach(_.bits := DontCare)
    reservStation.io.collector.readData.regs.foreach(_.data := DontCare)
  } else {
    // RS manages collector
    collector.io.readReq <> reservStation.io.collector.readReq
    reservStation.io.collector.readResp <> collector.io.readResp
    collector.io.readData <> reservStation.io.collector.readData
  }

  // drive EX operands from collector
  val executeIn = WireInit(0.U.asTypeOf(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = true)))
  val operands = Seq(executeIn.rs1Data, executeIn.rs2Data, executeIn.rs3Data).map(_.get)
  collector.io.readResp.ports.foreach(_.ready := true.B)
  (operands zip collector.io.readData.regs).foreach { case (opnd, port) =>
    opnd := port.data
  }

  // -------
  // execute
  // -------

  val execute = Module(new Execute())
  execute.io.id.clusterId := io.clusterId
  execute.io.id.coreId := io.coreId
  execute.io.softReset := io.softReset
  execute.io.feCSR := io.feCSR
  execute.io.req.bits := executeIn
  
  execute.io.token := issued.bits.token
  execute.io.mem.dmem <> io.dmem
  execute.io.mem.smem <> io.smem
  execute.io.lsuReserve <> io.lsuReserve

  if (bypass) {
    // fallback issue: stall every instruction until writeback
    // or execute req fire, for instructions that don't need writeback (i.e. stores, fences)
    val inFlight = RegInit(false.B)
    val hasIssued = RegInit(false.B)
    val willWriteback = RegInit(false.B)

    when (issued.fire) {
      inFlight := true.B
      willWriteback := true.B
      hasIssued := false.B

      val isLsuInst = issued.bits.uop.inst.b(UseLSUPipe)
      when (isLsuInst) {
        val memOp = LsuOpDecoder.decode(issued.bits.uop.inst.opcode, issued.bits.uop.inst.f3)
        willWriteback := MemOp.isLoad(memOp) || MemOp.isAtomic(memOp)
      }
    }
    issued.ready := !inFlight

    // assumes 1-cycle latency collector
    execute.io.req.valid := inFlight && !hasIssued
    val uop = RegEnable(issued.bits.uop, 0.U.asTypeOf(issued.bits.uop.cloneType), issued.fire)
    executeIn.uop := uop

    when (execute.io.req.fire) {
      when (willWriteback) {
        hasIssued := true.B
      }
      .otherwise {
        inFlight := false.B
      }
    }
    
    when (execute.io.resp.fire && willWriteback) {
      inFlight := false.B
    }
  } else {
    issued.ready := execute.io.req.ready
    execute.io.req.valid := issued.valid
    executeIn.uop := issued.bits.uop
  }

  // ---------
  // writeback
  // ---------

  // to schedule
  val exSchedWb = execute.io.resp.bits.sched.get
  io.schedWb.valid := execute.io.resp.valid && exSchedWb.valid
  io.schedWb.bits := exSchedWb.bits
  // scheduler writeback is valid only
  // TODO: consider collector writeback ready
  execute.io.resp.ready := true.B

  // to RS
  reservStation.io.writeback <> execute.io.resp.bits.reg.get

  // to collector
  val exRegWb = execute.io.resp.bits.reg.get
  collector.io.writeReq.bits.regs.head.enable := execute.io.resp.fire && exRegWb.valid
  collector.io.writeReq.bits.regs.head.pReg := exRegWb.bits.rd
  collector.io.writeReq.bits.regs.head.data.get := exRegWb.bits.data
  collector.io.writeReq.valid := collector.io.writeReq.bits.anyEnabled()
  // TODO: tmask
  collector.io.writeResp.ports.foreach(_.ready := true.B)
  dontTouch(collector.io)

  // debug
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
}
