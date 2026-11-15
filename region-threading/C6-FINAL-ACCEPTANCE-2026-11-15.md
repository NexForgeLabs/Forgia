# C6 Final Acceptance — 2026-11-15

`C6 FINAL ARCHITECTURE ACCEPTANCE: PASS`

Both final complete, unfiltered Persistent and Fixed profiles have genuinely completed on the unchanged production source. No unresolved scenario failures remain. Logical skips, framework Not Processed registrations, historical failures and the separately scoped coverage limitations remain explicit below.

## Final complete unfiltered profile pair

Preflight verified clean branch `main` at `afa7e4bc781257a756a3d0f6040cc64eebc84d62`. Both runs used that exact source HEAD. No production, test, batch, assertion, selection or opt-in gate was changed. Production still corresponds to `0ede200610867aea46d5473c7f6ec4fccdd063e0`; test sources still correspond to `39352097ffef918c91ba82298dd2899b6dac8b94`. Subsequent changes in this continuation are documentation only.

Both complete canonical registrations contain **74 batches / 180 GameTest functions**. Their runtime membership matches each other and the preserved complete recovery A; the failure-injection batch remains last with the existing membership assertion. Both tracked input inventories were identical before and after execution. Production fingerprint `f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787` matches all previously recorded final-source associated runs. All 95 historical receipt references were checksum-verified; no prior evidence was invalidated or repeated for its own sake.

Persistent ran 2026-11-15 **22:06:44–22:21:11 CST**; Fixed ran **22:21:59–22:35:57 CST**. The first server and Gradle client had exited before Fixed launched. Both servers naturally completed world saving and test-summary/XML emission; both actual JavaExec results and Gradle client exits are **0**.

| GameTest function population | Passed | Failed | Skipped | Not Processed | Aborted | Gradle exit | Server exit |
|---|---:|---:|---:|---:|---:|---:|---:|
| Persistent — 180 completed |170|0|10|0|0|0|0|
| Fixed — 180 completed |170|0|10|0|0|0|0|

| Distinct framework ID population | Passed | Failed | Skipped | Not Processed | Aborted |
|---|---:|---:|---:|---:|---:|
| Persistent — 182 registered IDs |168|0|10|4|0|
| Fixed — 182 registered IDs |168|0|10|4|0|

These are overlapping, different populations; do not add them. Each raw tracker says “All 180 required tests passed”, but ten bodies explicitly report disabled opt-in workloads and call `helper.succeed()`. Acceptance excludes those ten from Passed. The distinct framework raw count is 178 Passed / 4 Not Processed; subtracting the same ten logical skips gives 168 Passed / 10 Skipped / 4 Not Processed. Each raw XML contains 234 duplicate group rows, zero failure tags and four skipped tags; those tags denote framework Not Processed, not ten executed workload skips.

The ten unchanged logical skips are production soak, hard-kill recovery and eight benchmark/diagnostic/entity-soak workloads (random ticks, pistons, hoppers, entity churn, entity-worker diagnostics, entity-worker soak, furnaces and spawning). Their `serverSoak`, `crashRecovery` and `serverBenchmark` gates are absent in this canonical profile by definition. Exact test IDs, gates and skip diagnostics are retained independently per profile in the receipt. None is certified by this PASS.

The four unchanged Not Processed IDs are `testAttachmentSyncManual`, `customBoatType`, `extendPoiTypeTest` and `testFeatureFlagPacks`. Their sources define manual/client, registration or lifecycle-event checks without a required canonical GameTest body. They remain unprocessed coverage, never passed coverage. All 180 required canonical GameTest functions completed; no required C6 scenario failed, aborted or remained unprocessed.

Both profiles passed original-Mod Worker/Claims/Lease, ten foreign-writer refusals with counter delta0, physical reconstruction of25 chunks/13 BEs/9 entities, and failure recovery without whole-ticker Serial Replay. Full-run original body/counter deltas were83 =72 Worker+11 Serial for Persistent and82 =72 Worker+10 Serial for Fixed, each with overlap2. These natural full-run values are distinct from the preserved associated-run148 result. Both retain exact expected-failure diagnostics0→1→1 without resets.

### Exact commands and effective configuration

Environment: `JAVA_HOME=/home/yc/.local/opt/jdk-21.0.13+9`, with its `bin` first on `PATH`; Temurin21.0.13+9. No inherited JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, JAVA_OPTS, GRADLE_OPTS or gameTestFilter property was present. Commands, from the repository root:

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tools/runtime-proof/real_mod.gradle -I tmp/c6-profile-pair-20261115-2205/observe.gradle -PtrustedModJar=/home/yc/Minecraft/SkyExplorer-forgia/mods/game_console-0.31.0-alpha.61.jar -PtrustedModRun=tmp/c6-profile-pair-20261115-2205/persistent-r2/server -PcanonicalPersistent=true :tests:runCanonicalGameTestServer
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tools/runtime-proof/real_mod.gradle -I tmp/c6-profile-pair-20261115-2205/observe.gradle -PtrustedModJar=/home/yc/Minecraft/SkyExplorer-forgia/mods/game_console-0.31.0-alpha.61.jar -PtrustedModRun=tmp/c6-profile-pair-20261115-2205/fixed/server -PcanonicalPersistent=false :tests:runCanonicalGameTestServer
```

`observe.gradle` only reads launch/execution metadata and records the real JavaExec exit; it does not change filtering, JVM arguments, assertions or exit handling. Actual `/proc` server commands and generated VM/program argument files are preserved in each run directory, because the run task finalizes some launch arguments after a `doFirst` observer.

Both effective `forgia.toml` files enable bridge, Workers, block-entity ticks, Furnace work waves, resource ownership, entity ticks, random ticks, POI and scheduled ticks; use FIXED_3X3,3 chunks,4 workers, ownership violation WARN; and keep spawning, border updates, game events and packed leases off. `enableRegionThreadingPersistentWorkers=true/false` is their **only** config difference. Resources remain ON in Fixed as required by the existing original-Mod profile. The sole startup native agent, disabled attach/Unsafe intrinsic inlining, G1 and compressed object/class pointers are unchanged. The exact complete configs, argument files, upstream input SHA, native library and verified-Core jar hashes are retained.

### Raw evidence and retained infrastructure attempt

| Profile | Raw log | XML | Command/source/exit receipt |
|---|---|---|---|
| Persistent | [output.log](../tmp/c6-profile-pair-20261115-2205/persistent-r2/output.log) | [testframework-neotests-tests-20261115142108.junit.xml](../tmp/c6-profile-pair-20261115-2205/persistent-r2/server/tests/testframework-neotests-tests-20261115142108.junit.xml) | [result.json](../tmp/c6-profile-pair-20261115-2205/persistent-r2/result.json) |
| Fixed | [output.log](../tmp/c6-profile-pair-20261115-2205/fixed/output.log) | [testframework-neotests-tests-20261115143555.junit.xml](../tmp/c6-profile-pair-20261115-2205/fixed/server/tests/testframework-neotests-tests-20261115143555.junit.xml) | [result.json](../tmp/c6-profile-pair-20261115-2205/fixed/result.json) |

The [pair audit](../tmp/c6-profile-pair-20261115-2205/pair-acceptance-audit.json), per-run `analysis.json`, `server-execution.json`, live process captures and generated arguments bind the independent counts to the commands/source/artifacts. The machine receipt embeds their hashes and complete configurations. No private upstream jar, world, log or native binary is committed.

An initial evidence-observer setup attempt at22:05:52 CST failed before server launch: Gradle exit1, server exit unavailable (no server), zero executed GameTests. Its observer attempted to access `:tests` inside the included build where that project is absent. The private observer now guards an absent project, and Persistent was started from the beginning in a new directory. The original [log](../tmp/c6-profile-pair-20261115-2205/persistent/output.log), [receipt](../tmp/c6-profile-pair-20261115-2205/persistent/result.json) and failed observer source are preserved. This is one aborted **launch attempt**, not a failed test ID or an aborted scenario in either completed profile. There was no production/test-fixture fix and no repository code commit.

Whole-server real-Level GC and independent real runtime Mod hot-unload certification remain unverified. The existing shutdown-Engine/mocked-Level weak-owner unit controls, Level clear/shutdown controls, binding preflight and VM components retain their stated limits. This PASS covers the documented supported Linux/HotSpot21 C6 path and unchanged complete canonical profile pair; it adds no general Modpack, native FFI or production deployment claim.

## Historical recovery scene and provenance

- Handoff HEAD: `e136ff8bad96475e2402b3c8c10a549d9d13d7bb`; tracked/untracked status was clean. The supplied implementation checkpoint was `5f47e4ad126444f2ff8825a4ee4d98e8c0a0a855`.
- No GameTest/VM test was still running at handoff. PID437086 was the JDK21 Gradle8.13 daemon, not a final test. Original private evidence under `tmp/c6-final/` and `tmp/be-mod-worker-mission/` was retained. No reset/clean or Java-process termination was performed.
- The previous agent's last complete `tmp/c6-final/final-A` run completed and failed: 180 executed GameTests, one required save/reload fresh-instance failure. Its last test afterward was the accessor-name red control, JVM exit1. Neither is current-code acceptance evidence. A different earlier full-profile attempt ended with server exit143 and is aborted evidence.
- Recovery `tmp/c6-recovery-20261115/profile-A` completed naturally: raw tracker179/180 successful, one failed, wrapper/server exit1. Ten of the raw successes are explicit disabled-scenario skips, so acceptance accounting is169 Passed,1 Failed,10 Skipped. Its disk reconstruction passed (25 chunks,13 BEs,9 entities), but the unchanged global zero-failure assertion detected the earlier deliberately injected failure. That run used an earlier native binary and is superseded, never relabeled PASS.
- Final production revision: `0ede20061` (native publication and repeated-binding fixes). Validated test-source revision: `39352097ffef918c91ba82298dd2899b6dac8b94`. Evidence/source hashes bind runs taken before the final documentation commit to identical implementation/test inputs; documentation-only changes are not new test results.
- JDK: Eclipse Temurin21.0.13+9, Linux; NeoForge/Minecraft1.21.1; local Forgia version21.1.250.60-beta. Real-Mod profile: sole startup agent, attach disabled, Unsafe intrinsic inlining disabled, G1/compressed object and class pointers, resource ownership enabled, fixed3×3 regions and4 workers. Persistent workers are enabled for the four integration cases; isolated lifecycle/binding controls use the existing fixed executor.
- Original `piq_fc_arcade`0.31.0-alpha.61 input SHA256: `d4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510`. It is loaded unchanged by the normal loader; no upstream artifact/bytecode is committed. Its hash identifies an input, not Worker authority.

## What changed and why

1. `root_count` is atomic because first-root fast paths also run outside the registration monitor. Native binding metadata is serialized; the rebinding recursion flag is thread-local. Repeated stable bindings reuse the recorded wrapper and never rewrite a published original function pointer. These are VM interception metadata protections, not an additional resource authority or scheduler.
2. Standalone controls retain accessor-before-effects, concurrent root registration, actual JNI state, nested callbacks and frozen values. New test-only agents inject malformed evidence and repeat real binding observations. The production service exposes neither injection nor mutation helpers.
3. The opt-in GameTest subscriber stably places the deliberate failure batch last and asserts unchanged batch membership. No test is filtered out of an unfiltered run, no assertion is relaxed and no diagnostic counter is reset. The failure test now requires totals0 before injection,1 afterward and still1 after fresh recovery.
4. Added Core weak-owner/default-off unit controls and isolated original-Mod lifecycle/binding tests. Lifecycle decorators submit to the same existing executor and use actual original Region guards; they mint no authority. Binding controls invoke Core preflight against original objects, not a replacement ticker or a fabricated Permit.

## Evidence layers

| Layer | Result | Scope |
|---|---|---|
| Implemented | Yes | Optional installed-code evidence, enforced storage boundary and existing Core authority; publication races repaired |
| Unit tests | 419/419 original selected Core regressions;3/3 new weak-owner/default-off controls | The419-case receipt is reused for unchanged Java authority code with the optional service absent; the3 new tests separately exercise the actual Core callback class |
| VM component controls | 28 command steps:18 runtime controls and10 build/preparation steps; all exit0 | Includes96 invalid evidence cases,33 concurrent retained roots, actual JNI writes/calls platform/virtual-thread writers and duplicate binding observations; no production Core Mod permission issued |
| Original-Mod integration | 4/4 Passed,0 Failed/Skipped/Aborted, exit0 | Four selected GameTests: positive Worker, foreign writers, failure recovery and physical save/reload |
| Original-Mod lifecycle | 6/6 Passed,0 Failed/Skipped/Aborted; each process exit0 | Queued/running/releasing × Level clear/shutdown; six fresh isolated worlds |
| Original-object binding preflight | 1/1 Passed,0 Failed/Skipped/Aborted, exit0 | Same-delegate rebind, replacement, inconsistent Level, actual frozen coordinates and foreign callback replacement; preflight proof only, not another Worker E2E result |
| Complete GameTest acceptance | **PASS: both final complete unfiltered profiles** | Each:170 Passed /0 Failed /10 logical Skipped /0 GameTest Not Processed /0 Aborted; framework registrations separately168 Passed /10 Skipped /4 Not Processed; actual Gradle/server exits0. Older failed/aborted runs remain failures. |
| Architecture source review | Completed within the documented supported-VM/counter-closure scope | Does not replace the independently completed full-profile pair; boundaries unchanged |

Previously recorded associated-run original-Mod execution (retained unchanged; the final full-run values are above):

`C6_FIRST_ORIGINAL_MOD_WORKERS sources=2 differentRegions=true completeBodies=148 counterDelta=148 actualWorkerBodies=72 serialMaintenanceBodies=76 overlap=2 originalKernel=true realResourceScope=true noReplacementTicker=true`

`C6_REAL_MOD_LEASE originalClaimsIdentity=true foreignEnterRefused=true foreignScopeCloseRefused=true foreignOwnerCloseRefused=true afterJoinReenterRefused=true duplicateReleaseRefused=true originalTicketSucceededAndRetired=true proofReferencesCleared=true`

The complete original bodies and original counter delta must agree; Worker+Serial must equal complete bodies. The observer runs after production admission, checks the original Kernel/Ticket/Claims/Lease/Scope identities and uses latches for actual overlap of two Regions. These numbers come from the new final-source run, not the historical76 Worker/4 Serial pilot.

Writer evidence:

`C6_REAL_MOD_WRITER_EXCLUSION actualWorkerPermit=true actualResourceScope=true foreignAuthority=false rejectedRoutes=10 foreignCounterDelta=0 originalBodyCounterDelta=1 physicalActorExit=true noReplay=true`

The component JNI control checks actual state **before** the authorized positive control: protected value0 and earlier-effects0. Its authorized field/method controls subsequently produce7/991 and one earlier effect. The nested callback and accessor controls likewise retain protected value0/earlier-effects0. Native exceptions alone are never treated as proof that a store was blocked. Actual JNI/nested controls are identified as components; the real-Mod writer test separately holds actual Core authority and checks its real counter.

Failure/cleanup evidence:

`C6_REAL_MOD_FAILURE_RECOVERY failedOriginalBody=1 failedCounterDelta=1 suffixAfterFailure=0 freshOriginalWorkerBodies=2 freshCounterDelta=2 suffixInFreshPhase=1 sameRealLevel=true noReplay=true`

`C6_EXPECTED_FAILURE_ACCOUNTING before=0 after=1 afterFresh=1 exactInjectedFailure=true countersNeverReset=true`

The first started body is not replayed, its effect remains once, the unstarted suffix remains0, and a fresh same-Level phase executes two original Worker bodies before its one serial suffix. Physical join precedes guard sealing, resource release and outer Region release.

## Lifecycle and identity coverage

| State / lifecycle | Worker / Serial | Original counter delta | Wrong-Region refusals | External refusals | Exit |
|---|---:|---:|---:|---:|---:|
| queued-unload |2 / 0|2|4|4|0|
| queued-shutdown |2 / 0|2|4|4|0|
| running-unload |2 / 0|2|4|4|0|
| running-shutdown |2 / 0|2|4|4|0|
| releasing-unload |2 / 0|2|4|4|0|
| releasing-shutdown |2 / 0|2|4|4|0|

Each lifecycle run checks two actual original bodies/counter delta2; four outside-thread method/field refusals before/during cleanup and four wrong-Region method/field refusals from real Workers. A latch completes both cross-Region attempts before either authorized increment, preventing authorized writes from masking an illicit store. Cleanup is observed inside the actual published-phase wait. Releasing controls pause actual guard sealing after original tasks return, using their captured original Region guards.

The binding case uses a Core-generated manifest against actual VM-observed original objects. It invalidates the old frame even when rebind retains the same delegate, replaces the block through normal Level APIs, rejects inconsistent actual Level and refuses replacement access callbacks for mutable and frozen roots. Frozen coordinates are compared with separately captured primitive x/y/z values, not an aliased BlockPos reference. Preparation has counter delta0. Alias/domain/inconsistent-home normalization remains covered by the unchanged resource-ownership unit cases.

Weak-owner coverage strongly retains the actual Core callback while a real shutdown Engine and an unstubbed mocked Level are collected. It checks original-thread serial compatibility, foreign read/write denial and refusal while a home is running or any resource Scope is active. This is a unit retention/authority test, not a claim that a live MinecraftServer's world map was garbage-collected.

## Complete profiles and status accounting

| Run | Executed GameTests | Passed | Failed | Skipped | Not Processed | Aborted | Exit |
|---|---:|---:|---:|---:|---|---:|---|
| Previous agent `tmp/c6-final/final-A` |180|169|1|10|4 framework entries separately|0|server1; original wrapper receipt absent|
| Previous agent `final-profile-a` |Incomplete|Unknown|Unknown|Unknown|Unknown|1|server143|
| Recovery old-native `profile-A` |180|169|1|10|4 framework entries separately|0|1|
| Final-source full persistent profile |180|170|0|10|0 GameTests;4 framework entries separately|0|Gradle0; server0|
| Final-source full fixed profile |180|170|0|10|0 GameTests;4 framework entries separately|0|Gradle0; server0|

The raw GameTest tracker reports179 successful functions for each complete failed A run, but10 explicitly log skipped scenarios because serverSoak, serverBenchmark or crashRecovery options are absent. Those10 are excluded from the Passed column: production/entity soak, hard-kill recovery and seven benchmark/diagnostic workloads were not exercised. This is acceptance accounting, not a change to the tests or their recorded status.

The recovery full-A raw framework XML has234 group rows,229 rows without failure/skip tags,1 failure and4 skipped tags. It contains duplicate group memberships: distinct framework test IDs are177 Passed,1 Failed,4 Not Processed. The four Not Processed IDs are `testAttachmentSyncManual`, `customBoatType`, `extendPoiTypeTest`, `testFeatureFlagPacks`. They are never counted as passed. The180 GameTest functions and distinct framework IDs are different populations; neither XML row count nor process exit alone establishes full C6 acceptance. Final selected runs retain their filter and their unprocessed registration rows; they are only selected-case results.

## Architecture review

- Core alone creates the closure manifest and attaches it to the original unstarted INSPECTED Ticket. Mod id/hash/class name, v1 declarations and Adapter audit strings confer no Worker permission.
- `RegionThreadingEngine` continues through the same canonical coordinator, planner, all-or-none Claims reservation, one-shot Lease, thread-bound Scope, worker pool and invocation Kernel. Actual exact Claims identity is required by the original Ticket.
- Unknown/periodic/uncertified paths and Create remain complete Serial Fences. Single-unit Mod prefixes remain serial. Native/Furnace admission and v1 public descriptors are unchanged by these fixes.
- The counter-return closure performs no world/provider/event/effect commit. Unknown effect paths have no speculative grant. There is no second effect queue or whole-ticker serial replay after a started Worker body.
- Tasks physically join before guard sealing, ownership closure and Region release. Level unload/shutdown waits on the published phase; native interception consults that authority and cannot reserve or release it.
- The service remains opt-in/default-off. The new JUnit-only bridge dependency does not install a bootstrap/native service; its explicit control verifies Core admission remains unavailable. No production deployment, second Worker-enabled Adapter or C7 occurred.
- The supported Linux/HotSpot21 profile deliberately excludes arbitrary JNI/JNA libraries, attach, redefinition and native FFI downcalls. These results do not certify arbitrary hostile JVM/Core memory modification or general Modpack compatibility.

## Retained failures and coverage limits

Original red/failed/aborted evidence is retained. The first two weak-GC attempts retained the broad mocked invocation fixture; they failed the unchanged collection assertion. Exact failed source reconstructions match their recorded SHA256. Isolating the real Engine/callback from fixture observer/stubbing closures passed without changing production weak-owner logic. The first binding run aborted on `InternalError` wrapping an expected frozen-write refusal; its crash/log/source are retained. The repaired fixture unwraps only recognized reflection wrappers and strengthens the actual-coordinate comparison.

The final complete profile pair resolves the outstanding canonical GameTest gate and establishes C6 PASS within the stated supported scope. Lifecycle/source-binding/weak-owner evidence remains explicitly scoped above; runtime Mod hot-unload and whole-server real-Level GC are not independently claimed by these tests. Opt-in workloads and the four Not Processed framework registrations remain coverage limitations. Component JNI/nested evidence must not be presented as a separate real-Mod Worker E2E run.

Machine receipt: [C6 recovery receipt](C6-FINAL-ACCEPTANCE-2026-11-15.json). Recovery scene: [recovery report](C6-RECOVERY-2026-11-15.md). Private raw evidence remains at `tmp/c6-recovery-20261115/` and `tmp/c6-profile-pair-20261115-2205/` with immutable per-run commands, Git SHA/dirty-source hashes, JDK identity, artifacts, configs, raw logs/XML, real exits and retained failures. No private jar, world, crash report or native binary is committed.
