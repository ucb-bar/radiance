package radiance.memory

import chisel3.util._
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.tilelink.TLAdapterNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

/* this limits all slaves to conform to the region of a given scope */
class AddressScopeNode(base: BigInt, size: BigInt)(implicit p: Parameters) extends LazyModule {
  require((base == 0) || isPow2(base))
  val node = TLAdapterNode(
    clientFn  = c => c,
    managerFn = m => {
      val siBlock: BigInt = BigInt(1) << log2Floor(size / m.slaves.length)
      m.v2copy(slaves = m.slaves.zipWithIndex.map { case (s, si) =>
        println(siBlock * BigInt(si))
        println("scoped new address", AddressSet(base + siBlock * BigInt(si), siBlock - 1))
        s.v1copy(
          address = Seq(AddressSet(base + siBlock * BigInt(si), siBlock - 1))
        )
      })
    }
  )
  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out.map(_._1)).foreach { case (i, o) => o <> i }
  }
}

object AddressScopeNode {
  def apply(base: BigInt, size: BigInt)(implicit p: Parameters): TLAdapterNode = {
    LazyModule(new AddressScopeNode(base, size)).node
  }
}
