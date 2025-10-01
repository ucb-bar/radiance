package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.MulDivParams
import freechips.rocketchip.tile.{HasTileParameters, CoreParams, FPUParams}
import freechips.rocketchip.util.ParameterizedBundle
import org.chipsalliance.cde.config.{Parameters, Field}

case object MuonKey extends Field[MuonCoreParams]

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
  xLen: Int = 64, // for 33-bit phys address
  pgLevels: Int = 2,
  lrscCycles: Int = 0,
  // end boilerplate
  archLen: Int = 32, // "real" xLen visible to Muon
  numWarps: Int = 8,
  numLanes: Int = 16,
  hartIdBits: Int = 3,
  // schedule, dispatch, rename
  numPhysRegs: Int = 256,
  numArchRegs: Int = 128,
  numIPDOMEntries: Int = 8,
  ibufDepth: Int = 8,
  // issue
  numRegBanks: Int = 4,
  numOpCollectorEntries: Int = 2,
  // execute
  numFp32Lanes: Int = 8,
  numFDivLanes: Int = 8,
  csrAddrBits: Int = 32,
  // memory
  lsu: LoadStoreUnitParams = LoadStoreUnitParams(),
  logSMEMInFlights: Int = 2,
) extends CoreParams {
  val warpIdBits = log2Up(numWarps)
  val pRegBits = log2Up(numPhysRegs)
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

trait HasMuonCoreParameters {
  implicit val p: Parameters
  val muonParams: MuonCoreParams = p(MuonKey)

  val addressBits = muonParams.archLen
  val numLsqEntries = {
    muonParams.numWarps * (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries + muonParams.lsu.numSharedStqEntries)
  }
  val dmemTagBits  = log2Ceil(numLsqEntries)
  val dmemDataBits = muonParams.archLen * muonParams.lsu.numLsuLanes // FIXME: needs to be cache line
  val smemTagBits  = log2Ceil(numLsqEntries) // FIXME: separate lsq for gmem/smem?
  val smemDataBits = muonParams.archLen * muonParams.lsu.numLsuLanes
  val imemTagBits  = 4 // FIXME: ibuffer depth
  val imemDataBits = muonParams.instBits

  // compute "derived" LSU parameters
  val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams)
}

abstract class CoreModule(implicit val p: Parameters) extends Module
  with HasMuonCoreParameters

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p)
  with HasMuonCoreParameters

class DataMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  def dmemSizeBits = log2Ceil(dmemDataBits / 8)
  val req = Decoupled(new MemRequest(dmemTagBits, dmemSizeBits, addressBits, dmemDataBits))
  val resp = Flipped(Decoupled(new MemResponse(dmemTagBits, dmemDataBits)))
}

class SharedMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  def smemSizeBits = log2Ceil(smemDataBits / 8)
  val req = Decoupled(new MemRequest(smemTagBits, smemSizeBits, addressBits, smemDataBits))
  val resp = Flipped(Decoupled(new MemResponse(smemTagBits, smemDataBits)))
}

class InstMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  def imemSizeBits = log2Ceil(imemDataBits / 8)
  val req = Decoupled(new MemRequest(imemTagBits, imemSizeBits, addressBits, imemDataBits))
  val resp = Flipped(Decoupled(new MemResponse(imemTagBits, imemDataBits)))
}

/** Muon core and core-private L0/L1 caches */
class Muon(implicit p: Parameters) extends CoreModule with HasTileParameters {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val hartid = Input(UInt(muonParams.hartIdBits.W))
    // TODO: LCP (threadblock start/done, warp slot, synchronization)
  })

  // TODO: L0/L1

  val core = Module(new MuonCore)
  io.imem <> core.io.imem
  io.dmem <> core.io.dmem
  io.smem <> core.io.smem
}

/** Muon core without the caches.
 *  Keeps HasTileParameters out so that it can be instantiated
 *  standalone without the rocket subsystem.
 */
class MuonCore(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    // TODO: LCP (threadblock start/done, warp slot, synchronization)
  })
  dontTouch(io)

  val fe = Module(new Frontend)
  fe.io.imem <> io.imem

  val be = Module(new Backend)
  be.io.dmem <> io.dmem
  be.io.smem <> io.smem

  be.io.ibuf <> fe.io.ibuf
}
