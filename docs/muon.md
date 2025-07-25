Muon SIMT Core
==============

Muon is the SIMT core architecture of Radiance.  It executes shader
instructions of a Radiance program.  Its design focuses on being stable,
flexible, and power-efficient.

## Design Goals

### Stability and scalability

Muon should be a stable core implementation of the full Radiance instruction
set to facilitate prospective software development.  It should be easy to
extend Muon to additional instructions, e.g. SIMD-shuffling and atomics, to
explore kernel performance optimizations and hardware co-design.

### Good software performance latitude

Muon should have reasonable performance with low variance to a wide range of
kernels.  Existing academic architectures often exhibit highly varying
performance depending on how the kernel is written, e.g. stack spills and bank
conflicts for kernels with high register usage, or pipeline stalls for
instructions with back-to-back operand dependencies.

### High performance-per-watt


## Key Microarchitecture Features

* Dynamic warp occupancy

* Efficient operand collector

* Hardware threadblock scheduler
