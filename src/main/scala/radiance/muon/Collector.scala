package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

/** Collector receives an entire rs1/2/3 combination of a single uop at a time.
 *  TODO: dedup with response
 */
class CollectorRequest(
  val numPorts: Int,
  val hasData: Boolean
)(implicit p: Parameters) extends CoreBundle()(p) {
  /* rs1/rs2/rs3/rd */
  val ports = Vec(numPorts, Flipped(Decoupled(new Bundle {
    val pReg = pRegT
    val data = Option.when(hasData)(Vec(numLanes, regDataT))
    // TODO: tmask
  })))
}

/** Collector serves rs1/2/3 requests individually, potentially at different cycles.
 *  It guarantees requests to the same pReg to be served in-order, so the
 *  bundle does not need a separate tag ID.
 */
class CollectorResponse(
  val numPorts: Int,
  val hasData: Boolean
)(implicit p: Parameters) extends CoreBundle()(p) {
  val ports = Vec(numPorts, Decoupled(new Bundle {
    val pReg = pRegT
    val data = Option.when(hasData)(Vec(numLanes, regDataT))
    // TODO: tmask
  }))
}

/** Simple operand collector with duplicated register files for rs1/2/3.
 *  Guarantees no bank conflicts and 1-cycle read/write accesses, at the
 *  expense of large area.
 */
class DuplicatedCollector(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val readReq  = new CollectorRequest(Isa.maxNumRegs, hasData = false)
    val readResp = new CollectorResponse(Isa.maxNumRegs, hasData = true)
    val writeReq  = new CollectorRequest(1, hasData = true)
    val writeResp = new CollectorResponse(1, hasData = false)
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

  // duplicated collector never locks up
  io.readReq.ports.foreach(_.ready := true.B)
  io.writeReq.ports.foreach(_.ready := true.B)

  def regBankAddr(r: UInt): UInt = r(bankAddrWidth - 1, 0)
  def regBankId(r: UInt): UInt   = r(r.getWidth - 1, bankAddrWidth)

  val resps = io.readResp.ports

  // read port
  val readEnables = io.readReq.ports.map(_.valid)
  val readPRegs = io.readReq.ports.map(_.bits.pReg)
  (readEnables lazyZip readPRegs lazyZip resps lazyZip rfBanks)
    .foreach { case (en, pReg, respPort, banks) =>
      val bankReads = VecInit(banks.map(_.readPorts.head))
      val bankId = regBankId(pReg)

      // request
      bankReads.foreach(_.enable := false.B)
      bankReads(bankId).enable := en
      bankReads.foreach(_.address := regBankAddr(pReg))

      // response: 1 cycle later
      val respEnable = RegNext(en)
      val respPReg = RegNext(pReg)
      respPort.valid := respEnable
      respPort.bits.pReg := respPReg
      val zeros = VecInit.fill(numLanes)(0.U(archLen.W))
      val bankOuts = VecInit(bankReads.map(_.data))(RegNext(bankId))
      respPort.bits.data.get := Mux(respPReg === 0.U, zeros, bankOuts)
    }

  // write port
  require(io.writeReq.ports.length == 1, "collector: only single-writeback per cycle supported")
  val writeEnable = io.writeReq.ports.head.fire // TODO: handle ready properly
  val writePReg = io.writeReq.ports.head.bits.pReg
  val writeData = io.writeReq.ports.head.bits.data.get
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
  io.writeResp.ports.head.bits.pReg := RegNext(writePReg)
  require(io.writeResp.ports.head.bits.data.isEmpty)
}
