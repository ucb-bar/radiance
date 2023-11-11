package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Parameters, Field}

case object VortexL1Key extends Field[Option[VortexL1Config]](None /*default*/ )

case class VortexL1Config(
    numBanks: Int,
    wordSize: Int, // This is the read/write granularity of the L1 cache
    cacheLineSize: Int,
    coreTagWidth: Int,
    writeInfoReqQSize: Int,
    mshrSize: Int,
    uncachedAddrSets: Seq[AddressSet],
    icacheInstAddrSets: Seq[AddressSet]
) {
  def coreTagPlusSizeWidth: Int = {
    log2Ceil(wordSize) + coreTagWidth
  }
  require(
    mshrSize == l2ReqSourceGenSize,
    "MSHR size must match the number of sourceIds to downstream."
  )
}

object defaultVortexL1Config
    extends VortexL1Config(
      numBanks = 4,
      wordSize = 16,
      cacheLineSize = 16,
      coreTagWidth = 8,
      writeInfoReqQSize = 16,
      mshrSize = 8,
      l2ReqSourceGenSize = 8,
      uncachedAddrSets = Seq(AddressSet(0x2000000L, 0xffL)),
      icacheInstAddrSets = Seq(AddressSet(0x80000000L, 0xfffffffL))
    )

class VortexL1Cache(config: VortexL1Config)(implicit p: Parameters)
    extends LazyModule {
  // icache bank
  val icache_bank = LazyModule(new VortexFatBank(config, 0, isICache = true))

  // dcache banks
  val dcache_banks = Seq.tabulate(config.numBanks) { bankId =>
    val bank = LazyModule(new VortexFatBank(config, bankId))
    bank
  }
  // passthrough
  val passThrough = LazyModule(new FatBankPassThrough(config))

  // visibility node that exposes to upstream
  val coresideNode = TLIdentityNode()

  // core-side crossbar that arbitrates core requests to banks
  protected val bankXbar = LazyModule(new TLXbar)
  bankXbar.node :=* coresideNode
  dcache_banks.foreach { _.coalToVxCacheNode :=* bankXbar.node }
  passThrough.coalToVxCacheNode :=* bankXbar.node
  icache_bank.coalToVxCacheNode :=* bankXbar.node

  // master node that exposes to and drives the downstream
  val masterNode = TLIdentityNode()
  dcache_banks.foreach { masterNode := _.vxCacheToL2Node }
  masterNode := passThrough.vxCacheToL2Node
  masterNode := icache_bank.vxCacheToL2Node

  lazy val module = new LazyModuleImp(this)
}

// TODO: Make the FatBank Pass Through a Blocking Module
class FatBankPassThrough(config: VortexL1Config)(implicit p: Parameters)
    extends LazyModule {
  // Slave node to upstream
  val managerParam = Seq(
    TLSlavePortParameters.v1(
      beatBytes = config.wordSize,
      managers = Seq(
        TLSlaveParameters.v1(
          address = config.uncachedAddrSets,
          regionType = RegionType.IDEMPOTENT,
          executable = false,
          supportsGet = TransferSizes(1, config.wordSize),
          supportsPutPartial = TransferSizes(1, config.wordSize),
          supportsPutFull = TransferSizes(1, config.wordSize),
          fifoId = Some(0)
        )
      )
    )
  )

  // Master node to downstream
  val clientParam = Seq(
    TLMasterPortParameters.v1(
      clients = Seq(
        TLMasterParameters.v1(
          name = "VortexFatBank",
          sourceId = IdRange(0, 1 << (log2Ceil(config.l2ReqSourceGenSize) + 5)),
          supportsProbe = TransferSizes(1, config.wordSize),
          supportsGet = TransferSizes(1, config.wordSize),
          supportsPutFull = TransferSizes(1, config.wordSize),
          supportsPutPartial = TransferSizes(1, config.wordSize)
        )
      )
    )
  )

  val coalToVxCacheNode = TLManagerNode(managerParam)
  val vxCacheFetchNode = TLClientNode(clientParam)
  val vxCacheToL2Node = TLIdentityNode()
  vxCacheToL2Node := TLWidthWidget(config.cacheLineSize) := vxCacheFetchNode

  // the implementation to make everything a pass through
  lazy val module = new LazyModuleImp(this) {
    val (upstream, _) = coalToVxCacheNode.in(0)
    val (downstream, _) = vxCacheFetchNode.out(0)

    downstream.a <> upstream.a
    upstream.d <> downstream.d
  }
}

class VortexFatBank(
    config: VortexL1Config,
    bankId: Int,
    isICache: Boolean = false
)(implicit p: Parameters)
    extends LazyModule {
  // Generate AddressSet by excluding Addr we don't want
  def generateAddressSets(): Seq[AddressSet] = {
    if (isICache) {
      config.icacheInstAddrSets
      // Seq(AddressSet(0x00000000L, 0xFFFFFFFFL))
    } else {
      // suppose have 4 bank
      // base for bank 1: ...000000|01|0000
      // mask for bank 1;    111111|00|1111
      val mask = 0xffffffffL ^ ((config.numBanks - 1) * config.wordSize)
      val base = 0x00000000L | (bankId * config.wordSize)

      val excludeSets = (config.uncachedAddrSets ++ config.icacheInstAddrSets)
      var remainingSets: Seq[AddressSet] = Seq(AddressSet(base, mask))
      for (excludeSet <- excludeSets) {
        remainingSets = remainingSets.flatMap(_.subtract(excludeSet))
      }
      remainingSets
    }
  }

  // Slave node to upstream
  val managerParam = Seq(
    TLSlavePortParameters.v1(
      beatBytes = config.wordSize,
      managers = Seq(
        TLSlaveParameters.v1(
          address = generateAddressSets(),
          regionType = RegionType.IDEMPOTENT, // idk what this does
          executable = false,
          supportsGet = TransferSizes(1, config.wordSize),
          supportsPutPartial = TransferSizes(1, config.wordSize),
          supportsPutFull = TransferSizes(1, config.wordSize),
          fifoId = Some(0)
        )
      )
    )
  )

  // Master node to downstream
  val clientParam = Seq(
    TLMasterPortParameters.v1(
      clients = Seq(
        TLMasterParameters.v1(
          name = "VortexFatBank",
          sourceId = IdRange(0, config.l2ReqSourceGenSize),
          supportsProbe = TransferSizes(1, config.wordSize),
          supportsGet = TransferSizes(1, config.wordSize),
          supportsPutFull = TransferSizes(1, config.wordSize),
          supportsPutPartial = TransferSizes(1, config.wordSize)
        )
      )
    )
  )

  val coalToVxCacheNode = TLManagerNode(managerParam)
  val vxCacheToL2Node = TLIdentityNode()
  val vxCacheFetchNode = TLClientNode(clientParam)

  // We need this widthWidget here, because whenever the fatBank is performing
  // read and write to Mem, it must have the illusion that dataWidth is as big as
  // as its cacheline size
  vxCacheToL2Node := TLWidthWidget(config.cacheLineSize) := vxCacheFetchNode
  lazy val module = new VortexFatBankImp(this, config);
}

class VortexFatBankImp(
    outer: VortexFatBank,
    config: VortexL1Config
) extends LazyModuleImp(outer) {
  val vxCache = Module(
    new VX_cache(
      WORD_SIZE = config.wordSize,
      CACHE_LINE_SIZE = config.cacheLineSize,
      CORE_TAG_WIDTH = config.coreTagPlusSizeWidth,
      MSHR_SIZE = config.mshrSize
    )
  );

  vxCache.io.clk := clock
  vxCache.io.reset := reset

  val writeReqCount = RegInit(UInt(32.W), 0.U)
  val writeInputFire = Wire(Bool())
  val writeOutputFire = Wire(Bool())

  when(writeInputFire && ~writeOutputFire) {
    writeReqCount := writeReqCount + 1.U
  }.elsewhen(~writeInputFire && writeOutputFire) {
    writeReqCount := writeReqCount - 1.U
  }

  dontTouch(writeInputFire)
  dontTouch(writeOutputFire)
  dontTouch(writeReqCount)

  class WriteReqInfo extends Bundle {
    val id = UInt(32.W)
    val size = UInt(32.W)
  }

  class ReadReqInfo(config: VortexL1Config) extends Bundle {
    val size = UInt(log2Ceil(config.wordSize).W)
    val id = UInt(config.coreTagWidth.W)
  }

  val rcvWriteReqInfo = Module(
    new Queue(
      (new WriteReqInfo).cloneType,
      config.writeInfoReqQSize,
      true,
      false
    )
  )
  val readReqInfo = Wire(new ReadReqInfo(config))

  // Translate TL request from Coalescer to requests for VX_cache
  def TLReq2VXReq = {
    val (coalToBankBundle, _) = outer.coalToVxCacheNode.in.head
    // coal -> vxCache request on channel A
    val coalToBankA = coalToBankBundle.a;

    coalToBankA.ready := vxCache.io.core_req_ready && rcvWriteReqInfo.io.enq.ready // not optimal
    vxCache.io.core_req_valid := coalToBankA.valid

    // read = 0, write = 1
    vxCache.io.core_req_rw := !(coalToBankA.bits.opcode === TLMessages.Get)
    // 4 is also hardcoded, it should be log2WordSize
    vxCache.io.core_req_addr := coalToBankA.bits.address(
      31,
      log2Ceil(config.wordSize)
    )
    vxCache.io.core_req_byteen := coalToBankA.bits.mask
    vxCache.io.core_req_data := coalToBankA.bits.data

    // combine size and tag field into one big wire, to put into vxCache.io.core_req_tag
    readReqInfo.id := coalToBankA.bits.source
    readReqInfo.size := coalToBankA.bits.size
    vxCache.io.core_req_tag := readReqInfo.asTypeOf(vxCache.io.core_req_tag)

    writeInputFire := vxCache.io.core_req_rw && coalToBankA.fire

    // ignore param, size, corrupt fields

    // vxCache -> coal response on channel D
    // ok ... this part is a little tricky, the downstream coalescer requires
    // the L1 cache to send ack and dataAck, this is how coalescer knows when
    // an inflight ID has retired if we don't send ack, the coalescer will run
    // out of IDs, and can't generate new request

    // for read request, we send AckData when the FatBank has a valid output
    //
    // for write request, we can ack whenever we have a valid entry in
    // rcvWriteReqInfo Queue

    val coalToBankD = coalToBankBundle.d;

    // FIXME: currently assuming below buffer is never full
    rcvWriteReqInfo.io.enq.valid := !(coalToBankA.bits.opcode === TLMessages.Get) && coalToBankA.valid && coalToBankA.ready
    rcvWriteReqInfo.io.enq.bits.id := coalToBankA.bits.source
    rcvWriteReqInfo.io.enq.bits.size := coalToBankA.bits.size

    // prioritize Ack for Read, so we only deque from writeReqInfo, if we don't
    // have a readReq we need to ack
    //
    // vxCache.io.core_rsp_valid means readDataAck
    rcvWriteReqInfo.io.deq.ready := coalToBankD.ready && ~vxCache.io.core_rsp_valid

    vxCache.io.core_rsp_ready := coalToBankD.ready
    coalToBankD.valid := vxCache.io.core_rsp_valid || rcvWriteReqInfo.io.deq.valid

    coalToBankD.bits.source := Mux(
      vxCache.io.core_rsp_valid,
      vxCache.io.core_rsp_tag.asTypeOf(readReqInfo).id,
      rcvWriteReqInfo.io.deq.bits.id
    )

    coalToBankD.bits.opcode := Mux(
      vxCache.io.core_rsp_valid,
      TLMessages.AccessAckData,
      TLMessages.AccessAck
    )

    coalToBankD.bits.size := Mux(
      vxCache.io.core_rsp_valid,
      vxCache.io.core_rsp_tag.asTypeOf(readReqInfo).size,
      rcvWriteReqInfo.io.deq.bits.size
    )

    coalToBankD.bits.param := 0.U
    coalToBankD.bits.sink := 0.U
    coalToBankD.bits.denied := false.B
    coalToBankD.bits.corrupt := false.B
    coalToBankD.bits.data := vxCache.io.core_rsp_data
  }

  // Since Vortex L1 is a write-through cache, it doesn't bookkeep writes and
  // therefore doesn't allocate a new UUID for write requests.  We use a
  // separate source ID allocator to solve this.
  val sourceGen = Module(
    new NewSourceGenerator(
      log2Ceil(config.l2ReqSourceGenSize),
      metadata = Some(UInt(32.W)),
      ignoreInUse = false
    )
  )

  // Translate VX_cache mem request to a TL request to be sent to L2
  def VXReq2TLReq = {
    val (tlOutToL2, _) = outer.vxCacheFetchNode.out.head

    // vxCache -> downstream L2 request
    vxCache.io.mem_req_ready := tlOutToL2.a.ready && sourceGen.io.id.valid
    tlOutToL2.a.valid := vxCache.io.mem_req_valid && sourceGen.io.id.valid

    sourceGen.io.gen := tlOutToL2.a.fire
    sourceGen.io.meta := vxCache.io.mem_req_tag // save the old read id

    writeOutputFire := tlOutToL2.a.fire && vxCache.io.mem_req_rw

    tlOutToL2.a.bits.opcode := Mux(
      vxCache.io.mem_req_rw,
      Mux(
        vxCache.io.mem_req_byteen.andR,
        TLMessages.PutFullData,
        TLMessages.PutPartialData
      ),
      TLMessages.Get
    )

    tlOutToL2.a.bits.address := Cat(vxCache.io.mem_req_addr, 0.U(4.W))
    tlOutToL2.a.bits.mask := Mux(
      vxCache.io.mem_req_rw,
      vxCache.io.mem_req_byteen,
      0xffff.U
    )
    tlOutToL2.a.bits.data := vxCache.io.mem_req_data
    tlOutToL2.a.bits.source := sourceGen.io.id.bits
    // ignore param, size, corrupt fields
    tlOutToL2.a.bits.param := 0.U
    tlOutToL2.a.bits.size := 4.U // FIXME: hardcoded
    tlOutToL2.a.bits.corrupt := false.B
    // downstream L2 -> vxCache response
    tlOutToL2.d.ready := vxCache.io.mem_rsp_ready

    vxCache.io.mem_rsp_valid :=
      tlOutToL2.d.valid && (tlOutToL2.d.bits.opcode === TLMessages.AccessAckData)
    vxCache.io.mem_rsp_tag := sourceGen.io.peek
    vxCache.io.mem_rsp_data := tlOutToL2.d.bits.data

    sourceGen.io.reclaim.valid := tlOutToL2.d.fire
    sourceGen.io.reclaim.bits := tlOutToL2.d.bits.source
  }

  TLReq2VXReq
  VXReq2TLReq
}

class VX_cache(
    CACHE_ID: Int = 0,
    CACHE_SIZE: Int = 16384 / 4, // <FIXME, divided by 4 for faster simulation
    CACHE_LINE_SIZE: Int = 16,
    NUM_PORTS: Int = 1,
    WORD_SIZE: Int =
      16, // hack - one "word" is enough to satisfy all 4 warps after decoalescing.
    CREQ_SIZE: Int = 0,
    CRSQ_SIZE: Int = 2,
    MSHR_SIZE: Int = 8,
    MRSQ_SIZE: Int = 0,
    MREQ_SIZE: Int = 4,
    WRITE_ENABLE: Int = 1,
    CORE_TAG_WIDTH: Int =
      10, // source ID ranges from 0 to 1 << 10, we need to allocate upper bits to save size
    CORE_TAG_ID_BITS: Int =
      5, // no idea what this is, just match it with default L1 dcache
    BANK_ADDR_OFFSET: Int = 0,
    NC_ENABLE: Int = 0, // NC_ENABLE=1 means the cache becomes a passthrough
    WORD_ADDR_WIDTH: Int = 28, // 16 byte "word" = 4 bits
    MEM_TAG_WIDTH: Int =
      14, // Elaborated value is also completely different from (32 - log2Ceil(CACHE_LINE_SIZE)). This should match with sourceIds on client node associated with this cache
    MEM_ADDR_WIDTH: Int = 28 // 16 byte cache line = 4 bits
) extends BlackBox(
      Map(
        "CACHE_ID" -> CACHE_ID,
        "NUM_REQS" -> 1, // Force NUM_REQS to be 1, we use their Cache as our individual Bank
        "CACHE_SIZE" -> CACHE_SIZE,
        "CACHE_LINE_SIZE" -> CACHE_LINE_SIZE,
        "NUM_PORTS" -> NUM_PORTS,
        "WORD_SIZE" -> WORD_SIZE,
        "CREQ_SIZE" -> CREQ_SIZE,
        "CRSQ_SIZE" -> CRSQ_SIZE,
        "MSHR_SIZE" -> MSHR_SIZE,
        "MRSQ_SIZE" -> MRSQ_SIZE,
        "MREQ_SIZE" -> MREQ_SIZE,
        "WRITE_ENABLE" -> WRITE_ENABLE,
        "CORE_TAG_WIDTH" -> CORE_TAG_WIDTH,
        "CORE_TAG_ID_BITS" -> CORE_TAG_ID_BITS,
        "MEM_TAG_WIDTH" -> MEM_TAG_WIDTH,
        "BANK_ADDR_OFFSET" -> BANK_ADDR_OFFSET,
        "NC_ENABLE" -> NC_ENABLE
      )
    )
    with HasBlackBoxResource {

  val io = IO(new Bundle {
    val clk = Input(Clock())
    val reset = Input(Reset())

    // We should be able to turn the following into TileLink easily

    // CACHE <> CORE
    val core_req_valid = Input(Bool())
    val core_req_rw = Input(Bool())
    val core_req_addr = Input(UInt(WORD_ADDR_WIDTH.W))
    val core_req_byteen = Input(UInt(WORD_SIZE.W))
    val core_req_data = Input(UInt((WORD_SIZE * 8).W))
    val core_req_tag = Input(UInt(CORE_TAG_WIDTH.W))
    val core_req_ready = Output(Bool())

    val core_rsp_valid = Output(Bool()) // 1 bit wide
    val core_rsp_tmask =
      Output(Bool()) // 1 bit wide, probably can ignore (check waveform)
    val core_rsp_data = Output(UInt((WORD_SIZE * 8).W))
    val core_rsp_tag = Output(UInt(CORE_TAG_WIDTH.W))
    val core_rsp_ready = Input(Bool())

    // CACHE <> L2
    val mem_req_valid = Output(Bool())
    val mem_req_rw = Output(Bool())
    val mem_req_byteen = Output(UInt(CACHE_LINE_SIZE.W))
    val mem_req_addr = Output(UInt(MEM_ADDR_WIDTH.W))
    val mem_req_data = Output(UInt((CACHE_LINE_SIZE * 8).W))
    val mem_req_tag = Output(UInt(MEM_TAG_WIDTH.W))
    val mem_req_ready = Input(Bool())

    val mem_rsp_valid = Input(Bool())
    val mem_rsp_data = Input(UInt((CACHE_LINE_SIZE * 8).W))
    val mem_rsp_tag = Input(UInt(MEM_TAG_WIDTH.W))
    val mem_rsp_ready = Output(Bool())
  })

  addResource("/vsrc/vortex/hw/rtl/VX_dispatch.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_issue.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_cache_define.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_warp_sched.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_sat.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_stride.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_lerp.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_addr.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_mem.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_format.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_sampler.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_unit.sv")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_define.vh")
  addResource("/vsrc/vortex/hw/rtl/tex_unit/VX_tex_wrap.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_scope.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_fpu_unit.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_scoreboard.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_writeback.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_muldiv.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_decode.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_ibuffer.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_icache_stage.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_gpu_unit.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_trace_instr.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_gpu_types.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_config.vh")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_lzc.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_fifo_queue.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_scan.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_find_first.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_multiplier.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_bits_remove.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_pipe_register.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_priority_encoder.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_reset_relay.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_popcount.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_bits_insert.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_skid_buffer.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_fixed_arbiter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_shift_register.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_index_buffer.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_onehot_encoder.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_matrix_arbiter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_dp_ram.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_axi_adapter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_elastic_buffer.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_rr_arbiter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_stream_arbiter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_sp_ram.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_stream_demux.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_serial_div.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_fair_arbiter.sv")
  addResource("/vsrc/vortex/hw/rtl/libs/VX_pending_size.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_define.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_csr_data.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_cache_arb.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_ipdom_stack.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_gpr_stage.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_execute.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_fetch.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_alu_unit.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_platform.vh")
  addResource("/vsrc/vortex/hw/rtl/VX_commit.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_pipeline.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_lsu_unit.sv")
  addResource("/vsrc/vortex/hw/rtl/VX_csr_unit.sv")
  addResource("/vsrc/vortex/hw/VX_config.h")
  addResource("/vsrc/vortex/sim/common/rvfloats.h")
  addResource("/vsrc/vortex/sim/common/rvfloats.cpp")
  addResource("/csrc/softfloat/include/internals.h")
  addResource("/csrc/softfloat/include/primitives.h")
  addResource("/csrc/softfloat/include/primitiveTypes.h")
  addResource("/csrc/softfloat/include/softfloat.h")
  addResource("/csrc/softfloat/include/softfloat_types.h")
  addResource("/csrc/softfloat/RISCV/specialize.h")
  addResource("/vsrc/vortex/hw/dpi/float_dpi.cpp")
  addResource("/vsrc/vortex/hw/dpi/float_dpi.vh")
  addResource("/vsrc/vortex/hw/dpi/util_dpi.cpp")
  addResource("/vsrc/vortex/hw/dpi/util_dpi.vh")
  addResource("/vsrc/vortex/hw/rtl/fp_cores/VX_fpu_dpi.sv")
  addResource("/vsrc/vortex/hw/rtl/fp_cores/VX_fpu_define.vh")
  addResource("/vsrc/vortex/hw/rtl/fp_cores/VX_fpu_types.vh")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_icache_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_dcache_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_tex_csr_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_join_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_ifetch_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_cache_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_memsys_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_gpr_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_decode_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_writeback_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_gpu_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_pipeline_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_gpr_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_cmt_to_csr_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_csr_to_alu_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_ifetch_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_alu_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_csr_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_ibuffer_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_branch_ctl_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_dcache_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_icache_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_lsu_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_wstall_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_mem_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_fpu_to_csr_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_commit_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_tex_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_warp_ctl_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_tex_rsp_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_fetch_to_csr_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_tex_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_mem_req_if.sv")
  addResource("/vsrc/vortex/hw/rtl/interfaces/VX_fpu_req_if.sv")
  // addResource("/vsrc/vortex/hw/rtl/cache/VX_shared_mem.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_core_rsp_merge.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_tag_access.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_core_req_bank_sel.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_bank.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_data_access.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_flush_ctrl.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_nc_bypass.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_miss_resrv.sv")
  addResource("/vsrc/vortex/hw/rtl/cache/VX_cache.sv")

}

// <FIXME> Delete the following NewSourceGenerator when merging with origin/graphics
// we should just use the one in coalescing.scala written by hansung

class NewSourceGenerator[T <: Data](
    sourceWidth: Int,
    metadata: Option[T] = None,
    ignoreInUse: Boolean = false
) extends Module {
  def getMetadataType = metadata match {
    case Some(gen) => gen.cloneType
    case None      => UInt(0.W)
  }
  val io = IO(new Bundle {
    val gen = Input(Bool())
    val reclaim = Input(Valid(UInt(sourceWidth.W)))
    val id = Output(Valid(UInt(sourceWidth.W)))
    // below are used only when metadata is not None
    // `meta` is used as input when a request succeeds id generation to store
    // its value to the table.
    // `peek` is the retrieved metadata saved for the request when corresponding
    // request has come back, setting `reclaim`.
    // Although these do not use ValidIO, it is safe because any in-flight
    // response coming back should have allocated a valid entry in the table
    // when it went out.
    val meta = Input(getMetadataType)
    val peek = Output(getMetadataType)
    // for debugging; indicates whether there is at least one inflight request
    // that hasn't been reclaimed yet
    val inflight = Output(Bool())
  })
  val head = RegInit(UInt(sourceWidth.W), 0.U)
  head := Mux(io.gen, head + 1.U, head)

  val outstanding = RegInit(UInt((sourceWidth + 1).W), 0.U)
  io.inflight := (outstanding > 0.U) || io.gen

  val numSourceId = 1 << sourceWidth
  val row = new Bundle {
    val meta = getMetadataType
    val id = Valid(UInt(sourceWidth.W))
    val age = UInt(32.W) // New age field for debugging
  }
  // valid: in use, invalid: available
  // val occupancyTable = Mem(numSourceId, Valid(UInt(sourceWidth.W)))
  val occupancyTable = Mem(numSourceId, row)
  when(reset.asBool) {
    (0 until numSourceId).foreach { i =>
      occupancyTable(i).id.valid := false.B
      occupancyTable(i).age := 0.U // Reset age during reset
    }
  }
  val frees = (0 until numSourceId).map(!occupancyTable(_).id.valid)
  val lowestFree = PriorityEncoder(frees)
  val lowestFreeRow = occupancyTable(lowestFree)

  io.id.valid := (if (ignoreInUse) true.B else !lowestFreeRow.id.valid)
  io.id.bits := lowestFree
  when(io.gen && io.id.valid /* fire */ ) {
    occupancyTable(io.id.bits).id.valid := true.B // mark in use
    occupancyTable(
      io.id.bits
    ).age := 0.U // reset age upon issuing, double safety
    if (metadata.isDefined) {
      occupancyTable(io.id.bits).meta := io.meta
    }
  }

  // Increase age of all inflight IDs by 1, except for the one being reclaimed
  for (i <- 0 until numSourceId) {
    when(
      occupancyTable(
        i
      ).id.valid && (i.U =/= io.reclaim.bits || !io.reclaim.valid)
    ) {
      occupancyTable(i).age := occupancyTable(i).age + 1.U
    }
  }

  when(io.reclaim.valid) {
    assert(
      occupancyTable(io.reclaim.bits).id.valid === true.B,
      "tried to reclaim a non-used id"
    )
    occupancyTable(io.reclaim.bits).id.valid := false.B // mark freed
    occupancyTable(io.reclaim.bits).age := 0.U
  }

  io.peek := {
    if (metadata.isDefined) occupancyTable(io.reclaim.bits).meta else 0.U
  }

  when(io.gen && io.id.valid) {
    when(!io.reclaim.valid) {
      assert(outstanding < (1 << sourceWidth).U)
      outstanding := outstanding + 1.U
    }
  }.elsewhen(io.reclaim.valid) {
    assert(outstanding > 0.U)
    outstanding := outstanding - 1.U
  }

  // Debugging wires
  val ages = VecInit((0 until numSourceId).map(i => occupancyTable(i).age))
  val oldestIndex = PriorityEncoder(
    ages.map(a => a === ages.reduce((x, y) => Mux(x > y, x, y)))
  )
  val oldestIdInflight = Wire(UInt(sourceWidth.W))
  val oldestMetadata = Wire(getMetadataType)
  val oldestAge = Wire(UInt(32.W))

  oldestIdInflight := oldestIndex
  oldestMetadata := occupancyTable(oldestIndex).meta
  oldestAge := occupancyTable(oldestIndex).age
  assert(
    oldestAge <= 2000.U,
    "One id in the SourceGen is not released for long time, potential bug !"
  )

  dontTouch(oldestIdInflight)
  dontTouch(oldestMetadata)
  dontTouch(oldestAge)
  dontTouch(outstanding)

}
