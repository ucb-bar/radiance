# Memory

## Cache Hierarchy

There should be a tiny L0 I$ and tiny L0 D$ per core, a larger L1 per cluster,
and L2 for the SoC. All caches are non-blocking.

### L0

#### L0i

L0i should be 4KB with 64B cache lines (8 instructions), direct mapped.

Tag array size is 64x20b.

* **Banking**: single SRAM.
* **Ports**: single port (read dominated).
* **Bandwidth**: 8B/cycle sectored read.
* **Timing**: 3 cycle read.

#### L0d

L0d should be 16KB with 64B cache lines (16 words), direct mapped, write-through.

Tag array size is 256x18b.

* **Banking**: 4 SRAMs on depth dimension.
* **Ports**: single port.
* **Bandwidth**: 64B/cycle (1 line).
* **Timing**: 3 cycle read.

### L1

L1 should be unified L1i and L1d.

L1 should be 64KB. Hopper has 256KB. We have half the amount of cores and half
lanes (and half warps for instruction).

4-way set associative, write through, 64B line.

Tag array size is 1024x18b.

* **Banking**: 2 banks.
* **Ports**: single port.
* **Bandwidth**: 64B/cycle (1 line).
* **Timing**: ???

L0 and L1 will use HellaCache/NBDCache with modified MSHR datapath; write
allocate writes will not allocate and instead will be spat out.

### L2

Coherent, 512KB, 128B lines, 8-way, single port unbanked.

Bandwidth is 32B/cycle (SBus size = 256b).

## Shared Memory

64KB/SM, 64B lines, single port per bank, banked 4x16.

Each SRAM is 4B wide, 256 entries deep.

Total aggregate bandwidth is read+write 256B/cycle.

## Area Estimation

## Other Stuff

* Fencing support: drain LSU, invalidate L0/L1, wait for L2 coherency traffic
* Atomics: L2 only, bypassed in LSU
* Sectoring, banking in depth dim implementation

