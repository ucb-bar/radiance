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
  val pc = Option.when(muonParams.debug)(pcT)

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
  // `req.valid` dequeues operand from collector bank
  val req = Flipped(Valid(Vec(Isa.maxNumRegs, new Bundle {
    val enable = Bool()
    val collEntry = UInt(collEntryWidth.W)
  })))
  // same-cycle as `req`
  val resp = Valid(Vec(Isa.maxNumRegs, new Bundle {
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

  // collector banks, i.e. flip-flops that stage collected register data until
  // EX consumes them.  Separate per-reg, enq/deq drifts need care.
  val collBankEntries = 1
  val collBanks = Seq.fill(Isa.maxNumRegs)(Module(
    new Queue(gen = vecRegDataT, entries = collBankEntries, pipe = true)
  ))

  // collector allocation table
  val allocTable = new CollectorAllocTable(collBankEntries)(p)
  when (reset.asBool) { allocTable.reset }
  val nextAllocId = RegInit(0.U(allocTable.idWidth.W))
  // TODO: skip allocation when noen of readReq.regs.enable is set
  when (io.readReq.fire) {
    val (_, allocId) = allocTable.alloc
    nextAllocId := allocId
  }
  io.readReq.ready := allocTable.hasFree

  // read requests
  val readEns = io.readReq.bits.regs.map(_.enable)
  val readPRegs = io.readReq.bits.regs.map(_.pReg)
  (readEns lazyZip readPRegs lazyZip rfBanks lazyZip collBanks)
    .foreach { case (en, pReg, banks, collBank) =>
      val bankEn = io.readReq.fire && en
      val bankPorts = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)
      val nextBankId = RegNext(bankId, 0.U)
      val nextEn = RegNext(bankEn, false.B)
      val nextPReg = RegNext(pReg, 0.U)

      bankPorts.foreach(_.enable := false.B)
      bankPorts(bankId).enable := bankEn && (pReg =/= 0.U)
      bankPorts.foreach(_.address := regBankAddr(pReg))

      val regOut = Mux(nextPReg =/= 0.U,
        VecInit(bankPorts.map(_.data))(nextBankId),
        vecZeros)
      // TODO: @perf: Skip latching 0.U to collBank
      collBank.io.enq.valid := nextEn
      collBank.io.enq.bits := regOut
    }

  // read response
  // duplicated collector always answers readResp 1-cycle later
  (io.readReq.bits.regs lazyZip io.readResp.ports).foreach { case (reqPort, respPort) =>
    val opEn = io.readReq.fire && reqPort.enable
    respPort.valid := RegNext(opEn, false.B)
    respPort.bits.collEntry := nextAllocId
  }

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
  io.writeResp.ports.head.bits.collEntry := 0.U // writes don't have collector entry

  // operand serve (readData)
  // currently, collector drives response with valid data regardless of EX req
  io.readData.resp.valid := collBanks.map(_.io.deq.valid).reduce(_ || _)
  (io.readData.resp.bits zip collBanks).foreach { case (opnd, collBank) =>
    // FIXME: this doesn't work when collBanks drift!!
    opnd.enable := collBank.io.deq.valid
    opnd.data := collBank.io.deq.bits
    assert(opnd.pReg.isEmpty)
  }
  // dequeue collector bank on successful serve
  (io.readData.req.bits zip collBanks).foreach { case (req, collBank) =>
    assert(!req.enable || io.readData.req.valid,
      "collector: reg.enable set when request is invalid?")
    collBank.io.deq.ready := req.enable
  }
  val dequeue = io.readData.req.valid && io.readData.req.bits.map(_.enable).reduce(_ || _)
  when (dequeue) {
    allocTable.free(0.U /* FIXME */)
  }
}

class CollectorAllocTable(numEntries: Int)(implicit val p: Parameters)
extends HasCoreParameters {
  val idWidth = log2Up(numEntries)
  val table = Mem(numEntries, new CollectorAllocTableEntry)

  val emptyVec = VecInit((0 until numEntries).map(!table(_).valid))
  val hasEmpty = WireDefault(emptyVec.reduce(_ || _))
  val emptyId = PriorityEncoder(emptyVec)
  dontTouch(emptyVec)
  dontTouch(hasEmpty)

  def reset = {
    (0 until numEntries).foreach { table(_) := 0.U.asTypeOf(new CollectorAllocTableEntry) }
  }

  def hasFree: Bool = hasEmpty

  def alloc: (Bool /*valid*/, UInt /*id*/) = {
    when (hasEmpty) {
      table(emptyId).valid := true.B
    }
    (hasEmpty, emptyId)
  }

  def free(id: UInt) = {
    assert(id < numEntries.U, "collector alloc table id overflow")
    assert(table(id).valid, "double-free in collector alloc table")
    table(id).valid := false.B
  }
}

class CollectorAllocTableEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val rsEntryIdWidth = log2Up(muonParams.numIssueQueueEntries)
  val valid = Bool()
  // val rsEntryId = UInt(rsEntryIdWidth.W)
}
