# Radiance

Radiance is an open-source GPU platform developed at UC Berkeley. It is
designed to help researchers and developers build and evaluate full-stack
heterogeneous GPU systems targeting AI and neural graphics workloads.

Radiance provides:

* **A scalable integration platform for heterogeneous accelerators**, with clear
  interfaces for decoupled orchestration and data delivery.
* **A clean-slate SIMT core** designed for modern, register-heavy GPU
  kernels and optimized for ASIC implementation.
* **Chipyard-based SoC integration**, enabling tight host-device coordination
  for memory system behavior and kernel execution.

Radiance is under active development. Please refer to the following resources
for more information:

* [Radiance GPU architecture](docs/pdf/radiance-slice-retreat-2025.pdf)
* [Muon SIMT core and command processor architecture](docs/pdf/radiance-muon-slice-retreat-2026.pdf)
* [Neutrino orchestration unit architecture](docs/pdf/neutrino-slice-retreat-2025.key)

<p align="center">
  <img src="https://raw.githubusercontent.com/ucb-bar/radiance/refs/heads/main/docs/fig/radiance.svg" alt="Radiance GPU microarchitecture" width="400">
</p>

## Muon SIMT Core

Muon, the SIMT core design for the Radiance GPU, is currently under active development.

See spec documents:
* [ISA specification](docs/isa.md)
* [Muon microarchitecture specification](docs/muon.md)
* [Memory system specification](docs/memory.md)

### Run simulations

Run these commands in `${CHIPYARD_ROOT}/sims/vcs` after `source env.sh`.
Use the `graphics` branch for Chipyard.

**Full SoC config with multiple Muon clusters/cores, RTL memory subsystem and a
host CPU core**:

```
make CONFIG=RadianceTapeoutSimConfig run-binary LOADMEM=1 BINARY=...
```

Below simulates a GPU-standalone, core-isolated config, useful for functional
verification and fast debugging iterations:

**Single Muon core, with Cyclotron-based IMEM and GMEM memory models, with
differential-testing enabled against Cyclotron**:

```
make SUB_PROJECT=muon CONFIG=MuonCoreTestConfig run-binary BINARY=...
```

**Single Muon core, with Cyclotron-based IMEM and GMEM memory models, with
differential-testing disabled**:

```
make SUB_PROJECT=muon CONFIG=MuonCoreNoDiffTestConfig run-binary BINARY=...
```
