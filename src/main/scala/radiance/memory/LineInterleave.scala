package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/** Address maps of one bank of a cache whose banks are interleaved by line: bank `bank` of `nBanks` holds the
  * lines whose line number is `bank` mod nBanks. Inside the bank the bank bits are removed, so the bank sees a
  * dense address space of size / nBanks and uses all its sets. */
case class LineInterleave(bank: Int, nBanks: Int, lineBytes: Int, size: BigInt) {
  require(isPow2(nBanks) && isPow2(lineBytes) && bank < nBanks)
  val lineBits = log2Ceil(lineBytes)
  val bankBits = log2Ceil(nBanks)
  /** the lines of this bank, in the full address space */
  val address = AddressSet(bank * lineBytes, (size - 1) & ~BigInt((nBanks - 1) * lineBytes))
  def compress(x: UInt): UInt =
    if (nBanks == 1) x else Cat(0.U(bankBits.W), x >> (lineBits + bankBits), x(lineBits - 1, 0))
  def expand(x: UInt): UInt =
    if (nBanks == 1) x else Cat(x >> lineBits, bank.U(bankBits.W), x(lineBits - 1, 0))(x.getWidth - 1, 0)
}

/** In front of a bank: shows the bank's interleaved lines to the clients and removes the bank bits from
  * request addresses (A and C), adding them back on probe addresses (B). */
class LineInterleaveInNode(il: LineInterleave)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(
    clientFn = c => c,
    managerFn = m => m.v2copy(slaves = m.slaves.map(s => s.v2copy(address = Seq(il.address)))))
  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((i, ie), (o, oe)) =>
      o.a <> i.a
      o.a.bits.address := il.compress(i.a.bits.address)
      i.d <> o.d
      if (ie.bundle.hasBCE) {
        i.b <> o.b
        i.b.bits.address := il.expand(o.b.bits.address)
        o.c <> i.c
        o.c.bits.address := il.compress(i.c.bits.address)
        o.e <> i.e
      }
    }
  }
}

/** Behind a bank: adds the bank bits back to the bank's outgoing request addresses (A and C). */
class LineInterleaveOutNode(il: LineInterleave)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(clientFn = c => c, managerFn = m => m)
  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((i, ie), (o, oe)) =>
      o.a <> i.a
      o.a.bits.address := il.expand(i.a.bits.address)
      i.d <> o.d
      if (ie.bundle.hasBCE) {
        i.b <> o.b
        i.b.bits.address := il.compress(o.b.bits.address)
        o.c <> i.c
        o.c.bits.address := il.expand(i.c.bits.address)
        o.e <> i.e
      }
    }
  }
}
