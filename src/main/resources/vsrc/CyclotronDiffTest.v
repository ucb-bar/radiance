module CyclotronDiffTestBlackBox #(
  parameter CLUSTER_ID = 0,
  parameter CORE_ID = 0,
  parameter ARCH_LEN = 32,
  parameter INST_BITS = 64,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 9,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter CSR_IMM_BITS = 8,
  parameter PRED_BITS = 4,
  // advance cyclotron sim by one tick inside the difftest function.
  // set to 0 when some other DPI module does the tick, e.g. CyclotronFrontend
  parameter SIM_TICK = 1,
  localparam OPNOEXT_BITS = 7,
  localparam OPEXT_BITS = 2,
  localparam WARP_ID_BITS = $clog2(NUM_WARPS),
  localparam WARP_COUNT_BITS = $clog2(NUM_WARPS+1)
) (
  input clock,
  input reset,

  input  logic                            trace_valid,
  input  logic [ARCH_LEN-1:0]             trace_pc,
  input  logic [WARP_ID_BITS-1:0]         trace_warpId,
  input  logic [NUM_LANES-1:0]            trace_tmask,
  input  logic                            trace_regs_0_enable,
  input  logic [REG_BITS-1:0]             trace_regs_0_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_0_data,
  input  logic                            trace_regs_1_enable,
  input  logic [REG_BITS-1:0]             trace_regs_1_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_1_data,
  input  logic                            trace_regs_2_enable,
  input  logic [REG_BITS-1:0]             trace_regs_2_address,
  input  logic [(NUM_LANES*ARCH_LEN)-1:0] trace_regs_2_data
);
  `include "Cyclotron.vh"

  import "DPI-C" function cyclotron_difftest_reg(
    input bit  trace_sim_tick,
    input int  cluster_id,
    input int  core_id,
    input bit  trace_valid,
    input int  trace_pc,
    input int  trace_warpId,
    input int  trace_tmask,
    input bit  trace_regs_0_enable,
    input byte trace_regs_0_address,
    input int  trace_regs_0_data[NUM_LANES],
    input bit  trace_regs_1_enable,
    input byte trace_regs_1_address,
    input int  trace_regs_1_data[NUM_LANES],
    input bit  trace_regs_2_enable,
    input byte trace_regs_2_address,
    input int  trace_regs_2_data[NUM_LANES]
  );

  // need to be in ascending order to match with C array memory layout
  int     __trace_regs_0_data [0:NUM_LANES-1];
  int     __trace_regs_1_data [0:NUM_LANES-1];
  int     __trace_regs_2_data [0:NUM_LANES-1];

  initial cyclotron_init_task();

  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign __trace_regs_0_data[g] = trace_regs_0_data[ARCH_LEN*g +: ARCH_LEN];
      assign __trace_regs_1_data[g] = trace_regs_1_data[ARCH_LEN*g +: ARCH_LEN];
      assign __trace_regs_2_data[g] = trace_regs_2_data[ARCH_LEN*g +: ARCH_LEN];
    end
  endgenerate

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      cyclotron_difftest_reg(
        SIM_TICK,
        CLUSTER_ID,
        CORE_ID,
        trace_valid,
        trace_pc,
        trace_warpId,
        trace_tmask,
        trace_regs_0_enable,
        trace_regs_0_address,
        __trace_regs_0_data,
        trace_regs_1_enable,
        trace_regs_1_address,
        __trace_regs_1_data,
        trace_regs_2_enable,
        trace_regs_2_address,
        __trace_regs_2_data
      );
    end
  end

endmodule
