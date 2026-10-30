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
| `546c8b3` | `87e22f1` (branch `firesim-hbm`) | `d512bb6` (branch `main`) | 2026-10-30 | `RadianceHBMConfig` (`WithRadianceHBMCaches`, from `f9ac9ae`): 64 B lines, cluster L1 incoherent in 4 banks. Kernels for it: `HBM4=1` (hash 16 parts / 2 channels per slice / unit 64 / set stride 64, `MU_L1_BANKS=4`), and every kernel must publish GPU results with `mu_l1_flush` (radiance-kernels `nightly_kernel_end` and llama_e2e phases do). Kernels built for the earlier `RadianceHBMConfig` (unit 32, coherent L1) do not run on it. Verified on VCS, 4 SMs, every cluster checking the whole output: fa_mxfp8_sr_hbm, gemm_mx fp8 512, rmsnorm, layernorm, gelu pass; memstress 52 B/cycle (41 % of the 16 GPU channels). llama_e2e compiles for `HBM4=1` but is not run. |
| `eacb58d` | `87e22f1` (branch `firesim-hbm`) | `046a45a` (branch `main`) | 2026-10-26 | No Radiance change: Gemmini `87e22f1` merges upstream `gemmini-mx-cleanup` (`dd72270`: mesh / accumulator clock gating, VPU configs, requantizer and store fixes) into `6bab678`; `mxgen` at `817cb77`. Verified on 8-thread Verilator: `RadianceHBMConfig` fa_mxfp8_sr (SQ 128 and 512), gemm_mx fp8 512, rmsnorm, layernorm, gelu on 4 SMs; `RadianceFP4HBMConfig` gemm_mx fp4 512 on 1 and 2 SMs; all pass, cycles within 0.4 % of `6bab678`. |
| `5ea8145` | `6bab678` (branch `firesim-hbm`) | `046a45a` (branch `main`) | 2026-10-26 | `RadianceHBMConfig` is 4 SMs, 8 GPU L2 slices x 2 DRAM channels; the 2-SM config is `Radiance2SMHBMConfig`. Kernels for `RadianceHBMConfig`: `MU_ADDR_HASH_SLICES=16 MU_ADDR_HASH_CPS=2 MU_ADDR_HASH_CSS=128` (the defaults 4 / 1 / 0 are for `Radiance2SMHBMConfig` and the other `WithRadianceHBM` configs). Gemmini `6bab678`: one scratchpad source counter per bank. MuonDCache probe fix from `b58d4da`. Nightly regression 7/7 on 4 SMs, 6/6 on `Radiance2SMHBMConfig`. |
| `7a1e80a` | `754def1` (branch `firesim-hbm`) | `82be2c7` (branch `main`) | 2026-10-11 | Coalescer: a lane that can coalesce waits for its coalesced request (all configs). `RadianceHBMConfig`: L0d 8 in flight / 8 MSHRs, cluster L1 32 / 32 (`f7a2bff`). Nightly regression 12/12 on `RadianceHBMConfig` and `RadianceFP4HBMConfig`. |
| `d1cbf8b` | `754def1` (branch `firesim-hbm`) | `8b06cfa` (branch `main`) | 2026-10-09 | Adds `RadianceFP4HBMConfig` (`RadianceHBMConfig`'s memory system, MXFP4-only Gemmini): the config for MXFP4 kernels (`gemm_mx FMT=fp4`). |
| `01ddc7f` | `754def1` (branch `firesim-hbm`) | `8b06cfa` (branch `main`) | 2026-10-09 | `RadianceHBMConfig` uses the E4M3 MxGemmini (SPAD_REQUANT, loop retire counter): flash attention is `fa_mxfp8_sr`. MXFP4 kernels no longer run on `RadianceHBMConfig`. |
| `83461e8` | `754def1` (branch `firesim-hbm`) | `38b3425` (branch `main`) | 2026-10-09 | Split host/GPU L2 and GPU address hash (`RadianceHBMConfig`). For `+loadmem` on `RadianceHBMConfig`, build kernels with `MU_ADDR_HASH=1`. |
