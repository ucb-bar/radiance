# Memory

## Cache Hierarchy

There should be a tiny L0 I$ and tiny L0 D$ per core, a larger L1 per cluster,
and L2 for the SoC. All caches are non-blocking.

![memory.svg](Memory hierarchy)

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

To support intra-thread program ordering, we must treat each batch of request as
an atomized unit. That is, even in the case of serialization, there cannot be
parallel serving of different banks across requests from different cycles.

A major challenge with designing the shared memory interconnect is the existence
of bank level parallelism from Gemmini & software pipelining through tiling
across banks. This means that different banks (along the depth dimension) should
have parallel access.

Iterating upon Virgo's shared memory design, we no longer have the notion of
core *unaligned accesses*; all accesses are uniform. The main changes:

* Core serialization to create uniform requests
  * Core 0 and 1 are RR arbitrated to present a single set to SMEM
* Unaligned requests now only include off-chip slave requests
* Lowest index first now prefers core requests
  * Gemmini can experience starvation this way; however, this can be the job of
    the programmer to tile things correctly when software pipelining.
    Multi-cycle responses are already handled in the `DistributorNode`
  * Core responses this way will be guaranteed lockstep, assuming full
    pipelining


### Core serialization
* add serializing coreside crossbar to create uniform requests
  * register to hold result, as well as a valid bit mask
  * 

### Shared Memory Map

GPU Address |  Size     | Description
----------------------------
`0x7000_0000`  | `0x10000` | Shared memory cluster local
`0x7001_0000`  |   `0x200` | Core 0 print and perf buffer
`0x7001_0200`  |   `0x200` | Core 1 print and perf buffer
`0x7001_3000`  |   `0x100` | Cluster local Gemmini MMIO
`0x7001_3100`  |   `0x100` | Cluster local Gemmini CISC MMIO

## Global Memory Map

CPU Address |  Size         | Description
----------------------------
  `0x4000_0000` | `0x20000`     | Cluster 0 SMEM (inc. Gemmini)
  `0x4002_0000` | `0x20000`     | Cluster 1 SMEM (inc. Gemmini)
  `0x6000_0000` | `0x10000`     | GPU device command processor
  `0x8000_0000` | `0x8000_0000` | CPU-only DRAM (2GB)
`0x1_0000_0000` | `0x8000_0000` | GPU DRAM (2GB), CPU addressable

GPU will live in the illusion that addresses start at 0; when its requests leave
unified L1, it will be rewritten to append the 33rd bit before arriving at L2.

The Command Processor will need to have its own BootROM to act as failsafe when
the CPU fails to schedule work on the SIMT cores.

### Area Estimation

TODO

## Other Stuff

* Fencing support: drain LSU, invalidate L0/L1, wait for L2 coherency traffic
* Atomics: L2 only, bypassed in LSU
* Sectoring, banking in depth dim implementation


