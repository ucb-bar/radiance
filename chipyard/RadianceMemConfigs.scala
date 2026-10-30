package chipyard

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.subsystem.{ClustersLocated, InCluster, InSubsystem, MemoryBusKey, TilesLocated}
import radiance.memory._
import radiance.subsystem.{MuonTileAttachParams, RadianceClusterAttachParams, RadianceMemRegionsKey}

// Memory-system fragments for the Radiance GPU caches (cluster L1, L0d, L0i) and the DRAM channels. The
// measurements behind RadianceHBMConfig's choices are memstress streaming loads on 4 SMs and 16 channels.

/** Memory bus beat width (the GPU L2 slices' outer ports). */
class WithMemoryBusBeatBytes(beatBytes: Int) extends Config((site, here, up) => {
  case MemoryBusKey => up(MemoryBusKey).copy(beatBytes = beatBytes)
})

/** DRAM channel AXI data width. WithRadianceRegionMem builds the channels from up(ExtMem), below any ExtMem
  * override above it, so the width is set on the region parameters themselves. */
class WithDRAMChannelBeatBytes(beatBytes: Int) extends Config((site, here, up) => {
  case RadianceMemRegionsKey => up(RadianceMemRegionsKey).map(_.copy(beatBytes = beatBytes))
})

/** L0d geometry for every Muon tile in a cluster. The L0d MSHR file allows one outstanding miss per set
  * (rocket's rule), so streams that collide modulo the L0d size are nacked; more sets dilute that. */
class WithL0dGeometry(nSets: Int, nWays: Int) extends Config((site, here, up) => {
  case TilesLocated(loc @ InCluster(_)) =>
    up(TilesLocated(loc)).map {
      case m: MuonTileAttachParams =>
        m.copy(tileParams = m.tileParams.copy(
          dcache = m.tileParams.dcache.map(_.copy(nSets = nSets, nWays = nWays))))
      case other => other
    }
})

/** Muon load/store unit depths for every Muon tile in a cluster. The core reads MuonKey at its site, so both
  * MuonKey and the tiles' copy are set. */
class WithLsuDepths(loadDataEntries: Int, addressEntries: Int) extends Config((site, here, up) => {
  case radiance.muon.MuonKey =>
    val k = up(radiance.muon.MuonKey)
    k.copy(lsu = k.lsu.copy(loadDataEntries = loadDataEntries, addressEntries = addressEntries))
  case TilesLocated(loc @ InCluster(_)) =>
    up(TilesLocated(loc)).map {
      case m: MuonTileAttachParams =>
        val core = m.tileParams.core
        m.copy(tileParams = m.tileParams.copy(core = core.copy(lsu = core.lsu.copy(
          loadDataEntries = loadDataEntries, addressEntries = addressEntries))))
      case other => other
    }
})

/** Cluster L1 line size at the same capacity and associativity; the Muon tiles' L0d-to-L1 fragmenter follows
  * (MuonTileParams.l1CacheLineBytes). TLNBDCache needs one data-array row per line. */
class WithL1BlockBytes(blockBytes: Int) extends Config((site, here, up) => {
  case ClustersLocated(InSubsystem) =>
    up(ClustersLocated(InSubsystem)).map {
      case c: RadianceClusterAttachParams =>
        val l1 = c.clusterParams.l1Config
        val bytes = l1.nSets * l1.nWays * l1.blockBytes
        c.copy(clusterParams = c.clusterParams.copy(l1Config = l1.copy(
          blockBytes = blockBytes, rowBits = blockBytes * 8, nSets = bytes / (l1.nWays * blockBytes))))
      case other => other
    }
})

/** Cluster L1 set count at the same line size and ways (capacity changes); split across L1 banks. */
class WithL1Sets(nSets: Int) extends Config((site, here, up) => {
  case ClustersLocated(InSubsystem) =>
    up(ClustersLocated(InSubsystem)).map {
      case c: RadianceClusterAttachParams =>
        c.copy(clusterParams = c.clusterParams.copy(l1Config = c.clusterParams.l1Config.copy(nSets = nSets)))
      case other => other
    }
})

/** L0i line size at the same capacity. The L0i shares the cluster L1's input port, whose beat is the L1 line,
  * and rocket's cache needs its row = its outer beat, so the L0i line follows the L1 line. */
class WithL0iBlockBytes(blockBytes: Int) extends Config((site, here, up) => {
  case TilesLocated(loc @ InCluster(_)) =>
    up(TilesLocated(loc)).map {
      case m: MuonTileAttachParams =>
        m.copy(tileParams = m.tileParams.copy(icacheUsingD = m.tileParams.icacheUsingD.map { c =>
          val bytes = c.nSets * c.nWays * c.blockBytes
          c.copy(blockBytes = blockBytes, rowBits = blockBytes * 8, nSets = bytes / (c.nWays * blockBytes))
        }))
      case other => other
    }
})

/** A coherent cluster L1 releases clean victims to the L2 (L1CleanReleaseKey), so an inclusive L2 does not
  * probe them back on its own evictions. No effect on an incoherent L1. */
class WithL1CleanRelease(enable: Boolean = true) extends Config((site, here, up) => {
  case L1CleanReleaseKey => enable
})

/** The cluster L1 is a TL-UL client of the GPU L2 (L1IncoherentKey): no probes, dirty lines written back on
  * eviction or by the L1 flush registers (cluster-local 0x82000 + 0x100 x bank), which a kernel must write to
  * publish its results. */
class WithL1Incoherent(enable: Boolean = true) extends Config((site, here, up) => {
  case L1IncoherentKey => enable
})

/** Refill forwarding line buffers in the cluster L1 (L1RefillForwardKey); also used by WithL0dFastMiss. */
class WithL1RefillForward(slots: Int) extends Config((site, here, up) => {
  case L1RefillForwardKey => slots
})

/** Tagged sequential prefetch in the cluster L1, distance in lines of the address space (L1PrefetchDistanceKey). */
class WithL1Prefetch(distance: Int) extends Config((site, here, up) => {
  case L1PrefetchDistanceKey => distance
})

/** Cluster L1 metadata array with a read and a write port (L1TwoPortMetaKey). */
class WithL1TwoPortMeta(enable: Boolean = true) extends Config((site, here, up) => {
  case L1TwoPortMetaKey => enable
})

/** Cluster L1 banks interleaved by line (L1BanksKey); needs WithL1Incoherent. */
class WithL1Banks(n: Int) extends Config((site, here, up) => {
  case L1BanksKey => n
})

/** A nack in the cluster L1 input adapter blocks only its own set (L1NackBlocksSetOnlyKey), as in the L0d. */
class WithL1NackBlocksSetOnly(enable: Boolean = true) extends Config((site, here, up) => {
  case L1NackBlocksSetOnlyKey => enable
})

/** The L0d skips the victim metadata clear and forwards refills (L0dFastMissKey). */
class WithL0dFastMiss(enable: Boolean = true) extends Config((site, here, up) => {
  case L0dFastMissKey => enable
})

/** The cluster L1 nacks a hit only on a way being filled (L1PreciseVictimNackKey). */
class WithL1PreciseVictimNack(enable: Boolean = true) extends Config((site, here, up) => {
  case L1PreciseVictimNackKey => enable
})

/** The cluster L1 holds several misses per set (L1MultiMissKey; incoherent, precise victim nacks). */
class WithL1MultiMiss(enable: Boolean = true) extends Config((site, here, up) => {
  case L1MultiMissKey => enable
})

/** The GPU cache hierarchy of RadianceHBMConfig, for 4 SMs on 16 DRAM channels.
  *
  * In memstress (streaming loads, 4 SMs, 16 x 8 B channels) this reaches 52 B/cycle, 41 % of the channels'
  * 128 B/cycle, against 12 B/cycle with the earlier RadianceHBMConfig caches. Approximate contributions:
  * 64 B lines in the L1 / L2 (with the 512-bit system bus they need) ~1.7x; the incoherent L1 with refill
  * forwarding +18-26 %; several misses per set and precise victim nacks ~+10 %; prefetch 8 lines ~+9 %;
  * 4 L1 banks, set-only nacks, the L0d fast miss and the two-port metadata array a few % each.
  * Kernels: the GPU address hash unit follows the 64 B line (scramble with --unit 64 --chan-set-stride 64),
  * and a kernel must write the L1 flush registers to publish GPU results (the L1 is incoherent). */
class WithRadianceHBMCaches extends Config(
  new WithL0dFastMiss ++
  new WithL1MultiMiss ++
  new WithL1PreciseVictimNack ++
  new WithL1NackBlocksSetOnly ++
  new WithL1Banks(4) ++
  new WithL1TwoPortMeta ++
  new WithL1Prefetch(8) ++
  new WithL1RefillForward(8) ++
  new WithL1Incoherent ++
  new WithDRAMChannelBeatBytes(8) ++
  new WithL0iBlockBytes(64) ++
  new WithL1BlockBytes(64) ++
  // rocket's cache needs its data row = its outer beat and TLNBDCache needs row = line, so a 64 B L1 line
  // needs a 64 B cluster-to-L2 bus; the cluster's csbus takes the system bus width
  new _root_.chipyard.config.WithSystemBusWidth(512) ++
  new freechips.rocketchip.subsystem.WithCacheBlockBytes(64) ++
  // a 256-set direct-mapped L0d: fewer same-set miss conflicts between warps' streams
  new WithL0dGeometry(nSets = 256, nWays = 1) ++
  // each GPU L2 slice feeds two 8 B channels
  new WithMemoryBusBeatBytes(16) ++
  new WithMemParallelism(coalSrcIds = Some(32), coalRespDepth = Some(32),
    l0dInFlight = Some(32), l0dMSHRs = Some(32), l0dReorderDepth = Some(64),
    l1InFlight = Some(128), l1MSHRs = Some(128))
)
