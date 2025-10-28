package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ReservationStationEntry(implicit p: Parameters) extends CoreBundle()(p) {
  /** Uop being admitted to the reservation station. */
  val uop = uopT
  /** Indicates whether each operand reg (rs1/2/3) has been collected.
   *  If the uop does not use the operand field, sets to 1.
   *  TODO: separate "used" field?
   */
  val valid = Vec(Isa.maxNumRegs, Bool())
  /** Indicates whether each operand reg (rs1/2/3) is being written to by an
   *  in-flight uop in the backend. `busy == 1` means the operand can be
   *  potentially forwarded from EX. */
  val busy = Vec(Isa.maxNumRegs, Bool())
  // TODO: collector entry pointer
}

class ReservationStation(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    /** uop enqueued (admitted) to reservation station */
    val enq = Flipped(Decoupled(new ReservationStationEntry))
    /** uop issued (dispatched) to the downstream EX pipe */
    val issue = Decoupled(uopT)
  })

  val numEntries = muonParams.numIssueQueueEntries
  val rowValidTable = Mem(numEntries, Bool())
  // TODO: optimize; storing all of Decode fields in RS gets expensive
  val uopTable      = Mem(numEntries, uopT)
  val validTable    = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  val busyTable     = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))

  // enqueue
  val rowEmptyVec = VecInit((0 until numEntries).map(!rowValidTable(_)))
  val hasEmptyRow = rowEmptyVec.reduce(_ || _)
  io.enq.ready := hasEmptyRow

  val emptyRow = PriorityEncoder(rowEmptyVec)
  when (io.enq.fire) {
    assert(!rowValidTable(emptyRow))
    rowValidTable(emptyRow) := true.B
    uopTable(emptyRow)   := io.enq.bits.uop
    validTable(emptyRow) := io.enq.bits.valid
    busyTable(emptyRow)  := io.enq.bits.busy
  }

  // check issue eligiblity
  val eligibles = (0 until numEntries).map { i =>
    val valids = validTable(i)
    val busys  = busyTable(i)
    val allValid = valids.reduce(_ && _)

    val out = Wire(Decoupled(uopT))
    out.valid := allValid
    out.bits := uopTable(i)
    out
  }
  // TODO: warp-aware issue scheduling
  val issueScheduler = Module(
    new RRArbiter(chiselTypeOf(eligibles.head.bits), eligibles.length)
  )
  (issueScheduler.io.in zip eligibles).foreach { case (s, e) => s <> e }
  io.issue <> issueScheduler.io.out

  // reset
  when (reset.asBool) {
    (0 until numEntries).foreach { i => rowValidTable(i) := false.B }
    // @synthesis: do other entries need to be reset?
  }
}
