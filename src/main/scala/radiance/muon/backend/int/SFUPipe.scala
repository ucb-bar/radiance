package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import freechips.rocketchip.rocket.CSRs
import org.chipsalliance.cde.config.Parameters
import radiance.memory.BoolArrayUtils.BoolSeqUtils
import radiance.muon._
import radiance.muon.backend._
import radiance.muon.backend.int._

class SFUPipe(implicit p: Parameters) extends ExPipe(true, true) {
  val idIO = IO(clusterCoreIdT)
  val csrIO = IO(new Bundle {
    val fe = Flipped(feCSRIO)
    val mcycle = Input(UInt(64.W))
    val minstret = Input(UInt(64.W))
    val fcsr = new Bundle {
      val regData = Input(csrDataT)
      val regWrite = Valid(csrDataT)
    }
  })
  val fenceIO = IO(lsuFenceIO)
  val flushIO = IO(cacheFlushIO)
  val barIO = IO(barrierIO)
  // to fix scala lsp issues
  val barReqT = barIO.req.bits.cloneType.asInstanceOf[barIO.req.bits.type]

  val firstLidOH = PriorityEncoderOH(uop.tmask)
  val firstRs1 = Mux1H(firstLidOH, io.req.bits.rs1Data.get)
  val firstRs2 = Mux1H(firstLidOH, io.req.bits.rs2Data.get)
  val rs1Mask = VecInit(io.req.bits.rs1Data.get.map(_(0))).asUInt

  val writeback = Wire(schedWritebackT)

  case class StallFields[T <: Data](
    inProgress: Valid[T],
    storedWriteback: Valid[SchedWriteback],
    start: Bool,
    done: T => Bool,
    reqSent: Bool,
    respReceived: Bool) {

    def this(start: Bool, done: T => Bool, reqT: T) = {
      this(
        inProgress = RegInit(0.U.asTypeOf(Valid(reqT))),
        storedWriteback = RegInit(0.U.asTypeOf(schedWritebackT)),
        start = start,
        done = done,
        reqSent = RegInit(false.B),
        respReceived = RegInit(false.B)
      )
    }

    when (start) {
      inProgress.valid := true.B
      storedWriteback := writeback
      assert(!reqSent)
    }

    val finish = inProgress.valid && done(inProgress.bits)
    when (finish) {
      when (respReceived) {
        printf("WARNING: duplicate stall response\n")
      }
      respReceived := true.B
    }
  }

  val barriers = Seq.tabulate(m.numWarps) { wid =>
    new StallFields(
      start = WireInit(io.req.fire && (wid.U === io.req.bits.uop.wid) && inst.b(IsNuInvoke)),
      done = (p: barReqT.type) => barIO.resp.valid && (barIO.resp.bits.id === p.id),
      reqT = barReqT
    )
  }

  val fences = Seq(
    new StallFields(
      start = WireInit(io.req.fire && inst.b(IsFenceI)),
      done = (_: UInt) => flushIO.i.done,
      reqT = UInt(0.W)
    ),
    new StallFields(
      start = WireInit(io.req.fire && inst.b(IsFenceD)),
      done = (_: UInt) => flushIO.d.done,
      reqT = UInt(0.W)
    )
  )

  val stalls = barriers ++ fences

  writeback.valid := true.B

  writeback.bits.setTmask.bits := DontCare
  writeback.bits.setTmask.valid := inst.b(IsTMC) || inst.b(IsSplit) || inst.b(IsPred) || inst.b(IsToHost)

  writeback.bits.setPC.bits := DontCare
  writeback.bits.setPC.valid := false.B

  writeback.bits.ipdomPush.bits := DontCare
  writeback.bits.ipdomPush.valid := inst.b(IsSplit)

  writeback.bits.wspawn.bits := DontCare
  writeback.bits.wspawn.valid := inst.b(IsWSpawn)

  writeback.bits.pc := uop.pc
  writeback.bits.wid := uop.wid

  val regWriteback = Wire(regWritebackT)

  regWriteback.valid := inst.b(IsCSR)

  regWriteback.bits.rd := inst(Rd)
  regWriteback.bits.data := DontCare
  regWriteback.bits.tmask := uop.tmask

  when (inst.b(IsTMC)) {
    writeback.bits.setTmask.bits := firstRs1
  }

  when (inst.b(IsWSpawn)) {
    writeback.bits.wspawn.bits.count := firstRs1
    writeback.bits.wspawn.bits.pc := firstRs2
  }

  when (inst.b(IsSplit)) {
    // Rs2 of split is guaranteed to be not-renamed
    val invert = inst(Rs2) =/= 0.U

    val thenMask = uop.tmask & rs1Mask
    val elseMask = uop.tmask & (~rs1Mask).asUInt
    val divergent = thenMask.orR && elseMask.orR

    writeback.bits.ipdomPush.bits.restoredMask := uop.tmask
    writeback.bits.ipdomPush.bits.elseMask := Mux(invert, thenMask, elseMask)
    writeback.bits.ipdomPush.bits.elsePC := uop.pc + 8.U
    // this signals to scheduler if branch is non-divergent
    writeback.bits.setTmask.valid := divergent
    writeback.bits.setTmask.bits := Mux(invert, elseMask, thenMask)
  }

  when (inst.b(IsPred)) {
    val invert = inst(Rd) =/= 0.U
    val newTmask = uop.tmask & Mux(invert, (~rs1Mask).asUInt, rs1Mask)
    // vortex logic: if resultant mask is 0, set to first lane's rs2
    writeback.bits.setTmask.bits := Mux(newTmask.orR, newTmask, firstRs2)
  }


  // ========
  // CSRs
  // ========

  val warpOffset = log2Ceil(m.numLanes)
  val coreOffset = warpOffset + log2Ceil(m.numWarps)
  val clusterOffset = coreOffset + log2Ceil(m.numCores)

  csrIO.fcsr.regWrite.valid := false.B
  csrIO.fcsr.regWrite.bits := DontCare

  val csrFile = new CSRFile(
    mhartId   = (idIO.clusterId << clusterOffset).asUInt |
                (idIO.coreId << coreOffset).asUInt |
                (uop.wid << warpOffset).asUInt,
    threadId  = 0.U, // overridden in read logic
    warpId    = uop.wid,
    coreId    = idIO.coreId,
    clusterId = idIO.clusterId,

    wmask     = csrIO.fe.wmask,
    tmask     = uop.tmask,
    mcycle    = csrIO.mcycle,
    minstret  = csrIO.minstret,

    fcsr      = csrIO.fcsr.regData,
    fcsrWrite = x => {
      csrIO.fcsr.regWrite.valid := true.B
      csrIO.fcsr.regWrite.bits := x
    }
  )

  when (io.req.fire) {
    when (inst.b(IsToHost)) {
      when (firstRs1 === 0.U) {
        printf("TEST PASSED!\n")
      }.otherwise {
        printf(cf"TEST FAILED with tohost=${firstRs1}%d\n")
        assert(false.B, cf"TEST FAILED with tohost=${firstRs1}%d\n")
      }
      writeback.bits.setTmask.bits := 0.U
    }.elsewhen (inst.b(IsCSR)) {
      val csrDataRaw = Mux1H(Seq(
        (inst.b(IsCSRRW) || inst.b(IsCSRRS) || inst.b(IsCSRRC), firstRs1),
        (inst.b(IsCSRRWI) || inst.b(IsCSRRSI) || inst.b(IsCSRRCI), inst(CsrImm))
      ))
      val csrAddr = inst(Imm32)
      val currentValue = csrFile(csrAddr)
      val newValue = Mux1H(Seq(
        (inst.b(IsCSRRC) || inst.b(IsCSRRCI), currentValue & (~csrDataRaw).asUInt),
        (inst.b(IsCSRRS) || inst.b(IsCSRRSI), currentValue | csrDataRaw),
        (inst.b(IsCSRRW) || inst.b(IsCSRRWI), csrDataRaw)
      ))
      val currentFCSR = csrFile(csrFile.FCSR)
      val newNewValue = MuxCase(newValue, Seq(
        (csrAddr === CSRs.fflags.U) -> ((currentFCSR & 0xe0.U(32.W)) | (newValue & 0x1f.U(32.W))),
        (csrAddr === CSRs.frm.U) -> ((currentFCSR & 0x1f.U(32.W)) | ((newValue & 0x7.U(32.W)) << 5).asUInt),
        (csrAddr === CSRs.fcsr.U) -> (newValue & 0xff.U(32.W))
      ))
      when (currentValue =/= newNewValue) {
        csrFile.write(csrAddr, newNewValue)
      }

      // add lane offset to mhartid, thread id
      regWriteback.bits.data := Mux(
        (csrAddr === CSRs.mhartid.U) || (csrAddr === 0xcc0.U),
        VecInit.tabulate(m.numLanes)(currentValue + _.U),
        VecInit.fill(m.numLanes)(currentValue),
      )
    }
  }


  // ========
  // barriers
  // ========

  barriers.zipWithIndex.foreach { case (b, wid) =>
    when (b.start) {
      when (b.inProgress.valid) {
        printf("WARNING: duplicate barrier request, may lead to weird behavior\n")
      }
      b.inProgress.bits.id := firstRs1.asTypeOf(barIO.req.bits.id)
      b.inProgress.bits.have := 1.U
      b.inProgress.bits.want := inst(NuNumElems) +& 1.U
      assert(inst(F3) === 1.U(3.W), "only support sync, imm retire for neutrino")
      assert(!b.inProgress.valid, "cannot start barrier when one is in progress")
    }

    // val wbFire = io.resp.fire && respIsBarrier && (wid.U === io.resp.bits.sched.get.bits.wid)
    // when (wbFire) {
    //   b.inProgress.valid := false.B
    //   b.reqSent := false.B
    // }
  }

  // arbitrate barIO req to one warp
  val barReqArbiter = Module(new RRArbiter(barReqT, m.numWarps))
  (barReqArbiter.io.in zip barriers).foreach { case (arbIn, b) =>
    arbIn.valid := b.inProgress.valid && !b.reqSent
    arbIn.bits := b.inProgress.bits
    when (arbIn.fire) {
      b.reqSent := true.B
    }
  }
  barIO.req <> barReqArbiter.io.out

  // ========
  // fences
  // ========

  (fences zip Seq(flushIO.i, flushIO.d)).foreach { case (fence, flush) =>
    // start a flush when lsu is clear, and we havent sent out the request yet
    flush.start := fence.inProgress.valid && !fence.reqSent && (fenceIO.gmemOutstanding === 0.U)
    when (flush.start) {
      fence.reqSent := true.B
    }
  }


  // arbitrate writeback port for both barriers and fences
  val stallRespArbiter = Module(new RRArbiter(schedWritebackT, stalls.length))
  (stallRespArbiter.io.in zip stalls).foreach { case (arbIn, s) =>
    arbIn.valid := s.respReceived && s.inProgress.valid
    arbIn.bits := s.storedWriteback
    when (arbIn.fire) {
      s.inProgress.valid := false.B
      s.reqSent := false.B
      s.respReceived := false.B
    }
  }

  // writeback priority is: everything else > barriers/fences
  stallRespArbiter.io.out.ready := io.resp.ready && !busy

  io.req.ready := !busy || io.resp.fire // TODO: might be able to unset ready if bar wb pending
  io.resp.valid := busy || stallRespArbiter.io.out.valid
  io.resp.bits.sched.get := Mux(busy,
    RegEnable(writeback, 0.U.asTypeOf(schedWritebackT), io.req.fire),
    stallRespArbiter.io.out.bits
  )
  io.resp.bits.reg.get := Mux(busy,
    RegEnable(regWriteback, 0.U.asTypeOf(regWritebackT), io.req.fire),
    0.U.asTypeOf(regWritebackT)
  )

  // override busy
  when (io.req.fire) {
    busy := !inst.b(IsNuInvoke) && !inst.b(IsFenceI) && !inst.b(IsFenceD)
  }
}
