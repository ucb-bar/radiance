package radiance.muon

import chisel3._
import chisel3.util._
import freechips.rocketchip.resources.BigIntHexContext
import freechips.rocketchip.rocket.ALU
import freechips.rocketchip.util.ParameterizedBundle
import org.chipsalliance.cde.config.{Field, Parameters}
import org.chipsalliance.diplomacy.lazymodule.LazyModule
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
  // collector
  // if true, use a bank-conflict-avoiding operand collector
  // if false, use a simple rs1/2/3-duplicated register file
  useCollector: Boolean = false,
  numRegBanks: Int = 1,          // when useCollector true
  numCollectorEntries: Int = 1,  // when useCollector true
  // execute
  intPipe: IntPipeParams = IntPipeParams(16, 16),
  fpPipe: FPPipeParams = FPPipeParams(8, 1),
  csrAddrBits: Int = 32,
  // memory
  lsu: LoadStoreUnitParams = LoadStoreUnitParams(),
  logSMEMInFlights: Int = 2,
  // misc
  barrierBits: Int = 4,
  // debug bundles and prints
  debug: Boolean = true
) extends PhysicalCoreParams {
  val warpIdBits = log2Up(numWarps)
  val coreIdBits: Int = log2Ceil(numCores)
  val clusterIdBits: Int = log2Ceil(numClusters)
  val pRegBits = log2Up(numPhysRegs)
  def l0dReqTagBits: Int = {
    val packetBits = log2Up(numLanes / lsu.numLsuLanes) // from lsu derived params
    val sourceIdBits = LsuQueueToken.width(this) + packetBits
    val laneBits = log2Ceil(numLanes)
    val coalVsNonCoal = 1
    val sizeTagBits = 3 // store the size in the cache tag
    val totalBits = sourceIdBits + laneBits + coalVsNonCoal + sizeTagBits
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
    println("l1 tag bits", instVsData + coreBits + 6) // max(log2Ceil(nMSHRs + nMMIOs) for i, d)
    instVsData + coreBits + 6
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
  implicit val m = muonParams

  val numLanes = muonParams.numLanes
  val numWarps = muonParams.numWarps
  val archLen = muonParams.archLen
  val numLaneBytes = muonParams.numLanes * muonParams.archLen / 8

  val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams)
  val addressBits = muonParams.archLen
  val dmemTagBits  = lsuDerived.sourceIdBits + lsuDerived.laneIdBits
  val dmemDataBits = muonParams.archLen // FIXME: needs to be cache line
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

  def barrierIO = new BarrierBundle(BarrierParams(
    haveBits = m.warpIdBits,
    barrierBits = m.barrierBits,
    wantBits = m.warpIdBits + m.coreIdBits,
  ))
}

abstract class CoreModule(implicit val p: Parameters) extends Module
  with HasCoreParameters

abstract class CoreBundle(implicit val p: Parameters) extends ParameterizedBundle()(p)
  with HasCoreParameters

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

object Perf {
  val counterWidth = 64
}

class PerfIO()(implicit p: Parameters) extends CoreBundle()(p) {
  val backend = new BackendPerfIO
}

/** Trace IO to software testbench that logs PC and register read data at
 *  issue time. */
class TraceIO()(implicit p: Parameters) extends CoreBundle()(p) {
  val pc = pcT
  val warpId = widT
  val regs = Vec(Isa.maxNumRegs, new Bundle {
    val enable = Bool()
    val address = pRegT
    val data = Vec(numLanes, regDataT)
  })
}

/** Muon core and core-private L0 caches */
class MuonCore(
  val test: Boolean = false
)(implicit p: Parameters) extends CoreModule {
  val io = IO(new Bundle {
    val imem = new InstMemIO
    val dmem = new DataMemIO
    val smem = new SharedMemIO
    val barrier = barrierIO
    val softReset = Input(Bool())
    val coreId = Input(UInt(muonParams.coreIdBits.W))
    val clusterId = Input(UInt(muonParams.clusterIdBits.W))
    val finished = Output(Bool())
    val perf = new PerfIO
    /** PC/reg trace IO for diff-testing against model */
    val trace = Option.when(test)(Valid(new TraceIO))
    // TODO: LCP (threadblock start/done, warp slot, synchronization)
  })
  dontTouch(io)

  val fe = Module(new Frontend)
  fe.io.imem <> io.imem
  fe.io.softReset := io.softReset
  io.finished := fe.io.finished

  val be = Module(new Backend(test))
  be.io.dmem <> io.dmem
  be.io.smem <> io.smem
  be.io.feCSR := fe.io.csr
  be.io.barrier <> io.barrier
  be.io.coreId := io.coreId
  be.io.clusterId := io.clusterId
  be.io.softReset := io.softReset
  be.io.trace.foreach(_ <> io.trace.get)
  io.perf.backend <> be.io.perf

  fe.io.lsuReserve <> be.io.lsuReserve
  fe.io.commit := be.io.schedWb

  (be.io.ibuf zip fe.io.ibuf).foreach { case (b, f) => b <> f }
}
