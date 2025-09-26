package radiance.subsystem

import freechips.rocketchip.devices.tilelink.{BuiltInDevices, BuiltInErrorDeviceParams, BuiltInZeroDeviceParams, HasBuiltInDeviceParams}
import freechips.rocketchip.subsystem.{HasTileLinkLocations, PeripheryBusParams}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util.Location
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.LazyModule

case class RadianceCBusParams(
  beatBytes: Int,
  blockBytes: Int,
  dtsFrequency: Option[BigInt] = None,
  zeroDevice: Option[BuiltInZeroDeviceParams] = None,
  errorDevice: Option[BuiltInErrorDeviceParams] = None,
)
  extends HasTLBusParams
  with TLBusWrapperInstantiationLike
  with HasBuiltInDeviceParams
{
  def instantiate(context: HasTileLinkLocations, loc: Location[TLBusWrapper])(implicit p: Parameters): RadianceCBus = {
    val cbus = LazyModule(new RadianceCBus(this, loc.name))
    cbus.suggestName(loc.name)
    context.tlBusWrapperLocationMap += (loc -> cbus)
    cbus
  }
}

class RadianceCBus(params: RadianceCBusParams, name: String)(implicit p: Parameters)
    extends TLBusWrapper(params, name)
{
  override lazy val desiredName = s"RadControlBus_$name"

  private val fixer = LazyModule(new TLFIFOFixer(TLFIFOFixer.all))
  fixer.suggestName(name + "_fixer")
  private val node: TLNode = {
    val in_xbar = LazyModule(new TLXbar(nameSuffix = Some(s"${name}_in")))
    val out_xbar = LazyModule(new TLXbar(nameSuffix = Some(s"${name}_out")))
    (out_xbar.node :*= fixer.node :*= in_xbar.node)
  }

  def inwardNode: TLInwardNode = node
  def outwardNode: TLOutwardNode = node
  def busView: TLEdge = fixer.node.edges.in.head

  val prefixNode = None
  val builtInDevices: BuiltInDevices = BuiltInDevices.attach(params, outwardNode)
}
