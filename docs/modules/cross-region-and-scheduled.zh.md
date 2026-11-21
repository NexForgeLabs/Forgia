# 模块：跨区操作、scheduled tick、全局状态与玩家

[English](cross-region-and-scheduled.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`BlockEntityOperationRuntime`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/BlockEntityOperationRuntime.java)、[`RegionScheduledTickAdmission`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionScheduledTickAdmission.java)、[`RegionEntityInteractionTask`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityInteractionTask.java)、[`PlayerRegionTransitions`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/PlayerRegionTransitions.java)、[`GlobalRegionState`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/GlobalRegionState.java)
证据：[CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md)、[CHUNK-OWNER-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CHUNK-OWNER-OPERATIONS.md)、[STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)

状态：除范围很窄的灯队列（在该范围内通过 C6 正确性）外，整体为**实验性**。所有开关默认关闭。

---

## 1. 职责

| 领域 | 拥有 | 不拥有 |
|---|---|---|
| BE 操作 | 批次完成阶段的至多一次双 owner 完成 | 跨区并行 lease |
| Scheduled tick | 有界、region 内含的原版灯阶段 | 任意 scheduled 回调 |
| 实体交互 | 带明确结果的完整实体↔owner 交互 | 搬移 source 剩余 tick |
| Chunk owner 操作 | ticket 支持的同步 FULL-chunk owner 操作 | 异步 chunk 加载/续跑 |
| 全局状态 | 显式全局阶段、有界 mailbox、全局写入 fail-closed | worker 任意全局写入 |
| 玩家 | 单区域成员、有界交接、可选开启的 `enableRegionThreadingPlayerTicks` 与 `IRegionPayloadHandler` | 未声明模组的玩家 tick 或未批准载荷（fail-closed 回退 MAIN） |
| Worker 边界回退 | Worker 侧 `PacketDistributor` 与视觉/粒子广播（保留发送结果）、玩家效果/触发器以及 capability/`BlockCapabilityCache`/attachment/`SavedData` 边界守卫的主线程路由（`CapabilityListenerHolder` 在注册表锁外执行失效回调） | Worker 上的任意跨区可变写入 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `BlockEntityOperationRuntime` | 引用、请求、每批次队列、完成、计数 |
| `RegionScheduledTickAdmission` / `...Batch` / `...Metrics` | 仅灯准入、派发、p50/p95/p99 |
| `RegionScheduledTask` / `RegionScheduler` 实现 | 位置/实体/全局的延迟/周期任务 |
| `EngineRegionScheduler` / `EngineEntityRegionScheduler` / `EngineGlobalRegionScheduler` | 引擎侧入队与所有权 |
| `RegionEntityInteractionTask` / `RegionEntityOwnershipScope` | 交互队列、校验、取消 |
| `RegionPlayerSnapshots` / `PlayerRegionTransitions` / `PlayerRegionAudit` | 每 tick 快照、交接、重复检测 |
| `GlobalRegionState` / `RegionMailbox` / `GlobalRegionTickRunner` | 全局阶段、有界消息、drain |
| `DeferredEntityAdmissionScope` | 加载后 owner 准入 |

## 3. 批次完成顺序（BE 操作）

| 顺序 | 步骤 |
|---|---|
| 1 | worker 完成 |
| 2 | 边界传播 |
| 3 | 发布延迟 block/light |
| 4 | 延迟实体移除 |
| 5 | 若有操作：先发布 worker 排队的 scheduled mutations |
| 6 | **完整双 owner 操作（串行）** |
| 7 | 原有串行 fallback |
| 8 | 剩余 scheduled mutations |
| 9 | 批次关闭 |

| 后果 | 说明 |
|---|---|
| 可见性 | 操作在所有 worker 返回后看到稳定世界 |
| 并行 | 没有多区域 lease；独立机器仍在各自区域并行 tick |
| 重放 | 绝不；部分生效的操作只报告，不重试 |

## 4. Scheduled tick 准入（生产范围）

| 门 | 要求 |
|---|---|
| 布局 | 分区 region 布局（`DYNAMIC_CLUSTER` 或 `FIXED_3X3`）；遗留 `DYNAMIC` 返回 `NON_FIXED_LAYOUT` |
| Profiler | 原版 profiler 未激活 |
| 足迹 | 每个回调位置半径 2 立方体全部加载且在同一 region |
| 方块 | 足迹内只有空气、洞穴空气、虚空空气、石头、玻璃、红石块、红石灯 |
| 阶段内容 | 完整 block 阶段只包含原版红石灯 |
| 预算 | 整个阶段可放入延迟更新预算 |
| 随机 | 灯只切换 LIT；派生 random 不推进共享世界 RNG |

| 拒绝（整阶段串行） | `SerialReason` |
|---|---|
| 未知回调 | `UNCLASSIFIED_CALLBACK` |
| 流体阶段 | `FLUID_CALLBACK` |
| 非固定布局 | `NON_FIXED_LAYOUT` |
| Profiler 激活 | `PROFILER_ACTIVE` |
| 足迹未加载 | `UNLOADED_FOOTPRINT` |
| 外部/非拥有足迹 | `FOREIGN_FOOTPRINT` |
| 不安全邻居 | `UNSAFE_NEIGHBOR` |
| 超出预算 | `DEFERRED_UPDATE_BUDGET` |

任何拒绝都保留**原来的 live 串行 `LevelTicks` 队列**；引擎不会先抽空队列再模拟串行。`priority`、
`subTickOrder`、`clearArea`、`copyArea`、`willTickThisTick` 继续走原路径。

## 5. 实体交互与加载后 owner

| 方法 | source 角色 | 完成 |
|---|---|---|
| `submit(source, target, action)` | 参与者 | 双方复查；回调收到 live 对 |
| `submitToOwner(source, owner, action)` | 仅准入 | owner 复查；source 可被消耗 |
| `submitToOwnerAfterChunkLoad(source, owner, pos, action)` | 仅准入 | 临时 ticket、同步加载、一次回调、`finally` 释放 |

| 限制 | 说明 |
|---|---|
| 队列 | 每实体批次 4096 请求 |
| 取消 | 只在 `RUNNING` 前；加载中不能取消 |
| 跨维度 | 拒绝 |
| 阻塞 | 加载可能阻塞服务器 tick；不是异步 |
| 续跑 | 不重放调用方整个 tick |

## 6. 全局状态与 mailbox

| 规则 | 强制方式 |
|---|---|
| region 阶段的时间/天气写入 | 已断言并拒绝 |
| 其他全局状态（规则/边界/计分板/玩家列表） | 文档约定为服务器专用 |
| 全局阶段所有权 | 显式 `GLOBAL` 段；每 level 一个 region 批次 |
| Mailbox | 有界；在 global 段 drain 后原版才继续 |
| 全局 region worker | 可选开关；仍受存储 guard 约束 |

## 7. 玩家与交接

| 属性 | 行为 |
|---|---|
| Tick 线程 | 默认服务器线程；开启实验性 `enableRegionThreadingPlayerTicks` 可将已准入调用派发到 region worker（`FIXED_3X3`，或 `DYNAMIC_CLUSTER` 配合 `-Dforgia.dynamicPlayerSoak=true`，见 [player-ticks](player-ticks.zh.md)） |
| 成员 | 恰好一个 region |
| 快照 | 在 level tick 头部发布，每 tick 替换一次，卸载时丢弃 |
| 交接 | 行走、1000 格传送、批次中传送、下界往返、两名玩家交换区域 |
| 认证 | 有界测试；不是多人负载认证 |

## 8. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `enableRegionThreadingScheduledTicks` | `false` | 仅灯队列 |
| `enableGlobalRegionThreadingWorker` | `false` | 全局队列 drain 上池 |
| `enableRegionThreadingPackedRegionLeases` | `false` | 每任务 16 区域（仅 `FIXED_3X3` 生效；`DYNAMIC_CLUSTER` 下自动绕过） |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | 每队列 drain 的到期任务 |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | 每 drain 的立即任务 |

```toml
# config/forgia.toml —— scheduled tick 队列与 drain
enableRegionThreadingScheduledTicks = true
# enableGlobalRegionThreadingWorker = true
regionThreadingMaxScheduledTasksPerDrain = 10000
regionThreadingMaxImmediateTasksPerDrain = 10000
```

```text
/neoforge regionthreading compat              # 操作计数、scheduled 原因/延迟
/neoforge regionthreading workgraph start 200 # 观察 200 个阶段的有界访问图
/forgia region minecraft:overworld 2 -1       # 单个区域（区域坐标）
```

## 9. 对比

| 属性 | BE 操作 | 实体交互 | Scheduled tick | Mailbox/全局 |
|---|---|---|---|---|
| 单位 | 两个 BE | 实体 + owner | Block/fluid 回调 | 消息 |
| 结果句柄 | `Task<R>` | `EntityInteractionTask` | 无 | 无 |
| 跨区并行 | 否（完成串行） | 否（owner） | 是，区域不相交 | 否 |
| 加载 chunk | 否 | 仅 `AfterChunkLoad` | 否 | 否 |
| 失败重放 | 绝不 | 绝不 | 绝不 | 消息丢弃并计数 |
| 认证 | 实验性 | 实验性 | C6 仅灯 | 未认证 |

## 10. 失败语义

| 事件 | 行为 |
|---|---|
| 操作参与者被替换 | `SKIPPED`/`*_REPLACED`；需重新建立链接 |
| Scheduled 回调抛异常 | 报告；失败组未开始项计 `skipped` |
| Mailbox 目标关闭 | 消息丢弃并计数 |
| region 写全局状态 | 断言拒绝 |
| 交互加载期间 owner 退休 | 跳过回调，释放 ticket |
| 停机 | 队列 drain；已受理工作物理完成；之后服务拒绝 |

## 11. 限制

| 限制 | 说明 |
|---|---|
| 无公开任意 scheduled 回调 API | 只准入灯队列 |
| 无跨维度 BE 操作 | 拒绝 |
| 无并行多 owner lease | 完成阶段串行 |
| 无异步 chunk 加载 | 仅同步 ticket 加载 |
| worker 无全局写许可 | 六类状态保持服务器专用 |
| 无多人认证 | 仅有界交接测试 |

## 12. 诊断

| 信号 | 含义 |
|---|---|
| `blockEntities().counters()` | accepted/completed/skipped/cancelled/rejected/failed、callbackNanos、reasons |
| `api.scheduledTicks()` | 阶段计数、完成、拒绝、计时、p50/p95/p99 |
| `compat` | 操作计数、scheduled 原因/延迟、适配器状态 |
| `/forgia region ...` | 区域负载；BE/串行 fallback 覆盖不完整 |

## 13. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时用 BE 操作 | 机器必须合法完成与外部容器的交互 |
| 何时不用 | 只需要延迟任务，或想并行机器 tick |
| 何时 scheduled tick 才并行 | 仅在已加载半径 2 惰性足迹内、完整灯阶段的场景 |
| 为什么 fail closed | 未知回调与混合阶段可能触碰任何东西；串行是唯一安全默认 |
| 为什么玩家留在服务器线程 | 玩家始终 tick 且全局可见；没有 lease 能安全拥有它们 |
