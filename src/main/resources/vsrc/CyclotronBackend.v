module CyclotronBackendBlackBox #(
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

  // Issue interface
  output logic                       issue_0_ready,
  input  logic                       issue_0_valid,
  input  logic [ARCH_LEN-1:0]        issue_0_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_0_bits_wid,
  input  logic [OP_BITS-1:0]         issue_0_bits_op,
  input  logic [REG_BITS-1:0]        issue_0_bits_rd,
  input  logic [REG_BITS-1:0]        issue_0_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_0_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_0_bits_rs3,
  input  logic [31:0]                issue_0_bits_imm32,
  input  logic [23:0]                issue_0_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_0_bits_csrImm,
  input  logic [2:0]                 issue_0_bits_f3,
  input  logic [6:0]                 issue_0_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_0_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_0_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_0_bits_raw,

  output logic                       issue_1_ready,
  input  logic                       issue_1_valid,
  input  logic [ARCH_LEN-1:0]        issue_1_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_1_bits_wid,
  input  logic [OP_BITS-1:0]         issue_1_bits_op,
  input  logic [REG_BITS-1:0]        issue_1_bits_rd,
  input  logic [REG_BITS-1:0]        issue_1_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_1_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_1_bits_rs3,
  input  logic [31:0]                issue_1_bits_imm32,
  input  logic [23:0]                issue_1_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_1_bits_csrImm,
  input  logic [2:0]                 issue_1_bits_f3,
  input  logic [6:0]                 issue_1_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_1_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_1_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_1_bits_raw,

  output logic                       issue_2_ready,
  input  logic                       issue_2_valid,
  input  logic [ARCH_LEN-1:0]        issue_2_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_2_bits_wid,
  input  logic [OP_BITS-1:0]         issue_2_bits_op,
  input  logic [REG_BITS-1:0]        issue_2_bits_rd,
  input  logic [REG_BITS-1:0]        issue_2_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_2_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_2_bits_rs3,
  input  logic [31:0]                issue_2_bits_imm32,
  input  logic [23:0]                issue_2_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_2_bits_csrImm,
  input  logic [2:0]                 issue_2_bits_f3,
  input  logic [6:0]                 issue_2_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_2_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_2_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_2_bits_raw,

  output logic                       issue_3_ready,
  input  logic                       issue_3_valid,
  input  logic [ARCH_LEN-1:0]        issue_3_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_3_bits_wid,
  input  logic [OP_BITS-1:0]         issue_3_bits_op,
  input  logic [REG_BITS-1:0]        issue_3_bits_rd,
  input  logic [REG_BITS-1:0]        issue_3_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_3_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_3_bits_rs3,
  input  logic [31:0]                issue_3_bits_imm32,
  input  logic [23:0]                issue_3_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_3_bits_csrImm,
  input  logic [2:0]                 issue_3_bits_f3,
  input  logic [6:0]                 issue_3_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_3_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_3_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_3_bits_raw,

  output logic                       issue_4_ready,
  input  logic                       issue_4_valid,
  input  logic [ARCH_LEN-1:0]        issue_4_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_4_bits_wid,
  input  logic [OP_BITS-1:0]         issue_4_bits_op,
  input  logic [REG_BITS-1:0]        issue_4_bits_rd,
  input  logic [REG_BITS-1:0]        issue_4_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_4_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_4_bits_rs3,
  input  logic [31:0]                issue_4_bits_imm32,
  input  logic [23:0]                issue_4_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_4_bits_csrImm,
  input  logic [2:0]                 issue_4_bits_f3,
  input  logic [6:0]                 issue_4_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_4_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_4_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_4_bits_raw,

  output logic                       issue_5_ready,
  input  logic                       issue_5_valid,
  input  logic [ARCH_LEN-1:0]        issue_5_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_5_bits_wid,
  input  logic [OP_BITS-1:0]         issue_5_bits_op,
  input  logic [REG_BITS-1:0]        issue_5_bits_rd,
  input  logic [REG_BITS-1:0]        issue_5_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_5_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_5_bits_rs3,
  input  logic [31:0]                issue_5_bits_imm32,
  input  logic [23:0]                issue_5_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_5_bits_csrImm,
  input  logic [2:0]                 issue_5_bits_f3,
  input  logic [6:0]                 issue_5_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_5_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_5_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_5_bits_raw,

  output logic                       issue_6_ready,
  input  logic                       issue_6_valid,
  input  logic [ARCH_LEN-1:0]        issue_6_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_6_bits_wid,
  input  logic [OP_BITS-1:0]         issue_6_bits_op,
  input  logic [REG_BITS-1:0]        issue_6_bits_rd,
  input  logic [REG_BITS-1:0]        issue_6_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_6_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_6_bits_rs3,
  input  logic [31:0]                issue_6_bits_imm32,
  input  logic [23:0]                issue_6_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_6_bits_csrImm,
  input  logic [2:0]                 issue_6_bits_f3,
  input  logic [6:0]                 issue_6_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_6_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_6_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_6_bits_raw,

  output logic                       issue_7_ready,
  input  logic                       issue_7_valid,
  input  logic [ARCH_LEN-1:0]        issue_7_bits_pc,
  input  logic [WARP_ID_BITS-1:0]    issue_7_bits_wid,
  input  logic [OP_BITS-1:0]         issue_7_bits_op,
  input  logic [REG_BITS-1:0]        issue_7_bits_rd,
  input  logic [REG_BITS-1:0]        issue_7_bits_rs1,
  input  logic [REG_BITS-1:0]        issue_7_bits_rs2,
  input  logic [REG_BITS-1:0]        issue_7_bits_rs3,
  input  logic [31:0]                issue_7_bits_imm32,
  input  logic [23:0]                issue_7_bits_imm24,
  input  logic [CSR_IMM_BITS-1:0]    issue_7_bits_csrImm,
  input  logic [2:0]                 issue_7_bits_f3,
  input  logic [6:0]                 issue_7_bits_f7,
  input  logic [PRED_BITS-1:0]       issue_7_bits_pred,
  input  logic [NUM_LANES-1:0]       issue_7_bits_tmask,
  input  logic [INST_BITS-1:0]       issue_7_bits_raw,

  // Writeback interface
  output logic                       commit_0_valid,
  output logic                       commit_0_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_0_bits_setPC_bits,
  output logic                       commit_0_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_0_bits_setTmask_bits,
  output logic                       commit_0_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_0_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_0_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_0_bits_ipdomPush_bits_elsePC,
  output logic                       commit_0_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_0_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_0_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_0_bits_pc,

  output logic                       commit_1_valid,
  output logic                       commit_1_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_1_bits_setPC_bits,
  output logic                       commit_1_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_1_bits_setTmask_bits,
  output logic                       commit_1_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_1_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_1_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_1_bits_ipdomPush_bits_elsePC,
  output logic                       commit_1_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_1_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_1_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_1_bits_pc,

  output logic                       commit_2_valid,
  output logic                       commit_2_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_2_bits_setPC_bits,
  output logic                       commit_2_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_2_bits_setTmask_bits,
  output logic                       commit_2_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_2_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_2_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_2_bits_ipdomPush_bits_elsePC,
  output logic                       commit_2_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_2_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_2_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_2_bits_pc,

  output logic                       commit_3_valid,
  output logic                       commit_3_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_3_bits_setPC_bits,
  output logic                       commit_3_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_3_bits_setTmask_bits,
  output logic                       commit_3_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_3_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_3_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_3_bits_ipdomPush_bits_elsePC,
  output logic                       commit_3_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_3_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_3_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_3_bits_pc,

  output logic                       commit_4_valid,
  output logic                       commit_4_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_4_bits_setPC_bits,
  output logic                       commit_4_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_4_bits_setTmask_bits,
  output logic                       commit_4_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_4_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_4_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_4_bits_ipdomPush_bits_elsePC,
  output logic                       commit_4_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_4_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_4_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_4_bits_pc,

  output logic                       commit_5_valid,
  output logic                       commit_5_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_5_bits_setPC_bits,
  output logic                       commit_5_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_5_bits_setTmask_bits,
  output logic                       commit_5_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_5_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_5_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_5_bits_ipdomPush_bits_elsePC,
  output logic                       commit_5_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_5_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_5_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_5_bits_pc,

  output logic                       commit_6_valid,
  output logic                       commit_6_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_6_bits_setPC_bits,
  output logic                       commit_6_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_6_bits_setTmask_bits,
  output logic                       commit_6_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_6_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_6_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_6_bits_ipdomPush_bits_elsePC,
  output logic                       commit_6_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_6_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_6_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_6_bits_pc,

  output logic                       commit_7_valid,
  output logic                       commit_7_bits_setPC_valid,
  output logic [ARCH_LEN-1:0]        commit_7_bits_setPC_bits,
  output logic                       commit_7_bits_setTmask_valid,
  output logic [NUM_LANES-1:0]       commit_7_bits_setTmask_bits,
  output logic                       commit_7_bits_ipdomPush_valid,
  output logic [NUM_LANES-1:0]       commit_7_bits_ipdomPush_bits_restoredMask,
  output logic [NUM_LANES-1:0]       commit_7_bits_ipdomPush_bits_elseMask,
  output logic [ARCH_LEN-1:0]        commit_7_bits_ipdomPush_bits_elsePC,
  output logic                       commit_7_bits_wspawn_valid,
  output logic [WARP_COUNT_BITS-1:0] commit_7_bits_wspawn_bits_count,
  output logic [ARCH_LEN-1:0]        commit_7_bits_wspawn_bits_pc,
  output logic [ARCH_LEN-1:0]        commit_7_bits_pc,

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

  // number of issue ports (mirror the scalar issue_0..issue_7 ports)
  localparam int ISSUE_PORTS = 8;

  // Per-issue arrays for DPI writeback outputs (combinational outputs from the C model)
  byte __writeback_valid           [0:ISSUE_PORTS-1];
  int  __writeback_pc              [0:ISSUE_PORTS-1];
  int  __writeback_tmask           [0:ISSUE_PORTS-1];
  byte __writeback_wid             [0:ISSUE_PORTS-1];
  byte __writeback_rd_addr         [0:ISSUE_PORTS-1];
  int  __writeback_rd_data         [0:ISSUE_PORTS-1];
  byte __writeback_set_pc_valid    [0:ISSUE_PORTS-1];
  int  __writeback_set_pc          [0:ISSUE_PORTS-1];
  byte __writeback_set_tmask_valid [0:ISSUE_PORTS-1];
  int  __writeback_set_tmask       [0:ISSUE_PORTS-1];
  byte __writeback_wspawn_valid    [0:ISSUE_PORTS-1];
  int  __writeback_wspawn_count    [0:ISSUE_PORTS-1];
  int  __writeback_wspawn_pc       [0:ISSUE_PORTS-1];
  byte __finished                  [0:ISSUE_PORTS-1];

  byte __imem_req_ready;
  byte __imem_resp_valid;
  byte __imem_resp_bits_tag;
  longint __imem_resp_bits_data;

  // initialize model at the rtl sim start
  initial begin
    cyclotron_init();
  end

  // TODO: simulate backpressure

  // Mirror scalar issue ports into indexed arrays so we can iterate.
  // Per-issue input arrays
  logic issue_valid_arr                     [0:ISSUE_PORTS-1];
  logic [ARCH_LEN-1:0] issue_pc_arr         [0:ISSUE_PORTS-1];
  logic [WARP_ID_BITS-1:0] issue_wid_arr    [0:ISSUE_PORTS-1];
  logic [OP_BITS-1:0] issue_op_arr          [0:ISSUE_PORTS-1];
  logic [REG_BITS-1:0] issue_rd_arr         [0:ISSUE_PORTS-1];
  logic [REG_BITS-1:0] issue_rs1_arr        [0:ISSUE_PORTS-1];
  logic [REG_BITS-1:0] issue_rs2_arr        [0:ISSUE_PORTS-1];
  logic [REG_BITS-1:0] issue_rs3_arr        [0:ISSUE_PORTS-1];
  logic [31:0] issue_imm32_arr              [0:ISSUE_PORTS-1];
  logic [23:0] issue_imm24_arr              [0:ISSUE_PORTS-1];
  logic [CSR_IMM_BITS-1:0] issue_csrImm_arr [0:ISSUE_PORTS-1];
  logic [2:0] issue_f3_arr                  [0:ISSUE_PORTS-1];
  logic [6:0] issue_f7_arr                  [0:ISSUE_PORTS-1];
  logic [PRED_BITS-1:0] issue_pred_arr      [0:ISSUE_PORTS-1];
  logic [NUM_LANES-1:0] issue_tmask_arr     [0:ISSUE_PORTS-1];
  logic [INST_BITS-1:0] issue_raw_arr       [0:ISSUE_PORTS-1];

  // Map scalar ports into arrays
  assign issue_valid_arr[0] = issue_0_valid;
  assign issue_pc_arr[0]    = issue_0_bits_pc;
  assign issue_wid_arr[0]   = issue_0_bits_wid;
  assign issue_op_arr[0]    = issue_0_bits_op;
  assign issue_rd_arr[0]    = issue_0_bits_rd;
  assign issue_rs1_arr[0]   = issue_0_bits_rs1;
  assign issue_rs2_arr[0]   = issue_0_bits_rs2;
  assign issue_rs3_arr[0]   = issue_0_bits_rs3;
  assign issue_imm32_arr[0] = issue_0_bits_imm32;
  assign issue_imm24_arr[0] = issue_0_bits_imm24;
  assign issue_csrImm_arr[0]= issue_0_bits_csrImm;
  assign issue_f3_arr[0]    = issue_0_bits_f3;
  assign issue_f7_arr[0]    = issue_0_bits_f7;
  assign issue_pred_arr[0]  = issue_0_bits_pred;
  assign issue_tmask_arr[0] = issue_0_bits_tmask;
  assign issue_raw_arr[0]   = issue_0_bits_raw;

  assign issue_valid_arr[1] = issue_1_valid;
  assign issue_pc_arr[1]    = issue_1_bits_pc;
  assign issue_wid_arr[1]   = issue_1_bits_wid;
  assign issue_op_arr[1]    = issue_1_bits_op;
  assign issue_rd_arr[1]    = issue_1_bits_rd;
  assign issue_rs1_arr[1]   = issue_1_bits_rs1;
  assign issue_rs2_arr[1]   = issue_1_bits_rs2;
  assign issue_rs3_arr[1]   = issue_1_bits_rs3;
  assign issue_imm32_arr[1] = issue_1_bits_imm32;
  assign issue_imm24_arr[1] = issue_1_bits_imm24;
  assign issue_csrImm_arr[1]= issue_1_bits_csrImm;
  assign issue_f3_arr[1]    = issue_1_bits_f3;
  assign issue_f7_arr[1]    = issue_1_bits_f7;
  assign issue_pred_arr[1]  = issue_1_bits_pred;
  assign issue_tmask_arr[1] = issue_1_bits_tmask;
  assign issue_raw_arr[1]   = issue_1_bits_raw;

  assign issue_valid_arr[2] = issue_2_valid;
  assign issue_pc_arr[2]    = issue_2_bits_pc;
  assign issue_wid_arr[2]   = issue_2_bits_wid;
  assign issue_op_arr[2]    = issue_2_bits_op;
  assign issue_rd_arr[2]    = issue_2_bits_rd;
  assign issue_rs1_arr[2]   = issue_2_bits_rs1;
  assign issue_rs2_arr[2]   = issue_2_bits_rs2;
  assign issue_rs3_arr[2]   = issue_2_bits_rs3;
  assign issue_imm32_arr[2] = issue_2_bits_imm32;
  assign issue_imm24_arr[2] = issue_2_bits_imm24;
  assign issue_csrImm_arr[2]= issue_2_bits_csrImm;
  assign issue_f3_arr[2]    = issue_2_bits_f3;
  assign issue_f7_arr[2]    = issue_2_bits_f7;
  assign issue_pred_arr[2]  = issue_2_bits_pred;
  assign issue_tmask_arr[2] = issue_2_bits_tmask;
  assign issue_raw_arr[2]   = issue_2_bits_raw;

  assign issue_valid_arr[3] = issue_3_valid;
  assign issue_pc_arr[3]    = issue_3_bits_pc;
  assign issue_wid_arr[3]   = issue_3_bits_wid;
  assign issue_op_arr[3]    = issue_3_bits_op;
  assign issue_rd_arr[3]    = issue_3_bits_rd;
  assign issue_rs1_arr[3]   = issue_3_bits_rs1;
  assign issue_rs2_arr[3]   = issue_3_bits_rs2;
  assign issue_rs3_arr[3]   = issue_3_bits_rs3;
  assign issue_imm32_arr[3] = issue_3_bits_imm32;
  assign issue_imm24_arr[3] = issue_3_bits_imm24;
  assign issue_csrImm_arr[3]= issue_3_bits_csrImm;
  assign issue_f3_arr[3]    = issue_3_bits_f3;
  assign issue_f7_arr[3]    = issue_3_bits_f7;
  assign issue_pred_arr[3]  = issue_3_bits_pred;
  assign issue_tmask_arr[3] = issue_3_bits_tmask;
  assign issue_raw_arr[3]   = issue_3_bits_raw;

  assign issue_valid_arr[4] = issue_4_valid;
  assign issue_pc_arr[4]    = issue_4_bits_pc;
  assign issue_wid_arr[4]   = issue_4_bits_wid;
  assign issue_op_arr[4]    = issue_4_bits_op;
  assign issue_rd_arr[4]    = issue_4_bits_rd;
  assign issue_rs1_arr[4]   = issue_4_bits_rs1;
  assign issue_rs2_arr[4]   = issue_4_bits_rs2;
  assign issue_rs3_arr[4]   = issue_4_bits_rs3;
  assign issue_imm32_arr[4] = issue_4_bits_imm32;
  assign issue_imm24_arr[4] = issue_4_bits_imm24;
  assign issue_csrImm_arr[4]= issue_4_bits_csrImm;
  assign issue_f3_arr[4]    = issue_4_bits_f3;
  assign issue_f7_arr[4]    = issue_4_bits_f7;
  assign issue_pred_arr[4]  = issue_4_bits_pred;
  assign issue_tmask_arr[4] = issue_4_bits_tmask;
  assign issue_raw_arr[4]   = issue_4_bits_raw;

  assign issue_valid_arr[5] = issue_5_valid;
  assign issue_pc_arr[5]    = issue_5_bits_pc;
  assign issue_wid_arr[5]   = issue_5_bits_wid;
  assign issue_op_arr[5]    = issue_5_bits_op;
  assign issue_rd_arr[5]    = issue_5_bits_rd;
  assign issue_rs1_arr[5]   = issue_5_bits_rs1;
  assign issue_rs2_arr[5]   = issue_5_bits_rs2;
  assign issue_rs3_arr[5]   = issue_5_bits_rs3;
  assign issue_imm32_arr[5] = issue_5_bits_imm32;
  assign issue_imm24_arr[5] = issue_5_bits_imm24;
  assign issue_csrImm_arr[5]= issue_5_bits_csrImm;
  assign issue_f3_arr[5]    = issue_5_bits_f3;
  assign issue_f7_arr[5]    = issue_5_bits_f7;
  assign issue_pred_arr[5]  = issue_5_bits_pred;
  assign issue_tmask_arr[5] = issue_5_bits_tmask;
  assign issue_raw_arr[5]   = issue_5_bits_raw;

  assign issue_valid_arr[6] = issue_6_valid;
  assign issue_pc_arr[6]    = issue_6_bits_pc;
  assign issue_wid_arr[6]   = issue_6_bits_wid;
  assign issue_op_arr[6]    = issue_6_bits_op;
  assign issue_rd_arr[6]    = issue_6_bits_rd;
  assign issue_rs1_arr[6]   = issue_6_bits_rs1;
  assign issue_rs2_arr[6]   = issue_6_bits_rs2;
  assign issue_rs3_arr[6]   = issue_6_bits_rs3;
  assign issue_imm32_arr[6] = issue_6_bits_imm32;
  assign issue_imm24_arr[6] = issue_6_bits_imm24;
  assign issue_csrImm_arr[6]= issue_6_bits_csrImm;
  assign issue_f3_arr[6]    = issue_6_bits_f3;
  assign issue_f7_arr[6]    = issue_6_bits_f7;
  assign issue_pred_arr[6]  = issue_6_bits_pred;
  assign issue_tmask_arr[6] = issue_6_bits_tmask;
  assign issue_raw_arr[6]   = issue_6_bits_raw;

  assign issue_valid_arr[7] = issue_7_valid;
  assign issue_pc_arr[7]    = issue_7_bits_pc;
  assign issue_wid_arr[7]   = issue_7_bits_wid;
  assign issue_op_arr[7]    = issue_7_bits_op;
  assign issue_rd_arr[7]    = issue_7_bits_rd;
  assign issue_rs1_arr[7]   = issue_7_bits_rs1;
  assign issue_rs2_arr[7]   = issue_7_bits_rs2;
  assign issue_rs3_arr[7]   = issue_7_bits_rs3;
  assign issue_imm32_arr[7] = issue_7_bits_imm32;
  assign issue_imm24_arr[7] = issue_7_bits_imm24;
  assign issue_csrImm_arr[7]= issue_7_bits_csrImm;
  assign issue_f3_arr[7]    = issue_7_bits_f3;
  assign issue_f7_arr[7]    = issue_7_bits_f7;
  assign issue_pred_arr[7]  = issue_7_bits_pred;
  assign issue_tmask_arr[7] = issue_7_bits_tmask;
  assign issue_raw_arr[7]   = issue_7_bits_raw;

  // For now drive per-issue ready signals high (no backpressure)
  assign issue_0_ready = 1'b1;
  assign issue_1_ready = 1'b1;
  assign issue_2_ready = 1'b1;
  assign issue_3_ready = 1'b1;
  assign issue_4_ready = 1'b1;
  assign issue_5_ready = 1'b1;
  assign issue_6_ready = 1'b1;
  assign issue_7_ready = 1'b1;

  always @(negedge clock) begin
    integer i;
    // call backend once per issue port; each call fills the per-issue __writeback_* and __finished entries
    for (i = 0; i < ISSUE_PORTS; i = i + 1) begin
      cyclotron_backend(
        issue_valid_arr[i],
        issue_wid_arr[i],
        issue_pc_arr[i],
        issue_op_arr[i][6:0],
        issue_op_arr[i][8:7],
        issue_f3_arr[i],
        issue_rd_arr[i],
        issue_rs1_arr[i],
        issue_rs2_arr[i],
        issue_rs3_arr[i],
        32'h0,
        32'h0,
        32'h0,
        issue_f7_arr[i],
        issue_imm32_arr[i],
        issue_imm24_arr[i],
        issue_csrImm_arr[i],
        issue_pred_arr[i],
        issue_tmask_arr[i],
        issue_raw_arr[i],
        __writeback_valid[i],
        __writeback_pc[i],
        __writeback_tmask[i],
        __writeback_wid[i],
        __writeback_rd_addr[i],
        __writeback_rd_data[i],
        __writeback_set_pc_valid[i],
        __writeback_set_pc[i],
        __writeback_set_tmask_valid[i],
        __writeback_set_tmask[i],
        __writeback_wspawn_valid[i],
        __writeback_wspawn_count[i],
        __writeback_wspawn_pc[i],
        __finished[i]
      );
    end

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
      // reset per-issue dpi outputs
      integer i;
      for (i = 0; i < ISSUE_PORTS; i = i + 1) begin
        __writeback_valid[i] <= '0;
        __writeback_pc[i] <= '0;
        __writeback_tmask[i] <= '0;
        __writeback_wid[i] <= '0;
        __writeback_rd_addr[i] <= '0;
        __writeback_rd_data[i] <= '0;
        __writeback_set_pc_valid[i] <= '0;
        __writeback_set_pc[i] <= '0;
        __writeback_set_tmask_valid[i] <= '0;
        __writeback_set_tmask[i] <= '0;
        __writeback_wspawn_valid[i] <= '0;
        __writeback_wspawn_count[i] <= '0;
        __writeback_wspawn_pc[i] <= '0;
        __finished[i] <= '0;
      end

      __imem_req_ready <= '0;
      __imem_resp_valid <= '0;
      __imem_resp_bits_tag <= '0;
      __imem_resp_bits_data <= '0;

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
      // default all commit outputs to zero, then populate from per-issue writebacks
      commit_0_valid <= __writeback_valid[0];
      commit_0_bits_setPC_valid <= __writeback_set_pc_valid[0];
      commit_0_bits_setPC_bits  <= __writeback_set_pc[0];
      commit_0_bits_setTmask_valid <= __writeback_set_tmask_valid[0];
      commit_0_bits_setTmask_bits  <= __writeback_set_tmask[0];
      commit_0_bits_wspawn_valid    <= __writeback_wspawn_valid[0];
      commit_0_bits_wspawn_bits_count <= __writeback_wspawn_count[0];
      commit_0_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[0];
      commit_0_bits_pc <= __writeback_pc[0];

      commit_1_valid <= __writeback_valid[1];
      commit_1_bits_setPC_valid <= __writeback_set_pc_valid[1];
      commit_1_bits_setPC_bits  <= __writeback_set_pc[1];
      commit_1_bits_setTmask_valid <= __writeback_set_tmask_valid[1];
      commit_1_bits_setTmask_bits  <= __writeback_set_tmask[1];
      commit_1_bits_wspawn_valid    <= __writeback_wspawn_valid[1];
      commit_1_bits_wspawn_bits_count <= __writeback_wspawn_count[1];
      commit_1_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[1];
      commit_1_bits_pc <= __writeback_pc[1];

      commit_2_valid <= __writeback_valid[2];
      commit_2_bits_setPC_valid <= __writeback_set_pc_valid[2];
      commit_2_bits_setPC_bits  <= __writeback_set_pc[2];
      commit_2_bits_setTmask_valid <= __writeback_set_tmask_valid[2];
      commit_2_bits_setTmask_bits  <= __writeback_set_tmask[2];
      commit_2_bits_wspawn_valid    <= __writeback_wspawn_valid[2];
      commit_2_bits_wspawn_bits_count <= __writeback_wspawn_count[2];
      commit_2_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[2];
      commit_2_bits_pc <= __writeback_pc[2];

      commit_3_valid <= __writeback_valid[3];
      commit_3_bits_setPC_valid <= __writeback_set_pc_valid[3];
      commit_3_bits_setPC_bits  <= __writeback_set_pc[3];
      commit_3_bits_setTmask_valid <= __writeback_set_tmask_valid[3];
      commit_3_bits_setTmask_bits  <= __writeback_set_tmask[3];
      commit_3_bits_wspawn_valid    <= __writeback_wspawn_valid[3];
      commit_3_bits_wspawn_bits_count <= __writeback_wspawn_count[3];
      commit_3_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[3];
      commit_3_bits_pc <= __writeback_pc[3];

      commit_4_valid <= __writeback_valid[4];
      commit_4_bits_setPC_valid <= __writeback_set_pc_valid[4];
      commit_4_bits_setPC_bits  <= __writeback_set_pc[4];
      commit_4_bits_setTmask_valid <= __writeback_set_tmask_valid[4];
      commit_4_bits_setTmask_bits  <= __writeback_set_tmask[4];
      commit_4_bits_wspawn_valid    <= __writeback_wspawn_valid[4];
      commit_4_bits_wspawn_bits_count <= __writeback_wspawn_count[4];
      commit_4_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[4];
      commit_4_bits_pc <= __writeback_pc[4];

      commit_5_valid <= __writeback_valid[5];
      commit_5_bits_setPC_valid <= __writeback_set_pc_valid[5];
      commit_5_bits_setPC_bits  <= __writeback_set_pc[5];
      commit_5_bits_setTmask_valid <= __writeback_set_tmask_valid[5];
      commit_5_bits_setTmask_bits  <= __writeback_set_tmask[5];
      commit_5_bits_wspawn_valid    <= __writeback_wspawn_valid[5];
      commit_5_bits_wspawn_bits_count <= __writeback_wspawn_count[5];
      commit_5_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[5];
      commit_5_bits_pc <= __writeback_pc[5];

      commit_6_valid <= __writeback_valid[6];
      commit_6_bits_setPC_valid <= __writeback_set_pc_valid[6];
      commit_6_bits_setPC_bits  <= __writeback_set_pc[6];
      commit_6_bits_setTmask_valid <= __writeback_set_tmask_valid[6];
      commit_6_bits_setTmask_bits  <= __writeback_set_tmask[6];
      commit_6_bits_wspawn_valid    <= __writeback_wspawn_valid[6];
      commit_6_bits_wspawn_bits_count <= __writeback_wspawn_count[6];
      commit_6_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[6];
      commit_6_bits_pc <= __writeback_pc[6];

      commit_7_valid <= __writeback_valid[7];
      commit_7_bits_setPC_valid <= __writeback_set_pc_valid[7];
      commit_7_bits_setPC_bits  <= __writeback_set_pc[7];
      commit_7_bits_setTmask_valid <= __writeback_set_tmask_valid[7];
      commit_7_bits_setTmask_bits  <= __writeback_set_tmask[7];
      commit_7_bits_wspawn_valid    <= __writeback_wspawn_valid[7];
      commit_7_bits_wspawn_bits_count <= __writeback_wspawn_count[7];
      commit_7_bits_wspawn_bits_pc  <= __writeback_wspawn_pc[7];
      commit_7_bits_pc <= __writeback_pc[7];

      imem_resp_valid     <= __imem_resp_valid;
      imem_resp_bits_data <= __imem_resp_bits_data;
      imem_resp_bits_tag  <= __imem_resp_bits_tag;

      finished <= __finished.or();
    end
  end
  assign imem_req_ready = __imem_req_ready;
endmodule
