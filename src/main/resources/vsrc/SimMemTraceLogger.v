// FIXME hardcoded
`define DATA_WIDTH 64
`define MAX_NUM_LANES 32
`define SOURCEID_WIDTH 32
`define LOGSIZE_WIDTH 8

import "DPI-C" function int memtracelogger_init(
  input bit    is_response,
  input string filename
);

// Make sure to sync the parameters for:
// (1) import "DPI-C" declaration
// (2) C function declaration
// (3) DPI function calls inside initial/always blocks
import "DPI-C" function void memtracelogger_log
(
  input int     handle,
  input bit     trace_log_valid,
  input longint trace_log_cycle,
  input int     trace_log_lane_id,
  input int     trace_log_source,
  input longint trace_log_address,
  input bit     trace_log_is_store,
  input int     trace_log_size,
  input longint trace_log_data,
  output bit    trace_log_ready
);

module SimMemTraceLogger #(parameter
                           IS_RESPONSE = 0,
                           FILENAME = "undefined",
                           NUM_LANES = 4) (
  input                                 clock,
  input                                 reset,

  // NOTE: LSB is lane 0
  input [NUM_LANES-1:0]                 trace_log_valid,
  input [`SOURCEID_WIDTH*NUM_LANES-1:0] trace_log_source,
  input [`DATA_WIDTH*NUM_LANES-1:0]     trace_log_address,
  input [NUM_LANES-1:0]                 trace_log_is_store,
  input [`LOGSIZE_WIDTH*NUM_LANES-1:0]  trace_log_size,
  input [`DATA_WIDTH*NUM_LANES-1:0]     trace_log_data,
  output                                trace_log_ready
);
  int logger_handle;
  bit __in_ready;

  // cycle_counter will start off right after reset is deasserted which should
  // synchronize itself with SimMemTrace.cycle_counter
  reg [`DATA_WIDTH-1:0] cycle_counter;
  wire [`DATA_WIDTH-1:0] next_cycle_counter;
  assign next_cycle_counter = cycle_counter + 1'b1;

  // wires going into the DPC
  wire                      __valid [NUM_LANES-1:0];
  wire [`SOURCEID_WIDTH-1:0] __source [NUM_LANES-1:0];
  wire [`DATA_WIDTH-1:0]    __address [NUM_LANES-1:0];
  wire                      __is_store [NUM_LANES-1:0];
  wire [`LOGSIZE_WIDTH-1:0] __size [NUM_LANES-1:0];
  wire [`DATA_WIDTH-1:0]    __data [NUM_LANES-1:0];

  assign trace_log_ready = __in_ready;

  genvar g;
  generate
    for (g = 0; g < NUM_LANES; g = g + 1) begin
      // LSB is lane 0
      assign __valid[g] = trace_log_valid[g];
      assign __source[g] = trace_log_source[`SOURCEID_WIDTH*(g+1)-1:`SOURCEID_WIDTH*g];
      assign __address[g] = trace_log_address[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g];
      assign __is_store[g] = trace_log_is_store[g];
      assign __size[g] = trace_log_size[`LOGSIZE_WIDTH*(g+1)-1:`LOGSIZE_WIDTH*g];
      assign __data[g] = trace_log_data[`DATA_WIDTH*(g+1)-1:`DATA_WIDTH*g];
    end
  endgenerate

  initial begin
    /* $value$plusargs("uartlog=%s", __uartlog); */
    logger_handle = memtracelogger_init(IS_RESPONSE, FILENAME);
  end

  always @(posedge clock) begin
    if (reset) begin
      __in_ready = 1'b1;
      cycle_counter <= `DATA_WIDTH'b0;
    end else begin
      cycle_counter <= next_cycle_counter;

      for (integer tid = 0; tid < NUM_LANES; tid = tid + 1) begin
        memtracelogger_log(
          logger_handle,
          __valid[tid],
          cycle_counter,
          tid,
          __source[tid],
          __address[tid],
          __is_store[tid],
          __size[tid],
          __data[tid],
          __in_ready
        );
      end
    end
  end
endmodule
