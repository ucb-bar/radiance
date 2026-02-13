# Memory

## Cache Hierarchy

There should be a tiny L0 I$ and tiny L0 D$ per core, a larger L1 per cluster,
and L2 for the SoC. All caches are non-blocking.

![Memory Hierarchy](./fig/memory.svg)

### L0

Note: L0 is not currently instantiated due to Rocket NBDCache implementation.

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

L1 is a 64KiB unified cache that serves both instructions and data.

4-way set associative, write through, 32B line.

Tag array size is 1024x18b.

* **Banking**: 2 banks.
* **Ports**: single port.
* **Bandwidth**: 32B/cycle (1 line).
* **Timing**: ???

L0 and L1 will use HellaCache/NBDCache with modified MSHR datapath; write
allocate writes will not allocate and instead will be spat out.

### L2

Coherent, 512KB, 128B lines, 8-way, single port unbanked.

Bandwidth is 32B/cycle (SBus size = 256b).

## Shared Memory

![SMEM Block Diagram](./fig/smem.svg)

In order to fulfill the [workload requirement](#flashattention-3-requirements),
Shared memory should be sized 128KB/SM, 64B lines, 1R1W dual-port per bank, banked 4x16.
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

### FlashAttention-3 Requirements

![FlashAttention Pipeline Stages](./fig/flashattention.svg)

FA-3's deep software pipelining chain (shown as the bold line in the diagram)
requires extensive double-buffering and concurrent producer-write-consumer-read
access.  Rather than expanding banks to achieve high memory-level parallelism,
we use 1R1W dual-ported SRAM banks.

#### Bank Mapping

In order to achieve full-throughput pipelining, we need to solve the following
list of read/write conflicts that exist in the above pipelining scheme.

Note that `Xc` and `Xp` indicates the double-buffered consumer/producer tiles
of a given symbol `X`, respectively:

| Access  |  Tiles       | Conflict with | Reason                        |
|---------|--------------|---------------|-------------------------------|
| `READ`  | `Q`          | `Kc`          | Gemmini A/B input             |
| `READ`  | `Pc`         | `Vc`          | Gemmini A/B input             |
| `READ`  | `Q, Kc`      | `O`           | Gemmini-SIMT double-buffering |
| `READ`  | `Q, Kc`      | `QKc`         | Gemmini-SIMT double-buffering |
| `READ`  | `Vc, Pc`     | `QKc`         | Gemmini-SIMT double-buffering |
| `READ`  | `QKc`        | `O`           | Gemmini-SIMT double-buffering |
| `WRITE` | `Kp, Vp`     | `O`           | DMA-Gemmini double-buffering  |
| `WRITE` | `Kp, Vp`     | `QKp`         | DMA-Gemmini double-buffering  |
| `WRITE` | `Kp, Vp`     | `Pp`          | DMA-SIMT double-buffering     |
| `WRITE` | `O`          | `Pp`          | Gemmini-SIMT double-buffering |
| `WRITE` | `QKp`        | `Pp`          | Gemmini-SIMT double-buffering |
| `WRITE` | `QKp`        | `O`           | Gemmini-SIMT double-buffering |

Because each SRAM bank has separate 1 read and 1 write ports, there are no
conflicts that need to be resolved between the producer-consumer pairs (`Xc` -
`Xp`).

Note that `O` is not double-buffered due to an intra-iteration dependency
between `O = O + P*V` GEMM and `O` rescale ops.  Every operation on `O` is done
in-place within the single tile.
`Q` is also not double-buffered, because access to `Q` is read-only across all
of the loop iterations.


We can solve these conflicts in a mapping that requires 4 banks:

| Bank   |  Tiles                      |
|--------|-----------------------------|
| Bank 0 | `Kp, Vp, Kc, Vc`            |
| Bank 1 | `QKc, QKp`                  |
| Bank 2 | `O`                         |
| Bank 3 | `Q, Pc, Pp(quant), Pp(B16)` |

#### Capacity Requirement

With the above bank mapping, the total capacity requirement for SMEM becomes:

| `Brow=Bcol` | Head dim `d` | `Q, K, V, P` Precision | `QK, O` Precision | SMEM Capacity Requirement (KiB) | Notes                          |
|-------------|--------------|------------------------|-------------------|---------------------------------|--------------------------------|
| 64          | 64           | 32                     | 32                | 256                             | Virgo                          |
| 64          | 64           | 8                      | 16                | 80                              |                                |
| 64          | 64           | 4                      | 16                | 64                              |                                |
| 64          | 128          | 8                      | 16                | 128                             | **Muon** baseline              |
| 64          | 128          | 4                      | 16                | 64                              |                                |
| 128         | 64           | 8                      | 16                | 288                             |                                |
| 128         | 64           | 4                      | 16                | 256                             |                                |
| 128         | 128          | 8                      | 16                | 320                             |                                |

The SMEM capacity design target for Muon is **128KiB/cluster** with
`Brow=Bcol` 64 and head dimension 128.

**FP8 Down-conversion.**  Note that the inputs of the two GEMMs: `Q*K` and `P*V`
need to be down-converted to FP8 for consumption in the matrix PEs.

If we handle down-conversion in SIMT, it creates additional SMEM capacity
demand, since the converted FP8 values cannot be overwritten into the original
BF16 tile in-place, unless enforcing a strict ordering between the warps.
Therefore, for `Q`, `K` and `V`, down-conversion must be handled by the DMA
either in accumulator move-out, or GMEM to SMEM move-in.  We handle both cases
in the Requantizer module described below.

Note that while `Pp` can be stored down-converted in SMEM, it must
also be stored in BF16 intermediately to enable accurate row-sum computation of
`l`.  After that, `Pp` can be read and then written back to SMEM through the
requantizer, to a separate tile.  For this reason, we allocate separate space
for `Pp (quant)` and `Pp (BF16)` when deriving above table.

Overall, this allocation scheme errs on the side of over-provisioning memory to
be safe.  Some operations, e.g. `QKc -> Pp`, can be implemented in-place. A
tighter allocation scheme may be implemented in a kernel.


#### Bandwidth Requirement

SMEM bandwidth is provisioned so that it can fully saturate the SIMT compute
throughput of 16 INT32 lanes when doing element-wise operations (1 OP/byte).

### Core serialization
* add serializing coreside crossbar to create uniform requests
  * register to hold result, as well as a valid bit mask

### Shared Memory Map

| GPU Address    |  Size     | Description                     |
|----------------|-----------|-------------------------------- |
| `0x0000_0000`  | `0x20000` | Shared memory cluster local     |
| `0x0004_0000`  | `0x40000` | Requantized shared memory       |
| `0x0008_0000`  |   `0x200` | Shared print and perf buffer    |
| `0x0008_0200`  |   `0x100` | Core 0 L0i flush MMIO           |
| `0x0008_0300`  |   `0x100` | Core 0 L0d flush MMIO           |
| `0x0008_0400`  |   `0x100` | Core 1 L0i flush MMIO           |
| `0x0008_0500`  |   `0x100` | Core 1 L0d flush MMIO           |
| `0x0008_4000`  |   `0x100` | Gemmini MMIO                    |
| `0x0008_8000`  |  `0x4000` | Gemmini scaling factor memory   |

Gemmini MMIO has the following address map:

| Address | Bytes | Description       |
|---------|-------|-------------------|
| `0x00`  |     4 | RoCC instruction  |
| `0x10`  |     4 | RoCC RS1 LSB      |
| `0x14`  |     4 | RoCC RS1 MSB      |
| `0x18`  |     4 | RoCC RS2 LSB      |
| `0x1c`  |     4 | RoCC RS2 MSB      |
| `0x20`  |     4 | Busy              |
| `0x28`  |     4 | Num running loops |
| `0x30`  |     4 | CISC instruction  |
| `0x80`  |   384 | LUT table 0       |
| `0x200` |   384 | LUT table 1       |
| `0x380` |   384 | LUT table 2       |


GPU to requantizer: bf16 in, fp8 out; addressing scheme: magnify by 2x.

Requantizer input interface:

* Data: BF16, 16 elements
* Address: destination address stride (in 4 byte increments for FP4, 8 byte
  increments for FP8, starting from 0)
* Data type: FP8/FP6/FP4

Requantizer output interface:

* Number of bytes, byte address, data

Requantizer will only see data intended to be requantized and not passthroughed.

## Global Memory Map

|  CPU Address    |  Size         | Description                     |
|-----------------|---------------|---------------------------------|
|   `0x4000_0000` | `0x100000`    | Cluster 0 SMEM (inc. Gemmini)   |
|   `0x4010_0000` | `0x100000`    | Cluster 1 SMEM (inc. Gemmini)   |
|   `0x4100_0000` | `0x100`       | GPU reset aggregator            |
|   `0x6000_0000` | `0x10000`     | GPU device command processor    |
|   `0x8000_0000` | `0x8000_0000` | CPU-only DRAM (2GB)             |
| `0x1_0000_0000` | `0x8000_0000` | GPU DRAM (2GB), CPU addressable |

GPU will live in the illusion that addresses start at 0; when its requests leave
unified L1, it will be rewritten to append the 33rd bit before arriving at L2.

Reset aggregator has the following address map:

| Address | Bytes | Description               |
|---------|-------|---------------------------|
| `0x00`  |     4 | GPU reset                 |
| `0x08`  |     4 | GPU all finished          |
| `0x10`  |     4 | Cluster 0 core 0 finished |
| `0x14`  |     4 | Cluster 0 core 1 finished |
| `0x18`  |     4 | Cluster 1 core 0 finished |
| `0x1c`  |     4 | Cluster 1 core 1 finished |

<!--
The Command Processor will need to have its own BootROM to act as failsafe when
the CPU fails to schedule work on the SIMT cores.
-->

## Fabric

![Fabric Block Diagram](./fig/fabric.svg)

### Control Bus

![CBus Block Diagram](./fig/cbus.svg)

### System Bus

![SBus Block Diagram](./fig/sbus.svg)

<!--
### Area Estimation

TODO
-->

## Other Stuff

* Fencing support: drain LSU, invalidate L0/L1, wait for L2 coherency traffic
* Atomics: L2 only, bypassed in LSU
* Sectoring, banking in depth dim implementation


