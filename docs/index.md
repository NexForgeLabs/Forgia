<div class="hero" markdown>
  <div class="hero-copy">
    <p class="eyebrow"><span class="eyebrow-mark"></span> NexForge Labs <span class="eyebrow-sep">/</span> NeoForge 1.21.1 Runtime</p>
    <h1>Regionized execution<br>for <em>modded worlds.</em></h1>
    <p class="hero-lede">Forgia transforms Stock Forge/NeoForge's single-thread server loop into alternating <code>GLOBAL</code> and <code>REGION</code> execution phases—combining explicit spatial leases, worker-pool parallelism, and fail-closed Serial Fences for unverified mod callbacks.</p>
    <div class="hero-actions">
      <a class="action-primary" href="architecture.en/">Architecture vs. Stock <span>↗</span></a>
      <a class="action-secondary" href="benchmarks.en/">Performance Data <span>↗</span></a>
      <a class="action-secondary" href="architecture.zh/">中文对比 <span>↗</span></a>
    </div>
    <p class="hero-footnote"><span class="status-dot"></span> C6 Certified Scope (Fixed Layout) <span class="hero-footnote-divider">·</span> Default-OFF Experimental Switches</p>
  </div>
  <figure class="hero-visual">
    <div class="visual-topline"><span>Stock NeoForge vs. Forgia</span><span>Architecture Blueprint / 01</span></div>
    <img src="assets/arch-stock-vs-forgia.svg" alt="Side-by-side diagram comparing Stock Forge/NeoForge single-thread execution with Forgia's alternating GLOBAL/REGION worker model." />
    <figcaption class="visual-caption"><span>Single Server Thread → Explicit Region Leases</span><span>Fail-closed Serial Fence preserved</span></figcaption>
  </figure>
</div>

<div class="spec-strip">
  <div><span class="spec-label">Baseline Target</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">Entity Parallelism</span><strong>Up to <b>4.5×</b> (1024 cows)</strong></div>
  <div><span class="spec-label">32-Player Scale</span><strong><b>3.97 ms</b> (-6.4% vs serial)</strong></div>
  <div><span class="spec-label">BE Hot-Path (64 Furnaces)</span><strong><b>1.67×</b> MSPT · <b>-72%</b> alloc</strong></div>
</div>

<section class="arch-section-home">
  <div class="section-heading">
    <div>
      <div class="section-index">01 <span>— Architecture Comparison</span></div>
      <h2>Single thread vs.<br><em>explicit ownership.</em></h2>
    </div>
    <p class="status-lede">Stock Forge runs every tick slice on one server thread inside implicit shared memory. Forgia keeps vanilla causality while partitioning eligible work across leased worker regions.</p>
  </div>

  <div class="compare-grid">
    <div class="compare-card compare-card--stock">
      <div class="compare-card-head">
        <h3>Stock Forge / NeoForge</h3>
        <span class="compare-badge">1 SERVER THREAD</span>
      </div>
      <div class="compare-card-body">
        <p class="compare-lead">All world simulation—chunks, entities, block entities, redstone and players—competes inside a single sequential 50 ms loop.</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">EXECUTION</span><span>Sequential <code>ServerLevel.tick</code> on one core; async futures limited to chunk IO/gen.</span></li>
          <li><span class="compare-step-key">OWNERSHIP</span><span>Implicit global thread affinity; mods freely mutate neighbors or shared registries.</span></li>
          <li><span class="compare-step-key">BLOCK ENT.</span><span>Flat <code>Level.tickBlockEntities</code> loop serializes every furnace, hopper and machine.</span></li>
          <li><span class="compare-step-key">SCALING</span><span>High entity counts or spread-out multiplayer bases saturate a single CPU core.</span></li>
        </ul>
      </div>
    </div>

    <div class="compare-card compare-card--forgia">
      <div class="compare-card-head">
        <h3>Forgia Regionized Core</h3>
        <span class="compare-badge">GLOBAL ↔ REGION</span>
      </div>
      <div class="compare-card-body">
        <p class="compare-lead">Alternates quiescent <code>GLOBAL</code> coordination boundaries with parallel <code>REGION</code> worker batches—never assuming arbitrary mod code is thread-safe.</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">EXECUTION</span><span><code>RegionWorkerThread</code> pool runs leased regions in parallel using direct thread fields &amp; <code>preWake</code>.</span></li>
          <li><span class="compare-step-key">OWNERSHIP</span><span><code>RegionState</code> spatial leases + <code>BlockEntityResourceOwnership</code> READ/WRITE scopes.</span></li>
          <li><span class="compare-step-key">BLOCK ENT.</span><span><code>CanonicalBlockEntityCoordinator</code> dispatches proven prefixes; unknown mods hit a Serial Fence.</span></li>
          <li><span class="compare-step-key">SAFETY</span><span>All switches default to <code>false</code>; at-most-once execution (no replay) and physical join on unload.</span></li>
        </ul>
      </div>
    </div>
  </div>
  <figure class="diagram-frame">
    <img src="assets/arch-region-hierarchy.en.svg" alt="Execution hierarchy comparing Stock Forge single-thread flat queue against Forgia's independent leased Regions running in parallel on separate Workers." />
    <figcaption class="diagram-caption">
      <span>EXECUTION HIERARCHY // INDEPENDENT REGION PARALLELISM</span>
      <span>PER-REGION LEASE · MULTI-CORE WORKERS · BARRIER JOIN</span>
    </figcaption>
  </figure>
  <a class="text-link" href="architecture.en/">Explore full Stock vs. Forgia architecture &amp; subsystem matrix <span>↗</span></a>
</section>

<section class="architecture-section">
  <div class="section-heading">
    <div class="section-index">02 <span>— Core Design</span></div>
    <h2>Make concurrency<br><em>explicit.</em></h2>
  </div>
  <figure class="diagram-frame">
    <img src="assets/arch-be-pipeline.svg" alt="Forgia Canonical Block Entity Execution Pipeline showing O(1) prefix proof, lease + preWake, direct worker scopes, physical join and Serial Fence fallback." />
    <figcaption class="diagram-caption">
      <span>CANONICAL BLOCK ENTITY HOT PATH (main @ 7efd88d)</span>
      <span>O(1) PREFIX PROOF · ZERO-THREADLOCAL WORKER SCOPES · FULL SERIAL FENCE</span>
    </figcaption>
  </figure>
  <div class="focus-grid">
    <article class="focus-item">
      <span class="focus-number">A / 01</span>
      <h3>Region Leases &amp; Workers</h3>
      <p>Partition worlds into <code>FIXED_3X3</code> (3..32 chunk) grids or experimental loaded islands, backed by Fixed or Persistent work-stealing <code>RegionWorkerThread</code>s.</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 02</span>
      <h3>Canonical BE &amp; Resources</h3>
      <p>Preserve vanilla ticker order with a monotonic cursor, O(1) incremental prefix independence, sparse-cleared <code>ResourceTable</code>, and nonblocking wave leases.</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 03</span>
      <h3>Fail-Closed Compatibility</h3>
      <p>Unknown mod callbacks, Mixin-transformed methods, or Create contraptions never run speculatively off-thread—they physically join workers and run once on the server.</p>
      <span class="focus-rule"></span>
    </article>
  </div>
</section>

<section class="perf-section">
  <div class="section-heading">
    <div>
      <div class="section-index">03 <span>— Measured Performance</span></div>
      <h2>Where workers win,<br><em>and where serial stays faster.</em></h2>
    </div>
    <p class="status-lede">Every measurement below verifies full state or NBT equivalence. Forgia accelerates entity-heavy and multi-player workloads while candidly documenting coordination overhead on tiny stationary BEs.</p>
  </div>

  <div class="perf-grid">
    <div class="perf-card">
      <div class="perf-card-top">
        <h3>1024 Entities (Cows / Villagers)</h3>
        <span class="perf-delta">2.1× – 4.5× faster</span>
      </div>
      <p class="perf-sub">4-core/8-thread, JDK 21, ZGC, fixed 3×3 regions, 4 workers (lower MSPT is better).</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">1024 Cows Off</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">27.3 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 Cows On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:22%"></div></div><span class="bar-val">6.0 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 Villagers Off</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">54.5 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 Villagers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:47%"></div></div><span class="bar-val">25.7 mspt</span></div>
      </div>
      <p class="perf-note">1024 Zombies: 22.8 → 6.6 mspt (3.4×). 289 chunks spawning: 4.13–4.62 → 2.79–3.93 ms (1.15–1.65×).</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>32 Real TCP Players (n32-spread)</h3>
        <span class="perf-delta">14.7× vs .154 · -6.4% vs Serial</span>
      </div>
      <p class="perf-sub">32 connected players spread across regions, persistent workers, 10s warmup + 30s measured.</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">.154 Persistent On</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">58.30 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 Serial (Off)</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:14%"></div></div><span class="bar-val">4.24 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 2 Workers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:14%"></div></div><span class="bar-val">4.22 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 4 Workers On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:12%"></div></div><span class="bar-val">3.97 ms</span></div>
      </div>
      <p class="perf-note">Two-phase dispatch cost dropped 2.450 → 0.918 ms/tick; ping p99 improved 314.11 → 6.35 ms.</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>64 Burning Furnaces (Resources ON)</h3>
        <span class="perf-delta">1.67× MSPT · -72% Alloc</span>
      </div>
      <p class="perf-sub">400 warmup + 2×400 ticks, 4 workers, 2 GiB G1. Exact before/after NBT &amp; item conservation verified.</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">.98 Before Reuse</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">10.36 ms</span></div>
        <div class="bar-row"><span class="bar-label">Pre-7efd88d Base</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:22%"></div></div><span class="bar-val">2.24 ms</span></div>
        <div class="bar-row"><span class="bar-label">Forgia 7efd88d Opt</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:13%"></div></div><span class="bar-val">1.34 ms</span></div>
        <div class="bar-row"><span class="bar-label">Forgia Serial / Stock</span><div class="bar-track"><div class="bar-fill bar-fill--serial" style="width:4%"></div></div><span class="bar-val">0.35 / 0.22 ms</span></div>
      </div>
      <p class="perf-note">Alloc dropped 253 → 18.68 → 5.18 MiB/s (3.61× reduction). Light stationary BEs remain faster in serial.</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>6000-Tick Mixed Production Soak</h3>
        <span class="perf-delta perf-delta--neutral">0.976× mean vs Off</span>
      </div>
      <p class="perf-sub">Generated terrain: villagers, mobs, hopper/furnace lines, farm, border redstone clock &amp; fake player.</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">Pre-deferral On</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">7.35–8.28 ms</span></div>
        <div class="bar-row"><span class="bar-label">Same-build Off</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:82%"></div></div><span class="bar-val">6.25–6.70 ms</span></div>
        <div class="bar-row"><span class="bar-label">Deferred + Lift On</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:80%"></div></div><span class="bar-val">6.13–6.72 ms</span></div>
        <div class="bar-row"><span class="bar-label">Persistent + Packed</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:84%"></div></div><span class="bar-val">6.69 ms</span></div>
      </div>
      <p class="perf-note">0 ownership violations, 6200/6200 redstone consistency, 100% dispatched chunks on workers.</p>
    </div>
  </div>
  <a class="text-link" href="benchmarks.en/">View complete benchmark tables &amp; methodology <span>↗</span></a>
</section>

<section class="status-section">
  <div class="section-index">04 <span>— Status &amp; Boundaries</span></div>
  <div class="status-layout">
    <div>
      <h2>Research in<br><em>progress.</em></h2>
      <p class="status-lede">Forgia is active systems research—not a drop-in Folia clone and not a promise that arbitrary modpacks parallelize automatically.</p>
    </div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">C6 Acceptance</span><span>170/170 required GameTests pass on both Persistent and Fixed profiles (10 skipped benchmark/soak tests excluded).</span></div>
      <div class="status-row"><span class="status-key">Lifecycle &amp; GC</span><span>Stopped-server real-Level GC certified (28/28 references cleared); runtime mod hot-unload remains unsupported.</span></div>
      <div class="status-row"><span class="status-key">Serial Boundaries</span><span>Players, structures/pistons, Create contraptions and unverified mod callbacks stay on the server thread.</span></div>
      <div class="status-row"><span class="status-key">Production Defaults</span><span>All 16+ region-threading and resource switches default to <code>false</code>; enable explicitly per workload.</span></div>
      <a class="text-link" href="status.en/">Read detailed engineering status &amp; receipts <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">05 <span>— Documentation</span></div>
  <div class="docs-heading"><h2>Explore the<br><em>codebase.</em></h2><span>Bilingual English &amp; Chinese reference</span></div>
  <div class="docs-links">
    <a href="architecture.en/"><span class="docs-code">01</span><span><strong>Architecture: Stock vs. Forgia</strong><small>Execution model, phase alternation, canonical BE pipeline &amp; safety invariants</small></span><span class="docs-arrow">↗</span></a>
    <a href="benchmarks.en/"><span class="docs-code">02</span><span><strong>Performance Benchmarks</strong><small>1024 entities, 32-player scale, 64-furnace BE hot-path &amp; 6000-tick soak data</small></span><span class="docs-arrow">↗</span></a>
    <a href="api/README.en/"><span class="docs-code">03</span><span><strong>Parallel API &amp; Mod Adapters</strong><small>Public contracts, audited block-entity execution, Create &amp; MSC adapters</small></span><span class="docs-arrow">↗</span></a>
    <a href="modules/README.en/"><span class="docs-code">04</span><span><strong>Engine Internals</strong><small>Region engine, entity storage, chunk border fixpoint &amp; observability</small></span><span class="docs-arrow">↗</span></a>
    <a href="architecture.zh/"><span class="docs-code">中</span><span><strong>中文架构对比与性能报告</strong><small>原版 Forge vs Forgia 架构对比、实测性能图表与完整中文模块文档</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band">
  <span>Open engineering · NexForge Labs</span>
  <a href="https://github.com/NexForgeLabs/Forgia">Follow development on GitHub <span>↗</span></a>
</div>

<div class="site-disclaimer">Forgia is experimental software. Back up worlds and configurations before testing. Compatibility and performance depend on the exact modpack, configuration and workload.</div>
<div class="design-credit">Design approach adapted from <a href="https://github.com/heydamir/unslop">Unslop</a> by Damir Sokolovsky · CC BY 4.0</div>
