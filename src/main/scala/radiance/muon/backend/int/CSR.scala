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

class CSRFile(
  mhartId: UInt,
  threadId: UInt,
  warpId: UInt,
  coreId: UInt,
  clusterId: UInt,

  wmask: UInt,
  tmask: UInt,
  mcycle: UInt,
  minstret: UInt,

  fcsr: UInt,
  fcsrWrite: UInt => Unit,
)(implicit m: MuonCoreParams, implicit val p: Parameters) extends HasCoreBundles {
  def wrap(x: UInt) = Some(() => x)
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
  case object NumCores   extends MuonCSR(0xfc2, m.numCores.U)
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
