module CyclotronLoadStoreUnitBlackBox #(
  parameter ARCH_LEN = 32,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter NUM_LSU_LANES = 16,
  parameter CLUSTER_ID_BITS = 1,
  parameter CORE_ID_BITS = 1,
  parameter WARP_ID_BITS = 3,
  parameter TOKEN_BITS = 9,
  parameter ADDRESS_SPACE_BITS = 1,
  parameter MEM_OP_BITS = 4,
  parameter PREG_BITS = 8,
  parameter PACKET_BITS = 1,
  parameter SOURCE_ID_BITS = 10,
  parameter PER_LANE_MASK_BITS = 4,
  parameter DEBUG_ID_BITS = 0,
  parameter DEBUG_ID_PORT_BITS = 1
) (
  input clock,
  input reset,

  input  logic [CLUSTER_ID_BITS-1:0] cluster_id,
  input  logic [CORE_ID_BITS-1:0]    core_id,

  input  logic [NUM_WARPS-1:0]                              coreReservations_req_valid,
  output logic [NUM_WARPS-1:0]                              coreReservations_req_ready,
  input  logic [(NUM_WARPS*ADDRESS_SPACE_BITS)-1:0]         coreReservations_req_bits_addressSpace,
  input  logic [(NUM_WARPS*MEM_OP_BITS)-1:0]                coreReservations_req_bits_op,
  input  logic [(NUM_WARPS*DEBUG_ID_PORT_BITS)-1:0]         coreReservations_req_bits_debugId,
  output logic [NUM_WARPS-1:0]                              coreReservations_resp_valid,
  output logic [(NUM_WARPS*TOKEN_BITS)-1:0]                 coreReservations_resp_bits_token,

  input  logic                                               coreReq_valid,
  output logic                                               coreReq_ready,
  input  logic [TOKEN_BITS-1:0]                              coreReq_bits_token,
  input  logic [MEM_OP_BITS-1:0]                             coreReq_bits_op,
  input  logic [NUM_LANES-1:0]                               coreReq_bits_tmask,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0]                    coreReq_bits_address,
  input  logic [ARCH_LEN-1:0]                                coreReq_bits_imm,
  input  logic [PREG_BITS-1:0]                               coreReq_bits_destReg,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0]                    coreReq_bits_storeData,

  input  logic                                               coreResp_ready,
  output logic                                               coreResp_valid,
  output logic [WARP_ID_BITS-1:0]                            coreResp_bits_warpId,
  output logic [PACKET_BITS-1:0]                             coreResp_bits_packet,
  output logic [NUM_LANES-1:0]                               coreResp_bits_tmask,
  output logic [PREG_BITS-1:0]                               coreResp_bits_destReg,
  output logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                coreResp_bits_writebackData,
  output logic [DEBUG_ID_PORT_BITS-1:0]                      coreResp_bits_debugId,

  output logic                                               globalMemReq_valid,
  input  logic                                               globalMemReq_ready,
  output logic [SOURCE_ID_BITS-1:0]                          globalMemReq_bits_tag,
  output logic [MEM_OP_BITS-1:0]                             globalMemReq_bits_op,
  output logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                globalMemReq_bits_address,
  output logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                globalMemReq_bits_data,
  output logic [(NUM_LSU_LANES*PER_LANE_MASK_BITS)-1:0]      globalMemReq_bits_mask,
  output logic [NUM_LSU_LANES-1:0]                           globalMemReq_bits_tmask,
  output logic                                               globalMemResp_ready,
  input  logic                                               globalMemResp_valid,
  input  logic [SOURCE_ID_BITS-1:0]                          globalMemResp_bits_tag,
  input  logic [NUM_LSU_LANES-1:0]                           globalMemResp_bits_valid,
  input  logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                globalMemResp_bits_data,

  output logic                                               shmemReq_valid,
  input  logic                                               shmemReq_ready,
  output logic [SOURCE_ID_BITS-1:0]                          shmemReq_bits_tag,
  output logic [MEM_OP_BITS-1:0]                             shmemReq_bits_op,
  output logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                shmemReq_bits_address,
  output logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                shmemReq_bits_data,
  output logic [(NUM_LSU_LANES*PER_LANE_MASK_BITS)-1:0]      shmemReq_bits_mask,
  output logic [NUM_LSU_LANES-1:0]                           shmemReq_bits_tmask,
  output logic                                               shmemResp_ready,
  input  logic                                               shmemResp_valid,
  input  logic [SOURCE_ID_BITS-1:0]                          shmemResp_bits_tag,
  input  logic [NUM_LSU_LANES-1:0]                           shmemResp_bits_valid,
  input  logic [(NUM_LSU_LANES*ARCH_LEN)-1:0]                shmemResp_bits_data,

  output logic                                               sharedQueuesEmpty,
  output logic                                               globalQueuesEmpty
);
  `include "Cyclotron.vh"

  localparam RES_ADDR_SPACE_BITS = NUM_WARPS * ADDRESS_SPACE_BITS;
  localparam RES_OP_BITS = NUM_WARPS * MEM_OP_BITS;
  localparam RES_DEBUG_ID_BITS = NUM_WARPS * DEBUG_ID_PORT_BITS;
  localparam RES_TOKEN_BITS = NUM_WARPS * TOKEN_BITS;
  localparam CORE_ADDR_BITS = NUM_LANES * ARCH_LEN;
  localparam CORE_DATA_BITS = NUM_LANES * ARCH_LEN;
  localparam MEM_ADDR_BITS = NUM_LSU_LANES * ARCH_LEN;
  localparam MEM_DATA_BITS = NUM_LSU_LANES * ARCH_LEN;
  localparam MEM_MASK_BITS = NUM_LSU_LANES * PER_LANE_MASK_BITS;

  import "DPI-C" function void cyclotron_lsu_init(
    input bit [CLUSTER_ID_BITS-1:0] cluster_id,
    input bit [CORE_ID_BITS-1:0] core_id,
    input int arch_len,
    input int num_warps,
    input int num_lanes,
    input int num_lsu_lanes,
    input int cluster_id_bits,
    input int core_id_bits,
    input int warp_id_bits,
    input int token_bits,
    input int address_space_bits,
    input int mem_op_bits,
    input int preg_bits,
    input int packet_bits,
    input int source_id_bits,
    input int per_lane_mask_bits,
    input int debug_id_bits,
    input int debug_id_port_bits
  );

  import "DPI-C" function void cyclotron_lsu_reset(
    input bit [CLUSTER_ID_BITS-1:0] cluster_id,
    input bit [CORE_ID_BITS-1:0] core_id
  );
  import "DPI-C" function void cyclotron_lsu_eval(
    input  bit [CLUSTER_ID_BITS-1:0]      cluster_id,
    input  bit [CORE_ID_BITS-1:0]         core_id,
    input  bit [NUM_WARPS-1:0]            coreReservations_req_valid,
    input  bit [RES_ADDR_SPACE_BITS-1:0]  coreReservations_req_bits_addressSpace,
    input  bit [RES_OP_BITS-1:0]          coreReservations_req_bits_op,
    input  bit [RES_DEBUG_ID_BITS-1:0]    coreReservations_req_bits_debugId,
    input  bit                            coreReq_valid,
    input  bit [TOKEN_BITS-1:0]           coreReq_bits_token,
    input  bit [MEM_OP_BITS-1:0]          coreReq_bits_op,
    input  bit [NUM_LANES-1:0]            coreReq_bits_tmask,
    input  bit [CORE_ADDR_BITS-1:0]       coreReq_bits_address,
    input  bit [ARCH_LEN-1:0]             coreReq_bits_imm,
    input  bit [PREG_BITS-1:0]            coreReq_bits_destReg,
    input  bit [CORE_DATA_BITS-1:0]       coreReq_bits_storeData,
    input  bit                            coreResp_ready,
    input  bit                            globalMemReq_ready,
    input  bit                            globalMemResp_valid,
    input  bit [SOURCE_ID_BITS-1:0]       globalMemResp_bits_tag,
    input  bit [NUM_LSU_LANES-1:0]        globalMemResp_bits_valid,
    input  bit [MEM_DATA_BITS-1:0]        globalMemResp_bits_data,
    input  bit                            shmemReq_ready,
    input  bit                            shmemResp_valid,
    input  bit [SOURCE_ID_BITS-1:0]       shmemResp_bits_tag,
    input  bit [NUM_LSU_LANES-1:0]        shmemResp_bits_valid,
    input  bit [MEM_DATA_BITS-1:0]        shmemResp_bits_data,
    output bit [NUM_WARPS-1:0]            coreReservations_req_ready,
    output bit [NUM_WARPS-1:0]            coreReservations_resp_valid,
    output bit [RES_TOKEN_BITS-1:0]       coreReservations_resp_bits_token,
    output bit                            coreReq_ready,
    output bit                            coreResp_valid,
    output bit [WARP_ID_BITS-1:0]         coreResp_bits_warpId,
    output bit [PACKET_BITS-1:0]          coreResp_bits_packet,
    output bit [NUM_LANES-1:0]            coreResp_bits_tmask,
    output bit [PREG_BITS-1:0]            coreResp_bits_destReg,
    output bit [MEM_DATA_BITS-1:0]        coreResp_bits_writebackData,
    output bit [DEBUG_ID_PORT_BITS-1:0]   coreResp_bits_debugId,
    output bit                            globalMemReq_valid,
    output bit [SOURCE_ID_BITS-1:0]       globalMemReq_bits_tag,
    output bit [MEM_OP_BITS-1:0]          globalMemReq_bits_op,
    output bit [MEM_ADDR_BITS-1:0]        globalMemReq_bits_address,
    output bit [MEM_DATA_BITS-1:0]        globalMemReq_bits_data,
    output bit [MEM_MASK_BITS-1:0]        globalMemReq_bits_mask,
    output bit [NUM_LSU_LANES-1:0]        globalMemReq_bits_tmask,
    output bit                            globalMemResp_ready,
    output bit                            shmemReq_valid,
    output bit [SOURCE_ID_BITS-1:0]       shmemReq_bits_tag,
    output bit [MEM_OP_BITS-1:0]          shmemReq_bits_op,
    output bit [MEM_ADDR_BITS-1:0]        shmemReq_bits_address,
    output bit [MEM_DATA_BITS-1:0]        shmemReq_bits_data,
    output bit [MEM_MASK_BITS-1:0]        shmemReq_bits_mask,
    output bit [NUM_LSU_LANES-1:0]        shmemReq_bits_tmask,
    output bit                            shmemResp_ready,
    output bit                            sharedQueuesEmpty,
    output bit                            globalQueuesEmpty
  );
  import "DPI-C" function void cyclotron_lsu_commit(
    input bit [CLUSTER_ID_BITS-1:0]      cluster_id,
    input bit [CORE_ID_BITS-1:0]         core_id,
    input bit [NUM_WARPS-1:0]            coreReservations_req_valid,
    input bit [RES_ADDR_SPACE_BITS-1:0]  coreReservations_req_bits_addressSpace,
    input bit [RES_OP_BITS-1:0]          coreReservations_req_bits_op,
    input bit [RES_DEBUG_ID_BITS-1:0]    coreReservations_req_bits_debugId,
    input bit                            coreReq_valid,
    input bit [TOKEN_BITS-1:0]           coreReq_bits_token,
    input bit [MEM_OP_BITS-1:0]          coreReq_bits_op,
    input bit [NUM_LANES-1:0]            coreReq_bits_tmask,
    input bit [CORE_ADDR_BITS-1:0]       coreReq_bits_address,
    input bit [ARCH_LEN-1:0]             coreReq_bits_imm,
    input bit [PREG_BITS-1:0]            coreReq_bits_destReg,
    input bit [CORE_DATA_BITS-1:0]       coreReq_bits_storeData,
    input bit                            coreResp_ready,
    input bit                            globalMemReq_ready,
    input bit                            globalMemResp_valid,
    input bit [SOURCE_ID_BITS-1:0]       globalMemResp_bits_tag,
    input bit [NUM_LSU_LANES-1:0]        globalMemResp_bits_valid,
    input bit [MEM_DATA_BITS-1:0]        globalMemResp_bits_data,
    input bit                            shmemReq_ready,
    input bit                            shmemResp_valid,
    input bit [SOURCE_ID_BITS-1:0]       shmemResp_bits_tag,
    input bit [NUM_LSU_LANES-1:0]        shmemResp_bits_valid,
    input bit [MEM_DATA_BITS-1:0]        shmemResp_bits_data,
    input bit [NUM_WARPS-1:0]            coreReservations_req_ready,
    input bit [NUM_WARPS-1:0]            coreReservations_resp_valid,
    input bit [RES_TOKEN_BITS-1:0]       coreReservations_resp_bits_token,
    input bit                            coreReq_ready,
    input bit                            coreResp_valid,
    input bit [WARP_ID_BITS-1:0]         coreResp_bits_warpId,
    input bit [PACKET_BITS-1:0]          coreResp_bits_packet,
    input bit [NUM_LANES-1:0]            coreResp_bits_tmask,
    input bit [PREG_BITS-1:0]            coreResp_bits_destReg,
    input bit [MEM_DATA_BITS-1:0]        coreResp_bits_writebackData,
    input bit [DEBUG_ID_PORT_BITS-1:0]   coreResp_bits_debugId,
    input bit                            globalMemReq_valid,
    input bit [SOURCE_ID_BITS-1:0]       globalMemReq_bits_tag,
    input bit [MEM_OP_BITS-1:0]          globalMemReq_bits_op,
    input bit [MEM_ADDR_BITS-1:0]        globalMemReq_bits_address,
    input bit [MEM_DATA_BITS-1:0]        globalMemReq_bits_data,
    input bit [MEM_MASK_BITS-1:0]        globalMemReq_bits_mask,
    input bit [NUM_LSU_LANES-1:0]        globalMemReq_bits_tmask,
    input bit                            globalMemResp_ready,
    input bit                            shmemReq_valid,
    input bit [SOURCE_ID_BITS-1:0]       shmemReq_bits_tag,
    input bit [MEM_OP_BITS-1:0]          shmemReq_bits_op,
    input bit [MEM_ADDR_BITS-1:0]        shmemReq_bits_address,
    input bit [MEM_DATA_BITS-1:0]        shmemReq_bits_data,
    input bit [MEM_MASK_BITS-1:0]        shmemReq_bits_mask,
    input bit [NUM_LSU_LANES-1:0]        shmemReq_bits_tmask,
    input bit                            shmemResp_ready,
    input bit                            sharedQueuesEmpty,
    input bit                            globalQueuesEmpty
  );

  initial begin
    cyclotron_init_task();
    cyclotron_lsu_init(
      cluster_id,
      core_id,
      ARCH_LEN,
      NUM_WARPS,
      NUM_LANES,
      NUM_LSU_LANES,
      CLUSTER_ID_BITS,
      CORE_ID_BITS,
      WARP_ID_BITS,
      TOKEN_BITS,
      ADDRESS_SPACE_BITS,
      MEM_OP_BITS,
      PREG_BITS,
      PACKET_BITS,
      SOURCE_ID_BITS,
      PER_LANE_MASK_BITS,
      DEBUG_ID_BITS,
      DEBUG_ID_PORT_BITS
    );
  end

  always_comb begin
    coreReservations_req_ready = '0;
    coreReservations_resp_valid = '0;
    coreReservations_resp_bits_token = '0;
    coreReq_ready = '0;
    coreResp_valid = '0;
    coreResp_bits_warpId = '0;
    coreResp_bits_packet = '0;
    coreResp_bits_tmask = '0;
    coreResp_bits_destReg = '0;
    coreResp_bits_writebackData = '0;
    coreResp_bits_debugId = '0;
    globalMemReq_valid = '0;
    globalMemReq_bits_tag = '0;
    globalMemReq_bits_op = '0;
    globalMemReq_bits_address = '0;
    globalMemReq_bits_data = '0;
    globalMemReq_bits_mask = '0;
    globalMemReq_bits_tmask = '0;
    globalMemResp_ready = '0;
    shmemReq_valid = '0;
    shmemReq_bits_tag = '0;
    shmemReq_bits_op = '0;
    shmemReq_bits_address = '0;
    shmemReq_bits_data = '0;
    shmemReq_bits_mask = '0;
    shmemReq_bits_tmask = '0;
    shmemResp_ready = '0;
    sharedQueuesEmpty = '0;
    globalQueuesEmpty = '0;

    if (!reset) begin
      cyclotron_lsu_eval(
        cluster_id,
        core_id,
        coreReservations_req_valid,
        coreReservations_req_bits_addressSpace,
        coreReservations_req_bits_op,
        coreReservations_req_bits_debugId,
        coreReq_valid,
        coreReq_bits_token,
        coreReq_bits_op,
        coreReq_bits_tmask,
        coreReq_bits_address,
        coreReq_bits_imm,
        coreReq_bits_destReg,
        coreReq_bits_storeData,
        coreResp_ready,
        globalMemReq_ready,
        globalMemResp_valid,
        globalMemResp_bits_tag,
        globalMemResp_bits_valid,
        globalMemResp_bits_data,
        shmemReq_ready,
        shmemResp_valid,
        shmemResp_bits_tag,
        shmemResp_bits_valid,
        shmemResp_bits_data,
        coreReservations_req_ready,
        coreReservations_resp_valid,
        coreReservations_resp_bits_token,
        coreReq_ready,
        coreResp_valid,
        coreResp_bits_warpId,
        coreResp_bits_packet,
        coreResp_bits_tmask,
        coreResp_bits_destReg,
        coreResp_bits_writebackData,
        coreResp_bits_debugId,
        globalMemReq_valid,
        globalMemReq_bits_tag,
        globalMemReq_bits_op,
        globalMemReq_bits_address,
        globalMemReq_bits_data,
        globalMemReq_bits_mask,
        globalMemReq_bits_tmask,
        globalMemResp_ready,
        shmemReq_valid,
        shmemReq_bits_tag,
        shmemReq_bits_op,
        shmemReq_bits_address,
        shmemReq_bits_data,
        shmemReq_bits_mask,
        shmemReq_bits_tmask,
        shmemResp_ready,
        sharedQueuesEmpty,
        globalQueuesEmpty
      );
    end
  end

  always_ff @(posedge clock) begin
    if (reset) begin
      cyclotron_lsu_reset(cluster_id, core_id);
    end else begin
      cyclotron_lsu_commit(
        cluster_id,
        core_id,
        coreReservations_req_valid,
        coreReservations_req_bits_addressSpace,
        coreReservations_req_bits_op,
        coreReservations_req_bits_debugId,
        coreReq_valid,
        coreReq_bits_token,
        coreReq_bits_op,
        coreReq_bits_tmask,
        coreReq_bits_address,
        coreReq_bits_imm,
        coreReq_bits_destReg,
        coreReq_bits_storeData,
        coreResp_ready,
        globalMemReq_ready,
        globalMemResp_valid,
        globalMemResp_bits_tag,
        globalMemResp_bits_valid,
        globalMemResp_bits_data,
        shmemReq_ready,
        shmemResp_valid,
        shmemResp_bits_tag,
        shmemResp_bits_valid,
        shmemResp_bits_data,
        coreReservations_req_ready,
        coreReservations_resp_valid,
        coreReservations_resp_bits_token,
        coreReq_ready,
        coreResp_valid,
        coreResp_bits_warpId,
        coreResp_bits_packet,
        coreResp_bits_tmask,
        coreResp_bits_destReg,
        coreResp_bits_writebackData,
        coreResp_bits_debugId,
        globalMemReq_valid,
        globalMemReq_bits_tag,
        globalMemReq_bits_op,
        globalMemReq_bits_address,
        globalMemReq_bits_data,
        globalMemReq_bits_mask,
        globalMemReq_bits_tmask,
        globalMemResp_ready,
        shmemReq_valid,
        shmemReq_bits_tag,
        shmemReq_bits_op,
        shmemReq_bits_address,
        shmemReq_bits_data,
        shmemReq_bits_mask,
        shmemReq_bits_tmask,
        shmemResp_ready,
        sharedQueuesEmpty,
        globalQueuesEmpty
      );
    end
  end

endmodule
