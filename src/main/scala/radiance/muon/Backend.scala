package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.int.LsuOpDecoder

class Backend(
  difftest: Boolean = false
)(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val lsuReserve = reservationIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val feCSR = Flipped(feCSRIO)
    val barrier = barrierIO
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(ibufEntryT)))
    val schedWb = Output(schedWritebackT)
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val coreId = Input(UInt(muonParams.coreIdBits.W))
    val flush = cacheFlushIO
    val softReset = Input(Bool())
    val perf = Output(new BackendPerfIO)
    /** PC/reg trace IO for diff-testing against model */
    val trace = Option.when(difftest)(Valid(new TraceIO))
  })

  // -----
  // issue
  // -----

  val hazard = Module(new Hazard)
  hazard.io.ibuf <> io.ibuf

  val scoreboard = Module(new Scoreboard)
  scoreboard.io.hazard <> hazard.io.scb
  dontTouch(scoreboard.io)

  val reservStation = Module(new ReservationStation)
  reservStation.io.admit <> hazard.io.rsAdmit
  scoreboard.io.updateColl <> reservStation.io.scb.updateColl
  scoreboard.io.updateWB <> reservStation.io.scb.updateWB

  val noILP = muonParams.noILP
  val issued = if (noILP) {
    hazard.reset := true.B
    scoreboard.reset := true.B
    reservStation.reset := true.B
    reservStation.io.issue.ready := false.B

    val issueArb = Module(new RRArbiter(ibufEntryT, io.ibuf.length))
    (issueArb.io.in zip io.ibuf).foreach { case (a, b) => a <> b }
    issueArb.io.out
  } else {
    reservStation.io.issue
  }

  io.perf.cyclesDecoded := PerfCounter(io.ibuf.map(_.valid).reduce(_ || _))
  io.perf.cyclesEligible := PerfCounter(issued.valid)
  io.perf.cyclesIssued := PerfCounter(issued.fire)
  io.perf.perWarp.zipWithIndex.foreach { case (p, wid) =>
    p.cyclesIssued := PerfCounter(issued.fire && (issued.bits.uop.wid === wid.U))
    p.stallsBusy := PerfCounter(issued.valid && (issued.bits.uop.wid === wid.U) &&
                                !issued.ready)
  }
  (io.perf.perWarp zip hazard.io.perf).foreach { case (p, h) =>
    p.cyclesDecoded := h.cyclesDecoded
    p.stallsWAW := h.stallsWAW
    p.stallsWAR := h.stallsWAR
  }

  // -----------------
  // operand collector
  // -----------------

  val haves = Seq(HasRs1, HasRs2, HasRs3)
  val regs = Seq(Rs1, Rs2, Rs3)
  val collector = Module(new DuplicatedCollector)
  collector.io.readReq.valid := collector.io.readReq.bits.anyEnabled()
  if (noILP) {
    // on noILP, manage collector entirely after issue
    // (haves lazyZip regs lazyZip collector.io.readData.resp lazyZip collector.io.readReq.bits.regs)
    //   .foreach { case (has, reg, readData, collReq) =>
    //     val pReg = issued.bits.uop.inst(reg)
    //     collReq.enable := issued.valid && issued.bits.uop.inst.b(has)
    //     collReq.pReg := pReg
    //     readData.enable := issued.valid && issued.bits.uop.inst.b(has)
    //     readData.pReg.get := pReg
    //     readData.collEntry := DontCare
    //   }
    // collector.io.readReq.bits.rsEntryId := DontCare

    reservStation.io.collector.readReq.ready := false.B
    reservStation.io.collector.readResp.ports.foreach(_.valid := false.B)
    reservStation.io.collector.readResp.ports.foreach(_.bits := DontCare)
    // reservStation.io.collector.readData.resp.foreach(_.data := DontCare)
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
  (operands zip collector.io.readData.resp.bits).foreach { case (opnd, port) =>
    // value-gate to reduce switching
    opnd := Mux(port.enable,
      port.data,
      VecInit.fill(numLanes)(0.U.asTypeOf(regDataT)))
  }

  // -------
  // execute
  // -------

  val execute = Module(new Execute())
  execute.io.id.clusterId := io.clusterId
  execute.io.id.coreId := io.coreId
  execute.io.softReset := io.softReset
  execute.io.feCSR := io.feCSR
  execute.io.barrier <> io.barrier
  execute.io.flush <> io.flush
  execute.io.req.bits := executeIn

  execute.io.mem.dmem <> io.dmem
  execute.io.mem.smem <> io.smem
  execute.io.lsuReserve <> io.lsuReserve

  if (noILP) {
    // fallback issue: stall every instruction until writeback
    // or execute req fire, for instructions that don't need writeback (i.e. stores, fences)
    val inFlight = RegInit(false.B)
    val hasIssued = RegInit(false.B)
    val willWriteback = RegInit(false.B)
    val inFlightWarp = RegInit(0.U.asTypeOf(widT))

    when (issued.fire) {
      inFlight := true.B
      willWriteback := true.B
      hasIssued := false.B
      inFlightWarp := issued.bits.uop.wid

      val isLsuInst = issued.bits.uop.inst.b(UseLSUPipe)
      when (isLsuInst) {
        val memOp = LsuOpDecoder.decode(issued.bits.uop.inst.opcode, issued.bits.uop.inst.f3)
        willWriteback := MemOp.isLoad(memOp) || MemOp.isAtomic(memOp)
      }
      val expanded = issued.bits.uop.inst.expand()
      when (expanded.b(IsNuInvoke) || expanded.b(IsFenceD) || expanded.b(IsFenceI)) {
        willWriteback := false.B
      }
    }
    issued.ready := !inFlight

    execute.io.req.valid := inFlight && !hasIssued
    
    // assumes 1-cycle latency collector
    // FIXME: this changes issue-vs-execute timing on noILP=true/false, which
    // is confusing
    val uop = RegEnable(issued.bits.uop, 0.U.asTypeOf(issued.bits.uop.cloneType), issued.fire)
    val token = RegEnable(issued.bits.token, 0.U.asTypeOf(issued.bits.token.cloneType), issued.fire)
    executeIn.uop := uop
    execute.io.token := token

    when (execute.io.req.fire) {
      when (willWriteback) {
        hasIssued := true.B
      }
      .otherwise {
        inFlight := false.B
      }
    }
    
    when (execute.io.resp.fire && willWriteback) {
      val sched = execute.io.resp.bits.sched.get
      when (!sched.valid || (inFlightWarp === sched.bits.wid)) {
        assert(inFlight)
        inFlight := false.B
      }
    }
  } else {
    issued.ready := execute.io.req.ready
    execute.io.req.valid := issued.valid
    executeIn.uop := issued.bits.uop
    execute.io.token := issued.bits.token
  }

  // drive regtrace IO for testing
  io.trace.foreach { traceIO =>
    traceIO.valid := execute.io.req.fire
    traceIO.bits.pc := executeIn.uop.pc
    traceIO.bits.warpId := executeIn.uop.wid
    (traceIO.bits.regs zip operands)
      .zipWithIndex.foreach { case ((tReg, opnd), rsi) =>
        tReg.enable := executeIn.uop.inst(haves(rsi))
        tReg.address := executeIn.uop.inst(regs(rsi))
        tReg.data := opnd
      }
  }

  io.perf.instRetired := execute.io.perf.instRetired
  io.perf.cycles := execute.io.perf.cycles

  // ---------
  // writeback
  // ---------

  // to schedule
  val exSchedWb = execute.io.resp.bits.sched.get
  io.schedWb.valid := execute.io.resp.fire && exSchedWb.valid
  io.schedWb.bits := exSchedWb.bits
  // scheduler writeback is valid only
  // TODO: consider collector writeback ready
  execute.io.resp.ready := true.B

  val exRegWb = execute.io.resp.bits.reg.get
  val exRegWbFire = execute.io.resp.fire && exRegWb.valid

  // to RS
  reservStation.io.writeback.valid := exRegWbFire
  reservStation.io.writeback.bits := exRegWb.bits

  // to collector
  collector.io.writeReq.bits.regs.head.enable := exRegWbFire
  collector.io.writeReq.bits.regs.head.pReg := exRegWb.bits.rd
  collector.io.writeReq.bits.regs.head.data.get := exRegWb.bits.data
  collector.io.writeReq.bits.rsEntryId := 0.U // TODO: writes don't need to allocate RS entry; remove this
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

object Perf {
  val counterWidth = 64
  def T = UInt(counterWidth.W)
}

class PerfCounter(width: Int = Perf.counterWidth) {
  private val cond_ = WireInit(false.B)
  val value = RegInit(0.U(width.W))
  when (cond_) {
    value := value + 1.U
  }
  def cond(c: Bool) = { cond_ := c }
}

object PerfCounter {
  def apply(cond: Bool): UInt = {
    val c = new PerfCounter
    c.cond(cond)
    c.value
  }
}

// use traits to flatten all sub-module counters into this
class BackendPerfIO(implicit p: Parameters) extends CoreBundle()(p)
with HasIssuePerfCounters {
  val instRetired = Output(UInt(Perf.counterWidth.W))
  val cycles = Output(UInt(Perf.counterWidth.W))
  /** any decoded instruction this cycle? */
  val cyclesDecoded = Output(UInt(Perf.counterWidth.W))
  /** any warp eligible for issue this cycle? */
  val cyclesEligible = Output(UInt(Perf.counterWidth.W))
  /** any warp issued this cycle? */
  val cyclesIssued = Output(UInt(Perf.counterWidth.W))
}
