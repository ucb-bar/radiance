module CyclotronDataMemBlackBox #(
  parameter ARCH_LEN = 32,
  parameter DMEM_DATA_BITS = 32,
  parameter DMEM_TAG_BITS = 32,
  parameter NUM_LANES = 1,
  localparam DMEM_SIZE_BITS = $clog2($clog2(DMEM_DATA_BITS / 8) + 1),
  localparam DMEM_MASK_BITS = (DMEM_DATA_BITS / 8)
) (
  input clock,
  input reset,

  input  logic [NUM_LANES-1:0]                           req_valid,
  output logic [NUM_LANES-1:0]                           req_ready,
  input  logic [NUM_LANES-1:0]                           req_bits_store,
  input  logic [(NUM_LANES*DMEM_TAG_BITS)-1:0]           req_bits_tag,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0]                req_bits_address,
  input  logic [(NUM_LANES*DMEM_SIZE_BITS)-1:0]          req_bits_size,
  input  logic [(NUM_LANES*DMEM_DATA_BITS)-1:0]          req_bits_data,
  input  logic [(NUM_LANES*DMEM_MASK_BITS)-1:0]          req_bits_mask,
  input  logic [NUM_LANES-1:0]                           resp_ready,
  output logic [NUM_LANES-1:0]                           resp_valid,
  output logic [(NUM_LANES*DMEM_TAG_BITS)-1:0]           resp_bits_tag,
  output logic [(NUM_LANES*DMEM_DATA_BITS)-1:0]          resp_bits_data
);
  `include "Cyclotron.vh"

  import "DPI-C" function void cyclotron_gmem(
    input  bit     req_valid[NUM_LANES],
    output bit     req_ready[NUM_LANES],
    input  bit     req_bits_store[NUM_LANES],
    input  int     req_bits_address[NUM_LANES],
    input  byte    req_bits_size[NUM_LANES],
    input  int     req_bits_tag[NUM_LANES],
    input  int     req_bits_data[NUM_LANES],
    input  byte    req_bits_mask[NUM_LANES],
    input  bit     resp_ready[NUM_LANES],
    output bit     resp_valid[NUM_LANES],
    output int     resp_bits_tag[NUM_LANES],
    output int     resp_bits_data[NUM_LANES]
  );

  initial cyclotron_init_task();

  // "in": C->verilog, "out": verilog->C
  // need to be in ascending order to match with C array memory layout
  bit  __out_req_valid    [0:NUM_LANES-1];
  bit  __out_req_store    [0:NUM_LANES-1];
  int  __out_req_address  [0:NUM_LANES-1];
  byte __out_req_size     [0:NUM_LANES-1];
  int  __out_req_tag      [0:NUM_LANES-1];
  int  __out_req_data     [0:NUM_LANES-1];
  byte __out_req_mask     [0:NUM_LANES-1];
  bit  __out_resp_ready   [0:NUM_LANES-1];

  bit __in_req_ready      [0:NUM_LANES-1];
  bit __in_resp_valid     [0:NUM_LANES-1];
  int __in_resp_tag       [0:NUM_LANES-1];
  int __in_resp_data      [0:NUM_LANES-1];
  bit __in_req_ready_next [0:NUM_LANES-1];
  bit __in_resp_valid_next[0:NUM_LANES-1];
  int __in_resp_tag_next  [0:NUM_LANES-1];
  int __in_resp_data_next [0:NUM_LANES-1];

  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign __out_req_valid[g] = req_valid[g];
      assign __out_req_store[g] = req_bits_store[g];
      assign __out_req_address[g] = req_bits_address[ARCH_LEN*g +: ARCH_LEN];
      assign __out_req_size[g] = req_bits_size[DMEM_SIZE_BITS*g +: DMEM_SIZE_BITS];
      assign __out_req_tag[g] = req_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS];
      assign __out_req_data[g] = req_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS];
      assign __out_req_mask[g] = req_bits_mask[DMEM_MASK_BITS*g +: DMEM_MASK_BITS];
      assign __out_resp_ready[g] = resp_ready[g];

      assign req_ready[g] = __in_req_ready[g];
      assign resp_valid[g] = __in_resp_valid[g];
      assign resp_bits_tag[DMEM_TAG_BITS*g +: DMEM_TAG_BITS] =
        __in_resp_tag[g][DMEM_TAG_BITS-1:0];
      assign resp_bits_data[DMEM_DATA_BITS*g +: DMEM_DATA_BITS] =
        __in_resp_data[g][DMEM_DATA_BITS-1:0];
    end
  endgenerate

  // nonblocking assignments from DPI output vars ensure other posedge logic
  // reads pre-DPI values
  always @(posedge clock) begin
    if (reset) begin
      for (integer i = 0; i < NUM_LANES; i = i + 1) begin
        __in_req_ready[i] <= '0;
        __in_resp_valid[i] <= '0;
        __in_resp_tag[i] <= '0;
        __in_resp_data[i] <= '0;
      end
    end else begin
      cyclotron_gmem(
        __out_req_valid,
        __in_req_ready_next,
        __out_req_store,
        __out_req_address,
        __out_req_size,
        __out_req_tag,
        __out_req_data,
        __out_req_mask,
        __out_resp_ready,
        __in_resp_valid_next,
        __in_resp_tag_next,
        __in_resp_data_next
      );
      for (integer i = 0; i < NUM_LANES; i = i + 1) begin
        __in_req_ready[i] <= __in_req_ready_next[i];
        __in_resp_valid[i] <= __in_resp_valid_next[i];
        __in_resp_tag[i] <= __in_resp_tag_next[i];
        __in_resp_data[i] <= __in_resp_data_next[i];
      end
    end
  end

endmodule
