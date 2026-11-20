# Natural-spawn preparation, claims and ordered finalization

English | [中文](natural-spawn-preparation.zh.md) · [API index](README.en.md)
Source: [`ParallelApi.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/ParallelApi.java), [`ParallelAdapterRuntime.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/ParallelAdapterRuntime.java)
Evidence: [MOD-PARALLEL-API](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOD-PARALLEL-API.md), [NEARBY-SPAWN-ADMISSION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/NEARBY-SPAWN-ADMISSION.md)

Status: **Experimental.** Natural spawning has its own config switch (`enableRegionThreadingSpawning`), which is
**off by default and outside the accepted C6 profile**. Registration never enables it.

---

## 1. When to use / when not to

| Use it when | Why |
|---|---|
| Your mod's spawn listener only needs immutable rule data (difficulty, biome tags, pack config) | The body can read a snapshot instead of querying live objects on a worker |
| You want one frozen rule revision for a whole spawn plan | `preparePlan` returning plan-wide `Ready` |
| Your finalization mutates shared state or must see real add/cancel outcomes | Request `orderedFinalization` and let the server run the native continuation |
| Vanilla's global mob cap is the only quota you touch | A claim can take over *your mod's* spawn restriction only |

| Do not use it when | Because |
|---|---|
| You need structure lookups or chunk loading during preparation | No structure snapshot/cache service exists; loading is forbidden |
| You want `spawn_multiplier` or structure filters parallelised | That protocol is not implemented (see the MSC adapter) |
| You want certification that a third-party listener is safe | Registration is an author contract, not an automated proof |
| The engine/slice is off | Registration still happens, but there is no worker path to feed |

## 2. Why the protocol is split into plan + unit

Vanilla decides caps and state before iterating chunks. If preparation ran per chunk only, two chunks could
see different rule revisions in the same plan, and the cap decision would be inconsistent. Splitting gives:

* `preparePlan` — one decision before cap/state construction: `PerUnit`, plan-wide `Ready`, or `ServerRequired`.
* `prepare` — per source chunk, only reached in `PerUnit` mode, for data that genuinely varies per chunk.

A plan-wide `Ready` also supplies serial-fallback bodies of the *same* plan, so a refused chunk still runs
with the same rules the workers would have used.

## 3. Registration rules

```java
public final class RegisterParallelAdaptersEvent extends ServerLifecycleEvent {
    public ParallelApi.Registrar registrar();
    public MinecraftServer getServer();
}
```

Fired on `NeoForge.EVENT_BUS` after biome modifiers and `ServerAboutToStartEvent`, before world ticks — also on
servers without a region engine.

| Rule | Value / consequence |
|---|---|
| Valid scope | Inside the event, server thread, before freeze. A retained registrar fails afterwards |
| Total registrations | ≤ 128, shared with block-entity adapters, unique ids |
| Ordering | Callbacks run sorted by `id`, not by subscriber order |
| Version | `apiMajorVersion` must equal `ParallelApi.MAJOR_VERSION` |
| Atomicity | Duplicate id/type, version mismatch or overflow fails the whole registration; nothing is published |
| Nested failure | A caught exception does not whitelist the failed registration |
| Effect on engine | None. Registration opens no slice and lifts no compatibility refusal |

## 4. Parameters you actually set

| Parameter | Type | Constraint | Consequence |
|---|---|---|---|
| `apiMajorVersion` | `int` | must be `1` | mismatch aborts registration |
| `key` | `DataKey<S>` | non-null id + reference `snapshotType` | data slot identity and runtime type check |
| `preparation` | `NaturalSpawnPreparation<S>` | `prepare` is abstract, `preparePlan` defaults to `PerUnit` | the two decision points |
| `snapshot` | `S` | deeply immutable, detached | what the body may read |
| `orderedFinalization` | `boolean` | only from `preparePlan` | moves native finalization to the server |
| `reason` | `ResourceLocation` | namespaced, bounded | fallback diagnosis; never per-coordinate |
| `modId` (claim) | `String` | non-blank, must match a registered key's mod restriction | scope of the claim |
| `verified` (claim) | `BooleanSupplier` | must return true at startup | abort registration otherwise |

## 5. Purity rules for preparation

| Allowed | Forbidden | Why |
|---|---|---|
| Read your own immutable config/codec data | Consume `Level.random` or any game RNG | Worker bodies must be order-independent |
| Precompute lookup tables | Fire events, construct entities | Preparation runs inside a live plan |
| Copy primitive/immutable collections | Keep `Entity`, `LevelChunk`, mutable NBT, `IItemHandler` | Runtime type checks cannot prove immutability |
| Publish a `record` of primitives/immutable values | Share a mutable collection or RNG between units | Later mutation is invisible to the core |
| Do bounded work | Block, sleep, schedule async work | Preparation is not preempted and has no hard budget |

Exceptions, `null` returns and wrong snapshot types propagate as failures. They are never converted into a
serial success or an automatic retry.

## 6. Ordered finalization — what actually happens

| Step | Thread | What runs |
|---|---|---|
| 1 | Region worker | Native candidate selection loop, reading the frozen snapshot |
| 2 | Region worker | A cooperative stop point is set; all participating spawn workers park |
| 3 | Server thread | Deferred effects published before rule evaluation |
| 4 | Server thread | Complete native `finalizeSpawn`, passenger admission, `afterSpawn`, cluster/group-limit callbacks |
| 5 | Server thread | Real add/cancel outcome visible to the next candidate |
| 6 | Workers | Resume with the same rule revision, unit context and RNG stream (carried, prepared once) |

| Guaranteed | Not guaranteed |
|---|---|
| No two candidates can both take the last nearby-spawn slot | Vanilla global candidate order / RNG sequence bit equality |
| Cancelled spawns still consume their RNG like vanilla | Zero cost: parking, publishing and finalization are real work |
| A started body is never replayed | That a failed body can be retried |
| Unsupported configuration is refused **before** effects | That every third-party listener is safe |

A group rejected by the pool executes on its submitting thread through the same barrier; exceptions,
shutdown and interruption never replay an operation that already produced effects. Cross-group transactions
execute in submission order.

## 7. Claims — scope and limits

```java
registrar.claimNaturalSpawning(RULES, "example", () -> verifyInstalledVersion("1.2.3"));
```

| Claim replaces | Claim does not replace |
|---|---|
| That mod's `DISABLE_SPAWNING` compatibility action | `DISABLE_ENGINE` (e.g. c2me) |
| The default "leave this mod serial" decision | An administrator-disabled slice or switch |
| Nothing else | Another mod's restriction or event listener |

The claim is the adapter author's explicit contract. It must reference an already registered key, must not be
duplicated, and `verified` must succeed at startup. Unknown native injections should still fall back.

## 8. Examples

### 8.1 Per-chunk filter data (default protocol)

```java
static final ParallelApi.DataKey<RuleSet> RULES =
        new ParallelApi.DataKey<>(ResourceLocation.parse("example:spawn_rules"), RuleSet.class);

@SubscribeEvent
public static void onRegister(RegisterParallelAdaptersEvent event) {
    event.registrar().register(ParallelApi.MAJOR_VERSION, RULES,
            unit -> new ParallelApi.Ready<>(RuleSet.forChunk(unit.dimension(), unit.chunkX(), unit.chunkZ())));
}

// in the spawn listener that runs in the body
ParallelApi.prepared(RULES).ifPresent(rules -> applyFilter(rules));
```

### 8.2 Plan-wide rules with ordered finalization

```java
@Override
public Preparation<RuleSet> preparePlan(SpawnPlan plan) {
    if (!packConfigIsUniform(plan.dimension())) {
        return new ServerRequired<>(ResourceLocation.parse("example:world_dependent_rules"));
    }
    if (!registrarCapabilities.contains(ParallelApi.Capability.ORDERED_SPAWN_FINALIZATION)) {
        return new Ready<>(RuleSet.forPlan(plan)); // plain plan-wide snapshot
    }
    return new Ready<>(RuleSet.forPlan(plan), true);
}

@Override
public Preparation<RuleSet> prepare(SpawnUnit unit) {
    return new ServerRequired<>(ResourceLocation.parse("example:per_unit_disabled"));
}
```

## 9. Scoping and lifecycle

| Fact | Consequence |
|---|---|
| `prepared(key)` is thread-scoped to the current body | Do not hand it to a thread you started |
| Scope covers the whole `spawnForChunk` call | Presence does not mean a filter ran or a mob will be added |
| A serial fallback hides outer scopes but keeps plan-wide data | One rule revision per plan |
| Units are checked against server, dimension and gameTime | Stale units are skipped, never queued across ticks |
| A stopped service invalidates old units | Same JVM restart creates an independent service |
| Public snapshot values may be stored as pure data | Storing does not extend ownership or freshness |

## 10. Comparison of the three execution modes

| Property | Per-unit, worker finalization (default) | Plan-wide, worker finalization | Plan-wide, ordered finalization |
|---|---|---|---|
| Capability | `NATURAL_SPAWN_PREPARATION` | + `NATURAL_SPAWN_PLAN_PREPARATION` | + `ORDERED_SPAWN_FINALIZATION` |
| Rule revision | Per chunk | Per plan | Per plan |
| Candidate selection | Worker | Worker | Worker |
| Native finalization | Worker | Worker | Server, peers parked |
| Shared-slot safety | Vanilla per-level cap still applies | same | Explicitly ordered |
| Serial fallback data | Discarded | Retained | Retained |
| Cost profile | Lowest coordination | Low | Parking + publication + server continuation |
| Use when | Data varies by chunk | Uniform pack rules | Finalize mutates shared state / outcomes must be visible |

## 11. Diagnostics and failure

| Signal | Meaning | Action |
|---|---|---|
| `preparedUnits` grows, `serialUnits` grows | Preparation succeeds, admission still refused | Read `lastSerialReason` |
| `serialPlans` grows | Plan refused before cap construction | Fix the unsupported configuration, do not widen snapshots |
| Worker spawn ratio zero with spawning on | Slice off, compatibility refusal, or every plan serial | Check `compat`, engine switch, conflicts |
| `prepared` empty in your listener | Wrong thread/scope or native path | Read only inside the body |
| Registration throws | Duplicate id/type, version, limit, or claim misuse | Fix the manifest; do not catch and continue |

| Never | Why |
|---|---|
| Retry a partially applied spawn body | Effects are not rolled back |
| Use `prepared` as an event-cancellation flag | Presence proves nothing about other listeners |
| Build a reason string per coordinate | Unbounded labels; use a namespace |
| Assume ordered mode is faster | Parking and publication cost real time |

## 12. Checklist

- [ ] `preparePlan` handles every unsupported configuration with `ServerRequired`.
- [ ] Snapshot is deeply immutable and contains no live object.
- [ ] Only `preparePlan` returns `Ready(..., true)`.
- [ ] Claim references a registered key and a verified version.
- [ ] Reasons are namespaced and bounded.
- [ ] Behaviour validated with the spawning switch **off** first.
