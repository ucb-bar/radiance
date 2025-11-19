package radiance.memory

import chisel3._
import chisel3.experimental.SourceInfo
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.{BundleField, UIntIsOneOf}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule._

class CollectorNode(from: Int, to: Int)(implicit p: Parameters) extends LazyModule {
  require(isPow2(from) && isPow2(to) && (from <= to), "invalid collector node parameters")
  val numManagers = to / from

  val node = TLNexusNode(clientFn = seq => {
    val master = seq.head.masters.head
    require(isPow2(master.sourceId.size))

    seq.head.v1copy(
      clients = Seq(master.v2copy(
        name = s"${name}_collected_client",
        emits = TLMasterToSlaveTransferSizes(
          get = TransferSizes(to, to),
          putFull = TransferSizes(to, to),
          putPartial = TransferSizes(to, to)
        ),
      ))
    )
  }, managerFn = seq => {
    require(seq.map(_.slaves.size).sum == 1, s"there should only be one manager to a" +
      s"collector node, found ${seq.map(_.slaves.size).sum}")

    seq.head.v1copy(
      responseFields = BundleField.union(seq.flatMap(_.responseFields)),
      requestKeys = seq.flatMap(_.requestKeys).distinct,
      minLatency = seq.map(_.minLatency).min,
      endSinkId = TLXbar.mapOutputIds(seq).map(_.end).max,
      managers = Seq.tabulate(numManagers) { i =>
        seq.head.slaves.head.v2copy(
          name = Some(s"${name}_collector_manager_$i"),
          address = AddressSet.unify(seq.flatMap(_.slaves.flatMap(_.address))),
          supports = TLMasterToSlaveTransferSizes(
            get = TransferSizes(from, from),
            putFull = TransferSizes(from, from),
            putPartial = TransferSizes(from, from)
          ),
          fifoId = Some(0),
        )
      },
      beatBytes = from
    )
  })

  lazy val module = new LazyModuleImp(this) {
    val inNodesAndEdges = node.in
    val outNodesAndEdges = node.out
    val (ins, _inEdges) = (inNodesAndEdges.map(_._1), inNodesAndEdges.map(_._2))
    val (out, outEdge) = (outNodesAndEdges.head._1, outNodesAndEdges.head._2)
    val head = ins.head

    require(ins.length == numManagers)

    // A
    ins.zipWithIndex.foreach { case (x, i) =>
      // lane d valid only if self valid and all other lanes ready
      // this is because out.d fires only if all lanes are ready, but we cannot
      // combinationally couple ready -> valid for a given lane
      x.d.valid := out.d.valid &&
        VecInit(ins.filter(_ != x).map(_.d.ready)).asUInt.andR

      x.d.bits := out.d.bits // inEdges.head.AccessAck(x.a.bits)
      x.a.ready := out.a.ready

      // these two assertions may not be necessary due to masking, disable if causing trouble
      assert(x.a.valid === head.a.valid, "non-full access")
      assert(x.a.fire === head.a.fire)

      assert(!x.a.valid || x.a.bits.opcode.isOneOf(TLMessages.PutFullData, TLMessages.PutPartialData))
      assert(!x.a.valid || (x.a.bits.size === log2Ceil(from).U), s"collected write size should be $from bytes")
      assert(!x.a.valid || (x.a.bits.address === head.a.bits.address + (i * from).U),
        "unexpected address strides")
    }
    assert(!head.a.valid || !(head.a.bits.address & (to - 1).U).orR,
      "head address unaligned")

    out.a.valid := head.a.valid
    out.a.bits := outEdge.Put(
      fromSource = head.a.bits.source,
      toAddress = head.a.bits.address,
      lgSize = log2Ceil(to).U,
      data = VecInit(ins.map(_.a.bits.data)).asUInt,
      mask = VecInit(ins.map(_.a.valid)).asUInt,
    )._2
    // out.a.bits.data := VecInit(ins.map(_.a.bits.data)).asTypeOf(UInt((to * 8).W))

    // D

    val currDFires = VecInit(ins.map(_.d.fire))
    val pendingDResp = RegInit(VecInit.fill(numManagers)(false.B))
    val storedDResp = RegEnable(out.d.bits, 0.U.asTypeOf(out.d.bits), out.d.fire)
    val partialMode = pendingDResp.asUInt.orR
    // enter partial mode when cannot clear all D lanes in one cycle
    when (out.d.fire && !currDFires.asUInt.andR) {
      assert(pendingDResp.asUInt === 0.U)
      pendingDResp := currDFires.map(!_) // unfired lanes
    }
    // when in partial mode, clear lanes that fire
    when (partialMode) {
      (pendingDResp zip currDFires).foreach { case (p, f) => p := p && !f }
    }
    // when partial, in D is stored if that lane hasn't cleared yet; otherwise, it's out D
    (ins zip pendingDResp).foreach { case (in, pending) =>
      in.d.valid := Mux(partialMode, pending, out.d.valid)
      in.d.bits := Mux(partialMode, storedDResp, out.d.bits)
    }
    // take a new D resp when there's nothing pending
    out.d.ready := pendingDResp.asUInt === 0.U
  }
}

object CollectorNode {
  def apply(from: Int, to: Int)(implicit p: Parameters, valName: ValName, sourceInfo: SourceInfo): TLNexusNode = {
    LazyModule(new CollectorNode(from, to)).node
  }
}
