# Forgia API — entry, lifecycle and versioning

English | [中文](README.zh.md) · Parent: [docs](../README.en.md) · Full inventory: [API-REFERENCE](../../region-threading/API-REFERENCE.md)

Status: **Experimental, API major 1.** Public types are not a frozen ABI. Negotiate by capability.

This page answers the questions every integration asks first: *which entry do I use, when is it valid, what
does it actually grant, and why is the answer "not ownership"?* Detailed parameter tables live in the
per-contract pages linked from the matrix below.

---

## 1. When to use which entry

| Need | Get it from | Main type | Without a region engine |
|---|---|---|---|
| Version, capability, phase, adapter diagnostics | `ParallelApi.forServer(server)` | `ParallelApi.Server` | Service still exists after registration |
| Prepare natural-spawn data / claim a spawn restriction | `RegisterParallelAdaptersEvent.registrar()` | `ParallelApi.Registrar` | Event fires; native serial path does not run your preparation |
| Read your snapshot inside the spawn body | `ParallelApi.prepared(key)` | `DataKey<S>`, `SpawnUnit` | Usually empty |
| Admit an audited original BE ticker | same registrar | `BlockEntityTickAdapter`, `AuditedBlockEntityTickAdapter` | Native serial; registration never enables a slice |
| Whole two-owner BE operation / bounded container transfer | `api.blockEntities()` | `BlockEntityOperations` | Executes immediately when the server is quiescent; other contexts rejected |
| Scheduled-tick counters and latency | `api.scheduledTicks()` | `ScheduledTickStatus` | Empty list |
| Run a task on a position's region | `RegionThreadingManager.get()` → `regionScheduler()` | `RegionScheduler` | Manager empty; no replacement executor |
| Task follows an entity's owner | same → `entityScheduler()` | `EntityRegionScheduler` | same |
| Global-region task | same → `globalScheduler()` | `GlobalRegionScheduler` | same |
| Assert/query ownership | same | `RegionThreading`, `RegionThreadingContext` | Mod picks its own serial branch |
| Whole entity interaction / owner-after-load | same → `entityInteractions()` | `EntityInteractionScheduler` | No engine entry (unlike `ParallelApi`, which always registers) |
| Declare mod compatibility | mod metadata `regionThreading` | `RegionThreadingSupport` | Declaration only |

**Why this split exists.** `ParallelApi` is a server-scoped service that always exists; the schedulers belong
to an installed engine. Mixing them would make "the engine is off" indistinguishable from "the API is
missing", and mods would negotiate the wrong thing.

## 2. Compile surface

| Artifact | Contains | Use when |
|---|---|---|
| `:neoforge:parallelApiJar` (`*-parallel-api.jar`) | `net.neoforged.neoforge.server.threading.api` + `RegisterParallelAdaptersEvent` | You only call `ParallelApi`/registrar/operations |
| Full Forgia compile dependency | The facade **plus** `RegionThreadingManager`, the three schedulers, `EntityInteractionScheduler`, config | You schedule tasks or read `ForgiaConfig` |

```groovy
dependencies {
    // illustrative local file; no frozen Maven coordinate exists
    compileOnly files('libs/forgia-parallel-api.jar')
}
```

| Rule | Why |
|---|---|
| `compileOnly`, never shade/relocate/jar-in-jar | Two copies of the API classes break `instanceof`, capability negotiation and record equality |
| Guard optional entrypoints for stock NeoForge | Referencing `ParallelApi` from an always-loaded class fails before your capability check runs |
| Do not rely on `@ApiStatus.Internal` types | They exist for patches and the engine, not as a mod contract |

## 3. Lifecycle and validity windows

```
server construction
        │
        ├── RegisterParallelAdaptersEvent   ← registration is only valid here, on the server thread
        │        registrar().capabilities() ← protocol negotiation, no half-built service yet
        │        registrar().register...()  ← transaction: all-or-nothing, then frozen
        │
        ├── ParallelApi.forServer(server)   ← present from now until shutdown
        │        api.blockEntities()        ← service scoped to this server instance
        │
        └── shutdown                        ← forServer empty; retained services throw/reject
```

| Window | `forServer` | Registration | `blockEntities()` | `RegionThreadingManager.get()` |
|---|---|---|---|---|
| Before registration freeze | empty | valid | n/a | engine may be installing |
| Running server, engine on | present | frozen, throws/rejects | valid | present |
| Running server, engine off | present | frozen | valid (immediate on quiescent thread) | empty |
| After shutdown | empty | invalid | rejects (`SHUTDOWN`) | empty |

| Rule | Consequence if violated |
|---|---|
| A retained `Server` is not reusable after shutdown | `IllegalStateException` on service queries |
| `Reference`/`Task` belong to one service instance | Rejected after restart; never reuse across servers |
| Registration callbacks run sorted by id | Deterministic preparation order; do not depend on event subscriber order |
| Failed registration publishes nothing | A caught nested failure cannot leave a partial service |
| Config reload cannot migrate a built engine | Change engine/layout switches while stopped and start a new JVM |

## 4. Capability and phase semantics

`capabilities()` describes **protocols implemented on this server**. `Phase` describes **the calling thread's
execution context**. Neither is a permission token.

| Capability | Protocol it announces | What it does *not* mean |
|---|---|---|
| `EXECUTION_CONTEXT` | `currentPhase()` | Not an ownership pass |
| `NATURAL_SPAWN_PREPARATION` | Per-source-chunk preparation | Not permission to skip another listener |
| `NATURAL_SPAWN_PLAN_PREPARATION` | Whole-plan preparation before cap/state construction | Not a structure/chunk-load service |
| `NATURAL_SPAWN_CLAIMS` | Adapter takes over a specific mod's spawn restriction | Not an engine-enable override |
| `ORDERED_SPAWN_FINALIZATION` | Worker selection + server finalization with peers parked | Not vanilla RNG/order bit-equality |
| `BLOCK_ENTITY_TICK_ADMISSION` | Per-invocation admission of an original ticker | Not a type allowlist, not a slice switch |
| `BLOCK_ENTITY_OPERATIONS` | Two-owner operations and container transfer | Not parallel cross-region leases |
| `GENERAL_BLOCK_ENTITY_ADAPTERS` | C5 conservative request/validation protocol | Requests do not create execution authority; targets stay serial fences |
| `AUDITED_BLOCK_ENTITY_TICKS` | Audited complete original-ticker branch with READ/WRITE footprint | Hash ≠ semantic proof |
| `BLOCK_ENTITY_EXECUTION_ADAPTERS` | [Original ticks through region/resource execution](block-entity-execution.en.md) | Requires a complete trusted adapter audit |
| `SCHEDULED_TICK_DIAGNOSTICS` | Scheduled phase counters/latency | Empty list does not prove the slice is off |

| Phase | Meaning | Allowed to mutate world? |
|---|---|---|
| `QUIESCENT_SERVER` | Server thread in a provably still window | Only per the specific operation contract |
| `SERVER_SERVICE` | Main thread serving still-running workers | No blanket licence |
| `REGION_WORKER` | Inside a region batch worker | Only the current granted scope |
| `EXTERNAL` | Anything else | No |

## 5. Why the API is shaped like this

| Design choice | Reason |
|---|---|
| Capability negotiation instead of version sniffing | Slices and adapters land in different builds; a build number cannot express "this protocol exists" |
| Registration only inside one event, then frozen | Preparation order and budgets must be deterministic before any level ticks |
| Pure preparation + immutable snapshots | Worker bodies must not consume RNG, fire events or read live world state |
| Explicit `ServerRequired(reason)` | The safe fallback is a first-class result, not an exception or a silent serial run |
| Separate audited tick contract | The old predicate proves nothing about the body; audited adapters declare implementation, source/function classes and the exact mutable footprint |
| At-most-once operations with explicit states | Replaying a half-applied machine operation corrupts state; a `Task` reports instead of retrying |
| Diagnostics on the service, values only | Counters and bounded enum reasons are safe to expose; live objects are not |

## 6. Failure and refusal cheat-sheet

| Symptom | Likely cause | Correct response |
|---|---|---|
| `forServer` empty during registration | Registration not frozen yet | Use `registrar().capabilities()` inside the event |
| `UnsupportedOperationException` from a service method | Capability absent (older core or engine off) | Check capability; keep your serial path |
| `ServerRequired` every spawn unit | Unsupported configuration in your preparation | Keep it; do not "fix" it by widening the snapshot |
| `REJECTED / INVALID_CONTEXT` | Submitted from a network/async/service thread | Submit on the quiescent server thread or from the admitted ticker |
| `REJECTED / UNOWNED_SOURCE` | Ticker not the bound source instance | You cannot borrow another machine's authority |
| Adapter registered, zero worker completions | Slice off, phase over budget, or interior rule | Read `/neoforge regionthreading compat` before changing code |
| Large canonical phase | Planned in windows of at most 2048 candidates | Whole-phase resource/update budgets still apply; an exhausted suffix stays serial |

## 7. Verification checklist before shipping an integration

1. `MAJOR_VERSION` and capability checked, serial path preserved.
2. Registration is inside the event, ids namespaced, ≤128 total, no duplicate ids/types.
3. Preparation is pure; snapshots deeply immutable and detached from live objects.
4. Every refusal returns a bounded enum/reason, never a per-coordinate string.
5. No worker waits, no `join`/`get`, no blocking on another region.
6. Failures are reported, never replayed; partially applied operations never retried.
7. Diagnostics read through the service, not by keeping live objects.
8. Behaviour proven with the capability **off** first, then on.
