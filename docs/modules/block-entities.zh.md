# 模块：方块实体与 canonical 阶段

[English](block-entities.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`CanonicalBlockEntityCoordinator`](../../src/main/java/net/neoforged/neoforge/server/threading/region/CanonicalBlockEntityCoordinator.java)、[`NativeBlockEntityPreparation`](../../src/main/java/net/neoforged/neoforge/server/threading/region/NativeBlockEntityPreparation.java)、[`FurnaceWorkPreparation`](../../src/main/java/net/neoforged/neoforge/server/threading/region/FurnaceWorkPreparation.java)、[`BlockEntityResourceOwnership`](../../src/main/java/net/neoforged/neoforge/server/threading/region/BlockEntityResourceOwnership.java)、[`AuditedBlockEntityPreparation`](../../src/main/java/net/neoforged/neoforge/server/threading/region/AuditedBlockEntityPreparation.java)
证据：[BLOCK-ENTITY-PARALLELISM](../../region-threading/BLOCK-ENTITY-PARALLELISM.md)、[CANONICAL-BLOCK-ENTITY-COORDINATOR](../../region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md)、[BLOCK-ENTITY-WORK-GRAPH](../../region-threading/BLOCK-ENTITY-WORK-GRAPH.md)、[C6-FINAL-ACCEPTANCE-2026-11-15](../../region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md)

状态：有界队列在 C6 内**正确性已认证**；**未声明整服加速**。`enableRegionThreadingBlockEntityTicks` 默认关闭。
熔炉波次、资源与模组贡献是分别受门控的实验特性。

---

## 1. 职责

| 拥有 | 不拥有 |
|---|---|
| 一个带单调 cursor 的 canonical `Level.tickBlockEntities` 阶段 | 第二套 BE 循环或调度器 |
| 连续前缀执行与完整串行 fence | 模组机器语义 |
| Native/Furnace/Mod proof 准入与新鲜复核 | 未证明类型的 Claims 转换 |
| READ/WRITE 资源 claim、lease 与一次性 scope（实验） | 外部/世界级写入的所有权 |
| Work-graph 观察与有向波次规划（可选） | 任意依赖发现 |
| 受审计适配器集成（`AUDITED_BLOCK_ENTITY_TICKS`） | 成为第三方代码的沙箱 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `CanonicalBlockEntityCoordinator` | 单一 cursor、前缀规划、fence 放置、后缀重查 |
| `RegionBlockEntityTickPlan` / `...Batch` / `...Chunk` / `...Scope` | 每 chunk BE 工作、派发、所有权作用域 |
| `RegionBlockEntityTickAdmission` / `...ChunkCache` | 类型/chunk 准入缓存（策略 + 布局 + revision） |
| `NativeBlockEntityPreparation` | 精确 native 函数/实例/分支证明 |
| `FurnaceWorkPreparation` / `FurnaceWorkBatch` / `FurnaceWorkScope` | 已燃烧熔炉波次准备与执行 |
| `BlockEntityResourceOwnership` / `BlockEntityWorkPlanner` | claim、非阻塞预留、身份/别名冲突、预算 |
| `BlockEntityExecutionEngine` / `...Batch` | ticket/permit 绑定、一次性正文启动、join、效果 |
| `ExecutionBlockEntityPreparation` | 资源作用域 `BlockEntityExecutionAdapter` 准备、修订号校验与同区域波次分组 |
| `AuditedBlockEntityPreparation` | 清单校验、足迹检查、波次拆分 |
| `BlockEntityAdapterRegistry` / `BlockEntityAdapterPhase` | C5 保守申请协议（串行 fence） |
| `RegionBlockEntityWorkGraph` / `RegionBlockEntityTickAttribution` | 观察图与计时 |
| `RegionBlockEntityTickFailure` | 失败载体，不重放 |

## 3. Canonical 流水线

| 阶段 | 线程 | 动作 |
|---|---|---|
| 1 | 服务器 | 构建 canonical 阶段；为已注册适配器执行 `prepare`/`prepareCanonical` |
| 2 | 服务器 | 发布批次；捕获 wrapper/delegate/source/function/chunk/frame |
| 3 | Worker | 连续前缀：逐次准入、新鲜足迹、READ/WRITE 冲突分波 |
| 4 | Worker | 在持有 region lease 下**一次**运行原 ticker 正文 |
| 5 | Worker | 物理 join；提交必要效果 |
| 6 | 服务器 | 任何未证明项作为**完整串行 fence**；之后重查后缀 |
| 7 | 服务器 | 操作完成阶段（见[跨区](cross-region-and-scheduled.zh.md)），然后串行 fallback |

阶段绝不在未知回调处切开：未知 → 整个原 tick 串行执行一次，cursor 再前进。

## 4. 准入层次

| 层次 | 类型 | 要求 |
|---|---|---|
| Interior | 熔炉、高炉、烟熏炉、漏斗、显式 pack 准入类型 | 类型/chunk/实体加载/绑定/lease/延迟更新检查 |
| 边界 + 边界传播 | 仅内置熔炉族 | Interior 全部检查 **加上** 边界传播开启且八个邻居已加载并有 live 映射 |
| 受审计适配器 | 已注册稳态类型 | 清单完整性、精确绑定、新鲜 READ/WRITE 足迹 |
| 串行 fence | 其他全部 | 完整原 tick 在服务器执行 |

| 边界安全事实 | 说明 |
|---|---|
| 熔炉正文不向邻居传输物品 | 只改自身状态、邻居/比较器通知 |
| 效果走延迟发布 + 边界 fixpoint | worker 返回后运行；绝不重跑正文修复过期读 |
| 邻居/映射缺失 | 开始前选择完整服务器正文 |
| 含任何未证明 ticker 的 chunk | 保持整 chunk 服务器 fallback |
| 正文开始后缺 chunk | 报告失败；绝不整 tick 重试 |
| Pack 准入 | 只授予 interior 合同；类似熔炉的名字不授予边界访问 |

## 5. BE 执行路径对比

| 路径 | 证明 | 执行单位 | 配置 | 状态 |
|---|---|---|---|---|
| Native（原版） | 精确函数/实例/分支，新鲜 | 连续前缀 | `...BlockEntityTicks` | C6 |
| 熔炉波次 | 仅新鲜已燃烧熔炉正文 | 区域内定向波次 | `...FurnaceWorkWaves` | 实验性，C6 有界队列 |
| 资源 | 对象身份 READ/WRITE claim、一次性 scope | 熔炉前缀 | `...BlockEntityResources` | 实验性 |
| 模组受审计 | 适配器清单 + 足迹 | 连续前缀 | `...BlockEntityTicks` + 适配器 | 实验性 |
| C5 通用 | 保守 descriptor + validator | **无**（串行 fence） | `...BlockEntityTicks` | 仅诊断 |
| 未知 | — | 完整原 tick | — | 始终可用 |

## 6. 预算与限制

| 预算 | 值 | 影响 |
|---|---|---|
| Canonical 阶段条目 | **2048** | 更大的阶段整体串行 |
| 熔炉 catalog | 4096 | 超过则拒绝波次准备 |
| C5 声明 | 每单元 512，每阶段 131072 | 累计，不按 fence 重置 |
| 足迹根 | 每 proof 64 | 更多 → 拒绝 |
| Packed lease | 每任务 16 区域 | 全有或全无 |
| 延迟更新/级联 | 配置有界 | 溢出延后并计数 |

> 调大 2048 不是解法：需要通用有界窗口协调器，目前不存在。

## 7. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `enableRegionThreadingBlockEntityTicks` | `false` | 请求实际 canonical 阶段 |
| `enableRegionThreadingFurnaceWorkWaves` | `false` | 需要 BE 切片与固定布局 |
| `enableRegionThreadingBlockEntityResources` | `false` | 为熔炉前缀增加 claim/lease/scope；也门控 C6 模组准入 |
| `enableRegionThreadingBorderUpdates` | `false` | 熔炉边界准入必需 |
| `enableRegionThreadingPackedRegionLeases` | `false` | 16 区域打包 |
| `regionThreadingMode` / `FixedRegionChunks` | `FIXED_3X3` / `3` | 固定布局 |

```toml
# config/forgia.toml —— 最小方块实体切片
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingBorderUpdates = true        # 熔炉族边界准入需要
# enableRegionThreadingFurnaceWorkWaves = true     # 实验性
# enableRegionThreadingBlockEntityResources = true # 实验性
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
```

## 8. 实测负载（历史，勿过度解读）

| 负载 | Inline | 区域化 | 比值 |
|---|---|---|---|
| 2048 漏斗 | 3.27 mspt | 3.74–3.97 | 0.82–0.87×（更慢） |
| 4096 熔炉 | 2.60 mspt | 2.70–3.34 | 0.78–0.96×（更慢） |

| 事实 | 后果 |
|---|---|
| worker 正文每 ticker 约 2.5× inline 成本 | 轻负载会输；worker 路径要付协调成本 |
| 持久线程池默认关闭 | 等待完整负载矩阵 |
| 边界熔炉准入未基准测试 | 不声明加速 |
| C6 验收 | 正确性通过；性能是持平，不是领先 |

## 9. 失败语义

| 事件 | 行为 |
|---|---|
| 未知类/behaviour/变换 | 完整串行 tick；后缀重查 |
| `inspect`/`check` 抛异常 | 失败，绝不是许可 |
| 已开始正文抛异常 | join/清理后报告；绝不重放 |
| 新鲜足迹不同 | 拒绝未开始前缀 |
| 开始前邻居缺失 | 选择串行正文 |
| 开始后缺 chunk | 报告失败，不重试 |
| 跨区域 capability / `BlockCapabilityCache` / attachment / `SavedData` 访问 | `RegionWorkerBoundaryException` 触发 fail-closed BE 回退到主线程（`CapabilityListenerHolder` 在锁外运行失效回调） |
| Worker 视觉/粒子广播 | 通过服务端发布器交接路由回主线程执行，并保留粒子发送结果 |
| 停机/卸载 | 原物理完成边界；释放 ticket/lease |
| 操作回调抛异常 | 完成阶段失败报告；其他已受理请求继续排空 |

## 10. Work graph（可选）

| 方面 | 说明 |
|---|---|
| 目的 | 观察访问模式并规划有向波次 |
| 数据 | `workgraph` 命令；`nativeCompleted/failed/skipped` 计数 |
| 权限 | 观察**不授予任何权限**，只是诊断 |
| 采用 | 需要已审查的所有权/顺序要求（见 Chorus 参考） |
| 状态 | 实验性；不是调度器 |

## 11. 诊断

| 信号 | 含义 |
|---|---|
| `nativeCompleted` | 在 worker 上返回的 BE ticker 正文数 |
| `refused` + `reasons` | 按有界 decision 统计的拒绝 |
| 边界计数 | `unloadedNeighborhood`、`untrackedNeighborhood`、`boundaryChunk` |
| `/forgia regionthreading workgraph [start <phases>\|stop]` | 有界访问图与波次计数 |
| 归因 | canonical 前缀、串行 fallback、操作完成分别计时 |

## 12. 限制

| 限制 | 说明 |
|---|---|
| 2048 条目阶段 | 大型整合包仍串行 |
| 结构/移动装置/活塞 | 不感知区域 |
| 漏斗与 pack 准入机器 | interior 规则（读写活邻居） |
| 混合 chunk | 整 chunk fallback |
| 模组回调 | 适配器责任；没有自动化任务安全证明 |
| 加速 | 未声明；请测量自己的负载 |

## 13. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时能期待收益 | 负载下存在大量独立、已被证明的 BE 正文，且阶段 ≤2048 |
| 何时应保持关闭 | 轻 BE 负载、漏斗密集产线、大阶段、未证明模组 |
| 为什么单一 cursor | 在允许连续安全前缀的同时保留原版顺序 |
| 为什么串行 fence | 未知回调可能触碰任何东西；整体运行一次才是安全动作 |
| 为什么不重放 | 库存/消耗效果不可逆 |
| 为什么是 2048 | 保守上限，使阶段协调成本有界；不是性能调参旋钮 |
