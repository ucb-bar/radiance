package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class CollectorRequest(
  val numPorts: Int,
  val isWrite: Boolean
)(implicit p: Parameters) extends CoreBundle()(p) {
  val regs = Vec(numPorts, new Bundle {
    val enable = Bool()
    val pReg = pRegT
    val data = Option.when(isWrite)(Vec(numLanes, regDataT))
    // TODO: tmask
  })

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

  val rfBanks = Seq.fill(3)(Seq.fill(muonParams.numRegBanks)(SRAM(
    size = muonParams.numPhysRegs / muonParams.numRegBanks,
    tpe = Vec(numLanes, regDataT),
    numReadPorts = 1,
    numWritePorts = 1,
    numReadwritePorts = 0
  )))
  val regWidth = pRegT.getWidth
  val bankAddrWidth = rfBanks.head.head.readPorts.head.address.getWidth
  val bankIdWidth = regWidth - bankAddrWidth

  def regBankAddr(r: UInt): UInt = r(bankAddrWidth - 1, 0)
  def regBankId(r: UInt): UInt   = r(r.getWidth - 1, bankAddrWidth)

  // PReg #s and bank IDs serving the current response.  Used at accessing
  // collector banks at issue time.
  val respBankIds = Seq.fill(3)(WireDefault(0.U(bankIdWidth.W)))
  val respPRegs = Seq.fill(3)(WireDefault(0.U.asTypeOf(pRegT)))

  // read port
  // duplicated collector never locks up
  io.readReq.ready := true.B
  val readValid = io.readReq.valid
  val readEnables = io.readReq.bits.regs.map(_.enable)
  val readPRegs = io.readReq.bits.regs.map(_.pReg)
  (readEnables lazyZip (readPRegs zip respPRegs) lazyZip io.readResp.ports lazyZip (rfBanks zip respBankIds))
    .foreach { case (en, (pReg, respPReg), respPort, (banks, respBankId)) =>
      val bankReads = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)

      // request
      bankReads.foreach(_.enable := false.B)
      bankReads(bankId).enable := readValid && en
      bankReads.foreach(_.address := regBankAddr(pReg))

      // response: 1 cycle latency
      respPReg := RegNext(pReg, 0.U)
      respBankId := RegNext(bankId, 0.U)
      respPort.valid := RegNext(en, false.B)
      respPort.bits.collEntry := 0.U // duplicated collector has no collector entry

      // data is served by io.readData
    }

  // write port
  require(io.writeReq.bits.regs.length == 1, "collector: only single-writeback per cycle supported")
  io.writeReq.ready := true.B
  val writeEnable = io.writeReq.valid && io.writeReq.bits.regs.head.enable
  val writePReg = io.writeReq.bits.regs.head.pReg
  val writeData = io.writeReq.bits.regs.head.data.get
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

  // operand serve
  //
  // DuplicatedCollector doesn't have separate collector banks; SRAM output
  // ports directly drive the data.  The RS must ensure to align issue and SRAM
  // read.
  (io.readData.regs zip (rfBanks lazyZip respPRegs lazyZip respBankIds))
    .foreach { case (port, (banks, pReg, bankId)) =>
      val zeros = VecInit.fill(numLanes)(0.U.asTypeOf(regDataT))
      val bankReads = banks.map(_.readPorts.head)
      val bankOuts = VecInit(bankReads.map(_.data))(bankId)
      val bankEnable = port.enable && (pReg =/= 0.U)
      port.data := Mux(bankEnable, bankOuts, zeros)
    }
}
