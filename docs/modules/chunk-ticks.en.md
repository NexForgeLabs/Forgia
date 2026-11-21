# Module: chunk ticks and cross-region effects

English | [中文](chunk-ticks.zh.md) · [Module map](README.en.md)
Source: [`RegionChunkTickCoordinator`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionChunkTickCoordinator.java), [`RegionBorderUpdates`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionBorderUpdates.java), [`RegionBorderGameEvents`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionBorderGameEvents.java), [`RegionDeferredBlockUpdates`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionDeferredBlockUpdates.java)
Evidence: [STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md), [PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md), [GUARD-INVENTORY](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/GUARD-INVENTORY.md)

Status: random chunk ticks **certified within C6**; border updates and game events are **experimental**
(`enableRegionThreadingBorderUpdates`, `enableRegionThreadingGameEvents`, both default off, outside C6).

---

## 1. Responsibilities

| Owns | Does not own |
|---|---|
| Whole chunk tick body: random ticks, ice/snow, thunder | Natural/custom spawning (separate slice) |
| Per-chunk deterministic random derivation | Vanilla world RNG sequencing |
| Recording worker writes outside the lease into a border queue | Running the target region's body early |
| Same-tick fixpoint propagation of those effects | Unbounded cascade; overflow is delayed and counted |
| Foreign listener capture for game events | Arbitrary event-listener thread safety |
| Deferred block update publication | Making pistons/contraptions region-aware |

## 2. Class map

| Class | Responsibility |
|---|---|
| `RegionChunkTickCoordinator` | Build and dispatch chunk tick batches |
| `RegionChunkTickPlan` / `...Entry` / `...Batch` | Per-chunk work list, dispatch, join |
| `RegionChunkTickAttribution` | Timing per phase |
| `RegionDeferredBlockUpdates` | Queue of deferred block/light effects |
| `RegionBorderUpdates` / `RegionBorderPropagationBatch` | Cross-region neighbour propagation and fixpoint |
| `RegionBorderGameEvents` | Capture and deliver foreign game events |
| `RegionAwareRandomSource` / `NamedRandomSequences` | Region-local random derivation |
| `RegionNeighborUpdateScope` / `RegionCollectingNeighborUpdater` | Neighbour update capture |

## 3. Tick flow

| Stage | Thread | Action |
|---|---|---|
| 1 | Server | Collect tickable chunks, verify ownership/neighbourhood/budgets |
| 2 | Server | Freeze per-chunk metadata, derive region-local random sequences |
| 3 | Workers | Run the complete chunk body (random ticks, ice/snow, thunder) |
| 4 | Workers | Record foreign writes into the border queue; queue deferred block updates |
| 5 | Server | Barrier join; publish deferred effects |
| 6 | Server | Run the border fixpoint until stable or the cascade budget is hit |
| 7 | Server | Deliver captured game events to owning regions in posting order |
| 8 | Server | Serial fallback for refused chunks |

## 4. Admission gates

| Gate | Detail |
|---|---|
| Installed bridge + inactive profiler | Required (works on `DYNAMIC_CLUSTER`, `FIXED_3X3`, and `DYNAMIC`) |
| Loaded neighbourhood | The region's chunk neighbourhood must be loaded with live mappings |
| Ownership/budget | Valid lease and remaining cascade/deferred-update budget |
| No unsupported neighbour | A foreign/unrestricted neighbour keeps the chunk serial |
| Profiler | Active profiler → inline |

## 5. Border propagation and the fixpoint

| Behaviour | Detail |
|---|---|
| Worker write outside lease | Recorded, not executed by the worker |
| Target region alive | Applied **in the same tick** by the owner |
| Target missing/unloaded | Dropped **with a counter**, never silently |
| Cascade | Bounded; overflow is delayed to the next tick |
| Body replay | Never: a stale propagation read is not repaired by rerunning the chunk body |
| Interior rule lift | Chunk ticks may touch border neighbours without whole-chunk fallback (this is what moved 100 % of dispatched chunks onto workers) |

## 6. Game events (experimental)

| Aspect | Detail |
|---|---|
| What is captured | Foreign listener columns reached from a posting worker |
| Delivery | On the owning region, in posting order |
| Limits | Bounded, drops counted |
| Switch off | Inline listener dispatch from the posting worker — that baseline **can race** foreign listeners |
| Switch on | Capture path; outside C6, validate the target configuration explicitly |

## 7. Configuration

| Key | Default | Restart | Effect |
|---|---|---|---|
| `enableRegionThreadingRandomTicks` | `false` | yes | Whole chunk tick body on workers |
| `enableRegionThreadingBorderUpdates` | `false` | yes | Cross-region neighbour capture + fixpoint |
| `enableRegionThreadingGameEvents` | `false` | yes | Foreign game-event capture; OFF keeps inline dispatch |
| `regionThreadingMode` | `FIXED_3X3` | yes | `DYNAMIC_CLUSTER` (recommended) or `FIXED_3X3` (C6 baseline) |
| `regionThreadingOwnershipViolationMode` | `WARN` | no | Advisory assertions |

```toml
# config/forgia.toml — chunk ticks with optional cross-region effects
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingRandomTicks = true
# enableRegionThreadingBorderUpdates = true  # experimental, outside C6
# enableRegionThreadingGameEvents = true     # experimental, outside C6
regionThreadingMode = "DYNAMIC_CLUSTER"      # recommended (FIXED_3X3 is the C6 baseline)
regionThreadingDynamicSectionShift = 3
```

## 8. Measured workload (historical)

| Workload | Off (ms/slice) | Regionized | Speedup |
|---|---|---|---|
| 1024 chunks random ticks (9×9 regions) | 5.59 / 5.87 | 4.80 / 4.64 | 1.15–1.27× |
| 1024 chunks random ticks (3×3, interior rule lifted) | 6.88–6.92 | 5.54 | 1.24–1.25× |

After the interior-rule lift, **100 % of dispatched chunk ticks** ran on workers (1.31 M ticks, zero
boundary fallbacks) and the production soak landed at or below inline.

## 9. Determinism

| Property | Guarantee |
|---|---|
| Per-chunk random derivation | Independent of region layout, worker count, tick order and thread |
| Named random sequences | Region-aware wrapper resolves from the group; do not construct one as a safety claim |
| Vanilla global RNG order | Not preserved across regions |

## 10. Failure semantics

| Event | Behaviour |
|---|---|
| Chunk body throws | Reported; body not replayed |
| Cascade budget exceeded | Overflow delayed to the next tick, counted |
| Destination region missing | Update dropped, counted |
| Unloaded neighbourhood | Complete serial chunk body before start |
| Shutdown | Deferred queue drained or dropped with counters |

## 11. Comparison

| Property | Chunk random ticks | Border updates | Game events |
|---|---|---|---|
| Scope | Own chunk body | Neighbour effects | Foreign listeners |
| Default | off | off | off |
| Certified | C6 | outside C6 | outside C6 |
| Same-tick convergence | n/a | yes (bounded fixpoint) | posting order |
| Risk if off | — | vanilla collector path | inline dispatch can race foreign listeners |

## 12. Limits

| Limit | Detail |
|---|---|
| Cascade bound | Not unlimited same-tick convergence |
| Redstone | 49 layouts / 5 880 tick comparisons identical to vanilla, but arbitrary contraptions/pistons are not region-aware |
| Structures | Not region-aware |
| Mod chunk/event callbacks | Still require pack validation |
| Game events off | Not a universal safety claim |

## 13. Diagnostics

| Signal | Meaning |
|---|---|
| Chunk tick counters | dispatched/worker/serial/truncated per window |
| Border counters | updates applied/dropped/delayed |
| Game-event counters | captured/delivered/dropped |
| `/forgia region ...` | region workload/coverage; BE/serial fallback coverage partial |

## 14. When / how / why

| Question | Answer |
|---|---|
| When to enable random ticks | Chunk random-tick load dominates and the world is a fixed layout |
| Why is it only ~1.2× | The per-chunk body is small relative to coordination and publication |
| When to enable border updates | You need cross-border redstone/neighbour effects to converge in the same tick |
| Why off by default | The fixpoint and capture paths are experimental, with drop semantics |
| Why never replay a body | Partial effects would duplicate; a stale read is deferred, not repaired by rerunning |
