# 调度器、实体交互与 scheduled tick 诊断

[English](schedulers-and-entity-interactions.en.md) | 中文 · [API 索引](README.zh.md)
源码：[`RegionThreadingManager.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/RegionThreadingManager.java)、[`RegionScheduler.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/RegionScheduler.java)、[`EntityInteractionScheduler.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/EntityInteractionScheduler.java)、[`ScheduledTickStatus.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/ScheduledTickStatus.java)
证据：[CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md)、[CHUNK-OWNER-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CHUNK-OWNER-OPERATIONS.md)

状态：**实验性。** 调度器需要完整 Forgia 编译依赖且引擎已安装；`EntityInteractionScheduler` 单独标记实验性。

---

## 1. 何时用哪个

| 需求 | 入口 | 原因 |
|---|---|---|
| 在位置所属 region 上运行任务 | `regionScheduler()` | 按位置归属；不跟随实体 |
| 在实体当前所在处运行任务 | `entityScheduler()` | 任务与延迟随实体迁移 |
| 修改 global region 状态 | `globalScheduler()` | 不是“任意写 chunk”的许可 |
| 为位置/实体决定“现在还是稍后” | `executeOrSchedule(...)` | 已拥有则同步，否则入队 |
| 查询/断言所有权 | `isOwnedByCurrentRegion`、`assertOwnedByCurrentRegion` | 便宜的保护检查，不是加载/修改权 |
| 完成实体到 owner 的完整交互 | `entityInteractions()` | 带明确结果的完整交互 |
| 需要 FULL chunk 的 owner 操作 | `submitToOwnerAfterChunkLoad` | ticket 支持的同步加载，只回调一次 |
| scheduled 计数/延迟 | `api.scheduledTicks()` | block/fluid 阶段的有界诊断 |

不要把调度器当异步 executor、chunk 加载器、跨区域事务或阻塞点。这四者都不存在。

## 2. 为什么四条路径分开

| 路径 | 归属 | 是否迁移 | 混用的典型错误 |
|---|---|---|---|
| `RegionScheduler` | 位置所属 region | 否 | 实体走开后任务仍在旧位置执行 |
| `EntityRegionScheduler` | 实体当前 region | 是，含延迟 | 任务丢失或在旧 owner 上执行 |
| `GlobalRegionScheduler` | 全局 region | 否 | 误以为“主线程可以写一切” |
| `EntityInteractionScheduler` | 完成时的双方 | 会校验 | 先产生部分效果、第二次修改失败 |

## 3. `RegionThreadingManager`

```java
static Optional<RegionThreading> get();
static boolean isInstalled();
static void ifInstalled(Consumer<RegionThreading> action);
static RegionThreading getOrThrow();
```

| 方法 | 行为 | 失败 |
|---|---|---|
| `get()` | 已安装则返回引擎句柄 | bridge 关闭时为空 |
| `isInstalled()` | 便宜的布尔查询 | — |
| `ifInstalled(action)` | 在**当前线程立即**执行 action | 不会调度任何东西 |
| `getOrThrow()` | 返回引擎句柄 | 缺失时抛 `IllegalStateException` |

`currentOrNull`、`install`、`clear` 是内部生命周期方法。模组不得安装/卸载引擎。

## 4. `RegionThreading` 与上下文

```java
GlobalRegionScheduler globalScheduler();
RegionScheduler regionScheduler();
EntityRegionScheduler entityScheduler();
EntityInteractionScheduler entityInteractions();

boolean isOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
boolean isOwnedByCurrentRegion(Entity entity);
void assertOwnedByCurrentRegion(ServerLevel level, BlockPos pos);
void assertOwnedByCurrentRegion(Entity entity);
void executeOrSchedule(ServerLevel level, BlockPos pos, Runnable task);
void executeOrSchedule(Entity entity, Runnable task);
RegionThreadingContext currentContext();
```

| 成员 | 语义 | 不是 |
|---|---|---|
| `isOwnedByCurrentRegion` | 只回答当前 owner | chunk 加载或世界静止证明 |
| `assertOwnedByCurrentRegion` | 按配置的违规模式抛异常 | WARN 模式不是许可 |
| `executeOrSchedule` | 已拥有则同步，否则入队 | 保证“下一 tick”；可能重入 |
| `currentContext().kind()` | `NONE`、`GLOBAL_REGION`、`LEVEL`、`REGION`、`ASYNC` | 不是 lease；保存不续期 |
| `currentContext().isTickThread()` | global/level/region 为 true | 不是 BE 操作提交许可 |

## 5. 三个调度器

| 调度器 | 方法 | 队列/时机 | 句柄 |
|---|---|---|---|
| `RegionScheduler` | `execute(level,pos,task)`、`runDelayed(...delayTicks)`、`runAtFixedRate(...initialDelay,period)` | 位置所属 region | 延迟/周期返回 `ScheduledRegionTask` |
| `EntityRegionScheduler` | `execute(entity,task)`、`runDelayed(entity,task,delay)`、`runAtFixedRate(entity,task,initial,period)` | 实体所属 region；迁移并复查存活 | `ScheduledRegionTask` |
| `GlobalRegionScheduler` | `execute(task)`、`runDelayed(task,delay)`、`runAtFixedRate(task,initial,period)` | 全局 region | `ScheduledRegionTask` |

### 5.1 `ScheduledRegionTask`

```java
boolean cancel();
boolean isCancelled();
boolean isDone();
```

| 约束 | 值 |
|---|---|
| 延迟 / 初始延迟 | `>= 0`，否则 `IllegalArgumentException` |
| 周期 | `> 0`，否则 `IllegalArgumentException` |
| 时间单位 | 所属队列的逻辑 tick，不是毫秒 |
| 错过的周期 | 不补跑；下次 = 实际执行 tick + period |
| 运行中取消 | 返回 false；不会抢占正在执行的 Runnable |
| 引擎关闭 | 新提交可能抛 `IllegalStateException` |
| 异常 | 队列记录；不重试，句柄不提供异常读取 |

## 6. `EntityInteractionScheduler`

```java
EntityInteractionTask submit(Entity source, Entity target, BiConsumer<Entity, Entity> action);
EntityInteractionTask submitToOwner(Entity source, Entity owner, Consumer<Entity> action);
default EntityInteractionTask submitToOwnerAfterChunkLoad(
        Entity source, Entity owner, BlockPos position, BiConsumer<Entity, LevelChunk> action);
```

| 方法 | source 角色 | 校验 | 用途 |
|---|---|---|---|
| `submit` | 参与者 | 双方都复查（移除、原世界身份） | 双实体交互 |
| `submitToOwner` | 仅授权准入，之后可被消耗 | owner 必须仍在 source 原 level 中 | 提交后消耗自身的弹射物命中 |
| `submitToOwnerAfterChunkLoad` | 仅授权准入 | 临时 ticket、同步加载 FULL chunk、复查 owner/生命周期、只回调一次、`finally` 释放 | 需要 chunk 的 owner 操作 |

| 顺序/限制 | 说明 |
|---|---|
| native 提交来源 | 必须是拥有 `source` 的实体 tick group |
| 静止服务器线程 | 立即执行 |
| 主线程服务活跃 worker | 拒绝（`INVALID_CONTEXT`） |
| 外部/其他线程 | 拒绝 |
| 队列上限 | 每实体批次 4096 请求（与交互共享） |
| 取消 | 只在 `RUNNING` 前有效；加载中途无法取消 |
| 跨维度 | 拒绝（`LEVEL_CHANGED`） |

### 6.1 `EntityInteractionTask`

| 成员 | 含义 |
|---|---|
| `state()` | `QUEUED, RUNNING, COMPLETED, SKIPPED, CANCELLED, REJECTED, FAILED` |
| `reason()` | `NONE, CANCELLED, ENTITY_REMOVED, LEVEL_CHANGED, LEVEL_CLOSED, SHUTDOWN, BATCH_CLOSED, QUEUE_FULL, INVALID_CONTEXT, UNOWNED_SOURCE` |
| `cancel()` | 仅当未开始且永不会运行时为 true |
| `failure()` | 回调异常，同时走正常失败报告 |
| `isDone()` | 不是 `QUEUED`/`RUNNING` |

注意这是与 `BlockEntityOperations.Task<R>` **不同的类型**：没有 result 载荷。

## 7. `ScheduledTickStatus` 诊断

```java
record ScheduledTickStatus(String phase, long phases, long collected, long admitted,
        long workerCompletions, long serialCompletions, long failedCallbacks, long skippedCallbacks,
        long serialPhases, Map<SerialReason, Long> serialReasons, Timings timings, Latency latency) {}
record Timings(long preparationNanos, long workerNanos, long dispatchAwaitNanos,
        long completionNanos, long phaseNanos) {}
record Latency(int samples, long p50Nanos, long p95Nanos, long p99Nanos) {}
```

| 字段 | 单位/含义 | 不要误读为 |
|---|---|---|
| `phase` | `block` 或 `fluid` | 方块类型 |
| `phases` | 进入 dispatcher 预检的次数 | 非空阶段数 |
| `collected` | 调度器选出的回调数 | 已执行回调 |
| `admitted` | 准入 worker 协议的回调数 | 完成数 |
| `workerCompletions` | worker 上正常返回的正文数 | executor 拒绝后的 inline 调用 |
| `serialCompletions` | 串行正常返回，含拒绝后 inline | worker 工作 |
| `failedCallbacks` / `skippedCallbacks` | 已开始失败 / 未调用项 | 重试 |
| `serialPhases` / `serialReasons` | 整阶段被拒及有界原因 | 回调计数 |
| `workerNanos` | 各 worker 回调墙钟时间之和 | 主线程耗时 |
| `dispatchAwaitNanos` / `completionNanos` | 派发/等待/传播与完成工作 | tick 时间 |
| `samples`、`p50/p95/p99` | 最近 ≤128 个非空阶段的精确分位数（纳秒） | 整服延迟 |

`SerialReason` 取值：`UNCLASSIFIED_CALLBACK, FLUID_CALLBACK, NON_FIXED_LAYOUT, PROFILER_ACTIVE,
UNLOADED_FOOTPRINT, FOREIGN_FOOTPRINT, UNSAFE_NEIGHBOR, DEFERRED_UPDATE_BUDGET`。

没有公开 API 可注册任意 scheduled 回调、放行整个方块类或强制打开测试开关。生产准入只覆盖原版灯、
region 内含半径 2 惰性足迹的阶段。

## 8. 示例

### 8.1 位置任务 + 取消

```java
RegionThreadingManager.get().ifPresent(threading -> {
    ScheduledRegionTask task = threading.regionScheduler().runDelayed(
            level, position.immutable(), () -> updateOwnedPosition(level, position), 20L);
    // 保留句柄；绝不阻塞等待
});
```

### 8.2 跟随实体的任务

```java
threading.entityScheduler().runAtFixedRate(mob, () -> refreshOwnedGoal(mob), 0L, 10L);
```

### 8.3 不保留弹射物的命中

```java
EntityInteractionTask task = threading.entityInteractions().submitToOwner(
        projectile, owner, liveOwner -> applyCompleteImpact(liveOwner, immutableImpact));
if (task.state() == EntityInteractionTask.State.REJECTED) {
    handleRejectionBeforeConsumingProjectile(task.reason());
}
```

### 8.4 需要完整 chunk 的 owner 操作

```java
threading.entityInteractions().submitToOwnerAfterChunkLoad(
        source, owner, pos.immutable(),
        (liveOwner, chunk) -> applyChunkBoundEffect(liveOwner, chunk, immutableInput));
```

## 9. 对比

| 属性 | RegionScheduler | EntityRegionScheduler | GlobalRegionScheduler | EntityInteractionScheduler |
|---|---|---|---|---|
| 归属来源 | 位置 | 实体 | 全局 region | 提交时 source，执行时 owner |
| 随实体迁移 | 否 | 是 | 否 | 执行时校验 |
| 句柄 | `ScheduledRegionTask` | `ScheduledRegionTask` | `ScheduledRegionTask` | `EntityInteractionTask` |
| 结果载荷 | 无 | 无 | 无 | 无 |
| 强“从未执行”取消 | 否 | 否 | 否 | 是，开始前 |
| 加载 chunk | 否 | 否 | 否 | 仅 `AfterChunkLoad` 变体，同步 |
| 拒绝外部线程 | 不适用 | 不适用 | 不适用 | 是 |

## 10. 失败模式

| 症状 | 原因 | 修复 |
|---|---|---|
| 实体移动后任务仍在旧位置 | 对实体工作用了 `RegionScheduler` | 改用 `entityScheduler()` |
| `getOrThrow` 抛 `IllegalStateException` | 引擎未安装 | 用 `get()`/`ifInstalled` 并保留串行路径 |
| 任务从未执行且 `isCancelled()` 为 false | 队列上限、level 卸载或 owner 退休 | 检查生命周期；不要假设已执行 |
| 延迟后 `cancel()` 返回 false | 已在运行 | 视为已提交 |
| `REJECTED / INVALID_CONTEXT` | 从网络/异步/服务线程调用 | 在静止点或拥有该 tick 时提交 |
| `REJECTED / UNOWNED_SOURCE` | 源 tick group 不拥有该实体 | 从它自己的 tick 提交 |
| chunk 加载变体返回 `SKIPPED` | 加载期间 owner 被移除/level 变化 | 不要重试整个 tick |

## 11. 检查清单

- [ ] 用 `get()`/`isInstalled` 检查引擎存在；保留串行路径。
- [ ] 按归属模型选对调度器（位置/实体/全局）。
- [ ] 延迟与周期以 tick 为单位且合法（`>=0`、`>0`）。
- [ ] 保留句柄、绝不阻塞；处理终态。
- [ ] 交互在副作用**之前**提交；不保留活弹射物。
- [ ] 不因“提交返回了”就假设任务已执行。
- [ ] 诊断通过 `api.scheduledTicks()` 快照读取，不用累计总量。
