package radiance

import chisel3._
import chiseltest._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import radiance.muon.backend.{LaneDecomposer, LaneRecomposer}
import radiance.muon.{LoadStoreUnitParams, MuonCoreParams, MuonKey}

class SequencerTest extends AnyFlatSpec with ChiselScalatestTester {
  private def testParams(numLanes: Int): Parameters =
    Parameters.empty.alterPartial {
      case MuonKey => MuonCoreParams(
        numLanes = numLanes,
        lsu = LoadStoreUnitParams(numLsuLanes = numLanes)
      )
    }

  private def makeElemTypes(numInputs: Int, elemWidth: Int): Seq[Data] =
    Seq.fill(numInputs)(UInt(elemWidth.W))

  behavior of "LaneDecomposer"

  it should "emit lane slices in-order and bypass 0th cycle" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 2
    val outLanes = 2
    val totalPackets = 2
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneDecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int, input: Int) = (input * 16 + lane).U(elemWidth.W)
      c.io.out.ready.poke(true.B)
      for (input <- 0 until numInputs; lane <- 0 until totalPackets * outLanes) {
        c.io.in.bits.data(input)(lane).poke(laneValue(lane, input))
      }
      c.io.in.valid.poke(true.B)

      for (packet <- 0 until totalPackets) {
        c.io.out.valid.expect(true.B)
        for (j <- 0 until outLanes; input <- 0 until numInputs) {
          val laneIdx = packet * outLanes + j
          c.io.out.bits.data(input)(j).expect(laneValue(laneIdx, input))
        }
        c.clock.step()
        if (packet == 0) {
          c.io.in.valid.poke(false.B)
        }
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "support mixed element types" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 3
    val outLanes = 2
    val totalPackets = 2
    val elemTypesSeq = Seq(UInt(32.W), SInt(32.W), Bool())
    val inLanes = p(MuonKey).numLanes

    val elemTypes = elemTypesSeq
    test(new LaneDecomposer(inLanes, outLanes, elemTypes)) { c =>
      def uintValue(lane: Int) = (lane * 5 + 3).U(32.W)
      def sintValue(lane: Int) = (lane - 3).S(32.W)
      def boolValue(lane: Int) = (lane % 2 == 0).B
      c.io.out.ready.poke(true.B)
      for (lane <- 0 until totalPackets * outLanes) {
        c.io.in.bits.data(0)(lane).poke(uintValue(lane))
        c.io.in.bits.data(1)(lane).poke(sintValue(lane))
        c.io.in.bits.data(2)(lane).poke(boolValue(lane))
      }
      c.io.in.valid.poke(true.B)

      for (packet <- 0 until totalPackets) {
        c.io.out.valid.expect(true.B)
        for (j <- 0 until outLanes) {
          val laneIdx = packet * outLanes + j
          c.io.out.bits.data(0)(j).expect(uintValue(laneIdx))
          c.io.out.bits.data(1)(j).expect(sintValue(laneIdx))
          c.io.out.bits.data(2)(j).expect(boolValue(laneIdx))
        }
        c.clock.step()
        if (packet == 0) {
          c.io.in.valid.poke(false.B)
        }
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "bypass and immediately be ready for input next cycle for single-packet configs" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 1
    val outLanes = 4
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneDecomposer(inLanes, outLanes, elemTypes)) { c =>
      c.io.out.ready.poke(true.B)
      for (lane <- 0 until outLanes) {
        c.io.in.bits.data(0)(lane).poke((lane + 10).U(elemWidth.W))
      }
      c.io.in.valid.poke(true.B)

      c.io.out.valid.expect(true.B)
      for (lane <- 0 until outLanes) {
        c.io.out.bits.data(0)(lane).expect((lane + 10).U)
      }

      c.io.in.valid.poke(false.B)
      c.clock.step()
      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "hold slices until the consumer is ready" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 1
    val outLanes = 2
    val totalPackets = 2
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneDecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int) = (lane + 5).U(elemWidth.W)
      c.io.out.ready.poke(false.B)
      for (lane <- 0 until totalPackets * outLanes) {
        c.io.in.bits.data(0)(lane).poke(laneValue(lane))
      }
      c.io.in.valid.poke(true.B)

      c.io.in.ready.expect(true.B)
      c.io.out.valid.expect(true.B)
      for (j <- 0 until outLanes) {
        c.io.out.bits.data(0)(j).expect(laneValue(j))
      }

      c.clock.step()
      c.io.in.valid.poke(false.B)
      c.io.out.valid.expect(true.B)
      for (j <- 0 until outLanes) {
        c.io.out.bits.data(0)(j).expect(laneValue(j))
      }

      c.io.out.ready.poke(true.B)
      c.clock.step()
      c.io.out.valid.expect(true.B)
      for (j <- 0 until outLanes) {
        val laneIdx = outLanes + j
        c.io.out.bits.data(0)(j).expect(laneValue(laneIdx))
      }

      c.clock.step()
      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "bypass immediately for 16-lane input and 4-lane packets" in {
    implicit val p: Parameters = testParams(numLanes = 16)
    val numInputs = 2
    val outLanes = 4
    val totalPackets = p(MuonKey).numLanes / outLanes
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneDecomposer(inLanes, outLanes, elemTypes)) { c =>
      c.io.out.ready.poke(true.B)
      def laneValue(lane: Int, input: Int) = (input * 100 + lane).U(elemWidth.W)
      for (input <- 0 until numInputs; lane <- 0 until p(MuonKey).numLanes) {
        c.io.in.bits.data(input)(lane).poke(laneValue(lane, input))
      }
      c.io.in.valid.poke(true.B)

      for (packet <- 0 until totalPackets) {
        c.io.out.valid.expect(true.B)
        for (lane <- 0 until outLanes; input <- 0 until numInputs) {
          val laneIdx = packet * outLanes + lane
          c.io.out.bits.data(input)(lane).expect(laneValue(laneIdx, input))
        }
        c.clock.step()
        if (packet == 0) {
          c.io.in.valid.poke(false.B)
        }
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  behavior of "LaneRecomposer"

  it should "collect lane slices into a full lane" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 2
    val outLanes = 2
    val totalPackets = 2
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneRecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int, input: Int) = (input * 16 + lane).U(elemWidth.W)
      c.io.out.ready.poke(true.B)
      c.io.in.valid.poke(false.B)

      for (packet <- 0 until totalPackets) {
        c.io.in.valid.poke(true.B)
        for (j <- 0 until outLanes; input <- 0 until numInputs) {
          val laneIdx = packet * outLanes + j
          c.io.in.bits.data(input)(j).poke(laneValue(laneIdx, input))
        }

        if (packet < totalPackets - 1) {
          c.io.out.valid.expect(false.B)
        } else {
          c.io.out.valid.expect(true.B)
          for (lane <- 0 until totalPackets * outLanes; input <- 0 until numInputs) {
            c.io.out.bits.data(input)(lane).expect(laneValue(lane, input))
          }
        }

        c.clock.step()
        c.io.in.valid.poke(false.B)
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "recompose mixed element types" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 3
    val outLanes = 2
    val totalPackets = 2
    val elemTypesSeq = Seq(UInt(32.W), SInt(32.W), Bool())
    val inLanes = p(MuonKey).numLanes

    val elemTypes = elemTypesSeq
    test(new LaneRecomposer(inLanes, outLanes, elemTypes)) { c =>
      def uintValue(lane: Int) = (lane * 7 + 2).U(32.W)
      def sintValue(lane: Int) = (lane * 4 - 6).S(32.W)
      def boolValue(lane: Int) = (lane % 3 == 0).B
      c.io.out.ready.poke(true.B)

      for (packet <- 0 until totalPackets) {
        c.io.in.valid.poke(true.B)
        for (j <- 0 until outLanes) {
          val laneIdx = packet * outLanes + j
          c.io.in.bits.data(0)(j).poke(uintValue(laneIdx))
          c.io.in.bits.data(1)(j).poke(sintValue(laneIdx))
          c.io.in.bits.data(2)(j).poke(boolValue(laneIdx))
        }

        if (packet < totalPackets - 1) {
          c.io.out.valid.expect(false.B)
        } else {
          c.io.out.valid.expect(true.B)
          for (lane <- 0 until totalPackets * outLanes) {
            c.io.out.bits.data(0)(lane).expect(uintValue(lane))
            c.io.out.bits.data(1)(lane).expect(sintValue(lane))
            c.io.out.bits.data(2)(lane).expect(boolValue(lane))
          }
        }

        c.clock.step()
        c.io.in.valid.poke(false.B)
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "bypass recomposed output when only one packet is required" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 2
    val outLanes = 4
    val totalPackets = 1
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneRecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int, input: Int) = (input * 16 + lane + 3).U(elemWidth.W)
      c.io.out.ready.poke(true.B)
      for (lane <- 0 until outLanes; input <- 0 until numInputs) {
        c.io.in.bits.data(input)(lane).poke(laneValue(lane, input))
      }
      c.io.in.valid.poke(true.B)

      c.io.out.valid.expect(true.B)
      for (lane <- 0 until outLanes; input <- 0 until numInputs) {
        c.io.out.bits.data(input)(lane).expect(laneValue(lane, input))
      }

      c.io.in.valid.poke(false.B)
      c.clock.step()
      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "retain the recomposed lane across backpressure" in {
    implicit val p: Parameters = testParams(numLanes = 4)
    val numInputs = 2
    val outLanes = 2
    val totalPackets = 2
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneRecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int, input: Int) = (input * 32 + lane + 7).U(elemWidth.W)
      c.io.out.ready.poke(false.B)

      // First packet
      for (j <- 0 until outLanes; input <- 0 until numInputs) {
        val laneIdx = j
        c.io.in.bits.data(input)(j).poke(laneValue(laneIdx, input))
      }
      c.io.in.valid.poke(true.B)
      c.io.in.ready.expect(true.B)
      c.io.out.valid.expect(false.B)
      c.clock.step()
      c.io.in.valid.poke(false.B)

      // Final packet arrives while consumer back-pressures
      for (j <- 0 until outLanes; input <- 0 until numInputs) {
        val laneIdx = outLanes + j
        c.io.in.bits.data(input)(j).poke(laneValue(laneIdx, input))
      }
      c.io.in.valid.poke(true.B)
      c.io.in.ready.expect(true.B)
      c.io.out.valid.expect(true.B)
      for (lane <- 0 until totalPackets * outLanes; input <- 0 until numInputs) {
        c.io.out.bits.data(input)(lane).expect(laneValue(lane, input))
      }

      c.clock.step()
      c.io.in.valid.poke(false.B)
      c.io.out.valid.expect(true.B)
      for (lane <- 0 until totalPackets * outLanes; input <- 0 until numInputs) {
        c.io.out.bits.data(input)(lane).expect(laneValue(lane, input))
      }

      c.io.out.ready.poke(true.B)
      c.clock.step()
      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }

  it should "bypass the final packet when assembling 16 lanes from 4-lane slices" in {
    implicit val p: Parameters = testParams(numLanes = 16)
    val numInputs = 2
    val outLanes = 4
    val totalPackets = 4
    val elemWidth = 8
    val inLanes = p(MuonKey).numLanes

    val elemTypes = makeElemTypes(numInputs, elemWidth)
    test(new LaneRecomposer(inLanes, outLanes, elemTypes)) { c =>
      def laneValue(lane: Int, input: Int) = (input * 64 + lane).U(elemWidth.W)
      c.io.out.ready.poke(true.B)

      for (packet <- 0 until totalPackets) {
        c.io.in.valid.poke(true.B)
        for (j <- 0 until outLanes; input <- 0 until numInputs) {
          val laneIdx = packet * outLanes + j
          c.io.in.bits.data(input)(j).poke(laneValue(laneIdx, input))
        }

        if (packet < totalPackets - 1) {
          c.io.out.valid.expect(false.B)
        } else {
          c.io.out.valid.expect(true.B)
          for (lane <- 0 until totalPackets * outLanes; input <- 0 until numInputs) {
            c.io.out.bits.data(input)(lane).expect(laneValue(lane, input))
          }
        }

        c.clock.step()
        c.io.in.valid.poke(false.B)
      }

      c.io.out.valid.expect(false.B)
      c.io.in.ready.expect(true.B)
    }
  }
}
