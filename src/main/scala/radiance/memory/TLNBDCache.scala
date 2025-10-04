package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, TransferSizes}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{NonBlockingDCache, PRV, SimpleHellaCacheIF}
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.subsystem.GPUMemory


class TLNBDCache(staticIdForMetadataUseOnly: Int, val maxInFlights: Int)
                (implicit p: Parameters) extends LazyModule {

  val wordSize = p(TileKey).core.xLen / 8
  assert(wordSize == 4)

  val inNode = TLManagerNode(Seq(
    TLSlavePortParameters.v1(
      managers = Seq(
        TLSlaveParameters.v2(
          address = Seq(AddressSet(0, p(GPUMemory).get.size - 1)),
          name = Some("radiance_l1"),
          fifoId = Some(0),
          supports = TLMasterToSlaveTransferSizes(
            get = TransferSizes(1, wordSize),
            putFull = TransferSizes(1, wordSize),
            putPartial = TransferSizes(1, wordSize),
          ),
        )
      ),
      beatBytes = wordSize,
    )
  ))

  val nbdCache = LazyModule(new NonBlockingDCache(staticIdForMetadataUseOnly))

  val outNode = nbdCache.node
  override lazy val module = new TLNBDCacheModule(this)
}

class TLNBDCacheModule(outer: TLNBDCache) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  val (tlIn, _) = outer.inNode.in.head
  val inIF = Module(new SimpleHellaCacheIF())

  assert(!tlIn.a.valid || (tlIn.a.bits.size === log2Ceil(outer.wordSize).U))

  // tl <-> simple if
  // ================
  {
    val req = inIF.io.requestor.req
    val resp = inIF.io.requestor.resp

    inIF.io.requestor <> 0.U.asTypeOf(inIF.io.requestor)
    inIF.io.requestor.keep_clock_enabled := true.B

    // A
    req.valid := tlIn.a.valid
    tlIn.a.ready := req.ready
    req.bits := 0.U.asTypeOf(req.bits.cloneType)
    req.bits.tag := tlIn.a.bits.source
    req.bits.size := tlIn.a.bits.size
    req.bits.cmd := MuxCase(M_XRD, Seq(
      (tlIn.a.bits.opcode === TLMessages.Get) -> M_XRD,
      (tlIn.a.bits.opcode === TLMessages.PutFullData) -> M_XWR,
      (tlIn.a.bits.opcode === TLMessages.PutPartialData) -> M_XWR,
    )) // TODO: ability to flush
    req.bits.addr := tlIn.a.bits.address
    req.bits.data := tlIn.a.bits.data
    req.bits.mask := tlIn.a.bits.mask
    req.bits.signed := false.B
    req.bits.dprv := PRV.M.U
    req.bits.dv := false.B // virtualization
    req.bits.phys := true.B
    req.bits.no_resp := false.B
    req.bits.no_alloc := false.B // <- might be able to imp writethrough
    req.bits.no_xcpt := true.B // no vm/dp, so no page faults etc
    // TODO: use tlIn.a.bits.user to carry pc/tmask etc

    // D
    // assert(!resp.valid || !resp.bits.replay, "cannot replay requests")
    assert(!resp.valid || tlIn.d.ready, "response must be ready!")
    tlIn.d.valid := resp.valid
    tlIn.d.bits.data := resp.bits.data
    tlIn.d.bits.size := resp.bits.size
    tlIn.d.bits.source := resp.bits.tag
    tlIn.d.bits.opcode := MuxCase(TLMessages.AccessAckData, Seq(
      (resp.bits.cmd === M_XRD) -> TLMessages.AccessAckData,
      (resp.bits.cmd === M_XWR) -> TLMessages.AccessAck,
    ))
    assert(!resp.fire || resp.bits.cmd === M_XRD || resp.bits.cmd === M_XWR)
    assert(!resp.fire || (resp.bits.has_data === (resp.bits.cmd === M_XRD)))
    // tlIn.d.bits.user := ???
  }

  // simple if <-> cache
  // ================
  val cacheIO = outer.nbdCache.module.io

  cacheIO.cpu <> inIF.io.cache
  // cacheIO.cpu.req <> inIF.io.cache.req
  cacheIO.cpu.s1_kill := false.B
  cacheIO.cpu.s1_data := 0.U.asTypeOf(cacheIO.cpu.s1_data.cloneType)
  cacheIO.cpu.s2_kill := false.B
  cacheIO.cpu.keep_clock_enabled := true.B

  inIF.io.cache.resp := cacheIO.cpu.resp
  inIF.io.cache.perf := cacheIO.cpu.perf
//  inIF.io.cache.s2_nack := cacheIO.cpu.s2_nack
//  cacheIO.cpu.perf.
//  DontCare <> cacheIO.cpu.perf
  cacheIO.ptw <> DontCare
  cacheIO.ptw.req.ready := false.B
  cacheIO.ptw.resp.bits := DontCare
  cacheIO.ptw.resp.valid := false.B

  cacheIO.tlb_port.req <> DontCare
  cacheIO.tlb_port.req.valid := false.B
  cacheIO.tlb_port.s2_kill := false.B

  // handle user data
  // ================
  val userQueueEnq = Wire(DecoupledIO(tlIn.a.bits.user.cloneType))
  userQueueEnq.bits := tlIn.a.bits.user
  userQueueEnq.valid := tlIn.a.fire
  assert(!tlIn.a.fire || userQueueEnq.ready, "not enough user queue entries")

  val userQueueDeq = Queue(
    userQueueEnq,
    entries = outer.maxInFlights,
    useSyncReadMem = false)

  userQueueDeq.ready := tlIn.d.fire
  tlIn.d.bits.user := userQueueDeq.bits
  assert(!tlIn.d.fire || userQueueDeq.valid, "user queue entries got dropped")
}

