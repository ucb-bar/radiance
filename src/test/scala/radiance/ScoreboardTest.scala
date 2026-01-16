package radiance.muon

import chisel3._
import freechips.rocketchip.tile.{TileKey, TileParams}
import freechips.rocketchip.prci.ClockSinkParameters
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.EphemeralSimulator._

class ScoreboardTest extends AnyFlatSpec {
  private case class DummyTileParams(muon: MuonCoreParams) extends TileParams {
    val core: MuonCoreParams = muon
    val icache = None
    val dcache = None
    val btb = None
    val tileId = 0
    val blockerCtrlAddr = None
    val baseName = "scoreboard_test_tile"
    val uniqueName = baseName
    val clockSinkParams = ClockSinkParameters()
  }

  def testParams(): Parameters =
    Parameters.empty.alterPartial {
      case TileKey => DummyTileParams(MuonCoreParams())
      case MuonKey => MuonCoreParams()
    }

  def reset(c: Scoreboard): Unit = {
    c.reset.poke(true.B)
    c.clock.step()
    c.reset.poke(false.B)
    c.clock.step()
  }

  def clearIO(c: Scoreboard): Unit = {
    c.io.updateRS.enable.poke(false.B)
    c.io.updateRS.write.pReg.poke(0.U)
    c.io.updateRS.write.incr.poke(false.B)
    c.io.updateRS.write.decr.poke(false.B)
    c.io.updateRS.reads.foreach { r =>
      r.pReg.poke(0.U)
      r.incr.poke(false.B)
      r.decr.poke(false.B)
    }

    c.io.updateColl.enable.poke(false.B)
    c.io.updateColl.write.pReg.poke(0.U)
    c.io.updateColl.write.incr.poke(false.B)
    c.io.updateColl.write.decr.poke(false.B)
    c.io.updateColl.reads.foreach { r =>
      r.pReg.poke(0.U)
      r.incr.poke(false.B)
      r.decr.poke(false.B)
    }

    c.io.updateWB.enable.poke(false.B)
    c.io.updateWB.write.pReg.poke(0.U)
    c.io.updateWB.write.incr.poke(false.B)
    c.io.updateWB.write.decr.poke(false.B)
    c.io.updateWB.reads.foreach { r =>
      r.pReg.poke(0.U)
      r.incr.poke(false.B)
      r.decr.poke(false.B)
    }

    c.io.readRs1.enable.poke(false.B)
    c.io.readRs1.pReg.poke(0.U)
    c.io.readRs2.enable.poke(false.B)
    c.io.readRs2.pReg.poke(0.U)
    c.io.readRs3.enable.poke(false.B)
    c.io.readRs3.pReg.poke(0.U)
    c.io.readRd.enable.poke(false.B)
    c.io.readRd.pReg.poke(0.U)
  }

  def setUpdateRS(
      c: Scoreboard,
      pReg: Int,
      incr: Boolean,
      decr: Boolean,
      doWrite: Boolean,
      doRead: Boolean,
      rsi: Int = 0
  ): Unit = {
    c.io.updateRS.enable.poke(true.B)
    if (doWrite) c.io.updateRS.write.pReg.poke(pReg.U) else c.io.updateRS.write.pReg.poke(0.U)
    c.io.updateRS.write.incr.poke((doWrite && incr).B)
    c.io.updateRS.write.decr.poke((doWrite && decr).B)
    if (doRead) c.io.updateRS.reads(rsi).pReg.poke(pReg.U) else c.io.updateRS.reads(rsi).pReg.poke(0.U)
    c.io.updateRS.reads(rsi).incr.poke((doRead && incr).B)
    c.io.updateRS.reads(rsi).decr.poke((doRead && decr).B)
  }

  it should "increment pendingReads and pendingWrites after updateRS" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdateRS(c, pReg = 42, incr = true, decr = false, doWrite = true, doRead = true, rsi = 0)
      c.io.updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.readRs1.enable.poke(true.B)
      c.io.readRs1.pReg.poke(42.U)
      c.clock.step()

      c.io.readRs1.pendingReads.expect(1.U)
      c.io.readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "not increment if both incr and decr at the same time" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdateRS(c, pReg = 42, incr = true, decr = true, doWrite = true, doRead = false)
      c.io.updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.readRs1.enable.poke(true.B)
      c.io.readRs1.pReg.poke(42.U)
      c.clock.step()

      c.io.readRs1.pendingWrites.expect(0.U)
    }
  }
}
