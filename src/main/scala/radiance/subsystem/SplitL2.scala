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
  * has its own directory, capacity and control block; slice i of the cache owns regions(i). */
case class L2CacheSpec(
  name: String,
  regions: Seq[AddressSet],
  kBPerSlice: Int,
  ways: Int = 8,
  ctrlAddr: Option[BigInt] = None,
) {
  require(regions.nonEmpty, s"L2 cache $name has no regions")
}

/** Several independent L2 caches that together cover the cached memory, with no overlap. Every
  * slice of every cache also gets its own DRAM channel (see WithRadianceRegionMem), so the list of
  * slice regions here is also the list of channels. */
case class SplitL2Params(caches: Seq[L2CacheSpec]) {
  def regions: Seq[AddressSet] = caches.flatMap(_.regions)
  regions.combinations(2).foreach { case Seq(a, b) =>
    require(!a.overlaps(b), s"L2 slice regions overlap: $a and $b")
  }
}

case object SplitL2Key extends Field[Option[SplitL2Params]](None)

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

/** The COH location with several independent caches. One TL-C crossbar splits the inner side by
  * address, so each client reaches each cache over one edge and every address has one home. On the
  * outer side each slice leaves through a RegionBinder port restricted to its own region, so the
  * memory-bus crossbar connects it only to that region's DRAM channel. */
class SplitCoherenceManagerWrapper(params: SplitCoherenceManagerWrapperParams, context: HasTileLinkLocations)(implicit p: Parameters)
  extends TLBusWrapper(params, params.name) {
  private val inner = LazyModule(new TLXbar)
  private val outer = TLIdentityNode()

  params.split.caches.foreach { spec =>
    val (cacheIn, cacheOut) = params.makeCache(context, spec)
    cacheIn :*= inner.node
    val binder = RegionBinder(spec.regions)
    binder :*= cacheOut
    spec.regions.foreach { _ => outer := binder }
  }

  def busView: TLEdge = inner.node.edges.in.head
  val inwardNode = inner.node
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
