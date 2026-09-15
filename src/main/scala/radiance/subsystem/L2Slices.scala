package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.lazymodule._
import org.chipsalliance.diplomacy.nodes._
import freechips.rocketchip.devices.tilelink.BuiltInDevices
import freechips.rocketchip.prci.{ClockCrossingType, NoCrossing}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.subsystem.CoherenceManagerWrapper.CoherenceManagerInstantiationFn
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Location

/** `nSlices` L2 banks, each owning the addresses whose bits above log2(stripeBytes) select it.
  * stripeBytes = gpuSize / nSlices gives contiguous segments; stripeBytes = blockBytes gives
  * rocket-chip's WithNBanks interleave. */
case class L2SliceParams(nSlices: Int, stripeBytes: BigInt) {
  require(nSlices >= 2 && isPow2(nSlices), s"nSlices must be a power of two >= 2, got $nSlices")
  require(stripeBytes > 0 && isPow2(stripeBytes), s"stripeBytes must be a power of two, got $stripeBytes")
  def mask: BigInt = stripeBytes * (nSlices - 1)
  private def isPow2(x: BigInt) = x > 0 && (x & (x - 1)) == 0
  private def isPow2(x: Int) = x > 0 && (x & (x - 1)) == 0
}

case object L2SlicesKey extends Field[Option[L2SliceParams]](None)

/** CoherenceManagerWrapper with a configurable slice-select mask (rocket-chip hardcodes
  * BankBinder(nBanks, blockBytes)). */
case class SlicedCoherenceManagerWrapperParams(
    blockBytes: Int,
    beatBytes: Int,
    slices: L2SliceParams,
    name: String,
    dtsFrequency: Option[BigInt] = None)
  (val coherenceManager: CoherenceManagerInstantiationFn)
  extends HasTLBusParams
  with TLBusWrapperInstantiationLike
{
  require(slices.stripeBytes >= blockBytes, s"stripeBytes (${slices.stripeBytes}) must be at least one cache block ($blockBytes)")
  def instantiate(context: HasTileLinkLocations, loc: Location[TLBusWrapper])(implicit p: Parameters): SlicedCoherenceManagerWrapper = {
    val cmWrapper = LazyModule(new SlicedCoherenceManagerWrapper(this, context))
    cmWrapper.suggestName(loc.name + "_wrapper")
    cmWrapper.halt.foreach { context.anyLocationMap += loc.halt(_) }
    context.tlBusWrapperLocationMap += (loc -> cmWrapper)
    cmWrapper
  }
}

class SlicedCoherenceManagerWrapper(params: SlicedCoherenceManagerWrapperParams, context: HasTileLinkLocations)(implicit p: Parameters)
  extends TLBusWrapper(params, params.name) {
  val (tempIn, tempOut, halt) = params.coherenceManager(context)

  private val coherent_jbar = LazyModule(new TLJbar)
  def busView: TLEdge = coherent_jbar.node.edges.out.head
  val inwardNode = tempIn :*= coherent_jbar.node
  val builtInDevices = BuiltInDevices.none
  val prefixNode = None
  val outwardNode: TLOutwardNode = TLTempNode() :=* BankBinder(params.slices.mask) :*= tempOut
}

/** CoherentBusTopologyParams (SBUS -> COH -> MBUS) with the sliced wrapper. */
case class SlicedCoherentBusTopologyParams(
  mbus: MemoryBusParams,
  coherence: BankedCoherenceParams,
  slices: L2SliceParams,
  sbusToMbusXType: ClockCrossingType = NoCrossing,
  driveMBusClockFromSBus: Boolean = true
) extends TLBusWrapperTopology(
  instantiations = List(
    (MBUS, mbus),
    (COH, SlicedCoherenceManagerWrapperParams(mbus.blockBytes, mbus.beatBytes, slices, COH.name)(coherence.coherenceManager))),
  connections = List(
    (SBUS, COH,  TLBusWrapperConnection(driveClockFromMaster = Some(true), nodeBinding = BIND_STAR)()),
    (COH,  MBUS, TLBusWrapperConnection.crossTo(
      xType = sbusToMbusXType,
      driveClockFromMaster = if (driveMBusClockFromSBus) Some(true) else None,
      nodeBinding = BIND_QUERY)))
)

/** Topology half of the L2 split; use chipyard.WithL2Slices, which adds the capacity split
  * (InclusiveCacheKey is not visible to the radiance sbt project). Drops the contingent spad. */
class WithL2SliceTopology(nSlices: Int, stripeBytes: Option[BigInt] = None) extends Config((site, here, up) => {
  case L2SlicesKey => Some(L2SliceParams(nSlices, stripeBytes.getOrElse {
    val gmem = site(GPUMemory).getOrElse(throw new Exception("WithL2SliceTopology needs GPUMemory to derive stripeBytes"))
    gmem.size / nSlices
  }))
  case SubsystemBankedCoherenceKey => up(SubsystemBankedCoherenceKey).copy(nBanks = nSlices)
  case ContingentSpadKey => None
  case TLNetworkTopologyLocated(InSubsystem) => up(TLNetworkTopologyLocated(InSubsystem)).map {
    case c: CoherentBusTopologyParams =>
      SlicedCoherentBusTopologyParams(c.mbus, c.coherence, site(L2SlicesKey).get, c.sbusToMbusXType, c.driveMBusClockFromSBus)
    case other => other
  }
})
