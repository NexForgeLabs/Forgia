# Adapter mods — architecture, decision matrix and authoring guide

English | [中文](README.zh.md) · [API index](../api/README.en.md)
Evidence: [GENERAL-MOD-ADAPTER-ARCHITECTURE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/GENERAL-MOD-ADAPTER-ARCHITECTURE.md), [MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE.md)

Status: shipped adapters are **experimental**. Core contains **no target-mod-specific backend**: every
class, fingerprint, branch condition and footprint of a target mod lives in an adapter jar.

---

## 1. What an adapter is — and is not

| Is | Is not |
|---|---|
| A separately installed mod jar that declares audited work through `RegisterParallelAdaptersEvent` | A core patch per mod |
| Owner of the target mod's audit (classes, mixins, branches, resources, versions) | A sandbox or a proof of arbitrary Java safety |
| A registrar of types/ids into the existing canonical phase | Creator of a pool, scheduler, lease or execution authority |
| Free to fall back: refuse → the complete original server-thread path | Allowed to replay a started body |
| Version-pinned to a target mod build | A compatibility promise for other versions |

The core verifies generic facts only: implementation integrity, actual wrapper/source/function binding,
lifecycle, resource identity/aliases, freshness and cross-owner conflicts. It reuses the existing Native
prefix, `InvocationTicket`, region guard/permit, physical join and ordered effect publication.

## 2. Adapter surfaces — comparison

| Surface | Capability | What the adapter declares | What it can achieve today | Effort | Public docs |
|---|---|---|---|---|---|
| Natural-spawn preparation | `NATURAL_SPAWN_PREPARATION`, `..._PLAN_PREPARATION`, `..._CLAIMS`, `ORDERED_SPAWN_FINALIZATION` | Pure snapshots, optional claim + ordered finalization | Spawn body on worker with frozen rules | Medium | [guide](../api/natural-spawn-preparation.en.md) |
| v1 BE tick predicate | `BLOCK_ENTITY_TICK_ADMISSION` | Per-invocation `Decision` | Refusal gate; canonical path not granted by the predicate alone | Low | [guide](../api/block-entity-ticks.en.md) |
| Audited original ticker | `AUDITED_BLOCK_ENTITY_TICKS` | Manifest + READ/WRITE footprint | Original ticker on a worker in the canonical phase | High | [guide](../api/block-entity-ticks.en.md) |
| Original ticker execution adapter | `BLOCK_ENTITY_EXECUTION_ADAPTERS` | Manifest + `Mode` + footprint + dependency revisions | `REGION_OWNER` or `RESOURCE_SCOPED` original ticker execution | High | [guide](../api/block-entity-execution.en.md) |
| Operations-only integration | `BLOCK_ENTITY_OPERATIONS` | Nothing registered; uses `blockEntities()` | Cross-region completion, no tick parallelism | Low | [guide](../api/block-entity-operations.en.md) |

## 3. Decision matrix

| Your goal | Recommended path | Why not the alternative |
|---|---|---|
| My machine's tick is local once initialized | Audited original ticker | v1 predicate does not reach the canonical Level path |
| I only need to say "not now" cheaply | v1 predicate | Audited requires a full manifest and footprint |
| My machine periodically touches a neighbour's container | Operations-only + local audited tick | Parallel ticks cannot legally read a foreign root |
| My spawn listener needs pack config only | Natural-spawn preparation | Tick adapters do not affect spawning |
| My finalize callback must see real add/cancel results | Ordered finalization | Worker finalization cannot guarantee shared-slot order |
| My mod is MobSpawnController-like and has structure filters | No supported path yet; keep it serial | Structure/multiplier protocols are not implemented |
| I just want to move items between machines | `blockEntities().transfer` | Auditing both tickers is unnecessary and riskier |

## 4. Registration lifecycle and budgets

| Stage | Fact |
|---|---|
| Event | `RegisterParallelAdaptersEvent` on `NeoForge.EVENT_BUS`, after biome modifiers, before world ticks |
| Scope | Server thread, inside the event only; registrar retained later fails |
| Transaction | All-or-nothing across all adapters; caught failures still invalidate |
| Order | Callbacks sorted by id |
| Budget | ≤128 registrations total (spawn + BE share) |
| Ids | Unique per server; namespaced by the adapter mod |
| Types | ≤128 per BE adapter; no overlap with another BE registration |
| Freeze | After the event; no dynamic registration |
| Effect on config | None: registration never enables a slice or lifts a conflict |

## 5. Trust model

| Layer | Who is responsible |
|---|---|
| Semantic completeness (every branch, callback, inherited tick, RNG path) | Adapter author |
| Version pinning and changed-byte detection | Manifest hashes + reviewed Mixin set |
| Binding to the exact instance/wrapper/function/chunk | Core |
| Freshness of the footprint before each body | Core + adapter `inspect` |
| Cross-owner alias conflicts | Core (READ/WRITE reference identity) |
| External asynchronous writers | Adapter audit; Core cannot see them |
| Pack-level validation | You, on a real installed server |

Hashes establish *implemented bytes*, not safe behaviour. A green unit test is not a modpack certificate.

## 6. Adding a new adapter (repository layout)

```
adapters/<name>/
├── build.gradle                     # target-mod jar supplied via -P<name>Jar, tests skip without it
├── README.md                        # short, links to docs/adapters/<name>.en.md
├── src/main/java/.../               # adapter + mixin accessors, package-info
├── src/main/resources/              # mixins json + neoforge.mods.toml
├── src/junit/java/.../tests/        # integration tests with the real target jar
│   └── hooks/                       # unverified-Mixin negative controls
└── tools/                           # private real-server smoke script
```

Gradle projects are registered in `settings.gradle` as `:create-adapter`, `:mobspawncontroller-adapter`.

### Authoring steps

1. **Audit the target** — enumerate original ticker classes, inherited ticks, behaviours, lazy ticks,
   callbacks, RNG use, shared/foreign roots, and every transformation the pack can add.
2. **Choose the surface** from the decision matrix; write the refusal conditions first.
3. **Implement the manifest** — exact source/function classes, implementation hashes, reviewed Mixins.
4. **Implement `inspect`** — return `null` for any unclosed branch; include the source as WRITE and every
   real backing root; never query the live world from a worker.
5. **Register** in the event behind a capability check; keep a serial path.
6. **Test serial equivalence first** (switch off), then real overlap (switch on), then negative controls:
   unknown Mixin, unknown subclass/function, changed root, reentrant duplicate registration.
7. **Add diagnostics** with bounded enums only; no positions, types or network ids.
8. **Write the module doc pair** under `docs/adapters/` and link it from the short README.
9. **Record the scope** — versions, config switches, budgets, what is *not* covered.

## 7. Shipped adapters

| Adapter | Target | Capability used | Default state | Docs |
|---|---|---|---|---|
| Create 0.2.0 | Create 6.0.10 (MC 1.21.1) | `AUDITED_BLOCK_ENTITY_TICKS` | Steady kinetic types only; everything else serial | [Create](create-adapter.en.md) |
| MobSpawnController 0.3.0 | MSC 1.2.3 | Natural-spawn preparation + ordered finalization + claim | **Serial by default**; ordered mode is opt-in via `-Dforgia.mscOrderedSpawning=true` | [MSC](mobspawncontroller-adapter.en.md) |

## 8. Verification checklist

- [ ] Capability negotiated; registration only in the event.
- [ ] Ids/types namespaced, unique, inside the 128 budget.
- [ ] Every unsupported configuration falls back **before** effects.
- [ ] Manifest covers exact classes and reviewed Mixins; unknown transform → serial.
- [ ] Footprint includes the source as WRITE and all real roots; aliases resolved.
- [ ] No live world query, RNG, load, schedule or block inside `prepare`/`check`/`inspect`.
- [ ] Started bodies never replayed; failures reported.
- [ ] Serial equivalence test passes with the slice switch off.
- [ ] Real different-region overlap test passes with the switch on.
- [ ] Negative controls (unknown Mixin/subclass/root) stay serial.
- [ ] Diagnostics bounded; docs pair written; versions and limits recorded.

## 9. What the core will never provide

| Not provided | Consequence |
|---|---|
| A "make my mod parallel" certificate | You must audit the target mod yourself |
| A lease/Permit/authority constructor to adapters | Adapters cannot mint execution rights |
| A second scheduler/pool/effect queue | Everything rides the existing canonical phase |
| Replay of a started body | Failures are terminal and reported |
| Raising the 2048-entry canonical phase budget per adapter | Large phases need a bounded-window coordinator |
| Automatic support for arbitrary reflective/async writers | Out of scope of the trust model |
