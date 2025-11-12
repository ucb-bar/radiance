package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.rocket.constants.MemoryOpConstants
import freechips.rocketchip.rocket.{DCacheParams, NonBlockingDCache, PRV, SimpleHellaCacheIF}
import freechips.rocketchip.subsystem.CacheBlockBytes
import freechips.rocketchip.tile.TileKey
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.subsystem.{DummyTileParams, GPUMemory, PhysicalCoreParams}

case class TLNBDCacheParams(
  id: Int,
  cache: DCacheParams,
  cacheTagBits: Int,
  overrideDChannelSize: Option[Int] = None
)

case class DummyCacheCoreParams(
  cacheLineBytes: Int = 32,
  overrideCacheTagBits: Int = 0,
) extends PhysicalCoreParams {
  override val useVector: Boolean = true // for cache line size
  override val vLen: Int = 32
  override val eLen: Int = 32
  override def vMemDataBits: Int = cacheLineBytes * 8
  override def dcacheReqTagBits: Int = overrideCacheTagBits
}

case class DummyCacheTileParams(
  params: TLNBDCacheParams
) extends DummyTileParams {
  val core = DummyCacheCoreParams(
    cacheLineBytes = params.cache.blockBytes,
    overrideCacheTagBits = params.cacheTagBits
  )
  override val tileId = params.id
  override val dcache: Option[DCacheParams] = Some(params.cache)
}

class TLNBDCache(params: TLNBDCacheParams)
                (implicit p: Parameters) extends LazyModule {

  val beatBytes = params.cache.blockBytes
  require(params.cache.blockBytes == (params.cache.rowBits / 8))

  // pretty hacky, might want to figure out a better way
  val dChannelSize = params.overrideDChannelSize.getOrElse(log2Ceil(beatBytes))

  val inNode = TLManagerNode(Seq(
    TLSlavePortParameters.v1(
      managers = Seq(
        TLSlaveParameters.v2(
          address = Seq(AddressSet(0, p(GPUMemory).get.size - 1)),
          name = Some("radiance_l1"),
          fifoId = Some(0),
          supports = TLMasterToSlaveTransferSizes(
            get = TransferSizes(1, beatBytes),
            putFull = TransferSizes(1, beatBytes),
            putPartial = TransferSizes(1, beatBytes),
            // b and c are ignored, but this is passed down to clients
            acquireB = TransferSizes(1, beatBytes),
            acquireT = TransferSizes(1, beatBytes),
          ),
          regionType = RegionType.CACHED
        )
      ),
      beatBytes = beatBytes,
      endSinkId = 4, // hardcoded 4 sink ids for $ to ack E
    )
  ))

  implicit val q = p.alterMap(Map(
    TileKey -> DummyCacheTileParams(params),
    CacheBlockBytes -> params.cache.blockBytes,
    // TileVisibilityNodeKey -> visibilityNode,
  ))

  val nbdCache = LazyModule(new NonBlockingDCache(params.id)(q))

  val outNode = nbdCache.node
  override lazy val module = new TLNBDCacheModule(this)(q)
}

class TLNBDCacheModule(outer: TLNBDCache)(implicit p: Parameters) extends LazyModuleImp(outer)
  with MemoryOpConstants {

  val (tlIn, _) = outer.inNode.in.head
  val inIF = Module(new SimpleHellaCacheIF())

//  assert(!tlIn.a.valid || (tlIn.a.bits.size === log2Ceil(outer.beatBytes).U),
//    "only cache line size accesses supported")

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
    assert(req.bits.tag.getWidth == tlIn.a.bits.source.getWidth,
      s"cache tag bits doesnt match source: ${req.bits.tag.getWidth} != ${tlIn.a.bits.source.getWidth}")

    // every read is cache line sized. this is because the output always starts at lsb
    // regardless of address. i.e. this logic does not use active byte lanes that
    // tilelink uses, so we have to either read the whole line, or re-shift the result.
    req.bits.size := log2Ceil(outer.beatBytes).U // tlIn.a.bits.size
    req.bits.cmd := MuxCase(M_XRD, Seq(
      (tlIn.a.bits.opcode === TLMessages.Get) -> M_XRD,
      (tlIn.a.bits.opcode === TLMessages.PutFullData) -> M_XWR,
      (tlIn.a.bits.opcode === TLMessages.PutPartialData) -> M_XWR,
    )) // TODO: ability to flush
    req.bits.addr := tlIn.a.bits.address & (-outer.beatBytes).S(tlIn.params.addressBits.W).asUInt
    req.bits.data := tlIn.a.bits.data
    req.bits.mask := tlIn.a.bits.mask
    req.bits.signed := false.B
    req.bits.dprv := PRV.M.U
    req.bits.dv := false.B // virtualization
    req.bits.phys := true.B
    req.bits.no_resp := false.B
    req.bits.no_alloc := false.B // <- might be able to imp writethrough
    req.bits.no_xcpt := true.B // no vm/dp, so no page faults etc

//    when (req.fire) {
//      when (req.bits.cmd === M_XRD) {
//        printf(" load-req 0x%x", req.bits.addr)
//      }
//      when (req.bits.cmd === M_XWR) {
//        printf(" store-req %d 0x%x", req.bits.data, req.bits.addr)
//      }
//      printf(" #%d", req.bits.tag)
//    }

    // D
    // assert(!resp.valid || !resp.bits.replay, "cannot replay requests")
    assert(!resp.valid || tlIn.d.ready, "response must be ready!")
    tlIn.d.valid := resp.valid
    tlIn.d.bits.data := resp.bits.data
    tlIn.d.bits.size := outer.dChannelSize.U
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

}

