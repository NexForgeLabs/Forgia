# 模块实现说明 —— 地图与状态

[English](README.en.md) | 中文 · [文档索引](../README.zh.md)

这些页面描述**已实现运行时如何工作**：所有权、tick 流水线、准入、失败处理、配置与观测。它们是实现说明，
不是 API 教程 —— API 合同在 [`docs/api`](../api/README.zh.md)。

状态标签遵循 [docs/README](../README.zh.md#3-状态图例)。测量数字是历史负载证据，不是对你整合包的承诺；
所有区域线程开关**默认关闭**。

---

## 1. 模块地图

| 模块 | 主要类 | 配置键 | 状态 | 文档 |
|---|---|---|---|---|
| Region 引擎与所有权 | `RegionThreadingEngine`、`RegionLayout`、`RegionState`、`RegionSchedule`、`RegionOwnershipLeaseGroup`、`RegionWorkerPool`、`RegionWorkStealingDispatcher`、`RegionTickBarrier`、`RegionMailbox` | `enableRegionThreadingBridge`、`...Workers`、`regionThreadingMode`、`...WorkerThreads`、`...PersistentWorkers`、`...PackedRegionLeases` | 已认证（范围） | [region-engine](region-engine.zh.md) |
| 实体 tick | `RegionEntityTickPlan`、`...Batch`、`...Entry`、`RegionEntityStorageMode`、`FixedEntitySectionPartitions`、`EntityIdentityLedger`、`RegionEntityTransferJournal`、`RegionScopedEntityQuery` | `enableRegionThreadingEntityTicks` | 已认证（范围） | [entity-ticks](entity-ticks.zh.md) |
| Chunk tick 与跨区效果 | `RegionChunkTickCoordinator`、`...Plan`、`...Batch`、`RegionBorderUpdates`、`RegionBorderGameEvents`、`RegionDeferredBlockUpdates` | `enableRegionThreadingRandomTicks`、`...BorderUpdates`、`...GameEvents` | 已认证（范围）/ 实验性 | [chunk-ticks](chunk-ticks.zh.md) |
| 自然刷怪与 POI | `RegionSpawnPlan`、`...Batch`、`...State`、`RegionSpawnAdmissionBarrier`、`NearbySpawnReservations`、`RegionPoiStorage(s)`、`RegionPoiSections` | `enableRegionThreadingSpawning`、`...Poi` | 实验性 / 已认证（范围） | [natural-spawning-and-poi](natural-spawning-and-poi.zh.md) |
| 方块实体 | `CanonicalBlockEntityCoordinator`、`NativeBlockEntityPreparation`、`FurnaceWorkPreparation`、`BlockEntityResourceOwnership`、`BlockEntityWorkPlanner`、`AuditedBlockEntityPreparation` | `enableRegionThreadingBlockEntityTicks`、`...FurnaceWorkWaves`、`...BlockEntityResources` | 已认证（范围）/ 实验性 | [block-entities](block-entities.zh.md) |
| 跨区操作、scheduled tick、玩家 | `BlockEntityOperationRuntime`、`RegionScheduledTickAdmission`、`RegionEntityInteractionTask`、`RegionPlayerSnapshots`、`PlayerRegionTransitions`、`GlobalRegionState` | `enableRegionThreadingScheduledTicks`、`enableGlobalRegionThreadingWorker` | 实验性 | [cross-region-and-scheduled](cross-region-and-scheduled.zh.md) |
| 观测与配置 | `RuntimeObservability`、`RegionDiagnosticsCollector`、`RegionMetricsReport`、`ForgiaConfig`、`/forgia` 命令树 | 诊断相关键 | 已实现 | [observability](observability.zh.md) |

## 2. 如何阅读模块页

每页使用相同小节，便于横向对比实现：

| 小节 | 回答的问题 |
|---|---|
| 职责 | 该子系统拥有什么、明确不拥有什么 |
| 类地图 | 哪个类负责哪件事（实现入口） |
| Tick 流程 | 有序阶段、线程与屏障 |
| 准入门 | 工作离开服务器线程前必须成立的条件 |
| 配置 | 键、默认值、是否需重启、已认证取值 |
| 失败语义 | 拒绝、异常、停机时发生什么 |
| 限制 | 未实现或未认证的内容 |
| 诊断 | 哪些计数/命令能证明行为 |
| 何时 / 如何 / 为什么 | 运维与模组作者指南 |

## 3. 线程模型一表

| 执行者 | 运行内容 | 拥有 |
|---|---|---|
| 服务器线程 | 被拒或未区域化的 level tick 阶段、最终化、发布、操作完成、玩家 tick | 一切未被租出的内容 |
| Region worker | 租出区域内的实体/chunk/BE/scheduled 批次 | 仅租出区域的存储 |
| 全局 region worker（可选） | 全局 region 队列 drain | 全局状态，不是任意 chunk |
| 异步/外部 | 引擎内部、IO、网络 | 无世界修改权 |

所有权来自 lease，不来自线程名。`Thread.currentThread().getName()` 永远不是正确性信号。

## 4. 各切片状态矩阵

| 切片 | 已实现 | 实测收益 | 认证范围 | 说明 |
|---|---|---|---|---|
| 实体 tick | 是 | 1024 生物 2.5–4.5× | C6 | 固定类/骑乘栈仍串行 |
| Chunk 随机 tick | 是 | 1.15–1.27× | C6 | 解除内部规则后 100% 派发 chunk 在 worker |
| 自然刷怪 | 是 | 切片 4.6 → 2.8–3.9 ms | C6 之外 | 需要固定布局、刷怪门与 cap |
| POI | 是 | 查询 2.1–3.4× | C6 | 快照可能落后一次发布 |
| 方块实体 | 是 | 未声明整服收益 | C6（有界队列） | 轻负载下漏斗/熔炉可能更慢 |
| 红石/边界 | 是 | 同 tick 收敛 | C6（固定布局） | 带级联预算的 fixpoint |
| 跨区 BE 操作 | 是 | 不适用（正确性） | 实验性 | 完成阶段串行 |
| Scheduled tick | 是 | 不适用（正确性） | C6 仅灯队列 | 半径 2 惰性足迹 |
| 玩家 | 服务器线程 | 不适用 | 有界测试 | 未做多人认证 |
| 结构/移动装置 | 否 | — | — | 仍在服务器线程 |

## 5. 证据位置

| 主题 | 证据文档 |
|---|---|
| 总体状态与路线图 | [STATUS](../../region-threading/STATUS.md) |
| 配置矩阵 | [PRODUCTION-CONFIGURATION](../../region-threading/PRODUCTION-CONFIGURATION.md) |
| 基准 | [PERFORMANCE-BENCHMARKS](../../region-threading/PERFORMANCE-BENCHMARKS.md) |
| 方块实体 | [BLOCK-ENTITY-PARALLELISM](../../region-threading/BLOCK-ENTITY-PARALLELISM.md)、[CANONICAL-BLOCK-ENTITY-COORDINATOR](../../region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md) |
| 实体/所有权协议 | [OWNERSHIP-PROTOCOLS-VALIDATION](../../region-threading/OWNERSHIP-PROTOCOLS-VALIDATION.md) |
| 跨区/scheduled | [CROSS-REGION-OPERATIONS](../../region-threading/CROSS-REGION-OPERATIONS.md) |
| 观测 | [RUNTIME-OBSERVABILITY](../../region-threading/RUNTIME-OBSERVABILITY.md) |
