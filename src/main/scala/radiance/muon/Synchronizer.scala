package radiance.muon

import chisel3._
import chisel3.util._
import chisel3.experimental.SourceInfo
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._

case class BarrierParamsD(haveBits: Int)
case class BarrierParamsU(barrierBits: Int, wantBits: Int)
case class BarrierParams(haveBits: Int, barrierBits: Int, wantBits: Int)

object BarrierNode {
  object Imp extends SimpleNodeImp[BarrierParamsD, BarrierParamsU, BarrierParams, BarrierBundle] {
    def bundle(x: BarrierParams) = new BarrierBundle(x)
    def edge(x: BarrierParamsD, y: BarrierParamsU, p: Parameters, sourceInfo: SourceInfo) =
      BarrierParams(x.haveBits, y.barrierBits, y.wantBits)
    def render(x: BarrierParams): RenderedEdge = RenderedEdge("ffffff")
  }

  case class Master(haveBits: Int)(implicit valName: ValName)
    extends SourceNode(Imp)(Seq(BarrierParamsD(haveBits)))
  case class Slave(barrierBits: Int, wantBits: Int)(implicit valName: ValName)
    extends SinkNode(Imp)(Seq(BarrierParamsU(barrierBits, wantBits)))
  case class Junction()(implicit valName: ValName)
    extends NexusNode(Imp)(_.head, _.head)
    // extends NexusNode(Imp)(x => BarrierParamsD(x.map(_.haveBits).max + log2Ceil(x.length)), _.head)
}

class BarrierBundle(params: BarrierParams) extends Bundle {
  val req = Decoupled(new Bundle {
    val id = UInt(params.barrierBits.W)
    val want = UInt(params.wantBits.W)
    val have = UInt(params.haveBits.W)
  })
  val resp = Flipped(Valid(new Bundle {
    val id = UInt(params.barrierBits.W)
  }))
}

class BarrierJunction(implicit p: Parameters) extends LazyModule {
  val node = BarrierNode.Junction()

  lazy val module = new LazyModuleImp(this) {
    require(node.out.length == 1)
    val ins = node.in.map(_._1)
    val out = node.out.head._1

    ins.foreach(dontTouch(_))
    dontTouch(out)

    val reqArb = Module(new RRArbiter(out.req.bits.cloneType, ins.length))
    (reqArb.io.in zip ins.map(_.req)).foreach { case (a, b) => a <> b }
    out.req <> reqArb.io.out

    ins.foreach(_.resp := out.resp)
  }
}

class Synchronizer(implicit p: Parameters) extends LazyModule with HasMuonCoreParameters {

  val maxWantBits = log2Ceil(muonParams.numWarps * muonParams.numCores)
  val node = BarrierNode.Slave(muonParams.barrierBits, maxWantBits)

  lazy val module = new LazyModuleImp(this) {
    val numBarriers = 1 << node.barrierBits
    val barriers = RegInit(VecInit.fill(numBarriers)(0.U(node.wantBits.W)))
    require(node.in.length == 1, "use a junction before synchronizer")
    val (req, resp) = (node.in.head._1.req, node.in.head._1.resp)

    val bar = barriers(req.bits.id)
    val resolved = WireInit(false.B)
    when (req.fire) {
      when (bar === 0.U) {
        bar := req.bits.want - req.bits.have
        resolved := (req.bits.want === req.bits.have)
      }.otherwise {
        bar := bar - req.bits.have
        resolved := (req.bits.have === bar)
        assert(req.bits.have <= bar, "barrier underflow")
      }
    }

    req.ready := true.B
    resp.valid := RegNext(resolved)
    resp.bits.id := RegNext(req.bits.id)
  }
}
