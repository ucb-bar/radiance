Issue Logic
===========

## Module Interface

Inputs: TODO
Outputs: TODO

## Overview

![Issue stage](fig/issue.svg)

Central scoreboard and distributed reservation stations working in conjunction. (TODO)

We go after the following key performance opportunities in the issue stage:

* **Past-head-of-line intra-warp ILP.**
* **Bank conflict smoothing.**

## Scoreboard

The main role of the scoreboard is to **bookkeep pending writes and reads to
registers from in-flight instructions**.  Bookkeeping pending register writes
and reads are crucial to (1) correctly handle WAR/WAW hazards, and also to (2)
determine whether to read operand from the PRF or forward from EX for RAW
hazards.

The scoreboard is largely left as the bookkeeping states themselves, and the
combinational logic that handles hazard checks is split to a separate module.

### Hazard check

The main role of the hazard check module is to:

* **Gate admission of an IBUF entry to the reservation station.**
* **Correctly populate `busy` bit of the RS entry.**

#### RAW hazard

RAW hazards are handled by operand forwarding.  Upon RS admission, the
scoreboard's `pendingWrite` is queried for rs1/rs2/rs3 of the instruction. If
the bit is set, a new entry is created in the RS with the `busy` bit set. This
indicates that the operand should be broadcasted from the EX stage via
forwarding path, instead of being read from the PRF via the operand collector.

```
Set pendingWrite[R] when:
    admitting to RS an instruction writing to R
Clear pendingWrite[R] when:
    instruction writing to `R` completes writeback
```

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
incrementing it when admitting an instruction that reads the register to the
RS, and decrementing when its PRF read is complete.
Importantly, `pendingReads` must be incremented only for regs that are
**actually read from the PRF**.  Otherwise, if the regs are forwarded
from the fabric, we don't need to protect them as their values will not be
clobbered by an out-of-order WB.
In summary, the logic for `pendingReads` becomes:

```
Increment pendingReads[R] when:
    (admitting to RS an instruction writing to R)
    AND (pendingWrite[R] == 0, i.e. R will be read from PRF)
Decrement pendingReads[R] when:
    (R completes operand collection from PRF)
```

With all of this in place, we now gate admission to RS if `pendingReads[rs]` is set:

```
admitRS[inst] iff:
    pendingReads[rs] == 0 for all rs in inst.rs0/1/2
```

While a simple solution, this likely overly constrains the instruction window as
false hazards are frequent.

##### Option 2: Stall WB of younger writes

A second option is to use the same `pendingReads` counter, but **stall the
writeback of the younger write**.  Going back to the above example, instead of
gating admission of `i1` to the RS, we stall writeback of `i1` until
`pendingReads[r5]` reaches 0.  This ensures `i0` reads the correct old value
from PRF without getting clobbered by `i1`.

**TODO**: this likely requires a writeback buffer so that stalled writebacks do not
stall the whole EX pipeline.

**Non-issue**: With this option, it seems like we risk clobbering younger reads
after the WAR (`i2` below):

```
i0: lw     <- 0(r5)
i1: add r5 <-        # write-after-read
i2: sub    <- r5     # different form i0's r5
```

However, this is correctly handled by the forwarding logic from `i1`,
preventing `r5` from being read from the PRF that contains `i0`'s value.
The forwarding is guaranteed by the RAW hazard logic seeing `pendingWrite[r5]
== 1` and accordingly setting `busy` bit in the RS.

Note that this requires a **must-forward invariant**, i.e. forwarding fabric
must not drop any value due to arbitrating multiple writebacks.  This further
necessitates having a writeback buffer (TODO).


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
admitRS[inst] iff:
    pendingWrites[inst.rd] == 0
```

##### Optimization: Allow past-WAW admission to RS

Gating RS admission on a WAW gives up opportunity of discovering ILP
on younger instructions that are completely independent.  For example:

```
i0: add r5 <-   
i1: sub    <- r5
i2: add r5 <-     # XXX blocked admission
i3: lw     <- r4
```

`i3` does not consume or produce `r5`, and is safe to enter the RS.  However,
gating `i2` causes head-of-line blocking at the instruction buffer, since
IBUF-to-RS admission is done strictly in program-order at the head end of IBUF.

A fence-based scheme is possible to avoid this: Add a per-reg `Fence` bit to
the scoreboard, and set it upon seeing WAW at IBUF head.
Then, **look past the head of IBUF**, and **only** gate RS admission if the
instruction's rs or rd has `Fence` set.
`Fence` is cleared when the older write completes writeback.

```
admitRS[inst] iff:
    (Fence[rs] == 0 for all rs in inst.rs0/1/2) AND
    (Fence[inst.rd] == 0 AND pendingWrites[inst.rd])
```

This scheme increases hardware complexity, since now the hazard logic needs to
sequentially walk IBUF entries past the FIFO head. This is best to be left for
future optimization.


### Hardware requirements

Memory capacity requirement of the scoreboard using a per-reg bit vector
design:

(`PREG=256` registers) * (1 pendingWrite bit/reg + 2 pendingReads bit/reg) = **768 bits (96 bytes)**.

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

### Splitting metadata/data fields across RS/collector

The reservation station stores instruction metadata fields (e.g. op type,
physical register #, valid/busy bits) separately from the operand data fields.
This is because the metadata requires high-throughput CAM access for wake-up
broadcast on writeback and operand collector allocation.  As such, the metadata
fields are best stored in fast flip-flops.

On the other hand, the operand data fields have modest memory access
requirements: Single-row access per cycle for operand read and write-back. They
also have high capacity demand due to their wide bit widths (`NT*XLEN`). SRAM
arrays serve as the best storage of choice.

* **TODO**: Store age for load/store instructions
  * Necessary for LSU to determine program order of load/stores within a thread
  * Capped by in-flight memory ops, i.e. LSU queue depth


### Hardware requirements

#### Memory requirements

It is necessary to store the metadata fields used for writeback broadcasting
separately from the data fields used at the dispatch time, since the two have
different access characteristics.

* Each RS entry: 1625 bits
  * Metadata fields: 1b valid + (8b preg# + 1b valid + 1b busy) * 3 operands = 31 bits
    * Access: CAM for clearing busy bit on writeback
  * Data fields: (3b warp + 32b PC + 7b opcode + 16b tmask + (16*32b data) * 3 operands = 1594 bits
    * Access: 1R 1W
      * 1R: Read 1 entry selected by dispatch scheduler, dispatch it to FU.
        * **May need >1R** if RS is per-FU-class, serving multiple FU pipes.
      * 1W: Choose 1 entry out of the ones that matched writeback broadcast in
        the metadata table, and forward data to it
* 16-entry RS (2 per warp): 496b metadata table, 25504b data table

#### Wiring requirements

Forwarding fabric may be expensive, since operand bits are wide
(`VLEN*WORDSIZE`=64 bytes) and it must be broadcasted to *all* RS entries.


Operand Collector
-----------------

![Collector stage](fig/collector.svg)

Operand collector consists of two main storages:

* **Physical register file banks.**  PRF is banked by the register number.
  We use a simple, static round-robin banking scheme of `Bank[reg] = reg mod n_banks`.
* **Collector banks** stage the operand data that are read so far, either from
  the PRF or from the forwarding fabric.
  Collectors are banked by the FU pipe so that the connectivity to FUs are direct and low-cost.
  Each collector row contains all operand fields, PC/rs1/rs2/rs3, which makes
  single-cycle issue straightforward.
* **Collector allocator** serves two purposes:
  * **Arbitrates PRF banks** to choose conflict-free bank accesses.  This
    requires reading *all* entriy's preg# fields from the RS and
    solving an M-to-N matching problem.
  * **Allocates a collector entry** for each RS entry as possible.  This
    happens when (1) new entry is admitted to RF, and (2) there is available
    space in the collector bank that matches the instruction's FU type

### Decoupling collector capacity vs RS

The **crossbar** that connects PRF bank output ports to collector input ports
have high wiring cost; for a `B=8` banks and `C=4` collectors,
the cost scales with `B*C*NT*XLEN = 8*4*16*32b`.

Therefore, we need to keep `C` manageable, by potentially storing fewer
collector entries than there are RS entries.  This does not add complexity
with dynamic allocation, because allocation is already required in cases where
instruction mix is skewed and a per-FU collector bank runs out of space.

## Operand Forwarding
