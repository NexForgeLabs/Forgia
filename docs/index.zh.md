<div class="hero" markdown>
  <div class="hero-copy">
    <p class="eyebrow"><span class="eyebrow-mark"></span> NexForge Labs <span class="eyebrow-sep">/</span> NeoForge 1.21.1</p>
    <h1>让模组世界<br><em>真正用上多核。</em></h1>
    <p class="hero-lede">Forgia 是面向 NeoForge 的区域化服务端运行时。它将已加载世界划分为动态合并的 Region，把符合条件的实体、方块实体与世界 Tick 工作交给 Worker 并行执行，同时通过显式所有权边界和 Fail-Closed 回退机制控制并发风险。</p>
    <div class="hero-actions">
      <a class="action-primary" href="../architecture.zh/">了解架构 <span>↗</span></a>
      <a class="action-secondary" href="../benchmarks.zh/">C7 生产实测 <span>↗</span></a>
      <a class="action-secondary" href="../">English Home <span>↗</span></a>
    </div>
    <p class="hero-footnote"><span class="status-dot"></span> C7 生产验证已完成 <span class="hero-footnote-divider">·</span> NeoForge 1.21.1</p>
  </div>
  <figure class="hero-visual">
    <div class="visual-topline"><span>Forgia / 区域化运行时</span><span>架构 / 01</span></div>
    <img src="../assets/arch-region-hierarchy.zh.svg" alt="动态区域拓扑与并行 Worker 执行结构图。" />
    <figcaption class="visual-caption"><span>动态加载岛 Region</span><span>显式所有权 · Fail-Closed 兼容边界</span></figcaption>
  </figure>
</div>

<div class="spec-strip">
  <div><span class="spec-label">目标平台</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">生产整合包</span><strong><b>188</b> 个启用 Mod</strong></div>
  <div><span class="spec-label">多人耐久测试</span><strong><b>20 TPS</b> · 80 分钟</strong></div>
  <div><span class="spec-label">高速跑图测试</span><strong><b>179</b> 个活跃 Region</strong></div>
</div>

<section class="arch-section-home">
  <div class="section-heading">
    <div><div class="section-index">01 <span>— Forgia 是什么</span></div><h2>区域化并发执行，<br><em>不只是多开几个线程。</em></h2></div>
    <p class="status-lede">Minecraft 世界和模组回调中存在大量共享状态与单线程假设。Forgia 显式管理资源所有权，让相互独立的 Region 并发执行；无法证明兼容的工作仍留在服务端线程。</p>
  </div>
  <div class="compare-grid">
    <div class="compare-card compare-card--stock">
      <div class="compare-card-head"><h3>原版 NeoForge</h3><span class="compare-badge">顺序 Tick</span></div>
      <div class="compare-card-body">
        <p class="compare-lead">大部分世界模拟都竞争服务端线程的 50ms Tick 预算。</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">世界模拟</span><span>实体、方块实体与大量世界更新沿单一 Tick 流程执行。</span></li>
          <li><span class="compare-step-key">模组生态</span><span>模组通常依赖隐式的服务端线程访问共享世界状态。</span></li>
          <li><span class="compare-step-key">扩展瓶颈</span><span>分散的玩家基地和高密度实体可能争抢同一个 CPU 核心。</span></li>
        </ul>
      </div>
    </div>
    <div class="compare-card compare-card--forgia">
      <div class="compare-card-head"><h3>Forgia</h3><span class="compare-badge">GLOBAL ↔ REGION</span></div>
      <div class="compare-card-body">
        <p class="compare-lead">独立 Region 在显式所有权租约保护下，将合格工作交给 Worker 并行执行。</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">动态拓扑</span><span><code>DYNAMIC_CLUSTER</code> 将已加载 Section 动态合并、拆分为连通的 Region 岛屿。</span></li>
          <li><span class="compare-step-key">通用沙盒</span><span>普通模组方块实体可进入通用 Region Sandbox，无需每个模组单独编写 Adapter。</span></li>
          <li><span class="compare-step-key">安全边界</span><span>未知或不兼容回调 Fail-Closed 回退串行路径，不会盲目重放已产生副作用的代码。</span></li>
        </ul>
      </div>
    </div>
  </div>
  <a class="text-link" href="../architecture.zh/">查看完整架构与安全模型 <span>↗</span></a>
</section>

<section class="perf-section">
  <div class="section-heading">
    <div><div class="section-index">02 <span>— 最新生产验证</span></div><h2>在真实的<br><em>188 Mod 整合包上测试。</em></h2></div>
    <p class="status-lede">C7 使用 SkyExplorer 生产整合包，包含 Create、Cataclysm、Sable、TacZ 等模组。以下是特定负载下的观测结果，不代表所有整合包都能获得相同加速。</p>
  </div>
  <div class="perf-grid">
    <div class="perf-card"><div class="perf-card-top"><h3>多人高负载</h3><span class="perf-delta">20.0 TPS</span></div><p class="perf-sub">44–86 个活跃 Region · 267–825 区块 · 80 分钟耐久测试</p><p><strong>18.2–28.4 ms MSPT</strong></p><p class="perf-note">记录的 80 分钟测试窗口内无崩溃。</p></div>
    <div class="perf-card"><div class="perf-card-top"><h3>高速跑图</h3><span class="perf-delta">20.0 TPS</span></div><p class="perf-sub">179 个活跃 Region · 530 个区块</p><p><strong>平均 18.8 ms MSPT</strong></p><p class="perf-note">1 分钟 Max 为 87.2 ms；进程 CPU 峰值 407%（约 4 个逻辑核心忙碌）。</p></div>
    <div class="perf-card"><div class="perf-card-top"><h3>方块实体执行</h3><span class="perf-delta">72–82%</span></div><p class="perf-sub">C7 负载中由 Worker 执行的 BE 工作占比。</p><p><strong>自动降级 2–3 种 BE 类型</strong></p><p class="perf-note">这是 Worker 执行占比，不代表 72–82% 的所有模组类型都已兼容。</p></div>
    <div class="perf-card"><div class="perf-card-top"><h3>串行 Fence 开销</h3><span class="perf-delta">下降 91%</span></div><p class="perf-sub">与此前记录状态的对比。</p><p><strong>4,116 ms → 360 ms</strong></p><p class="perf-note">报告中的 Fence 次数从 911,115 降至 18,446。</p></div>
  </div>
  <a class="text-link" href="../benchmarks.zh/">查看测试条件、限制与历史基准 <span>↗</span></a>
</section>

<section class="architecture-section">
  <div class="section-heading"><div><div class="section-index">03 <span>— 核心系统</span></div><h2>以资源所有权<br><em>约束并发。</em></h2></div></div>
  <div class="focus-grid">
    <article class="focus-item"><span class="focus-number">A / 01</span><h3>Dynamic Cluster</h3><p>已加载 Section 动态合并为连通 Region；拓扑变化后，空置 Section 会延迟拆分。这是默认区域策略。</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 02</span><h3>通用 Region Sandbox</h3><p>普通第三方方块实体无需逐模组编写 Adapter。越界访问和不安全行为会被隔离，并可降级回串行执行。</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 03</span><h3>独立 Region 时钟</h3><p>各 Region 可独立调度工作，不必等待所有 Region 在同一个全局 Tick Barrier 同步完成；全局世界状态仍通过协调边界处理。</p><span class="focus-rule"></span></article>
    <article class="focus-item"><span class="focus-number">A / 04</span><h3>专用兼容层</h3><p>对于 Create 这类拥有全局网络或跨区不变量的复杂模组，可使用专用兼容 Adapter。大多数普通模组不需要 Adapter。</p><span class="focus-rule"></span></article>
  </div>
</section>

<section class="status-section">
  <div class="section-index">04 <span>— 项目状态</span></div>
  <div class="status-layout">
    <div><h2>已完成生产验证。<br><em>仍在持续开发。</em></h2><p class="status-lede">C7 已完成文档所述 188 Mod 负载的生产验证。Forgia 尚不宣称自己是所有模组包都能直接替换的通用稳定版。</p></div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">平台</span><span>NeoForge 1.21.1；本网站描述当前 1.21.1 开发线。</span></div>
      <div class="status-row"><span class="status-key">区域策略</span><span><code>DYNAMIC_CLUSTER</code> 为默认策略；旧布局仅用于兼容和特定测试。</span></div>
      <div class="status-row"><span class="status-key">模组兼容</span><span>普通 BE 使用通用 Sandbox；复杂共享状态行为使用专用 Adapter 或串行围栏。</span></div>
      <div class="status-row"><span class="status-key">正式发布</span><span>Forgia 1.21.1 Stable 仍是未来里程碑；当前计划以 C20 为目标。</span></div>
      <a class="text-link" href="../status.zh/">查看工程状态与历史证据 <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">05 <span>— 文档</span></div>
  <div class="docs-heading"><h2>了解整个<br><em>项目。</em></h2><span>中英文双语参考文档</span></div>
  <div class="docs-links">
    <a href="../architecture.zh/"><span class="docs-code">01</span><span><strong>架构设计</strong><small>区域拓扑、执行阶段、资源所有权与安全模型</small></span><span class="docs-arrow">↗</span></a>
    <a href="../benchmarks.zh/"><span class="docs-code">02</span><span><strong>性能基准</strong><small>C7 生产负载、受控测试与历史测量数据</small></span><span class="docs-arrow">↗</span></a>
    <a href="../configuration.zh/"><span class="docs-code">03</span><span><strong>配置与调优</strong><small>运行时开关、Region Worker 与硬件配置</small></span><span class="docs-arrow">↗</span></a>
    <a href="../api/README.zh/"><span class="docs-code">04</span><span><strong>并行 API 与 Adapter</strong><small>模组开发者接口与专用兼容层</small></span><span class="docs-arrow">↗</span></a>
    <a href="../"><span class="docs-code">EN</span><span><strong>English documentation</strong><small>Architecture, benchmarks, configuration and engineering status</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band"><span>开放工程 · NexForge Labs</span><a href="https://github.com/NexForgeLabs/Forgia">在 GitHub 关注开发进度 <span>↗</span></a></div>
<div class="site-disclaimer">Forgia 仍处于持续开发阶段。测试前请备份世界与配置。兼容性和性能取决于具体构建、整合包、配置及负载。除文档明确说明外，并行执行开关仍需用户主动启用。</div>
