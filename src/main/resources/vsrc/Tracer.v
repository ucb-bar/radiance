module TracerBlackBox #(
  parameter CLUSTER_ID = 0,
  parameter CORE_ID = 0,
  parameter ARCH_LEN = 32,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter LSU_LANES = 16,
  parameter REG_BITS = 8,
  parameter DMEM_TAG_BITS = 32,
  parameter DMEM_DATA_BITS = 32,
  parameter SMEM_TAG_BITS = 32,
  parameter SMEM_DATA_BITS = 32,
  localparam DMEM_SIZE_BITS = $clog2($clog2(DMEM_DATA_BITS / 8) + 1),
  localparam DMEM_MASK_BITS = (DMEM_DATA_BITS / 8),
  localparam SMEM_SIZE_BITS = $clog2($clog2(SMEM_DATA_BITS / 8) + 1),
  localparam SMEM_MASK_BITS = (SMEM_DATA_BITS / 8),
  localparam WARP_ID_BITS = $clog2(NUM_WARPS)
) (
  input clock,
  input reset,

  input  logic                            inst_valid,
  input  logic [ARCH_LEN-1:0]             inst_pc,
  input  logic [WARP_ID_BITS-1:0]         inst_warpId,
  input  logic [NUM_LANES-1:0]            inst_tmask,
  input  logic                            inst_regs_0_enable,
  input  logic [REG_BITS-1:0]             inst_regs_0_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] inst_regs_0_data,
  input  logic                            inst_regs_1_enable,
  input  logic [REG_BITS-1:0]             inst_regs_1_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] inst_regs_1_data,
  input  logic                            inst_regs_2_enable,
  input  logic [REG_BITS-1:0]             inst_regs_2_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] inst_regs_2_data,

  input  logic [LSU_LANES-1:0]            dmem_req_valid,
  input  logic [LSU_LANES-1:0]            dmem_req_bits_store,
  input  logic [(LSU_LANES*ARCH_LEN)-1:0] dmem_req_bits_address,
  input  logic [(LSU_LANES*DMEM_SIZE_BITS)-1:0] dmem_req_bits_size,
  input  logic [(LSU_LANES*DMEM_TAG_BITS)-1:0] dmem_req_bits_tag,
  input  logic [(LSU_LANES*DMEM_DATA_BITS)-1:0] dmem_req_bits_data,
  input  logic [(LSU_LANES*DMEM_MASK_BITS)-1:0] dmem_req_bits_mask,
  input  logic [LSU_LANES-1:0]            dmem_resp_valid,
  input  logic [(LSU_LANES*DMEM_TAG_BITS)-1:0] dmem_resp_bits_tag,
  input  logic [(LSU_LANES*DMEM_DATA_BITS)-1:0] dmem_resp_bits_data,

  input  logic [LSU_LANES-1:0]            smem_req_valid,
  input  logic [LSU_LANES-1:0]            smem_req_bits_store,
  input  logic [(LSU_LANES*ARCH_LEN)-1:0] smem_req_bits_address,
  input  logic [(LSU_LANES*SMEM_SIZE_BITS)-1:0] smem_req_bits_size,
  input  logic [(LSU_LANES*SMEM_TAG_BITS)-1:0] smem_req_bits_tag,
  input  logic [(LSU_LANES*SMEM_DATA_BITS)-1:0] smem_req_bits_data,
  input  logic [(LSU_LANES*SMEM_MASK_BITS)-1:0] smem_req_bits_mask,
  input  logic [LSU_LANES-1:0]            smem_resp_valid,
  input  logic [(LSU_LANES*SMEM_TAG_BITS)-1:0] smem_resp_bits_tag,
  input  logic [(LSU_LANES*SMEM_DATA_BITS)-1:0] smem_resp_bits_data
);
  `include "Cyclotron.vh"

  import "DPI-C" function cyclotron_trace(
    input bit  inst_valid,
    input int  inst_pc,
    input int  inst_warpId,
    input int  inst_tmask,
    input bit  inst_regs_0_enable,
    input byte inst_regs_0_address,
    input int  inst_regs_0_data[NUM_LANES],
    input bit  inst_regs_1_enable,
    input byte inst_regs_1_address,
    input int  inst_regs_1_data[NUM_LANES],
    input bit  inst_regs_2_enable,
    input byte inst_regs_2_address,
    input int  inst_regs_2_data[NUM_LANES],

    input bit  dmem_req_valid[LSU_LANES],
    input bit  dmem_req_bits_store[LSU_LANES],
    input int  dmem_req_bits_address[LSU_LANES],
    input byte dmem_req_bits_size[LSU_LANES],
    input int  dmem_req_bits_tag[LSU_LANES],
    input int  dmem_req_bits_data[LSU_LANES],
    input byte dmem_req_bits_mask[LSU_LANES],
    input bit  dmem_resp_valid[LSU_LANES],
    input int  dmem_resp_bits_tag[LSU_LANES],
    input int  dmem_resp_bits_data[LSU_LANES],

    input bit  smem_req_valid[LSU_LANES],
    input bit  smem_req_bits_store[LSU_LANES],
    input int  smem_req_bits_address[LSU_LANES],
    input byte smem_req_bits_size[LSU_LANES],
    input int  smem_req_bits_tag[LSU_LANES],
    input int  smem_req_bits_data[LSU_LANES],
    input byte smem_req_bits_mask[LSU_LANES],
    input bit  smem_resp_valid[LSU_LANES],
    input int  smem_resp_bits_tag[LSU_LANES],
    input int  smem_resp_bits_data[LSU_LANES]
  );

  // need to be in ascending order to match with C array memory layout
  int     __inst_regs_0_data [0:NUM_LANES-1];
  int     __inst_regs_1_data [0:NUM_LANES-1];
  int     __inst_regs_2_data [0:NUM_LANES-1];

  bit     __dmem_req_valid [0:LSU_LANES-1];
  bit     __dmem_req_bits_store [0:LSU_LANES-1];
  int     __dmem_req_bits_address [0:LSU_LANES-1];
  byte    __dmem_req_bits_size [0:LSU_LANES-1];
  int     __dmem_req_bits_tag [0:LSU_LANES-1];
  int     __dmem_req_bits_data [0:LSU_LANES-1];
  byte    __dmem_req_bits_mask [0:LSU_LANES-1];
  bit     __dmem_resp_valid [0:LSU_LANES-1];
  int     __dmem_resp_bits_tag [0:LSU_LANES-1];
  int     __dmem_resp_bits_data [0:LSU_LANES-1];

  bit     __smem_req_valid [0:LSU_LANES-1];
  bit     __smem_req_bits_store [0:LSU_LANES-1];
  int     __smem_req_bits_address [0:LSU_LANES-1];
  byte    __smem_req_bits_size [0:LSU_LANES-1];
  int     __smem_req_bits_tag [0:LSU_LANES-1];
  int     __smem_req_bits_data [0:LSU_LANES-1];
  byte    __smem_req_bits_mask [0:LSU_LANES-1];
  bit     __smem_resp_valid [0:LSU_LANES-1];
  int     __smem_resp_bits_tag [0:LSU_LANES-1];
  int     __smem_resp_bits_data [0:LSU_LANES-1];

  initial cyclotron_init_task();

  initial begin
    if (DMEM_TAG_BITS > 32) $fatal(1, "Tracer: DMEM_TAG_BITS > 32 not supported by DPI int");
    if (DMEM_DATA_BITS > 32) $fatal(1, "Tracer: DMEM_DATA_BITS > 32 not supported by DPI int");
    if (SMEM_TAG_BITS > 32) $fatal(1, "Tracer: SMEM_TAG_BITS > 32 not supported by DPI int");
    if (SMEM_DATA_BITS > 32) $fatal(1, "Tracer: SMEM_DATA_BITS > 32 not supported by DPI int");
  end

  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign __inst_regs_0_data[g] = inst_regs_0_data[ARCH_LEN*g +: ARCH_LEN];
      assign __inst_regs_1_data[g] = inst_regs_1_data[ARCH_LEN*g +: ARCH_LEN];
      assign __inst_regs_2_data[g] = inst_regs_2_data[ARCH_LEN*g +: ARCH_LEN];
    end
    for (g = 0; g < LSU_LANES; g = g + 1) begin
      assign __dmem_req_valid[g] = dmem_req_valid[g];
      assign __dmem_req_bits_store[g] = dmem_req_bits_store[g];
      assign __dmem_req_bits_address[g] = dmem_req_bits_address[ARCH_LEN*g +: ARCH_LEN];
      assign __dmem_req_bits_size[g] = dmem_req_bits_size[DMEM_SIZE_BITS*g +: DMEM_SIZE_BITS];
      assign __dmem_req_bits_tag[g] = dmem_req_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS];
      assign __dmem_req_bits_data[g] = dmem_req_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS];
      assign __dmem_req_bits_mask[g] = dmem_req_bits_mask[DMEM_MASK_BITS*g +: DMEM_MASK_BITS];
      assign __dmem_resp_valid[g] = dmem_resp_valid[g];
      assign __dmem_resp_bits_tag[g] = dmem_resp_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS];
      assign __dmem_resp_bits_data[g] = dmem_resp_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS];

      assign __smem_req_valid[g] = smem_req_valid[g];
      assign __smem_req_bits_store[g] = smem_req_bits_store[g];
      assign __smem_req_bits_address[g] = smem_req_bits_address[ARCH_LEN*g +: ARCH_LEN];
      assign __smem_req_bits_size[g] = smem_req_bits_size[SMEM_SIZE_BITS*g +: SMEM_SIZE_BITS];
      assign __smem_req_bits_tag[g] = smem_req_bits_tag[SMEM_TAG_BITS*g +: SMEM_TAG_BITS];
      assign __smem_req_bits_data[g] = smem_req_bits_data[SMEM_DATA_BITS*g +: SMEM_DATA_BITS];
      assign __smem_req_bits_mask[g] = smem_req_bits_mask[SMEM_MASK_BITS*g +: SMEM_MASK_BITS];
      assign __smem_resp_valid[g] = smem_resp_valid[g];
      assign __smem_resp_bits_tag[g] = smem_resp_bits_tag[SMEM_TAG_BITS*g +: SMEM_TAG_BITS];
      assign __smem_resp_bits_data[g] = smem_resp_bits_data[SMEM_DATA_BITS*g +: SMEM_DATA_BITS];
    end
  endgenerate

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      cyclotron_trace(
        inst_valid,
        inst_pc,
        inst_warpId,
        inst_tmask,
        inst_regs_0_enable,
        inst_regs_0_address,
        __inst_regs_0_data,
        inst_regs_1_enable,
        inst_regs_1_address,
        __inst_regs_1_data,
        inst_regs_2_enable,
        inst_regs_2_address,
        __inst_regs_2_data,
        __dmem_req_valid,
        __dmem_req_bits_store,
        __dmem_req_bits_address,
        __dmem_req_bits_size,
        __dmem_req_bits_tag,
        __dmem_req_bits_data,
        __dmem_req_bits_mask,
        __dmem_resp_valid,
        __dmem_resp_bits_tag,
        __dmem_resp_bits_data,
        __smem_req_valid,
        __smem_req_bits_store,
        __smem_req_bits_address,
        __smem_req_bits_size,
        __smem_req_bits_tag,
        __smem_req_bits_data,
        __smem_req_bits_mask,
        __smem_resp_valid,
        __smem_resp_bits_tag,
        __smem_resp_bits_data
      );
    end
  end

endmodule
