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
  /** false if scoreboard counters saturated */
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

class ScoreboardIO(implicit p: Parameters) extends CoreBundle()(p) {
  /** scoreboard updates on RS admission */
  val updateRS = new ScoreboardUpdate
  /** scoreboard updates on writeback */
  val updateWB = new ScoreboardUpdate
  /** scoreboard accesses on RS admission */
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
  val io = IO(new ScoreboardIO)

  class Entry extends Bundle {
    val pendingReads = chiselTypeOf(io.readRd.pendingReads)
    val pendingWrites = chiselTypeOf(io.readRd.pendingWrites)
    // TODO: reads epoch
  }

  // flip-flops
  val readTable = Mem(muonParams.numPhysRegs, chiselTypeOf(io.readRd.pendingReads))
  val writeTable = Mem(muonParams.numPhysRegs, chiselTypeOf(io.readRd.pendingWrites))

  // reset
  // @synthesis: unsure if this will generate expensive trees, revisit
  when (reset.asBool) {
    (0 until muonParams.numPhysRegs).foreach { pReg =>
      readTable(pReg) := 0.U
      writeTable(pReg) := 0.U
    }
  }

  // read
  // ----
  //
  def read(port: ScoreboardRead) = {
    port.pendingReads := 0.U
    port.pendingWrites := 0.U
    // valid-gate
    when (port.enable) {
      port.pendingReads  := readTable(port.pReg)
      port.pendingWrites := writeTable(port.pReg)
    }
  }
  read(io.readRs1)
  read(io.readRs2)
  read(io.readRs3)
  read(io.readRd)

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
  def consolidateUpdates(updates: Seq[ScoreboardRegUpdate]): Seq[ConsolidatedRegUpdate] = {
    // check if reg # is unique with prefix Sum
    val matchCount = updates.zipWithIndex.map { case (self, i) =>
      // backward prefix sum
      (0 until i).map { j =>
        val other = updates(j)
        Mux(self.pReg === other.pReg, 1.U, 0.U)
      }.fold(0.U)(_ +& _)
    }

    // coalesce total incr/decrs to the same reg with prefix sum
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
      upd.pReg := pr
      upd.incr := Mux(uniq, inc, 0.U)
      upd.decr := Mux(uniq, dec, 0.U)
      upd
    }
  }

  // RS admission bumps counters up, which may fail due to saturation; need to
  // check for that and report to Hazard to guard admission.
  val updateRSSuccess = WireDefault(true.B)

  when (io.updateRS.enable || io.updateWB.enable) {
    when (io.updateRS.enable) {
      printf(cf"scoreboard: received RS update ")
      printUpdate(io.updateRS)
    }.elsewhen (io.updateWB.enable) {
      printf(cf"scoreboard: received WB update ")
      printUpdate(io.updateWB)
    }

    // If any of the rs1/rs2/rs3/rd fails to update due to counter overflow,
    // the entire instruction should be held back from commit to the table. So
    // collect the per-reg updates first, then do a separate commit at the end
    // when all regs are confirmed successful.

    def generateNewCounters(uniqUpdates: Seq[ConsolidatedRegUpdate], isWrite: Boolean) = {
      val table = (if (isWrite) {writeTable} else {readTable})
      val maxCount = (if (isWrite) {maxPendingWritesU} else {maxPendingReadsU})
      val countName = (if (isWrite) {"pendingWrites"} else {"pendingReads"})

      uniqUpdates.map { u =>
        val dirty = WireDefault(false.B)
        val currCount = table(u.pReg)
        val newCount = WireDefault(currCount)

        // skip x0 updates
        when (u.pReg =/= 0.U) {
          // if currCount + u.incr overflows but u.decr cancels it out, treat
          // it as a success.
          when (u.incr =/= u.decr) {
            val delta = u.incr.asSInt - u.decr.asSInt
            val newCountWide = currCount.asSInt +& delta
            when (newCountWide.asUInt > maxCount) {
              // overflow; don't reflect increments, but do decrements
              // it is important to always succeed WBs and collector
              // decrements, otherwise we risk deadlock

              // FIXME: this doesn't guard against partial writes of updateRS!!!
              updateRSSuccess := false.B
              assert(false.B,
                     cf"TODO: partial update rollback on counter overflow not handled " +
                     cf"(${countName}, pReg:${u.pReg}, newCount:${newCountWide}, oldCount:${currCount})")

              dirty := (u.decr =/= 0.U)
              assert(currCount >= u.decr,
                     cf"scoreboard: ${countName} underflow at pReg=${u.pReg}" +
                     cf"(currCount=${currCount}, incr=${u.incr}, decr=${u.decr}) ")
              newCount := currCount - u.decr
            }.otherwise {
              dirty := true.B
              // underflow should never be possible since the number of retired
              // regs should strictly be smaller than the pending regs, i.e. no
              // over-commit beyond what's issued
              assert(newCountWide >= 0.S,
                     cf"scoreboard: ${countName} underflow at pReg=${u.pReg}" +
                     cf"(currCount=${currCount}, incr=${u.incr}, decr=${u.decr}) ")
              newCount := newCountWide.asUInt
            }
          }
        }

        (dirty, u.pReg, newCount)
      }
    }

    // reads
    val allReadUpdates = io.updateRS.reads // updateWB.reads should be empty
    val uniqReadUpdates = consolidateUpdates(allReadUpdates)
    val readNewCounters = generateNewCounters(uniqReadUpdates, isWrite = false)

    // writes
    // consolidate updates from RS-admission and writeback
    val allWriteUpdates = Seq(io.updateRS.write, io.updateWB.write)
    val uniqWriteUpdates = consolidateUpdates(allWriteUpdates)
    val writeNewCounters = generateNewCounters(uniqWriteUpdates, isWrite = true)

    // commit
    def commitReg(dirty: Bool, pReg: UInt, newVal: UInt, isWrite: Boolean) = {
      when (dirty) {
        assert(pReg =/= 0.U, "update to x0 not filtered in the logic?")
        if (isWrite) {
          printf(cf"scoreboard: commited write (pReg:${pReg}, pendingWrites:${newVal})\n")
          writeTable(pReg) := newVal
        } else {
          printf(cf"scoreboard: commited read (pReg:${pReg}, pendingReads:${newVal})\n")
          readTable(pReg) := newVal
        }
      }
    }

    readNewCounters.foreach { case (dirty, pReg, newRead) => {
      commitReg(dirty, pReg, newRead, isWrite = false)
    }}
    writeNewCounters.foreach { case (dirty, pReg, newWrite) => {
      commitReg(dirty, pReg, newWrite, isWrite = true)
    }}

    when (!updateRSSuccess) {
      printf(cf"scoreboard: failed to commit RS update ")
      printUpdate(io.updateRS)
    }
  }

  io.updateRS.success := updateRSSuccess
  // WB decrement always succeeds
  io.updateWB.success := true.B

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

}
