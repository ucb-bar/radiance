# Memory

## Cache Hierarchy

There should be a tiny L0 I$ and tiny L0 D$ per core, a larger L1 per cluster,
and L2 for the SoC. All caches are non-blocking.

### L0

#### L0i

L0i should be 4KB with 32B cache lines (4 instructions), direct mapped.

Tag array size is 128x20b.

**Banking**: single SRAM.
**Ports**: single port (read dominated).
**Bandwidth**: 8B/cycle sectored read.
**Timing**: 1 cycle read.

#### L0d

L0d should be 8KB with 64B cache lines (16 words), direct mapped, write-through.

Tag array size is 128x19b.

**Banking**: single SRAM.
**Ports**: dual port R, W.
**Bandwidth**: 64B/cycle (1 line).
**Timing**: 1 cycle read.

### L1

#### L1i

L1i should be 32KB. Hopper has 128KB/SM, we have half the amount of cores and
half warps. May bump to 64KB.

L1i should have 64B cache lines, 2 way set associative. (32B lines?)

**Banking**: 2 banks.
**Ports**: single port.
**Bandwidth**: 64B/cycle (1 line).
**Timing**: ???

#### L1d

L1d should be 64KB. Hopper has 256KB/SM, we have half the amount of cores and
half lanes.

L1d should have 64B cache lines, 4 way set associative. (6 or 8 way?)

Write policy is write-back/write-allocate.

At 64B lines, 4 way: tag array size is 128x18b.

**Banking**: 2 banks.
**Ports**: dual port R, W.
**Bandwidth**: 64B/cycle (1 line).
**Timing**: ???

### L2

Coherent, 512KB, 128B lines, 8-way, single port unbanked.

Bandwidth is 32B/cycle.

## Shared Memory

64KB/SM, 64B lines, single port per bank, banked 4x16.

Each SRAM is 4B wide, 256 entries deep.

Total aggregate bandwidth is read+write 256B/cycle.

## Area Estimation

## Other Stuff

* Fencing support: drain LSU, flush L0, write out L1, wait for L2 coherent
  traffic to stop
* Flushing
* Atomics: L2 only? Additional FUs?
* Sectoring
* Coherency and consistency
* Nonblocking cache parameters (MSHRs)
* DSE

