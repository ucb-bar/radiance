module CyclotronInstMemBlackBox #(
  parameter ARCH_LEN = 32,
  parameter INST_BITS = 64,
  parameter IMEM_TAG_BITS = 64
) (
  input clock,
  input reset,

  input  logic                     req_valid,
  input  logic [IMEM_TAG_BITS-1:0] req_bits_tag,
  input  logic [ARCH_LEN-1:0]      req_bits_pc,
  output logic                     resp_valid,
  output logic [IMEM_TAG_BITS-1:0] resp_bits_tag,
  output logic [INST_BITS-1:0]     resp_bits_inst
);
  `include "Cyclotron.vh"

  import "DPI-C" function void cyclotron_fetch(
    input  bit     req_valid,
    input  longint req_bits_tag,
    input  int     req_bits_pc,
    output bit     resp_valid,
    output longint resp_bits_tag,
    output longint resp_bits_inst
  );

  initial cyclotron_init_task();

  // nonblocking assignments from DPI output vars ensure other posedge logic
  // reads pre-DPI values
  always @(posedge clock) begin
    if (reset) begin
      resp_valid <= '0;
      resp_bits_tag <= '0;
      resp_bits_inst <= '0;
    end else begin
      bit     __resp_valid;
      longint __resp_bits_tag;
      longint __resp_bits_inst;

      cyclotron_fetch(
        req_valid,
        req_bits_tag,
        req_bits_pc,
        __resp_valid,
        __resp_bits_tag,
        __resp_bits_inst
      );
      resp_valid <= __resp_valid;
      resp_bits_tag <= __resp_bits_tag;
      resp_bits_inst <= __resp_bits_inst;
    end
  end

endmodule
