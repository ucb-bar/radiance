module CyclotronDiffTestBlackBox #(
  parameter ARCH_LEN = 32,
  parameter INST_BITS = 64,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 9,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter CSR_IMM_BITS = 8,
  parameter PRED_BITS = 4,
  localparam OPNOEXT_BITS = 7,
  localparam OPEXT_BITS = 2,
  localparam WARP_ID_BITS = $clog2(NUM_WARPS),
  localparam WARP_COUNT_BITS = $clog2(NUM_WARPS+1)
) (
  input clock,
  input reset,

  input  logic                            trace_valid,
  input  logic [ARCH_LEN-1:0]             trace_pc,
  input  logic                            trace_regs_0_enable,
  input  logic [REG_BITS-1:0]             trace_regs_0_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_0_data,
  input  logic                            trace_regs_1_enable,
  input  logic [REG_BITS-1:0]             trace_regs_1_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_1_data,
  input  logic                            trace_regs_2_enable,
  input  logic [REG_BITS-1:0]             trace_regs_2_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_2_data,

  output logic finished
);
  `include "Cyclotron.vh"

  // "in": C->verilog, "out": verilog->C
  // need to be in ascending order to match with C array memory layout
  bit     __out_trace_valid;
  int     __out_trace_pc;
  bit     __out_trace_regs_0_enable;
  byte    __out_trace_regs_0_address;
  int     __out_trace_regs_0_data [0:NUM_LANES-1];
  bit     __out_trace_regs_1_enable;
  byte    __out_trace_regs_1_address;
  int     __out_trace_regs_1_data [0:NUM_LANES-1];
  bit     __out_trace_regs_2_enable;
  byte    __out_trace_regs_2_address;
  int     __out_trace_regs_2_data [0:NUM_LANES-1];

  bit __in_finished;

  string elffile;

  // initialize model at the rtl sim start
  // use BINARY= argument (i.e. first non-plusarg argument) as the Cyclotron
  // ELF
  initial begin
    elffile = vpi_get_binary();
    cyclotron_init(elffile);
  end

  // connect regtrace signals
  assign __out_trace_valid = trace_valid;
  assign __out_trace_pc = trace_pc;
  assign __out_trace_regs_0_enable  = trace_regs_0_enable;
  assign __out_trace_regs_0_address = trace_regs_0_address;
  assign __out_trace_regs_1_enable  = trace_regs_1_enable;
  assign __out_trace_regs_1_address = trace_regs_1_address;
  assign __out_trace_regs_2_enable  = trace_regs_2_enable;
  assign __out_trace_regs_2_address = trace_regs_2_address;
  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign __out_trace_regs_0_data[g] = trace_regs_0_data[ARCH_LEN*g +: ARCH_LEN];
      assign __out_trace_regs_1_data[g] = trace_regs_1_data[ARCH_LEN*g +: ARCH_LEN];
      assign __out_trace_regs_2_data[g] = trace_regs_2_data[ARCH_LEN*g +: ARCH_LEN];
    end
  endgenerate

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      cyclotron_difftest_reg(
        __out_trace_valid,
        __out_trace_pc,
        __out_trace_regs_0_enable,
        __out_trace_regs_0_address,
        __out_trace_regs_0_data,
        __out_trace_regs_1_enable,
        __out_trace_regs_1_address,
        __out_trace_regs_1_data,
        __out_trace_regs_2_enable,
        __out_trace_regs_2_address,
        __out_trace_regs_2_data
      );
    end
  end

endmodule
