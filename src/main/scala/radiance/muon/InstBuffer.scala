package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.int.LsuOpDecoder

/** UOp represents a single instruction executed in the backend, as well as a
 *  single entry in InstBuffer.
 */
class UOp(implicit p: Parameters) extends CoreBundle()(p) {
  val inst = new Decoded(full = false)
  val tmask = tmaskT
  val pc = pcT
  val wid = widT
}

class InstBufEntry(implicit p: Parameters) extends CoreBundle()(p) {
  val uop = uopT
  val token = lsuTokenT
}

trait HasUOpFields {
  val pc: UInt
  val wid: UInt
  val op: UInt
  val rd: UInt
  val rs1: UInt
  val rs2: UInt
  val rs3: UInt
  val imm32: UInt
  val imm24: UInt
  val csrImm: UInt
  val f3: UInt
  val f7: UInt
  val pred: UInt
  val tmask: UInt
  val raw: UInt
}

class UOpFlattened(implicit p: Parameters) extends CoreBundle()(p) with HasUOpFields {
  val pc = pcT
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
  val tmask = tmaskT
  val raw = instT

  def fromUop(bundle: Bundle) = {
    val uop = bundle.asTypeOf(uopT)
    val inst = uop.inst.expand()
    this.pc := uop.pc
    this.wid := uop.wid
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
    this.tmask := uop.tmask
    this.raw := inst(Raw)
  }

  def toUop(): Bundle = {
    val uop = Wire(uopT)
    uop.pc := this.pc
    uop.wid := this.wid
    uop.tmask := this.tmask
    uop.inst := Decoder.decode(this.raw).shrink()
    uop.inst(Rd) := this.rd
    uop.inst(Rs1) := this.rs1
    uop.inst(Rs2) := this.rs2
    uop.inst(Rs3) := this.rs3
    uop
  }
}

class InstBuffer(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {
  val io = IO(new Bundle {
    val enq = Flipped(ibufEnqIO)
    val deq = Vec(muonParams.numWarps, Decoupled(ibufEntryT))
    val lsuReserve = Flipped(reservationIO)
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
  (warpBufs lazyZip io.deq lazyZip io.lsuReserve).zipWithIndex.foreach { case ((b, deq, reserve), wid) =>
    b.io.enq.valid := io.enq.entry.valid && (io.enq.entry.bits.wid === wid.U)
    b.io.enq.bits := io.enq.entry.bits.uop
    assert(!b.io.enq.valid || b.io.enq.ready, s"$wid ibuf full")

    // this is slow (more latency), but safe
    // for memory instructions, we first acquire LSU token, then dequeue,
    // rather than trying to do both on the same cycle
    val inst = b.io.deq.bits.inst
    val needsLsuReserve = b.io.deq.valid && inst.b(UseLSUPipe)
    val opext = inst.opcode(8, 7)
    reserve.req.bits.addressSpace := MuxLookup(opext, AddressSpace.globalMemory)(Seq(
      0.U -> AddressSpace.globalMemory,
      1.U -> AddressSpace.sharedMemory
    ))
    reserve.req.bits.op := LsuOpDecoder.decode(inst.opcode, inst.f3)

    when (needsLsuReserve) {
      val acquiredToken = RegInit(0.U.asTypeOf(new LsuQueueToken))
      val acquiredTokenValid = RegInit(false.B)
      
      deq.valid := false.B
      b.io.deq.ready := false.B
      reserve.req.valid := true.B

      deq.bits := DontCare

      when (reserve.req.fire) {
        acquiredToken := reserve.resp.bits.token
        acquiredTokenValid := true.B
      }

      when (acquiredTokenValid) {
        deq.valid := true.B
        b.io.deq.ready := deq.ready
        reserve.req.valid := false.B

        deq.bits.uop := b.io.deq.bits
        deq.bits.token := acquiredToken

        when (deq.fire) {
          acquiredTokenValid := false.B
        }
      }
    }
    .otherwise {
      deq.valid := b.io.deq.valid
      b.io.deq.ready := deq.ready
      reserve.req.valid := false.B

      deq.bits.uop := b.io.deq.bits
      deq.bits.token := DontCare
    }

    io.enq.count(wid) := b.io.count
  }
}
