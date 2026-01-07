module ProfilerBlackBox #(
  parameter COUNTER_WIDTH = 64
) (
  input clock,
  input reset,
  input logic                     finished,
  input logic [COUNTER_WIDTH-1:0] perf_backend_execute_instRetired,
  input logic [COUNTER_WIDTH-1:0] perf_backend_execute_cycle
);

  `include "Cyclotron.vh"

  import "DPI-C" function void profile_perf_counters(
    input longint inst_retired,
    input longint cycle,
    input bit     finished
  );

  initial cyclotron_init_task();

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      profile_perf_counters(
        perf_backend_execute_instRetired,
        perf_backend_execute_cycle,
        finished
      );
    end
  end

endmodule
