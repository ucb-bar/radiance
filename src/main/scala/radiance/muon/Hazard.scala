package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

/** Hazard module checks for WAW/WAR hazards in the per-warp instructions at
 *  ibuffer heads, and gates their admission to the reservation station. IOW,
 *  the module resolves WAW/WAR hazards by stalling.  RAW hazards are handled
 *  inside the reservation station.
 */
class Hazard(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    /** per-warp IBUF interface */
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(uopT)))
    /** scoreboard interface */
    val scb = Flipped(new ScoreboardIO)
    // TODO: per-FU RS
    val rsAdmit = Decoupled(new ReservationStationEntry)
    /** writeback interface from EX */
    val writeback = Flipped(regWritebackT)
  })

  // TODO: bogus
  io.ibuf.foreach(_.ready := true.B)

  def tryWarp(ibufPort: DecoupledIO[UOp]): DecoupledIO[ReservationStationEntry] = {
    val uopValid = ibufPort.valid
    val hasRd    = ibufPort.bits.inst(HasRd) .asBool
    val hasRs1   = ibufPort.bits.inst(HasRs1).asBool
    val hasRs2   = ibufPort.bits.inst(HasRs2).asBool
    val hasRs3   = ibufPort.bits.inst(HasRs3).asBool

    // TODO: multi-port scoreboard ports
    io.scb.readRs1.enable := uopValid && hasRs1
    io.scb.readRs1.pReg   := ibufPort.bits.inst.rs1
    io.scb.readRs2.enable := uopValid && hasRs2
    io.scb.readRs2.pReg   := ibufPort.bits.inst.rs2
    io.scb.readRs3.enable := uopValid && hasRs3
    io.scb.readRs3.pReg   := ibufPort.bits.inst.rs3
    io.scb.readRd.enable  := uopValid && hasRd
    io.scb.readRd.pReg    := ibufPort.bits.inst.rd

    // RS admission logic
    val rsAdmit = Wire(Decoupled(new ReservationStationEntry))

    // assumes combinational-read scoreboard
    val hasWAW = hasRd && (io.scb.readRd.pendingWrites =/= 0.U)
    val hasWAR = hasRd && (io.scb.readRd.pendingReads =/= 0.U)

    // TODO: for now simply gates WAR into RS; relax this into stalling at
    // writeback
    rsAdmit.valid := uopValid && !hasWAW && !hasWAR

    val rsEntry = rsAdmit.bits
    rsEntry.uop := ibufPort.bits
    // TODO connect collector
    rsEntry.valid(0) := !hasRs1
    rsEntry.valid(1) := !hasRs2
    rsEntry.valid(2) := !hasRs3
    // TODO: if writeback happends to these rs's at the same cycle, busy should
    // be set to false!
    rsEntry.busy(0) := (io.scb.readRs1.pendingWrites =/= 0.U)
    rsEntry.busy(1) := (io.scb.readRs2.pendingWrites =/= 0.U)
    rsEntry.busy(2) := (io.scb.readRs3.pendingWrites =/= 0.U)

    rsAdmit
  }

  // TODO only handling warp 0 because of single-port scoreboard
  val rsAdmitAllWarps = io.ibuf.zipWithIndex.map{ case (ibPort, wid) =>
    wid match {
      case 0 => tryWarp(ibPort)
      case _ => {
        val rsAdmit = Wire(Decoupled(new ReservationStationEntry))
        rsAdmit.valid := false.B
        rsAdmit.bits := DontCare
        rsAdmit
      }
    }
  }

  // arbitrates multiple RS enqueue signals into the single write port for each
  // RS table
  // TODO: per-FU RS
  val rsAdmitArbiter = Module(
    new RRArbiter(chiselTypeOf(rsAdmitAllWarps.head.bits), rsAdmitAllWarps.length)
  )
  (rsAdmitArbiter.io.in zip rsAdmitAllWarps).foreach { case (a, w) => a <> w }
  val rsAdmitChosen = rsAdmitArbiter.io.out

  // update scoreboard upon RS admission
  // this must be done at the same cycle as scoreboard read, so that updated
  // values are immediately visible at the next cycle and never lost.
  //
  // Note that io.rsAdmit.ready needs to be checked so that we trigger scoreboard
  // update only when there's guaranteed space in the RS.
  when (rsAdmitChosen.valid && io.rsAdmit.ready) {
    assert(rsAdmitArbiter.io.chosen === 0.U,
           "TODO: arbiter chose something else than warp 0") // FIXME

    val chosenUop = rsAdmitChosen.bits.uop
    val hasRd    = chosenUop.inst(HasRd) .asBool
    val hasRss   = Seq(chosenUop.inst(HasRs1).asBool,
                       chosenUop.inst(HasRs2).asBool,
                       chosenUop.inst(HasRs3).asBool)
    val rss      = Seq(chosenUop.inst.rs1, chosenUop.inst.rs2, chosenUop.inst.rs3)

    io.scb.updateRS.enable := hasRd || hasRss.reduce(_ || _)
    io.scb.updateRS.write.pReg := chosenUop.inst.rd
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
  io.rsAdmit.valid := rsAdmitChosen.valid && io.scb.updateRS.success
  rsAdmitChosen.ready := io.rsAdmit.ready && io.scb.updateRS.success
  io.rsAdmit.bits  := rsAdmitChosen.bits
  // for good measure, assert scoreboard update has always succeeded upon RS
  // admission fire
  assert(!io.rsAdmit.fire || (!io.scb.updateRS.enable || io.scb.updateRS.success),
         "uop entered RS without succeeding scoreboard update")

  // update scoreboard upon writeback
  io.scb.updateWB.enable := io.writeback.valid
  io.scb.updateWB.write.pReg := Mux(io.writeback.valid, io.writeback.bits.rd, 0.U)
  io.scb.updateWB.write.incr := false.B
  io.scb.updateWB.write.decr := io.writeback.valid
  io.scb.updateWB.reads.foreach { read =>
    read.pReg := 0.U
    read.incr := false.B
    read.decr := false.B
  }
}
