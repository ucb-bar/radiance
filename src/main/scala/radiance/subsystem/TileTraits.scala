package radiance.subsystem

import freechips.rocketchip.tilelink._
import gemmini.Gemmini
import radiance.cluster.{GemminiTileParams, SoftResetFinishNode}
import radiance.muon.MuonTileParams

trait MuonTileLike {
  val muonParams: MuonTileParams
  val smemNodes: Seq[TLNode]
  val icacheNode: TLNode
  val dcacheNode: TLNode
  val softResetFinishSlave: SoftResetFinishNode.Slave
}

trait GemminiTileLike {
  val gemminiParams: GemminiTileParams
  // TODO: remove `gemmini` from the trait, replace with the
  // TODO: TL nodes that it exposes
  val gemmini: Gemmini[gemminiParams.T, gemminiParams.U, gemminiParams.V]
  val requantizerMuonManager: Option[TLManagerNode]
  val requantizerSmemClient: Option[TLClientNode]
  val slaveNode: TLNode
}
