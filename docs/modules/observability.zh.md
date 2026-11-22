# 模块：观测、命令与配置

[English](observability.en.md) | 中文 · [模块地图](README.zh.md)
源码：[`RuntimeObservability`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RuntimeObservability.java)、[`RegionThreadingCommand`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/command/RegionThreadingCommand.java)、[`ForgiaConfig`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/common/ForgiaConfig.java)
证据：[RUNTIME-OBSERVABILITY](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/RUNTIME-OBSERVABILITY.md)、[PRODUCTION-CONFIGURATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PRODUCTION-CONFIGURATION.md)

状态：**已实现。** 监控不新增调度器、线程池、ticket、权限或磁盘写入；它读取既有引擎与原生服务器 tick 环。

---

## 1. 职责

| 拥有 | 不拥有 |
|---|---|
| `/forgia` 与 `/neoforge regionthreading` 运维视图 | 新增测量权限 |
| 原生服务器 MSPT 环与有界历史 | 持久化时间序列 |
| 区域计时/负载/覆盖快照 | 完整的每区域归因 |
| API 诊断记录（`Server` 方法） | 活对象或修改权 |
| 配置 schema、默认值、迁移 | 重新配置已构建的引擎 |

## 2. 命令

| 命令 | 输出 |
|---|---|
| `/forgia` | 紧凑用法 |
| `/forgia status` | 线程/准入/兼容状态 |
| `/forgia regions [page]` | 每页十个区域，稳定 id 顺序 |
| `/forgia region <dimension> <x> <z>` | 单个区域的计时/负载/生命周期/覆盖；x/z 是**区域**坐标 |
| `/forgia region top <metric> [count]` | 降序排名，稳定 tie-break；默认 10，最大 100 |
| `/forgia workers` | configured/alive/busy/idle、queued/submitted/completed/rejected/failed、CPU/wall、shutdown/join；前 20 行 |
| `/forgia scheduler` | 池/区域化器、熔炉/资源计数、P3 融合切片（`Fused slices`）与 BE 适配器状态 |
| `/forgia bottleneck` | 关键路径剖析：最慢区域占比、`estMainOrSerial`、各切片 `critAvg/barrierAvg` 与主线程分段耗时（`beSerial`、`entityHandoff`、`entityFallbacks`、`borderFixpoint`） |
| `/forgia fences` | 同步栅栏（sync fence）热点排名（按触发次数与耗时定位序列化瓶颈） |
| `/forgia overload` | 区域过载隔离状态、预算与当前被降频（`THROTTLED`）区域列表 |
| `/forgia itemclear [status\|now [minAgeSeconds]]` | 查看多区域掉落物清理状态，或立即手动触发一次掉落物清扫 |
| `/forgia performance` | 原生 10 秒 MSPT + 最新 JVM 采样 |
| `/forgia performance history [seconds]` | 1/10/60/300 秒窗口；可请求 1..300 |
| `/forgia performance json` | 版本化紧凑 JSON，不可用数值为 null |
| `/forgia diagnostics` | 性能/worker/调度器/阈值摘要 |
| `/neoforge regionthreading status [raw]` | 切片/worker/fallback/health/区域统计，raw 诊断 |
| `/neoforge regionthreading status players` | 玩家各阶段计数、拒绝与清理诊断。[玩家 Tick](player-ticks.zh.md) |
| `/neoforge regionthreading compat` | 兼容声明、适配器注册、操作计数、scheduled 原因/延迟 |
| `/neoforge regionthreading workgraph [start <phases>\|stop]` | 有界访问图、熔炉波次计数、资源指标 |
| `/neoforge regionthreading regions [count]` | 按慢速排序的区域 |

| 属性 | 说明 |
|---|---|
| 权限 | 等级 2，在根部应用并被所有子命令继承（控制台、游戏内、JSON） |
| 补全 | 维度 id、metric、常见 history 时长 |
| 格式 | 纯文本，统一 ms/秒/百分比/MiB 单位；无颜色也可读 |
| `top` 指标 | `mspt, avg, p50, p95, p99, entities, blockentities, chunks, pending, worker, serial` |

```text
/forgia status
/forgia region top mspt 10
/forgia performance history 60
/forgia performance json
/neoforge regionthreading compat
```

所有路由需要权限等级 2，并从根命令继承。

## 3. 指标定义

| 指标 | 定义 | 不要与什么比较 |
|---|---|---|
| 原生服务器 MSPT | `ServerTickEvent.Post` 时已完成的 `MinecraftServer.tickTimesNanos[tickCount % 100]` | 事件处理器前后的时长 |
| TPS | `(samples-1) / 首末完成时间差` | `1000 / 平均 MSPT` |
| 历史 | 每服务器两个预分配 12,000 项 long 数组，O(1) 无分配，服务器线程拥有 | 持久化序列 |
| 窗口分位数 | 对保留原始 tick 的精确 nearest-rank P50/P95/P99 | 每 tick 分位数的平均 |
| 区域 MSPT | 最近 100 个服务器 tick 的插桩租出组时长之和 | 服务器 MSPT；区域会重叠 |
| 区域 main/worker 时间 | 按实际池作用域的累计墙钟时长 | CPU 时间 |
| 超时计数 | 超过 50 ms 的已关闭测量槽 | 失败 tick |
| Pending | 区域队列**条目**（取消项在 drain 前仍计数） | 已执行工作项 |
| 玩家数 | 每 tick 玩家快照 | 已连接会话数 |
| BE 数 / 活跃 chunk / 待处理 block tick | **N/A** —— 未插桩 | 零 |

| 历史事实 | 说明 |
|---|---|
| 保留量 | 12,000 tick；20 TPS 下 ≥5 墙钟分钟 |
| `capacityLimited` | 加速服务器提前耗尽环时为 true |
| 隔离 | 按 owner 线程约束，弱引用服务器；stop 事件清除 |
| 起点 | 采集启动时；不持久化 |

## 4. API 诊断

| 入口 | 返回 | 是否有界 |
|---|---|---|
| `Server.adapters()` | 刷怪准备状态 | 是（id + 计数） |
| `Server.blockEntityTickAdapters()` | v1 检查/拒绝/完成/计时 | 是 |
| `Server.auditedBlockEntityTicks()` | 清单完整性/reason | 是 |
| `Server.blockEntityAdapters()` | C5 状态/拒绝 | 是 |
| `Server.lastBlockEntityAdapterPhase()` | 最近关闭的会话（仅声明波次） | 是 |
| `Server.scheduledTicks()` | 阶段计数/计时/分位数 | 是 |
| `blockEntities().counters()` | 操作结果 + 回调时间 | 是 |

绝不要用位置、类型或网络 id 构造诊断；`Reason`/`Decision`/`SerialReason` 是固定枚举，正是为了标签有界。

## 5. 配置与生命周期

| 事实 | 说明 |
|---|---|
| 文件 | `config/forgia.toml`，SERVER 配置；世界覆盖 `serverconfig/forgia.toml` 整体替换。全部 36 项键与布局前置条件见[配置与硬件调优指南](../configuration.zh.md) |
| 迁移 | 加载前：`forgia.toml` 优先，否则 `neoforge-server.toml`，再 `neoforge-server.forgia.toml` |
| 删除 `forgia.toml` | 可能重新激活旧的启用设置；回滚要写显式 `false` |
| 仅重启 | 引擎/布局/存储/切片/服务开关（全部默认 `false`，包括 `enableRegionThreadingPlayerTicks`、`enableRegionThreadingPersistentWorkers`、`enableRegionThreadingPackedRegionLeases` 与 `DYNAMIC_CLUSTER` 布局）。注意：`DYNAMIC_CLUSTER` 下 `enableRegionThreadingPlayerTicks` 需配合 `-Dforgia.dynamicPlayerSoak=true`，而 `FurnaceWorkWaves`、`BlockEntityResources` 与 `PackedRegionLeases` 仅在 `FIXED_3X3` 下生效 |
| 实时读取 | 告警/drain/所有权设置（canary 期间请固定） |
| 非法值 | spec 修正越界值；直接布局构造会拒绝 |

## 6. 明确为 N/A 的内容

| 缺失 | 原因 |
|---|---|
| 每区域 BE/串行 fallback 时间 | canonical 前缀/正文/提交未完全插桩 |
| 每区域方块实体数、活跃 tick chunk、待处理 block/fluid tick | 需要扫描 chunk 内容 |
| 每区域准入/拒绝/提交/协调/竞争 | 未插桩 |
| 每区域 CPU 归因 | 线程作用域与区域不是一一对应 |
| 完整区域 tick 成本归因 | 租出组计时只覆盖部分工作 |

N/A 就报告为 N/A，而不是伪造的零（例如 `blockentities` 排名会解释缺失测量，而不是排虚构的零）。

## 7. 读报表时避免自欺

| 错误 | 纠正 |
|---|---|
| 把区域时长相加推断服务器 MSPT | 区域会重叠；用原生服务器 MSPT |
| 用累计计数当速率 | 取两次快照相减 |
| 把 `admitted` 当完成 | 只有 `workerCompletions`（及串行完成）才是返回 |
| 把 worker 比例当加速 | 测量整 tick MSPT/CPU/分配 |
| 跨不同 worker 数/布局比较 | 每次运行固定配置 |
| 把 `implementationsVerified` 当安全 | 它只是清单完整性 |
| 把历史当持久序列 | 禁用/停机时清空 |

## 8. 排查流程

| 症状 | 先看 | 再看 |
|---|---|---|
| MSPT 高、worker 空闲 | bridge/workers 开关、兼容拒绝 | `compat` 中的 `DISABLE_*` 与切片标志 |
| worker 忙、MSPT 仍高 | 工作是否真的在 region 切片内 | `scheduler`、`region top mspt` |
| BE 切片“开着”但无完成 | 阶段 >2048、interior 规则、未知类 | `compat` 适配器行、拒绝原因 |
| 刷怪串行 | `serialPlans`、冲突动作、配置门 | 适配器准备矩阵 |
| Scheduled 阶段串行 | `serialReasons` | 检查 profiler、足迹、流体阶段 |
| 内存/GC 压力 | 历史 + JVM 采样 | 比较相同负载，不跨配置 |

## 9. 限制

| 限制 | 说明 |
|---|---|
| 区域覆盖不完整 | BE/串行 fallback 计时不完整 |
| 无每区域 CPU | 只有墙钟时长 |
| 无持久指标 | 采集按服务器实例驻留内存 |
| 监控开销 | 未检测到高于 JVM/负载波动；不声明负成本 |
| 不是 profiler | JFR/profiler 运行单独进行且不计入基准 |

## 10. 何时 / 如何 / 为什么

| 问题 | 答案 |
|---|---|
| 何时打开这些命令 | 开启开关前、每次变更后、canary 期间 |
| 如何比较运行 | 同配置、同世界、同负载、反向顺序、快照差值 |
| 为什么这么多 N/A | 诚实的空白胜过看似合理的零；覆盖需要插桩工作 |
| 为什么原生采集 | 正确的 MSPT 必须与 Minecraft 自己的记账边界一致 |
| 为什么标签有界 | 无界 reason 会泄漏位置并爆内存 |
| `/forgia async` | 查看 Phase 2 独立 Region 异步时钟状态（`trackedRegions`、`inFlight`、`submitted/completed/skippedBusy/failed` 及最慢 Region 的独立 TPS 与 EWMA） |
