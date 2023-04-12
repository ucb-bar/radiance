`define DATA_WIDTH 64
`define MAX_NUM_LANES 32
`define LOGSIZE_WIDTH 32

import "DPI-C" function void memtrace_init(
  input  string  filename
);

// Make sure to sync the parameters for:
// (1) import "DPI-C" declaration
// (2) C function declaration
// (3) DPI function calls inside initial/always blocks
import "DPI-C" function void memtrace_query
(
  input  bit     trace_read_ready,
  input  longint trace_read_cycle,
  input  int     trace_read_tid,
  output bit     trace_read_valid,
  output longint trace_read_address,
  output bit     trace_read_is_store,
  output int     trace_read_size,
  output longint trace_read_data,
  output bit     trace_read_finished
);

module SimMemTrace #(parameter FILENAME = "undefined", NUM_LANES = 4) (
  input clock,
  input reset,

  // These have to match the IO port name of the Chisel wrapper module.
  input                                 trace_read_ready,
  output [NUM_LANES-1:0]                trace_read_valid,
  output [`DATA_WIDTH*NUM_LANES-1:0]    trace_read_address,

  output [NUM_LANES-1:0]                trace_read_is_store,
  output [`LOGSIZE_WIDTH*NUM_LANES-1:0] trace_read_size,
  output [`DATA_WIDTH*NUM_LANES-1:0]    trace_read_data,
  output                                trace_read_finished
);
  bit     __in_valid   [NUM_LANES-1:0];
  longint __in_address [NUM_LANES-1:0];

  bit     __in_is_store [NUM_LANES-1:0];
  int     __in_size [NUM_LANES-1:0];
  longint __in_data [NUM_LANES-1:0];

  bit __in_finished;
  string __uartlog;

  // Cycle counter that is used to query C parser whether we have a request
  // coming in at the current cycle.
  reg [`DATA_WIDTH-1:0] cycle_counter;
  wire [`DATA_WIDTH-1:0] next_cycle_counter;
  assign next_cycle_counter = cycle_counter + 1'b1;

  // registers that stage outputs of the C parser
  reg [NUM_LANES-1:0]   __in_valid_reg;
  reg [`DATA_WIDTH-1:0] __in_address_reg [NUM_LANES-1:0];

  reg [NUM_LANES-1:0]   __in_is_store_reg;
  int                   __in_size_reg [NUM_LANES-1:0];
  reg [`DATA_WIDTH-1:0] __in_data_reg [NUM_LANES-1:0];
  reg                   __in_finished_reg;

  genvar g;

  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      assign trace_read_valid[g] = __in_valid_reg[g];
      assign trace_read_address[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g]  = __in_address_reg[g];

      assign trace_read_is_store[g] = __in_is_store_reg[g];
      assign trace_read_size[`LOGSIZE_WIDTH*(g+1)-1:`LOGSIZE_WIDTH*g] = __in_size_reg[g];
      assign trace_read_data[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g] = __in_data_reg[g];
    end
  endgenerate
  assign trace_read_finished = __in_finished_reg;

  initial begin
      /* $value$plusargs("uartlog=%s", __uartlog); */
      memtrace_init(FILENAME);
  end

  // Evaluate the signals on the positive edge
  always @(posedge clock) begin
    if (reset) begin
      for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
        __in_valid[tid] = 1'b0;
        __in_address[tid] = `DATA_WIDTH'b0;
        
        __in_is_store[tid] = 1'b0;
        __in_size[tid] = `LOGSIZE_WIDTH'b0;
        __in_data[tid] = `DATA_WIDTH'b0;
      end

      __in_finished = 1'b0;

      cycle_counter <= `DATA_WIDTH'b0;

      // setting default value for register to avoid latches
      for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
        __in_valid_reg[tid] <= 1'b0;
        __in_address_reg[tid] <= `DATA_WIDTH'b0;

        __in_is_store_reg[tid] = 1'b0;
        __in_size_reg[tid] = `LOGSIZE_WIDTH'b0;
        __in_data_reg[tid] = `DATA_WIDTH'b0;
      end

      __in_finished_reg <= 1'b0;
    end else begin
      cycle_counter <= next_cycle_counter;

      // Getting values from C function into pseudeo register
      for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
        memtrace_query(
          trace_read_ready,
          // Since parsed results are latched to the output on the next
          // cycle due to staging registers, we need to pass in the next cycle
          // to sync up.
          next_cycle_counter,
          tid,

          __in_valid[tid],
          __in_address[tid],
 
          __in_is_store[tid],
          __in_size[tid],
          __in_data[tid],

          __in_finished
        );
      end

      // Connect values from pseudo register into verilog register 
      for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
        __in_valid_reg[tid]   <= __in_valid[tid];
        __in_address_reg[tid] <= __in_address[tid];

        __in_is_store_reg[tid] <= __in_is_store[tid];
        __in_size_reg[tid] <= __in_size[tid];
        __in_data_reg[tid] <= __in_data[tid];
      end
      __in_finished_reg <= __in_finished;
    end
  end
endmodule
