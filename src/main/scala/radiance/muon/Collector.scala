package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class CollectorRequest(
  val numPorts: Int,
  val isWrite: Boolean
)(implicit p: Parameters) extends CoreBundle()(p) {
  val rsEntryIdWidth = log2Up(muonParams.numIssueQueueEntries)
  val regs = Vec(numPorts, new Bundle {
    val enable = Bool()
    val pReg = pRegT
    val data = Option.when(isWrite)(Vec(numLanes, regDataT))
    // TODO: tmask
  })
  val rsEntryId = UInt(rsEntryIdWidth.W)

  def anyEnabled(): Bool = {
    regs.map(_.enable).reduce(_ || _)
  }
}

object CollectorRequest {
  def apply(numPorts: Int, isWrite: Boolean)(implicit p: Parameters)
  : DecoupledIO[CollectorRequest] = {
    Flipped(Decoupled(new CollectorRequest(numPorts, isWrite)))
  }
}

class CollectorResponse(
  val numPorts: Int,
  val isWrite: Boolean
)(implicit p: Parameters) extends CoreBundle()(p) {
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)
  val ports = Vec(numPorts, Decoupled(new Bundle {
    /** pointer to the collector entry; RS uses this to know where to issue
     *  operands from */
    val collEntry = UInt(collEntryWidth.W)
    // TODO: tmask
  }))
}

object CollectorResponse {
  def apply(numPorts: Int, isWrite: Boolean)(implicit p: Parameters)
  : CollectorResponse = {
    new CollectorResponse(numPorts, isWrite)
  }
}

class CollectorOperandRead(implicit p: Parameters) extends CoreBundle()(p) {
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)
  val hasPReg = !muonParams.useCollector
  /** RS will keep track of collEntry and send a req with it */
  val req = Flipped(Valid(new Bundle {
    val collEntry = UInt(collEntryWidth.W)
  }))
  val resp = Decoupled(Vec(Isa.maxNumRegs, new Bundle {
    val enable = Bool()
    val pReg = Option.when(hasPReg)(pRegT)
    val data = Vec(numLanes, regDataT)
    // TODO: tmask
  }))
}

/** Simple operand collector with duplicated register files for rs1/2/3.
 *  Guarantees no bank conflicts and 1-cycle read/write accesses, at the
 *  expense of large area.
 */
class DuplicatedCollector(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    /** Request collection of a single uop with full rs1/2/3 combination. */
    val readReq  = CollectorRequest(Isa.maxNumRegs, isWrite = false)
    /** Response that indicates a register read has been collected.  The
     *  rs1/2/3 registers of a single uop may potentially be responded to at
     *  different cycles. Responses to the same pReg are guaranteed to be
     *  served in the same order as the requests. */
    val readResp = CollectorResponse(Isa.maxNumRegs, isWrite = false)
    /** Writebacks are served one dest register at a time. */
    val writeReq  = CollectorRequest(1, isWrite = true)
    val writeResp = CollectorResponse(1, isWrite = true)
    /** Data read port for the operands readily collected & stored in the
     *  flip-flop banks. */
    val readData = new CollectorOperandRead
  })

  def vecRegDataT = Vec(numLanes, regDataT)
  val vecZeros = 0.U.asTypeOf(vecRegDataT)
  val rfBanks = Seq.fill(Isa.maxNumRegs)(Seq.fill(muonParams.numRegBanks)(SRAM(
    size = muonParams.numPhysRegs / muonParams.numRegBanks,
    tpe = vecRegDataT,
    numReadPorts = 1,
    numWritePorts = 1,
    numReadwritePorts = 0
  )))
  val regWidth = pRegT.getWidth
  val bankAddrWidth = rfBanks.head.head.readPorts.head.address.getWidth
  val bankIdWidth = regWidth - bankAddrWidth

  def regBankAddr(r: UInt): UInt = r(bankAddrWidth - 1, 0)
  def regBankId(r: UInt): UInt   = {
    if (bankIdWidth == 0) 0.U else r(regWidth - 1, bankAddrWidth)
  }

  // flip-flop banks that stage collected registers until EX consumes them
  val collBanks = Seq.fill(Isa.maxNumRegs)(Module(
    new Queue(gen = vecRegDataT, entries = 1, pipe = true)
  ))

  // read requests
  val readEns = io.readReq.bits.regs.map(_.enable)
  val readPRegs = io.readReq.bits.regs.map(_.pReg)
  (readEns lazyZip readPRegs lazyZip rfBanks lazyZip collBanks)
    .foreach { case (en, pReg, banks, collBank) =>
      val bankEn = io.readReq.fire && en && (pReg =/= 0.U)
      val bankPorts = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)
      val nextBankId = RegNext(bankId, 0.U)
      val nextEn = RegNext(bankEn, false.B)
      val nextPReg = RegNext(pReg, 0.U)

      // request
      bankPorts.foreach(_.enable := false.B)
      bankPorts(bankId).enable := bankEn
      bankPorts.foreach(_.address := regBankAddr(pReg))

      val bankOut = Mux(nextPReg =/= 0.U,
        VecInit(bankPorts.map(_.data))(nextBankId),
        vecZeros)
      collBank.io.enq.valid := nextEn
      collBank.io.enq.bits := bankOut
    }
  // consider backpressure from collector banks
  val allCollBanksReady = collBanks.map(_.io.enq.ready).reduce(_ && _)
  io.readReq.ready := allCollBanksReady

  // read response
  // duplicated collector always answers readResp 1-cycle later
  (io.readReq.bits.regs lazyZip io.readResp.ports).foreach { case (reqPort, respPort) =>
    val opEn = io.readReq.fire && reqPort.enable
    respPort.valid := RegNext(opEn, false.B)
    respPort.bits.collEntry := 0.U // fixed for DuplicatedCollector
  }

  // // readData
  // val dataEnables = io.readData.regs.map(_.enable)
  // val dataPRegs = io.readData.regs.map(_.pReg.get)
  // (dataEnables lazyZip dataPRegs lazyZip rfBanks lazyZip io.readData.regs)
  //   .foreach { case (en, pReg, banks, readDataOp) =>
  //     val bankPorts = VecInit(banks.map(_.readPorts.head))
  //     val bankId = regBankId(pReg)
  //     val nextBankId = RegNext(bankId, 0.U)
  //     val nextPReg = RegNext(pReg, 0.U)

  //     // request
  //     bankPorts.foreach(_.enable := false.B)
  //     bankPorts(bankId).enable := en && (pReg =/= 0.U)
  //     bankPorts.foreach(_.address := regBankAddr(pReg))

  //     val bankOut = Mux(nextPReg =/= 0.U,
  //                       VecInit(bankPorts.map(_.data))(nextBankId),
  //                       vecZeros)
  //     readDataOp.data := bankOut
  //   }

  // write port
  require(io.writeReq.bits.regs.length == 1, "collector: only single-writeback per cycle supported")
  io.writeReq.ready := true.B
  val writeEnable = io.writeReq.fire && io.writeReq.bits.regs.head.enable
  val writePReg = io.writeReq.bits.regs.head.pReg
  val writeData = io.writeReq.bits.regs.head.data.get
  // write to all of rs1/2/3 banks
  rfBanks.foreach { case banks =>
    val bankWrites = VecInit(banks.map(_.writePorts.head))
    bankWrites.foreach { b =>
      b.address := regBankAddr(writePReg)
      b.data := writeData
      b.enable := false.B
    }
    bankWrites(regBankId(writePReg)).enable := writeEnable && (writePReg =/= 0.U)
  }

  io.writeResp.ports.head.valid := RegNext(writeEnable)
  io.writeResp.ports.head.bits.collEntry := 0.U // duplicated collector has no collector entry

  // operand serve (readData)
  io.readData.resp.valid := collBanks.map(_.io.deq.valid).reduce(_ || _)
  (io.readData.resp.bits zip collBanks).foreach { case (opnd, collBank) =>
    opnd.enable := collBank.io.deq.valid
    opnd.data := collBank.io.deq.bits
    assert(opnd.pReg.isEmpty)
    // dequeue.  Note: should be fire to consider EX back-pressure
    collBank.io.deq.ready := io.readData.resp.fire
  }
}

class CollectorAllocTableEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val rsEntryIdWidth = log2Up(muonParams.numIssueQueueEntries)
  val collEntryIdWidth = log2Up(muonParams.numCollectorEntries)
  val valid = Bool()
  val rsEntryId = UInt(rsEntryIdWidth.W)
  val collEntryId = UInt(collEntryIdWidth.W)
}
