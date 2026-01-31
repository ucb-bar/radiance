package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Rename(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val rename = Flipped(renameIO)
    val ibuf = Vec(muonParams.numWarps, ibufEnqIO)
    val softReset = Input(Bool())
  })

  val totalArchRegs = m.numWarps * m.numArchRegs

  val defaultAssignment = VecInit.fill(m.numWarps)(
    VecInit.tabulate(m.numArchRegs)(i => (i == 0).B) // x0 always assigned
  )
  val assigned = RegInit(defaultAssignment)

  // calculate wmask's (MSB idx + 1) rounded up to the nearest power of 2. eg 0b10001 => 8
  val logMask = Log2(io.rename.bits.wmask)
  val logLogMask = Mux(io.rename.bits.wmask <= 1.U, 0.U, Log2(logMask) + 1.U)
  val clippedLogLogMask = Mux(logLogMask < m.logRenameMinWarps.U, m.logRenameMinWarps.U, logLogMask)
  // val currentOccupancy = (1.U << clippedLogLogMask).asUInt
  val maxPRUsage = (m.numPhysRegs.U >> clippedLogLogMask).asUInt

  val useSRAM = false

  val (rPorts, wPort) = if (useSRAM) {
    val table = SRAM(
      size = totalArchRegs,
      tpe = pRegT,
      numReadPorts = 4,
      numWritePorts = 1,
      numReadwritePorts = 0
    )
    val rPorts = table.readPorts
    val wPort = table.writePorts.head
    (rPorts, wPort)
  } else {
    val addrWidth = log2Up(totalArchRegs)
    val rPorts = Wire(Vec(4, new MemoryReadPort(pRegT, addrWidth)))
    val wPort = Wire(new MemoryWritePort(pRegT, addrWidth, false))
    val table = RegInit(VecInit.fill(totalArchRegs)(0.U.asTypeOf(pRegT)))

    rPorts.foreach { p =>
      p.data := RegNext(table(p.address), 0.U)
    }
    when (wPort.enable) {
      table(wPort.address) := wPort.data
    }
    (rPorts, wPort)
  }

  val wid = io.rename.bits.wid

  // decoding stage is included in rename
  val decoded = Decoder.decode(io.rename.bits.inst)

  val hasReg = Seq(decoded.b(HasRd), decoded.b(HasRs1), decoded.b(HasRs2), decoded.b(HasRs3))
  val regs = Seq(Rd, Rs1, Rs2, Rs3)
  val arAddr = regs.map(decoded(_))

  // read translations
  (rPorts lazyZip hasReg lazyZip arAddr).foreach { case (port, v, addr) =>
    port.enable := io.rename.valid && v
    port.address := Cat(wid, addr.asTypeOf(aRegT))
  }

  // val prIdxWidth = log2Ceil(m.numPhysRegs).U - clippedLogLogMask
  // val prWarpPrefix = (wid << prIdxWidth).asUInt
  val prAddr = rPorts.map(_.data)

  // update rd entry in table
  val unassigned = !assigned(wid)(decoded.rd)
  val writesToRd = decoded.b(HasRd)
  val assigning = io.rename.valid && writesToRd && unassigned

  wPort.enable := assigning
  when (assigning) {
    assigned(wid)(decoded.rd) := true.B
  }

  // substitute pr's for ibuf enq
  def bypassAndShortCircuit(ars: UInt, prs: UInt): UInt = {
    // bypass read result if wid matches, and prev cycle assigned, and prev rd matches
    val prevRead = RegNext(Cat(wid, ars.asTypeOf(aRegT)))
    val prevWrite = RegNext(Cat(wid, decoded.rd.asTypeOf(aRegT)))
    Mux(
      RegNext(ars === 0.U),
      0.U, // always rename to x0
      Mux(
        RegNext(assigning) && (prevRead === prevWrite),
        RegNext(wPort.data),
        prs
      )
    )
  }

  val shrunkDecoded = decoded.shrink()
  val decodedReg = RegNext(shrunkDecoded, 0.U.asTypeOf(shrunkDecoded))
  val uop = WireInit(decodedReg)

  regs.zipWithIndex.foreach { case (r, i) => // regs is rd/rs1/rs2/rs3
    uop(r) := Mux(RegNext(hasReg(i)), bypassAndShortCircuit(arAddr(i), prAddr(i)), decodedReg(r))
  }

  io.ibuf.zipWithIndex.foreach { case (wbuf, wbufId) =>
    wbuf.uop.valid := RegNext(io.rename.valid && wid === wbufId.U)
    wbuf.uop.bits.inst := uop
    wbuf.uop.bits.tmask := RegNext(io.rename.bits.tmask)
    wbuf.uop.bits.pc := RegNext(io.rename.bits.pc)
    wbuf.uop.bits.wid := RegNext(io.rename.bits.wid)
  }

  // create & update counters
  val counters = VecInit.tabulate(m.numWarps) { counterId =>
    Counter(
      r = 1 until m.numArchRegs, // x0 is mapped
      enable = assigning && (counterId.U === wid),
      reset = io.softReset,
    )._1
  }
  val (globalCounter, globalOverSubscription) = Counter(
    r = 1 until m.numPhysRegs,
    enable = assigning,
    reset = io.softReset)

  assert(!globalOverSubscription, "total register usage exceeded maximum number of physical registers")

  wPort.address := Cat(wid, decoded.rd.asTypeOf(aRegT))
  wPort.data := globalCounter
  // wPort.data := counters(wid).asTypeOf(pRegT)

  // check for warp level oversubscription
  assert(!assigning || (counters(wid) < maxPRUsage), cf"warp $wid oversubscribed PRs, capped to $maxPRUsage")

  // reset assignment on kernel relaunch
  when (io.softReset) {
    assigned := defaultAssignment
  }
}
