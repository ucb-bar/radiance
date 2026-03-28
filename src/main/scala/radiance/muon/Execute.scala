package radiance.muon

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon.backend.int._
import radiance.muon.backend.fp._

class Execute(implicit p: Parameters) extends CoreModule()(p) {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(fuInT(hasRs1 = true, hasRs2 = true, hasRs3 = true)))
    val resp = Decoupled(writebackT())
    val id = clusterCoreIdT
    val mem = memoryIO
    val token = Input(lsuTokenT)
    val lsuReserve = reservationIO
    val feCSR = Flipped(feCSRIO)
    val barrier = barrierIO
    val flush = cacheFlushIO
    val softReset = Input(Bool())
    val beCSR = new Bundle {
      val cyclesDispatched = Input(Perf.T)
      val cyclesEligible = Input(Perf.T)
      val cyclesIssued = Input(Perf.T)
    }
    val perf = new Bundle {
      val instRetired = Output(Perf.T)
      val cycles =  Output(Perf.T)
    }
  })
  
  val aluPipe = Module(new ALUPipe())
  val fpAddMulPipe = Module(new FPPipe(isDivSqrt = false))
  val fpDivSqrtPipe = Module(new FPPipe(isDivSqrt = true))
  val fpExPipe = Module(new FPExPipe(FPFormat.BF16))
  val mulDivPipe = Module(new MulDivPipe())
  val lsuPipe = Module(new LSUPipe())
  val sfuPipe = Module(new SFUPipe())

  val inst = io.req.bits.uop.inst

  sfuPipe.csrIO.fcsr <> fpAddMulPipe.fCSRIO
  fpExPipe.fCSRIO.regData := fpAddMulPipe.fCSRIO.regData
  // TODO: connect
  fpDivSqrtPipe.fCSRIO.regWrite.valid := false.B
  fpDivSqrtPipe.fCSRIO.regWrite.bits := DontCare
  sfuPipe.idIO := io.id
  sfuPipe.barIO <> io.barrier
  sfuPipe.flushIO <> io.flush
  sfuPipe.fenceIO := lsuPipe.flushIO

  lsuPipe.idIO := io.id
  lsuPipe.memIO <> io.mem
  lsuPipe.reserveIO <> io.lsuReserve
  lsuPipe.tokenIO := io.token

  val pipes = Seq(aluPipe, fpAddMulPipe, fpDivSqrtPipe, mulDivPipe, fpExPipe, lsuPipe, sfuPipe)
  val uses = Seq(inst.b(UseALUPipe),
                 inst.b(UseFPPipe) && !inst.b(IsFPDivSqrt),
                 inst.b(UseFPPipe) && inst.b(IsFPDivSqrt),
                 inst.b(UseMulDivPipe),
                 inst.b(UseFPExPipe),
                 inst.b(UseLSUPipe),
                 inst.b(UseSFUPipe))
  
  assert(!io.req.valid || uses.map(_.asUInt).reduce(_ +& _) === 1.U,
    cf"pipeline selection should be one hot, but got ${VecInit(uses).asUInt}%b")

  (pipes zip uses).foreach { case (pipe, use) =>
    pipe.io.req.valid := io.req.valid && use
    pipe.io.req.bits.uop := io.req.bits.uop
    pipe.io.req.bits.rs1Data.foreach(_ := io.req.bits.rs1Data.get)
    pipe.io.req.bits.rs2Data.foreach(_ := io.req.bits.rs2Data.get)
    pipe.io.req.bits.rs3Data.foreach(_ := io.req.bits.rs3Data.get)
  }
  io.req.ready := Mux1H(VecInit(uses), pipes.map(_.io.req.ready))

  val respArbiter = Module(new RRArbiter(writebackT(), pipes.length))
  (respArbiter.io.in zip pipes).foreach { case (arbIn, pipe) =>
    arbIn.bits.sched.get := pipe.io.resp.bits.sched.getOrElse(0.U.asTypeOf(schedWritebackT))
    arbIn.bits.reg.get := pipe.io.resp.bits.reg.getOrElse(0.U.asTypeOf(regWritebackT))
    arbIn.valid := pipe.io.resp.valid
    pipe.io.resp.ready := arbIn.ready
  }
  io.resp :<>= respArbiter.io.out

  val mcycle = Wire(Perf.T)
  val mcycleReg = RegEnable(mcycle, 0.U.asTypeOf(Perf.T), true.B)
  mcycle := Mux(io.softReset, 0.U.asTypeOf(Perf.T), mcycleReg + 1.U)

  // NOTE: using io.resp.fire here excludes stores
  // NOTE: currently doesn't count vx_join, which gets absorbed in the frontend
  val minstret = Wire(Perf.T)
  val minstretReg = RegEnable(minstret, 0.U.asTypeOf(Perf.T), io.req.fire)
  minstret := Mux(io.softReset, 0.U.asTypeOf(Perf.T), minstretReg + 1.U)

  io.perf.cycles := mcycleReg
  io.perf.instRetired := minstret

  sfuPipe.csrIO.perf.mcycle := mcycleReg
  sfuPipe.csrIO.perf.minstret := minstretReg
  sfuPipe.csrIO.perf.mcycleDecoded := io.feCSR.cyclesDecoded
  sfuPipe.csrIO.perf.mcycleDispatched := io.beCSR.cyclesDispatched
  sfuPipe.csrIO.perf.mcycleEligible := io.beCSR.cyclesEligible
  sfuPipe.csrIO.perf.mcycleIssued := io.beCSR.cyclesIssued
  sfuPipe.csrIO.wmask := io.feCSR.wmask
}

class RegWriteback(implicit p: Parameters) extends CoreBundle()(p) {
  val rd = pRegT
  val data = Vec(muonParams.numLanes, regDataT)
  val tmask = tmaskT
}
