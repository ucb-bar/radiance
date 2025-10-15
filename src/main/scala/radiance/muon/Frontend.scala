package radiance.muon

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

trait HasFrontEndBundles extends HasMuonCoreParameters {
  implicit val m = muonParams

  def pcT = UInt(m.archLen.W)
  def widT = UInt(log2Ceil(m.numWarps).W)
  def tmaskT = UInt(m.numLanes.W)
  def wmaskT = UInt(m.numWarps.W)
  def instT = UInt(m.instBits.W)
  def ibufIdxT = UInt(log2Ceil(m.ibufDepth + 1).W)

  def ipdomStackEntryT = new Bundle {
    val restoredMask = tmaskT
    val elseMask = tmaskT
    val elsePC = pcT
  }

  def wspawnT = new Bundle {
    val count = UInt(log2Ceil(m.numWarps + 1).W)
    val pc = pcT
  }

  require(isPow2(m.numIPDOMEntries))

  def commitIO = Vec(m.numWarps, Flipped(ValidIO(new Bundle {
      val setPC = ValidIO(pcT)
      val setTmask = ValidIO(tmaskT)
      val ipdomPush = ValidIO(ipdomStackEntryT) // this should be split PC+8
      val wspawn = ValidIO(wspawnT)
      val pc = pcT
    }
  )))

  def icacheIO = new Bundle {
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

  def issueIO = new Bundle {
    val eligible = Flipped(ValidIO(wmaskT))
    val issued = Output(wmaskT) // 1H, next cycle from input
  }

  def csrIO = new Bundle {
    val read = Flipped(ValidIO(new Bundle {
      val addr = UInt(m.csrAddrBits.W)
      val wid = widT
    })) // reads only
    val resp = Output(UInt(m.archLen.W)) // next cycle
  }

  def cmdProcIO = Flipped(ValidIO(new Bundle {
    val schedule = pcT
  }))

  def uopT = new Bundle {
    val inst = new Decoded
    val tmask = tmaskT
    val pc = pcT
  }

  def renameIO = ValidIO(new Bundle {
    val inst = instT
    val tmask = tmaskT
    val wmask = wmaskT
    val wid = widT
    val pc = pcT
  })

  def ibufEnqIO = new Bundle {
    val count = Input(Vec(m.numWarps, ibufIdxT))
    val entry = ValidIO(new Bundle {
      val uop = uopT
      val wid = widT
    })
  }

  def prT = UInt(log2Ceil(m.numPhysRegs).W)
  def arT = UInt(log2Ceil(m.numArchRegs).W)
}

class Frontend(implicit p: Parameters)
  extends CoreModule()(p)
  with HasFrontEndBundles {

  val io = IO(new Bundle {
    val imem = new InstMemIO
    val ibuf = Vec(muonParams.numWarps, Decoupled(uopT))
    // TODO: writeback
    val commit = commitIO
    val issue = issueIO
    val csr = csrIO
    val cmdProc: Option[Bundle] = None
    val hartId = Input(UInt(muonParams.hartIdBits.W))
  })

  val warpScheduler = Module(new WarpScheduler)
  val renamer = Module(new Rename())
  val ibuffer = Module(new InstBuffer)

  { // scheduler & fetch
    val i$ = warpScheduler.io.icache
    val (req, resp) = (io.imem.req, io.imem.resp)
    val tagInc = WireInit(VecInit.fill(muonParams.numWarps)(false.B))
    val tagCounters = VecInit(Seq.tabulate(muonParams.numWarps) { i =>
      Counter(tagInc(i), muonParams.ibufDepth)._1
    })
    when (i$.in.fire) {
      tagInc(i$.in.bits.wid) := true.B
    }
    req.valid := i$.in.valid
    req.bits.size := log2Ceil(muonParams.instBits / 8).U
    req.bits.store := false.B
    req.bits.address := i$.in.bits.pc
    req.bits.tag := Cat(i$.in.bits.wid, tagCounters(i$.in.bits.wid))
    req.bits.data := DontCare // i$ is read only
    req.bits.mask := ((1 << muonParams.instBytes) - 1).U
//    req.bits.metadata.pc := i$.in.bits.pc
//    req.bits.metadata.wid := i$.in.bits.wid
    i$.in.ready := req.ready

    resp.ready := true.B
    i$.out.valid := resp.valid
    i$.out.bits.inst := resp.bits.data
//    i$.out.bits.wid := resp.bits.metadata.wid
//    i$.out.bits.pc := resp.bits.metadata.pc

    io.commit <> warpScheduler.io.commit
    io.issue <> warpScheduler.io.issue
    io.csr <> warpScheduler.io.csr

    io.cmdProc.foreach { c =>
      c <> warpScheduler.io.cmdProc.get
    }

    // handle user data
    // ================
    val userQueueEnq = Wire(Decoupled(i$.in.bits.cloneType))
    userQueueEnq.bits := i$.in.bits
    userQueueEnq.valid := req.fire
    assert(!req.fire || userQueueEnq.ready, "not enough user queue entries")

    val userQueueDeq = Queue(
      userQueueEnq,
      entries = muonParams.ibufDepth,
      useSyncReadMem = false)

    userQueueDeq.ready := resp.fire
    i$.out.bits.pc := userQueueDeq.bits.pc
    i$.out.bits.wid := userQueueDeq.bits.wid
    assert(!resp.fire || userQueueDeq.valid, "user queue entries got dropped")

    // other stuff
    warpScheduler.io.ibuf.count := ibuffer.io.enq.count
  }

  { // rename
    renamer.io.softReset := false.B // TODO
    renamer.io.rename := warpScheduler.io.rename
    ibuffer.io.enq.entry.bits := renamer.io.ibuf.entry.bits
    ibuffer.io.enq.entry.valid := renamer.io.ibuf.entry.valid
    renamer.io.ibuf.count := ibuffer.io.enq.count
    dontTouch(renamer.io.ibuf)
  }

  // IBuffer
  {

  }

  io.ibuf <> ibuffer.io.deq
}
