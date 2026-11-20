# Create adapter 0.2.0 — audited steady-state kinetic ticks

> Historical documentation for 0.2.0. Version 0.3.0 uses execution adapters, same-region resource scopes and bounded planning windows; see the [current source guide](https://github.com/NexForgeLabs/Forgia/blob/main/adapters/create/README.md).

English | [中文](create-adapter.zh.md) · [Adapter index](README.en.md)
Source: [`adapters/create`](../../adapters/create) · Protocol: [`AUDITED_BLOCK_ENTITY_TICKS`](../api/block-entity-ticks.en.md)
Evidence: [CREATE-ADAPTER-VALIDATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CREATE-ADAPTER-VALIDATION.md), [CREATE-OPTIMIZATION-PLAN](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CREATE-OPTIMIZATION-PLAN.md)

Status: **Experimental.** Adapter `0.2.0`, Minecraft **1.21.1**, Create **6.0.10**, core providing
`AUDITED_BLOCK_ENTITY_TICKS` (built against `21.1.250.107-beta`). The original Create jar is required and is
never bundled.

---

## 1. When to use it

| Use when | Why |
|---|---|
| You run Create 6.0.10 and want steady kinetic block entities on region workers | Those types are audited to a local, callback-free branch |
| Your base is dominated by shafts/cogs/gearboxes/clutches in **small** BE phases | The canonical phase must stay ≤2048 entries |
| You can accept that water wheels, millstones, belts, funnels, basins and contraptions stay serial | Their complete contracts are not closed |
| You want a measurable `nativeCompleted` counter for the covered types | Diagnostics are per-adapter and bounded |

| Do not use it when | Because |
|---|---|
| Your pack adds unknown Mixins/coremods over Create | Unknown transformation → complete serial fallback |
| Your BE phase exceeds 2048 entries | The core keeps the whole phase serial regardless of the adapter |
| You expect contraptions, trains, deployers or belts to run on workers | Out of scope for 0.2.0 |
| You need certified multiplayer throughput | Local tests are not a server certification |

## 2. Why the adapter exists

Create's kinetic network is shared mutable state (`TorquePropagator`, behaviour maps, network dirty flags).
Ticking all of Create on workers would race that state. The adapter instead audits the subset whose whole
original ticker branch is *local once initialized*: it reads no power-network registry, publishes no
callbacks, and touches only its own block entity plus reviewed Accessor-backed fields. Everything not in
that subset stays a complete serial fence, and the core re-inspects the suffix afterward.

## 3. Coverage matrix

| Object | Canonical Level path | Complete serial boundary |
|---|---|---|
| `create:simple_kinetic` (shafts, cogs, large cogs) | Local original tick after initialization, no external callback | Initialization, speed propagation, network dirty, periodic validation, particle publication, unknown behaviour/implementation |
| `create:encased_shaft`, `encased_cogwheel`, `encased_large_cogwheel`, `gearbox` | Same as above | Same |
| `create:clutch`, `create:gearshift` | **New in 0.2.0:** steady-state original tick | Topology branches from redstone switching/source updates, and all unclosed states |
| `create:water_wheel`, `large_water_wheel`, `millstone` | Still complete serial | Historic v1 explicitly-driven checks/tests; no full canonical body contract yet |
| Belts, funnels, basins, mixers, presses, fluid/storage networks | Complete serial | Shared inventories/controllers, recipes, callbacks, external writers |
| Contraption entities, Deployer USE/PUNCH, trains | Complete serial | World operations, collisions, passengers, fake players, global managers |

Coverage is **per type, not per production line**. A covered type inside a large phase still runs serially
because of the 2048-entry canonical budget.

## 4. Install and configure

1. Install the core Forgia build that provides `AUDITED_BLOCK_ENTITY_TICKS`.
2. Put `forgia-create-adapter-0.2.0.jar` **and** `create-1.21.1-6.0.10.jar` into `mods/`.
3. Enable the engine (the adapter opens nothing itself):

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 8
```

| Note | Detail |
|---|---|
| Do **not** add Create types to `admit_block_entities` | Audited admission is per-invocation, not a type allowlist |
| Interior rule applies | The outer ring of an 8×8 region chunk area stays serial |
| The core's existing budgets remain | Phase >2048 entries → whole phase serial |
| No runtime-proof/VM agent needed | This is an explicitly installed adapter contract |

## 5. API usage

`CreateTickAdmission implements AuditedBlockEntityTickAdapter`, registered through
`ParallelApi.Registrar.registerBlockEntityTicks`:

| Method | Create-specific behaviour |
|---|---|
| `audit()` | Declares fixed-version implementations, exact source/function classes, fingerprints and reviewed Mixin origins; frozen at registration |
| `prepareCanonical(level)` | The local kinetic branch needs no whole-network scan; retains no live level or network permit |
| `inspect(source, tickerImplementation)` | Checks the complete current branch and lists the real READ/WRITE backing roots (source, effect/behaviour identities) |
| `check(source)` | v1 per-invocation refusal gate; the original ticker may run only after a successful result and only counts completion if it returns |

Register only after checking `ParallelApi.Capability.AUDITED_BLOCK_ENTITY_TICKS`:

```java
if (registrar.capabilities().contains(ParallelApi.Capability.AUDITED_BLOCK_ENTITY_TICKS)) {
    registrar.registerBlockEntityTicks(ParallelApi.MAJOR_VERSION,
            ResourceLocation.parse("forgia_create:steady_ticks"),
            STEADY_TYPES, new CreateTickAdmission());
}
```

The core handles integrity, binding, lifecycle, alias conflicts and freshness. Create-specific conditions
never enter the core.

## 6. Diagnostics

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

```text
Audited original-tick adapter forgia_create:steady_ticks:
  implementationsVerified=true, reason=VERIFIED_IMPLEMENTATIONS, audit=..., revision=...
Block entity tick adapter forgia_create:steady_ticks:
  checks=..., refused=..., nativeCompleted=..., prepareMs=..., checkMs=..., reasons={...}
```

| Field | Meaning | Not |
|---|---|---|
| `implementationsVerified` | Frozen manifest integrity | Current branch or completion |
| `refused` | Invocations kept serial | Errors |
| `nativeCompleted` | Original tickers that returned on a worker | Submissions or type lists |

Use deltas together with whole-tick MSPT/CPU/allocation; never derive a speedup from cumulative counts.

## 7. Build and test

```sh
# build adapter + facade + installer (Create jar is local, never committed)
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar

# integration tests against the real Create jar
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:junitTest

# same bodies on the persistent pool
./gradlew ... -PcreateAdapterPersistentWorkers=true :create-adapter:junitTest

# canonical Create + Furnace/Resource switch combinations (existing budgets kept)
./gradlew ... -PcreateAdapterPersistentWorkers=true -PcreateAdapterFurnaceWaves=true \
  -PcreateAdapterResourceOwnership=true \
  :create-adapter:junitTest --tests '*CreateCanonicalIntegrationTest'

# negative control: inject an unknown Mixin, expect complete serial fallback
./gradlew ... -PcreateAdapterUnverifiedHookControl=true :create-adapter:junitTest
```

| Artifact | Path |
|---|---|
| Adapter jar | `adapters/create/build/libs/forgia-create-adapter-0.2.0.jar` |
| Missing Create jar | Compile/package/real-jar tests are skipped — **not** counted as passing |

## 8. Comparison: 0.1.0 vs 0.2.0

| Property | 0.1.0 | 0.2.0 |
|---|---|---|
| Integration point | Historic v1 explicitly driven path | Actual `Level.tickBlockEntities` canonical path |
| Types reaching workers | v1 list only | Steady kinetics + encased variants + clutch/gearshift steady state |
| Audit manifest | none | implementations + source/function classes + reviewed Mixins |
| Footprint | not declared | READ/WRITE backing roots |
| Water wheel/millstone | explicitly driven | retained historic checks; still serial |
| Failure behaviour | v1 refusal | fresh footprint checks, prefix splitting, serial fence suffix |

## 9. Limits and expectations

| Limit | Effect |
|---|---|
| 2048-entry canonical phase | Large bases remain serial; the adapter cannot raise the budget |
| Interior ring | Boundary chunks stay serial |
| Unknown Mixin/coremod over Create | Whole audited set re-checked; unknown → serial |
| Unknown behaviour/subclass added dynamically | Refused before the next invocation |
| Contraptions/trains/deployers/belts | Not covered |
| Local tests | Prove the API path, not pack-wide throughput |

## 10. Verification status

| Claim | Evidence |
|---|---|
| Canonical path admission works with real transformed classes | `CreateCanonicalIntegrationTest` (real ticker, Core wrapper, actual Level loop) |
| Steady kinetic bodies of different regions overlap | canonical integration test |
| Unknown behaviour/subclass/function and serial-fence suffix | integration + negative control |
| Core-side audited contract independent of Create | core `AuditedBlockEntityTickAdapterTest` |
| Real server loads and completes | private `tools/server_smoke.py` (not a production instance) |

Not established: installed modpack compatibility, whole-server speedup, multiplayer certification.
