# Architecture: Stock Forge / NeoForge vs. Forgia

<div class="lang-pill-bar">
  <a class="active" href="../architecture.en/">English</a>
  <a href="../architecture.zh/">中文</a>
</div>

Forgia is built on NeoForge 1.21.1. Instead of replacing mod compatibility assumptions with an opaque global lock or assuming arbitrary mods are thread-safe, Forgia keeps the vanilla tick phase structure and introduces **explicit spatial leases**, **alternating `GLOBAL` / `REGION` phases**, and **fail-closed Serial Fences** for unknown code.

!!! tip "3D walkthrough"
    Prefer the flow first? Open the [Forgia 3D architecture walkthrough](visualizer/index.html): six interactive steps covering GLOBAL/REGION alternation, spatial leases, parallel batches and the join barrier, cross-region seqlock/mailbox traffic, and the Canonical block-entity serial fence.

<figure class="diagram-frame">
  <img src="../assets/arch-stock-vs-forgia.svg" alt="Side-by-side architecture comparison between Stock Forge/NeoForge and Forgia" />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 01 // STOCK NEOFORGE VS FORGIA</span>
    <span>GLOBAL ↔ REGION PHASE ALTERNATION · EXPLICIT OWNERSHIP</span>
  </figcaption>
</figure>

<figure class="diagram-frame">
  <img src="../assets/arch-region-hierarchy.en.svg" alt="Execution hierarchy comparing Stock Forge single-thread flat queue against Forgia's independent leased Regions running in parallel on separate Workers." />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 02 // EXECUTION HIERARCHY &amp; REGION PARALLELISM</span>
    <span>INDEPENDENT LEASED REGIONS · MULTI-CORE WORKERS</span>
  </figcaption>
</figure>

---

## 1. Side-by-Side Execution Model

<div class="compare-grid">
  <div class="compare-card compare-card--stock">
    <div class="compare-card-head">
      <h3>Stock Forge / NeoForge</h3>
      <span class="compare-badge">SINGLE SERVER THREAD</span>
    </div>
    <div class="compare-card-body">
      <p class="compare-lead">One server thread executes every tick phase sequentially inside shared mutable memory. Simple for mods, capped by single-core throughput.</p>
      <ul class="compare-steps">
        <li><span class="compare-step-key">WORLD STATE</span><span>Time, weather, world border, raids and chunk source run directly on the server thread.</span></li>
        <li><span class="compare-step-key">CHUNKS</span><span>Random ticks and natural spawning iterate loaded chunks serially in one loop.</span></li>
        <li><span class="compare-step-key">ENTITIES</span><span><code>EntityTickList</code> ticks every mob, projectile and villager sequentially on 1 core.</span></li>
        <li><span class="compare-step-key">BLOCK ENT.</span><span><code>Level.tickBlockEntities</code> invokes vanilla and modded tickers in a flat serial loop.</span></li>
        <li><span class="compare-step-key">PLAYERS</span><span>Network packet handling, movement and container checks share the same server tick budget.</span></li>
        <li><span class="compare-step-key">SAFETY</span><span>Implicit single-thread assumption: any mod can mutate any chunk or neighbor without declarations.</span></li>
      </ul>
    </div>
  </div>

  <div class="compare-card compare-card--forgia">
    <div class="compare-card-head">
      <h3>Forgia Regionized Core</h3>
      <span class="compare-badge">EXPLICIT LEASES + FENCES</span>
    </div>
    <div class="compare-card-body">
      <p class="compare-lead">Alternates quiescent <code>GLOBAL</code> boundaries with parallel <code>REGION</code> worker slices. Every off-thread write requires an active lease; unknown code falls back cleanly.</p>
      <ul class="compare-steps">
        <li><span class="compare-step-key">GLOBAL PHASE</span><span>Time, weather, border, player snapshots and cross-region commits execute on the server coordinator.</span></li>
        <li><span class="compare-step-key">LAYOUT</span><span><code>DYNAMIC_CLUSTER</code> loaded-island coalescing (recommended) or <code>FIXED_3X3</code> (3..32 chunk C6 baseline grid).</span></li>
        <li><span class="compare-step-key">WORKERS</span><span>Dedicated <code>RegionWorkerThread</code> pool (Fixed or Persistent work-stealing) with direct thread fields &amp; <code>preWake</code>.</span></li>
        <li><span class="compare-step-key">ENTITIES</span><span>Region-owned section partitions, transfer journal and published <code>RegionPlayerSnapshots</code>.</span></li>
        <li><span class="compare-step-key">BLOCK ENT.</span><span><code>CanonicalBlockEntityCoordinator</code> runs proven prefixes on workers; unknown callbacks trigger a full Serial Fence.</span></li>
        <li><span class="compare-step-key">SAFETY</span><span>All threading switches default to <code>false</code>. At-most-once execution (no replay) and physical join before unload.</span></li>
      </ul>
    </div>
  </div>
</div>

---

## 2. Subsystem Comparison Matrix

| Dimension | Stock Forge / NeoForge | Forgia |
|---|---|---|
| **Tick Loop** | Monolithic single-thread `ServerLevel.tick` | Alternating `GLOBAL` ↔ `REGION` phases (`LevelTickPhaseState`); at most one region batch per level in flight |
| **World Ownership** | Implicit global ownership by the server thread | Explicit `RegionState` leases; `RegionLeasePack` acquires up to 16 regions all-or-none |
| **Worker Threads** | None for world simulation (async IO/chunk gen futures only) | `RegionWorkerPool` (`Fixed` or `Persistent` work-stealing deques) using `RegionWorkerThread` direct fields |
| **Entity Ticks** | Sequential `EntityTickList` | Parallel per-region entity batches (`FixedEntitySectionPartitions`, `EntityIdentityLedger`, `RegionEntityTransferJournal`) |
| **Chunk & Redstone** | Direct neighbor chunk mutation during tick | Parallel random ticks + `RegionDeferredBlockUpdates` / `RegionBorderUpdates` with same-tick fixpoint convergence |
| **Block Entities** | Flat `Level.tickBlockEntities` loop | `CanonicalBlockEntityCoordinator` monotonic cursor: proven prefixes + `BlockEntityResourceOwnership` + Serial Fences |
| **Players** | Inline server tick | Server-owned connection traversal with opt-in worker dispatch for admitted entity calls and the original `doTick()` body. [Player ticks](modules/player-ticks.en.md) |
| **Unknown Mod Code** | Runs inline natively | **Fail-closed Serial Fence**: physical worker join → run full original ticker once on server thread → re-audit suffix |
| **Cross-Region Ops** | Direct mutation | `BlockEntityOperationRuntime`, `RegionMailbox`, seqlock cross-region read verification & serial completion stage |
| **Default Behavior** | Always single-threaded | All region-threading & resource flags default to `false` (behaves like serial NeoForge until opted in) |

---

## 3. Canonical Block Entity Pipeline (Optimized Hot Path)

Modded block entities cannot simply be split arbitrarily across threads without violating causality or corrupting shared inventories. Forgia solves this with `CanonicalBlockEntityCoordinator` and `BlockEntityResourceOwnership`:

<figure class="diagram-frame">
  <img src="../assets/arch-be-pipeline.svg" alt="Forgia Canonical Block Entity Pipeline" />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 02 // CANONICAL BLOCK ENTITY COORDINATOR</span>
    <span>O(1) INCREMENTAL PREFIX · DIRECT WORKER SCOPES · SERIAL FENCE</span>
  </figcaption>
</figure>

Key architectural mechanisms in the block entity pipeline:

1. **Monotonic Cursor (`CanonicalBlockEntityCoordinator`)**: Preserves vanilla `Level.tickBlockEntities` sequence order without a second scheduler.
2. **Incremental Prefix Independence**: Consecutive proven units (native Hoppers, burning vanilla Furnaces, or audited mod adapters) are checked incrementally in $O(1)$ per unit via `PrefixIndependence` / `LongOpenHashSet`, avoiding quadratic re-planning.
3. **Zero-`ThreadLocal` Worker Scopes**: `RegionWorkerThread` stores `BlockEntityResourceOwnership.Scope` and `FurnaceWorkScope` as direct thread fields, eliminating `ThreadLocal` probes in tight worker loops.
4. **Fresh Revalidation & `preWake` Overlap**: After acquiring region leases and delivering canonical mailboxes, persistent workers are pre-woken (`workerPool.preWake`) while the coordinator verifies fresh bindings, inventories and `SharedReads` without copying shared recipe tables into per-wave `ResourceTable` arrays.
5. **Fail-Closed Serial Fence**: Any unknown mod class, Mixin-transformed method (`MixinMerged`), or alias conflict stops the prefix before execution, physically joins workers, runs the full original ticker once on the server thread, and re-audits the remaining suffix.

---

## 4. Phase 1 + Phase 2: Region Sandbox, Independent Clock & Broadcast Adapter

With `DYNAMIC_CLUSTER` as the default region policy, Forgia introduces a Folia-style **Region Sandbox + Broadcast Adapter (`RegionAdapterApi`)** and **Independent Per-Region Tick Clock (`RegionIndependentTickScheduler`)**, allowing ordinary modded Block Entities to parallelize without per-mod adapters (verified in C7 / Issue #5 across a 188-mod pack):

1. **Generic BE Sandbox (`enableRegionSandboxAllBlockEntities`)**:
   - All non-piston, non-denylisted modded BEs execute in parallel on region workers via the zero-ticket sandbox fast path (`SANDBOX_NATIVE_INSPECTION`).
   - Any incompatible boundary call or runtime exception fails closed, demotes the BE type to the server thread without crashing, and persists it in `config/regionthreading-learned.json`.
2. **Sandbox Isolation & Main-Thread Safe Routing**:
   - `RegionSavedDataSandbox` isolates per-region `DimensionDataStorage` access and supports shared-ID mirroring (`shareSavedData`).
   - Cross-region `BlockCapability` queries return `null`, `ServerLevel.players()` exposes a safe sandbox view, and worker `sendParticles` / cross-thread `blockEvents` / advancements route safely to the server thread via `RegionAdapterApi.postGlobal` / `enqueueCommit` and synchronized guards.
3. **Independent Region Clock & Long-Tail Isolation (`RegionIndependentTickScheduler`)**:
   - Each active region maintains an independent 50ms async clock with an atomic `inFlight` guard so BE waves run without blocking on a global barrier.
   - `ENTITY_TICK` throttles only hotspot regions (`ewma > 12ms` / `24ms`, keeping players and random ticks at full rate), `SPAWN` staggers across ticks, and `MobPathfindingThrottle` auto-learns slow pathfinding mobs (`>3ms -> 10t`, `>8ms -> 20t`).
4. **Role of Dedicated Mod Adapters**:
   - Ordinary mods require no adapter; dedicated adapters (e.g., `forgia-create-parallel-compat` for Create's global kinetic/logistics graphs and single-tick predicates, `forgia-cataclysm-compat` for per-dimension `CMWorldData`) are reserved for mods with intricate global or cross-region state invariants.

---

## 5. Deep-Dive Module Documentation

- [Region Engine & Ownership](modules/region-engine.en.md) — Phases, layouts (`FIXED_3X3`, `DYNAMIC_CLUSTER`), leases, worker pools
- [Entity Ticks](modules/entity-ticks.en.md) — Section partitions, identity ledger, transfer journal, query guards
- [Block Entities](modules/block-entities.en.md) — `CanonicalBlockEntityCoordinator`, Furnace Waves, Resource Ownership, Adapters
- [Chunk Ticks & Border Fixpoint](modules/chunk-ticks.en.md) — Random ticks, deferred block updates, sculk/game events
- [Cross-Region Ops & Players](modules/cross-region-and-scheduled.en.md) — Two-owner ops, scheduled ticks, player dispatch
- [Observability & Configuration](modules/observability.en.md) — `forgia.toml` keys, `/forgia` diagnostics, JMX/ring metrics
