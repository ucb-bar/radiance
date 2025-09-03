Issue Logic
===========

Module Interface
----------------

Inputs: TODO
Outputs: TODO

Overview
--------

TODO: Central scoreboard and distributed reservation stations working in conjunction.

![Issue stage](fig/issue.svg)

Scoreboard
----------

TODO Consideration: Per-reg busy bits vs. vector of counters.
TODO Number of of bits for per-reg design.

Reservation Station
-------------------

Key features of Muon's reservation station are:

* **Intra-warp OoO issue**.  Unlike a scoreboard design, the RS looks past a
  blocked head and issues a later independent instruction *inside a single
  warp.* This allows making progress past a long-stalling memory op and
  uncovering some amount of intra-warp ILP.
* **Operand collection**.  The RS stores the data bits for each register
  operand, not only the busy bits.  This allows the RS to pre-issue operand
  read from the PRF, potentially overlapping PRF access latency with receiving
  forwarded data from the functional units.

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

Operand Fetch
-------------

TODO

Operand Forwarding
------------------

TODO
