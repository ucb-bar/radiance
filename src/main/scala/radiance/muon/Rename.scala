package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class Rename(implicit p: Parameters) extends CoreModule with HasFrontEndBundles {

  val io = IO(new Bundle {
    val decode = Flipped(decodeIO)
    val ibuf = ibufEnqIO
    val softReset = Input(Bool())
  })

  val totalARs = m.numWarps * m.numArchRegs

  val defaultAssignment = VecInit.fill(m.numWarps)(
    VecInit.tabulate(m.numArchRegs)(i => (i == 0).B) // x0 always assigned
  )
  val assigned = RegInit(defaultAssignment)

  // calculate wmask's (MSB idx + 1) rounded up to the nearest power of 2. eg 0b10001 => 8
  val logMask = Log2(io.decode.bits.wmask)
  val logLogMask = Mux(io.decode.bits.wmask <= 1.U, 0.U, Log2(logMask) + 1.U)
  val clippedLogLogMask = Mux(logLogMask < m.logRenameMinWarps.U, m.logRenameMinWarps.U, logLogMask)
  // val currentOccupancy = (1.U << clippedLogLogMask).asUInt
  val maxPRUsage = (m.numPhysRegs.U >> clippedLogLogMask).asUInt

  val table = SRAM(
    size = totalARs,
    tpe = prT,
    numReadPorts = 4,
    numWritePorts = 1,
    numReadwritePorts = 0
  )

  val rPorts = table.readPorts
  val wPort = table.writePorts.head

  val wid = io.decode.bits.wid
  val inst = io.decode.bits.inst
  val decoded = Decoded(inst)

  val instReg = RegNext(io.decode.bits.inst, 0.U)

  val hasReg = Seq(decoded.hasRd, decoded.hasRs1, decoded.hasRs2, decoded.hasRs3)
  val arAddr = Seq(decoded.rd, decoded.rs1, decoded.rs2, decoded.rs3)

  // read translations
  (rPorts lazyZip hasReg lazyZip arAddr).foreach { case (port, v, addr) =>
    port.enable := io.decode.valid && v
    port.address := Cat(wid, addr.asTypeOf(arT))
  }

  val prAddr = rPorts.map(_.data.asTypeOf(UInt(8.W)))

  // update rd entry in table
  val unassigned = !assigned(wid)(decoded.rd)
  val writesToRd = decoded.hasRd
  val assigning = io.decode.valid && writesToRd && unassigned

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
  val newInst = Cat(
    instReg(63, 44),
    Mux(RegNext(hasReg(3)), bypass(arAddr(3), prAddr(3)), instReg(43, 36)),
    Mux(RegNext(hasReg(2)), bypass(arAddr(2), prAddr(2)), instReg(35, 28)),
    Mux(RegNext(hasReg(1)), bypass(arAddr(1), prAddr(1)), instReg(27, 20)),
    instReg(19, 17),
    Mux(RegNext(hasReg(0)), bypass(arAddr(0), prAddr(0)), instReg(16, 9)),
    instReg(8, 0),
  )
  io.ibuf.enq.valid := RegNext(io.decode.valid)
  io.ibuf.enq.bits.wid := RegNext(wid)
  io.ibuf.enq.bits.inst := newInst
  io.ibuf.enq.bits.tmask := RegNext(io.decode.bits.tmask)
  io.ibuf.enq.bits.wmask := RegNext(io.decode.bits.wmask)

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
