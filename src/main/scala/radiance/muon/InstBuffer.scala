package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class InstBufferEntry(implicit p: Parameters) extends CoreBundle()(p) with HasFrontEndBundles {
  val pc = UInt(addressBits.W)
  val wid = widT
  val op = UInt(Isa.opcodeBits.W)
  val rd = UInt(Isa.regBits.W)
  val rs1 = UInt(Isa.regBits.W)
  val rs2 = UInt(Isa.regBits.W)
  val rs3 = UInt(Isa.regBits.W)
  val imm32 = UInt(32.W)
  val imm24 = UInt(24.W)
  val csrImm = UInt(Isa.csrImmBits.W)
  val f3 = UInt(3.W)
  val f7 = UInt(7.W)
  val pred = UInt(Isa.predBits.W)
  val tmask = UInt(muonParams.numLanes.W)
  val raw = UInt(muonParams.instBits.W)
  // TODO: op ext, pipe-specific args e.g. fpu rm

  def fromUop(uop: Bundle) = {
    val u = uop.asTypeOf(uopT)
    val inst = u.inst.expand()
    this.pc := u.pc
    this.wid := u.wid
    this.op := inst(Opcode)
    this.rd := inst(Rd)
    this.rs1 := inst(Rs1)
    this.rs2 := inst(Rs2)
    this.rs3 := inst(Rs3)
    this.imm32 := inst(Imm32)
    this.imm24 := inst(Imm24)
    this.csrImm := inst(CsrImm)
    this.f3 := inst(F3)
    this.f7 := inst(F7)
    this.pred := inst(Pred)
    this.tmask := u.tmask
    this.raw := inst(Raw)
  }

  def toUop(): Bundle = {
    val u = Wire(uopT)
    u.pc := this.pc
    u.wid := this.wid
    u.tmask := this.tmask
    u.inst := Decoder.decode(this.raw).shrink()
    u.inst(Rd) := this.rd
    u.inst(Rs1) := this.rs1
    u.inst(Rs2) := this.rs2
    u.inst(Rs3) := this.rs3
    u
  }
}

class InstBuffer(implicit p: Parameters) extends CoreModule()(p) with HasFrontEndBundles {
  val io = IO(new Bundle {
    val enq = Flipped(ibufEnqIO)
    val deq = Vec(muonParams.numWarps, Decoupled(uopT))
  })

  val warpBufs = Seq.tabulate(muonParams.numWarps){ wid =>
    val buf = Module(new Queue(
      gen = uopT,
      entries = muonParams.ibufDepth,
      pipe = false,
      flow = false,
      useSyncReadMem = true,
      hasFlush = false
    )).suggestName(s"ibuf_w$wid")
    buf.ram.suggestName(s"ibuf")
    buf
  }
  (warpBufs zip io.deq).zipWithIndex.foreach { case ((b, deq), wid) =>
    b.io.enq.valid := io.enq.entry.valid && (io.enq.entry.bits.wid === wid.U)
    b.io.enq.bits := io.enq.entry.bits.uop
    assert(!b.io.enq.valid || b.io.enq.ready, s"$wid ibuf full")

    deq <> b.io.deq

    io.enq.count(wid) := b.io.count
  }
}
