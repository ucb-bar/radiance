# Execute

## Overview

The execute stage consists of multiple execution pipelines:
* Floating point units
* Integer units
* Branch units
* Special function units

The LSU and SFU is discussed separately.

It also contains interfacing logic with issue and writeback.

## FPU

The FPU design will use the fpnew IP. We will support 8 lanes of FP32 operation
per cycle and 16 lanes of FP16 operation per cycle, by utilizing the SIMT
packing parameterizaiton.

DIV and SQRT are iterative and not fully pipelined.

### Area Estimation

From fpnew paper, in Global Foundries 22nm, the cost of each component (assuming
2 fp16 fits in 1 fp32 lane):

component | unit area | count | total area
---------------------------------------------
muladd    | 10.4k     | 8     |  83.2k
comp      |  0.4k     | 8     |   3.2k
conv      |  2.6k     | 8     |  20.8k
sqrt/ div |  3.8k     | 8     |  30.4k
total     |           |       | 137.6k

In this case the full chip with 4 cores will have ~550.4k um2 post-par dedicated
to FPUs. Assuming 22nm had 1.6x area overhead, better estimate is ~350k area at
16nm.

### Data Types

We will decode the data type in the decode stage to specify each operand.

### Rounding Modes, Exceptions

FPnew does support rounding modes input and exceptions output. These will be
hooked up to the CSR hardware registers, but writing to them will require extra
effort.

## INT

From fpnew paper, in same RI5CY 22nm tapeout INT ALU + INT Mul is 2k + 3.6k; or
90k area for one core. This translates 360k in 22nm, 225k in 16nm.

We will have one INT DIV per 8 lanes.

## Warp Control

Branches are resolved using comparators (adders). An array of 16 branch
destinations is fed to the warp scheduler to set PC. This will be computed by
the ALU. Anything that is predecoded to stall needs to be resolved & sent to the warp
scheduler.

In single thread instructions (the ones that uses `eldest` in cyclotron), we
will pick the result with a priority mux. This is mainly `tmc`; `wspawn` is
obsolete and `pred` is lower priority (and semantics of this is subject to
debate).

In `split`s, the split interface must be driven in the warp scheduler. In
`join`s, nothing needs to be done as it's all handled there.

### Control status registers (CSRs)

Only thread mask and warp mask reads are handled in the warp scheduler by
driving the CSR interface there. That interface is per lane.

CSRs require a dedicated CSR file. Per cyclotron, there are 3 sections:

* Read only, stuff like `misa`, we hardcode logic.
  * This also include all the `id`s, like `thread_id` or `core_id`. Note we have
    to take care to dedupe `core_id`.
* User read only. These will have to hook up to special logic in hardware. A
  list of stuff we have to include:
  * `warp_mask`, `thread_mask`
  * `minstret(_h)`
  * `mcycle(_h)`
* User writeable, which is only `fflags`, `frm` and `fcsr`. Might be able to
  skip this. We should also consider adding CSRs for testing purposes.

### Synchronization hardware

Barriers will call local command processor interface (TBD).

## Arbitration

We want to decouple the number of units with the width of the SIMT datapath.
This should be done in a per-FU-type wrapping counter that counts the offset
from each group. We should interleave access (e.g. if 4 lanes share 1 unit, all
lane id mod 4 = 0 goes first). In cases where a lane is not active, we skip the
computation but do not schedule another active lane. The mapping is fully
static.

Other Points to cover:
* forwarding support
* predicated execution
  * at least we should have a hacked single thread mode
