package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.nodes.BIND_QUERY
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLBusWrapperConnection, TLBusWrapperTopology}
import radiance.memory.{AddressHashNode, AddressHashParams, GPUAddressHashKey, HashedMasterPortParams}

/** Hash GPU memory over the GPU DRAM channels at cache-line granularity (AddressHashParams), applied by
  * every master that reaches GPU memory through the coherent fabric:
  *   - each cluster's master port (all GPU-side masters: L1, Gemmini DMA, scale-factor clients),
  *   - the rocket tile's master port (D-cache, I-cache, uncached accesses),
  *   - the front bus where it joins the system bus (serial-TL/TSI host access, debug system-bus access).
  * The hash part is the channel; each GPU L2 slice owns a contiguous run of channels, and the hash
  * picks the slice from the low bits of the part so consecutive lines alternate slices
  * (AddressHashParams.channelsPerSlice). Requires WithRadianceSplitL2.
  *
  * Not covered: masters on the memory bus that bypass the L2 (the tile's trace-sink DMA must not
  * target GPU memory), and loaders that write DRAM directly (SimDRAM's +loadmem, FireSim's LoadMem):
  * with the hash on, load through TSI or make the loader apply AddressHashParams.forward.
  */
class WithGPUAddressHash(unitBytes: Option[Int] = None) extends Config((site, here, up) => {
  case GPUAddressHashKey => {
    val gpu = site(GPUMemory).getOrElse(throw new Exception("WithGPUAddressHash needs GPUMemory"))
    val split = site(SplitL2Key).getOrElse(throw new Exception("WithGPUAddressHash needs WithRadianceSplitL2"))
    val gpuRange = AddressSet(gpu.address, gpu.size - 1)
    val gpuCaches = split.caches.filter(_.regions.exists(_.overlaps(gpuRange)))
    require(gpuCaches.size == 1, s"WithGPUAddressHash expects one L2 cache over GPU memory, found ${gpuCaches.map(_.name)}")
    val channels = gpuCaches.head.channels
    val stripe = gpu.size / channels.size
    // the hash's part index is the top bits of the physical offset, so the channels must be the
    // contiguous equal split of GPU memory
    require(channels.toSet == Seq.tabulate(channels.size)(i => AddressSet(gpu.address + i * stripe, stripe - 1)).toSet,
      s"WithGPUAddressHash needs the GPU DRAM channels to be contiguous equal parts of GPU memory, got $channels")
    val spec = gpuCaches.head
    val unit = unitBytes.getOrElse(site(CacheBlockBytes))
    // spread a slice's channels over its L2 sets (AddressHashParams.chanSetStride), in hash units
    val l2Sets = spec.kBPerSlice * 1024 / (site(CacheBlockBytes) * spec.ways)
    val chanSetStride =
      if (spec.channelsPerSlice == 1) 0 else (l2Sets / spec.channelsPerSlice) * site(CacheBlockBytes) / unit
    Some(AddressHashParams(gpu.address, gpu.size, unit, channels.size,
      channelsPerSlice = spec.channelsPerSlice, chanSetStride = chanSetStride))
  }
  case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem)).map {
    case r: RocketTileAttachParams => r.copy(crossingParams = r.crossingParams.copy(
      master = HashedMasterPortParams(r.crossingParams.master, site(GPUAddressHashKey).get)))
    case other => other
  }
  case ClustersLocated(InSubsystem) => up(ClustersLocated(InSubsystem)).map {
    case c: RadianceClusterAttachParams => c.crossingParams match {
      case r: RocketCrossingParams => c.copy(crossingParams = r.copy(
        master = HashedMasterPortParams(r.master, site(GPUAddressHashKey).get)))
      case other => throw new Exception(s"WithGPUAddressHash: unsupported cluster crossing params $other")
    }
    case other => other
  }
  case TLNetworkTopologyLocated(InSubsystem) => up(TLNetworkTopologyLocated(InSubsystem)).map {
    case h: HierarchicalBusTopologyParams =>
      val hash = site(GPUAddressHashKey).get
      val xType = h.xTypes.fbusToSbusXType
      new TLBusWrapperTopology(h.instantiations, h.connections.map {
        // same as HierarchicalBusTopologyParams' FBUS -> SBUS connection, plus the hash
        case (FBUS, SBUS, _) => (FBUS, SBUS, TLBusWrapperConnection(xType,
          if (h.driveClocksFromSBus) Some(false) else None, BIND_QUERY, flipRendering = true)(
          masterNodeView = { case (w, q) => w.crossOutHelper(xType)(q) },
          inject = q => AddressHashNode(hash)(q)))
        case other => other
      })
    case other => other
  }
})
