package radiance.memory

import chisel3.util.isPow2
import org.chipsalliance.cde.config.{Config, Field}

/** Outstanding-request limits of the GPU memory path. This key is the only place these values are
  * set: WithMuonCores and WithRadianceCluster copy the MSHR counts into the L0d and cluster L1
  * DCacheParams, which is where every consumer reads them, and the cache configs (L0dCacheConfig,
  * L1CacheConfig) do not state them. The in-flight depths and the coalescer depth have no other
  * home and are read from this key where those blocks are built. The cache tag widths follow from
  * the resulting TileLink source widths (TLNBDCache.reqTagBits).
  *
  * An in-flight depth is a DepthHellaCacheIF buffer size. That buffer holds every request, hit or
  * miss, until its response arrives, so the depth caps the memory-level parallelism of the port and
  * has to cover the port's round trip: one request per cycle needs depth >= mean round-trip
  * latency. See the header of DepthHellaCacheIF.scala.
  *
  * @param l0iInFlight  requests the fetch adapter holds in flight. rocket hardcodes 3; the fetch
  *                     port's round trip is 2 cycles on a hit and 5.50 on the mean, the mean pulled
  *                     up by a 6.4% miss rate whose tail reaches 72 cycles, so 3 caps fetch at 0.39
  *                     instructions per cycle while the L0i sits ready 95% of cycles and nacks zero
  *                     times, and the instruction buffers starve 92% of the time. 8 covers the mean
  *                     with margin for the tail: fetch 0.39 -> 0.90 instructions per cycle,
  *                     imem_req_ready 54% -> 100%, instruction buffers non-empty 8% -> 84%, and the
  *                     best L0d-resident loop 3.07 -> 2.59 cycles per 64 B line (24.7 B/cyc, 38.6%
  *                     of line rate). Cost: five extra HellaCacheReq registers per port, +6,712
  *                     flop bits over four cores. Raising it past 8 did not help (2.81 at 16): the
  *                     entries are held by latency, not by capacity.
  *
  *                     This was reverted once. At 8 the reads of `fflags` in
  *                     rv32uzfh-p-{fadd,fdiv,fmadd} began returning the value from before the
  *                     preceding FP op, because the Fix 11 interlock (`fpBusy`, from each FP pipe's
  *                     `occupied`) releases as soon as the pipe responds and the faster fetch lets
  *                     the CSR read arrive in exactly that cycle. Ledger row 11 already recorded
  *                     that hole as the reason rv32uzfh-p-fcvt_w failed. The exception-flag checks
  *                     have since been removed from the test macros (this machine does not support
  *                     them), so nothing observes the stale read and the suite is 82/83 at depth 3
  *                     and at depth 8, the single failure being vx32-p-wspawn, which is
  *                     upstream-waived. The interlock hole is still there. If exception flags ever
  *                     matter, fix `fpBusy` to cover an FP instruction from issue rather than from
  *                     FP-pipe entry before relying on fcsr reads.
  * @param l0dInFlight  requests the L0d input adapter holds in flight
  * @param l0dMSHRs     L0d MSHRs
  * @param l1InFlight   requests the cluster L1 input adapter holds in flight
  * @param l1MSHRs      cluster L1 MSHRs
  * @param coalSrcIds   coalesced requests one core can have outstanding on the TileLink side:
  *                     the coalescer's source-id space. A power of two.
  * @param coalRespDepth entries in the coalescer's response queue. Uncoalescing one 64-byte
  *                     response puts an entry in every lane's queue, so this is the core's
  *                     memory-level parallelism for coalesced traffic. At 2 a fully occupied core
  *                     sustained 1.7 loads in flight and retired one every 69 cycles whatever the
  *                     working set (runs/mlp2_*, runs/ms_fix_v2). Affordable at 8 since Fix 13
  *                     narrowed an entry from 521 bits to 41. Distinct from coalSrcIds: the source
  *                     space bounds what TileLink can track, this bounds what the core can absorb.
  * @param l0dReorderDepth requests the L0d-to-L1 ResponseFIFOFixer can hold in flight; A waits
  *                     while all are taken. Indexed by source id instead, that fixer needed one
  *                     277-bit entry for each of 64 ids per core, while its measured peak occupancy
  *                     on core 0 is 5 to 6 at the default adapter depths and 15 to 16 with
  *                     l0dInFlight or l0dMSHRs raised to 8 (memstress sat_8K_w8, loop_w8_long).
  */
case class MemParallelismParams(
  l0iInFlight: Int = 8,
  l0dInFlight: Int = 3,
  l0dMSHRs: Int = 4,
  l1InFlight: Int = 3,
  l1MSHRs: Int = 8,
  coalSrcIds: Int = 8,
  coalRespDepth: Int = 8,
  l0dReorderDepth: Int = 16,
) {
  require(isPow2(coalSrcIds), s"coalSrcIds must be a power of two, got $coalSrcIds")
}

case object MemParallelismKey extends Field[MemParallelismParams](MemParallelismParams())

/** Overrides the given limits and keeps the others. */
class WithMemParallelism(
  l0iInFlight: Option[Int] = None,
  l0dInFlight: Option[Int] = None,
  l0dMSHRs: Option[Int] = None,
  l1InFlight: Option[Int] = None,
  l1MSHRs: Option[Int] = None,
  coalSrcIds: Option[Int] = None,
  coalRespDepth: Option[Int] = None,
  l0dReorderDepth: Option[Int] = None,
) extends Config((site, here, up) => {
  case MemParallelismKey => {
    val prev = up(MemParallelismKey)
    MemParallelismParams(
      l0iInFlight = l0iInFlight.getOrElse(prev.l0iInFlight),
      l0dInFlight = l0dInFlight.getOrElse(prev.l0dInFlight),
      l0dMSHRs = l0dMSHRs.getOrElse(prev.l0dMSHRs),
      l1InFlight = l1InFlight.getOrElse(prev.l1InFlight),
      l1MSHRs = l1MSHRs.getOrElse(prev.l1MSHRs),
      coalSrcIds = coalSrcIds.getOrElse(prev.coalSrcIds),
      coalRespDepth = coalRespDepth.getOrElse(prev.coalRespDepth),
      l0dReorderDepth = l0dReorderDepth.getOrElse(prev.l0dReorderDepth))
  }
})
