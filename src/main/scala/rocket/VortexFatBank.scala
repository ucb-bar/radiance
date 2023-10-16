//package freechips.rocketchip.rocket
package freechips.rocketchip.tilelink

import chisel3._
import chisel3.util._
import chisel3.experimental._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Parameters, Field}


// VortexTile has dmemNodes, imemNodes

//Param and Key are used during SoC Generation

case class VortexFatBankParam(wordSize: Int = 16, busWidthInBytes: Int = 8)
case object VortexFatBankKey extends Field[Option[VortexFatBankConfig]](None /*default*/)

case class VortexFatBankConfig(
    wordSize: Int,      //This is the read/write granularity of the L1 cache
    cacheLineSize: Int,
    coreTagWidth: Int,
) {
    def coreTagPlusSizeWidth: Int = {
        log2Ceil(wordSize) + coreTagWidth
    }
}

object defaultFatBankConfig extends VortexFatBankConfig(
    wordSize = 16,
    cacheLineSize = 16,
    coreTagWidth = 8,
)


class VortexFatBank (config: VortexFatBankConfig) (implicit p: Parameters) extends LazyModule {

    val clientParam = Seq(TLMasterPortParameters.v1(
        clients = Seq(
            TLMasterParameters.v1(
                name = "VortexFatBank",
                sourceId = IdRange(0, 1 << 14), // FIXME: magic number
                supportsProbe = TransferSizes(1, config.wordSize),
                supportsGet = TransferSizes(1, config.wordSize),
                supportsPutFull = TransferSizes(1, config.wordSize),
                supportsPutPartial = TransferSizes(1, config.wordSize)
            )
        )
    ))

    val managerParam = Seq(TLSlavePortParameters.v1(
        beatBytes = config.wordSize,
        managers = Seq(
            TLSlaveParameters.v1(
                address            = Seq(AddressSet(0x80000000L, 0xfffffff)), // 0x80000000 -> 0x90000000 are possible address tracer can emit
                regionType         = RegionType.IDEMPOTENT, // idk what this does
                executable         = false,
                supportsGet        = TransferSizes(1, config.wordSize),
                supportsPutPartial = TransferSizes(1, config.wordSize),
                supportsPutFull    = TransferSizes(1, config.wordSize),
                fifoId             = Some(0)
            )
        )
    ))

    val coalToVxCacheNode = TLManagerNode(managerParam)
    val vxCacheToL2Node = TLIdentityNode()
    val vxCacheFetchNode = TLClientNode(clientParam)
    
    //We need this widthWidget here, because whenever the fatBank is performing
    //read and write to Mem, it must have the illusion that dataWidth is as big as
    //as its cacheline size
    vxCacheToL2Node := TLWidthWidget(config.cacheLineSize) := vxCacheFetchNode
    lazy val module = new VortexFatBankImp(this, config);
}

class VortexFatBankImp (
    outer: VortexFatBank,
    config: VortexFatBankConfig
) extends LazyModuleImp(outer) {

    val vxCache = Module(new VX_cache(
        WORD_SIZE=config.wordSize, 
        CACHE_LINE_SIZE=config.cacheLineSize,
        CORE_TAG_WIDTH= config.coreTagPlusSizeWidth
        )
    );

    vxCache.io.clk := clock
    vxCache.io.reset := reset

    class WriteReqInfo extends Bundle {
        val id = UInt(32.W)
        val size = UInt(32.W)
    }
    

    //<FIXME> assuming this is never full
    val rcvWriteReqInfo = Module(new Queue((new WriteReqInfo).cloneType, 64, true, false))
    
    class ReadReqInfo(config: VortexFatBankConfig) extends Bundle {
        val size = UInt(log2Ceil(config.wordSize).W)
        val id   = UInt(config.coreTagWidth.W)
    }

    val readReqInfo = Wire(new ReadReqInfo(config))

    // Translate TL request from Coalescer to requests for VX_cache
    def TLReq2VXReq = {
        val (coalToBankBundle, _) = outer.coalToVxCacheNode.in.head
        // coal -> vxCache request on channel A
        val coalToBankA = coalToBankBundle.a;
        
        coalToBankA.ready := vxCache.io.core_req_ready
        vxCache.io.core_req_valid := coalToBankA.valid

        // read = 0, write = 1
        vxCache.io.core_req_rw     := !(coalToBankA.bits.opcode === TLMessages.Get)
        //4 is also hardcoded, it should be log2WordSize
        vxCache.io.core_req_addr   := coalToBankA.bits.address(31, 4)
        vxCache.io.core_req_byteen := coalToBankA.bits.mask
        vxCache.io.core_req_data   := coalToBankA.bits.data
        
        readReqInfo.id   := coalToBankA.bits.source
        readReqInfo.size := coalToBankA.bits.size
        vxCache.io.core_req_tag := readReqInfo.asTypeOf(vxCache.io.core_req_tag)
        
        
        // we ignore param, size, corrupt fields

        // vxCache -> coal response on channel D
        // ok ... this part is a little tricky, the downstream coalescer requires the L1 cache
        // to send ack and dataAck, this is how coalescer knows when an inflight ID has retired
        // if we don't send ack, the coalescer will run out of IDs, and can't generate new request

        // for read request, we send AckData when the FatBank has a valid output
        // for write request, we can immediate Ack on the next clock cycle (not the same clock cycle, otherwise critical path too long)
        // It's possible that on the same cycle, we need to do both "AckData" and "Ack"
        //    in this case, we always priorize "Ack", this makes the design easier

        //I think this just shows the flaws of Tilelink. CPU never waits for an Ack upon regular write request
        //the Core should unconditionally move forward after every regular write request

        val coalToBankD = coalToBankBundle.d;


        //<FIXME> currently assuming below buffer is never full
        rcvWriteReqInfo.io.enq.valid     := !(coalToBankA.bits.opcode === TLMessages.Get) && coalToBankA.valid && coalToBankA.ready
        rcvWriteReqInfo.io.enq.bits.id   := coalToBankA.bits.source
        rcvWriteReqInfo.io.enq.bits.size := coalToBankA.bits.size


        rcvWriteReqInfo.io.deq.ready := coalToBankD.ready
        
        //if we "need" to do Ack
        //we unconditionally set the vxCache.ready to be false, so it gets delayed
        vxCache.io.core_rsp_ready := Mux(
            rcvWriteReqInfo.io.deq.valid,
            false.B,
            coalToBankD.ready
        )

        coalToBankD.valid := Mux(
            rcvWriteReqInfo.io.deq.valid,
            true.B,
            vxCache.io.core_rsp_valid
        )

        coalToBankD.bits.source := Mux(
            rcvWriteReqInfo.io.deq.valid,
            rcvWriteReqInfo.io.deq.bits.id,
            vxCache.io.core_rsp_tag.asTypeOf(readReqInfo).id
        )

        coalToBankD.bits.opcode  := Mux(
            rcvWriteReqInfo.io.deq.valid,
            TLMessages.AccessAck,
            TLMessages.AccessAckData
        )

        coalToBankD.bits.size := Mux(
            rcvWriteReqInfo.io.deq.valid,
            rcvWriteReqInfo.io.deq.bits.size,
            vxCache.io.core_rsp_tag.asTypeOf(readReqInfo).size
        )

        coalToBankD.bits.param   := 0.U
        coalToBankD.bits.sink    := 0.U
        coalToBankD.bits.denied  := false.B
        coalToBankD.bits.corrupt := false.B

        coalToBankD.bits.data   := vxCache.io.core_rsp_data
    }


    //Using Hansung's Source Generator
    //Why do we need to do this, what is the issue ?
    //Tilelink requires all inflight Read and Write Request to have a unique source_ID
    //vx_cache can indeed guarantee that all active read operation has unique ID
    //However, since the cache is write_through, so it can't ensure unique ID for write operation
    //Therefore, we need our own internal source_ID generator for all write operation
    //
    //Now, we allocate id range: 0-15 for all write operation
    //                    and    16-> above for read operation
    val sourceGen = Module( new SourceGenerator(log2Ceil(16), ignoreInUse = false))


    // Translate VX_cache mem request to a TL request to be sent to L2
    def VXReq2TLReq = {
        val (vxCacheToL2Bundle, _) = outer.vxCacheFetchNode.out.head
        // vxCache -> L2 request on channel A
        val vxCacheToL2A = vxCacheToL2Bundle.a;
        

        //Read Operation is ready as long as downstream L2 is ready

        vxCache.io.mem_req_ready := vxCacheToL2A.ready

        vxCacheToL2A.valid := Mux(
            vxCache.io.mem_req_rw,
            vxCache.io.mem_req_valid && sourceGen.io.id.valid,
            vxCache.io.mem_req_valid
        )

        vxCacheToL2A.bits.opcode := Mux(
            vxCache.io.mem_req_rw, 
            Mux(vxCache.io.mem_req_byteen.andR, TLMessages.PutFullData, TLMessages.PutPartialData), 
            TLMessages.Get
        )

        vxCacheToL2A.bits.address := Cat(vxCache.io.mem_req_addr, 0.U(4.W))
        vxCacheToL2A.bits.mask    := Mux(
            vxCache.io.mem_req_rw, 
            vxCache.io.mem_req_byteen,
            0xFFFF.U
        )
        vxCacheToL2A.bits.data    := vxCache.io.mem_req_data

        
        vxCacheToL2A.bits.source  := Mux(
            vxCache.io.mem_req_rw,
            sourceGen.io.id.bits,
            vxCache.io.mem_req_tag + 16.U
        )
        //mark current source_id as in-use
        sourceGen.io.gen := vxCache.io.mem_req_rw && vxCacheToL2A.ready && vxCacheToL2A.valid

        vxCacheToL2A.bits.param   := 0.U
        vxCacheToL2A.bits.size    := 4.U
        vxCacheToL2A.bits.corrupt := false.B

        // we ignore param, size, corrupt fields

        // L2 -> vxCache response on channel D
        val vxCacheToL2D = vxCacheToL2Bundle.d;
        vxCacheToL2D.ready := vxCache.io.mem_rsp_ready

        vxCache.io.mem_rsp_valid := vxCacheToL2D.valid && vxCacheToL2D.bits.opcode === TLMessages.AccessAckData
        vxCache.io.mem_rsp_tag   := vxCacheToL2D.bits.source - 16.U // -16 for read resp, we can safely do this, since write-ack wouldn't pass through
        vxCache.io.mem_rsp_data := vxCacheToL2D.bits.data

        sourceGen.io.reclaim.valid := vxCacheToL2D.ready && vxCacheToL2D.valid && vxCacheToL2D.bits.opcode === TLMessages.AccessAck
        sourceGen.io.reclaim.bits := vxCacheToL2D.bits.source

    }

    TLReq2VXReq
    VXReq2TLReq

}

class VX_cache (
    CACHE_ID: Int = 0,
    CACHE_SIZE: Int = 16384,
    CACHE_LINE_SIZE: Int = 16,
    NUM_PORTS: Int = 1, 
    WORD_SIZE: Int = 16, // hack - one "word" is enough to satisfy all 4 warps after decoalescing.
    CREQ_SIZE: Int = 0,
    CRSQ_SIZE: Int = 2,
    MSHR_SIZE: Int = 8,
    MRSQ_SIZE: Int = 0,
    MREQ_SIZE: Int = 4,
    WRITE_ENABLE: Int = 1,
    CORE_TAG_WIDTH: Int = 10, // source ID ranges from 0 to 1 << 10, we need to allocate upper bits to save size
    CORE_TAG_ID_BITS: Int = 5, // no idea what this is, just match it with default L1 dcache
    BANK_ADDR_OFFSET: Int = 0,
    NC_ENABLE: Int = 0, //NC_ENABLE=1 means the cache becomes a passthrough
    WORD_ADDR_WIDTH: Int = 28, // 16 byte "word" = 4 bits
    MEM_TAG_WIDTH: Int = 14, // Elaborated value is also completely different from (32 - log2Ceil(CACHE_LINE_SIZE)). This should match with sourceIds on client node associated with this cache
    MEM_ADDR_WIDTH: Int = 28 // 16 byte cache line = 4 bits
) extends BlackBox (
    Map(
        "CACHE_ID" -> CACHE_ID,
        "NUM_REQS" -> 1, //Force NUM_REQS to be 1, we use their Cache as our individual Bank
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
        "NC_ENABLE" -> NC_ENABLE,
    )
) with HasBlackBoxResource {

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

        val core_rsp_valid = Output(Bool())  // 1 bit wide
        val core_rsp_tmask = Output(Bool())  // 1 bit wide, probably can ignore (check waveform)
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
    //addResource("/vsrc/vortex/hw/rtl/cache/VX_shared_mem.sv")
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
