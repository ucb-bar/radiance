module CyclotronBlackBox #(
  parameter ARCH_LEN = 32,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 7,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter PRED_BITS = 4,

  parameter TAG_BITS = 32,
  parameter LSU_LANES = 16,
) (
  input clock,
  input reset,

  output logic req_ready,
  input  logic req_valid,

  input  logic req_store,
  input  logic [ARCH_LEN-1:0] req_address,
  input  logic [TAG_BITS-1:0] req_tag,
  input  logic [DATA_WIDTH-1:0] req_data,
  input  logic [MASK_WIDTH-1:0] req_mask,

  input  logic resp_ready,
  output logic resp_valid,

  output logic [TAG_BITS-1:0] resp_tag,
  output logic [DATA_WIDTH-1:0] resp_data
);
  localparam DATA_WIDTH = LSU_LANES * ARCH_LEN;
  localparam MASK_WIDTH = DATA_WIDTH / 8;

  // Currently, code only supports 32-bit addresses and <= 32 bit tags
  if (ARCH_LEN > 32) begin
    $error("64-bit addresses not supported");
  end

  if (TAG_BITS > 32) begin
    $error("Tag size exceeds 32 bits");
  end

  import "DPI-C" function chandle cyclotron_mem_init(
    input longint num_lanes,
  );

  import "DPI-C" function void cyclotron_mem_free(
    input chandle mem_model_ptr
  );

  import "DPI-C" function void cyclotron_mem(
    input chandle mem_model_ptr,

    output byte                  req_ready,
    input  byte                  req_valid,

    input  byte                  req_store,
    input  int                   req_address,
    input  int                   req_tag,
    input  bit  [DATA_WIDTH-1:0] req_data,
    input  bit  [MASK_WIDTH-1:0] req_mask,

    input  byte                  resp_ready,
    output byte                  resp_valid,

    output int                   resp_tag,
    output bit  [DATA_WIDTH-1:0] resp_data
  );

  chandle               mem_model_ptr;
  byte                  __req_ready;
  byte                  __req_valid;
  byte                  __req_store;
  int                   __req_address;
  int                   __req_tag;
  bit  [DATA_WIDTH-1:0] __req_data;
  bit  [MASK_WIDTH-1:0] __req_mask;
  byte                  __resp_ready;
  byte                  __resp_valid;
  int                   __resp_tag;
  bit  [DATA_WIDTH-1:0] __resp_data;

  initial begin
    mem_model_ptr = cyclotron_mem_init();
  end


  always @(negedge clock) begin
    __req_valid = req_valid;
    __req_store = req_store;
    __req_address = req_address;
    __req_tag = req_tag;
    __req_data = req_data;
    __req_mask = req_mask;
    __resp_ready = resp_ready;

    cyclotron_mem(
      mem_model_ptr,

      __req_ready,
      __req_valid,

      __req_store,
      __req_address,
      __req_tag,
      __req_data,
      __req_mask,

      __resp_ready,
      __resp_valid,

      __resp_tag,
      __resp_data
    );

    req_ready <= __req_ready;
    resp_valid <= __resp_valid;
    resp_tag <= __resp_tag;
    resp_data <= __resp_data;
  end

endmodule
