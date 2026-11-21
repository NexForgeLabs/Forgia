# Production configuration audit

> **Historical C6 audit snapshot.** This document records the frozen C6 fixed-layout (`FIXED_3X3`) audit at commit `a45a3bddf595dde0a4e390f4fdaf05fb2bf832a1`. For the **current** 36-key `config/forgia.toml` schema (including `DYNAMIC_CLUSTER`, `enableRegionThreadingPlayerTicks`, runtime diagnostics keys, and exact `FIXED_3X3`-only vs. `DYNAMIC_CLUSTER` prerequisites), see [docs/configuration.en.md](../docs/configuration.en.md) ([中文](../docs/configuration.zh.md)).

Audit source: `a45a3bddf595dde0a4e390f4fdaf05fb2bf832a1` after fetching `origin/main`.
Production fingerprint: `f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787`.
See [readiness and evidence](PRODUCTION-READINESS.md) and the [canary procedure](CONTROLLED-CANARY.md).

## Reading the matrix

**C6** below means the value is present in both accepted, complete C6 profiles, within their documented
Linux/HotSpot 21 scope. It does not certify every combination, Mod pack, installed artifact or performance
outcome. **Historical/component** identifies narrower or older evidence. **Outside C6** means the enabled
configuration was not exercised by the final C6 pair. A feature being OFF in C6 certifies no enabled behavior.
The separate lifecycle certificate covers stopped-server collection with both pools, not live dimension
removal or runtime Mod hot-unload. The frozen certificates remain authoritative for their original scope.

The runtime schema is [ForgiaConfig.java](../src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java).
The following table includes every key in that schema. Defaults apply when no retained config overrides them.
All sixteen Boolean switches default to `false`; the native service is also absent on ordinary startup.

## Files, precedence and restart

`config/forgia.toml` is a SERVER config. `<selected-world>/serverconfig/forgia.toml`, when present, selects the
world override under FML's SERVER-config rules; treat it as a complete file, not a partial overlay to merge
by hand. Check `level-name` and the actual loaded world. A file in `defaultconfigs/` may seed a new world;
retain it in the deployment inventory too. The declaration JSON is read from the **instance** config directory
and Mod resources, independently of the TOML world override.

Before configs load, [migration](../src/main/java/net/neoforged/neoforge/common/ForgiaConfigMigration.java)
runs for both the instance and world directories. If `forgia.toml` exists it wins. Otherwise the first legacy
file with Forgia keys wins: `neoforge-server.toml`, then `neoforge-server.forgia.toml`. Sources remain unchanged;
a malformed candidate legacy file fails startup with its path. With no surviving keys the defaults apply.
Deleting `forgia.toml` can therefore **reactivate old enabled settings**. Rollback writes explicit false values.

Change engine, layout, storage, slice and service settings while stopped and start a **new JVM**. The engine
captures configuration before Level construction; config reload cannot migrate a Level's storage or remove a
startup native agent. Warning/drain/ownership settings have live readers, but changing them during a canary
would change the experiment. Keep them pinned throughout each run.

## Complete TOML matrix

| Key | Default / allowed values | Accepted C6 value | Conditions, consequence and failure behavior | Certification |
|---|---|---|---|---|
| `enableRegionThreadingBridge` | `false` | `true` | Requests installation before Levels load. Known conflicts and Mod support policy may refuse installation; the server then uses its server-thread path. All slices depend on an installed engine. | C6 ON; service-absent/default-off controls separately pass. |
| `enableRegionThreadingWorkers` | `false` | `true` | Runs due **region scheduler queue drains** on the pool. It is not a master gate for entity/BE/chunk/scheduled/spawn slices. OFF retains server-thread queue drains. Rejected submissions use the caller's completion path. | C6 ON. |
| `enableGlobalRegionThreadingWorker` | `false` | `false` | Separately moves global-region queue drains to the pool; requires installed bridge. OFF drains on the server. A busy global region has a bounded acquisition wait. | Enabled behavior outside C6. |
| `enableRegionThreadingEntityTicks` | `false` | `true` | Requires fixed layout, region-owned entity storage and inactive vanilla profiler. Implies region POI storage. Pinned classes/types/riding stacks and unavailable pre-body footprints stay serial. A failure after body start is reported without whole-ticker replay; some chunk refusals truncate work and are counted. | C6 ON; arbitrary Mod entity bodies are not closure-certified by C6. |
| `enableRegionThreadingBlockEntityTicks` | `false` | `true` | Requests the actual Level canonical BE phase; active vanilla profiler, closed Level or missing engine keeps inline ticking. Native/Furnace/Mod proofs below decide each contiguous prefix; unknown entries are complete Serial Fences. | C6 ON with bounded cohorts only. |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | `true` | Requires BE slice and fixed layout for Furnace prefixes. Executes only freshly verified already-burning furnace-family bodies. Fuel/re-light/extinguish, unknown items/recipes, aliases and unsafe notification paths are whole serial calls. | C6 ON; no performance benefit certified. |
| `enableRegionThreadingBlockEntityResources` | `false` | `true` | Adds READ/WRITE Claims, nonblocking reservation and one-shot scopes to Furnace prefixes. Also gates C6 Mod admission independently of the Furnace switch in current engine code. Does not enable a slice or admit a new type. Incomplete/stale/over-budget/conflicting proofs fall back before effects. | C6 ON in **both** pools. Resources-OFF has earlier/component evidence, not the final C6 pair. |
| `enableRegionThreadingScheduledTicks` | `false` | `true` | Fixed, loaded radius-two inert footprint, inactive profiler, complete vanilla lamp-only block phase. Fluids, mixed/unknown callbacks, foreign/unloaded footprint or over-budget phase retain the live serial LevelTicks loop. | C6 ON for narrow lamp cohort, not general redstone/fluid parallelism. |
| `enableRegionThreadingRandomTicks` | `false` | `true` | Requests the whole chunk tick body: random ticks, ice/snow and thunder. Requires loaded neighborhood and valid ownership/budget. Natural spawning/custom spawners remain separate. Pre-body refusals are serial; foreign writes have deferred/read-after-write semantics. | C6 ON; transformed Mod chunk/event callbacks still require pack validation. |
| `enableRegionThreadingSpawning` | `false` | `false` | Requires fixed entity storage, chunk/player/spawn gates, loaded neighborhood and budget. Mob cap uses check-and-reserve; custom spawners/global decisions stay serial. Conflicts/adapters may retain the complete serial plan. Started failed/truncated bodies cannot be replayed safely. | Enabled behavior outside C6; historical/component evidence only. |
| `enableRegionThreadingPoi` | `false` | `true` | Installed fixed layout; also implied by entity ticks even if this key is false. Publishes foreign region snapshots; answers can be one publication old, with live ticket checks. No supported live storage conversion. | C6 ON; POI save/reload equivalence separately tested. |
| `enableRegionThreadingBorderUpdates` | `false` | `false` | Captures cross-region neighbor effects for target-owner fixpoint processing. Cascade limit delays overflow to the next tick; closed destinations can drop counted work. OFF uses the existing vanilla neighbor collector. | Enabled behavior historical/component, outside final C6 pair. |
| `enableRegionThreadingGameEvents` | `false` | `false` | Captures foreign listener columns and delivers in posting order on their owner. Limits/drops are counted. **OFF retains inline listener dispatch from the posting worker; that baseline can race foreign listeners.** | Enabled behavior historical/component, outside final C6 pair; OFF is not a universal safety claim. |
| `enableRegionThreadingPersistentWorkers` | `false` | `true` / `false` | Selects persistent deques and work stealing versus the existing fixed executor. Independent of region dimensions. Accepted work must physically finish on shutdown in either mode. No automatic performance-based pool switching. | Both C6 profiles and both real-Level GC profiles PASS. |
| `enableRegionThreadingPackedRegionLeases` | `false` | `false` | Groups up to 16 region leases per task in canonical all-or-none order. Failed pack acquisition releases partial leases and tries regions singly. Separate from persistent workers. | Enabled behavior historical/component, outside C6 pair. |
| `regionThreadingForceWithKnownConflicts` | `false` | `false` | Measurement-only bypass of known-conflict actions, loudly logged unsupported. Does not supply missing ownership or override every other admission gate. Keep false for a canary. | Forced deployment unsupported. |
| `regionThreadingWorkerThreads` | `0`, range `0..512` | `4` | `0` resolves to `max(1, availableProcessors - 1)`; pin an explicit count to avoid host/container-dependent changes. Pool is created when engine is installed; persistent threads start immediately, fixed threads start as needed. Bad resource sizing can fail thread creation or increase contention. | Exactly four in C6/GC; other counts require target validation. |
| `regionThreadingMode` | `"FIXED_3X3"`; also `"DYNAMIC"` | `"FIXED_3X3"` | Fixed global grid. DYNAMIC is the legacy 16-chunk merge/split policy, not load-adaptive region sizing; entity/spawn/POI and fine Furnace requirements cannot be met as in C6. | Fixed C6; DYNAMIC outside certification. |
| `regionThreadingFixedRegionChunks` | `3`, range `3..32` | `3` | Edge length N means N×N chunks, 16N×16N blocks horizontally, full height, per Level. Larger cells reduce parallelism. Config spec corrects out-of-range values; direct layout construction rejects them. Retain corrected file/log as a config mismatch. | 3×3 C6; other sizes have some component/historical evidence only. |
| `regionThreadingOwnershipViolationMode` | `"WARN"`; `"DISABLED"`, `"THROW"` | `"WARN"` | Controls advisory assertion handling. All modes count violations; WARN logs, THROW throws, DISABLED suppresses that report. Mandatory worker-storage and native access checks still reject independently. A warning is a canary stop signal, not permission to continue. | WARN C6; do not silently change failure semantics for rollout. |
| `defaultRegionThreadingSupport` | `"UNKNOWN"`; `"SUPPORTED"`, `"UNSUPPORTED"`, `"REQUIRED"` | `"UNKNOWN"` | Default metadata policy. UNSUPPORTED or any explicitly unsupported loaded Mod blocks bridge installation. REQUIRED with bridge off warns; SUPPORTED/REQUIRED grant no BE proof/Claims. Unknown Mods are not rejected merely for being unknown. | UNKNOWN C6; policy is not pack certification. |
| `regionThreadingLongTickWarningNanos` | `0`, range `0..Long.MAX_VALUE` | `0` | Queue-drain warning threshold; zero disables warnings. Measures region/global drains, not every server tick or every phase. Monitoring must supply whole-server MSPT separately. | Default present in C6; nonzero is an unmeasured diagnostic change. |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000`, `0..Integer.MAX_VALUE` | `10000` | Per-queue immediate drain count, not milliseconds. Zero disables limit. Capped work remains queued and capped-drain counters advance; backlog can increase latency. | Default present in C6. |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000`, `0..Integer.MAX_VALUE` | `10000` | Per-queue due scheduler tasks, distinct from Minecraft block/fluid callbacks. Zero disables limit. Remaining due work waits for later drains. | Default present in C6. |

## Admission gates and native dependencies

| Gate / option | Default and required conditions | Consequence / failure | Certified scope |
|---|---|---|---|
| Known conflicts | Active, force OFF. `c2me`: DISABLE_ENGINE; `mobspawncontroller`: DISABLE_SPAWNING. | Requested engine/slice can be OFF at runtime even when TOML says true. `modernfix`, `moonlight`, `sable`, `voxy`, `byepregen`, `ftbchunks`, `alltheleaks`, `create`, `spawn` are REPORT_ONLY rows, not current pack certificates. Detection is by Mod id; unknown transformations are not exhaustively discovered. | Shipped policy/component coverage; actual pack must be rehearsed. |
| Metadata/declarations | No declaration file by default. Metadata `regionThreading`; Mod `regionThreadingDeclarations` resources; instance `config/regionthreading-declarations.json`. | `entities` / `block_entities` pin types serial. Invalid/missing/unknown entries warn and are ignored, so an intended restriction may be absent. `admit_block_entities` widens legacy candidacy but **cannot bypass current canonical body proofs**. Capture all sources; keep widening list empty for first canary. | Unknown/serial behavior retained in C6; declarations never grant Mod authority. |
| Native Hopper admission | No extra enable flag beyond BE slice; original exact Hopper class/ticker, original binding/chunk/Level/clock, no attachments or unreviewed reached Mixin, fresh cooling (`cooldownTime > 1`) or disabled branch. | Only the callback-free branch is eligible. Active inventory/capability/loot/entity/neighbor transfer paths remain complete serial calls. | C6 and earlier Native tests; not generic Hopper transfers. |
| Furnace admission | Waves opt-in; exact furnace/smoker/blast furnace, `litTime > 1`, original ticker and fresh binding; verified runtime/recipe catalog/plain items, no alias, safe notifications. | Original body once; notifications commit in canonical order after physical join. Fuel, custom catalogs/providers and unsafe neighborhoods fence the full call. No optional JVMTI service is needed for this vanilla cohort. | C6 protected cohort; resource layer can cost more MSPT/allocation. |
| Unknown Mod Serial Fence | Always enforced; no disable switch. | Join and commit the proven prefix, run the full original unknown/periodic ticker serial once, then re-prove the suffix. Exceptions stay failures; no started body replay. | C6. Applies to BE tickers, not every arbitrary Mod/entity/event access. |
| Create Serial Fence | Current canonical Level requires Core proof independent of legacy v1 ADMITTED predicates. Create's transformed/machine closure is not admitted. | Whole original Create calls remain serial. Legacy adapter source/tests and old README Worker results do not grant current production admission; structures/contraptions have separate serial policies. | C6 preserves fence; Create Worker production certification absent. |
| General Mod adapters / v1 | Optional startup registration through existing API, major 1; shared total bound 128 registrations, with id/type uniqueness. | Execution/resource/audit descriptions are requests, not authority. Registered unproved general targets remain Serial Fences. v1 callbacks can only narrow an independent Core proof; invalid or retired bindings refuse. | C6 preserves original API/authority; no new Worker adapter. |
| C6 Mod admission | BE slice + resources ON, real bootstrap service installed at JVM start, healthy supported profile, bundled Core integrity matches actual installed definitions, exact source/frame/binding, fresh Claims and original Kernel. | Only the verified original counter-return closure is supported. Periodic/unknown paths, single-unit Mod prefixes, stale code/binding, profiler/TimeTracker instrumentation, missing capacity or invalid evidence stay serial before effects. Native service presence alone proves neither Worker execution nor speedup. | C6 original `piq_fc_arcade` 0.31.0-alpha.61 input; not arbitrary Mods. Furnace switch is not itself required by the Mod gate; C6 nevertheless enables it. |
| Optional VM service | Absent by default; explicit `-agentpath:/absolute/libforgia_runtime_proof.so=/absolute/matching-verified-core.jar`. Bridge must be bootstrap-defined; ordinary classpath JAR is insufficient. No runtime attach installation/removal. | Absent/unhealthy/unsupported proof leaves Mod admission serial. An explicitly requested missing/bad agent or bootstrap JAR can abort JVM startup; later native-library refusals can fail a Mod or server. This is not always a harmless startup fallback. | Linux x86_64/HotSpot Temurin 21.0.13+9, G1/compressed pointers in retained runs. Other OS/VM/ABI/JDK/GC profiles not certified. |
| Native build/runtime | Optional Linux build: JDK 21 headers, gcc C11, `-fPIC -shared -pthread`, libdl, libc/pthreads. Build tasks `:runtime-proof:compileNativeProof` and `:runtime-proof:verifiedCoreJar`; latter binds the exact compiled Core. | Build skips native compilation on non-Linux; that is not a supported alternative binary. A source fingerprint alone does not certify a rebuilt/installed binary, ABI or transformed Core. Validate final installed artifact combination. | Frozen native/verified-Core hashes in readiness report; installed canary package still needs rehearsal. |
| Supported VM restrictions | Sole startup agent; `-XX:+DisableAttachMechanism -XX:+UnlockDiagnosticVMOptions -XX:-InlineUnsafeOps`; compressed ordinary/class pointers ON; JVMCI/WhiteBox OFF. | Additional `-javaagent`, `-agentlib`, `-Xrun`, extra `-agentpath`, `-Xbootclasspath`, preview or `--enable-native-access` refuse the proof profile. Unknown JNI/JNA libraries are refused before initialization; FFI downcalls and redefinition are unsupported. Existing interception is not uninstalled when admission refuses. | Restricted C6 profile only. A general Mod pack or profiler needing natives/attach is not certified. |
| Native library / diagnostic allowlists | Real paths under the trusted JDK lib directory only: `libjava`, `libnet`, `libnio`, `libzip`, `libmanagement`, `libmanagement_ext`, `libjimage`, `libverify`, `libsyslookup`, `libjsvml` (`.so`). | Other library loads return refusal. In-process diagnostic commands allow `help`, `VM.version`, `VM.command_line`, `VM.flags`, `VM.info`, `Thread.print`, `GC.class_histogram`, `GC.heap_info`; other diagnostic commands throw. `jcmd` attach is disabled. Plan observability before launch. | Components/C6; unrelated compression, voice, database, profiler or OSHI/JNA natives need explicit compatibility validation. |
| MobSpawnController ordered experiment | Optional adapter 1.2.3; `-Dforgia.mscOrderedSpawning=false` by default. | ON claims a narrow prepared spawn path. Recorded pack attempts truncate work and do not establish behavior equivalence or speedup. Keep OFF; spawning remains serial for this Mod. | Experimental enabled mode unsuitable for first canary. |
| Error removal (upstream NeoForge) | `removeErroringEntities=false`, `removeErroringBlockEntities=false` in effective `neoforge-server.toml`. | ON deletes failing subjects and can hide tick failures/data loss. Keep false when judging compatibility; these switches do not repair ownership. | Existing default behavior; removal is not canary recovery. |

The native implementation restricts object storage, method entry and some VM/native routes. It is not a hostile
JVM/Core memory-corruption sandbox. Hashes identify inputs; they never grant runtime Claims or permission.

## Fixed phase and capacity budgets

These are compiled bounds, not additional TOML tuning options. Preserve them for the canary.

| Budget | Value / source | Limit behavior and certification boundary |
|---|---|---|
| Canonical BE units | 2048 (`RegionBlockEntityWorkGraph.MAX_UNITS`) | An over-limit canonical phase uses serial entries. Shadow graph truncation must not be mistaken for executed coverage. C6 retains this bound. |
| General resource Claims | 512 resources/unit; 131072 logical bindings/phase (`BlockEntityResourceOwnership`) | Incomplete/overflow/stale proofs refuse before body start. One budget spans prefixes/fences; a new prefix does not replenish it. C6 retains it. |
| Legacy work graph | 64 resources/unit; at most 200 observed phases | Partial diagnostic model, not production authority or a safe-parallelism estimate. `workgraph start` defaults to 20 phases, accepts 1..200; OFF initially. |
| Deferred block effects | 65536 records (`RegionDeferredBlockUpdates`) | Unit reservations: entity/BE/scheduled/spawn 8 each, chunk 32. Nominal admission ceilings 8192/2048 respectively; other admission gates may be stricter. Pre-body budget refusal can stay serial; unexpected mid-body exhaustion is counted failure/truncation, never replay permission. |
| Border/game event captures | 65536 each | Bound and overflow/drop counters; border excess can move to the next-tick mailbox. Cascade passes/applied updates follow `max-chained-neighbor-updates` (vanilla dedicated default 1000000; negative uses vanilla constant). Not C6 enabled-mode certification. |
| Foreign-read / rerun / write state | 256 foreign chunks, 1024 rerun seeds, 4096 deferred positions per RegionState | Bounded tracking; overflow is observable and can refuse/truncate work. Raw internal map/array writes remain outside seqlock coverage. |
| Entity/BE interaction operations | 4096 per batch | Queue rejection can hard-fail a started interaction; cancellation does not replenish capacity. This is an explicit compatibility/load limitation, not lossless serial fallback. |
| Entity transfer journal | 1024 pending, 4096 remembered results | Rejects excess records; derived memory is rebuilt after restart, not durable transaction state. |
| Region lease acquisition | 4 attempts; deferred entity moves 3 attempts; global-region wait 50 ms | Refusal/deferral as appropriate; no unsafe forced lease. These are not shutdown timeouts. |
| Furnace catalog proof | At most 4096 recipes/catalog; ingredient recursion depth 8, node budget 256, 64 compound children | Unknown/oversized catalogs keep whole Furnace calls serial; resource Claim bounds also apply. |
| Optional VM evidence/access | 4 MiB evidence payload, 4096 members, 2048 retained named definitions / 32 MiB bytes; 4096 protected roots; 16384 tracked native allocations | Incomplete/full evidence tables prevent proof; invalid/unbounded memory routes refuse. Finite metadata can reduce admission as a pack grows. Dead weak roots prune during normal registration; no eager world-retention claim. |

## Shutdown, failures and recovery

| Boundary | Runtime behavior / failure | Certification |
|---|---|---|
| Level unload | Production HIGHEST unload callback waits for the published phase and submitted work, closes Level identity, clears queues/indexes, cancels tasks. Repeated unload cannot recreate old admission. Queued cross-tick effects may be dropped and counted. | Lifecycle races plus C6 isolated original-Mod Level controls. Live server world-map detachment GC is not certified. |
| Server stop | Normal saves and Level close, adapter registration close, manager clear, engine shutdown and physical pool termination. Participating worker/coordinator lifecycle self-waits reject before cleanup monitors. | Fixed/Persistent real-Level GC and race coverage. |
| Worker/body/commit failure | Preserve first Throwable; aggregate distinct later failures while cleanup continues. Physically join, seal guards, commit started effects in order, release resource/Region authority, retire proofs. Never replay a started original ticker serially. | C6 failure recovery and separate lifecycle controls. A fresh later phase is not recovery of undone effects. |
| Stalled worker | Pool close waits until accepted work physically returns, even through closing-thread interruption. No configurable timeout releases authority early. | Pool/component coverage. Operator must distinguish a hung process from a clean stop. |
| Save/restart | Quiescent vanilla chunk/entity/POI files authoritative; mappings, caches, identity ledgers, leases/scopes/pools reconstructed. Flushing regionized POI save waits for I/O. | C6 actual disk reconstruction; separate historical clean-restart evidence. |
| Hard kill | No cleanup/flush guarantee; unsaved and cross-tick deferred work can be lost. Restore/restart from an independently verified save/snapshot. | Hard-kill workload was logically SKIPPED in both final C6 profiles. Earlier tests do not certify the C6 canary pack. |
| Runtime Mod or agent unload | No supported FML Mod unload/reload boundary; cannot remove native startup hooks/config/storage live. Use normal stop and a fresh JVM. | **NOT CERTIFIED / unsupported**, including reload after genuine Mod hot-unload. |

Extra diagnostics: `-Dneoforge.regionOwnershipDiagnosis=true` enables detailed entity ownership rejection logging
(default false). Entity/BE/chunk/spawn/block-event/barrier attribution toggles default OFF and are programmatic
instrumentation; no normal production command enables those Java setters. Test-only `*ForTesting` methods,
`gameTestFilter`, `canonicalPersistent`, `canonicalResources`, `furnaceResourceOwnership`, lifecycle init scripts,
and fault/Mixin controls are test infrastructure, not production startup settings. Do not ship test fixtures or
use them to bypass admission. `TimeTracker.BLOCK_ENTITY_UPDATE` or the vanilla profiler can force BE serial
fallback; capture any monitoring-induced change in worker completion rates.

Primary implementations: [startup](../src/main/java/net/neoforged/neoforge/server/ServerLifecycleHooks.java),
[conflict policy](../src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingConflicts.java),
[engine](../src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java),
[Native proof](../src/main/java/net/neoforged/neoforge/server/threading/region/NativeBlockEntityPreparation.java),
[Furnace proof](../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkPreparation.java),
[Mod proof](../src/main/java/net/neoforged/neoforge/server/threading/region/CoreModTickerVerifier.java),
[VM profile](../projects/runtime-proof/src/main/java/net/neoforged/forgia/runtimeproof/RuntimeProof.java),
[native restrictions](../projects/runtime-proof/src/main/native/access_enforcement.c).
