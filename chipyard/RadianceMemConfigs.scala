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

/** Cluster L1 replay queue depth per MSHR (rocket's nRPQ, default 16): the requests waiting on one MSHR's
  * refill. */
class WithL1RPQ(n: Int) extends Config((site, here, up) => {
  case ClustersLocated(InSubsystem) =>
    up(ClustersLocated(InSubsystem)).map {
      case c: RadianceClusterAttachParams =>
        c.copy(clusterParams = c.clusterParams.copy(l1Config = c.clusterParams.l1Config.copy(nRPQ = n)))
      case other => other
    }
})

/** The GPU cache hierarchy of RadianceHBMConfig, for 4 SMs on 16 DRAM channels.
  *
  * The smallest set of the measured features and queue sizes that keeps the DRAM channels above 80 % busy in
  * steady state: memstress (streaming loads, 4 SMs, 16 x 8 B channels) reaches 81 % (103 B/cycle of 128) in
  * steady state and 95 B/cycle over the whole load phase. One feature removed at a time costs (whole load
  * phase): prefetch -43 %, 64-set L0d -27 %, L0d fast miss -27 %, refill forwarding -18 %, precise victim nacks
  * -8 %, set-only nacks -8 %, 2 L1 banks instead of 4 -7 %. Several misses per set (WithL1MultiMiss) and the
  * two-port L1 metadata array (WithL1TwoPortMeta) gain about 1 % here and are left out. With a coherent L1 the
  * GPU L2 slices' schedulers (one MSHR action per cycle) limit the channels to ~63 %.
  * Kernels: the GPU address hash unit follows the 64 B line (scramble with --unit 64 --chan-set-stride 64),
  * and a kernel must write the L1 flush registers to publish GPU results (the L1 is incoherent). */
class WithRadianceHBMCaches extends Config(
  new WithL0dUnordered ++
  new WithL0dFastMiss ++
  new WithL1PreciseVictimNack ++
  new WithL1NackBlocksSetOnly ++
  new WithL1Banks(4) ++
  new WithL1Prefetch(8) ++
  new WithL1RefillForward(4) ++
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
  // 64 B memory bus: an L2 refill is one beat. At 16 B a refill held TLCacheCork's D arbiter for 4 beats, its
  // ReleaseAck queue then refused the L2's clean victim Releases, and each L2 miss waited for its victim's
  // Release before its Acquire (memstress, 4 SMs: L2 outer C stalled 66 %). The DRAM channels stay 8 B.
  new WithMemoryBusBeatBytes(64) ++
  // queue sizes: halving the coalescer and L0d queues (to 8) or the L1 MSHRs (to 32, 8 per bank) costs 13-15 %;
  // memstress does not reach the L1 in-flight (32 per bank) or replay queue (4) limits
  new WithL1RPQ(4) ++
  new WithMemParallelism(coalSrcIds = Some(16), coalRespDepth = Some(16),
    l0dInFlight = Some(16), l0dMSHRs = Some(16), l0dReorderDepth = Some(32),
    l1InFlight = Some(32), l1MSHRs = Some(64))
)

/** The L0d takes L1 responses in any order (L0dUnorderedKey; needs equal L0d and L1 lines). */
class WithL0dUnordered(enable: Boolean = true) extends Config((site, here, up) => {
  case L0dUnorderedKey => enable
})

/** GPU L2 slice geometry (the "gpu" cache of the split L2): capacity per slice and ways. The inclusive cache
  * holds one active MSHR per set, so more sets dilute same-set misses. */
class WithGPUL2Geometry(kBPerSlice: Int, ways: Int) extends Config((site, here, up) => {
  case radiance.subsystem.SplitL2Key => up(radiance.subsystem.SplitL2Key).map(s => s.copy(caches = s.caches.map(c =>
    if (c.name == "gpu") c.copy(kBPerSlice = kBPerSlice, ways = ways) else c)))
})
