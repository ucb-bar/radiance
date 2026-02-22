package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.ALU
import freechips.rocketchip.util.ParameterizedBundle
import org.chipsalliance.cde.config.{Field, Parameters}
import radiance.cluster.CacheFlushBundle
import radiance.muon.backend.RegWriteback
import radiance.muon.backend.fp.FPPipeParams
import radiance.muon.backend.int.IntPipeParams
import radiance.subsystem.PhysicalCoreParams

case object MuonKey extends Field[MuonCoreParams]

case class MuonCoreParams(
  archLen: Int = 32,
  // end boilerplate
  numClusters: Int = 2,
  numCores: Int = 2,
  numWarps: Int = 8,
  numLanes: Int = 16,
  // schedule, dispatch, rename
  numPhysRegs: Int = 256,
  numArchRegs: Int = 128,
  logRenameMinWarps: Int = 1, // minimum 2 warps share PRF
  numIPDOMEntries: Int = 8,
  ibufDepth: Int = 8,
  startAddress: BigInt = x"1000_0000",
  // issue
  numIssueQueueEntries: Int = 8, // RS
  maxPendingReads: Int = 3,      // scoreboard
  noILP: Boolean = false, // fallback to single-in-flight instruction issue
                          // logic ("bypass")
  // collector
  useCollector: Boolean = true,  // if true, use a bank-conflict-avoiding
                                 // operand collector; if false, use a simple
                                 // rs1/2/3-duplicated register file
  numRegBanks: Int = 1,          // when useCollector true
  numCollectorEntries: Int = 2,  // when useCollector true
  forwardCollectorIssue: Boolean = true,  // perf optimization: pre-empt collector
                                          // responses by one cycle for earlier
                                          // issue eligiblity check
  // execute
  intPipe: IntPipeParams = IntPipeParams(16, 16),
  fpPipe: FPPipeParams = FPPipeParams(8, 2),
  csrAddrBits: Int = 32,
  // memory
  lsu: LoadStoreUnitParams = LoadStoreUnitParams(),
  logGMEMInFlights: Int = 4, // per lane
  logCoalGMEMInFlights: Int = 5, // all lanes
  logNonCoalGMEMInFlights: Int = 5, // all lanes
  // misc
  barrierBits: Int = 4,
  debug: Boolean = false, // enable extra IOs for debug (ex: PC)
  difftest: Boolean = false // enable arch-state differential testing
                            // against cyclotron
) extends PhysicalCoreParams {
  val warpIdBits = log2Up(numWarps)
  val coreIdBits: Int = log2Ceil(numCores)
  val clusterIdBits: Int = log2Ceil(numClusters)
  val pRegBits = log2Up(numPhysRegs)
  def l0dReqTagBits: Int = {
    val coalVsNonCoal = 1
    val sizeTagBits = 3 // store the size in the cache tag
    val totalBits = (logCoalGMEMInFlights max logNonCoalGMEMInFlights) + coalVsNonCoal + sizeTagBits
    println("l0d tag bits", totalBits)
    totalBits
  }
  def l0iReqTagBits: Int = {
    println("l0i tag bits", warpIdBits + log2Ceil(ibufDepth))
    log2Ceil(ibufDepth) + warpIdBits
  }
  def l1ReqTagBits: Int = {
    val instVsData = 1
    val coreBits = log2Ceil(numCores)
    instVsData + coreBits + 5
  }
}

object Isa {
  def instBits = 64
  def opcodeBits = 9
  def regBits = 8
  def csrImmBits = 8
  def f3Bits = 3
  def f7Bits = 7
  def immBits = 32
  def predBits = 4
  def maxNumRegs = 3 // rs1/2/3
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
  val mask = UInt((dataBits / 8).W)
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

/** derived parameters from MuonCoreParams */
trait HasCoreParameters {
  implicit val p: Parameters
  val muonParams: MuonCoreParams = p(MuonKey)
  implicit val m: MuonCoreParams = muonParams

  val numLanes = muonParams.numLanes
  val numWarps = muonParams.numWarps
  val archLen = muonParams.archLen
  val numLaneBytes = muonParams.numLanes * muonParams.archLen / 8

  val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams)
  val addressBits = muonParams.archLen
  val dmemTagBits  = lsuDerived.sourceIdBits + lsuDerived.laneIdBits
  val dmemDataBits = muonParams.archLen
  val smemTagBits  = lsuDerived.sourceIdBits + lsuDerived.laneIdBits
  val smemDataBits = muonParams.archLen
  val imemTagBits  = log2Ceil(muonParams.numWarps * muonParams.ibufDepth)
  val imemDataBits = muonParams.instBits

  require(muonParams.maxPendingReads > 0, "wrong maxPendingReads for scoreboard")
  val scoreboardReadCountBits = log2Ceil(muonParams.maxPendingReads + 1)
  val scoreboardWriteCountBits = 1 // 0 or 1

  def pcT = UInt(m.archLen.W)
  def widT = UInt(m.warpIdBits.W)
  def tmaskT = UInt(m.numLanes.W)
  def wmaskT = UInt(m.numWarps.W)
  def instT = UInt(m.instBits.W)
  def ibufIdxT = UInt(log2Ceil(m.ibufDepth + 1).W)

  def ipdomStackEntryT = new Bundle {
    val divergent = Bool()
    val restoredMask = tmaskT
    val elseMask = tmaskT
    val elsePC = pcT
  }

  def wspawnT = new Bundle {
    val count = UInt(log2Ceil(m.numWarps + 1).W)
    val pc = pcT
  }

  require(isPow2(m.numIPDOMEntries))

  def fuInT(hasRs1: Boolean = false, hasRs2: Boolean = false, hasRs3: Boolean = false) = new Bundle {
    val uop = uopT
    val rs1Data = Option.when(hasRs1)(Vec(m.numLanes, regDataT))
    val rs2Data = Option.when(hasRs2)(Vec(m.numLanes, regDataT))
    val rs3Data = Option.when(hasRs3)(Vec(m.numLanes, regDataT))
  }

  def schedWritebackT = Valid(new SchedWriteback)
  def regWritebackT = Valid(new RegWriteback)

  def writebackT(hasSched: Boolean = true, hasReg: Boolean = true) = new Bundle {
    val sched = Option.when(hasSched)(schedWritebackT)
    val reg = Option.when(hasReg)(regWritebackT)
  }

  def icacheIO = new Bundle {
    val in = DecoupledIO(new Bundle {
      val pc = pcT
      val wid = widT
    }) // icache can stall scheduler
    val out = Flipped(Valid(new Bundle {
      val inst = instT
      val pc = pcT
      val wid = widT
    }))
  }

  def memoryIO = new Bundle {
    val dmem = new DataMemIO
    val smem = new SharedMemIO
  }

  def reservationIO = Vec(muonParams.numWarps, new Bundle {
    val req = Flipped(Decoupled(new LsuReservationReq))
    val resp = Valid(new LsuReservationResp)
  })

  def issueIO = new Bundle {
    val eligible = Flipped(Valid(wmaskT))
    val issued = Output(widT) // comb
  }

  def feCSRIO = Output(new Bundle {
    val wmask = wmaskT
  })

  def cmdProcIO = Flipped(Valid(new Bundle {
    val schedule = pcT
  }))

  def renameIO = Valid(new Bundle {
    val inst = instT
    val tmask = tmaskT
    val wmask = wmaskT
    val wid = widT
    val pc = pcT
  })

  def uopT = new UOp
  def lsuTokenT = new LsuQueueToken

  def ibufEntryT = new InstBufEntry
  def ibufEnqIO = new Bundle {
    val count = Input(ibufIdxT)
    val uop = Valid(uopT)
  }

  def clusterCoreIdT = new Bundle {
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val coreId = Input(UInt(muonParams.coreIdBits.W))
  }

  def pRegT = UInt(log2Ceil(m.numPhysRegs).W)
  def aRegT = UInt(log2Ceil(m.numArchRegs).W)
  def regDataT = UInt(m.archLen.W)

  def aluOpT = UInt(ALU.SZ_ALU_FN.W)

  def csrDataT = UInt(32.W)

  def lsuFenceIO = Output(new Bundle {
    val globalQueuesEmpty = Bool()
    val sharedQueuesEmpty = Bool()
  })

  def cacheFlushIO = new Bundle {
    val i = new CacheFlushBundle
    val d = new CacheFlushBundle
  }

  def barrierIO = new BarrierBundle(BarrierParams(
    haveBits = m.warpIdBits,
    barrierBits = m.barrierBits,
    wantBits = m.warpIdBits + m.coreIdBits,
  ))

  def debugf(pable: Printable) = {
    if (muonParams.debug) {
      printf(pable)
    }
  }
}

abstract class CoreModule(implicit val p: Parameters) extends Module
  with HasCoreParameters

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p)
  with HasCoreParameters

/** Data memory interface for the core.
  * The interface is per-lane, with each lane being word-size-wide with its own
  * tag and ready/valid.  The coalescer at downstream is responsible for merging
  * them into wide reqs when possible. */
class DataMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  val req = Vec(
    muonParams.lsu.numLsuLanes,
    Decoupled(new MemRequest(dmemTagBits, addressBits, dmemDataBits))
  )
  val resp = Vec(
    muonParams.lsu.numLsuLanes,
    Flipped(Decoupled(new MemResponse(dmemTagBits, dmemDataBits)))
  )
}

/** Shared memory interface for the core.
  * Per-lane similar to DataMemIO. */
class SharedMemIO(implicit p: Parameters) extends CoreBundle()(p) {
  val req = Vec(
    muonParams.lsu.numLsuLanes,
    Decoupled(new MemRequest(smemTagBits, addressBits, smemDataBits))
  )
  val resp = Vec(
    muonParams.lsu.numLsuLanes,
    Flipped(Decoupled(new MemResponse(smemTagBits, smemDataBits)))
  )
}

/** Instruction memory interface for the core.
  * The interface is a single, instruction-length-wide lane, serving all lanes
  * in the backend via SIMD. */
class InstMemIO(implicit val p: Parameters) extends ParameterizedBundle()(p) with HasCoreParameters {
  val req = Decoupled(new MemRequest(
    tagBits = imemTagBits,
    addressBits = addressBits,
    dataBits = imemDataBits,
  ).cloneType)
  val resp = Flipped(Decoupled(new MemResponse(
    tagBits = imemTagBits,
    dataBits = imemDataBits,
  ).cloneType))
}

class PerfIO()(implicit p: Parameters) extends CoreBundle()(p) {
  val backend = new BackendPerfIO
}

/** Trace IO to software testbench that logs PC and register read data at
  * issue time. */
class TraceIO()(implicit p: Parameters) extends CoreBundle()(p) {
  val pc = pcT
  val warpId = widT
  val tmask = tmaskT
  val regs = Vec(Isa.maxNumRegs, new Bundle {
    val enable = Bool()
    val address = pRegT
    val data = Vec(numLanes, regDataT)
  })
}

/** Muon core and core-private L0 caches */
class MuonCore(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val barrier = barrierIO
    val flush = cacheFlushIO
    val softReset = Input(Bool())
    val coreId = Input(UInt(muonParams.coreIdBits.W))
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val finished = Output(Bool())
    val perf = new PerfIO
    /** PC/reg trace IO for diff-testing against model */
    val trace = Option.when(muonParams.difftest)(Valid(new TraceIO))
    // TODO: LCP (threadblock start/done, warp slot, synchronization)
  })
  dontTouch(io)

  val fe = Module(new Frontend)
  fe.io.imem <> io.imem
  fe.io.softReset := io.softReset
  io.finished := fe.io.finished

  val be = Module(new Backend(muonParams.difftest))
  be.io.dmem <> io.dmem
  be.io.smem <> io.smem
  be.io.feCSR := fe.io.csr
  be.io.barrier <> io.barrier
  be.io.flush <> io.flush
  be.io.coreId := io.coreId
  be.io.clusterId := io.clusterId
  be.io.softReset := io.softReset
  be.reset := reset.asBool || io.softReset
  be.io.trace.foreach(_ <> io.trace.get)
  io.perf.backend <> be.io.perf

  fe.io.lsuReserve <> be.io.lsuReserve
  fe.io.commit := be.io.schedWb

  (be.io.ibuf zip fe.io.ibuf).foreach { case (b, f) => b <> f }
}
