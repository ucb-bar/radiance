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
    val update = new ScoreboardWrite
    val read = new ScoreboardRead(readCountBits, writeCountBits)
    // TODO: per-warp ports
  })

  def entryT = new Bundle {
    val pendingReads = UInt(readCountBits.W)
    // TODO: epoch
    val pendingWrites = UInt(writeCountBits.W)
  }

  // flip-flops
  val validTable = Mem(muonParams.numPhysRegs, Bool())
  val table = Mem(muonParams.numPhysRegs, entryT)

  val maxPendingReadsU = muonParams.maxPendingReads.U
  val maxPendingWritesU = 1.U

  // read
  io.read.pendingReads := 0.U
  io.read.pendingWrites := 0.U
  when (io.read.enable) {
    val row = table(io.read.pReg)
    io.read.pendingReads := row.pendingReads
    io.read.pendingWrites := row.pendingWrites
  }

  // update
  when (io.update.enable) {
    assert(!(io.update.readInc && io.update.readDec),
           "scoreboard increment and decrement cannot be both asserted")
    assert(!(io.update.writeInc && io.update.writeDec),
           "scoreboard increment and decrement cannot be both asserted")

    val row = table(io.update.pReg)
    when (io.update.readInc === true.B) {
      when (row.pendingReads =/= maxPendingReadsU) {
        row.pendingReads := row.pendingReads + 1.U
      }
    }.elsewhen (io.update.readDec === true.B) {
      when (row.pendingReads =/= 0.U) {
        row.pendingReads := row.pendingReads - 1.U
      }
    }
    when (io.update.writeInc === true.B) {
      when (row.pendingWrites =/= maxPendingWritesU) {
        row.pendingWrites := row.pendingWrites + 1.U
      }
    }.elsewhen (io.update.writeDec === true.B) {
      when (row.pendingWrites =/= 0.U) {
        row.pendingWrites := row.pendingWrites - 1.U
      }
    }
  }
}
