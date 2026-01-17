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
  val readRs1  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
  val readRs2  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
  val readRs3  = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
  val readRd   = new ScoreboardRead(scoreboardReadCountBits, scoreboardWriteCountBits)
  // TODO: per-warp ports
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
    val hazard = Vec(numWarps, new ScoreboardHazardIO)
  })

  class Entry extends Bundle {
    val pendingReads = chiselTypeOf(io.hazard.head.readRd.pendingReads)
    val pendingWrites = chiselTypeOf(io.hazard.head.readRd.pendingWrites)
    // TODO: reads epoch
  }

  // flip-flops
  val readTable = Mem(muonParams.numPhysRegs, chiselTypeOf(io.hazard.head.readRd.pendingReads))
  val writeTable = Mem(muonParams.numPhysRegs, chiselTypeOf(io.hazard.head.readRd.pendingWrites))

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
  def lookup(records: Seq[UpdateRecord], pReg: UInt, isWrite: Boolean) = {
    val table = (if (isWrite) writeTable else readTable)
    val value = Wire(UInt(table(0).getWidth.W))
    val dirty = WireDefault(false.B)
    value := table(pReg)

    // priority-tree
    records.foreach { rec =>
      when (rec.pReg === pReg) {
        value := rec.counter
        dirty := rec.dirty
      }
    }

    (value, dirty)
  }

  // updateWB/updateColl is decrement-only, and always succeeds (otherwise we
  // risk deadlock);
  // updateRS is increment-only, and may fail due to counter overflow.
  //
  // Apply updateWB/Coll first, and on the updated counter values, try
  // applying updateRS.  If the latter fails, only commit the post-WB/Coll
  // values to the table.  This requires generating updated counter values as
  // a separate stage into a set of Wires, and conditionally latching those values
  // to the Mem; that is what UpdateRecord is for.

  def applyUpdates(records: Seq[UpdateRecord], uniqUpdates: Seq[ConsolidatedRegUpdate], isWrite: Boolean, debug: String = ""):
    (Seq[UpdateRecord] /* new table */, Bool /* success */) = {
    val maxCount = (if (isWrite) {maxPendingWritesU} else {maxPendingReadsU})
    val countName = (if (isWrite) {"pendingWrites"} else {"pendingReads"})
    val success = WireDefault(true.B)

    val newRecords = uniqUpdates.map { u =>
      val dirtied = WireDefault(false.B)
      val (currCount, prevDirty) = lookup(records, u.pReg, isWrite)
      val newCount = WireDefault(currCount)

      // skip x0 updates
      //
      // TODO: refactor; handling both incr / decr seems overkill
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
      UpdateRecord(dirty, u.pReg, newCount, width = currCount.getWidth)
    }

    (newRecords, success)
  }

  def commitUpdate(recs: Seq[UpdateRecord], isWrite: Boolean) = {
    // need to reflect the latest index in the seq
    // TODO: refactor; the logic is largely similar to consolidateUpdates
    val syncRecs = recs.zipWithIndex.map { case (r, i) =>
      val count = WireDefault(r.counter)
      val dirty = WireDefault(r.dirty)
      // prefix-sum; overwrite self with right-most match
      // relies on its order being preserved in elaboration
      // NOTE: @perf: has high chance of being expensive for long recs!
      for (j <- i + 1 until recs.length) {
        when (recs(j).dirty && recs(j).pReg === r.pReg) {
          count := recs(j).counter
          dirty := recs(j).dirty
        }
      }
      UpdateRecord(dirty, r.pReg, count, width = r.counter.getWidth)
    }

    syncRecs.foreach { r =>
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

  // collector and writeback updates.  These are global across-warp
  //
  val uniqCollReadUpdates = consolidateUpdates(io.updateColl.reads)
  val uniqWBWriteUpdates  = consolidateUpdates(Seq(io.updateWB.write))
  val (collRecs, collSuccess) = applyUpdates(Seq(), uniqCollReadUpdates, isWrite = false, debug = "coll")
  val (wbRecs, wbSuccess) = applyUpdates(Seq(), uniqWBWriteUpdates,  isWrite = true,  debug = "wb")
  // assert(collSuccess && wbSuccess, "scoreboard: collector / WB update must always succeed!")

  io.updateWB.success := wbSuccess
  io.updateColl.success := collSuccess

  when (io.updateWB.enable) {
    printf(cf"scoreboard: received WB update ")
    printUpdate(io.updateWB)
  }
  when (io.updateColl.enable) {
    printf(cf"scoreboard: received collector update ")
    printUpdate(io.updateColl)
  }

  // RS admit updates.  These are per-warp
  //
  def perWarp(warpIo: ScoreboardHazardIO, warpId: Int) = {
    // RS admission updates
    val uniqRSReadUpdates  = consolidateUpdates(warpIo.updateRS.reads)
    val uniqRSWriteUpdates = consolidateUpdates(Seq(warpIo.updateRS.write))
    val (rsReadRecs,  rsReadSuccess)  = applyUpdates(collRecs, uniqRSReadUpdates,  isWrite = false, debug = "rsRead")
    val (rsWriteRecs, rsWriteSuccess) = applyUpdates(wbRecs,  uniqRSWriteUpdates, isWrite = true,  debug = "rsWrite")
    val rsSuccess = WireDefault(rsReadSuccess && rsWriteSuccess)
    dontTouch(rsSuccess)

    warpIo.updateRS.success := warpIo.updateRS.enable && rsSuccess

    when (warpIo.updateRS.enable) {
      printf(cf"scoreboard: received RS update (warp=${warpId}) ")
      printUpdate(warpIo.updateRS)
    }

    when (warpIo.updateRS.enable || io.updateWB.enable || io.updateColl.enable) {
      commitUpdate(collRecs ++ rsReadRecs, isWrite = false)
      commitUpdate(wbRecs ++ rsWriteRecs, isWrite = true)

      when (!rsReadSuccess) {
        printf(cf"scoreboard: warp=${warpId}: failed to commit RS update due to read overflow: ")
        printUpdate(warpIo.updateRS)
      }.elsewhen (!rsWriteSuccess) {
        printf(cf"scoreboard: warp=${warpId}: failed to commit RS update due to write overflow: ")
        printUpdate(warpIo.updateRS)
      }

      printf(cf"scoreboard: warp=${warpId}: table update, content beforehand:\n")
      printTable
    }

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
        port.pendingReads  := lookup(collRecs, port.pReg, isWrite = false)._1
        port.pendingWrites := lookup(wbRecs, port.pReg, isWrite = true)._1
      }
    }
    read(warpIo.readRs1)
    read(warpIo.readRs2)
    read(warpIo.readRs3)
    read(warpIo.readRd)
  }

  io.hazard.zipWithIndex.foreach { case (io, wid) => perWarp(io, wid) }

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
