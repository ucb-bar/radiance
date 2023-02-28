`define DATA_WIDTH 64

import "DPI-C" function void memtrace_init(
    input  string  filename
);

import "DPI-C" function void memtrace_tick
(
    output bit     trace_read_valid,
    input  bit     trace_read_ready,
    output longint trace_read_cycle,
    output longint trace_read_address
);

module SimMemTrace  (
    input              clock,
    input              reset,

    output                   trace_read_valid,
    input                    trace_read_ready,
    output [`DATA_WIDTH-1:0] trace_read_cycle,
    output [`DATA_WIDTH-1:0] trace_read_address
);

    bit __in_valid;
    longint __in_cycle;
    longint __in_address;
    string __uartlog;
    int __uartno;

    initial begin
        $value$plusargs("uartlog=%s", __uartlog);
        memtrace_init("vecadd.core1.thread4.trace");
    end

    reg __in_valid_reg;
    reg [`DATA_WIDTH-1:0] __in_cycle_reg;
    reg [`DATA_WIDTH-1:0] __in_address_reg;

    assign trace_read_valid   = __in_valid_reg;
    assign trace_read_cycle   = __in_cycle_reg;
    assign trace_read_address = __in_address_reg;

    // Evaluate the signals on the positive edge
    always @(posedge clock) begin
        if (reset) begin
            __in_valid = 1'b0;

            __in_valid_reg <= 1'b0;
            __in_cycle_reg <= `DATA_WIDTH'b0;
        end else begin
            memtrace_tick(
                __in_valid,
                trace_read_ready,
                __in_cycle,
                __in_address
            );

            __in_valid_reg   <= __in_valid;
            __in_cycle_reg   <= __in_cycle;
            __in_address_reg <= __in_address;
        end
    end

endmodule
