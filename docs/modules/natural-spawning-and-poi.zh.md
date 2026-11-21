# 模块：自然刷怪与兴趣点（POI）

[English](natural-spawning-and-poi.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`RegionSpawnPlan`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionSpawnPlan.java)、[`RegionSpawnAdmissionBarrier`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionSpawnAdmissionBarrier.java)、[`LevelSpawnCapCounter`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/LevelSpawnCapCounter.java)、[`RegionPoiStorage`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPoiStorage.java)、[`RegionPoiStorages`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionPoiStorages.java)
证据：[NEARBY-SPAWN-ADMISSION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/NEARBY-SPAWN-ADMISSION.md)、[STATUS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/STATUS.md)、[MOBSPAWNCONTROLLER-LOCAL-RESULTS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOBSPAWNCONTROLLER-LOCAL-RESULTS.md)

状态：POI 在 C6 内**已认证**；自然刷怪**实验性且 C6 之外**
（`enableRegionThreadingSpawning` 默认关闭；`enableRegionThreadingPoi` 默认关闭但会被实体 tick 隐含）。

---

## 1. 职责

| 子系统 | 拥有 | 不拥有 |
|---|---|---|
| 刷怪 | 针对原版 level 级 cap 的每区域刷怪正文、刷怪计划、准入屏障、本地 cap 记账 | 结构过滤、自定义刷怪器、全局刷怪决策 |
| POI | 区域分区存储、已发布快照、live ticket 检查 | live 存储转换、结构快照服务 |
| 适配器 | 冻结规则、有序最终化、显式 claim | 证明任意第三方监听器安全 |

## 2. 类地图

| 类 | 职责 |
|---|---|
| `RegionSpawnPlan` / `RegionSpawnState` | 整计划决策、每区域状态 |
| `RegionSpawnSource` / `RegionSpawnChunk` / `RegionSpawnNeighborhood` | 源 chunk、候选 chunk 数据、邻域 |
| `RegionSpawnBatch` / `RegionSpawnAttribution` | 派发/join 与计时 |
| `RegionSpawnAdmissionBarrier` | 有序最终化的协作停点 |
| `LevelSpawnCapCounter` | 针对 level 级 cap 的原子 check-and-reserve |
| `RegionLocalMobCapCalculator` | 区域本地计数，精确划分 level 总数 |
| `NearbySpawnReservations` | 已准备候选的附近数量预留 |
| `RegionPoiStorage` / `RegionPoiStorages` / `RegionPoiSections` / `RegionPoiRegion` | 分区 POI 记录与每区域视图 |
| `RegionPoiCounters` | 查询/ticket 计数 |

## 3. 刷怪流程

| 阶段 | 线程 | 动作 |
|---|---|---|
| 1 | 服务器 | 原版 cap/state 构造前执行适配器 `preparePlan` |
| 2 | 服务器 | 构建计划；`PerUnit` 才会逐 chunk `prepare` |
| 3 | 服务器 | 校验门：分区实体存储（`DYNAMIC_CLUSTER` 或 `FIXED_3X3`）、chunk/player/spawn 门、已加载邻域、预算 |
| 4 | Worker | 运行刷怪正文；区域状态划分 level 计数 |
| 5 | Worker | 原子 check-and-reserve 预留 cap；附近数量限制 |
| 6 | 服务器 | 有序最终化时：停驻 peer、发布延迟效果、执行原生 finalize/admission/afterSpawn |
| 7 | 服务器 | 被拒单元串行 fallback；已开始失败不重放 |

## 4. 刷怪准入门

| 门 | 说明 |
|---|---|
| 分区实体存储 | 需要 region-owned 实体存储（`DYNAMIC_CLUSTER` 或 `FIXED_3X3`） |
| Chunk/玩家/刷怪门 | 既有 level 门 + 玩家快照 |
| 邻域已加载 | 候选位置可离开源 chunk；邻域必须已加载 |
| 预算 | 延迟更新/刷怪预算 |
| 兼容 | 冲突模组（`DISABLE_SPAWNING`）或适配器可能让整份计划保持串行 |
| 适配器准备 | 所有已注册 provider 必须返回 `Ready` 才能 worker 准入 |

## 5. 刷怪语义

| 属性 | 保证 |
|---|---|
| Level 级 mob cap | 原子 check-and-reserve，绝不出现两个赢家 |
| 区域计数 | 区域状态精确划分 level 计数 |
| 候选位置 | 不受源 chunk 限制；查询使用真实服务器实体视图 |
| 次级生成（如 JOCKEY） | 同一规则 revision；未加入世界的父实体不会被误算为名额赢家 |
| 取消生成 | 保留原版行为与 RNG 消耗 |
| 已开始失败正文 | 报告，绝不重放 |
| 顺序 | 有序模式保证不双拿名额，不保证原版全局候选/RNG 逐位一致 |

## 6. POI 语义

| 属性 | 保证 |
|---|---|
| 查询结果 | 与原版 `PoiManager` 一致（对真实单线程管理器验证） |
| 快照年龄 | 回答可能落后一次发布；live ticket 检查仍生效 |
| 存储转换 | 无受支持的 live 转换；`DYNAMIC_CLUSTER` 与 `FIXED_3X3` 在世界启动时安装分区存储 |
| 保存/重载 | 记录、顺序、空闲 ticket 数等价（已测试） |
| 实体 tick 隐含 | 开启实体 tick 同时安装 POI 存储 |

## 7. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `enableRegionThreadingSpawning` | `false` | 需要 `DYNAMIC_CLUSTER` 或 `FIXED_3X3`、region-owned 存储、门、邻域、预算 |
| `enableRegionThreadingPoi` | `false` | 即使为 false 也会被实体 tick 隐含；需要 `DYNAMIC_CLUSTER` 或 `FIXED_3X3` |
| `enableRegionThreadingEntityTicks` | `false` | 安装区域 POI 存储 |
| `regionThreadingMode` | `FIXED_3X3` | `DYNAMIC_CLUSTER`（推荐）或 `FIXED_3X3`（C6 基线） |
| `enableRegionThreadingWorkers` | `false` | 刷怪正文在 region worker 上运行 |

```toml
# config/forgia.toml —— 开启 POI，刷怪按需开启
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingPoi = true
# enableRegionThreadingSpawning = true   # 实验性，C6 之外
regionThreadingMode = "DYNAMIC_CLUSTER"  # 推荐（FIXED_3X3 为 C6 基线）
regionThreadingDynamicSectionShift = 3
```

## 8. 实测负载（历史）

| 负载 | 关闭 | 区域化 | 比值 |
|---|---|---|---|
| 289 chunk 自然刷怪（切片） | 4.13–4.62 ms | 2.79–3.93 ms | 1.15–1.65× |
| 1024 村民 POI 查询 | 54.5 / 52.0 mspt | 25.7 / 22.0 | 2.1–2.4× |
| 1024 僵尸 POI 查询 | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |

刷怪测量在 C6 已验收配置之外。

## 9. 失败语义

| 事件 | 行为 |
|---|---|
| 准备不支持的配置 | 完整串行计划/单元 |
| worker 刷怪正文抛异常 | 报告；不重放 |
| 屏障/peer 失败 | 其他参与者停止，报告失败，不重放 |
| 正文中途目标卸载 | 报告失败；不整 tick 重试 |
| POI 快照缺失 | 按合同回退（live ticket 检查），绝不撕裂读 |
| 停机 | 计划/单元失效；重启后新服务 |

## 10. 对比

| 属性 | 原版 | 区域化刷怪 | 区域化 POI |
|---|---|---|---|
| 执行 | 服务器线程 | Region worker（+ 服务器最终化） | 区域分区存储 |
| Cap | level 级循环 | 原子 check-and-reserve | 不适用 |
| 顺序 | 全局候选顺序 | 不双拿；非逐位一致 | 不适用 |
| 查询延迟 | 单线程 | 不适用 | 密集 POI 上 2.1–3.4× |
| 认证 | — | C6 之外 | C6 |
| 结构 | 原版 | 串行 | 不适用 |

## 11. 限制

| 限制 | 说明 |
|---|---|
| 结构/光照/高度/季节过滤 | 串行 |
| `spawn_multiplier != 1` | 串行 |
| 自定义刷怪器 / 全局决策 | 串行 |
| 已开始失败正文 | 无法安全重放 |
| 无结构快照服务 | 准备阶段不能加载结构 |
| 多人长跑 | 未建立 |

## 12. 诊断

| 信号 | 含义 |
|---|---|
| `AdapterStatus.preparedUnits/serialUnits/serialPlans` | 准备量 vs 回退量 |
| `Ordered spawn finalization: transactions/serverMs` | 续跑次数与服务器正文时间 |
| POI 计数 | 查询/ticket 次数 |
| `/forgia region ...` | 区域负载/覆盖（BE/串行 fallback 覆盖不完整） |

## 13. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时开启 POI | 密集村民/POI 负载；它会被实体 tick 隐含 |
| 何时开启刷怪 | 只在有已审计适配器并演练 canary 后；否则预期串行回退 |
| 为什么按区域划分计数 | 原版 cap 是 level 级的；区域状态不能重复计数 |
| 为什么原子预留 | 两个区域绝不能同时拿走最后一个允许的生物 |
| 为什么需要 POI 快照 | worker 不能遍历 live `PoiManager` 存储 |
| 为什么没有 live 存储转换 | 运行中重分区 live 存储无法证明安全 |
