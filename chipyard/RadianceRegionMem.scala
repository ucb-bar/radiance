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
import radiance.subsystem.{CanHaveRadianceRegionMemPort, L2SlicesKey, SplitL2Key, RadianceMemRegionParams, RadianceMemRegions, RadianceMemRegionsKey}
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
    val ext = up(ExtMem).getOrElse(
      throw new Exception("WithRadianceRegionMem requires ExtMem"))
    // one channel per L2 slice, from the same description the slices are built from
    val regions: Seq[AddressSet] = site(SplitL2Key).map(_.regions).getOrElse {
      val slices = site(L2SlicesKey).getOrElse(throw new Exception(
        "WithRadianceRegionMem requires WithL2Slices or WithRadianceSplitL2; regions come from the slices"))
      RadianceMemRegions.fromMask(ext.master.base, ext.master.size, slices.mask)
    }
    Some(RadianceMemRegionParams(regions, ext.master.beatBytes, ext.master.idBits))
  }
  case BuildSystem => (q: Parameters) => new RadianceDigitalTop()(q)
})

/** Split L2 (option 2b of docs/l2-topology-options.md): one 256 KiB host slice over
  * 0x8000_0000..0x1_0000_0000 and four 64 KiB GPU slices over 512 MiB each of
  * 0x1_0000_0000..0x1_8000_0000, each slice with its own DRAM channel (5 channels). */
class RadianceHBMConfig extends Config(
  new WithRadianceRegionMemPunchthrough ++
  new WithRadianceRegionMem ++
  new WithRadianceSplitL2(hostKB = 256, gpuSlices = 4, gpuKBPerSlice = 64) ++
  new WithSerialTLSinkBits(9) ++
  new RadianceTapeoutSimConfig
)
