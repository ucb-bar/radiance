`define DATA_WIDTH 64
`define MAX_NUM_THREADS 32

import "DPI-C" function void memtrace_init(
  input  string  filename
);

import "DPI-C" function void memtrace_tick
(
  output bit     trace_read_valid,
  input  bit     trace_read_ready,
  output longint trace_read_cycle,
  output longint trace_read_address,
  output bit     trace_read_finished
);

module SimMemTrace #(parameter NUM_THREADS = 4) (
  input              clock,
  input              reset,

  output                   trace_read_valid,
  input                    trace_read_ready,
  output [`DATA_WIDTH-1:0] trace_read_cycle,
  output [`DATA_WIDTH*NUM_THREADS-1:0] trace_read_address,
  output                   trace_read_finished
);
  bit __in_valid;
  longint __in_cycle;
  longint __in_address[NUM_THREADS-1:0];
  bit __in_finished;
  string __uartlog;
  int __uartno;

  initial begin
      /* $value$plusargs("uartlog=%s", __uartlog); */
      memtrace_init("vecadd.core1.thread4.trace");
  end

  reg __in_valid_reg;
  reg [`DATA_WIDTH-1:0] __in_cycle_reg;
  reg [`DATA_WIDTH-1:0] __in_address_reg [NUM_THREADS-1:0];
  reg __in_finished_reg;

  genvar g;

  assign trace_read_valid    = __in_valid_reg;
  assign trace_read_cycle    = __in_cycle_reg;
  generate
    for (g = 0; g < NUM_THREADS; g = g + 1) begin
      assign trace_read_address[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g]  = __in_address_reg[g];
    end
  endgenerate
  assign trace_read_finished = __in_finished_reg;

  // Evaluate the signals on the positive edge
  always @(posedge clock) begin
    if (reset) begin
      __in_valid = 1'b0;
      __in_cycle = `DATA_WIDTH'b0;
      for (integer i = 0; i < NUM_THREADS; i = i + 1) begin
        __in_address[i] = `DATA_WIDTH'b0;
      end
      __in_finished = 1'b0;

      __in_valid_reg <= 1'b0;
      __in_cycle_reg <= `DATA_WIDTH'b0;
      for (integer i = 0; i < NUM_THREADS; i = i + 1) begin
        __in_address_reg[i] <= `DATA_WIDTH'b0;
      end
      __in_finished_reg <= 1'b0;
    end else begin
      for (integer i = 0; i < NUM_THREADS; i = i + 1) begin
        memtrace_tick(
          __in_valid,
          trace_read_ready,
          __in_cycle,
          __in_address[i],
          __in_finished
        );
      end

      __in_valid_reg   <= __in_valid;
      __in_cycle_reg   <= __in_cycle;
      for (integer i = 0; i < NUM_THREADS; i = i + 1) begin
        __in_address_reg[i] <= __in_address[i];
      end
      __in_finished_reg <= __in_finished;
    end
  end
endmodule
