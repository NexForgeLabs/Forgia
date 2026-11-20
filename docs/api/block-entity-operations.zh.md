# BlockEntityOperations —— 完整双 owner 操作与有界搬运

[English](block-entity-operations.en.md) | 中文 · [API 索引](README.zh.md)
源码：[`BlockEntityOperations.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityOperations.java)
证据：[CROSS-REGION-OPERATIONS](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CROSS-REGION-OPERATIONS.md) · 示例：[`ContainerLinkExample.java`](https://github.com/NexForgeLabs/Forgia/blob/main/examples/parallel-api/ContainerLinkExample.java)

状态：**实验性**，能力 `BLOCK_ENTITY_OPERATIONS`。完成阶段是串行的：本服务让你*安全地完成*一次交互，
并不给两个区域并行 lease。

---

## 1. 何时使用

| 适用 | 原因 |
|---|---|
| worker 拥有的机器必须把物品送入/取出另一个区域的容器 | 搬运会被重新校验，并在双方都安全的位置执行 |
| 机器 tick 必须以一次完整协商修改外部方块实体 | `submit` 在所有 worker 返回后，用活参与者执行你的回调 |
| 需要明确结果而不是“也许成功了” | `Task` 暴露 state/reason/result/failure，至多一次 |
| 正在移植目前会抛跨区异常的漏斗/Create 式交互 | 在副作用之前提交，而不是事后捕获 |

| 不要用 | 原因 |
|---|---|
| 只需要在某个位置延迟执行任务 | 用 [`RegionScheduler`](schedulers-and-entity-interactions.zh.md) |
| 需要跨区域修改实体 | 用 `EntityInteractionScheduler` |
| 想让两台机器并行 tick | 分别审计 ticker；操作在完成阶段串行 |
| 需要跨维度搬运 | 不支持 |
| 想在 worker 上等待结果 | 禁止：绝不能让 worker 阻塞 |

## 2. 为什么 API 基于 Reference

活体 `BlockEntity` 不能跨 owner 传递。`Reference` 捕获*实例身份*以及维度/位置/类型，弱引用世界与实体，
不暴露 lease，也永不加载 chunk。完成阶段核心会重新校验双方身份、位置、level 和已加载 chunk。
因此同位置替换**不能继承**原权限，过期链接会以明确 reason 失败，而不是静默改指向。

## 3. 获取服务

```java
ParallelApi.Server api = ParallelApi.forServer(server).orElseThrow();
if (!api.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_OPERATIONS)) {
    return; // 保留你自己的串行路径
}
BlockEntityOperations ops = api.blockEntities();
```

| 事实 | 行为 |
|---|---|
| 引擎关闭 | 服务仍存在；静止线程提交立即执行 |
| 引擎开启 | native 提交在批次完成阶段执行 |
| 服务停止 | `reference`/`submit` 拒绝；引用不可复用 |
| 跨服务器 | 引用与任务只属于一个服务实例 |

## 4. 完整参数面

```java
Reference reference(BlockEntity blockEntity);
<R> Task<R> submit(Reference source, Reference target, Operation<R> operation);
Task<TransferResult> transfer(Reference source, Reference target, Transfer request);
Counters counters();
```

| 方法 | 参数 | 返回 | 有效上下文 | 限制 |
|---|---|---|---|---|
| `reference` | 已加载 chunk 中的 attached `BlockEntity` | `Reference` | 静止服务器线程，或*源*机器精确已准入的 native ticker 内 | 不加载 chunk；已移除/无 chunk/服务停止会抛异常 |
| `submit` | 两个引用 + `Operation<R>` | `Task<R>` | 同上 | 每个 BE 批次 ≤4096 请求；双方必须在同一 `ServerLevel` |
| `transfer` | 两个引用 + `Transfer` | `Task<TransferResult>` | 同上 | `maxItems` 1..64；槽位 ≥0；sided 容器必须给方向 |
| `counters` | — | `Counters` | 任意线程（只读 record） | 累计值 |

### 4.1 `Reference`

| 成员 | 类型 | 含义 |
|---|---|---|
| `dimension()` | `ResourceKey<Level>` | 参与者所在 level |
| `position()` | `BlockPos` | 不可变位置 |
| `type()` | `ResourceLocation` | 方块实体类型 id |

不透明：自己实现该接口不会生成有效引用。

### 4.2 `Operation<R>`

```java
@FunctionalInterface
interface Operation<R> {
    @Nullable R execute(BlockEntity source, BlockEntity target);
}
```

| 规则 | 后果 |
|---|---|
| 结果必须是脱离世界的不可变数据 | 保留活 BE 不延长修改权 |
| 允许 `null` 结果 | 用 `state()` 区分“完成”和“无结果” |
| 只操作传入的参与者 | 捕获其他活对象是未定义行为 |
| 不要重试部分生效的工作 | 效果不会回滚 |

### 4.3 `Task<R>`

| 成员 | 类型 | 含义 |
|---|---|---|
| `state()` | `State` | 生命周期状态 |
| `reason()` | `Reason` | 终态/拒绝原因 |
| `cancel()` | `boolean` | 仅当操作尚未开始且永不会开始时为 true |
| `result()` | `Optional<R>` | 仅 `COMPLETED` 且结果非 null 时存在 |
| `failure()` | `Optional<Throwable>` | `FAILED` 后存在 |
| `isDone()` | `boolean` | `state` 不是 `QUEUED`/`RUNNING` |

| `State` | 含义 |
|---|---|
| `QUEUED` | 已受理，未开始 |
| `RUNNING` | 回调执行中（此时取消失败） |
| `COMPLETED` | 回调返回；可能有 result |
| `SKIPPED` | 参与者不可用/被替换，回调未调用 |
| `CANCELLED` | 开始前取消 |
| `REJECTED` | 从未受理（上下文/所有权/队列） |
| `FAILED` | 回调抛异常或操作失败 |

| `Reason` | 含义 |
|---|---|
| `NONE` | 无终态拒绝 |
| `CANCELLED` | 调用方在开始前取消 |
| `INVALID_REFERENCE` | 引用不再解析到捕获的实例 |
| `INVALID_CONTEXT` | 调用线程/阶段无权提交 |
| `UNOWNED_SOURCE` | 源 ticker 不是绑定的源实例 |
| `BLOCK_ENTITY_REMOVED` | 参与者被移除 |
| `SOURCE_REPLACED` / `TARGET_REPLACED` | 该位置实例已更换 |
| `LEVEL_CHANGED` | 参与者换了 level |
| `CHUNK_UNAVAILABLE` | 参与者 chunk 未加载 |
| `LEVEL_CLOSED` | level 已关闭 |
| `SHUTDOWN` | 服务停止 |
| `BATCH_CLOSED` | 请求执行前批次已结束 |
| `QUEUE_FULL` | 达到每批/每实体请求上限 |

### 4.4 `Transfer` 与 `TransferResult`

```java
record Transfer(int sourceSlot, int targetSlot, int maxItems,
                @Nullable Direction extractionSide, @Nullable Direction insertionSide) {}
record TransferResult(int moved, TransferStatus status) {}
```

| 字段 | 约束 | 含义 |
|---|---|---|
| `sourceSlot` | ≥0 | 源容器槽位 |
| `targetSlot` | ≥0 | 目标容器槽位 |
| `maxItems` | 1..64 | 请求上限；允许部分搬运 |
| `extractionSide` | 可空 | `WorldlyContainer` 必须给；普通 `Container` 传 `null` |
| `insertionSide` | 可空 | 目标同理 |

| `TransferStatus` | 含义 |
|---|---|
| `MOVED` | 至少移动一个物品 |
| `NO_ITEMS` | 源槽为空 |
| `DESTINATION_FULL` | 目标无法接收 |
| `ITEM_REJECTED` | 目标 `canPlaceItem` 拒绝该堆叠 |
| `INVALID_SLOT` | 槽位越界 |
| `SIDE_REQUIRED` | sided 容器缺少适用方向 |
| `UNSUPPORTED_CONTAINER` | 参与者不是受支持 `Container` |
| `SAME_CONTAINER` | 源与目标为同一容器 |

零搬运的 `COMPLETED` 是**业务结果**，不是调度失败。

### 4.5 `Counters`

| 字段 | 含义 |
|---|---|
| `accepted` / `completed` / `skipped` / `cancelled` / `rejected` / `failed` | 累计请求结果 |
| `callbackNanos` | 累计回调墙钟时间 |
| `reasons` | `Map<Reason, Long>`，只含有界枚举键 |

绝不要把位置、类型或网络 id 放进诊断标签。

## 5. 提交上下文

| 调用方 | 结果 |
|---|---|
| 静止服务器线程（含引擎关闭的串行模式） | 立即执行 |
| 源 BE 的精确已准入 native ticker | 进入本 BE 批次，所有 worker 返回后执行 |
| 主线程正在服务活跃 worker | `REJECTED / INVALID_CONTEXT` |
| 其他机器 ticker，或无对应绑定的 worker | `REJECTED / UNOWNED_SOURCE` 或 `INVALID_CONTEXT` |
| 网络/异步/其他线程 | `REJECTED / INVALID_CONTEXT` |

## 6. 单个 BE 批次内的执行顺序

| 顺序 | 步骤 |
|---|---|
| 1 | worker 完成各自 tick 正文 |
| 2 | 边界传播 |
| 3 | 发布延迟 block/light 效果 |
| 4 | 延迟实体移除 |
| 5 | 若有完整操作：先发布 worker 排队的 scheduled mutations |
| 6 | **完整操作串行执行** |
| 7 | 原有串行 fallback 正文 |
| 8 | 剩余 scheduled mutations |
| 9 | 批次关闭 |

因为第 6 步只会在所有 worker 返回后执行，操作看到稳定世界，但**不会**获得跨区并行 lease。
独立机器仍在各自 region 内并行 tick。

## 7. 示例

### 7.1 从已准入 ticker 发起有界搬运

```java
// 静止阶段：在目标 chunk 已加载时捕获外部目标
BlockEntityOperations.Reference targetRef = ops.reference(targetMachine);

// 源机器已准入的 ticker 内，抽取任何物品之前
var task = ops.transfer(ops.reference(sourceMachine), targetRef,
        new BlockEntityOperations.Transfer(0, 0, 8, Direction.DOWN, Direction.UP));

// 后续安全 tick；绝不在 worker 上等待
if (task.isDone()) {
    if (task.state() == BlockEntityOperations.State.COMPLETED) {
        task.result().ifPresent(r -> LOG.info("moved={} status={}", r.moved(), r.status()));
    } else {
        LOG.warn("link failed: {} / {}", task.state(), task.reason());
    }
}
```

### 7.2 自定义完整操作 + 脱离世界的结果

```java
record ExchangeResult(int moved, boolean targetEmptied) {}

var task = ops.submit(sourceRef, targetRef, (liveSource, liveTarget) -> {
    // 完整协商：判断、修改双方、触发它们各自的同步回调
    int moved = exchange(liveSource, liveTarget);
    return new ExchangeResult(moved, isEmpty(liveTarget));
});
```

### 7.3 开始前取消

```java
var task = ops.transfer(sourceRef, targetRef, request);
if (!task.cancel()) {
    // 已 RUNNING 或已结束：取消不是回滚
}
```

## 8. 对比

| 属性 | `BlockEntityOperations` | `EntityInteractionScheduler` | `RegionScheduler` |
|---|---|---|---|
| 范围 | 完整双 BE 交互 / 容器搬运 | 完整实体-owner 交互 | 普通 region 任务 |
| 源权限 | 精确绑定源 ticker | 实体 tick group | 任意调用方 |
| 结果句柄 | `Task<R>`，含 result/failure/reason | `EntityInteractionTask`（无 result） | 无（`execute`）/只能取消 |
| 强取消保证 | 是，开始前 | 是，开始前 | 无抢占 |
| 执行时机 | BE 批次完成阶段、worker 之后 | 安全停点 / 静止服务器 | 目标 region 队列 drain |
| 跨区并行 | 否（完成阶段串行） | 否（owner 执行） | 不适用 |
| 跨维度 | 拒绝 | 拒绝（`LEVEL_CHANGED`） | 不适用 |
| 至多一次 | 是 | 是 | 不保证（无重放承诺） |

## 9. 限制与失败语义

| 限制 | 说明 |
|---|---|
| 至多一次调用 | 受理 ≠ 完成；没有回滚 |
| 仅同一 `ServerLevel` | 不接受跨维度 |
| `maxItems` ≤64 | 更大搬运需拆成多次 |
| 每 BE 批次 4096 请求 | 取消不退还该批次的受理名额 |
| 不加载 chunk | 引用指向未加载 chunk 会 `CHUNK_UNAVAILABLE` |
| 容器合同仍适用 | 抛异常/不一致的自定义 `Container` 可能留下部分效果；只报告、不重试 |
| 不是管网 | 不自动合并双箱，也不适配 `IItemHandler` |
| 不是事务 | 不恢复任意 Java 调用栈 |

| 失败 | 表现 | 绝不 |
|---|---|---|
| 引用指向被替换实例 | `SOURCE_REPLACED`/`TARGET_REPLACED` | 自动改指向 |
| 从服务线程提交 | `INVALID_CONTEXT` | 照样入队 |
| 回调立即抛异常 | 向调用者抛出 | 吞掉 |
| 完成阶段回调抛异常 | 服务器失败路径报告 | 重放 |
| worker 阻塞等待任务 | 死锁/截断 | 被支持 |

## 10. 诊断

```java
ops.counters(); // accepted/completed/skipped/cancelled/rejected/failed + callbackNanos + reasons
```

`/neoforge regionthreading compat` 打印同样的累计计数。用两次快照算速率；不要用 accepted 除以机器数声称并行度。

## 11. 检查清单

- [ ] 已检查能力；缺失时保留串行路径。
- [ ] 在静止阶段捕获目标引用，且其 chunk 已加载。
- [ ] 提交发生在操作的**任何副作用之前**。
- [ ] 结果数据脱离世界且不可变。
- [ ] 在后续安全 tick 轮询；worker 不等待。
- [ ] 处理终态与 reason；部分生效后绝不重试。
- [ ] sided 容器给出明确方向并检查槽位。
