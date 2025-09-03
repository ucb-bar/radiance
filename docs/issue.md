Issue Logic
===========

## Module Interface

Inputs: TODO
Outputs: TODO

## Overview

![Issue stage](fig/issue.svg)

Central scoreboard and distributed reservation stations working in conjunction. (TODO)

## Scoreboard

The main role of the scoreboard is to **bookkeep pending writes and reads to
registers from in-flight instructions**.  Pending write/read checks are
necessary to correctly stall for WAR/WAW hazards, and determine whether to read
operand from the PRF or forward from the EX stage for RAW hazards.

See [hazard check](#hazard-check) for the detailed hazard detection logic.

### Memory requirements

For a per-reg busy bit design:

(`PREG=256` registers) * (1 bit/reg) = **256 bits = 32 bytes**

### Hazard check

**Gate admission to the reservation station.**  

#### RAW hazard

#### WAR hazard

Since we don't eliminate WAR/WAW hazards via full register renaming, WAR
hazards are simply resolved by stalls.

Let's look at an example:

```
i0: lw     <- 0(r5)
i1: add r5 <-        # write-after-read
```

Since the RS allows out-of-order issue, `i1` may issue and complete earlier
than `i0`, e.g. when LSU is busy.  This risks the result of `add` clobbering
the older read of `r5` at `i0`.

##### Option 1: Gate RS admission of younger writes

The simplest option to solve WAR is to prevent admission of any
younger writers (`i1` to `r5`) if there exists any older reads in the RS (`i0`).
This requires the scoreboard to keep track of a `pendingReads` signal per register,
incrementing at dispatch of that reg to the operand collector, and decrementing
upon OPC complete.

```
admit[inst] iff:
    pendingReads[rs] == 0 for all rs in inst.rs0/1/2
```

While a simple solution, this likely overconstrains the instruction window for
every false hazards.

##### Option 2: Stall WB of younger writes

A second option is to use the same `pendingReads` counter, but stall the
writeback of the younger write.  In above example, WB of `i1` waits
until `pendingReads[r5]` reaches 0.

TODO: this likely requires a writeback buffer so that stalled writebacks do not
stall the whole EX pipeline.


#### WAW hazard

Similar to WAR, WAW hazards are also resolved by stalls.  Specifically, we gate
admission of an instruction to the RS if the instruction's destination register
already has a pending write.  This way, we guarantee that there is only one
write to every register within the RS issue window, without aliasing younger
reads to a different write:

```
i0: add r5 <-   
i1: sub    <- r5
i2: add r5 <-     # XXX blocked admission; otherwise younger reads (i3)
                  # cannot be distinguished from older reads (i1)
i3: mul    <- r5
```

The final RS admission logic becomes:

```
admit[inst] iff:
    writeBusy[inst.rd] == 0
```


## Reservation Station

Key features of Muon's reservation station are:

* **Intra-warp OoO issue**.  Unlike a scoreboard design, the RS looks past a
  blocked head and issues a later independent instruction *inside a single
  warp.* This allows making progress past a long-stalling memory op and
  uncovering some amount of intra-warp ILP.
* **Unified handling of operand forwarding and collection**.  The RS stores the
  data bits for each register operand, not only the busy bits.  This allows the
  RS to book PRF read request from the operand collector, while at the same
  time receiving forwarded data from the EX stage.

A major difference with RS designs in CPU OoO is:

* **No WAR/WAW avoidance via register renaming**.  In OoO CPUs, WAW hazards are
  avoided by allocating new physical registers to multi-writer instructions to
  the same architectural destination register.  Since physical register budget
  is tight for GPUs, we resolve WAW simply by stalling issue until previous
  writes are complete. This choice is backed up by three reasons:
  * Kernel writers frequently unroll loops to amortize branching costs and
    increase ILP.  This eliminates a major source for WAW hazards for GPUs.
  * Without WAW renaming, the sole purpose for renaming becomes compacting
    per-warp arch regs to contiguous physical reg space.  This vastly
    simplifies the renaming hardware since no freeing of phys regs happen
    during the kernel runtime, and the renaming logic boils down to simple
    linear allocation.
  WAR hazard is a non-issue since we don't support precise exceptions.

**TODO**: Determine if "issue queue" is a better terminology than RS.

Operand Collector
-----------------

TODO
