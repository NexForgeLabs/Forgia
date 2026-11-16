# ParallelApi — server service, keys and preparation results

English | [中文](parallel-api.zh.md) · [API index](README.en.md) · Source: [`ParallelApi.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/ParallelApi.java)

Status: **Experimental (major 1).** `ParallelApi` is the only always-present mod-facing service. It never
grants world ownership; it lets you *negotiate*, *register* and *read bounded diagnostics*.

---

## 1. When to use

| Situation | Use `ParallelApi` | Because |
|---|---|---|
| You need to know whether a protocol exists before touching a slice | `forServer(server).capabilities()` | Capabilities are per-server and appear in the registration event |
| Your mod has rule data the spawn body needs but must not compute on a worker | `register(...)` + `prepared(key)` | Preparation is pure and server-thread; the body reads a snapshot |
| You want the whole spawn plan decided before vanilla caps are built | `preparePlan` returning plan-wide `Ready` | One frozen snapshot per plan instead of per chunk |
| You must keep a chunk on the server thread for an unsupported configuration | `prepare` returning `ServerRequired(reason)` | Explicit, diagnosable fallback; no silent serial |
| You need counters for your adapter | `adapters()`, `blockEntityTickAdapters()`, `auditedBlockEntityTicks()` | Bounded values only; no live objects |
| You want to move items across regions | `blockEntities()` | Dedicated at-most-once service ([ops](block-entity-operations.en.md)) |

Do **not** use `ParallelApi` to: obtain a thread, a lease, a chunk-loading facility, an event bus, or a
"safe to mutate" flag. None of those exist here.

## 2. Why it is designed this way

* **One service per `MinecraftServer`.** The runtime keys everything by server instance, so a static
  singleton would leak across integrated-server worlds and test servers.
* **Registration is transactional and frozen.** Order, budgets and duplicate checks must be decided before
  any level ticks; there is no "register later" path.
* **Preparation is a pure function.** A worker may not read live world objects, consume RNG or fire events,
  so the only safe input is a detached immutable snapshot produced on a quiescent thread.
* **Fallback is a value, not an error.** `ServerRequired` is a normal result, so adapters can be honest about
  partial support without throwing.
* **Diagnostics never hand out authority.** Counters and enum reasons are safe to print; entity references
  are not.

## 3. Static entry points

```java
public static final int MAJOR_VERSION = 1;

public static Optional<ParallelApi.Server> forServer(MinecraftServer server);
public static <S> Optional<S> prepared(ParallelApi.DataKey<S> key);
public static Optional<ParallelApi.SpawnUnit> currentSpawnUnit();
```

| Method | Parameter | Returns | Valid when | Notes / failure |
|---|---|---|---|---|
| `MAJOR_VERSION` | — | `1` | always | Compare before calling any registration method |
| `forServer` | `MinecraftServer` (non-null) | `Optional<Server>` | after registration freeze, until shutdown | Empty before freeze and after shutdown; `null` throws NPE |
| `prepared` | `DataKey<S>` (non-null) | `Optional<S>` | inside the matching natural-spawn body and its synchronous callbacks | Empty on native/off paths, other threads, after the body; presence does **not** mean a filter ran |
| `currentSpawnUnit` | — | `Optional<SpawnUnit>` | same scope as `prepared` | Source chunk metadata only; not a candidate-position bound |

`prepared` is not inherited by threads you start, and it is not an event-cancellation flag.

## 4. `Server` — full method table

```java
int apiMajorVersion();
Set<Capability> capabilities();
boolean regionThreadingInstalled();
Phase currentPhase();
List<AdapterStatus> adapters();
List<BlockEntityTickAdapter.Status> blockEntityTickAdapters();
List<AuditedBlockEntityTickAdapter.Status> auditedBlockEntityTicks();
List<BlockEntityAdapters.Status> blockEntityAdapters();
Optional<BlockEntityAdapters.PhaseSummary> lastBlockEntityAdapterPhase();
BlockEntityOperations blockEntities();
List<ScheduledTickStatus> scheduledTicks();
```

| Method | Meaning | Unit / default | Common misuse |
|---|---|---|---|
| `apiMajorVersion()` | Facade major | `1` | Treating it as a build number |
| `capabilities()` | Protocols implemented here | `Set` | Treating it as "enabled" |
| `regionThreadingInstalled()` | Is an engine installed now | boolean | Assuming `false` disables `ParallelApi` |
| `currentPhase()` | Calling thread's execution phase | enum | Using `QUIESCENT_SERVER` as a mutation licence |
| `adapters()` | Natural-spawn preparation registrants and cumulative results | counts | Reading counts as entities spawned |
| `blockEntityTickAdapters()` | v1 predicate adapters: checks, refusals, native completions, timings | counts/nanos | Equating `checks` with completions |
| `auditedBlockEntityTicks()` | Audited adapters: reference/revision, `verified`, reason | records | Treating `verified` as runtime proof |
| `blockEntityAdapters()` | C5 conservative request diagnostics | records | Expecting C5 to enable workers |
| `lastBlockEntityAdapterPhase()` | Last closed C5 session, declaration-only waves | `Optional` | Reading it as an execution plan |
| `blockEntities()` | Two-owner operation service | service | Assuming cross-region parallelism |
| `scheduledTicks()` | Block/fluid scheduled phase snapshot | list | Inferring slice state from emptiness |

All list/optional methods have conservative defaults (`List.of()` / `Optional.empty()`), so an older core
does not throw `AbstractMethodError`.

## 5. Records and enums

### 5.1 `DataKey<S>`

```java
record DataKey<S>(ResourceLocation id, Class<S> snapshotType) {}
```

| Component | Constraint | Why |
|---|---|---|
| `id` | non-null, namespaced, unique per server | identity of the adapter's data slot |
| `snapshotType` | non-null reference type (no primitives) | runtime type check when publishing |

### 5.2 `SpawnUnit` / `SpawnPlan`

```java
record SpawnUnit(ResourceKey<Level> dimension, int chunkX, int chunkZ, long gameTime) {}
record SpawnPlan(ResourceKey<Level> dimension, long gameTime) {}
```

| Field | Meaning | Not to be used as |
|---|---|---|
| `dimension` | level of the spawn call | a chunk-load handle |
| `chunkX`/`chunkZ` | **source** chunk of the complete spawn call | a bound on candidate positions |
| `gameTime` | logical game time of the plan/unit | a wall-clock deadline |

### 5.3 `Preparation<S>` hierarchy

```java
sealed interface Preparation<S> permits Ready, ServerRequired, PerUnit {}
record PerUnit<S>() implements Preparation<S> {}
record Ready<S>(S snapshot, boolean orderedFinalization) implements Preparation<S> { Ready(S snapshot); }
record ServerRequired<S>(ResourceLocation reason) implements Preparation<S> {}
```

| Result | Returned from | Effect | Allowed later? |
|---|---|---|---|
| `PerUnit` | `preparePlan` only | Collect chunks, then call `prepare` per unit | Default plan behaviour |
| `Ready(snapshot)` | `prepare` or `preparePlan` | Freeze data for that scope | Plan-wide data also feeds serial fallback bodies |
| `Ready(snapshot, true)` | `preparePlan` only | Request ordered finalization | Needs `ORDERED_SPAWN_FINALIZATION` |
| `ServerRequired(reason)` | `prepare` or `preparePlan` | Keep the complete serial plan/unit | Reason must be a bounded namespaced label |

Validation rules (all throw or fail registration): `snapshot != null`, `reason != null`, `orderedFinalization`
only from `preparePlan`, and a per-unit `prepare` must not ask for ordered finalization.

### 5.4 `AdapterStatus`

```java
record AdapterStatus(ResourceLocation id, long preparedUnits, long serialUnits, long serialPlans,
                     long preparationNanos, Optional<ResourceLocation> lastSerialReason) {}
```

| Field | Meaning | Interpret as |
|---|---|---|
| `id` | adapter data key id | join key with your own logs |
| `preparedUnits` | units prepared for a worker | attempts, not entities |
| `serialUnits` | units kept serial | fallback volume |
| `serialPlans` | whole plans refused before cap construction | strongest fallback signal |
| `preparationNanos` | cumulative preparation wall time | per-window deltas only |
| `lastSerialReason` | most recent namespaced reason | diagnose without per-position labels |

Use differences between two snapshots for rates. Do not average cumulative totals across windows.

## 6. `Registrar` registration methods

```java
Set<Capability> capabilities();
<S> void register(int apiMajorVersion, DataKey<S> key, NaturalSpawnPreparation<S> preparation);
void claimNaturalSpawning(DataKey<?> key, String modId, BooleanSupplier verified);
void registerBlockEntityTicks(int apiMajorVersion, ResourceLocation id,
        Set<ResourceLocation> types, BlockEntityTickAdapter adapter);
void registerBlockEntityExecutionAdapter(int protocolVersion, ResourceLocation id,
        Set<ResourceLocation> types, BlockEntityExecutionAdapter adapter);
BlockEntityAdapters.Registration registerBlockEntityAdapter(int protocolVersion,
        BlockEntityAdapters.Descriptor descriptor, BlockEntityAdapter adapter);
```

| Method | Required arguments | Transaction rules | Grants |
|---|---|---|---|
| `register` | major `1`, unique key, preparation | ≤128 registrations total; duplicate id aborts all | A data slot + preparation callbacks |
| `claimNaturalSpawning` | already-registered key, non-blank `modId`, `verified` true at startup | No duplicate claim; missing key fails | Replaces *that mod's* spawn restriction only |
| `registerBlockEntityTicks` | major `1`, unique id, 1..128 types, adapter | Shares the 128 budget/id space; overlapping types rejected | Per-invocation admission checks ([ticks](block-entity-ticks.en.md)) |
| `registerBlockEntityExecutionAdapter` | protocol `1`, unique id/types, complete audited execution adapter | Shared budget and id/type conflict rules | Original region/resource execution ([protocol](block-entity-execution.en.md)) |
| `registerBlockEntityAdapter` | protocol `1`, descriptor, adapter | Same budget; C5 id/type space | Conservative C5 request diagnostics, serial fences |

Registration is only valid inside `RegisterParallelAdaptersEvent`, on the server thread. A caught nested
failure still invalidates the transaction — you cannot "swallow" a bad registration.

## 7. Examples

### 7.1 Capability negotiation + reading a snapshot

```java
static final ParallelApi.DataKey<Rules> RULES =
        new ParallelApi.DataKey<>(ResourceLocation.parse("example:rules"), Rules.class);

@SubscribeEvent
public static void register(RegisterParallelAdaptersEvent event) {
    var registrar = event.registrar();
    if (!registrar.capabilities().contains(ParallelApi.Capability.NATURAL_SPAWN_PREPARATION)) return;
    registrar.register(ParallelApi.MAJOR_VERSION, RULES, unit -> new ParallelApi.Ready<>(Rules.frozen()));
}

// inside a listener that runs during the matching natural-spawn body
ParallelApi.prepared(RULES).ifPresent(rules -> {
    // read only; do not cancel other listeners because a snapshot exists
});
```

### 7.2 Plan-wide snapshot with ordered finalization, and an honest fallback

```java
@Override
public Preparation<Rules> preparePlan(SpawnPlan plan) {
    if (!worldSupportsPlanRules(plan.dimension())) {
        return new ServerRequired<>(ResourceLocation.parse("example:world_dependent_rules"));
    }
    return new Ready<>(Rules.frozenFor(plan), true); // ordered finalization requested
}

@Override
public Preparation<Rules> prepare(SpawnUnit unit) {
    // Called per unit only when preparePlan returned PerUnit; keep it pure.
    return Rules.covers(unit) ? new Ready<>(Rules.frozenFor(unit))
                              : new ServerRequired<>(ResourceLocation.parse("example:structure_unavailable"));
}
```

### 7.3 Diagnostics without retaining live objects

```java
ParallelApi.forServer(server).ifPresent(api -> {
    long nativeDone = api.blockEntityTickAdapters().stream()
            .mapToLong(BlockEntityTickAdapter.Status::workerCompletions).sum();
    api.adapters().forEach(status -> LOG.info("{} prepared={} serialPlans={} lastReason={}",
            status.id(), status.preparedUnits(), status.serialPlans(), status.lastSerialReason()));
});
```

## 8. Comparison: per-unit vs plan-wide vs ordered finalization

| Property | Default `PerUnit` | Plan-wide `Ready` | Plan-wide `Ready(_, true)` |
|---|---|---|---|
| Preparation granularity | Per source chunk | Once per plan | Once per plan |
| Memory | One snapshot per unit | One shared snapshot | One shared snapshot |
| Serial-fallback data | Discarded per unit | Retained for the plan | Retained for the plan |
| Worker selection | Yes | Yes | Yes |
| Native finalization location | Worker | Worker | Server, peers parked |
| Ordering guarantee | None vs vanilla | None vs vanilla | Finalization order preserved per unit batch; RNG not bit-identical |
| Capability needed | `NATURAL_SPAWN_PREPARATION` | same | + `ORDERED_SPAWN_FINALIZATION` |
| Best for | Per-chunk rule data | Uniform pack rules | Mods whose finalize callback mutates shared state |

## 9. Failure modes

| Failure | Reported as | Never happens |
|---|---|---|
| Null component in a record | `NullPointerException` / `IllegalArgumentException` at construction | Silently accepted |
| Wrong major version | Registration failure | Partial publish |
| Duplicate id or type | Registration failure (whole transaction) | Merge of two adapters |
| >128 registrations | Registration failure | Eviction of an older adapter |
| `prepare` throws | Failure propagates through the spawn plan | Treated as success or retried |
| Snapshot mutated later | Undefined behaviour you caused | Runtime mutation detection |
| `prepared` read outside the body | `Optional.empty()` | A cached or leaked snapshot |

## 10. Checklist

- [ ] Capability checked in the event; serial path kept.
- [ ] `DataKey` id namespaced and unique; snapshot type is a reference type.
- [ ] Preparation has no RNG, events, entity construction or world writes.
- [ ] Snapshot is deeply immutable and detached.
- [ ] `ServerRequired` used for every unsupported configuration, with a bounded reason.
- [ ] `orderedFinalization` requested only from `preparePlan`.
- [ ] Diagnostics read via `Server`, never by retaining entities/chunks.
