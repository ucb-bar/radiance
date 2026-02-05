module CyclotronTile #(
  parameter ARCH_LEN = 32,
  parameter INST_BITS = 64,
  parameter IMEM_TAG_BITS = 32,
  parameter DMEM_DATA_BITS = 32,
  parameter DMEM_TAG_BITS = 32,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 9,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter CSR_IMM_BITS = 8,
  parameter PRED_BITS = 4,
  localparam DMEM_SIZE_BITS = $clog2($clog2(DMEM_DATA_BITS / 8) + 1),
  localparam DMEM_MASK_BITS = (DMEM_DATA_BITS / 8),
  localparam OPNOEXT_BITS = 7,
  localparam OPEXT_BITS = 2,
  localparam WARP_ID_BITS = $clog2(NUM_WARPS),
  localparam WARP_COUNT_BITS = $clog2(NUM_WARPS+1)
) (
  input clock,
  input reset,

  output logic                             imem_req_valid,
  input  logic                             imem_req_ready,
  output logic [ARCH_LEN-1:0]              imem_req_bits_address,
  output logic [IMEM_TAG_BITS-1:0]         imem_req_bits_tag,
  output logic                             imem_resp_ready,
  input  logic                             imem_resp_valid,
  input  logic [IMEM_TAG_BITS-1:0]         imem_resp_bits_tag,
  input  logic [INST_BITS-1:0]             imem_resp_bits_data,

  output logic [NUM_LANES-1:0]                  dmem_req_valid,
  input  logic [NUM_LANES-1:0]                  dmem_req_ready,
  output logic [NUM_LANES-1:0]                  dmem_req_bits_store,
  output logic [(NUM_LANES*DMEM_TAG_BITS)-1:0]  dmem_req_bits_tag,
  output logic [(NUM_LANES*ARCH_LEN)-1:0]       dmem_req_bits_address,
  output logic [(NUM_LANES*DMEM_SIZE_BITS)-1:0] dmem_req_bits_size,
  output logic [(NUM_LANES*DMEM_DATA_BITS)-1:0] dmem_req_bits_data,
  output logic [(NUM_LANES*DMEM_MASK_BITS)-1:0] dmem_req_bits_mask,
  output logic [NUM_LANES-1:0]                  dmem_resp_ready,
  input  logic [NUM_LANES-1:0]                  dmem_resp_valid,
  input  logic [(NUM_LANES*DMEM_TAG_BITS)-1:0]  dmem_resp_bits_tag,
  input  logic [(NUM_LANES*DMEM_DATA_BITS)-1:0] dmem_resp_bits_data,
  output logic finished
);
  `include "Cyclotron.vh"

  import "DPI-C" function void cyclotron_tile_tick(
    output bit     imem_req_valid,
    input  bit     imem_req_ready,
    output int     imem_req_bits_address,
    output longint imem_req_bits_tag,
    output bit     imem_resp_ready,
    input  bit     imem_resp_valid,
    input  longint imem_resp_bits_tag,
    input  longint imem_resp_bits_data,
    output bit     dmem_req_valid[NUM_LANES],
    input  bit     dmem_req_ready[NUM_LANES],
    output bit     dmem_req_bits_store[NUM_LANES],
    output int     dmem_req_bits_address[NUM_LANES],
    output byte    dmem_req_bits_size[NUM_LANES],
    output int     dmem_req_bits_tag[NUM_LANES],
    output int     dmem_req_bits_data[NUM_LANES],
    output byte    dmem_req_bits_mask[NUM_LANES],
    output bit     dmem_resp_ready[NUM_LANES],
    input  bit     dmem_resp_valid[NUM_LANES],
    input  int     dmem_resp_bits_tag[NUM_LANES],
    input  int     dmem_resp_bits_data[NUM_LANES],
    output bit     finished
  );

  initial cyclotron_init_task();

  bit     __out_imem_req_ready;
  bit     __out_imem_resp_valid;
  longint __out_imem_resp_bits_tag;
  longint __out_imem_resp_bits_data;

  bit     __in_imem_req_valid;
  int     __in_imem_req_bits_address;
  longint __in_imem_req_bits_tag;
  bit     __in_imem_resp_ready;
  bit     __in_imem_req_valid_next;
  int     __in_imem_req_bits_address_next;
  longint __in_imem_req_bits_tag_next;
  bit     __in_imem_resp_ready_next;

  bit __in_finished;
  bit __in_finished_next;

  bit  __out_dmem_req_ready [0:NUM_LANES-1];
  bit  __out_dmem_resp_valid[0:NUM_LANES-1];
  int  __out_dmem_resp_tag  [0:NUM_LANES-1];
  int  __out_dmem_resp_data [0:NUM_LANES-1];

  bit  __in_dmem_req_valid    [0:NUM_LANES-1];
  bit  __in_dmem_req_store    [0:NUM_LANES-1];
  int  __in_dmem_req_address  [0:NUM_LANES-1];
  byte __in_dmem_req_size     [0:NUM_LANES-1];
  int  __in_dmem_req_tag      [0:NUM_LANES-1];
  int  __in_dmem_req_data     [0:NUM_LANES-1];
  byte __in_dmem_req_mask     [0:NUM_LANES-1];
  bit  __in_dmem_resp_ready   [0:NUM_LANES-1];

  bit  __in_dmem_req_valid_next   [0:NUM_LANES-1];
  bit  __in_dmem_req_store_next   [0:NUM_LANES-1];
  int  __in_dmem_req_address_next [0:NUM_LANES-1];
  byte __in_dmem_req_size_next    [0:NUM_LANES-1];
  int  __in_dmem_req_tag_next     [0:NUM_LANES-1];
  int  __in_dmem_req_data_next    [0:NUM_LANES-1];
  byte __in_dmem_req_mask_next    [0:NUM_LANES-1];
  bit  __in_dmem_resp_ready_next  [0:NUM_LANES-1];

  assign __out_imem_req_ready = imem_req_ready;
  assign __out_imem_resp_valid = imem_resp_valid;
  assign __out_imem_resp_bits_tag = imem_resp_bits_tag;
  assign __out_imem_resp_bits_data = imem_resp_bits_data;

  assign imem_req_valid = __in_imem_req_valid;
  assign imem_req_bits_address = __in_imem_req_bits_address[ARCH_LEN-1:0];
  assign imem_req_bits_tag = __in_imem_req_bits_tag[IMEM_TAG_BITS-1:0];
  assign imem_resp_ready = __in_imem_resp_ready;

  assign finished = __in_finished;

  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign __out_dmem_req_ready[g] = dmem_req_ready[g];
      assign __out_dmem_resp_valid[g] = dmem_resp_valid[g];
      assign __out_dmem_resp_tag[g] = dmem_resp_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS];
      assign __out_dmem_resp_data[g] = dmem_resp_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS];

      assign dmem_req_valid[g] = __in_dmem_req_valid[g];
      assign dmem_req_bits_store[g] = __in_dmem_req_store[g];
      assign dmem_req_bits_address[ARCH_LEN*g +: ARCH_LEN] =
        __in_dmem_req_address[g][ARCH_LEN-1:0];
      assign dmem_req_bits_size[DMEM_SIZE_BITS*g +: DMEM_SIZE_BITS] =
        __in_dmem_req_size[g][DMEM_SIZE_BITS-1:0];
      assign dmem_req_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS] =
        __in_dmem_req_tag[g][DMEM_TAG_BITS-1:0];
      assign dmem_req_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS] =
        __in_dmem_req_data[g][DMEM_DATA_BITS-1:0];
      assign dmem_req_bits_mask[DMEM_MASK_BITS*g +: DMEM_MASK_BITS] =
        __in_dmem_req_mask[g][DMEM_MASK_BITS-1:0];
      assign dmem_resp_ready[g] = __in_dmem_resp_ready[g];
    end
  endgenerate

  always @(posedge clock) begin
    if (reset) begin
      __in_imem_req_valid <= '0;
      __in_imem_req_bits_address <= '0;
      __in_imem_req_bits_tag <= '0;
      __in_imem_resp_ready <= '0;
      __in_finished <= '0;
      for (integer i = 0; i < NUM_LANES; i = i + 1) begin
        __in_dmem_req_valid[i] <= '0;
        __in_dmem_req_store[i] <= '0;
        __in_dmem_req_address[i] <= '0;
        __in_dmem_req_size[i] <= '0;
        __in_dmem_req_tag[i] <= '0;
        __in_dmem_req_data[i] <= '0;
        __in_dmem_req_mask[i] <= '0;
        __in_dmem_resp_ready[i] <= '0;
      end
    end else begin
      __in_imem_req_valid_next = __in_imem_req_valid;
      __in_imem_req_bits_address_next = __in_imem_req_bits_address;
      __in_imem_req_bits_tag_next = __in_imem_req_bits_tag;
      __in_imem_resp_ready_next = __in_imem_resp_ready;
      __in_finished_next = __in_finished;
      for (integer i = 0; i < NUM_LANES; i = i + 1) begin
        __in_dmem_req_valid_next[i] = __in_dmem_req_valid[i];
        __in_dmem_req_store_next[i] = __in_dmem_req_store[i];
        __in_dmem_req_address_next[i] = __in_dmem_req_address[i];
        __in_dmem_req_size_next[i] = __in_dmem_req_size[i];
        __in_dmem_req_tag_next[i] = __in_dmem_req_tag[i];
        __in_dmem_req_data_next[i] = __in_dmem_req_data[i];
        __in_dmem_req_mask_next[i] = __in_dmem_req_mask[i];
        __in_dmem_resp_ready_next[i] = __in_dmem_resp_ready[i];
      end
      cyclotron_tile_tick(
        __in_imem_req_valid_next,
        __out_imem_req_ready,
        __in_imem_req_bits_address_next,
        __in_imem_req_bits_tag_next,
        __in_imem_resp_ready_next,
        __out_imem_resp_valid,
        __out_imem_resp_bits_tag,
        __out_imem_resp_bits_data,
        __in_dmem_req_valid_next,
        __out_dmem_req_ready,
        __in_dmem_req_store_next,
        __in_dmem_req_address_next,
        __in_dmem_req_size_next,
        __in_dmem_req_tag_next,
        __in_dmem_req_data_next,
        __in_dmem_req_mask_next,
        __in_dmem_resp_ready_next,
        __out_dmem_resp_valid,
        __out_dmem_resp_tag,
        __out_dmem_resp_data,
        __in_finished_next
      );
      __in_imem_req_valid <= __in_imem_req_valid_next;
      __in_imem_req_bits_address <= __in_imem_req_bits_address_next;
      __in_imem_req_bits_tag <= __in_imem_req_bits_tag_next;
      __in_imem_resp_ready <= __in_imem_resp_ready_next;
      __in_finished <= __in_finished_next;
      for (integer i = 0; i < NUM_LANES; i = i + 1) begin
        __in_dmem_req_valid[i] <= __in_dmem_req_valid_next[i];
        __in_dmem_req_store[i] <= __in_dmem_req_store_next[i];
        __in_dmem_req_address[i] <= __in_dmem_req_address_next[i];
        __in_dmem_req_size[i] <= __in_dmem_req_size_next[i];
        __in_dmem_req_tag[i] <= __in_dmem_req_tag_next[i];
        __in_dmem_req_data[i] <= __in_dmem_req_data_next[i];
        __in_dmem_req_mask[i] <= __in_dmem_req_mask_next[i];
        __in_dmem_resp_ready[i] <= __in_dmem_resp_ready_next[i];
      end
    end
  end

endmodule
