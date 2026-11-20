# 模块：Chunk tick 与跨区效果

[English](chunk-ticks.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`RegionChunkTickCoordinator`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionChunkTickCoordinator.java)、[`RegionBorderUpdates`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionBorderUpdates.java)、[`RegionBorderGameEvents`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionBorderGameEvents.java)、[`RegionDeferredBlockUpdates`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionDeferredBlockUpdates.java)
证据：[STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)、[PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md)、[GUARD-INVENTORY](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/GUARD-INVENTORY.md)

状态：随机 chunk tick 在 C6 内**已认证**；边界更新与 game event 为**实验性**
（`enableRegionThreadingBorderUpdates`、`enableRegionThreadingGameEvents` 默认关闭，C6 之外）。

---

## 1. 职责

| 拥有 | 不拥有 |
|---|---|
| 完整 chunk tick 正文：随机 tick、冰霜/积雪、雷暴 | 自然/自定义刷怪（独立切片） |
| 每 chunk 确定性随机派生 | 原版世界 RNG 顺序 |
| 把 lease 外写入记录进边界队列 | 提前运行目标区域正文 |
| 同 tick fixpoint 传播这些效果 | 无界级联；溢出延后并计数 |
| game event 的外部监听器捕获 | 任意事件监听器的线程安全 |
| 延迟方块更新发布 | 让活塞/移动装置感知区域 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `RegionChunkTickCoordinator` | 构建并派发 chunk tick 批次 |
| `RegionChunkTickPlan` / `...Entry` / `...Batch` | 每 chunk 工作列表、派发、join |
| `RegionChunkTickAttribution` | 每阶段计时 |
| `RegionDeferredBlockUpdates` | 延迟 block/light 效果队列 |
| `RegionBorderUpdates` / `RegionBorderPropagationBatch` | 跨区域邻居传播与 fixpoint |
| `RegionBorderGameEvents` | 捕获并投递外部 game event |
| `RegionAwareRandomSource` / `NamedRandomSequences` | 区域本地随机派生 |
| `RegionNeighborUpdateScope` / `RegionCollectingNeighborUpdater` | 邻居更新捕获 |

## 3. Tick 流程

| 阶段 | 线程 | 动作 |
|---|---|---|
| 1 | 服务器 | 收集可 tick chunk，校验所有权/邻域/预算 |
| 2 | 服务器 | 冻结每 chunk 元数据，派生区域本地随机序列 |
| 3 | Worker | 运行完整 chunk 正文（随机 tick、冰霜/积雪、雷暴） |
| 4 | Worker | 记录外部写入到边界队列；排队延迟方块更新 |
| 5 | 服务器 | 屏障 join；发布延迟效果 |
| 6 | 服务器 | 运行边界 fixpoint 直到稳定或达到级联预算 |
| 7 | 服务器 | 按发布顺序把捕获的 game event 投递给拥有区域 |
| 8 | 服务器 | 被拒 chunk 的串行 fallback |

## 4. 准入门

| 门 | 说明 |
|---|---|
| 固定布局 + profiler 未激活 | 必需 |
| 邻域已加载 | 区域 chunk 邻域必须已加载且有 live 映射 |
| 所有权/预算 | 有效 lease 与剩余级联/延迟更新预算 |
| 无不受支持邻居 | 外部/无限制邻居会让 chunk 保持串行 |
| Profiler | 激活 → inline |

## 5. 边界传播与 fixpoint

| 行为 | 说明 |
|---|---|
| worker 在 lease 外写入 | 记录，不由 worker 执行 |
| 目标区域存活 | 由 owner 在**同一 tick** 应用 |
| 目标缺失/未加载 | **带计数**丢弃，绝不静默 |
| 级联 | 有界；溢出延后到下一 tick |
| 正文重放 | 绝不：过期传播读不会靠重跑 chunk 正文修复 |
| 解除内部规则 | chunk tick 可触碰边界邻居而不整 chunk fallback（这正是把 100% 派发 chunk 移到 worker 的原因） |

## 6. Game event（实验性）

| 方面 | 说明 |
|---|---|
| 捕获什么 | 从发布 worker 到达的外部监听器列 |
| 投递 | 在拥有区域上，按发布顺序 |
| 限制 | 有界，丢弃计数 |
| 开关关闭 | 从发布 worker inline 派发 —— 该基线**可能**与外部监听器竞争 |
| 开关开启 | 捕获路径；C6 之外，必须显式验证目标配置 |

## 7. 配置

| 键 | 默认 | 需重启 | 效果 |
|---|---|---|---|
| `enableRegionThreadingRandomTicks` | `false` | 是 | 整个 chunk tick 正文在 worker |
| `enableRegionThreadingBorderUpdates` | `false` | 是 | 跨区邻居捕获 + fixpoint |
| `enableRegionThreadingGameEvents` | `false` | 是 | 外部 game event 捕获；关闭时保持 inline 派发 |
| `regionThreadingMode` / `FixedRegionChunks` | `FIXED_3X3` / `3` | 是 | 要求固定布局 |
| `regionThreadingOwnershipViolationMode` | `WARN` | 否 | 建议性断言 |

```toml
# config/forgia.toml —— chunk tick 与可选跨区效果
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingRandomTicks = true
# enableRegionThreadingBorderUpdates = true  # 实验性，C6 之外
# enableRegionThreadingGameEvents = true     # 实验性，C6 之外
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
```

## 8. 实测负载（历史）

| 负载 | 关闭（ms/切片） | 区域化 | 加速 |
|---|---|---|---|
| 1024 chunk 随机 tick（9×9 区域） | 5.59 / 5.87 | 4.80 / 4.64 | 1.15–1.27× |
| 1024 chunk 随机 tick（3×3，解除内部规则） | 6.88–6.92 | 5.54 | 1.24–1.25× |

解除内部规则后，**100% 派发 chunk tick** 在 worker 上运行（1.31 M tick，零边界回退），生产 soak 落在 inline 或以下。

## 9. 确定性

| 属性 | 保证 |
|---|---|
| 每 chunk 随机派生 | 与区域布局、worker 数、tick 顺序、线程无关 |
| 命名随机序列 | 区域感知包装从组内解析；不要构造它作为安全声明 |
| 原版全局 RNG 顺序 | 跨区域不保留 |

## 10. 失败语义

| 事件 | 行为 |
|---|---|
| chunk 正文抛异常 | 报告；正文不重放 |
| 超出级联预算 | 溢出延后到下一 tick，计数 |
| 目标区域缺失 | 更新丢弃，计数 |
| 邻域未加载 | 开始前完整串行 chunk 正文 |
| 停机 | 延迟队列 drain 或带计数丢弃 |

## 11. 对比

| 属性 | Chunk 随机 tick | 边界更新 | Game event |
|---|---|---|---|
| 范围 | 自身 chunk 正文 | 邻居效果 | 外部监听器 |
| 默认 | 关闭 | 关闭 | 关闭 |
| 认证 | C6 | C6 之外 | C6 之外 |
| 同 tick 收敛 | 不适用 | 是（有界 fixpoint） | 发布顺序 |
| 关闭风险 | — | 原版 collector 路径 | inline 派发可能与外部监听器竞争 |

## 12. 限制

| 限制 | 说明 |
|---|---|
| 级联上限 | 不是无限同 tick 收敛 |
| 红石 | 49 布局 / 5 880 tick 比较与原版一致，但任意移动装置/活塞仍不感知区域 |
| 结构 | 不感知区域 |
| 模组 chunk/事件回调 | 仍需整合包验证 |
| game event 关闭 | 不是通用安全声明 |

## 13. 诊断

| 信号 | 含义 |
|---|---|
| Chunk tick 计数 | 每窗口 dispatched/worker/serial/truncated |
| 边界计数 | applied/dropped/delayed |
| Game event 计数 | captured/delivered/dropped |
| `/forgia region ...` | 区域负载/覆盖；BE/串行 fallback 覆盖不完整 |

## 14. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时开启随机 tick | chunk 随机 tick 负载占主导且世界为固定布局 |
| 为什么只有约 1.2× | 相对协调与发布成本，每 chunk 正文很小 |
| 何时开启边界更新 | 需要跨边界红石/邻居效果同 tick 收敛 |
| 为什么默认关闭 | fixpoint 与捕获路径是实验性的，有丢弃语义 |
| 为什么绝不重放正文 | 部分效果会重复；过期读被延后处理，而不是靠重跑修复 |
