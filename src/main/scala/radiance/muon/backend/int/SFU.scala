package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._

class SFU(implicit p: Parameters) extends IntPipe with HasCoreBundles {

  val uop = io.req.bits.uop

//  uop.inst()
}
