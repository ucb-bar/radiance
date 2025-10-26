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

  def perWarp(ibufPort: DecoupledIO[UOp]): DecoupledIO[ReservationStationEntry] = {
    val uopValid = ibufPort.valid
    val hasRd    = ibufPort.bits.inst(HasRd) .asBool
    val hasRs1   = ibufPort.bits.inst(HasRs1).asBool
    val hasRs2   = ibufPort.bits.inst(HasRs2).asBool
    val hasRs3   = ibufPort.bits.inst(HasRs3).asBool

    io.scb.readRs1.enable := ibufPort.valid && hasRs1
    io.scb.readRs1.pReg   := ibufPort.bits.inst.rs1
    io.scb.readRs2.enable := ibufPort.valid && hasRs2
    io.scb.readRs2.pReg   := ibufPort.bits.inst.rs2
    io.scb.readRs3.enable := ibufPort.valid && hasRs3
    io.scb.readRs3.pReg   := ibufPort.bits.inst.rs3
    io.scb.readRd.enable  := ibufPort.valid && hasRd
    io.scb.readRd.pReg    := ibufPort.bits.inst.rd

    // TODO gate WAW and WAR

    // RS enqueue logic
    val rsEnq = Wire(Decoupled(new ReservationStationEntry))
    rsEnq.valid := uopValid

    val rsEntry = rsEnq.bits
    rsEntry.uop := ibufPort.bits
    // TODO connect collector
    rsEntry.valid(0) := !hasRs1
    rsEntry.valid(1) := !hasRs2
    rsEntry.valid(2) := !hasRs3
    // assumes combinational-read scoreboard
    rsEntry.busy(0) := (io.scb.readRs1.pendingWrites =/= 0.U)
    rsEntry.busy(1) := (io.scb.readRs2.pendingWrites =/= 0.U)
    rsEntry.busy(2) := (io.scb.readRs3.pendingWrites =/= 0.U)

    rsEnq
  }

  // TODO only looking at warp 0 at the moment
  val rsEnqWarp0 = perWarp(io.ibuf(0))
  io.rsEnq <> rsEnqWarp0

  io.scb.update.enable := true.B
  io.scb.update.pReg := 1.U
  io.scb.update.readInc := true.B
  io.scb.update.readDec := false.B
  io.scb.update.writeInc := false.B
  io.scb.update.writeDec := true.B
}
