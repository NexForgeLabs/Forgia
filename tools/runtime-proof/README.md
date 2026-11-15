# Optional VM evidence and original Mod integration

`C6 FINAL ARCHITECTURE ACCEPTANCE: NOT ACCEPTED`

The [2026-11-15 recovery report](../../region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md) and [machine receipt](../../region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.json) distinguish current targeted results from complete-profile acceptance. The operator narrowed continuation to fixes and associated cases. Eleven final-source associated GameTests passed, but the required final unfiltered profile pair was not rerun. Earlier full-profile failures, aborted runs, logical skips and framework Not Processed rows remain recorded.

This opt-in Linux/HotSpot JDK21 runtime supplies actual installed-method evidence and object-access checks for the existing Core invocation Kernel. It creates no scheduler, invocation Ticket, resource reservation or execution permission. Ordinary startup does not load the service, and an absent/unsupported startup service leaves Mod invocations serial. A bridge jar on the ordinary JUnit classpath does not install a bootstrap/native service; a dedicated control verifies that distinction.

The integration fixture uses the original 方块电玩 / `piq_fc_arcade`0.31.0-alpha.61 artifact, SHA256 `d4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510`. Supply it locally. It is All Rights Reserved and is not redistributed here. This pin establishes a reproducible input. Core authorization instead uses installed function semantics, actual storage/binding, enforced access and original Kernel/resource authority. Neither Mod identity nor this hash grants Worker permission.

## VM component controls

Use JDK21 and its headers, a C compiler and a **new** output directory:

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/runtime-proof/verify_runtime.py \
  --java-home "$JAVA_HOME" \
  --output tmp/runtime-proof-components-new
```

The runner retains exact commands/exits/logs, Git SHA/dirty status, JDK identity, source copies/hashes and artifact hashes. The final recovery executed28 command steps:18 runtime controls and10 build/preparation steps. It covers:

- Actual installed hidden bytecodes/handlers, defensive copies and loaded-class identity.
- Direct/reflection/MethodHandle/VarHandle/Unsafe stores, actual JNI field/method calls, accessor entry before earlier effects, nested callbacks and immutable-value enforcement.
- Publication races, concurrent registration of33 retained roots, platform and JDK21 virtual-thread writers, and repeated native binding observations.
- Missing/truncated/negative/overflow/stale evidence:96 rejection cases through a test-only service fault injector, followed by restoration of real VM evidence.
- Native/header bounds, XML/thread creation, absent/unsupported profiles, refusal of unknown native libraries before initialization, FFI/JMX native-agent routes and actual JVMTI redefinition refusal.

Controls check protected values and earlier-effect counters, including after native calls. An exception alone is insufficient. Authorized positive controls must still work. Test-only translation units add evidence faults or exercise JNI/repeated observations; the production library exposes no evidence-injection or mutation API. These components issue no production Core Mod Permit or Lease.

## Associated original-Mod tests

All run directories must be new. Preserve stdout, raw XML and the real process exit code. Existing canonical preparation resets the world in its selected run directory, so never point it at retained evidence.

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain \
  --max-workers=1 -PforgiaVersion=21.1.250.60-beta \
  -I tools/runtime-proof/real_mod.gradle \
  -PtrustedModJar=/local/game_console-0.31.0-alpha.61.jar \
  -PtrustedModRun=tmp/runtime-proof-associated-new/server \
  -PgameTestFilter=saveReload,regionC6Worker,regionC6Writer,regionC6Failure \
  :tests:runCanonicalGameTestServer
```

The four selected cases passed on final production revision `0ede20061`:148 complete original bodies/counter delta148, Worker72/Serial76, overlap2; ten foreign writer routes rejected with counter delta0; actual original Lease/Ticket/Claims identity and retirement; failed-body no-replay plus a fresh same-Level phase; disk reconstruction of25 chunks,13 BEs and9 entities. These are new run data, not the historical76 Worker/4 Serial pilot. This selected command is not a complete-profile pass.

The opt-in fixture stably puts deliberate failure injection last while asserting identical batch membership. Unrelated zero-failure assertions are unchanged. Diagnostics must be0 before injection,1 afterward and still1 after fresh recovery; counters are never reset.

For an isolated lifecycle case, use the common command above with a fresh directory and replace its filter/configuration with:

```sh
-PcanonicalPersistent=false \
-PruntimeProofLifecycleCase=queued-unload \
-PgameTestFilter=regionC6Lifecycle
```

Run each of `queued-unload`, `queued-shutdown`, `running-unload`, `running-shutdown`, `releasing-unload`, `releasing-shutdown` in a separate fresh process/world. The destructive lifecycle controls use the existing fixed executor, actual original Region guards and production phase waits. They create no second pool or authority. All six final-source runs passed: two original Worker bodies/delta2, four wrong-Region refusals and four outside-thread refusals per case, actual cleanup wait, physical join and no replay.

The separate original-object binding preflight uses `-PcanonicalPersistent=false -PruntimeProofLifecycleCase=binding -PgameTestFilter=regionC6Binding`, again with a fresh directory. It verifies same-delegate rebind/version invalidation, normal block replacement, inconsistent Level, refusal of foreign replacement callbacks, and actual frozen coordinates against independent primitive x/y/z values. It creates no Ticket/Lease and is not another Worker E2E result.

Omit `gameTestFilter` for the complete required GameTest registration set; `-PcanonicalPersistent=false` selects the fixed-pool variant. The final unfiltered pair remains an open acceptance gate. Report Passed/Failed/Skipped/Not Processed/Aborted and exit separately: disabled soak/benchmark/hard-kill bodies that call `helper.succeed()` are still logical skips, and duplicate XML group rows are not additional executed tests.

## Boundary and limits

Only a structurally verified counter-return path runs as the entire original third-party function. Periodic/unknown paths receive a complete serial invocation before body start. Core captures wrapper prerequisites and claims actual source/wrapper/delegate/position storage; the Mod Worker reads no live Chunk. Exact Core BlockPos fields remain frozen even for an owner. Mutable-root callback recursion cannot bypass access/entry/native checks. Native binding metadata is serialized, its first-root count is atomic and stable repeated bindings reuse published wrappers without rewriting function targets.

The supported profile disables attach/Unsafe intrinsic inlining and requires the sole startup service, compressed object/class pointers, no JVMCI/WhiteBox and no native FFI downcalls. Unknown JNI/JNA libraries are refused before loading. This restrictive profile is not general Modpack certification or a hostile JVM/Core memory-corruption sandbox. Source/binding/evidence/lifecycle/Claims must remain current; physical join and original resource/Region cleanup precede release. No started body is serially replayed after its effects.

Weak-owner unit controls strongly retain the actual Core callback while a real shutdown Engine and an unstubbed mocked Level collect; they check original-thread serial compatibility and deny foreign/running-home/entered-Scope fallback. This does not certify whole-server real-Level GC or runtime Mod hot-unload. See the final report for explicit remaining gates. No production deployment, second Worker-enabled Adapter, C7 or performance improvement is claimed.
