package radiance.muon

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import freechips.rocketchip.util.UIntIsOneOf
import org.chipsalliance.cde.config.Parameters

class WarpScheduler(implicit p: Parameters)
  extends CoreModule
  with HasFrontEndBundles {

  val cmdProcOpt = None

  val io = IO(new Bundle {
    val commit = commitIO
    val icache = icacheIO
    val issue = issueIO
    val csr = csrIO
    val decode = decodeIO
    val ibuf = ibufEnqIO
    val cmdProc = cmdProcOpt.map(_ => cmdProcIO)
  })

  val threadMasks = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(tmaskT)))
  val pcTracker = io.cmdProc match {
    case Some(_) => RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(pcT))))
    case None => RegInit(VecInit.tabulate(m.numWarps) { wid =>
      (new Valid(pcT)).Lit(_.valid -> (wid == 0).B, _.bits -> m.startAddress.U)
    })
  }
  val icacheInFlights = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(ibufIdxT)))
  val icacheInFlightsReg = RegNext(icacheInFlights, 0.U.asTypeOf(icacheInFlights.cloneType))
  val discardTillPC = RegInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(pcT))))

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
      val wspawn = VecInit(io.commit.map { c =>
        val base = Wire(winnerT)
        base.valid := c.valid && c.bits.wspawn.valid
        base.bits := c.bits.wspawn.bits
        base
      }).reduceTree { case (c0, c1) =>
        val winner = Wire(winnerT)
        winner.valid := c0.valid || c1.valid
        winner.bits := Mux(c0.valid, c0.bits, c1.bits)
        winner
      }

      when (wspawn.valid) {
        val wspawnMask = ((1.U << wspawn.bits.count).asUInt - 1.U).asTypeOf(wmaskT)

        wspawnMask.asBools.zipWithIndex.map { case (en, wid) =>
          when (en) {
            threadMasks(wid) := fullThreadMask
            pcTracker(wid).bits := wspawn.bits.pc
            pcTracker(wid).valid := true.B
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

    when (fetchWid === wid.U) {
      val currPC = Mux(joinPC.valid, joinPC.bits, entry.bits)
      io.icache.in.bits.pc := currPC
      io.icache.in.bits.wid := wid.U
      assert((entry.valid && (!stallTracker.isStalled(wid.U)._1 || joinPC.valid)) || !io.icache.in.valid)
      // when the request fires, we increment
      when (io.icache.in.fire) {
        discardTillPC(wid).bits := currPC
        entry.bits := currPC + 8.U
      }
    }
  }

  // assign decode outputs
  val iresp = io.icache.out.bits
  io.decode.bits.inst := Decoder.decode(iresp.inst)
  io.decode.bits.wid := iresp.wid
  io.decode.bits.wmask := VecInit(pcTracker.map(_.valid)).asUInt.asTypeOf(wmaskT)
  val fetchNewMask = ipdomStack.newMask(iresp.wid) // forward new mask to fetch port
  io.decode.bits.tmask := Mux(fetchNewMask.valid, fetchNewMask.bits, threadMasks(iresp.wid))

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
      when (stalls) {
        // non join hazards stall
        stallTracker.stall(iresp.wid, iresp.pc)
      }
      // joins still enables discard, but does not stall
      discardEntry.valid := stalls || joins
      // discardEntry.bits is being set to the latest issued pc when we fetch

      // reset fetch PC to post-stall
      pcTracker(iresp.wid).bits := iresp.pc + 8.U // for branches: setPC overrides this

      when (joins) {
        ipdomStack.pop(iresp.wid) // this will set newPC/newMask, reflected elsewhere
      }

      io.decode.valid := !joins // joins are dealt with internally
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
  io.commit.zipWithIndex.foreach { case (commitBundle, wid) =>
    val commit = commitBundle.bits

    when (commitBundle.valid) {
      // update stalls
      val stallEntry = stallTracker.stalls(wid)
      val warpStalled = stallEntry.stallReason(stallTracker.HAZARD)
      val canUnstall = warpStalled && (stallEntry.pc === commit.pc)
      when (canUnstall) {
        stallTracker.unstall(wid.U)
      }

      // update thread masks, pc, ipdom
      when (commit.setPC.valid) {
        assert(canUnstall)
        pcTracker(wid).bits := commit.setPC.bits
      }
      when (commit.setTmask.valid) {
        assert(canUnstall)
        threadMasks(wid) := commit.setTmask.bits
      }
      when (commit.ipdomPush.valid) {
        assert(canUnstall)
        ipdomStack.push(wid.U, commit.ipdomPush.bits)
      }
    }

    // join mask update
    val mask = ipdomStack.newMask(wid)
    when (mask.valid) {
      assert(!commitBundle.valid || !commit.setTmask.valid,
        "cannot set mask while there's a join")
      threadMasks(wid) := mask.bits
    }
  }

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

  // misc
  io.ibuf.enq.valid := false.B
  io.ibuf.enq.bits := DontCare
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
    assert(stalls(wid).stallReason(HAZARD))
    stalls(wid).stallReason(HAZARD) := false.B
  }

  def isStalled(wid: UInt) = {
    (stalls(wid).stallReason.asUInt.orR, stalls(wid).stallReason)
  }
}

object Predecoder {
  def decode(inst: UInt) = {
    val d = Decoder.decode(inst)
    val isHazardInst = d.opcode.isOneOf(MuOpcode.JALR, MuOpcode.JAL, MuOpcode.SYSTEM, MuOpcode.BRANCH) ||
      d.b(IsTMC) || d.b(IsSplit) || d.b(IsPred) || d.b(IsWSpawn) || d.b(IsBar)
    val stall = WireInit(isHazardInst)
    val join = WireInit(d.b(IsJoin))
    (stall, join)
  }
}

class IPDOMStack(outer: WarpScheduler)(implicit m: MuonCoreParams) {
  val ipdomStackMem = Seq.fill(m.numWarps)(SRAM(m.numIPDOMEntries, outer.ipdomStackEntryT, 0, 0, 1))
  val branchTaken = RegInit(VecInit.fill(m.numWarps)(VecInit.fill(m.numIPDOMEntries)(false.B)))

  val ptr = RegInit(VecInit.fill(m.numWarps)(0.U(log2Ceil(m.numIPDOMEntries).W)))
  val pushing = WireInit(VecInit.fill(m.numWarps)(false.B))
  val joiningElse = RegInit(VecInit.fill(m.numWarps)(false.B))
  val joiningEnd = RegInit(VecInit.fill(m.numWarps)(false.B))

  val ports = VecInit(ipdomStackMem.map(m => m.readwritePorts(0)))

  val newPC = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(outer.pcT))))
  val newMask = WireInit(VecInit.fill(m.numWarps)(0.U.asTypeOf(Valid(outer.wmaskT))))

  (joiningElse lazyZip joiningEnd lazyZip newPC lazyZip newMask).zipWithIndex
    .foreach { case ((j0, j1, pc, mask), wid) =>
      pc.valid := j0
      pc.bits := ports(wid).readData.elsePC
      mask.valid := j0 || j1
      mask.bits := Mux(j0, ports(wid).readData.elseMask, ports(wid).readData.restoredMask)
    }


  (ports zip ptr).foreach { case (p, pa) =>
    p.enable := false.B
    p.address := pa
    p.isWrite := false.B
    p.writeData := DontCare
  }

  def push(wid: UInt, ent: Bundle): Unit = {
    ports(wid).enable := true.B
    ports(wid).isWrite := true.B
    ports(wid).writeData := ent.asTypeOf(outer.ipdomStackEntryT)
    branchTaken(wid)(ptr(wid)) := false.B
    pushing(wid) := true.B
    assert(ptr(wid) < (m.numIPDOMEntries - 1).U, "ipdom stack is full")
    ptr(wid) := ptr(wid) + 1.U
  }

  def pop(wid: UInt) = {
    assert(!pushing(wid)) // there should never be a simultaneous push and pop

    joiningElse.foreach(_ := false.B)
    joiningEnd.foreach(_ := false.B)

    when (!branchTaken(wid)(ptr(wid))) {
      // done with then, start with else: set pc, update taken
//      newMask := ports(wid).readData.elseMask
      // this is handled earlier
      branchTaken(wid)(ptr(wid)) := true.B
      joiningElse(wid) := true.B
    }.otherwise {
      // done with else, pop but don't set pc
      ports(wid).enable := true.B
      ports(wid).isWrite := false.B
      assert(ptr(wid) > 0.U, "ipdom stack is empty")
      ptr(wid) := ptr(wid) - 1.U
      joiningEnd(wid) := true.B
    }
  }
}
