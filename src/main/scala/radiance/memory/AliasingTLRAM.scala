package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

class AliasingTLRAM(sizeInKB: Int,
                    widthInBytes: Int,
                    targetAddress: AddressSet)
                   (implicit p: Parameters) extends LazyModule {

  val manager = TLManagerNode(Seq(TLSlavePortParameters.v1(
    managers = Seq(TLSlaveParameters.v2(
      address = Seq(targetAddress),
      regionType = RegionType.UNCACHED,
      executable = true,
      fifoId = Some(0),
      supports = TLMasterToSlaveTransferSizes(
        get = TransferSizes(1, widthInBytes),
        putFull = TransferSizes(1, widthInBytes),
        putPartial = TransferSizes(1, widthInBytes),
      )
    )),
    beatBytes = widthInBytes,
  )))

  lazy val module = new LazyModuleImp(this) {
    val backingSRAM = SRAM.masked(size = sizeInKB << 10, tpe = Vec(widthInBytes, UInt(8.W)), 0, 0, 1)
    val port = backingSRAM.readwritePorts.head
    val mask = (sizeInKB << 10) - 1

    val (node, edge) = manager.in.head
    port.enable := node.a.fire
    port.address := (node.a.bits.address & mask.U) >> log2Ceil(widthInBytes)
    port.mask.foreach { m =>
      (m zip node.a.bits.mask.asBools).foreach { case (a, b) => a := b }
    }
    port.isWrite := node.a.bits.opcode =/= TLMessages.Get
    port.writeData := node.a.bits.data.asTypeOf(port.writeData)

    val skidBuffer = Module(new Queue(gen = node.d.bits.cloneType, 2, false, false))
    val aFiredDly = RegNext(node.a.fire, false.B)
    val aBitsDly = RegEnable(node.a.bits, node.a.fire)
    skidBuffer.io.enq.valid := aFiredDly
    assert(!skidBuffer.io.enq.valid || skidBuffer.io.enq.ready, "D skid buffer overflow")
    skidBuffer.io.enq.bits := Mux(
      aBitsDly.opcode === TLMessages.Get,
      edge.AccessAck(aBitsDly, port.readData.asUInt),
      edge.AccessAck(aBitsDly))

    val enqThisCycle = aFiredDly
    val deqThisCycle = node.d.fire
    val queueCountNext = Wire(UInt(2.W))
    queueCountNext := skidBuffer.io.count
    when (enqThisCycle && !deqThisCycle) {
      queueCountNext := skidBuffer.io.count + 1.U
    }.elsewhen (!enqThisCycle && deqThisCycle) {
      queueCountNext := skidBuffer.io.count - 1.U
    }
    // Accept a new request if this cycle leaves at least one queue entry free
    // for that request's delayed response next cycle.
    node.a.ready := queueCountNext =/= 2.U

    node.d.valid := skidBuffer.io.deq.valid
    node.d.bits := skidBuffer.io.deq.bits
    skidBuffer.io.deq.ready := node.d.ready
  }
}
