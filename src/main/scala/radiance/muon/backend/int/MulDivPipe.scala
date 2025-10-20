package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.{MulDiv, MulDivParams}
import org.chipsalliance.cde.config.Parameters
import radiance.muon.Isa
import radiance.muon.backend.{LaneDecomposer, LaneRecomposer}

class MulDivPipe(implicit p: Parameters)
  extends IntPipe {
  implicit val decomposerTypes =
    Seq(UInt(archLen.W), UInt(archLen.W))
  val mulDivDecomposer = Module(new LaneDecomposer(
    inLanes = numLanes,
    outLanes = numALULanes,
    elemTypes = decomposerTypes
  ))

  val vecMulDiv = Seq.fill(numMulDivLanes)(Module(
    new MulDiv(MulDivParams(), archLen, Isa.regBits))
  )
}