# Performance optimization and runtime observability report

Session starts from clean `506e3707a80884140dd68b2bd51211e40aa879b4`. This is a measured optimization and diagnostics implementation with explicitly limited parallelization scope. It does **not** establish that Forgia is generally faster than vanilla/NeoForge, and it does **not** introduce new chunk generation/loading or player tick worker admission.

## Main result

On the same installed64-furnace workload, exact full NBT/output/inventory equivalence holds across14fresh JVMs and28measured400tick windows. Reusing furnace catalog lookahead strictly within a still-unexecuted canonical prefix reduces the four unsampled window means from10.376/10.432/10.347/10.283ms to2.375/3.090/2.335/2.981ms. Their descriptive mean falls10.359→2.695ms (74.0%); allocation falls253.08→18.70MiB/s (92.6%). Separate JFR captures confirm main-thread CPU4.582→1.844s over comparable18s intervals. This fixes a substantial regression, but stock NeoForge remains0.19–0.26ms and Forgia serial0.25–0.27ms on this inexpensive workload. Worker proof/planning overhead still exceeds the useful work.

A separate due-drain admission fix avoids submissions whose original timer is already known not to be due. One same-workload6000tick normal-world soak pair passes all51invariants and changes8.274→8.108ms mean; P99 worsens16.456→17.234ms, so a general tail benefit is not established. All measurements retain their own VM, workload, observation and JIT limitations in [PERFORMANCE-BENCHMARKS.md](PERFORMANCE-BENCHMARKS.md). No online workload is relabeled as a controlled benchmark.

## Implemented changes and boundaries

| Subsystem | Server thread | Existing workers | Newly moved / changed | Correctness boundary |
|---|---|---|---|---|
| Region queue drains | Original lifecycle/lease admission and physical barrier | Original pool executes due drains | Pre-submission due check; no new body admission | Active/accepted work is awaited before the optimization; original worker timer check remains |
| Canonical furnace phase | Collection, lookahead, fresh proof, reservations, ordered commit | Original protected furnace bodies under original Tickets/Claims/leases/scopes | Share read-only catalog/alias lookahead within one unexecuted prefix; no extra worker types | Discard before every execution/serial fence; preserve held-region mailbox delivery, fresh audit, whole-phase alias/budget checks and all validators |
| Pool lifecycle | Original close/join controller | Same Fixed/Persistent workers | Fixed threads explicitly joined after executor termination | Accepted work is never abandoned; self-close still rejected; interruption restored after physical join |
| Observability | Native tick boundary, optional1s JVM sample, administrator command output | Original pool boundaries update bounded counters | Native history, median/distribution metrics, worker/queue counters and commands | No scheduler/pool/authority/effects created; value-only observations; missing coverage N/A |
| Chunk generation | Original FULL/registration lifecycle and serialized/owned stages | Existing vanilla biome/noise futures, worldgen/light infrastructure | None | Existing status/neighbor dependencies preserved; arbitrary callback and cross-chunk writes unproven |
| Chunk loading | Original reconstruction, POI/light effects, factories, registration/events | Existing I/O/NBT/data-upgrade pipeline | None | Whole serializer is not a pure worker task; no second loader |
| Players | Original packets, validation, inventory/combat/lifecycle, player ticks | Existing worker AI snapshot intersection | None | Player snapshots include live references; no immutable movement-result epoch/commit protocol |

The canonical Coordinator/Kernel/Ticket/Claims/planner/reservation/Lease/Scope, worker admission, physical join, ordered commit and retirement remain authoritative. Unknown mod callbacks and Create retain complete serial fallback. No unrelated adapters, runtime mod hot-unload, independent resource locks or effect queues are added. Frozen C6 and lifecycle documents/receipts are unchanged; new source measurements do not rewrite their historical acceptance scope.

## Commands and monitoring

`/forgia status`, `/forgia regions [page]`, `/forgia region <dimension> <x> <z>`, `/forgia region top <metric> [count]`, `/forgia workers`, `/forgia scheduler`, `/forgia performance`, `/forgia performance history [seconds]`, `/forgia performance json`, `/forgia diagnostics`.

Permission level2is enforced at the root. Existing `/neoforge regionthreading` commands/raw lines remain compatible. Region and metric completion, bounded pagination, empty/retired/error output, standard units and JSON-null unavailable values are implemented. Threshold warnings are configurable and describe observations rather than asserting root causes.

History uses12,000preallocated tick/time slots, exact nearest-rank P50/P95/P99 and1/10/60/300wall-second queries. Accelerated servers report capacity truncation. Recording allocates nothing after initialization. JVM CPU is distinguished from blocked task wall time; allocation covers the server+pool, GC MXBean time is not called pause time, and task completion is not called individual body success. Worker timing is optional; essential ownership/queue/task counters stay active when disabled. Sampling performs no per-tick full-region scan or disk I/O.

Region MSPT remains the instrumented leased-group sum, **not complete per-region cost**: canonical Native/Furnace and serial-fallback body/commit coverage is explicitly N/A. Block-entity count, active ticking chunks, per-region scheduled block/fluid ticks, active resource reservations/tickets and lock/commit-wait durations are also N/A rather than invented. Command-time whole-region snapshot/sort work is bounded per region but has not been certified at extreme region counts. Full definitions, thresholds, cost and limitations: [RUNTIME-OBSERVABILITY.md](RUNTIME-OBSERVABILITY.md).

The dedicated harness measures warmed native history5.134–5.256ns/tick,0allocated bytes, and region begin+record55.118ns,0allocated bytes. Installed enabled/disabled sampling rates both average18.70MiB/s on the optimized furnace workload; its timing differences are within independent-JVM variation. The full normal-world enabled/disabled and reverse-order pairs are reported separately; no zero-cost or tight whole-server upper-bound claim is made.

## Correctness and evidence

- Compile/install: all final production code and test sources compile on JDK21; pinned `.98`/`.99` installed fixtures and final `.100` packaging run in disposable directories.
- Complete final JUnit: **1,424Passed/0Failed/0Errors/0Skipped** independently with Fixed and Persistent flags, resources ON. The separate unknown merged-furnace-hook serial control passes1/1. Earlier1,417case complete populations precede catalog changes; later213case selections are narrower and are not added to complete totals.
- Final original upstream Mod selection: **4/4GameTests per configuration**, actual server/Gradle exits0. Fixed observes151complete original bodies=76Worker+75Serial; Persistent152=72Worker+80Serial; each has overlap2. Both reject10foreign writer routes with delta0, retire exact original Ticket/Claims/Lease authority, reconstruct25chunks/13BEs/9entities, and preserve failure0→1→1with no started-body replay and fresh same-Level work.
- Region GameTests: exact15-batch Fixed selection15/15. First Persistent selection13/15, with two unchanged timeout/chunk-load failures retained; those two pass in independent fresh Persistent and Fixed controls. The final `.100` repeated15-batch Persistent selection completes14Passed/1Failed at `physicalEntitySectionCrossing`'s required chunk-load wait; this selected-profile gate remains open. These are completed failures, not skipped tests; selected runs are not unfiltered C6 acceptance.
- Normal-world6000tick soaks: baseline, due-drain candidate, final monitoring ON and OFF all pass51invariants; final primary ON8.204ms/OFF7.776ms. A later reverse-order unmonitored6000tick run completes50/51invariants and fails final `regionMappingMatchesPosition=1` for a live bat (mapped23/23, expected23/22); it is excluded from passing performance comparisons. The independent monitored reverse-order cell passes51/51invariants at8.406ms, but it does not convert the failed OFF cell into an equivalent passing overhead pair. Both results remain recorded.
- Final stopped-server real-Level GC: **CERTIFIED** for both Fixed/Persistent. Each physically joins original server/Worker threads, observes4collections, clears/enqueues28/28tracked references while the engine and6actual callbacks remain strongly reachable, and refuses12retained-callback foreign accesses. Original GameTest/observer/Gradle exits0. This is real world retirement, not runtime Mod unloading.
- Monitoring: exact known-distribution/raw-ring/region/group/history tests, real queue/failure/join controls, two-server-thread isolation and independent CPU bracketing; benchmark correctness is separate from timing. Python measurement parser19/19and controlled comparison/export4/4pass.

The main mixed-world regression gate remains open, independent of the equivalent stationary furnace improvement.

The new controlled tests validate exact native ring input, known-distribution MSPT/P50/P95/P99, region execution accounting, wall-time history bounds/retirement, permissions/completion/pagination, both real pool queue/failure/rejection/physical-join paths, disabled timing with counters intact, and independent CPU bracketing for a waiting worker. The dedicated timing harness is counted as a measurement, not a correctness test. All original assertions remain; skipped/unselected/manual populations are never promoted to passed.

Retained failures include the initial short soak's missing cross-region write, the initial monitor unloaded-config/physical-join failures, the catalog5-versus2red test, the reproduced cross-server monitor-sample race (fixed by server-thread confinement), the new ineligible-furnace observer-fixture failure, and the Persistent15-batch ice/chunk timeouts, final Persistent physical-entity chunk-load failure, and reverse-order soak's misplaced-bat invariant. Two unchanged failing region cases pass together in new Persistent and Fixed worlds; this does not erase the first failed profile. Exact populations, logs, commands, actual exits and output hashes are retained under `tmp/performance-session` and the [hashed session receipt](PERFORMANCE-SESSION-RECEIPT.json).

## Parallelization audits and remaining blockers

- [Generation stage safety matrix](CHUNK-GENERATION-PARALLELISM-AUDIT.md): dependency radii/status transitions, ProtoChunk/heightmap ownership, features' neighbor writes, seeded determinism, light/status futures, cancellation and original callbacks. Existing vanilla asynchronous terrain is distinguished from new Forgia execution. No new deterministic generation implementation is claimed.
- [Complete loading audit and implementation plan](CHUNK-LOADING-PARALLELISM-AUDIT.md): I/O/NBT/upgrades are already background; palette/heightmap preparation requires extracted immutable/owned inputs, while reconstruction currently reaches POI, lighting, attachments, structures, factories and load events. No wholesale worker serializer is admitted.
- [Player call-graph and worker audit](PLAYER-WORKER-EXECUTION-AUDIT.md): both entity and connection tick entrances reach mutable player state. A useful future collision/environment preparation needs immutable inputs, original packet/identity/lifecycle epochs, cross-region completeness and authoritative revalidation/commit. Existing AABB snapshot intersection is not newly parallelized player ticking.

New generation determinism/cancellation/hash comparisons, new loading reconstruction/POI/light/callback parity tests, multiplayer movement/packet/inventory/combat/lifecycle tests and throughput benchmarks for expanded paths are **unprocessed**, because those paths are not implemented. The stationary fixture cannot provide player-main-thread time or new generation/load throughput; these remain N/A.

## Separate assessments

| Objective | Status | Evidence and remaining limitation |
|---|---|---|
| Existing MSPT optimization | **CONDITIONAL** | Large measured canonical furnace regression removed; same completed work, lower CPU/allocation; still slower than stock on this light fixture. Final Persistent selected GameTests and one mixed soak retain failed correctness gates |
| Chunk generation parallelization | **NOT YET** | Complete stage matrix; existing vanilla futures retained; no additional Forgia stage admitted or determinism suite for it |
| Chunk loading parallelization | **NOT YET** | Complete pipeline audit/plan; existing I/O/upgrade workers retained; no new pure preparation/commit implementation |
| Player worker-side computation | **NOT YET** | Current snapshot queries audited; no new packet/lifecycle-epoch preparation result path |
| Full player tick parallelization | **UNSAFE** under current contract | Direct worker move violates live packet/player/world/callback ownership; constrained future pure preparation remains possible |
| Region-level runtime diagnostics | **CONDITIONAL** | Working accurate instrumented metrics, rankings, pagination and lifecycle; complete region cost/workload coverage is explicitly N/A |
| Worker and scheduler diagnostics | **CONDITIONAL** | Real task/queue/CPU/wall/failure/join and canonical counters validated; active resource/ticket/commit-wait metrics remain N/A |
| Historical performance monitoring | **PASS** within documented bounds | Exact bounded1/10/60/300wall-second history, capacity reporting, disabled sampling and controlled percentile/overhead checks |

No result here promotes unsupported mod hot-unload or broad modpack compatibility. The next performance work should target remaining proof/prefix planner cost using the equivalent fixture, then extract safe pure chunk/player preparation contracts only with their missing ownership, lifecycle and determinism evidence.
