module ProfilerBlackBox #(
  parameter NUM_WARPS = 8,
  parameter COUNTER_WIDTH = 64
) (
  input clock,
  input reset,
  input logic                     finished,
  input logic [COUNTER_WIDTH-1:0] instRetired,
  input logic [COUNTER_WIDTH-1:0] cycles,
  input logic [COUNTER_WIDTH-1:0] cyclesDecoded,
  input logic [COUNTER_WIDTH-1:0] cyclesEligible,
  input logic [COUNTER_WIDTH-1:0] cyclesIssued,
  input logic [(NUM_WARPS*COUNTER_WIDTH)-1:0] perWarp_cyclesDecoded,
  input logic [(NUM_WARPS*COUNTER_WIDTH)-1:0] perWarp_stallsWAW,
  input logic [(NUM_WARPS*COUNTER_WIDTH)-1:0] perWarp_stallsWAR
);

  `include "Cyclotron.vh"

  longint per_warp_cycles_decoded [0:NUM_WARPS-1];
  longint per_warp_stalls_waw [0:NUM_WARPS-1];
  longint per_warp_stalls_war [0:NUM_WARPS-1];

  genvar i;
  generate
    for (i = 0; i < NUM_WARPS; i = i + 1) begin
      assign per_warp_cycles_decoded[i] = perWarp_cyclesDecoded[i*COUNTER_WIDTH +: COUNTER_WIDTH];
      assign per_warp_stalls_waw[i] = perWarp_stallsWAW[i*COUNTER_WIDTH +: COUNTER_WIDTH];
      assign per_warp_stalls_war[i] = perWarp_stallsWAR[i*COUNTER_WIDTH +: COUNTER_WIDTH];
    end
  endgenerate

  import "DPI-C" function void profile_perf_counters(
    input longint inst_retired,
    input longint cycle,
    input longint cycles_decoded,
    input longint cycles_eligible,
    input longint cycles_issued,
    input longint per_warp_cycles_decoded[NUM_WARPS],
    input longint per_warp_stalls_waw[NUM_WARPS],
    input longint per_warp_stalls_war[NUM_WARPS],
    input bit     finished
  );

  initial cyclotron_init_task();

  always @(posedge clock) begin
    if (reset) begin
    end else begin
      profile_perf_counters(
        instRetired,
        cycles,
        cyclesDecoded,
        cyclesEligible,
        cyclesIssued,
        per_warp_cycles_decoded,
        per_warp_stalls_waw,
        per_warp_stalls_war,
        finished
      );
    end
  end

endmodule
