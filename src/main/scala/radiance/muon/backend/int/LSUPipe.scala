package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.ExPipe
import radiance.muon.LoadStoreUnit
import radiance.muon.LsuReservationReq
import radiance.muon.LsuReservationResp
import radiance.muon.DataMemIO
import radiance.muon.SharedMemIO
import radiance.muon.LSUCoreAdapter
import radiance.muon.MuOpcode
import chisel3.util.experimental.decode.decoder
import chisel3.util.experimental.decode.TruthTable
import radiance.muon.MemOp
import radiance.muon.Imm32
import radiance.muon.LsuQueueToken
import radiance.muon.backend.LaneRecomposer
import radiance.muon.LsuResponse

class LSUPipe(implicit p: Parameters) extends ExPipe(writebackReg = true, writebackSched = false) {
    val reserveIO = IO(reservationIO)
    
    val tokenIO = IO(Input(lsuTokenT))

    val memIO = IO(memoryIO)
    
    val lsu = Module(new LoadStoreUnit)
    lsu.io.coreReservations <> reserveIO

    val reqOp = LsuOpDecoder.decode(inst.opcode, inst.f3)
    lsu.io.coreReq.bits.op := reqOp
    lsu.io.coreReq.bits.imm := inst(Imm32)
    lsu.io.coreReq.bits.destReg := inst.rd
    
    lsu.io.coreReq.bits.address := io.req.bits.rs1Data.get
    lsu.io.coreReq.bits.storeData := io.req.bits.rs2Data.get
    lsu.io.coreReq.bits.tmask := VecInit(uop.tmask.asBools)
    lsu.io.coreReq.bits.token := tokenIO
    
    lsu.io.coreReq.valid := io.req.valid
    io.req.ready := lsu.io.coreReq.ready

    // we use the recomposer in a bit of a weird way, compose numPackets
    // LsuResponse bundles
    val lsuRecomposer = Module(new LaneRecomposer(
      inLanes = lsuDerived.numPackets,
      outLanes = 1,
      elemTypes = Seq(new LsuResponse)
    ))

    lsu.io.coreResp.ready := lsuRecomposer.io.in.ready
    lsuRecomposer.io.in.valid := lsu.io.coreResp.valid

    lsuRecomposer.io.in.bits.data(0)(0) := lsu.io.coreResp.bits

    lsuRecomposer.io.out.ready := io.resp.ready
    io.resp.valid := lsuRecomposer.io.out.valid
    
    val firstPacket = lsuRecomposer.io.out.bits.data(0)(0).asTypeOf(new LsuResponse)

    val wb = io.resp.bits.reg.get
    wb.valid := true.B // ?? why is this a valid interface
    wb.bits.rd := firstPacket.destReg
    wb.bits.tmask := Cat(firstPacket.tmask.reverse)
    
    val packets = lsuRecomposer.io.out.bits.data(0).asTypeOf(Vec(lsuDerived.numPackets, new LsuResponse))
    val allData = packets.foldLeft(Seq[UInt]())((d: Seq[UInt], r: LsuResponse) => {
      d ++ r.writebackData.toSeq
    })

    wb.bits.data := VecInit(allData)

    val lsuAdapter = Module(new LSUCoreAdapter)
    lsuAdapter.io.lsu.globalMemReq :<>= lsu.io.globalMemReq
    lsu.io.globalMemResp :<>= lsuAdapter.io.lsu.globalMemResp
    lsuAdapter.io.lsu.shmemReq :<>= lsu.io.shmemReq
    lsu.io.shmemResp :<>= lsuAdapter.io.lsu.shmemResp

    lsuAdapter.io.core.dmem <> memIO.dmem
    lsuAdapter.io.core.smem <> memIO.smem
}

object LsuOpDecoder {
  def decode(opcode: UInt, f3: UInt): MemOp.Type = {
    val table = Seq[(BitPat, BitPat)](
      (BitPat(MuOpcode.LOAD) ## BitPat("b000")) -> BitPat(MemOp.loadByte.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.LOAD) ## BitPat("b001")) -> BitPat(MemOp.loadHalf.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.LOAD) ## BitPat("b010")) -> BitPat(MemOp.loadWord.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.LOAD) ## BitPat("b100")) -> BitPat(MemOp.loadByteUnsigned.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.LOAD) ## BitPat("b101")) -> BitPat(MemOp.loadHalfUnsigned.litValue.U(MemOp.getWidth.W)),
    
      (BitPat(MuOpcode.STORE) ## BitPat("b000")) -> BitPat(MemOp.storeByte.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.STORE) ## BitPat("b001")) -> BitPat(MemOp.storeHalf.litValue.U(MemOp.getWidth.W)),
      (BitPat(MuOpcode.STORE) ## BitPat("b010")) -> BitPat(MemOp.storeWord.litValue.U(MemOp.getWidth.W)),
    
      (BitPat(MuOpcode.MISC_MEM) ## BitPat("b000") -> BitPat(MemOp.fence.litValue.U(MemOp.getWidth.W))),
    )
    
    val memOpUint = decoder(Cat(opcode(6, 0), f3), TruthTable(table, BitPat(MemOp.loadWord.litValue.U(MemOp.getWidth.W))))
    MemOp(memOpUint)
  }
}

