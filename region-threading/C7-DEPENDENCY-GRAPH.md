# C7: independent dependency-graph Block Entity execution

C7 is an experimental second execution system. C6 is retained and remains the default. This milestone implements a working engine and one deliberately narrow vanilla operation; it does **not** certify arbitrary mod-machine parallelism or a production migration.

The checkpoint is `checkpoint/c6-before-c7-20261116` at `88566baafe74b92e95701cdd0a4aa6c4e7e39202`. Development is on `feature/c7-dependency-graph`. See [C7-DEVELOPMENT.md](C7-DEVELOPMENT.md) for the baseline source audit.

## Authority and canonical entry

`Level.tickBlockEntities` retains pending publication, fresh `onLoad`, membership semantics and exception-safe finalization. Immediately after those steps it captures one `blockEntityExecutionSystem` choice:

* `C6`: the original plan/collector/coordinator/worker/fallback/exception pipeline.
* `C7`: the independent C7 entry. Failure to enter C7 safely takes the original serial loop, never C6.
* `DISABLED`: the original serial loop.

There is no tick that executes both drivers. No C6 adapter session, invocation ticket, resource grant, execution batch, fallback state or metric is used by C7. The only shared facilities are infrastructure: the existing worker pool, lifecycle read gate, admission boundary and Region ownership leases.

C7's outer gate first joins existing Region work, then closes participating admission. It holds the lifecycle read gate throughout collection, preparation, physical worker joins, validation, canonical commit and serial fences. The server coordinator owns the ordinary Region leases it uses. Detached workers receive **no Region context or permission to read/write live world state**. External unload/shutdown waits for this lifetime. Lifecycle self-waits from the coordinator or a pool worker are rejected before cleanup monitors can deadlock.

This is the hierarchical starting model. It permits independent detached operations in the same Region/chunk without changing `RegionState`'s single-owner rule. A snapshot operation never uses another Region's world storage on a worker. Resource keys and the graph can express cross-chunk, cross-Region and global conflicts, but they do not grant cross-Region mutation authority.

## Current-invocation resources and graph

`C7Resource` includes the actual lifetime and physical object identity, plus a resource kind. Kinds cover BE, block state, chunk, inventory, item/fluid/energy handler, capability, world mutation, scheduled tick, entity and global resources. Equal coordinates in another level or a replacement instance are not the same resource. Cached capability views must declare a common canonical underlying key: different Java wrappers or different kinds are not automatically alias analysis.

`C7Footprint` is an immutable complete/incomplete READ/WRITE declaration. Overflow makes a proof incomplete; it never authorizes a truncated subset. Incomplete work is an exclusive serial fence. Runtime history is diagnostic, not proof of the next invocation's accesses.

`C7DependencyGraph` uses last-writer plus readers-since-writer indexing. It emits RAW, WAR and WAW edges in original canonical order; shared readers have no edge. Construction is proportional to accesses and emitted edges, with bounded fence fan-in. A deterministic ready queue produces bounded waves. Explicit additional edges support dynamically supplied dependencies; invalid endpoints and cycles reject the plan before execution. No unresolved edge is silently discarded. The production engine currently builds forward resource dependencies, not speculative cross-Region transactions. Dependency counters count emitted edges in constructed graphs; serial prefix boundaries enforce additional implicit ordering without allocating those edges.

`C7AccessScope` checks explicit detached accesses before performing them. An undeclared access aborts the wave, reports the resource kind, and demotes subsequent invocations. This is **not** Java bytecode instrumentation: arbitrary field reads, cached handler aliases, callbacks and mod globals cannot be automatically intercepted. The internal compute contract therefore admits only separately audited code. There is no general mod opt-in registry that can turn an unverified callback into a worker task.

## Waves, commits and failures

C7 limits lanes to the existing pool size, distributes ready work through a shared cursor and never starts a second pool. Singleton work and unknown callbacks retain original serial ticks. Graph/task/resource/depth budgets are checked conservatively. A slow computation demotes future invocations; it never abandons a running task.

Workers compute detached results. World commits still follow the **original canonical invocation order**, including when a later independent result finishes earlier. A stale or unsupported canonical frontier discards later speculative detached results and rebuilds the suffix after the original serial invocation. Recomputing proven-pure arithmetic after discarding an uncommitted result is safe; no live BE tick or failed commit is replayed. With `c7Conservative=true`, a refusal keeps the rest of that phase serial.

Submission rejection runs only unclaimed detached work inline. A once-only runner claim also protects against an executor that accepts and then throws. All accepted computations physically finish before ownership is released, even when the caller's barrier throws or the coordinator is interrupted. A computation, serial body or partial commit failure propagates without an automatic retry. A failed wave commits none of that wave's results; earlier canonical commits retain their ordinary already-applied semantics.

## Built-in operation and conservative boundaries

The independent `C7FurnaceProgram` ticker token identifies the actual original function only for worlds started in enabled C7 mode. It does not reuse C6's token. C7 supports exact vanilla furnace, blast furnace and smoker instances on their **empty-input clock branch**:

* `litTime > 1`: decrement the burn clock and reset cooking progress.
* `litTime <= 0`: preserve the burn clock and apply vanilla cooling arithmetic.
* `litTime == 1`, active input, fuel/recipe processing, extinguishing, notification and inventory mutation: execute the complete original body serially.

Capture checks exact core binding/version, live chunk identity, current block/type, removal, attachments, timing/profiler constraints and recognized merged methods. Immutable clock records contain only primitive counters. Preparation and commit inspect live state only under the coordinator's Region ownership. The original C6 methods and execution logic are unchanged; the furnace patch only adds C7 snapshot/commit methods.

Recognized transformed callback classes are refused. The reflection audit is not a proof against arbitrary bytecode agents/coremods; broader runtime proof remains a compatibility gate. This is a reason to retain default-off status and run actual pack validation before production use.

| Access path | Current C7 support |
|---|---|
| BE counters | Exact audited clock snapshots and canonical writes |
| Block/chunk state | Coordinator-only identity/state validation; shared read declarations |
| Inventory | Empty-input condition read on coordinator; no worker inventory mutations |
| Neighbor BEs / item / fluid / energy handlers | Unknown complete serial invocation |
| Capabilities and cached aliases | Unknown complete serial invocation |
| World mutations / scheduled ticks / entity interaction | Original complete serial invocation; no fake deferred-success result |
| External callbacks / network controllers / shared mod globals | Original complete serial invocation |

Vanilla hoppers remain serial in C7. Chests do not by themselves establish a server-ticking workload. Create kinetic networks, AE2 and Mekanism have **no C7 parallel admission** in this milestone. Existing C6 adapters retain their own independent behavior. A mod's serial fallback in C7 is not a claim of tested compatibility.

## Configuration

All options live in `forgia.toml`. Existing C6 flags and defaults remain intact. C7 additionally requires the Region bridge, native workers and a fixed layout.

```toml
blockEntityExecutionSystem = "C7" # default C6; alternatives C6/C7/DISABLED
# Both the independent selector and enable flag are required.
enableC7 = true                 # default false
c7ExecutionMode = "PARALLEL"    # default SHADOW (plans, original serial bodies)
c7Workers = 2                  # capped at the existing pool size
c7MaxWaveSize = 256
c7MaxWaves = 256
c7MaxTasks = 16384
c7MaxResources = 256
c7StragglerNanos = 5000000
c7Debug = false
c7TrackingDiagnostics = true
c7Conservative = true
```

Selectors, enablement and scheduling limits require a world restart. Disabling diagnostics never disables correctness counters, completeness checks or ownership. Turning conservative mode off allows fresh suffixes after a serial fence; it does not admit unknown access.

`/forgia regionthreading c7 types` lists bounded per-type collected, parallel, serial, skipped and failed counts with **one serial reason per completed fallback**, separately from potentially repeated proof-attempt refusals. Unknown native functions are categorized by the missing audited operation contract (inventory/transfer, entities, world callbacks, globals or no detached program). These categories are not assertions that the current idle invocation actually touched those resources. Custom ticker/type getters are not invoked to obtain a diagnostic label.

`/forgia regionthreading c7` reports independent C7 task counts, dependencies, wave sizes, observed simultaneous computations, conflicts, discoveries, demotions, failures/rejections, proof refusals and collection/proof/graph/prepare/dispatch/compute/commit/phase times. Compute occupancy is an aggregate wall-time occupancy measurement, **not CPU utilization**. Parallel completion counts require a detached result computed on a worker and successfully committed; inline rejection work is serial completion. A snapshot computed then discarded is not counted as a completed parallel BE invocation. Proof-refusal counts are attempts, not unique BE counts. The original-loop fallback when the C7 outer gate cannot be entered is outside an entered C7 phase and is not represented as a C7 completion.

## Chorus reference: source of concepts, not imported implementation

Revisited the actual released `Chorus-1.20.1-complete-0.23.0.jar`; SHA-256 is `0dba6cb62bff1820b064b6c5c19320ea4550220ab68fc9d5c410e83270fa5796`. Reviewed `ChorusBeEngine`, `ChorusCarto`, `LevelAccessMixin`, wave selection and exception/join bytecode, rather than treating the previous report as source evidence. Local reference artifacts remain ignored under `tmp/chorus-reference`. No Chorus source/decompiled code is imported; its LGPL-3 artifact is an architectural reference for independently written Forgia code.

The [published engine](https://chorusmc.dev/engine/) and [roadmap](https://chorusmc.dev/roadmap/) distinguish shipped BE cartography/waves from experimental entity clean lanes, queued entity waves and a parked transactional engine. The roadmap explicitly leaves deep Create-base verification open. The [configuration documentation](https://chorusmc.dev/docs/) lists the BE/wave/carto switches.

The inspected BE implementation records bounded position sets (`hardTouch` stops adding at 64), constructs empirical clusters, chooses first-fit nonconflicting waves, learns overlaps after a wave, defers intents and waits for slow waves after marking demotion. It does not establish complete automatic Java resource tracking or READ/WRITE distinctions. The website's broad border-retry description must not be treated as proof that a partial inventory operation can safely be rerun. The artifact has catch/log/demote paths and AIR/null out-of-capture behavior described in [the earlier bounded bytecode review](CHORUS-BLOCK-ENTITY-REFERENCE.md), rechecked for this milestone.

Forgia adopts dependency waves, bounded observation, future-invocation demotion and explicit synchronization. It uses directed canonical dependencies instead of first-fit coloring: `A touches X; B touches X,Y; C touches Y` requires `A -> B -> C`, not `[A,C],[B]`. It rejects incomplete proofs rather than treating learned/truncated position sets as authority, retains synchronous world semantics and never retries partial live execution.

## Validation and evaluation gates

Automated suites exercise real worker overlap, READ/READ sharing, RAW/WAR/WAW order, the coloring counterexample, explicit cycles, unknown fences, overflow, budget refusal, stale snapshots, removal, chunk unload/reload, replacement, shutdown, rejection, barrier failure, partial exceptions, recursion and many same-Region/cross-chunk machines. The real `Level` entry tests C6/C7/DISABLED selection, C7-off fallback, SHADOW mode, canonical exact-once state, original serial hopper behavior and C6 metric isolation. Original C6 regression classes are run unchanged.

The opt-in `:tests:runC7EvaluationGameTestServer -Pc7Driver=C6|C7|DISABLED` run uses one fixed-seed flat world/fixture recipe, zero players, the same loaded test mods and the same persistent pool/non-BE configuration. It samples full server tick bodies after warmup, validates every clock tick and inventory, and writes `tests/runs/c7EvaluationGameTestServer/c7-evaluation.json`. It resets only that isolated generated run directory. Copy each result before the next driver. C6 furnace-wave/resource switches default off in this evaluation so the primary C6 row preserves those shipped settings; pass `-Pc6FurnaceWaves=true -Pc6Resources=true` for an additional, explicitly labeled C6 resource-wave profile. Never present that optional profile as the only C6 baseline. These are controlled empty-input furnace measurements, not evidence about active machine networks or mixed-mod packs. CPU/heap/allocation observations have their stated JVM sampling limits; heap endpoint deltas are not retained-memory estimates.

The additional `-Pc7AllVanilla=true -Pc7VanillaCopies=16` evaluation enumerates every vanilla BE type and every valid vanilla block variant to find server-ticking representatives. It probes every possible block state, then places one representative of each non-ticking type and 16 instances of each type with a ticking state. It initializes moving pistons with actual motion state and plays real records in jukeboxes so conditional tickers are covered, then writes `c7-vanilla-evaluation.json`. Non-ticking blocks are listed separately and never counted as fallback. Moving pistons naturally finish during warmup and are reported as transient rather than silently removed from coverage. The full original bodies of unsupported types run serially. This is a mostly idle test with playing jukeboxes. It is not a force-unsafe admission switch or a test of every active recipe/entity/network state. The paused checkpoint and measurement/validation limitations are recorded in [C7-EVALUATION-2026-11-16/STATUS.md](C7-EVALUATION-2026-11-16/STATUS.md).

Real mixed-mod equivalence, representative Create/AE2/Mekanism loads, long soaks and broader operation admission remain outstanding evaluation gates. C6 must remain present even after future gates pass.
