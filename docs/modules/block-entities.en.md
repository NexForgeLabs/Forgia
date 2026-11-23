# Module: block entities and the canonical phase

English | [中文](block-entities.zh.md) · [Module map](README.en.md)
Source: [`CanonicalBlockEntityCoordinator`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/CanonicalBlockEntityCoordinator.java), [`NativeBlockEntityPreparation`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/NativeBlockEntityPreparation.java), [`FurnaceWorkPreparation`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkPreparation.java), [`BlockEntityResourceOwnership`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/BlockEntityResourceOwnership.java), [`AuditedBlockEntityPreparation`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/AuditedBlockEntityPreparation.java)
Evidence: [BLOCK-ENTITY-PARALLELISM](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/BLOCK-ENTITY-PARALLELISM.md), [CANONICAL-BLOCK-ENTITY-COORDINATOR](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md), [BLOCK-ENTITY-WORK-GRAPH](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/BLOCK-ENTITY-WORK-GRAPH.md), [C6-FINAL-ACCEPTANCE-2026-11-15](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md)

Status: C6 correctness results apply only to the historical bounded cohorts. Separately, C7 validated the generic Region Sandbox on a 188-mod production pack: ordinary modded BEs do not require per-mod adapters, and measured BE execution on workers reached 72%–82% in that workload. This is an execution share, not a mod compatibility percentage or a universal speedup claim. Enable `enableRegionThreadingBlockEntityTicks` and `enableRegionSandboxAllBlockEntities` deliberately; both are off by default. Complex shared-state mods such as Create still need dedicated compatibility handling or serial fallback. Furnace waves and resource-scoped legacy paths remain separately gated.

---

## 1. Responsibilities

| Owns | Does not own |
|---|---|
| One canonical `Level.tickBlockEntities` phase with a single monotonic cursor | A second BE loop or scheduler |
| Contiguous prefix execution with complete serial fences | Mod machine semantics |
| Native/Furnace/Mod proof admission and fresh revalidation | Claims conversion for unproven types |
| READ/WRITE resource claims, leases and one-shot scopes (experimental) | Ownership of foreign/world-wide writes |
| Work-graph observation and directed wave planning (opt-in) | Arbitrary dependency discovery |
| Audited adapter integration (`AUDITED_BLOCK_ENTITY_TICKS`) | Being a sandbox for third-party code |

## 2. Class map

| Class | Responsibility |
|---|---|
| `CanonicalBlockEntityCoordinator` | Single cursor, prefix planning, fence placement, suffix re-inspection |
| `RegionBlockEntityTickPlan` / `...Batch` / `...Chunk` / `...Scope` | Per-chunk BE work, dispatch, ownership scope |
| `RegionBlockEntityTickAdmission` / `...ChunkCache` | Type/chunk admission cache (policy + layout + revision) |
| `NativeBlockEntityPreparation` | Exact native function/instance/branch proof |
| `FurnaceWorkPreparation` / `FurnaceWorkBatch` / `FurnaceWorkScope` | Burning-furnace wave preparation and execution |
| `BlockEntityResourceOwnership` / `BlockEntityWorkPlanner` | Claims, non-blocking reservations, identity/alias conflicts, budgets |
| `BlockEntityExecutionEngine` / `...Batch` | Ticket/permit binding, one-shot body start, join, effects |
| `ExecutionBlockEntityPreparation` | Resource-scoped `BlockEntityExecutionAdapter` preparation, revision checks, and same-region wave grouping |
| `AuditedBlockEntityPreparation` | Manifest verification, footprint checks, wave splitting |
| `RegionBlockEntityWorkGraph` / `RegionBlockEntityTickAttribution` | Observation graph and timing |
| `RegionBlockEntityTickFailure` | Failure carrier, no replay |

## 3. Canonical pipeline

| Stage | Thread | Action |
|---|---|---|
| 1 | Server | Build the canonical phase; `prepare`/`prepareCanonical` for registered adapters |
| 2 | Server | Publish the batch; capture wrapper/delegate/source/function/chunk/frame |
| 3 | Workers | Contiguous prefix: per-invocation admission, fresh footprint, wave split on READ/WRITE conflicts |
| 4 | Workers | Original ticker bodies run **once** under the held region lease |
| 5 | Workers | Physical join; required effects committed |
| 6 | Server | Any unproven entry is a **complete serial fence**; suffix re-inspected afterwards |
| 7 | Server | Operations completion (see [cross-region](cross-region-and-scheduled.en.md)), then serial fallback |

The phase never splits around an unknown callback: unknown → the whole original tick runs once, serially,
before the cursor advances.

## 4. Admission tiers

| Tier | Types | Requires |
|---|---|---|
| Interior | Furnace, blast furnace, smoker, hopper, explicitly pack-admitted types | Type/chunk/entity-load/binding/lease/deferred-update checks |
| Boundary + border propagation | Built-in furnace family only | All interior checks **plus** border propagation enabled and all eight neighbours loaded with live mappings |
| Audited adapter | Registered steady types | Manifest integrity, exact binding, fresh READ/WRITE footprint |
| Serial fence | Everything else | Complete original tick on the server |

| Boundary safety fact | Detail |
|---|---|
| Furnace body does not transfer items to neighbours | Only its own state, neighbour/comparator notifications |
| Effects use deferred publication + border fixpoint | Runs after workers return; never reruns the body to repair a stale read |
| Missing neighbour/mapping | Complete server body selected **before** start |
| Chunk with any unproven ticker | Whole-chunk server fallback maintained |
| Missing chunk after start | Reported failure; never a whole-tick retry |
| Pack admission | Grants only the interior contract; a furnace-like name does not grant boundary access |

## 5. Comparison of BE execution paths

| Path | Proof | Execution unit | Config | Status |
|---|---|---|---|---|
| Native (vanilla) | Exact function/instance/branch, fresh | Contiguous prefix | `...BlockEntityTicks` | C6 |
| Furnace waves | Fresh burning-furnace bodies only | Directed waves inside a region | `...FurnaceWorkWaves` | Experimental, C6 with bounded cohorts |
| Resources | Object identity READ/WRITE claims, one-shot scopes | Furnace prefixes | `...BlockEntityResources` | Experimental |
| Mod audited | Adapter manifest + footprint | Contiguous prefix | `...BlockEntityTicks` + adapter | Experimental |
| Unknown | — | Complete original tick | — | Always available |

## 6. Budgets and limits

| Budget | Value | Effect |
|---|---|---|
| Canonical phase entries | **2048** | A larger phase stays wholly serial |
| Furnace catalog | 4096 | Catalog larger than this refuses wave preparation |
| C5 declarations | 512 per unit, 131072 per phase | Cumulative, not reset per fence |
| Footprint roots | 64 per proof | More → refuse |
| Packed leases | 16 regions per task | All-or-none acquisition |
| Deferred update/cascade | Config-bounded | Overflow delayed, counted |

> Raising the 2048 constant is not a solution: a bounded-window coordinator is required and does not exist yet.

## 7. Configuration

| Key | Default | Notes |
|---|---|---|
| `enableRegionThreadingBlockEntityTicks` | `false` | Requests the actual canonical phase (works on `DYNAMIC_CLUSTER` and `FIXED_3X3`) |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | Requires the BE slice and **`FIXED_3X3` only** (ignored in `DYNAMIC_CLUSTER`) |
| `enableRegionThreadingBlockEntityResources` | `false` | Adds claims/leases/scopes to furnace prefixes (**`FIXED_3X3` only**); also gates C6 mod admission |
| `enableRegionThreadingBorderUpdates` | `false` | Required for furnace boundary admission |
| `enableRegionThreadingPackedRegionLeases` | `false` | 16-region packing (**`FIXED_3X3` only**; bypassed in `DYNAMIC_CLUSTER`) |
| `regionThreadingMode` / `FixedRegionChunks` | `DYNAMIC_CLUSTER` / `3` | Current default is `DYNAMIC_CLUSTER`; legacy furnace-wave/resource experiments and packed leases require `FIXED_3X3` |

```toml
# config/forgia.toml — minimal block-entity slice
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingBorderUpdates = true        # needed for furnace-family boundary admission
# enableRegionThreadingFurnaceWorkWaves = true     # experimental, FIXED_3X3 only
# enableRegionThreadingBlockEntityResources = true # experimental, FIXED_3X3 only
regionThreadingMode = "FIXED_3X3"                # legacy-only example for furnace-wave/resource experiments; normal deployments use DYNAMIC_CLUSTER
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
```

## 8. Measured workload (historical, do not over-read)

| Workload | Inline | Regionized | Ratio |
|---|---|---|---|
| 2048 hoppers | 3.27 mspt | 3.74–3.97 | 0.82–0.87× (slower) |
| 4096 furnaces | 2.60 mspt | 2.70–3.34 | 0.78–0.96× (slower) |

| Fact | Consequence |
|---|---|
| Worker body costs ≈2.5× inline per ticker | Light loads lose; the worker path pays coordination |
| Persistent pool off by default | Pending the complete workload matrix |
| Boundary furnace admission not benchmarked | No speedup claim |
| C6 acceptance | Correctness passed; performance parity, not a win |

## 9. Failure semantics

| Event | Behaviour |
|---|---|
| Unknown class/behaviour/transform | Complete serial tick; suffix re-inspected |
| `inspect`/`check` throws | Failure, never permission |
| Started body throws | Reported after join/cleanup; never replayed |
| Fresh footprint differs | Unstarted prefix refused |
| Missing neighbour before start | Serial body selected |
| Missing chunk after start | Reported failure, no retry |
| Off-region capability / `BlockCapabilityCache` / attachment / `SavedData` access | `RegionWorkerBoundaryException` triggers fail-closed BE fallback to the server thread (`CapabilityListenerHolder` runs invalidation callbacks outside its lock) |
| Worker visual/particle broadcast | Routed to the server thread via serviced publisher handoff preserving particle send results |
| Shutdown/unload | Original physical completion boundary; tickets/leases released |
| Operation callback throws | Completion-stage failure reported; other accepted requests drain |

## 10. Work graph (opt-in)

| Aspect | Detail |
|---|---|
| Purpose | Observe access patterns and plan directed waves |
| Data | `workgraph` command; `nativeCompleted/failed/skipped` counters |
| Authority | Observation **grants nothing**; it is diagnostics |
| Adoption | Requires reviewed ownership/order requirements (see Chorus reference) |
| Status | Experimental; not a scheduler |

## 11. Diagnostics

| Signal | Meaning |
|---|---|
| `nativeCompleted` | BE ticker bodies returning on workers |
| `refused` + `reasons` | Admission refusals by bounded decision |
| Boundary counters | `unloadedNeighborhood`, `untrackedNeighborhood`, `boundaryChunk` |
| `/forgia regionthreading workgraph [start <phases>\|stop]` | Bounded access graph and wave counts |
| Attribution | Separate canonical-prefix, serial-fallback and operation-completion timings |

## 12. Limits

| Limit | Detail |
|---|---|
| 2048-entry phase | Large modpacks remain serial |
| Structures/contraptions/pistons | Not region-aware |
| Hoppers and pack-admitted machines | Interior rule (they read/mutate live neighbours) |
| Mixed chunks | Whole-chunk fallback |
| Mod callbacks | Adapter's responsibility; no automated task-safety proof |
| Speedup | Not claimed; measure your own workload |

## 13. When / how / why

| Question | Answer |
|---|---|
| When to expect a gain | Many independent, already-proven BE bodies in phases ≤2048 under load |
| When to keep it off | Light BE load, hopper-heavy lines, large phases, unproven mods |
| Why a single cursor | Preserves vanilla ordering while allowing a contiguous safe prefix |
| Why serial fences | An unknown callback could touch anything; running it whole is the only safe action |
| Why no replay | Inventory/consumption effects are irreversible |
| Why 2048 | Conservative bound so the phase's coordination cost stays bounded; not a tunable performance knob |
