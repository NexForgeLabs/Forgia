# 配置与硬件调优指南（`config/forgia.toml`）

<div class="lang-pill-bar">
  <a href="../configuration.en/">English</a>
  <a class="active" href="../configuration.zh/">中文</a>
</div>

Forgia 的全部并行与诊断开关统一存放在独立配置文件 **`config/forgia.toml`**（与上游 `neoforge-server.toml` 隔离）。所有并行开关默认均为 `false`（关闭即等价于原版串行），修改标注为“需重启”的引擎/布局/切片开关后必须**停服并以新 JVM 重启**才能生效。

---

## 1. 快速选型：不同硬件的推荐配置

> **核心调优原则**：
> 1. **区域模式优先选 `DYNAMIC_CLUSTER`（默认 `shift = 3`，即 8×8 chunks / 128×128 格）**：已通过 30/30 动态拓扑与所有权审计测试及 32 玩家耐久验证，相比 `FIXED_3X3` 吞吐更高（+9.2% TPS）、P95 更低（-17%）且玩家进 Worker 比例高出 +37%。
> 2. **区分仅 `FIXED_3X3` 生效或需 JVM 参数的开关**：
>    - `enableRegionThreadingPlayerTicks = true` 在 `FIXED_3X3` 下可直接生效；在 `DYNAMIC_CLUSTER` 下**必须同时在 JVM 启动参数中添加 `-Dforgia.dynamicPlayerSoak=true`**，否则玩家 tick 会保持主线程串行。
>    - `enableRegionThreadingFurnaceWorkWaves`、`enableRegionThreadingBlockEntityResources` 与 `enableRegionThreadingPackedRegionLeases` **仅在 `FIXED_3X3` 下生效**（`DYNAMIC_CLUSTER` 下熔炉波次/资源会被忽略，租约打包会被动态岛合包自动绕过）。在 `DYNAMIC_CLUSTER` 配置中请将这三项保持 `false`。
> 3. **不要在超线程 CPU 上放任 `regionThreadingWorkerThreads = 0`**：`0` 表示 `逻辑线程数 - 1`（例如 8 核 16 线程会开出 15 个 Worker），容易与主线程、原版区块生成线程池和 GC 争抢物理核与内存带宽。建议按 **`物理核数 - 1 ~ 2`** 显式固定 Worker 数，**始终给 Server 主线程留出至少 1 个完整物理核**。

### 场景 A：4 核 8 线程机器（如 i5-1135G7 / 4C VPS，8–16 GB 内存）

* 追求**最低中位延迟（P50）与最低内存占用**（实测 P50 `43.34 ms`，堆内存比串行省 35%）：`regionThreadingWorkerThreads = 3`（留 1 物理核给主线程）。
* 追求**更低 P99 长尾延迟**：`regionThreadingWorkerThreads = 4`。

```toml
# === 核心与并行切片开关 ===
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true            # DYNAMIC_CLUSTER 下需配合 JVM 参数 -Dforgia.dynamicPlayerSoak=true
enableRegionThreadingBlockEntityTicks = false      # 轻负载静止 BE 串行更快；重度熔炉/受审计适配器场景再开
enableRegionThreadingFurnaceWorkWaves = false      # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动忽略）
enableRegionThreadingBlockEntityResources = false  # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动忽略）
enableRegionThreadingScheduledTicks = false        # 仅对受审计红石灯阶段生效，常规服可保持关闭
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = false    # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动绕过）

# === 动态区域布局与线程池 ===
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3             # 8x8 chunks (128x128 格)
regionThreadingDynamicSplitDelayTicks = 40         # 2 秒延迟防边界抖动拆合
regionThreadingWorkerThreads = 3                   # 4 物理核：3 Workers + 1 物理核留给主线程（追求 P99 可设 4）
```

### 场景 B：8 核 16 线程高频 CPU + 32 GB 双通道内存（如 E5-2667 v2 @ 4.1GHz，16GB×2 DDR3-1666）

* **硬件特点**：8 个物理核充裕、25 MB 大 L3 缓存非常契合 `8×8` 动态区域，但双通道 DDR3-1666 内存带宽偏紧，切忌开满 15 个超线程 Worker。
* **核数分配**：1 物理核专供 Server 主线程 + **6 个物理核给 Region Workers**（重度探图/生区块高峰可设 `5`）+ 1 物理核及剩余超线程给原版世界生成、Netty I/O 与分代 ZGC。

```toml
# === 核心与并行切片开关 ===
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableGlobalRegionThreadingWorker = true
enableRegionThreadingEntityTicks = true
enableRegionThreadingPlayerTicks = true            # DYNAMIC_CLUSTER 下需配合 JVM 参数 -Dforgia.dynamicPlayerSoak=true
enableRegionThreadingBlockEntityTicks = true       # 若服内有大量熔炉/受审计适配器可开启，否则可设为 false
enableRegionThreadingFurnaceWorkWaves = false      # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动忽略）
enableRegionThreadingBlockEntityResources = false  # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动忽略）
enableRegionThreadingScheduledTicks = false        # 仅对受审计红石灯阶段生效，常规服可保持关闭
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true
enableRegionThreadingBorderUpdates = true
enableRegionThreadingGameEvents = true
enableRegionThreadingPersistentWorkers = true
enableRegionThreadingPackedRegionLeases = false    # 仅 FIXED_3X3 生效（DYNAMIC_CLUSTER 下自动绕过）

# === 动态区域布局与线程池 ===
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3             # 8x8 chunks，充分利用 25MB L3 并减少内存总线访问
regionThreadingDynamicSplitDelayTicks = 40
regionThreadingWorkerThreads = 6                   # 8 物理核甜点（重度生区块高峰期可改 5）
```

配套 JDK 21 启动参数（32 GB 物理内存留一半给 OS Page Cache 缓存 `.mca` 区块文件；若在 `DYNAMIC_CLUSTER` 下开启 `enableRegionThreadingPlayerTicks = true`，请加上 `-Dforgia.dynamicPlayerSoak=true`）：

```bash
-Xms12G -Xmx12G -XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch -Dforgia.dynamicPlayerSoak=true
```

### 场景 C：16 核及以上高并发服务器（如 Ryzen 9 / EPYC，64+ GB 内存）

* 留 2–4 个物理核给主线程、区块生成、Netty 与 GC，其余 **10–12 核** 分配给 `regionThreadingWorkerThreads`。
* 若 20+ 名玩家高度集中在同一个 200–300 格半径主城内，可将 `regionThreadingDynamicSectionShift` 降至 `2`（`4×4` chunks）防止合并成单个巨型岛；常规分散生存服保持 `3`（`8×8` chunks）。

### 场景 D：回滚至原版串行 / C6 历史基线

* **一键回滚串行**：将 `enableRegionThreadingBridge = false` 并重启 JVM，即可 100% 退回原版串行热路径。
* **历史 C6 固定布局基线**：参考 `tools/production-readiness/config/c6-persistent.toml`（`regionThreadingMode = "FIXED_3X3"`、`regionThreadingFixedRegionChunks = 3`、`regionThreadingWorkerThreads = 4`）。

---

## 2. `regionThreadingDynamicSectionShift` 尺寸选择建议

| `shift` 值 | 单 Section 尺寸 | 适用场景 | 特点 |
|---:|---|---|---|
| **`2`** | `4×4` chunks（64×64 格） | 玩家聚集在较近区域（主城/小地图）或追求极致 P99 | 岛合并更克制、岛数更多、P99 更低，但玩家更易跨区边界 |
| **`3`（推荐）** | `8×8` chunks（128×128 格） | **绝大多数多人与混合生存/模组服** | 综合 TPS 最高、L3 缓存友好、玩家进 Worker 比例最高（~86%） |
| **`4`** | `16×16` chunks（256×256 格） | 超大地图且玩家彼此相距 >500 格的百人服（Folia 默认） | 边界极少，但在玩家间距 <300 格时易合并成大岛降低并发度 |

---

## 3. 完整配置项总表（全部 36 项 `config/forgia.toml` 键）

### 3.1 并行核心与切片开关（16 项，均默认 `false`，均需重启）

| 配置键 | 默认值 | 依赖前置 | 说明 |
|---|---:|---|---|
| `enableRegionThreadingBridge` | `false` | 无 | 总开关：安装区域线程引擎；关闭时完全退回原版串行 |
| `enableRegionThreadingWorkers` | `false` | `Bridge` | 在原生 Region Worker 线程池上执行区域任务队列 drain（不是其他 tick 切片的总开关） |
| `enableGlobalRegionThreadingWorker` | `false` | `Bridge` | 将全局区域（Global Region）队列 drain 移至原生 Worker 池 |
| `enableRegionThreadingEntityTicks` | `false` | `Bridge` + `DYNAMIC_CLUSTER`/`FIXED_3X3` | 在 Region Worker 上并行执行 `ServerLevel` 实体 tick，自动启用 region-owned 实体存储与延迟状态发布 |
| `enableRegionThreadingPlayerTicks` | `false` | `Bridge` + `EntityTicks` +（`FIXED_3X3`，或 `DYNAMIC_CLUSTER` + `-Dforgia.dynamicPlayerSoak=true`） | 在满足空间/状态/回调/网络契约时，将玩家 `ENTITY`（`ServerPlayer.tick`）与受控 `CONNECTION_BODY` 派发至 Region Worker；按实际影响面判定第三方模组准入 |
| `enableRegionThreadingBlockEntityTicks` | `false` | `Bridge` | 将已声明区域安全的方块实体 tick 派发至 Region Worker；未声明或存在跨区副作用的类型保持主线程串行 |
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
| `regionThreadingMode` | `"FIXED_3X3"` | `DYNAMIC_CLUSTER` / `FIXED_3X3` / `DYNAMIC` | 是 | 区域划分策略。**推荐 `"DYNAMIC_CLUSTER"`**；`"FIXED_3X3"` 为 C6 固定网格基线；`"DYNAMIC"` 为遗留 16×16 网格 |
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
enableIndependentRegionTickClock = true           # 启用 Folia 式 per-region 异步时钟（消除 BE 全局 Barrier）
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
