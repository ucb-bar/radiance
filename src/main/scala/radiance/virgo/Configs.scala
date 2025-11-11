package radiance.virgo

import freechips.rocketchip.prci._
import freechips.rocketchip.rocket.{DCacheParams, ICacheParams}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLBusWrapperConnection, TLBusWrapperTopology}
import org.chipsalliance.cde.config.Config
import org.chipsalliance.diplomacy.nodes.{BIND_QUERY, BIND_STAR}
import radiance.subsystem.SIMTCoreKey

case class VortexTileAttachParams(
  tileParams: VortexTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = VortexTile
}

class WithVortexCores(
  n: Int,
  location: HierarchicalLocation,
  crossing: RocketCrossingParams,
  tensorCoreFP16: Boolean,
  tensorCoreDecoupled: Boolean,
  useVxCache: Boolean
) extends Config((site, _, up) => {
  case TilesLocated(`location`) => {
    val prev = up(TilesLocated(`location`))
    val idOffset = up(NumTiles)
    val coreIdOffset = up(NumVortexCores)
    val vortex = VortexTileParams(
      core = VortexCoreParams(
        tensorCoreFP16 = tensorCoreFP16,
        tensorCoreDecoupled = tensorCoreDecoupled
      ),
      btb = None,
      useVxCache = useVxCache,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 1,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBSets = 1,
        nTLBWays = 1,
        nTLBBasePageSectors = 1,
        nTLBSuperpages = 1,
        blockBytes = site(CacheBlockBytes))))
    List.tabulate(n)(i => VortexTileAttachParams(
      vortex.copy(
        tileId = i + idOffset,
        coreId = i + coreIdOffset,
      ),
      crossing
    )) ++ prev
  }
  case NumTiles => up(NumTiles) + n
  case NumVortexCores => up(NumVortexCores) + n
}) {
  // constructor override that omits `crossing`
  def this(n: Int, location: HierarchicalLocation = InSubsystem,
           tensorCoreFP16: Boolean = false, tensorCoreDecoupled: Boolean = false,
           useVxCache: Boolean = false)
  = this(n, location, RocketCrossingParams(
    master = HierarchicalElementMasterPortParams.locationDefault(location),
    slave = HierarchicalElementSlavePortParams.locationDefault(location),
    mmioBaseAddressPrefixWhere = location match {
      case InSubsystem => CBUS
      case InCluster(clusterId) => CCBUS(clusterId)
    }
  ), tensorCoreFP16, tensorCoreDecoupled, useVxCache)
}

class WithVortexL1Banks(nBanks: Int = 4) extends Config((site, here, up) => {
  case VortexL1Key => {
    Some(defaultVortexL1Config.copy(
      numBanks = nBanks,
      inputSize = up(SIMTCoreKey).get.numLsuLanes * 4 /*32b word*/ ,
      cacheLineSize = up(SIMTCoreKey).get.numLsuLanes * 4 /*32b word*/ ,
      memSideSourceIds = 16,
      mshrSize = 16,
    ))
  }
})

case class VirgoClusterAttachParams(
  clusterParams: VirgoClusterParams,
  crossingParams: HierarchicalElementCrossingParamsLike
) extends CanAttachCluster {
  type ClusterType = VirgoCluster
}

case class CLBUS(clusterId: Int) extends TLBusWrapperLocation(s"clbus$clusterId")

case class VirgoClusterBusTopologyParams(
  clusterId: Int,
  csbus: SystemBusParams,
  ccbus: PeripheryBusParams,
  coherence: BankedCoherenceParams
) extends TLBusWrapperTopology(
  instantiations = List(
    (CSBUS(clusterId), csbus),
    (CLBUS(clusterId), csbus),
    (CCBUS(clusterId), ccbus)) ++ (if (coherence.nBanks == 0) Nil else List(
    (CMBUS(clusterId), csbus),
    (CCOH (clusterId), CoherenceManagerWrapperParams(csbus.blockBytes, csbus.beatBytes, coherence.nBanks, CCOH(clusterId).name)(coherence.coherenceManager)))),
  connections = if (coherence.nBanks == 0) Nil else List(
    (CSBUS(clusterId), CCOH (clusterId), TLBusWrapperConnection(driveClockFromMaster = Some(true), nodeBinding = BIND_STAR)()),
    (CCOH (clusterId), CMBUS(clusterId), TLBusWrapperConnection.crossTo(
      xType = NoCrossing,
      driveClockFromMaster = Some(true),
      nodeBinding = BIND_QUERY))
  )
)

class WithVirgoCluster(
  clusterId: Int,
  location: HierarchicalLocation = InSubsystem,
  crossing: RocketCrossingParams = RocketCrossingParams()
) extends Config((site, here, up) => {
  case ClustersLocated(`location`) => up(ClustersLocated(location)) :+ VirgoClusterAttachParams(
    VirgoClusterParams(clusterId = clusterId),
    crossing)
  case TLNetworkTopologyLocated(InCluster(`clusterId`)) => List(
    VirgoClusterBusTopologyParams(
      clusterId = clusterId,
      csbus = site(SystemBusKey),
      ccbus = site(ControlBusKey).copy(errorDevice = None),
      coherence = site(ClusterBankedCoherenceKey(clusterId))
    )
  )
  case PossibleTileLocations => up(PossibleTileLocations) :+ InCluster(clusterId)
})
