package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.tilelink._

/** BankBinderNode with one explicit address region per port instead of the classes of one mask.
  *
  * Port i narrows the managers it passes up to `regions(i)`, so the cache above claims only that
  * range, and narrows the visibility of the clients it passes down to `regions(i)`, so a crossbar
  * below connects port i only to the managers inside it. The second part is what keeps a slice
  * from being wired to another slice's DRAM channel.
  */
case class RegionBinderNode(regions: Seq[AddressSet])(implicit valName: ValName) extends TLCustomNode {
  require(regions.nonEmpty, "RegionBinder needs at least one region")
  regions.combinations(2).foreach { case Seq(a, b) =>
    require(!a.overlaps(b), s"RegionBinder regions overlap: $a and $b")
  }
  // A transfer never crosses an aligned 4 KiB boundary, and every region here is larger than that.
  private val maxXfer = TransferSizes(1, 4096)

  def resolveStar(iKnown: Int, oKnown: Int, iStars: Int, oStars: Int): (Int, Int) = {
    val ports = regions.size
    val oStar = if (oStars == 0) 0 else (ports - oKnown) / oStars
    val iStar = if (iStars == 0) 0 else (ports - iKnown) / iStars
    require(ports == iKnown + iStar * iStars, s"$name must have $ports inputs, but has $iKnown + $iStar*$iStars")
    require(ports == oKnown + oStar * oStars, s"$name must have $ports outputs, but has $oKnown + $oStar*$oStars")
    (iStar, oStar)
  }

  def mapParamsD(n: Int, p: Seq[TLMasterPortParameters]): Seq[TLMasterPortParameters] =
    (p zip regions).map { case (cp, region) => cp.v1copy(clients = cp.clients.map { c => c.v1copy(
      visibility         = c.visibility.flatMap(_.intersect(region)),
      supportsProbe      = c.supports.probe      intersect maxXfer,
      supportsArithmetic = c.supports.arithmetic intersect maxXfer,
      supportsLogical    = c.supports.logical    intersect maxXfer,
      supportsGet        = c.supports.get        intersect maxXfer,
      supportsPutFull    = c.supports.putFull    intersect maxXfer,
      supportsPutPartial = c.supports.putPartial intersect maxXfer,
      supportsHint       = c.supports.hint       intersect maxXfer)})}

  def mapParamsU(n: Int, p: Seq[TLSlavePortParameters]): Seq[TLSlavePortParameters] =
    (p zip regions).map { case (mp, region) => mp.v1copy(managers = mp.managers.flatMap { m =>
      val addresses = m.address.flatMap(_.intersect(region))
      if (addresses.isEmpty) None
      else Some(m.v1copy(
        address            = addresses,
        supportsAcquireT   = m.supportsAcquireT   intersect maxXfer,
        supportsAcquireB   = m.supportsAcquireB   intersect maxXfer,
        supportsArithmetic = m.supportsArithmetic intersect maxXfer,
        supportsLogical    = m.supportsLogical    intersect maxXfer,
        supportsGet        = m.supportsGet        intersect maxXfer,
        supportsPutFull    = m.supportsPutFull    intersect maxXfer,
        supportsPutPartial = m.supportsPutPartial intersect maxXfer,
        supportsHint       = m.supportsHint       intersect maxXfer))
    })}
}

class RegionBinder(regions: Seq[AddressSet])(implicit p: Parameters) extends LazyModule {
  val node = RegionBinderNode(regions)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    (node.in zip node.out).foreach { case ((in, _), (out, _)) => out <> in }
  }
}

object RegionBinder {
  def apply(regions: Seq[AddressSet])(implicit p: Parameters): TLNode =
    LazyModule(new RegionBinder(regions)).node
}
