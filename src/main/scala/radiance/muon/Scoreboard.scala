package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ScoreboardWrite(implicit p: Parameters) extends CoreBundle()(p) {
  val enable = Input(Bool())
  val pReg = Input(pRegT)
  val writeInc = Input(Bool())
  val writeDec = Input(Bool())
  val readInc  = Input(Bool())
  val readDec  = Input(Bool())
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

class Scoreboard(implicit p: Parameters) extends CoreModule()(p) {
  require(muonParams.maxPendingReads > 0, "wrong maxPendingReads for scoreboard")
  val readCountBits = log2Ceil(muonParams.maxPendingReads + 1)
  val writeCountBits = 1 // 0 or 1
  val io = IO(new Bundle {
    // asynchronous-read, synchronous-write
    val update  = new ScoreboardWrite
    val readRs1 = new ScoreboardRead(readCountBits, writeCountBits)
    val readRs2 = new ScoreboardRead(readCountBits, writeCountBits)
    val readRs3 = new ScoreboardRead(readCountBits, writeCountBits)
    val readRd  = new ScoreboardRead(readCountBits, writeCountBits)
    // TODO: per-warp ports
  })

  def entryT = new Bundle {
    val pendingReads = UInt(readCountBits.W)
    val pendingWrites = UInt(writeCountBits.W)
    // TODO: reads epoch
  }

  // flip-flops
  val table = Mem(muonParams.numPhysRegs, entryT)

  val maxPendingReadsU = muonParams.maxPendingReads.U
  val maxPendingWritesU = 1.U

  // reset
  // @synthesis: unsure if this will generate expensive trees, revisit
  when (reset.asBool) {
    (0 until muonParams.numPhysRegs).foreach { pReg =>
      table(pReg.U).pendingReads := 0.U
      table(pReg.U).pendingWrites := 0.U
    }
  }

  // read
  def read(port: ScoreboardRead) = {
    port.pendingReads := 0.U
    port.pendingWrites := 0.U
    when (port.enable) {
      val row = table(port.pReg)
      port.pendingReads  := row.pendingReads
      port.pendingWrites := row.pendingWrites
    }
  }
  read(io.readRs1)
  read(io.readRs2)
  read(io.readRs3)
  read(io.readRd)

  // update
  when (io.update.enable) {
    assert(!(io.update.readInc && io.update.readDec),
           "scoreboard increment and decrement cannot be both asserted")
    assert(!(io.update.writeInc && io.update.writeDec),
           "scoreboard increment and decrement cannot be both asserted")

    // partial writes to Mem rows seem to be flaky, construct a full row for
    // writes instead
    val row = table(io.update.pReg)
    val newRow = WireDefault(row)
    when (io.update.readInc === true.B) {
      when (row.pendingReads =/= maxPendingReadsU) {
        newRow.pendingReads := row.pendingReads + 1.U
      }
    }.elsewhen (io.update.readDec === true.B) {
      when (row.pendingReads =/= 0.U) {
        newRow.pendingReads := row.pendingReads - 1.U
      }
    }
    when (io.update.writeInc === true.B) {
      when (row.pendingWrites =/= maxPendingWritesU) {
        newRow.pendingWrites := row.pendingWrites + 1.U
      }
    }.elsewhen (io.update.writeDec === true.B) {
      when (row.pendingWrites =/= 0.U) {
        newRow.pendingWrites := row.pendingWrites - 1.U
      }
    }
    table(io.update.pReg) := newRow
  }
}
