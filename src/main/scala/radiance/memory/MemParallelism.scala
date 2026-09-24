package radiance.memory

import chisel3.util.isPow2
import org.chipsalliance.cde.config.{Config, Field}

/** Outstanding-request limits of the GPU data path below the coalescer. This key is the only
  * place these values are set: WithMuonCores and WithRadianceCluster copy the MSHR counts into the
  * L0d and cluster L1 DCacheParams, which is where every consumer reads them, and the cache
  * configs (L0dCacheConfig, L1CacheConfig) do not state them. The in-flight depths and the
  * coalescer depth have no other home and are read from this key where those blocks are built.
  * The cache tag widths follow from the resulting TileLink source widths (TLNBDCache.reqTagBits).
  *
  * @param l0dInFlight  requests the L0d input adapter (DepthHellaCacheIF) holds in flight. Every
  *                     request, hit or miss, keeps an entry until its response, so this caps the
  *                     memory-level parallelism of the port.
  * @param l0dMSHRs     L0d MSHRs
  * @param l1InFlight   requests the cluster L1 input adapter holds in flight
  * @param l1MSHRs      cluster L1 MSHRs
  * @param coalInFlight coalesced requests one core can have outstanding: the coalescer's source
  *                     ids and its response-queue depth, read by MuonTile. A power of two.
  */
case class MemParallelismParams(
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
  l0dInFlight: Option[Int] = None,
  l0dMSHRs: Option[Int] = None,
  l1InFlight: Option[Int] = None,
  l1MSHRs: Option[Int] = None,
  coalInFlight: Option[Int] = None,
) extends Config((site, here, up) => {
  case MemParallelismKey => {
    val prev = up(MemParallelismKey)
    MemParallelismParams(
      l0dInFlight = l0dInFlight.getOrElse(prev.l0dInFlight),
      l0dMSHRs = l0dMSHRs.getOrElse(prev.l0dMSHRs),
      l1InFlight = l1InFlight.getOrElse(prev.l1InFlight),
      l1MSHRs = l1MSHRs.getOrElse(prev.l1MSHRs),
      coalInFlight = coalInFlight.getOrElse(prev.coalInFlight))
  }
})
