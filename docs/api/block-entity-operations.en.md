# BlockEntityOperations — whole two-owner operations and bounded transfers

English | [中文](block-entity-operations.zh.md) · [API index](README.en.md)
Source: [`BlockEntityOperations.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityOperations.java)
Evidence: [CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md) · Example: [`ContainerLinkExample.java`](https://github.com/NexForgeLabs/Forgia/blob/main/examples/parallel-api/ContainerLinkExample.java)

Status: **Experimental**, capability `BLOCK_ENTITY_OPERATIONS`. The completion stage is serial: this service
lets you *complete* an interaction safely, it does not give two regions parallel leases.

---

## 1. When to use

| Use it when | Why |
|---|---|
| A worker-owned machine must move items into/out of another region's container | The transfer is revalidated and executed where both participants are safe |
| A machine tick must mutate a foreign block entity as one complete negotiation | `submit` runs your callback with live participants after all workers return |
| You need an explicit outcome instead of "maybe it worked" | `Task` exposes state/reason/result/failure, at-most-once |
| You are porting a hopper/Create-style interaction that currently throws a cross-region exception | Submit before the effect instead of catching after |

| Do not use it when | Because |
|---|---|
| You only need a deferred task on a position | Use [`RegionScheduler`](schedulers-and-entity-interactions.en.md) |
| You need to modify an entity across regions | Use `EntityInteractionScheduler` |
| You want parallel ticking of two machines | Audit each ticker separately; operations serialize at completion |
| You need cross-dimension transfers | Not supported |
| You want to wait for the result on the worker | Forbidden: never block a worker |

## 2. Why the API is reference-based

A live `BlockEntity` cannot travel between owners. A `Reference` captures *instance identity* plus
dimension/position/type, weak-references the world and entity, exposes no lease and never loads a chunk.
At completion Core revalidates both identities, positions, levels and loaded chunks. A same-position
replacement therefore **cannot inherit** the original authority, and a stale link fails with an explicit
reason instead of silently retargeting.

## 3. Acquiring the service

```java
ParallelApi.Server api = ParallelApi.forServer(server).orElseThrow();
if (!api.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS)) {
    return; // keep your serial path
}
BlockEntityOperations ops = api.blockEntities();
```

| Fact | Behaviour |
|---|---|
| Engine off | Service still exists; quiescent-thread submissions execute immediately |
| Engine on | Native submissions run at the batch completion stage |
| Service stopped | `reference`/`submit` reject; references are not reusable |
| Cross-server | References and tasks are scoped to one service instance |

## 4. Full parameter surface

```java
Reference reference(BlockEntity blockEntity);
<R> Task<R> submit(Reference source, Reference target, Operation<R> operation);
Task<TransferResult> transfer(Reference source, Reference target, Transfer request);
Counters counters();
```

| Method | Parameters | Returns | Valid context | Limits |
|---|---|---|---|---|
| `reference` | `BlockEntity` attached in a loaded chunk | `Reference` | Quiescent server thread, or inside the exact admitted native ticker for the *source* | Does not load chunks; throws for removed entity/unavailable chunk/stopped service |
| `submit` | both references + `Operation<R>` | `Task<R>` | Same | ≤4096 requests per BE batch; both must be in the same `ServerLevel` |
| `transfer` | both references + `Transfer` | `Task<TransferResult>` | Same | `maxItems` 1..64; slots ≥0; sided containers need a side |
| `counters` | — | `Counters` | Any thread (read-only record) | Cumulative values |

### 4.1 `Reference`

| Member | Type | Meaning |
|---|---|---|
| `dimension()` | `ResourceKey<Level>` | level of the participant |
| `position()` | `BlockPos` | immutable position |
| `type()` | `ResourceLocation` | block entity type id |

Opaque: implementing the interface yourself does not mint a valid reference.

### 4.2 `Operation<R>`

```java
@FunctionalInterface
interface Operation<R> {
    @Nullable R execute(BlockEntity source, BlockEntity target);
}
```

| Rule | Consequence |
|---|---|
| Result must be detached immutable data | Holding a live BE does not extend mutation rights |
| `null` result allowed | Inspect `state()` to distinguish completion from absence |
| Only touch the supplied participants | Capturing other live world objects is undefined |
| Do not retry partially applied work | Effects are not rolled back |

### 4.3 `Task<R>`

| Member | Type | Meaning |
|---|---|---|
| `state()` | `State` | lifecycle state |
| `reason()` | `Reason` | terminal/refusal reason |
| `cancel()` | `boolean` | true only if the operation has not started and never will |
| `result()` | `Optional<R>` | present only after `COMPLETED` with non-null result |
| `failure()` | `Optional<Throwable>` | present after `FAILED` |
| `isDone()` | `boolean` | `state` not `QUEUED`/`RUNNING` |

| `State` | Meaning |
|---|---|
| `QUEUED` | Accepted, not started |
| `RUNNING` | Callback executing (cancel now fails) |
| `COMPLETED` | Callback returned; result may be present |
| `SKIPPED` | Participants unavailable/replaced; callback not invoked |
| `CANCELLED` | Cancelled before start |
| `REJECTED` | Never accepted (context/ownership/queue) |
| `FAILED` | Callback threw or the operation failed |

| `Reason` | Meaning |
|---|---|
| `NONE` | No terminal refusal |
| `CANCELLED` | Caller cancelled before start |
| `INVALID_REFERENCE` | Reference no longer resolves to the captured instance |
| `INVALID_CONTEXT` | Caller thread/phase may not submit |
| `UNOWNED_SOURCE` | Source ticker is not the bound source instance |
| `BLOCK_ENTITY_REMOVED` | Participant removed |
| `SOURCE_REPLACED` / `TARGET_REPLACED` | Instance at that position changed |
| `LEVEL_CHANGED` | Participant moved to another level |
| `CHUNK_UNAVAILABLE` | Participant chunk not loaded |
| `LEVEL_CLOSED` | Level closed |
| `SHUTDOWN` | Service stopped |
| `BATCH_CLOSED` | Batch ended before the request ran |
| `QUEUE_FULL` | Per-batch/per-entity request bound reached |

### 4.4 `Transfer` and `TransferResult`

```java
record Transfer(int sourceSlot, int targetSlot, int maxItems,
                @Nullable Direction extractionSide, @Nullable Direction insertionSide) {}
record TransferResult(int moved, TransferStatus status) {}
```

| Field | Constraint | Meaning |
|---|---|---|
| `sourceSlot` | ≥0 | slot in the source container |
| `targetSlot` | ≥0 | slot in the target container |
| `maxItems` | 1..64 | requested upper bound; partial moves allowed |
| `extractionSide` | nullable | required for `WorldlyContainer`; `null` for plain `Container` |
| `insertionSide` | nullable | same for the target |

| `TransferStatus` | Meaning |
|---|---|
| `MOVED` | One or more items moved |
| `NO_ITEMS` | Source slot empty |
| `DESTINATION_FULL` | Target cannot accept any |
| `ITEM_REJECTED` | Target's `canPlaceItem` refused the stack |
| `INVALID_SLOT` | Slot outside the container |
| `SIDE_REQUIRED` | Sided container without an applicable side |
| `UNSUPPORTED_CONTAINER` | Participant is not a supported `Container` |
| `SAME_CONTAINER` | Source and target are the same container |

A zero-move `COMPLETED` result is a **business outcome**, not a scheduling failure.

### 4.5 `Counters`

| Field | Meaning |
|---|---|
| `accepted` / `completed` / `skipped` / `cancelled` / `rejected` / `failed` | Cumulative request outcomes |
| `callbackNanos` | Cumulative callback wall time |
| `reasons` | `Map<Reason, Long>`, bounded enum keys only |

Never place positions, types or network ids into diagnostic labels.

## 5. Submission contexts

| Caller | Result |
|---|---|
| Quiescent server thread (including engine-off serial mode) | Executes immediately |
| Exact admitted native ticker of the source BE | Queued into this BE batch, runs after all workers return |
| Server thread while serving active workers | `REJECTED / INVALID_CONTEXT` |
| Another machine's ticker, or a worker without the matching binding | `REJECTED / UNOWNED_SOURCE` or `INVALID_CONTEXT` |
| Network/async/other thread | `REJECTED / INVALID_CONTEXT` |

## 6. Execution order inside one BE batch

| Order | Step |
|---|---|
| 1 | Workers finish their tick bodies |
| 2 | Boundary propagation |
| 3 | Deferred block/light effects published |
| 4 | Deferred entity removals |
| 5 | If operations exist: worker-queued scheduled mutations published |
| 6 | **Complete operations run (serial)** |
| 7 | Original serial fallback bodies |
| 8 | Remaining scheduled mutations |
| 9 | Batch closes |

Because step 6 runs only after every worker returned, an operation sees a stable world but never gets a
parallel multi-region lease. Independent machines still tick in parallel in their own regions.

## 7. Examples

### 7.1 Bounded transfer from an admitted ticker

```java
// quiescent phase: capture the foreign target while its chunk is loaded
BlockEntityOperations.Reference targetRef = ops.reference(targetMachine);

// inside the source machine's admitted ticker, before extracting anything
var task = ops.transfer(ops.reference(sourceMachine), targetRef,
        new BlockEntityOperations.Transfer(0, 0, 8, Direction.DOWN, Direction.UP));

// on a later safe tick; never wait on a worker
if (task.isDone()) {
    if (task.state() == BlockEntityOperations.State.COMPLETED) {
        task.result().ifPresent(r -> LOG.info("moved={} status={}", r.moved(), r.status()));
    } else {
        LOG.warn("link failed: {} / {}", task.state(), task.reason());
    }
}
```

### 7.2 Custom whole operation with detached result

```java
record ExchangeResult(int moved, boolean targetEmptied) {}

var task = ops.submit(sourceRef, targetRef, (liveSource, liveTarget) -> {
    // complete negotiation: decide, mutate both, fire their sync callbacks
    int moved = exchange(liveSource, liveTarget);
    return new ExchangeResult(moved, isEmpty(liveTarget));
});
```

### 7.3 Cancel before start

```java
var task = ops.transfer(sourceRef, targetRef, request);
if (!task.cancel()) {
    // already RUNNING or finished: cancellation is not a rollback
}
```

## 8. Comparison

| Property | `BlockEntityOperations` | `EntityInteractionScheduler` | `RegionScheduler` |
|---|---|---|---|
| Scope | Whole two-BE interaction / container transfer | Whole entity-to-owner interaction | Ordinary region task |
| Source authority | Exact bound source ticker | Entity tick group | Any caller |
| Result handle | `Task<R>` with result/failure/reason | `EntityInteractionTask` (no result) | None (`execute`) / cancel-only |
| Strong cancel guarantee | Yes, before start | Yes, before start | No preemption |
| Runs when | BE batch completion, after workers | Safe point / quiescent server | Queue drain of the target region |
| Cross-region parallelism | No (serial completion) | No (owner executes) | N/A |
| Cross-dimension | Rejected | Rejected (`LEVEL_CHANGED`) | N/A |
| At-most-once | Yes | Yes | Not guaranteed (no replay promise) |

## 9. Limits and failure semantics

| Limit | Detail |
|---|---|
| At-most-once invocation | Acceptance ≠ completion; there is no rollback |
| Same `ServerLevel` only | Cross-dimension is not accepted |
| `maxItems` ≤64 | Larger moves must be split into several requests |
| 4096 requests per BE batch | Cancellation does not refund the batch's acceptance slot |
| No chunk loading | A reference to an unloaded chunk fails `CHUNK_UNAVAILABLE` |
| Container contracts still apply | A throwing/inconsistent custom `Container` can leave partial effects; reported, never retried |
| Not a pipe network | Does not merge double chests or adapt `IItemHandler` automatically |
| Not a transaction | No arbitrary Java stack recovery |

| Failure | Reported as | Never |
|---|---|---|
| Reference to a replaced instance | `SOURCE_REPLACED`/`TARGET_REPLACED` | Retargeted automatically |
| Submission from a service thread | `INVALID_CONTEXT` | Queued anyway |
| Callback throws immediately | Thrown to the caller | Swallowed |
| Callback throws in the completion stage | Server failure path reports it | Replayed |
| Worker blocked on a task | Deadlock/truncation | Supported |

## 10. Diagnostics

```java
ops.counters(); // accepted/completed/skipped/cancelled/rejected/failed + callbackNanos + reasons
```

`/neoforge regionthreading compat` prints the same cumulative counters. Use two snapshots for rates; do not
divide accepted requests by machine count to claim parallelism.

## 11. Checklist

- [ ] Capability checked; serial path preserved when absent.
- [ ] Target reference captured while quiescent and its chunk loaded.
- [ ] Submission happens **before** any effect of the operation.
- [ ] Result data is detached and immutable.
- [ ] Polling happens on a later safe tick; no worker wait.
- [ ] Terminal state and reason handled; nothing retried after partial effects.
- [ ] Sided containers get explicit sides and checked slots.
