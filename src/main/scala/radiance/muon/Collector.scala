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
    val data = Option.when(!isWrite)(Vec(numLanes, regDataT))
    // TODO: tmask
  }))
}

object CollectorResponse {
  def apply(numPorts: Int, isWrite: Boolean)(implicit p: Parameters)
  : CollectorResponse = {
    new CollectorResponse(numPorts, isWrite)
  }
}

/** Simple operand collector with duplicated register files for rs1/2/3.
 *  Guarantees no bank conflicts and 1-cycle read/write accesses, at the
 *  expense of large area.
 */
class DuplicatedCollector(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    /** Collector receives an entire uop with full rs1/2/3 combination at each
     *  request. */
    val readReq  = CollectorRequest(Isa.maxNumRegs, isWrite = false)
    /** Collector serves rs1/2/3 requests individually, potentially at different cycles.
     *  The response port width is the same as request to keep throughput.
     *  Requests to the same pReg are guaranteed to be served in-order. */
    val readResp = CollectorResponse(Isa.maxNumRegs, isWrite = false)
    /** Writebacks are served one dest register at a time. */
    val writeReq  = CollectorRequest(1, isWrite = true)
    val writeResp = CollectorResponse(1, isWrite = true)
  })

  val rfBanks = Seq.fill(3)(Seq.fill(muonParams.numRegBanks)(SRAM(
    size = muonParams.numPhysRegs / muonParams.numRegBanks,
    tpe = Vec(numLanes, UInt(archLen.W)),
    numReadPorts = 1,
    numWritePorts = 1,
    numReadwritePorts = 0
  )))
  val regWidth = pRegT.getWidth
  val bankAddrWidth = rfBanks.head.head.readPorts.head.address.getWidth

  def regBankAddr(r: UInt): UInt = r(bankAddrWidth - 1, 0)
  def regBankId(r: UInt): UInt   = r(r.getWidth - 1, bankAddrWidth)

  val resps = io.readResp.ports

  // read port
  // duplicated collector never locks up
  io.readReq.ready := true.B
  val readValid = io.readReq.valid
  val readEnables = io.readReq.bits.regs.map(_.enable)
  val readPRegs = io.readReq.bits.regs.map(_.pReg)
  (readEnables lazyZip readPRegs lazyZip resps lazyZip rfBanks)
    .foreach { case (en, pReg, respPort, banks) =>
      val bankReads = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)

      // request
      bankReads.foreach(_.enable := false.B)
      bankReads(bankId).enable := readValid && en
      bankReads.foreach(_.address := regBankAddr(pReg))

      // response: 1 cycle later
      val respEnable = RegNext(en)
      val respPReg = RegNext(pReg)
      respPort.valid := respEnable
      respPort.bits.collEntry := 0.U // duplicated collector has no collector entry
      val zeros = VecInit.fill(numLanes)(0.U(archLen.W))
      val bankOuts = VecInit(bankReads.map(_.data))(RegNext(bankId))
      respPort.bits.data.get := Mux(respPReg === 0.U, zeros, bankOuts)
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
  require(io.writeResp.ports.head.bits.data.isEmpty)
}
