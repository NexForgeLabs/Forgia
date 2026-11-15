# Controlled canary procedure

**Preparation only.** Repository acceptance is preserved; an installed canary with its exact pack, world,
JVM, monitoring and rollback target has not been certified by this audit. See [readiness](PRODUCTION-READINESS.md)
and the [configuration matrix](PRODUCTION-CONFIGURATION.md). This procedure introduces no C7 or Worker adapter.

## Candidate choice

Use a disposable **copy** of the intended server first. Preserve the original stopped save and every Mod,
datapack, script, config and library. Only one JVM may open a world directory at a time. A running filesystem
copy is not an independently verified save. Restore the backup into a different directory and boot it to
prove that the recovery input is usable before inviting canary players.

Start with [serial.toml](../tools/production-readiness/config/serial.toml): bridge and all slices OFF, no
optional native service. This establishes same-build gameplay, persistence, monitoring and stop behavior.
Use the existing known-good production installation as the rollback target; record its actual hashes and
launcher, rather than assuming an arbitrary upstream NeoForge release can read every pack/API combination.
Keep all upstream `removeErroring*` options false so a disappearing subject cannot look like recovery.

For the first Worker rehearsal, [c6-fixed.toml](../tools/production-readiness/config/c6-fixed.toml) preserves
the final C6 settings: four Fixed workers, 3×3 chunks, WARN, resources/Furnace/entity/POI/chunk/scheduled ON;
spawning/global-worker/border/game-events/packed/force OFF. Its optional service is a separate launch choice:

- Service absent: Mod BEs stay serial. This is a conservative compatibility candidate supported by Java
  controls, **not** a repetition of C6's native-service profile or a claim of C6 Mod Worker execution.
- Service present: reproduce the restrictive C6 VM conditions and verify the **installed** Core/bridge/native
  combination and actual original Worker completions. General native-dependent packs may fail before gameplay.
  Keep this stage in isolation until the full startup and monitoring path succeeds.

The matching [Persistent template](../tools/production-readiness/config/c6-persistent.toml) changes one key.
Test it in a separate run after Fixed passes. Both pools have correctness and stopped-server GC evidence;
no performance advantage selects a default. Changing pool, geometry, count, collector or resources creates
a new target configuration and requires its own rehearsal. Resource ownership remains default-off in the
product; enabling it to reproduce C6 is an explicit acceptance-profile choice with known cost.

C6 has game-event and border capture OFF. Some enabled entity/chunk callbacks can still reach foreign sculk
listeners with game-event capture OFF; arbitrary pack callbacks also retain unproved ownership behavior.
Rehearse these paths explicitly. If the target requires a switch or cohort outside C6, record a distinct
candidate and its evidence. Do not call a broader configuration C6-certified or bypass a refusal with force,
`admit_block_entities`, a different manifest or a replacement adapter.

## Manifest and admission before launch

Record these in a private, immutable run directory. Missing data is a failed gate, never a zero value.

| Input / gate | Required evidence |
|---|---|
| Source | Fetch output, checkout SHA, status and successful `audit.py` result; frozen C6/lifecycle documents retained. |
| Build/install | Exact installer/universal and installed runtime library hashes, build flags/version, installation log and generated launcher/argfiles. A file named `.60-beta` alone does not bind it to C6. Build in a separate output/checkout to preserve retained evidence. |
| VM/OS | Java executable and version, architecture/OS/libc, JVM flags, collector, heap, CPU quota/count, memory limit, disk headroom. Pin the same values for comparisons. |
| Optional service | Native `.so` and exact verified-Core JAR hashes; one absolute `-agentpath:...=...`; all C6 VM restrictions satisfied. Never copy the ordinary bridge jar into mods and call it installed evidence. |
| Pack | SHA256 inventory of every Mod and nested dependency, configs, KubeJS/scripts, datapacks and declarations; versions of Create/Connector/FML and other transformations. Review known conflicts and historical upstream API drift. |
| Configuration | Instance and selected-world `forgia.toml`, legacy migration inputs, `defaultconfigs`, declaration resources, effective `neoforge-server.toml`, server.properties, launcher/environment arguments. No ignored/corrected config or declaration may go unexplained. |
| Data and recovery | Offline backup id/hash or storage snapshot id, tested restore location, known-good launcher/config/binaries, supported recovery point and accepted rollback data-loss window. Include external Mod databases and scripts. |
| Exposure | Named operator, whitelist, maximum concurrent users and allowed gameplay area/dimensions, start/end window and stop criteria. Keep initial copied-server ports private; isolate RCON and credentials. |
| Observability | Prove how raw command responses, logs, real tick latencies, process memory, GC and thread state are collected on this exact JVM **before** the run. Native attach restrictions must not be discovered during an incident. |

A source audit does not build, install, accept an EULA, change server configs, contact users or launch a server.
Installation/rehearsal is a later concrete action against an identified isolated target. Never point canonical
GameTest preparation at a retained or production world: it deliberately resets its selected test world.

Optional service example for an **isolated** installed server, after selecting matching immutable artifacts:

```text
-XX:+DisableAttachMechanism
-XX:+UnlockDiagnosticVMOptions
-XX:-InlineUnsafeOps
-XX:+UseG1GC
-XX:+UseCompressedOops
-XX:+UseCompressedClassPointers
-agentpath:/absolute/candidate/libforgia_runtime_proof.so=/absolute/candidate/matching-verified-core.jar
-Xlog:gc*:file=/absolute/new-run/gc.log:time,uptime,level,tags
```

Pin heap separately. Do not inherit ZGC recommendations from historical non-service benchmarks: compressed
pointers and G1 were part of the accepted C6 launches. Extra Java/native agents, preview/native-access flags,
JVMCI/WhiteBox and unknown JNI/JNA libraries are outside this service profile. A bad requested startup agent
can abort the JVM; refusing Mod admission does not uninstall native hooks. Verify expected service log markers
and actual completions, not merely a successful process start.

## Rehearsal and bounded exposure

The durations below are proposed operator gates, not historical passed tests. Run sequentially without builds
or competing load tests; preserve failures in their own output directories. Compare equal warmup/load windows
from identical restored worlds and fixed artifacts. An OFF/ON/OFF sequence helps distinguish host drift.

| Stage | Proposed scope | Exit gate |
|---|---|---|
| Serial baseline | At least 30 minutes on copied target world/pack, representative automated or manual activity, no service. | Baseline full-tick percentiles, memory/GC, gameplay counters, actual save and clean stop captured; restore test passes. |
| Fixed rehearsal | At least 60 minutes, exact chosen config/service, initial no-player setup then representative workload. | Requested **effective** slices agree with plan; relevant actual completions advance; zero unexplained failure/loss counters; invariants and save/reload pass. |
| Compatibility exercises | Machine and inventory pipelines; recipes/fuel/notification boundaries; chunk unload/reload; cross-region redstone/sculk; portals/dimensions; player riding/teleport and projectile behavior; Mod-specific I/O/native features. | Original item/entity/POI/ticket invariants and gameplay outcomes checked. Serial fences count as expected containment, not parallel coverage. |
| Rollback drill | Clean save/stop, copy restored into known-good installation; boot with explicit serial config and no native service. | Backup/rollback world opens, invariants hold, client reconnect works, no duplicate UUID or missing Mod data, clean second save/stop. |
| Persistent comparison | Separate fresh restored copy, only pool flag differs. | Same correctness/recovery gates; report latency/allocation without assuming speedup. Optional for initial Fixed deployment. |
| First real-server window | Only after rehearsal/rollback/monitoring gates pass; proposed whitelist of 1–2 users, 30 minutes, operator present. | No stop signal; close with save/stop and post-run review before another window. Never auto-expand from a green dashboard. |
| Longer follow-up | Proposed 2-hour then 6-hour supervised windows, separately recorded. | Evidence for each window, representative activity and persisted-state check. Long soak and multiplayer remain unverified until actually executed. |

Use workloads that exercise the slices being claimed. A flat force-loaded world can leave chunk/spawn counters
at zero. Fake players and short boot tests do not certify network clients or multiplayer concurrency. A full
canonical PASS does not exercise the ten opt-in soak/benchmark/hard-kill workloads that were skipped.

## Observation and stop criteria

Capture console/RCON responses at startup, after warmup, every 60 seconds during the small canary, before save,
and before stop. Compare **deltas per process and window**; never reset cumulative counters to conceal a failure.
Commands require permission level 2 (console needs no leading slash):

```text
neoforge regionthreading status raw
neoforge regionthreading compat
neoforge regionthreading regions 10
neoforge regionthreading workgraph
```

Retain the complete multi-line raw response. Human `status` hides zero values; it is insufficient for a parser.
`workgraph` without `start` reads metrics. Optional `workgraph start 20` samples a bounded partial shadow graph
and adds work; record it as a diagnostic interval. It grants no admission and is not a throughput estimate.

| Signal | Interpretation / action |
|---|---|
| `workerExecution`, effective slice flags, actual entity/BE/chunk/scheduled completions and `Region worker dispatch` pool | Confirm execution plan and positive workload coverage. Queue submissions, Claims/leases, declarations and readiness booleans are not body completions. Unexpected zero completions blocks promotion. |
| `ownershipViolationCount`, `tickFailures`, `failedQueuedTasks`, nested slice failure counters, worker storage/native access exceptions | Any new unplanned value/failure stops the canary. The normal server has no C6 deliberate-failure allowance. Preserve first cause and suppressed exceptions. |
| `truncatedEntityTicks`, `truncatedChunkTicks`, `truncatedSpawnChunks`, refused additions/interactions, dropped deferred records and unknown unsafe-read/write refusals | Any unexplained new work loss/truncation stops the canary. A prevented illegal write does not prove the ticker completed correctly. |
| Expected serial/fallback reasons | Normally valid containment. Establish per-workload baseline; investigate unexpected changes and zero Worker coverage. An unavailable service can leave a healthy-looking fully serial server. |
| Deferred/mailbox/transfer/POI state, save-point diagnostics | Persistent growing backlog blocks promotion. Save with batch in flight or uncommitted per-tick work is a stop signal. Cross-tick deferred work is distinct and can be lost on crash. |
| Whole-server mean/p50/p95/p99/max MSPT, TPS, workload counts | Measure actual ticks including prepare/barrier/commit. Proposed stop: mean >50 ms for 60 s, or three consecutive comparable windows with mean or p95 >20% above baseline. Establish a tighter service objective before real users if needed. |
| RSS, heap/GC, disk, threads, save/stop duration | Retain startup/warmup baselines and host limits. Monotonic retained growth across comparable cycles, OOM, disk/save errors or extra/orphaned workers blocks promotion. Raw RSS is not heap usage and GC timing is not leak certification. |
| Item/entity/POI conservation and visible gameplay | Any unexplained missing/duplicated items, UUIDs, tickets, stale inventories or wrong dimension/teleport stops immediately. Safety counters at zero are insufficient evidence of gameplay equivalence. |

The command does **not** export every internal diagnostic: `RegionThreadingDiagnostics.saveCounters` and some
canonical Mod/kernel details are available through Java/internal proof fixtures but are absent from normal
`status raw`. This is a known monitoring gap, not a field to fill with zero. Validate durable invariants through
save/restart inspection and original production save-warning logs, and require a compatible observer if stronger
live evidence is necessary. There is no built-in Prometheus export or canary supervisor in this package.

C6 disables attach: plan `/proc/<pid>/status`, OS CPU/RSS/disk monitoring, startup GC logs and compatible
in-process/console observations. On tested Linux HotSpot, rehearse a targeted SIGQUIT/`kill -3 <exact-server-pid>`
thread dump to captured stderr on the isolated JVM; do not depend on untested attach/JMX tooling. In-process
native diagnostic commands have a narrow allowlist. Heap histograms/dumps and full graph sampling can pause
or perturb the process; keep diagnostic intervals distinct from performance measurements. Never enable an
unverified agent during a live incident to get measurements.

## Stop and rollback

1. Close admission to new players/actions and retain the last diagnostics, log and exact process identity.
   If the process responds, issue normal `save-all flush`, retain acknowledgement, then `stop` and wait for
   actual process exit. A timeout, sent command or empty socket is not a clean-stop receipt.
2. A Worker that never returns can block shutdown intentionally: authority cannot be released while it still
   writes. Proposed incident threshold is 120 seconds without shutdown progress. Capture threads/process state,
   escalate to the operator's forced-termination procedure only for the exact server process, and record an
   unclean stop. Never launch the rollback JVM on that world until the old JVM has actually exited.
3. Preserve the failed run's world/logs/crash/GC/config/artifacts read-only. For suspected corruption, restore
   the tested pre-canary snapshot into a different directory. Retaining post-canary player progress is safe
   only after validating the saved state; record the chosen recovery point and lost window.
4. Restore the complete known-good binary/library/Mod/script/config combination. For same-build serial rollback,
   explicitly apply every value in `serial.toml` to the effective instance/world config and remove the startup
   `-agentpath` plus its service-only options as recorded in the known-good launcher. Inspect world overrides
   and legacy migration inputs. Setting only `enableRegionThreadingWorkers=false` is insufficient; deleting
   `forgia.toml` can restore old enabled keys. Native interception survives until JVM exit.
5. Start a new JVM with a single owner of the restored world. Confirm bridge absent, no Forgia workers and no
   native service; check original counts/state, clients, Mod data, a flushing save and a second clean stop.
   For upstream NeoForge rollback, use the previously rehearsed full pack/version installation: disabling
   Forgia does not supply newer NeoForge APIs or unload a Mod. Do not remove Mods and reopen their only save.
6. Record real start/exit codes, old/new artifact/config hashes, recovery point, diagnosis and rollout verdict.
   Failed, aborted, skipped and unsupported tests retain those labels. A successful restart is not evidence
   that the original failed ticker was undone or that hot-unload became supported.

A go decision needs an identified host/pack/world and filled manifest, passing installed rehearsal, proven
monitoring and successful restore/rollback drill. The repository audit provides none of those deployment
results automatically. Runtime Mod hot-unload, same-JVM server reload and a second Worker adapter remain
outside this canary plan.
