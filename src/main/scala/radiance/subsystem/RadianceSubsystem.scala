// See LICENSE.SiFive for license details.

package radiance.subsystem

import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink.{TLBusWrapperTopology, TLBusWrapperConnection}
import freechips.rocketchip.prci._
import org.chipsalliance.diplomacy.nodes._
import radiance.tile._

case class RadianceTileAttachParams(
  tileParams: RadianceTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile { type TileType = RadianceTile }

case class RadianceClusterAttachParams (
  clusterParams: RadianceClusterParams,
  crossingParams: HierarchicalElementCrossingParamsLike
) extends CanAttachCluster {
  type ClusterType = RadianceCluster
}

case class CLBUS(clusterId: Int) extends TLBusWrapperLocation(s"clbus$clusterId")

case class RadianceClusterBusTopologyParams(
                                             clusterId: Int,
                                             csbus: SystemBusParams,
                                             ccbus: PeripheryBusParams,
                                             coherence: BankedCoherenceParams
                                           ) extends TLBusWrapperTopology(
  instantiations = List(
    (CSBUS(clusterId), csbus),
    (CLBUS(clusterId), csbus), // TODO don't copy from csbus params
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
