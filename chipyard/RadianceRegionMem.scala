package chipyard

import chisel3._
import chisel3.reflect.DataMirror
import org.chipsalliance.cde.config.{Config, Parameters}
import org.chipsalliance.diplomacy.lazymodule.InModuleBody
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.prci.{ClockSinkNode, ClockSinkParameters}
import freechips.rocketchip.subsystem.{ExtMem, HasTileLinkLocations, MBUS, MemoryBusKey}
import chipyard.iobinders.{AXI4MemPort, GetSystemParameters, OverrideLazyIOBinder}
import radiance.subsystem.{CanHaveRadianceRegionMemPort, L2SlicesKey, RadianceMemRegionParams, RadianceMemRegions, RadianceMemRegionsKey}
import testchipip.util.ClockedIO

/** DigitalTop plus one AXI4 memory port per address region. Subclassing rather than replacing:
  * rocket's CanHaveMasterAXI4MemPort stays mixed in and is silenced by nMemoryChannels = 0. */
class RadianceDigitalTop(implicit p: Parameters) extends DigitalTop
  with CanHaveRadianceRegionMemPort

/** Punches the region memory ports out of the system, as ordinary AXI4MemPorts so that the stock
  * harness binders (WithBlackBoxSimMem, WithSimAXIMem) pick them up unchanged. */
class WithRadianceRegionMemPunchthrough extends OverrideLazyIOBinder({
  (system: CanHaveRadianceRegionMemPort) => {
    implicit val p: Parameters = GetSystemParameters(system)
    val clockSinkNode = p(RadianceMemRegionsKey).map(_ => ClockSinkNode(Seq(ClockSinkParameters())))
    clockSinkNode.map(_ := system.asInstanceOf[HasTileLinkLocations].locateTLBusWrapper(MBUS).fixedClockNode)
    def clockBundle = clockSinkNode.get.in.head._1

    InModuleBody {
      val ports: Seq[AXI4MemPort] = system.radiance_mem_axi4.zipWithIndex.map { case (m, i) =>
        val port = IO(new ClockedIO(DataMirror.internal.chiselTypeClone[AXI4Bundle](m))).suggestName(s"axi4_mem_$i")
        port.bits <> m
        port.clock := clockBundle.clock
        // The harness is handed the WHOLE ExtMem range, not this region's: SimDRAM keys its
        // backing store on the base, so all regions share one image and the ELF still loads once.
        // Per-region models can branch on `edge` instead, which carries this region's AddressSet.
        AXI4MemPort(() => port, p(ExtMem).get, system.radianceMemAXI4Node.edges.in(i),
                    p(MemoryBusKey).dtsFrequency.get.toInt)
      }.toSeq
      (ports, Nil)
    }
  }
})

/** One DRAM channel per L2 slice, each owning the slice's contiguous region.
  * Requires WithL2Slices; the regions are derived from the same mask, so the mapping is 1:1 by
  * construction rather than by convention. */
class WithRadianceRegionMem extends Config((site, here, up) => {
  case ExtMem => up(ExtMem).map(_.copy(nMemoryChannels = 0)) // silence rocket's interleaved ports
  case RadianceMemRegionsKey => {
    val slices = site(L2SlicesKey).getOrElse(
      throw new Exception("WithRadianceRegionMem requires WithL2Slices; regions come from its mask"))
    val ext = up(ExtMem).getOrElse(
      throw new Exception("WithRadianceRegionMem requires ExtMem"))
    val regions: Seq[AddressSet] =
      RadianceMemRegions.fromMask(ext.master.base, ext.master.size, slices.mask)
    Some(RadianceMemRegionParams(regions, ext.master.beatBytes, ext.master.idBits))
  }
  case BuildSystem => (q: Parameters) => new RadianceDigitalTop()(q)
})

/** 4 L2 slices on contiguous 1 GiB regions of ExtMem, each with its own DRAM channel. */
class RadianceTapeoutSimRegionMemConfig extends Config(
  new WithRadianceRegionMemPunchthrough ++
  new WithRadianceRegionMem ++
  new WithL2Slices(4, stripeBytes = Some(x"4000_0000")) ++
  new RadianceTapeoutSimConfig
)
