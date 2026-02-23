// See LICENSE.SiFive for license details.

package radiance.unittest

import chisel3._
import freechips.rocketchip.subsystem.BaseSubsystemConfig
import freechips.rocketchip.unittest._
import org.chipsalliance.cde.config._
import radiance.memory._
import radiance.muon.MuonLoadStoreUnitDebugIdKey
import radiance.subsystem.{WithMuonCores, WithSIMTConfig}
import radiance.virgo.TensorCoreDecoupledTest

case object TestDurationMultiplier extends Field[Int]

// -----------------------------------------------------------------------------
// Muon tests
// -----------------------------------------------------------------------------

class WithMuonUnitTestHarness(harness: Parameters => UnitTest) extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    Seq(Module(harness(q)))
  }
})

class WithMuonLSUDebugIds(width: Int) extends Config((_, _, _) => {
  case MuonLoadStoreUnitDebugIdKey => Some(width)
})

class MuonCoreTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonCoreTest()(_)) ++
  new WithMuonCores(1, standalone = true, trace = true, difftest = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonCoreLongTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonCoreTest(timeout = 10000000)(_)) ++
  new WithMuonCores(1, standalone = true, trace = true, difftest = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonCoreNoILPTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonCoreTest()(_)) ++
  new WithMuonCores(1, standalone = true, noILP = true, trace = true, difftest = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonCoreNoDiffTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonCoreTest()(_)) ++
  new WithMuonCores(1, standalone = true, trace = true, difftest = false) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonFrontendTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonFrontendTest()(_)) ++
  new WithMuonCores(1, standalone = true, difftest = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonBackendTestConfig extends Config(
  new WithMuonUnitTestHarness(new MuonBackendTest()(_)) ++
  new WithMuonCores(1, standalone = true, difftest = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

class MuonLSUTestConfig extends Config(
  new WithMuonLSUDebugIds(width = 16) ++
  new WithMuonUnitTestHarness(new MuonLSUTest()(_)) ++
  new WithMuonCores(1, standalone = true) ++
  new WithSIMTConfig(numWarps = 8, numLanes = 16, numLsuLanes = 16, numSMEMInFlights = 4) ++
  new BaseSubsystemConfig)

// -----------------------------------------------------------------------------
// Tensor core tests
// -----------------------------------------------------------------------------

class WithTestDuration(x: Int) extends Config((site, here, up) => {
  case TestDurationMultiplier => x
})

class WithTensorUnitTests extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(Module(new TensorCoreDecoupledTest(timeout=timeout)))
  }
})

class TensorUnitTestConfig extends Config(
  new WithTensorUnitTests ++
  new WithTestDuration(10) ++
  new BaseSubsystemConfig)

// -----------------------------------------------------------------------------
// Coalescer tests
// -----------------------------------------------------------------------------

class WithCoalescingUnitTests extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(
      Module(new TLRAMCoalescerLoggerTest(filename="vecadd.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="sfilter.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="nearn.core1.thread4.trace", timeout=50000000 * site(TestDurationMultiplier))),
      // Module(new TLRAMCoalescerLoggerTest(filename="psort.core1.thread4.trace", timeout=timeout)),
      // Module(new TLRAMCoalescerLoggerTest(filename="nvbit.vecadd.n100000.filter_sm0.trace", timeout=timeout)(new WithSimtConfig(32))),
      // Module(new TLRAMCoalescerLoggerTest(filename="nvbit.vecadd.n100000.filter_sm0.lane4.trace", timeout=timeout)),
    ) }
})

class WithCoalescingUnitSynthesisDummy(nLanes: Int) extends Config((site, _, _) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 50000 * site(TestDurationMultiplier)
    Seq(Module(new DummyCoalescerTest(timeout=timeout)(new WithSIMTConfig(numLsuLanes=4))))
  }
})

class CoalescingUnitTestConfig extends Config(
  new WithCoalescingUnitTests ++
  new WithTestDuration(10) ++
  new WithSIMTConfig(numLsuLanes=4) ++
  new BaseSubsystemConfig)

// Dummy configs of various sizes for synthesis
class CoalescingSynthesisDummyLane4Config extends Config(
  new WithCoalescingUnitSynthesisDummy(4) ++
  new WithTestDuration(10) ++
  new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane8Config extends Config(
  new WithCoalescingUnitSynthesisDummy(8) ++
  new WithTestDuration(10) ++
  new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane16Config extends Config(
  new WithCoalescingUnitSynthesisDummy(16) ++
  new WithTestDuration(10) ++
  new BaseSubsystemConfig)
class CoalescingSynthesisDummyLane32Config extends Config(
  new WithCoalescingUnitSynthesisDummy(32) ++
  new WithTestDuration(10) ++
  new BaseSubsystemConfig)

