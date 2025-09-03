# Rename

## Overview

The rename stage takes an architectural register (AR) name per cycle and
assigns physical register (PR) name to it. The block assumes that per-warp AR
usage will never exceed the limit specified by the command processor, so it
does not maintain a free list.

The block is instantiated per core, and takes care of all threads of all warps
in that core.

## Parameters

* Number of total PR (names): tentatively max 128 regs @ 4w: 512 PRs

## Top Level IO

* Input valid: derived from whether the instruction writes to RD
* AR name: derived from the RD field
* Warp name: which warp is currently active, corresponds to the AR
* (Log) Regs per warp: set by the command processor
* Reset assignments: Unbinds all ARs seen

## Operation

TODO: block diagram

Upon reset, the block initializes the map to empty, all warp usage counters to
0, and all bitmaps to all zeros. The map needs to be a 1R1W dual port SRAM, as
deep as the total number of PRs.

### Stage 0:

R0 is a special case and will always map to P0. Short circuit the request.

When a register rename request comes in, the bitmap register for that warp is
checked to see if the incoming AR has already been assigned. The inverse of
this is fed to the enable of the warp usage counter, incrementing it when a new
assignment is made. The bitmap register is updated.

In parallel, the SRAM address is calculated: `ar + wid << log_regs_per_warp`,
and read request is passed to the map SRAM, regardless of AR assignment.

### Stage 1:

SRAM read result is available; counter + 1 is flopped. Based on whether a new
AR was allocated in stage 0, output one of these two.

In parallel, if a new AR was allocated, initiate a write to the SRAM to store
the new assignment. If a new request arrived to ask for the same AR, bypass the
SRAM read (same cycle R/W to same address, undefined behavior) and forward the
new assignment next cycle. Need to take care this happens 1 time max.

## Future Improvements

### Full Renaming

It might be desirable to allow one AR to map to multiple PRs to allow WAW and
WAR elimination. This is possible because an AR with multiple alive PRs will
eventually settle down on one PR after all instructions are drained. However,
to ensure fairness among warps, the oversubscription of PRs must be done in a
uniform fashion. Furthermore, this requires the block to keep track of a free
list.

### Multiple Renames per Cycle

If we want to support dual fetch & decode per warp (unlikely), this is
requried.

