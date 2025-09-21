package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.MulDivParams
import freechips.rocketchip.tile.{CoreParams, FPUParams}
import freechips.rocketchip.util.ParameterizedBundle
import org.chipsalliance.cde.config.Parameters

case class MuonCoreParams(
  bootFreqHz: BigInt = 0,
  useVM: Boolean = false,
  useUser: Boolean = false,
  useSupervisor: Boolean = false,
  useHypervisor: Boolean = false,
  useDebug: Boolean = true,
  useAtomics: Boolean = false,
  useAtomicsOnlyForIO: Boolean = false,
  useCompressed: Boolean = false,
  useRVE: Boolean = false,
  useConditionalZero: Boolean = false,
  useZba: Boolean = false,
  useZbb: Boolean = false,
  useZbs: Boolean = false,
  mulDiv: Option[MulDivParams] = None,
  fpu: Option[FPUParams] = None,
  fetchWidth: Int = 1,
  decodeWidth: Int = 1,
  retireWidth: Int = 1,
  instBits: Int = 64,
  nLocalInterrupts: Int = 0,
  useNMI: Boolean = false,
  nBreakpoints: Int = 1,
  useBPWatch: Boolean = false,
  nPMPs: Int = 8,
  pmpGranularity: Int = 4,
  mcontextWidth: Int = 0,
  scontextWidth: Int = 0,
  nPerfCounters: Int = 0,
  haveBasicCounters: Boolean = true,
  haveFSDirty: Boolean = false,
  misaWritable: Boolean = false,
  haveCFlush: Boolean = false,
  nL2TLBEntries: Int = 0,
  nL2TLBWays: Int = 1,
  nPTECacheEntries: Int = 8,
  mtvecInit: Option[BigInt] = Some(BigInt(0)),
  mtvecWritable: Boolean = false,
  traceHasWdata: Boolean = false,
  xLen: Int = 32,
  pgLevels: Int = 2,
  lrscCycles: Int = 0,
  // end boilerplate
  numWarps: Int = 8,
  numLanes: Int = 16,
  // schedule, dispatch, rename
  numPhysRegs: Int = 256,
  numArchRegs: Int = 128,
  numIPDOMEntries: Int = 8,
  // issue
  numRegBanks: Int = 4,
  numOpCollectorEntries: Int = 2,
  // execute
  numFp32Lanes: Int = 8,
  numFDivLanes: Int = 8,
  // memory
  lsu: LoadStoreUnitParams = LoadStoreUnitParams(
    numLsuLanes = 16,
    numLdqEntries = 4,
    numStqEntries = 4
  ),
  logSMEMInFlights: Int = 2,
) extends CoreParams {
  val warpIdWidth = log2Up(numWarps)
}

// move to decode?
object Isa {
  def regBits = 8
  def immBits = 8
  def predBits = 4
}

class MemRequest (
  tagBits: Int,
  sizeBits: Int,
  addressBits: Int,
  dataBits: Int
) extends Bundle {
  val store = Bool()
  val address = UInt(addressBits.W)
  val size = UInt(sizeBits.W) // log size
  val tag = UInt(tagBits.W)
  val data = UInt(dataBits.W)
}

class MemResponse (
  tagBits: Int,
  dataBits: Int
) extends Bundle {
  val tag = UInt(tagBits.W)
  val data = UInt(dataBits.W)
}

trait HasMuonCoreParameters extends freechips.rocketchip.tile.HasCoreParameters {
  val muonParams: MuonCoreParams = tileParams.core.asInstanceOf[MuonCoreParams]
}

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p) with HasMuonCoreParameters

abstract class CoreModule(implicit val p: Parameters) extends Module
  with HasMuonCoreParameters

trait MemIO extends CoreBundle {
  val tagBits: Int
  val dataBits: Int
  val addressBits = muonParams.xLen
  def sizeBits = log2Ceil(dataBits / 8)
  def req = Decoupled(new MemRequest(tagBits, sizeBits, addressBits, dataBits))
  def resp = Flipped(Decoupled(new MemResponse(tagBits, dataBits)))
}

class DataMemIO(implicit p: Parameters) extends CoreBundle()(p) with MemIO {
  val tagBits  = 4 // FIXME
  val dataBits = muonParams.xLen * muonParams.lsu.numLsuLanes // TODO: get from dcache
}

class InstMemIO(implicit p: Parameters) extends CoreBundle()(p) with MemIO {
  val tagBits  = 4 // FIXME
  val dataBits = muonParams.instBits
}

class Muon(tile: MuonTile)(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    // TODO: LCP (threadblock start/done, warp slot, synchronization)
  })

  val fe = Module(new Frontend)
  fe.io.imem <> io.imem

  val be = Module(new Backend)
  be.io.dmem <> io.dmem

  be.io.ibuf <> fe.io.ibuf
}
