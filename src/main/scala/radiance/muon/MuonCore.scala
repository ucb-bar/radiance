package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.MulDivParams
import freechips.rocketchip.tile.{CoreParams, FPUParams, HasNonDiplomaticTileParameters, HasTileParameters}
import freechips.rocketchip.util.ParameterizedBundle
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule

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
  xLen: Int = 32,
  pgLevels: Int = 2,
  lrscCycles: Int = 0,
  // end boilerplate
  numWarps: Int = 8,
  numLanes: Int = 16,
  hartIdBits: Int = 3,
  // schedule, dispatch, rename
  numPhysRegs: Int = 256,
  numArchRegs: Int = 128,
  numIPDOMEntries: Int = 8,
  ibufDepth: Int = 8,
  startAddress: BigInt = x"0000_0000",
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
  def opcodeBits = 7
  def regBits = 8
  def immBits = 8
  def predBits = 4
}

class MemRequest[T <: Bundle] (
  tagBits: Int,
  addressBits: Int = 32,
  dataBits: Int = 32,
  metadataT: T = new Bundle{},
) extends Bundle {
  val store = Bool()
  val address = UInt(addressBits.W)
  val size = UInt(log2Ceil(log2Ceil(dataBits / 8) + 1).W) // log size
  val tag = UInt(tagBits.W)
  val data = UInt(dataBits.W)
  val metadata = metadataT.cloneType
}

class MemResponse[T <: Bundle] (
  tagBits: Int,
  dataBits: Int,
  metadataT: T = new Bundle{},
) extends Bundle {
  val tag = UInt(tagBits.W)
  val data = UInt(dataBits.W)
  val metadata = metadataT.cloneType
}

trait HasMuonCoreParameters {
  implicit val p: Parameters
  val muonParams: MuonCoreParams = p(MuonKey)
  val addressBits = muonParams.xLen

  val numLsqEntries = {
    muonParams.numWarps * (muonParams.lsu.numGlobalLdqEntries + muonParams.lsu.numGlobalStqEntries + muonParams.lsu.numSharedLdqEntries + muonParams.lsu.numSharedStqEntries)
  }
  val dmemTagBits  = log2Ceil(numLsqEntries)
  val dmemDataBits = muonParams.xLen * muonParams.lsu.numLsuLanes // FIXME: needs to be cache line
  val smemTagBits  = log2Ceil(numLsqEntries) // FIXME: separate lsq for gmem/smem?
  val smemDataBits = muonParams.xLen * muonParams.lsu.numLsuLanes
  val imemTagBits  = log2Ceil(muonParams.ibufDepth)
  val imemDataBits = muonParams.instBits

  // compute "derived" LSU parameters
  val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams)
}

abstract class CoreModule(implicit val p: Parameters) extends Module
  with HasMuonCoreParameters

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p)
  with HasMuonCoreParameters

class DataMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  val req = Decoupled(new MemRequest(dmemTagBits, addressBits, dmemDataBits))
  val resp = Flipped(Decoupled(new MemResponse(dmemTagBits, dmemDataBits)))
}

class SharedMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  val req = Decoupled(new MemRequest(smemTagBits, addressBits, smemDataBits))
  val resp = Flipped(Decoupled(new MemResponse(smemTagBits, smemDataBits)))
}

class InstMemIO(implicit val p: Parameters) extends ParameterizedBundle()(p) with HasFrontEndBundles {
  val req = Decoupled(new MemRequest(
    tagBits = imemTagBits,
    addressBits = addressBits,
    dataBits = imemDataBits,
    metadataT = new Bundle {
      val pc = pcT
      val wid = widT
    }
  ).cloneType)
  val resp = Flipped(Decoupled(new MemResponse(
    tagBits = imemTagBits,
    dataBits = imemDataBits,
    metadataT = new Bundle {
      val pc = pcT
      val wid = widT
    }
  ).cloneType))
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

  // TODO: L0

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
  dontTouch(fe.io)

  val be = Module(new Backend)
  be.io.dmem <> io.dmem
  be.io.smem <> io.smem
  dontTouch(be.io)

  be.io.ibuf <> fe.io.ibuf
}
