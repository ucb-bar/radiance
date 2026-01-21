package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ReservationStationEntry(implicit p: Parameters) extends CoreBundle()(p) {
  /** IBUF entry being admitted to the reservation station. */
  val ibufEntry = ibufEntryT
  /** Indicates whether each operand reg (rs1/2/3) has been collected.
   *  If the instruction does not use the operand field, sets to 1. */
  val valid = Vec(Isa.maxNumRegs, Bool())
  /** Indicates whether each operand reg (rs1/2/3) is being written to by an
   *  in-flight instruction in the backend. `busy == 1` means the operand can be
   *  potentially forwarded from EX. */
  val busy = Vec(Isa.maxNumRegs, Bool())
}

class ReservationStation(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    /** uop admitted to reservation station */
    val admit = Flipped(Decoupled(new ReservationStationEntry))
    /** instruction issued to the downstream EX pipe */
    val issue = Decoupled(ibufEntryT)
    /** writeback from the downstream EX pipe */
    val writeback = Flipped(regWritebackT)
    /** scoreboard interface for collector updates */
    val scb = new Bundle {
      val updateColl = Flipped(new ScoreboardUpdate)
      val updateWB = Flipped(new ScoreboardUpdate)
    }
    /** collector request/response; RS keeps track of operand validity */
    val collector = new Bundle {
      val readReq  = Flipped(CollectorRequest(Isa.maxNumRegs, isWrite = false))
      val readResp = Flipped(CollectorResponse(Isa.maxNumRegs, isWrite = false))
      val readData = Flipped(new CollectorOperandRead)
    }
  })

  val numEntries = muonParams.numIssueQueueEntries
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)
  val useCollector = muonParams.useCollector

  // whether this table row is valid
  val validTable     = Mem(numEntries, Bool())
  // TODO: optimize; storing all of Decode fields in RS gets expensive
  val instTable      = Mem(numEntries, ibufEntryT)
  // whether the instruction uses rs1/2/3
  // not actually a state; combinationally computed from instTable
  val hasOpTable     = Wire(Vec(numEntries, Vec(Isa.maxNumRegs, Bool())))
  // rs1/2/3 of the instruction
  // not actually a state; combinationally computed from instTable
  val rsTable        = Wire(Vec(numEntries, Vec(Isa.maxNumRegs, pRegT)))
  // whether the used operands has been collected
  val opReadyTable   = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // whether the operands are being written-to by in-flight uops in EX
  val busyTable      = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // whether the operands are currently being collected
  // a partial set of hasOpTable; not all of the operands can be collected at
  // once
  val collFiredTableMem = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  def collFiredTable(row: UInt): Vec[Bool] = {
    useCollector match {
      case true  => collFiredTableMem(row)
      // if not using collector, mark always fired
      case false => VecInit.fill(Isa.maxNumRegs)(WireDefault(true.B))
    }
  }
  def collFiredTable(row: Int): Vec[Bool] = collFiredTable(row.U)
  // where the operand lives in the collector banks
  val collPtrTable   = Mem(numEntries, Vec(Isa.maxNumRegs, UInt(collEntryWidth.W)))
  val collAllReadyTable = Wire(Vec(numEntries, Bool()))
  // mostly for debugging
  val eligibleTable = WireDefault(VecInit.fill(numEntries)(false.B))
  dontTouch(eligibleTable)

  (0 until numEntries).map { i =>
    val uop = instTable(i).uop
    hasOpTable(i) := VecInit(Seq(uop.inst(HasRs1).asBool,
                                 uop.inst(HasRs2).asBool,
                                 uop.inst(HasRs3).asBool))
    rsTable(i) := VecInit(Seq(uop.inst.rs1,
                              uop.inst.rs2,
                              uop.inst.rs3))
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
    instTable(emptyRow)  := io.admit.bits.ibufEntry
    opReadyTable(emptyRow) := io.admit.bits.valid
    busyTable(emptyRow)  := io.admit.bits.busy
    collFiredTable(emptyRow) := VecInit.fill(Isa.maxNumRegs)(false.B)
    collPtrTable(emptyRow) := VecInit.fill(Isa.maxNumRegs)(0.U)

    if (muonParams.debug) {
      printf(cf"RS: admitted: PC=${io.admit.bits.ibufEntry.uop.pc}%x at row ${emptyRow}\n")
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
    val collFired = collFiredTable(i)
    val needCollectOps = VecInit((opReadys zip (busys zip collFired))
      .map { case (r, (b, cf)) => !r && !b && !cf})
    val needCollect = valid && needCollectOps.reduce(_ || _)
    // is this uop RAW-cleared and only waiting for collection?
    val needCollectAllReady = needCollect && (needCollectOps === VecInit(opReadys.map(!_)))
    (needCollect, needCollectOps, needCollectAllReady)
  }
  // select a single entry for collection
  // TODO: @perf: currently a simple priority encoder; might introduce fairness
  // problem
  val collBitvec = WireDefault(VecInit(needCollects.map(_._1)))
  collAllReadyTable := VecInit(needCollects.map(_._3))
  dontTouch(collBitvec)
  dontTouch(collAllReadyTable)

  // don't allow early-firing collector requests for partial operands of an
  // instruction.  Partial collects may result in a deadlock with insufficient
  // collector entries where the younger instruction requesting a partial
  // collect blocks collection of an older instruction, but has RAW hazard to
  // that older instruction.
  val allowPartialCollect = false
  // If allowPartialCollect == true, prioritize rows that has no RAW-busy ops
  // (no partial-collects), and only needs collection as the last step before
  // issue.  Otherwise, rows with partial ops can take up valuable space in the
  // collector banks.
  //
  // NOTE: Arguably all of this should be in the collector module.  But for
  // that, we need some kind of bookkeeping in the collector for the
  // partial-collect uops, which is what the collector banks are meant for,
  // which are expensive.
  val allReadyExists = collAllReadyTable.reduce(_ || _)
  val firstAllReadyRow = PriorityEncoder(collAllReadyTable)
  val firstNeedRow = PriorityEncoder(collBitvec)
  val collRow = Mux(!allowPartialCollect.B || allReadyExists,
    firstAllReadyRow, firstNeedRow)
  dontTouch(collRow)

  val collOpNeed = VecInit(needCollects.map(_._2))(collRow)
  val collValid = (if (allowPartialCollect) {
    allReadyExists
  } else {
    collOpNeed.reduce(_ || _)
  })
  val collUop = instTable(collRow).uop
  val collPC = WireDefault(collUop.pc)
  dontTouch(collPC)
  val collRegs = Seq(collUop.inst.rs1, collUop.inst.rs2, collUop.inst.rs3)

  assert(collOpNeed.length == io.collector.readReq.bits.regs.length)
  assert(collRegs.length == io.collector.readReq.bits.regs.length)

  io.collector.readReq.valid := collValid
  // this is clunky, but Mem does not support partial-field updates
  val newCollPtr = WireDefault(collPtrTable(collRow))
  (collOpNeed lazyZip collRegs lazyZip io.collector.readReq.bits.regs)
    .zipWithIndex.foreach { case ((need, pReg, collPort), rsi) =>
      assert(collPort.data.isEmpty)
      collPort.enable := collValid && need
      collPort.pReg := Mux(need, pReg, 0.U)
      // TODO: currently assumes DuplicatedCollector with only 1 entry
      newCollPtr(rsi) := 0.U
    }
  io.collector.readReq.bits.rsEntryId := collRow
  when (io.collector.readReq.fire) {
    val newFired = (collFiredTable(collRow) zip io.collector.readReq.bits.regs.map(_.enable))
                .map { case (a,b) => a || b }
    collFiredTable(collRow) := newFired
    collPtrTable(collRow) := newCollPtr

    printf(cf"RS: collector request fired at row:${collRow}, pc:${collUop.pc}%x\n")
  }

  // upon collector response:
  // 1. mark operand ready in the table
  // 2. update scoreboard pending-reads
  //
  // collector wakeup should never block
  io.collector.readResp.ports.foreach(_.ready := true.B)
  io.scb.updateColl.enable := io.collector.readResp.ports.map(_.fire).reduce(_ || _)
  // set later
  io.scb.updateColl.reads.foreach(_ := 0.U.asTypeOf(new ScoreboardRegUpdate))
  io.scb.updateColl.write := 0.U.asTypeOf(new ScoreboardRegUpdate)

  (0 until numEntries).foreach { i =>
    val valid = validTable(i)
    val rss = rsTable(i)
    val opReadys = opReadyTable(i)
    val newOpReadys = WireDefault(opReadys)
    val collFired = collFiredTable(i)
    val collPtrs = collPtrTable(i)
    val updated = WireDefault(false.B)
    (opReadys lazyZip collFired lazyZip
     (collPtrs zip rss) lazyZip
     (io.collector.readResp.ports zip io.scb.updateColl.reads))
      .zipWithIndex.foreach { case ((rdy, cf, (cptr, rs), (cPort, scbPort)), rsi) =>
        assert(useCollector.B || !cPort.fire,
               "RS: unexpected collector response when useCollector == false!")

        when (cPort.fire && valid && !rdy && cf && (cPort.bits.collEntry === cptr)) {
          newOpReadys(rsi) := true.B
          updated := true.B

          scbPort.pReg := rs
          scbPort.incr := false.B
          scbPort.decr := (rs =/= 0.U)

          printf(cf"RS: collector response handled at row:${i}, pc:${instTable(i).uop.pc}%x, rs:${rs}, rsi:${rsi}\n")
        }
      }
    when (updated) {
      opReadyTable(i) := newOpReadys
    }
  }

  // -----
  // issue
  // -----

  def issueArbBundleT = new Bundle {
    val entry = chiselTypeOf(io.issue.bits)
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
    // issue eligibility logic
    val eligible = useCollector match {
      case true  => valid && allCollected
      case false => valid && noneBusy
    }

    // NOTE: cannot bypass same-cycle collector response for allCollected, since
    // the collected data are visible at the readData port 1 cycle after the
    // response.
    assert(!valid || !allCollected || noneBusy, "operand collected but still marked busy?")

    // TODO: Consider same-cycle writeback for busyTable
    // NOTE: When doing this, need to ensure that the collector itself supports
    // writeback-to-read forwarding.

    // TODO: Consider same-cycle collector response

    eligibleTable(i) := eligible

    val candidate = Wire(Decoupled(issueArbBundleT))
    candidate.valid := eligible
    candidate.bits.entry := instTable(i)
    candidate.bits.entryId := i.U

    // deregister upon issue
    when (candidate.fire) {
      validTable(i) := false.B
    }

    candidate
  })
  dontTouch(eligibles)

  // schedule issue by arbitrating eligibles
  // TODO: warp-aware issue scheduling
  val issueScheduler = Module(
    new RRArbiter(chiselTypeOf(eligibles.head.bits), eligibles.length)
  )
  (issueScheduler.io.in zip eligibles).foreach { case (s, e) => s <> e }
  val issueScheduled = issueScheduler.io.out

  // drive collector's operand serve port upon issue, for use in EX
  val issuedId = WireDefault(issueScheduler.io.out.bits.entryId)
  io.collector.readData.req.valid := issueScheduled.valid
  io.collector.readData.req.bits.collEntry := 0.U // fixed for DuplicatedCollector
  io.collector.readData.resp.ready := issueScheduled.fire
  assert(useCollector, "FIXME: !useCollector is broken currently")
  // io.collector.readData.req.bits.zipWithIndex.foreach { case (port, rsi) =>
  //   port.enable := issueScheduled.fire && hasOpTable(issuedId)(rsi)
  //   port.pReg match {
  //     // for no-collector config, collEntry pointer is not used; use pRegs to
  //     // actually drive SRAMs
  //     // FIXME: remove
  //     case Some(pReg) => pReg := rsTable(issuedId)(rsi)
  //     case None => assert(useCollector,
  //          "collector data port has unnecessary pReg field instantiated when useCollector == true")
  //   }
  //   // port.data input is not used
  // }
  dontTouch(issuedId)

  // if not using collector, RS only directly uses the readData port and never
  // sends readReq / gets readResp back, so we need to signal scoreboard
  // explicitly at issue time
  // FIXME: Hacky; handle this altogether in Collector
  if (!useCollector) {
    io.scb.updateColl.enable := issueScheduled.fire
    io.scb.updateColl.reads.foreach(_ := 0.U.asTypeOf(new ScoreboardRegUpdate))
    io.scb.updateColl.reads.zipWithIndex.foreach { case (scbPort, rsi) =>
      when (issueScheduled.fire) {
        val hasRs = hasOpTable(issuedId)(rsi)
        val rs = rsTable(issuedId)(rsi)
        scbPort.pReg := rs
        scbPort.incr := false.B
        scbPort.decr := hasRs && (rs =/= 0.U)
      }
    }
    io.scb.updateColl.write := 0.U.asTypeOf(new ScoreboardRegUpdate)
  }

  // If true, we're using simple SRAMs with op-duplication, which has timing
  // implication due to sequential reads unlike collector buffers with
  // combinational reads. In that case, we need to align the issue timing with
  // the SRAM round-trip.
  val operandOneCycleLate = !useCollector
  if (operandOneCycleLate) {
    val queue = Module(
      new Queue(gen = chiselTypeOf(io.issue.bits), entries = 1, pipe = true)
    )
    queue.io.enq.valid := issueScheduler.io.out.valid
    queue.io.enq.bits := issueScheduler.io.out.bits.entry
    issueScheduler.io.out.ready := queue.io.enq.ready
    io.issue <> queue.io.deq
  } else {
    io.issue.valid := issueScheduler.io.out.valid
    io.issue.bits := issueScheduler.io.out.bits.entry
    issueScheduler.io.out.ready := io.issue.ready
  }

  if (muonParams.debug) {
    when (io.issue.fire) {
      printf(cf"RS: issued: PC=${io.issue.bits.uop.pc}%x at row ${issueScheduler.io.out.bits.entryId}:\n")
      printTable
    }
  }


  // ---------
  // writeback
  // ---------

  // CAM broadcast to wake-up entries
  (0 until numEntries).foreach { i =>
    val uop = instTable(i).uop
    val rss = Seq(uop.inst.rs1, uop.inst.rs2, uop.inst.rs3)
    val hasOps = hasOpTable(i)
    val valid = validTable(i)
    val busys = busyTable(i)
    val newBusys = WireDefault(busys)
    val rdWriteback = io.writeback.bits.rd
    val updated = WireDefault(false.B)
    (hasOps zip rss).zipWithIndex.foreach { case ((hasRs, rs), rsi) =>
      when (io.writeback.fire && valid && hasRs && (rs =/= 0.U) && (rs === rdWriteback)) {
        // NOTE: busys(rsi) may actually be already false, since that rs might
        // be referring to the rd of an older write. Example:
        //     li  x4 <- ..
        //     add .. <- x4
        //     li  x4 <- .. // handling this writeback
        // This becomes a problem only when the two li's write back
        // out-of-order, which is prevented by the WAW gating at the hazard
        // logic.  With that out of the way, the younger writeback does not
        // need to wake up anything.
        //
        // assert(busys(rsi) === true.B,
        //   cf"RS: busy was already low when writeback arrived " +
        //   cf"(#${i}, pc:${uop.pc}%x, rdWB:${rdWriteback}, rsi:${rsi})")
        newBusys(rsi) := false.B
        updated := true.B
      }
    }

    when (updated) {
      busyTable(i) := newBusys
      if (muonParams.debug) {
        printf(cf"RS: writeback: PC=${uop.pc}%x at row ${i}, rd=${io.writeback.bits.rd}:\n")
        printTable
      }
    }
  }

  // update scoreboard upon writeback
  io.scb.updateWB.enable := io.writeback.fire
  io.scb.updateWB.write.pReg := Mux(io.writeback.fire, io.writeback.bits.rd, 0.U)
  io.scb.updateWB.write.incr := false.B
  io.scb.updateWB.write.decr := io.writeback.fire
  io.scb.updateWB.reads.foreach { read =>
    read.pReg := 0.U
    read.incr := false.B
    read.decr := false.B
  }

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
        val uop = instTable(i).uop
        printf(cf"${i} | warp:${uop.wid} | pc:0x${uop.pc}%x | " +
               cf"regs: [rd:${uop.inst.rd} rs1:${uop.inst.rs1} rs2:${uop.inst.rs2} rs3:${uop.inst.rs3}] | " +
               cf"hasOp:${hasOpTable(i)(0)}${hasOpTable(i)(1)}${hasOpTable(i)(2)} | " +
               cf"opReady:${opReadyTable(i)(0)}${opReadyTable(i)(1)}${opReadyTable(i)(2)} | " +
               cf"busy:${busyTable(i)(0)}${busyTable(i)(1)}${busyTable(i)(2)} | " +
               cf"collFired:${collFiredTable(i)(0)}${collFiredTable(i)(1)}${collFiredTable(i)(2)} | " +
               cf"eligible:${eligibleTable(i)}" +
               cf"\n")
      }
    }
    printf("=" * 100 + "\n")
  }
}

class FakeWriteback(implicit p: Parameters) extends CoreModule()(p) {
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
