package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

/** Hazard module checks WAW/WAR hazards for instructions at the ibuffer heads,
 *  and gates their admission to the reservation station. IOW, the module
 *  resolves WAW/WAR hazards by stalling.  RAW hazards are handled at the
 *  reservation station.
 */
class Hazard(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    /** per-warp IBUF interface */
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(ibufEntryT)))
    /** scoreboard interface */
    val scb = new Bundle {
      val updateRS = Flipped(new ScoreboardUpdate)
      val updateWB = Flipped(new ScoreboardUpdate)
      val readRs1  = Flipped(new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits))
      val readRs2  = Flipped(new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits))
      val readRs3  = Flipped(new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits))
      val readRd   = Flipped(new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits))
    }
    // TODO: per-FU RS
    val rsAdmit = Decoupled(new ReservationStationEntry)
    /** writeback interface from EX */
    val writeback = Flipped(regWritebackT)
    val perf = Output(new IssuePerfIO)
  })

  val cyclesDecoded = Seq.fill(numWarps)(new PerfCounter)
  val stallsWAW = Seq.fill(numWarps)(new PerfCounter)
  val stallsWAR = Seq.fill(numWarps)(new PerfCounter)
  io.perf.perWarp.zipWithIndex.foreach { case (p, wid) =>
    p.cyclesDecoded := cyclesDecoded(wid).value
    p.stallsWAW := stallsWAW(wid).value
    p.stallsWAR := stallsWAR(wid).value
  }

  def tryWarp(warpId: Int): DecoupledIO[ReservationStationEntry] = {
    val ibufPort = io.ibuf(warpId)
    val uopValid = ibufPort.valid
    val hasRd    = ibufPort.bits.uop.inst(HasRd) .asBool
    val hasRs1   = ibufPort.bits.uop.inst(HasRs1).asBool
    val hasRs2   = ibufPort.bits.uop.inst(HasRs2).asBool
    val hasRs3   = ibufPort.bits.uop.inst(HasRs3).asBool

    // TODO: multi-port scoreboard ports
    io.scb.readRs1.enable := uopValid && hasRs1
    io.scb.readRs1.pReg   := ibufPort.bits.uop.inst.rs1
    io.scb.readRs2.enable := uopValid && hasRs2
    io.scb.readRs2.pReg   := ibufPort.bits.uop.inst.rs2
    io.scb.readRs3.enable := uopValid && hasRs3
    io.scb.readRs3.pReg   := ibufPort.bits.uop.inst.rs3
    io.scb.readRd.enable  := uopValid && hasRd
    io.scb.readRd.pReg    := ibufPort.bits.uop.inst.rd

    // RS admission logic
    val rsAdmit = Wire(Decoupled(new ReservationStationEntry))

    // assumes combinational-read scoreboard
    val hasWAW = hasRd && (io.scb.readRd.pendingWrites =/= 0.U)
    val hasWAR = hasRd && (io.scb.readRd.pendingReads =/= 0.U)

    cyclesDecoded(warpId).cond(uopValid)
    stallsWAW(warpId).cond(uopValid && hasWAW)
    stallsWAR(warpId).cond(uopValid && hasWAR)

    // @perf: for now simply gates WAR into RS; relax this into stalling at
    // writeback
    rsAdmit.valid := uopValid && !hasWAW && !hasWAR
    if (muonParams.debug) {
      when (uopValid && hasWAR) {
        printf(cf"hazard: IBUF head (wid=${ibufPort.bits.uop.wid}, PC=${ibufPort.bits.uop.pc}%x) is gated RS admission due to WAR\n")
      }.elsewhen (uopValid && hasWAW) {
        printf(cf"hazard: IBUF head (wid=${ibufPort.bits.uop.wid}, PC=${ibufPort.bits.uop.pc}%x) is gated RS admission due to WAW\n")
      }
    }

    val rsEntry = rsAdmit.bits
    rsEntry.ibufEntry := ibufPort.bits
    // don't filter out x0 for valid; collector will handle that
    rsEntry.valid(0) := !hasRs1
    rsEntry.valid(1) := !hasRs2
    rsEntry.valid(2) := !hasRs3
    rsEntry.busy(0) := hasRs1 && (io.scb.readRs1.pendingWrites =/= 0.U)
    rsEntry.busy(1) := hasRs2 && (io.scb.readRs2.pendingWrites =/= 0.U)
    rsEntry.busy(2) := hasRs3 && (io.scb.readRs3.pendingWrites =/= 0.U)

    rsAdmit
  }

  // TODO only handling warp 0 because of single-port scoreboard
  val rsAdmitPerWarp = io.ibuf.zipWithIndex.map{ case (ibPort, wid) =>
    wid match {
      case 0 => tryWarp(wid)
      case _ => {
        when (ibPort.valid) {
          assert(false.B,
            cf"hazard: TODO: ibuf for warpId>0 not handled yet " +
            cf"(pc=0x${ibPort.bits.uop.pc}%x, warpId=${ibPort.bits.uop.wid})")
        }
        val rsAdmit = Wire(Decoupled(new ReservationStationEntry))
        rsAdmit.valid := false.B
        rsAdmit.bits := DontCare
        rsAdmit
      }
    }
  }
  // dequeue from IBUF
  (io.ibuf zip rsAdmitPerWarp).foreach { case (ib, rs) =>
    ib.ready := rs.fire // since ib.valid != rs.valid
  }

  // arbitrates multiple RS enqueue signals into the single write port for each
  // RS table
  // TODO: per-FU RS
  val rsAdmitArbiter = Module(
    new RRArbiter(chiselTypeOf(rsAdmitPerWarp.head.bits), rsAdmitPerWarp.length)
  )
  (rsAdmitArbiter.io.in zip rsAdmitPerWarp).foreach { case (a, w) => a <> w }
  val rsAdmitChosen = rsAdmitArbiter.io.out

  // update scoreboard upon RS admission
  // This must be done at the same cycle as scoreboard read, so that the updated
  // values are immediately visible at the next cycle and never lost.
  //
  // Note that io.rsAdmit.ready needs to be checked so that we trigger scoreboard
  // update only when there's guaranteed space in the RS.
  when (rsAdmitChosen.valid && io.rsAdmit.ready) {
    assert(rsAdmitArbiter.io.chosen === 0.U,
           "TODO: arbiter chose something else than warp 0") // FIXME

    val chosenUop = rsAdmitChosen.bits.ibufEntry.uop
    val hasRd    = chosenUop.inst(HasRd).asBool
    val hasRss   = Seq(chosenUop.inst(HasRs1).asBool,
                       chosenUop.inst(HasRs2).asBool,
                       chosenUop.inst(HasRs3).asBool)
    val rss      = Seq(chosenUop.inst.rs1, chosenUop.inst.rs2, chosenUop.inst.rs3)

    io.scb.updateRS.enable := hasRd || hasRss.reduce(_ || _)
    io.scb.updateRS.write.pReg := chosenUop.inst.rd
    // RS admission always increments
    io.scb.updateRS.write.incr := hasRd
    io.scb.updateRS.write.decr := false.B
    (io.scb.updateRS.reads zip (hasRss zip rss)).foreach { case (read, (hasRs, rs)) =>
      read.pReg := rs
      read.incr := hasRs
      read.decr := false.B
    }
  }.otherwise {
    // due diligence to value-gate
    io.scb.updateRS.enable := false.B
    io.scb.updateRS.write.pReg := 0.U
    io.scb.updateRS.write.incr := false.B
    io.scb.updateRS.write.decr := false.B
    io.scb.updateRS.reads.foreach { read =>
      read.pReg := 0.U
      read.incr := false.B
      read.decr := false.B
    }
  }

  // gate RS entry if scoreboard update failed
  // note io.scb.updateRS.success is combinational.
  io.rsAdmit.valid := rsAdmitChosen.valid && io.scb.updateRS.success
  rsAdmitChosen.ready := io.rsAdmit.ready && io.scb.updateRS.success
  io.rsAdmit.bits  := rsAdmitChosen.bits
  // for good measure, assert scoreboard update has always succeeded upon RS
  // admission fire
  assert(!io.rsAdmit.fire || (!io.scb.updateRS.enable || io.scb.updateRS.success),
         "uop entered RS without succeeding scoreboard update")
  if (muonParams.debug) {
    when (io.rsAdmit.valid && io.rsAdmit.ready && !io.scb.updateRS.success) {
      printf(cf"hazard: IBUF head (PC=${io.rsAdmit.bits.ibufEntry.uop.pc}%x) passed hazard check, but " +
             cf"RS admission blocked due to scoreboard overflow\n")
    }
  }

  // update scoreboard upon writeback
  io.scb.updateWB.enable := io.writeback.valid
  io.scb.updateWB.write.pReg := Mux(io.writeback.valid, io.writeback.bits.rd, 0.U)
  // Writeback always increments
  io.scb.updateWB.write.incr := false.B
  io.scb.updateWB.write.decr := io.writeback.valid
  io.scb.updateWB.reads.foreach { read =>
    read.pReg := 0.U
    read.incr := false.B
    read.decr := false.B
  }
}

trait HasIssuePerfCounters extends HasCoreParameters {
  implicit val p: Parameters
  val perWarp = Vec(numWarps, new Bundle {
    val cyclesDecoded = Perf.T
    val stallsWAW = Perf.T
    val stallsWAR = Perf.T
  })
}

class IssuePerfIO(implicit p: Parameters) extends CoreBundle()(p)
with HasIssuePerfCounters
