package radiance.muon.backend.int

import chisel3._
import chisel3.util._
import radiance.muon._
import freechips.rocketchip.rocket.CSRs
import freechips.rocketchip.util.UIntIsOneOf
import org.chipsalliance.cde.config.Parameters

abstract class MuonCSR(
  val address: Int,
  val defaultValue: UInt = 0.U,
  val width: Int = 32,
  val accessor: Option[() => UInt] = None,
  val setter: Option[UInt => Unit] = None,
)

object PerfCSRs {
  private def hpmCounter(n: Int): Int = CSRs.mhpmcounter3 + (n - 3)
  private def hpmCounterH(n: Int): Int = CSRs.mhpmcounter3h + (n - 3)

  val mcycleDecoded = CSRs.mhpmcounter3
  val mcycleDecodedh = CSRs.mhpmcounter3h
  val mcycleDispatched = CSRs.mhpmcounter4
  val mcycleDispatchedh = CSRs.mhpmcounter4h
  val mcycleEligible = CSRs.mhpmcounter5
  val mcycleEligibleh = CSRs.mhpmcounter5h
  val mcycleIssued = CSRs.mhpmcounter6
  val mcycleIssuedh = CSRs.mhpmcounter6h

  def perWarpStallsWAW(wid: Int): Int = hpmCounter(7 + 6 * wid)
  def perWarpStallsWAWh(wid: Int): Int = hpmCounterH(7 + 6 * wid)
  def perWarpStallsWAR(wid: Int): Int = hpmCounter(8 + 6 * wid)
  def perWarpStallsWARh(wid: Int): Int = hpmCounterH(8 + 6 * wid)
  def perWarpStallsScoreboard(wid: Int): Int = hpmCounter(9 + 6 * wid)
  def perWarpStallsScoreboardh(wid: Int): Int = hpmCounterH(9 + 6 * wid)
  def perWarpStallsRSFull(wid: Int): Int = hpmCounter(10 + 6 * wid)
  def perWarpStallsRSFullh(wid: Int): Int = hpmCounterH(10 + 6 * wid)
  def perWarpStallsBusy(wid: Int): Int = hpmCounter(11 + 6 * wid)
  def perWarpStallsBusyh(wid: Int): Int = hpmCounterH(11 + 6 * wid)
  def perWarpStallsBusyLSU(wid: Int): Int = hpmCounter(12 + 6 * wid)
  def perWarpStallsBusyLSUh(wid: Int): Int = hpmCounterH(12 + 6 * wid)
}

class CSRFile(
  mhartId: UInt,
  threadId: UInt,
  warpId: UInt,
  coreId: UInt,
  clusterId: UInt,

  wmask: UInt,
  tmask: UInt,
  mcycle: UInt,
  mcycleDecoded: UInt,
  mcycleDispatched: UInt,
  mcycleEligible: UInt,
  mcycleIssued: UInt,
  perWarpStallsWAW: Seq[UInt],
  perWarpStallsWAR: Seq[UInt],
  perWarpStallsScoreboard: Seq[UInt],
  perWarpStallsRSFull: Seq[UInt],
  perWarpStallsBusy: Seq[UInt],
  perWarpStallsBusyLSU: Seq[UInt],
  minstret: UInt,

  fcsr: UInt,
  fcsrWrite: UInt => Unit,
)(implicit m: MuonCoreParams, implicit val p: Parameters) extends HasCoreParameters {
  def wrap(x: UInt) = Some(() => x)
  require(perWarpStallsWAW.length == 4)
  require(perWarpStallsWAR.length == 4)
  require(perWarpStallsScoreboard.length == 4)
  require(perWarpStallsRSFull.length == 4)
  require(perWarpStallsBusy.length == 4)
  require(perWarpStallsBusyLSU.length == 4)
  case object MVendorId  extends MuonCSR(CSRs.mvendorid) // 0: non commercial
  case object MArchId    extends MuonCSR(CSRs.marchid, 0x6D756F6E.U)
  case object MImpId     extends MuonCSR(CSRs.mimpid, 0x20260408.U) // 🙏
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
  case object MHartId    extends MuonCSR(CSRs.mhartid, accessor = wrap(mhartId))
  case object ThreadId   extends MuonCSR(0xcc0, accessor = wrap(threadId))
  case object WarpId     extends MuonCSR(0xcc1, accessor = wrap(warpId))
  case object CoreId     extends MuonCSR(0xcc2, accessor = wrap(coreId))
  case object ClusterId  extends MuonCSR(0xcd0, accessor = wrap(clusterId))
  case object NumLanes   extends MuonCSR(0xfc0, m.numLanes.U)
  case object NumWarps   extends MuonCSR(0xfc1, m.numWarps.U)
  case object NumCores   extends MuonCSR(0xfc2, m.numCores.U) // per-cluster
  case object BlockIdxX  extends MuonCSR(0xfc3)
  case object BlockIdxY  extends MuonCSR(0xfc4)
  case object BlockIdxZ  extends MuonCSR(0xfc5)
  case object ThreadIdxX extends MuonCSR(0xfc6)
  case object ThreadIdxY extends MuonCSR(0xfc7)
  case object ThreadIdxZ extends MuonCSR(0xfc8)
  case object WarpMask   extends MuonCSR(0xcc3, accessor = wrap(wmask))
  case object ThreadMask extends MuonCSR(0xcc4, accessor = wrap(tmask))
  case object MCycle     extends MuonCSR(CSRs.mcycle,    accessor = wrap(mcycle(31, 0)))
  case object MCycleH    extends MuonCSR(CSRs.mcycleh,   accessor = wrap(mcycle(63, 32)))
  case object MInstRet   extends MuonCSR(CSRs.minstret,  accessor = wrap(minstret(31, 0)))
  case object MInstRetH  extends MuonCSR(CSRs.minstreth, accessor = wrap(minstret(63, 32)))
  case object MCycleDec  extends MuonCSR(PerfCSRs.mcycleDecoded,   accessor = wrap(mcycleDecoded(31, 0)))
  case object MCycleDecH extends MuonCSR(PerfCSRs.mcycleDecodedh,  accessor = wrap(mcycleDecoded(63, 32)))
  case object MCycleDisp  extends MuonCSR(PerfCSRs.mcycleDispatched, accessor = wrap(mcycleDispatched(31, 0)))
  case object MCycleDispH extends MuonCSR(PerfCSRs.mcycleDispatchedh,accessor = wrap(mcycleDispatched(63, 32)))
  case object MCycleEli  extends MuonCSR(PerfCSRs.mcycleEligible,  accessor = wrap(mcycleEligible(31, 0)))
  case object MCycleEliH extends MuonCSR(PerfCSRs.mcycleEligibleh, accessor = wrap(mcycleEligible(63, 32)))
  case object MCycleIss  extends MuonCSR(PerfCSRs.mcycleIssued,    accessor = wrap(mcycleIssued(31, 0)))
  case object MCycleIssH extends MuonCSR(PerfCSRs.mcycleIssuedh,   accessor = wrap(mcycleIssued(63, 32)))
  val perWarpPerfCSRs = Seq.tabulate(4) { wid =>
    Seq(
      new MuonCSR(PerfCSRs.perWarpStallsWAW(wid), accessor = wrap(perWarpStallsWAW(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsWAWh(wid), accessor = wrap(perWarpStallsWAW(wid)(63, 32))) {},
      new MuonCSR(PerfCSRs.perWarpStallsWAR(wid), accessor = wrap(perWarpStallsWAR(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsWARh(wid), accessor = wrap(perWarpStallsWAR(wid)(63, 32))) {},
      new MuonCSR(PerfCSRs.perWarpStallsScoreboard(wid), accessor = wrap(perWarpStallsScoreboard(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsScoreboardh(wid), accessor = wrap(perWarpStallsScoreboard(wid)(63, 32))) {},
      new MuonCSR(PerfCSRs.perWarpStallsRSFull(wid), accessor = wrap(perWarpStallsRSFull(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsRSFullh(wid), accessor = wrap(perWarpStallsRSFull(wid)(63, 32))) {},
      new MuonCSR(PerfCSRs.perWarpStallsBusy(wid), accessor = wrap(perWarpStallsBusy(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsBusyh(wid), accessor = wrap(perWarpStallsBusy(wid)(63, 32))) {},
      new MuonCSR(PerfCSRs.perWarpStallsBusyLSU(wid), accessor = wrap(perWarpStallsBusyLSU(wid)(31, 0))) {},
      new MuonCSR(PerfCSRs.perWarpStallsBusyLSUh(wid), accessor = wrap(perWarpStallsBusyLSU(wid)(63, 32))) {},
    )
  }.flatten
  case object FFlags     extends MuonCSR(CSRs.fflags,    accessor = wrap(fcsr(4, 0)), setter = Some(fcsrWrite))
  case object FRM        extends MuonCSR(CSRs.frm,       accessor = wrap(fcsr(7, 5)), setter = Some(fcsrWrite))
  case object FCSR       extends MuonCSR(CSRs.fcsr,      accessor = wrap(fcsr),       setter = Some(fcsrWrite))

  val allCSRs = Seq(
    MVendorId, MArchId, MImpId, MISA, SATP, MStatus,
    MEDeleg, MIDeleg, MIE, MTVec, MEPC, PMPCfg0, PMPAddr0,
    MHartId, ThreadId, WarpId, CoreId, ClusterId,
    NumLanes, NumWarps, NumCores,
    BlockIdxX, BlockIdxY, BlockIdxZ,
    ThreadIdxX, ThreadIdxY, ThreadIdxZ,
    WarpMask, ThreadMask,
    MCycle, MCycleH, MInstRet, MInstRetH,
    MCycleDec, MCycleDecH, MCycleDisp, MCycleDispH, MCycleEli, MCycleEliH, MCycleIss, MCycleIssH
  ) ++ perWarpPerfCSRs ++ Seq(
    FFlags, FRM, FCSR
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
    allCSRs.filter(_.setter.isDefined).foreach { csr =>
      when (csrAddr === csr.address.U) {
        csr.setter.get(writeData)
      }
    }
  }
}
