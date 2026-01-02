package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.IdRange
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule._
import radiance.memory.BoolArrayUtils.BoolSeqUtils

class SingleAddrCoalescer(val sourceBits: Int = 2)
                         (implicit p: Parameters) extends LazyModule {

  val node = TLAdapterNode(clientFn = c => c, managerFn = m => m)
  val coalNode = TLClientNode(Seq(TLMasterPortParameters.v2(
    Seq(TLMasterParameters.v2(
      name = "single_addr_coalescer",
      sourceId = IdRange(0, 1 << sourceBits),
    ))
  )))

  lazy val module = new LazyModuleImp(this) {
    val coalOut = coalNode.out.head._1
    val numLanes = node.out.length

    val coalSource = Module(new SourceGenerator(
      sourceWidth = sourceBits,
      metadata = Some(Vec(numLanes, Valid(UInt(node.in.head._1.params.sourceBits.W))))
    ))

    val coalescibleOut = Wire(Bool())
    val coalescedIn = Wire(Bool())

    // passthrough A/D
    (node.out zip node.in).foreach { case ((out, _), (in, _)) =>
      out.a <> in.a
      in.d <> out.d

      out.a.valid := in.a.valid && !coalescibleOut
      in.a.ready := Mux(coalescibleOut, coalOut.a.ready && coalSource.io.id.valid, out.a.ready)

      in.d.valid := out.d.valid && !coalescedIn // TODO FIXME HACK, ASSUMES UNIFORM D READY
      out.d.ready := in.d.ready && !coalescedIn // coalesced response has priority
    }

    // coalesced A output
    val inA = node.in.map(_._1.a)
    val leader = PriorityMux(inA.map(a => (a.valid, a.bits)))
    val leaderReady = PriorityMux(inA.map(a => (a.valid, a.ready)))

    coalescibleOut := inA.map(x => !x.valid || (x.bits.address === leader.address)).andR

    coalOut.a.valid := coalescibleOut && inA.map(_.valid).orR && coalSource.io.id.valid
    coalOut.a.bits := leader
    coalOut.a.bits.source := coalSource.io.id.bits
    coalSource.io.gen := coalOut.a.fire
    (coalSource.io.meta.get zip inA).foreach { case (meta, a) =>
      meta.bits := a.bits.source
      meta.valid := a.valid
    }

    assert(!coalescibleOut ||
      // when coalesced, each lane either is not valid, or must have the same ready
      inA.map(x => !x.valid || (x.ready === leaderReady)).andR)

    // coalesced D input
    val inD = node.in.map(_._1.d)

    val currDFires = inD.map(_.fire)
    val pendingDResp = RegInit(VecInit.fill(numLanes)(false.B))
    val storedDResp = RegEnable(coalOut.d.bits, 0.U.asTypeOf(coalOut.d.bits), coalOut.d.fire)
    val peekMeta = coalSource.io.peek.get
    val storedDMeta = RegEnable(peekMeta, 0.U.asTypeOf(peekMeta), coalOut.d.fire)
    val partialMode = pendingDResp.asUInt.orR

    // enter partial mode when cannot clear all D lanes in one cycle
    val currDFireOrInvalid = (currDFires zip peekMeta).map { case (df, pm) =>
      df || (!pm.valid)
    }
    when (coalOut.d.fire && !currDFireOrInvalid.andR) {
      assert(pendingDResp.asUInt === 0.U)
      // unfired and valid lanes
      pendingDResp := VecInit(currDFireOrInvalid.map(!_))
    }
    // when in partial mode, clear lanes that fire
    when (partialMode) {
      (pendingDResp zip currDFires).foreach { case (p, f) => p := p && !f }
    }

    coalescedIn := coalOut.d.valid || partialMode
    // we only drive uncoalesced D inputs when there's a coalesced response,
    // otherwise, passthrough nodes drive the inputs
    when (coalescedIn) {
      // when partial, in D is stored if that lane hasn't cleared yet; otherwise, it's out D
      val metas = Mux(partialMode, storedDMeta, peekMeta)
      (inD lazyZip pendingDResp lazyZip metas).foreach { case (in, pending, meta) =>
        in.valid := Mux(partialMode, pending, coalOut.d.valid) && meta.valid
        in.bits := Mux(partialMode, storedDResp, coalOut.d.bits)
        in.bits.source := meta.bits
      }
    }
    // take a new D resp when there's nothing pending
    coalOut.d.ready := pendingDResp.asUInt === 0.U

    coalSource.io.reclaim.valid := coalOut.d.fire
    coalSource.io.reclaim.bits := coalOut.d.bits.source
  }
}

object SingleAddrCoalescer {
  def apply(sourceBits: Int = 2)(implicit p: Parameters): (TLAdapterNode, TLClientNode) = {
    val coalescer = LazyModule(new SingleAddrCoalescer(sourceBits))
    (coalescer.node, coalescer.coalNode)
  }
}