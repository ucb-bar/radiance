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
    val softReset = Input(Bool())
  })
  
  val aluPipe = Module(new ALUPipe())
  val fpPipe = Module(new FPPipe())
  val mulDivPipe = Module(new MulDivPipe())
  val lsuPipe = Module(new LSUPipe())
  val sfuPipe = Module(new SFUPipe())

  val inst = io.req.bits.uop.inst

  sfuPipe.csrIO.fcsr <> fpPipe.fCSRIO
  sfuPipe.idIO := io.id
  sfuPipe.barIO <> io.barrier

  lsuPipe.memIO <> io.mem
  lsuPipe.reserveIO <> io.lsuReserve
  lsuPipe.tokenIO := io.token

  val pipes = Seq(aluPipe, fpPipe, mulDivPipe, lsuPipe, sfuPipe)
  val uses = Seq(inst.b(UseALUPipe),
                 inst.b(UseFPPipe),
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

  val mcycle = Wire(UInt(64.W))
  val mcycleReg = RegEnable(mcycle, 0.U(64.W), true.B)
  mcycle := Mux(io.softReset, 0.U(64.W), mcycleReg + 1.U)

  val minstret = Wire(UInt(64.W))
  val minstretReg = RegEnable(minstret, 0.U(64.W), io.resp.fire)
  minstret := Mux(io.softReset, 0.U(64.W), minstretReg + 1.U)

  sfuPipe.csrIO.mcycle := mcycleReg
  sfuPipe.csrIO.minstret := minstretReg
  sfuPipe.csrIO.fe := io.feCSR
}
