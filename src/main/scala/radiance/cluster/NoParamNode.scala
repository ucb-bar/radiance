package radiance.cluster

import chisel3._
import chisel3.experimental.SourceInfo
import chisel3.util._
import freechips.rocketchip.resources.BigIntHexContext
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.nodes._

case class NullParams()

class NoParamNode[T <: Bundle](b: T) {
  object Imp extends SimpleNodeImp[NullParams, NullParams, NullParams, T] {
    def bundle(x: NullParams) = b
    def edge(x: NullParams, y: NullParams, p: Parameters, sourceInfo: SourceInfo): NullParams = NullParams()
    def render(x: NullParams): RenderedEdge = RenderedEdge("ffffff")
  }

  case class Master()(implicit valName: ValName) extends SourceNode(Imp)(Seq(NullParams()))
  case class Slave()(implicit valName: ValName) extends SinkNode(Imp)(Seq(NullParams()))
}

class AccBundle extends Bundle {
  val cmd = Output(Valid(UInt(32.W)))
  val status = Input(UInt(1.W))

  def dest(): UInt = { cmd.bits(7, 5) }
  def mask: UInt = x"ffffff1f".U
}

object AccNode extends NoParamNode(new AccBundle)

class SoftResetFinishBundle extends Bundle {
  val softReset = Output(Bool())
  val finished = Input(Bool())
}

object SoftResetFinishNode extends NoParamNode(new SoftResetFinishBundle)

class CacheFlushBundle extends Bundle {
  val start = Output(Bool())
  val done = Input(Bool())
}

/** Tile -> cache flush request port.  `quiescent` is asserted by the tile while it has no memory
  * request in flight on its lane links and no store waiting in the LSU; the cache defers every
  * flush start (fence, MMIO write, finish) until then, so a sweep never runs ahead of stores that
  * are still on their way down to it. */
class CacheFlushNodeBundle extends CacheFlushBundle {
  val quiescent = Output(Bool())
}

object CacheFlushNode extends NoParamNode(new CacheFlushNodeBundle)
