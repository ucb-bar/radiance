package radiance.muon

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import freechips.rocketchip.tile.{TileKey, TileParams}
import freechips.rocketchip.prci.ClockSinkParameters
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

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
    c.io.hazard.foreach { wio =>
      wio.updateRS.enable.poke(false.B)
      wio.updateRS.write.pReg.poke(0.U)
      wio.updateRS.write.incr.poke(false.B)
      wio.updateRS.write.decr.poke(false.B)
      wio.updateRS.reads.foreach { r =>
        r.pReg.poke(0.U)
        r.incr.poke(false.B)
        r.decr.poke(false.B)
      }
      wio.readRs1.enable.poke(false.B)
      wio.readRs1.pReg.poke(0.U)
      wio.readRs2.enable.poke(false.B)
      wio.readRs2.pReg.poke(0.U)
      wio.readRs3.enable.poke(false.B)
      wio.readRs3.pReg.poke(0.U)
      wio.readRd.enable.poke(false.B)
      wio.readRd.pReg.poke(0.U)
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

    c.io.hazard(0).readRs1.enable.poke(false.B)
    c.io.hazard(0).readRs1.pReg.poke(0.U)
    c.io.hazard(0).readRs2.enable.poke(false.B)
    c.io.hazard(0).readRs2.pReg.poke(0.U)
    c.io.hazard(0).readRs3.enable.poke(false.B)
    c.io.hazard(0).readRs3.pReg.poke(0.U)
    c.io.hazard(0).readRd.enable.poke(false.B)
    c.io.hazard(0).readRd.pReg.poke(0.U)

    c.clock.step()
  }

  def setUpdate(
      upd: ScoreboardUpdate,
      pReg: Int,
      incr: Boolean,
      decr: Boolean,
      doWrite: Boolean,
      doRead: Boolean,
      rsi: Int = 0
  ): Unit = {
    upd.enable.poke(true.B)
    if (doWrite) upd.write.pReg.poke(pReg.U) else upd.write.pReg.poke(0.U)
    upd.write.incr.poke((doWrite && incr).B)
    upd.write.decr.poke((doWrite && decr).B)
    if (doRead) upd.reads(rsi).pReg.poke(pReg.U) else upd.reads(rsi).pReg.poke(0.U)
    upd.reads(rsi).incr.poke((doRead && incr).B)
    upd.reads(rsi).decr.poke((doRead && decr).B)
  }

  it should "increment pendingReads and pendingWrites after updateRS" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = true, rsi = 0)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(1.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "not increment if incr and decr cancels each other" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = true, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
    }
  }

  it should "succeed if counter is saturated but decr cancels out incr" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      clearIO(c)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(false.B)
      c.clock.step()

      clearIO(c)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = true, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "increment pendingReads on multiple updateRS to the same reg" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 0)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 1)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 2)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(3.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
    }
  }

  it should "fail if pendingReads counter has overflown" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      for (i <- 0 until m.maxPendingReads) {
        setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
        c.io.hazard(0).updateRS.success.expect(true.B)
        c.clock.step()
      }

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(false.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(m.maxPendingReads.U)
    }
  }

  it should "fail if pendingWrites counter has overflown" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(false.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "unroll both pendingReads and Writes if either counter has overflown" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = true)
      c.io.hazard(0).updateRS.success.expect(false.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "fail if pendingReads/pendingWrites counter has underflown" in {
    val p = testParams()
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)

      c.clock.step()

      setUpdate(c.io.updateColl, pReg = 42, incr = false, decr = true, doWrite = false, doRead = true)
      setUpdate(c.io.updateWB,   pReg = 42, incr = false, decr = true, doWrite = true, doRead = false)
      c.io.updateColl.success.expect(false.B)
      c.io.updateWB.success.expect(false.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
    }
  }

  it should "correctly reflect concurrent updateWB and updateRS to same reg" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      // pendingWrites(42) == 1

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      setUpdate(c.io.updateWB, pReg = 42, incr = false, decr = true, doWrite = true, doRead = false)
      // order matters!
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.io.updateWB.success.expect(true.B)
      c.clock.step()

      // pendingWrites(42): 1 -> 0 (updateWB) -> 1 (updateRS)

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(1.U)
    }
  }

  it should "correctly reflect concurrent updateWB and updateRS writes to different reg" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      setUpdate(c.io.updateWB, pReg = 42, incr = false, decr = true, doWrite = true, doRead = false)
      setUpdate(c.io.hazard(0).updateRS, pReg = 5, incr = true, decr = false, doWrite = true, doRead = false)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.io.updateWB.success.expect(true.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs2.enable.poke(true.B)
      c.io.hazard(0).readRs2.pReg.poke(5.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
      c.io.hazard(0).readRs2.pendingWrites.expect(1.U)
    }
  }

  it should "correctly reflect concurrent updateColl and updateRS reads to different reg" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      setUpdate(c.io.updateColl, pReg = 42, incr = false, decr = true, doWrite = false, doRead = true)
      setUpdate(c.io.hazard(0).updateRS, pReg = 5, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.io.updateColl.success.expect(true.B)
      c.clock.step()

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs2.enable.poke(true.B)
      c.io.hazard(0).readRs2.pReg.poke(5.U)
      c.io.hazard(0).readRs1.pendingReads.expect(0.U)
      c.io.hazard(0).readRs2.pendingReads.expect(1.U)
    }
  }

  it should "correctly reflect concurrent updateColl and updateRS" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      // pendingReads(42) == 1

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 0)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 1)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 2)
      setUpdate(c.io.updateColl, pReg = 42, incr = false, decr = true, doWrite = false, doRead = true)
      // order matters!
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.io.updateColl.success.expect(true.B)
      c.clock.step()

      // pendingReads(42): 1 +  -1 (updateColl) + 3 (updateRS) = 3

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(3.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
    }
  }

  it should "unroll failed updateRS upon concurrent updateColl" in {
    val p = testParams()
    val m = p(MuonKey)
    simulate(new Scoreboard()(p)) { c =>
      reset(c)
      clearIO(c)

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true)
      c.io.hazard(0).updateRS.success.expect(true.B)
      c.clock.step()

      // pendingReads(42) == 2

      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 0)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 1)
      setUpdate(c.io.hazard(0).updateRS, pReg = 42, incr = true, decr = false, doWrite = false, doRead = true, rsi = 2)
      setUpdate(c.io.updateColl, pReg = 42, incr = false, decr = true, doWrite = false, doRead = true)
      // order matters!
      c.io.hazard(0).updateRS.success.expect(false.B)
      c.io.updateColl.success.expect(true.B)
      c.clock.step()

      // pendingReads(42): 2 +  -1 (updateColl) + 3 (updateRS, failed) = 1

      clearIO(c)

      c.io.hazard(0).readRs1.enable.poke(true.B)
      c.io.hazard(0).readRs1.pReg.poke(42.U)
      c.io.hazard(0).readRs1.pendingReads.expect(1.U)
      c.io.hazard(0).readRs1.pendingWrites.expect(0.U)
    }
  }
}
