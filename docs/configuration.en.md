# Configuration & Hardware Tuning Guide (`config/forgia.toml`)

<div class="lang-pill-bar">
  <a class="active" href="../configuration.en/">English</a>
  <a href="../configuration.zh/">中文</a>
</div>

All Forgia parallel execution and runtime diagnostics settings live in **`config/forgia.toml`** (isolated from upstream `neoforge-server.toml`). Every parallel switch defaults to `false` (equivalent to vanilla serial execution). Keys marked **Restart required** only take effect after stopping the server and starting a fresh JVM.

---

## 1. Safe starting point and hardware tuning

> **Current defaults and scope (checked against `ForgiaConfig.java`):**
> - `regionThreadingMode` defaults to `DYNAMIC_CLUSTER`. The old `FIXED_3X3` profile is retained for legacy compatibility and historical C6 comparisons; it is not the current default.
> - The parallel execution switches default to `false`. This includes the bridge, worker slices, generic Block Entity Sandbox and Independent Region Clock. A server remains on the serial path until the relevant switches are deliberately enabled.
> - C7 validated one specific 188-mod SkyExplorer workload. Its measurements are published on the [benchmark page](benchmarks.en.md); they are not universal hardware recommendations or a ready-made config for arbitrary modpacks.
> - Ordinary modded block entities can use the generic Sandbox without per-mod adapters when `enableRegionSandboxAllBlockEntities` is enabled. Complex shared-state mods may still require dedicated adapters or serial fallback.
> - Do not enable every switch just because it exists. Some features are experimental, have prerequisites, or only support particular layouts. Check the table below and the relevant module page first.

### Worker count

`regionThreadingWorkerThreads = 0` lets Forgia choose `max(1, logicalCpus - 1)`. This is a convenience default, not a promise that all logical CPUs are optimal. For production, start with a conservative explicit count, monitor the server thread, workers, chunk generation and GC together, then tune using repeatable workload tests. Leave enough CPU capacity for the server thread and non-Forgia work.

There is no single worker count that is correct for every CPU or modpack. Avoid treating the old hardware examples and historical P50/P99 figures in archived reports as current universal guidance.

### Minimal configuration workflow

1. Back up the world and current configuration.
2. Confirm the generated `config/forgia.toml` matches the build you are running.
3. Start with only the region engine and the specific execution slices your workload needs.
4. Enable generic BE Sandbox and Independent Region Clock separately; both are opt-in.
5. Test on a copy of the production world, inspect `/neoforge regionthreading status` and worker/fallback counters, then run a controlled soak.
6. If ownership violations, compatibility failures or instability appear, disable the relevant slice or set `enableRegionThreadingBridge = false`, restart and verify the serial rollback.

The snippets further down this page are feature-specific examples, not a one-size-fits-all production profile.

---

## 2. Choosing `regionThreadingDynamicSectionShift`

| `shift` | Section Size | Best For | Trade-offs |
|---:|---|---|---|
| **`2`** | `4×4` chunks (64×64 blocks) | Tightly clustered hubs / small maps / lowest P99 | Fewer island merges and lower P99, but players cross region borders more often |
| **`3` (Recommended)** | `8×8` chunks (128×128 blocks) | **Most multiplayer & modded survival servers** | Highest overall TPS, best L3 locality, highest player worker admission (~86%) |
| **`4`** | `16×16` chunks (256×256 blocks) | Very large worlds where players stay >500 blocks apart (Folia default) | Fewest boundaries, but merges into oversized islands when players are <300 blocks apart |

---

## 3. Complete Configuration Reference (All 36 `config/forgia.toml` Keys)

### 3.1 Core & Parallel Slice Switches (parallel switches default `false`; restart requirements vary by key)

| Key | Default | Prerequisites | Behavior |
|---|---:|---|---|
| `enableRegionThreadingBridge` | `false` | None | Master switch: installs the region threading engine; `false` runs vanilla serial |
| `enableRegionThreadingWorkers` | `false` | `Bridge` | Drains region task queues on native worker threads (not a master switch for tick slices) |
| `enableGlobalRegionThreadingWorker` | `false` | `Bridge` | Moves global-region queue drains onto the native worker pool |
| `enableRegionThreadingEntityTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Runs `ServerLevel` entity ticks on region workers; enables region-owned entity storage and deferred handoff |
| `enableRegionThreadingPlayerTicks` | `false` | `Bridge` + `EntityTicks` + (`FIXED_3X3`, or `DYNAMIC_CLUSTER` + `-Dforgia.dynamicPlayerSoak=true`) | Dispatches eligible player `ENTITY` (`ServerPlayer.tick`) and bounded `CONNECTION_BODY` ticks to region workers; evaluates third-party mods by actual transform/listener surface |
| `enableRegionThreadingBlockEntityTicks` | `false` | `Bridge` | Dispatches declared region-safe block entity ticks to region workers; undeclared/unsafe types stay serial |
| `enableRegionSandboxAllBlockEntities` | `false` | Block Entity worker path | Opts ordinary modded BEs into the generic Region Sandbox; compatibility guards and serial fallback still apply |
| `enableIndependentRegionTickClock` | `false` | Region engine | Enables independent per-region tick scheduling; global world state and cross-region commits remain coordinated |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | `Bridge` + `BlockEntityTicks` + **`FIXED_3X3` only** | Experimental: runs verified already-burning vanilla furnace-family ticks in directed resource waves (ignored in `DYNAMIC_CLUSTER`) |
| `enableRegionThreadingBlockEntityResources` | `false` | `FurnaceWorkWaves` + **`FIXED_3X3` only** | Experimental: enables object-level READ/WRITE claims, nonblocking leases, and scopes inside furnace waves (ignored in `DYNAMIC_CLUSTER`) |
| `enableRegionThreadingScheduledTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Bounded scheduled block/fluid tick slice (audited inert vanilla redstone-lamp phases); others stay serial in `LevelTicks` |
| `enableRegionThreadingRandomTicks` | `false` | `Bridge` | Runs chunk ticks (random block/fluid ticks, snow/ice, lightning) on region workers when the 3×3 chunk neighborhood is loaded |
| `enableRegionThreadingSpawning` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Runs natural spawning on region workers sharing the level-wide vanilla mob cap (`LevelSpawnCapCounter`) |
| `enableRegionThreadingPoi` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | Partitions POI storage per region (villagers, iron golems, bees, etc.); implied when `EntityTicks` is enabled |
| `enableRegionThreadingBorderUpdates` | `false` | `Bridge` | Captures cross-region neighbor updates and replays them on the target region's worker in the same tick |
| `enableRegionThreadingGameEvents` | `false` | `Bridge` | Captures cross-region game events (e.g., sculk vibrations) and delivers them on target region workers in posting order |
| `enableRegionThreadingPersistentWorkers` | `false` | `Bridge` | Uses persistent worker threads with work-stealing ranges instead of a fixed executor, cutting dispatch/wake overhead |
| `enableRegionThreadingPackedRegionLeases` | `false` | `Bridge` + **`FIXED_3X3`** (non-`DYNAMIC_CLUSTER`) | Packs up to 16 regions per worker task (all-or-nothing lease) to reduce per-region submission overhead; automatically bypassed in `DYNAMIC_CLUSTER` |
| `enableRegionThreadingCallerRuns` | `false` | `Bridge` + `PersistentWorkers` | Allows the server thread to steal and execute one unstarted region group at a time from persistent work-stealing batches while waiting at the barrier |
| `enableRegionThreadingParallelBorderFixpoint` | `false` | `Bridge` | Uses 8-neighbor graph coloring on the first pass of cross-region border-update fixpoint propagation so non-adjacent target regions propagate in parallel |
| `enableCanonicalFenceTail` | `false` | `Bridge` | Experimental (relaxes canonical BE order): defers unproven serial-fence block entities to a single serial tail after parallel BE waves join |
| `enableRegionThreadingFusedSlices` | `false` | `Bridge` | **P3 slice fusion**: fuses eligible per-region `CHUNK_TICK` and self-driven `ENTITY_TICK` slices into a single worker task, single barrier, and single border fixpoint per tick |

### 3.2 Region Layout, Worker Pool & Safety/Budget Controls (11 keys)

| Key | Default | Range | Restart | Behavior |
|---|---|---|---:|---|
| `regionThreadingMode` | `"DYNAMIC_CLUSTER"` | `DYNAMIC_CLUSTER` / `FIXED_3X3` / `DYNAMIC` | yes | Region policy. **`"DYNAMIC_CLUSTER"` recommended**; `"FIXED_3X3"` is the C6 baseline; `"DYNAMIC"` is legacy 16×16 |
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

### 3.4 Overload Isolation & Multi-Region Item Cleanup (15 keys)

| Key | Default | Range | Restart | Behavior |
|---|---|---|---:|---|
| `enableRegionOverloadGovernor` | `false` | bool | yes | Enables per-region overload throttling (`DYNAMIC_CLUSTER` only), isolating lagging regions to `1/N` tick rate |
| `regionOverloadBudgetNanos` | `15000000` | `1_000_000..1_000_000_000` | yes | Per-region EWMA budget in nanoseconds (default 15ms) |
| `regionOverloadEnterTicks` | `20` | `1..1200` | yes | Consecutive over-budget ticks before entering `THROTTLED` |
| `regionOverloadExitTicks` | `100` | `1..6000` | yes | Recovery ticks below exit threshold before returning to `NORMAL` |
| `regionOverloadExitRatio` | `0.6` | `0.1..0.95` | yes | Fraction of `regionOverloadBudgetNanos` for recovery credit |
| `regionOverloadMaxPeriod` | `8` | `2..64` | yes | Maximum dispatch period (`1/N`) for throttled regions |
| `regionOverloadThrottlePlayerRegions` | `false` | bool | yes | Whether regions containing players can enter `THROTTLED` (`ServerPlayer` ticks themselves are always preserved at 20 TPS) |
| `regionOverloadMaxCatchUpScheduledTicks` | `65536` | `1..1_000_000` | yes | Max scheduled ticks rescheduled per skipped tick |
| `enableItemCleanup` | `false` | bool | no | Enables multi-region aware dropped-item cleanup (scheduled sweeps + per-region emergency cap) |
| `itemCleanupIntervalSeconds` | `300` | `0..86400` | no | Seconds between scheduled sweeps (`0` disables scheduled sweeps while keeping per-region cap active) |
| `itemCleanupMinAgeSeconds` | `60` | `0..3600` | no | Minimum dropped age in seconds before an item is eligible for cleanup |
| `itemCleanupWarningSeconds` | `15` | `0..300` | no | Countdown warning seconds before a scheduled sweep (`0` disables) |
| `itemCleanupMaxPerRegion` | `256` | `0..100000` | no | Per-region emergency cap on dropped `ItemEntity` count (`0` disables) |
| `itemCleanupIgnoreNamedItems` | `true` | bool | no | Preserves dropped items with custom names |
| `itemCleanupWhitelist` | `[]` | item IDs | no | Item registry IDs never removed by cleanup (e.g. `["minecraft:nether_star"]`) |

---

## 4. Full Template

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
enableRegionThreadingPlayerTicks = true            # add -Dforgia.dynamicPlayerSoak=true to JVM flags in DYNAMIC_CLUSTER
enableRegionThreadingBlockEntityTicks = false
enableRegionThreadingFurnaceWorkWaves = false      # FIXED_3X3 only
enableRegionThreadingBlockEntityResources = false  # FIXED_3X3 only
enableRegionThreadingScheduledTicks = false
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = false    # FIXED_3X3 only (bypassed in DYNAMIC_CLUSTER)
enableRegionThreadingCallerRuns = true
enableRegionThreadingParallelBorderFixpoint = true
enableRegionThreadingFusedSlices = true

# --- Region Layout & Worker Pool [restart] ---
regionThreadingMode = "DYNAMIC_CLUSTER"          # Default architecture (FIXED_3X3 / DYNAMIC are legacy-only)
regionThreadingDynamicSectionShift = 2
regionThreadingDynamicSplitDelayTicks = 40
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 0                  # 0 = auto; or explicit worker count

# --- Phase 2: Independent Region Tick Clock & Generic BE Sandbox [restart] ---
enableIndependentRegionTickClock = true           # Folia-style per-region async clock (removes BE global barrier)
independentRegionTickIntervalNanos = 50000000     # 50ms target (20 TPS)
independentRegionMaxCatchUpTicks = 4              # Max catch-up ticks on lag
enableRegionSandboxAllBlockEntities = true        # Admit all non-piston, non-denylisted modded BEs into Region Sandbox
enableRegionThreadingFluidTicks = true            # Per-region parallel fluid ticks (inland +/-5 blocks) while keeping redstone serial

# --- Adaptive Mob Pathfinding Throttle ---
enableMobPathfindingThrottle = true               # Auto-learns slow pathfinding: >3ms -> 10t, >8ms -> 20t cooldown
mobPathfindingCooldowns = ["primal:shark=40", "minecraft:zombie_villager=20"]
mobPathfindingDefaultCooldownTicks = 0

# --- Region Overload Isolation [restart] ---
enableRegionOverloadGovernor = true
regionOverloadBudgetNanos = 15000000
regionOverloadEnterTicks = 20
regionOverloadExitTicks = 100
regionOverloadExitRatio = 0.6
regionOverloadMaxPeriod = 8
regionOverloadThrottlePlayerRegions = false
regionOverloadMaxCatchUpScheduledTicks = 65536

# --- Multi-Region Item Cleanup (hot-configurable) ---
enableItemCleanup = true
itemCleanupIntervalSeconds = 120
itemCleanupMinAgeSeconds = 120
itemCleanupWarningSeconds = 15
itemCleanupMaxPerRegion = 128
itemCleanupIgnoreNamedItems = true
itemCleanupWhitelist = []

# --- Safety, Compatibility & Budgets ---
regionThreadingOwnershipViolationMode = "WARN"
defaultRegionThreadingSupport = "UNKNOWN"
regionThreadingForceWithKnownConflicts = false
regionThreadingLongTickWarningNanos = 0
regionThreadingMaxImmediateTasksPerDrain = 10000
regionThreadingMaxScheduledTasksPerDrain = 10000
```
