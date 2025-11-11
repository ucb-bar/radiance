package radiance.memory

import freechips.rocketchip.diplomacy.TransferSizes
import freechips.rocketchip.tilelink.TLAdapterNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}

// WARNING: This only edits diplomacy metadata; it does NOT implement AMO semantics.
class HackAtomicNode(beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode(
    clientFn  = c => c,
    managerFn = m => {
      val atom = TransferSizes(1, beatBytes)
      m.v2copy(slaves = m.slaves.map( s => {
        s.v1copy(
          supportsArithmetic = s.supportsArithmetic mincover atom,
          supportsLogical = s.supportsLogical mincover atom
        )
      }))
    }
  )
  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out.map(_._1)).foreach { case (i, o) => o <> i }
  }
}

object HackAtomicNode {
  def apply(beatBytes: Int)(implicit p: Parameters): TLAdapterNode = {
    LazyModule(new HackAtomicNode(beatBytes)).node
  }
}
