Muon SIMT Core
==============

Muon is the SIMT core of the Radiance GPU that executes shader instructions of
a Radiance device program.  Its design focuses on being stable, flexible, and
power-efficient.

## Design Goals

### Stability and extensibility

Muon should be a stable microarchitectural implementation of the full Radiance
instruction set to facilitate prospective software development.  Where possible,
Muon should choose a simpler, over a more performant, design for better
RTL maintainability and debuggability.  It should be easy to extend Muon to
additional instruction capabilities, e.g. SIMD-shuffling and special-function
operations, to foster exploring kernel performance optimizations and hardware
co-design.

### Wide software performance latitude

Muon should perform reasonably well for a wide range of kernels.  Existing
academic architectures often exhibit high variance in performance depending on
how the kernel is written, due to e.g. stack spills and bank conflicts for
kernels with high register usage, or pipeline stalls for instructions with
back-to-back operand dependencies.  As a result, they restrict the programmer
to write kernels using rigid and inflexible optimizations, such as assembly
intrinsics and manual register allocation.  Muon should let the programmer to
both write kernels using good software practices, and also expect reasonable
performance.

### High performance-per-watt

While performance is not Muon's first priority, Muon strives to be area- and
power-efficient where possible in order to drive high perf/W.  Design effort is
focused on modules with high area consumption, e.g. issue and operand stages.
Muon should at minimum actively employ basic power optimizations such as
value-gating, and potentially explore further optimizations such as clock- or
power-gating.


## Key Microarchitecture Features

* Dynamic warp occupancy

* Efficient operand collector

* Hardware threadblock scheduler

* Operand-forwarding paths


## TODO

[] Target system and perf/power curve.  Embedded/mobile; desktop; datacenter?
   Expected power budget?

[] Flesh out microarchitectural features.
