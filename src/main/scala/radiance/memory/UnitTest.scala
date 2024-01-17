// See LICENSE.SiFive for license details.

package radiance.memory

import chisel3._
import freechips.rocketchip.amba.ahb._
import freechips.rocketchip.amba.apb._
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem.{BaseSubsystemConfig}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import radiance.subsystem.WithSimtLanes
import freechips.rocketchip.unittest._
//import rocket.VortexFatBankTest

case object TestDurationMultiplier extends Field[Int]

class WithTestDuration(x: Int) extends Config((site, here, up) => {
  case TestDurationMultiplier => x
})
class WithCoalescingUnitTests extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(
      // Module(new TLRAMCoalescerTest(timeout=timeout)),
      Module(new TLRAMCoalescerLoggerTest(filename="vecadd.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="sfilter.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="nearn.core1.thread4.trace", timeout=50000000 * site(TestDurationMultiplier))),
      // Module(new TLRAMCoalescerLoggerTest(filename="psort.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="nvbit.vecadd.n100000.filter_sm0.trace", timeout=timeout)(new WithSimtLanes(32))),
      // Module(new TLRAMCoalescerLoggerTest(filename="nvbit.vecadd.n100000.filter_sm0.lane4.trace", timeout=timeout)),
    ) }
})

/*
class WithVortexFatBankUnitTests extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(
      Module(new VortexFatBankTest(filename="oclprintf.core1.thread4.trace", timeout=timeout)),
    )}
})
*/

class WithCoalescingUnitSynthesisDummy(nLanes: Int) extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(
      Module(new DummyCoalescerTest(timeout=timeout)(new WithSimtLanes(nLanes=4))),
    ) }
})

class WithECCTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    Seq(
      // try some perfect codes
      Module(new ECCTest(1)),  // n=3
      Module(new ECCTest(4)),  // n=7
      Module(new ECCTest(11)), // n=15
      // try +1 perfect
      Module(new ECCTest(2)),  // n=5
      Module(new ECCTest(5)),  // n=9
      Module(new ECCTest(12)), // n=17
      // try -1 perfect
      Module(new ECCTest(3)),  // n=6
      Module(new ECCTest(10)), // n=14
      // try a useful size
      Module(new ECCTest(8)) ) }
})

class WithScatterGatherTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    Seq(
      Module(new GatherTest(1)),
      Module(new GatherTest(2)),
      Module(new GatherTest(3)),
      Module(new GatherTest(7)),
      Module(new GatherTest(8)),
      Module(new GatherTest(9)),
      Module(new ScatterTest(1)),
      Module(new ScatterTest(2)),
      Module(new ScatterTest(3)),
      Module(new ScatterTest(7)),
      Module(new ScatterTest(8)),
      Module(new ScatterTest(9)))}})

class WithPLRUTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    Seq(
      Module(new PLRUTest(2)),
      Module(new PLRUTest(3)),
      Module(new PLRUTest(4)),
      Module(new PLRUTest(5)),
      Module(new PLRUTest(6)))}})

class WithPowerQueueTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    Seq(
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   1,  2, false, false, 10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   2,  6, false, false, 10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   3, 10, false, false, 10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   2,  8, false, true,  10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   4,  8, true,  false, 10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   1, 16, true,  true,  10000)),
      Module(new PositionedQueueTest(FloppedLanePositionedQueue,                   4,  2, true,  true,  10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 4, 12, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 4, 16, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 4, 20, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 1, 12, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 3, 16, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 5, 20, false, false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 2, 32, true,  false, 10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 2, 16, false, true,  10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 4,  8, true,  true,  10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 1, 16, true,  true,  10000)),
      Module(new PositionedQueueTest(OnePortLanePositionedQueue(new IdentityCode), 2,  8, true,  true,  10000)),
      Module(new MultiPortQueueTest(1, 1, 2, 10000)),
      Module(new MultiPortQueueTest(3, 3, 2, 10000)),
      Module(new MultiPortQueueTest(5, 5, 6, 10000)),
      Module(new MultiPortQueueTest(4, 3, 6, 10000)),
      Module(new MultiPortQueueTest(4, 5, 2, 10000)),
      Module(new MultiLaneQueueTest(1, 2, 10000)),
      Module(new MultiLaneQueueTest(3, 2, 10000)),
      Module(new MultiLaneQueueTest(5, 6, 10000))
      )}})

class AMBAUnitTestConfig extends Config(new WithAMBAUnitTests ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class TLSimpleUnitTestConfig extends Config(new WithTLSimpleUnitTests ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class TLWidthUnitTestConfig extends Config(new WithTLWidthUnitTests ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class TLXbarUnitTestConfig extends Config(new WithTLXbarUnitTests ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class CoalescingUnitTestConfig extends Config(new WithCoalescingUnitTests ++ new WithTestDuration(10) ++ new WithSimtLanes(nLanes=4) ++ new BaseSubsystemConfig)
//class VortexFatBankUnitTestConfig extends Config(new WithVortexFatBankUnitTests ++ new WithTestDuration(10) ++ new WithSimtLanes(nLanes=4) ++ new BaseSubsystemConfig)
class ECCUnitTestConfig extends Config(new WithECCTests)
class ScatterGatherTestConfig extends Config(new WithScatterGatherTests)
class PLRUUnitTestConfig extends Config(new WithPLRUTests)
class PowerQueueTestConfig extends Config(new WithPowerQueueTests)

// Dummy configs of various sizes for synthesis
class CoalescingSynthesisDummyLane4Config extends Config(new WithCoalescingUnitSynthesisDummy(4) ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane8Config extends Config(new WithCoalescingUnitSynthesisDummy(8) ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane16Config extends Config(new WithCoalescingUnitSynthesisDummy(16) ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane32Config extends Config(new WithCoalescingUnitSynthesisDummy(32) ++ new WithTestDuration(10) ++ new BaseSubsystemConfig)

