# C7 dependency-graph execution — development record

Baseline inspected 2026-11-16: `88566baafe74b92e95701cdd0a4aa6c4e7e39202`.
Checkpoint: `checkpoint/c6-before-c7-20261116`; working branch: `feature/c7-dependency-graph`. The worktree was clean. Target remains Java 21, Minecraft 1.21.1, NeoForge 21.1.x.

## Audited C6 behavior (source, not prior report)

* `Level.java.patch::tickBlockEntities` merges pending tickers under the membership lock, runs fresh `onLoad`, then calls `RegionThreadingHooks.beginBlockEntityTickPlan`. The canonical list snapshot, not chunk registration order, determines phase ordinals. A null plan takes the original serial loop. Finalization merges pending entries even on failure.
* `RegionBlockEntityTickPlan` retains canonical and legacy collectors. `RegionThreadingEngine.beginBlockEntityTickPlan` rejects disabled slices, shutdown, nested admission, active profilers and retired levels.
* `tickBlockEntities` selects existing canonical/native/furnace drivers; `tickCanonicalBlockEntities` enters the quiescent admission boundary, publishes the active batch before preparation, opens adapter state, and admits prefixes at the canonical cursor. Unknown invocations are serial fences.
* C6 owns `BlockEntityExecutionEngine`, `BlockEntityResourceOwnership`, `RegionBlockEntityTickScope`, `RegionTickBatch`, `RegionState` ownership, admission caches, failure handling and fallback counters. Furnace proofs validate current ticker binding, exact classes, recipes/items, aliased inventory identities and bounded notification footprints.
* `RegionWorkerPool`, `RegionTaskBatch` and physical barriers are shared infrastructure. Completion always joins accepted tasks; rejection runs only unstarted work inline. A partial tick must never be replayed. C6 effect commit, lease release, global messages, POI publication and failure reporting remain its responsibility.
* Existing diagnostics include BE attribution, work-graph observation, furnace work and resource metrics, plus region queue/worker telemetry in `/forgia` and `/regionthreading`. C7 counters must be separate.

## Implementation strategy

Start with hierarchical, **detached compute / canonical commit**, not concurrent world mutation. Region ownership and the quiescent admission fence remain the outer boundary. C7 workers receive immutable invocation snapshots only; they do not acquire C6 task grants or mutate live world/BE/chunk/capability state. All live capture, validation and commit occurs on the server under the outer fence. Unknown bodies are exclusive serial fences. This permits independent work inside one region without pretending that two workers both own its `RegionState`.

C7 supplies independent resource identities (including lifetime/level identity), READ/WRITE declarations, ordered RAW/WAR/WAW DAG construction, explicit extra-edge/cycle handling, bounded ready waves, physical joins, future-invocation straggler demotion, runtime declared-access validation and independent metrics. Observed history is diagnostic only, never proof of future access.

The initial built-in operation will be intentionally narrow and separately audited. No blanket support for Create, AE2, Mekanism, item/fluid/energy capabilities or arbitrary Java/mod globals is inferred. Unsupported paths run their original complete ticker serially. No retry follows compute/commit/body failure.

## Work sequence

1. Recheck actual Chorus artifact and published engine/roadmap; record implemented vs advertised behavior without importing LGPL-3 code.
2. Implement independent C7 resources, DAG and wave engine; add pure concurrency/conflict/lifecycle tests.
3. Add mutually exclusive C6/C7/DISABLED selection, independent config, outer ownership bridge, audited built-in capture/compute/commit and diagnostics.
4. Run C7 tests, original C6 regression suites, compile/build checks and matched local benchmark fixtures. Distinguish fixture timing from real server MSPT and leave untested mod compatibility explicitly unverified.

C6 is retained regardless of C7 outcomes; C7 is default off. Full mixed-mod server equivalence and real-world benchmarks remain evaluation gates, not assumptions.
