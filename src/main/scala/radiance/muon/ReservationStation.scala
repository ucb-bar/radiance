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
    /** uop admitted to reservation station */
    val admit = Flipped(Decoupled(new ReservationStationEntry))
    /** uop issued (dispatched) to the downstream EX pipe */
    val issue = Decoupled(uopT)
    /** uop written-back from the downstream EX pipe */
    val writeback = Flipped(regWritebackT)
    /** writeback pass-through to the hazard module */
    val writebackHazard = regWritebackT
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
  io.admit.ready := hasEmptyRow
  dontTouch(rowEmptyVec)

  val emptyRow = PriorityEncoder(rowEmptyVec)
  when (io.admit.fire) {
    assert(!rowValidTable(emptyRow))
    rowValidTable(emptyRow) := true.B
    uopTable(emptyRow)   := io.admit.bits.uop
    validTable(emptyRow) := io.admit.bits.valid
    busyTable(emptyRow)  := io.admit.bits.busy

    if (muonParams.debug) {
      printf(cf"RS: admitted PC=0x${io.admit.bits.uop.pc}%x at row ${emptyRow}\n")
      printTable
    }
  }

  // check issue eligiblity
  val eligibles = VecInit((0 until numEntries).map { i =>
    val rowValid = rowValidTable(i)
    val valids = validTable(i)
    val busys  = busyTable(i)
    val allCollected = valids.reduce(_ && _)
    val noneBusy = !busys.reduce(_ || _)

    assert(!rowValid || !allCollected || noneBusy, "operand valid but still marked busy?")

    val out = Wire(Decoupled(uopT))
    out.valid := rowValid && allCollected
    out.bits := uopTable(i)
    out
  })
  dontTouch(eligibles)

  // TODO: warp-aware issue scheduling
  val issueScheduler = Module(
    new RRArbiter(chiselTypeOf(eligibles.head.bits), eligibles.length)
  )
  (issueScheduler.io.in zip eligibles).foreach { case (s, e) => s <> e }
  io.issue <> issueScheduler.io.out

  // writeback
  // CAM broadcast to wake-up entries
  (0 until numEntries).foreach { i =>
    val uop = uopTable(i)
    val hasRss = Seq(uop.inst(HasRs1).asBool,
                     uop.inst(HasRs2).asBool,
                     uop.inst(HasRs3).asBool)
    val rss = Seq(uop.inst.rs1, uop.inst.rs2, uop.inst.rs3)

    val rowValid = rowValidTable(i)
    val busys = busyTable(i)
    val newBusys = WireDefault(busys)
    val rdWriteback = io.writeback.bits.rd
    val updated = WireDefault(false.B)
    (hasRss zip rss).zipWithIndex.foreach { case ((hasRs, rs), rsi) =>
      when (io.writeback.fire && rowValid && hasRs && (rs === rdWriteback)) {
        assert(newBusys(rsi), "RS: busy was already low before writeback")
        newBusys(rsi) := false.B
        updated := true.B
      }
    }

    when (updated) {
      busyTable(i) := newBusys
      if (muonParams.debug) {
        printf(cf"RS: writeback to PC=0x${uop.pc}%x at row ${emptyRow}\n")
      }
    }
  }

  // pass-through to scoreboard to also update pendingWrites
  io.writebackHazard <> io.writeback

  // reset
  when (reset.asBool) {
    (0 until numEntries).foreach { i => rowValidTable(i) := false.B }
    // @synthesis: do other entries need to be reset?
  }

  // debug print
  def printTable = {
    printf("=" * 40 + " ReservationStation " + "=" * 40 + "\n")
    for (i <- 0 until numEntries) {
      val rowValid = rowValidTable(i)
      when (rowValid) {
        val valids = validTable(i)
        val busys  = busyTable(i)
        val uop    = uopTable(i)
        printf(cf"${i} | warp:${uop.wid} | pc:0x${uop.pc}%x | " +
               cf"opvalid: (rs1:${valids(0)} rs2:${valids(1)} rs3:${valids(2)}) | " +
               cf"busy: (rs1:${busys(0)} rs2:${busys(1)} rs3:${busys(2)})\n")
      }
    }
    printf("=" * 100 + "\n")
  }
}

class FakeWriteback(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val issue = Flipped(Decoupled(uopT))
    val writeback = regWritebackT
  })

  val latency = 4
  val depth = 2
  val queue = Module(new Queue(gen = uopT, entries = depth))

  val wbValid = io.issue.valid && io.issue.bits.inst(HasRd).asBool
  io.issue.ready := queue.io.enq.ready
  queue.io.enq.valid := ShiftRegister(wbValid,       latency, queue.io.enq.ready)
  queue.io.enq.bits  := ShiftRegister(io.issue.bits, latency, queue.io.enq.ready)
  io.writeback.valid := queue.io.deq.valid
  io.writeback.bits.rd := queue.io.deq.bits.inst.rd
  (0 until io.writeback.bits.data.length).foreach { i =>
    io.writeback.bits.data(i) := 0.U // bogus
  }
  io.writeback.bits.tmask := queue.io.deq.bits.tmask
  // writeback never stalls
  queue.io.deq.ready := true.B
}
