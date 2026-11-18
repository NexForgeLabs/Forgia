<div class="hero" markdown>
  <div class="hero-copy">
    <p class="eyebrow"><span class="eyebrow-mark"></span> NexForge Labs <span class="eyebrow-sep">/</span> Minecraft server runtime</p>
    <h1>Parallel execution<br>for <em>modded worlds.</em></h1>
    <p class="hero-lede">Forgia is an experimental regionized server core for NeoForge 1.21.1. It explores explicit region ownership and worker-thread execution while keeping cross-region coordination part of the design.</p>
    <div class="hero-actions">
      <a class="action-primary" href="README.en/">Read the project <span>↗</span></a>
      <a class="action-secondary" href="https://github.com/NexForgeLabs/Forgia">Browse source <span>↗</span></a>
    </div>
    <p class="hero-footnote"><span class="status-dot"></span> Active development <span class="hero-footnote-divider">·</span> Experimental software</p>
  </div>
  <figure class="hero-visual">
    <div class="visual-topline"><span>Region execution model</span><span>Conceptual model / 01</span></div>
    <img src="assets/region-topology.svg" alt="Conceptual diagram of region-owned work assigned to workers, with a separate cross-region coordination path." />
    <figcaption class="visual-caption"><span>Region ownership</span><span>Explicit boundaries · Coordinated handoff</span></figcaption>
  </figure>
</div>

<div class="spec-strip">
  <div><span class="spec-label">Target</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">Execution model</span><strong>Region <b>ownership</b></strong></div>
  <div><span class="spec-label">Development stage</span><strong>Active <b>research</b></strong></div>
  <div><span class="spec-label">Project</span><strong>NexForge <b>Labs</b></strong></div>
</div>

<section class="intro-section">
  <div class="section-index">01 <span>— The idea</span></div>
  <div class="intro-content">
    <h2>The hard part is<br><em>ownership.</em></h2>
    <p>Traditional server ticking is built around a central thread. Forgia explores a different boundary: divide the world into independently owned regions, then move eligible work onto workers without pretending that every interaction is isolated.</p>
    <p>The hard part is not creating more threads. It is defining who may touch what, when ownership changes, and how cross-region work remains correct.</p>
  </div>
</section>

<section class="architecture-section">
  <div class="section-heading">
    <div class="section-index">02 <span>— Engineering focus</span></div>
    <h2>Make concurrency<br><em>explicit.</em></h2>
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
  <div class="section-index">03 <span>— Project status</span></div>
  <div class="status-layout">
    <div>
      <h2>Research in<br><em>progress.</em></h2>
      <p class="status-lede">Forgia is under active development. It is not a drop-in, production-ready Folia replacement, and parallel execution is not universally safe for arbitrary mods.</p>
    </div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">Implemented foundation</span><span>Region-threading infrastructure and selected worker execution paths.</span></div>
      <div class="status-row"><span class="status-key">Ongoing work</span><span>Broader ownership coverage, cross-region coordination and scheduler evolution.</span></div>
      <div class="status-row"><span class="status-key">Known limits</span><span>Some player, mod callback and world interaction paths remain serialized or constrained.</span></div>
      <div class="status-row"><span class="status-key">What this does not promise</span><span>No universal mod compatibility or fixed player-capacity guarantee.</span></div>
      <a class="text-link" href="README.en/">Read implementation status and limitations <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">04 <span>— Developer resources</span></div>
  <div class="docs-heading"><h2>Explore the<br><em>codebase.</em></h2><span>Documentation in English and Chinese</span></div>
  <div class="docs-links">
    <a href="api/README.en/"><span class="docs-code">01</span><span><strong>Parallel API</strong><small>Public contracts, capabilities and scheduling</small></span><span class="docs-arrow">↗</span></a>
    <a href="adapters/README.en/"><span class="docs-code">02</span><span><strong>Mod adapters</strong><small>Integration guides and adapter trust model</small></span><span class="docs-arrow">↗</span></a>
    <a href="modules/README.en/"><span class="docs-code">03</span><span><strong>Engine internals</strong><small>Subsystem architecture and implementation notes</small></span><span class="docs-arrow">↗</span></a>
    <a href="README.zh/"><span class="docs-code">中</span><span><strong>中文文档</strong><small>项目说明、API 与模块文档</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band">
  <span>Open engineering</span>
  <a href="https://github.com/NexForgeLabs/Forgia">Follow development on GitHub <span>↗</span></a>
</div>

<div class="site-disclaimer">Forgia is experimental software. Back up worlds and configurations before testing. Compatibility and performance depend on the exact modpack, configuration and workload.</div>
<div class="design-credit">Design approach adapted from <a href="https://github.com/heydamir/unslop">Unslop</a> by Damir Sokolovsky · CC BY 4.0</div>
