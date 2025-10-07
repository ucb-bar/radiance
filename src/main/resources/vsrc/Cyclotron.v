module CyclotronBlackBox #(
  parameter ARCH_LEN = 32,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 7,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter PRED_BITS = 4
) (
  input clock,
  input reset,

  input  [NUM_WARPS-1:0]          ibuf_ready,
  output [NUM_WARPS-1:0]          ibuf_valid,
  output [ARCH_LEN*NUM_WARPS-1:0] ibuf_pc,
  output [OP_BITS*NUM_WARPS-1:0]  ibuf_op,
  output [REG_BITS*NUM_WARPS-1:0] ibuf_rd,

  output                          finished
);
  // whenever you change these interfaces, make sure to update:
  // (1) import "DPI-C" declaration
  // (2) C function declaration
  // (3) Verilog DPI calls inside initial/always blocks
  import "DPI-C" function void cyclotron_init();
  import "DPI-C" function void cyclotron_get_trace(
    input  bit ready[NUM_WARPS],
    output bit valid[NUM_WARPS],
    output int pc[NUM_WARPS],
    output int op[NUM_WARPS],
    output int rd[NUM_WARPS],
    output bit finished
  );

  // "in": C->verilog, "out": verilog->C
  // need to be in ascending order to match with C array memory layout
  bit     __out_ibuf_ready [0:NUM_WARPS-1];
  bit     __in_ibuf_valid  [0:NUM_WARPS-1];
  int     __in_ibuf_pc     [0:NUM_WARPS-1];
  int     __in_ibuf_op     [0:NUM_WARPS-1];
  int     __in_ibuf_rd     [0:NUM_WARPS-1];
  bit     __in_finished;

  genvar g;
  generate
    for (g = 0; g < NUM_WARPS; g = g + 1) begin
      assign __out_ibuf_ready[g] = ibuf_ready[g];
      assign ibuf_valid[g] = __in_ibuf_valid[g];
      assign ibuf_pc[ARCH_LEN*g +: ARCH_LEN] = __in_ibuf_pc[g][ARCH_LEN-1:0];
      assign ibuf_op[OP_BITS*g  +: OP_BITS] = __in_ibuf_op[g][OP_BITS-1:0];
      assign ibuf_rd[REG_BITS*g +: REG_BITS] = __in_ibuf_rd[g][REG_BITS-1:0];
    end
  endgenerate
  assign finished = __in_finished;

  initial begin
    cyclotron_init();
  end

  always @(posedge clock) begin
    if (reset) begin
      for (integer g = 0; g < NUM_WARPS; g = g + 1) begin
        __in_ibuf_pc[g] = '0;
        __in_ibuf_op[g] = '0;
        __in_ibuf_rd[g] = '0;
        __in_finished   = '0;
      end
    end else begin
      cyclotron_get_trace(
        __out_ibuf_ready,
        __in_ibuf_valid,
        __in_ibuf_pc,
        __in_ibuf_op,
        __in_ibuf_rd,
        __in_finished
      );
      // for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
      //   $display("verilog: %04d a_valid[%d]=%d, a_address[%d]=0x%x, d_ready[%d]=%d",
      //     $time, tid, __in_a_valid[tid], tid, __in_a_address[tid], tid, __in_d_ready[tid]);
      // end
    end
  end
endmodule
