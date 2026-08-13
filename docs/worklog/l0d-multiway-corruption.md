# L0d data corruption at `nWays > 1`

**Status:** root-caused and **fixed** in `MuonDCache.scala`. `vecadd` now `*** PASSED ***` at
64 sets x 4 ways (549,479 cycles); previously asserted at ~12.5 us.
**Date:** 2026-08-12
**Impact:** unblocks `nWays > 1` on the L0d. Note that associativity turned out **not** to fix
the `vecadd` bandwidth problem it was pursued for — see §1.

---

## 1. Why this matters: it blocks the remaining `vecadd` gap

`vecadd` (N=65536, 2 cores, ILP2 w2) delivers **1.963 B/cycle = 24.5% of the 8 B/cycle AXI
port** after the replay-depth fix (radiance `1be06d158`, which took it from 17.0% → 24.5%,
1.45x). Everything below the L0d has been measured and exonerated:

| level | evidence it is *not* the limiter |
|---|---|
| L2 / bus | `l1_out` A-channel stall = **0.00%** over 400k cycles |
| DRAM | `dram_full` = **0.0%**, avg 1.54 reads outstanding, ~21 cyc service |
| AXI port | 2.297 B/cyc actual = **28.7%** utilized (32 B bursts) |
| source shrinkers | inward/outward counters byte-identical; no ID exhaustion |

The binding constraint is the **L0d refusing its own input 74% of cycles**. Splitting
`s2_nack` by source (qualified by `s2_valid`, cross-checked against the shim's independent
count of 6,199):

```
L0d  m16_w1_s64  s2_valid=18677  nack=6199 (33%)   miss=5161 (83%)  victim=256 (4%)  idxmatch=7398 (40%)
```

83% of nacks are `s2_nack_miss = !s2_hit && !mshrs.io.req.ready`, and `idx_match` asserts on
**40% of requests**. Rocket's `MSHRFile` serializes misses **per set index**, ways-blind:

```scala
// rocket-chip NBDcache.scala:452
io.req.ready := Mux(!cacheable, mmio_rdy,
                    sdq_rdy && Mux(idx_match, tag_match && sec_rdy, pri_rdy))
// :410
alloc_arb.io.out.ready := io.req.valid && sdq_rdy && cacheable && !idx_match
```

Same set + same tag merges as a secondary miss; **same set + different tag cannot allocate at
all**, no matter how many MSHRs are free — and `idx_match` compares *set index only*, so this is
equally true at 1 way and at 16.

**Associativity was expected to be the fix. It is not** — measured once the bug below was
repaired (64x4, same kernel and geometry otherwise):

| metric | 1-way baseline | 4-way (fixed) |
|---|---|---|
| total cycles | 579,648 | 549,479 (-5.2%) |
| AXI port utilization | 28.7% | 28.6% (unchanged) |
| L0d nack rate | 33% | 36.8% |
| `idx_match` | 40% of reqs | 44.8% |
| nacks that are `victim` | 4% | 48% |

Adding ways cannot relax a ways-blind `idx_match`; it merely converted `s2_nack_miss` into
`s2_nack_victim` (secondary-miss-on-hit) and slightly raised the total. The two real levers,
in measured order of size, are:

1. **Shim head-of-line blocking.** `MuonHellaCacheIFReplayQueue` refuses *all* new requests
   while any nacked request awaits replay:
   `io.req.ready := !inflight.andR && !nackq.io.deq.valid && !io.nack.valid`.
   At a 37% nack rate the queue is almost never empty, so the L0d shim stalls
   **135,072 of 141,747 offered cycles (95%), of which 98% is `nackq`**. This is now the
   dominant term by a wide margin.
2. **Way-aware MSHR allocation.** Let a primary miss allocate against a set that already has a
   fill in flight whenever a free way exists, instead of refusing on set index alone.

Ruled out empirically as alternative fixes: more MSHRs (byte-identical cycles at 4 vs 16),
`maxInFlight` = `nMSHRs` instead of `nMSHRs+1` (5.8% worse), more sets (256x1 = 0.992x — the
array stride `B-A = 2^18` aliases at *every* power-of-two geometry), and de-aliasing the arrays
by one line (0.924x, nacks +36% — a 64 B pad merely re-phases the collision so `B[k]` lands on
`A[k+1]`'s set).

---

## 2. Reproduction

```bash
make -C sims/vcs debug CONFIG=RadianceL0dWaysConfig      # 64 sets x 4 ways x 64 B, nMSHRs=16
cd sims/vcs && ./simv-chipyard.harness-RadianceL0dWaysConfig-debug +permissive +verbose \
  +dramsim +dramsim_ini_dir=<...>/dramsim2_ini +max-cycles=40000 \
  +loadmem=<kernels/vecadd/sweep_ilp2_w2.soc.elf> +permissive-off <same elf>
```

Configs are in `chipyard/RadianceConfigs.scala`. Any `nWays > 1` on the L0d failed; every
`nWays = 1` geometry ran.

## 3. Symptom

Dies at ~12.5 us with `Assertion failed: MuonHellaCacheIF exception`:

```
[XCPT] id2_m2_b32 paddr=0x00000000 ... ae.ld=1 ...
```

`m2_b32` is the **L0i**, not the L0d — an instruction fetch from address 0. The crash is a
*consequence*: the L0d returns zeros, the core follows a null function pointer, and the L0i
faults fetching the resulting wild PC.

## 4. Root cause

**A dirty line's writeback reads out as zeros, because the flush invalidates the way's
metadata in the same cycle it issues the writeback, and the writeback's read data is muxed by
tag-match rather than by the way it asked for.**

`CacheFlushUnit` couples invalidate and writeback so they fire together — for a dirty line
`meta_write.valid` requires `wbAndSrcReady` and `wb_req.valid` requires `meta_write.ready`,
so both handshake in the same cycle (`MuonDCache.scala:123,150`). The `WritebackUnit` then
issues `data_req` alongside `meta_read` and streams `refillCycles` beats *afterwards*
(`rocket-chip/NBDcache.scala:491`), by which time `coh` is already `Nothing`.

Those beats come back through the shared datapath:

```scala
val en1 = s1_clk_en && s1_tag_eq_way(w)                 // :413  tag EQUALITY, no valid bit
    when (en) { regs(i) := data.io.resp(w) ... }        //       -> way 0's data IS latched
val s2_data_muxed = Mux1H(s2_tag_match_way, s2_data)    // :422  tag MATCH, ands in coh.isValid()
wb.io.data_resp := s2_data_corrected                    // :505
```

The flush's `meta_write` preserves the tag (`data.tag := meta.tag`) and clears only `coh`, so
`s1_tag_eq_way(0)` still fires and the correct data reaches `s2_data(0)` — but
`s2_tag_match_way` is **all-zero**, and `Mux1H` with a zero select emits zeros.

### Why only at `nWays > 1`

`chisel3/SeqUtils.scala:89`:

```scala
def do_oneHotMux[T <: Data](in: Iterable[(Bool, T)]) = {
  if (in.tail.isEmpty) { in.head._2 }   // single way -> select is DISCARDED
  else { ...real AND-OR one-hot mux... }
}
```

At `nWays = 1` the select is optimized away entirely and `s2_data(0)` passes through
regardless, so the zero select is harmless. At `nWays >= 2` it becomes a real mux and a
zero select yields zero. The defect has always been present; associativity merely made it
observable.

### Why the 4-way L1 is unaffected

`canFlush = params.flushAddr.isDefined` (`TLNBDCache.scala:102`), so only the L0ds get a
`CacheFlushUnit` — confirmed empirically: exactly two `[FLDONE]` lines (the two cores' L0ds),
none from the L1. The L1 already runs **4-way** (`m32_w4_s512`) today because all of its
writebacks are MSHR- or prober-driven, and those keep the line valid until the writeback
drains. Muon's flush is the only producer that violates that invariant.

## 5. Evidence

Flush handles the `schedule_context` line (`0x10041080` → tag `0x10041`, set 2) correctly:

```
5668 [FLST]  idx=2 way_en=0x1 valid=1 dirty=1 mw_v=1 mw_r=1 wb_v=1 wb_r=1
5669 [FLINV] idx=2 way_en=0x1 tag=0x10041 dirty=1
5670 [FLWB]  idx=2 way_en=0x1 tag=0x10041
```

Six lines later, both halves of that 64 B line arrive at the L1 as **zeros**:

```
5676 [WAY] m32_w4_s512 addr=0x10041080 cmd=1 ... data=0x0000...0000
5678 [WAY] m32_w4_s512 addr=0x100410a0 cmd=1 ... data=0x0000...0000
```

The stores themselves were fine — `0x10041080` misses into way 0 (`replway=0x1`), then
`...84` and `...88` hit way 0 (`tagmatchway=0x1`) carrying `0x100420b8` / `0x1000104c` /
`0x2`. After the zero writeback, later loads miss and refill from L1 with zeros.

## 6. Hypotheses that were tested and refuted

Recorded because each looked strong and cost a build:

* **`CacheFlushUnit` skipping lines.** The unit does have a real structural race — a 2-deep
  read pipeline behind a 1-deep holding register (`readyForMeta = anyClear || !metaValid`
  is evaluated at T+1 while `metaValid` is still low, so a second read is issued before the
  first line is examined; the flush sits lowest on both `metaReadArb`/`metaWriteArb`).
  Instrumented as `metaValid && metaReadFired && !anyClear`: **`skip=0`**. It never fires,
  because 254 of 256 lines are invalid at flush time and `clearInvalid` supplies `anyClear`.
  Measured `read=256 inv=2 wb=2` — properly paired.
* **A line duplicated across ways.** `PopCount(s1_tag_match_way) > 1` instrumented as
  `[DUPWAY]`: **0 occurrences**. No tag is ever valid in two ways.
* **`replacer.way` s1/s2 skew** — `s2_replaced_way_en` and the meta `RegEnable` use the same
  s1 way; self-consistent.
* **Fork divergence from upstream.** Correct, and the reason the earlier diff found nothing:
  `NBDcache.scala:896,978` are *identical* to the fork. The bug is in how Muon's flush
  **uses** the datapath, not in the datapath's divergence from it.
* **"count and pray" writeback source IDs** — bounded by `wbStall := inFlights >= (1 << srcWidth)`;
  no ID collision.
* **`metaArb.io.in(5).bits.way_en := metaArb.io.in(4).bits.way_en`** — looks like a copy-paste
  way bug but is inside a `/* */` block (dead, like the `maskMshrs` region).

## 7. Fix

`MuonDCache.scala`, at the data mux — select the way the read asked for when the access is a
writeback:

```scala
val s1_wb_way_en = Reg(UInt(nWays.W))
when (wb.io.data_req.valid) { s1_wb_way_en := wb.io.data_req.bits.way_en }
val s2_wb_way_en = RegEnable(s1_wb_way_en, s1_clk_en)
val s2_writeback = RegEnable(s1_writeback, s1_clk_en)
val s2_data_muxed = Mux1H(Mux(s2_writeback, s2_wb_way_en, s2_tag_match_way), s2_data)
```

For a still-valid line the two selects are identical, so this is a no-op everywhere the cache
works today and repairs only the invalidated-then-written-back case.

Rejected alternative: gate the flush's `meta_write` on `wb_resp_fire` so the line stays valid
until its release is acked. That restores upstream's invariant and is contained to Muon code,
but it restructures the flush handshake (currently deliberately coupled) and serializes it to
one outstanding writeback. Worth revisiting if the datapath fix proves insufficient.

**Residual race, not addressed:** between the flush's invalidate and the writeback's data
beats, a new miss to that set could allocate the same way and overwrite its tag, which would
stop `s1_tag_eq_way` from latching the line. The window is a few cycles; the `wb_resp_fire`
alternative above would close it.

## 8. Instrumentation used (uncommitted at time of writing)

* `radiance/memory/TLCounterNode.scala` — pass-through TL counters (throughput / back-pressure /
  occupancy), inserted at L0d in/out, L1 in/out, and both sides of the non-coal shrinker.
* `radiance/memory/MuonHellaCacheIF.scala` — `[NBDSTALL]` shim stall attribution, `[XCPT]` dump.
* `radiance/memory/MuonDCache.scala` — `[S2NACK]` nack-source counters, `[WAY]`/`[WAYWR]` probes,
  `[FLRD]`/`[FLST]`/`[FLINV]`/`[FLWB]`/`[FLSKIP]`/`[FLDONE]` flush probes, `[DUPWAY]` check.
* `testchipip/csrc/mm_dramsim2.cc` — `[DRAMSTAT]` DRAM transaction/back-pressure counters.

All printf-based counters require `+verbose` (`PRINTF_COND`); **rtlq does not pass it**, so
these runs must invoke `simv` directly. Note `[WAYWR]` produced no output in the failing run
and may be mis-qualified — it was not needed for the diagnosis.
