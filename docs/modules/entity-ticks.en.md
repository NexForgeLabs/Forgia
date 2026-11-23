# Module: entity ticks

English | [中文](entity-ticks.zh.md) · [Module map](README.en.md)
Source: [`RegionEntityTickPlan`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityTickPlan.java), [`RegionEntityTickBatch`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityTickBatch.java), [`RegionScopedEntityQuery`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionScopedEntityQuery.java), [`RegionPlayerSnapshots`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerSnapshots.java)
Evidence: [OWNERSHIP-PROTOCOLS-VALIDATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/OWNERSHIP-PROTOCOLS-VALIDATION.md), [ENTITY-CALLBACK-LIFECYCLE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/ENTITY-CALLBACK-LIFECYCLE.md), [STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)

Status: the bounded entity-tick path passed its **historical C6 acceptance scope** (`enableRegionThreadingEntityTicks`). This is not a blanket mod-AI compatibility claim. The current default region policy is `DYNAMIC_CLUSTER`; parallel execution remains opt-in and off by default.

---

## 1. Responsibilities

| Owns | Does not own |
|---|---|
| Region-partitioned entity storage and tick batches | Default player ticks (server thread unless opt-in experimental `enableRegionThreadingPlayerTicks` is enabled) |
| Identity ledger, transfer journal, deferred add/remove/section updates | Entity AI implementations or mod callbacks |
| Region-scoped entity query answers (index, buckets, player snapshot) | Chunk loading |
| Per-entity attribution and query counters | Guaranteeing mod tick body safety |

## 2. Class map

| Class | Responsibility |
|---|---|
| `RegionEntityTickPlan` / `RegionEntityTickEntry` | Order entities into a per-region plan |
| `RegionEntityTickBatch` / `RegionEntityTickSlice` | Dispatch a batch, join, publish |
| `RegionEntityStorageMode` / `FixedEntitySectionPartitions` | Region-owned storage layout |
| `EntityIdentityLedger` / `EntityIdentity` | Identity stability across moves |
| `RegionEntityTransferJournal` / `RegionEntityTransferToken` | Cross-region entity handoff |
| `RegionDeferredEntityAdditions` / `...Removals` / `...SectionUpdates` | Deferred membership mutations |
| `RegionScopedEntityQuery` / `RegionAwareEntityGetter` / `RegionEntityQueryCounters` | Worker-side query answers |
| `RegionPlayerSnapshots` | Per-tick immutable player snapshot |
| `RegionEntityTickPolicy` / `RegionEntityTickActivation` | Eligibility and pre-body checks |
| `RegionEntityTickAttribution` / `RegionEntityOwnershipScope` | Timing and ownership assertions |

## 3. Tick flow

| Stage | Thread | Action |
|---|---|---|
| 1 | Server | Build the per-region entity plan (tick-list order, pinned exclusions) |
| 2 | Server | Publish player snapshot; freeze batch metadata |
| 3 | Workers | Tick entities of the leased region, answering queries from region data |
| 4 | Workers | Record foreign writes / deferred additions and removals |
| 5 | Server | Barrier join; boundary propagation |
| 6 | Server | Publish deferred block/light/entity changes; drain transfer journal |
| 7 | Server | Serial fallback for refused entities; failure reporting |

Only one entity batch per level is in flight; the barrier is paid once per slice, not per entity.

## 4. Admission and eligibility

| Gate | Detail |
|---|---|
| Section-partitioned layout + region-owned storage | Required (`DYNAMIC_CLUSTER` or `FIXED_3X3`); legacy `DYNAMIC` is not supported |
| Inactive vanilla profiler | Active profiler keeps entities inline |
| Not pinned | Pinned classes/types and riding stacks stay serial |
| Pre-body footprint available | Otherwise the entity stays serial |
| Chunk owned | A worker that reaches a chunk outside the lease truncates that work once and is counted |
| Server-only declaration | `entities`/`block_entities` take precedence over any admission |

## 5. Query surface answered to a worker

| Entry point | Worker answer | Counter |
|---|---|---|
| `get(int)`, `getEntity(int)`, `getEntityOrPart(int)` | Region entity index once the level index is published | `local`; `refused` for foreign region, deferred removal, unpublished index |
| `get(UUID)`, `getEntity(UUID)` | Region index (projectile owner resolution) | `local`/`refused` |
| `getAll()`, `getAllEntities()` | Region index in tick-list order | `local`, `truncated` when the level index is larger |
| `get(EntityTypeTest, consumer)`, `getEntities(EntityTypeTest, Predicate, List, int)` | Region index | `local`/`truncated` |
| `get(AABB, Consumer)`, `getEntities(...AABB...)`, `Level.getEntities(...)` | Region section bucket clamped to the lease; `Level.getEntities` also appends answered multipart owners' parts | `local`, `truncated` on clamp |
| Player class queries (`getEntitiesOfClass(Player.class, box)`, subtypes) | Region bucket + published players whose recorded bounding box intersects | `local` + `published` |
| `players()`, `getNearestPlayer`, `getPlayerByUUID`, `getNearbyPlayers`, `hasNearbyAlivePlayer`, `getPlayers`, `getRandomPlayer` | Per-tick player snapshot | `published`; `refused` without a snapshot |
| `EntityStorageGuardedGetter` | Still refused (no level → no lease) | `refused` |
| Transfer journal, identity ledger, section partitions | Still refused (server-owned state) | violation counter |

| Snapshot fact | Consequence |
|---|---|
| Published at the head of the level tick, replaced not mutated | At most one tick old |
| Joined/left/dimension-changed players absent | Query reflects the tick-open state |
| Bounding boxes are as recorded at tick open | A clipping box answers like a vanilla section scan |
| Entity itself is live | Predicates/targeting read current state like vanilla |
| No snapshot → refused | Never falls back to the live list |

## 6. Deferred writes and publication

| Deferred work | Applied at |
|---|---|
| Block/light effects from workers | Server phase after join, before serial fallback |
| Entity additions | Server phase, ordered |
| Entity removals | Server phase, before operations completion |
| Entity section updates | Server phase |
| Cross-region entity transfer | Journal drain at the boundary |

A worker write outside its lease is recorded and applied by the target region in the same tick (border
propagation); a destination without a live region drops the update **with a counter**, never silently.

## 7. Configuration

| Key | Default | Notes |
|---|---|---|
| `enableRegionThreadingEntityTicks` | `false` | Requires `DYNAMIC_CLUSTER` or `FIXED_3X3`, region-owned storage, inactive profiler; implies POI storage |
| `enableRegionThreadingPoi` | `false` | Also implied by entity ticks even when false |
| `regionThreadingMode` | `DYNAMIC_CLUSTER` | `DYNAMIC_CLUSTER` (recommended) or legacy `FIXED_3X3` (C6 comparison profile); legacy `DYNAMIC` stays serial |
| `regionThreadingDynamicSectionShift` / `regionThreadingFixedRegionChunks` | `3` / `3` | Section shift for `DYNAMIC_CLUSTER` (`3` = 8×8) or fixed grid edge length for `FIXED_3X3` |
| `regionThreadingWorkerThreads` | `0` | Pin for reproducible measurements |
| `regionThreadingOwnershipViolationMode` | `WARN` | Advisory assertions only; storage guards still reject |

```toml
# config/forgia.toml — minimal entity tick slice (DYNAMIC_CLUSTER recommended; FIXED_3X3 is the legacy C6 comparison profile)
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true          # implies POI storage
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3
regionThreadingWorkerThreads = 4
```

## 8. Measured workload (historical)

| Workload | Off (server) | Regionized | Speedup |
|---|---|---|---|
| 1024 cows | 27.3 / 19.3 mspt | 6.0 / 5.8 | 4.5× / 3.4× |
| 1024 zombies | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |
| 1024 villagers (bed + workstation) | 54.5 / 52.0 | 25.7 / 22.0 | 2.1× / 2.4× |

These are bounded fixtures, not a modpack claim.

## 9. Failure semantics

| Event | Behaviour |
|---|---|
| Worker tick throws | Reported by the server thread after join; the ticker is not replayed |
| Chunk outside the lease | Work truncated once; class moves to the server thread; counted |
| Deferred removal of a queried entity | Query refused, not answered from stale index |
| Shutdown/unload | Physical completion boundary honoured; journal drained or dropped with counters |
| Mod callback does a foreign write | Recorded; convergence or counted drop at the boundary |

## 10. Comparison

| Property | Entity ticks | Chunk ticks | Block-entity ticks |
|---|---|---|---|
| Ownership unit | Entity → region | Chunk → region | Block entity → region batch |
| Per-tick body | Original entity tick | Random/ice/snow/thunder | Original BE ticker prefix |
| Typical blocker | Pinned type, riding stack, foreign query | Unloaded neighbourhood, cascade budget | Shared resource, unknown branch, 2048 phase |
| Player handling | Snapshot queries | n/a | n/a |
| Measured benefit | High on dense mobs | Modest (1.15–1.27×) | Not claimed |

## 11. Limits

| Limit | Detail |
|---|---|
| Players | Server thread by default; opt-in experimental dispatch via `enableRegionThreadingPlayerTicks` (see [player-ticks](player-ticks.en.md)) |
| Mod AI | Arbitrary mod tick bodies are not closure-certified by C6 |
| Structures/contraptions | Entity-like contraptions are not region-aware |
| Query surface | Only the documented entry points are answered; others refuse |
| Load adaptation | None; static layout only |

## 12. Diagnostics

| Signal | Meaning |
|---|---|
| `Region entity queries:` line | `local`/`published`/`refused`/`truncated` counts per operation |
| Ownership violation counter | Advisory violations (also mandatory guard rejections where applicable) |
| `/forgia region ...` | Region timing/workload/coverage |
| `/forgia workers` | Busy/idle/queued/completed/failed |

## 13. When / how / why

| Question | Answer |
|---|---|
| When to enable | Dense entity workloads where the server thread is the bottleneck, after a canary rehearsal |
| How | Fixed layout, region-owned storage, worker count pinned, verify counters |
| Why region-owned storage | Vanilla storage is server-thread mutable; a worker must not walk it |
| Why the player snapshot | Players are always-ticking server-thread entities; a live list must never be walked by a worker |
| Why truncation | Replaying work after a partial body would duplicate effects |
