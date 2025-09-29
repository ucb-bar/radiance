package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.subsystem.RadianceSimArgs
import radiance.muon.CoreModule

class WarpScheduler(implicit p: Parameters) extends CoreModule {
  implicit val m: MuonCoreParams = muonParams

  val pcT = UInt(32.W)
  val widT = UInt(log2Ceil(m.numWarps).W)
  val tmaskT = UInt(m.numLanes.W)
  val wmaskT = UInt(m.numWarps.W)
  val instT = UInt(64.W)
  val ibufIdxT = UInt(log2Ceil(m.ibufDepth + 1).W)

  val ipdomStackEntryT = new Bundle {
    val restoredMask = tmaskT
    val elseMask = tmaskT
    val elsePC = pcT
  }

  val poppedEntryT = new Bundle {
    val mask = tmaskT
    val pc = pcT
  }

  require(isPow2(m.numIPDOMEntries))

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
    val read = Flipped(ValidIO(UInt(m.csrAddrBits.W))) // reads only
    val resp = Output(UInt(m.xLen.W)) // next cycle
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
    val count = Input(Vec(m.numWarps, ibufIdxT))
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

  val threadMasks = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(tmaskT)))
  val pcTracker = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(pcT))))
  val icacheInFlights = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(ibufIdxT)))
  val icacheInFlightsReg = RegNext(icacheInFlights, 0.U.asTypeOf(icacheInFlights.cloneType))
  val discardTillPC = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(pcT))))

  val stallTracker = new StallTracker(this)
  val ipdomStack = new IPDOMStack(this)

  val fetchWid = WireInit(0.U)

  // handle new warps
  val numActiveWarps = RegInit(0.U(log2Ceil(m.numWarps + 1).W))
  when (io.cmdProc.valid) {
    assert(numActiveWarps =/= m.numWarps.U, "cannot spawn more warps")
    val wid = numActiveWarps

    // initialize thread mask, pc, stall
    threadMasks(wid) := ((1 << m.numLanes) - 1).U.asTypeOf(tmaskT)
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
      discardTillPC(wid).bits := pcTracker(wid).bits // the latest issued pc
    }
  }

  // assign decode outputs
  val iresp = io.icache.out.bits
  io.decode.bits.inst := iresp.inst
  io.decode.bits.wid := iresp.wid
  io.decode.bits.tmask := threadMasks(iresp.wid)

  // handle i$ response, predecode
  io.decode.valid := false.B
  when (io.icache.out.fire) {
    val discardEntry = discardTillPC(iresp.wid)

    when (discardEntry.valid) {
      // we are currently in discard mode, check if we can exit
      when (iresp.pc === discardEntry.bits) {
        // exit discard mode
        discardEntry.valid := false.B
      }
    }.otherwise {
      val (stalls, joins) = Predecoder.decode(iresp.inst)
      // if the current instruction stalls, enter discard mode
      when (stalls || joins) {
        // stall the warp and enable discard mode
        stallTracker.stall(iresp.wid, iresp.pc)
        discardEntry.valid := true.B
        // discardEntry.bits is being set to the latest issued pc when we fetch
      }

      // reset fetch PC to post-stall
      pcTracker(iresp.wid) := iresp.pc + 8.U // for branches: setPC overrides this

      // TODO: handle joins

      io.decode.valid := !joins // joins are dealt with internally
    }
  }

  // update icache in flight count
  val icacheRespWid = io.icache.out.bits.wid
  icacheInFlights := icacheInFlightsReg
  when (!((fetchWid === icacheRespWid) && io.icache.in.fire && io.icache.out.fire)) {
    // make sure we take care of simultaneous in & out fire for the same warp
    when (io.icache.in.fire) {
      assert(icacheInFlightsReg(icacheRespWid) <= m.ibufDepth.U)
      icacheInFlights(fetchWid) := icacheInFlightsReg(fetchWid) + 1.U
    }
    when (io.icache.out.fire) {
      assert(icacheInFlightsReg(icacheRespWid) >= 0.U)
      icacheInFlights(icacheRespWid) := icacheInFlightsReg(icacheRespWid) - 1.U
    }
  }

  // update warp scheduler upon commit
  when (io.commit.fire) {
    val commit = io.commit.bits

    // update stalls
    val stallEntry = stallTracker.stalls(commit.wid)
    val warpStalled = stallEntry.stallReason(stallTracker.HAZARD)
    val canUnstall = warpStalled && (stallEntry.pc === commit.pc)
    when (canUnstall) {
      stallTracker.unstall(commit.wid)
    }

    // update thread masks, pc, ipdom
    when (commit.setPC.valid) {
      assert(canUnstall)
      pcTracker(commit.wid).bits := commit.setPC.bits
    }

    when (commit.setTmask.valid) {
      assert(canUnstall)
      threadMasks(commit.wid) := commit.setTmask.bits
    }

    when (commit.ipdomPush.valid) {
      assert(canUnstall)
//      ipdomStack.
    }
  }

//  when (io.split)

  // select warp for fetch

  // select warp for issue

  // handle csr reads
}

class StallTracker(outer: WarpScheduler)(implicit m: MuonCoreParams) {
  val pcT = outer.pcT

  val HAZARD = 0
  val IBUF = 1

  val stallEntryT = new Bundle {
    val pc = pcT
    val stallReason = UInt(2.W) // hazard, ibuf backpressure
  }
  val stalls = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(stallEntryT)))

  stalls.zipWithIndex.foreach { case (entry, wid) =>
    val ibufReady = (outer.io.ibuf.count(wid) +& outer.icacheInFlights(wid)) < m.ibufDepth.U
    entry.stallReason(IBUF) := !ibufReady
  }

  def stall(wid: UInt, pc: UInt) = {
    assert(!stalls(wid).stallReason(HAZARD))
    stalls(wid).pc := pc
    stalls(wid).stallReason(HAZARD) := true.B
  }

  def unstall(wid: UInt) = {
    assert(stalls(wid).stallReason(HAZARD))
    stalls(wid).stallReason(HAZARD) := false.B
  }

  def isStalled(wid: UInt) = {
    val icacheReady = outer.io.icache.in.ready
    (stalls(wid).stallReason.orR || !icacheReady, stalls(wid).stallReason)
  }
}

object Predecoder {

  def decode(inst: UInt) = {
    val d = Decoded(inst)
    val isHazardInst = VecInit(Seq(MuOpcode.JALR, MuOpcode.JAL, MuOpcode.SYSTEM, MuOpcode.BRANCH)
      .map(d.opcode === _)).reduceTree(_ || _) || d.isTMC || d.isSplit || d.isPred || d.isWSpawn || d.isBar
    val stall = WireInit(isHazardInst)
    val join = WireInit(d.isJoin)
    (stall, join)
  }
}

class IPDOMStack(outer: WarpScheduler)(implicit m: MuonCoreParams) {
  val ipdomStackMem = SRAM(m.numIPDOMEntries, outer.ipdomStackEntryT, 1, 1, 0)
  val branchTaken = RegInit(0.U(m.numIPDOMEntries.W))

  val r = ipdomStackMem.readPorts(0)
  val w = ipdomStackMem.writePorts(0)
  r.enable := false.B
  w.enable := false.B

  def push(wid: UInt, ent: outer.ipdomStackEntryT.type): Unit = {
    ipdomStackMem.writePorts(0).enable := true.B

    // TODO
  }
}

class WarpArbiter(policy: Int)(implicit p: MuonCoreParams) extends Module {
}
