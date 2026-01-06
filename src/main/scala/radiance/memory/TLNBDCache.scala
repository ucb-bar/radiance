package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
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
  overrideDChannelSize: Option[Int] = None,
  flushAddr: Option[BigInt] = None,
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

class TLNBDCache(val params: TLNBDCacheParams)
                (implicit p: Parameters) extends LazyModule {

  val beatBytes = params.cache.blockBytes
  require(params.cache.blockBytes == (params.cache.rowBits / 8))

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

  val flushRegNode = params.flushAddr.map { addr =>
    TLRegisterNode(
      address = Seq(AddressSet(addr, 0xff)),
      device = new SimpleDevice(f"muon-cache-flush", Seq(s"muon-cache-flush")),
      beatBytes = 8,
      concurrency = 1,
    )
  }

  implicit val q = p.alterMap(Map(
    TileKey -> DummyCacheTileParams(params),
    CacheBlockBytes -> params.cache.blockBytes,
    // TileVisibilityNodeKey -> visibilityNode,
  ))

  val nbdCache = LazyModule(new MuonNonBlockingDCache(params.id, params.flushAddr.isDefined)(q))

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
    // following example of Saturn's HellaInterface
    inIF.io.requestor.s1_kill := false.B
    inIF.io.requestor.s1_data := DontCare
    inIF.io.requestor.s2_kill := false.B
    inIF.io.requestor.keep_clock_enabled := true.B

    val dChSize = outer.params.overrideDChannelSize
    val sizeTagWidth = dChSize match {
      case Some(_) => 0
      case None => tlIn.params.sizeBits
    }

    // A
    req.valid := tlIn.a.valid
    tlIn.a.ready := req.ready
    req.bits := 0.U.asTypeOf(req.bits.cloneType)
    assert(req.bits.tag.getWidth == tlIn.a.bits.source.getWidth + sizeTagWidth,
      s"cache tag bits doesnt match source:" +
      s"${req.bits.tag.getWidth} != ${tlIn.a.bits.source.getWidth} + $sizeTagWidth")

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

    // on the other hand, stores need to have the real size / address so that NBDCache can 
    // generate the correct byte mask when storing to its internal data array
    // (i have no idea what req.bits.mask is actually even being used for? agent claims
    // it is for AMOALU operations or something)
    when (tlIn.a.bits.opcode === TLMessages.PutFullData || tlIn.a.bits.opcode === TLMessages.PutPartialData) {
      req.bits.addr := tlIn.a.bits.address
      // @richard: do we need to actually do the shift here?
      req.bits.data := tlIn.a.bits.data 
      req.bits.size := tlIn.a.bits.size
    }

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
    tlIn.d.bits.opcode := MuxCase(TLMessages.AccessAckData, Seq(
      (resp.bits.cmd === M_XRD) -> TLMessages.AccessAckData,
      (resp.bits.cmd === M_XWR) -> TLMessages.AccessAck,
    ))
    assert(!resp.fire || resp.bits.cmd === M_XRD || resp.bits.cmd === M_XWR)
    assert(!resp.fire || (resp.bits.has_data === (resp.bits.cmd === M_XRD)))
    // tlIn.d.bits.user := ???

    outer.params.overrideDChannelSize match {
      case Some(size) =>
        req.bits.tag := tlIn.a.bits.source
        tlIn.d.bits.size := size.U
        tlIn.d.bits.source := resp.bits.tag
      case None =>
        req.bits.tag := Cat(tlIn.a.bits.size, tlIn.a.bits.source)
        tlIn.d.bits.size := resp.bits.tag >> tlIn.params.sourceBits
        tlIn.d.bits.source := resp.bits.tag(tlIn.params.sourceBits - 1, 0)
    }

  }

  // simple if <-> cache
  // ================
  val cacheIO = outer.nbdCache.module.io

  cacheIO.cpu <> inIF.io.cache
  
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

  // flush mmio
  outer.flushRegNode.foreach { node =>
    val fio = outer.nbdCache.module.flush_io.get
    fio.start := false.B
    node.regmap(
      0x0 -> Seq(RegField.w(32, (valid: Bool, _: UInt) => {
        when (valid) {
          fio.start := true.B
        }
        true.B
        // !fio.busy
      })),
    )
  }

}

