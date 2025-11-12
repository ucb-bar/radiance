package radiance.subsystem

import freechips.rocketchip.prci.ClockSinkParameters
import freechips.rocketchip.rocket.{BTBParams, DCacheParams, ICacheParams, MulDivParams}
import freechips.rocketchip.tile.{CoreParams, FPUParams, TileParams}
import radiance.muon.MuonCoreParams

trait DummyTileParams extends TileParams {
  val core: CoreParams
  val tileId: Int
  // bare tile defaults
  val icache: Option[ICacheParams] = None
  val dcache: Option[DCacheParams] = None
  val btb: Option[BTBParams] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val baseName: String = "dummy_tile"
  val uniqueName: String = s"dummy_tile_$tileId"
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
}

object DummyTileParams extends DummyTileParams {
  override val core: CoreParams = PhysicalCoreParams
  override val tileId: Int = 0
}

trait PhysicalCoreParams extends CoreParams {
  val bootFreqHz: BigInt = 0
  val useVM: Boolean = false
  val useUser: Boolean = false
  val useSupervisor: Boolean = false
  val useHypervisor: Boolean = false
  val useDebug: Boolean = true
  val useAtomics: Boolean = false
  val useAtomicsOnlyForIO: Boolean = false
  val useCompressed: Boolean = false
  val useRVE: Boolean = false
  val useConditionalZero: Boolean = false
  val useZba: Boolean = false
  val useZbb: Boolean = false
  val useZbs: Boolean = false
  val mulDiv: Option[MulDivParams] = None
  val fpu: Option[FPUParams] = None
  val fetchWidth: Int = 1
  val decodeWidth: Int = 1
  val retireWidth: Int = 1
  val instBits: Int = 64
  val nLocalInterrupts: Int = 0
  val useNMI: Boolean = false
  val nBreakpoints: Int = 1
  val useBPWatch: Boolean = false
  val nPMPs: Int = 8
  val pmpGranularity: Int = 4
  val mcontextWidth: Int = 0
  val scontextWidth: Int = 0
  val nPerfCounters: Int = 0
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 0
  val nL2TLBWays: Int = 1
  val nPTECacheEntries: Int = 8
  val mtvecInit: Option[BigInt] = Some(BigInt(0))
  val mtvecWritable: Boolean = false
  val traceHasWdata: Boolean = false
  val xLen: Int = 32
  val pgLevels: Int = 2
  val lrscCycles: Int = 0
}

object PhysicalCoreParams extends PhysicalCoreParams