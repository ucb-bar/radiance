# Radiance

A Chisel-based heterogeneous GPU architecture for neural graphics applications.

## Muon SIMT Core

Muon, the SIMT core design for the Radiance GPU, is currently under active development.

See documentations:
* [ISA specification](docs/isa.md)
* [Muon microarchitecture specification](docs/muon.md)

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
