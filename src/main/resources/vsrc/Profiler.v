module ProfilerBlackBox #(
  parameter COUNTER_WIDTH = 64
) (
  input clock,
  input reset,
  input logic                     finished,
  input logic [COUNTER_WIDTH-1:0] perf_backend_instRetired,
  input logic [COUNTER_WIDTH-1:0] perf_backend_cycles,
  input logic [COUNTER_WIDTH-1:0] perf_backend_cyclesDecoded,
  input logic [COUNTER_WIDTH-1:0] perf_backend_cyclesEligible,
  input logic [COUNTER_WIDTH-1:0] perf_backend_cyclesIssued
);

  `include "Cyclotron.vh"

  import "DPI-C" function void profile_perf_counters(
    input longint inst_retired,
    input longint cycle,
    input longint cycles_decoded,
    input longint cycles_eligible,
    input longint cycles_issued,
    input bit     finished
  );

  initial cyclotron_init_task();

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      profile_perf_counters(
        perf_backend_instRetired,
        perf_backend_cycles,
        perf_backend_cyclesDecoded,
        perf_backend_cyclesEligible,
        perf_backend_cyclesIssued,
        finished
      );
    end
  end

endmodule
