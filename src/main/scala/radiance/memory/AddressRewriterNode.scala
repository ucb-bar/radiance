package radiance.memory

import chisel3._
import chisel3.experimental.SourceInfo
import freechips.rocketchip.tilelink.TLAdapterNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule._


class AddressRewriterNode(rewriteFn: UInt => UInt, inverseFn: UInt => UInt)
                         (implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(clientFn = c => c, managerFn = m => m)
  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out.map(_._1)).foreach { case (i, o) =>
      o.a <> i.a
      o.a.bits.address := rewriteFn(i.a.bits.address)
      i.b <> o.b
      i.b.bits.address := inverseFn(o.b.bits.address)
      o.c <> i.c
      o.c.bits.address := rewriteFn(i.c.bits.address)
      i.d <> o.d
      o.e <> i.e
    }
  }
}

object AddressOrNode {
  // note: the inverse here is not a true inverse
  def apply(baseAddr: BigInt)(implicit p: Parameters, valName: ValName, sourceInfo: SourceInfo): TLAdapterNode = {
    LazyModule(new AddressRewriterNode(x => x | baseAddr.U, x => x & (~baseAddr.U).asUInt)).node
  }
}

object AddressAndNode {
  // not a true inverse either
  def apply(baseAddr: BigInt)(implicit p: Parameters, valName: ValName, sourceInfo: SourceInfo): TLAdapterNode = {
    LazyModule(new AddressRewriterNode(x => x & baseAddr.U, x => x | (~baseAddr.U).asUInt)).node
  }
}
