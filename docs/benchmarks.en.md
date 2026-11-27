# Performance Benchmarks: Stock / Serial vs. Forgia

This page separates the **latest Phase 4A 1-hour production soak (`21.1.250.323-beta`)**, **Phase 3 production validation (`21.1.250.300-beta`)**, and earlier C7 and historical controlled tests. Older microbenchmarks describe only their named build, fixture and configuration; they are not current-build speedup claims.

---

## Latest Phase 4A 1-Hour Production Soak (`21.1.250.323-beta` / SkyExplorer Live Server)

Measured every 5 minutes over **1 hour (`67.5 min` uptime)** on `21.1.250.323-beta` (`enableUnifiedRegionTickLoop = true`, `enableIndependentRegionTickClock = true`, `enableRegionSandboxAllBlockEntities = true`, 14 Region Workers, `DYNAMIC_CLUSTER/4ch`, `Intel Xeon Gold 6138 @ 2.00GHz`, 16 vCPU / 32 GiB) with **2–4 players exploring apart (up to `7,269` loaded overworld chunks)**:

| Metric | Forgia Phase 4A Live (1-Hour Window) | Estimated Full-Serial Equivalent |
| :--- | :--- | :--- |
| **TPS** | **`19.71 – 20.0 TPS`** (steady `20.0` in 10s / 1m windows) | **~`2.5 – 4.8 TPS`** (severely overloaded) |
| **MSPT (Median / P95)** | **Median `29.0 – 40.0 ms`** / **10s P95 `38.0 – 55.3 ms`** | **~`210 – 390 ms/tick`** |
| **CPU Core Utilization** | **Panel `652.9% – 1129.9%`** (~**`6.5 – 11.3 / 16 cores`** active) | Capped at 1 core (`100%` single-thread) |
| **Memory (ZGC + Container RSS)** | **Old `3.2 – 7.3 GB` / 28.6 GB**, RSS **`29.8 – 31.4 GiB`** | Similar footprint for same loaded chunks/entities |

- **Serial TPS Estimate Basis**: Under 4-player exploration (`~7,000` loaded chunks), the main thread spends `~30–40 ms/tick` while 14 Region Workers concurrently absorb **`~6.5 – 11.3 cores`** of entity, block entity, fluid, and random-tick work (equivalent to **`~180 – 350 ms/tick`** on a single thread). Running that same workload serially on one thread would yield `210 – 390 ms/tick`, or only **`~2.5 – 4.8 TPS`**.

---

## Latest Phase 3 Production Modpack Benchmarks (`21.1.250.300-beta` / SkyExplorer Live Server)

Measured live after **3.6 hours (`12,961s`)** of continuous production uptime on a heavy NeoForge `1.21.1` modpack (Create 6.0.10, Cataclysm, Sable 2.0.5, TacZ, Quark, Lootr, Primal, NetherExp, Spawn, MobSpawnController; 8 Region Workers, `DYNAMIC_CLUSTER/4ch`, `enableIndependentRegionTickClock = true`, `enableRegionSandboxAllBlockEntities = true`, all 5 core slices asynchronous):

| Workload / Metric | Single-Thread / Pre-Phase 3 (`294-beta`) | Phase 3 Live (`21.1.250.300-beta`, 3.6h Soak) | Improvement |
| :--- | :--- | :--- | :--- |
| **Multi-player load (82 Regions / 868 chunks / 838 entities)** | `166.03 ms/tick` total region work (~6.0 TPS single-threaded) · `44.4 ms` on `294-beta` | **`20.01 TPS`** / **MSPT `20.13 ms`** (`p95 30.97 ms`, 10s max `45.2 ms`, 15m TPS `19.96`) | **`8.25×` effective multi-core speedup** |
| **Top-5 hotspot regions (`-38,-54`, `-41,106`, `-37,-54`, `-66,-89`, `-37,-53`)** | `76.90 ms/tick` combined across top 5 regions | Each region runs independently at **`19.996 observedTps`** (`12.1–21.3 ms/region`) | Zero cross-region stall |
| **Block Entity Parallelism** | `63.4%` on `294-beta` (`create:belt` + `lootr` fallback) | **`99.89%`** on Workers (`168,318,908` worker ticks vs `188,913` fallbacks) | Full sandbox + compat parallelization |
| **Canonical BE Serial Fences** | `14.63 ms/tick` (`294-beta`) | **`0.021 ms/tick`** (`5,432.8 ms` total over `257,835` ticks) | **-99.86% reduction** |
| **Sync Slice Barriers (`ENTITY` / `BE` / `SPAWN` / `CHUNK` / `FLUID`)** | `SPAWN 24.3ms max`, `CHUNK 47.9ms max`, `FLUID 17.2ms max` | **`0` sync barrier batches** across all 5 slices (`PROPAGATION` only: `0.036 ms` avg) | **100% zero-barrier async** |
| **Worker Pool Efficiency (LPT + Affinity + Micro-Packing)** | 1 submission per region tick (`72.6%` OS `parkWakeUps`) | `3,614,596` region ticks in `1,495,671` submissions (**`2.42` ticks/submission**; `42.5%` spin wake-ups) | Process CPU `429%` (~4.3 cores active) |

### 3-Player Heavy Multi-Base Live Snapshot (`21.1.250.314-beta` / SkyExplorer Live Server)

Measured live on `21.1.250.314-beta` (with sandboxed `sendBlockUpdated` / `LightEngine` deferral and graceful cross-region boundary exception forwarding) with **3 players online** across **86 active regions / 852 chunks / 780 entities**:

- **Total Region Work vs. Main Thread**: `176.98 ms/tick` total region work (`avgMspt = 2.058 ms`) → **`20.00 TPS`** (`300s` avg `24.88 ms`, `p50 = 22.09 ms`, `p95 = 46.01 ms`; `/neoforge tps` overall `28.58 ms/tick`, **`7.11×` 300s effective speedup / `6.19×` overall speedup**).
  - **Vanilla / Single-Thread Equivalent**: Running this same 86-region workload on stock Vanilla or single-threaded NeoForge would require `176.98 ms/tick` for region work alone — yielding only **`~5.65 TPS`** (**`~5.2 TPS`** including ~15 ms of main-thread global overhead). Forgia sustains **`20.00 TPS`** on the same `2.00 GHz` processor.
- **Top-5 Hotspot Regions**: `-38,-54` (`21.92 ms`), `-41,106` (`16.86 ms`), `-37,-54` (`15.23 ms`), `-37,-53` (`13.05 ms`), `-39,-54` (`11.61 ms`) — `78.66 ms/tick` combined, each running independently at `observedTps = 24.812` (`capTps = 20.000`).
- **Parallelism & Resources**: **`99.85%`** Block Entity worker parallelism (`26,184,715` worker ticks vs `40,295` fallbacks), **`99.31%`** Chunk Tick worker parallelism (`2,102,054` vs `14,619`), 8 workers with `failed = 0` / `rejected = 0`, `47.9%` spin wake-ups, and **`488.5%`** process CPU (~4.9 cores active).

#### Production Server Reference Configuration (Spark + `forgia.toml` + `server.properties`)

| Category | Live Production Setting / Reading |
| :--- | :--- |
| **CPU & OS (Spark Profile)** | `Intel(R) Xeon(R) Gold 6138 CPU @ 2.00GHz` (10 vCPU / Skylake-SP, Ubuntu 22.04.5 LTS, [Spark Profile](https://spark.lucko.me/HgD2z4RTlH)) |
| **JVM & GC** | Java 25 (Zulu `25.0.3`), `-Xms4G -Xmx16G -XX:+UseZGC -XX:+DisableExplicitGC -XX:ActiveProcessorCount=10`, `-Dforgia.dynamicPlayerSoak=true -Dforgia.mscOrderedSpawning=true` |
| **Spark Health (10s / 1m / 15m)** | TPS `20.0 / 20.0 / 19.77`; 10s ticks `15.0 / 24.4 / 41.6 / 52.9 ms` (min/med/p95/max); Process CPU `44% / 46% / 30%` (10 vCPU); Heap `11.8 / 16.0 GB (73%)` (ZGC Old `2.4 GB` / Young `9.4 GB`) |
| **View & Simulation Distance** | `view-distance = 6`, `simulation-distance = 8`, `sync-chunk-writes = false` |
| **Forgia Region Scheduling** | `regionThreadingMode = "DYNAMIC_CLUSTER"`, `regionThreadingDynamicSectionShift = 2` (4×4 chunks), `regionThreadingWorkerThreads = 8`, `enableIndependentRegionTickClock = true`, `enableRegionSandboxAllBlockEntities = true` |
| **Anti-Burst Item Protection** | `enableItemCleanup = true`, `itemCleanupIntervalSeconds = 120`, `itemCleanupMaxPerRegion = 128` (soft cap), `itemCleanupEmergencyHardCap = 512` (emergency hard cap, discards immediately regardless of `minAge`), `itemCleanupIgnoreNamedItems = true` |

---

## Earlier C7 Production Modpack Benchmarks (`21.1.250.200-beta` / SkyExplorer 188 Mods)

Validated under **GitHub Issue #5 (C7)** on a live 188-mod production server (Create 6.0.10, Cataclysm, Sable 2.0.5, TacZ, Quark, Lootr, Primal, NetherExp, Spawn; 8 Region Workers, `DYNAMIC_CLUSTER/4ch`, `enableIndependentRegionTickClock = true`, `enableRegionSandboxAllBlockEntities = true`, `simulation-distance = 6`):

| Workload / Metric | Before Phase 2 + Sandbox Fixes | C7 Phase 1+2 Live (production validation) | Notes |
| :--- | :--- | :--- | :--- |
| **Multi-player load (44–86 Regions / 267–825 chunks)** | `12.6–14.3 TPS` / `MSPT ~72ms` | **`20.0 TPS`** / **MSPT `18.2–28.4ms`** (80-min soak, 0 crashes) | Steady 20.0 TPS across all windows |
| **Active chunk exploration (179 Regions / 530 chunks)** | `300–1000ms` stall spikes | **`20.0 TPS`** / **MSPT `18.8ms` (1m Max `87.2ms`)** | Process CPU `407%` (~4 cores active) |
| **BE Worker Execution & Auto-Demotions** | Manual per-mod allowlists; `15` false-positive demotions | **`72%–82%`** on Workers; only **`2–3`** demoted BE types | Execution share, not mod-type compatibility coverage |
| **Canonical BE Serial Fences** | `4,116 ms` (911,115 fences) | **`360 ms`** (18,446 fences; `33 ms` idle) | **-91% reduction** |
| **Slice Barrier Avg** | `ENTITY_TICK 16.7ms` / `SPAWN 6.1ms` | `ENTITY_TICK` **`4.17–6.45ms`** / `SPAWN` **`0.54–1.77ms`** | Adaptive throttle + staggered spawn |
| **Independent Region Clock** | Global lockstep barrier | `enabled=true`, `858,298+` dispatches, **`failed=0`** | `Health: clean` |

---


## Historical controlled installed-server results (.100)

Historical only — the `.100` build predates the current C7 production validation and is not a current-build performance result. The final `.100` build was independently checked with the same installed 64-furnace fixture. Four 400-tick windows (two unsampled and two monitored) completed with identical full NBT and item conservation. The four means were 2.324, 2.624, 2.393 and 2.273 ms; their simple descriptive average is **2.404 ms**. The same fixture's Forgia serial windows average about **0.262 ms**, while stock NeoForge 21.1.249 averages about **0.229 ms**. Worker allocation remains about **18.7 MiB/s**. This deliberately light stationary workload is still much faster in serial; do not present the Worker result as a speedup.

The final .100 6000-tick mixed-world soak measured **8.204 ms mean / 11.320 ms P95 / 16.948 ms P99** with diagnostics enabled and **7.776 / 11.170 / 17.250 ms** with sampling disabled. Passing cells satisfied all 51 invariants. The reverse-order monitored cell also passed at 8.406 ms mean, but its unmonitored counterpart failed the final region-mapping assertion (a live bat mapped 23/23 instead of 23/22) and is excluded. Consequently, these data do not establish a causal monitoring-overhead bound or a broad parallel speedup. GameTest accelerated timing is not paced-server TPS.

See the full per-window data, environment, failure accounting and methodology in [region-threading/PERFORMANCE-BENCHMARKS.md](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PERFORMANCE-BENCHMARKS.md).


## 1. Visual Summary Across Workloads

<div class="perf-grid">
  <div class="perf-card">
    <div class="perf-card-top">
      <h3>1024 Cows / Entity Slice</h3>
      <span class="perf-delta">3.4× – 4.5× faster</span>
    </div>
    <p class="perf-sub">4-core/8-thread, JDK 21, ZGC, fixed 3×3 regions, 4 workers (lower MSPT is better).</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">Forgia Off (Serial)</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">27.3 mspt</span></div>
      <div class="bar-row"><span class="bar-label">Forgia Regionized</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:22%"></div></div><span class="bar-val">6.0 mspt</span></div>
      <div class="bar-row"><span class="bar-label">1024 Villagers Off</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">54.5 mspt</span></div>
      <div class="bar-row"><span class="bar-label">1024 Villagers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:47%"></div></div><span class="bar-val">25.7 mspt</span></div>
    </div>
    <p class="perf-note">Measured with 200 warmup + 600 ticks across two repeats. Villagers include bed + workstation POI queries.</p>
  </div>

  <div class="perf-card">
    <div class="perf-card-top">
      <h3>32 Real TCP Players (n32-spread)</h3>
      <span class="perf-delta">14.7× vs .154 · -6.4% vs Serial</span>
    </div>
    <p class="perf-sub">32 connected players spread across regions, 10s warmup + 30s window, persistent workers (lower MSPT is better).</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">.154 Persistent On</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">58.30 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 Serial (Off)</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:14%"></div></div><span class="bar-val">4.24 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 2 Workers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:14%"></div></div><span class="bar-val">4.22 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 4 Workers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:12%"></div></div><span class="bar-val">3.97 ms</span></div>
    </div>
    <p class="perf-note">Two-phase dispatch overhead fell 2.450 → 0.918 ms/tick; ping p99 fell 314.11 → 6.35 ms.</p>
  </div>

  <div class="perf-card"><div class="perf-card-top"><h3>64 Burning Furnaces (.100)</h3><span class="perf-delta perf-delta--neutral">Worker path still slower</span></div><p class="perf-sub">Final .100 installed-server confirmation; 4 workers, 2 GiB G1, 400 warmup + 400-tick windows. Full NBT and item conservation verified.</p><div class="bar-group"><div class="bar-row"><span class="bar-label">Forgia .100 Workers</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">2.40 ms</span></div><div class="bar-row"><span class="bar-label">Forgia Serial</span><div class="bar-track"><div class="bar-fill bar-fill--serial" style="width:11%"></div></div><span class="bar-val">0.26 ms</span></div><div class="bar-row"><span class="bar-label">Stock NeoForge 21.1.249</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:10%"></div></div><span class="bar-val">0.23 ms</span></div></div><p class="perf-note">Worker allocation ~18.7 MiB/s. This light stationary vanilla-BE fixture exposes coordination overhead, not whole-modpack performance.</p></div>

  <div class="perf-card"><div class="perf-card-top"><h3>6000-Tick Mixed Normal-World Soak (.100)</h3><span class="perf-delta perf-delta--neutral">No broad win established</span></div><p class="perf-sub">Redstone, POI, entities, spawning and random ticks; 2 GiB/G1 test profile.</p><div class="bar-group"><div class="bar-row"><span class="bar-label">Diagnostics ON</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">8.204 ms mean</span></div><div class="bar-row"><span class="bar-label">Sampling OFF</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:95%"></div></div><span class="bar-val">7.776 ms mean</span></div><div class="bar-row"><span class="bar-label">Reverse-order ON (passes)</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:98%"></div></div><span class="bar-val">8.406 ms mean</span></div></div><p class="perf-note">Passing cells: 51/51 invariants. Reverse-order OFF failed region mapping and is excluded; no causal monitoring-overhead bound is claimed.</p></div>
</div>

---

## 2. Subsystem Slice Table (Historical 4-Core/8-Thread ZGC Suite)

| Workload | Off (Server Thread) | Regionized (4 Workers) | Speedup / Ratio |
|---|---:|---:|---:|
| 1024 cows | 27.3 / 19.3 mspt | 6.0 / 5.8 mspt | **4.5× / 3.4×** |
| 1024 zombies | 22.8 / 22.2 mspt | 6.6 / 8.8 mspt | **3.4× / 2.5×** |
| 1024 villagers (bed + workstation) | 54.5 / 52.0 mspt | 25.7 / 22.0 mspt | **2.1× / 2.4×** |
| 1024 chunks random ticks (9×9) | 5.59 / 5.87 ms slice | 4.80 / 4.64 ms | **1.15×–1.27×** |
| 1024 chunks random ticks (3×3, interior lift) | 6.88–6.92 ms slice | 5.54 ms | **1.24×–1.25×** |
| 289 chunks natural spawning | 4.13–4.62 ms slice | 2.79–3.93 ms | **1.15×–1.65×** |
| 2048 hoppers | 3.27 mspt | 3.74–3.97 mspt | 0.82×–0.87× (slower · **Pending Optimization**) |
| 4096 furnaces (historical before `7efd88d`) | 2.60 mspt | 2.70–3.34 mspt | 0.78×–0.96× (slower · **Pending Optimization**) |

---

## 3. Block Entity Hot-Path Optimization (`main @ 7efd88d`)

Measured using `tools/performance/controlled_server.py` (64 continuously burning vanilla furnaces across 8 regions, full 3×3 loaded neighborhoods, 400 warmup + 2×400 measured ticks, 4 persistent workers, `enableRegionThreadingBlockEntityResources = true`, 2 GiB G1 heap). Every run verified 128 output items/window, inventory conservation, `furnace.nativeCompleted == 25600`, `resource.refusedWaves == 0`, and identical before/after NBT SHA256 (`bc74013f2c5b`, `2ca340b85b64`).

| Build / Condition (Window 0 / Window 1) | Mean (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Main CPU (s) | Worker CPU (s) | Alloc (MiB/s) |
|---|---:|---:|---:|---:|---:|---:|---:|
| `.98` before catalog lookahead reuse | 10.38 / 10.43 | 10.13 / 10.11 | 10.87 / 11.49 | 25.05 / 24.18 | 4.42 / 4.69 | 0.79 / 0.82 | 252.82 / 253.40 |
| Local baseline before `7efd88d` | 2.264 / 2.215 | 2.227 / 2.167 | 2.468 / 2.453 | 3.343 / 3.338 | 1.852 / 1.944 | 0.864 / 0.921 | 18.72 / 18.64 |
| Optimized `7efd88d` (first pass) | 1.202 / 1.098 | 1.173 / 1.083 | 1.364 / 1.213 | 1.818 / 1.607 | 1.425 / 1.465 | 1.056 / 1.067 | 5.25 / 5.22 |
| Optimized `7efd88d` (final commit) | **1.354 / 1.330** | **1.233 / 1.259** | **1.671 / 1.724** | **2.175 / 2.509** | 1.517 / 1.613 | 1.128 / 1.239 | **5.20 / 5.16** |
| Same-tree Forgia serial (`--bridge` off) | 0.354 / 0.342 | 0.297 / 0.305 | 0.555 / 0.517 | 0.989 / 0.821 | 1.483 / 1.840 | 0.000 / 0.000 | 0.46 / 0.44 |
| Stock NeoForge `21.1.249` (historical ref) | 0.193–0.260 | 0.182–0.240 | 0.239–0.313 | 0.528–0.615 | 1.056–1.097 | 0.000 | 0.37–0.40 |

**Takeaway**: `7efd88d` cuts MSPT mean by **40.1% (1.67× faster)**, P99 tail latency by **1.43×**, and server+worker allocation rate by **72.3% (3.61× reduction)** compared to the pre-optimization `BlockEntityResources` baseline. Even so (**Pending Optimization**), for 64 stationary vanilla furnaces, coordinating proof/lease/join across 4 worker threads still costs ~3.86× more than Forgia's serial path (`0.348 ms`), so block-entity switches remain opt-in (`false` by default).

---

## 4. 32-Player Dispatch Optimization (`.221` P5)

Measured with 32 real TCP clients (`verify_player_scale.py`, `n32-spread`, 10s warmup + 30s measurement, cross-region combat `20→19` verified):

| Version & Pool Config | Tick Mean (ms) | Tick P50 (ms) | Tick P99 (ms) | Ping P99 (ms) | Comparison |
|---|---:|---:|---:|---:|---|
| `.154` Persistent ON | 58.297 | 55.900 | 90.793 | 314.110 | Pre-optimization baseline |
| `.221` Persistent OFF (Serial, 2-round avg) | 4.237 | 4.073 | 6.134 | 6.027 | Same-version serial baseline |
| `.221` Persistent ON (2 Workers, r2 / 2-round avg) | 4.223 / 4.332 | 4.082 / 4.182 | 5.670 / 6.114 | 6.179 / 6.290 | Parity with serial (~13.5× faster than `.154`) |
| `.221` Persistent ON (4 Workers) | **3.965** | **3.800** | 6.517 | 6.346 | **-6.4% MSPT (-6.7% P50) vs serial; 14.7× vs `.154`** |

---

## 5. 10-Minute 32-Fake-Player Full-Parallel vs. Vanilla Serial Soak (`FullParallelSoakGameTests`)

Measured with `tests/src/main/java/net/neoforged/neoforge/debug/level/FullParallelSoakGameTests.java` (`:tests:runFullParallelSoakGameTestServer` vs. `:tests:runFullSerialSoakGameTestServer`, identical world seed `4242424242`, 32 `ServerPlayer` + `EmbeddedChannel` bots moving, breaking, and placing blocks for 600s):

| Metric | Forgia Full Parallel (`DYNAMIC_CLUSTER`, 7 Workers) | Vanilla Serial (`Bridge = false`) | Status / Comparison |
|---|---:|---:|---|
| Elapsed / Total Ticks | 600.02 s / 11,873 | 600.04 s / 11,859 | +0.12% throughput |
| Mean TPS | **19.79** | 19.76 | Parity |
| **P50 MSPT** | **10.78 ms** | 11.61 ms | **-7.1% (faster)** |
| **Mean MSPT** | 13.20 ms | **12.37 ms** | +6.7% (**Pending Optimization**) |
| **P95 MSPT** | 27.75 ms | **25.03 ms** | +10.9% (**Pending Optimization**) |
| **P99 MSPT** | 45.75 ms | **37.78 ms** | +21.1% (**Pending Optimization**) |
| **Max MSPT** | **1026.41 ms** | 1110.14 ms | **-7.5% (lower spike)** |
| **GC Count / Time** | **52 / 914 ms** | 63 / 1,786 ms | **-48.8% GC pause** |
| **Final Heap Used** | **1,278 MiB** | 1,543 MiB | **-17.1% (lower)** |
| Ownership Violations | **0** | N/A | `entityTicksOnWorkers=102,920` |

### Items Trailing Vanilla Serial (Pending Optimization)

1. **Mean / P95 / P99 MSPT under light load (+6.7% / +10.9% / +21.1%) — Pending Optimization**: At ~11–13 ms/tick where a single core is unsaturated, multi-slice barrier and coordination overhead exceeds parallel gains.
2. **High Block-Entity & Scheduled-Tick Fallback Rate (`fallbackBlockEntityTicks=2,166,896`, `fallbackScheduledTicks=13,565`) — Pending Optimization**: Unadmitted mixed-world ticks still pay coordinator inspection overhead before falling back to serial execution.
3. **Historical player tick fallback.** This build recorded `UNKNOWN_MOD` for test mods such as `add_pack_finders_test`. Current builds evaluate the callback and transform surface. [Player ticks](modules/player-ticks.en.md)
4. **Server-Thread Entity Ratio & Soak Coverage (`serverThreadOnlyEntityTicks=379,968`, `attacks=0`, `merges=0`) — Pending Optimization**: Reduce server-thread-pinned entity categories and improve entity attack targeting and island merge/split coverage in `FullParallelSoakGameTests`.

### 120s 32-Player Complex Behavior: Scheduling Overhead Optimization & Region Mode Comparison (`DYNAMIC_CLUSTER` 8×8 / 4×4 vs. `FIXED_3X3` vs. Vanilla Serial)

Measured on the enhanced `FullParallelSoakGameTests` workload (seed `4242424242`, 32 players exploring, merging/splitting islands, executing 4,000+ entity attacks and 2,800+ block breaks/placements with all attribution probes enabled on a 4C/8T i5-1135G7, JDK 21) after landing O(1) criterion-listener verification, incremental player-state template refresh, single-wave player dispatch, LPT snake-distributed batch ordering, and shared `SPAWN`/`CHUNK_TICK` neighborhood caches:

| Metric | Forgia Before (`DYNAMIC_CLUSTER` 8×8) | **Forgia After (`DYNAMIC_CLUSTER` 8×8, `shift=3`)** | **Forgia After (`DYNAMIC_CLUSTER` 4×4, `shift=2`)** | Forgia After (`FIXED_3X3` Fixed 3×3) | Vanilla Serial (`Bridge = false`) |
|---|---:|---:|---:|---:|---:|
| **Region Topology** | 98 sections → 14 islands (`merges=13`) | 98 sections → **14 dynamic islands** (`merges=13`) | **32 dynamic islands** (`merges=2`) | **239 fixed regions** (no merges) | Single-thread unpartitioned |
| **Coalesced Submissions Saved** | 54,226 | **54,226** | **45,688** | 0 | - |
| **Total Ticks (120s)** | 1,103 | **1,368 (+24.0%)** | 1,291 | 1,254 | 1,425 |
| **Mean TPS** | 9.19 | **11.40 (+24.0%)** | 10.75 | 10.44 | 11.87 |
| **Mean MSPT** | 84.29 ms | **56.02 – 56.72 ms (-33.5%)** | 60.16 ms | 56.67 ms | 54.32 ms |
| **P50 MSPT** | 63.45 ms | **43.68 – 46.52 ms (-31.2%, 3W: 43.34 ms)** | 44.72 ms | 45.50 ms | 32.43 ms |
| **P95 MSPT** | 144.66 ms | **101.27 – 106.74 ms (-30.0%)** | 110.12 ms | 122.16 ms | 76.94 ms |
| **P99 MSPT** | 234.99 ms | **156.61 – 172.51 ms (-33.4%)** | 167.13 ms (-28.9%) | 172.26 ms | 123.55 ms |
| **Max MSPT** | 2,971.13 ms | **1,295.74 – 3,561.12 ms (-79.5% vs serial)** | 2,810.72 ms | **1,181.43 ms** | 6,328.14 ms |
| **`ENTITY_TICK.plan`** | 0.75 ms/tick | 0.67 – 0.73 ms/tick | **0.46 ms/tick** | 0.52 ms/tick | - |
| **`ENTITY_TICK.await`** | 22.76 ms/tick | **21.55 – 22.85 ms/tick** | 22.45 ms/tick | 23.53 ms/tick | - |
| **`ENTITY_TICK.handoff`** | 26.00 ms/tick | **4.57 – 5.30 ms/tick (-82.4%)** | 5.27 ms/tick | 5.25 ms/tick | - |
| **Player `ENTITY` on Workers** | 22,628 (~5.4 waves/tick) | **28,922 (86.3%, 1.00 wave/tick)** | 24,590 (75.8%, 1.00 wave/tick) | 21,099 (69.3%, 1.00 wave/tick) | 0 |
| **Ownership Violations** | **0** | **0** | **0** | **0** | - |

---

## 6. Evidence & Limits

- All benchmark tools live under [`tools/performance/`](https://github.com/NexForgeLabs/Forgia/tree/main/tools/performance) and [`tools/player-region-tick/`](https://github.com/NexForgeLabs/Forgia/tree/main/tools/player-region-tick).
- Raw session reports and limitations are preserved in [`region-threading/PERFORMANCE-BENCHMARKS.md`](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PERFORMANCE-BENCHMARKS.md), [`region-threading/PLAYER-REGION-TICK-P5.zh.md`](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PLAYER-REGION-TICK-P5.zh.md), and [Status](status.en.md).
- Forgia does **not** claim universal "faster-than-vanilla on every modpack" performance. Always benchmark with your own workload before enabling experimental switches.
