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

/** DRAM channels that each belong to one L2 slice. With WithRadianceSplitL2 they are the split L2's
  * channels (SplitL2Params.channels: each slice's region in channelsPerSlice contiguous parts); with
  * WithL2Slices, one channel per slice from the same mask. Either way the mapping comes from the
  * slice description itself rather than from convention. */
class WithRadianceRegionMem extends Config((site, here, up) => {
  case ExtMem => up(ExtMem).map(_.copy(nMemoryChannels = 0)) // silence rocket's interleaved ports
  case RadianceMemRegionsKey => {
    val ext = up(ExtMem).getOrElse(
      throw new Exception("WithRadianceRegionMem requires ExtMem"))
    // one channel per L2 slice, from the same description the slices are built from
    val regions: Seq[AddressSet] = site(SplitL2Key).map(_.channels).getOrElse {
      val slices = site(L2SlicesKey).getOrElse(throw new Exception(
        "WithRadianceRegionMem requires WithL2Slices or WithRadianceSplitL2; regions come from the slices"))
      RadianceMemRegions.fromMask(ext.master.base, ext.master.size, slices.mask)
    }
    Some(RadianceMemRegionParams(regions, ext.master.beatBytes, ext.master.idBits))
  }
  case BuildSystem => (q: Parameters) => new RadianceDigitalTop()(q)
})

/** 4 SMs (E4M3 MxGemmini clusters 0..3) on the split L2: one 256 KiB host slice over
  * 0x8000_0000..0x1_0000_0000 with one DRAM channel, and eight 64 KiB GPU slices over 256 MiB each of
  * 0x1_0000_0000..0x1_8000_0000 with two 8 B DRAM channels each (17 channels). GPU memory is hashed over the
  * 16 GPU channels at 64 B (the line; WithGPUAddressHash; consecutive lines alternate slices, and a slice's
  * second channel is offset by half its L2 sets). +loadmem needs an ELF scrambled with --unit 64 --slices 16
  * --channels-per-slice 2 --chan-set-stride 64.
  * GPU caches (WithRadianceHBMCaches): 64 B lines; the cluster L1 is incoherent (TL-UL to the GPU L2) in 4
  * line-interleaved banks with refill forwarding, prefetch, a two-port metadata array and several misses per
  * set. A kernel must write the L1 flush registers (cluster-local 0x82000, 0x82100, 0x82200, 0x82300) after
  * its L0d flush to publish results to the host or to other clusters. */
class RadianceHBMConfig extends Config(
  new WithRadianceHBMCaches ++
  new WithRadianceHBM(gpuChannelsPerSlice = 2, gpuSlices = 8) ++
  new RadianceE4M3MxGemminiFourClusterConfig
)

/** 2 SMs on the split L2 (option 2b of docs/l2-topology-options.md): one 256 KiB host slice over
  * 0x8000_0000..0x1_0000_0000 and four 64 KiB GPU slices over 512 MiB each of
  * 0x1_0000_0000..0x1_8000_0000, each slice with its own DRAM channel (5 channels). GPU memory is
  * hashed over the GPU slices at 32 B granularity (WithGPUAddressHash). +loadmem writes DRAM
  * directly, past the hash, so it needs a scrambled ELF: one whose GPU load segments are already
  * in the hashed layout (radiance-kernels soc/scramble_gpu_elf.py, or make MU_ADDR_HASH=1).
  * To load an unscrambled ELF, use TSI on RadianceHBMTSIConfig.
  * Gemmini is the E4M3 MxGemmini (WithRadianceE4M3MxGemmini: E4M3 inputs only, no FP4; SPAD_REQUANT
  * and the loop retire counter).
  * The L0d holds 8 requests in flight with 8 MSHRs and the cluster L1 32 with 32 (defaults 3 / 4 and
  * 3 / 8). At the defaults these depths limit GPU memory bandwidth before the L2 does: a small
  * rmsnorm on 1 SM takes 202k cycles at the defaults and 120k here. */
class Radiance2SMHBMConfig extends Config(
  new radiance.memory.WithMemParallelism(l0dInFlight = Some(8), l0dMSHRs = Some(8),
    l1InFlight = Some(32), l1MSHRs = Some(32)) ++
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiTwoClusterConfig
)

/** RadianceHBMConfig's memory system for any Radiance config with GPU memory at the top of ExtMem
  * (WithExtGPUMem): split L2, one DRAM channel for the host slice and gpuChannelsPerSlice for each
  * GPU slice, GPU memory hashed over the GPU channels. Loading is the same as on RadianceHBMConfig
  * (+loadmem needs an ELF scrambled for gpuSlices * gpuChannelsPerSlice parts). */
class WithRadianceHBM(gpuChannelsPerSlice: Int = 1, gpuSlices: Int = 4) extends Config(
  new radiance.subsystem.WithGPUAddressHash ++
  new WithRadianceRegionMemPunchthrough ++
  new WithRadianceRegionMem ++
  new WithRadianceSplitL2(hostKB = 256, gpuSlices = gpuSlices, gpuKBPerSlice = 64,
                          gpuChannelsPerSlice = gpuChannelsPerSlice) ++
  // every L2 bank behind the split wrapper's inner crossbar gets 64 sinks: (1 host + gpuSlices) * 64
  new WithSerialTLSinkBits(WithRadianceHBM.sinkBits(gpuSlices))
)

object WithRadianceHBM {
  def sinkBits(gpuSlices: Int): Int = {
    val sinks = 64 * (1 + gpuSlices)
    32 - Integer.numberOfLeadingZeros(sinks - 1)   // ceil(log2(sinks)): 4 slices -> 9, 8 -> 10
  }
}

/** The E4M3-single MxGemmini cluster configs on Radiance2SMHBMConfig's memory system (4 GPU slices, one
  * DRAM channel each). */
class RadianceE4M3MxGemminiSingleClusterHBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiSingleClusterConfig
)

class RadianceE4M3MxGemminiSingleClusterTraceHBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiSingleClusterTraceConfig
)

class RadianceE4M3MxGemminiTwoClusterHBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiTwoClusterConfig
)

class RadianceE4M3MxGemminiTwoClusterTraceHBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiTwoClusterTraceConfig
)

class RadianceE4M3MxGemminiFourClusterHBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceE4M3MxGemminiFourClusterConfig
)

/** Radiance2SMHBMConfig's memory system with an MXFP4-only Gemmini (WithRadianceFP4MxGemmini) in place of the E4M3
  * one: for MXFP4 kernels. Loading is the same as on RadianceHBMConfig (+loadmem needs a scrambled ELF). */
class RadianceFP4HBMConfig extends Config(
  new WithRadianceHBM ++
  new RadianceFP4MxGemminiTwoClusterConfig
)

/** RadianceHBMConfig for loading through TSI (no +loadmem): the GPU is held in reset at power-on,
  * and the run releases it after fesvr has loaded the ELF by passing +init_write=0x41000000:0x0
  * (fesvr performs init writes after the load, before starting hart 0). */
class RadianceHBMTSIConfig extends Config(
  new Config((site, here, up) => {
    case radiance.subsystem.GPUResetKey => up(radiance.subsystem.GPUResetKey).map(_.copy(defaultReset = true))
  }) ++
  new RadianceHBMConfig
)
