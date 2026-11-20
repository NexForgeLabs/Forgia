# 模块：region 引擎、布局与所有权

[English](region-engine.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`RegionThreadingEngine`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionThreadingEngine.java)、[`RegionLayout`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionLayout.java)、[`LevelTickPhase`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/LevelTickPhase.java)、[`RegionWorkerPool`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionWorkerPool.java)
证据：[FOLIA-REFERENCE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/FOLIA-REFERENCE.md)、[PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md)、[STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)

C6 认证覆盖固定布局、4 个 worker 和文档列出的开关。所有开关默认 `false`。`DYNAMIC_CLUSTER` 为实验模式，属于 C6 认证范围之外。

---

## 1. 职责

| 拥有 | 不拥有 |
|---|---|
| chunk→region 映射、每区域 lease、批次调度与屏障 | chunk 生成/IO 并行 |
| 把每个原版 tick 切片交给 region worker | 默认玩家 tick 执行（除非显式开启实验性 `enableRegionThreadingPlayerTicks`，否则玩家留在服务器线程） |
| 存储与全局状态的所有权断言 | 结构/活塞/移动装置区域化 |
| worker 池生命周期与停机 join | 各切片自己的准入 |
| 冲突策略、兼容拒绝、配置捕获 | 吞吐量保证 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `RegionThreadingEngine` | 安装、配置捕获、每 tick 编排、切片派发、join、停机 |
| `RegionLayout` / `RegionPolicy` / `RegionCoordinate` | 固定区域、加载岛 Section 和遗留 16-chunk 网格的静态坐标 |
| `DynamicRegionTopology` | 跟踪加载区块，合并或延迟拆分加载岛，发布 Section 到加载岛的映射 |
| `RegionState` / `RegionSchedule` / `RegionLifecycleState` | 每区域所有权、队列与生命周期 |
| `RegionOwnershipLeaseGroup` / `RegionLeasePack` | lease；每任务最多 16 个区域的全有或全无获取 |
| `RegionWorkerPool` / `RegionWorkerExecutor` / `RegionWorkStealingDispatcher` | 固定 executor，或持久 deque + 区段窃取 |
| `RegionTickBarrier` / `RegionTickGrouping` / `RegionTaskBatch` | 批次形成、屏障等待、分组任务 |
| `RegionMailbox` / `GlobalRegionState` / `GlobalRegionTickRunner` | 有界跨区消息与全局阶段 |
| `LevelTickPhase` / `LevelTickPhaseState` | 显式 GLOBAL/REGION 交替与违规检测 |
| `RegionThreadingDiagnostics` / `RegionMetricsReport` | 计数、计时、区域快照 |
| `RegionThreadingConfig` / `RegionThreadingOptions` | 校验后的启动配置 |

## 3. Tick 流程

```
ServerLevel.tick（服务器线程）
 ├─ GLOBAL 段   : 时间、天气、边界、袭击、chunk source、方块事件、实体管理
 ├─ REGION 段   : 派发切片批次 → worker tick 租出区域 → 屏障 join
 ├─ GLOBAL 段   : 发布延迟效果、drain mailbox
 ├─ REGION 段   : 下一个切片
 └─ ……重复；同一 level 同时只有一个批次在飞
```

| 规则 | 后果 |
|---|---|
| 每 level 同时只有一个 region 批次 | 同一 level 的两个 region 切片绝不重叠 |
| GLOBAL 阶段持有 level 级状态 | worker 写入即所有权违规 |
| Region 阶段只拥有租出的区域 | 允许读全局状态，禁止写 |
| Mailbox 在 global 段 drain | 全局消息在原版正文继续前应用 |
| 阶段转换是显式状态并被计数 | 不是约定；违规会被报告 |

## 4. 全局状态所有权（已断言）

| 状态 | 由谁写 | 强制方式 |
|---|---|---|
| 时间：game time、day time、day fraction、day per tick | `ServerLevel.tickTime`、`setDayTime`、`setDayTimeFraction`、`setDayTimePerTick` | 已断言：region 阶段被拒 |
| 天气 | `advanceWeatherCycle`、`setWeatherParameters` | 在 `setWeatherParameters` 断言 |
| 游戏规则 | `GameRules.Value#set` 及各规则 setter | 仅文档约定 |
| 世界边界 | `WorldBorder#setSize`、`setCenter`、警告/伤害 | 仅文档约定 |
| 计分板 | `ServerScoreboard` 目标/队伍/分数 | 仅文档约定 |
| 玩家列表 + 网络状态 | `PlayerList#add/remove`、`ChunkMap` tracking、连接 tick | 仅文档约定 |

“已断言”表示访问器会在 region 阶段调用 hook 抛异常/计数；“仅文档约定”表示修改器分散在多个对象上，
没有单点可拦截。区域代码必须把六行都视为服务器专用。

## 5. 任何 region 工作之前的准入门

| 门 | 说明 |
|---|---|
| bridge 已安装 | `enableRegionThreadingBridge`；已知冲突策略可能拒绝安装 |
| 布局 | `FIXED_3X3` 是 C6 基线。`DYNAMIC_CLUSTER` 使用固定 Section 坐标，并保留各切片的准入检查。 |
| profiler 未激活 | 激活的原版 profiler 会让切片保持 inline |
| 邻域已加载 | chunk/BE/刷怪切片要求；FULL ticket 本身不能证明加载完成 |
| 所有权/存储模式 | 实体 tick 需要 region-owned 实体存储；各切片有各自 guard |
| 预算 | 延迟更新、级联、队列与阶段预算 |
| 兼容声明 | 服务器专用类型/切片优先 |

## 6. 配置（引擎相关子集）

| 键 | 默认 | 范围 | 需重启 | 后果 |
|---|---|---|---|---|
| `enableRegionThreadingBridge` | `false` | bool | 是 | 安装引擎；所有切片依赖它 |
| `enableRegionThreadingWorkers` | `false` | bool | 是 | region 队列 drain 上池；不是其他切片的总开关 |
| `enableGlobalRegionThreadingWorker` | `false` | bool | 是 | 把 global region drain 移到池（C6 之外） |
| `regionThreadingMode` | `"FIXED_3X3"` | `FIXED_3X3`/`DYNAMIC`/`DYNAMIC_CLUSTER` | 是 | `DYNAMIC_CLUSTER` 为实验性加载岛派发，`DYNAMIC` 为遗留策略 |
| `regionThreadingDynamicSectionShift` | `3` | 2..4 | 是 | `DYNAMIC_CLUSTER` 的 Section 边长为 `1 << shift` 个 chunk |
| `regionThreadingDynamicSplitDelayTicks` | `40` | 0..1200 | 是 | Section 卸空后等待多少 tick 才参与拆分 |
| `regionThreadingFixedRegionChunks` | `3` | 3..32 | 是 | N×N chunk，16N×16N 方块，全高度 |
| `regionThreadingWorkerThreads` | `0` | 0..512 | 是 | `0` → `max(1, cpus-1)`；可复现性要求显式固定 |
| `enableRegionThreadingPersistentWorkers` | `false` | bool | 是 | 持久 deque + work stealing 取代固定 executor |
| `enableRegionThreadingPackedRegionLeases` | `false` | bool | 是 | 每任务最多 16 个区域全有或全无 |
| `regionThreadingOwnershipViolationMode` | `WARN` | `WARN`/`THROW`/`DISABLED` | 否 | 断言处理；所有模式都计数；强制检查独立拒绝 |
| `regionThreadingLongTickWarningNanos` | `0` | ≥0 | 否 | 队列 drain 告警阈值；0 关闭 |
| `regionThreadingMaxImmediateTasksPerDrain` | `10000` | ≥0 | 否 | 每队列立即 drain 数量（0 不限） |
| `regionThreadingMaxScheduledTasksPerDrain` | `10000` | ≥0 | 否 | 每队列到期调度任务（0 不限） |
| `regionThreadingForceWithKnownConflicts` | `false` | bool | 是 | 仅测量的绕过；明确不支持 |

## 7. 冲突策略

| 模组 / 条件 | 动作 |
|---|---|
| `c2me` | `DISABLE_ENGINE` |
| `mobspawncontroller` | `DISABLE_SPAWNING` |
| `modernfix`、`moonlight`、`sable`、`voxy`、`byepregen`、`ftbchunks`、`alltheleaks`、`create`、`spawn` | REPORT_ONLY 行（不是证书） |
| mod metadata `regionThreading = UNSUPPORTED` | 阻止 bridge 安装 |
| `REQUIRED` 且 bridge 关闭 | 诊断警告；不会自动开启引擎 |
| 未知模组 | 不会仅因未知而被拒 |

检测按 mod id；未知变换不会被穷尽发现。

## 8. 失败语义

| 事件 | 行为 |
|---|---|
| worker tick 失败 | 由服务器线程报告；绝不在 worker 上继续构建工作 |
| tick 触到 lease 未持有的 chunk | 截断一次；其类移到服务器线程 |
| 所有权违规 | 计数；WARN 记录、THROW 抛出、DISABLED 只抑制报告 |
| 停机 | 已受理工作必须物理完成；线程池 join |
| 配置重载 | 引擎/布局开关仅启动生效；必须新 JVM |
| Level 卸载 | 通过生命周期边界释放 lease；延迟工作 drain 或带计数丢弃 |

## 9. 对比

`DYNAMIC_CLUSTER` 按实际加载的 chunk 建立 Section 连通分量。相邻 Section（含对角）在全局阶段合并，卸空的 Section 到期后触发拆分。普通批次可合并同一加载岛内的 worker 提交。待派发任务多于 worker 时才合并，并保留至少与 worker 数相同的任务。每个 Section 按切片记录近期耗时。合包成本上限取最重单项和平均每个 worker 成本的较大值。每个 Section 各自持有租约并应用现有所有权检查。有序刷怪依赖多个参与者并行推进，因此沿用独立提交。`/forgia regions` 在当前维度显示加载岛数量、大小、合并拆分计数及 `submissionsSaved`。区域耗时仍按 Section 记录。

49 个负载区块的随机刻 GameTest 使用 4 个 worker，每种布局单次 JVM 运行，预热 200 tick，测量 600 tick。固定 3×3、固定 8×8 和动态 8×8 的平均 MSPT 分别为 2.110、1.770 和 1.948。三组的 worker 区块 tick 计数均为 39,249，所有权违规及回退为零。动态 8×8 的平均 chunk 批次等待为 0.861 ms，固定 8×8 为 0.558 ms。这些是加入并行度下限之前的结果。加入下限后的单次复测得到平均 2.871 MSPT、P99 9.812 MSPT，worker 区块 tick 数、所有权违规和回退计数相同。各次运行的随机状态指纹各异，需要独立 JVM 重复测量来评估下限对耗时的影响。

加权合包在同一份源码的独立目录中与按任务数合包对照。两组使用相同世界种子、固定负载坐标、144 个负载区块和 4 个 worker，各预热 200 tick，测量 600 tick。平均 MSPT 从 4.050 降至 2.464，P99 从 10.619 降至 3.961，平均 chunk 批次等待从 1.999 ms 降至 1.061 ms。两组的 worker 区块 tick 数均为 115,344，回退与所有权违规均为零。每组运行一次，结束时随机状态指纹不同。多岛扩展性和整岛所有权仍待验证。

| 选择 | 固定 N×N | 遗留 DYNAMIC |
|---|---|---|
| 区域边界 | 从不合并/拆分 | 16-chunk 网格合并/拆分 |
| 实体/刷怪/POI/细粒度熔炉支持 | 是 | 未认证；多项要求无法满足 |
| 负载自适应尺寸 | 未实现 | 未实现 |
| 配置 | `FIXED_3X3` + size | `DYNAMIC` |

| 选择 | 固定 executor | 持久 + work stealing |
|---|---|---|
| 线程 | 按需启动 | 立即启动并常驻 |
| 派发 | 提交整任务 | 批量提交、窃取未开始区段 |
| 实测 | 基线 | 仅派发测量；未声明整服加速 |
| 默认 | 是 | 关闭 |

## 10. 限制

| 限制 | 说明 |
|---|---|
| 所有权粒度 | 加载岛派发保留独立的 Section 租约和跨 Section 检查 |
| 每 level 一个批次 | 同一 level 内 region 切片不重叠 |
| 玩家 | 由服务器线程 tick；仅有有界交接测试 |
| 结构/移动装置 | 不感知区域；仍在服务器线程 |
| 跨区操作 | 在串行完成阶段完成，不并行 |
| 吞吐 | 认证的是正确性与范围，不是“处处快于原版” |

## 11. 诊断

| 信号 | 位置 |
|---|---|
| 阶段转换与违规 | `RegionThreadingDiagnostics`、`compat`/`status raw` |
| worker 数量/队列/窃取 | `/forgia workers`、dispatch 行 |
| 区域计时/覆盖 | `/forgia regions`、`/forgia region <dim> <x> <z>` |
| 屏障等待 | 状态行（`barrier wait`）、归因计数 |

## 12. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 运维何时应开启？ | 先读就绪审计并演练 canary；绝不“顺手打开” |
| 如何安全开启？ | 停机、显式设置 bridge/workers/layout、固定 worker 数、启动新 JVM、用 `compat` 验证 |
| 为什么默认关闭？ | 不支持的工作必须保持串行；打开引擎不会让模组变安全 |
| 模组作者何时该关心？ | 当某个切片需要工作离开服务器线程时 —— 且只能通过文档化 API |
| 为什么用固定网格？ | 所有权确定、测量可复现；动态合并/拆分破坏细粒度合同 |
