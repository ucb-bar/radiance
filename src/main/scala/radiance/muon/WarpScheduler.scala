package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.subsystem.RadianceSimArgs

class WarpScheduler(implicit p: MuonCoreParams, q: Parameters) extends Module {
  val pcT = UInt(32.W)
  val widT = UInt(log2Ceil(p.numWarps).W)
  val tmaskT = UInt(p.numLanes.W)
  val wmaskT = UInt(p.numWarps.W)
  val instT = UInt(64.W)
  val ibufIdxT = UInt(log2Ceil(p.ibufDepth + 1).W)

  val ipdomStackEntryT = new Bundle {
    val restoredMask = tmaskT
    val elseMask = tmaskT
    val elsePC = pcT
  }

  val poppedEntryT = new Bundle {
    val mask = tmaskT
    val pc = pcT
  }

  require(isPow2(p.numIPDOMEntries))

  val ipdomIO = new Bundle {
    val pop = Input(Bool())
    val popped = ValidIO(poppedEntryT)
    val wid = Input(widT)
  } // TODO

  val commitIO = ValidIO(new Bundle {
    val setPC = Flipped(ValidIO(pcT))
    val setTmask = Flipped(ValidIO(tmaskT))
    val ipdomPush = Flipped(ValidIO(ipdomStackEntryT))
    val pc = Input(pcT)
    val wid = Input(widT)
  })

  val icacheIO = new Bundle {
    val in = DecoupledIO(new Bundle {
      val pc = pcT
      val wid = widT
    }) // icache can stall scheduler
    val out = Flipped(ValidIO(new Bundle {
      val inst = instT
      val pc = pcT
      val wid = widT
    }))
  }

  val issueIO = new Bundle {
    val eligible = Flipped(ValidIO(wmaskT))
    val issued = Output(wmaskT) // 1H, next cycle from input
  }

  val csrIO = new Bundle {
    val read = Flipped(ValidIO(UInt(p.csrAddrBits.W))) // reads only
    val resp = Output(UInt(p.xLen.W)) // next cycle
  }

  val cmdProcIO = Flipped(ValidIO(new Bundle {
    val schedule = pcT
  }))

  val decodeIO = DecoupledIO(new Bundle {
    val inst = instT
    val tmask = tmaskT
    val wid = widT
  })

  val ibufIO = new Bundle {
    val count = Input(Vec(p.numWarps, ibufIdxT))
  }

  val io = IO(new Bundle {
    val commit = commitIO
    val icache = icacheIO
    val issue = issueIO
    val csr = csrIO
    val cmdProc = cmdProcIO
    val decode = decodeIO
    val ibuf = ibufIO
  })

  val threadMask = RegInit(VecInit.fill(p.numWarps)(0.U.asTypeOf(tmaskT)))
  val pcTracker = RegInit(VecInit.fill(p.numWarps)(0.U.asTypeOf(Valid(pcT))))
  val ipdomStackMem = SRAM(p.numIPDOMEntries, ipdomStackEntryT, 1, 1, 0)
  val branchTaken = RegInit(0.U(p.numIPDOMEntries.W))
  val icacheInFlights = WireInit(VecInit.fill(p.numWarps)(0.U.asTypeOf(ibufIdxT)))
  val icacheInFlightsReg = RegNext(icacheInFlights, 0.U.asTypeOf(icacheInFlights.cloneType))
  val discardTillPC = RegInit(VecInit.fill(p.numWarps)(0.U.asTypeOf(Valid(pcT))))

  val stallTracker = new StallTracker(this)

  val fetchWid = WireInit(0.U)

  // handle new warps
  val numActiveWarps = RegInit(0.U(log2Ceil(p.numWarps + 1)))
  when (io.cmdProc.valid) {
    assert(numActiveWarps =/= p.numWarps.U, "cannot spawn more warps")
    val wid = numActiveWarps

    // initialize thread mask, pc, stall
    threadMask(wid) := ((1 << p.numLanes) - 1).U.asTypeOf(tmaskT)
    pcTracker(wid).bits := io.cmdProc.bits.schedule
    pcTracker(wid).valid := true.B
    stallTracker.unstall(wid)

    numActiveWarps := numActiveWarps + 1.U
  }

  // increment/set PCs, fetch from i$
  io.icache.in.valid := false.B
  pcTracker.zipWithIndex.foreach { case (entry, wid) =>
    // only increment PC if it's 1. enabled 2. not stalled (no hazard, icache ready) 3. selected for fetch
    when (wid.U === fetchWid && entry.valid && !stallTracker.isStalled(wid.U)._1) {
      // every PC increment is accompanied by fetch fire
      io.icache.in.valid := true.B
      io.icache.in.bits := pcTracker(wid).bits
      pcTracker(wid).bits := pcTracker(wid).bits + 8.U
    }
  }

  // handle i$ response, predecode
  when (io.icache.out.valid) {

    //
    io.icache.out.bits.inst
  }

  // update icache in flight count
  val icacheRespWid = io.icache.out.bits.wid
  icacheInFlights := icacheInFlightsReg
  when (!((fetchWid === icacheRespWid) && io.icache.in.fire && io.icache.out.fire)) {
    // make sure we take care of simultaneous in & out fire for the same warp

    when (io.icache.in.fire) {
      assert(icacheInFlightsReg(icacheRespWid) <= p.ibufDepth.U)
      icacheInFlights(fetchWid) := icacheInFlightsReg(fetchWid) + 1.U
    }
    when (io.icache.out.fire) {
      assert(icacheInFlightsReg(icacheRespWid) >= 0.U)
      icacheInFlights(icacheRespWid) := icacheInFlightsReg(icacheRespWid) - 1.U
    }
  }


  // update thread masks & update pc valid

  // select warp for fetch

  // select warp for issue
}

class StallTracker(outer: WarpScheduler)(implicit p: MuonCoreParams, q: Parameters) {
  val pcT = outer.pcT

  val HAZARD = 0
  val IBUF = 1

  val stallEntryT = new Bundle {
    val pc = pcT
    val stallReason = UInt(2.W) // hazard, ibuf backpressure
  }
  val stalls = RegInit(VecInit.fill(p.numWarps)(0.U.asTypeOf(stallEntryT)))

  stalls.zipWithIndex.foreach { case (entry, wid) =>
    val ibufReady = (outer.io.ibuf.count(wid) +& outer.icacheInFlights(wid)) < p.ibufDepth.U
    entry.stallReason(IBUF) := !ibufReady
  }

  def stall(wid: UInt, pc: UInt) = {
    stalls(wid).pc := pc
    stalls(wid).stallReason(HAZARD) := true.B
  }

  def unstall(wid: UInt) = {
    stalls(wid).stallReason(HAZARD) := false.B
  }

  def isStalled(wid: UInt) = {
    val icacheReady = outer.io.icache.in.ready
    (stalls(wid).stallReason.orR || !icacheReady, stalls(wid).stallReason)
  }
}

class Predecoder(implicit p: MuonCoreParams) extends Module {

  def decode(inst: UInt) = {
    val d = Decoded(inst)
    val isHazardInst = VecInit(Seq(MuOpcode.JALR, MuOpcode.JAL, MuOpcode.SYSTEM, MuOpcode.BRANCH)
      .map(d.opcode === _)).reduceTree(_ || _) || d.isTMC || d.isSplit || d.isPred || d.isWSpawn || d.isBar
    val stall = WireInit(isHazardInst)
    val join = WireInit(d.isJoin)
    (stall, join)
  }
}

class IPDOMStack(implicit p: MuonCoreParams) extends Module {
}

class WarpArbiter(policy: Int)(implicit p: MuonCoreParams) extends Module {
}
