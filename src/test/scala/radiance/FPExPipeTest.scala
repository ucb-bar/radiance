package radiance.muon

import chisel3._
import chiseltest._
import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.tile.{TileKey, TileParams}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import radiance.muon.backend.fp._

class FPExPipeTest extends AnyFlatSpec with ChiselScalatestTester {
  private case class DummyTileParams(muon: MuonCoreParams) extends TileParams {
    val core: MuonCoreParams = muon
    val icache = None
    val dcache = None
    val btb = None
    val tileId = 0
    val blockerCtrlAddr = None
    val baseName = "fp_ex_pipe_test_tile"
    val uniqueName = baseName
    val clockSinkParams = ClockSinkParameters()
  }

  private def testParams(
      numLanes: Int,
      numFP32Lanes: Int,
      numFP16ExpLanes: Int,
      archLen: Int = 32
  ): Parameters = {
    val muonParams = MuonCoreParams(
      numWarps = numLanes,
      numLanes = numLanes,
      archLen = archLen,
      fpPipe = FPPipeParams(
        numFP32Lanes = numFP32Lanes,
        numFP32DivLanes = numFP32Lanes,
        numFP16ExpLanes = numFP16ExpLanes
      ),
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

  private def bf16ToFloat(bits: Int): Float = {
    java.lang.Float.intBitsToFloat(bits << 16)
  }

  private def floatToBf16RNE(value: Float): Int = {
    val bits = java.lang.Float.floatToIntBits(value)
    val lsb = (bits >> 16) & 0x1
    val roundingBias = 0x7fff + lsb
    ((bits + roundingBias) >> 16) & 0xffff
  }

  private def signExtend16(bits: Int): BigInt = {
    val masked = bits & 0xffff
    if ((masked & 0x8000) != 0) {
      BigInt(masked | 0xffff0000L)
    } else {
      BigInt(masked)
    }
  }

  private def checkLane(
      name: String,
      laneIdx: Int,
      actual: BigInt,
      inputBits: Int,
      neg: Boolean
  ): Unit = {
    val actual16 = (actual & 0xffff).toInt
    val signIn = (inputBits >> 15) & 0x1
    val sign = signIn ^ (if (neg) 1 else 0)
    val exp = (inputBits >> 7) & 0xff
    val frac = inputBits & 0x7f
    val isZero = exp == 0 && frac == 0
    val isSub = exp == 0 && frac != 0
    val isInf = exp == 0xff && frac == 0
    val isNaN = exp == 0xff && frac != 0
    val maxExp = 0x85
    val maxSig = 0x31
    val overflow = (sign == 0) && (exp > maxExp || (exp == maxExp && frac > maxSig))

    if (isNaN) {
      val outExp = (actual16 >> 7) & 0xff
      val outFrac = actual16 & 0x7f
      val outSign = (actual16 >> 15) & 0x1
      assert(outExp == 0xff && outFrac != 0, s"$name lane $laneIdx: expected NaN result")
      assert(outSign == sign, s"$name lane $laneIdx: NaN sign mismatch")
    } else if (isZero || isSub) {
      assert(actual16 == 0x3f80, s"$name lane $laneIdx: expected +1.0 for zero/subnorm")
    } else if (isInf && sign == 1) {
      assert(actual16 == 0x0000, s"$name lane $laneIdx: expected +0 for -inf")
    } else if (isInf && sign == 0) {
      assert(actual16 == 0x7f80, s"$name lane $laneIdx: expected +inf for +inf")
    } else if (overflow) {
      assert(actual16 == 0x7f80, s"$name lane $laneIdx: expected +inf on overflow")
    } else {
      val inFloat = bf16ToFloat(inputBits)
      val signedIn = if (neg) -inFloat else inFloat
      val expected = Math.exp(signedIn.toDouble).toFloat
      val actualF = bf16ToFloat(actual16)
      val denom = Math.max(1e-6f, Math.abs(expected))
      val relErr = Math.abs(actualF - expected) / denom
      assert(relErr <= 0.05f, s"$name lane $laneIdx: rel err $relErr too large")
    }
  }

  private case class FPExRequestSpec(
      name: String,
      rs2Field: Int,
      rd: Int,
      rs1Lanes: Seq[Int],
      tmask: Int
  )

  private def issueAndCheckFPExPipe(
      c: FPExPipe,
      spec: FPExRequestSpec,
      archLen: Int,
      applyRespBackpressure: Boolean
  ): Unit = {
    zeroDecoded(c.io.req.bits.uop.inst)
    pokeDecoded(c.io.req.bits.uop.inst, Opcode, MuOpcode.CUSTOM2.U.litValue)
    pokeDecoded(c.io.req.bits.uop.inst, F3, 0.U.litValue) // RNE
    pokeDecoded(c.io.req.bits.uop.inst, F7, "b0101110".U.litValue) // bf16 fpexp/fpnexp
    pokeDecoded(c.io.req.bits.uop.inst, Rd, spec.rd)
    pokeDecoded(c.io.req.bits.uop.inst, Rs1, 1)
    pokeDecoded(c.io.req.bits.uop.inst, Rs2, spec.rs2Field)
    pokeDecoded(c.io.req.bits.uop.inst, HasRd, 1)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs1, 1)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs2, 1)
    pokeDecoded(c.io.req.bits.uop.inst, HasRs3, 0)
    pokeDecoded(c.io.req.bits.uop.inst, UseFPExPipe, 1)

    c.io.req.bits.uop.tmask.poke(spec.tmask.U(c.io.req.bits.uop.tmask.getWidth.W))
    c.io.req.bits.uop.pc.poke(0.U(archLen.W))
    c.io.req.bits.uop.wid.poke(0.U)

    val rs1Padded = spec.rs1Lanes.map(signExtend16)
    pokeLaneVec(c.io.req.bits.rs1Data.get, rs1Padded, archLen)
    pokeLaneVec(c.io.req.bits.rs2Data.get, Seq.fill(c.io.req.bits.rs2Data.get.length)(BigInt(0)), archLen)
    c.io.req.bits.rs3Data.foreach(rs3 => pokeLaneVec(rs3, Seq.fill(rs3.length)(BigInt(0)), archLen))

    c.io.req.valid.poke(true.B)
    var reqWait = 0
    while (!c.io.req.ready.peek().litToBoolean) {
      c.clock.step()
      reqWait += 1
      require(reqWait <= 20, s"${spec.name}: request not accepted")
    }
    c.clock.step()
    c.io.req.valid.poke(false.B)

    var respSeen = false
    var cycles = 0
    while (!respSeen) {
      if (applyRespBackpressure) {
        c.io.resp.ready.poke((cycles % 3 != 0).B)
      } else {
        c.io.resp.ready.poke(true.B)
      }

      if (c.io.resp.valid.peek().litToBoolean && c.io.resp.ready.peek().litToBoolean) {
        c.io.resp.bits.reg.get.valid.expect(true.B, s"${spec.name}: expected register write")
        c.io.resp.bits.reg.get.bits.rd.expect(spec.rd.U, s"${spec.name}: rd mismatch")
        val data = c.io.resp.bits.reg.get.bits.data
        val neg = spec.rs2Field == 0x2
        spec.rs1Lanes.zipWithIndex.foreach { case (inputBits, laneIdx) =>
          val actual = data(laneIdx).peek().litValue
          checkLane(spec.name, laneIdx, actual, inputBits, neg)
        }
        respSeen = true
      }
      c.clock.step()
      cycles += 1
      require(cycles <= 200, s"${spec.name}: response not observed")
    }
  }

  behavior of "FPExPipe"

  it should "handle back-to-back FPEx requests" in {
    implicit val p: Parameters = testParams(numLanes = 8, numFP32Lanes = 4, numFP16ExpLanes = 4)
    test(new FPExPipe(FPFormat.BF16)) { c =>
      val archLen = p(MuonKey).archLen

      val goodValues = Seq(0.25f, 0.5f, 1.0f, -0.5f, 1.5f, -1.25f, 2.0f, -2.0f)
      val goodLanes = goodValues.map(floatToBf16RNE)
      val specialLanes = Seq(0x0000, 0x8000, 0x7f80, 0xff80, 0x7fc1, 0x7f81, 0x0001, 0x8001)
      val mixedLanes = Seq(0x0000, goodLanes(0), 0x7f80, goodLanes(1), 0x7fc1, goodLanes(2), 0x0001, goodLanes(3))

      val specs = Seq(
        FPExRequestSpec("good_fpexp.h", rs2Field = 0x1, rd = 1, rs1Lanes = goodLanes, tmask = 0xff),
        FPExRequestSpec("good_fpnexp.h", rs2Field = 0x2, rd = 2, rs1Lanes = goodLanes, tmask = 0xff),
        FPExRequestSpec("special_fpexp.h", rs2Field = 0x1, rd = 3, rs1Lanes = specialLanes, tmask = 0xff),
        FPExRequestSpec("special_fpnexp.h", rs2Field = 0x2, rd = 4, rs1Lanes = specialLanes, tmask = 0xff),
        FPExRequestSpec("mixed_fpexp.h", rs2Field = 0x1, rd = 5, rs1Lanes = mixedLanes, tmask = 0xff),
        FPExRequestSpec("mixed_fpnexp.h", rs2Field = 0x2, rd = 6, rs1Lanes = mixedLanes, tmask = 0xff)
      )

      specs.foreach { spec =>
        issueAndCheckFPExPipe(c, spec, archLen, applyRespBackpressure = false)
      }
    }
  }

  it should "handle FPEx requests with response stalls" in {
    implicit val p: Parameters = testParams(numLanes = 8, numFP32Lanes = 4, numFP16ExpLanes = 4)
    test(new FPExPipe(FPFormat.BF16)) { c =>
      val archLen = p(MuonKey).archLen

      val goodValues = Seq(0.125f, 0.75f, 1.25f, -0.25f, 1.75f, -1.5f, 2.25f, -2.5f)
      val goodLanes = goodValues.map(floatToBf16RNE)
      val specialLanes = Seq(0x0000, 0x8000, 0x7f80, 0xff80, 0x7fc1, 0x7f81, 0x0001, 0x8001)
      val mixedLanes = Seq(goodLanes(0), 0x0000, goodLanes(1), 0x7f80, goodLanes(2), 0x7fc1, goodLanes(3), 0x0001)

      val specs = Seq(
        FPExRequestSpec("stall_good_fpexp.h", rs2Field = 0x1, rd = 7, rs1Lanes = goodLanes, tmask = 0xff),
        FPExRequestSpec("stall_good_fpnexp.h", rs2Field = 0x2, rd = 8, rs1Lanes = goodLanes, tmask = 0xff),
        FPExRequestSpec("stall_special_fpexp.h", rs2Field = 0x1, rd = 9, rs1Lanes = specialLanes, tmask = 0xff),
        FPExRequestSpec("stall_special_fpnexp.h", rs2Field = 0x2, rd = 10, rs1Lanes = specialLanes, tmask = 0xff),
        FPExRequestSpec("stall_mixed_fpexp.h", rs2Field = 0x1, rd = 11, rs1Lanes = mixedLanes, tmask = 0xff),
        FPExRequestSpec("stall_mixed_fpnexp.h", rs2Field = 0x2, rd = 12, rs1Lanes = mixedLanes, tmask = 0xff)
      )

      specs.foreach { spec =>
        issueAndCheckFPExPipe(c, spec, archLen, applyRespBackpressure = true)
      }
    }
  }
}
