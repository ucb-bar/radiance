package radiance

import chisel3._
import chiseltest._
import freechips.rocketchip.tile.{TileKey, TileParams}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.prci.ClockSinkParameters
import radiance.muon.backend.int.{ALUPipe, IntPipeParams}
import radiance.muon.{LoadStoreUnitParams, MuOpcode, MuonCoreParams, MuonKey}

class ALUPipeTest extends AnyFlatSpec with ChiselScalatestTester {
  private case class DummyTileParams(muon: MuonCoreParams) extends TileParams {
    val core: MuonCoreParams = muon
    val icache = None
    val dcache = None
    val btb = None
    val tileId = 0
    val blockerCtrlAddr = None
    val baseName = "integer_pipe_test_tile"
    val uniqueName = baseName
    val clockSinkParams = ClockSinkParameters()
  }

  private def testParams(
      numLanes: Int,
      numAluLanes: Int,
      archLen: Int = 32
  ): Parameters =
    Parameters.empty.alterPartial {
      case TileKey =>
        DummyTileParams(
          MuonCoreParams(
            numLanes = numLanes,
            archLen = archLen,
            intPipe = IntPipeParams(numALULanes = numAluLanes),
            lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
          )
        )
      case MuonKey =>
        MuonCoreParams(
          numLanes = numLanes,
          archLen = archLen,
          intPipe = IntPipeParams(numALULanes = numAluLanes),
          lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
        )
    }

  behavior of "ALUPipe"

  private def maskFrom(bits: Seq[Boolean]): BigInt =
    bits.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (flag, idx)) =>
      if (flag) acc | (BigInt(1) << idx) else acc
    }

  private def driveRequest(
      c: ALUPipe,
      op: UInt,
      f3: UInt,
      f7: UInt,
      rd: Int,
      in1: Seq[BigInt],
      in2: Seq[BigInt],
      tmask: BigInt,
      archLen: Int,
      pc: BigInt = 0
  ): Unit = {
    c.io.req.valid.poke(true.B)
    c.io.req.bits.op.poke(op)
    c.io.req.bits.f3.poke(f3)
    c.io.req.bits.f7.poke(f7)
    c.io.req.bits.rd.poke(rd.U)
    c.io.req.bits.tmask.poke(tmask.U)
    c.io.req.bits.pc.poke(pc.U(archLen.W))
    in1.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in1(idx).poke(value.U(archLen.W))
    }
    in2.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in2(idx).poke(value.U(archLen.W))
    }
  }

  private def consumeResponse(
      c: ALUPipe,
      expected: Seq[BigInt],
      rd: Int,
      archLen: Int,
      totalPackets: Int,
      tmask: BigInt,
      expectedPcWen: Option[Boolean] = None,
      expectedRespTmask: Option[BigInt] = None
  )(nextAction: => Unit = ()): Unit = {
    c.io.req.valid.poke(false.B)
    var cycles = 0
    while (!c.io.resp.valid.peek().litToBoolean) {
      c.clock.step()
      cycles += 1
      require(cycles <= totalPackets + 4, "IntPipe response did not arrive in time")
    }

    expected.zipWithIndex.foreach { case (value, idx) =>
      if (((tmask >> idx) & 1) == 1)
        c.io.resp.bits.data(idx).expect(value.U(archLen.W))
    }
    expectedPcWen.foreach { flag =>
      c.io.resp.bits.pc_w_en.expect(flag.B)
    }
    expectedRespTmask.foreach { value =>
      c.io.resp.bits.tmask.expect(value.U)
    }
    c.io.resp.bits.rd.expect(rd.U)
    c.io.req.ready.expect(true.B)
    nextAction
    c.clock.step()
    c.io.resp.valid.expect(false.B)
  }

  it should "execute ADD then SUB with all lanes active" in {
    val numLanes = 4
    val numAluLanes = 2
    implicit val p: Parameters = testParams(numLanes, numAluLanes)

    test(new ALUPipe) { c =>
      val archLen = p(MuonKey).archLen
      val mask = (BigInt(1) << archLen) - 1
      val fullTmask = (BigInt(1) << numLanes) - 1
      val totalPackets = numLanes / numAluLanes

      c.io.resp.ready.poke(true.B)

      // ADD
      val addRd = 7
      val addIn1 = Seq(BigInt(10), BigInt(20), BigInt(30), BigInt(40))
      val addIn2 = Seq(BigInt(1), BigInt(2), BigInt(3), BigInt(4))
      val addExpected = addIn1.zip(addIn2).map { case (a, b) => (a + b) & mask }

      val subRd = 9
      val subIn1 = Seq(BigInt(100), BigInt(80), BigInt(60), BigInt(40))
      val subIn2 = Seq(BigInt(1), BigInt(5), BigInt(10), BigInt(15))
      val subExpected = subIn1.zip(subIn2).map { case (a, b) => (a - b) & mask }

      driveRequest(c, MuOpcode.OP, 0.U, 0.U, addRd, addIn1, addIn2, fullTmask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP,
          0.U,
          "b0100000".U,
          subRd,
          subIn1,
          subIn2,
          fullTmask,
          archLen
        )
      }

      consumeResponse(c, subExpected, subRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      )()
    }
  }

  it should "execute ADD then SUB with a partial lane tmask" in {
    val numLanes = 4
    val numAluLanes = 2
    implicit val p: Parameters = testParams(numLanes, numAluLanes)

    test(new ALUPipe) { c =>
      val archLen = p(MuonKey).archLen
      val mask = (BigInt(1) << archLen) - 1
      val totalPackets = numLanes / numAluLanes
      val activeMask = BigInt("1010", 2) // lanes 1 and 3 active

      c.io.resp.ready.poke(true.B)

      // ADD with partial mask
      val addRd = 3
      val addIn1 = Seq(BigInt(5), BigInt(6), BigInt(7), BigInt(8))
      val addIn2 = Seq(BigInt(1), BigInt(2), BigInt(3), BigInt(4))
      val addRaw = addIn1.zip(addIn2).map { case (a, b) => (a + b) & mask }
      val addExpected = addRaw

      val subRd = 4
      val subIn1 = Seq(BigInt(50), BigInt(40), BigInt(30), BigInt(20))
      val subIn2 = Seq(BigInt(1), BigInt(5), BigInt(10), BigInt(15))
      val subRaw = subIn1.zip(subIn2).map { case (a, b) => (a - b) & mask }
      val subExpected = subRaw

      driveRequest(c, MuOpcode.OP, 0.U, 0.U, addRd, addIn1, addIn2, activeMask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, activeMask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(activeMask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP,
          0.U,
          "b0100000".U,
          subRd,
          subIn1,
          subIn2,
          activeMask,
          archLen
        )
      }

      consumeResponse(c, subExpected, subRd, archLen, totalPackets, activeMask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(activeMask)
      )()
    }
  }

  it should "handle mixed arithmetic, branch, and jump operations across lanes" in {
    val numLanes = 4
    val numAluLanes = 2
    implicit val p: Parameters = testParams(numLanes, numAluLanes)

    test(new ALUPipe) { c =>
      val archLen = p(MuonKey).archLen
      val mask = (BigInt(1) << archLen) - 1
      val fullTmask = (BigInt(1) << numLanes) - 1
      val totalPackets = numLanes / numAluLanes

      c.io.resp.ready.poke(true.B)

      // ADD
      val addRd = 5
      val addIn1 = Seq(BigInt(4), BigInt(8), BigInt(12), BigInt(16))
      val addIn2 = Seq(BigInt(1), BigInt(3), BigInt(5), BigInt(7))
      val addExpected = addIn1.zip(addIn2).map { case (a, b) => (a + b) & mask }

      val subRd = 6
      val subIn1 = Seq(BigInt(40), BigInt(30), BigInt(20), BigInt(10))
      val subIn2 = Seq(BigInt(2), BigInt(4), BigInt(6), BigInt(8))
      val subExpected = subIn1.zip(subIn2).map { case (a, b) => (a - b) & mask }

      val beqRd = 7
      val beqIn1 = Seq(BigInt(3), BigInt(10), BigInt(3), BigInt(14))
      val beqIn2 = Seq(BigInt(3), BigInt(11), BigInt(3), BigInt(20))
      val beqPc = BigInt(0x1000)
      val beqExpected = Seq.fill(numLanes)(beqPc)
      val beqPcWen = Seq(true, false, true, false) // lanes 0 & 2 equal

      val bneRd = 8
      val bneIn1 = Seq(BigInt(21), BigInt(22), BigInt(23), BigInt(24))
      val bneIn2 = Seq(BigInt(20), BigInt(22), BigInt(23), BigInt(30))
      val bnePc = BigInt(0x2000)
      val bneExpected = Seq.fill(numLanes)(bnePc)
      val bneMask = BigInt("1101", 2) // lanes 0,2,3 active
      val bnePcWen = Seq(true, false, false, true) // lane2 equal -> false

      val jalRd = 9
      val jalIn1 = Seq(BigInt(50), BigInt(60), BigInt(70), BigInt(80))
      val jalIn2 = Seq(BigInt(5), BigInt(6), BigInt(7), BigInt(8))
      val jalExpected = jalIn1.zip(jalIn2).map { case (a, b) => (a + b) & mask }

      val jalrRd = 10
      val jalrIn1 = Seq(BigInt(100), BigInt(120), BigInt(140), BigInt(160))
      val jalrIn2 = Seq(BigInt(1), BigInt(3), BigInt(5), BigInt(7))
      val jalrMask = BigInt("0101", 2) // lanes 0 and 2 active
      val jalrExpected = jalrIn1.zip(jalrIn2).map { case (a, b) => (a + b) & mask }

      driveRequest(c, MuOpcode.OP, 0.U, 0.U, addRd, addIn1, addIn2, fullTmask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP,
          0.U,
          "b0100000".U,
          subRd,
          subIn1,
          subIn2,
          fullTmask,
          archLen
        )
      }

      consumeResponse(c, subExpected, subRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.BRANCH,
          0.U,
          0.U,
          beqRd,
          beqIn1,
          beqIn2,
          fullTmask,
          archLen,
          beqPc
        )
      }

      consumeResponse(
        c,
        beqExpected,
        beqRd,
        archLen,
        totalPackets,
        fullTmask,
        expectedPcWen = Some(true),
        expectedRespTmask = Some(maskFrom(beqPcWen))
      ) {
        driveRequest(
          c,
          MuOpcode.BRANCH,
          1.U,
          0.U,
          bneRd,
          bneIn1,
          bneIn2,
          bneMask,
          archLen,
          bnePc
        )
      }

      consumeResponse(
        c,
        bneExpected,
        bneRd,
        archLen,
        totalPackets,
        bneMask,
        expectedPcWen = Some(true),
        expectedRespTmask = Some(maskFrom(bnePcWen))
      ) {
        driveRequest(
          c,
          MuOpcode.JAL,
          0.U,
          0.U,
          jalRd,
          jalIn1,
          jalIn2,
          fullTmask,
          archLen
        )
      }

      consumeResponse(
        c,
        jalExpected,
        jalRd,
        archLen,
        totalPackets,
        fullTmask,
        expectedPcWen = Some(true),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.JALR,
          0.U,
          0.U,
          jalrRd,
          jalrIn1,
          jalrIn2,
          jalrMask,
          archLen
        )
      }

      consumeResponse(
        c,
        jalrExpected,
        jalrRd,
        archLen,
        totalPackets,
        jalrMask,
        expectedPcWen = Some(true),
        expectedRespTmask = Some(jalrMask)
      )()
    }
  }
}
