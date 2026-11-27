# Forgia API —— 入口、生命周期与版本

[English](README.en.md) | 中文 · 上级：[docs](../README.zh.md) · 完整清单：[API-REFERENCE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/API-REFERENCE.md)

状态：**实验性，API major = 1。** 公开类型不是冻结 ABI，必须按 capability 协商。

本页回答每个接入方最先遇到的问题：*用哪个入口、什么时候有效、它到底授予什么、为什么答案总是“不是所有权”？*
详细参数表在各合同页面中，见下方矩阵。

---

## 1. 何时用哪个入口

| 需求 | 获取路径 | 主要类型 | 无区域引擎时 |
|---|---|---|---|
| 版本、能力、阶段、适配器诊断 | `ParallelApi.forServer(server)` | `ParallelApi.Server` | 注册完成后服务仍存在 |
| 准备刷怪数据 / 接管某项刷怪限制 | `RegisterParallelAdaptersEvent.registrar()` | `ParallelApi.Registrar` | 事件仍触发；原生串行路径不执行你的准备 |
| 在刷怪正文内读取快照 | `ParallelApi.prepared(key)` | `DataKey<S>`、`SpawnUnit` | 通常为空 |
| 让受审计的原 BE ticker 准入 | 同一 registrar | `BlockEntityTickAdapter`、`AuditedBlockEntityTickAdapter` | 原生串行；注册永不启用切片 |
| 注册资源作用域 BE 执行适配器 | 同一 registrar | `BlockEntityExecutionAdapter` | 原生串行；见 [BE 执行契约](block-entity-execution.zh.md) |
| 声明区域安全服务端 PLAY 载荷处理器 | `PayloadRegistrar.playToServerRegion(...)` | `IRegionPayloadHandler`、`IRegionPayloadContext` | 玩家区域 tick 关闭或被拒时回退到 MAIN 执行 |
| 完整双 owner BE 操作 / 有界容器搬运 | `api.blockEntities()` | `BlockEntityOperations` | 静止服务器线程立即执行，其他上下文拒绝 |
| scheduled tick 计数与延迟 | `api.scheduledTicks()` | `ScheduledTickStatus` | 空列表 |
| 在位置所属 region 上运行任务 | `RegionThreadingManager.get()` → `regionScheduler()` | `RegionScheduler` | Manager 为空；不提供替代 executor |
| 任务跟随实体 owner | 同上 → `entityScheduler()` | `EntityRegionScheduler` | 同上 |
| 全局 region 任务 | 同上 → `globalScheduler()` | `GlobalRegionScheduler` | 同上 |
| 查询/断言所有权 | 同上 | `RegionThreading`、`RegionThreadingContext` | 模组自行走串行分支 |
| 整体实体交互 / 加载后 owner 操作 | 同上 → `entityInteractions()` | `EntityInteractionScheduler` | 无引擎入口（`ParallelApi` 则始终注册） |
| 声明模组兼容等级 | mod metadata `regionThreading` | `RegionThreadingSupport` | 仅声明 |
| 沙盒 / 亲和路由 / 区块钉选钩子（兼容扩展） | `RegionAdapterApi`（`registerChunkAffinityResolver`、`registerServerThreadChunkPredicate`、`admitRegionSandboxedNamespace`、`shareSavedData`） | `RegionAdapterApi` | 供随附兼容适配器使用 |

**为什么要这样拆分。** `ParallelApi` 是始终存在的服务器级服务；调度器属于已安装的引擎。混在一起会让
“引擎关了”和“API 不存在”无法区分，模组就会协商错对象。

## 2. 编译面

| 产物 | 内容 | 使用场景 |
|---|---|---|
| `:neoforge:parallelApiJar`（`*-parallel-api.jar`） | `net.neoforged.neoforge.server.threading.api` + `RegisterParallelAdaptersEvent` | 只调用 `ParallelApi`/registrar/operations |
| 完整 Forgia 编译依赖 | 门面**加上** `RegionThreadingManager`、三个调度器、`EntityInteractionScheduler`、配置 | 需要调度任务或读取 `ForgiaConfig` |

```groovy
dependencies {
    // 仅示意本地文件；目前没有冻结的 Maven 坐标
    compileOnly files('libs/forgia-parallel-api.jar')
}
```

| 规则 | 原因 |
|---|---|
| `compileOnly`，绝不 shade/重定位/jar-in-jar | 两份 API 类会破坏 `instanceof`、能力协商和 record 相等性 |
| 可选入口必须加原版守卫 | 从始终加载的类里直接引用 `ParallelApi`，会在能力检查之前就类加载失败 |
| 不依赖 `@ApiStatus.Internal` 类型 | 它们服务于补丁和引擎，不是模组合同 |

## 3. 生命周期与有效期

```
服务器构造
        │
        ├── RegisterParallelAdaptersEvent   ← 注册只在此事件内、服务器线程上有效
        │        registrar().capabilities() ← 协议协商，此时尚无半成品服务
        │        registrar().register...()  ← 事务：全有或全无，然后冻结
        │
        ├── ParallelApi.forServer(server)   ← 从此刻到关闭前存在
        │        api.blockEntities()        ← 服务按服务器实例隔离
        │
        └── 关闭                            ← forServer 为空；保留的服务抛异常/拒绝
```

| 窗口 | `forServer` | 注册 | `blockEntities()` | `RegionThreadingManager.get()` |
|---|---|---|---|---|
| 注册冻结前 | 空 | 有效 | 不适用 | 引擎可能正在安装 |
| 运行中，引擎开启 | 存在 | 已冻结，抛异常/拒绝 | 有效 | 存在 |
| 运行中，引擎关闭 | 存在 | 已冻结 | 有效（静止线程上立即执行） | 空 |
| 关闭后 | 空 | 无效 | 拒绝（`SHUTDOWN`） | 空 |

| 规则 | 违反后果 |
|---|---|
| 保留的 `Server` 关闭后不可复用 | 服务查询抛 `IllegalStateException` |
| `Reference`/`Task` 属于某一服务实例 | 重启后拒绝；绝不跨服务器复用 |
| 注册回调按 id 排序 | 准备顺序确定；不要依赖事件订阅顺序 |
| 注册失败不发布任何内容 | 捕获的嵌套失败不能留下部分服务 |
| 配置重载不能迁移已构建引擎 | 改引擎/布局开关必须停机并启动新 JVM |

## 4. 能力与阶段语义

`capabilities()` 描述**本服务器实现哪些协议**；`Phase` 描述**当前线程的执行上下文**。二者都不是权限令牌。

| Capability | 它宣告的协议 | 它**不**代表 |
|---|---|---|
| `EXECUTION_CONTEXT` | `currentPhase()` | 不是所有权通行证 |
| `NATURAL_SPAWN_PREPARATION` | 逐源 chunk 的数据准备 | 不允许跳过其他监听器 |
| `NATURAL_SPAWN_PLAN_PREPARATION` | cap/state 构造前的整计划准备 | 不是结构/chunk 加载服务 |
| `NATURAL_SPAWN_CLAIMS` | 适配器接管特定模组的刷怪限制 | 不能覆盖引擎启用开关 |
| `ORDERED_SPAWN_FINALIZATION` | worker 选择 + 其他 worker 停驻后的主线程最终化 | 不承诺与原版 RNG/顺序逐位一致 |
| `BLOCK_ENTITY_TICK_ADMISSION` | 原 ticker 的逐次准入 | 不是类型白名单，也不是切片开关 |
| `BLOCK_ENTITY_OPERATIONS` | 双 owner 操作与容器搬运 | 不是并行跨区 lease |
| `AUDITED_BLOCK_ENTITY_TICKS` | 带 READ/WRITE 足迹的完整原 ticker 分支审计 | 哈希 ≠ 语义证明 |
| `BLOCK_ENTITY_EXECUTION_ADAPTERS` | [完整原 ticker 的区域/资源 Scope 执行](block-entity-execution.zh.md) | 需要完整 adapter 审计，不能自动认证未知代码 |
| `SCHEDULED_TICK_DIAGNOSTICS` | scheduled 阶段计数/延迟 | 列表为空不能证明切片关闭 |

| Phase | 含义 | 可否修改世界 |
|---|---|---|
| `QUIESCENT_SERVER` | 服务器线程处于可证明静止窗口 | 仅按具体操作合同 |
| `SERVER_SERVICE` | 主线程为仍运行的 worker 服务 | 无一揽子许可 |
| `REGION_WORKER` | 位于某个 region 批次 worker 内 | 仅限本次授权范围 |
| `EXTERNAL` | 其他情况 | 否 |

## 5. 为什么 API 是这个形状

| 设计选择 | 原因 |
|---|---|
| 能力协商而非版本嗅探 | 切片和适配器在不同构建落地，构建号无法表达“这个协议存在” |
| 注册只在一个事件内、随后冻结 | 任何人 tick 之前，准备顺序和预算必须确定 |
| 纯准备 + 不可变快照 | worker 正文不得消费 RNG、发事件或读取 live 世界状态 |
| 显式 `ServerRequired(reason)` | 安全回退是一等结果，而不是异常或静默串行 |
| 独立的受审计 tick 合同 | 旧谓词不能证明正文；受审计适配器声明实现、source/function 类和精确可变足迹 |
| 至多一次、显式状态的操作 | 重放半途生效的机器操作会破坏状态；`Task` 只报告不重试 |
| 诊断只暴露服务与值 | 计数器与有界枚举 reason 可安全暴露，活对象不行 |

## 6. 失败与拒绝速查

| 症状 | 常见原因 | 正确处理 |
|---|---|---|
| 注册期间 `forServer` 为空 | 注册尚未冻结 | 在事件内用 `registrar().capabilities()` |
| 服务方法抛 `UnsupportedOperationException` | 能力缺失（旧核心或引擎关闭） | 检查能力，保留自己的串行路径 |
| 每个刷怪单元都 `ServerRequired` | 你的准备不支持该配置 | 保持它；不要靠放宽快照“修好” |
| `REJECTED / INVALID_CONTEXT` | 从网络/异步/服务线程提交 | 在静止服务器线程或已准入 ticker 内提交 |
| `REJECTED / UNOWNED_SOURCE` | ticker 不是绑定的源实例 | 不能借用别的机器的权限 |
| 适配器已注册但 worker 完成为 0 | 切片关闭、阶段超预算或 interior 规则 | 先看 `/neoforge regionthreading compat` 再改代码 |
| 大 canonical 阶段 | 以至多 2048 个候选的窗口规划 | 整阶段资源/更新预算继续保留；预算不足后缀串行 |

## 7. 交付前检查清单

1. 已检查 `MAJOR_VERSION` 与 capability，并保留串行路径。
2. 注册在事件内，id 带命名空间，总数 ≤128，无重复 id/type。
3. 准备是纯的；快照深层不可变且脱离活对象。
4. 每次拒绝都返回有界枚举/reason，绝不为每个坐标生成字符串。
5. 没有 worker 等待、没有 `join`/`get`、不阻塞其他区域。
6. 失败只报告不重放；部分生效的操作绝不重试。
7. 诊断通过服务读取，不保留活对象。
8. 先证明能力**关闭**时的行为，再证明开启时。
