# Configuration & Hardware Tuning Guide (`config/forgia.toml`)

<div class="lang-pill-bar">
  <a class="active" href="../configuration.en/">English</a>
  <a href="../configuration.zh/">中文</a>
</div>

All Forgia parallel execution and runtime diagnostics settings live in **`config/forgia.toml`** (isolated from upstream `neoforge-server.toml`). Every parallel switch defaults to `false` (equivalent to vanilla serial execution). Keys marked **Restart required** only take effect after stopping the server and starting a fresh JVM.

---

## 1. Recommended Profiles by Hardware

> **Core Tuning Principles**:
> 1. **Use `DYNAMIC_CLUSTER` (`shift = 3`, i.e., 8×8 chunks / 128×128 blocks) as the default region mode**: verified by 30/30 dynamic cluster audit/topology tests and 32-player soaks (`0` ownership violations), delivering +9.2% TPS, -17% P95 MSPT, and +37% higher player worker admission than `FIXED_3X3`.
> 2. **Do not leave `regionThreadingWorkerThreads = 0` on hyperthreaded CPUs**: `0` resolves to `logicalThreads - 1` (e.g., 15 workers on an 8C/16T CPU), which oversubscribes physical cores and saturates memory bandwidth against the server thread, chunk generation, and GC. Pin workers explicitly to **`physicalCores - 1..2`**, always reserving at least **1 full physical core** for the Server thread.

### Profile A: 4-Core / 8-Thread CPU (e.g., i5-1135G7 / 4C VPS, 8–16 GB RAM)

* For **lowest median MSPT (P50) and lowest heap** (measured P50 `43.34 ms`, `-35.4%` heap vs. serial): `regionThreadingWorkerThreads = 3` (leaving 1 physical core for the Server thread).
* For **tighter P99 tail latency**: `regionThreadingWorkerThreads = 4`.

```toml
# === Core & Parallel Slice Switches ===
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true
enableRegionThreadingBlockEntityTicks = false      # serial is faster on light stationary BEs; enable for heavy furnace/audited adapter worlds
enableRegionThreadingFurnaceWorkWaves = false
enableRegionThreadingBlockEntityResources = false
enableRegionThreadingScheduledTicks = false        # only admits audited redstone-lamp phases; keep off for general servers
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = true

# === Dynamic Region Layout & Worker Pool ===
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3             # 8x8 chunks (128x128 blocks)
regionThreadingDynamicSplitDelayTicks = 40         # 2s hysteresis against boundary split/merge churn
regionThreadingWorkerThreads = 3                   # 4 physical cores: 3 workers + 1 core for Server thread (or 4 for tighter P99)
```

### Profile B: 8-Core / 16-Thread High-Clock CPU + 32 GB Dual-Channel RAM (e.g., Xeon E5-2667 v2 @ 4.1GHz, 16GB×2 DDR3-1666)

* **Hardware profile**: 8 physical cores and a large 25 MB L3 cache pair well with `8×8` dynamic sections, while dual-channel DDR3-1666 memory bandwidth is relatively tight—avoid spawning 15 hyperthreaded workers (`0`).
* **Core budget**: 1 physical core for the Server thread + **6 physical cores for Region Workers** (`5` during heavy worldgen/exploration bursts) + 1 physical core (and remaining SMT threads) for chunk generation, Netty I/O, and Generational ZGC.

```toml
# === Core & Parallel Slice Switches ===
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true
enableRegionThreadingBlockEntityTicks = true       # set false if the world has no heavy furnace/audited-adapter load
enableRegionThreadingFurnaceWorkWaves = false
enableRegionThreadingBlockEntityResources = false
enableRegionThreadingScheduledTicks = true
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = true

# === Dynamic Region Layout & Worker Pool ===
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3             # 8x8 chunks: maximizes 25 MB L3 hit rate & reduces memory bus traffic
regionThreadingDynamicSplitDelayTicks = 40
regionThreadingWorkerThreads = 6                   # 8-core sweet spot (use 5 during heavy chunk generation)
```

Recommended JDK 21 JVM flags (32 GB RAM — leave ~half for OS page cache of `.mca` region files):

```bash
-Xms12G -Xmx12G -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch
```

### Profile C: 16+ Core High-Concurrency Server (e.g., Ryzen 9 / EPYC, 64+ GB RAM)

* Reserve 2–4 physical cores for the Server thread, chunk generation, Netty, and GC; assign **10–12** to `regionThreadingWorkerThreads`.
* Keep `regionThreadingDynamicSectionShift = 3` (`8×8` chunks) for normal survival/modded servers; lower to `2` (`4×4` chunks) if 20+ players cluster tightly within a 200–300 block hub and merge into a single mega-island.

### Profile D: Vanilla Serial Rollback & Historical C6 Baseline

* **Instant Serial Rollback**: set `enableRegionThreadingBridge = false` and restart the JVM to return 100% to vanilla serial execution.
* **Historical C6 Fixed Baseline**: see `tools/production-readiness/config/c6-persistent.toml` (`regionThreadingMode = "FIXED_3X3"`, `regionThreadingFixedRegionChunks = 3`, `regionThreadingWorkerThreads = 4`).

---

## 2. Choosing `regionThreadingDynamicSectionShift`

| `shift` | Section Size | Best For | Trade-offs |
|---:|---|---|---|
| **`2`** | `4×4` chunks (64×64 blocks) | Tightly clustered hubs / small maps / lowest P99 | Fewer island merges and lower P99, but players cross region borders more often |
| **`3` (Recommended)** | `8×8` chunks (128×128 blocks) | **Most multiplayer & modded survival servers** | Highest overall TPS, best L3 locality, highest player worker admission (~86%) |
| **`4`** | `16×16` chunks (256×256 blocks) | Very large worlds where players stay >500 blocks apart (Folia default) | Fewest boundaries, but merges into oversized islands when players are <300 blocks apart |

---

## 3. Complete Configuration Reference (All 36 `config/forgia.toml` Keys)

### 3.1 Core & Parallel Slice Switches (16 keys, all default `false`, all restart-only)

| Key | Default | Prerequisites | Behavior |
|---|---:|---|---|
| `enableRegionThreadingBridge` | `false` | None | Master switch: installs the region threading engine; `false` runs vanilla serial |
| `enableRegionThreadingWorkers` | `false` | `Bridge` | Drains region task queues on native worker threads (not a master switch for tick slices) |
| `enableGlobalRegionThreadingWorker` | `false` | `Bridge` | Moves global-region queue drains onto the native worker pool |
| `enableRegionThreadingEntityTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Runs `ServerLevel` entity ticks on region workers; enables region-owned entity storage and deferred handoff |
| `enableRegionThreadingPlayerTicks` | `false` | `Bridge` + `EntityTicks` | Dispatches eligible player `ENTITY` (`ServerPlayer.tick`) and bounded `CONNECTION_BODY` ticks to region workers; evaluates third-party mods by actual transform/listener surface |
| `enableRegionThreadingBlockEntityTicks` | `false` | `Bridge` | Dispatches declared region-safe block entity ticks to region workers; undeclared/unsafe types stay serial |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | `BlockEntityTicks` + `FIXED_3X3` | Experimental: runs verified already-burning vanilla furnace-family ticks in directed resource waves |
| `enableRegionThreadingBlockEntityResources` | `false` | `FurnaceWorkWaves` | Experimental: enables object-level READ/WRITE claims, nonblocking leases, and scopes inside furnace waves |
| `enableRegionThreadingScheduledTicks` | `false` | `Bridge` | Bounded scheduled block/fluid tick slice (audited inert vanilla redstone-lamp phases); others stay serial in `LevelTicks` |
| `enableRegionThreadingRandomTicks` | `false` | `Bridge` | Runs chunk ticks (random block/fluid ticks, snow/ice, lightning) on region workers when the 3×3 chunk neighborhood is loaded |
| `enableRegionThreadingSpawning` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Runs natural spawning on region workers sharing the level-wide vanilla mob cap (`LevelSpawnCapCounter`) |
| `enableRegionThreadingPoi` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Partitions POI storage per region (villagers, iron golems, bees, etc.); implied when `EntityTicks` is enabled |
| `enableRegionThreadingBorderUpdates` | `false` | `Bridge` | Captures cross-region neighbor updates and replays them on the target region's worker in the same tick |
| `enableRegionThreadingGameEvents` | `false` | `Bridge` | Captures cross-region game events (e.g., sculk vibrations) and delivers them on target region workers in posting order |
| `enableRegionThreadingPersistentWorkers` | `false` | `Bridge` | Uses persistent worker threads with work-stealing ranges instead of a fixed executor, cutting dispatch/wake overhead |
| `enableRegionThreadingPackedRegionLeases` | `false` | `Bridge` | Packs up to 16 regions per worker task (all-or-nothing lease) to reduce per-region submission overhead |

### 3.2 Region Layout, Worker Pool & Safety/Budget Controls (11 keys)

| Key | Default | Range | Restart | Behavior |
|---|---|---|---:|---|
| `regionThreadingMode` | `"FIXED_3X3"` | `DYNAMIC_CLUSTER` / `FIXED_3X3` / `DYNAMIC` | yes | Region policy. **`"DYNAMIC_CLUSTER"` recommended**; `"FIXED_3X3"` is the C6 baseline; `"DYNAMIC"` is legacy 16×16 |
| `regionThreadingDynamicSectionShift` | `3` | `2..4` | yes | Dynamic section edge bit shift: `1 << shift` chunks (`2`=4×4, `3`=8×8 recommended, `4`=16×16) |
| `regionThreadingDynamicSplitDelayTicks` | `40` | `0..1200` | yes | Hysteresis ticks before an emptied dynamic section detaches and checks for island splitting |
| `regionThreadingFixedRegionChunks` | `3` | `3..32` | yes | Edge length in chunks of one fixed region when `regionThreadingMode = "FIXED_3X3"` |
| `regionThreadingWorkerThreads` | `0` | `0..512` | yes | Worker thread count. `0` = `max(1, logicalCpus - 1)`; pin explicitly to `physicalCores - 1..2` |
| `regionThreadingOwnershipViolationMode` | `"WARN"` | `WARN` / `THROW` / `DISABLED` | no | Handling of ownership assertion failures (all modes still count; mandatory guards reject independently) |
| `defaultRegionThreadingSupport` | `"UNKNOWN"` | `RegionThreadingSupport` enum | yes | Default support status for mods without explicit metadata |
| `regionThreadingForceWithKnownConflicts` | `false` | bool | yes | Measurement-only bypass of the known-conflict table; keep `false` in production |
| `regionThreadingLongTickWarningNanos` | `0` | `≥ 0` | no | Logs a warning when a region/global queue drain exceeds this threshold in nanoseconds (`0` disables) |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | `≥ 0` | no | Maximum immediate tasks drained per queue per tick (`0` = unlimited) |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | `≥ 0` | no | Maximum due scheduled tasks drained per queue per tick (`0` = unlimited) |

### 3.3 Runtime Diagnostics & Alert Thresholds (9 keys, hot-configurable / no restart)

| Key | Default | Range | Behavior |
|---|---:|---|---|
| `runtimeDiagnosticsEnabled` | `true` | bool | Collects bounded native tick history and worker task timings for `/forgia` commands |
| `runtimeJvmSamplingEnabled` | `true` | bool | Samples local JVM CPU, allocation, and GC counters once per wall second |
| `runtimeHighRegionMspt` | `50` | `1..10000` | Single-region MSPT threshold (ms) for `HIGH_REGION_MSPT` alert |
| `runtimeWorkerSaturationPercent` | `90` | `1..100` | Aggregate worker CPU % threshold for `WORKER_SATURATION` alert |
| `runtimeQueuePerWorker` | `2` | `1..10000` | Pending tasks per worker threshold for `QUEUE_CONGESTION` alert |
| `runtimeSerialFallbackPercent` | `50` | `1..100` | Entity/BE serial fallback % threshold for `HIGH_SERIAL_FALLBACK` (after ≥100 bodies) |
| `runtimeRegionImbalanceRatio` | `4` | `2..1000` | Hottest-region-to-mean ratio threshold for `REGION_LOAD_IMBALANCE` |
| `runtimeMainCpuPercent` | `90` | `1..100` | Main-thread single-core CPU % threshold for `MAIN_THREAD_BOTTLENECK` |
| `runtimeHighServerMspt` | `50` | `1..10000` | Mean server MSPT threshold (ms) for `MAIN_THREAD_BOTTLENECK` |

---

## 4. Full Template (All 36 Keys)

Copy to `config/forgia.toml` and adjust `regionThreadingWorkerThreads` to match your physical core count:

```toml
# Forgia Recommended Parallel Template (DYNAMIC_CLUSTER 8x8)
# Keys marked [restart] require stopping the server and starting a new JVM.

# --- Runtime Diagnostics & Alerts ---
runtimeDiagnosticsEnabled = true
runtimeJvmSamplingEnabled = true
runtimeHighRegionMspt = 50
runtimeWorkerSaturationPercent = 90
runtimeQueuePerWorker = 2
runtimeSerialFallbackPercent = 50
runtimeRegionImbalanceRatio = 4
runtimeMainCpuPercent = 90
runtimeHighServerMspt = 50

# --- Core & Parallel Slice Switches [restart] ---
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true
enableRegionThreadingBlockEntityTicks = false
enableRegionThreadingFurnaceWorkWaves = false
enableRegionThreadingBlockEntityResources = false
enableRegionThreadingScheduledTicks = false
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = true

# --- Region Layout & Worker Pool [restart] ---
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3
regionThreadingDynamicSplitDelayTicks = 40
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 6                  # 4C -> 3..4, 8C -> 5..6, 16C -> 10..12

# --- Safety, Compatibility & Budgets ---
regionThreadingOwnershipViolationMode = "WARN"
defaultRegionThreadingSupport = "UNKNOWN"
regionThreadingForceWithKnownConflicts = false
regionThreadingLongTickWarningNanos = 0
regionThreadingMaxImmediateTasksPerDrain = 10000
regionThreadingMaxScheduledTasksPerDrain = 10000
```
