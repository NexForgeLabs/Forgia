# Module: cross-region operations, scheduled ticks, global state and players

English | [中文](cross-region-and-scheduled.zh.md) · [Module map](README.en.md)
Source: [`BlockEntityOperationRuntime`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/BlockEntityOperationRuntime.java), [`RegionScheduledTickAdmission`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionScheduledTickAdmission.java), [`RegionEntityInteractionTask`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityInteractionTask.java), [`PlayerRegionTransitions`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/PlayerRegionTransitions.java), [`GlobalRegionState`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/GlobalRegionState.java)
Evidence: [CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md), [CHUNK-OWNER-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CHUNK-OWNER-OPERATIONS.md), [STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)

Status: **Experimental** except the narrow lamp-only scheduled cohort, which passed C6 correctness for that
scope. All switches default off.

---

## 1. Responsibilities

| Area | Owns | Does not own |
|---|---|---|
| BE operations | At-most-once two-owner completion at the batch completion stage | Parallel cross-region leases |
| Scheduled ticks | Bounded, region-contained vanilla-lamp phases | Arbitrary scheduled callbacks |
| Entity interactions | Whole entity↔owner interactions with explicit outcomes | Moving the source's remaining tick |
| Chunk-owner ops | Ticket-backed synchronous FULL-chunk owner operations | Asynchronous chunk loading/continuation |
| Global state | Explicit global phase, bounded mailbox, fail-closed global writes | Arbitrary global writes from workers |
| Players | One-region membership, bounded handoffs, opt-in `enableRegionThreadingPlayerTicks` & `IRegionPayloadHandler` | Unverified mod player ticks or unapproved payloads (fail closed to MAIN) |
| Worker boundary fallback | Server-thread routing for worker `PacketDistributor` and visual/particle broadcasts (preserving send results), player effects/triggers, and capability/`BlockCapabilityCache`/attachment/`SavedData` boundary guards (`CapabilityListenerHolder` runs invalidation callbacks outside its registry lock) | Arbitrary cross-region mutable writes on workers |

## 2. Class map

| Class | Responsibility |
|---|---|
| `BlockEntityOperationRuntime` | References, requests, per-batch queue, completion, counters |
| `RegionScheduledTickAdmission` / `...Batch` / `...Metrics` | Lamp-only admission, dispatch, p50/p95/p99 |
| `RegionScheduledTask` / `RegionScheduler` implementations | Position/entity/global tasks with delay/period |
| `EngineRegionScheduler` / `EngineEntityRegionScheduler` / `EngineGlobalRegionScheduler` | Engine-side queueing and ownership |
| `RegionEntityInteractionTask` / `RegionEntityOwnershipScope` | Interaction queue, validation, cancellation |
| `RegionPlayerSnapshots` / `PlayerRegionTransitions` / `PlayerRegionAudit` | Per-tick snapshot, handoff, duplicates |
| `GlobalRegionState` / `RegionMailbox` / `GlobalRegionTickRunner` | Global phase, bounded messages, drains |
| `RegionChunkTickPlan`-independent `DeferredEntityAdmissionScope` | Owner-after-load admission |

## 3. Batch completion ordering (BE operations)

| Order | Step |
|---|---|
| 1 | Workers finish |
| 2 | Boundary propagation |
| 3 | Deferred block/light publication |
| 4 | Deferred entity removals |
| 5 | Worker-queued scheduled mutations published (if operations exist) |
| 6 | **Complete two-owner operations (serial)** |
| 7 | Original serial fallback |
| 8 | Remaining scheduled mutations |
| 9 | Batch closes |

| Consequence | Detail |
|---|---|
| Visibility | Operations see a stable world after all workers returned |
| Parallelism | No multi-region lease; independent machines still tick in parallel |
| Replay | Never; a partially applied operation is reported, not retried |

## 4. Scheduled tick admission (production scope)

| Gate | Requirement |
|---|---|
| Layout | Fixed region layout |
| Profiler | Vanilla profiler inactive |
| Footprint | Radius-2 cube around each callback fully loaded and inside one region |
| Blocks | Only air, cave air, void air, stone, glass, redstone block, redstone lamp in the footprint |
| Phase contents | Complete block phase consists only of vanilla redstone lamps |
| Budget | Whole phase fits the deferred-update budget |
| Random | Lamp toggles only LIT; derived random does not advance shared world RNG |

| Rejection (whole phase serial) | `SerialReason` |
|---|---|
| Unknown callback | `UNCLASSIFIED_CALLBACK` |
| Fluid phase | `FLUID_CALLBACK` |
| Non-fixed layout | `NON_FIXED_LAYOUT` |
| Profiler active | `PROFILER_ACTIVE` |
| Unloaded footprint | `UNLOADED_FOOTPRINT` |
| Foreign/unowned footprint | `FOREIGN_FOOTPRINT` |
| Unsafe neighbour | `UNSAFE_NEIGHBOR` |
| Budget exceeded | `DEFERRED_UPDATE_BUDGET` |

Any rejection keeps the **original live serial `LevelTicks` queue**; the engine does not drain it and simulate
serial. `priority`, `subTickOrder`, `clearArea`, `copyArea` and `willTickThisTick` keep their vanilla paths.

## 5. Entity interactions and owner-after-load

| Method | Source role | Completion |
|---|---|---|
| `submit(source, target, action)` | Participant | Both revalidated; callback receives live pair |
| `submitToOwner(source, owner, action)` | Admission only | Owner revalidated; source may be consumed |
| `submitToOwnerAfterChunkLoad(source, owner, pos, action)` | Admission only | Temporary ticket, synchronous load, one callback, ticket released in `finally` |

| Limit | Detail |
|---|---|
| Queue | 4096 requests per entity batch |
| Cancel | Only before `RUNNING`; not during loading |
| Cross-dimension | Rejected |
| Blocking | Loading may block the server tick; it is not async |
| Continuation | No replay of the caller's whole tick |

## 6. Global state and mailbox

| Rule | Enforcement |
|---|---|
| Time/weather writes from a region phase | Asserted and rejected |
| Other global state (rules/border/scoreboard/player list) | Documented server-only |
| Global phase ownership | Explicit `GLOBAL` segments; one region batch per level |
| Mailbox | Bounded; drained at global segments before vanilla continues |
| Global-region worker | Optional switch; still subject to storage guards |

## 7. Players and handoffs

| Property | Behaviour |
|---|---|
| Tick thread | Server thread (players are always-ticking, never leased) |
| Membership | Exactly one region |
| Snapshot | Published at level tick head, replaced once per tick, dropped on unload |
| Handoffs | Walking, 1000-block teleport, teleport during a batch, nether round trip, two players swapping regions |
| Certification | Bounded tests; not multiplayer load certification |

## 8. Configuration

| Key | Default | Notes |
|---|---|---|
| `enableRegionThreadingScheduledTicks` | `false` | Lamp cohort only |
| `enableGlobalRegionThreadingWorker` | `false` | Global queue drains on pool |
| `enableRegionThreadingPackedRegionLeases` | `false` | 16 regions per task |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | Due tasks per queue drain |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | Immediate tasks per drain |

```toml
# config/forgia.toml — scheduled tick cohort + drains
enableRegionThreadingScheduledTicks = true
# enableGlobalRegionThreadingWorker = true
regionThreadingMaxScheduledTasksPerDrain = 10000
regionThreadingMaxImmediateTasksPerDrain = 10000
```

```text
/neoforge regionthreading compat              # operations counters, scheduled reasons/latency
/neoforge regionthreading workgraph start 200 # bounded access graph for 200 phases
/forgia region minecraft:overworld 2 -1       # one region (region coordinates)
```

## 9. Comparison

| Property | BE operations | Entity interactions | Scheduled ticks | Mailbox/global |
|---|---|---|---|---|
| Unit | Two BEs | Entity + owner | Block/fluid callbacks | Message |
| Result handle | `Task<R>` | `EntityInteractionTask` | none | none |
| Parallel across regions | No (serial completion) | No (owner) | Yes, disjoint regions | No |
| Loads chunks | No | Only `AfterChunkLoad` | No | No |
| Replay on failure | Never | Never | Never | Message dropped, counted |
| Certified | experimental | experimental | C6 lamp-only | not certified |

## 10. Failure semantics

| Event | Behaviour |
|---|---|
| Operation participant replaced | `SKIPPED`/`*_REPLACED`; link must be re-established |
| Scheduled callback throws | Reported; failed group's unstarted items counted `skipped` |
| Mailbox destination closed | Message dropped, counted |
| Global write from region | Asserted rejection |
| Interaction during load | Owner retired → callback skipped, ticket released |
| Shutdown | Queues drained; accepted work physically completes; services reject afterwards |

## 11. Limits

| Limit | Detail |
|---|---|
| No public arbitrary scheduled-callback API | Only the lamp cohort is admitted |
| No cross-dimension BE operations | Rejected |
| No parallel multi-owner lease | Completion stage serial |
| No async chunk loading | Synchronous ticket-backed load only |
| No global write licence for workers | Six state families remain server-only |
| No multiplayer certificate | Bounded handoff tests only |

## 12. Diagnostics

| Signal | Meaning |
|---|---|
| `blockEntities().counters()` | accepted/completed/skipped/cancelled/rejected/failed, callbackNanos, reasons |
| `api.scheduledTicks()` | Phase counts, completions, refusals, timings, p50/p95/p99 |
| `compat` | Operations counters, scheduled reasons/latency, adapter status |
| `/forgia region ...` | Region workload; BE/serial fallback coverage partial |

## 13. When / how / why

| Question | Answer |
|---|---|
| When to use BE operations | A machine must legally complete an interaction with a foreign container |
| When not to | You only need a delayed task, or you want parallel machine ticks |
| When are scheduled ticks parallel | Only for complete lamp-only phases inside a loaded radius-2 inert footprint |
| Why fail closed | Unknown callbacks and mixed phases could touch anything; serial is the only safe default |
| Why players stay on the server thread | They are always-ticking and globally visible; no lease can own them safely |
