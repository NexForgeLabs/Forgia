# 配置与硬件调优指南（`config/forgia.toml`）

<div class="lang-pill-bar">
  <a href="../configuration.en/">English</a>
  <a class="active" href="../configuration.zh/">中文</a>
</div>

Forgia 的全部并行与诊断开关统一存放在独立配置文件 **`config/forgia.toml`**（与上游 `neoforge-server.toml` 隔离）。核心区域并行切片开关默认均为 `false`（关闭即等价于原版串行），而运行时诊断、探针以及安全的 IO/序列化优化（`enableFastChunkPaletteSerialization`、`enableAsyncChunkLoadDeserialize`、`enableAsyncPlayerDataSave`、`enableAsyncAdvancementSave`、`enableParallelWorldGen`、`enableNonBlockingStructureLookups`）默认开启为 `true`。修改标注为“需重启”的引擎/布局/切片开关后必须**停服并以新 JVM 重启**才能生效。

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
| **`1`** | `2×2` chunks（32×32 格） | 大型工业区 / 极致多核拆分 | 细粒度区域；边界交接更频繁 |
| **`2`** | `4×4` chunks（64×64 格） | 高密度主城 / 小地图 | 均衡工业聚簇粒度 |
| **`3`（默认）** | `8×8` chunks（128×128 格） | 通用起点 | Section 尺寸较均衡；仍需根据玩家分布和实际负载验证 |
| **`4`** | `16×16` chunks（256×256 格） | 玩家群体彼此距离较远的地图 | 基础 Section 更大、边界更少，但独立 Region 的粒度也可能降低 |

---

## 3. 完整配置项总表（`config/forgia.toml`）

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
| `regionThreadingDynamicSectionShift` | `3` | `1..4` | 是 | `DYNAMIC_CLUSTER` 基础 Section 边长位偏移：`1 << shift` chunks（`1`=2×2、`2`=4×4、`3`=8×8 推荐、`4`=16×16） |
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
| `enableLowTpsProbe` | `true` | bool | 当全局 TPS 持续低于阈值时，自动记录完整瓶颈快照到控制台与 `logs/forgia-low-tps-probe.jsonl` |
| `lowTpsProbeThreshold` | `18.0` | `1.0..20.0` | 触发低 TPS 探针计时的全局 TPS 阈值 |
| `lowTpsProbeDurationSeconds` | `30` | `5..300` | 连续低于阈值多少秒后触发瓶颈记录 |
| `lowTpsProbeCooldownSeconds` | `60` | `10..3600` | 两次探针记录之间的最小冷却间隔秒数 |
| `lowTpsProbeTopCount` | `5` | `1..20` | 每次探针报告记录的最重区域 / 主线程阶段 / 切片数量 |
| `enableSandboxDemotionProbe` | `true` | bool | 异步记录方块实体沙箱降级与边界回退的去重调用栈到 `logs/forgia-sandbox-demotions.jsonl` |

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
| `itemCleanupEmergencyHardCap` | `512` | `0..100000` | 否 | 单区域掉落物硬上限；瞬间爆发超限时立即清理最旧条目（忽略落地时间，仍保留命名/白名单物品） |
| `itemCleanupIgnoreNamedItems` | `true` | bool | 否 | 保留自定义命名物品（如铁砧重命名装备） |
| `itemCleanupWhitelist` | `[]` | 物品注册表 ID 列表 | 否 | 永不清理的物品白名单（如 `["minecraft:nether_star"]`） |

### 3.5 独立 Region 时钟、通用方块实体沙盒与 Phase 3/4 异步管线

| 配置键 | 默认值 | 取值范围 / 类型 | 需重启 | 说明 |
|---|---:|---|---:|---|
| `enableIndependentRegionTickClock` | `false` | bool | 否 | 启用 Folia 式独立每区域异步 Tick 时钟 |
| `independentRegionTickIntervalNanos` | `50000000` | `1_000_000..1_000_000_000` | 否 | 独立 Region 时钟目标周期（默认 50ms / 20 TPS） |
| `independentRegionMaxCatchUpTicks` | `4` | `1..100` | 否 | 滞后 Region 允许的最大追帧步数 |
| `independentRegionCommitDrainBudgetNanos` | `4000000` | `500_000..50_000_000` | 否 | 主线程每 Tick 排空异步 Region 提交队列的最大时间预算（纳秒） |
| `enableRegionSandboxAllBlockEntities` | `false` | bool | 否 | 将非黑名单模组方块实体纳入 Region Sandbox Worker 并行执行 |
| `sandboxTransientCooldownTicks` | `20` | `1..1200` | 否 | 单实例偶发边界异常回退主线程后的冷却 tick 数 |
| `sandboxPermanentDemotionThreshold` | `16` | `1..1000` | 否 | 同类型 BE 累计触发边界异常达到此次数后永久降级主线程 |
| `sandboxMaxInstanceBackoffTicks` | `1200` | `20..72000` | 否 | 单坐标反复触发边界异常时的最大指数退避冷却（tick） |
| `sandboxEntityProximityLockstepRadius` | `4` | `0..32` | 否 | 活跃主线程实体周围局部锁步半径（格），仅邻近 BE 与主线程同帧步调 |
| `sandboxSideEffectEscalationTicks` | `60` | `0..600` | 否 | 沙盒 BE 在 Worker 生成复杂实体时对该单实例临时升级主线程的冷却时长（tick） |
| `sandboxHopperPlayerSafeRadiusBlocks` | `8` | `0..64` | 否 | Phase 4 统一循环下，内陆裸露漏斗在 Worker 原位吸取掉落物所需的无玩家安全半径（格） |
| `enableAsyncRegionSpawning` | `true` | bool | 否 | Phase 3A：将自然刷怪异步分发到 Region Worker，不阻塞主线程屏障 |
| `skipUnloadedNeighborhoodSpawning` | `true` | bool | 否 | 跳过 3×3 邻域未全部加载的模拟距离边缘区块的自然刷怪 |
| `enableAsyncRegionEntityTicks` | `false` | bool | 否 | Phase 3B（实验性）：将各区域非玩家实体脱离同步栅栏异步分发到 Region Worker |
| `enableSelectiveAsyncEntityTicks` | `true` | bool | 否 | 选择性将无玩家且无特殊物理耦合的区域放入异步队列，其余保留同步栅栏 |
| `enableRegionThreadingFluidTicks` | `true` | bool | 是 | 区域并行流体（水/岩浆）刻，同时保持红石方块计划刻主线程串行 |
| `enableAsyncRegionFluidTicks` | `false` | bool | 否 | Phase 3C：将内陆区域流体计算脱离屏障异步分发到 Region Worker |
| `enableUnifiedRegionTickLoop` | `false` | bool | 否 | Phase 4A：单区域内按 区块随机刻 → 实体 → 方块实体 顺序在单个异步 Worker 波次中执行 |
| `enableSingleRegionContraptionWorkers` | `false` | bool | 否 | Phase 4B：允许完全处于单一区域内部的移动动态结构实体在统一循环 Stage 2 原位运行 |
| `unifiedRegionEntityContainmentMarginBlocks` | `8` | `1..32` | 否 | Phase 4B 移动结构实体留在 Worker 运行所需的距区域边界最小安全边距（格） |

### 3.6 自适应实体节流、寻路冷却与单区块卡顿隔离

| 配置键 | 默认值 | 取值范围 / 类型 | 需重启 | 说明 |
|---|---:|---|---:|---|
| `enableMobPathfindingThrottle` | `false` | bool | 否 | 开启按生物类型与热点区域的 `PathNavigation.createPath` 冷却节流 |
| `mobPathfindingCooldowns` | `["primal:shark=40"]` | list | 否 | 指定生物类型的寻路冷却时间（格式 `"modid:entity=ticks"`） |
| `mobPathfindingDefaultCooldownTicks` | `0` | `0..200` | 否 | 未列入名单生物的全局默认寻路冷却 tick（`0` 关闭） |
| `entityTickThrottleModerateNanos` | `15000000` | `1_000_000..500_000_000` | 否 | 区域实体耗时 EWMA 超过此阈值（ns）时，非玩家实体降频为每 2 tick 一次 |
| `entityTickThrottleSevereNanos` | `28000000` | `1_000_000..1_000_000_000` | 否 | 区域实体耗时 EWMA 超过此阈值（ns）时，非玩家实体降频为每 3 tick 一次 |
| `hotRegionEntityThresholdNanos` | `5000000` | `500_000..500_000_000` | 否 | 判定为实体热点区域的 EWMA 阈值（ns），超限后自动启用轻量寻路冷却 |
| `hotRegionMobPathfindingCooldownTicks` | `4` | `0..100` | 否 | 热点区域内非战斗/游荡生物的寻路冷却 tick 数 |
| `hotRegionCombatMobPathfindingCooldownTicks` | `2` | `0..100` | 否 | 热点区域内正在追击玩家的生物寻路冷却 tick 数 |
| `slowPathfindingLearnThresholdNanos` | `3000000` | `500_000..100_000_000` | 否 | 单次 `findPath` 耗时超过此值（ns）时，该生物类型自动学习 10 tick 冷却 |
| `severePathfindingLearnThresholdNanos` | `8000000` | `1_000_000..500_000_000` | 否 | 单次 `findPath` 耗时超过此值（ns）时，该生物类型自动学习 20 tick 冷却 |
| `enableSlimeWorkerTicks` | `true` | bool | 是 | 允许史莱姆与岩浆怪在 Region Worker 上执行，死亡分裂子体通过延迟队列安全入世 |
| `enableHighDensityEntityTrackerThrottle` | `true` | bool | 否 | 开启高密度区块客户端实体追踪发包自适应降频（绝不跳过服务端逻辑帧） |
| `entityTrackerDenseChunkThreshold` | `64` | `8..10000` | 否 | 单区块追踪实体数超过此阈值时，普通实体客户端同步包降为每 2 tick 发送一次 |
| `entityTrackerSevereChunkThreshold` | `256` | `16..10000` | 否 | 单区块追踪实体数超过此阈值时，普通实体客户端同步包降为每 4 tick 发送一次 |
| `maxSyncNavigationRecomputesPerTick` | `16` | `0..10000` | 否 | 单次 `sendBlockUpdated` 主线程同步重算寻路的最大生物数量，超出部分延迟至 Worker |
| `blockEventsPerRegionBudgetNanos` | `2000000` | `0..50_000_000` | 否 | 主线程每 Tick 处理单个 Region 方块事件的最大耗时预算（ns），超出无损顺延下帧 |
| `enableAsyncInlandBlockScheduledTicks` | `false` | bool | 否 | 实验性：将严格位于单 Region 内陆的方块计划刻合流至所属 Region 异步 Worker 执行 |
| `inlandBlockScheduledTickMarginBlocks` | `6` | `2..15` | 否 | 启用内陆方块计划刻异步化时，距区域边界的最小安全边距（格） |
| `blockScheduledTickSerialBudgetNanos` | `2000000` | `0..50_000_000` | 否 | 主线程串行方块计划刻单个 Region 每帧最大耗时预算（ns），超出无损推迟下帧 |

### 3.7 区块 IO、并行世界生成、自动保存与成就存储优化

| 配置键 | 默认值 | 取值范围 / 类型 | 需重启 | 说明 |
|---|---:|---|---:|---|
| `autosaveMaxQuiesceWaitMillis` | `15` | `0..10000` | 否 | 增量自动保存等待慢速异步 Region 静默的最大毫秒数（超时则留待下轮） |
| `enableFastChunkPaletteSerialization` | `true` | bool | 否 | 在 `ChunkSerializer` 读写方块状态调色板时使用缓存 NBT 直连编解码（异常自动回退原版 Codec） |
| `enableAsyncChunkLoadDeserialize` | `true` | bool | 否 | 在后台线程预解码区块 Section 的方块与群系调色板并统计计数，主线程保留 POI/光照/事件 |
| `chunkUnloadTickBudgetNanos` | `4000000` | `0..50_000_000` | 否 | 主线程每 Tick 在 `ChunkMap.processUnloads()` 的最大时间预算（ns，强制 `saveAllChunks` 不受影响） |
| `chunkUnloadBacklogMaxPerTick` | `16` | `0..2000` | 否 | `unloadQueue > 2000` 时单 Tick 允许超预算强制排空的卸载任务硬上限（`0` 表示无上限） |
| `enableChunkUnloadSpinGuard` | `true` | bool | 否 | 在 `processUnloads()` 开始时快照队列长度，防止未就绪区块同 Tick 反复重入队空转 |
| `incrementalAutosaveBudgetNanos` | `4000000` | `0..50_000_000` | 否 | 5 分钟自动存档期间非 flush `ChunkMap.saveAllChunks(false)` 的单 Tick 时间预算（ns） |
| `enableAsyncPlayerDataSave` | `true` | bool | 否 | 主线程快照玩家 NBT 后，将 GZIP 压缩与原子写盘异步卸载到 `Util.ioPool()`（按 UUID 保序） |
| `enableParallelWorldGen` | `true` | bool | 否 | 启用 C2ME 式多核并行区块生成（按 `ChunkStep` 写入半径空间加锁） |
| `parallelWorldGenThreads` | `0` | `0..32` | 否 | 开启并行区块生成时的最大并发线程数（`0` = `clamp(CPU核心数 / 2, 2, 8)`） |
| `enableNonBlockingStructureLookups` | `true` | bool | 否 | 在实时 `ServerLevel` 查询结构时使用非加载查询（`load = false`），避免未加载原点触发 `managedBlock` 阻塞 |
| `enableAsyncAdvancementSave` | `true` | bool | 否 | 主线程快照成就进度后，将 Codec 编码与 GSON 写盘异步卸载到 `Util.ioPool()`（按文件保序） |

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
