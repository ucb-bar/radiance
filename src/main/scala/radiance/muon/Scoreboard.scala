package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ScoreboardUpdate(implicit p: Parameters) extends CoreBundle()(p) {
  class RegUpdate extends CoreBundle()(p) {
    val pReg = Input(pRegT)
    val incr = Input(Bool())
    val decr = Input(Bool())
  }
  val enable = Input(Bool())
  /** rd update for pendingWrite */
  val write = new RegUpdate
  /** rs1/2/3 update for pendingReads */
  val reads = Vec(Isa.maxNumRegs, new RegUpdate)
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

/** Scoreboard module keeps track of pending reads and writes to every register
 *  by the current in-flight instructions.  It instructs the Hazard module and
 *  the reservation station whether an instruction has unresolved RAW/WAR/WAW
 *  hazards at the time of access.
 */
class Scoreboard(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new CoreBundle {
    // asynchronous-read, synchronous-write
    val update  = scoreboardUpdateIO
    val readRs1 = scoreboardReadIO
    val readRs2 = scoreboardReadIO
    val readRs3 = scoreboardReadIO
    val readRd  = scoreboardReadIO
    // TODO: per-warp ports
  })

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
      readTable(pReg.U) := 0.U
      writeTable(pReg.U) := 0.U
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

  // consolidate read counter updates to the same rs registers
  // if rs1/rs2/rs3 points to the same reg, bump the counters by the number
  // of duplicates.  This keeps coalescing out of consideration when designing
  // the collector.
  def consolidateUpdates: Seq[(UInt /* pReg */, UInt /* incr */, UInt /* decr */)] = {
    // check if reg # is unique with prefix Sum
    val matchCount = io.update.reads.zipWithIndex.map { case (self, i) =>
      // backward prefix sum
      (0 until i).map { j =>
        val other = io.update.reads(j)
        Mux(self.pReg === other.pReg, 1.U, 0.U)
      }.fold(0.U)(_ +& _)
    }

    // coalesce total incr/decrs to the same reg with prefix sum
    val coalescedIncDec = io.update.reads.zipWithIndex.map { case (self, i) =>
      // forward prefix sum
      (i until io.update.reads.length).map { j =>
        if (i == j) {
          (self.incr, self.decr)
        } else {
          val other = io.update.reads(j)
          (Mux(self.pReg === other.pReg, other.incr, 0.U),
           Mux(self.pReg === other.pReg, other.decr, 0.U))
        }
      }.reduce { (a, b) => (a._1 +& b._1, a._2 +& b._2) }
    }

    // filter out non-unique reg updates
    val pRegs = io.update.reads.map(_.pReg)
    ((pRegs zip matchCount) zip coalescedIncDec).map { case ((pr, cnt), (inc, dec)) =>
      val uniq = (cnt === 0.U)
      (pr, Mux(uniq, inc, 0.U), Mux(uniq, dec, 0.U))
    }
  }

  val accSuccess = WireDefault(true.B)

  when (io.update.enable) {
    printf(cf"scoreboard: received update ")
    printUpdate(io.update)

    // If any of the rs1/rs2/rs3/rd fails to update due to counter overflow,
    // none of the per-register updates should commit to the table. So collect
    // the updates first, then do a separate commit at the end when all regs
    // are confirmed successful.

    // reads
    val uniqReadUpdates = consolidateUpdates
    val readNewRows = uniqReadUpdates.map { read =>
      val pReg = read._1
      val incr = read._2
      val decr = read._3
      assert(incr === 0.U || decr === 0.U,
             "scoreboard increment and decrement cannot be both asserted")

      val dirty = WireDefault(false.B)
      val currReads = readTable(pReg)
      val newReads = WireDefault(currReads)

      when (pReg === 0.U) {
          // skip x0 updates
      }.elsewhen (incr =/= 0.U) {
        when (currReads + incr > maxPendingReadsU) {
          // overflow; report fail and don't do anything
          accSuccess := false.B
        }.otherwise {
          dirty := true.B
          newReads := currReads + incr
        }
      }.elsewhen (decr =/= 0.U) {
        // this should always succeed as retired reads would always be smaller
        // than recorded pendingReads
        assert(currReads > decr, cf"underflow of pendingReads at pReg=${pReg}")
        dirty := true.B
        newReads := currReads - decr
      }

      (dirty, pReg, newReads)
    }

    // write
    val writeNewRow = {
      val dirty = WireDefault(false.B)
      val pReg = io.update.write.pReg
      val currWrites = writeTable(pReg)
      val newWrites = WireDefault(currWrites)
      assert(!(io.update.write.incr && io.update.write.decr),
             "scoreboard increment and decrement cannot be both asserted")

      when (pReg === 0.U) {
        // skip x0 updates
      }.elsewhen (io.update.write.incr === true.B) {
        when (currWrites === maxPendingWritesU) {
          // overflow; report fail and don't do anything
          accSuccess := false.B
        }.otherwise {
          dirty := true.B
          newWrites := currWrites + 1.U
        }
      }.elsewhen (io.update.write.decr === true.B) {
        when (currWrites =/= 0.U) {
          dirty := true.B
          newWrites := currWrites - 1.U
        }
      }

      (dirty, pReg, newWrites)
    }

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
    when (accSuccess) {
      readNewRows.foreach { case (dirty, pReg, newRead) => {
        commitReg(dirty, pReg, newRead, isWrite = false)
      }}
      writeNewRow match { case (dirty, pReg, newWrite) => {
        commitReg(dirty, pReg, newWrite, isWrite = true)
      }}
    }.otherwise {
      printf(cf"scoreboard: failed to commit update ")
      printUpdate(io.update)
    }
  }

  io.update.success := accSuccess

  def printUpdate(upd: ScoreboardUpdate) = {
    def printReg(reg: upd.RegUpdate) = {
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
