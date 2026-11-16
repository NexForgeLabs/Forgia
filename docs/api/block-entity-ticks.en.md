# Block-entity tick admission (v1 predicate + audited original ticker)

English | [中文](block-entity-ticks.zh.md) · [API index](README.en.md)
Source: [`BlockEntityTickAdapter.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityTickAdapter.java), [`AuditedBlockEntityTickAdapter.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/AuditedBlockEntityTickAdapter.java)
Evidence: [AUDITED-BLOCK-ENTITY-TICK-API](../../region-threading/AUDITED-BLOCK-ENTITY-TICK-API.md), [BLOCK-ENTITY-PARALLELISM](../../region-threading/BLOCK-ENTITY-PARALLELISM.md)

Status: **Experimental**, capability `BLOCK_ENTITY_TICK_ADMISSION` (v1) and `AUDITED_BLOCK_ENTITY_TICKS` (audited).
Registration never enables the slice; `enableRegionThreadingBlockEntityTicks` must be on.

---

## 1. When to use which contract

| Your situation | Contract | Because |
|---|---|---|
| You can prove the *whole* original ticker body is region-local, including inherited ticks, behaviours, lazy ticks and third-party transforms | **Audited** (`AuditedBlockEntityTickAdapter`) | The audited contract carries the manifest and READ/WRITE footprint Core needs for the canonical path |
| You only need a cheap per-invocation refusal gate and accept that the canonical path may still keep you serial | **v1** (`BlockEntityTickAdapter`) | v1 registration is binary compatible but does not gain the canonical execution contract |
| The machine reads or writes a foreign container / shared network | **Neither** | Unknown or shared resources must keep the complete serial ticker |
| You cannot enumerate every branch / callback | **Neither** | Return a refusal; do not guess |
| You want cross-region effects, not parallel ticks | [Block-entity operations](block-entity-operations.en.md) | Different protocol: at-most-once operations, not ownership |

## 2. Why the tick contract is per-invocation, not a type allowlist

A type-level allowlist says "this class is usually fine". That is unsound: the same class can be
initializing, mid-topology-change, running a callback, or holding a shared network. Conversely, a
per-invocation check cannot be cached, because the state changes every tick.

So the engine asks, immediately before the original ticker and *under region ownership*: "is this exact
invocation's whole body closed?" Admission is never cached. Only the type eligibility, the interior chunk rule
and the whole-chunk fallback for undeclared types are cached.

## 3. Registration

```java
registrar.registerBlockEntityTicks(
        ParallelApi.MAJOR_VERSION,                       // 1
        ResourceLocation.parse("example:steady_machine"), // unique id
        Set.of(ResourceLocation.parse("example:machine")),// 1..128 types
        adapter);                                          // v1 or audited
```

| Argument | Constraint | Notes |
|---|---|---|
| `apiMajorVersion` | `1` | mismatch aborts the registration |
| `id` | unique per server, namespaced | shared id space with spawn adapters |
| `types` | non-empty, ≤128, disjoint from all other BE registrations | overlapping type sets are rejected |
| `adapter` | instance implementing v1 or audited contract | one instance per id |
| Budget | ≤128 registrations total | shared with natural-spawn preparation |

Server-only declarations (`entities`/`block_entities`) and the compatibility/conflict policy still take
precedence. Registering does not lift `DISABLE_ENGINE` or turn on `enableRegionThreadingBlockEntityTicks`.

## 4. v1 contract — `BlockEntityTickAdapter`

```java
@FunctionalInterface
public interface BlockEntityTickAdapter {
    default void prepare(ServerLevel level) {}
    Decision check(BlockEntity blockEntity);

    enum Decision { ADMITTED, INITIALIZING, TOPOLOGY_CHANGE, CALLBACK_REQUIRED, UNKNOWN_CLASS,
                    UNKNOWN_BEHAVIOUR, SHARED_RESOURCE, INVALID_INSTANCE, UNVERIFIED_TRANSFORMATIONS }

    record Status(ResourceLocation id, Set<ResourceLocation> types, long checks, long refusals,
                  long workerCompletions, long preparationNanos, long checkNanos,
                  Map<Decision, Long> reasons) {}
}
```

| Method | Thread / frequency | Allowed work | Required result |
|---|---|---|---|
| `prepare(ServerLevel)` | Quiescent server, once per non-empty level batch | Read already-loaded resources only; publish detached immutable metadata | Bounded preflight; no chunk load, no mutation |
| `check(BlockEntity)` | Region worker, immediately before each original ticker | Read only the supplied instance's owned fields + prepared metadata | `ADMITTED` only if the whole body is closed |

| `Decision` | Meaning | Typical cause |
|---|---|---|
| `ADMITTED` | Whole tick body is region-local this time | Steady state, local inventory only |
| `INITIALIZING` | Object is still constructing/hydrating | First tick after load/placement |
| `TOPOLOGY_CHANGE` | Network/neighbour topology is being rewritten | Redstone switch, network rebuild |
| `CALLBACK_REQUIRED` | Branch needs a server-side callback/listener | Third-party hook, event dispatch |
| `UNKNOWN_CLASS` | Ticker/behaviour class not covered by your audit | Mod added a subclass/behaviour |
| `UNKNOWN_BEHAVIOUR` | Unknown behaviour/lazy-tick path | Dynamic registration |
| `SHARED_RESOURCE` | Reads/writes a foreign or shared root | Neighbour container, power network |
| `INVALID_INSTANCE` | Removed, replaced or wrong level/position | Race with removal |
| `UNVERIFIED_TRANSFORMATIONS` | Mixin/coremod set differs from the audited set | Pack added a transformation |

Everything except `ADMITTED` keeps the **complete original tick** and runs it once on the server after
workers return. A refusal must happen before any effect; an exception is a failure, never permission.

## 5. Audited contract — `AuditedBlockEntityTickAdapter`

```java
public interface AuditedBlockEntityTickAdapter extends BlockEntityTickAdapter {
    Audit audit();
    default void prepareCanonical(ServerLevel level) { prepare(level); }
    @Nullable Proof inspect(BlockEntity source, Class<?> tickerImplementation);

    record Implementation(Class<?> type, String sha256, Set<String> allowedMixins) {}
    record Audit(String reference, String revision, Set<Class<?>> sourceClasses,
                 Set<Class<?>> tickerClasses, List<Implementation> implementations) {}
    record Status(ResourceLocation id, String reference, String revision, boolean verified, String reason) {}
    enum Access { READ, WRITE }
    record Storage(Object identity, Access access) {}
    record Proof(List<Storage> storage) {}
}
```

| Method | Thread / lifetime | Required result |
|---|---|---|
| `audit()` | Registration server thread, once | Frozen manifest: bounded reference/revision, exact source/function classes, implementation hashes and allowed Mixins |
| `prepareCanonical(level)` | Quiescent server in the original published phase | Bounded read-only preparation; no async scheduling, chunk load or stale success cache |
| `inspect(source, tickerImplementation)` | Server cursor after batch publication, then held-region prefix checks (may run on a worker) | `null` if the branch is not closed; otherwise the complete current READ/WRITE footprint |
| `check(source)` | Region worker, immediately before a body | v1 refusal gate; Core revalidates binding and footprint afterwards |

### 5.1 Manifest validation (enforced at construction)

| Component | Rule |
|---|---|
| `reference` | non-blank, ≤512 chars |
| `revision` | non-blank, ≤128 chars |
| `sourceClasses` | non-empty, ≤128, must include exact source ancestry |
| `tickerClasses` | non-empty, ≤128, must include exact function ancestry |
| `implementations` | non-empty, ≤256, one per reached implementation |
| `Implementation.type` | non-null |
| `Implementation.sha256` | lowercase hex, exactly 64 chars |
| `Implementation.allowedMixins` | ≤64 entries, each non-blank, ≤256 chars |
| `Proof.storage` | non-empty, ≤64 entries; identity non-null; access non-null |

Missing metadata, changed bytes or unknown transforms leave the target serial. Function classes with mutable
instance storage are rejected by this first contract.

### 5.2 Footprint rules

| Rule | Reason |
|---|---|
| Include the exact `source` as `WRITE` | The body mutates its own block entity |
| Every mutable backing root reached by the branch must be listed | A wrapper around a shared root must name the real root |
| Shared read-only roots use `READ` | Allows parallel reads |
| Identities compare by reference; `equals`/`hashCode` are never called | Mod objects cannot forge ownership by equality |
| READ/WRITE overlap splits prefix waves, even in the same region | An earlier write could invalidate a later branch |
| READ/READ may stay parallel | No conflict |
| Whole-world/server/chunk claims, entity roots, foreign BE roots | Not authorized for a region-local body |
| A fresh inspection must return the same roots and modes | Changed identities refuse the unstarted prefix |

## 6. Execution sequence

| Stage | Thread | What happens |
|---|---|---|
| 1 | Server | Original canonical phase builds the BE batch; `prepare`/`prepareCanonical` runs |
| 2 | Server | Original wrapper/delegate/source/function/chunk/frame captured; registration identity retained |
| 3 | Workers | Batch published, mailbox delivered, candidates inspected, footprint conflicts separated into waves |
| 4 | Worker | Per-invocation `check` + fresh revalidation under held region lease |
| 5 | Worker | The **original** complete ticker runs once |
| 6 | Workers | Physical join; deferred publications/removals committed |
| 7 | Server | Any unknown entry runs as a complete serial fence; its suffix is re-inspected afterwards |
| 8 | Server | Started-body failure is reported; the tick is never replayed |

The bridge creates no independent authority: it reuses the existing Native prefix, `InvocationTicket`,
region guard/permit, leases and task pool. No adapter receives the engine, ticket, region state or lease
constructor.

## 7. Examples

### 7.1 v1 predicate adapter (minimal)

```java
public final class SteadyGate implements BlockEntityTickAdapter {
    @Override
    public void prepare(ServerLevel level) {
        // read-only preflight for this batch; publish immutable metadata only
    }

    @Override
    public Decision check(BlockEntity be) {
        if (!(be instanceof MyMachine machine)) return Decision.UNKNOWN_CLASS;
        if (machine.isInitializing()) return Decision.INITIALIZING;
        if (machine.networkDirty()) return Decision.TOPOLOGY_CHANGE;
        return machine.usesLocalStorageOnly() ? Decision.ADMITTED : Decision.SHARED_RESOURCE;
    }
}
```

### 7.2 Audited adapter skeleton

```java
public final class SteadyTicks implements AuditedBlockEntityTickAdapter {
    private static final Set<Class<?>> SOURCES = Set.of(MyMachineBlockEntity.class);
    private static final Set<Class<?>> TICKERS = Set.of(MyMachineTicker.class);

    @Override
    public Audit audit() {
        return new Audit("docs/steady-machine-audit", "3",
                SOURCES, TICKERS,
                List.of(new Implementation(MyMachineTicker.class, sha256Of(MyMachineTicker.class),
                        Set.of("mymod.mixin.MachineTickerMixin"))));
    }

    @Override
    public void prepareCanonical(ServerLevel level) {
        // bounded, read-only; never retain the level
    }

    @Override
    public @Nullable Proof inspect(BlockEntity source, Class<?> tickerImplementation) {
        if (!(source instanceof MyMachineBlockEntity machine)) return null;
        if (!TICKERS.contains(tickerImplementation)) return null;
        if (!machine.branchIsAuditedAndSteady()) return null;

        return new Proof(List.of(
                new Storage(machine, Access.WRITE),            // the source itself
                new Storage(machine.energyBuffer(), Access.WRITE),
                new Storage(machine.localRecipeCache(), Access.READ)));
    }

    @Override
    public Decision check(BlockEntity be) {
        return be instanceof MyMachineBlockEntity m && m.isSteady()
                ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
    }
}
```

Register it exactly like the v1 adapter; negotiate `AUDITED_BLOCK_ENTITY_TICKS` first.

## 8. Comparison

| Property | v1 predicate | Audited original ticker |
|---|---|---|
| Registration API | `registerBlockEntityTicks` | same method, audited instance |
| Capability | `BLOCK_ENTITY_TICK_ADMISSION` | `AUDITED_BLOCK_ENTITY_TICKS` (+ v1) |
| Manifest | none | exact source/function classes + implementation SHA-256 + allowed Mixins |
| Footprint | not declared | up to 64 READ/WRITE backing roots |
| Core checks | instance/type/position/level/bound ticker + predicate | all of v1 **plus** manifest integrity, binding, fresh footprint, alias/conflict waves |
| Canonical Level path | predicate alone does not grant it | yes |
| Unknown branch | serial | serial (`null` proof) |
| Best for | cheap refusal gate / legacy adapters | audited steady-state machines |
| Effort | low | high (must enumerate every branch and root) |

## 9. Limits that are not negotiable

| Limit | Effect |
|---|---|
| Large canonical phase | Current core plans windows of at most 2048 candidates; whole-phase resource/update budgets remain |
| Interior chunk rule | A BE whose chunk neighbours are outside the lease stays serial unless its protocol allows boundary admission |
| Unknown class/subclass/function | Serial; exact class prevents inheritance of the contract |
| Unknown behaviour/lazy tick added dynamically | Refused before the next invocation |
| Function class with mutable instance storage | Rejected by the audited contract |
| Hash match | Proves implementation bytes, **not** semantic safety |
| Adapter trust | Installing an adapter trusts its audit; this is not a JVM sandbox |

## 10. Failure semantics

| Failure | Result | Never |
|---|---|---|
| `check`/`inspect` throws | Reported as failure | Converted to permission or a "successful refusal" |
| Pre-body refusal | Complete original tick runs later on the server | Partial body |
| Body throws after start | Reported after join/cleanup | Replayed whole ticker |
| Fresh inspection differs | Unstarted prefix refused | Body started with stale proof |
| Shutdown/unload during batch | Original physical completion boundary kept | Orphaned lease |
| Second registration with equal descriptor | Rejected/ignored as a new candidate | Inherits the old identity |

## 11. Diagnostics

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

```text
Audited original-tick adapter forgia_create:steady_ticks:
  implementationsVerified=true, reason=VERIFIED_IMPLEMENTATIONS, audit=..., revision=...
Block entity tick adapter forgia_create:steady_ticks:
  checks=..., refused=..., nativeCompleted=..., prepareMs=..., checkMs=..., reasons={...}
```

| Field | Meaning | Not |
|---|---|---|
| `implementationsVerified` | Frozen manifest integrity at registration | Current branch safety or completion |
| `checks` / `refusals` | Invocation counts | Entity counts |
| `nativeCompleted` | Bodies that returned successfully on a worker | Submissions, fallbacks or class lists |
| `prepareMs` / `checkMs` | Cumulative wall time | Per-tick cost |

## 12. Checklist

- [ ] Capability negotiated; slice switch understood; serial path preserved.
- [ ] Type ids disjoint, ≤128 per adapter, unique adapter id.
- [ ] `prepare`/`prepareCanonical` read-only, no load/schedule/wait.
- [ ] `check`/`inspect` reads only owned fields and immutable metadata.
- [ ] Every inherited tick, behaviour, lazy tick, callback and RNG branch audited.
- [ ] `Proof` includes the exact source as WRITE and every real backing root.
- [ ] Refusals return a bounded `Decision`; no per-position labels.
- [ ] Started bodies never replayed; failures reported.
- [ ] Verified on a real pack with the switch off (serial equivalence) and on (overlap).
