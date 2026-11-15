# C6 Final Acceptance recovery — 2026-11-15

`C6 FINAL ARCHITECTURE ACCEPTANCE: NOT ACCEPTED`

The operator narrowed continuation to fixes and associated tests after recovery Profile A was already running. No new full Profile A/B was started afterward. Passing the associated tests below does not satisfy the outstanding requirement for two complete, unfiltered profiles on the final implementation.

## Scene and provenance

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
| Complete GameTest acceptance | **Not satisfied** | The final-code full pair was cancelled when the operator narrowed reruns; older failed/aborted profiles remain failures |
| Architecture source review | Completed within the documented supported-VM/counter-closure scope | Does not override the missing full-profile gate |

Latest final-source original-Mod execution:

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
| Final-source full persistent profile |0|0|0|0|Not run|0|No process; cancelled before launch|
| Final-source full fixed profile |0|0|0|0|Not run|0|No process; cancelled before launch|

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

## Retained failures and remaining gates

Original red/failed/aborted evidence is retained. The first two weak-GC attempts retained the broad mocked invocation fixture; they failed the unchanged collection assertion. Exact failed source reconstructions match their recorded SHA256. Isolating the real Engine/callback from fixture observer/stubbing closures passed without changing production weak-owner logic. The first binding run aborted on `InternalError` wrapping an expected frozen-write refusal; its crash/log/source are retained. The repaired fixture unwraps only recognized reflection wrappers and strengthens the actual-coordinate comparison.

Formal C6 acceptance remains blocked by the two complete final-code unfiltered GameTest profiles. Lifecycle/source-binding/weak-owner evidence is explicitly scoped above; runtime Mod hot-unload and whole-server real-Level GC are not independently claimed by these tests. Component JNI/nested evidence must not be presented as a separate real-Mod Worker E2E run.

Machine receipt: [C6 recovery receipt](C6-FINAL-ACCEPTANCE-2026-11-15.json). Recovery scene: [recovery report](C6-RECOVERY-2026-11-15.md). Private raw evidence remains at `tmp/c6-recovery-20261115/` with immutable per-run commands, Git SHA/dirty-source hashes, JDK identity, artifacts, configs, raw logs/XML, real exits and retained failures. No private jar, world, crash report or native binary is committed.
