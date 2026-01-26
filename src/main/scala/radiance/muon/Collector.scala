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
  // ID of the allocated collector bank entry
  val collEntry = UInt(collEntryWidth.W)
  val regs = Vec(numPorts, new Bundle {
    val enable = Bool()
    // TODO: tmask
  })
}

object CollectorResponse {
  def apply(numPorts: Int, isWrite: Boolean)(implicit p: Parameters)
  : ValidIO[CollectorResponse] = {
    Valid(new CollectorResponse(numPorts, isWrite))
  }
}

class CollectorOperandRead(implicit p: Parameters) extends CoreBundle()(p) {
  val collEntryWidth = log2Up(muonParams.numCollectorEntries)
  val hasPReg = !muonParams.useCollector
  // `req.valid` dequeues operand from collector bank
  val req = Flipped(Valid(new Bundle {
    val collEntry = UInt(collEntryWidth.W)
    val regs = Vec(Isa.maxNumRegs, new Bundle {
      val enable = Bool()
    })
  }))
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
  val numCollEntries = muonParams.numCollectorEntries
  val collBanks = Seq.fill(Isa.maxNumRegs)(
    Seq.fill(numCollEntries)(RegInit(vecZeros))
  )
  // @perf: this creates a big MUX
  val collBanksMux = collBanks.map(VecInit(_))

  // collector allocation table
  val allocTable = new CollectorAllocTable(numCollEntries)(p)
  when (reset.asBool) { allocTable.reset }
  val nextAllocId = RegInit(0.U(allocTable.idWidth.W))
  // TODO: skip allocation when noen of readReq.regs.enable is set
  when (io.readReq.fire) {
    when (freeNow) {
      // concurrent alloc/free; reuse id being freed
      debugf(cf"collector: concurrently alloc/freeing id=${rdCollEntry}. before: ")
      allocTable.print

      nextAllocId := rdCollEntry
    }.otherwise {
      val (succ, allocId) = allocTable.alloc
      debugf(cf"collector: allocating id=${allocId}. before: ")
      allocTable.print

      assert(succ, "unexpected collector alloc fail")
      nextAllocId := allocId
    }
  }.elsewhen (freeNow) {
    debugf(cf"collector: freeing id=${rdCollEntry}. before: ")
    allocTable.print

    allocTable.free(rdCollEntry)
  }
  // handle same-cycle collector bank dealloc
  // TODO: return alloc result via readResp, not readReq.ready
  io.readReq.ready := allocTable.hasFree || freeNow

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
      collBank.zipWithIndex.foreach { case (entry, i) =>
        when (nextEn && (i.U === nextAllocId)) {
          entry := regOut
        }
      }
      dontTouch(regOut)
    }

  // read response
  // duplicated collector always answers readResp 1-cycle later
  io.readResp.valid := RegNext(io.readReq.fire, false.B)
  (io.readReq.bits.regs lazyZip io.readResp.bits.regs).foreach { case (reqPort, respPort) =>
    val opEn = io.readReq.fire && reqPort.enable
    respPort.enable := RegNext(opEn, false.B)
  }
  io.readResp.bits.collEntry := nextAllocId

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

  io.writeResp.valid := RegNext(writeEnable)
  io.writeResp.bits.regs.head.enable := true.B
  io.writeResp.bits.collEntry := 0.U // writes don't use collector entries

  // operand serve (readData)
  // currently, collector drives response with valid data regardless of EX req
  def rdCollEntry = io.readData.req.bits.collEntry
  assert(!io.readData.req.valid || allocTable.read(rdCollEntry).valid,
    "collector: readData's collEntry should always point to a valid entry")
  io.readData.resp.valid := allocTable.read(rdCollEntry).valid
  (io.readData.req.bits.regs lazyZip io.readData.resp.bits lazyZip collBanksMux).foreach
    { case (req, opnd, collBank) =>
      // TODO: there should be a per-reg valid bit stored in alloc table or somewhere
      opnd.enable := io.readData.req.valid && req.enable
      opnd.data := collBank(rdCollEntry)
      assert(opnd.pReg.isEmpty)
    }
  // free collector entry on successful serve
  def freeNow = io.readData.req.valid &&
                io.readData.req.bits.regs.map(_.enable).reduce(_ || _)
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
    (0 until numEntries).foreach { table(_) := (new CollectorAllocTableEntry).empty }
  }

  def read(id: UInt): CollectorAllocTableEntry = {
    table(id)
  }

  def hasFree: Bool = hasEmpty

  def alloc: (Bool /*valid*/, UInt /*id*/) = {
    // Mem doesn't support partial-field updates
    val newEntry = WireDefault(table(emptyId))
    newEntry.valid := true.B
    when (hasEmpty) {
      table(emptyId) := newEntry
    }
    (hasEmpty, emptyId)
  }

  def free(id: UInt) = {
    assert(id < numEntries.U, "collector alloc table id overflow")
    assert(table(id).valid, "double-free in collector alloc table")
    val newEntry = WireDefault(table(emptyId))
    newEntry.valid := false.B
    table(id) := newEntry
  }

  def print = {
    debugf("table content: ")
    (0 until numEntries).foreach { i =>
      debugf(cf"${table(i).valid}")
    }
    debugf("\n")
  }
}

class CollectorAllocTableEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val rsEntryIdWidth = log2Up(muonParams.numIssueQueueEntries)
  val valid = Bool()
  val hasOps = Vec(Isa.maxNumRegs, Bool())
  // val rsEntryId = UInt(rsEntryIdWidth.W)
  def empty = 0.U.asTypeOf(this)
}
