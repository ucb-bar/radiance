module CyclotronFrontendBlackBox #(
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

  // Imem/fetch interface
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

  // Decode interface: per-warp instruction buffer heads
  input  logic [NUM_WARPS-1:0]                ibuf_ready,
  output logic [NUM_WARPS-1:0]                ibuf_valid,
  output logic [(NUM_WARPS*ARCH_LEN)-1:0]     ibuf_pc,
  output logic [(NUM_WARPS*WARP_ID_BITS)-1:0] ibuf_wid,
  output logic [(NUM_WARPS*OP_BITS)-1:0]      ibuf_op,
  output logic [(NUM_WARPS*REG_BITS)-1:0]     ibuf_rd,
  output logic [(NUM_WARPS*REG_BITS)-1:0]     ibuf_rs1,
  output logic [(NUM_WARPS*REG_BITS)-1:0]     ibuf_rs2,
  output logic [(NUM_WARPS*REG_BITS)-1:0]     ibuf_rs3,
  output logic [(NUM_WARPS*32)-1:0]           ibuf_imm32,
  output logic [(NUM_WARPS*24)-1:0]           ibuf_imm24,
  output logic [(NUM_WARPS*CSR_IMM_BITS)-1:0] ibuf_csrImm,
  output logic [(NUM_WARPS*3)-1:0]            ibuf_f3,
  output logic [(NUM_WARPS*7)-1:0]            ibuf_f7,
  output logic [(NUM_WARPS*PRED_BITS)-1:0]    ibuf_pred,
  output logic [(NUM_WARPS*NUM_LANES)-1:0]    ibuf_tmask,
  output logic [(NUM_WARPS*INST_BITS)-1:0]    ibuf_raw,

  output logic finished
);
  `include "Cyclotron.vh"

  import "DPI-C" function void cyclotron_frontend(
    input  bit     ready[NUM_WARPS],
    output bit     valid[NUM_WARPS],
    output int     pc[NUM_WARPS],
    output byte    wid[NUM_WARPS],
    output byte    op[NUM_WARPS],
    output byte    opext[NUM_WARPS],
    output byte    f3[NUM_WARPS],
    output byte    rd_addr[NUM_WARPS],
    output byte    rs1_addr[NUM_WARPS],
    output byte    rs2_addr[NUM_WARPS],
    output byte    rs3_addr[NUM_WARPS],
    output byte    f7[NUM_WARPS],
    output int     imm32[NUM_WARPS],
    output int     imm24[NUM_WARPS],
    output byte    csr_imm[NUM_WARPS],
    output int     tmask[NUM_WARPS],
    output longint raw[NUM_WARPS],
    output bit     finished
  );

  bit  __imem_req_ready;
  bit  __imem_resp_valid;
  byte __imem_resp_bits_tag;
  longint __imem_resp_bits_data;

  // "in": C->verilog, "out": verilog->C
  // need to be in ascending order to match with C array memory layout
  bit     __out_ibuf_ready [0:NUM_WARPS-1];
  bit     __in_ibuf_valid  [0:NUM_WARPS-1];
  byte    __in_ibuf_wid    [0:NUM_WARPS-1];
  int     __in_ibuf_pc     [0:NUM_WARPS-1];
  byte    __in_ibuf_op     [0:NUM_WARPS-1];
  byte    __in_ibuf_opext  [0:NUM_WARPS-1];
  byte    __in_ibuf_rd     [0:NUM_WARPS-1];
  byte    __in_ibuf_rs1    [0:NUM_WARPS-1];
  byte    __in_ibuf_rs2    [0:NUM_WARPS-1];
  byte    __in_ibuf_rs3    [0:NUM_WARPS-1];
  int     __in_ibuf_imm32  [0:NUM_WARPS-1];
  int     __in_ibuf_imm24  [0:NUM_WARPS-1];
  byte    __in_ibuf_csrImm [0:NUM_WARPS-1];
  byte    __in_ibuf_f3     [0:NUM_WARPS-1];
  byte    __in_ibuf_f7     [0:NUM_WARPS-1];
  int     __in_ibuf_pred   [0:NUM_WARPS-1];
  int     __in_ibuf_tmask  [0:NUM_WARPS-1];
  longint __in_ibuf_raw    [0:NUM_WARPS-1];
  bit     __in_ibuf_valid_next  [0:NUM_WARPS-1];
  byte    __in_ibuf_wid_next    [0:NUM_WARPS-1];
  int     __in_ibuf_pc_next     [0:NUM_WARPS-1];
  byte    __in_ibuf_op_next     [0:NUM_WARPS-1];
  byte    __in_ibuf_opext_next  [0:NUM_WARPS-1];
  byte    __in_ibuf_rd_next     [0:NUM_WARPS-1];
  byte    __in_ibuf_rs1_next    [0:NUM_WARPS-1];
  byte    __in_ibuf_rs2_next    [0:NUM_WARPS-1];
  byte    __in_ibuf_rs3_next    [0:NUM_WARPS-1];
  int     __in_ibuf_imm32_next  [0:NUM_WARPS-1];
  int     __in_ibuf_imm24_next  [0:NUM_WARPS-1];
  byte    __in_ibuf_csrImm_next [0:NUM_WARPS-1];
  byte    __in_ibuf_f3_next     [0:NUM_WARPS-1];
  byte    __in_ibuf_f7_next     [0:NUM_WARPS-1];
  int     __in_ibuf_pred_next   [0:NUM_WARPS-1];
  int     __in_ibuf_tmask_next  [0:NUM_WARPS-1];
  longint __in_ibuf_raw_next    [0:NUM_WARPS-1];

  bit __in_finished;
  bit __in_finished_next;

  // initialize model at the rtl sim start
  // use BINARY= argument (i.e. first non-plusarg argument) as the Cyclotron
  // ELF
  initial cyclotron_init_task();

  always @(negedge clock) begin
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
      __in_finished <= '0;
      for (integer g = 0; g < NUM_WARPS; g = g + 1) begin
        __in_ibuf_valid[g] = '0;
        __in_ibuf_wid[g] = '0;
        __in_ibuf_pc[g] = '0;
        __in_ibuf_op[g] = '0;
        __in_ibuf_opext[g] = '0;
        __in_ibuf_rd[g] = '0;
        __in_ibuf_rs1[g] = '0;
        __in_ibuf_rs2[g] = '0;
        __in_ibuf_rs3[g] = '0;
        __in_ibuf_imm32[g] = '0;
        __in_ibuf_imm24[g] = '0;
        __in_ibuf_csrImm[g] = '0;
        __in_ibuf_f3[g] = '0;
        __in_ibuf_f7[g] = '0;
        __in_ibuf_pred[g] = '0;
        __in_ibuf_tmask[g] = '0;
        __in_ibuf_raw[g] = '0;
      end
    end else begin
    end
  end
  assign imem_req_ready = __imem_req_ready;

  // connect ibuffer signals
  // packed vector <-> array conversion uses ascending lane order
  genvar g;
  generate
    for (g = 0; g < NUM_WARPS; g = g + 1) begin
      assign __out_ibuf_ready[g] = ibuf_ready[g];
      assign ibuf_valid[g] = __in_ibuf_valid[g];
      assign ibuf_wid[WARP_ID_BITS*g +: WARP_ID_BITS] = __in_ibuf_wid[g][WARP_ID_BITS-1:0];
      assign ibuf_pc[ARCH_LEN*g +: ARCH_LEN]      = __in_ibuf_pc[g][ARCH_LEN-1:0];
      assign ibuf_op[OP_BITS*g  +: OP_BITS]       =
             {__in_ibuf_opext[g][OPEXT_BITS-1:0], __in_ibuf_op[g][OPNOEXT_BITS-1:0]};
      assign ibuf_rd[REG_BITS*g +: REG_BITS]      = __in_ibuf_rd[g][REG_BITS-1:0];
      assign ibuf_rs1[REG_BITS*g +: REG_BITS]     = __in_ibuf_rs1[g][REG_BITS-1:0];
      assign ibuf_rs2[REG_BITS*g +: REG_BITS]     = __in_ibuf_rs2[g][REG_BITS-1:0];
      assign ibuf_rs3[REG_BITS*g +: REG_BITS]     = __in_ibuf_rs3[g][REG_BITS-1:0];
      assign ibuf_imm32[32*g +: 32]               = __in_ibuf_imm32[g][32-1:0];
      assign ibuf_imm24[24*g +: 24]               = __in_ibuf_imm24[g][24-1:0];
      assign ibuf_csrImm[CSR_IMM_BITS*g +: CSR_IMM_BITS] = __in_ibuf_csrImm[g][CSR_IMM_BITS-1:0];
      assign ibuf_f3[3*g +: 3]                    = __in_ibuf_f3[g][3-1:0];
      assign ibuf_f7[7*g +: 7]                    = __in_ibuf_f7[g][7-1:0];
      assign ibuf_pred[PRED_BITS*g +: PRED_BITS]  = __in_ibuf_pred[g][PRED_BITS-1:0];
      assign ibuf_tmask[NUM_LANES*g +: NUM_LANES] = __in_ibuf_tmask[g][NUM_LANES-1:0];
      assign ibuf_raw[INST_BITS*g +: INST_BITS]   = __in_ibuf_raw[g][INST_BITS-1:0];
    end
  endgenerate

  assign finished = __in_finished;

  always @(posedge clock) begin
    if (reset) begin
      for (integer g = 0; g < NUM_WARPS; g = g + 1) begin
        __in_ibuf_pc[g] = '0;
        __in_ibuf_wid[g] = '0;
        __in_ibuf_op[g] = '0;
        __in_ibuf_rd[g] = '0;
        __in_ibuf_rs1[g] = '0;
        __in_ibuf_rs2[g] = '0;
        __in_ibuf_rs3[g] = '0;
        __in_ibuf_imm32[g] = '0;
        __in_ibuf_imm24[g] = '0;
        __in_ibuf_csrImm[g] = '0;
        __in_ibuf_f3[g] = '0;
        __in_ibuf_f7[g] = '0;
        __in_ibuf_pred[g] = '0;
        __in_ibuf_tmask[g] = '0;
        __in_ibuf_raw[g] = '0;
        __in_finished   = '0;
      end
    end else begin
      cyclotron_frontend(
        __out_ibuf_ready,
        __in_ibuf_valid_next,
        __in_ibuf_pc_next,
        __in_ibuf_wid_next,
        __in_ibuf_op_next,
        __in_ibuf_opext_next,
        __in_ibuf_f3_next,
        __in_ibuf_rd_next,
        __in_ibuf_rs1_next,
        __in_ibuf_rs2_next,
        __in_ibuf_rs3_next,
        __in_ibuf_f7_next,
        __in_ibuf_imm32_next,
        __in_ibuf_imm24_next,
        __in_ibuf_csrImm_next,
        __in_ibuf_tmask_next,
        __in_ibuf_raw_next,
        __in_finished_next
      );
      for (integer g = 0; g < NUM_WARPS; g = g + 1) begin
        __in_ibuf_valid[g] <= __in_ibuf_valid_next[g];
        __in_ibuf_wid[g] <= __in_ibuf_wid_next[g];
        __in_ibuf_pc[g] <= __in_ibuf_pc_next[g];
        __in_ibuf_op[g] <= __in_ibuf_op_next[g];
        __in_ibuf_opext[g] <= __in_ibuf_opext_next[g];
        __in_ibuf_rd[g] <= __in_ibuf_rd_next[g];
        __in_ibuf_rs1[g] <= __in_ibuf_rs1_next[g];
        __in_ibuf_rs2[g] <= __in_ibuf_rs2_next[g];
        __in_ibuf_rs3[g] <= __in_ibuf_rs3_next[g];
        __in_ibuf_imm32[g] <= __in_ibuf_imm32_next[g];
        __in_ibuf_imm24[g] <= __in_ibuf_imm24_next[g];
        __in_ibuf_csrImm[g] <= __in_ibuf_csrImm_next[g];
        __in_ibuf_f3[g] <= __in_ibuf_f3_next[g];
        __in_ibuf_f7[g] <= __in_ibuf_f7_next[g];
        __in_ibuf_pred[g] <= __in_ibuf_pred_next[g];
        __in_ibuf_tmask[g] <= __in_ibuf_tmask_next[g];
        __in_ibuf_raw[g] <= __in_ibuf_raw_next[g];
      end
      __in_finished <= __in_finished_next;
    end
  end

endmodule
