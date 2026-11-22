# 架构对比：原版 Forge / NeoForge vs. Forgia

<div class="lang-pill-bar">
  <a href="../architecture.en/">English</a>
  <a class="active" href="../architecture.zh/">中文</a>
</div>

Forgia 基于 NeoForge 1.21.1 构建。面对模组生态复杂的共享状态回调，Forgia 没有用一把全局大锁掩盖并发，也没有假定任意模组天生线程安全，而是保留原版 Tick 阶段因果顺序，通过 **显式空间租约（Lease）**、**`GLOBAL` / `REGION` 交替阶段** 以及 **Fail-Closed 串行围栏（Serial Fence）** 实现受控并行。

!!! tip "3D 动画解说"
    想先看流程再看细节？打开 [Forgia 3D 架构动画流](visualizer/index.html)，用 6 个可交互步骤依次演示 GLOBAL/REGION 交替、空间租约、并行批次与屏障、跨区 Seqlock/邮箱，以及 Canonical 方块实体的串行围栏。

<figure class="diagram-frame">
  <img src="../assets/arch-stock-vs-forgia.zh.svg" alt="原版 Forge/NeoForge 与 Forgia 架构对比蓝图" />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 01 // 原版 NEOFORGE VS FORGIA 架构对比</span>
    <span>GLOBAL ↔ REGION 阶段交替 · 显式所有权边界</span>
  </figcaption>
</figure>

<figure class="diagram-frame">
  <img src="../assets/arch-region-hierarchy.zh.svg" alt="执行层级对比：原版 Forge 全局单队列串行 vs Forgia 按 Region 独立持有租约并行执行" />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 02 // 执行层级与 REGION 独立并行</span>
    <span>独立区域租约 · 多核 WORKER 并行 · 延迟跨区与串行围栏</span>
  </figcaption>
</figure>

---

## 1. 执行模型左右对比

<div class="compare-grid">
  <div class="compare-card compare-card--stock">
    <div class="compare-card-head">
      <h3>原版 Forge / NeoForge</h3>
      <span class="compare-badge">单服务器线程串行</span>
    </div>
    <div class="compare-card-body">
      <p class="compare-lead">整条服务端主循环在单一线程与共享可变内存中顺序执行。对模组兼容性零心智负担，但全部子系统共用单核 50 ms 预算。</p>
      <ul class="compare-steps">
        <li><span class="compare-step-key">世界状态</span><span>时间、天气、世界边界、袭击与区块源管理直接在服务器线程串行执行。</span></li>
        <li><span class="compare-step-key">区块刻</span><span>随机刻（Random Ticks）与自然刷怪在单一循环中逐区块顺序扫描。</span></li>
        <li><span class="compare-step-key">实体刻</span><span><code>EntityTickList</code> 在单核上顺序驱动所有生物、投射物与村民 AI。</span></li>
        <li><span class="compare-step-key">方块实体</span><span><code>Level.tickBlockEntities</code> 以扁平串行循环依次调用原版与模组 ticker。</span></li>
        <li><span class="compare-step-key">玩家刻</span><span>网络包处理、移动校验与容器同步与世界模拟争抢同一核心时间片。</span></li>
        <li><span class="compare-step-key">安全假设</span><span>隐式单线程契约：任意模组回调均可无声明地直接读写相邻区块或全局表。</span></li>
      </ul>
    </div>
  </div>

  <div class="compare-card compare-card--forgia">
    <div class="compare-card-head">
      <h3>Forgia 区域化核心</h3>
      <span class="compare-badge">显式租约 + 串行围栏</span>
    </div>
    <div class="compare-card-body">
      <p class="compare-lead">静止的 <code>GLOBAL</code> 协调阶段与并行的 <code>REGION</code> Worker 切片严格交替。任何离主线程写入都必须持有有效租约；未知路径自动闭环回退。</p>
      <ul class="compare-steps">
        <li><span class="compare-step-key">全局阶段</span><span>时间、天气、边界、玩家快照发布与跨区提交在主协调线程安全点完成。</span></li>
        <li><span class="compare-step-key">区域划分</span><span>支持 <code>DYNAMIC_CLUSTER</code> 加载岛动态合并/拆分（推荐）及 <code>FIXED_3X3</code>（3..32 区块 C6 基线网格）。</span></li>
        <li><span class="compare-step-key">工作线程池</span><span>专用 <code>RegionWorkerThread</code>（Fixed / Persistent 工作窃取），直访线程字段 + <code>preWake</code> 预唤醒。</span></li>
        <li><span class="compare-step-key">实体隔离</span><span>区域私有 section 分区、迁移日志（Transfer Journal）与只读玩家快照。</span></li>
        <li><span class="compare-step-key">方块实体</span><span><code>CanonicalBlockEntityCoordinator</code> 派发已证明前缀至 Worker；未知回调触发完整串行围栏。</span></li>
        <li><span class="compare-step-key">安全红线</span><span>所有开关默认 <code>false</code>；正文至多执行一次（不重放）；停服与卸载前强制物理 join。</span></li>
      </ul>
    </div>
  </div>
</div>

---

## 2. 核心维度对比矩阵

| 对比维度 | 原版 Forge / NeoForge | Forgia |
|---|---|---|
| **Tick 主循环** | 单线程 `ServerLevel.tick` 顺序执行 | `GLOBAL` ↔ `REGION` 交替（`LevelTickPhaseState`），同维度同一时刻最多一个区域批次在途 |
| **世界所有权** | 服务器线程隐式独占全部可变状态 | 显式 `RegionState` 租约；`RegionLeasePack` 支持最多 16 个区域全有或全无打包获取 |
| **工作线程模型** | 仅异步 I/O / 世界生成 Future，无 Tick 并行池 | `RegionWorkerPool`（Fixed 或 Persistent 工作窃取双端队列），`RegionWorkerThread` 直访字段 |
| **实体 Tick** | 单核顺序遍历 `EntityTickList` | 按区域并行执行（`FixedEntitySectionPartitions`、`EntityIdentityLedger`、迁移日志） |
| **区块与红石** | 直接跨区块修改相邻状态 | 区域并行随机刻 + `RegionDeferredBlockUpdates` / `RegionBorderUpdates` 同 Tick 不动点收敛 |
| **方块实体** | 扁平循环 `Level.tickBlockEntities` | `CanonicalBlockEntityCoordinator` 单调 cursor：已证明前缀 + `BlockEntityResourceOwnership` + 串行围栏 |
| **玩家 Tick** | 主线程内联执行 | 服务器线程负责连接遍历。开启实验开关后，已准入的实体调用和原始 `doTick()` 正文可交给 worker。[玩家 Tick](modules/player-ticks.zh.md) |
| **未知模组代码** | 直接运行 | **Fail-Closed 串行围栏**：物理 join Worker → 主线程完整执行一次原 ticker → 重审后缀 |
| **跨区域交互** | 直接读写目标对象 | `BlockEntityOperationRuntime`、`RegionMailbox`、Seqlock 跨区读校验与串行完成阶段 |
| **默认行为** | 单线程 | 所有区域线程与资源开关默认 `false`（未开启时保持原版串行行为） |

---

## 3. Canonical 方块实体流水线（热路径优化后）

模组方块实体不能简单按坐标拆到多线程，否则会破坏原版执行顺序或引发跨容器竞争。Forgia 通过 `CanonicalBlockEntityCoordinator` 与 `BlockEntityResourceOwnership` 解决这一问题：

<figure class="diagram-frame">
  <img src="../assets/arch-be-pipeline.zh.svg" alt="Forgia Canonical 方块实体执行流水线" />
  <figcaption class="diagram-caption">
    <span>BLUEPRINT 02 // CANONICAL 方块实体协调器</span>
    <span>O(1) 增量前缀证明 · 直访线程 Scope · 串行围栏</span>
  </figcaption>
</figure>

关键工程机制：

1. **单一单调 Cursor（`CanonicalBlockEntityCoordinator`）**：严格保持 `Level.tickBlockEntities` 原版因果顺序，不引入第二套调度器。
2. **O(1) 增量前缀独立性判定**：连续的已证明单元（原版漏斗、燃烧熔炉、受审计适配器）通过 `PrefixIndependence` / `LongOpenHashSet` 增量判定独立性，彻底消除 $O(N^2)$ 重复规划。
3. **零 `ThreadLocal` Worker Scope**：`RegionWorkerThread` 直接持有 `BlockEntityResourceOwnership.Scope` 与 `FurnaceWorkScope` 字段，高频校验路径零哈希探针。
4. **租约获取与 `preWake` 重叠 + `SharedReads` 直查**：获取区域租约前预先唤醒持久化 Worker；规划与预留阶段不再把数百条配方 `ItemStack` 灌入 `ResourceTable`，而是直接查询 `SharedReads` 开放寻址表并稀疏清理 `usedSlots`。
5. **Fail-Closed 串行围栏**：遇到未知模组类、Mixin 注入方法（`MixinMerged`）或别名冲突时，在正文启动前截断前缀、物理 join Worker、在主线程完整执行一次原 ticker 并重新验证后续后缀。

---

## 4. Phase 1 + Phase 2: Region Sandbox、独立时钟与广播适配架构

自 `DYNAMIC_CLUSTER` 成为默认区域策略后，Forgia 在核心执行层上新增了 Folia 风格的 **Region Sandbox + Broadcast Adapter（`RegionAdapterApi`）** 与 **独立 Region 异步时钟（`RegionIndependentTickScheduler`）**，使绝大多数普通模组无需逐一编写专属 Adapter 即可安全并行：

1. **通用 BE 沙盒（`enableRegionSandboxAllBlockEntities`）**：
   - 全服所有非移动活塞、非黑名单方块实体通过零票据沙盒快路径（`SANDBOX_NATIVE_INSPECTION`）在各自 Region Worker 上并行执行；
   - 任何不兼容的边界操作或运行期异常都会自动截断、无崩服降级回主线程并持久化记录至 `config/regionthreading-learned.json`。
2. **沙盒边界隔离与主线程安全投递**：
   - `RegionSavedDataSandbox` 为各 Region 隔离 `DimensionDataStorage` 读取并提供 `shareSavedData` 全局镜像；
   - 跨 Region `BlockCapability` 查询安全返回 `null`，`ServerLevel.players()` 返回沙盒保护视图；
   - `ServerLevel.sendParticles`、`ServerLevel.blockEvents`、成就触发与实体生成通过 `RegionAdapterApi.postGlobal` / `enqueueCommit` / `synchronized` 安全路由回主线程，避免并发破坏或误杀拉黑。
3. **独立 Region 异步时钟与长尾隔离（`RegionIndependentTickScheduler`）**：
   - 每个活跃 Region 拥有独立 50ms 异步时钟与原子 `inFlight` 护栏，BE 批次脱离全局同步 Barrier，慢 Region 不会阻塞快 Region 或主线程；
   - `ENTITY_TICK` 自动对长尾热点 Region（`ewma > 12ms` / `24ms`）进行 2/3 tick 步幅节流（玩家与随机刻不受影响），`SPAWN` 支持交错分帧调度；
   - `MobPathfindingThrottle` 自适应学习慢寻路生物（`>3ms` 自动冷却 `10t`，`>8ms` 自动冷却 `20t`）。
4. **Mod Adapter 的新定位**：
   - 普通 Mod 无需编写 Adapter；仅 Create（跨区全局动力学/物流网并发容器 + 单 Tick 拓扑守卫）与 Cataclysm（按维度缓存 `CMWorldData`）等复杂 Mod 保留针对性 Adapter。

---

## 5. 模块内部文档

- [区域引擎与所有权](modules/region-engine.zh.md) — 阶段交替、固定/动态布局、租约与线程池
- [实体 Tick](modules/entity-ticks.zh.md) — 分区存储、身份账本、跨区迁移与查询护栏
- [方块实体与 Canonical 阶段](modules/block-entities.zh.md) — `CanonicalBlockEntityCoordinator`、熔炉波次、资源所有权与适配器
- [区块随机刻与边界更新](modules/chunk-ticks.zh.md) — 并行随机刻、延迟更新与 Sculk 事件
- [跨区操作、Scheduled 与玩家](modules/cross-region-and-scheduled.zh.md) — 双所有者操作、计划刻与玩家调度
- [可观测性与配置](modules/observability.zh.md) — `forgia.toml` 开关、`/forgia` 诊断命令与指标采样
