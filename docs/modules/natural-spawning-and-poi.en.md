# Module: natural spawning and points of interest

English | [中文](natural-spawning-and-poi.zh.md) · [Module map](README.en.md)
Source: [`RegionSpawnPlan`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionSpawnPlan.java), [`RegionSpawnAdmissionBarrier`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionSpawnAdmissionBarrier.java), [`LevelSpawnCapCounter`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/LevelSpawnCapCounter.java), [`RegionPoiStorage`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPoiStorage.java), [`RegionPoiStorages`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPoiStorages.java)
Evidence: [NEARBY-SPAWN-ADMISSION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/NEARBY-SPAWN-ADMISSION.md), [STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md), [MOBSPAWNCONTROLLER-LOCAL-RESULTS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOBSPAWNCONTROLLER-LOCAL-RESULTS.md)

Status: POI **certified within C6**; natural spawning **experimental and outside C6**
(`enableRegionThreadingSpawning`, default off; `enableRegionThreadingPoi`, default off but implied by entity ticks).

---

## 1. Responsibilities

| Subsystem | Owns | Does not own |
|---|---|---|
| Spawning | Per-region spawn bodies against the vanilla level-wide cap, spawn plans, admission barrier, local cap accounting | Structure filters, custom spawners, global spawn decisions |
| POI | Region-partitioned storage with published snapshots and live ticket checks | Live storage conversion, structure snapshot service |
| Adapters | Frozen rules, ordered finalization, explicit claims | Proving arbitrary third-party listeners safe |

## 2. Class map

| Class | Responsibility |
|---|---|
| `RegionSpawnPlan` / `RegionSpawnState` | Whole-plan decision, per-region state |
| `RegionSpawnSource` / `RegionSpawnChunk` / `RegionSpawnNeighborhood` | Source chunk, candidate chunk data, neighbourhood |
| `RegionSpawnBatch` / `RegionSpawnAttribution` | Dispatch/join and timing |
| `RegionSpawnAdmissionBarrier` | Cooperative stop point for ordered finalization |
| `LevelSpawnCapCounter` | Atomic check-and-reserve against the level-wide cap |
| `RegionLocalMobCapCalculator` | Region-local counting, partitions the level total |
| `NearbySpawnReservations` | Nearby-count reservations for prepared candidates |
| `RegionPoiStorage` / `RegionPoiStorages` / `RegionPoiSections` / `RegionPoiRegion` | Partitioned POI records and per-region views |
| `RegionPoiCounters` | Query/ticket counters |

## 3. Spawn flow

| Stage | Thread | Action |
|---|---|---|
| 1 | Server | `preparePlan` (adapter) before vanilla cap/state construction |
| 2 | Server | Build the plan; `PerUnit` reaches per-chunk `prepare` |
| 3 | Server | Verify gates: fixed storage, chunk/player/spawn gates, loaded neighbourhood, budget |
| 4 | Workers | Run spawn bodies; region states partition the level counts |
| 5 | Workers | Reserve cap with an atomic check-and-reserve; nearby-count limits |
| 6 | Server | If ordered finalization: park peers, publish deferred effects, run native finalize/admission/afterSpawn |
| 7 | Server | Serial fallback for refused units; started failures are not replayed |

## 4. Spawn admission gates

| Gate | Detail |
|---|---|
| Fixed entity storage | Region-owned entity storage required |
| Chunk/player/spawn gates | Existing level gates plus the player snapshot |
| Loaded neighbourhood | Candidate positions may leave the source chunk; the neighbourhood must be loaded |
| Budget | Deferred-update/spawn budgets |
| Compatibility | Conflicting mods (`DISABLE_SPAWNING`) or adapters may keep the complete serial plan |
| Adapter preparation | Every registered provider must return `Ready` for worker admission |

## 5. Spawn semantics

| Property | Guarantee |
|---|---|
| Level-wide mob cap | Enforced with an atomic check-and-reserve, never two winners |
| Region counts | Region states partition the level counts exactly |
| Candidate positions | Not bounded by the source chunk; queries use the real server entity view |
| Secondary spawns (e.g. JOCKEY) | Same rule revision; a parent not added to the world is not counted as a slot winner |
| Cancelled spawn | Keeps vanilla behaviour and RNG consumption |
| Started failed body | Reported, never replayed |
| Ordering | Ordered mode guarantees no double-take, not vanilla global candidate/RNG bit equality |

## 6. POI semantics

| Property | Guarantee |
|---|---|
| Query results | Identical to vanilla `PoiManager` (validated against a real single-threaded manager) |
| Snapshot age | Answers may be one publication old; live ticket checks still apply |
| Storage conversion | No supported live conversion; fixed layout installs partitioned storage |
| Save/reload | Equivalent records, order and free-ticket counts (tested) |
| Entity-tick implication | Enabling entity ticks also installs POI storage |

## 7. Configuration

| Key | Default | Notes |
|---|---|---|
| `enableRegionThreadingSpawning` | `false` | Requires fixed storage, gates, neighbourhood, budget |
| `enableRegionThreadingPoi` | `false` | Implied by entity ticks even when false |
| `enableRegionThreadingEntityTicks` | `false` | Installs region POI storage |
| `regionThreadingMode` / `FixedRegionChunks` | `FIXED_3X3` / `3` | Fixed layout |
| `enableRegionThreadingWorkers` | `false` | Spawn bodies run on region workers |

```toml
# config/forgia.toml — POI on, spawning left serial
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingPoi = true
# enableRegionThreadingSpawning = true   # experimental, requires an audited adapter + canary
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
```

## 8. Measured workload (historical)

| Load | Off | Regionized | Ratio |
|---|---|---|---|
| 289 chunks natural spawning (slice) | 4.13–4.62 ms | 2.79–3.93 ms | 1.15–1.65× |
| 1024 villagers POI queries | 54.5 / 52.0 mspt | 25.7 / 22.0 | 2.1–2.4× |
| 1024 zombies POI queries | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |

Spawning measurements are outside the C6 accepted configuration.

## 9. Failure semantics

| Event | Behaviour |
|---|---|
| Preparation unsupported configuration | Complete serial plan/unit |
| Worker spawn body throws | Reported; not replayed |
| Barrier/peer failure | Other participants stopped, failure reported, no replay |
| Destination unloaded mid-body | Reported failure; no whole-tick retry |
| POI snapshot missing | Query falls back per contract (live ticket check), never a torn read |
| Shutdown | Plans/units invalidated; new service on restart |

## 10. Comparison

| Property | Vanilla | Regionized spawning | Regionized POI |
|---|---|---|---|
| Execution | Server thread | Region workers (+ server finalization) | Region-partitioned storage |
| Cap | Level-wide loop | Atomic check-and-reserve | n/a |
| Ordering | Global candidate order | No double-take; not bit-identical | n/a |
| Query latency | Single-threaded | n/a | 2.1–3.4× on dense POI |
| Certified | — | outside C6 | C6 |
| Structures | Vanilla | Serial | n/a |

## 11. Limits

| Limit | Detail |
|---|---|
| Structure/light/height/season filters | Serial |
| `spawn_multiplier != 1` | Serial |
| Custom spawners / global decisions | Serial |
| Started failed bodies | Cannot be safely replayed |
| No structure snapshot service | Preparation cannot load structures |
| Multiplayer soak | Not established |

## 12. Diagnostics

| Signal | Meaning |
|---|---|
| `AdapterStatus.preparedUnits/serialUnits/serialPlans` | Prepared vs fallback volume |
| `Ordered spawn finalization: transactions/serverMs` | Continuations and server body time |
| POI counters | Query/ticket counts |
| `/forgia region ...` | Region workload/coverage (partial for BE/serial fallback) |

## 13. When / how / why

| Question | Answer |
|---|---|
| When to enable POI | Dense villager/POI workloads; it is implied by entity ticks |
| When to enable spawning | Only with an audited adapter and after a canary rehearsal; expect serial fallback otherwise |
| Why partition counts by region | The vanilla cap is level-wide; region states must not double-count |
| Why atomic reservation | Two regions must never both take the last allowed mob |
| Why POI snapshots | A worker may not walk the live `PoiManager` storage |
| Why no live storage conversion | Re-partitioning live storage mid-run cannot be proven safe |
