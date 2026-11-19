<div class="hero" markdown>
  <div class="hero-copy">
    <p class="eyebrow"><span class="eyebrow-mark"></span> NexForge Labs <span class="eyebrow-sep">/</span> NeoForge 1.21.1 运行时</p>
    <h1>面向模组世界的<br><em>区域化并发执行引擎。</em></h1>
    <p class="hero-lede">Forgia 将原版 Forge / NeoForge 的单线程服务端主循环改造为交替执行的 <code>GLOBAL</code> 与 <code>REGION</code> 阶段——结合显式空间租约、工作线程池并行以及面向未审计模组回调的 Fail-Closed 串行围栏（Serial Fence）。</p>
    <div class="hero-actions">
      <a class="action-primary" href="../architecture.zh/">架构对比：原版 vs Forgia <span>↗</span></a>
      <a class="action-secondary" href="../benchmarks.zh/">实测性能数据 <span>↗</span></a>
      <a class="action-secondary" href="../">English Home <span>↗</span></a>
    </div>
    <p class="hero-footnote"><span class="status-dot"></span> C6 认证范围（固定布局） <span class="hero-footnote-divider">·</span> 所有实验开关默认关闭</p>
  </div>
  <figure class="hero-visual">
    <div class="visual-topline"><span>原版 NeoForge vs Forgia</span><span>架构蓝图 / 01</span></div>
    <img src="../assets/arch-stock-vs-forgia.zh.svg" alt="对比原版 Forge/NeoForge 单线程执行模型与 Forgia 交替 GLOBAL/REGION 工作线程模型的架构图。" />
    <figcaption class="visual-caption"><span>单服务端主线程 → 显式区域租约</span><span>保留 Fail-Closed 串行围栏</span></figcaption>
  </figure>
</div>

<div class="spec-strip">
  <div><span class="spec-label">基线目标</span><strong>NeoForge <b>1.21.1</b></strong></div>
  <div><span class="spec-label">实体并行度</span><strong>最高 <b>4.5×</b>（1024 只牛）</strong></div>
  <div><span class="spec-label">32 玩家规模</span><strong><b>3.97 ms</b>（较串行 -6.4%）</strong></div>
  <div><span class="spec-label">BE 热路径（64 熔炉）</span><strong><b>1.67×</b> MSPT · <b>-72%</b> 内存分配</strong></div>
</div>

<section class="arch-section-home">
  <div class="section-heading">
    <div>
      <div class="section-index">01 <span>— 架构对比</span></div>
      <h2>单线程全局共享 vs<br><em>显式所有权分区。</em></h2>
    </div>
    <p class="status-lede">原版 Forge 在单一服务端线程的共享内存中顺序执行每个 tick 切片；Forgia 在保留原版因果顺序的同时，将合格工作切分至持有租约的 Worker 区域并行执行。</p>
  </div>

  <div class="compare-grid">
    <div class="compare-card compare-card--stock">
      <div class="compare-card-head">
        <h3>原版 Forge / NeoForge</h3>
        <span class="compare-badge">单服务端线程</span>
      </div>
      <div class="compare-card-body">
        <p class="compare-lead">全部世界模拟——区块、实体、方块实体、红石与玩家——竞争同一个 50 ms 串行主循环。</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">执行模型</span><span><code>ServerLevel.tick</code> 在单核顺序运行；异步 Future 仅限于区块 IO 与生成。</span></li>
          <li><span class="compare-step-key">所有权</span><span>隐式全局线程亲和性；模组可自由修改邻近区块或共享注册表。</span></li>
          <li><span class="compare-step-key">方块实体</span><span>扁平的 <code>Level.tickBlockEntities</code> 循环串行执行每一个熔炉、漏斗与机器。</span></li>
          <li><span class="compare-step-key">扩展瓶颈</span><span>高实体数量或分散的多人基地很快饱和单核 CPU。</span></li>
        </ul>
      </div>
    </div>

    <div class="compare-card compare-card--forgia">
      <div class="compare-card-head">
        <h3>Forgia 区域化核心</h3>
        <span class="compare-badge">GLOBAL ↔ REGION</span>
      </div>
      <div class="compare-card-body">
        <p class="compare-lead">在静默的 <code>GLOBAL</code> 协调边界与并行的 <code>REGION</code> Worker 批次间交替执行——绝不假设任意模组代码线程安全。</p>
        <ul class="compare-steps">
          <li><span class="compare-step-key">执行模型</span><span><code>RegionWorkerThread</code> 线程池基于直连线程字段与 <code>preWake</code> 并行执行已租约区域。</span></li>
          <li><span class="compare-step-key">所有权</span><span><code>RegionState</code> 空间租约 + <code>BlockEntityResourceOwnership</code> 读写（READ/WRITE）作用域。</span></li>
          <li><span class="compare-step-key">方块实体</span><span><code>CanonicalBlockEntityCoordinator</code> 派发已证明前缀；未知模组触发串行围栏。</span></li>
          <li><span class="compare-step-key">安全边界</span><span>所有开关默认 <code>false</code>；至多执行一次（不重放），卸载时物理 join。</span></li>
        </ul>
      </div>
    </div>
  </div>
  <figure class="diagram-frame">
    <img src="../assets/arch-region-hierarchy.zh.svg" alt="对比原版 Forge 单线程扁平队列与 Forgia 在独立 Worker 上并行运行已租约区域的执行层级图。" />
    <figcaption class="diagram-caption">
      <span>执行层级 // 独立区域并行</span>
      <span>按区域租约 · 多核 WORKER · 屏障 JOIN</span>
    </figcaption>
  </figure>
  <a class="text-link" href="../architecture.zh/">查看完整原版 vs Forgia 架构与子系统矩阵 <span>↗</span></a>
</section>

<section class="architecture-section">
  <div class="section-heading">
    <div class="section-index">02 <span>— 核心设计</span></div>
    <h2>让并发<br><em>显式且可验证。</em></h2>
  </div>
  <figure class="diagram-frame">
    <img src="../assets/arch-be-pipeline.zh.svg" alt="Forgia Canonical 方块实体执行管线：O(1) 前缀证明、租约与 preWake、零 ThreadLocal Worker 作用域、物理 join 与串行围栏回退。" />
    <figcaption class="diagram-caption">
      <span>CANONICAL 方块实体热路径（main @ 7efd88d）</span>
      <span>O(1) 前缀证明 · 零 THREADLOCAL WORKER 作用域 · 完整串行围栏</span>
    </figcaption>
  </figure>
  <div class="focus-grid">
    <article class="focus-item">
      <span class="focus-number">A / 01</span>
      <h3>区域租约与工作线程</h3>
      <p>将世界划分为 <code>FIXED_3X3</code>（3..32 区块）网格或实验性加载岛屿（<code>DYNAMIC_CLUSTER</code>），由 Fixed 或 Persistent 窃取型 <code>RegionWorkerThread</code> 执行。</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 02</span>
      <h3>Canonical BE 与资源作用域</h3>
      <p>以单调游标保持原版 ticker 顺序，结合 O(1) 增量前缀独立性、稀疏清理的 <code>ResourceTable</code> 与非阻塞波次租约。</p>
      <span class="focus-rule"></span>
    </article>
    <article class="focus-item">
      <span class="focus-number">A / 03</span>
      <h3>Fail-Closed 兼容性</h3>
      <p>未知模组回调、Mixin 改造方法或机械动力动态装置绝不会在后台线程投机执行——它们会物理 join Worker 并在主线程执行一次。</p>
      <span class="focus-rule"></span>
    </article>
  </div>
</section>

<section class="perf-section">
  <div class="section-heading">
    <div>
      <div class="section-index">03 <span>— 实测性能</span></div>
      <h2>Worker 在哪里显著领先，<br><em>以及哪里仍由串行占优。</em></h2>
    </div>
    <p class="status-lede">以下每项测量均验证完整的世界状态或 NBT 等价性。Forgia 在高实体与多人场景下提供显著加速，同时如实记录小型静态方块实体上的协调开销。</p>
  </div>

  <div class="perf-grid">
    <div class="perf-card">
      <div class="perf-card-top">
        <h3>1024 实体（牛 / 村民）</h3>
        <span class="perf-delta">2.1× – 4.5× 加速</span>
      </div>
      <p class="perf-sub">4 核/8 线程，JDK 21，ZGC，固定 3×3 区域，4 个 Worker（MSPT 越低越好）。</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">1024 牛（关闭）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">27.3 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 牛（开启）</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:22%"></div></div><span class="bar-val">6.0 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 村民（关闭）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">54.5 mspt</span></div>
        <div class="bar-row"><span class="bar-label">1024 村民（开启）</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:47%"></div></div><span class="bar-val">25.7 mspt</span></div>
      </div>
      <p class="perf-note">1024 僵尸：22.8 → 6.6 mspt（3.4×）。289 区块自然刷怪：4.13–4.62 → 2.79–3.93 ms（1.15–1.65×）。</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>32 真实 TCP 玩家（n32-spread）</h3>
        <span class="perf-delta">14.7× 对比 .154 · -6.4% 对比串行</span>
      </div>
      <p class="perf-sub">32 名分散在不同区域的在线玩家，Persistent Worker，10 秒预热 + 30 秒测量。</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">.154 Persistent 开启</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">58.30 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 串行（关闭）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:14%"></div></div><span class="bar-val">4.24 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 2 Workers 开启</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:14%"></div></div><span class="bar-val">4.22 ms</span></div>
        <div class="bar-row"><span class="bar-label">.221 4 Workers 开启</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:12%"></div></div><span class="bar-val">3.97 ms</span></div>
      </div>
      <p class="perf-note">两阶段派发开销从 2.450 降至 0.918 ms/tick；Ping P99 从 314.11 降至 6.35 ms。</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>64 燃烧熔炉（Resources 开启）</h3>
        <span class="perf-delta">1.67× MSPT · -72% 分配</span>
      </div>
      <p class="perf-sub">400 预热 + 2×400 测量 tick，4 个 Worker，2 GiB G1。验证前后 NBT 与物品守恒完全一致。</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">.98 复用优化前</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">10.36 ms</span></div>
        <div class="bar-row"><span class="bar-label">7efd88d 优化前基线</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:22%"></div></div><span class="bar-val">2.24 ms</span></div>
        <div class="bar-row"><span class="bar-label">Forgia 7efd88d 优化</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:13%"></div></div><span class="bar-val">1.34 ms</span></div>
        <div class="bar-row"><span class="bar-label">Forgia 串行 / 原版</span><div class="bar-track"><div class="bar-fill bar-fill--serial" style="width:4%"></div></div><span class="bar-val">0.35 / 0.22 ms</span></div>
      </div>
      <p class="perf-note">分配速率从 253 → 18.68 → 5.18 MiB/s（降低 3.61×）。轻量静态 BE 仍以串行更快。</p>
    </div>

    <div class="perf-card">
      <div class="perf-card-top">
        <h3>6000-Tick 混合生产耐久测试</h3>
        <span class="perf-delta perf-delta--neutral">均值 0.976× 对比关闭</span>
      </div>
      <p class="perf-sub">真实生成地形：村民、怪物、漏斗/熔炉产线、农田、跨边界红石时钟与假人。</p>
      <div class="bar-group">
        <div class="bar-row"><span class="bar-label">延迟提交前（开启）</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">7.35–8.28 ms</span></div>
        <div class="bar-row"><span class="bar-label">同构建关闭</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:82%"></div></div><span class="bar-val">6.25–6.70 ms</span></div>
        <div class="bar-row"><span class="bar-label">延迟提交 + Lift 开启</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:80%"></div></div><span class="bar-val">6.13–6.72 ms</span></div>
        <div class="bar-row"><span class="bar-label">Persistent + Packed</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:84%"></div></div><span class="bar-val">6.69 ms</span></div>
      </div>
      <p class="perf-note">0 次所有权违规，6200/6200 红石一致，100% 已派发区块运行在 Worker。</p>
    </div>
  </div>
  <a class="text-link" href="../benchmarks.zh/">查看完整基准测试表格与方法论 <span>↗</span></a>
</section>

<section class="status-section">
  <div class="section-index">04 <span>— 状态与边界</span></div>
  <div class="status-layout">
    <div>
      <h2>持续演进的<br><em>系统工程。</em></h2>
      <p class="status-lede">Forgia 是活跃的系统研究项目——它不是 Folia 的简单平替，也不承诺任意整合包无需适配即可自动并行。</p>
    </div>
    <div class="status-details">
      <div class="status-row"><span class="status-key">C6 验收</span><span>170/170 项必测 GameTest 在 Persistent 与 Fixed 两个配置上全部通过（另有 10 项跳过的基准/耐久测试）。</span></div>
      <div class="status-row"><span class="status-key">生命周期与 GC</span><span>停服真实 Level GC 已认证（28/28 跟踪引用全部回收）；运行时 Mod 热卸载仍不支持。</span></div>
      <div class="status-row"><span class="status-key">串行边界</span><span>结构/活塞、Create 动态装置及未审计模组回调保留在主线程；玩家区域 tick 为默认关闭的实验特性。</span></div>
      <div class="status-row"><span class="status-key">生产默认值</span><span>全部 16+ 项区域线程与资源开关默认 <code>false</code>；请按具体负载显式评估开启。</span></div>
      <a class="text-link" href="../status.zh/">阅读详细工程状态与测试回执 <span>↗</span></a>
    </div>
  </div>
</section>

<section class="docs-section">
  <div class="section-index">05 <span>— 文档导航</span></div>
  <div class="docs-heading"><h2>深入阅读<br><em>代码与文档。</em></h2><span>完整中英双语技术参考</span></div>
  <div class="docs-links">
    <a href="../architecture.zh/"><span class="docs-code">01</span><span><strong>架构对比：原版 vs Forgia</strong><small>执行模型、阶段交替、Canonical BE 管线与安全不变量</small></span><span class="docs-arrow">↗</span></a>
    <a href="../benchmarks.zh/"><span class="docs-code">02</span><span><strong>实测性能基准</strong><small>1024 实体、32 玩家规模、64 熔炉热路径与 6000-tick / 10 分钟耐久数据</small></span><span class="docs-arrow">↗</span></a>
    <a href="../api/README.zh/"><span class="docs-code">03</span><span><strong>并行 API 与模组适配器</strong><small>公开契约、受审计 BE 执行接口、Create 与 MSC 适配器</small></span><span class="docs-arrow">↗</span></a>
    <a href="../modules/README.zh/"><span class="docs-code">04</span><span><strong>引擎内部实现</strong><small>区域引擎、实体存储、区块边界不动点与可观测性</small></span><span class="docs-arrow">↗</span></a>
    <a href="../"><span class="docs-code">EN</span><span><strong>English Home &amp; Reference</strong><small>Switch to the English landing page and documentation index</small></span><span class="docs-arrow">↗</span></a>
  </div>
</section>

<div class="closing-band">
  <span>Open engineering · NexForge Labs</span>
  <a href="https://github.com/NexForgeLabs/Forgia">在 GitHub 上关注开发进度 <span>↗</span></a>
</div>

<div class="site-disclaimer">Forgia 为实验性软件。测试前请务必备份存档与配置。兼容性与性能表现取决于具体整合包、配置参数与实际负载。</div>
<div class="design-credit">Design approach adapted from <a href="https://github.com/heydamir/unslop">Unslop</a> by Damir Sokolovsky · CC BY 4.0</div>
