package radiance

import chisel3._
import chiseltest._
import freechips.rocketchip.tile.{TileKey, TileParams}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.prci.ClockSinkParameters
import radiance.muon.backend.int.{ALUPipe, IntPipeParams}
import radiance.muon.{Decoder, DecodeField, Decoded, F3, F7, HasRd, HasRs1, HasRs2, HasRs3, LoadStoreUnitParams, MuOpcode, MuonCoreParams, MuonKey, Opcode, Rd, UseIntPipe}

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

  private val essentialFieldIndex = Decoder.essentialFields.zipWithIndex.toMap

  private def zeroIbuf(inst: Decoded): Unit = {
    Decoder.essentialFields.foreach { field =>
      val idx = essentialFieldIndex(field)
      inst.essentials(idx).poke(0.U(field.width.W))
    }
  }

  private def pokeIbuf(inst: Decoded, field: DecodeField, value: BigInt): Unit = {
    val idx = essentialFieldIndex(field)
    inst.essentials(idx).poke(value.U(field.width.W))
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
    zeroIbuf(c.io.req.bits.ibuf.inst)
    pokeIbuf(c.io.req.bits.ibuf.inst, Opcode, op.litValue)
    pokeIbuf(c.io.req.bits.ibuf.inst, F3, f3.litValue)
    pokeIbuf(c.io.req.bits.ibuf.inst, F7, f7.litValue)
    pokeIbuf(c.io.req.bits.ibuf.inst, Rd, rd)
    c.io.req.bits.ibuf.tmask.poke(tmask.U(c.io.req.bits.ibuf.tmask.getWidth.W))
    c.io.req.bits.ibuf.pc.poke(pc.U(archLen.W))
    c.io.req.bits.ibuf.wid.poke(0.U)
    in1.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in1(idx).poke(value.U(archLen.W))
    }
    in2.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in2(idx).poke(value.U(archLen.W))
    }
  }


  private case class PipeOp(
      name: String,
      opcode: UInt,
      f3: UInt,
      f7: UInt,
      rd: Int,
      in1: Seq[BigInt],
      in2: Seq[BigInt],
      pc: BigInt,
      reqMask: BigInt,
      expectedData: Seq[BigInt],
      dataCheckMask: BigInt,
      expectedRespMask: BigInt,
      expectedPcWen: Boolean,
      holdCycles: Int
  )

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

      driveRequest(c, MuOpcode.OP.U, 0.U, 0.U, addRd, addIn1, addIn2, fullTmask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP.U,
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

      driveRequest(c, MuOpcode.OP.U, 0.U, 0.U, addRd, addIn1, addIn2, activeMask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, activeMask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(activeMask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP.U,
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

      driveRequest(c, MuOpcode.OP.U, 0.U, 0.U, addRd, addIn1, addIn2, fullTmask, archLen)
      c.clock.step()
      consumeResponse(c, addExpected, addRd, archLen, totalPackets, fullTmask,
        expectedPcWen = Some(false),
        expectedRespTmask = Some(fullTmask)
      ) {
        driveRequest(
          c,
          MuOpcode.OP.U,
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
          MuOpcode.BRANCH.U,
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
          MuOpcode.BRANCH.U,
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
          MuOpcode.JAL.U,
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
          MuOpcode.JALR.U,
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

  it should "hold responses under output backpressure" in {
    val numLanes = 4
    val numAluLanes = 2
    implicit val p: Parameters = testParams(numLanes, numAluLanes)

    test(new ALUPipe) { c =>
      val archLen = p(MuonKey).archLen
      val mask = (BigInt(1) << archLen) - 1
      val fullTmask = (BigInt(1) << numLanes) - 1
      val totalPackets = numLanes / numAluLanes

      val beqMaskBools = Seq(true, false, true, false)
      val beqRespMask = maskFrom(beqMaskBools)

      val ops = Seq(
        PipeOp(
          name = "add",
          opcode = MuOpcode.OP.U,
          f3 = 0.U,
          f7 = 0.U,
          rd = 12,
          in1 = Seq(BigInt(3), BigInt(7), BigInt(11), BigInt(15)),
          in2 = Seq(BigInt(1), BigInt(2), BigInt(3), BigInt(4)),
          pc = 0,
          reqMask = fullTmask,
          expectedData = Seq(BigInt(4), BigInt(9), BigInt(14), BigInt(19)).map(_ & mask),
          dataCheckMask = fullTmask,
          expectedRespMask = fullTmask,
          expectedPcWen = false,
          holdCycles = 0
        ),
        PipeOp(
          name = "sub",
          opcode = MuOpcode.OP.U,
          f3 = 0.U,
          f7 = "b0100000".U,
          rd = 13,
          in1 = Seq(BigInt(50), BigInt(40), BigInt(30), BigInt(20)),
          in2 = Seq(BigInt(5), BigInt(4), BigInt(3), BigInt(2)),
          pc = 0,
          reqMask = fullTmask,
          expectedData = Seq(BigInt(45), BigInt(36), BigInt(27), BigInt(18)).map(_ & mask),
          dataCheckMask = fullTmask,
          expectedRespMask = fullTmask,
          expectedPcWen = false,
          holdCycles = 2
        ),
        PipeOp(
          name = "xor",
          opcode = MuOpcode.OP.U,
          f3 = 4.U,
          f7 = 0.U,
          rd = 14,
          in1 = Seq(BigInt(0xAAAA), BigInt(0x5555), BigInt(0x0F0F), BigInt(0xF0F0)),
          in2 = Seq(BigInt(0x1111), BigInt(0x2222), BigInt(0x3333), BigInt(0x4444)),
          pc = 0,
          reqMask = BigInt("0101", 2),
          expectedData = Seq(BigInt(0xBBBB), BigInt(0x7777), BigInt(0x3C3C), BigInt(0xB4B4)).map(_ & mask),
          dataCheckMask = BigInt("0101", 2),
          expectedRespMask = BigInt("0101", 2),
          expectedPcWen = false,
          holdCycles = 0
        ),
        PipeOp(
          name = "beq",
          opcode = MuOpcode.BRANCH.U,
          f3 = 0.U,
          f7 = 0.U,
          rd = 15,
          in1 = Seq(BigInt(9), BigInt(5), BigInt(9), BigInt(7)),
          in2 = Seq(BigInt(9), BigInt(8), BigInt(9), BigInt(1)),
          pc = BigInt(0x400),
          reqMask = fullTmask,
          expectedData = Seq.fill(numLanes)(BigInt(0x400)),
          dataCheckMask = beqRespMask,
          expectedRespMask = beqRespMask,
          expectedPcWen = true,
          holdCycles = 3
        ),
        PipeOp(
          name = "jal",
          opcode = MuOpcode.JAL.U,
          f3 = 0.U,
          f7 = 0.U,
          rd = 16,
          in1 = Seq(BigInt(16), BigInt(32), BigInt(48), BigInt(64)),
          in2 = Seq(BigInt(4), BigInt(4), BigInt(4), BigInt(4)),
          pc = BigInt(0x800),
          reqMask = BigInt("1110", 2),
          expectedData = Seq(BigInt(20), BigInt(36), BigInt(52), BigInt(68)).map(_ & mask),
          dataCheckMask = BigInt("1110", 2),
          expectedRespMask = BigInt("1110", 2),
          expectedPcWen = true,
          holdCycles = 2
        )
      )

      c.io.resp.ready.poke(true.B)

      ops.foreach { op =>
        while (!c.io.req.ready.peek().litToBoolean) { c.clock.step() }
        driveRequest(c, op.opcode, op.f3, op.f7, op.rd, op.in1, op.in2, op.reqMask, archLen, op.pc)
        c.clock.step()
        c.io.req.valid.poke(false.B)

        if (op.holdCycles > 0) c.io.resp.ready.poke(false.B) else c.io.resp.ready.poke(true.B)

        var waitCycles = 0
        while (!c.io.resp.valid.peek().litToBoolean) {
          c.clock.step()
          waitCycles += 1
          require(waitCycles <= totalPackets + 4, s"${op.name} response did not arrive in time")
        }

        op.expectedData.zipWithIndex.foreach { case (value, idx) =>
          if (((op.dataCheckMask >> idx) & 1) == 1) {
            c.io.resp.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx mismatch")
          }
        }
        c.io.resp.bits.rd.expect(op.rd.U)
        c.io.resp.bits.pc_w_en.expect(op.expectedPcWen.B)
        c.io.resp.bits.tmask.expect(op.expectedRespMask.U)

        for (_ <- 0 until op.holdCycles) {
          c.clock.step()
          c.io.resp.valid.expect(true.B, s"${op.name} response dropped under backpressure")
          op.expectedData.zipWithIndex.foreach { case (value, idx) =>
            if (((op.dataCheckMask >> idx) & 1) == 1) {
              c.io.resp.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx changed under backpressure")
            }
          }
          c.io.resp.bits.pc_w_en.expect(op.expectedPcWen.B)
          c.io.resp.bits.tmask.expect(op.expectedRespMask.U)
        }

        c.io.resp.ready.poke(true.B)
        c.clock.step()
        c.io.resp.valid.expect(false.B)
      }
    }
  }

}
