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
            numWarps = numLanes,
            numLanes = numLanes,
            archLen = archLen,
            intPipe = IntPipeParams(numALULanes = numAluLanes),
            lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
          )
        )
      case MuonKey =>
        MuonCoreParams(
          numWarps = numLanes,
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

  private def zeroDecoded(inst: Decoded): Unit = {
    Decoder.essentialFields.foreach { field =>
      val idx = essentialFieldIndex(field)
      inst.essentials(idx).poke(0.U(field.width.W))
    }
  }

  private def pokeDecoded(inst: Decoded, field: DecodeField, value: BigInt): Unit = {
    val idx = essentialFieldIndex(field)
    inst.essentials(idx).poke(value.U(field.width.W))
  }

  private def driveRegisterData(vec: Vec[UInt], values: Seq[BigInt], archLen: Int): Unit = {
    val padded = values.padTo(vec.length, BigInt(0))
    vec.zip(padded).foreach { case (port, value) =>
      port.poke(value.U(archLen.W))
    }
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
    zeroDecoded(c.io.req.bits.uop.inst)
    pokeDecoded(c.io.req.bits.uop.inst, Opcode, op.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, F3, f3.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, F7, f7.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, Rd, rd)
    pokeDecoded(c.io.req.bits.uop.inst, HasRd, if (rd != 0) 1 else 0)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs1, if (in1.nonEmpty) 1 else 0)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs2, if (in2.nonEmpty) 1 else 0)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs3, 0)
    pokeDecoded(c.io.req.bits.uop.inst, UseIntPipe, 1)
    c.io.req.bits.uop.tmask.poke(tmask.U(c.io.req.bits.uop.tmask.getWidth.W))
    c.io.req.bits.uop.pc.poke(pc.U(archLen.W))
    c.io.req.bits.uop.wid.poke(0.U)
    driveRegisterData(c.io.req.bits.rs1Data.get, in1, archLen)
    driveRegisterData(c.io.req.bits.rs2Data.get, in2, archLen)
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
      expectedRegValid: Boolean,
      expectedSetPcValid: Boolean,
      expectedSetPcValue: Option[BigInt],
      holdCycles: Int
  )

  private def consumeResponse(
      c: ALUPipe,
      op: PipeOp,
      archLen: Int,
      totalPackets: Int
  )(nextAction: => Unit = ()): Unit = {
    c.io.req.valid.poke(false.B)
    var cycles = 0
    while (!c.io.resp.valid.peek().litToBoolean) {
      c.clock.step()
      cycles += 1
      require(cycles <= totalPackets + 4, "IntPipe response did not arrive in time")
    }

    op.expectedData.zipWithIndex.foreach { case (value, idx) =>
      if (((op.dataCheckMask >> idx) & 1) == 1)
        c.io.resp.bits.reg.get.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx mismatch")
    }

    if (op.expectedRegValid) {
      c.io.resp.bits.reg.get.valid.expect(true.B, s"${op.name} reg write unexpectedly invalid")
      c.io.resp.bits.reg.get.bits.rd.expect(op.rd.U)
      c.io.resp.bits.reg.get.bits.tmask.expect(op.expectedRespMask.U)
    } else {
      c.io.resp.bits.reg.get.valid.expect(false.B, s"${op.name} reg write unexpectedly valid")
    }

    c.io.resp.bits.sched.get.bits.setPC.valid.expect(op.expectedSetPcValid.B)
    op.expectedSetPcValue.foreach { value =>
      c.io.resp.bits.sched.get.bits.setPC.bits.expect(value.U(archLen.W))
    }

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

      val addOp = PipeOp(
        name = "add",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = addRd,
        in1 = addIn1,
        in2 = addIn2,
        pc = 0,
        reqMask = fullTmask,
        expectedData = addExpected,
        dataCheckMask = fullTmask,
        expectedRespMask = fullTmask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      val subRd = 9
      val subIn1 = Seq(BigInt(100), BigInt(80), BigInt(60), BigInt(40))
      val subIn2 = Seq(BigInt(1), BigInt(5), BigInt(10), BigInt(15))
      val subExpected = subIn1.zip(subIn2).map { case (a, b) => (a - b) & mask }

      val subOp = PipeOp(
        name = "sub",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = "b0100000".U,
        rd = subRd,
        in1 = subIn1,
        in2 = subIn2,
        pc = 0,
        reqMask = fullTmask,
        expectedData = subExpected,
        dataCheckMask = fullTmask,
        expectedRespMask = fullTmask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      driveRequest(c, addOp.opcode, addOp.f3, addOp.f7, addOp.rd, addOp.in1, addOp.in2, addOp.reqMask, archLen, addOp.pc)
      c.clock.step()
      consumeResponse(c, addOp, archLen, totalPackets) {
        driveRequest(c, subOp.opcode, subOp.f3, subOp.f7, subOp.rd, subOp.in1, subOp.in2, subOp.reqMask, archLen, subOp.pc)
      }

      consumeResponse(c, subOp, archLen, totalPackets)()
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

      val addOp = PipeOp(
        name = "add-partial",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = addRd,
        in1 = addIn1,
        in2 = addIn2,
        pc = 0,
        reqMask = activeMask,
        expectedData = addExpected,
        dataCheckMask = activeMask,
        expectedRespMask = activeMask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      val subRd = 4
      val subIn1 = Seq(BigInt(50), BigInt(40), BigInt(30), BigInt(20))
      val subIn2 = Seq(BigInt(1), BigInt(5), BigInt(10), BigInt(15))
      val subRaw = subIn1.zip(subIn2).map { case (a, b) => (a - b) & mask }
      val subExpected = subRaw

      val subOp = PipeOp(
        name = "sub-partial",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = "b0100000".U,
        rd = subRd,
        in1 = subIn1,
        in2 = subIn2,
        pc = 0,
        reqMask = activeMask,
        expectedData = subExpected,
        dataCheckMask = activeMask,
        expectedRespMask = activeMask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      driveRequest(c, addOp.opcode, addOp.f3, addOp.f7, addOp.rd, addOp.in1, addOp.in2, addOp.reqMask, archLen, addOp.pc)
      c.clock.step()
      consumeResponse(c, addOp, archLen, totalPackets) {
        driveRequest(c, subOp.opcode, subOp.f3, subOp.f7, subOp.rd, subOp.in1, subOp.in2, subOp.reqMask, archLen, subOp.pc)
      }

      consumeResponse(c, subOp, archLen, totalPackets)()
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

      val addOp = PipeOp(
        name = "add",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = addRd,
        in1 = addIn1,
        in2 = addIn2,
        pc = 0,
        reqMask = fullTmask,
        expectedData = addExpected,
        dataCheckMask = fullTmask,
        expectedRespMask = fullTmask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      val subOp = PipeOp(
        name = "sub",
        opcode = MuOpcode.OP.U,
        f3 = 0.U,
        f7 = "b0100000".U,
        rd = subRd,
        in1 = subIn1,
        in2 = subIn2,
        pc = 0,
        reqMask = fullTmask,
        expectedData = subExpected,
        dataCheckMask = fullTmask,
        expectedRespMask = fullTmask,
        expectedRegValid = true,
        expectedSetPcValid = false,
        expectedSetPcValue = None,
        holdCycles = 0
      )

      val beqRd = 7
      val beqIn1 = Seq(BigInt(3), BigInt(10), BigInt(3), BigInt(14))
      val beqIn2 = Seq(BigInt(3), BigInt(11), BigInt(3), BigInt(20))
      val beqPc = BigInt(0x1000)
      val beqPcWen = Seq(true, false, true, false)
      val beqTakenMask = maskFrom(beqPcWen)

      val beqOp = PipeOp(
        name = "beq",
        opcode = MuOpcode.BRANCH.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = beqRd,
        in1 = beqIn1,
        in2 = beqIn2,
        pc = beqPc,
        reqMask = fullTmask,
        expectedData = Seq.fill(numLanes)(BigInt(0)),
        dataCheckMask = BigInt(0),
        expectedRespMask = BigInt(0),
        expectedRegValid = false,
        expectedSetPcValid = beqTakenMask != 0,
        expectedSetPcValue = Some(beqPc),
        holdCycles = 0
      )

      val bneRd = 8
      val bneIn1 = Seq(BigInt(21), BigInt(22), BigInt(23), BigInt(24))
      val bneIn2 = Seq(BigInt(20), BigInt(22), BigInt(23), BigInt(30))
      val bnePc = BigInt(0x2000)
      val bneMask = BigInt("1101", 2)
      val bnePcWen = Seq(true, false, false, true)
      val bneTakenMask = maskFrom(bnePcWen)

      val bneOp = PipeOp(
        name = "bne",
        opcode = MuOpcode.BRANCH.U,
        f3 = 1.U,
        f7 = 0.U,
        rd = bneRd,
        in1 = bneIn1,
        in2 = bneIn2,
        pc = bnePc,
        reqMask = bneMask,
        expectedData = Seq.fill(numLanes)(BigInt(0)),
        dataCheckMask = BigInt(0),
        expectedRespMask = BigInt(0),
        expectedRegValid = false,
        expectedSetPcValid = bneTakenMask != 0,
        expectedSetPcValue = Some(bnePc),
        holdCycles = 0
      )

      val jalRd = 9
      val jalIn1 = Seq(BigInt(50), BigInt(60), BigInt(70), BigInt(80))
      val jalIn2 = Seq(BigInt(5), BigInt(6), BigInt(7), BigInt(8))
      val jalExpected = jalIn1.zip(jalIn2).map { case (a, b) => (a + b) & mask }

      val jalOp = PipeOp(
        name = "jal",
        opcode = MuOpcode.JAL.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = jalRd,
        in1 = jalIn1,
        in2 = jalIn2,
        pc = 0,
        reqMask = fullTmask,
        expectedData = jalExpected,
        dataCheckMask = fullTmask,
        expectedRespMask = BigInt(0),
        expectedRegValid = false,
        expectedSetPcValid = true,
        expectedSetPcValue = Some(jalExpected.head),
        holdCycles = 0
      )
      val jalrIn1 = Seq(BigInt(100), BigInt(120), BigInt(140), BigInt(160))
      val jalrIn2 = Seq(BigInt(1), BigInt(3), BigInt(5), BigInt(7))
      val jalrMask = BigInt("0101", 2)
      val jalrExpected = jalrIn1.zip(jalrIn2).map { case (a, b) => (a + b) & mask }
      val jalrRd = 10

      val jalrOp = PipeOp(
        name = "jalr",
        opcode = MuOpcode.JALR.U,
        f3 = 0.U,
        f7 = 0.U,
        rd = jalrRd,
        in1 = jalrIn1,
        in2 = jalrIn2,
        pc = 0,
        reqMask = jalrMask,
        expectedData = jalrExpected,
        dataCheckMask = jalrMask,
        expectedRespMask = BigInt(0),
        expectedRegValid = false,
        expectedSetPcValid = true,
        expectedSetPcValue = Some(jalrExpected.head),
        holdCycles = 0
      )

      driveRequest(c, addOp.opcode, addOp.f3, addOp.f7, addOp.rd, addOp.in1, addOp.in2, addOp.reqMask, archLen, addOp.pc)
      c.clock.step()
      consumeResponse(c, addOp, archLen, totalPackets) {
        driveRequest(c, subOp.opcode, subOp.f3, subOp.f7, subOp.rd, subOp.in1, subOp.in2, subOp.reqMask, archLen, subOp.pc)
      }

      consumeResponse(c, subOp, archLen, totalPackets) {
        driveRequest(c, beqOp.opcode, beqOp.f3, beqOp.f7, beqOp.rd, beqOp.in1, beqOp.in2, beqOp.reqMask, archLen, beqOp.pc)
      }

      consumeResponse(c, beqOp, archLen, totalPackets) {
        driveRequest(c, bneOp.opcode, bneOp.f3, bneOp.f7, bneOp.rd, bneOp.in1, bneOp.in2, bneOp.reqMask, archLen, bneOp.pc)
      }

      consumeResponse(c, bneOp, archLen, totalPackets) {
        driveRequest(c, jalOp.opcode, jalOp.f3, jalOp.f7, jalOp.rd, jalOp.in1, jalOp.in2, jalOp.reqMask, archLen, jalOp.pc)
      }

      consumeResponse(c, jalOp, archLen, totalPackets) {
        driveRequest(c, jalrOp.opcode, jalrOp.f3, jalrOp.f7, jalrOp.rd, jalrOp.in1, jalrOp.in2, jalrOp.reqMask, archLen, jalrOp.pc)
      }

      consumeResponse(c, jalrOp, archLen, totalPackets)()
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

      val jalBackpressureData = Seq(BigInt(20), BigInt(36), BigInt(52), BigInt(68)).map(_ & mask)

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
          expectedRegValid = true,
          expectedSetPcValid = false,
          expectedSetPcValue = None,
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
          expectedRegValid = true,
          expectedSetPcValid = false,
          expectedSetPcValue = None,
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
          expectedRegValid = true,
          expectedSetPcValid = false,
          expectedSetPcValue = None,
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
          expectedData = Seq.fill(numLanes)(BigInt(0)),
          dataCheckMask = BigInt(0),
          expectedRespMask = BigInt(0),
          expectedRegValid = false,
          expectedSetPcValid = beqRespMask != 0,
          expectedSetPcValue = Some(BigInt(0x400)),
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
          expectedData = jalBackpressureData,
          dataCheckMask = BigInt("1110", 2),
          expectedRespMask = BigInt(0),
          expectedRegValid = false,
          expectedSetPcValid = true,
          expectedSetPcValue = Some(jalBackpressureData(1)),
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
            c.io.resp.bits.reg.get.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx mismatch")
          }
        }
        if (op.expectedRegValid) {
          c.io.resp.bits.reg.get.valid.expect(true.B, s"${op.name} reg write unexpectedly invalid")
          c.io.resp.bits.reg.get.bits.rd.expect(op.rd.U)
          c.io.resp.bits.reg.get.bits.tmask.expect(op.expectedRespMask.U)
        } else {
          c.io.resp.bits.reg.get.valid.expect(false.B, s"${op.name} reg write unexpectedly valid")
        }
        c.io.resp.bits.sched.get.bits.setPC.valid.expect(op.expectedSetPcValid.B)
        op.expectedSetPcValue.foreach { value =>
          c.io.resp.bits.sched.get.bits.setPC.bits.expect(value.U(archLen.W))
        }

        for (_ <- 0 until op.holdCycles) {
          c.clock.step()
          c.io.resp.valid.expect(true.B, s"${op.name} response dropped under backpressure")
          op.expectedData.zipWithIndex.foreach { case (value, idx) =>
            if (((op.dataCheckMask >> idx) & 1) == 1) {
              c.io.resp.bits.reg.get.bits.data(idx).expect(value.U(archLen.W), s"${op.name} lane $idx changed under backpressure")
            }
          }
          if (op.expectedRegValid) {
            c.io.resp.bits.reg.get.valid.expect(true.B)
            c.io.resp.bits.reg.get.bits.tmask.expect(op.expectedRespMask.U)
            c.io.resp.bits.reg.get.bits.rd.expect(op.rd.U)
          } else {
            c.io.resp.bits.reg.get.valid.expect(false.B)
          }
          c.io.resp.bits.sched.get.bits.setPC.valid.expect(op.expectedSetPcValid.B)
          op.expectedSetPcValue.foreach { value =>
            c.io.resp.bits.sched.get.bits.setPC.bits.expect(value.U(archLen.W))
          }
        }

        c.io.resp.ready.poke(true.B)
        c.clock.step()
        c.io.resp.valid.expect(false.B)
      }
    }
  }

}
