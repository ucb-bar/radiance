package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._
import freechips.rocketchip.devices.tilelink.BuiltInDevices
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.prci.{ClockCrossingType, NoCrossing}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Location

/** One independent L2 cache: its slices, one contiguous region each, and its geometry. Each cache
  * has its own directory, capacity and control block; slice i of the cache owns regions(i), and
  * that region is split into channelsPerSlice contiguous equal DRAM channels. */
case class L2CacheSpec(
  name: String,
  regions: Seq[AddressSet],
  kBPerSlice: Int,
  ways: Int = 8,
  ctrlAddr: Option[BigInt] = None,
  channelsPerSlice: Int = 1,
) {
  require(regions.nonEmpty, s"L2 cache $name has no regions")
  require(channelsPerSlice >= 1 && (channelsPerSlice & (channelsPerSlice - 1)) == 0,
    s"L2 cache $name: channelsPerSlice must be a power of two, got $channelsPerSlice")

  /** The DRAM channel regions, slice by slice: channel j of slice i is part j of regions(i). */
  def channels: Seq[AddressSet] = regions.flatMap { r =>
    require(r.contiguous, s"L2 cache $name: slice region $r is not contiguous")
    val part = (r.mask + 1) / channelsPerSlice
    Seq.tabulate(channelsPerSlice)(j => AddressSet(r.base + j * part, part - 1))
  }
}

/** Several independent L2 caches that together cover the cached memory, with no overlap. Every
  * slice of every cache gets its own DRAM channels (see WithRadianceRegionMem), which no other slice
  * reaches: `channels` lists them, slice by slice. */
case class SplitL2Params(caches: Seq[L2CacheSpec]) {
  def regions: Seq[AddressSet] = caches.flatMap(_.regions)
  def channels: Seq[AddressSet] = caches.flatMap(_.channels)
  regions.combinations(2).foreach { case Seq(a, b) =>
    require(!a.overlaps(b), s"L2 slice regions overlap: $a and $b")
  }
}

case object SplitL2Key extends Field[Option[SplitL2Params]](None)

/** Each L2 slice takes its own edge from the system bus (SplitCoherenceManagerWrapper). */
case object SplitL2SliceLinksKey extends Field[Boolean](true)

object SplitL2 {
  /** Builds one cache between an inward and an outward node. The outward node must present one
    * edge per slice of the spec. Supplied from the chipyard package, where InclusiveCache is
    * visible (see chipyard.WithRadianceSplitL2). */
  type CacheFactory = (HasTileLinkLocations, L2CacheSpec) => (TLInwardNode, TLOutwardNode)
}

case class SplitCoherenceManagerWrapperParams(
    blockBytes: Int,
    beatBytes: Int,
    split: SplitL2Params,
    name: String,
    dtsFrequency: Option[BigInt] = None)
  (val makeCache: SplitL2.CacheFactory)
  extends HasTLBusParams
  with TLBusWrapperInstantiationLike
{
  def instantiate(context: HasTileLinkLocations, loc: Location[TLBusWrapper])(implicit p: Parameters): SplitCoherenceManagerWrapper = {
    val cmWrapper = LazyModule(new SplitCoherenceManagerWrapper(this, context))
    cmWrapper.suggestName(loc.name + "_wrapper")
    context.tlBusWrapperLocationMap += (loc -> cmWrapper)
    cmWrapper
  }
}

/** The COH location with several independent caches. On the inner side one RegionBinder port per
  * slice takes its own edge from the system-bus crossbar, which splits by address: every address has
  * one home, and each slice has its own link. (A single crossbar here gave the system bus one edge to
  * all slices: one 64 B beat per cycle for every cluster together, 94 % busy at 57 B/cycle in memstress
  * on RadianceHBMConfig.) On the outer side each slice leaves through a RegionBinder port restricted to
  * its own region, so the memory-bus crossbar connects it only to that region's DRAM channels. */
class SplitCoherenceManagerWrapper(params: SplitCoherenceManagerWrapperParams, context: HasTileLinkLocations)(implicit p: Parameters)
  extends TLBusWrapper(params, params.name) {
  // SplitL2SliceLinksKey = false keeps the single crossbar (for comparison)
  private val sliceLinks = p(SplitL2SliceLinksKey)
  private val binder = Option.when(sliceLinks)(LazyModule(new RegionBinder(params.split.regions)))
  private val xbar = Option.when(!sliceLinks)(LazyModule(new TLXbar))
  private val outer = TLIdentityNode()

  params.split.caches.foreach { spec =>
    val (cacheIn, cacheOut) = params.makeCache(context, spec)
    if (sliceLinks) {
      // slice i of this cache takes the inner binder port of regions(i); ports are bound in the order of
      // params.split.regions (caches in order, each cache's regions in order)
      val slices = TLIdentityNode()
      cacheIn :*= slices
      spec.regions.foreach { _ => slices := binder.get.node }
    } else {
      cacheIn :*= xbar.get.node
    }
    val outBinder = RegionBinder(spec.regions)
    outBinder :*= cacheOut
    spec.regions.foreach { _ => outer := outBinder }
  }

  def busView: TLEdge = binder.map(_.node.edges.in.head).getOrElse(xbar.get.node.edges.in.head)
  val inwardNode: TLInwardNode = binder.map(b => b.node: TLInwardNode).getOrElse(xbar.get.node)
  val outwardNode: TLOutwardNode = outer
  val builtInDevices = BuiltInDevices.none
  val prefixNode = None
}

/** CoherentBusTopologyParams (SBUS -> COH -> MBUS) with the split wrapper at COH. */
case class SplitL2CoherentBusTopologyParams(
  mbus: MemoryBusParams,
  split: SplitL2Params,
  makeCache: SplitL2.CacheFactory,
  sbusToMbusXType: ClockCrossingType = NoCrossing,
  driveMBusClockFromSBus: Boolean = true
) extends TLBusWrapperTopology(
  instantiations = List(
    (MBUS, mbus),
    (COH, SplitCoherenceManagerWrapperParams(mbus.blockBytes, mbus.beatBytes, split, COH.name)(makeCache))),
  connections = List(
    (SBUS, COH,  TLBusWrapperConnection(driveClockFromMaster = Some(true), nodeBinding = BIND_STAR)()),
    (COH,  MBUS, TLBusWrapperConnection.crossTo(
      xType = sbusToMbusXType,
      driveClockFromMaster = if (driveMBusClockFromSBus) Some(true) else None,
      nodeBinding = BIND_QUERY)))
)

/** Topology half of the split L2; use chipyard.WithRadianceSplitL2, which defines SplitL2Key and
  * supplies the cache factory. Drops the contingent spad, as WithL2SliceTopology does. */
class WithSplitL2Topology(makeCache: SplitL2.CacheFactory) extends Config((site, here, up) => {
  case ContingentSpadKey => None
  case TLNetworkTopologyLocated(InSubsystem) => up(TLNetworkTopologyLocated(InSubsystem)).map {
    case c: CoherentBusTopologyParams =>
      val split = site(SplitL2Key).getOrElse(throw new Exception("WithSplitL2Topology needs SplitL2Key"))
      SplitL2CoherentBusTopologyParams(c.mbus, split, makeCache, c.sbusToMbusXType, c.driveMBusClockFromSBus)
    case other => other
  }
})
