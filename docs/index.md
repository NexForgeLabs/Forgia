<div class="hero" markdown>
  <div class="hero-copy">
    <div class="eyebrow"><span class="eyebrow-mark"></span> NEXFORGELABS <span class="eyebrow-sep">/</span> SERVER ENGINEERING</div>
    <h1>More room<br>to <em>run</em>.</h1>
    <p class="hero-lede">Forgia is an experimental regionized server core for NeoForge 1.21.1 — bringing region ownership and parallel execution research to heavily modded Minecraft.</p>
    <div class="hero-actions">
      <a class="action-primary" href="README.en/">Explore the project <span>↗</span></a>
      <a class="action-secondary" href="https://github.com/NexForgeLabs/Forgia">View source <span>↗</span></a>
    </div>
    <div class="hero-footnote"><span class="status-dot"></span> ACTIVE DEVELOPMENT <span class="hero-footnote-divider">·</span> EXPERIMENTAL SOFTWARE</div>
  </div>
  <div class="hero-visual">
    <div class="visual-topline"><span>EXECUTION MODEL</span><span>FIG. 01 — CONCEPT</span></div>
    <img src="assets/region-topology.svg" alt="Conceptual diagram showing a server coordinator assigning work across three region workers." />
    <div class="visual-caption"><span>01 / REGION OWNERSHIP</span><span>COORDINATED, NOT SHARED</span></div>
  </div>
</div>

<div class="spec-strip">
  <div><span class="spec-label">TARGET PLATFORM</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">CORE MODEL</span><strong>Regionized <b>execution</b></strong></div>
  <div><span class="spec-label">PROJECT PHASE</span><strong>Active <b>research</b></strong></div>
  <div><span class="spec-label">MAINTAINED BY</span><strong>NexForge <b>Labs</b></strong></div>
</div>

<section class="intro-section">
  <div class="section-index">01 <span>— THE IDEA</span></div>
  <div class="intro-content">
    <h2>Concurrency needs<br>a <em>clear owner.</em></h2>
    <p>Traditional server ticking is built around a central thread. Forgia explores a different boundary: divide the world into independently owned regions, then move eligible work onto workers without pretending that every interaction is isolated.</p>
    <p>The hard part is not creating more threads. It is defining who may touch what, when ownership changes, and how cross-region work remains correct.</p>
  </div>
</section>

<section class="architecture-section">
  <div class="section-heading">
    <div class="section-index">02 <span>— ENGINEERING FOCUS</span></div>
    <h2>Built around<br><em>ownership.</em></h2>
  </div>
  <div class="focus-grid">
    <article class="focus-item">
      <span class="focus-number">A / 01</span>
      <h3>Region scheduling</h3>
      <p>Partition world work into region-owned execution domains, with explicit scheduling and handoff boundaries.</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 02</span>
      <h3>Block entity safety</h3>
      <p>Define admission, ownership and coordination contracts before allowing modded block-entity work off-thread.</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 03</span>
      <h3>Mod compatibility</h3>
      <p>Keep NeoForge and real mod workloads central to the design. Unknown or unsafe paths must fail closed.</p>
      <span class="focus-rule"></span>
    </article>
  </div>
</section>

<section class="status-section">
  <div class="section-index">03 <span>— HONEST STATUS</span></div>
  <div class="status-layout">
    <div>
      <h2>Experimental<br>by <em>design.</em></h2>
      <p class="status-lede">Forgia is under active development. It is not a drop-in, production-ready Folia replacement, and parallel execution is not universally safe for arbitrary mods.</p>
    </div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">IMPLEMENTED FOUNDATION</span><span>Region-threading infrastructure and selected worker execution paths.</span></div>
      <div class="status-row"><span class="status-key">ONGOING WORK</span><span>Broader ownership coverage, cross-region coordination and scheduler evolution.</span></div>
      <div class="status-row"><span class="status-key">KNOWN LIMITS</span><span>Some player, mod callback and world interaction paths remain serialized or constrained.</span></div>
      <div class="status-row"><span class="status-key">NOT A PROMISE</span><span>No universal mod compatibility or fixed player-capacity guarantee.</span></div>
      <a class="text-link" href="README.en/">Read implementation status and limitations <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">04 <span>— DEVELOPER RESOURCES</span></div>
  <div class="docs-heading"><h2>Go <em>deeper.</em></h2><span>Maintained documentation · English / 中文</span></div>
  <div class="docs-links">
    <a href="api/README.en/"><span class="docs-code">01</span><span><strong>Parallel API</strong><small>Public contracts, capabilities and scheduling</small></span><span class="docs-arrow">↗</span></a>
    <a href="adapters/README.en/"><span class="docs-code">02</span><span><strong>Mod adapters</strong><small>Integration guides and adapter trust model</small></span><span class="docs-arrow">↗</span></a>
    <a href="modules/README.en/"><span class="docs-code">03</span><span><strong>Engine internals</strong><small>Subsystem architecture and implementation notes</small></span><span class="docs-arrow">↗</span></a>
    <a href="README.zh/"><span class="docs-code">中</span><span><strong>中文文档</strong><small>项目说明、API 与模块文档</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band">
  <span>OPEN ENGINEERING</span>
  <a href="https://github.com/NexForgeLabs/Forgia">Follow development on GitHub <span>↗</span></a>
</div>

<div class="site-disclaimer">Forgia is experimental software. Back up worlds and configurations before testing. Compatibility and performance depend on the exact modpack, configuration and workload.</div>
