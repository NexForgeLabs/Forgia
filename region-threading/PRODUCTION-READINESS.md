# Production readiness and controlled canary preparation

**Phases 1–2: repository/source audit PASS; controlled-canary preparation available. Installed deployment is
NOT YET VALIDATED.** C6 and the separate lifecycle certificates remain valid within their original scope.
The next operational gate is an isolated rehearsal of the exact server/pack/world/VM and a successful restore
and rollback drill. This audit did not launch, alter or deploy a real server.

Read the [complete configuration matrix](PRODUCTION-CONFIGURATION.md) for every runtime option, default,
condition, consequence, failure and certification limit. Use the [canary/observability/rollback procedure](CONTROLLED-CANARY.md)
and [read-only re-audit command/config templates](../tools/production-readiness/README.md) for preparation.
No C7 or new Worker-enabled Mod Adapter is included.

## Current source and provenance

Fresh `git fetch --prune origin` succeeded. Local `main`, `origin/main` and the supplied latest known HEAD
all resolve to `a45a3bddf595dde0a4e390f4fdaf05fb2bf832a1`. The checkout was clean before this work. The fetched
history ends with separate lifecycle certification documentation; it does not contain a newer runtime revision.
The new documentation and audit tools do not change that production revision.

The [machine re-audit receipt](PRODUCTION-READINESS-AUDIT.json) records fresh checks of current files, not an
assumption that the old reports still apply:

| Check | Result |
|---|---|
| Tracked production inventory | **1955 /1955** source/patch/optional-service files match the C6 aggregate fingerprint. |
| Production fingerprint | `f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787` |
| Additional production/build paths vs `0ede200610867aea46d5473c7f6ec4fccdd063e0` | Empty diff; no untracked production inputs. Includes adapters, coremods, build scripts and buildSrc in addition to the fingerprint paths. |
| Frozen C6 report and receipt | Both byte-for-byte preserved; **123/123** unique path/hash evidence references verify, including byte lengths. |
| Frozen lifecycle report and receipt | Both byte-for-byte preserved; **372/372** unique path/hash evidence references verify, including byte lengths. Counts overlap across receipts and are not independent test executions. |
| Canonical definitions | All three C6-pinned definitions match: `tests/build.gradle`, `tools/runtime-proof/real_mod.gradle`, `RealModSuiteOrdering.java`. |
| Optional native service, current local build | `4c59ec052f086ac1cbdc0c1919504e5870a4043bf563d1da275d5112a89da2df` — matches retained C6/lifecycle binary. |
| Optional verified-Core JAR, current local build | `914f4715293ec3dbc24bfd6159edc17fc727c3f4e0b6fa2f435c358bad15d602` — matches retained C6/lifecycle artifact. |
| Installed server binary/pack/world equivalence | **Not checked:** no canary installation selected or run. Matching source/local service artifacts do not establish installed equivalence. |

Fingerprint reproduction follows the exact frozen collector encoding: `git ls-files -z` for `src/main/`,
`patches/`, `projects/runtime-proof/src/main/`; map each tracked path to SHA256 of current bytes; then SHA256
of Python `json.dumps(mapping, sort_keys=True).encode()` with its default separators. This is an inventory
hash, not a Git commit, JAR hash or runtime admission credential. The broader production diff separately
covers inputs outside that original inventory. Private evidence is present in this workspace; on another
clone, request `--verify-retained-evidence` only with the actual retained files. Missing evidence never passes.

## Existing certification and its boundaries

| Evidence layer | Preserved result | Practical limit |
|---|---|---|
| [C6 final acceptance report](C6-FINAL-ACCEPTANCE-2026-11-15.md) / [receipt](C6-FINAL-ACCEPTANCE-2026-11-15.json) | Fixed **170 Passed /0 Failed /10 Skipped**, Persistent **170 Passed /0 Failed /10 Skipped**;0 GameTest Not Processed or Aborted; actual Gradle/server exits0. | Two complete unfiltered180-function/74-batch suites. Framework population separately168 Passed /10 Skipped /4 Not Processed; duplicate XML/group rows are not extra passes. |
| C6 optional VM components | 28 command steps:18 runtime controls and10 build/preparation steps; exit0. | Supported VM/native/evidence boundary; component JNI tests are not additional real-Mod execution results. |
| C6 original-Mod/lifecycle/binding | Original bodies/Claims/Lease identities, writer refusal, failure without replay, reconstruction and selected lifecycle cases passed. | Original local `piq_fc_arcade`0.31.0-alpha.61 input and narrow semantic counter-return closure; no Mod-name/hash permission, generic machine admission or arbitrary pack certification. |
| [Separate lifecycle certificate](LIFECYCLE-CERTIFICATION-2026-11-15.md) / [receipt](LIFECYCLE-CERTIFICATION-2026-11-15.json) | Fixed and Persistent real-Level GC PASS:28/28 weak clearings and queue arrivals,4 observed collections,3 complete observations,12 old callback refusals each;0 exits. | Whole stopped-server graph, no connected players. Does not certify removing a Level from a live server, same-JVM restart, arbitrary Mod resources or classloader unloading. |
| [Lifecycle audit](LIFECYCLE-AUDIT.md) / races | Production unload callback, queued/rebind/running/releasing, repeat unload, cleanup/shutdown and stale callbacks covered.116 selected JUnit executions per flag profile; overlapping4-case strengthened reruns;4 associated real-Mod GameTests. | Deterministic four-case race gate uses the existing fixed executor in both flag invocations; do not present it as four new Persistent queue scenarios. Persistent real-server GC is separate evidence. |
| Genuine runtime Mod hot-unload | **NOT CERTIFIED / unsupported**. Four genuine unload/reload scenarios Not Processed. | FML4.0.42/ModLauncher11.0.5 retains container/shared defining game loader; no supported unload lifecycle. Adapter closure, Level stop or datapack reload cannot substitute. |
| Current-source real-server canary/performance | **Not executed** by this audit. | No general MSPT improvement, long multiplayer soak, disaster recovery or production deployment claim. |

Ten logical skips in each complete C6 profile include production soak, hard-kill recovery and eight gated
benchmark/diagnostic/entity-soak workloads. The four framework-only IDs are `testAttachmentSyncManual`,
`customBoatType`, `extendPoiTypeTest`, `testFeatureFlagPacks`. These exclusions remain visible; raw tracker180,
raw framework178 and234 XML group rows must not become acceptance pass counts. Old failed/aborted attempts
remain frozen with their original exits; the separate lifecycle result supersedes only C6's historical
unverified real-Level GC limit. It does not rewrite the frozen C6 report.

## Production configuration findings

The [matrix](PRODUCTION-CONFIGURATION.md) covers **all24 TOML keys**, including **16 default-false Boolean
switches**, every opt-in slice, both pools/count/geometry, ownership policy, queue limits and warnings. It also
covers static capacity budgets, declarations/metadata, native availability, narrow Hopper/Furnace/Mod proof,
Unknown/Create fences, failure/cleanup and recovery.

The deployment-sensitive findings are:

1. `enableRegionThreadingWorkers` controls region scheduler queue drains. Tick slices have independent flags;
   changing that one value cannot disable parallel ticking. Rollback disables the bridge and every slice,
   checks world overrides and uses a fresh JVM.
2. Effective config can come from the selected world's `serverconfig/forgia.toml`. Missing files can migrate
   enabled keys from either legacy NeoForge TOML file. Deleting the independent file can re-enable old settings.
   Capture migration inputs and set an explicit rollback config.
3. No Worker feature, native service, forced compatibility mode, ordered MobSpawnController experiment or
   packed lease mode is enabled by default. Fixed grid3×3 is the default layout, with auto worker count0
   (`max(1, availableProcessors-1)`) only relevant after engine installation. C6 pins four workers.
4. Both final C6 profiles have resources ON, Furnace waves ON, and bridge/entity/BE/chunk/POI/scheduled ON.
   Their only config difference is Persistent workers. Spawning, border/game-event capture, global workers,
   packed leases and force remain OFF. The serial baseline template is separate from these accepted settings.
5. Optional native proof is absent on ordinary startup and unnecessary for the vanilla Hopper/Furnace cohort.
   C6 Mod admission additionally requires resources ON, actual bootstrap installation, restrictive VM profile,
   fresh Core integrity/binding/storage evidence and original Claims/Kernel authority. Native presence alone
   does not prove a Worker completion. Bad explicitly requested agent paths can abort startup; unknown native
   library refusals can break dependent Mods.
6. Unknown/periodic Mod calls and Create remain full Serial Fences. General/v1 adapter registration, pack
   `admit_block_entities`, declaration metadata and cached success do not bypass current canonical body proof.
   No adapter was added or widened here.
7. Ownership WARN and zero guard counters are insufficient to prove all gameplay safe. Mandatory guards still
   throw; unguarded arbitrary callback/native/raw-array effects are outside their proof scope. Game-event
   capture OFF retains an inline foreign-listener race baseline when relevant workers post events.
8. The resource layer has known allocation/latency costs; historical improvements to allocations do not
   establish whole-server gains. Fixed/Persistent correctness acceptance does not choose a faster pool.

The main README contained stale claims that C6 remained incomplete, game-event capture was unimplemented,
and no bounded pack/installed evidence existed. It also mixed historical ZGC benchmark advice with the G1
native profile. Those entry-point descriptions now point to current certificates and separate their scopes.
Both frozen certificates and all production source/config defaults are preserved.

## Unsupported and unverified environments

These are operational exclusions and known limits of the inspected tree, not a new expanded certification.

- **Optional service:** Windows/macOS, non-HotSpot or non-JDK21 VM, unvalidated architecture/ABI/JDK update,
  changed compressed-pointer/collector profile, attach/extra agents, JVMCI/WhiteBox, preview/native FFI,
  redefinition and arbitrary JNI/JNA libraries are outside its certified profile. The retained real runs use
  Linux x86_64/WSL2, glibc2.41, Temurin HotSpot21.0.13+9, G1/compressed pointers. Ordinary Java region logic
  without that service is a separate configuration, not a claim that all these OS/VMs are supported by C6.
- **Pack/loader:** C2ME keeps the engine disabled; force bypass is unsupported. MobSpawnController keeps natural
  spawning serial unless its separate experiment opts in; that experiment has known truncation and no accepted
  behavior/performance result. REPORT_ONLY rows and unknown Mods are not blanket compatibility passes. Core
  transformation checks can refuse everything while the server still boots. The fork's version label does not
  imply complete newer upstream NeoForge API parity; missing post-fork APIs can still cause linkage errors.
- **Execution shape:** dynamic/load-adaptive region behavior, changed fixed dimensions/count and packed leases
  are outside the final C6 combination. Active vanilla profiling/TimeTracker can force serial paths. Current
  Native Hopper proof excludes live transfer callbacks; Furnace proof excludes unknown recipes/items/providers,
  aliasing and unsafe notification neighborhoods; C6 Mod proof is a narrow original counter branch. Arbitrary
  capabilities, shared resources, cross-region machine transactions and a second Worker adapter remain unproved.
- **Gameplay:** players/riding stacks, projectiles including pearls/fireworks/skulls, shulkers, slimes/fishing
  hooks and recognized moving-structure policies keep complete ticks serial. This does not authorize direct
  Mod damage/explosion/effect calls or every AreaEffectCloud interaction. Generic whole-operation ownership
  remains incomplete. The bounded4096 interaction queue can reject work after a body has started.
- **World reads/effects:** raw chunk maps/section/heightmap arrays bypass normal seqlock guards. Foreign block
  writes can become deferred effects; same-tick read-back can see old state. Ordering is per target Region,
  not a guaranteed global vanilla callback order. Worker player snapshots are at most a tick old; region
  entity queries can be truncated. Mid-body unavailable chunks can truncate/fail without replay; future serial
  class pinning does not restore that lost invocation. Full asynchronous chunk/teleport continuation is absent.
- **Persistence:** generic SavedData still clears dirty after submission and logs caught I/O exceptions without
  versioned durability acknowledgement/retry. Map registry handoff does not authorize concurrent map pixel/
  tracker mutation; raw named-random access can bypass protected wrappers. A clean `save` response does not
  prove every arbitrary Mod resource is durable. Hard kill loses unsaved/cross-tick work; final C6 hard-kill
  workload was skipped. A verified offline backup/restore is required for a canary rollback.
- **Lifecycle/load:** live-server dimension graph detachment, same-JVM server restart, genuine Mod hot-unload,
  arbitrary class unloading, six-hour multiplayer load and long-lived native metadata behavior under a much
  larger pack are unverified. A stuck Worker intentionally blocks clean pool shutdown; there is no safe timeout
  that releases its authority while it writes.
- **Observability:** normal commands omit internal save counters and some Mod/Kernel evidence; no built-in
  metrics exporter or canary controller is provided. Native attach is disabled and diagnostic commands/natives
  are restricted. A parser must distinguish unavailable values from zero, fallback from loss, and submission/
  Lease counts from completed original bodies.

Current-source checks also confirm the4096 interaction bound, generic SavedData submission/dirty-clear path,
and lack of generic area-effect/explosion worker ownership in the entity policy. The historical audits describe
older snapshots and some subsequently fixed defects; this report does not reopen repaired projectile/replay
findings as new bugs. Further context: [crash/logic audit](CRASH-AND-LOGIC-AUDIT.md),
[map/I/O limits](MAP-AND-IO-CONCURRENCY.md), [named random limits](NAMED-RANDOM-SEQUENCES.md),
[pack smoke scope](MODPACK.md), [historical upstream drift](UPSTREAM-DRIFT.md),
[resource latency evidence](BLOCK-ENTITY-RESOURCE-COST-ATTRIBUTION.md).

## Existing infrastructure and appropriate reuse

| Infrastructure inspected | Use / required caution |
|---|---|
| `tools/runtime-proof/verify_runtime.py` | Standalone VM component controls; JDK21 headers/compiler and new output directory. No Core Mod execution permission issued. |
| `tools/runtime-proof/real_mod.gradle` and canonical GameTest profile | Original local Mod plus complete C6 profiles. It builds/installs the service for the test JVM, pins the original jar and uses a selected resettable test world. Omit filter for full acceptance; never use a production/retained world. |
| `run_lifecycle.py`, `level_gc.gradle`, `collect_lifecycle.py`, heap analyzer | Separate real-Level post-stop observation, actual collection/queue counts and true original/observer exits. GameTest success alone is insufficient; private heap/log evidence remains private. |
| JUnit authority/resource/retirement/pool suites | Targeted implementation regressions; world seams are not installed-server/player coverage. |
| Modpack group/bisect/load tools | Isolated startup or bounded workload evidence; dependency/group/classifier limits remain. Load harness normally expects spawn/other slices ON, which differs from C6 spawn OFF; it does not automatically provision the optional service or test real clients. |
| Historical Create smoke/adapter tests | Old explicit/indexed bridge evidence; current canonical Create serial fence remains. No promotion to a second Worker-enabled adapter. |
| New `tools/production-readiness/audit.py` | Read-only source/receipt/evidence verification; no server/action side effects. Complete example configs preserve original defaults by remaining uninstalled. |

This change was verified with12 audit/configuration controls, including deliberate source/certificate/evidence/
artifact tampering, missing inputs, outside-checkout evidence links, output overwrite refusal, complete schema/
matrix coverage and exact C6 template equality. The new-tool [control log](../tmp/production-readiness-20261115T164423278024Z/controls.log)
and [validation receipt](../tmp/production-readiness-20261115T164423278024Z/validation.json) retain the real command,
zero exit and tool input hashes; the separate [preservation receipt](PRODUCTION-READINESS-AUDIT.json) records
source/evidence/native-artifact checks. Markdown/local links and whitespace checks passed. No Java/native/runtime
source, test fixture or canonical definition was changed, so a new full C6/GC run was not performed for these
documentation/tool additions. Existing suites remain preserved evidence, not newly executed tests.

## Open launch gates

The repository provides a concrete candidate configuration and procedure. Before a real-server canary, fill
in the exact host/pack/world/artifact manifest, prove a restored baseline can boot/save/stop, rehearse the chosen
Fixed configuration with compatible monitoring, exercise pack-specific machine/player/persistence paths, and
complete rollback to the known-good installation. Confirm original Worker body coverage for each claimed slice;
expected serial containment does not certify parallel operation. Any data mismatch, unexplained work loss,
ownership/tick failure, unbounded backlog or material latency regression stops promotion.

The first proposed exposure is a short, whitelisted, supervised window following those gates. No live deployment,
server message, Mod removal/unload, forced compatibility mode, production default change or new Worker adapter
has been performed or implied by this preparation.
