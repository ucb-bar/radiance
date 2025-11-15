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
  val regs = Vec(Isa.maxNumRegs, new Bundle {
    val enable = Input(Bool())
    val collEntry = Input(UInt(collEntryWidth.W))
    val data = Output(Vec(numLanes, regDataT))
    // TODO: tmask
  })
}

/** Simple operand collector with duplicated register files for rs1/2/3.
 *  Guarantees no bank conflicts and 1-cycle read/write accesses, at the
 *  expense of large area.
 */
class DuplicatedCollector(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
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
     *  flip-flop banks.  Combinational-read. */
    val readData = new CollectorOperandRead
  })

  def vecRegDataT = Vec(numLanes, regDataT)
  val vecZeros = 0.U.asTypeOf(vecRegDataT)
  val rfBanks = Seq.fill(3)(Seq.fill(muonParams.numRegBanks)(SRAM(
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
  def regBankId(r: UInt): UInt   = r(r.getWidth - 1, bankAddrWidth)

  // collector bank entry allocation
  //
  // only one entry for DuplicatedCollector
  val allocTable = RegInit(0.U.asTypeOf(new CollectorAllocTableEntry))
  // if readReq is requesting partial ops on the already-registered RS row, we
  // should admit it
  val allocInUse = WireDefault(false.B) // set later
  allocInUse := allocTable.valid && (allocTable.rsEntryId =/= io.readReq.bits.rsEntryId)
  val allocFreedNow = WireDefault(false.B) // set later
  io.readReq.ready := !allocInUse || allocFreedNow
  dontTouch(allocInUse)
  dontTouch(allocFreedNow)

  when (io.readReq.fire) {
    allocTable.valid := true.B
    allocTable.rsEntryId := io.readReq.bits.rsEntryId
  }.elsewhen (allocFreedNow) {
    allocTable.valid := false.B
  }

  val rfBankOuts = WireDefault(VecInit.fill(3)(0.U.asTypeOf(vecRegDataT)))
  val rfBankOutEns = WireDefault(VecInit.fill(3)(false.B))
  dontTouch(rfBankOuts)
  dontTouch(rfBankOutEns)

  // read port
  // drive SRAM read ports
  val readEnables = io.readReq.bits.regs.map(_.enable)
  val readPRegs = io.readReq.bits.regs.map(_.pReg)
  (readEnables lazyZip readPRegs lazyZip io.readResp.ports
    lazyZip (rfBanks lazyZip rfBankOuts lazyZip rfBankOutEns))
    .foreach { case (en, pReg, respPort, (banks, bankOut, bankOutEn)) =>
      val bankPorts = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)
      val nextBankId = RegNext(bankId, 0.U)
      val nextPReg = RegNext(pReg, 0.U)

      // request
      bankPorts.foreach(_.enable := false.B)
      val opEn = io.readReq.fire && en
      bankPorts(bankId).enable := opEn && (pReg =/= 0.U)
      bankPorts.foreach(_.address := regBankAddr(pReg))

      bankOut := Mux(nextPReg =/= 0.U,
                     VecInit(bankPorts.map(_.data))(nextBankId),
                     vecZeros)
      // also register zeros to the collector banks
      bankOutEn := RegNext(opEn, false.B)

      // response: 1 cycle latency
      respPort.valid := bankOutEn
      respPort.bits.collEntry := 0.U // duplicated collector has no collector entry
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
  io.writeResp.ports.head.bits.collEntry := 0.U // duplicated collector has no collector entry

  // collector banks

  val collBanks = Seq.fill(3)(Module(new Queue(vecRegDataT, entries = 1, pipe = true, flow = false)))
  allocFreedNow := io.readData.regs.map(_.enable).reduce(_ || _)

  collBanks.foreach(q => dontTouch(q.io.enq.valid))
  collBanks.foreach(q => dontTouch(q.io.enq.ready))
  collBanks.foreach(q => dontTouch(q.io.deq.valid))
  collBanks.foreach(q => dontTouch(q.io.deq.ready))

  // write
  (collBanks lazyZip rfBankOutEns lazyZip rfBankOuts).foreach { case (q, en, rfOut) =>
    assert(!en || q.io.enq.ready,
           "collector: allocation did not correctly ensure collector banks are enq-ready")
    q.io.enq.valid := en
    q.io.enq.bits := Mux(en, rfOut, vecZeros)
  }

  // read
  (io.readData.regs zip collBanks).foreach { case (port, q) =>
    assert(port.collEntry === 0.U)
    q.io.deq.ready := port.enable
    port.data := q.io.deq.bits
  }
}

class CollectorAllocTableEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val rsEntryIdWidth = log2Up(muonParams.numIssueQueueEntries)
  val collEntryIdWidth = log2Up(muonParams.numCollectorEntries)
  val valid = Bool()
  val rsEntryId = UInt(rsEntryIdWidth.W)
  val collEntryId = UInt(collEntryIdWidth.W)
}
