# Schedulers, entity interactions and scheduled-tick diagnostics

English | [中文](schedulers-and-entity-interactions.zh.md) · [API index](README.en.md)
Source: [`RegionThreadingManager.java`](../../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingManager.java), [`RegionScheduler.java`](../../src/main/java/net/neoforged/neoforge/server/threading/RegionScheduler.java), [`EntityInteractionScheduler.java`](../../src/main/java/net/neoforged/neoforge/server/threading/EntityInteractionScheduler.java), [`ScheduledTickStatus.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/ScheduledTickStatus.java)
Evidence: [CROSS-REGION-OPERATIONS](../../region-threading/CROSS-REGION-OPERATIONS.md), [CHUNK-OWNER-OPERATIONS](../../region-threading/CHUNK-OWNER-OPERATIONS.md)

Status: **Experimental.** Schedulers require the full Forgia compile dependency and an installed engine;
`EntityInteractionScheduler` is separately marked experimental.

---

## 1. When to use which

| Need | Entry | Why |
|---|---|---|
| Run a task on the region that owns a position | `regionScheduler()` | Location-owned work; not entity-following |
| Run a task wherever an entity currently lives | `entityScheduler()` | Tasks and delays migrate with the entity |
| Touch global-region state | `globalScheduler()` | Not a licence to write arbitrary chunks |
| Decide "now or later" for a position/entity | `executeOrSchedule(...)` | Synchronous when already owned, queued otherwise |
| Query/assert ownership | `isOwnedByCurrentRegion`, `assertOwnedByCurrentRegion` | Cheap guard; not a load/mutation right |
| Complete an entity-to-owner interaction | `entityInteractions()` | Whole interaction with explicit outcome |
| Owner operation needing a FULL chunk | `submitToOwnerAfterChunkLoad` | Ticket-backed synchronous load, one callback |
| Scheduled-tick counters/latency | `api.scheduledTicks()` | Bounded diagnostics of block/fluid phases |

Do not use a scheduler as an async executor, a chunk loader, a cross-region transaction or a place to block.
None of the four exists.

## 2. Why the four paths are separate

| Path | Owner | Migration | Typical error if conflated |
|---|---|---|---|
| `RegionScheduler` | Position's region | No | Entity walks away; task still runs at the old position |
| `EntityRegionScheduler` | Entity's current region | Yes, with delays | Task lost or runs on the old owner |
| `GlobalRegionScheduler` | Global region | No | Assumed "main thread can write anything" |
| `EntityInteractionScheduler` | Both participants at completion | Validated | Partial effect, then a failed second mutation |

## 3. `RegionThreadingManager`

```java
static Optional<RegionThreading> get();
static boolean isInstalled();
static void ifInstalled(Consumer<RegionThreading> action);
static RegionThreading getOrThrow();
```

| Method | Behaviour | Failure |
|---|---|---|
| `get()` | Engine handle if installed | Empty when bridge is off |
| `isInstalled()` | Cheap boolean | — |
| `ifInstalled(action)` | Runs action **immediately on the current thread** | Does not schedule anything |
| `getOrThrow()` | Engine handle | `IllegalStateException` when absent |

`currentOrNull`, `install` and `clear` are internal lifecycle methods. Mods must not install or uninstall the
engine.

## 4. `RegionThreading` and context

```java
GlobalRegionScheduler globalScheduler();
RegionScheduler regionScheduler();
EntityRegionScheduler entityScheduler();
EntityInteractionScheduler entityInteractions();

boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
boolean isOwnedByCurrentRegion(Entity entity);
void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
void assertOwnedByCurrentRegion(Entity entity);
void executeOrSchedule(ServerLevel level, BlockPos pos, Runnable task);
void executeOrSchedule(Entity entity, Runnable task);
RegionThreadingContext currentContext();
```

| Member | Semantics | Not |
|---|---|---|
| `isOwnedByCurrentRegion` | Answers the current owner only | A chunk-load or whole-world-still proof |
| `assertOwnedByCurrentRegion` | Throws per configured violation mode | A WARN mode is not permission |
| `executeOrSchedule` | Synchronous if owned, else queued | Guaranteed "next tick"; may re-enter |
| `currentContext().kind()` | `NONE`, `GLOBAL_REGION`, `LEVEL`, `REGION`, `ASYNC` | Not a lease; saving it does not renew anything |
| `currentContext().isTickThread()` | True for global/level/region | Not a BE-operation submission permit |

## 5. The three schedulers

| Scheduler | Methods | Queue/timing | Handle |
|---|---|---|---|
| `RegionScheduler` | `execute(level,pos,task)`, `runDelayed(...delayTicks)`, `runAtFixedRate(...initialDelay,period)` | Region owning the position | `ScheduledRegionTask` for delayed/periodic |
| `EntityRegionScheduler` | `execute(entity,task)`, `runDelayed(entity,task,delay)`, `runAtFixedRate(entity,task,initial,period)` | Entity's region; migrates, re-checks liveness | `ScheduledRegionTask` |
| `GlobalRegionScheduler` | `execute(task)`, `runDelayed(task,delay)`, `runAtFixedRate(task,initial,period)` | Global region | `ScheduledRegionTask` |

### 5.1 `ScheduledRegionTask`

```java
boolean cancel();
boolean isCancelled();
boolean isDone();
```

| Constraint | Value |
|---|---|
| Delay / initial delay | `>= 0`, else `IllegalArgumentException` |
| Period | `> 0`, else `IllegalArgumentException` |
| Time unit | Logical ticks of the owning queue, not milliseconds |
| Missed periods | Not replayed; next run = actual tick + period |
| Cancel while running | Returns false; a running Runnable is not preempted |
| Engine shutdown | New submissions may throw `IllegalStateException` |
| Exceptions | Recorded by the queue; no retry, no exception retrieval from the handle |

## 6. `EntityInteractionScheduler`

```java
EntityInteractionTask submit(Entity source, Entity target, BiConsumer<Entity, Entity> action);
EntityInteractionTask submitToOwner(Entity source, Entity owner, Consumer<Entity> action);
default EntityInteractionTask submitToOwnerAfterChunkLoad(
        Entity source, Entity owner, BlockPos position, BiConsumer<Entity, LevelChunk> action);
```

| Method | Source role | Validation | Use |
|---|---|---|---|
| `submit` | Participant | Both revalidated (removal, original level identity) | Two-entity interaction |
| `submitToOwner` | Admission only; may be consumed afterwards | Owner must still exist in the source's original level | Projectile impact that consumes the projectile |
| `submitToOwnerAfterChunkLoad` | Admission only | Temporary ticket, synchronous FULL-chunk load, revalidate owner/lifecycle, one callback, ticket released in `finally` | Owner operation needing the chunk |

| Ordering/limit | Detail |
|---|---|
| Native submission source | Must be the entity tick group that owns `source` |
| Quiescent server thread | Executes immediately |
| Server thread serving workers | Rejected (`INVALID_CONTEXT`) |
| External/other thread | Rejected |
| Queue bound | 4096 requests per entity batch (shared with interactions) |
| Cancellation | Only before `RUNNING`; a failed load cannot be cancelled mid-way |
| Cross-dimension | Rejected (`LEVEL_CHANGED`) |

### 6.1 `EntityInteractionTask`

| Member | Meaning |
|---|---|
| `state()` | `QUEUED, RUNNING, COMPLETED, SKIPPED, CANCELLED, REJECTED, FAILED` |
| `reason()` | `NONE, CANCELLED, ENTITY_REMOVED, LEVEL_CHANGED, LEVEL_CLOSED, SHUTDOWN, BATCH_CLOSED, QUEUE_FULL, INVALID_CONTEXT, UNOWNED_SOURCE` |
| `cancel()` | True only if not started and never will run |
| `failure()` | Callback throwable, also reported through normal failure path |
| `isDone()` | Not `QUEUED`/`RUNNING` |

This is a **different type** from `BlockEntityOperations.Task<R>`: it has no result payload.

## 7. `ScheduledTickStatus` diagnostics

```java
record ScheduledTickStatus(String phase, long phases, long collected, long admitted,
        long workerCompletions, long serialCompletions, long failedCallbacks, long skippedCallbacks,
        long serialPhases, Map<SerialReason, Long> serialReasons, Timings timings, Latency latency) {}
record Timings(long preparationNanos, long workerNanos, long dispatchAwaitNanos,
        long completionNanos, long phaseNanos) {}
record Latency(int samples, long p50Nanos, long p95Nanos, long p99Nanos) {}
```

| Field | Unit / meaning | Misread as |
|---|---|---|
| `phase` | `block` or `fluid` | a block type |
| `phases` | Dispatcher preflights entered | non-empty phases |
| `collected` | Callbacks the scheduler selected | callbacks executed |
| `admitted` | Callbacks admitted to the worker protocol | completions |
| `workerCompletions` | Native worker bodies returning normally | executor-rejected inline calls |
| `serialCompletions` | Serial normal returns, including inline after rejection | worker work |
| `failedCallbacks` / `skippedCallbacks` | Started failures / never-invoked items | retries |
| `serialPhases` / `serialReasons` | Whole phases refused, by bounded reason | callback counts |
| `workerNanos` | Sum of per-worker callback wall time | a main-thread duration |
| `dispatchAwaitNanos` / `completionNanos` | Dispatch/wait/propagation and completion work | tick time |
| `samples`, `p50/p95/p99` | Exact quantiles over the last ≤128 non-empty phases (nanoseconds) | whole-server latency |

`SerialReason` values: `UNCLASSIFIED_CALLBACK, FLUID_CALLBACK, NON_FIXED_LAYOUT, PROFILER_ACTIVE,
UNLOADED_FOOTPRINT, FOREIGN_FOOTPRINT, UNSAFE_NEIGHBOR, DEFERRED_UPDATE_BUDGET`.

There is no public API to register arbitrary scheduled callbacks, whitelist a whole block class, or force a
test gate. Production admission covers only the vanilla-lamp, region-contained radius-2 inert footprint.

## 8. Examples

### 8.1 Position task with cancel

```java
RegionThreadingManager.get().ifPresent(threading -> {
    ScheduledRegionTask task = threading.regionScheduler().runDelayed(
            level, position.immutable(), () -> updateOwnedPosition(level, position), 20L);
    // keep the handle; never block waiting for it
});
```

### 8.2 Entity-following task

```java
threading.entityScheduler().runAtFixedRate(mob, () -> refreshOwnedGoal(mob), 0L, 10L);
```

### 8.3 Projectile impact without retaining the projectile

```java
EntityInteractionTask task = threading.entityInteractions().submitToOwner(
        projectile, owner, liveOwner -> applyCompleteImpact(liveOwner, immutableImpact));
if (task.state() == EntityInteractionTask.State.REJECTED) {
    handleRejectionBeforeConsumingProjectile(task.reason());
}
```

### 8.4 Owner operation that needs the full chunk

```java
threading.entityInteractions().submitToOwnerAfterChunkLoad(
        source, owner, pos.immutable(),
        (liveOwner, chunk) -> applyChunkBoundEffect(liveOwner, chunk, immutableInput));
```

## 9. Comparison

| Property | RegionScheduler | EntityRegionScheduler | GlobalRegionScheduler | EntityInteractionScheduler |
|---|---|---|---|---|
| Ownership source | position | entity | global region | source entity at submit, owner at execution |
| Migrates with entity | No | Yes | No | Validated at execution |
| Handle | `ScheduledRegionTask` | `ScheduledRegionTask` | `ScheduledRegionTask` | `EntityInteractionTask` |
| Result payload | No | No | No | No |
| Strong "never ran" cancel | No | No | No | Yes, before start |
| Loads chunks | No | No | No | Only the `AfterChunkLoad` variant, synchronously |
| Rejects external threads | n/a | n/a | n/a | Yes |

## 10. Failure modes

| Symptom | Cause | Fix |
|---|---|---|
| Task runs at the old place after an entity moved | Used `RegionScheduler` for entity work | Use `entityScheduler()` |
| `IllegalStateException` from `getOrThrow` | Engine not installed | Use `get()`/`ifInstalled` and keep a serial path |
| Task never runs and `isCancelled()` false | Queue capped, level unloaded, or owner retired | Inspect lifecycle; do not assume execution |
| `cancel()` false after delay | Already running | Treat as committed |
| `REJECTED / INVALID_CONTEXT` | Called from network/async/service thread | Submit at a quiescent point or from the owning tick |
| `REJECTED / UNOWNED_SOURCE` | Source tick group does not own the entity | Submit from its own tick |
| Chunk-load variant returns `SKIPPED` | Owner removed/level changed while loading | Do not retry the whole tick |

## 11. Checklist

- [ ] Engine presence checked with `get()`/`isInstalled`; serial path preserved.
- [ ] Correct scheduler for the ownership model (position vs entity vs global).
- [ ] Delays/periods are ticks and valid (`>=0`, `>0`).
- [ ] Handles kept, never blocked on; terminal state handled.
- [ ] Interaction submitted **before** effects; no live projectile retained.
- [ ] No assumption that a task ran because submission returned.
- [ ] Diagnostics read via `api.scheduledTicks()` snapshots, not cumulative totals.
