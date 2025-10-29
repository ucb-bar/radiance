package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.int._
import radiance.muon.backend.fp._

class Execute(implicit p: Parameters) extends CoreModule()(p) with HasCoreBundles {

  val aluPipe = Module(new ALUPipe())
  val fpPipe = Module(new FPPipe())
  val mulDivPipe = Module(new MulDivPipe())
  val sfuPipe = Module(new SFU())


}
