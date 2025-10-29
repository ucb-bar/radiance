package radiance

import chisel3._
import chiseltest._
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.tile.{TileKey, TileParams}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import radiance.muon._
import radiance.muon.backend.fp._

class FPPipeTest extends AnyFlatSpec with ChiselScalatestTester {
  private case class DummyTileParams(muon: MuonCoreParams) extends TileParams {
    val core: MuonCoreParams = muon
    val icache = None
    val dcache = None
    val btb = None
    val tileId = 0
    val blockerCtrlAddr = None
    val baseName = "fp_pipe_test_tile"
    val uniqueName = baseName
    val clockSinkParams = ClockSinkParameters()
  }

  private def testParams(
      numLanes: Int,
      numFP32Lanes: Int,
      archLen: Int = 32
  ): Parameters = {
    val muonParams = MuonCoreParams(
      numWarps = numLanes,
      numLanes = numLanes,
      archLen = archLen,
      fpPipe = FPPipeParams(numFP32Lanes = numFP32Lanes),
      lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
    )

    Parameters.empty.alterPartial {
      case TileKey => DummyTileParams(muonParams)
      case MuonKey => muonParams
    }
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

  private def pokeLaneVec(vec: Vec[UInt], values: Seq[BigInt], archLen: Int): Unit = {
    val padded = values.padTo(vec.length, BigInt(0))
    vec.zip(padded).foreach { case (port, value) =>
      port.poke(value.U(archLen.W))
    }
  }

  private def roundingModeFrom(bits: UInt): FPRoundingMode.Type = {
    val mode = bits.litValue.toInt
    FPRoundingMode.all.find(_.litValue == mode)
      .getOrElse(throw new IllegalArgumentException(s"Unsupported rounding mode $mode"))
  }

  private def packLanes(lanes: Seq[BigInt], laneWidth: Int): BigInt = {
    lanes.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (value, idx)) =>
      acc | (value << (idx * laneWidth))
    }
  }

  private def expandedMask(tmask: Int, numFP32Lanes: Int): BigInt = {
    val laneEnables = (0 until numFP32Lanes).map(i => ((tmask >> i) & 0x1) == 1)
    laneEnables.reverse.foldLeft(BigInt(0)) { (acc, active) =>
      (acc << 2) | (if (active) 0x3 else 0x0)
    }
  }

  private case class FPRequestSpec(
      name: String,
      opcode: UInt,
      f3: UInt,
      f7: UInt,
      rs1Idx: Int,
      rs2Field: Int,
      rs3Idx: Option[Int],
      rd: Int,
      rs1Lanes: Seq[BigInt],
      rs2Lanes: Seq[BigInt],
      rs3Lanes: Seq[BigInt],
      tmask: Int,
      expectedOp: FPUOp.Type,
      expectedSrcFmt: FPFormat.Type,
      expectedDstFmt: FPFormat.Type,
      expectedResultLanes: Seq[BigInt],
      responseLatency: Int
  )

  private case class TestEnv(archLen: Int, numLanes: Int, numFP32Lanes: Int)

  private def issueAndCheck(
      c: FP32Pipe,
      spec: FPRequestSpec,
      env: TestEnv,
      postResponseGap: Int
  ): Unit = {
    val archLen = env.archLen
    val laneWidth = archLen
    val packedRs1 = packLanes(spec.rs1Lanes.take(env.numFP32Lanes), laneWidth)
    val packedRs2 = packLanes(spec.rs2Lanes.take(env.numFP32Lanes), laneWidth)
    val packedRs3 = packLanes(spec.rs3Lanes.take(env.numFP32Lanes), laneWidth)
    val packedResult = packLanes(spec.expectedResultLanes.take(env.numFP32Lanes), laneWidth)

    zeroDecoded(c.io.req.bits.uop.inst)
    pokeDecoded(c.io.req.bits.uop.inst, Opcode, spec.opcode.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, F3, spec.f3.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, F7, spec.f7.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, Rd, spec.rd)
    pokeDecoded(c.io.req.bits.uop.inst, Rs1, spec.rs1Idx)
    pokeDecoded(c.io.req.bits.uop.inst, Rs2, spec.rs2Field)
    pokeDecoded(c.io.req.bits.uop.inst, HasRd, if (spec.rd != 0) 1 else 0)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs1, 1)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs2, 1)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs3, spec.rs3Idx.fold(0)(_ => 1))
    pokeDecoded(c.io.req.bits.uop.inst, UseFPPipe, 1)
    spec.rs3Idx.foreach(rs3 => pokeDecoded(c.io.req.bits.uop.inst, Rs3, rs3))

    c.io.req.bits.uop.tmask.poke(spec.tmask.U(c.io.req.bits.uop.tmask.getWidth.W))
    c.io.req.bits.uop.pc.poke(0.U(archLen.W))
    c.io.req.bits.uop.wid.poke(0.U)

    pokeLaneVec(c.io.req.bits.rs1Data.get, spec.rs1Lanes, archLen)
    pokeLaneVec(c.io.req.bits.rs2Data.get, spec.rs2Lanes, archLen)
    c.io.req.bits.rs3Data.foreach(rs3 => pokeLaneVec(rs3, spec.rs3Lanes, archLen))

    c.io.req.valid.poke(true.B)
    var reqWaitCycles = 0
    while (!c.io.req.ready.peek().litToBoolean) {
      c.clock.step()
      reqWaitCycles += 1
      require(reqWaitCycles <= 10, s"${spec.name}: request was not accepted in time")
    }
    var cvReqSeen = false
    var cvReqCycles = 0
    def checkCvReq(): Unit = {
      c.cvFPUIF.resp.valid.poke(false.B)
      if (!cvReqSeen && c.cvFPUIF.req.valid.peek().litToBoolean) {
        c.cvFPUIF.req.bits.op.expect(spec.expectedOp, s"${spec.name}: op mismatch")
        c.cvFPUIF.req.bits.srcFormat.expect(spec.expectedSrcFmt, s"${spec.name}: src fmt mismatch")
        c.cvFPUIF.req.bits.dstFormat.expect(spec.expectedDstFmt, s"${spec.name}: dst fmt mismatch")
        c.cvFPUIF.req.bits.roundingMode.expect(roundingModeFrom(spec.f3), s"${spec.name}: rounding mode mismatch")
        c.cvFPUIF.req.bits.tag.expect(spec.rd.U, s"${spec.name}: tag mismatch")
        c.cvFPUIF.req.bits.simdMask.expect(expandedMask(spec.tmask, env.numFP32Lanes).U, s"${spec.name}: SIMD mask mismatch")
        c.cvFPUIF.req.bits.operands(0).expect(packedRs1.U, s"${spec.name}: operand0 mismatch")
        c.cvFPUIF.req.bits.operands(1).expect(packedRs2.U, s"${spec.name}: operand1 mismatch")
        c.cvFPUIF.req.bits.operands(2).expect(packedRs3.U, s"${spec.name}: operand2 mismatch")
        cvReqSeen = true
      }
    }

    checkCvReq()

    def stepAndCheck(): Unit = {
      c.clock.step()
      cvReqCycles += 1
      c.io.req.valid.poke(false.B)
      checkCvReq()
      if (!cvReqSeen) {
        require(cvReqCycles <= 10, s"${spec.name}: CVFPU request did not fire")
      }
    }

    stepAndCheck()
    while (!cvReqSeen) {
      stepAndCheck()
    }

    for (_ <- 0 until spec.responseLatency) {
      c.cvFPUIF.resp.valid.poke(false.B)
      c.clock.step()
    }

    c.cvFPUIF.resp.bits.result.poke(packedResult.U)
    c.cvFPUIF.resp.bits.status.poke(0.U)
    c.cvFPUIF.resp.bits.tag.poke(spec.rd.U)
    c.cvFPUIF.resp.valid.poke(true.B)
    c.clock.step()
    c.cvFPUIF.resp.valid.poke(false.B)

    var respCycles = 0
    var respSeen = false
    while (!respSeen) {
      if (c.io.resp.valid.peek().litToBoolean) {
        c.io.resp.bits.reg.get.valid.expect(true.B, s"${spec.name}: expected register write")
        c.io.resp.bits.reg.get.bits.rd.expect(spec.rd.U, s"${spec.name}: rd mismatch")
        c.io.resp.bits.reg.get.bits.tmask.expect(spec.tmask.U, s"${spec.name}: tmask mismatch")
        spec.expectedResultLanes.take(env.numFP32Lanes).zipWithIndex.foreach { case (value, idx) =>
          c.io.resp.bits.reg.get.bits.data(idx).expect(value.U(archLen.W), s"${spec.name}: lane $idx data mismatch")
        }
        respSeen = true
      }
      c.clock.step()
      respCycles += 1
      require(respCycles <= 20, s"${spec.name}: pipeline response not observed")
    }

    for (_ <- 0 until postResponseGap) {
      c.clock.step()
    }
  }

  behavior of "FP32Pipe"

  it should "handle back-to-back FP32 requests" in {
    implicit val p: Parameters = testParams(numLanes = 4, numFP32Lanes = 4)
    test(new FP32Pipe) { c =>
      val muon = p(MuonKey)
      val env = TestEnv(muon.archLen, muon.numLanes, muon.fpPipe.numFP32Lanes)

      c.io.resp.ready.poke(true.B)
      c.cvFPUIF.req.ready.poke(true.B)
      c.cvFPUIF.resp.valid.poke(false.B)

      val specs = Seq(
        FPRequestSpec(
          name = "fadd.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0000000".U,
          rs1Idx = 1,
          rs2Field = 2,
          rs3Idx = None,
          rd = 8,
          rs1Lanes = Seq(0x3f800000L, 0x40800000L, 0x40a00000L, 0x40c00000L),
          rs2Lanes = Seq(0x3f800000L, 0x3f800000L, 0x3f000000L, 0x3f000000L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.ADD,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40000000L, 0x40a00000L, 0x40c00000L, 0x40e00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fsub.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0000100".U,
          rs1Idx = 3,
          rs2Field = 4,
          rs3Idx = None,
          rd = 9,
          rs1Lanes = Seq(0x41000000L, 0x40f00000L, 0x40e00000L, 0x40d00000L),
          rs2Lanes = Seq(0x3f800000L, 0x3f000000L, 0x3f000000L, 0x3f000000L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.SUB,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40800000L, 0x40a00000L, 0x40c00000L, 0x40e00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fmadd.s",
          opcode = MuOpcode.MADD.U,
          f3 = "b000".U,
          f7 = "b0000000".U,
          rs1Idx = 5,
          rs2Field = 6,
          rs3Idx = Some(7),
          rd = 10,
          rs1Lanes = Seq(0x3f800000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs2Lanes = Seq(0x3f800000L, 0x40400000L, 0x3f800000L, 0x40400000L),
          rs3Lanes = Seq(0x3f800000L, 0x3f000000L, 0x3f800000L, 0x3f000000L),
          tmask = 0xF,
          expectedOp = FPUOp.FMADD,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40000000L, 0x40b00000L, 0x40c00000L, 0x40f00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fmsub.s",
          opcode = MuOpcode.MSUB.U,
          f3 = "b000".U,
          f7 = "b0000000".U,
          rs1Idx = 8,
          rs2Field = 9,
          rs3Idx = Some(10),
          rd = 11,
          rs1Lanes = Seq(0x40000000L, 0x40200000L, 0x40400000L, 0x40600000L),
          rs2Lanes = Seq(0x3f800000L, 0x3f800000L, 0x3f800000L, 0x3f800000L),
          rs3Lanes = Seq(0x3f000000L, 0x3f000000L, 0x3f000000L, 0x3f000000L),
          tmask = 0xF,
          expectedOp = FPUOp.FMSUB,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x3f800000L, 0x3fa00000L, 0x3fc00000L, 0x3fe00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fcvt.s.h",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0100000".U,
          rs1Idx = 11,
          rs2Field = 0x2,
          rs3Idx = None,
          rd = 12,
          rs1Lanes = Seq(0x00004000L, 0x0000c000L, 0x00014000L, 0x0001c000L),
          rs2Lanes = Seq.fill(4)(0L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.F2F,
          expectedSrcFmt = FPFormat.FP16,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x3f000000L, 0x40000000L, 0x40400000L, 0x40800000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fdiv.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0001100".U,
          rs1Idx = 12,
          rs2Field = 13,
          rs3Idx = None,
          rd = 13,
          rs1Lanes = Seq(0x40800000L, 0x40a00000L, 0x40c00000L, 0x40e00000L),
          rs2Lanes = Seq(0x3f800000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.DIV,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40000000L, 0x3f800000L, 0x3f000000L, 0x3e800000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fsqrt.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0101100".U,
          rs1Idx = 14,
          rs2Field = 0,
          rs3Idx = None,
          rd = 14,
          rs1Lanes = Seq(0x3f800000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs2Lanes = Seq.fill(4)(0L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.SQRT,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x3f800000L, 0x3f5a827aL, 0x3f3504f3L, 0x3f1a827aL),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fmin.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0010100".U,
          rs1Idx = 15,
          rs2Field = 16,
          rs3Idx = None,
          rd = 15,
          rs1Lanes = Seq(0x3f000000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs2Lanes = Seq(0x3f800000L, 0x3f000000L, 0x40800000L, 0x40400000L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.MINMAX,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x3f000000L, 0x3f000000L, 0x40400000L, 0x40400000L),
          responseLatency = 2
        )
      )

      specs.foreach { spec =>
        issueAndCheck(c, spec, env, postResponseGap = 0)
      }
    }
  }

  it should "handle FP32 requests with response gaps" in {
    implicit val p: Parameters = testParams(numLanes = 4, numFP32Lanes = 4)
    test(new FP32Pipe) { c =>
      val muon = p(MuonKey)
      val env = TestEnv(muon.archLen, muon.numLanes, muon.fpPipe.numFP32Lanes)

      c.io.resp.ready.poke(true.B)
      c.cvFPUIF.req.ready.poke(true.B)
      c.cvFPUIF.resp.valid.poke(false.B)

      val specs = Seq(
        FPRequestSpec(
          name = "fadd.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0000000".U,
          rs1Idx = 1,
          rs2Field = 2,
          rs3Idx = None,
          rd = 8,
          rs1Lanes = Seq(0x3f800000L, 0x40800000L, 0x40a00000L, 0x40c00000L),
          rs2Lanes = Seq(0x3f800000L, 0x3f800000L, 0x3f000000L, 0x3f000000L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.ADD,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40000000L, 0x40a00000L, 0x40c00000L, 0x40e00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fmadd.s",
          opcode = MuOpcode.MADD.U,
          f3 = "b000".U,
          f7 = "b0000000".U,
          rs1Idx = 5,
          rs2Field = 6,
          rs3Idx = Some(7),
          rd = 10,
          rs1Lanes = Seq(0x3f800000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs2Lanes = Seq(0x3f800000L, 0x40400000L, 0x3f800000L, 0x40400000L),
          rs3Lanes = Seq(0x3f800000L, 0x3f000000L, 0x3f800000L, 0x3f000000L),
          tmask = 0xF,
          expectedOp = FPUOp.FMADD,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x40000000L, 0x40b00000L, 0x40c00000L, 0x40f00000L),
          responseLatency = 2
        ),
        FPRequestSpec(
          name = "fsqrt.s",
          opcode = MuOpcode.OP_FP.U,
          f3 = "b000".U,
          f7 = "b0101100".U,
          rs1Idx = 14,
          rs2Field = 0,
          rs3Idx = None,
          rd = 14,
          rs1Lanes = Seq(0x3f800000L, 0x40000000L, 0x40400000L, 0x40800000L),
          rs2Lanes = Seq.fill(4)(0L),
          rs3Lanes = Seq.fill(4)(0L),
          tmask = 0xF,
          expectedOp = FPUOp.SQRT,
          expectedSrcFmt = FPFormat.FP32,
          expectedDstFmt = FPFormat.FP32,
          expectedResultLanes = Seq(0x3f800000L, 0x3f5a827aL, 0x3f3504f3L, 0x3f1a827aL),
          responseLatency = 2
        )
      )

      specs.foreach { spec =>
        issueAndCheck(c, spec, env, postResponseGap = 1)
      }
    }
  }
}
