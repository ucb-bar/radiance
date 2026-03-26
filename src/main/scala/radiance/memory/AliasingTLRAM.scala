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
    val backingSRAM = SRAM(size = sizeInKB << 10, tpe = UInt((widthInBytes * 8).W), 0, 0, 1)
    val port = backingSRAM.readwritePorts.head
    val mask = (sizeInKB << 10) - 1

    val (node, edge) = manager.in.head
    port.enable := node.a.fire
    port.address := (node.a.bits.address & mask.U) >> log2Ceil(widthInBytes)
    port.mask.foreach(_ := node.a.bits.mask)
    port.isWrite := node.a.bits.opcode =/= TLMessages.Get
    port.writeData := node.a.bits.data

    val skidBuffer = Module(new Queue(gen = node.d.bits.cloneType, 2, false, false))
    skidBuffer.io.enq.valid := RegNext(node.a.fire)
    skidBuffer.io.enq.bits := Mux(
      node.a.bits.opcode === TLMessages.Get,
      edge.AccessAck(RegNext(node.a.bits)),
      edge.AccessAck(RegNext(node.a.bits), port.readData))
    node.a.ready := skidBuffer.io.count <= 1.U

    node.d.valid := skidBuffer.io.deq.valid
    node.d.bits := skidBuffer.io.deq.bits
    skidBuffer.io.deq.ready := node.d.ready
  }
}
