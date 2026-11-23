# Module implementation notes — map and status

English | [中文](README.zh.md) · [docs index](../README.en.md)

These pages describe **how the implemented runtime works**: ownership, tick pipelines, admission, failure
handling, configuration and observation. They are implementation notes, not API tutorials — API contracts
live in [`docs/api`](../api/README.en.md).

Status labels follow [docs/README](../README.en.md#3-status-legend). C6 certification labels refer to the historical C6 acceptance profile. C7 separately validated a 188-mod production workload with `DYNAMIC_CLUSTER`, generic BE Sandbox and Independent Region Clock. Historical microbenchmarks below are not current-build speedup claims. Every parallel execution switch remains **off by default**.

---

## 1. Module map

| Module | Primary classes | Config keys | Status | Doc |
|---|---|---|---|---|
| Region engine & ownership | `RegionThreadingEngine`, `RegionLayout`, `RegionState`, `RegionSchedule`, `RegionOwnershipLeaseGroup`, `RegionWorkerPool`, `RegionWorkStealingDispatcher`, `RegionTickBarrier`, `RegionMailbox` | `enableRegionThreadingBridge`, `...Workers`, `regionThreadingMode`, `...WorkerThreads`, `...PersistentWorkers`, `...PackedRegionLeases` | Certified (scope) | [region-engine](region-engine.en.md) |
| Entity ticks | `RegionEntityTickPlan`, `...Batch`, `...Entry`, `RegionEntityStorageMode`, `FixedEntitySectionPartitions`, `EntityIdentityLedger`, `RegionEntityTransferJournal`, `RegionScopedEntityQuery` | `enableRegionThreadingEntityTicks` | Certified (scope) | [entity-ticks](entity-ticks.en.md) |
| Chunk ticks & cross-region effects | `RegionChunkTickCoordinator`, `...Plan`, `...Batch`, `RegionBorderUpdates`, `RegionBorderGameEvents`, `RegionDeferredBlockUpdates` | `enableRegionThreadingRandomTicks`, `...BorderUpdates`, `...GameEvents` | Certified (scope) / experimental | [chunk-ticks](chunk-ticks.en.md) |
| Natural spawning & POI | `RegionSpawnPlan`, `...Batch`, `...State`, `RegionSpawnAdmissionBarrier`, `NearbySpawnReservations`, `RegionPoiStorage(s)`, `RegionPoiSections` | `enableRegionThreadingSpawning`, `...Poi` | Experimental / certified (scope) | [natural-spawning-and-poi](natural-spawning-and-poi.en.md) |
| Block entities | `CanonicalBlockEntityCoordinator`, `NativeBlockEntityPreparation`, `FurnaceWorkPreparation`, `BlockEntityResourceOwnership`, `BlockEntityWorkPlanner`, `AuditedBlockEntityPreparation` | `enableRegionThreadingBlockEntityTicks`, `...FurnaceWorkWaves`, `...BlockEntityResources` | Certified (scope) / experimental | [block-entities](block-entities.en.md) |
| Cross-region ops, scheduled ticks, players | `BlockEntityOperationRuntime`, `RegionScheduledTickAdmission`, `RegionEntityInteractionTask`, `RegionPlayerSnapshots`, `PlayerRegionTransitions`, `GlobalRegionState` | `enableRegionThreadingScheduledTicks`, `enableGlobalRegionThreadingWorker` | Experimental | [cross-region-and-scheduled](cross-region-and-scheduled.en.md) |
| Player ticks | `RegionPlayerTickCoordinator`, `RegionPlayerTickDispatch`, `RegionPlayerNetworkGate` | `enableRegionThreadingPlayerTicks` | Experimental | [player-ticks](player-ticks.en.md) |
| Observation & configuration | `RuntimeObservability`, `RegionDiagnosticsCollector`, `RegionMetricsReport`, `ForgiaConfig`, `/forgia` tree | diagnostics keys | Implemented | [observability](observability.en.md) |

## 2. How to read a module page

Every page uses the same sections so implementations can be compared:

| Section | Question it answers |
|---|---|
| Responsibilities | What this subsystem owns, and what it explicitly does not |
| Class map | Which class does which job (implementation entry points) |
| Tick flow | Ordered stages, threads and barriers |
| Admission gates | What must be true before work leaves the server thread |
| Configuration | Keys, defaults, restart requirements, certified values |
| Failure semantics | What happens on refusal, exception, shutdown |
| Limits | What is not implemented or not certified |
| Diagnostics | Which counters/commands prove behaviour |
| When / how / why | Operator and mod-author guidance |

## 3. Threading model in one table

| Executor | Runs | Owns |
|---|---|---|
| Server thread | Level tick phases that were refused or are not regionized, finalization, publication, operations completion, player connection traversal | Everything not leased |
| Region worker | A leased region's entity/chunk/BE/scheduled batch and admitted player bodies | Only the leased region's storage |
| Global-region worker (opt-in) | Global-region queue drains | Global state, not arbitrary chunks |
| Async/external | Engine internals, IO, network | No world mutation |

Ownership is by lease, not by thread name. `Thread.currentThread().getName()` is never a correctness signal.

## 4. Status matrix of the slices

| Slice | Implemented | Historical measured result | Validation scope | Notes |
|---|---|---|---|---|
| Entity ticks | Yes | 2.5–4.5× on 1024 mobs (historical) | C6 historical | Pinned classes/riding stacks stay serial |
| Chunk random ticks | Yes | 1.15–1.27× (historical) | C6 historical | 100 % of dispatched chunks on workers after interior-rule lift |
| Natural spawning | Yes | Slice 4.6 → 2.8–3.9 ms | Outside C6 | Requires `DYNAMIC_CLUSTER` or `FIXED_3X3`, spawn gates, caps |
| POI | Yes | 2.1–3.4× queries (historical) | C6 historical | Snapshot may be one publication old |
| Block entities | Yes | No whole-server gain claimed | C6 historical (bounded cohorts) | Hoppers/furnaces can be slower under light load |
| Redstone/border | Yes | Same-tick convergence | C6 (fixed layouts) | Fixpoint with cascade budget |
| Cross-region BE ops | Yes | n/a (correctness) | Experimental | Completion stage serial |
| Scheduled ticks | Yes | n/a (correctness) | C6 lamp-only cohort | Radius-2 inert footprint |
| Player ticks | Opt-in bounded dispatch | 32-player short GameTest coverage | Experimental | Each connection body joins before traversal continues |
| Structures/contraptions | No | — | — | Still server thread |

## 5. Where the evidence lives

| Topic | Evidence document |
|---|---|
| Overall status and roadmap | [STATUS](../status.en.md) |
| Configuration matrix | [PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md) |
| Benchmarks | [PERFORMANCE-BENCHMARKS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PERFORMANCE-BENCHMARKS.md) |
| Block entities | [BLOCK-ENTITY-PARALLELISM](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/BLOCK-ENTITY-PARALLELISM.md), [CANONICAL-BLOCK-ENTITY-COORDINATOR](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md) |
| Entity/ownership protocols | [OWNERSHIP-PROTOCOLS-VALIDATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/OWNERSHIP-PROTOCOLS-VALIDATION.md) |
| Cross-region/scheduled | [CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md) |
| Observability | [RUNTIME-OBSERVABILITY](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/RUNTIME-OBSERVABILITY.md) |
