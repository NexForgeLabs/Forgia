# Module: region engine, layout and ownership

English | [中文](region-engine.zh.md) · [Module map](README.en.md)
Source: [`RegionThreadingEngine`](../../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java), [`RegionLayout`](../../src/main/java/net/neoforged/neoforge/server/threading/region/RegionLayout.java), [`LevelTickPhase`](../../src/main/java/net/neoforged/neoforge/server/threading/region/LevelTickPhase.java), [`RegionWorkerPool`](../../src/main/java/net/neoforged/neoforge/server/threading/region/RegionWorkerPool.java)
Evidence: [FOLIA-REFERENCE](../../region-threading/FOLIA-REFERENCE.md), [PRODUCTION-CONFIGURATION](../../region-threading/PRODUCTION-CONFIGURATION.md), [STATUS](../../region-threading/STATUS.md)

Status: **Certified within C6 scope** for the fixed layout, four workers, documented switches. All switches
default to `false`. `DYNAMIC_CLUSTER` is experimental and falls outside the C6 certification scope.

---

## 1. Responsibilities

| Owns | Does not own |
|---|---|
| Mapping chunks to regions, leases per region, batch scheduling and barriers | Chunk generation/IO parallelisation |
| Handing each vanilla tick slice to region workers | Player tick execution (players stay on the server thread) |
| Ownership assertions for storage and global state | Structure/piston/contraption regionalisation |
| Worker pool lifecycle and shutdown join | Module-specific admission (each slice has its own) |
| Conflict policy, compatibility refusals, config capture | Guaranteeing throughput |

## 2. Class map

| Class | Responsibility |
|---|---|
| `RegionThreadingEngine` | Installation, config capture, per-tick orchestration, slice dispatch, join, shutdown |
| `RegionLayout` / `RegionPolicy` / `RegionCoordinate` | Static coordinates for fixed regions, loaded-island sections, and the legacy 16-chunk grid |
| `DynamicRegionTopology` | Loaded chunk tracking, merges and delayed splits, and published section-to-island lookup |
| `RegionState` / `RegionSchedule` / `RegionLifecycleState` | Per-region ownership, queue and lifecycle |
| `RegionOwnershipLeaseGroup` / `RegionLeasePack` | Leases; packed acquisition of up to 16 regions per task |
| `RegionWorkerPool` / `RegionWorkerExecutor` / `RegionWorkStealingDispatcher` | Fixed executor or persistent deques with segment stealing |
| `RegionTickBarrier` / `RegionTickGrouping` / `RegionTaskBatch` | Batch formation, barrier wait, grouped tasks |
| `RegionMailbox` / `GlobalRegionState` / `GlobalRegionTickRunner` | Bounded cross-region messages and global phase |
| `LevelTickPhase` / `LevelTickPhaseState` | Explicit GLOBAL/REGION alternation and violation detection |
| `RegionThreadingDiagnostics` / `RegionMetricsReport` | Counters, timings, region snapshots |
| `RegionThreadingConfig` / `RegionThreadingOptions` | Validated startup configuration |

## 3. Tick flow

```
ServerLevel.tick (server thread)
 ├─ GLOBAL segment   : time, weather, border, raids, chunk source, block events, entity management
 ├─ REGION segment   : dispatch slice batch → workers tick leased regions → barrier join
 ├─ GLOBAL segment   : publish deferred effects, drain mailbox
 ├─ REGION segment   : next slice
 └─ ... repeat; only one batch per level is in flight at a time
```

| Rule | Consequence |
|---|---|
| Exactly one region batch per level in flight | Two region slices of the same level never overlap |
| GLOBAL phases hold the level-wide state | A worker write to it is an ownership violation |
| Region phase owns only its leased regions | Reads of global state are allowed; writes are not |
| Mailbox drained at global segments | A global message is applied before the vanilla body continues |
| Phase transitions are explicit state and counted | Not a convention; a violation is reported |

## 4. Global-state ownership (asserted)

| State | Written by | Enforcement |
|---|---|---|
| Time: game time, day time, day fraction, day per tick | `ServerLevel.tickTime`, `setDayTime`, `setDayTimeFraction`, `setDayTimePerTick` | Asserted: rejected in a region phase |
| Weather | `advanceWeatherCycle`, `setWeatherParameters` | Asserted in `setWeatherParameters` |
| Game rules | `GameRules.Value#set` and per-rule setters | Documented only |
| World border | `WorldBorder#setSize`, `setCenter`, warnings/damage | Documented only |
| Scoreboard | `ServerScoreboard` objectives/teams/scores | Documented only |
| Player list + network state | `PlayerList#add/remove`, `ChunkMap` tracking, connection tick | Documented only |

"Asserted" means the accessor calls a hook that throws/counts in a region phase; "documented only" means the
mutator exists on several objects and is not interceptable at one point. Region code must treat all six rows
as server-only.

## 5. Admission gates before any region work

| Gate | Detail |
|---|---|
| Bridge installed | `enableRegionThreadingBridge`; known-conflict policy may refuse installation |
| Layout | `FIXED_3X3` is the C6 baseline. `DYNAMIC_CLUSTER` uses stable section coordinates and retains per-slice admission checks. |
| Profiler inactive | An active vanilla profiler keeps slices inline |
| Loaded neighbourhood | Required by chunk/BE/spawn slices; a FULL ticket alone does not prove load |
| Ownership/storage mode | Region-owned entity storage for entity ticks; per-slice guards |
| Budgets | Deferred-update, cascade, queue and phase budgets |
| Compatibility declarations | Server-only types/slices take precedence |

## 6. Configuration (engine-relevant subset)

| Key | Default | Range | Restart | Consequence |
|---|---|---|---|---|
| `enableRegionThreadingBridge` | `false` | bool | yes | Installs the engine; all slices depend on it |
| `enableRegionThreadingWorkers` | `false` | bool | yes | Region queue drains on the pool; not a master gate for other slices |
| `enableGlobalRegionThreadingWorker` | `false` | bool | yes | Moves global-region drains to the pool (outside C6) |
| `regionThreadingMode` | `"FIXED_3X3"` | `FIXED_3X3`/`DYNAMIC`/`DYNAMIC_CLUSTER` | yes | `DYNAMIC_CLUSTER` enables experimental loaded-island dispatch |
| `regionThreadingDynamicSectionShift` | `3` | 2..4 | yes | Section edge length is `1 << shift` chunks |
| `regionThreadingDynamicSplitDelayTicks` | `40` | 0..1200 | yes | Ticks to retain an emptied section before checking for a split |
| `regionThreadingFixedRegionChunks` | `3` | 3..32 | yes | N×N chunks, 16N×16N blocks, full height |
| `regionThreadingWorkerThreads` | `0` | 0..512 | yes | `0` → `max(1, cpus-1)`; pin explicitly for reproducibility |
| `enableRegionThreadingPersistentWorkers` | `false` | bool | yes | Persistent deques + work stealing instead of fixed executor |
| `enableRegionThreadingPackedRegionLeases` | `false` | bool | yes | Up to 16 regions acquired all-or-none per task |
| `regionThreadingOwnershipViolationMode` | `WARN` | `WARN`/`THROW`/`DISABLED` | no | Assertion handling; all modes still count; mandatory checks reject independently |
| `regionThreadingLongTickWarningNanos` | `0` | ≥0 | no | Queue-drain warning threshold; 0 disables |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | ≥0 | no | Per-queue immediate drain count (0 = unlimited) |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | ≥0 | no | Per-queue due scheduler tasks (0 = unlimited) |
| `regionThreadingForceWithKnownConflicts` | `false` | bool | yes | Measurement-only bypass; loudly unsupported |

## 7. Conflict policy

| Mod / condition | Action |
|---|---|
| `c2me` | `DISABLE_ENGINE` |
| `mobspawncontroller` | `DISABLE_SPAWNING` |
| `modernfix`, `moonlight`, `sable`, `voxy`, `byepregen`, `ftbchunks`, `alltheleaks`, `create`, `spawn` | REPORT_ONLY rows (not certificates) |
| Mod metadata `regionThreading = UNSUPPORTED` | Blocks bridge installation |
| `REQUIRED` with bridge off | Diagnostic warning; does not enable the engine |
| Unknown mod | Not rejected merely for being unknown |

Detection is by mod id; unknown transformations are not exhaustively discovered.

## 8. Failure semantics

| Event | Behaviour |
|---|---|
| Worker tick failure | Reported by the server thread; work is never built on the worker |
| Tick reaching a chunk the lease does not hold | Truncated once; its class moves to the server thread |
| Ownership violation | Counted; WARN logs, THROW throws, DISABLED suppresses the report only |
| Shutdown | Accepted work must physically finish; pools joined |
| Config reload | Engine/layout switches are startup-only; a new JVM is required |
| Level unload | Leases released through the lifecycle boundary; deferred work drained or dropped with counters |

## 9. Comparison

`DYNAMIC_CLUSTER` builds connected islands from loaded chunks. Adjacent sections, including diagonals, merge at a global phase boundary. Empty sections trigger a split check when their delay expires. Ordinary batches can coalesce work from the same island when the number of tasks exceeds the worker count. Coalescing leaves at least one task per worker available in that case. Recent section timings are tracked per slice, and a pack is capped at the greater of the largest task and average work per worker. Each section retains its own ownership lease. Ordered spawning keeps separate submissions so its participants can advance concurrently. `/forgia regions` reports island sizes, merges, splits, and submissions saved for the current dimension. Tick timings are recorded per section.

The [random-tick GameTest fixture](../../tests/src/main/java/net/neoforged/neoforge/debug/level/ServerLoadBenchmarkGameTests.java) compares the original task-count policy with weighted packing. Both runs used the same source baseline, world seed and workload coordinates, 144 workload chunks, and four workers. Each JVM warmed up for 200 ticks and measured 600 ticks.

| Metric | Task-count packing | Weighted packing |
|---|---:|---:|
| Mean MSPT | 4.050 | 2.464 |
| P99 MSPT | 10.619 | 3.961 |
| Mean chunk batch wait (ms) | 1.999 | 1.061 |

Both runs completed 115,344 worker chunk ticks with zero fallbacks and ownership violations. The final random-tick state fingerprints differed. Each policy was measured once in a single connected workload. Multi-island scaling and whole-island ownership still need validation.

| Choice | Fixed N×N | Legacy DYNAMIC |
|---|---|---|
| Region boundaries | Never merge/split | Merge/split 16-chunk grid |
| Entity/spawn/POI/fine furnace support | Yes | Not certified; several requirements unmet |
| Load-adaptive sizing | Not implemented | Not implemented |
| Config | `FIXED_3X3` + size | `DYNAMIC` |

| Choice | Fixed executor | Persistent + work stealing |
|---|---|---|
| Threads | Started as needed | Start immediately, persist |
| Dispatch | Submit whole tasks | Batch submissions, steal unstarted ranges |
| Measured | Baseline | Dispatch-only measurements; no whole-server speedup claim |
| Default | yes | off |

## 10. Limits

| Limit | Detail |
|---|---|
| Ownership granularity | Loaded-island dispatch keeps individual section leases and cross-section checks |
| One batch per level | Region slices do not overlap within a level |
| Players | Ticked by the server thread; bounded handoff tests only |
| Structures/contraptions | Not region-aware; still server thread |
| Cross-region operations | Complete at the serial completion stage, not in parallel |
| Throughput | Certification is correctness/scope, not "faster than vanilla everywhere" |

## 11. Diagnostics

| Signal | Where |
|---|---|
| Phase transitions and violations | `RegionThreadingDiagnostics`, `compat`/`status raw` |
| Worker counts/queues/steals | `/forgia workers`, dispatch lines |
| Region timing/coverage | `/forgia regions`, `/forgia region <dim> <x> <z>` |
| Barrier wait | Status lines (`barrier wait`), attribution counters |

## 12. When / how / why

| Question | Answer |
|---|---|
| When should an operator enable it? | Only after reading the readiness audit and rehearsing a canary; never "just turn it on" |
| How to enable safely? | Stop, set bridge/workers/layout explicitly, pin worker count, start a new JVM, verify `compat` |
| Why is the default off? | Unsupported work must stay serial; turning the engine on does not make mods safe |
| When should a mod author care? | When a slice needs work to leave the server thread — and only through the documented API |
| Why a fixed grid? | Deterministic ownership and reproducible measurements; dynamic merge/split breaks the fine-grained contracts |
