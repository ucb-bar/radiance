package radiance

import chisel3._
import chiseltest._
import freechips.rocketchip.tile.{TileKey, TileParams}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import freechips.rocketchip.prci.ClockSinkParameters
import radiance.muon.backend.int.{IntPipeParams, MulDivPipe}
import radiance.muon.{LoadStoreUnitParams, MuOpcode, MuonCoreParams, MuonKey}

class MulDivPipeTest extends AnyFlatSpec with ChiselScalatestTester {
  private case class DummyTileParams(muon: MuonCoreParams) extends TileParams {
    val core: MuonCoreParams = muon
    val icache = None
    val dcache = None
    val btb = None
    val tileId = 0
    val blockerCtrlAddr = None
    val baseName = "muldiv_pipe_test_tile"
    val uniqueName = baseName
    val clockSinkParams = ClockSinkParameters()
  }

  private def testParams(
      numLanes: Int,
      numMulDivLanes: Int,
      archLen: Int = 32
  ): Parameters =
    Parameters.empty.alterPartial {
      case TileKey =>
        DummyTileParams(
          MuonCoreParams(
            numLanes = numLanes,
            archLen = archLen,
            intPipe = IntPipeParams(numMulDivLanes = numMulDivLanes),
            lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
          )
        )
      case MuonKey =>
        MuonCoreParams(
          numLanes = numLanes,
          archLen = archLen,
          intPipe = IntPipeParams(numMulDivLanes = numMulDivLanes),
          lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
        )
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
      holdCycles: Int
  )

  private def maskFrom(bits: Seq[Boolean]): BigInt =
    bits.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (flag, idx)) =>
      if (flag) acc | (BigInt(1) << idx) else acc
    }

  private def maskWidth(archLen: Int): BigInt =
    (BigInt(1) << archLen) - 1

  private def maskValue(value: BigInt, archLen: Int): BigInt =
    value & maskWidth(archLen)

  private def toSigned(value: BigInt, archLen: Int): BigInt = {
    val unsigned = maskValue(value, archLen)
    val signBit = BigInt(1) << (archLen - 1)
    if ((unsigned & signBit) != 0) unsigned - (BigInt(1) << archLen) else unsigned
  }

  private def computeExpected(
      f3: UInt,
      in1: Seq[BigInt],
      in2: Seq[BigInt],
      archLen: Int
  ): Seq[BigInt] = {
    val mask = maskWidth(archLen)
    in1.zip(in2).map { case (aRaw, bRaw) =>
      val aSigned = toSigned(aRaw, archLen)
      val bSigned = toSigned(bRaw, archLen)
      val aUnsigned = aRaw & mask
      val bUnsigned = bRaw & mask
      val result = f3.litValue.toInt match {
        case 0 => // MUL
          aSigned * bSigned
        case 1 => // MULH
          (aSigned * bSigned) >> archLen
        case 2 => // MULHSU
          (aSigned * bUnsigned) >> archLen
        case 3 => // MULHU
          (aUnsigned * bUnsigned) >> archLen
        case 4 => // DIV
          val divisor = bSigned
          if (divisor == 0) BigInt(-1)
          else if (aSigned == -(BigInt(1) << (archLen - 1)) && divisor == -1) aSigned
          else aSigned / divisor
        case 5 => // DIVU
          val divisor = bUnsigned
          if (divisor == 0) mask
          else aUnsigned / divisor
        case 6 => // REM
          val divisor = bSigned
          if (divisor == 0) aSigned
          else if (aSigned == -(BigInt(1) << (archLen - 1)) && divisor == -1) BigInt(0)
          else aSigned % divisor
        case 7 => // REMU
          val divisor = bUnsigned
          if (divisor == 0) aUnsigned
          else aUnsigned % divisor
        case other =>
          throw new IllegalArgumentException(s"Unsupported funct3 $other for MulDiv")
      }

      maskValue(result, archLen)
    }
  }

  private def driveRequest(
      c: MulDivPipe,
      op: PipeOp,
      archLen: Int
  ): Unit = {
    c.io.req.valid.poke(true.B)
    c.io.req.bits.op.poke(op.opcode)
    c.io.req.bits.f3.poke(op.f3)
    c.io.req.bits.f7.poke(op.f7)
    c.io.req.bits.rd.poke(op.rd.U)
    c.io.req.bits.pc.poke(op.pc.U(archLen.W))
    c.io.req.bits.tmask.poke(op.reqMask.U)
    op.in1.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in1(idx).poke(value.U(archLen.W))
    }
    op.in2.zipWithIndex.foreach { case (value, idx) =>
      c.io.req.bits.in2(idx).poke(value.U(archLen.W))
    }
  }

  private def waitForResponse(
      c: MulDivPipe,
      maxCycles: Int,
      opName: String
  ): Unit = {
    var cycles = 0
    while (!c.io.resp.valid.peek().litToBoolean) {
      c.clock.step()
      cycles += 1
      require(cycles <= maxCycles, s"$opName response did not arrive in time")
    }
  }

  private def checkResponse(
      c: MulDivPipe,
      op: PipeOp,
      archLen: Int
  ): Unit = {
    op.expectedData.zipWithIndex.foreach { case (value, idx) =>
      if (((op.dataCheckMask >> idx) & 1) == 1)
        c.io.resp.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx mismatch")
    }
    c.io.resp.bits.rd.expect(op.rd.U)
    c.io.resp.bits.pc_w_en.expect(false.B)
    c.io.resp.bits.tmask.expect(op.expectedRespMask.U)
  }

  private def finishResponse(c: MulDivPipe): Unit = {
    c.clock.step()
    c.io.resp.valid.expect(false.B)
  }

  it should "execute mul/div operations without backpressure" in {
    val numLanes = 4
    val numMulDivLanes = 2
    implicit val p: Parameters = testParams(numLanes, numMulDivLanes)

    test(new MulDivPipe) { c =>
      val archLen = p(MuonKey).archLen
      val fullMask = (BigInt(1) << numLanes) - 1
      val maxWait = 80

      val operandsA = Seq(BigInt(7), BigInt(-3) & maskWidth(archLen), BigInt(12345), BigInt(-200) & maskWidth(archLen))
      val operandsB = Seq(BigInt(5), BigInt(9), BigInt(-7) & maskWidth(archLen), BigInt(13))
      val operandsC = Seq(BigInt(0x12345678L), BigInt(0x80000000L), BigInt(0x7FFFFFFFL), BigInt(0x40000000L))
      val operandsD = Seq(BigInt(3), BigInt(0xFFFF_FFFFL), BigInt(2), BigInt(8))
      val remuIn2 = operandsD.map(x => if (x == 0) BigInt(1) else x)

      val ops = Seq(
        PipeOp(
          name = "mul",
          opcode = MuOpcode.OP.U,
          f3 = 0.U,
          f7 = "b0000001".U,
          rd = 5,
          in1 = operandsA,
          in2 = operandsB,
          pc = 0,
          reqMask = fullMask,
          expectedData = computeExpected(0.U, operandsA, operandsB, archLen),
          dataCheckMask = fullMask,
          expectedRespMask = fullMask,
          holdCycles = 0
        ),
        PipeOp(
          name = "mulh",
          opcode = MuOpcode.OP.U,
          f3 = 1.U,
          f7 = "b0000001".U,
          rd = 6,
          in1 = operandsC,
          in2 = operandsD,
          pc = 0,
          reqMask = BigInt("1101", 2),
          expectedData = computeExpected(1.U, operandsC, operandsD, archLen),
          dataCheckMask = BigInt("1101", 2),
          expectedRespMask = BigInt("1101", 2),
          holdCycles = 0
        ),
        PipeOp(
          name = "div",
          opcode = MuOpcode.OP.U,
          f3 = 4.U,
          f7 = "b0000001".U,
          rd = 7,
          in1 = operandsA,
          in2 = operandsB,
          pc = 0,
          reqMask = fullMask,
          expectedData = computeExpected(4.U, operandsA, operandsB, archLen),
          dataCheckMask = fullMask,
          expectedRespMask = fullMask,
          holdCycles = 0
        ),
        PipeOp(
          name = "remu",
          opcode = MuOpcode.OP.U,
          f3 = 7.U,
          f7 = "b0000001".U,
          rd = 8,
          in1 = operandsC,
          in2 = remuIn2,
          pc = 0,
          reqMask = BigInt("0111", 2),
          expectedData = computeExpected(7.U, operandsC, remuIn2, archLen),
          dataCheckMask = BigInt("0111", 2),
          expectedRespMask = BigInt("0111", 2),
          holdCycles = 0
        )
      )

      c.io.resp.ready.poke(true.B)

      ops.foreach { op =>
        while (!c.io.req.ready.peek().litToBoolean) { c.clock.step() }
        driveRequest(c, op, archLen)
        c.clock.step()
        c.io.req.valid.poke(false.B)

        waitForResponse(c, maxWait, op.name)
        checkResponse(c, op, archLen)
        finishResponse(c)
      }
    }
  }

  it should "handle mul/div responses under backpressure" in {
    val numLanes = 4
    val numMulDivLanes = 2
    implicit val p: Parameters = testParams(numLanes, numMulDivLanes)

    test(new MulDivPipe) { c =>
      val archLen = p(MuonKey).archLen
      val fullMask = (BigInt(1) << numLanes) - 1
      val maxWait = 80

      val mulhuIn1 = Seq(BigInt(10), BigInt(20), BigInt(30), BigInt(40))
      val mulhuIn2 = Seq(BigInt(3), BigInt(5), BigInt(7), BigInt(9))
      val divuIn1 = Seq(BigInt(-1) & maskWidth(archLen), BigInt(0x80000000L), BigInt(0x7FFFFFFFL), BigInt(15))
      val divuIn2 = Seq(BigInt(11), BigInt(3), BigInt(5), BigInt(2))
      val mulhsuIn1 = Seq(BigInt(0x12345678L), BigInt(0xCAFEBABEL), BigInt(0x13579BDDL), BigInt(0x2468ACE0L))
      val mulhsuIn2 = Seq(BigInt(0x11111111L), BigInt(0x01020304L), BigInt(0x22222222L), BigInt(3))
      val remIn1 = Seq(BigInt(0x80000000L), BigInt(0x7FFFFFFFL), BigInt(123456), BigInt(-654321) & maskWidth(archLen))
      val remIn2 = Seq(BigInt(-1) & maskWidth(archLen), BigInt(2), BigInt(789), BigInt(321))

      val ops = Seq(
        PipeOp(
          name = "mulhu",
          opcode = MuOpcode.OP.U,
          f3 = 3.U,
          f7 = "b0000001".U,
          rd = 10,
          in1 = mulhuIn1,
          in2 = mulhuIn2,
          pc = 0,
          reqMask = fullMask,
          expectedData = computeExpected(3.U, mulhuIn1, mulhuIn2, archLen),
          dataCheckMask = fullMask,
          expectedRespMask = fullMask,
          holdCycles = 0
        ),
        PipeOp(
          name = "divu",
          opcode = MuOpcode.OP.U,
          f3 = 5.U,
          f7 = "b0000001".U,
          rd = 11,
          in1 = divuIn1,
          in2 = divuIn2,
          pc = 0,
          reqMask = BigInt("1110", 2),
          expectedData = computeExpected(5.U, divuIn1, divuIn2, archLen),
          dataCheckMask = BigInt("1110", 2),
          expectedRespMask = BigInt("1110", 2),
          holdCycles = 2
        ),
        PipeOp(
          name = "mulhsu",
          opcode = MuOpcode.OP.U,
          f3 = 2.U,
          f7 = "b0000001".U,
          rd = 12,
          in1 = mulhsuIn1,
          in2 = mulhsuIn2,
          pc = 0,
          reqMask = BigInt("1011", 2),
          expectedData = computeExpected(2.U, mulhsuIn1, mulhsuIn2, archLen),
          dataCheckMask = BigInt("1011", 2),
          expectedRespMask = BigInt("1011", 2),
          holdCycles = 1
        ),
        PipeOp(
          name = "rem",
          opcode = MuOpcode.OP.U,
          f3 = 6.U,
          f7 = "b0000001".U,
          rd = 13,
          in1 = remIn1,
          in2 = remIn2,
          pc = 0,
          reqMask = fullMask,
          expectedData = computeExpected(6.U, remIn1, remIn2, archLen),
          dataCheckMask = fullMask,
          expectedRespMask = fullMask,
          holdCycles = 3
        )
      )

      ops.foreach { op =>
        while (!c.io.req.ready.peek().litToBoolean) { c.clock.step() }
        driveRequest(c, op, archLen)
        c.clock.step()
        c.io.req.valid.poke(false.B)

        if (op.holdCycles > 0) c.io.resp.ready.poke(false.B) else c.io.resp.ready.poke(true.B)

        waitForResponse(c, maxWait, op.name)
        checkResponse(c, op, archLen)

        for (_ <- 0 until op.holdCycles) {
          c.clock.step()
          c.io.resp.valid.expect(true.B, s"${op.name} response dropped under backpressure")
          checkResponse(c, op, archLen)
        }

        c.io.resp.ready.poke(true.B)
        finishResponse(c)
      }
    }
  }
}
