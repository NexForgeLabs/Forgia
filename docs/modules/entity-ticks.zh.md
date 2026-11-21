# 模块：实体 tick

[English](entity-ticks.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`RegionEntityTickPlan`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityTickPlan.java)、[`RegionEntityTickBatch`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionEntityTickBatch.java)、[`RegionScopedEntityQuery`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionScopedEntityQuery.java)、[`RegionPlayerSnapshots`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPlayerSnapshots.java)
证据：[OWNERSHIP-PROTOCOLS-VALIDATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/OWNERSHIP-PROTOCOLS-VALIDATION.md)、[ENTITY-CALLBACK-LIFECYCLE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/ENTITY-CALLBACK-LIFECYCLE.md)、[STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)

状态：在 C6 范围内（`enableRegionThreadingEntityTicks`）**已认证**。默认关闭。

---

## 1. 职责

| 拥有 | 不拥有 |
|---|---|
| 按区域分区的实体存储与 tick 批次 | 默认玩家 tick（除非开启实验性 `enableRegionThreadingPlayerTicks`，否则留在服务器线程） |
| identity ledger、transfer journal、延迟加/删/section 更新 | 实体 AI 实现或模组回调 |
| region 作用域实体查询回答（index、bucket、玩家快照） | chunk 加载 |
| 每实体归因与查询计数 | 保证模组 tick 正文安全 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `RegionEntityTickPlan` / `RegionEntityTickEntry` | 把实体排进每区域计划 |
| `RegionEntityTickBatch` / `RegionEntityTickSlice` | 派发批次、join、发布 |
| `RegionEntityStorageMode` / `FixedEntitySectionPartitions` | region-owned 存储布局 |
| `EntityIdentityLedger` / `EntityIdentity` | 跨移动的身份稳定性 |
| `RegionEntityTransferJournal` / `RegionEntityTransferToken` | 跨区域实体交接 |
| `RegionDeferredEntityAdditions` / `...Removals` / `...SectionUpdates` | 延迟成员变更 |
| `RegionScopedEntityQuery` / `RegionAwareEntityGetter` / `RegionEntityQueryCounters` | worker 侧查询回答 |
| `RegionPlayerSnapshots` | 每 tick 不可变玩家快照 |
| `RegionEntityTickPolicy` / `RegionEntityTickActivation` | 资格与正文前检查 |
| `RegionEntityTickAttribution` / `RegionEntityOwnershipScope` | 计时与所有权断言 |

## 3. Tick 流程

| 阶段 | 线程 | 动作 |
|---|---|---|
| 1 | 服务器 | 构建每区域实体计划（tick-list 顺序，固定排除项） |
| 2 | 服务器 | 发布玩家快照；冻结批次元数据 |
| 3 | Worker | tick 租出区域的实体，用区域数据回答查询 |
| 4 | Worker | 记录外部写入/延迟加删 |
| 5 | 服务器 | 屏障 join；边界传播 |
| 6 | 服务器 | 发布延迟 block/light/实体变更；drain transfer journal |
| 7 | 服务器 | 被拒实体串行 fallback；失败报告 |

同一 level 只有一个实体批次在飞；屏障每个切片付一次，不是每个实体。

## 4. 准入与资格

| 门 | 说明 |
|---|---|
| 分区布局 + region-owned 存储 | 必需（`DYNAMIC_CLUSTER` 或 `FIXED_3X3`）；遗留 `DYNAMIC` 不支持 |
| 原版 profiler 未激活 | 激活时实体保持 inline |
| 未被固定 | 固定类/类型与骑乘栈保持串行 |
| 正文前足迹可用 | 否则实体保持串行 |
| chunk 已租出 | worker 触到 lease 外 chunk 会截断该工作一次并计数 |
| 服务器专用声明 | `entities`/`block_entities` 优先于任何准入 |

## 5. 回答给 worker 的查询面

| 入口 | worker 回答 | 计数 |
|---|---|---|
| `get(int)`、`getEntity(int)`、`getEntityOrPart(int)` | level index 发布后用区域实体 index | `local`；外部区域、延迟移除、未发布 index 记 `refused` |
| `get(UUID)`、`getEntity(UUID)` | 区域 index（弹射物 owner 解析） | `local`/`refused` |
| `getAll()`、`getAllEntities()` | 区域 index，按 tick-list 顺序 | `local`；level index 更大时 `truncated` |
| `get(EntityTypeTest, consumer)`、`getEntities(EntityTypeTest, Predicate, List, int)` | 区域 index | `local`/`truncated` |
| `get(AABB, Consumer)`、`getEntities(...AABB...)`、`Level.getEntities(...)` | 区域 section bucket，按 lease 裁剪；`Level.getEntities` 还追加已回答 multipart owner 的 parts | `local`；裁剪时 `truncated` |
| 玩家类查询（`getEntitiesOfClass(Player.class, box)` 及子类） | 区域 bucket + 快照中记录包围盒相交的玩家 | `local` + `published` |
| `players()`、`getNearestPlayer`、`getPlayerByUUID`、`getNearbyPlayers`、`hasNearbyAlivePlayer`、`getPlayers`、`getRandomPlayer` | 每 tick 玩家快照 | `published`；无快照时 `refused` |
| `EntityStorageGuardedGetter` | 仍拒绝（无 level → 无 lease） | `refused` |
| transfer journal、identity ledger、section partitions | 仍拒绝（服务器拥有状态） | 违规计数 |

| 快照事实 | 后果 |
|---|---|
| 在 level tick 头部发布，替换而非修改 | 最多落后一个 tick |
| 加入/离开/换维度的玩家不在其中 | 查询反映 tick 开始时的状态 |
| 包围盒是 tick 开始时的记录 | 裁剪盒查询与原生 section 扫描一致 |
| 实体本身是活的 | 谓词/目标选择读当前状态，与原版一致 |
| 无快照 → 拒绝 | 绝不回退到 live 列表 |

## 6. 延迟写入与发布

| 延迟工作 | 应用时机 |
|---|---|
| worker 产生的 block/light 效果 | join 后、串行 fallback 之前的服务器阶段 |
| 实体加入 | 服务器阶段，有序 |
| 实体移除 | 服务器阶段，在操作完成之前 |
| 实体 section 更新 | 服务器阶段 |
| 跨区域实体交接 | 边界处 drain journal |

worker 在 lease 外的写入会被记录，并由目标区域在同一 tick 应用（边界传播）；没有 live 区域的去向会
**带计数**丢弃，绝不静默。

## 7. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `enableRegionThreadingEntityTicks` | `false` | 需要 `DYNAMIC_CLUSTER` 或 `FIXED_3X3`、region-owned 存储、profiler 未激活；隐含 POI 存储 |
| `enableRegionThreadingPoi` | `false` | 即使为 false 也会被实体 tick 隐含 |
| `regionThreadingMode` | `FIXED_3X3` | `DYNAMIC_CLUSTER`（推荐）或 `FIXED_3X3`（C6 基线）；遗留 `DYNAMIC` 保持串行 |
| `regionThreadingDynamicSectionShift` / `regionThreadingFixedRegionChunks` | `3` / `3` | `DYNAMIC_CLUSTER` Section 位偏移（`3` = 8×8）或 `FIXED_3X3` 固定边长 |
| `regionThreadingWorkerThreads` | `0` | 可复现测量需固定 |
| `regionThreadingOwnershipViolationMode` | `WARN` | 仅建议性断言；存储 guard 仍独立拒绝 |

```toml
# config/forgia.toml —— 最小实体 tick 切片（推荐 DYNAMIC_CLUSTER；FIXED_3X3 为 C6 基线）
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true          # 隐含 POI 存储
regionThreadingMode = "DYNAMIC_CLUSTER"
regionThreadingDynamicSectionShift = 3
regionThreadingWorkerThreads = 4
```

## 8. 实测负载（历史）

| 负载 | 关闭（服务器） | 区域化 | 加速 |
|---|---|---|---|
| 1024 牛 | 27.3 / 19.3 mspt | 6.0 / 5.8 | 4.5× / 3.4× |
| 1024 僵尸 | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |
| 1024 村民（床 + 工作站） | 54.5 / 52.0 | 25.7 / 22.0 | 2.1× / 2.4× |

这些是有界夹具，不是整合包结论。

## 9. 失败语义

| 事件 | 行为 |
|---|---|
| worker tick 抛异常 | join 后由服务器线程报告；ticker 不重放 |
| chunk 在 lease 外 | 工作截断一次；类移到服务器线程；计数 |
| 被查询实体已延迟移除 | 查询拒绝，不用过期 index 回答 |
| 停机/卸载 | 遵守物理完成边界；journal drain 或带计数丢弃 |
| 模组回调做外部写入 | 记录；边界处收敛或带计数丢弃 |

## 10. 对比

| 属性 | 实体 tick | Chunk tick | 方块实体 tick |
|---|---|---|---|
| 所有权单位 | 实体 → 区域 | Chunk → 区域 | 方块实体 → 区域批次 |
| 每 tick 正文 | 原实体 tick | 随机/冰霜/雷暴 | BE 原 ticker 前缀 |
| 典型阻塞 | 固定类型、骑乘栈、外部查询 | 未加载邻域、级联预算 | 共享资源、未知分支、2048 阶段 |
| 玩家处理 | 快照查询 | 不适用 | 不适用 |
| 实测收益 | 密集生物时高 | 中等（1.15–1.27×） | 未声明 |

## 11. 限制

| 限制 | 说明 |
|---|---|
| 玩家 | 默认在服务器线程；可通过实验性 `enableRegionThreadingPlayerTicks` 显式开启（见 [player-ticks](player-ticks.zh.md)） |
| 模组 AI | 任意模组 tick 正文未被 C6 闭包认证 |
| 结构/移动装置 | 类实体移动装置不感知区域 |
| 查询面 | 只有文档化入口被回答，其他拒绝 |
| 负载自适应 | 无；仅静态布局 |

## 12. 诊断

| 信号 | 含义 |
|---|---|
| `Region entity queries:` 行 | 各操作的 `local`/`published`/`refused`/`truncated` 计数 |
| 所有权违规计数 | 建议性违规（适用处也有强制 guard 拒绝） |
| `/forgia region ...` | 区域计时/负载/覆盖 |
| `/forgia workers` | busy/idle/queued/completed/failed |

## 13. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时开启 | 服务器线程是瓶颈的密集实体负载，且已演练 canary |
| 如何开启 | 固定布局、region-owned 存储、固定 worker 数、核对计数 |
| 为什么需要 region-owned 存储 | 原版存储是服务器线程可变的；worker 不能遍历它 |
| 为什么需要玩家快照 | 玩家是始终 tick 的服务器线程实体；worker 绝不能遍历 live 列表 |
| 为什么要截断 | 部分正文后重放会重复效果 |
