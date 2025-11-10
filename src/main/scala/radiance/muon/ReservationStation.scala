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
    /** uop issued to the downstream EX pipe */
    val issue = Decoupled(uopT)
    /** writeback from the downstream EX pipe */
    val writeback = Flipped(regWritebackT)
    /** writeback pass-through to the hazard module */
    val writebackHazard = regWritebackT
    /** collector request/response; RS keeps track of operand validity */
    val collector = new Bundle {
      val readReq  = Flipped(CollectorRequest(Isa.maxNumRegs, isWrite = false))
      val readResp = Flipped(CollectorResponse(Isa.maxNumRegs, isWrite = false))
      val readData = Flipped(new CollectorOperandRead)
    }
  })

  val numEntries = muonParams.numIssueQueueEntries
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)

  // whether this table row is valid
  val validTable     = Mem(numEntries, Bool())
  // TODO: optimize; storing all of Decode fields in RS gets expensive
  val uopTable       = Mem(numEntries, uopT)
  // whether the uop uses rs1/2/3
  // not actually a state; combinationally computed from uops
  val hasOpTable     = Wire(Vec(numEntries, Vec(Isa.maxNumRegs, Bool())))
  // whether the used operands has been collected
  val opReadyTable   = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // whether the operands are being written-to by in-flight uops in EX
  val busyTable      = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // whether the operands are currently being collected
  // a partial set of hasOpTable; not all of the operands can be collected at
  // once
  val collFiredTable = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // where the operand lives in the collector banks
  val collPtrTable   = Mem(numEntries, Vec(Isa.maxNumRegs, UInt(collEntryWidth.W)))

  (0 until numEntries).map { i =>
    val uop = uopTable(i)
    hasOpTable(i) := VecInit(Seq(uop.inst(HasRs1).asBool,
                                 uop.inst(HasRs2).asBool,
                                 uop.inst(HasRs3).asBool))
  }

  // ---------
  // admission
  // ---------

  val rowEmptyVec = VecInit((0 until numEntries).map(!validTable(_)))
  val hasEmptyRow = rowEmptyVec.reduce(_ || _)
  io.admit.ready := hasEmptyRow

  val emptyRow = PriorityEncoder(rowEmptyVec)
  when (io.admit.fire) {
    assert(!validTable(emptyRow))
    validTable(emptyRow) := true.B
    uopTable(emptyRow)   := io.admit.bits.uop
    opReadyTable(emptyRow) := io.admit.bits.valid
    busyTable(emptyRow)  := io.admit.bits.busy
    collFiredTable(emptyRow) := VecInit.fill(Isa.maxNumRegs)(false.B)
    collPtrTable(emptyRow) := VecInit.fill(Isa.maxNumRegs)(0.U)

    if (muonParams.debug) {
      printf(cf"RS: admitted: PC=${io.admit.bits.uop.pc}%x at row ${emptyRow}\n")
      printTable
    }
  }

  // -----------------
  // collector control
  // -----------------

  // trigger collector on an entry with incomplete opReadys
  val needCollects = (0 until numEntries).map { i =>
    val valid = validTable(i)
    val opReadys = opReadyTable(i)
    val busys = busyTable(i)
    val needCollectOps = VecInit((opReadys zip busys)
      .map { case (r, b) => !r && !b })
    val needCollect = valid && needCollectOps.reduce(_ || _)
    (needCollect, needCollectOps)
  }
  // select a single entry for collection
  // TODO: @perf: currently a simple priority encoder; might introduce fairness
  // problem
  val collBitvec = WireDefault(VecInit(needCollects.map(_._1)))
  dontTouch(collBitvec)
  val collRow = PriorityEncoder(collBitvec)
  val collOpNeed = VecInit(needCollects.map(_._2))(collRow)
  val collUop = uopTable(collRow)
  val collRegs = Seq(collUop.inst.rs1, collUop.inst.rs2, collUop.inst.rs3)
  // this is clunky, but Mem does not support partial-field updates
  val newCollFired = WireDefault(collFiredTable(collRow))
  val newCollPtr = WireDefault(collPtrTable(collRow))
  assert(collOpNeed.length == io.collector.readReq.bits.regs.length)
  assert(collRegs.length == io.collector.readReq.bits.regs.length)
  (collOpNeed lazyZip collRegs lazyZip io.collector.readReq.bits.regs)
    .zipWithIndex.foreach { case ((need, pReg, collPort), rsi) =>
      assert(collPort.data.isEmpty)
      collPort.enable := need && !collFiredTable(collRow)(rsi)
      collPort.pReg := Mux(need, pReg, 0.U)
      newCollFired(rsi) := true.B
      // TODO: currently assumes DuplicatedCollector with only 1 entry
      newCollPtr(rsi) := 0.U
    }
  io.collector.readReq.valid := io.collector.readReq.bits.anyEnabled()
  when (io.collector.readReq.fire) {
    collFiredTable(collRow) := newCollFired
    collPtrTable(collRow) := newCollPtr
  }

  // mark operand valid upon collector response
  // collector wakeup should never block
  io.collector.readResp.ports.foreach(_.ready := true.B)
  (0 until numEntries).foreach { i =>
    val valid = validTable(i)
    val opReadys = opReadyTable(i)
    val newOpReadys = WireDefault(opReadys)
    val collFired = collFiredTable(i)
    val collPtrs = collPtrTable(i)
    val updated = WireDefault(false.B)
    (opReadys lazyZip collFired lazyZip collPtrs lazyZip io.collector.readResp.ports)
      .zipWithIndex.foreach { case ((rdy, cf, cptr, cResp), rsi) =>
        when (cResp.fire && valid && !rdy && cf && (cResp.bits.collEntry === cptr)) {
          newOpReadys(rsi) := true.B
          updated := true.B
          printf(cf"RS: collector response handled at row ${i}, rsi ${rsi}\n")
        }
      }
    when (updated) {
      opReadyTable(i) := newOpReadys
    }
  }
  when (io.collector.readResp.ports.map(_.fire).reduce(_ || _)) {
    printf(cf"RS: collector response handled; current table:\n")
    printTable
  }

  // -----
  // issue
  // -----

  def issueArbBundleT = new Bundle {
    val uop = uopT
    val entryId = UInt(log2Ceil(numEntries).W)
  }

  // check issue eligiblity after collection finished & RAW settled
  val eligibles = VecInit((0 until numEntries).map { i =>
    val valid = validTable(i)
    val opReadys = opReadyTable(i)
    val hasOps = hasOpTable(i)
    val busys = busyTable(i)
    val allCollected = opReadys.reduce(_ && _)
    val noneBusy = !busys.reduce(_ || _)

    assert(!valid || !allCollected || noneBusy, "operand collected but still marked busy?")

    val bundle = Wire(Decoupled(issueArbBundleT))
    bundle.valid := valid && allCollected
    bundle.bits.uop := uopTable(i)
    bundle.bits.entryId := i.U

    // deregister upon issue
    when (bundle.fire) {
      validTable(i) := false.B
    }

    bundle
  })
  dontTouch(eligibles)

  // schedule issue by arbitrating eligibles
  // TODO: warp-aware issue scheduling
  val issueScheduler = Module(
    new RRArbiter(chiselTypeOf(eligibles.head.bits), eligibles.length)
  )
  (issueScheduler.io.in zip eligibles).foreach { case (s, e) => s <> e }
  io.issue.valid := issueScheduler.io.out.valid
  io.issue.bits := issueScheduler.io.out.bits.uop
  issueScheduler.io.out.ready := io.issue.ready

  // drive the operands port upon issue fire
  val issuedId = WireDefault(issueScheduler.io.out.bits.entryId)
  io.collector.readData.regs.zipWithIndex.foreach { case (port, rsi) =>
    port.enable := hasOpTable(issuedId)(rsi)
    port.collEntry := collPtrTable(issuedId)(rsi)
    // port.data input is not used
  }
  dontTouch(issuedId)

  if (muonParams.debug) {
    when (io.issue.fire) {
      printf(cf"RS: issued: PC=${io.issue.bits.pc}%x at row ${issueScheduler.io.out.bits.entryId}:\n")
      printTable
    }
  }


  // ---------
  // writeback
  // ---------

  // CAM broadcast to wake-up entries
  (0 until numEntries).foreach { i =>
    val uop = uopTable(i)
    val rss = Seq(uop.inst.rs1, uop.inst.rs2, uop.inst.rs3)
    val hasOps = hasOpTable(i)
    val valid = validTable(i)
    val busys = busyTable(i)
    val newBusys = WireDefault(busys)
    val rdWriteback = io.writeback.bits.rd
    val updated = WireDefault(false.B)
    (hasOps zip rss).zipWithIndex.foreach { case ((hasRs, rs), rsi) =>
      when (io.writeback.fire && valid && hasRs && (rs =/= 0.U) && (rs === rdWriteback)) {
        assert(busys(rsi) === true.B,
          cf"RS: busy was already low when writeback arrived " +
          cf"(#${i}, pc:${uop.pc}%x, rdWB:${rdWriteback}, rsi:${rsi})")
        newBusys(rsi) := false.B
        updated := true.B
      }
    }

    when (updated) {
      busyTable(i) := newBusys
      if (muonParams.debug) {
        printf(cf"RS: writeback: PC=${uop.pc}%x at row ${emptyRow}\n")
        printTable
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
        val uop      = uopTable(i)
        printf(cf"${i} | warp:${uop.wid} | pc:0x${uop.pc}%x | " +
               cf"regs: [rs1:${uop.inst.rs1} rs2:${uop.inst.rs2} rs3:${uop.inst.rs3}] | " +
               cf"opReady:${opReadyTable(i)(0)}${opReadyTable(i)(1)}${opReadyTable(i)(2)} | " +
               cf"busy:${busyTable(i)(0)}${busyTable(i)(1)}${busyTable(i)(2)} | " +
               cf"collFired:${collFiredTable(i)(0)}${collFiredTable(i)(1)}${collFiredTable(i)(2)}" +
               cf"\n")
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
