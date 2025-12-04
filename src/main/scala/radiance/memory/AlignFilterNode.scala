package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.BundleField
import freechips.rocketchip.diplomacy.{AddressSet, IdRange}
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.ValName
import org.chipsalliance.diplomacy.lazymodule._

// this node splits the incoming requests into two outgoing edges,
// the first edge contains requests that match the filter AddressSet,
// and the second edge contains requests that don't.
// on the return leg, the two responses are arbitrated in a RR fashion.
class AlignFilterNode(filters: Seq[AddressSet], sourceBits: Int = 0)(implicit p: Parameters) extends LazyModule {

  val node = TLNexusNode(clientFn = seq => {
    require(seq.map(_.masters.size).sum == 1, s"there should only be one client to a filter node, " +
      s"found ${seq.map(_.masters.size).sum}")
    val master = seq.head.masters.head

    val inMapping = if (sourceBits > 0) {
      TLXbar.mapInputIds(Seq.fill(filters.length)(seq.head))
    } else {
      TLXbar.mapInputIds(Seq.fill(filters.length + 1)(seq.head))
    }
    val unalignedSrcRange = inMapping.last

    val realSourceBits = log2Ceil(seq.head.masters.head.sourceId.end)
    require(sourceBits == 0 || (sourceBits == realSourceBits),
      s"set align filter source bits to $realSourceBits")

    seq.head.v1copy(
      clients = filters.zipWithIndex.map { case (filter, i) =>
        master.v2copy(
          name = s"${name}_filter_aligned",
          sourceId = inMapping(i),
          visibility = Seq(filter),
        )
      } ++ Option.when(sourceBits == 0) {
        master.v2copy(
          name = s"${name}_filter_unaligned",
          sourceId = unalignedSrcRange,
          visibility = Seq(AddressSet.everything),
        )
      }
    )
  }, managerFn = seq => {
    val addresses = seq.flatMap(_.slaves.flatMap(_.address))
    val fullAddresses = addresses.map { a =>
      val newMask = (BigInt(1) << log2Ceil(a.mask)) - 1
      AddressSet(a.base & ~newMask, newMask)
    }
    seq.head.v1copy(
      responseFields = BundleField.union(seq.flatMap(_.responseFields)),
      requestKeys = seq.flatMap(_.requestKeys).distinct,
      minLatency = seq.map(_.minLatency).min,
      endSinkId = TLXbar.mapOutputIds(seq).map(_.end).max,
      managers = Seq(TLSlaveParameters.v2(
        name = Some(s"${name}_manager"),
        address = fullAddresses,
        supports = seq.map(_.anySupportClaims).reduce(_ mincover _)
      ))
    )
  })

  val unalignedNode = Option.when(sourceBits > 0) {
    TLClientNode(Seq(TLMasterPortParameters.v2(Seq(TLMasterParameters.v2(
      name = s"${name}_filter_unaligned",
      sourceId = IdRange(0, 1 << sourceBits),
      visibility = Seq(AddressSet.everything),
    )))))
  }

  def castD[T <: TLBundleD](d: TLBundleD, targetDType: T): T = {
    val newD = Wire(targetDType.cloneType)
    d.elements.foreach { case (name, data) =>
      val newDField = newD.elements.filter(_._1 == name).head._2
      newDField := data.asTypeOf(newDField)
    }
    newD
  }

  def castD[T <: DecoupledIO[TLBundleD]](ds: Seq[DecoupledIO[TLBundleD]], targetDType: T): Seq[T] = {
    ds.map { d =>
      val newD = Wire(targetDType.cloneType)
      newD.valid := d.valid
      newD.bits := castD(d.bits, targetDType.bits)
      d.ready := newD.ready
      newD
    }
  }

  lazy val module = new LazyModuleImp(this) {
    unalignedNode match {
      case Some(unaligned) =>
        val (c, cEdge) = node.in.head
        val a = node.out.map(_._1)
        val ua = unaligned.out.head._1

        val inMapping = TLXbar.mapInputIds(Seq.fill(filters.length)(node.in.head._2.client))

        val aAligned = filters.map(_.contains(c.a.bits.address))

        (a zip aAligned).zipWithIndex.foreach { case ((a, aligned), idx) =>
          a.a.bits := c.a.bits
          a.a.bits.source := inMapping(idx).start.U + c.a.bits.source
          a.a.valid := c.a.valid && aligned
        }
        ua.a.bits := c.a.bits
        ua.a.bits.source := c.a.bits.source
        ua.a.valid := c.a.valid && !aAligned.reduce(_ || _)
        c.a.ready := MuxCase(ua.a.ready, (a zip aAligned).map { case (a, aligned) => aligned -> a.a.ready })

        TLArbiter.robin(cEdge, c.d, castD(a.map(_.d) ++ Seq(ua.d), c.d): _*)

      case None =>
        val (c, cEdge) = node.in.head
        val a = node.out.init.map(_._1)
        val ua = node.out.last._1

        val inMapping = TLXbar.mapInputIds(Seq.fill(filters.length + 1)(node.in.head._2.client))
        val unalignedSrc = inMapping.last

        val aAligned = filters.map(_.contains(c.a.bits.address))

        (a zip aAligned).zipWithIndex.foreach { case ((a, aligned), idx) =>
          a.a.bits := c.a.bits
          a.a.bits.source := inMapping(idx).start.U + c.a.bits.source
          a.a.valid := c.a.valid && aligned
        }
        ua.a.bits := c.a.bits
        ua.a.bits.source := unalignedSrc.start.U + c.a.bits.source // + (1.U << c.a.bits.source.getWidth)
        ua.a.valid := c.a.valid && !aAligned.reduce(_ || _)
        c.a.ready := MuxCase(ua.a.ready, (a zip aAligned).map { case (a, aligned) => aligned -> a.a.ready })

        TLArbiter.robin(cEdge, c.d, castD(a.map(_.d) ++ Seq(ua.d), c.d): _*)
    }
  }
}

object AlignFilterNode {
  def apply(filters: Seq[AddressSet])(implicit p: Parameters, valName: ValName): TLNexusNode = {
    LazyModule(new AlignFilterNode(filters)).node
  }

  def apply(filters: Seq[AddressSet], sourceBits: Int)
           (implicit p: Parameters, valName: ValName): (TLNexusNode, TLClientNode) = {
    val alignFilter = LazyModule(new AlignFilterNode(filters, sourceBits))
    (alignFilter.node, alignFilter.unalignedNode.get)
  }
}
