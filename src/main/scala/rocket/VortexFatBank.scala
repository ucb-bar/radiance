package rocket

import chisel3._
import chisel3.util._
import chisel3.experimental._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

// VortexTile has dmemNodes, imemNodes

class VortexFatBank (

) (implicit p: Parameters) extends LazyModule {

    val clientParam = Seq(TLMasterPortParameters.v1(
        clients = Seq(
            TLMasterParameters.v1(
                name = "VortexFatBank",
                sourceId = IdRange(0, 1 << 14), // FIXME: magic number
                supportsProbe = TransferSizes(1, 16),
                supportsGet = TransferSizes(1, 16),
                supportsPutFull = TransferSizes(1, 16),
                supportsPutPartial = TransferSizes(1, 16)
            )
        )
    ))

    val managerParam = Seq(TLSlavePortParameters.v1(
        beatBytes = 16,
        managers = Seq(
            TLSlaveParameters.v1(
                address            = Seq(AddressSet(0x0, 0xffffff)), // intercept all requests (it does not like all F for some reason)
                regionType         = RegionType.IDEMPOTENT, // idk what this does
                executable         = false,
                supportsGet        = TransferSizes(1, 16),
                supportsPutPartial = TransferSizes(1, 16),
                supportsPutFull    = TransferSizes(1, 16),
                fifoId             = Some(0)
            )
        )
    ))

    val coalToBankNode = TLManagerNode(managerParam)
    val bankToL2Node = TLClientNode(clientParam)
    lazy val module = new VortexFatBankImp(this);
}

class VortexFatBankImp (
    outer: VortexFatBank
) extends LazyModuleImp(outer) {
    val bank = Module(new VX_Cache());

    bank.io.clk := clock
    bank.io.reset := reset

    // Translate TL request from Coalescer to requests for VX_Cache
    def TLReq2VXReq = {
        val (coalToBankBundle, _) = outer.coalToBankNode.in.head
        // coal -> bank request on channel A
        val coalToBankA = coalToBankBundle.a;
        
        coalToBankA.ready := bank.io.core_req_ready
        bank.io.core_req_valid := coalToBankA.valid

        // read = 0, write = 1
        bank.io.core_req_rw     := !(coalToBankA.bits.opcode === TLMessages.Get)
        bank.io.core_req_addr   := coalToBankA.bits.address(31, 4)
        bank.io.core_req_byteen := coalToBankA.bits.mask
        bank.io.core_req_data   := coalToBankA.bits.data
        bank.io.core_req_tag    := coalToBankA.bits.source

        // we ignore param, size, corrupt fields

        // bank -> coal response on channel D
        val coalToBankD = coalToBankBundle.d;

        bank.io.core_rsp_ready := coalToBankD.ready
        coalToBankD.valid := bank.io.core_rsp_valid

        // Cache is not TL compliant since we don't generate AccessAcks on successful writes
        // but VortexCore is not expecting them anyways
        coalToBankD.bits.opcode  := TLMessages.AccessAckData
        coalToBankD.bits.param   := 0.U
        coalToBankD.bits.size    := 4.U
        coalToBankD.bits.sink    := 0.U
        coalToBankD.bits.denied  := false.B
        coalToBankD.bits.corrupt := false.B

        coalToBankD.bits.data   := bank.io.core_rsp_data
        coalToBankD.bits.source := bank.io.core_rsp_tag
    }

    // Translate VX_Cache mem request to a TL request to be sent to L2
    def VXReq2TLReq = {
        val (bankToL2Bundle, _) = outer.bankToL2Node.out.head
        // bank -> L2 request on channel A
        val bankToL2A = bankToL2Bundle.a;
        

        bank.io.mem_req_ready := bankToL2A.ready
        bankToL2A.valid := bank.io.mem_req_valid 

        bankToL2A.bits.opcode := Mux(
            bank.io.mem_req_rw, 
            Mux(bank.io.mem_req_byteen.andR, TLMessages.PutFullData, TLMessages.PutPartialData), 
            TLMessages.Get
        )
        bankToL2A.bits.address := Cat(bank.io.mem_req_addr, 0.U(4.W))
        bankToL2A.bits.mask    := bank.io.mem_req_byteen
        bankToL2A.bits.data    := bank.io.mem_req_data
        bankToL2A.bits.source  := bank.io.mem_req_tag

        bankToL2A.bits.param   := 0.U
        bankToL2A.bits.size    := 4.U
        bankToL2A.bits.corrupt := false.B

        // we ignore param, size, corrupt fields

        // L2 -> bank response on channel D
        val bankToL2D = bankToL2Bundle.d;

        bankToL2D.ready := bank.io.mem_rsp_ready
        bank.io.mem_rsp_valid := (bankToL2D.valid && (bankToL2D.bits.opcode === TLMessages.AccessAckData)) // need to ignore AccessAcks

        bank.io.mem_rsp_tag  := bankToL2D.bits.source
        bank.io.mem_rsp_data := bankToL2D.bits.data

    }

    TLReq2VXReq
    VXReq2TLReq

}

class VX_Cache (
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
    CORE_TAG_WIDTH: Int = 10, // source ID ranges from 0 to 1 << 10
    CORE_TAG_ID_BITS: Int = 5, // no idea what this is, just match it with default L1 dcache
    BANK_ADDR_OFFSET: Int = 0,
    NC_ENABLE: Int = 1, // Unsure what this does, but it's elaborated as 1 in default L1 setup so hopefully this is ok
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

    addResource("/vsrc/vortex/hw/rtl/cache/VX_cache_define.vh")
    // unused addResource("/vsrc/vortex/hw/rtl/libs/VX_mux.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_lzc.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_fifo_queue.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_scan.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_find_first.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_multiplier.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_bits_remove.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_pipe_register.sv")
    // unused addResource("/vsrc/vortex/hw/rtl/libs/VX_onehot_mux.sv")
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
    // unused addResource("/vsrc/vortex/hw/rtl/libs/VX_divider.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_dp_ram.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_axi_adapter.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_elastic_buffer.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_rr_arbiter.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_stream_arbiter.sv")
    // unused addResource("/vsrc/vortex/hw/rtl/libs/VX_bypass_buffer.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_sp_ram.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_stream_demux.sv")
    
    // unused addResource("/vsrc/vortex/hw/rtl/libs/VX_index_queue.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_serial_div.sv")
    addResource("/vsrc/vortex/hw/rtl/libs/VX_fair_arbiter.sv")

    addResource("/vsrc/vortex/hw/rtl/VX_define.vh")
    addResource("/vsrc/vortex/hw/VX_config.h")

    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_icache_rsp_if.sv")
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_dcache_req_if.sv")

    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_cache_if.sv")
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_perf_memsys_if.sv")
 
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_dcache_rsp_if.sv")
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_icache_req_if.sv")
 
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_mem_rsp_if.sv")
    addResource("/vsrc/vortex/hw/rtl/interfaces/VX_mem_req_if.sv")
    
    addResource("/vsrc/vortex/hw/rtl/cache/VX_shared_mem.sv")
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
