package radiance.muon

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import freechips.rocketchip.util.UIntIsOneOf
import org.chipsalliance.cde.config.Parameters

class SchedWriteback(implicit p: Parameters) extends CoreBundle()(p) {
  val setPC = Valid(pcT)
  val setTmask = Valid(tmaskT)
  val ipdomPush = Valid(ipdomStackEntryT) // this should be split PC+8
  val wspawn = Valid(wspawnT)
  val pc = pcT
  val wid = widT
}

class WarpScheduler(implicit p: Parameters)
  extends CoreModule
  with HasCoreBundles {

  val cmdProcOpt = None

  val io = IO(new Bundle {
    val commit = Flipped(schedWritebackT)
    val icache = icacheIO
    val issue = issueIO
    val csr = csrIO
    val rename = renameIO
    val ibuf = ibufEnqIO
    val cmdProc = cmdProcOpt.map(_ => cmdProcIO)

    val softReset = Input(Bool())
    val finished = Output(Bool())
  })

  val threadMasks = RegInit(VecInit.tabulate(m.numWarps) { wid =>
    if (wid == 0) { -1.S(tmaskT.getWidth.W).asUInt } else { 0.U.asTypeOf(tmaskT) }
  })
  val pcTracker = io.cmdProc match {
    case Some(_) => RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(pcT))))
    case None => RegInit(VecInit.tabulate(m.numWarps) { wid =>
      (new Valid(pcT)).Lit(_.valid -> (wid == 0).B, _.bits -> m.startAddress.U)
    })
  }
  val icacheInFlights = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(ibufIdxT)))
  val icacheInFlightsReg = RegNext(icacheInFlights, 0.U.asTypeOf(icacheInFlights.cloneType))
  val discardValid = RegInit(VecInit.fill(m.numWarps)(false.B))
  val discardTillPC = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(pcT)))
  val latestFetchPC = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(pcT)))
  // TODO: do we want to discard when wspawn happens? what happens if there are
  // two consecutive wspawns from other warps?
  // val spawning = WireInit(VecInit.fill(m.numWarps)(false.B))

  val stallTracker = new StallTracker(this)
  val ipdomStack = new IPDOMStack(this)

  val fetchWid = WireInit(0.U)

  val fetchArbiter = Module(new RRArbiter(UInt(), m.numWarps))
  val issueArbiter = Module(new RRArbiter(UInt(), m.numWarps))

  val fullThreadMask = (-1).S(m.numLanes.W).asUInt
  // handle new warps
  io.cmdProc match {
    case Some(cmdProc) =>
      val numActiveWarps = RegInit(0.U(log2Ceil(m.numWarps + 1).W))
      when(cmdProc.valid) {
        assert(numActiveWarps =/= m.numWarps.U, "cannot spawn more warps")
        val wid = numActiveWarps

        // initialize thread mask, pc, stall
        threadMasks(wid) := fullThreadMask
        pcTracker(wid).bits := cmdProc.bits.schedule
        pcTracker(wid).valid := true.B
        stallTracker.unstall(wid)

        numActiveWarps := numActiveWarps + 1.U
      }
    case None =>
      // tree reduce the warp spawn bundle: smallest warp wins

      def winnerT = new Bundle {
        val valid = Bool()
        val bits = wspawnT
      }

      val wspawn = io.commit.bits.wspawn
      // val wspawn = VecInit(io.commit.map { c =>
      //   val base = Wire(winnerT)
      //   base.valid := c.valid && c.bits.wspawn.valid
      //   base.bits := c.bits.wspawn.bits
      //   base
      // }).reduceTree { case (c0, c1) =>
      //   val winner = Wire(winnerT)
      //   winner.valid := c0.valid || c1.valid
      //   winner.bits := Mux(c0.valid, c0.bits, c1.bits)
      //   winner
      // }

      when(io.commit.valid && wspawn.valid) {
        val wspawnMask = ((1.U << wspawn.bits.count).asUInt - 1.U).asTypeOf(wmaskT)

        wspawnMask.asBools.zipWithIndex.map { case (en, wid) =>
          when(en) {
            when (!pcTracker(wid).valid) {
              // only set thread mask if warp not already active
              threadMasks(wid) := fullThreadMask
            }
            pcTracker(wid).bits := wspawn.bits.pc
            pcTracker(wid).valid := true.B
            // spawning(wid) := true.B
            stallTracker.unstall(wid.U)
          }
        }
      }
  }

  // increment/set PCs, fetch from i$
  io.icache.in.bits := 0.U.asTypeOf(io.icache.in.bits)
  io.icache.in.bits.wid := fetchWid
  io.icache.in.valid := fetchArbiter.io.out.valid
  pcTracker.zipWithIndex.foreach { case (entry, wid) =>
    val joinPC = ipdomStack.newPC(wid)
    // only increment PC if it's 1. enabled 2. not stalled (no hazard, icache ready) 3. selected for fetch
    when (joinPC.valid) {
      entry.bits := joinPC.bits // override normal pc
      // if this warp is being issued, we also override it
    }

    latestFetchPC(wid) := RegNext(latestFetchPC(wid))
    when (fetchWid === wid.U) {
      val currPC = Mux(joinPC.valid, joinPC.bits, entry.bits)
      io.icache.in.bits.pc := currPC
      io.icache.in.bits.wid := wid.U
      assert((entry.valid && (!stallTracker.isStalled(wid.U)._1 || joinPC.valid)) || !io.icache.in.valid)
      // when the request fires, we increment
      when(io.icache.in.fire) {
        latestFetchPC(wid) := currPC // combinational override
        entry.bits := currPC + 8.U
      }
    }

    assert(!entry.valid || threadMasks(wid).orR, s"pc tracker warp ${wid} valid but thread masks all 0")
  }

  // make discardTillPC track fetchPC when we're not discarding
  (discardTillPC lazyZip discardValid lazyZip latestFetchPC).foreach { case (d, dv, f) =>
    val latchedDiscardBits = RegNext(d, 0.U.asTypeOf(d))
    d := Mux(dv, latchedDiscardBits, f)
  }

  // assign outputs to rename
  val iresp = io.icache.out.bits
  io.rename.bits.inst := iresp.inst
  io.rename.bits.pc := iresp.pc
  io.rename.bits.wid := iresp.wid
  io.rename.bits.wmask := VecInit(pcTracker.map(_.valid)).asUInt.asTypeOf(wmaskT)
  val fetchNewMask = ipdomStack.newMask(iresp.wid) // forward new mask to fetch port
  io.rename.bits.tmask := Mux(fetchNewMask.valid, fetchNewMask.bits, threadMasks(iresp.wid))

  // handle i$ response, predecode
  io.rename.valid := false.B
  when(io.icache.out.fire) {
    when(discardValid(iresp.wid)) {
      // we are currently in discard mode, check if we can exit
      when(iresp.pc === discardTillPC(iresp.wid)) {
        // exit discard mode
        discardValid(iresp.wid) := false.B
      }
    }.otherwise {
      // not currently in discard mode, might enter if instruction is stalling
      val (stalls, joins) = Predecoder.decode(iresp.inst)
      // we discard in flights - unless there are no in flights!
      val latestPC = Mux(fetchWid === iresp.wid && io.icache.in.fire,
        io.icache.in.bits.pc, discardTillPC(iresp.wid))
      val stallingPCIsLatestPC = iresp.pc === latestPC
      when (stalls) {
        // non join hazards stall
        stallTracker.stall(iresp.wid, iresp.pc)
      }

      when (stalls || joins) {
        when (!stallingPCIsLatestPC) { // no discards: dont replay
          // reset fetch PC to post-stall
          pcTracker(iresp.wid).bits := iresp.pc + 8.U // for branches: setPC overrides this
        }
      }
      // joins still enables discard, but does not stall
      discardValid(iresp.wid) := (stalls || joins) && !stallingPCIsLatestPC
      // discardEntry.valid := stalls && !stallingPCIsLatestPC
      // discardEntry.bits is being set to the latest issued pc when we fetch

      when (joins) {
        ipdomStack.pop(iresp.wid) // this will set newPC/newMask, reflected elsewhere
      }

      io.rename.valid := !joins // joins are dealt with internally
    }
  }

  // update icache in flight count
  val icacheRespWid = io.icache.out.bits.wid
  icacheInFlights := icacheInFlightsReg
  when (!((fetchWid === icacheRespWid) && io.icache.in.fire && io.icache.out.fire)) {
    // make sure we take care of simultaneous in & out fire for the same warp
    when (io.icache.in.fire) {
      assert(icacheInFlightsReg(fetchWid) < m.ibufDepth.U)
      icacheInFlights(fetchWid) := icacheInFlightsReg(fetchWid) + 1.U
    }
    when (io.icache.out.fire) {
      assert(icacheInFlightsReg(icacheRespWid) > 0.U)
      icacheInFlights(icacheRespWid) := icacheInFlightsReg(icacheRespWid) - 1.U
    }
  }

  // update warp scheduler upon commit
  // io.commit.zipWithIndex.foreach { case (commitBundle, wid) =>
  //   val commit = commitBundle.bits
  //   when (commitBundle.valid) {
  {
    val commitBundle = io.commit
    val commit = commitBundle.bits
    val wid = commit.wid

    when (commitBundle.valid) {
      // update stalls
      val stallEntry = stallTracker.stalls(wid)
      val warpStalled = stallEntry.stallReason(stallTracker.HAZARD)
      val canUnstall = warpStalled && (stallEntry.pc === commit.pc)
      when (canUnstall) {
        stallTracker.unstall(wid)
      }

      // update thread masks, pc, ipdom
      when (commit.setPC.valid) {
        // TODO: disabled assertion because wspawn from another warp can unstall a currently stalled warp
        // TODO: and when the unstalling commit comes back, there's nothing to unstall
        // assert(canUnstall)
        pcTracker(wid).bits := commit.setPC.bits
      }
      when (commit.setTmask.valid) {
        // assert(canUnstall)
        threadMasks(wid) := commit.setTmask.bits
        when (commit.setTmask.bits === 0.U) {
          // tmc 0 -> disable warp
          pcTracker(wid).valid := false.B
        }
      }
      when (commit.ipdomPush.valid) {
        // TODO: however this one cannot be disabled because it mutates state
        assert(canUnstall)
        ipdomStack.push(wid, commit.ipdomPush.bits)
      }
    }
  }

  Seq.tabulate(numWarps) { wid =>
    // join mask update
    val mask = ipdomStack.newMask(wid)
    when (mask.valid) {
      assert((io.commit.bits.wid =/= wid.U) || !io.commit.valid || !io.commit.bits.setTmask.valid,
        "cannot set mask while there's a join")
      assert(mask.bits.orR, "join mask should not be zero")
      threadMasks(wid) := mask.bits
    }
  }

  io.finished := VecInit(pcTracker.map(!_.valid)).asUInt.andR

  // select warp for fetch
  fetchArbiter.io.in.zipWithIndex.foreach { case (arb, wid) =>
    arb.bits := wid.U
    arb.valid := pcTracker(wid).valid && !stallTracker.isStalled(wid.U)._1
  }
  fetchArbiter.io.out.ready := true.B
  fetchWid := fetchArbiter.io.out.bits

  // select warp for issue
  issueArbiter.io.in.zipWithIndex.foreach { case (arb, wid) =>
    arb.bits := wid.U
    arb.valid := io.issue.eligible.bits(wid)
  }
  issueArbiter.io.out.ready := io.issue.eligible.valid
  io.issue.issued := issueArbiter.io.out.bits
  assert(!io.issue.eligible.valid || !(io.issue.eligible.bits.orR) || issueArbiter.io.out.valid,
    "issue arbiter out not valid when inputs are valid")

  // handle csr reads
  io.csr.resp := DontCare
  when (io.csr.read.fire) {
    val req = io.csr.read.bits
    val csrData = MuxCase(
      DontCare,
      Seq(
        (req.addr === 0xcc3.U) -> VecInit(pcTracker.map(_.valid)).asUInt, // warp mask
        (req.addr === 0xcc4.U) -> threadMasks(req.wid) // thread mask
        // TODO: b00 mcycle, b80 mcycle_h
        // TODO: b02 minstret, b82 minstret_h
      )
    )
    io.csr.resp := RegNext(csrData)
  }

  // soft reset procedure
  when (io.softReset) {
    // TODO
    // enable one warp only
    // reset thread masks
    // clear discards and stalls
    // clear ipdom stacks
    // clear all registers (e.g. joins)
  }

  // misc
  io.ibuf.entry.valid := false.B
  io.ibuf.entry.bits := DontCare
}

class StallTracker(outer: WarpScheduler)(implicit m: MuonCoreParams) {
  val HAZARD = 0
  val IBUF = 1

  val stallEntryT = new Bundle {
    val pc = outer.pcT
    val stallReason = Vec(2, Bool()) // hazard, ibuf backpressure
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
//    assert(stalls(wid).stallReason(HAZARD))
    stalls(wid).stallReason(HAZARD) := false.B
  }

  def isStalled(wid: UInt) = {
    (stalls(wid).stallReason.asUInt.orR, stalls(wid).stallReason)
  }
}

object Predecoder {
  def decode(inst: UInt) = {
    val d = Decoder.decode(inst)
    val stall = WireInit(d.b(HasControlHazard))
    val join = WireInit(d.b(IsJoin))
    (stall, join)
  }
}

class IPDOMStack(outer: WarpScheduler)(implicit m: MuonCoreParams) {
  val ipdomStackMem = Seq.fill(m.numWarps)(SRAM(m.numIPDOMEntries, outer.ipdomStackEntryT, 0, 0, 1))
  val branchTaken = RegInit(VecInit.fill(m.numWarps)(VecInit.fill(m.numIPDOMEntries)(false.B)))

  val wptr = RegInit(VecInit.fill(m.numWarps)(0.U(log2Ceil(m.numIPDOMEntries + 1).W)))
  val rptr = WireInit(VecInit(wptr.map(x => (x - 1.U)(log2Ceil(m.numIPDOMEntries) - 1, 0))))
  val pushing = WireInit(VecInit.fill(m.numWarps)(false.B))
  val joiningElse = RegInit(VecInit.fill(m.numWarps)(false.B))
  val joiningEnd = RegInit(VecInit.fill(m.numWarps)(false.B))

  val ports = VecInit(ipdomStackMem.map(m => m.readwritePorts(0)))

  val newPC = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(outer.pcT))))
  val newMask = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(outer.tmaskT))))

  joiningElse.foreach(_ := false.B)
  joiningEnd.foreach(_ := false.B)

  (joiningElse lazyZip joiningEnd lazyZip newPC lazyZip newMask).zipWithIndex
    .foreach { case ((j0, j1, pc, mask), wid) =>
      pc.valid := j0
      pc.bits := ports(wid).readData.elsePC
      mask.valid := j0 || j1
      mask.bits := Mux(j0, ports(wid).readData.elseMask, ports(wid).readData.restoredMask)
    }


  (ports lazyZip rptr lazyZip wptr).foreach { case (p, ra, wa) =>
    p.enable := false.B
    p.isWrite := false.B
    p.address := Mux(p.isWrite, wa, ra)
    p.writeData := DontCare
  }

  def push(wid: UInt, ent: Bundle): Unit = {
    ports(wid).enable := true.B
    ports(wid).isWrite := true.B
    val entry = ent.asTypeOf(outer.ipdomStackEntryT)
    ports(wid).writeData := entry
    branchTaken(wid)(wptr(wid)) := (entry.elseMask === 0.U) // non-divergent branch
    pushing(wid) := true.B
    assert(wptr(wid) < m.numIPDOMEntries.U, "ipdom stack is full")
    wptr(wid) := wptr(wid) + 1.U
  }

  def pop(wid: UInt) = {
    assert(!pushing(wid)) // there should never be a simultaneous push and pop
    ports(wid).enable := true.B
    ports(wid).isWrite := false.B
    assert(wptr(wid) > 0.U, "ipdom stack is empty")

    when (!branchTaken(wid)(rptr(wid))) {
      // done with then, start with else: set pc, update taken
//      newMask := ports(wid).readData.elseMask
      // this is handled earlier
      branchTaken(wid)(rptr(wid)) := true.B
      joiningElse(wid) := true.B
    }.otherwise {
      // done with else, pop but don't set pc
      wptr(wid) := wptr(wid) - 1.U
      joiningEnd(wid) := true.B
    }
  }
}
