`define DATA_WIDTH 64
`define MAX_NUM_THREADS 32

import "DPI-C" function void memtrace_init(
  input  string  filename
);

// Make sure to sync the parameters for:
// (1) import "DPI-C" declaration
// (2) C function declaration
// (3) DPI function calls inside initial/always blocks
import "DPI-C" function void memtrace_tick
(
  input  bit     trace_read_ready,
  input  longint trace_read_cycle,
  input  int     trace_read_tid,
  output bit     trace_read_valid,
  output longint trace_read_address,
  output bit     trace_read_finished
);

module SimMemTrace #(parameter NUM_THREADS = 4) (
  input              clock,
  input              reset,

  // These have to match the IO port of the Chisel wrapper module.
  input                    trace_read_ready,
  output                   trace_read_valid,
  output [`DATA_WIDTH*NUM_THREADS-1:0] trace_read_address,
  output                   trace_read_finished
);
  bit __in_valid;
  longint __in_address[NUM_THREADS-1:0];
  bit __in_finished;
  string __uartlog;

  // Cycle counter that is used to query C parser whether we have a request
  // coming in at the current cycle.
  reg [`DATA_WIDTH-1:0] cycle_counter;

  // registers that stage outputs of the C parser
  reg __in_valid_reg;
  reg [`DATA_WIDTH-1:0] __in_address_reg [NUM_THREADS-1:0];
  reg __in_finished_reg;

  genvar g;

  assign trace_read_valid    = __in_valid_reg;
  generate
    for (g = 0; g < NUM_THREADS; g = g + 1) begin
      assign trace_read_address[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g]  = __in_address_reg[g];
    end
  endgenerate
  assign trace_read_finished = __in_finished_reg;

  initial begin
      /* $value$plusargs("uartlog=%s", __uartlog); */
      memtrace_init("vecadd.core1.thread4.trace");
  end

  // Evaluate the signals on the positive edge
  always @(posedge clock) begin
    if (reset) begin
      __in_valid = 1'b0;
      for (integer tid = 0; tid < NUM_THREADS; tid = tid + 1) begin
        __in_address[tid] = `DATA_WIDTH'b0;
      end
      __in_finished = 1'b0;

      cycle_counter <= `DATA_WIDTH'b0;

      __in_valid_reg <= 1'b0;
      for (integer tid = 0; tid < NUM_THREADS; tid = tid + 1) begin
        __in_address_reg[tid] <= `DATA_WIDTH'b0;
      end
      __in_finished_reg <= 1'b0;
    end else begin
      cycle_counter <= cycle_counter + 1'b1;

      for (integer tid = 0; tid < NUM_THREADS; tid = tid + 1) begin
        memtrace_tick(
          trace_read_ready,
          cycle_counter,
          tid,
          __in_valid,
          __in_address[tid],
          __in_finished
        );
      end

      __in_valid_reg   <= __in_valid;
      for (integer tid = 0; tid < NUM_THREADS; tid = tid + 1) begin
        __in_address_reg[tid] <= __in_address[tid];
      end
      __in_finished_reg <= __in_finished;
    end
  end
endmodule
