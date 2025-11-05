package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import radiance.muon._
import radiance.muon.backend._
import freechips.rocketchip.rocket.CSRs
import freechips.rocketchip.util.UIntIsOneOf

class SFUPipe(implicit p: Parameters) extends ExPipe(true, false) with HasCoreBundles {
  val idIO = IO(clusterCoreIdT)

  val firstLidOH = PriorityEncoderOH(uop.tmask)
  val firstRs1 = Mux1H(firstLidOH, io.req.bits.rs1Data.get)
  val firstRs2 = Mux1H(firstLidOH, io.req.bits.rs2Data.get)
  val rs1Mask = VecInit(io.req.bits.rs1Data.get.map(_(0))).asUInt

  val writeback = Wire(schedWritebackT)

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

  when (inst.b(IsTMC)) {
    writeback.bits.setTmask.bits := firstRs1
  }

  when (inst.b(IsWSpawn)) {
    writeback.bits.wspawn.bits.count := firstRs1
    writeback.bits.wspawn.bits.pc := firstRs2
  }

  when (inst.b(IsSplit)) {
    // vortex specifies rs2 addr = 1, but this might get renamed.
    // however, this logic still holds because x0 always gets renamed to 0;
    // furthermore, x0 also does not have a wid prefix.
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

  val warpOffset = log2Ceil(m.numLanes)
  val coreOffset = warpOffset + log2Ceil(m.numWarps)
  val clusterOffset = coreOffset + log2Ceil(m.numCores)

  val csrFile = new CSRFile(
    mhartId   = (idIO.clusterId << clusterOffset).asUInt |
                (idIO.coreId << coreOffset).asUInt |
                (uop.wid << warpOffset).asUInt,
    threadId  = 0.U, // overridden in read logic
    warpId    = uop.wid,
    coreId    = idIO.coreId,
    clusterId = idIO.clusterId
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
      // stop()
    }.elsewhen (inst.b(IsCSR)) {

      assert(false.B, "i dont have csrs yet")
    }
  }

  io.req.ready := !busy || io.resp.fire
  io.resp.valid := busy
  io.resp.bits.sched.get := RegEnable(writeback, 0.U.asTypeOf(schedWritebackT), io.req.fire)
}

abstract class MuonCSR(
  val address: Int,
  val defaultValue: UInt = 0.U,
  val width: Int = 32,
  val accessor: Option[() => UInt] = None,
)

class CSRFile(
  mhartId: UInt,
  threadId: UInt,
  warpId: UInt,
  coreId: UInt,
  clusterId: UInt,
)(implicit m: MuonCoreParams, implicit val p: Parameters)
  extends HasCoreBundles {
  case object MVendorId  extends MuonCSR(CSRs.mvendorid) // 0: non commercial
  case object MArchId    extends MuonCSR(CSRs.marchid, 0x6D756F6E.U)
  case object MImpId     extends MuonCSR(CSRs.mimpid, 0x20260402.U) // 🙏
  case object MISA       extends MuonCSR(CSRs.misa, "b0100_0000_1000_0000_0001_0001_0110_0000".U)
  case object SATP       extends MuonCSR(CSRs.satp)
  case object MStatus    extends MuonCSR(CSRs.mstatus)
  case object MEDeleg    extends MuonCSR(CSRs.medeleg)
  case object MIDeleg    extends MuonCSR(CSRs.mideleg)
  case object MIE        extends MuonCSR(CSRs.mie)
  case object MTVec      extends MuonCSR(CSRs.mtvec)
  case object MEPC       extends MuonCSR(CSRs.mepc)
  case object PMPCfg0    extends MuonCSR(CSRs.pmpcfg0)
  case object PMPAddr0   extends MuonCSR(CSRs.pmpaddr0)
  case object MHartId    extends MuonCSR(CSRs.mhartid, accessor = Some(() => mhartId))
  case object ThreadId   extends MuonCSR(0xcc0, accessor = Some(() => threadId))
  case object WarpId     extends MuonCSR(0xcc1, accessor = Some(() => warpId))
  case object CoreId     extends MuonCSR(0xcc2, accessor = Some(() => coreId))
  case object ClusterId  extends MuonCSR(0xcc3, accessor = Some(() => clusterId))
  case object NumLanes   extends MuonCSR(0xfc0, m.numLanes.U)
  case object NumWarps   extends MuonCSR(0xfc1, m.numWarps.U)
  case object NumCores   extends MuonCSR(0xfc2, m.numCores.U)
  case object BlockIdxX  extends MuonCSR(0xfc3)
  case object BlockIdxY  extends MuonCSR(0xfc4)
  case object BlockIdxZ  extends MuonCSR(0xfc5)
  case object ThreadIdxX extends MuonCSR(0xfc6)
  case object ThreadIdxY extends MuonCSR(0xfc7)
  case object ThreadIdxZ extends MuonCSR(0xfc8)

  val allCSRs = Seq(
    MVendorId, MArchId, MImpId, MISA, SATP, MStatus,
    MEDeleg, MIDeleg, MIE, MTVec, MEPC, PMPCfg0, PMPAddr0,
    MHartId, ThreadId, WarpId, CoreId, ClusterId,
    NumLanes, NumWarps, NumCores,
    BlockIdxX, BlockIdxY, BlockIdxZ,
    ThreadIdxX, ThreadIdxY, ThreadIdxZ,
  )

  def allStoredCSRs: Seq[MuonCSR] = allCSRs.filter(_.accessor.isEmpty)

  val csrData = RegInit(MixedVecInit(
    allStoredCSRs.map(csr => csr.defaultValue.asTypeOf(UInt(csr.width.W)))
  ))

  def _check(addr: UInt) = {
    assert(addr.isOneOf(allCSRs.map(_.address.U)), "illegal csr address")
  }

  def apply(csr: MuonCSR): UInt = {
    csr.accessor.map(_()).getOrElse( // use accessor if exists
      csrData(allStoredCSRs.indexOf(csr)) // return type is writable
    )
  }

  def apply(csrAddr: UInt): UInt = {
    _check(csrAddr)
    Mux1H(allCSRs.map { csr =>
      (csrAddr === csr.address.U, this(csr).asTypeOf(csrDataT))
    })
  }

  def write(csrAddr: UInt, writeData: UInt): Unit = {
    // this implementation is such that writing to csrs that have
    // an accessor will not error but will also not do anything.
    // there's also no protection
    _check(csrAddr)
    (allStoredCSRs zip csrData).foreach { case (csr, reg) =>
      when (csrAddr === csr.address.U) {
        reg := writeData.asTypeOf(reg)
      }
    }
  }
}