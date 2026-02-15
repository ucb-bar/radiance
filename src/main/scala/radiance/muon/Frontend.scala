package radiance.muon

import chisel3._
import chisel3.reflect.DataMirror
import chisel3.util._
import org.chipsalliance.cde.config.Parameters


class Frontend(implicit p: Parameters)
  extends CoreModule()(p) {

  val io = IO(new Bundle {
    val imem = new InstMemIO
    val ibuf = Vec(muonParams.numWarps, Decoupled(ibufEntryT))
    val lsuReserve = Flipped(reservationIO)
    // TODO: writeback
    val commit = Flipped(schedWritebackT)
//    val issue = issueIO
    val csr = feCSRIO
    val cmdProc: Option[Bundle] = None
    val softReset = Input(Bool())
    val finished = Output(Bool())
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
      entries = muonParams.ibufDepth * muonParams.numWarps,
      useSyncReadMem = false)

    userQueueDeq.ready := resp.fire || io.softReset // drain user queue on soft reset
    i$.out.bits.pc := userQueueDeq.bits.pc
    i$.out.bits.wid := userQueueDeq.bits.wid
    assert(!resp.fire || userQueueDeq.valid, "user queue entries got dropped")

    // other stuff
    (warpScheduler.io.ibuf zip ibuffer.io.enq).foreach { case (ws, ib) =>
      ws.count := ib.count
    }
    warpScheduler.io.softReset := io.softReset
    io.finished := warpScheduler.io.finished
  }

  { // rename
    renamer.io.rename := warpScheduler.io.rename
    ibuffer.io.enq <> renamer.io.ibuf
    renamer.io.softReset := io.softReset
  }

  { // ibuffer
    // val eligible = VecInit(ibuffer.io.deq.map(_.valid)).asUInt
    // warpScheduler.io.issue.eligible.bits := eligible
    // warpScheduler.io.issue.eligible.valid := io.ibuf.ready
    warpScheduler.io.issue.eligible.bits := 0.U
    warpScheduler.io.issue.eligible.valid := false.B

    // val winner = UIntToOH(warpScheduler.io.issue.issued)

    (io.ibuf zip ibuffer.io.deq).foreach { case (to, from) =>
      to :<>= from
    }

    io.lsuReserve <> ibuffer.io.lsuReserve
    
    // io.ibuf.bits := Mux1H(winner, ibuffer.io.deq.map(_.bits))
    // io.ibuf.valid := eligible.orR
    // (ibuffer.io.deq zip winner.asBools).foreach { case (warpBuf, w) =>
    //   warpBuf.ready := io.ibuf.ready && w
    // }
  }

}
