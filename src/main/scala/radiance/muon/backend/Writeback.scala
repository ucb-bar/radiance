package radiance.muon.backend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.{CoreModule, HasMuonCoreParameters, Isa}
import radiance.muon.backend.int.IntPipeResp

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

class Writeback(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new IntPipeResp))
    val rfWIssueIF = Output(Valid(new IssueIF))
    val schedIF = Output(new Bundle {
      val pc = UInt(muonParams.archLen.W)
      val pc_w_en = UInt(muonParams.numLanes.W)
    })
  })
}