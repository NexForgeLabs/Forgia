# 配置与硬件调优指南（`config/forgia.toml`）

<div class="lang-pill-bar">
  <a href="../configuration.en/">English</a>
  <a class="active" href="../configuration.zh/">中文</a>
</div>

Forgia 的全部并行与诊断开关统一存放在独立配置文件 **`config/forgia.toml`**（与上游 `neoforge-server.toml` 隔离）。所有并行开关默认均为 `false`（关闭即等价于原版串行），修改标注为“需重启”的引擎/布局/切片开关后必须**停服并以新 JVM 重启**才能生效。

---

## 1. 安全起步与硬件调优

> **当前默认值与适用范围（已对照 `ForgiaConfig.java`）：**
> - `regionThreadingMode` 默认值是 `DYNAMIC_CLUSTER`。旧 `FIXED_3X3` 布局仍用于历史 C6 对照和兼容场景，但它不是当前默认模式。
> - 并行执行开关默认均为 `false`，包括 Bridge、Worker 切片、通用方块实体 Sandbox 和独立 Region 时钟。只有主动启用相应开关后，服务器才会进入对应并行路径。
> - C7 验证的是特定的 188 Mod SkyExplorer 负载。数据见[性能基准页](benchmarks.zh.md)，不能直接当作所有硬件、整合包的通用推荐配置。
> - 启用 `enableRegionSandboxAllBlockEntities` 后，普通模组 BE 可在通用 Sandbox 中执行，无需逐个编写 Adapter；复杂共享状态模组仍可能需要专用 Adapter 或串行回退。
> - 不要因为配置项存在就全部开启。部分功能仍属实验性、有前置条件，或只支持特定布局；请先阅读下方配置表和对应模块文档。

### Worker 数量

`regionThreadingWorkerThreads = 0` 时，Forgia 自动选择 `max(1, 逻辑 CPU 数 - 1)`。这是便捷默认值，不代表所有逻辑线程都能带来最佳性能。生产环境建议先设置较保守的显式数量，同时观察 Server 主线程、Worker、区块生成和 GC，再用可重复的实际负载测试逐步调整。必须为主线程及其他服务端任务预留 CPU 资源。

不存在适用于所有 CPU 和整合包的固定 Worker 数。旧硬件示例和历史报告中的 P50/P99 数据，不应被当作当前通用调优结论。

### 最小配置流程

1. 备份世界存档和当前配置。
2. 确认生成的 `config/forgia.toml` 与正在运行的构建版本一致。
3. 从区域引擎和实际需要的执行切片开始，不要一次性开启全部功能。
4. 通用 BE Sandbox 与独立 Region 时钟分别启用；两者默认关闭。
5. 在生产世界副本上测试，检查 `/neoforge regionthreading status`、Worker 和 fallback 计数，再进行受控耐久测试。
6. 如果出现所有权违规、兼容失败或不稳定，关闭对应切片；必要时设置 `enableRegionThreadingBridge = false`，重启并确认已回到串行路径。

本页后续代码片段是针对具体功能的示例，不是适用于所有整合包的通用生产配置。

---

## 2. `regionThreadingDynamicSectionShift` 尺寸选择建议

| `shift` 值 | 单 Section 尺寸 | 适用场景 | 特点 |
|---:|---|---|---|
| **`2`** | `4×4` chunks（64×64 格） | 高密度主城 / 小地图 | 基础 Section 更小；可能产生更多 Region 边界和拓扑管理开销 |
| **`3`（默认）** | `8×8` chunks（128×128 格） | 通用起点 | Section 尺寸较均衡；仍需根据玩家分布和实际负载验证 |
| **`4`** | `16×16` chunks（256×256 格） | 玩家群体彼此距离较远的地图 | 基础 Section 更大、边界更少，但独立 Region 的粒度也可能降低 |

---

## 3. 完整配置项总表（全部 36 项 `config/forgia.toml` 键）

### 3.1 并行核心与切片开关（默认关闭；需重启项见各键说明）

| 配置键 | 默认值 | 依赖前置 | 说明 |
|---|---:|---|---|
| `enableRegionThreadingBridge` | `false` | 无 | 总开关：安装区域线程引擎；关闭时完全退回原版串行 |
| `enableRegionThreadingWorkers` | `false` | `Bridge` | 在原生 Region Worker 线程池上执行区域任务队列 drain（不是其他 tick 切片的总开关） |
| `enableGlobalRegionThreadingWorker` | `false` | `Bridge` | 将全局区域（Global Region）队列 drain 移至原生 Worker 池 |
| `enableRegionThreadingEntityTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | 在 Region Worker 上并行执行 `ServerLevel` 实体 tick，自动启用 region-owned 实体存储与延迟状态发布 |
| `enableRegionThreadingPlayerTicks` | `false` | `Bridge` + `EntityTicks` +（`FIXED_3X3`，或 `DYNAMIC_CLUSTER` + `-Dforgia.dynamicPlayerSoak=true`） | 在满足空间/状态/回调/网络契约时，将玩家 `ENTITY`（`ServerPlayer.tick`）与受控 `CONNECTION_BODY` 派发至 Region Worker；按实际影响面判定第三方模组准入 |
| `enableRegionThreadingBlockEntityTicks` | `false` | `Bridge` | 将已声明区域安全的方块实体 tick 派发至 Region Worker；未声明或存在跨区副作用的类型保持主线程串行 |
| `enableRegionSandboxAllBlockEntities` | `false` | 方块实体 Worker 路径 | 将普通模组 BE 纳入通用 Region Sandbox；仍受兼容护栏和串行回退约束 |
| `enableIndependentRegionTickClock` | `false` | Region 引擎 | 启用独立 Region Tick 调度；全局世界状态与跨区提交仍需协调 |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | `Bridge` + `BlockEntityTicks` + **仅 `FIXED_3X3`** | 实验性：按有向资源波次并行执行已验证燃烧中的原版熔炉族方块实体（`DYNAMIC_CLUSTER` 下忽略） |
| `enableRegionThreadingBlockEntityResources` | `false` | `FurnaceWorkWaves` + **仅 `FIXED_3X3`** | 实验性：在熔炉波次内启用对象级 READ/WRITE 资源 claims、非阻塞租约与 scope（`DYNAMIC_CLUSTER` 下忽略） |
| `enableRegionThreadingScheduledTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | 启用有界计划刻（红石灯等受审计惰性方块阶段）并行执行；其余保持原版 `LevelTicks` 串行 |
| `enableRegionThreadingRandomTicks` | `false` | `Bridge` | 在 Region Worker 上并行执行区块 tick（随机方块/流体刻、冰雪降水、雷击），要求 3×3 邻域区块已加载 |
| `enableRegionThreadingSpawning` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | 在 Region Worker 上并行执行自然刷怪，共享全维度原版生物上限（`LevelSpawnCapCounter`） |
| `enableRegionThreadingPoi` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | 按区域分区存储 POI（村民/铁傀儡/蜜蜂等），开启 `EntityTicks` 时隐含启用 |
| `enableRegionThreadingBorderUpdates` | `false` | `Bridge` | 捕获跨区方块邻居更新并在同 tick 由目标区域 Worker 通过定点传播重放 |
| `enableRegionThreadingGameEvents` | `false` | `Bridge` | 捕获跨区 Game Event（如幽匿感测体振动）并在同 tick 按发布顺序交由目标区域 Worker 投递 |
| `enableRegionThreadingPersistentWorkers` | `false` | `Bridge` | 使用持久化 Worker 线程 + 工作窃取（Work Stealing）派发器替代普通固定线程池，大幅降低批次提交与唤醒开销 |
| `enableRegionThreadingPackedRegionLeases` | `false` | `Bridge` + **仅 `FIXED_3X3`**（非 `DYNAMIC_CLUSTER`） | 允许单个 Worker 任务打包持有多个区域租约（最多 16 个，全有或全无），减少微任务提交开销；`DYNAMIC_CLUSTER` 下自动绕过 |
| `enableRegionThreadingCallerRuns` | `false` | `Bridge` + `PersistentWorkers` | 允许主线程在等待屏障期间（泵送主线程 handoff 间隙）从持久化工作窃取队列中窃取并执行单个未启动区域组，降低屏障空等 |
| `enableRegionThreadingParallelBorderFixpoint` | `false` | `Bridge` | 在跨区边界更新定点传播首轮使用 8-邻域图着色，让不相邻目标区域在 Worker 上并行传播 |
| `enableCanonicalFenceTail` | `false` | `Bridge` | 实验性（放宽严格顺序）：将未验证的串行栅栏方块实体延后至并行 BE 波次汇合后的单次串行尾阶段，合并多轮屏障 |
| `enableRegionThreadingFusedSlices` | `false` | `Bridge` | **P3 切片融合**：将同一区域的 `CHUNK_TICK` 与自驱动 `ENTITY_TICK` 融合成单次 Worker 任务、单次屏障与单次边界传播；遇到块事件/存档/外部读失效自动回退 |

### 3.2 区域布局、线程池与安全/预算控制（11 项）

| 配置键 | 默认值 | 取值范围 | 需重启 | 说明 |
|---|---|---|---:|---|
| `regionThreadingMode` | `"DYNAMIC_CLUSTER"` | `DYNAMIC_CLUSTER` / `FIXED_3X3` / `DYNAMIC` | 是 | 当前默认值为 `DYNAMIC_CLUSTER`；`FIXED_3X3` 保留用于旧版/C6 对照；`DYNAMIC` 为遗留 16×16 网格 |
| `regionThreadingDynamicSectionShift` | `3` | `2..4` | 是 | `DYNAMIC_CLUSTER` 基础 Section 边长位偏移：`1 << shift` chunks（`2`=4×4、`3`=8×8 推荐、`4`=16×16） |
| `regionThreadingDynamicSplitDelayTicks` | `40` | `0..1200` | 是 | `DYNAMIC_CLUSTER` 空 Section 分离并触发连通分量拆分的迟滞等待 tick 数 |
| `regionThreadingFixedRegionChunks` | `3` | `3..32` | 是 | `FIXED_3X3` 模式下单个固定区域的边长（chunk 数） |
| `regionThreadingWorkerThreads` | `0` | `0..512` | 是 | Worker 线程数。`0` 自动取 `max(1, 逻辑核数 - 1)`；生产与调优强烈建议显式设为 `物理核数 - 1 ~ 2` |
| `regionThreadingOwnershipViolationMode` | `"WARN"` | `WARN` / `THROW` / `DISABLED` | 否 | 所有权断言失败处理模式（所有模式均持续计数，强制安全拒绝不受此项关闭） |
| `defaultRegionThreadingSupport` | `"UNKNOWN"` | `RegionThreadingSupport` 枚举 | 是 | 未声明显式元数据的模组默认并行支持状态 |
| `regionThreadingForceWithKnownConflicts` | `false` | bool | 是 | 仅用于兼容测试：忽略已知冲突表强制安装引擎（生产环境请务必保持 `false`） |
| `regionThreadingLongTickWarningNanos` | `0` | `≥ 0` | 否 | 区域或全局队列耗时超过该纳秒阈值时输出告警日志；`0` 关闭 |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | `≥ 0` | 否 | 单次 tick 每队列最大立即任务 drain 数量；`0` 不限制 |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | `≥ 0` | 否 | 单次 tick 每队列最大到期计划任务 drain 数量；`0` 不限制 |

### 3.3 运行时诊断与瓶颈告警阈值（9 项，均支持热生效、无需重启）

| 配置键 | 默认值 | 取值范围 | 说明 |
|---|---:|---|---|
| `runtimeDiagnosticsEnabled` | `true` | bool | 采集有界原生 tick 历史与 Worker 任务耗时供 `/forgia` 命令查询 |
| `runtimeJvmSamplingEnabled` | `true` | bool | 每秒采样一次本地 JVM CPU、内存分配与 GC 计数（无 profiler/磁盘 I/O 开销） |
| `runtimeHighRegionMspt` | `50` | `1..10000` | 单区域最新 tick 耗时触发 `HIGH_REGION_MSPT` 告警的毫秒阈值 |
| `runtimeWorkerSaturationPercent` | `90` | `1..100` | Worker 采样总 CPU 占配置 Worker 核数百分比触发 `WORKER_SATURATION` 的阈值 |
| `runtimeQueuePerWorker` | `2` | `1..10000` | 每 Worker 待处理任务数触发 `QUEUE_CONGESTION` 的阈值 |
| `runtimeSerialFallbackPercent` | `50` | `1..100` | 实体/BE 串行回退占比触发 `HIGH_SERIAL_FALLBACK` 的百分比阈值（至少 100 次后生效） |
| `runtimeRegionImbalanceRatio` | `4` | `2..1000` | 最重区域均值与活跃区域均值之比触发 `REGION_LOAD_IMBALANCE` 的倍率阈值 |
| `runtimeMainCpuPercent` | `90` | `1..100` | 主线程单核 CPU 占用触发 `MAIN_THREAD_BOTTLENECK` 的百分比阈值 |
| `runtimeHighServerMspt` | `50` | `1..10000` | 服务端均值 MSPT 触发 `MAIN_THREAD_BOTTLENECK` 的毫秒阈值 |

### 3.4 区域过载隔离与多区域掉落物清理（15 项）

| 配置键 | 默认值 | 取值范围 / 说明 | 需重启 | 说明 |
|---|---|---|---:|---|
| `enableRegionOverloadGovernor` | `false` | bool | 是 | 启用每区域过载隔离（仅 `DYNAMIC_CLUSTER`）；高耗时区域降频为 `1/N` 调度，不拖累全服 TPS |
| `regionOverloadBudgetNanos` | `15000000` | `1_000_000..1_000_000_000` | 是 | 单区域 EWMA 耗时预算（纳秒，默认 15ms） |
| `regionOverloadEnterTicks` | `20` | `1..1200` | 是 | 连续超预算 tick 数达到后进入 `THROTTLED` |
| `regionOverloadExitTicks` | `100` | `1..6000` | 是 | 低于退出阈值累积恢复 tick 数后返回 `NORMAL` |
| `regionOverloadExitRatio` | `0.6` | `0.1..0.95` | 是 | 退出阈值占 `regionOverloadBudgetNanos` 的比例 |
| `regionOverloadMaxPeriod` | `8` | `2..64` | 是 | 过载区域最大降频周期（`1/N`） |
| `regionOverloadThrottlePlayerRegions` | `false` | bool | 是 | 是否对包含在线玩家的区域启用过载降频；设为 `false` 可避免玩家周围实体/机器降频（`ServerPlayer` 自身始终保底 20 TPS） |
| `regionOverloadMaxCatchUpScheduledTicks` | `65536` | `1..1_000_000` | 是 | 被跳过 tick 的计划刻最大顺延重排上限 |
| `enableItemCleanup` | `false` | bool | 否 | 启用多区域感知的掉落物定时清理与单区域紧急上限 |
| `itemCleanupIntervalSeconds` | `300` | `0..86400` | 否 | 定时清理周期（秒）；`0` 仅保留单区域紧急上限 |
| `itemCleanupMinAgeSeconds` | `60` | `0..3600` | 否 | 仅清理落地时间达到该秒数的掉落物；刚掉落物品不会被误清 |
| `itemCleanupWarningSeconds` | `15` | `0..300` | 否 | 定时清理前全服倒计时预警秒数；`0` 关闭预警 |
| `itemCleanupMaxPerRegion` | `256` | `0..100000` | 否 | 单区域掉落物紧急上限；超限时仅清理该超标区域内最旧的合格掉落物 |
| `itemCleanupIgnoreNamedItems` | `true` | bool | 否 | 保留自定义命名物品（如铁砧重命名装备） |
| `itemCleanupWhitelist` | `[]` | 物品注册表 ID 列表 | 否 | 永不清理的物品白名单（如 `["minecraft:nether_star"]`） |

---

## 4. 完整全开配置模板

可直接复制保存为 `config/forgia.toml`，再根据本机物理核数调整 `regionThreadingWorkerThreads`：

```toml
# Forgia 推荐并行配置模板（DYNAMIC_CLUSTER 8x8）
# 修改标注 [重启] 的项后请停服并以新 JVM 启动。

# --- 运行时诊断与告警 ---
runtimeDiagnosticsEnabled = true
runtimeJvmSamplingEnabled = true
runtimeHighRegionMspt = 50
runtimeWorkerSaturationPercent = 90
runtimeQueuePerWorker = 2
runtimeSerialFallbackPercent = 50
runtimeRegionImbalanceRatio = 4
runtimeMainCpuPercent = 90
runtimeHighServerMspt = 50

# --- 核心与并行切片开关 [重启] ---
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true            # DYNAMIC_CLUSTER 下需配合 JVM 参数 -Dforgia.dynamicPlayerSoak=true
enableRegionThreadingBlockEntityTicks = false
enableRegionThreadingFurnaceWorkWaves = false      # 仅 FIXED_3X3 生效
enableRegionThreadingBlockEntityResources = false  # 仅 FIXED_3X3 生效
enableRegionThreadingScheduledTicks = false
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = false    # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动绕过）
enableRegionThreadingCallerRuns = true
enableRegionThreadingParallelBorderFixpoint = true
enableRegionThreadingFusedSlices = true

# --- 区域布局与线程池 [重启] ---
regionThreadingMode = "DYNAMIC_CLUSTER"          # 默认架构：动态聚簇（FIXED_3X3 / DYNAMIC 仅作兼容保留）
regionThreadingDynamicSectionShift = 2
regionThreadingDynamicSplitDelayTicks = 40
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 0                  # 0 表示自动按物理核数计算；也可显式指定

# --- Phase 2: 独立 Region 异步时钟与全服 BE 沙盒 [重启] ---
enableIndependentRegionTickClock = true           # 启用 Folia 式 per-region 时钟调度；全局状态仍需协调
independentRegionTickIntervalNanos = 50000000     # 目标周期 50ms (20 TPS)
independentRegionMaxCatchUpTicks = 4              # 滞后时最大追帧上限
enableRegionSandboxAllBlockEntities = true        # 默认把全服所有非移动活塞/非黑名单 BE 丢进 Region Worker 沙盒
enableRegionThreadingFluidTicks = true            # 独立并行流体（水/岩浆）调度，红石 blockTicks 保持串行

# --- 自适应寻路节流 ---
enableMobPathfindingThrottle = true               # 开启后自动学习：单次寻路 >3ms 冷却 10t，>8ms 冷却 20t
mobPathfindingCooldowns = ["primal:shark=40", "minecraft:zombie_villager=20"]
mobPathfindingDefaultCooldownTicks = 0

# --- 区域过载隔离 [重启] ---
enableRegionOverloadGovernor = true
regionOverloadBudgetNanos = 15000000
regionOverloadEnterTicks = 20
regionOverloadExitTicks = 100
regionOverloadExitRatio = 0.6
regionOverloadMaxPeriod = 8
regionOverloadThrottlePlayerRegions = false
regionOverloadMaxCatchUpScheduledTicks = 65536

# --- 多区域掉落物清理（热生效）---
enableItemCleanup = true
itemCleanupIntervalSeconds = 120
itemCleanupMinAgeSeconds = 120
itemCleanupWarningSeconds = 15
itemCleanupMaxPerRegion = 128
itemCleanupIgnoreNamedItems = true
itemCleanupWhitelist = []

# --- 安全、兼容与预算 ---
regionThreadingOwnershipViolationMode = "WARN"
defaultRegionThreadingSupport = "UNKNOWN"
regionThreadingForceWithKnownConflicts = false
regionThreadingLongTickWarningNanos = 0
regionThreadingMaxImmediateTasksPerDrain = 10000
regionThreadingMaxScheduledTasksPerDrain = 10000
```
