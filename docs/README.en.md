# Forgia documentation

English | [中文](README.zh.md)

This directory is the **curated, maintained** documentation set for Forgia. Every page exists as a
`*.en.md` / `*.zh.md` pair. The two files of a pair describe the same contract; when they disagree, the
English page is the reference text and the pair must be fixed in the same commit.

The `region-threading/` directory keeps the **evidence archive** (audits, acceptance runs, profiler
records, experiment reports). It is not a tutorial and is not translated page by page. Use this directory
to learn how something works or how to call it, and `region-threading/` to check the measurement behind a
claim.

---

## 1. Which page do I need? / 需求 → 文档

| I want to… | Read | Primary types |
|---|---|---|
| Understand what Forgia is and what is certified | [README](index.md), [STATUS](status.en.md) ([中文](status.zh.md)) | — |
| See the execution flow as a 3D animation first | [3D architecture walkthrough](visualizer/index.html) (6 interactive steps) | — |
| Understand experimental player dispatch | [Player ticks](modules/player-ticks.en.md) | `ENTITY`, `CONNECTION`, `CONNECTION_BODY` |
| Start a server / enable region threading | [Observability & configuration](modules/observability.en.md) | `forgia.toml`, `/forgia` |
| Call a mod-facing API | [API overview](api/README.en.md) | `ParallelApi` |
| Prepare natural-spawn data on the server, run the body on a worker | [Natural-spawn preparation](api/natural-spawn-preparation.en.md) | `NaturalSpawnPreparation`, `Ready`, `ServerRequired` |
| Admit my block entity's original ticker on a worker | [Block-entity tick admission](api/block-entity-ticks.en.md) | `BlockEntityTickAdapter`, `AuditedBlockEntityTickAdapter` |
| Move items / run a two-owner operation across regions | [Block-entity operations](api/block-entity-operations.en.md) | `BlockEntityOperations` |
| Schedule a task on a region / entity / global scheduler | [Schedulers](api/schedulers-and-entity-interactions.en.md) | `RegionScheduler`, `EntityRegionScheduler`, `GlobalRegionScheduler` |
| Write an adapter mod for a third-party mod | [Adapter guide](adapters/README.en.md) | `RegisterParallelAdaptersEvent` |
| Install or validate the Create adapter | [Create adapter](adapters/create-adapter.en.md) | `forgia_create:steady_ticks` |
| Install or validate the MobSpawnController adapter | [MSC adapter](adapters/mobspawncontroller-adapter.en.md) | `forgia_mobspawncontroller` |
| Understand the engine internals | [Module docs](modules/README.en.md) | `RegionThreadingEngine`, `CanonicalBlockEntityCoordinator` |
| Read a measurement, not a summary | [Evidence archive](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md) | — |

## 2. Documentation map

```
docs/
├── README.{en,zh}.md                     index, conventions, status legend
├── visualizer/                           single-file 3D architecture walkthrough (not a MkDocs page)
├── api/                                  public mod-facing contracts
│   ├── README.{en,zh}.md                 entry matrix, lifecycle, compile setup
│   ├── parallel-api.{en,zh}.md           ParallelApi, Server, Capability, Phase, DataKey
│   ├── natural-spawn-preparation.{en,zh}.md
│   ├── block-entity-ticks.{en,zh}.md     v1 predicate + audited original-ticker contract
│   ├── block-entity-operations.{en,zh}.md
│   └── schedulers-and-entity-interactions.{en,zh}.md
├── adapters/                             shipped adapter mods and how to write one
│   ├── README.{en,zh}.md                 adapter types, decision matrix, trust model
│   ├── create-adapter.{en,zh}.md
│   └── mobspawncontroller-adapter.{en,zh}.md
└── modules/                              implementation notes per subsystem
    ├── README.{en,zh}.md                 module map and status matrix
    ├── region-engine.{en,zh}.md
    ├── entity-ticks.{en,zh}.md
    ├── chunk-ticks.{en,zh}.md
    ├── natural-spawning-and-poi.{en,zh}.md
    ├── block-entities.{en,zh}.md
    ├── cross-region-and-scheduled.{en,zh}.md
    └── observability.{en,zh}.md
```

## 3. Status legend

Every page states the status of what it describes. The labels are not marketing tiers; they say exactly
how much evidence exists.

| Label | Meaning | What you may assume |
|---|---|---|
| **Stable-ish** | Public surface that has shipped for several builds and is covered by tests. | Signatures will not change without a major version bump. |
| **Experimental** | `@ApiStatus.Experimental`, gated behind a capability and usually a config switch. | Behaviour can change in a minor build; always negotiate by capability. |
| **Certified (scope)** | Passed the named acceptance run inside its documented scope (for example C6, Linux/HotSpot 21). | Only the documented configuration is certified. Not your modpack. |
| **Unsupported** | Present in code or proposals but not a contract. | Do not build on it. |
| **Not implemented** | Described in an RFC with no code. | Nothing to call. |

## 4. Ground rules for API readers

1. **Negotiate, don't guess.** Check `ParallelApi.MAJOR_VERSION`, then
   `Server.capabilities()`. Never infer behaviour from a Forgia build number.
2. **Capability ≠ enabled.** A capability says the protocol exists on this server. A config switch says
   the slice runs. Neither grants world ownership.
3. **Registration is a contract, not a proof.** The core checks implementation integrity, binding,
   lifetime and declared footprints. It cannot prove arbitrary Java safe.
4. **No blocking across regions.** Never wait on a worker, another region or a service result from inside
   a region body. Submit before the effect, poll on a later safe tick.
5. **Do not keep live objects as authority.** A `Reference`, snapshot or context names an instance now.
   It does not extend mutation rights to a later tick or another thread.
6. **At-most-once, no replay.** A started body that fails is reported, never re-run. A partially applied
   operation is never retried.
7. **Fail closed.** Unknown callback, unknown class, unloaded neighbourhood, unverified transformation →
   the complete original server-thread path runs.

## 5. Building the compile-only API facade

```sh
./gradlew --offline :neoforge:parallelApiJar
# → projects/neoforge/build/libs/*-parallel-api.jar
```

Use it as `compileOnly`. Never shade, relocate or jar-in-jar a second copy: the runtime classes are
provided by the Forgia server. There is no frozen independent Maven coordinate yet.

```groovy
dependencies {
    compileOnly files('libs/forgia-parallel-api.jar')
}
```

Portable mods must load the Forgia-specific entrypoint behind an optional-mod guard; do not reference
optional Forgia types from a class that loads on stock NeoForge.

## 6. Related directories

| Path | Contents |
|---|---|
| `region-threading/` | Audits, acceptance records, profiler JSON, experiment reports, upstream/folia references |
| `examples/parallel-api/` | Compile-only `ContainerLinkExample.java` |
| `adapters/` | Shipped adapter mods and their smoke tools |
| `testframework/`, `tests/` | Unit/GameTest harness and suites |
| `tools/` | Benchmark, probe, modpack-matrix and readiness controllers |

## 7. Note on removed scratch artifacts

The local `tmp/` (profiler/benchmark scratch, ~31 GB), `tests/runs/` (GameTest run output) and `.pi/tasks/`
(agent task notes) directories were deleted during the documentation cleanup, and are now git-ignored.
Some evidence documents under `region-threading/` still link into those paths; such links are **historical**
and the files are not part of the repository. Keep new measurement artifacts outside the tree or under
`tmp/` (ignored), and cite the evidence document rather than a local path from maintained docs.
