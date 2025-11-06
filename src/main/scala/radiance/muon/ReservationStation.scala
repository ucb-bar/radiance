package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ReservationStationEntry(implicit p: Parameters) extends CoreBundle()(p) {
  /** uop being admitted to the reservation station. */
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
    /** writeback from the downstream EX pipe */
    val writeback = Flipped(regWritebackT)
    /** writeback pass-through to the hazard module */
    val writebackHazard = regWritebackT
    /** collector request/response; RS keeps track of operand validity */
    val collector = new Bundle {
      val readReq  = Flipped(CollectorRequest(Isa.maxNumRegs, isWrite = false))
      val readResp = Flipped(CollectorResponse(Isa.maxNumRegs, isWrite = false))
    }
  })

  val numEntries = muonParams.numIssueQueueEntries
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)
  val validTable = Mem(numEntries, Bool())
  // TODO: optimize; storing all of Decode fields in RS gets expensive
  val uopTable       = Mem(numEntries, uopT)
  val opValidTable   = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  val busyTable      = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // marks whether the operand is currently being collected
  // different than opValidTable, because not all of invalid operands will be
  // served by the collector at once
  val collValidTable = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // marks where the operand lives in the collector banks
  val collPtrTable   = Mem(numEntries, Vec(Isa.maxNumRegs, UInt(collEntryWidth.W)))

  // enqueue
  val rowEmptyVec = VecInit((0 until numEntries).map(!validTable(_)))
  val hasEmptyRow = rowEmptyVec.reduce(_ || _)
  io.admit.ready := hasEmptyRow

  val emptyRow = PriorityEncoder(rowEmptyVec)
  when (io.admit.fire) {
    assert(!validTable(emptyRow))
    validTable(emptyRow) := true.B
    uopTable(emptyRow)   := io.admit.bits.uop
    opValidTable(emptyRow) := io.admit.bits.valid
    busyTable(emptyRow)  := io.admit.bits.busy
    collValidTable(emptyRow) := VecInit(Seq(0.U, 0.U, 0.U))
    collPtrTable(emptyRow) := VecInit(Seq(0.U, 0.U, 0.U))

    if (muonParams.debug) {
      printf(cf"RS: admitted PC=0x${io.admit.bits.uop.pc}%x at row ${emptyRow}\n")
      printTable
    }
  }

  // trigger collector on an entry with incomplete opValids
  val needCollects = (0 until numEntries).map { i =>
    val valid = validTable(i)
    val opValids = opValidTable(i)
    val busys = busyTable(i)
    val needCollectOps = VecInit((opValids zip busys)
      .map { case (v, busy) => !v && !busy })
    val needCollect = valid && needCollectOps.reduce(_ || _)
    (needCollect, needCollectOps)
  }
  // select a single entry for collection
  // TODO: @perf: currently a simple priority encoder; might introduce fairness
  // problem
  val collSelBitvec = VecInit(needCollects.map(_._1))
  val collSelRow = PriorityEncoder(collSelBitvec)
  val collSelOpValid = VecInit(needCollects.map(_._2))(collSelRow)
  val collSelUop = uopTable(collSelRow)
  val collSelRegs = Seq(collSelUop.inst.rs1, collSelUop.inst.rs2, collSelUop.inst.rs3)
  io.collector.readReq.valid := io.collector.readReq.bits.anyEnabled()
  assert(collSelOpValid.length == io.collector.readReq.bits.regs.length)
  assert(collSelRegs.length == io.collector.readReq.bits.regs.length)
  (collSelOpValid lazyZip collSelRegs lazyZip io.collector.readReq.bits.regs)
    .zipWithIndex.foreach { case ((cv, pReg, collPort), rsi) =>
      assert(collPort.data.isEmpty)
      collPort.enable := cv
      collPort.pReg := Mux(cv, pReg, 0.U)
      when (io.collector.readReq.fire && cv) {
        collValidTable(collSelRow)(rsi) := true.B
      }
    }

  // mark operand valid upon collector response
  // collector wakeup should never block
  io.collector.readResp.ports.foreach(_.ready := true.B)
  (0 until numEntries).foreach { i =>
    val valid = validTable(i)
    val collValids = collValidTable(i)
    val collPtrs = collPtrTable(i)
    (collValids lazyZip collPtrs lazyZip io.collector.readResp.ports)
      .zipWithIndex.foreach { case ((cv, cptr, port), rsi) =>
        when (valid && cv && port.fire && (port.bits.collEntry === cptr)) {
          collValidTable(i)(rsi) := false.B
          assert(opValidTable(i)(rsi) === true.B)
          opValidTable(i)(rsi) := false.B
        }
      }
  }

  // check issue eligiblity after collection finished & RAW settled
  val eligibles = VecInit((0 until numEntries).map { i =>
    val valid = validTable(i)
    val opValids = opValidTable(i)
    val busys = busyTable(i)
    val allCollected = opValids.reduce(_ && _)
    val noneBusy = !busys.reduce(_ || _)

    assert(!valid || !allCollected || noneBusy, "operand collected but still marked busy?")

    val e = Wire(Decoupled(uopT))
    e.valid := valid && allCollected
    e.bits := uopTable(i)
    // deregister upon issue
    when (e.fire) {
      validTable(i) := false.B
    }

    e
  })
  dontTouch(eligibles)

  // schedule issue out of eligibles
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

    val valid = validTable(i)
    val busys = busyTable(i)
    val newBusys = WireDefault(busys)
    val rdWriteback = io.writeback.bits.rd
    val updated = WireDefault(false.B)
    (hasRss zip rss).zipWithIndex.foreach { case ((hasRs, rs), rsi) =>
      when (io.writeback.fire && valid && hasRs && (rs =/= 0.U) && (rs === rdWriteback)) {
        assert(newBusys(rsi),
          cf"RS: busy was already low when writeback arrived (pc:${uop.pc}%x, rd:${rdWriteback})")
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
    (0 until numEntries).foreach { i => validTable(i) := false.B }
    // @synthesis: do other entries need to be reset?
  }

  // debug print
  def printTable = {
    printf("=" * 40 + " ReservationStation " + "=" * 40 + "\n")
    for (i <- 0 until numEntries) {
      val valid = validTable(i)
      when (valid) {
        val opValids = opValidTable(i)
        val busys    = busyTable(i)
        val uop      = uopTable(i)
        printf(cf"${i} | warp:${uop.wid} | pc:0x${uop.pc}%x | " +
               cf"opvalid: (rs1:${opValids(0)} rs2:${opValids(1)} rs3:${opValids(2)}) | " +
               cf"busy: (rs1:${busys(0)} rs2:${busys(1)} rs3:${busys(2)}) | " +
               cf"regs: (rs1:${uop.inst.rs1} rs2:${uop.inst.rs2} rs3:${uop.inst.rs3})\n")
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
