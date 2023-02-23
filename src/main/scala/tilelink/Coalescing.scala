// See LICENSE.SiFive for license details.

package freechips.rocketchip.tilelink

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.unittest._

class CoalescingUnitTest(nManagers: Int, txns: Int = 5000, timeout: Int = 500000)(implicit p: Parameters) extends UnitTest(timeout) {
  val dut = Module(LazyModule(new TLRAMXbar(nManagers,txns)).module)
  dut.io.start := io.start
  io.finished := true.B
}
