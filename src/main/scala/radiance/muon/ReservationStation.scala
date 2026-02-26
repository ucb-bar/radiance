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
  val numCollEntriesWidth = log2Up(muonParams.numCollectorEntries)
  val useCollector = muonParams.useCollector

  // whether this table row is valid
  val validTable     = Mem(numEntries, Bool())
  // @perf: optimize; storing all of Decode fields in RS gets expensive
  val instTable      = Mem(numEntries, ibufEntryT)
  // whether the instruction uses rs1/2/3
  // not actually a state; combinationally computed from instTable
  val hasOpTable     = Wire(Vec(numEntries, Vec(Isa.maxNumRegs, Bool())))
  // rs1/2/3 of the instruction
  // not actually a state; combinationally computed from instTable
  val rsTable        = Wire(Vec(numEntries, Vec(Isa.maxNumRegs, pRegT)))
  // whether the valid operands are not busy & have been collected from regfile
  val opReadyTable   = Mem(numEntries, Vec(Isa.maxNumRegs, Bool()))
  // whether the operands are being written-to by in-flight insts in EX
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
  // where the operand lives in the collector banks.  RS uses this to allocate a
  // spot in the collector & feed the correct data to EX upon issue.
  val collPtrTable   = Mem(numEntries, UInt(numCollEntriesWidth.W))
  // whether this entry has received the final `rseadResp` from collector this
  // cycle. Used when `forwardCollectorIssue` is enabled, to indicate whether
  // we need to use the forwarded opReady and collPtr data instead of the
  // latched one.
  val collForwardedTable = WireDefault(VecInit.fill(numEntries)(false.B))
  val collNeedAllReadyTable = Wire(Vec(numEntries, Bool()))
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
    collPtrTable(emptyRow) := 0.U

    debugf(cf"RS: admitted: warp=${io.admit.bits.ibufEntry.uop.wid}, " +
           cf"PC=${io.admit.bits.ibufEntry.uop.pc}%x at row ${emptyRow}\n")
    printTable
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
  val collNeedTable = WireDefault(VecInit(needCollects.map(_._1)))
  collNeedAllReadyTable := VecInit(needCollects.map(_._3))
  dontTouch(collNeedTable)
  dontTouch(collNeedAllReadyTable)

  // don't allow early-firing collector requests for partial operands of an
  // instruction.  Partial collects may result in a deadlock with insufficient
  // collector entries where the younger instruction requesting a partial
  // collect blocks collection of an older instruction, but has RAW hazard to
  // that older instruction.
  val allowPartialCollect = false
  // if allowPartialCollect == true, prioritize rows that have no RAW-busy ops
  // (no partial-collects), and only need collection as the last step before
  // issue.  Otherwise, rows with partial ops can take up valuable space in the
  // collector banks.
  val allReadyExists = collNeedAllReadyTable.reduce(_ || _)
  val firstAllReadyRow = PriorityEncoder(collNeedAllReadyTable)
  val firstNeedRow = PriorityEncoder(collNeedTable)
  val collRow = Mux(!allowPartialCollect.B || allReadyExists,
    firstAllReadyRow, firstNeedRow)
  dontTouch(collRow)

  val collOpNeed = VecInit(needCollects.map(_._2))(collRow)
  val collValid = (if (!allowPartialCollect) {
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
  (collOpNeed lazyZip collRegs lazyZip io.collector.readReq.bits.regs)
    .zipWithIndex.foreach { case ((need, pReg, readReqReg), rsi) =>
      assert(readReqReg.data.isEmpty) // isRead
      assert(readReqReg.tmask.isEmpty) // isRead
      readReqReg.enable := collValid && need
      readReqReg.pReg := Mux(need, pReg, 0.U)
    }
  io.collector.readReq.bits.rsEntryId := collRow
  io.collector.readReq.bits.pc.foreach(_ := collPC)
  when (io.collector.readReq.fire) {
    // collector alloc succeeded
    val newFired = (collFiredTable(collRow) zip io.collector.readReq.bits.regs.map(_.enable))
                   .map { case (a,b) => a || b }
    collFiredTable(collRow) := newFired
    debugf(cf"RS: collector request fired at row:${collRow}, warp:${collUop.wid}, pc:${collUop.pc}%x\n")
  }

  // upon collector response:
  // 1. mark operand ready in the table
  // 2. update scoreboard pending-reads
  //
  // collector wakeup should never block
  io.scb.updateColl.enable := io.collector.readResp.valid &&
                              io.collector.readResp.bits.regs.map(_.enable).reduce(_ || _)
  io.scb.updateColl.write := 0.U.asTypeOf(new ScoreboardRegUpdate)
  io.scb.updateColl.reads.foreach(_ := 0.U.asTypeOf(new ScoreboardRegUpdate))

  // ops already-collected + will-be-collected next cycle
  val newOpReadyTable = WireDefault(
    VecInit.tabulate(numEntries)(i => opReadyTable(i))
  )
  (0 until numEntries).foreach { i =>
    val valid = validTable(i)
    val rss = rsTable(i)
    val opReadys = opReadyTable(i)
    val newOpReadys = newOpReadyTable(i)
    val collFired = collFiredTable(i)
    val updated = WireDefault(false.B)
    (opReadys lazyZip collFired lazyZip rss lazyZip
     (io.collector.readResp.bits.regs zip io.scb.updateColl.reads))
      .zipWithIndex.foreach { case ((rdy, cFired, rs, (cRespReg, scbPort)), rsi) =>
        if (!useCollector) {
          assert(!cRespReg.enable,
            "RS: unexpected collector response when useCollector == false!")
        }

        // TODO: currently there's no way of matching collector response <-> RS
        // entry that fired readReq; we're assuming that only one RS entry
        // sends out a readReq at a time, and readResp comes back in-order.
        // Use rsEntryId in collector to relax this.
        val cRespValid = io.collector.readResp.valid
        when (valid && cRespValid && cRespReg.enable && !rdy && cFired) {
          newOpReadys(rsi) := true.B
          updated := true.B

          scbPort.pReg := rs
          scbPort.incr := false.B
          scbPort.decr := (rs =/= 0.U)

          debugf(cf"RS: collector response handled at row:${i}, " +
                 cf"warp:${instTable(i).uop.wid}, pc:${instTable(i).uop.pc}%x, " +
                 cf"collEntry:${io.collector.readResp.bits.collEntry}, " +
                 cf"rs:${rs}, rsi:${rsi}\n")
        }
      }
    when (updated) {
      opReadyTable(i) := newOpReadys
      collPtrTable(i) := io.collector.readResp.bits.collEntry
      // collection finished this cycle & eligible for forwarding?
      when (newOpReadys.reduce(_ && _)) {
        collForwardedTable(i) := true.B
      }
    }
  }

  // -----
  // issue
  // -----

  def issueBundleT = new Bundle {
    val entry = chiselTypeOf(io.issue.bits)
    val entryId = UInt(log2Ceil(numEntries).W)
    // these need to be stored at the issue output latch to control the
    // collector to serve EX with the right operands
    val hasOps = chiselTypeOf(hasOpTable.head)
    val collFired = Vec(Isa.maxNumRegs, Bool())
    val collEntry = UInt(numCollEntriesWidth.W)
  }

  // check issue eligiblity after collection finished & RAW settled
  val eligibles = VecInit((0 until numEntries).map { i =>
    val valid = validTable(i)
    val opReadys = opReadyTable(i)
    val newOpReadys = newOpReadyTable(i)
    val busys = busyTable(i)
    // using newOpReadys here considers ops that will-be-collected next cycle
    // as issue-eligible, eliminating one cycle latency from coll req -> issue
    // sched. enabling this optimization also brings some area benefit, since
    // reducing this latency allows shallower collector banks, which are
    // generally large
    val allCollected = (if (muonParams.forwardCollectorIssue)
      newOpReadys else opReadys).reduce(_ && _)
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

    eligibleTable(i) := eligible

    val candidate = Wire(Decoupled(issueBundleT))
    candidate.valid := eligible
    candidate.bits.entry := instTable(i)
    candidate.bits.entryId := i.U
    candidate.bits.hasOps := hasOpTable(i)
    candidate.bits.collFired := collFiredTable(i)
    candidate.bits.collEntry := (if (muonParams.forwardCollectorIssue) {
      // forward collector resp to issue, but only when this particular RS
      // entry has received that response
      Mux(collForwardedTable(i),
        io.collector.readResp.bits.collEntry,
        collPtrTable(i))
    } else {
      collPtrTable(i)
    })

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

  val issuedId = WireDefault(issueScheduler.io.out.bits.entryId)
  // FIXME: Old bypass-specific code; remove
  //
  // io.collector.readData.req.bits.zipWithIndex.foreach { case (port, rsi) =>
  //   port.enable := issueScheduled.fire && hasOpTable(issuedId)(rsi)
  //   port.pReg match {
  //     // for no-collector config, collEntry pointer is not used; use pRegs to
  //     // actually drive SRAMs
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

  // latch the issue at the output to cut timing before EX (which currently
  // does not latch its input), and also to stage it when EX is !ready.
  val issueStaged = Module(
    new Queue(gen = issueBundleT, entries = 1, pipe = true)
  )
  issueStaged.io.enq <> issueScheduler.io.out
  io.issue.valid := issueStaged.io.deq.valid
  io.issue.bits := issueStaged.io.deq.bits.entry
  issueStaged.io.deq.ready := io.issue.ready

  when (io.issue.fire) {
    debugf(cf"RS: issued: row:${issueStaged.io.deq.bits.entryId}, " +
           cf"warp:${io.issue.bits.uop.wid}, pc:${io.issue.bits.uop.pc}%x, " +
           cf"tmask:${io.issue.bits.uop.tmask}%b, " +
           cf"collEntry:${issueStaged.io.deq.bits.collEntry}; before:\n")
    printTable
  }

  // drive collector's operand serve port upon issue, for use in EX
  // make sure to line this up with EX fire, so that collector data gets
  // consumed when it's used
  // TODO: instead of the RS supplying collector hasOp, have the collector
  // track hasOp and reply with per-reg enable bits
  // only request readData for instructions that have accessed the collector
  val collFiredAny = issueStaged.io.deq.bits.collFired.reduce(_ || _)
  io.collector.readData.req.valid := issueStaged.io.deq.fire && collFiredAny
  io.collector.readData.req.bits.collEntry := issueStaged.io.deq.bits.collEntry
  (io.collector.readData.req.bits.regs zip issueStaged.io.deq.bits.hasOps).foreach { case (req, hasOp) =>
    req.enable := io.issue.fire && hasOp
  }

  assert(useCollector, "FIXME: !useCollector is broken currently")

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
      debugf(cf"RS: writeback: warp=${uop.wid}, rd=${io.writeback.bits.rd} " +
             cf"waking up PC=${uop.pc}%x at row ${i}:\n")
      printTable
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
    debugf("=" * 40 + " ReservationStation " + "=" * 40 + "\n")
    for (i <- 0 until numEntries) {
      val valid = validTable(i)
      when (valid) {
        val uop = instTable(i).uop
        debugf(cf"${i} | warp:${uop.wid} | pc:0x${uop.pc}%x | " +
               cf"[rd:${uop.inst.rd} rs1:${uop.inst.rs1} rs2:${uop.inst.rs2} rs3:${uop.inst.rs3}] | " +
               cf"hasOp:${hasOpTable(i)(0)}${hasOpTable(i)(1)}${hasOpTable(i)(2)} | " +
               cf"opReady:${opReadyTable(i)(0)}${opReadyTable(i)(1)}${opReadyTable(i)(2)} | " +
               cf"busy:${busyTable(i)(0)}${busyTable(i)(1)}${busyTable(i)(2)} | " +
               cf"collNeed:${collNeedAllReadyTable(i)} | " +
               cf"collFired:${collFiredTable(i)(0)}${collFiredTable(i)(1)}${collFiredTable(i)(2)} | " +
               cf"eligible:${eligibleTable(i)}" +
               cf"\n")
      }
    }
    debugf("=" * 100 + "\n")
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
