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

  output logic        imem_req_ready,
  input  logic        imem_req_valid,
  input  logic        imem_req_bits_store,
  input  logic [31:0] imem_req_bits_address,
  input  logic [1:0]  imem_req_bits_size,
  input  logic [5:0]  imem_req_bits_tag,
  input  logic [63:0] imem_req_bits_data,
  input  logic [7:0]  imem_req_bits_mask,
  input  logic        imem_resp_ready,
  output logic        imem_resp_valid,
  output logic [5:0]  imem_resp_bits_tag,
  output logic [63:0] imem_resp_bits_data,

  output logic        ibuf_ready,
  input  logic        ibuf_valid,
  input  logic [31:0] ibuf_bits_pc,
  input  logic [2:0]  ibuf_bits_wid,
  input  logic [8:0]  ibuf_bits_op,
  input  logic [7:0]  ibuf_bits_rd,
  input  logic [7:0]  ibuf_bits_rs1,
  input  logic [7:0]  ibuf_bits_rs2,
  input  logic [7:0]  ibuf_bits_rs3,
  input  logic [31:0] ibuf_bits_imm32,
  input  logic [23:0] ibuf_bits_imm24,
  input  logic [7:0]  ibuf_bits_csrImm,
  input  logic [2:0]  ibuf_bits_f3,
  input  logic [6:0]  ibuf_bits_f7,
  input  logic [3:0]  ibuf_bits_pred,
  input  logic [15:0] ibuf_bits_tmask,
  input  logic [63:0] ibuf_bits_raw,

  output logic        commit_0_valid,
  output logic        commit_0_bits_setPC_valid,
  output logic [31:0] commit_0_bits_setPC_bits,
  output logic        commit_0_bits_setTmask_valid,
  output logic [15:0] commit_0_bits_setTmask_bits,
  output logic        commit_0_bits_ipdomPush_valid,
  output logic [15:0] commit_0_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_0_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_0_bits_ipdomPush_bits_elsePC,
  output logic        commit_0_bits_wspawn_valid,
  output logic [3:0]  commit_0_bits_wspawn_bits_count,
  output logic [31:0] commit_0_bits_wspawn_bits_pc,
  output logic [31:0] commit_0_bits_pc,
  output logic        commit_1_valid,
  output logic        commit_1_bits_setPC_valid,
  output logic [31:0] commit_1_bits_setPC_bits,
  output logic        commit_1_bits_setTmask_valid,
  output logic [15:0] commit_1_bits_setTmask_bits,
  output logic        commit_1_bits_ipdomPush_valid,
  output logic [15:0] commit_1_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_1_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_1_bits_ipdomPush_bits_elsePC,
  output logic        commit_1_bits_wspawn_valid,
  output logic [3:0]  commit_1_bits_wspawn_bits_count,
  output logic [31:0] commit_1_bits_wspawn_bits_pc,
  output logic [31:0] commit_1_bits_pc,
  output logic        commit_2_valid,
  output logic        commit_2_bits_setPC_valid,
  output logic [31:0] commit_2_bits_setPC_bits,
  output logic        commit_2_bits_setTmask_valid,
  output logic [15:0] commit_2_bits_setTmask_bits,
  output logic        commit_2_bits_ipdomPush_valid,
  output logic [15:0] commit_2_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_2_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_2_bits_ipdomPush_bits_elsePC,
  output logic        commit_2_bits_wspawn_valid,
  output logic [3:0]  commit_2_bits_wspawn_bits_count,
  output logic [31:0] commit_2_bits_wspawn_bits_pc,
  output logic [31:0] commit_2_bits_pc,
  output logic        commit_3_valid,
  output logic        commit_3_bits_setPC_valid,
  output logic [31:0] commit_3_bits_setPC_bits,
  output logic        commit_3_bits_setTmask_valid,
  output logic [15:0] commit_3_bits_setTmask_bits,
  output logic        commit_3_bits_ipdomPush_valid,
  output logic [15:0] commit_3_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_3_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_3_bits_ipdomPush_bits_elsePC,
  output logic        commit_3_bits_wspawn_valid,
  output logic [3:0]  commit_3_bits_wspawn_bits_count,
  output logic [31:0] commit_3_bits_wspawn_bits_pc,
  output logic [31:0] commit_3_bits_pc,
  output logic        commit_4_valid,
  output logic        commit_4_bits_setPC_valid,
  output logic [31:0] commit_4_bits_setPC_bits,
  output logic        commit_4_bits_setTmask_valid,
  output logic [15:0] commit_4_bits_setTmask_bits,
  output logic        commit_4_bits_ipdomPush_valid,
  output logic [15:0] commit_4_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_4_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_4_bits_ipdomPush_bits_elsePC,
  output logic        commit_4_bits_wspawn_valid,
  output logic [3:0]  commit_4_bits_wspawn_bits_count,
  output logic [31:0] commit_4_bits_wspawn_bits_pc,
  output logic [31:0] commit_4_bits_pc,
  output logic        commit_5_valid,
  output logic        commit_5_bits_setPC_valid,
  output logic [31:0] commit_5_bits_setPC_bits,
  output logic        commit_5_bits_setTmask_valid,
  output logic [15:0] commit_5_bits_setTmask_bits,
  output logic        commit_5_bits_ipdomPush_valid,
  output logic [15:0] commit_5_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_5_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_5_bits_ipdomPush_bits_elsePC,
  output logic        commit_5_bits_wspawn_valid,
  output logic [3:0]  commit_5_bits_wspawn_bits_count,
  output logic [31:0] commit_5_bits_wspawn_bits_pc,
  output logic [31:0] commit_5_bits_pc,
  output logic        commit_6_valid,
  output logic        commit_6_bits_setPC_valid,
  output logic [31:0] commit_6_bits_setPC_bits,
  output logic        commit_6_bits_setTmask_valid,
  output logic [15:0] commit_6_bits_setTmask_bits,
  output logic        commit_6_bits_ipdomPush_valid,
  output logic [15:0] commit_6_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_6_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_6_bits_ipdomPush_bits_elsePC,
  output logic        commit_6_bits_wspawn_valid,
  output logic [3:0]  commit_6_bits_wspawn_bits_count,
  output logic [31:0] commit_6_bits_wspawn_bits_pc,
  output logic [31:0] commit_6_bits_pc,
  output logic        commit_7_valid,
  output logic        commit_7_bits_setPC_valid,
  output logic [31:0] commit_7_bits_setPC_bits,
  output logic        commit_7_bits_setTmask_valid,
  output logic [15:0] commit_7_bits_setTmask_bits,
  output logic        commit_7_bits_ipdomPush_valid,
  output logic [15:0] commit_7_bits_ipdomPush_bits_restoredMask,
  output logic [15:0] commit_7_bits_ipdomPush_bits_elseMask,
  output logic [31:0] commit_7_bits_ipdomPush_bits_elsePC,
  output logic        commit_7_bits_wspawn_valid,
  output logic [3:0]  commit_7_bits_wspawn_bits_count,
  output logic [31:0] commit_7_bits_wspawn_bits_pc,
  output logic [31:0] commit_7_bits_pc,

  output logic        finished
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

  import "DPI-C" function void cyclotron_backend(
    input  byte issue_valid,
    input  byte issue_warp_id,
    input  int  issue_pc,
    input  byte issue_op,
    input  byte issue_opext,
    input  byte issue_f3,
    input  byte issue_rd_addr,
    input  byte issue_rs1_addr,
    input  byte issue_rs2_addr,
    input  byte issue_rs3_addr,
    input  int  issue_rs1_data,
    input  int  issue_rs2_data,
    input  int  issue_rs3_data,
    input  byte issue_f7,
    input  int  issue_imm32,
    input  int  issue_imm24,
    input  byte issue_csr_imm,
    input  int  issue_pred,
    input  int  issue_tmask,
    input  longint issue_raw_inst,
    output byte writeback_valid,
    output int  writeback_pc,
    output int  writeback_tmask,
    output byte writeback_wid,
    output byte writeback_rd_addr,
    output int  writeback_rd_data,
    output byte writeback_set_pc_valid,
    output int  writeback_set_pc,
    output byte writeback_set_tmask_valid,
    output int  writeback_set_tmask,
    output byte writeback_wspawn_valid,
    output int  writeback_wspawn_count,
    output int  writeback_wspawn_pc
  );

  // "in": C->verilog, "out": verilog->C
  // need to be in ascending order to match with C array memory layout
  bit     __out_ibuf_ready [0:NUM_WARPS-1];
  bit     __in_ibuf_valid  [0:NUM_WARPS-1];
  int     __in_ibuf_pc     [0:NUM_WARPS-1];
  int     __in_ibuf_op     [0:NUM_WARPS-1];
  int     __in_ibuf_rd     [0:NUM_WARPS-1];
  bit     __in_finished;

  byte __writeback_valid;
  int  __writeback_pc;
  int  __writeback_tmask;
  byte __writeback_wid;
  byte __writeback_rd_addr;
  int  __writeback_rd_data;
  byte __writeback_set_pc_valid;
  int  __writeback_set_pc;
  byte __writeback_set_tmask_valid;
  int  __writeback_set_tmask;
  byte __writeback_wspawn_valid;
  int  __writeback_wspawn_count;
  int  __writeback_wspawn_pc;

  assign finished = 1'b0;

  // TODO: simulate backpressure
  assign ibuf_ready = 1'b1;
  always @(posedge clock) begin
    if (reset) begin
      imem_req_ready <= 1'b0;
      imem_resp_valid <= 1'b0;
      commit_0_valid <= 1'b0;
      commit_1_valid <= 1'b0;
      commit_2_valid <= 1'b0;
      commit_3_valid <= 1'b0;
      commit_4_valid <= 1'b0;
      commit_5_valid <= 1'b0;
      commit_6_valid <= 1'b0;
      commit_7_valid <= 1'b0;
    end else begin
      cyclotron_backend(
        ibuf_valid,
        ibuf_bits_wid,
        ibuf_bits_pc,
        ibuf_bits_op[6:0],
        ibuf_bits_op[8:7],
        ibuf_bits_f3,
        ibuf_bits_rd,
        ibuf_bits_rs1,
        ibuf_bits_rs2,
        ibuf_bits_rs3,
        32'h0,
        32'h0,
        32'h0,
        ibuf_bits_f7,
        ibuf_bits_imm32,
        ibuf_bits_imm24,
        ibuf_bits_csrImm,
        ibuf_bits_pred,
        ibuf_bits_tmask,
        ibuf_bits_raw,
        __writeback_valid,
        __writeback_pc,
        __writeback_tmask,
        __writeback_wid,
        __writeback_rd_addr,
        __writeback_rd_data,
        __writeback_set_pc_valid,
        __writeback_set_pc,
        __writeback_set_tmask_valid,
        __writeback_set_tmask,
        __writeback_wspawn_valid,
        __writeback_wspawn_count,
        __writeback_wspawn_pc
      );
    end
    assign commit_0_valid = __writeback_valid && (__writeback_wid == '0);
    assign commit_1_valid = __writeback_valid && (__writeback_wid == '1);
    assign commit_2_valid = __writeback_valid && (__writeback_wid == '2);
    assign commit_3_valid = __writeback_valid && (__writeback_wid == '3);
    assign commit_4_valid = __writeback_valid && (__writeback_wid == '4);
    assign commit_5_valid = __writeback_valid && (__writeback_wid == '5);
    assign commit_6_valid = __writeback_valid && (__writeback_wid == '6);
    assign commit_7_valid = __writeback_valid && (__writeback_wid == '7);

    assign commit_0_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_0_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_0_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_0_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_0_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_0_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_0_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_0_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_0_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_0_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_0_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_0_bits_pc =                          __writeback_pc;

    assign commit_1_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_1_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_1_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_1_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_1_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_1_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_1_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_1_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_1_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_1_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_1_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_1_bits_pc =                          __writeback_pc;

    assign commit_2_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_2_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_2_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_2_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_2_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_2_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_2_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_2_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_2_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_2_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_2_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_2_bits_pc =                          __writeback_pc;

    assign commit_3_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_3_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_3_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_3_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_3_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_3_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_3_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_3_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_3_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_3_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_3_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_3_bits_pc =                          __writeback_pc;

    assign commit_4_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_4_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_4_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_4_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_4_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_4_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_4_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_4_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_4_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_4_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_4_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_4_bits_pc =                          __writeback_pc;

    assign commit_5_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_5_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_5_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_5_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_5_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_5_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_5_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_5_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_5_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_5_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_5_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_5_bits_pc =                          __writeback_pc;

    assign commit_6_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_6_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_6_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_6_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_6_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_6_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_6_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_6_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_6_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_6_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_6_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_6_bits_pc =                          __writeback_pc;

    assign commit_7_bits_setPC_valid =                 __writeback_set_pc_valid;
    assign commit_7_bits_setPC_bits =                  __writeback_set_pc;
    assign commit_7_bits_setTmask_valid =              __writeback_set_tmask_valid;
    assign commit_7_bits_setTmask_bits =               __writeback_set_tmask;
    assign commit_7_bits_ipdomPush_valid =             1'b0; // TODO
    assign commit_7_bits_ipdomPush_bits_restoredMask = '0; // TODO
    assign commit_7_bits_ipdomPush_bits_elseMask =     '0; // TODO
    assign commit_7_bits_ipdomPush_bits_elsePC =       '0; // TODO
    assign commit_7_bits_wspawn_valid =                __writeback_wspawn_valid;
    assign commit_7_bits_wspawn_bits_count =           __writeback_wspawn_count;
    assign commit_7_bits_wspawn_bits_pc =              __writeback_wspawn_pc;
    assign commit_7_bits_pc =                          __writeback_pc;
  end
//   genvar g;
//   generate
//     for (g = 0; g < NUM_WARPS; g = g + 1) begin
//       assign __out_ibuf_ready[g] = ibuf_ready[g];
//       assign ibuf_valid[g] = __in_ibuf_valid[g];
//       assign ibuf_pc[ARCH_LEN*g +: ARCH_LEN] = __in_ibuf_pc[g][ARCH_LEN-1:0];
//       assign ibuf_op[OP_BITS*g  +: OP_BITS] = __in_ibuf_op[g][OP_BITS-1:0];
//       assign ibuf_rd[REG_BITS*g +: REG_BITS] = __in__ibuf_rd[g][REG_BITS-1:0];
//     end
//   endgenerate
//   assign finished = __in_finished;
//
  initial begin
    cyclotron_init();
  end
//
//   always @(posedge clock) begin
//     if (reset) begin
//       for (integer g = 0; g < NUM_WARPS; g = g + 1) begin
//         __in_ibuf_pc[g] = '0;
//         __in_ibuf_op[g] = '0;
//         __in_ibuf_rd[g] = '0;
//         __in_finished   = '0;
//       end
//     end else begin
//       cyclotron_get_trace(
//         __out_ibuf_ready,
//         __in_ibuf_valid,
//         __in_ibuf_pc,
//         __in_ibuf_op,
//         __in_ibuf_rd,
//         __in_finished
//       );
//       // for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
//       //   $display("verilog: %04d a_valid[%d]=%d, a_address[%d]=0x%x, d_ready[%d]=%d",
//       //     $time, tid, __in_a_valid[tid], tid, __in_a_address[tid], tid, __in_d_ready[tid]);
//       // end
//     end
//   end
endmodule
