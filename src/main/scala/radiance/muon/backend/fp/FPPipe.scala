package radiance.muon.backend.fp

import chisel3._
import chisel3.util._
import chisel3.util.experimental.decode._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

case class FPPipeParams (val numFP32Lanes: Int = 8,
                         val numFPDivLanes: Int = 8)

trait HasFPPipeParams extends HasMuonCoreParameters {
  def numFP32Lanes = muonParams.fpPipe.numFP32Lanes
  def numFPDivLanes = muonParams.fpPipe.numFPDivLanes
}