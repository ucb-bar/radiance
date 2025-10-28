package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

class ReservationStationEntry(implicit p: Parameters) extends CoreBundle()(p) {
  // TODO: optimize; storing all of Decode fields in RS gets expensive
  val uop = uopT
  /** Indicates whether each operand reg (rs1/2/3) has been collected.
   *  If the uop does not use the operand field, sets to 1.
   *  TODO: separate "used" field?
   */
  val valid = Vec(Isa.maxNumRegs, pRegT)
  /** Indicates whether each operand reg (rs1/2/3) is being written to by an
   *  in-flight uop in the backend. `busy == 1` means the operand can be
   *  potentially forwarded from EX. */
  val busy = Vec(Isa.maxNumRegs, pRegT)
  // TODO: collector entry pointer
}
