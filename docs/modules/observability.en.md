# Module: observation, commands and configuration

English | [中文](observability.zh.md) · [Module map](README.en.md)
Source: [`RuntimeObservability`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RuntimeObservability.java), [`RegionThreadingCommand`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/command/RegionThreadingCommand.java), [`ForgiaConfig`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java)
Evidence: [RUNTIME-OBSERVABILITY](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/RUNTIME-OBSERVABILITY.md), [PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md)

Status: **Implemented.** Monitoring adds no scheduler, pool, ticket, authority or disk writer; it reads the
existing engine and native server tick ring.

---

## 1. Responsibilities

| Owns | Does not own |
|---|---|
| `/forgia` and `/neoforge regionthreading` administrative views | Adding measurement authority |
| Native server MSPT ring and bounded history | Persisted time series |
| Region timing/workload/coverage snapshots | Complete per-region attribution |
| API diagnostic records (`Server` methods) | Live objects or mutation rights |
| Config schema, defaults, migration | Re-configuring a built engine |

## 2. Commands

| Command | Output |
|---|---|
| `/forgia` | Compact usage |
| `/forgia status` | Threading/admission/compatibility status |
| `/forgia regions [page]` | Ten regions per page, stable id order |
| `/forgia region <dimension> <x> <z>` | One region's timing/workload/lifecycle/coverage; x/z are **region** coordinates |
| `/forgia region top <metric> [count]` | Descending rank, stable tie-break; default 10, max 100 |
| `/forgia workers` | Configured/alive/busy/idle, queued/submitted/completed/rejected/failed, CPU/wall, shutdown/join; first 20 rows |
| `/forgia scheduler` | Pool/regionizer, furnace/resource counters, P3 fused slice counters, and BE adapter status |
| `/forgia bottleneck` | Critical-path breakdown: slowest region share, `estMainOrSerial`, per-slice `critAvg/barrierAvg`, and main-thread phases (`beSerial`, `entityHandoff`, `entityFallbacks`, `borderFixpoint`) |
| `/forgia fences` | Top sync-fence hotspots ranked by hit count and cost |
| `/forgia overload` | Region overload governor configuration and currently `THROTTLED` regions |
| `/forgia itemclear [status\|now [minAgeSeconds]]` | Show multi-region item cleanup stats or trigger an immediate sweep |
| `/forgia performance` | Native 10-second MSPT + latest JVM sample |
| `/forgia performance history [seconds]` | 1/10/60/300 s windows; requested 1..300 |
| `/forgia performance json` | Versioned compact JSON, nulls for unavailable numbers |
| `/forgia diagnostics` | Performance/worker/scheduler/threshold summaries |
| `/neoforge regionthreading status [raw]` | Slice/worker/fallback/health/region stats, raw diagnostics |
| `/neoforge regionthreading status players` | Player phase counts, refusal and cleanup diagnostics. [Player ticks](player-ticks.en.md) |
| `/neoforge regionthreading compat` | Compatibility declarations, adapter registrations, operations counters, scheduled reasons/latency |
| `/neoforge regionthreading workgraph [start <phases>\|stop]` | Bounded access graph, furnace wave counts, resource metrics |
| `/neoforge regionthreading regions [count]` | Regions ranked by slowness |

| Property | Detail |
|---|---|
| Permission | Level 2, applied at the root and inherited by every child (console, in-game, JSON) |
| Completion | Dimension ids, metrics, common history durations |
| Format | Plain text, consistent ms/seconds/percent/MiB units; readable without colour |
| Metrics for `top` | `mspt, avg, p50, p95, p99, entities, blockentities, chunks, pending, worker, serial` |

```text
/forgia status
/forgia region top mspt 10
/forgia performance history 60
/forgia performance json
/neoforge regionthreading compat
```

All routes require permission level 2 and inherit it from the root command.

## 3. Metric definitions

| Metric | Definition | Do not compare with |
|---|---|---|
| Native server MSPT | Completed `MinecraftServer.tickTimesNanos[tickCount % 100]` at `ServerTickEvent.Post` | A duration around an event handler |
| TPS | `(samples-1) / time between first and last completions` | `1000 / mean MSPT` |
| History | Two preallocated 12,000-entry long arrays per server, O(1) allocation-free, server-thread-owned | A persisted series |
| Window quantiles | Exact nearest-rank P50/P95/P99 over retained raw ticks | Average of per-tick percentiles |
| Region MSPT | Last-100 server ticks sum of instrumented leased-group durations | Server MSPT; regions overlap |
| Region main/worker time | Cumulative wall duration by actual pool scope | CPU time |
| Overrun count | Closed measured slots above 50 ms | Failed ticks |
| Pending | Region queue **entries** (cancelled entries counted until drained) | Work items executed |
| Player count | Per-tick player snapshot | Connected-session count |
| BE count / active chunks / pending block ticks | **N/A** — not instrumented | Zero |

| History fact | Detail |
|---|---|
| Retention | 12,000 ticks; ≥5 wall minutes at 20 TPS |
| `capacityLimited` | True when an accelerated server exhausts the ring early |
| Isolation | Owner-thread confined with a weak server reference; stop event clears it |
| Start | Collection startup; not persisted |

## 4. API diagnostics

| Entry | Returns | Bounded? |
|---|---|---|
| `Server.adapters()` | Spawn preparation statuses | Yes (ids + counts) |
| `Server.blockEntityTickAdapters()` | v1 checks/refusals/completions/timings | Yes |
| `Server.auditedBlockEntityTicks()` | Manifest integrity/reason | Yes |
| `Server.blockEntityAdapters()` | C5 statuses/refusals | Yes |
| `Server.lastBlockEntityAdapterPhase()` | Last closed session (declaration-only waves) | Yes |
| `Server.scheduledTicks()` | Phase counters/timings/quantiles | Yes |
| `blockEntities().counters()` | Operation outcomes + callback time | Yes |

Never build diagnostics from positions, types or network ids; `Reason`/`Decision`/`SerialReason` are fixed
enums precisely so labels stay bounded.

## 5. Configuration and lifecycle

| Fact | Detail |
|---|---|
| File | `config/forgia.toml`, a SERVER config; world override `serverconfig/forgia.toml` replaces it as a whole. See [Configuration & Hardware Tuning Guide](../configuration.en.md) for all 36 keys and layout prerequisites |
| Migration | Before load: `forgia.toml` wins, else legacy `neoforge-server.toml`, then `neoforge-server.forgia.toml` |
| Deleting `forgia.toml` | Can reactivate old enabled settings; rollback writes explicit `false` |
| Restart-only | Engine/layout/storage/slice/service switches (all default `false`, including `enableRegionThreadingPlayerTicks`, `enableRegionThreadingPersistentWorkers`, `enableRegionThreadingPackedRegionLeases`, and `DYNAMIC_CLUSTER` layout). Note: under `DYNAMIC_CLUSTER`, `enableRegionThreadingPlayerTicks` also requires `-Dforgia.dynamicPlayerSoak=true`, while `FurnaceWorkWaves`, `BlockEntityResources` and `PackedRegionLeases` apply to `FIXED_3X3` only |
| Live readers | Warning/drain/ownership settings (pin them during a canary) |
| Bad values | Spec corrects out-of-range values; direct layout construction rejects |

## 6. What is explicitly N/A

| Missing | Why |
|---|---|
| Per-region BE/serial-fallback time | Canonical prefix/body/commit not fully instrumented |
| Block-entity count, active ticking chunks, pending block/fluid ticks per region | Would require scanning chunk contents |
| Per-region admission/refusal/commit/coordination/contention | Not instrumented |
| CPU attribution per region | Thread scope does not map 1:1 to regions |
| Complete region tick-cost attribution | Leased-group timers cover only part of the work |

N/A is reported as N/A, not a fabricated zero (for example `blockentities` ranking explains the missing
measurement).

## 7. Reading a report without fooling yourself

| Mistake | Correction |
|---|---|
| Summing region durations to infer server MSPT | Regions overlap; use native server MSPT |
| Using cumulative counters as a rate | Take two snapshots and subtract |
| Treating `admitted` as completed | Only `workerCompletions` (and serial completions) are returns |
| Treating a worker ratio as a speedup | Measure whole-tick MSPT/CPU/allocation |
| Comparing across different worker counts/layouts | Pin configuration per run |
| Reading `implementationsVerified` as safety | It is manifest integrity only |
| Treating history as a persisted series | It clears on disable/stop |

## 8. Triage workflow

| Symptom | First check | Then |
|---|---|---|
| MSPT high, workers idle | Bridge/workers switch, compatibility refusals | `compat` for `DISABLE_*` and slice flags |
| Workers busy, MSPT still high | Scope: is the work in a region slice at all? | `scheduler`, `region top mspt` |
| BE slice "on" but no completion | Phase >2048, interior rule, unknown class | `compat` adapter lines, refusal reasons |
| Spawning serial | `serialPlans`, conflict action, config gates | Adapter preparation matrix |
| Scheduled phases serial | `serialReasons` | Check profiler, footprint, fluid phase |
| Memory/GC pressure | History + JVM sample | Compare identical workloads, not across configs |

## 9. Limits

| Limit | Detail |
|---|---|
| Region coverage partial | BE/serial fallback timers are incomplete |
| No per-region CPU | Wall duration only |
| No persisted metrics | Collection is in-memory per server |
| Monitoring overhead | Not detected above JVM/workload variation; no negative-cost claim |
| Not a profiler | JFR/profiler runs are separate and excluded from benchmarks |

## 10. When / how / why

| Question | Answer |
|---|---|
| When to open these commands | Before enabling a switch, after each change, and during a canary |
| How to compare runs | Same config, same world, same workload, reverse order, snapshot deltas |
| Why so much N/A | Honest gaps beat plausible-looking zeros; coverage requires instrumentation work |
| Why collect natively | A correct MSPT must match Minecraft's own tallying boundary |
| Why bounded labels | Unbounded reasons would leak positions and explode memory |
