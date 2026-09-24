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
  * @param coalInFlight coalesced requests one core can have outstanding: the coalescer's source
  *                     ids and its response-queue depth, read by MuonTile. A power of two.
  */
case class MemParallelismParams(
  l0iInFlight: Int = 8,
  l0dInFlight: Int = 3,
  l0dMSHRs: Int = 4,
  l1InFlight: Int = 3,
  l1MSHRs: Int = 8,
  coalInFlight: Int = 8,
) {
  require(isPow2(coalInFlight), s"coalInFlight must be a power of two, got $coalInFlight")
}

case object MemParallelismKey extends Field[MemParallelismParams](MemParallelismParams())

/** Overrides the given limits and keeps the others. */
class WithMemParallelism(
  l0iInFlight: Option[Int] = None,
  l0dInFlight: Option[Int] = None,
  l0dMSHRs: Option[Int] = None,
  l1InFlight: Option[Int] = None,
  l1MSHRs: Option[Int] = None,
  coalInFlight: Option[Int] = None,
) extends Config((site, here, up) => {
  case MemParallelismKey => {
    val prev = up(MemParallelismKey)
    MemParallelismParams(
      l0iInFlight = l0iInFlight.getOrElse(prev.l0iInFlight),
      l0dInFlight = l0dInFlight.getOrElse(prev.l0dInFlight),
      l0dMSHRs = l0dMSHRs.getOrElse(prev.l0dMSHRs),
      l1InFlight = l1InFlight.getOrElse(prev.l1InFlight),
      l1MSHRs = l1MSHRs.getOrElse(prev.l1MSHRs),
      coalInFlight = coalInFlight.getOrElse(prev.coalInFlight))
  }
})
