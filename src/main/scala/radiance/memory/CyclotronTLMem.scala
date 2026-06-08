package radiance.memory

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.regmapper.RegField
import freechips.rocketchip.resources.SimpleDevice
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.diplomacy.lazymodule.{LazyModule, LazyModuleImp}
import radiance.cluster.CacheFlushNode
import radiance.muon.{LoadStoreUnitDerivedParams, MuonKey}
import radiance.subsystem.GPUMemory
import radiance.unittest.{CyclotronDataMem, CyclotronInstMem}

case class CyclotronTLInstMemParams(
  name: String = "cyclotron_tl_inst_mem",
  respQueueDepth: Int = 4,
  flushAddr: Option[BigInt] = None,
)

case class CyclotronTLDataMemParams(
  name: String = "cyclotron_tl_data_mem",
  respQueueDepth: Int = 4,
  flushAddr: Option[BigInt] = None,
)

private object CyclotronTLMem {
  def gmemAddress(implicit p: Parameters): Seq[AddressSet] =
    Seq(AddressSet(0, p(GPUMemory).get.size - 1))

  def flushRegNode(name: String, flushAddr: Option[BigInt]): Option[TLRegisterNode] =
    flushAddr.map { addr =>
      TLRegisterNode(
        address = Seq(AddressSet(addr, 0xff)),
        device = new SimpleDevice(name, Seq(name)),
        beatBytes = 8,
        concurrency = 1,
      )
    }

  def flushNode(flushAddr: Option[BigInt]): Option[CacheFlushNode.Slave] =
    flushAddr.map(_ => CacheFlushNode.Slave())
}

private object CyclotronTLNoOpFlush {
  def apply(
    flushRegNode: Option[TLRegisterNode],
    flushNode: Option[CacheFlushNode.Slave],
  ): Unit = {
    flushRegNode.foreach { node =>
      node.regmap(
        0x0 -> Seq(RegField.w(32, (_: Bool, _: UInt) => true.B)),
      )
    }

    flushNode.map(_.in.head._1).foreach { node =>
      // one-cycle delay from start to done
      node.done := RegNext(node.start, false.B)
    }
  }
}

class CyclotronTLResp(
  sourceBits: Int,
  sizeBits: Int,
  dataBits: Int,
) extends Bundle {
  val source = UInt(sourceBits.W)
  val size = UInt(sizeBits.W)
  val data = UInt(dataBits.W)
  val isRead = Bool()
}

class CyclotronTLReqMeta(sizeBits: Int) extends Bundle {
  val size = UInt(sizeBits.W)
  val isRead = Bool()
}

class CyclotronTLInstMem(val params: CyclotronTLInstMemParams = CyclotronTLInstMemParams())
                         (implicit p: Parameters) extends LazyModule {
  require(params.respQueueDepth > 0)

  private val muonParams = p(MuonKey)
  val beatBytes: Int = muonParams.instBits / 8

  val inNode = TLManagerNode(Seq(
    TLSlavePortParameters.v1(
      managers = Seq(TLSlaveParameters.v2(
        address = CyclotronTLMem.gmemAddress,
        name = Some(params.name),
        regionType = RegionType.UNCACHED,
        executable = true,
        fifoId = Some(0),
        supports = TLMasterToSlaveTransferSizes(
          get = TransferSizes(1, beatBytes),
          putFull = TransferSizes(1, beatBytes),
          putPartial = TransferSizes(1, beatBytes),
        ),
      )),
      beatBytes = beatBytes,
    )
  ))

  val flushRegNode = CyclotronTLMem.flushRegNode(s"${params.name}_flush", params.flushAddr)
  val flushNode = CyclotronTLMem.flushNode(params.flushAddr)

  lazy val module = new CyclotronTLInstMemImp(this)
}

class CyclotronTLInstMemImp(outer: CyclotronTLInstMem)
                           (implicit p: Parameters) extends LazyModuleImp(outer) {
  val (tl, edge) = outer.inNode.in.head
  val imem = Module(new CyclotronInstMem)

  require(
    tl.params.sourceBits <= p(MuonKey).l0iReqTagBits,
    s"TL sourceBits (${tl.params.sourceBits}) exceed Cyclotron IMem tag bits (${p(MuonKey).l0iReqTagBits})"
  )

  // decoupling queue to safely stall Cyclotron mem when d.ready is blocked;
  // not essential
  val respQueue = Module(new Queue(
    new CyclotronTLResp(tl.params.sourceBits, tl.params.sizeBits, outer.beatBytes * 8),
    outer.params.respQueueDepth,
  ))

  val outstanding = RegInit(0.U(log2Ceil(outer.params.respQueueDepth + 1).W))
  val canAccept = outstanding < outer.params.respQueueDepth.U || tl.d.fire

  tl.a.ready := imem.io.imem.req.ready && canAccept

  imem.io.imem.req.valid := tl.a.valid && canAccept
  imem.io.imem.req.bits.store := false.B
  imem.io.imem.req.bits.address := tl.a.bits.address
  imem.io.imem.req.bits.size := log2Ceil(outer.beatBytes).U
  imem.io.imem.req.bits.tag := tl.a.bits.source
  imem.io.imem.req.bits.data := 0.U
  imem.io.imem.req.bits.mask := Fill(outer.beatBytes, 1.U(1.W))
  imem.io.imem.req.bits.metadata := DontCare

  assert(!tl.a.valid || tl.a.bits.opcode === TLMessages.Get,
    "CyclotronTLInstMem only supports TL Get requests")
  assert(!tl.a.fire || tl.a.bits.size === log2Ceil(outer.beatBytes).U,
    "CyclotronTLInstMem expects instruction-sized TL Gets")

  imem.io.imem.resp.ready := respQueue.io.enq.ready
  respQueue.io.enq.valid := imem.io.imem.resp.valid
  respQueue.io.enq.bits.source := imem.io.imem.resp.bits.tag
  respQueue.io.enq.bits.size := log2Ceil(outer.beatBytes).U
  respQueue.io.enq.bits.data := imem.io.imem.resp.bits.data
  respQueue.io.enq.bits.isRead := true.B
  assert(!imem.io.imem.resp.valid || respQueue.io.enq.ready,
    "CyclotronTLInstMem response queue overflow")

  tl.d.valid := respQueue.io.deq.valid
  tl.d.bits := edge.AccessAck(
    toSource = respQueue.io.deq.bits.source,
    lgSize = respQueue.io.deq.bits.size,
    data = respQueue.io.deq.bits.data,
  )
  respQueue.io.deq.ready := tl.d.ready

  when (tl.a.fire && !tl.d.fire) {
    outstanding := outstanding + 1.U
  }.elsewhen (!tl.a.fire && tl.d.fire) {
    outstanding := outstanding - 1.U
  }

  CyclotronTLNoOpFlush(outer.flushRegNode, outer.flushNode)
}

class CyclotronTLDataMem(val params: CyclotronTLDataMemParams = CyclotronTLDataMemParams())
                         (implicit p: Parameters) extends LazyModule {
  require(params.respQueueDepth > 0)

  private val muonParams = p(MuonKey)
  private val lsuDerived = new LoadStoreUnitDerivedParams(p, muonParams)
  val numLanes: Int = muonParams.lsu.numLsuLanes
  val beatBytes: Int = muonParams.archLen / 8
  val dmemTagBits: Int = lsuDerived.sourceIdBits + lsuDerived.laneIdBits

  val inNodes = Seq.tabulate(numLanes) { lane =>
    TLManagerNode(Seq(
      TLSlavePortParameters.v1(
        managers = Seq(TLSlaveParameters.v2(
          address = CyclotronTLMem.gmemAddress,
          name = Some(s"${params.name}_lane_$lane"),
          regionType = RegionType.UNCACHED,
          executable = false,
          fifoId = Some(0),
          supports = TLMasterToSlaveTransferSizes(
            get = TransferSizes(1, beatBytes),
            putFull = TransferSizes(1, beatBytes),
            putPartial = TransferSizes(1, beatBytes),
          ),
        )),
        beatBytes = beatBytes,
      )
    ))
  }

  val flushRegNode = CyclotronTLMem.flushRegNode(s"${params.name}_flush", params.flushAddr)
  val flushNode = CyclotronTLMem.flushNode(params.flushAddr)

  lazy val module = new CyclotronTLDataMemImp(this)
}

class CyclotronTLDataMemImp(outer: CyclotronTLDataMem)
                           (implicit p: Parameters) extends LazyModuleImp(outer) {
  val dmem = Module(new CyclotronDataMem)

  for (((node, laneReq), laneResp) <- outer.inNodes zip dmem.io.dmem.req zip dmem.io.dmem.resp) {
    val (tl, edge) = node.in.head

    require(
      tl.params.sourceBits <= outer.dmemTagBits,
      s"TL sourceBits (${tl.params.sourceBits}) exceed Cyclotron DMem tag bits (${outer.dmemTagBits})"
    )

    val reqMeta = Reg(Vec(1 << tl.params.sourceBits, new CyclotronTLReqMeta(tl.params.sizeBits)))
    val respQueue = Module(new Queue(
      new CyclotronTLResp(tl.params.sourceBits, tl.params.sizeBits, outer.beatBytes * 8),
      outer.params.respQueueDepth,
    ))

    val outstanding = RegInit(0.U(log2Ceil(outer.params.respQueueDepth + 1).W))
    val canAccept = outstanding < outer.params.respQueueDepth.U || tl.d.fire
    val isRead = tl.a.bits.opcode === TLMessages.Get
    val isWrite = tl.a.bits.opcode === TLMessages.PutFullData ||
      tl.a.bits.opcode === TLMessages.PutPartialData

    tl.a.ready := laneReq.ready && canAccept

    laneReq.valid := tl.a.valid && canAccept
    laneReq.bits.store := !isRead
    laneReq.bits.address := tl.a.bits.address
    laneReq.bits.size := tl.a.bits.size
    laneReq.bits.tag := tl.a.bits.source
    laneReq.bits.data := tl.a.bits.data
    laneReq.bits.mask := tl.a.bits.mask
    laneReq.bits.metadata := DontCare

    when (tl.a.fire) {
      reqMeta(tl.a.bits.source).isRead := isRead
      reqMeta(tl.a.bits.source).size := tl.a.bits.size
    }

    assert(!tl.a.valid || isRead || isWrite,
      "CyclotronTLDataMem only supports TL Get/PutFullData/PutPartialData requests")
    assert(!tl.a.fire || tl.a.bits.size <= log2Ceil(outer.beatBytes).U,
      "CyclotronTLDataMem request size exceeds lane beat size")

    val respSource = laneResp.bits.tag(tl.params.sourceBits - 1, 0)
    laneResp.ready := respQueue.io.enq.ready
    respQueue.io.enq.valid := laneResp.valid
    respQueue.io.enq.bits.source := respSource
    respQueue.io.enq.bits.size := reqMeta(respSource).size
    respQueue.io.enq.bits.data := laneResp.bits.data
    respQueue.io.enq.bits.isRead := reqMeta(respSource).isRead
    assert(!laneResp.valid || respQueue.io.enq.ready,
      "CyclotronTLDataMem response queue overflow")

    tl.d.valid := respQueue.io.deq.valid
    tl.d.bits := Mux(
      respQueue.io.deq.bits.isRead,
      edge.AccessAck(
        toSource = respQueue.io.deq.bits.source,
        lgSize = respQueue.io.deq.bits.size,
        data = respQueue.io.deq.bits.data,
      ),
      edge.AccessAck(
        toSource = respQueue.io.deq.bits.source,
        lgSize = respQueue.io.deq.bits.size,
      ),
    )
    respQueue.io.deq.ready := tl.d.ready

    when (tl.a.fire && !tl.d.fire) {
      outstanding := outstanding + 1.U
    }.elsewhen (!tl.a.fire && tl.d.fire) {
      outstanding := outstanding - 1.U
    }
  }

  CyclotronTLNoOpFlush(outer.flushRegNode, outer.flushNode)
}
