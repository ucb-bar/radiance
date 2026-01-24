package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ScoreboardRegUpdate(implicit p: Parameters) extends CoreBundle()(p) {
  val pReg = pRegT
  val incr = Bool()
  val decr = Bool()
}

class ScoreboardUpdate(implicit p: Parameters) extends CoreBundle()(p) {
  val enable = Input(Bool())
  /** rd update to pendingWrite */
  val write = Input(new ScoreboardRegUpdate)
  /** rs1/2/3 updates to pendingReads */
  val reads = Input(Vec(Isa.maxNumRegs, new ScoreboardRegUpdate))
  /** false if scoreboard counters saturated.  Combinational same-cycle. */
  val success = Output(Bool())
}

class ScoreboardRead(
  readCountBits: Int,
  writeCountBits: Int
)(implicit p: Parameters) extends CoreBundle()(p) {
  val enable = Input(Bool())
  val pReg = Input(pRegT)
  val pendingReads = Output(UInt(readCountBits.W))
  val pendingWrites = Output(UInt(writeCountBits.W))
}

class ScoreboardHazardIO(implicit p: Parameters) extends CoreBundle()(p) {
  val updateRS = new ScoreboardUpdate
  val warps = Vec(numWarps, new Bundle {
    val readRs1  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
    val readRs2  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
    val readRs3  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
    val readRd   = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
  })
}

/** Scoreboard module keeps track of pending reads and writes to every register
 *  by the current in-flight instructions.  It instructs the Hazard module and
 *  the reservation station whether an instruction has unresolved RAW/WAR/WAW
 *  hazards at the time of access.
 */
class Scoreboard(implicit p: Parameters) extends CoreModule()(p) {
  // asynchronous-read, synchronous-write
  val io = IO(new Bundle {
    /** scoreboard pending-read decrements on collector response */
    val updateColl = new ScoreboardUpdate
    /** scoreboard pending-write decrements on writeback */
    val updateWB = new ScoreboardUpdate
    /** scoreboard accesses/updates on RS admission from the Hazard stage.
     *  These are per-warp ports. */
    val hazard = new ScoreboardHazardIO
  })

  class Entry extends Bundle {
    val pendingReads = UInt(scoreboardReadCountBits.W)
    val pendingWrites = UInt(scoreboardWriteCountBits.W)
    // TODO: reads epoch
  }

  // flip-flops
  val readTable = Mem(muonParams.numPhysRegs, UInt(scoreboardReadCountBits.W))
  val writeTable = Mem(muonParams.numPhysRegs, UInt(scoreboardWriteCountBits.W))

  // reset
  // @synthesis: unsure if this will generate expensive trees, revisit
  when (reset.asBool) {
    (0 until muonParams.numPhysRegs).foreach { pReg =>
      readTable(pReg) := 0.U
      writeTable(pReg) := 0.U
    }
  }

  // update
  // ------
  //
  val maxPendingReadsU = muonParams.maxPendingReads.U
  val maxPendingWritesU = 1.U

  class ConsolidatedRegUpdate(
    maxCount: Int
  )(implicit p: Parameters) extends CoreBundle()(p) {
    val pReg = pRegT
    val incr = UInt(log2Ceil(maxCount + 1).W)
    val decr = UInt(log2Ceil(maxCount + 1).W)
  }

  // consolidate counter updates to the same rs registers
  // if rs1/rs2/rs3 points to the same reg, bump the counters by the number
  // of duplicates.  This keeps coalescing out of consideration when designing
  // the collector.
  // note the final Seq is always length 3 (maxNumRegs).
  def consolidateUpdates(updates: Seq[ScoreboardRegUpdate]): Seq[ConsolidatedRegUpdate] = {
    // check if reg # is unique with prefix Sum
    val matchCount = updates.zipWithIndex.map { case (self, i) =>
      // backward prefix sum
      (0 until i).map { j =>
        val other = updates(j)
        Mux(self.pReg === other.pReg, 1.U, 0.U)
      }.fold(0.U)(_ +& _)
    }

    // coalesce total incr/decrs to the same reg with prefix-sum
    val coalescedIncDec = updates.zipWithIndex.map { case (self, i) =>
      // forward prefix sum
      (i until updates.length).map { j =>
        if (i == j) {
          (self.incr, self.decr)
        } else {
          val other = updates(j)
          (Mux(self.pReg === other.pReg, other.incr, 0.U),
           Mux(self.pReg === other.pReg, other.decr, 0.U))
        }
      }.reduce { (a, b) => (a._1 +& b._1, a._2 +& b._2) }
    }

    // filter out non-unique reg updates
    val pRegs = updates.map(_.pReg)
    ((pRegs zip matchCount) zip coalescedIncDec).map { case ((pr, cnt), (inc, dec)) =>
      val uniq = (cnt === 0.U)
      val upd = Wire(new ConsolidatedRegUpdate(updates.length))
      upd.pReg := Mux(uniq, pr, 0.U)
      upd.incr := Mux(uniq, inc, 0.U)
      upd.decr := Mux(uniq, dec, 0.U)
      upd
    }
  }

  class UpdateRecord(counterBits: Int)(implicit p: Parameters) extends CoreBundle()(p) {
    val dirty = Bool()
    val pReg = pRegT
    val counter = UInt(counterBits.W)
  }

  object UpdateRecord {
    def apply(dirty: Bool, pReg: UInt, counter: UInt, width: Int): UpdateRecord = {
      val rec = Wire(new UpdateRecord(width))
      rec.dirty := dirty
      rec.pReg := pReg
      rec.counter := counter
      rec
    }
  }

  // Look up the most recent counter values for a pReg, by not only reading
  // the flip-flop table, but also the given update records.
  // Also returns a bitvector that indicates which of the `records` was hit for
  // the lookup.
  def lookup(records: Seq[UpdateRecord], pReg: UInt, isWrite: Boolean) = {
    val table = (if (isWrite) writeTable else readTable)
    val hit   = WireDefault(false.B)
    val value = Wire(UInt(table(0).getWidth.W))
    val dirty = WireDefault(false.B)
    value := table(pReg)

    // priority-tree
    val hitvec = records.map { rec =>
      val hit = (rec.pReg === pReg)
      when (hit) {
        value := rec.counter
        dirty := rec.dirty
      }
      hit
    }

    (hitvec, (value, dirty))
  }

  // updateWB/updateColl is decrement-only, and always succeeds (otherwise we
  // risk deadlock);
  // updateRS is increment-only, and may fail due to counter overflow.
  //
  // Apply updateWB/Coll first, and on the updated counter values, try applying
  // updateRS.  If the latter fails, only commit the post-WB/Coll values to the
  // table.  This requires generating updated counter values as a separate stage
  // into a set of Wires, and conditionally latching those values to the Mem;
  // that is what UpdateRecord is for.

  def applyUpdates(records: Seq[UpdateRecord], uniqUpdates: Seq[ConsolidatedRegUpdate], isWrite: Boolean, debug: String = ""):
    (Seq[UpdateRecord] /* new updates */, Bool /* success */) = {
    val maxCount = (if (isWrite) {maxPendingWritesU} else {maxPendingReadsU})
    val countName = (if (isWrite) {"pendingWrites"} else {"pendingReads"})
    val success = WireDefault(true.B)

    val processUpdates = uniqUpdates.map { u =>
      val dirtied = WireDefault(false.B)
      // are we updating upon another same-cycle update?
      val (hitvec, (currCount, prevDirty)) = lookup(records, u.pReg, isWrite)
      val newCount = WireDefault(currCount)

      // skip x0 updates
      when (u.pReg =/= 0.U) {
        // if currCount + u.incr overflows but u.decr cancels it out, treat
        // it as a success.
        when (u.incr =/= u.decr) {
          // stay positive (literally)
          val posDelta = (u.incr > u.decr)
          val overflow = posDelta && ((u.incr - u.decr) > (maxCount - currCount))
          val underflow = !posDelta && (u.decr - u.incr) > currCount

          success := !overflow && !underflow
          dirtied := success
          when (success) {
            newCount := currCount + u.incr - u.decr
          }

          printf(cf"applyUpdates: [${debug}] ${countName} pReg:${u.pReg}, newCount:${newCount}, currCount:${currCount}, incr:${u.incr}(${u.incr.getWidth}W), decr:${u.decr}(${u.decr.getWidth}W), success:${success}\n")

          // underflow should never be possible since the number of retired
          // regs should strictly be smaller than the pending regs, i.e. no
          // over-commit beyond what's issued
          //
          // assert(!underflow,
          //        cf"scoreboard: ${countName} underflow at pReg=${u.pReg} " +
          //        cf"(currCount=${currCount}, incr=${u.incr}, decr=${u.decr})")
        }.elsewhen (u.incr === u.decr && u.incr =/= 0.U) {
          printf(cf"applyUpdates: [${debug}] ${countName} incr/decr cancel; pReg:${u.pReg}, newCount: ${newCount}, currCount: ${currCount}(${currCount.getWidth}W), incr:${u.incr}(${u.incr.getWidth}W), decr:${u.decr}(${u.decr.getWidth}W)\n")
        }
      }

      val dirty = prevDirty || dirtied
      (UpdateRecord(dirty, u.pReg, newCount, width = currCount.getWidth), hitvec)
    }
    val newRecords = processUpdates.map(_._1)

    // if each of the `records` got dirtied by the new updates, we need to
    // invalidate them so we don't do double-updates to the counter table
    val prevRecords: Seq[UpdateRecord] = if (records.isEmpty) {
      Seq()
    } else {
      val recordsHitvec = processUpdates.map(_._2).map(VecInit(_).asUInt).reduce(_ | _).asBools
      assert(recordsHitvec.length == records.length)
      (records zip recordsHitvec).map { case (rec, hit) =>
        UpdateRecord(!hit && rec.dirty, rec.pReg, rec.counter, width = rec.counter.getWidth)
      }
    }

    (prevRecords ++ newRecords, success)
  }

  def commitUpdate(recs: Seq[UpdateRecord], isWrite: Boolean) = {
    recs.foreach { r =>
      when (r.dirty) {
        assert(r.pReg =/= 0.U, "update to x0 not filtered in the logic?")
        if (isWrite) {
          printf(cf"scoreboard: committed write (pReg:${r.pReg}, new pendingWrites:${r.counter})\n")
          writeTable(r.pReg) := r.counter
        } else {
          printf(cf"scoreboard: committed read (pReg:${r.pReg}, new pendingReads:${r.counter})\n")
          readTable(r.pReg) := r.counter
        }
      }
    }
  }

  // collector and writeback updates
  val uniqCollReadUpdates = consolidateUpdates(io.updateColl.reads)
  val uniqWBWriteUpdates  = consolidateUpdates(Seq(io.updateWB.write))
  val (collRecs, collSuccess) = applyUpdates(Seq(), uniqCollReadUpdates, isWrite = false, debug = "coll")
  val (wbRecs, wbSuccess) = applyUpdates(Seq(), uniqWBWriteUpdates,  isWrite = true,  debug = "wb")
  // assert(collSuccess && wbSuccess, "scoreboard: collector / WB update must always succeed!")

  io.updateWB.success := wbSuccess
  io.updateColl.success := collSuccess

  // RS admission updates
  val uniqRSReadUpdates  = consolidateUpdates(io.hazard.updateRS.reads)
  val uniqRSWriteUpdates = consolidateUpdates(Seq(io.hazard.updateRS.write))
  val (rsReadRecs,  rsReadSuccess)  = applyUpdates(collRecs, uniqRSReadUpdates, isWrite = false, debug = "rsRead")
  val (rsWriteRecs, rsWriteSuccess) = applyUpdates(wbRecs,  uniqRSWriteUpdates, isWrite = true,  debug = "rsWrite")
  val rsSuccess = WireDefault(rsReadSuccess && rsWriteSuccess)
  dontTouch(rsSuccess)

  io.hazard.updateRS.success := io.hazard.updateRS.enable && rsSuccess

  when (io.hazard.updateRS.enable) {
    if (muonParams.debug) {
      printf(cf"scoreboard: received RS update ")
      printUpdate(io.hazard.updateRS)
    }

    // these contain coll/wb updates
    when (rsSuccess) {
      commitUpdate(rsReadRecs, isWrite = false)
      commitUpdate(rsWriteRecs, isWrite = true)
    }.otherwise {
      commitUpdate(collRecs, isWrite = false)
      commitUpdate(wbRecs, isWrite = true)
    }

    if (muonParams.debug) {
      when (!rsReadSuccess) {
        printf(cf"scoreboard: failed to commit RS update due to read overflow: ")
        printUpdate(io.hazard.updateRS)
      }.elsewhen (!rsWriteSuccess) {
        printf(cf"scoreboard: failed to commit RS update due to write overflow: ")
        printUpdate(io.hazard.updateRS)
      }

      printf(cf"scoreboard: table received RS update; content beforehand:\n")
      printTable
    }
  }.elsewhen (io.updateWB.enable || io.updateColl.enable) {
    if (muonParams.debug) {
      when (io.updateWB.enable) {
        printf("scoreboard: received WB update ")
        printUpdate(io.updateWB)
      }
      when (io.updateColl.enable) {
        printf("scoreboard: received coll update ")
        printUpdate(io.updateColl)
      }
    }

    commitUpdate(collRecs, isWrite = false)
    commitUpdate(wbRecs, isWrite = true)

    if (muonParams.debug) {
      printf(cf"scoreboard: table received coll/WB update; content beforehand:\n")
      printTable
    }
  }

  def readWarp(warpId: Int) = {
    // read
    // ----
    //
    def read(port: ScoreboardRead) = {
      port.pendingReads := 0.U
      port.pendingWrites := 0.U
      when (port.enable) {
        // using lookup here enables bypassing same-cycle updates to reads to the
        // same pReg
        // NOTE: don't use rsReadRecs/rsWriteRecs here, otherwise results in a
        // combinational cycle with the RS admission logic in the Hazard module
        port.pendingReads  := lookup(collRecs, port.pReg, isWrite = false)._2._1
        port.pendingWrites := lookup(wbRecs, port.pReg, isWrite = true)._2._1
      }
    }
    read(io.hazard.warps(warpId).readRs1)
    read(io.hazard.warps(warpId).readRs2)
    read(io.hazard.warps(warpId).readRs3)
    read(io.hazard.warps(warpId).readRd)
  }

  for (i <- 0 until numWarps) { readWarp(i) }

  def printUpdate(upd: ScoreboardUpdate) = {
    def printReg(reg: ScoreboardRegUpdate) = {
      printf(cf"{pReg:${reg.pReg}, incr:")
       when (reg.incr) {
        printf("1")
      }.elsewhen (reg.decr) {
        printf("-1")
      }.otherwise {
        printf("0")
      }
      printf("}")
    }

    printf("{rs1: ")
    printReg(upd.reads(0))
    printf(", rs2: ")
    printReg(upd.reads(1))
    printf(", rs3: ")
    printReg(upd.reads(2))
    printf(", rd: ")
    printReg(upd.write)
    printf("}\n")
  }

  def printTable = {
    // @perf: NOTE: this can instantiate a read port for *every* table entries;
    // make sure to guard this with debug
    if (muonParams.debug) {
      printf("=" * 8 + " Scoreboard " + "=" * 8 + "\n")
      for (i <- 0 until muonParams.numPhysRegs) {
        val reads = readTable(i)
        val writes = writeTable(i)
        when (reads > 0.U || writes > 0.U) {
          printf(cf"p${i} | writes:${writes} | reads:${reads}\n")
        }
      }
      printf("=" * 28 + "\n")
    }
  }
}
