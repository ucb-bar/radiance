package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

// TODO: consolidate with below types
class RegWriteback(implicit p: Parameters) extends CoreBundle()(p) {
  val rd = aRegT
  val data = Vec(muonParams.numLanes, regDataT)
  val tmask = tmaskT
}

class WritebackReq(implicit val p: Parameters)
  extends Bundle with HasMuonCoreParameters {
  val wmask = UInt(numLaneBytes.W)
  val pc_w_en = Bool()
  val rd = UInt(Isa.regBits.W)
  val data = Vec(numLanes, UInt(archLen.W))
}

class IssueIF(implicit val p: Parameters)
  extends Bundle with HasMuonCoreParameters {
  val regAddr = UInt(Isa.regBits.W)
  val data = Vec(numLanes, UInt(archLen.W))
  val wmask = UInt(numLaneBytes.W)
}

class Writeback(implicit p: Parameters)
  extends CoreModule with HasCoreBundles {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(writebackT()))
    val rfWIssueIF = Output(Valid(new IssueIF))
    val schedIF = Output(new Bundle {
      val pc = UInt(muonParams.archLen.W)
      val pc_w_en = UInt(muonParams.numLanes.W)
    })
  })
}
