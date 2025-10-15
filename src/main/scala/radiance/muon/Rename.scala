package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Rename(implicit p: Parameters) extends CoreModule with HasFrontEndBundles {

  val io = IO(new Bundle {
    val rename = Flipped(renameIO)
    val ibuf = ibufEnqIO
    val softReset = Input(Bool())
  })

  val totalARs = m.numWarps * m.numArchRegs

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
      size = totalARs,
      tpe = prT,
      numReadPorts = 4,
      numWritePorts = 1,
      numReadwritePorts = 0
    )
    val rPorts = table.readPorts
    val wPort = table.writePorts.head
    (rPorts, wPort)
  } else {
    val addrWidth = log2Up(totalARs)
    val rPorts = Vec(4, new MemoryReadPort(prT, addrWidth))
    val wPort = new MemoryWritePort(prT, addrWidth, false)
    val table = RegInit(VecInit.fill(totalARs)(prT))

    rPorts.foreach { p =>
      p.data := DontCare
      when (p.enable) {
        p.data := table(p.address)
      }
    }
    when (wPort.enable) {
      table(wPort.address) := wPort.data
    }
    (rPorts, wPort)
  }


  val wid = io.rename.bits.wid
  val decoded = Decoder.decode(io.rename.bits.inst)

  val hasReg = Seq(decoded.b(HasRd), decoded.b(HasRs1), decoded.b(HasRs2), decoded.b(HasRs3))
  val regs = Seq(Rd, Rs1, Rs2, Rs3)
  val arAddr = regs.map(decoded(_))

  // read translations
  (rPorts lazyZip hasReg lazyZip arAddr).foreach { case (port, v, addr) =>
    port.enable := io.rename.valid && v
    port.address := Cat(wid, addr.asTypeOf(arT))
  }

  val prAddr = rPorts.map(_.data.asTypeOf(UInt(8.W)))

  // update rd entry in table
  val unassigned = !assigned(wid)(decoded.rd)
  val writesToRd = decoded.b(HasRd)
  val assigning = io.rename.valid && writesToRd && unassigned

  wPort.enable := assigning
  when (assigning) {
    assigned(wid)(decoded.rd) := true.B
  }

  // substitute pr's for ibuf enq
  def bypass(ars: UInt, prs: UInt): UInt = {
    // bypass read result if wid matches, and prev cycle assigned, and prev rd matches
    val prevRead = RegNext(Cat(wid, ars.asTypeOf(arT)))
    val prevWrite = RegNext(Cat(wid, decoded.rd.asTypeOf(arT)))
    Mux(RegNext(assigning) && (prevRead === prevWrite), RegNext(wPort.data), prs)
  }
  val decodedReg = RegNext(decoded, 0.U.asTypeOf(decoded))
  val microInst = WireInit(decodedReg)

  regs.zipWithIndex.foreach { case (r, i) => // regs is rd/rs1/rs2/rs3
    microInst(r) := Mux(RegNext(hasReg(i)), bypass(arAddr(i), prAddr(i)), decodedReg(r))
  }

  io.ibuf.entry.valid := RegNext(io.rename.valid)
  io.ibuf.entry.bits.wid := RegNext(wid)
  io.ibuf.entry.bits.uop.inst := microInst
  io.ibuf.entry.bits.uop.tmask := RegNext(io.rename.bits.tmask)
  io.ibuf.entry.bits.uop.pc := RegNext(io.rename.bits.pc)

  // create & update counters
  val counters = VecInit.tabulate(m.numWarps) { counterId =>
    Counter(
      r = 1 until m.numArchRegs, // x0 is mapped
      enable = assigning && (counterId.U === wid),
      reset = io.softReset,
    )._1
  }
  wPort.address := Cat(wid, decoded.rd.asTypeOf(arT))
  wPort.data := counters(wid).asTypeOf(prT)

  // check for oversubscription
  assert(!assigning || (wPort.data < maxPRUsage), cf"warp $wid oversubscribed PRs, capped to $maxPRUsage")

  // reset assignment on kernel relaunch
  when (io.softReset) {
    assigned := defaultAssignment
  }
}
