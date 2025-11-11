package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class LaneFrame
(inLanes: Int, elemTypes: Seq[Data])
(implicit val p: Parameters) extends Bundle {
  val data = MixedVec(elemTypes.map(e => Vec(inLanes, e.cloneType)))
}

class LaneSlice
(outLanes: Int, elemTypes: Seq[Data])
(implicit val p: Parameters) extends Bundle {
  val data = MixedVec(elemTypes.map(e => Vec(outLanes, e.cloneType)))
}

abstract class Sequencer
(val inLanes: Int, val outLanes: Int, val elemTypes: Seq[Data])
(implicit p: Parameters) extends Module {

  val numInputs = elemTypes.length
  require(outLanes <= inLanes,
    s"Sequencer outLanes ($outLanes) must be <= inLanes ($inLanes)")
  require(inLanes % outLanes == 0,
    s"Sequencer inLanes ($inLanes) must be divisible by outLanes ($outLanes)")
  val totalPackets = inLanes / outLanes
  val packetCtrBits = log2Ceil(totalPackets + 1)
  val packetWidth = outLanes

  def slice(data: MixedVec[Data], base: UInt) = {
    val out = Wire(MixedVec(elemTypes.map(t => Vec(outLanes, t.cloneType))))

    assert(base + (outLanes.U - 1.U) < inLanes.U,
      "slice(base) out of range: base + outLanes - 1 must be < inLanes")

    for (i <- elemTypes.indices) {
      for (j <- 0 until outLanes) {
        val laneIdx = (base + j.U)
        out(i)(j) := data(i).asInstanceOf[Vec[Data]](laneIdx).asTypeOf(out(i)(j))
      }
    }
    out
  }

}

class LaneDecomposer
(inLanes: Int, outLanes: Int, elemTypes: Seq[Data])
(implicit p: Parameters)
  extends Sequencer(inLanes, outLanes, elemTypes) {

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new LaneFrame(inLanes, elemTypes)))
    val out = Decoupled(new LaneSlice(outLanes, elemTypes))
  })

  val req_data = Reg(chiselTypeOf(io.in.bits.data))
  val packetIdx = RegInit(0.U(packetCtrBits.W))
  val busy = RegInit(0.B)
  val endPacket = packetIdx === (totalPackets - 1).U
  val laneStartIdx = packetIdx * packetWidth.U

  when (io.in.fire) {
    req_data := io.in.bits.data
    busy := true.B
    packetIdx := 0.U
    // same cycle short circuit
    when (io.out.fire) {
      if (totalPackets == 1) {
        packetIdx := 0.U
        busy := false.B
      } else {
        packetIdx := 1.U
      }
    }
  }

  when (busy && io.out.ready) {
    when (endPacket) {
      packetIdx := 0.U
      busy := false.B
    } .otherwise {
      packetIdx := packetIdx + 1.U
    }
  }

  io.in.ready := !busy
  io.out.valid := busy || io.in.fire
  io.out.bits.data := Mux(busy,
      slice(req_data.asInstanceOf[MixedVec[Data]], laneStartIdx),
      slice(io.in.bits.data.asInstanceOf[MixedVec[Data]], 0.U)
  )
}

class LaneRecomposer
(inLanes: Int, outLanes: Int, elemTypes: Seq[Data])
(implicit p: Parameters)
  extends Sequencer(inLanes, outLanes, elemTypes) {

  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new LaneSlice(outLanes, elemTypes)))
    val out = Decoupled(new LaneFrame(inLanes, elemTypes))
  })

  val buffer_data = Reg(chiselTypeOf(io.out.bits.data))
  val packetIdx = RegInit(0.U(packetCtrBits.W))
  val sawLastPacket = packetIdx === totalPackets.U
  val laneStartIdx = Mux(io.in.fire && io.out.fire, 0.U, packetIdx * packetWidth.U)

  when (io.in.fire) {
    packetIdx := packetIdx + 1.U
    for (i <- 0 until numInputs) {
      for (j <- 0 until outLanes) {
        buffer_data(i)(laneStartIdx + j.U) := io.in.bits.data(i)(j)
      }
    }
  }

  when (io.out.fire) {
    packetIdx := Mux(io.in.fire, 1.U, 0.U)
  }

  io.in.ready := packetIdx =/= totalPackets.U || (sawLastPacket && io.out.ready)
  io.out.valid := sawLastPacket
  io.out.bits.data := buffer_data
}
