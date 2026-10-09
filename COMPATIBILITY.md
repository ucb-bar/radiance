# Compatibility

This file records which commits of Gemmini and radiance-kernels go with each Radiance commit. It
is for bookkeeping only. No script reads it.

* Gemmini: https://github.com/ucb-bar/gemmini
* radiance-kernels: https://github.com/ucb-bar/radiance-kernels

A row applies from its Radiance commit until the Radiance commit of the row above it. The newest
row is at the top. When a Radiance change needs a different Gemmini or radiance-kernels commit,
add a new row at the top.

| Radiance (from) | Gemmini | radiance-kernels | Date | Notes |
|---|---|---|---|---|
| `83461e8` | `754def1` (branch `firesim-hbm`) | `38b3425` (branch `main`) | 2026-10-09 | Split host/GPU L2 and GPU address hash (`RadianceHBMConfig`). For `+loadmem` on `RadianceHBMConfig`, build kernels with `MU_ADDR_HASH=1`. |
