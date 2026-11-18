# Forgia

<div class="home-hero" markdown>
  <div class="hero-kicker"><span class="kicker-mark"></span> NEXFORGE LABS <span class="kicker-divider">/</span> SERVER ENGINEERING</div>
  <h1>Regionized execution<br>for <span>modded worlds.</span></h1>
  <p class="hero-summary">An experimental NeoForge 1.21.1 server core exploring Folia-style region ownership, controlled worker execution, and explicit cross-region coordination.</p>
  <div class="hero-actions">
    <a class="button button-primary" href="README.en/">Explore the project <span>↗</span></a>
    <a class="button button-quiet" href="https://github.com/NexForgeLabs/Forgia">View source <span>↗</span></a>
  </div>
  <div class="hero-meta"><span class="live-indicator"></span> Active development <span class="meta-separator">·</span> Experimental software</div>
</div>

<div class="hero-facts">
  <div><span>PLATFORM</span><strong>NeoForge 1.21.1</strong></div>
  <div><span>MODEL</span><strong>Region ownership</strong></div>
  <div><span>PRIORITY</span><strong>Correctness first</strong></div>
</div>

<section class="home-section" markdown>
<div class="section-label">01 <span> / THE APPROACH</span></div>

## Concurrency starts with ownership.

Minecraft's server logic was not designed around arbitrary parallel access. Forgia approaches the problem by defining ownership boundaries first, then admitting only narrowly understood work to worker threads.

The goal is not to make every tick parallel. It is to make more work safely independent while preserving the original execution path whenever the required guarantees are missing.

<div class="principle-grid">
  <article><span class="principle-id">01 — SCHEDULING</span><h3>Region-owned work</h3><p>Partition eligible world work into region execution domains with explicit handoff and lifecycle boundaries.</p></article>
  <article><span class="principle-id">02 — SAFETY</span><h3>Audited admission</h3><p>Worker execution requires a known call path, resource footprint, and valid ownership. Unknown paths stay serial.</p></article>
  <article><span class="principle-id">03 — COMPATIBILITY</span><h3>Modded by design</h3><p>NeoForge and mod callbacks are central constraints, not an afterthought. Compatibility is earned path by path.</p></article>
</div>
</section>

<section class="home-section status-section" markdown>
<div class="section-label">02 <span> / ENGINEERING STATUS</span></div>

## Progress, with boundaries.

Forgia has implemented and tested selected region, player, and mod-payload execution paths. Those results apply only to their documented admission contracts and test profiles.

<div class="status-list">
  <div><span class="status-tag">IMPLEMENTED</span><p>Region execution infrastructure, controlled player tick paths, selected owner-routed packet handling, and an explicitly declared custom payload worker API.</p></div>
  <div><span class="status-tag">RESTRICTED</span><p>Unknown mods, packet types, callbacks, shared-state paths, and unverified resource access remain on the original serial path.</p></div>
  <div><span class="status-tag">NOT A CLAIM</span><p>No universal mod compatibility, arbitrary-mod parallelism, production-readiness guarantee, or fixed player-capacity promise.</p></div>
</div>
<p class="status-link"><a href="status.en/">Read the engineering status and evidence <span>↗</span></a></p>
</section>

<section class="home-section" markdown>
<div class="section-label">03 <span> / DEVELOPER DOCUMENTATION</span></div>

## Start with the contracts.

<div class="doc-grid">
  <a href="README.en/"><span class="doc-number">01</span><span><strong>Project overview</strong><small>Architecture, scope and current limitations</small></span><b>↗</b></a>
  <a href="api/README.en/"><span class="doc-number">02</span><span><strong>Public API</strong><small>Capabilities, lifecycle and execution contracts</small></span><b>↗</b></a>
  <a href="adapters/README.en/"><span class="doc-number">03</span><span><strong>Mod adapters</strong><small>Integration model and audit requirements</small></span><b>↗</b></a>
  <a href="modules/README.en/"><span class="doc-number">04</span><span><strong>Engine internals</strong><small>Subsystems and implementation notes</small></span><b>↗</b></a>
  <a href="README.zh/"><span class="doc-number">中</span><span><strong>中文文档</strong><small>项目说明、API 与引擎模块</small></span><b>↗</b></a>
</div>
</section>

<div class="home-footer"><div><span class="footer-mark">F.</span><span>Built in the open by NexForge Labs.</span></div><a href="https://github.com/NexForgeLabs/Forgia">GitHub repository ↗</a></div>
<div class="home-disclaimer">Experimental software. Back up worlds before testing. Compatibility and performance depend on the exact build, configuration, modpack and workload.</div>
