# Lifecycle certification — real Levels and runtime Mod unload

This is a separate lifecycle result. The frozen `C6 FINAL ARCHITECTURE ACCEPTANCE: PASS` is preserved unchanged. This work is not C7 or another Mod Adapter.

| Requirement | Result | Actual boundary |
|---|---|---|
| Whole-server real-Level GC | **CERTIFIED** | Original Mod Workers on normal real Levels, actual server unload/close and Core shutdown, physical thread return, then observed collection of the stopped world graph with the shutdown Core engine and native access callbacks retained |
| Genuine runtime Mod hot-unload | **NOT CERTIFIED** | NeoForge/FML has no supported runtime Mod unload/reload lifecycle in this runtime. Level/server retirement is independently exercised; the original FML container and defining classloader remain loaded. |

The [machine receipt](LIFECYCLE-CERTIFICATION-2026-11-15.json) contains exact argv, timestamps, source inventories, counts, actual exits, GC observations, all original failures and SHA256 evidence references. The [audit and transition map](LIFECYCLE-AUDIT.md) records the ownership analysis made before changing test code, followed by the observed diagnostic corrections.

## Source and environment

Preflight: clean `main` at `acd5207cbbe55e138cb9ba6c30b774b7322b1bc0`. Delivered test/tool/audit source revision: `3474ba9fd7e0f34510c176aa02ee5925b37847b1`. Execution receipts retain the original HEAD and per-file hashes/copies of dirty test/tool inputs; the later commit does not manufacture a different execution HEAD. Runs span November15–16,2026 CST; exact per-process timestamps are in the receipt.

Production remains `0ede200610867aea46d5473c7f6ec4fccdd063e0`, production fingerprint `f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787`. No production code, native service, Core manifest, default configuration, canonical profile definition or existing C6 test was changed. The new Java control is a JUnit test. The real-Level fixture and its single GameTest-only exit Mixin are included exclusively by `-I tools/runtime-proof/level_gc.gradle`.

Environment: Linux x86_64 / WSL2 `6.18.40.1-microsoft-standard-WSL2`, glibc2.41; Temurin HotSpot21.0.13+9 at `/home/yc/.local/opt/jdk-21.0.13+9`; Gradle8.13; Minecraft1.21.1; Forgia/NeoForge21.1.250.60-beta; FML4.0.42; ModLauncher11.0.5; SecureJarHandler3.0.8; G1 with compressed object/class pointers. The original local `piq_fc_arcade`0.31.0-alpha.61 input SHA256 is `d4cb3a41cd3d98c99a0118b1e97475b5781106711ac1d4e321d87c4c2506c510`. The artifact is not redistributed.

The real-server launches retain the C6 sole native startup service, disabled attach/Unsafe intrinsic inlining and other existing supported-VM restrictions. JUnit installs no native service; its default-off control passes. None of these results grants arbitrary Mod/native/JNI compatibility or expanded parallel admission.

## Architecture findings

The live server intentionally owns its Levels through `levels` and `worldArray`; each Level owns chunks, indexes, bound ticker wrappers and original Mod block entities. The production stop path saves and closes the Levels without clearing the server's world map. Whole-server collection requires the stopped server graph itself to become unreachable.

Core's published canonical phase, original invocation Tickets, manifests, exact Claims, Leases, entered scopes and queued/running callbacks intentionally retain the resources they protect. These strong references must survive the entire original body, physical join, guard sealing, ordered commits, resource release and outer Region release. A weak owner reference cannot replace those strong runtime authorities.

`CoreModPreparation` stores weak protected-root keys. Its access callbacks have weak references to the original engine, Level, coordinator Thread and home Region. Native interception holds **strong** callback global references and weak protected-root JNI references. Retaining the actual callbacks throughout collection therefore tests a real retention risk. After physical completion, the Kernel cancels unstarted Tickets, marks proofs RETIRED, nulls manifests/Claims and clears the Ticket/effect maps. Thread-bound scopes remove their ThreadLocals. Closed Level identities are weak keys in `ThreadedRegionizer`.

The real lifecycle exercised is:

`MinecraftServer.loadLevel / normal original ticker registration -> original Level.tickBlockEntities -> existing canonical coordinator / planner / invocation Kernel / Claims / resource Scope / Worker pool -> physical phase return -> MinecraftServer.stopServer -> actual LevelEvent.Unload -> NeoForgeEventHandler.onDimensionUnload -> RegionThreadingHooks.clearLevelRegionTasks -> engine phase/task waits and identity/queue/Region retirement -> unchanged ServerLevel.close -> handleServerStopped -> adapter closure / RegionThreadingManager.clear -> engine.shutdown / pool.close -> server thread return`.

No second scheduler, Worker pool, Ticket/Permit, Claims/reservation, Lease/resource lock, effect queue or cleanup authority was added. Unknown Mod paths and Create keep their Serial Fences. No started original body is replayed serially after its side effects.

## Real-Level GC evidence

The dedicated GameTest uses the three actual `ServerLevel` objects constructed by the normal server lifecycle. It creates two original upstream block entities in separate End Regions through `Level.setBlock`, waits for their normal ticker registration/execution, then invokes the original Level phase. Both complete original bodies run on actual Core Workers with their original Permit, Mod manifest, exact resource Claims/Lease and entered Mod/resource scopes. Counters start at1 and end at2 for each source: two original bodies, total delta2, with no replay.

The testing body observer is removed after the phase physically returns. Sources and chunks are left to normal shutdown. The real unload events occur for overworld, nether and End. After the original close loop, the original entity identity ledgers are sealed and the original entity IOWorkers have their shutdown flag and mailbox CLOSED bit set. The canonical server/engine providers are empty after stop. Both observed original Mod Worker threads and the original server thread physically terminate.

The instrumentation changes only `GameTestServer.onServerExit`'s last `System.exit`. It preserves the original GameTest exit status, stops the GameTest runner through its public `GameTestTicker.clear` API, and allows the server thread to return before the independent observer collects. The observer releases its temporary joined-Thread reference. It does not clear any server world map, Level, Mod registry, old Thread task or Core reference to force success. An ordinary GameTest JVM otherwise exits inside `onServerExit` and provides no observation window.

The shutdown engine and six actual registered access callbacks stay strongly reachable throughout GC, with explicit reachability fences. The28 tracked weak references cover the real server, all three real Levels, both chunks, both original Mod sources/wrappers/delegates, both Permits/Tickets/manifests/Claims/Leases/phases, and both Mod/resource scopes.

| GC profile | Requests | Actual collections observed | Weak references cleared | Queue arrivals | Consecutive complete observations | Old callback foreign read/write refusals | Original GameTest / observer / Gradle exit |
|---|---:|---:|---:|---:|---:|---:|---|
| Fixed — `gc-fixed-6` |4|4|28/28|28/28|3|12|0 /0 /0|
| Persistent — `gc-persistent-1` |4|4|28/28|28/28|3|12|0 /0 /0|

In both runs the first collection clears every weak reference but only16 queue notifications have arrived. The test waits for the remaining notifications and requires three complete observations before certifying. GC request, GC MXBean observation, weak clearing and reference-queue arrival are recorded separately. The bounded loop allows40 attempts and waits on the ReferenceQueue for at most250ms per attempt; it uses no arbitrary sleeps or forced finalization. Finalization is neither requested nor observed as a correctness condition.

The known retained engine maps/sets for published work, mailbox/phase state, worker-server tasks, border/game events, foreign reads and chunk/entity admission are empty. Actual collection of the real Level graph while the engine/callbacks remain reachable establishes the absence of a world-retaining path through those Core roots. This is a whole stopped-server boundary; it does not certify detaching a Level from a still-live server's world map.

## Runtime Mod unloading capability

The installed FML4.0.42 APIs, private fields, lifecycle class inventory and relevant bytecode were inspected directly. `ModList.INSTANCE` holds loaded/indexed containers. `FMLModContainer` retains its loaded classes, module and event bus. `FMLLoader.gameLayer` and ModLauncher's completed game layer remain live. The lifecycle has construct/setup/IMC/load-complete events and no supported runtime Mod unload/reload event or drain-and-retire API. Internal startup failure cancellation is not a runtime unload route. A module-layer consumer, resource reader closure or mutable registry removal does not provide such a protocol.

`CommonModLoader.begin/load/finish` initializes and freezes registries and network state. `ServerLifecycleHooks.handleServerStopped` closes server configs, scoped adapter registrations and server/engine providers; it does not retire the process-wide Mod list, registrations, listeners and defining loader. Both real post-server observations show the same original `piq_fc_arcade` FML container and the same `cpw.mods.modlauncher.TransformingClassLoader` in the game layer, with `isLoaded=true`.

| Boundary | Meaning in this runtime |
|---|---|
| Adapter unregister / registration closure | Stops the scoped Core registration. Does not unload the Mod or its classes. |
| Mod-specific disable flag | Application behavior only; no platform-wide join, listener, registry or classloader retirement guarantee. |
| Real Level/server retirement | Supported and exercised through actual unload/close/shutdown and collection here. |
| Datapack/resource reload, replacing a loader or deleting a JAR | Does not unload the already defined original Mod classes or reverse all registrations. |
| Actual runtime Mod hot-unload/reload | Unsupported by this pinned runtime; **NOT CERTIFIED**. |

The queued/running/releasing **genuine Mod hot-unload** scenarios and reload-after-hot-unload are **Not Processed (4)** because the prerequisite runtime lifecycle does not exist. They are not Passed, and the Level/adapter tests are not substituted for them. The strongest verified real boundary is real Level/server retirement with original Mod execution authority retired while FML deliberately retains the Mod itself.

The [NeoForge1.21.1 lifecycle documentation](https://docs.neoforged.net/docs/1.21.1/concepts/events#the-mod-lifecycle) describes lifecycle events as occurring once during startup. [Java21 JLS §12.7](https://docs.oracle.com/javase/specs/jls/se21/html/jls-12.html#jls-12.7) permits class unloading only when the defining loader can be reclaimed. Exact installed-binary evidence and the actual runtime identities, rather than documentation alone, establish this boundary. No fake hot-unload implementation was introduced.

## Races and regression accounting

The new `LifecycleRetirementRaceTest` deterministically gates queued, queued-plus-same-delegate-rebind, running and releasing work with latches and the actual published lifecycle write transition. It calls the production `NeoForgeEventHandler.onDimensionUnload`, repeats unload, races shutdown against pending cleanup, physically joins both controllers, and re-executes every retained original pool callback after retirement. Each case requires unchanged world/cell/inventory state, empty retired execution authority and refusal to recreate a phase for the old Level. A counter verifies that every stale callback was actually attempted. These are component controls with JUnit world seams; they are not labeled real-Level GC or Mod hot-unload.

Existing selected Kernel/resource, weak-owner/default-off, lifecycle/fence, recovery/effect and Worker-pool tests exercise physical joins, self-wait rejection, one-shot/thread-bound authority, same-position replacement, failure cleanup and independent later commits. The commit-failure control preserves the exact first Throwable while later started effects still commit, and verifies that the unstarted suffix has zero effects. The real-Mod associated selection independently checks foreign writes with delta0, original identity/Claims/Lease retirement, original bodies and periodic Serial Fences, original failure accounting without reset/replay, fresh same-Level work and physical disk reconstruction.

| Selected population | Passed | Failed | Skipped | Not Processed | Aborted | Gradle / server or test JVM exit |
|---|---:|---:|---:|---:|---:|---|
| Lifecycle/authority JUnit — Fixed |116|0|0|0|0|0 /0|
| Lifecycle/authority JUnit — Persistent |116|0|0|0|0|0 /0|
| Final strengthened four race cases — Fixed |4|0|0|0|0|0 /0|
| Final strengthened four race cases — Persistent |4|0|0|0|0|0 /0|
| Real-Level GC certification — Fixed |1|0|0|0|0|0 /0|
| Real-Level GC certification — Persistent |1|0|0|0|0|0 /0|
| Original-Mod associated GameTests |4|0|0|0|0|0 /0|
| Genuine hot-unload/reload scenarios |0|0|0|4|0|Not launched: unsupported boundary|

The final race reruns overlap the116-case populations. Only their attempt-accounting assertions changed after the broader runs; the112 other cases remain unchanged. The four new deterministic cases deliberately use the existing fixed executor in both profile invocations to gate actual delivery. The Persistent flag rerun is not a claim of four additional persistent-queue race scenarios; actual Persistent-pool world collection is established by its separate real-server run. XML/argv/source inventories distinguish these executions. These totals must not be added to the framework registration populations or to the GameTest body inside each GC case.

Each GC launch selects1 of181 registered GameTest functions;180 are outside selection and unprocessed. Its separate raw framework XML has235 duplicate group rows representing183 distinct IDs:2 Passed /0 Failed /0 Skipped /181 Not Processed /0 Aborted. The extra Passed ID is the existing feature-gating lifecycle check. The associated selection runs4 of180 GameTest functions, leaving176 outside selection; its182 distinct framework IDs are5 Passed /0 Failed /0 Skipped /177 Not Processed /0 Aborted. No outside-selection or optional/manual entry is counted as passed coverage.

The new associated run records147 complete original bodies/counter delta147 =72 Worker+75 Serial, overlap2. This is its own result, distinct from C6's historical148-body associated result. All ten foreign writer routes retain illegal delta0; one original authorized body increments once. Original failure accounting remains exactly0→1→1, the failed body is never replayed, two fresh original Worker bodies complete, and physical disk reconstruction creates25 new chunks,13 new block entities and9 new entities. Actual Gradle and server exits are0.

Documentation and the evidence recorder/collector were edited during the associated/final-Fixed-race runs. Their exact dirty-input inventories retain those differences. Compiled production and runtime test inputs for those invocations were unchanged; the final Persistent-flag race rerun has no source changes during execution. Both GC certification runs likewise have unchanged per-run source inventories.

## Retained original failures

| Attempt | Original result | Actual exits / evidence |
|---|---|---|
| `gc-fixed-1` | Init script did not guard Gradle's included build; requested runtime case Not Processed |Gradle1; server not launched|
| `gc-fixed-2` | Wrong test call overload; compilation failed; requested runtime case Not Processed |Gradle1; server not launched|
| `gc-fixed-3` | Observation Mixin changed integrity-checked `ServerLevel.close`; Core refused Worker admission; Worker assertion crashed the server;1 Aborted GameTest |Gradle1 /server1; crash/log/source retained|
| `gc-fixed-4` | Pre-shutdown GameTest1 Passed; post-shutdown GC certification1 Failed after40 observed collections |Original GameTest0 /observer1 /Gradle1|
| `gc-fixed-5` | Same retention reproduced; GC certification1 Failed; live HPROF retained and analyzed |Original GameTest0 /observer1 /Gradle1|

The heap search scanned4,286,502 object/array records and excluded `Reference.referent` edges. Its shortest strong path was an observer Java-frame root to the terminated server `Thread`, then `Thread.holder -> FieldHolder.task -> MinecraftServer startup lambda -> AtomicReference.value -> GameTestServer.worldArray -> real ServerLevel`. The observer's local and lambda capture retained the Thread, whose task retained the startup server reference. Releasing that observer-owned temporary reference after join resolved collection. The server, stopped Thread and Core were not mutated to hide the path.

All original failing source snapshots, errors and actual exits are preserved. The failed runs remain failed; their pre-shutdown GameTest successes do not turn failed GC certification into a pass. The successful Fixed/Persistent runs use the corrected observer without the Level-close transformation or a weakened integrity check.

## Commands and evidence

All commands run from `/home/yc/Minecraft/Forgia`, with the JDK path above first on PATH and without inherited `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_OPTS` or `GRADLE_OPTS`. The evidence runner rejects an existing output directory. Reproduction must use new run directories; retained worlds/logs must not be overwritten.

The exact final GC Gradle commands are:

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tools/runtime-proof/real_mod.gradle -I tools/runtime-proof/level_gc.gradle -PtrustedModJar=/home/yc/Minecraft/SkyExplorer-forgia/mods/game_console-0.31.0-alpha.61.jar -PtrustedModRun=tmp/lifecycle-20261115/gc-fixed-6/server -PcanonicalPersistent=false -PgameTestFilter=regionLifecycleGc :tests:runCanonicalGameTestServer
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tools/runtime-proof/real_mod.gradle -I tools/runtime-proof/level_gc.gradle -PtrustedModJar=/home/yc/Minecraft/SkyExplorer-forgia/mods/game_console-0.31.0-alpha.61.jar -PtrustedModRun=tmp/lifecycle-20261115/gc-persistent-1/server -PcanonicalPersistent=true -PgameTestFilter=regionLifecycleGc :tests:runCanonicalGameTestServer
```

The associated command is:

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain --max-workers=1 -PforgiaVersion=21.1.250.60-beta -I tools/runtime-proof/real_mod.gradle -I tmp/c6-profile-pair-20261115-2205/observe.gradle -PtrustedModJar=/home/yc/Minecraft/SkyExplorer-forgia/mods/game_console-0.31.0-alpha.61.jar -PtrustedModRun=tmp/lifecycle-20261115/associated/server -PgameTestFilter=saveReload,regionC6Worker,regionC6Writer,regionC6Failure :tests:runCanonicalGameTestServer
```

JUnit uses the same common Gradle flags, `:tests:junitTest -PfurnaceResourceOwnership=true`, and additionally `-PfurnacePersistentWorkers=true` for Persistent. Exact `--tests` argv for all10 selected classes and the final `*LifecycleRetirementRaceTest` reruns are in the receipt. Installed-platform inspection uses JDK21 `javap -private` and `javap -private -c`; exact binary paths, commands, zero exits, inventories and SHA256 are under `capability/`.

| Evidence | Location |
|---|---|
| Fixed real-Level GC result / log / GC log / XML / actual exit | `tmp/lifecycle-20261115/gc-fixed-6/` |
| Persistent real-Level GC result / log / GC log / XML / actual exit | `tmp/lifecycle-20261115/gc-persistent-1/` |
| Component XML, raw logs and commands | `tmp/lifecycle-20261115/unit-fixed/`, `unit-persistent/`, `races-final-fixed/`, `races-final-persistent/` |
| Original-Mod integration XML, log and actual exit | `tmp/lifecycle-20261115/associated/` |
| Failed GC retention path and private live heap | `tmp/lifecycle-20261115/gc-fixed-5/retention-path.json`, `level-gc.json.hprof` |
| All original failed attempt sources/commands/logs/exits | `tmp/lifecycle-20261115/gc-fixed-1/` through `gc-fixed-5/` |
| Frozen C6 evidence and production/definition/artifact comparison | `tmp/lifecycle-20261115/frozen-c6-audit.json`, `source-preservation.json`, `preflight.json` |

The machine receipt binds all these files to sizes/SHA256, including configs, generated argument files and original native/verified-Core artifacts. No private Mod JAR, native binary, world, crash report or heap is committed.

## C6 preservation and limits

Both frozen C6 documents remain byte-for-byte identical. All123 references in the existing C6 receipt checksum-verify. Production sources have an empty diff from the C6 production revision. The canonical profile definitions are unchanged; the native service SHA256 remains `4c59ec052f086ac1cbdc0c1919504e5870a4043bf563d1da275d5112a89da2df`, and the verified-Core JAR remains `914f4715293ec3dbc24bfd6159edc17fc727c3f4e0b6fa2f435c358bad15d602`, matching C6 retained artifacts exactly.

No C6 production evidence was invalidated. Another complete unfiltered Persistent/Fixed C6 pair was **not required and was not run**, because this change adds opt-in tests/instrumentation, component controls, diagnostics and documentation only. The separate original-Mod associated regression was rerun. The historical C6 ten opt-in skips and four framework-only unprocessed IDs remain historical coverage limitations; they are not promoted by this report.

The real-Level fixture has no connected players and uses two original Mod sources in the End, while tracking all three normal Levels. It certifies the stopped server graph, not live-server dimension detachment, a server restart/reload in the same JVM, arbitrary Mod resources or arbitrary classloader unloading. Native dead-key callback metadata remains bounded and is pruned by normal registration; this report proves it does not retain retired worlds, without claiming eager deletion of every metadata row. Genuine Mod hot-unload/reload remains unsupported and **NOT CERTIFIED**. No production deployment, compatibility expansion, second Worker-enabled Adapter or C7 was performed.
