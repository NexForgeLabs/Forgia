# MobSpawnController adapter 0.3.0 — ordered spawn finalization

English | [中文](mobspawncontroller-adapter.zh.md) · [Adapter index](README.en.md)
Source: [`adapters/mobspawncontroller`](../../adapters/mobspawncontroller) · Protocol: [natural-spawn preparation](../api/natural-spawn-preparation.en.md)
Evidence: [MOBSPAWNCONTROLLER-LOCAL-RESULTS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOBSPAWNCONTROLLER-LOCAL-RESULTS.md), [MOBSPAWNCONTROLLER-ADAPTER-AUDIT](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOBSPAWNCONTROLLER-ADAPTER-AUDIT.md), [NEARBY-SPAWN-ADMISSION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/NEARBY-SPAWN-ADMISSION.md)

Status: **Experimental, and not recommended for production.** Adapter `0.3.0`, Minecraft **1.21.1**,
MobSpawnController **1.2.3**. Requires a core with the ordered-spawn-finalization protocol.

> **Spawning is not taken over by default.** The measured A/B was *slower* and showed thousands of
> truncated spawn bodies. The default remains `DISABLE_SPAWNING` (complete serial natural spawning).
> `-Dforgia.mscOrderedSpawning=true` is for experiments only.

---

## 1. When to use it — and the honest answer first

| Situation | Recommendation |
|---|---|
| You run MSC 1.2.3 and want natural spawning on workers | **Do not.** Default serial; there is no certified speedup |
| You are researching near-count-limited spawn parallelism | Enable the ordered flag in a disposable test instance |
| Your pack uses structure/light/height/season conditions or `spawn_multiplier != 1` | Keep serial; those protocols are unimplemented |
| You want to see whether the adapter is even admitted | Read `ready`/`serialPlans` and the reason strings |

## 2. Why ordered finalization, not plain parallel spawning

MSC's value is enforcing nearby-count limits and cancel rules at `FinalizeSpawn`. If candidates were
selected and finalized on six workers independently, two could both believe they took the last nearby slot.
Ordered finalization keeps **selection** on workers but runs the complete native finalization
(`finalizeSpawn`, passenger admission, `afterSpawn`, cluster/group-limit callbacks) on the server while all
participating spawn workers are parked. The next candidate then sees the real add/cancel result.

## 3. Measured result (read before enabling)

60 seconds, same world, same rules:

| Metric | Ordered parallel | Serial control |
|---|---:|---:|
| Median MSPT | 39.3 | 17.8 |
| Naturally spawned entities | 210 | 283 |
| Truncated spawn chunks | **5294** | 0 |
| Ownership violations | 0 | 0 |

| Caveat | Detail |
|---|---|
| The ratio is not a controlled performance result | The ordered round overlapped with Gradle tests |
| Truncation blocks acceptance | 5294 truncated bodies + 4437 external chunk-read refusals |
| Stop points, main-thread queries and task dispatch cost real time | Not quantitatively attributed yet |

## 4. Support matrix

| Configuration / operation | Behaviour |
|---|---|
| No `natural-settings` entries | Freeze spawn switch and attribute map; keep the original worker path |
| natural settings limited to `chance`, `max_nearby`, `nearby_radius`, `spawn_types`, `excluded_spawn_types` | Only with `-Dforgia.mscOrderedSpawning=true`: candidate selection and position checks on workers; original rule evaluation, finalize, real add and post-add count callbacks at a safe stop on the server. Default remains serial |
| Structure, light, height, season or other natural conditions | Complete native serial plan is retained before core cap construction |
| `spawn_multiplier != 1` | Complete native serial plan; original weight/cap protocol |
| Unverified `NaturalSpawner` Mixin | natural settings stay serial, reason `unverified_spawn_hooks` |
| Non-natural spawning outside the prepared body | Original mod path and live rules |
| Config hot-reload | Admitted plans keep the original revision; later plans read the new one |
| Empty structure selector list | Returns false immediately; non-empty lists use the original method |
| Exceptions / nested spawning | Restore the mod's boost ThreadLocal; nothing leaks into the next task |

This is **not** "the whole mod runs on workers". Whenever a nearby-count limit exists, the final rule
decision and the real add outcome must be one ordered operation.

## 5. Protocol in detail

| Step | Where | What happens |
|---|---|---|
| 1 | Event bus | Original `FinalizeSpawn` listener stays in its original position; no evaluator copy, no re-dispatch |
| 2 | Plan | `RULES`, natural settings and attribute map frozen into a plan-level immutable view |
| 3 | Preparation | `NATURAL_SPAWN_SETTINGS` data source redirected; rule logic still executed by the original mod |
| 4 | Worker | Candidate selection loop runs; core sets a cooperative stop point per real worker group |
| 5 | Barrier | Only groups that actually started register as participants |
| 6 | Server | Full finalize, passenger admission, `afterSpawn`, cluster/group-limit callbacks |
| 7 | Server | Deferred block/entity/tick effects published before finalization so rules see prior changes |
| 8 | Server | Same RNG source and prepared data continue on the main thread; do not re-prepare in `SERVER_SERVICE` |
| 9 | Server | `NaturalSpawnBoost.ACTIVE_LEVEL` saved/restored on both worker and continuation paths |
| 10 | All | Pool rejection, ordinary main-thread requests, barrier shutdown and spawn exceptions each have a path |

Attribute overrides still run once in the original listener position. A cancelled spawn keeps original
behaviour and RNG consumption. Cross-group transactions execute in submission order; vanilla global
candidate order / RNG sequence is not promised bit-identical.

## 6. Enablement

```toml
enableRegionThreadingSpawning = true
enableRegionThreadingWorkers = true
regionThreadingMode = "FIXED_3X3"
```

```
-Dforgia.mscOrderedSpawning=true
```

| Requirement | Detail |
|---|---|
| Core | Must contain the ordered spawn finalization protocol |
| Claim | The adapter must pass startup verification and call `claimNaturalSpawning` |
| What the claim replaces | **Only** this mod's `DISABLE_SPAWNING`; never `DISABLE_ENGINE` (e.g. c2me) or an admin-disabled slice |
| Verification | Unknown injection sources/versions fall back to serial |
| Extra audit | ByePregen 1.1.2.1 (chunk cache/nearest player) and YUNG Better Nether Fortresses 1.21.1-NeoForge-3.1.5 (fortress candidate selection) are reviewed call sites; unknown sources fall back |

## 7. Diagnostics

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

| Output | Meaning | Not |
|---|---|---|
| `ready` / `serial` / `serialPlans` | Prepared units / serial units / refused plans | Entities spawned |
| preparation time | Cumulative preparation wall time | Per-tick cost |
| last reason | `forgia_mobspawncontroller:world_dependent_rules`, `...:unverified_spawn_hooks` | A per-position label |
| `Ordered spawn finalization: transactions=..., serverMs=...` | Continuations (including refused/cancelled) and main-thread body time | Total cost (waiting, dispatch and deferred publication excluded) |

If spawn workers are on but `serialPlans` keeps growing and real worker counts are zero, you are still
serial. Whole-server timing must come from whole-tick MSPT, not from adapter counters.

## 8. Build and test

```sh
# build adapter + facade + installer
./gradlew :mobspawncontroller-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar \
  -PforgiaVersion=21.1.250.48-beta --offline

# integration tests with the real 1.2.3 jar
./gradlew :mobspawncontroller-adapter:junitTest \
  -PforgiaVersion=21.1.250.48-beta \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar --offline

# negative control: remove the adapter mod, expect exactly two empty-list cases to fail
./gradlew ... -PmscAdapterNegativeControl=true --offline

# negative control: inject an unverified Mixin, expect fallback before the plan starts
./gradlew ... -PmscUnverifiedHookControl=true --offline
```

| Artifact | Path |
|---|---|
| Adapter jar | `adapters/mobspawncontroller/build/libs/forgia-mobspawncontroller-adapter-0.3.0.jar` |
| Missing MSC jar | Integration tasks skip explicitly; not a pass |
| Never packaged | Original jar, decompiled sources, API copies, test classes |

## 9. Comparison: three modes

| Property | Vanilla serial (default) | Ordered parallel (opt-in) | Fully parallel (not implemented) |
|---|---|---|---|
| Candidate selection | Server | Worker | Worker |
| Nearby-count rule evaluation | Server | Server at safe stop | Worker (would race) |
| Native finalization | Server | Server, peers parked | Worker |
| Shared last-slot correctness | Guaranteed | Guaranteed by ordering | Not achievable without a quota protocol |
| Measured MSPT (60 s fixture) | 17.8 | 39.3 | — |
| Truncated bodies | 0 | 5294 | — |
| Status | **Default, safe** | Experimental, not recommended | Out of scope |

## 10. Limits

| Limit | Consequence |
|---|---|
| Structure/light/height/season conditions | Serial |
| `spawn_multiplier != 1` | Serial |
| Unverified Mods over `NaturalSpawner` | Serial |
| Truncation at chunk/region boundaries | Blocks behavioural acceptance |
| No performance benefit established | Do not enable for throughput |
| Unit/fake-player tests | Not a multiplayer soak or modpack certificate |

## 11. Checklist before enabling in any world

- [ ] Disposable instance only; production stays on the default serial path.
- [ ] Core contains the ordered finalization protocol; adapter startup verification passes.
- [ ] `claimNaturalSpawning` verified; no c2me/`DISABLE_ENGINE` conflict.
- [ ] All natural settings limited to the supported field subset; multiplier is 1.
- [ ] `serialPlans`/`serial` counters inspected; `unverified_spawn_hooks` not recurring.
- [ ] Whole-tick MSPT and truncation counters measured, not adapter totals.
