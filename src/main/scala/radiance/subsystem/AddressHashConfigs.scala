package radiance.subsystem

import org.chipsalliance.cde.config._
import org.chipsalliance.diplomacy.nodes.BIND_QUERY
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLBusWrapperConnection, TLBusWrapperTopology}
import radiance.memory.{AddressHashNode, AddressHashParams, GPUAddressHashKey, HashedMasterPortParams}

/** Hash GPU memory over the GPU L2 slices at cache-line granularity (AddressHashParams), applied by
  * every master that reaches GPU memory through the coherent fabric:
  *   - each cluster's master port (all GPU-side masters: L1, Gemmini DMA, scale-factor clients),
  *   - the rocket tile's master port (D-cache, I-cache, uncached accesses),
  *   - the front bus where it joins the system bus (serial-TL/TSI host access, debug system-bus access).
  * The GPU L2 keeps contiguous slices, one per DRAM channel; requires WithRadianceSplitL2.
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
    val slices = gpuCaches.head.regions
    val stripe = gpu.size / slices.size
    // the hash's slice index is the top bits of the physical offset, so the slices must be the
    // contiguous equal split of GPU memory
    require(slices.toSet == Seq.tabulate(slices.size)(i => AddressSet(gpu.address + i * stripe, stripe - 1)).toSet,
      s"WithGPUAddressHash needs the GPU L2 slices to be contiguous equal parts of GPU memory, got $slices")
    Some(AddressHashParams(gpu.address, gpu.size, unitBytes.getOrElse(site(CacheBlockBytes)), slices.size))
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
