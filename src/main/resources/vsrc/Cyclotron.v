module CyclotronBlackBox #(
  parameter ARCH_LEN = 32,
  parameter INST_BITS = 64,
  parameter NUM_WARPS = 8,
  parameter NUM_LANES = 16,
  parameter OP_BITS = 9,
  parameter REG_BITS = 8,
  parameter IMM_BITS = 32,
  parameter CSR_IMM_BITS = 8,
  parameter PRED_BITS = 4,
  localparam WARP_ID_BITS = $clog2(NUM_WARPS),
  localparam WARP_COUNT_BITS = $clog2(NUM_WARPS+1)
) (
  input clock,
  input reset,

  output logic                     imem_req_ready,
  input  logic                     imem_req_valid,
  input  logic                     imem_req_bits_store,
  input  logic [ARCH_LEN-1:0]      imem_req_bits_address,
  input  logic [1:0]               imem_req_bits_size,
  input  logic [5:0]               imem_req_bits_tag,
  input  logic [INST_BITS-1:0]     imem_req_bits_data,
  input  logic [(INST_BITS/8)-1:0] imem_req_bits_mask,
  input  logic                     imem_resp_ready,
  output logic                     imem_resp_valid,
  output logic [5:0]               imem_resp_bits_tag,
  output logic [INST_BITS-1:0]     imem_resp_bits_data,

  output logic                    ibuf_ready,
  input  logic                    ibuf_valid,
  input  logic [ARCH_LEN-1:0]     ibuf_bits_pc,
  input  logic [WARP_ID_BITS-1:0] ibuf_bits_wid,
  input  logic [OP_BITS-1:0]      ibuf_bits_op,
  input  logic [REG_BITS-1:0]     ibuf_bits_rd,
  input  logic [REG_BITS-1:0]     ibuf_bits_rs1,
  input  logic [REG_BITS-1:0]     ibuf_bits_rs2,
  input  logic [REG_BITS-1:0]     ibuf_bits_rs3,
  input  logic [31:0]             ibuf_bits_imm32,
  input  logic [23:0]             ibuf_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0] ibuf_bits_csrImm,
  input  logic [WARP_ID_BITS-1:0] ibuf_bits_f3,
  input  logic [6:0]              ibuf_bits_f7,
  input  logic [PRED_BITS-1:0]    ibuf_bits_pred,
  input  logic [NUM_LANES-1:0]    ibuf_bits_tmask,
  input  logic [INST_BITS-1:0]    ibuf_bits_raw,

  output logic        commit_0_valid,
  output logic        commit_0_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_0_bits_setPC_bits,
  output logic        commit_0_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_0_bits_setTmask_bits,
  output logic        commit_0_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_0_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_0_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_0_bits_ipdomPush_bits_elsePC,
  output logic        commit_0_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_0_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_0_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_0_bits_pc,
  output logic        commit_1_valid,
  output logic        commit_1_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_1_bits_setPC_bits,
  output logic        commit_1_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_1_bits_setTmask_bits,
  output logic        commit_1_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_1_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_1_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_1_bits_ipdomPush_bits_elsePC,
  output logic        commit_1_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_1_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_1_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_1_bits_pc,
  output logic        commit_2_valid,
  output logic        commit_2_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_2_bits_setPC_bits,
  output logic        commit_2_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_2_bits_setTmask_bits,
  output logic        commit_2_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_2_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_2_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_2_bits_ipdomPush_bits_elsePC,
  output logic        commit_2_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_2_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_2_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_2_bits_pc,
  output logic        commit_3_valid,
  output logic        commit_3_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_3_bits_setPC_bits,
  output logic        commit_3_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_3_bits_setTmask_bits,
  output logic        commit_3_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_3_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_3_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_3_bits_ipdomPush_bits_elsePC,
  output logic        commit_3_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_3_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_3_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_3_bits_pc,
  output logic        commit_4_valid,
  output logic        commit_4_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_4_bits_setPC_bits,
  output logic        commit_4_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_4_bits_setTmask_bits,
  output logic        commit_4_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_4_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_4_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_4_bits_ipdomPush_bits_elsePC,
  output logic        commit_4_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_4_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_4_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_4_bits_pc,
  output logic        commit_5_valid,
  output logic        commit_5_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_5_bits_setPC_bits,
  output logic        commit_5_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_5_bits_setTmask_bits,
  output logic        commit_5_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_5_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_5_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_5_bits_ipdomPush_bits_elsePC,
  output logic        commit_5_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_5_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_5_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_5_bits_pc,
  output logic        commit_6_valid,
  output logic        commit_6_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_6_bits_setPC_bits,
  output logic        commit_6_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_6_bits_setTmask_bits,
  output logic        commit_6_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_6_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_6_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_6_bits_ipdomPush_bits_elsePC,
  output logic        commit_6_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_6_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_6_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_6_bits_pc,
  output logic        commit_7_valid,
  output logic        commit_7_bits_setPC_valid,
  output logic [ARCH_LEN-1:0] commit_7_bits_setPC_bits,
  output logic        commit_7_bits_setTmask_valid,
  output logic [NUM_LANES-1:0] commit_7_bits_setTmask_bits,
  output logic        commit_7_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0] commit_7_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0] commit_7_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0] commit_7_bits_ipdomPush_bits_elsePC,
  output logic        commit_7_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0]  commit_7_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0] commit_7_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0] commit_7_bits_pc,

  output logic        finished
);
  // whenever you change these interfaces, make sure to update:
  // (1) import "DPI-C" declaration
  // (2) C function declaration
  // (3) Verilog DPI calls inside initial/always blocks
  import "DPI-C" function void cyclotron_init();
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
    output int  writeback_wspawn_pc,
    output byte finished
  );

  import "DPI-C" function void cyclotron_imem(
    output byte imem_req_ready,
    input  byte imem_req_valid,
    input  byte imem_req_bits_store,
    input  int  imem_req_bits_address,
    input  byte imem_req_bits_size,
    input  byte imem_req_bits_tag,
    input  longint imem_req_bits_data,
    input  byte imem_req_bits_mask,
    input  byte imem_resp_ready,
    output byte imem_resp_valid,
    output byte imem_resp_bits_tag,
    output longint imem_resp_bits_data
  );

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
  byte __finished;

  byte __imem_req_ready;
  byte __imem_resp_valid;
  byte __imem_resp_bits_tag;
  longint __imem_resp_bits_data;

  // initialize model at the rtl sim start
  initial begin
    cyclotron_init();
  end

  // TODO: simulate backpressure
  assign ibuf_ready = 1'b1;

  always @(negedge clock) begin
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
      __writeback_wspawn_pc,
      __finished
    );

    cyclotron_imem(
      __imem_req_ready,
      imem_req_valid,
      imem_req_bits_store,
      imem_req_bits_address,
      imem_req_bits_size,
      imem_req_bits_tag,
      imem_req_bits_data,
      imem_req_bits_mask,
      imem_resp_ready,
      __imem_resp_valid,
      __imem_resp_bits_tag,
      __imem_resp_bits_data
    );
  end

  always @(posedge clock) begin
    if (reset) begin
      // reset dpi regs
      __writeback_valid <= '0;
      __writeback_pc <= '0;
      __writeback_tmask <= '0;
      __writeback_wid <= '0;
      __writeback_rd_addr <= '0;
      __writeback_rd_data <= '0;
      __writeback_set_pc_valid <= '0;
      __writeback_set_pc <= '0;
      __writeback_set_tmask_valid <= '0;
      __writeback_set_tmask <= '0;
      __writeback_wspawn_valid <= '0;
      __writeback_wspawn_count <= '0;
      __writeback_wspawn_pc <= '0;
      __imem_req_ready <= '0;
      __imem_resp_valid <= '0;
      __imem_resp_bits_tag <= '0;
      __imem_resp_bits_data <= '0;
      __finished <= '0;

      imem_resp_valid <= 1'b0;
      commit_0_valid <= 1'b0;
      commit_1_valid <= 1'b0;
      commit_2_valid <= 1'b0;
      commit_3_valid <= 1'b0;
      commit_4_valid <= 1'b0;
      commit_5_valid <= 1'b0;
      commit_6_valid <= 1'b0;
      commit_7_valid <= 1'b0;
      finished <= 1'b0;
    end else begin

      commit_0_valid <= __writeback_valid && (__writeback_wid == 3'h0);
      commit_1_valid <= __writeback_valid && (__writeback_wid == 3'h1);
      commit_2_valid <= __writeback_valid && (__writeback_wid == 3'h2);
      commit_3_valid <= __writeback_valid && (__writeback_wid == 3'h3);
      commit_4_valid <= __writeback_valid && (__writeback_wid == 3'h4);
      commit_5_valid <= __writeback_valid && (__writeback_wid == 3'h5);
      commit_6_valid <= __writeback_valid && (__writeback_wid == 3'h6);
      commit_7_valid <= __writeback_valid && (__writeback_wid == 3'h7);

      commit_0_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_0_bits_setPC_bits                   <= __writeback_set_pc;
      commit_0_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_0_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_0_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_0_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_0_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_0_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_0_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_0_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_0_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_0_bits_pc                           <= __writeback_pc;

      commit_1_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_1_bits_setPC_bits                   <= __writeback_set_pc;
      commit_1_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_1_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_1_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_1_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_1_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_1_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_1_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_1_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_1_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_1_bits_pc                           <= __writeback_pc;

      commit_2_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_2_bits_setPC_bits                   <= __writeback_set_pc;
      commit_2_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_2_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_2_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_2_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_2_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_2_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_2_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_2_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_2_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_2_bits_pc                           <= __writeback_pc;

      commit_3_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_3_bits_setPC_bits                   <= __writeback_set_pc;
      commit_3_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_3_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_3_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_3_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_3_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_3_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_3_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_3_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_3_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_3_bits_pc                           <= __writeback_pc;

      commit_4_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_4_bits_setPC_bits                   <= __writeback_set_pc;
      commit_4_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_4_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_4_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_4_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_4_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_4_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_4_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_4_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_4_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_4_bits_pc                           <= __writeback_pc;

      commit_5_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_5_bits_setPC_bits                   <= __writeback_set_pc;
      commit_5_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_5_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_5_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_5_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_5_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_5_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_5_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_5_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_5_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_5_bits_pc                           <= __writeback_pc;

      commit_6_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_6_bits_setPC_bits                   <= __writeback_set_pc;
      commit_6_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_6_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_6_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_6_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_6_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_6_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_6_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_6_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_6_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_6_bits_pc                           <= __writeback_pc;

      commit_7_bits_setPC_valid                  <= __writeback_set_pc_valid;
      commit_7_bits_setPC_bits                   <= __writeback_set_pc;
      commit_7_bits_setTmask_valid               <= __writeback_set_tmask_valid;
      commit_7_bits_setTmask_bits                <= __writeback_set_tmask;
      commit_7_bits_ipdomPush_valid              <= 1'b0; // TODO
      commit_7_bits_ipdomPush_bits_restoredMask  <= '0; // TODO
      commit_7_bits_ipdomPush_bits_elseMask      <= '0; // TODO
      commit_7_bits_ipdomPush_bits_elsePC        <= '0; // TODO
      commit_7_bits_wspawn_valid                 <= __writeback_wspawn_valid;
      commit_7_bits_wspawn_bits_count            <= __writeback_wspawn_count;
      commit_7_bits_wspawn_bits_pc               <= __writeback_wspawn_pc;
      commit_7_bits_pc                           <= __writeback_pc;

      imem_resp_valid     <= __imem_resp_valid;
      imem_resp_bits_data <= __imem_resp_bits_data;
      imem_resp_bits_tag  <= __imem_resp_bits_tag;

      finished <= __finished;

    end
  end
  assign imem_req_ready = __imem_req_ready;
endmodule
