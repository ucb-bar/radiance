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
    // per-warp IBUF interface
    val ibuf = Flipped(Vec(muonParams.numWarps, Decoupled(uopT)))
    // scoreboard interface
    val scb = new Bundle {
      val update  = Flipped(scoreboardUpdateIO)
      val readRs1 = Flipped(scoreboardReadIO)
      val readRs2 = Flipped(scoreboardReadIO)
      val readRs3 = Flipped(scoreboardReadIO)
      val readRd  = Flipped(scoreboardReadIO)
    }
    // TODO: per-FU RS
    val rsEnq = Decoupled(new ReservationStationEntry)
  })

  io.ibuf.foreach(_.ready := true.B)

  def checkWarp(ibufPort: DecoupledIO[UOp]): DecoupledIO[ReservationStationEntry] = {
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

    // RS enqueue logic
    val rsEnq = Wire(Decoupled(new ReservationStationEntry))

    // assumes combinational-read scoreboard
    val hasWAW = hasRd && (io.scb.readRd.pendingWrites =/= 0.U)
    val hasWAR = hasRd && (io.scb.readRd.pendingReads =/= 0.U)

    // TODO: for now simply gates WAR into RS; relax this into stalling at
    // writeback
    rsEnq.valid := uopValid && !hasWAW && !hasWAR

    val rsEntry = rsEnq.bits
    rsEntry.uop := ibufPort.bits
    // TODO connect collector
    rsEntry.valid(0) := !hasRs1
    rsEntry.valid(1) := !hasRs2
    rsEntry.valid(2) := !hasRs3
    rsEntry.busy(0) := (io.scb.readRs1.pendingWrites =/= 0.U)
    rsEntry.busy(1) := (io.scb.readRs2.pendingWrites =/= 0.U)
    rsEntry.busy(2) := (io.scb.readRs3.pendingWrites =/= 0.U)

    rsEnq
  }

  // TODO only looking at warp 0 at the moment
  val rsEnqAllWarps = io.ibuf.zipWithIndex.map{ case (ibPort, wid) =>
    wid match {
      case 0 => checkWarp(ibPort)
      case _ => {
        val rsEnq = Wire(Decoupled(new ReservationStationEntry))
        rsEnq.valid := false.B
        rsEnq.bits := DontCare
        rsEnq
      }
    }
  }

  // arbitrates multiple RS enqueue signals into the single write port for each
  // RS table
  // TODO: only outputs to one unified table at the moment
  val rsEnqArbiter = Module(
    new RRArbiter(chiselTypeOf(rsEnqAllWarps.head.bits), rsEnqAllWarps.length)
  )
  (rsEnqArbiter.io.in zip rsEnqAllWarps).foreach { case (a, w) => a <> w }
  io.rsEnq <> rsEnqArbiter.io.out

  // update scoreboard upon RS admission
  when (io.rsEnq.fire) {
    assert(rsEnqArbiter.io.chosen === 0.U,
           "TODO: arbiter chose something else than warp 0") // FIXME

    val chosenUop = io.rsEnq.bits.uop
    val hasRd    = chosenUop.inst(HasRd) .asBool
    val hasRs1   = chosenUop.inst(HasRs1).asBool
    val hasRs2   = chosenUop.inst(HasRs2).asBool
    val hasRs3   = chosenUop.inst(HasRs3).asBool

    io.scb.update.enable := hasRd
    io.scb.update.pReg   := chosenUop.inst.rd
    io.scb.update.readInc  := false.B // TODO: check rs1/2/3
    io.scb.update.readDec  := false.B // TODO: check rs1/2/3
    io.scb.update.writeInc := true.B
    io.scb.update.writeDec := false.B
  }.otherwise {
    io.scb.update.enable := false.B
    io.scb.update.pReg   := 0.U
    io.scb.update.readInc  := false.B
    io.scb.update.readDec  := false.B
    io.scb.update.writeInc := false.B
    io.scb.update.writeDec := false.B
  }
}
