module CVFPU
  import fpnew_pkg::*;
  import cf_math_pkg::*;
  import defs_div_sqrt_mvp::*;
#(
  parameter WIDTH = 512,
  parameter LANES = 16,
  parameter TAG_WIDTH = 1,
  parameter IS_DIVSQRT_UNIT = 0
) (
  input logic                               clock,
  input logic                               reset,
  // Input signals
  input logic [WIDTH-1:0]                   req_bits_operands_0,
  input logic [WIDTH-1:0]                   req_bits_operands_1,
  input logic [WIDTH-1:0]                   req_bits_operands_2,

  input logic [2:0]                         req_bits_roundingMode,
  input logic [4:0]                         req_bits_op, // op_mod is lsb
  input logic [2:0]                         req_bits_srcFormat,
  input logic [2:0]                         req_bits_dstFormat,
  input logic [1:0]                         req_bits_intFormat,
  input logic [TAG_WIDTH-1:0]               req_bits_tag,
  input logic [15:0]                        req_bits_simdMask,
  // Input Handshake
  input  logic                              req_valid,
  output logic                              req_ready,
  input  logic                              flush,
  // Output signals
  output logic [WIDTH-1:0]                  resp_bits_result,
  output logic [4:0]                        resp_bits_status,
  output logic [TAG_WIDTH-1:0]              resp_bits_tag,
  // Output handshake
  output logic                              resp_valid,
  input  logic                              resp_ready,
  // Indication of valid data in flight
  output logic                              busy
);

  // TODO: wrap raw fpu op into enum in fpnew_pkg

  fpnew_top #(
    .Features('{
      Width:         (WIDTH),
      EnableVectors: 1'b1,
      EnableNanBox:  1'b1,
      FpFmtMask:     5'b10100, // {fp32, fp64, fp16, fp8, fp16_alt}
      IntFmtMask:    4'b0110   // {int8, int16, int32, int64}
    }),
    .Implementation('{
      PipeRegs:   '{default: 2},
      UnitTypes:  IS_DIVSQRT_UNIT ?
                  '{'{default: fpnew_pkg::DISABLED}, // ADDMUL
                    '{default: fpnew_pkg::MERGED},   // DIVSQRT
                    '{default: fpnew_pkg::DISABLED}, // NONCOMP
                    '{default: fpnew_pkg::DISABLED}} // CONV
                  :
                  '{'{default: fpnew_pkg::MERGED},   // ADDMUL
                    '{default: fpnew_pkg::DISABLED}, // DIVSQRT
                    '{default: fpnew_pkg::PARALLEL}, // NONCOMP
                    '{default: fpnew_pkg::MERGED}},  // CONV
      PipeConfig: fpnew_pkg::DISTRIBUTED
    }),
    .PulpDivsqrt(1),
    .TagType(logic [TAG_WIDTH-1:0]), // <- this can be used to pass our stuff
    .TrueSIMDClass(1),
    .EnableSIMDMask(1)
  ) fpu (
    .clk_i          (clock         ),
    .rst_ni         (~reset        ),
    .operands_i     ('{
        req_bits_operands_2,
        req_bits_operands_1,
        req_bits_operands_0
    }),
    .rnd_mode_i     (req_bits_roundingMode),
    .op_i           (req_bits_op[4:1]     ),
    .op_mod_i       (req_bits_op[0]       ),
    .src_fmt_i      (req_bits_srcFormat   ),
    .dst_fmt_i      (req_bits_dstFormat   ),
    .int_fmt_i      (req_bits_intFormat   ),
    .vectorial_op_i (1'b1                 ),
    .tag_i          (req_bits_tag         ), // <- used here
    .simd_mask_i    (req_bits_simdMask    ),
    .in_valid_i     (req_valid            ),
    .in_ready_o     (req_ready            ),
    .flush_i        (flush                ),
    .result_o       (resp_bits_result     ),
    .status_o       (resp_bits_status     ),
    .tag_o          (resp_bits_tag        ), // <- and here
    .out_valid_o    (resp_valid           ),
    .out_ready_i    (resp_ready           ),
    .busy_o         (busy                 )
  );

endmodule
