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
    val perf = new Bundle {
      val instRetired = Output(UInt(Perf.counterWidth.W))
      val cycles =  Output(UInt(Perf.counterWidth.W))
      // TODO: execute fire
    }
  })
  
  val aluPipe = Module(new ALUPipe())
  val fpAddMulPipe = Module(new FPPipe(isDivSqrt = false))
  val fpDivSqrtPipe = Module(new FPPipe(isDivSqrt = true))
  val mulDivPipe = Module(new MulDivPipe())
  val lsuPipe = Module(new LSUPipe())
  val sfuPipe = Module(new SFUPipe())

  val inst = io.req.bits.uop.inst

  sfuPipe.csrIO.fcsr <> fpAddMulPipe.fCSRIO
  // TODO: connect
  fpDivSqrtPipe.fCSRIO.regWrite.valid := false.B
  fpDivSqrtPipe.fCSRIO.regWrite.bits := DontCare
  sfuPipe.idIO := io.id
  sfuPipe.barIO <> io.barrier
  sfuPipe.flushIO <> io.flush
  // TODO: LSU needs to provide these
  sfuPipe.fenceIO.gmemOutstanding := 0.U
  sfuPipe.fenceIO.smemOutstanding := 0.U

  lsuPipe.memIO <> io.mem
  lsuPipe.reserveIO <> io.lsuReserve
  lsuPipe.tokenIO := io.token

  val pipes = Seq(aluPipe, fpAddMulPipe, fpDivSqrtPipe, mulDivPipe, lsuPipe, sfuPipe)
  val uses = Seq(inst.b(UseALUPipe),
                 inst.b(UseFPPipe) && !inst.b(IsFPDivSqrt),
                 inst.b(UseFPPipe) && inst.b(IsFPDivSqrt),
                 inst.b(UseMulDivPipe),
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

  val mcycle = Wire(UInt(Perf.counterWidth.W))
  val mcycleReg = RegEnable(mcycle, 0.U(Perf.counterWidth.W), true.B)
  mcycle := Mux(io.softReset, 0.U(Perf.counterWidth.W), mcycleReg + 1.U)

  val minstret = Wire(UInt(Perf.counterWidth.W))
  val minstretReg = RegEnable(minstret, 0.U(Perf.counterWidth.W), io.resp.fire)
  minstret := Mux(io.softReset, 0.U(Perf.counterWidth.W), minstretReg + 1.U)

  // io.perf.instRetired := minstret
  // io.perf.cycle := mcycleReg
  val perf = new ExecutePerfCounter
  perf.cycles.cond(true.B)
  perf.instRetired.cond(io.resp.fire)
  io.perf.cycles := perf.cycles.value
  io.perf.instRetired := perf.instRetired.value

  sfuPipe.csrIO.mcycle := mcycleReg
  sfuPipe.csrIO.minstret := minstretReg
  sfuPipe.csrIO.fe := io.feCSR
}

class ExecutePerfCounter {
  /** total retired instructions */
  val instRetired = new PerfCounter
  /** total elapsed cycle */
  val cycles = new PerfCounter
}
