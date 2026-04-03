package radiance.memory

import freechips.rocketchip.tilelink.TLIdentityNode
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._

/** This node asserts d.ready to true for edges that have !d.valid.
 *  This node is meant to correct the manager-side bundles of an TLXbar, where
 *  the d.ready is de-asserted even for edges that do not have d.valid, causing
 *  potentially unnecessary response backpressure to the downstream.
 */
class DReadyRewriterNode(implicit p: Parameters) extends LazyModule {
  val node = TLIdentityNode()

  lazy val module = new LazyModuleImp(this) {
    (node.in.map(_._1) zip node.out.map(_._1)).foreach { case (i, o) =>
      o.a <> i.a
      i.b <> o.b
      o.c <> i.c
      i.d <> o.d
      o.d.ready := i.d.ready || !o.d.valid
      o.e <> i.e
    }
  }
}

object DReadyRewriterNode {
  def apply()(implicit p: Parameters): TLIdentityNode = {
    val mod = LazyModule(new DReadyRewriterNode)
    mod.node
  }
}
