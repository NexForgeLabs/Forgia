<div class="hero" markdown>
  <div class="hero-copy">
    <p class="eyebrow"><span class="eyebrow-mark"></span> NexForge Labs <span class="eyebrow-sep">/</span> NeoForge 1.21.1</p>
    <h1>More cores for<br><em>modded worlds.</em></h1>
    <p class="hero-lede">Forgia is a regionized server runtime for NeoForge. It partitions loaded worlds into dynamically merged regions and moves eligible entity, block-entity and world work onto worker threads—while enforcing ownership boundaries and falling back to the server thread when compatibility cannot be proven.</p>
    <div class="hero-actions">
      <a class="action-primary" href="architecture.en/">How Forgia works <span>↗</span></a>
      <a class="action-secondary" href="benchmarks.en/">C7 production results <span>↗</span></a>
      <a class="action-secondary" href="index.zh/">中文首页 <span>↗</span></a>
    </div>
    <p class="hero-footnote"><span class="status-dot"></span> C7 production validation completed <span class="hero-footnote-divider">·</span> NeoForge 1.21.1</p>
  </div>
  <figure class="hero-visual">
    <div class="visual-topline"><span>Forgia / Regionized Runtime</span><span>Architecture / 01</span></div>
    <img src="assets/arch-region-hierarchy.en.svg" alt="Dynamic region hierarchy and parallel worker execution." />
    <figcaption class="visual-caption"><span>Dynamic loaded-island regions</span><span>Explicit ownership · Fail-closed compatibility</span></figcaption>
  </figure>
</div>

<div class="spec-strip">
  <div><span class="spec-label">Target</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">Production pack</span><strong><b>188</b> enabled mods</strong></div>
  <div><span class="spec-label">Multiplayer soak</span><strong><b>20 TPS</b> · 80 min</strong></div>
  <div><span class="spec-label">Exploration test</span><strong><b>179</b> active regions</strong></div>
</div>

<section class="arch-section-home">
  <div class="section-heading">
    <div>
      <div class="section-index">01 <span>— What Forgia is</span></div>
      <h2>Regionized execution,<br><em>not just more threads.</em></h2>
    </div>
    <p class="status-lede">Minecraft worlds contain shared state and decades of single-thread assumptions in mod callbacks. Forgia makes ownership explicit, schedules independent regions concurrently, and keeps unsafe or unverified work on the server thread.</p>
  </div>
  <div class="compare-grid">
    <div class="compare-card compare-card--stock">
      <div class="compare-card-head"><h3>Stock NeoForge</h3><span class="compare-badge">SEQUENTIAL TICK</span></div>
      <div class="compare-card-body">
        <p class="compare-lead">Most world simulation competes for the server thread's 50 ms tick budget.</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">WORLD</span><span>Entities, block entities and many world updates execute in a sequential tick flow.</span></li>
          <li><span class="compare-step-key">MODS</span><span>Mods commonly rely on implicit server-thread access to shared world state.</span></li>
          <li><span class="compare-step-key">SCALING</span><span>Dispersed player bases and entity-heavy areas can contend for the same core.</span></li>
        </ul>
      </div>
    </div>
    <div class="compare-card compare-card--forgia">
      <div class="compare-card-head"><h3>Forgia</h3><span class="compare-badge">GLOBAL ↔ REGION</span></div>
      <div class="compare-card-body">
        <p class="compare-lead">Independent regions run eligible work on a worker pool under explicit ownership leases.</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">TOPOLOGY</span><span><code>DYNAMIC_CLUSTER</code> merges and splits loaded sections into connected region islands.</span></li>
          <li><span class="compare-step-key">SANDBOX</span><span>Ordinary modded block entities can use the generic Region Sandbox without a per-mod adapter.</span></li>
          <li><span class="compare-step-key">SAFETY</span><span>Unknown or incompatible callbacks fail closed to a serial path; work is not blindly replayed.</span></li>
        </ul>
      </div>
    </div>
  </div>
  <a class="text-link" href="architecture.en/">Read the architecture and safety model <span>↗</span></a>
</section>

<section class="perf-section">
  <div class="section-heading">
    <div>
      <div class="section-index">02 <span>— Latest production validation</span></div>
      <h2>Tested against a<br><em>real 188-mod pack.</em></h2>
    </div>
    <p class="status-lede">C7 validation used the SkyExplorer production pack with Create, Cataclysm, Sable, TacZ and other major mods. These are workload-specific observations, not a universal speedup guarantee.</p>
  </div>
  <div class="perf-grid">
    <div class="perf-card">
      <div class="perf-card-top"><h3>Multiplayer load</h3><span class="perf-delta">20.0 TPS</span></div>
      <p class="perf-sub">44–86 active regions · 267–825 chunks · 80-minute soak</p>
      <p><strong>18.2–28.4 ms MSPT</strong></p>
      <p class="perf-note">No crashes during the recorded 80-minute validation window.</p>
    </div>
    <div class="perf-card">
      <div class="perf-card-top"><h3>Active exploration</h3><span class="perf-delta">20.0 TPS</span></div>
      <p class="perf-sub">179 active regions · 530 chunks</p>
      <p><strong>18.8 ms average MSPT</strong></p>
      <p class="perf-note">One-minute maximum 87.2 ms; process CPU reached 407% (~4 logical cores busy).</p>
    </div>
    <div class="perf-card">
      <div class="perf-card-top"><h3>Block-entity execution</h3><span class="perf-delta">72–82%</span></div>
      <p class="perf-sub">Share of measured BE execution performed on workers in the C7 workload.</p>
      <p><strong>2–3 BE types auto-demoted</strong></p>
      <p class="perf-note">This is worker execution share, not a claim that 72–82% of all mod types are compatible.</p>
    </div>
    <div class="perf-card">
      <div class="perf-card-top"><h3>Serial fence cost</h3><span class="perf-delta">−91%</span></div>
      <p class="perf-sub">C7 comparison against the earlier measured state.</p>
      <p><strong>4,116 ms → 360 ms</strong></p>
      <p class="perf-note">Fence count fell from 911,115 to 18,446 in the reported comparison.</p>
    </div>
  </div>
  <a class="text-link" href="benchmarks.en/">Read benchmark conditions, caveats and historical results <span>↗</span></a>
</section>

<section class="architecture-section">
  <div class="section-heading">
    <div><div class="section-index">03 <span>— Core systems</span></div><h2>Concurrency with<br><em>ownership boundaries.</em></h2></div>
  </div>
  <div class="focus-grid">
    <article class="focus-item"><span class="focus-number">A / 01</span><h3>Dynamic Cluster</h3><p>Loaded sections dynamically merge into connected regions and split after a delay when the topology changes. This is the default region policy.</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 02</span><h3>Generic Region Sandbox</h3><p>Ordinary third-party block entities do not need individual adapters. Boundary violations and unsafe behavior are isolated and can be demoted to serial execution.</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 03</span><h3>Independent Region Clock</h3><p>Regions can schedule their own work without requiring every region to finish at one global tick barrier. Global world state still uses coordinated boundaries.</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 04</span><h3>Dedicated Compatibility</h3><p>Complex mods with global graphs or cross-region invariants—such as Create—can use dedicated compatibility adapters. Most ordinary mods do not need one.</p><span class="focus-rule"></span></article>
  </div>
</section>

<section class="status-section">
  <div class="section-index">04 <span>— Project status</span></div>
  <div class="status-layout">
    <div><h2>Production-tested.<br><em>Still evolving.</em></h2><p class="status-lede">C7 production validation is complete for the documented 188-mod workload. Forgia is not yet presented as a universal drop-in replacement for every modpack.</p></div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">Platform</span><span>NeoForge 1.21.1; this documentation describes the current 1.21.1 development line.</span></div>
      <div class="status-row"><span class="status-key">Region policy</span><span><code>DYNAMIC_CLUSTER</code> is the default. Legacy layouts remain for compatibility and specific tests.</span></div>
      <div class="status-row"><span class="status-key">Mod compatibility</span><span>Generic Sandbox for ordinary BEs; dedicated adapters or serial fences for complex/shared-state behavior.</span></div>
      <div class="status-row"><span class="status-key">Release</span><span>Forgia 1.21.1 stable release is a future milestone; C20 is the current planned release target.</span></div>
      <a class="text-link" href="status.en/">Read engineering status and historical evidence <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">05 <span>— Documentation</span></div>
  <div class="docs-heading"><h2>Explore the<br><em>project.</em></h2><span>English &amp; Chinese references</span></div>
  <div class="docs-links">
    <a href="architecture.en/"><span class="docs-code">01</span><span><strong>Architecture</strong><small>Region topology, execution phases, ownership and safety model</small></span><span class="docs-arrow">↗</span></a>
    <a href="benchmarks.en/"><span class="docs-code">02</span><span><strong>Performance benchmarks</strong><small>C7 production workload, controlled tests and historical measurements</small></span><span class="docs-arrow">↗</span></a>
    <a href="configuration.en/"><span class="docs-code">03</span><span><strong>Configuration &amp; tuning</strong><small>Runtime switches, region workers and hardware profiles</small></span><span class="docs-arrow">↗</span></a>
    <a href="api/README.en/"><span class="docs-code">04</span><span><strong>Parallel API &amp; adapters</strong><small>Contracts for mod developers and dedicated compatibility layers</small></span><span class="docs-arrow">↗</span></a>
    <a href="index.zh/"><span class="docs-code">中</span><span><strong>中文文档</strong><small>项目介绍、架构、性能、配置与工程状态</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band"><span>Open engineering · NexForge Labs</span><a href="https://github.com/NexForgeLabs/Forgia">Follow development on GitHub <span>↗</span></a></div>
<div class="site-disclaimer">Forgia is experimental software. Back up worlds and configurations before testing. Compatibility and performance depend on the exact build, modpack, configuration and workload. Parallel execution switches remain opt-in unless explicitly documented otherwise.</div>
