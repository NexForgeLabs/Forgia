# ParallelApi —— 服务器服务、数据键与准备结果

[English](parallel-api.en.md) | 中文 · [API 索引](README.zh.md) · 源码：[`ParallelApi.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/ParallelApi.java)

状态：**实验性（major 1）。** `ParallelApi` 是唯一始终存在的模组级服务。它从不授予世界所有权，只让你
*协商*、*注册*、*读取有界诊断*。

---

## 1. 何时使用

| 场景 | 使用 | 原因 |
|---|---|---|
| 触碰某切片前确认协议是否存在 | `forServer(server).capabilities()` | 能力按服务器实例隔离，并在注册事件中可见 |
| 刷怪正文需要规则数据，但不能在 worker 上计算 | `register(...)` + `prepared(key)` | 准备是纯的且在主线程，正文只读快照 |
| 希望在原版 cap 构造前就决定整计划 | 让 `preparePlan` 返回整计划 `Ready` | 每计划一个冻结快照，而不是每 chunk 一个 |
| 某配置必须留在服务器线程 | `prepare` 返回 `ServerRequired(reason)` | 显式、可诊断的回退，而非静默串行 |
| 需要适配器计数 | `adapters()`、`blockEntityTickAdapters()`、`auditedBlockEntityTicks()` | 只暴露有界值，不暴露活对象 |
| 跨区域搬运物品 | `blockEntities()` | 专门的至多一次服务（[操作](block-entity-operations.zh.md)） |

**不要**用 `ParallelApi` 获取：线程、lease、chunk 加载能力、事件总线或“可安全修改”标志。这里都没有。

## 2. 为什么这样设计

* **每个 `MinecraftServer` 一个服务。** 运行时按服务器实例记账；静态单例会在集成服世界和测试服之间泄漏。
* **注册是事务且冻结的。** 顺序、预算、重复检查必须在任何 level tick 之前确定，没有“稍后注册”的路径。
* **准备是纯函数。** worker 不能读 live 世界对象、消耗 RNG 或发事件，唯一安全输入是静止线程产出的脱离快照。
* **回退是一种值，不是错误。** `ServerRequired` 是正常结果，适配器可以诚实地表达部分支持而不抛异常。
* **诊断从不发放权限。** 计数与枚举 reason 可以安全打印，实体引用不行。

## 3. 静态入口

```java
public static final int MAJOR_VERSION = 1;

public static Optional<ParallelApi.Server> forServer(MinecraftServer server);
public static <S> Optional<S> prepared(ParallelApi.DataKey<S> key);
public static Optional<ParallelApi.SpawnUnit> currentSpawnUnit();
```

| 方法 | 参数 | 返回 | 有效时机 | 说明 / 失败 |
|---|---|---|---|---|
| `MAJOR_VERSION` | — | `1` | 始终 | 调用任何注册方法前先比较 |
| `forServer` | `MinecraftServer`（非 null） | `Optional<Server>` | 注册冻结后到关闭前 | 冻结前/关闭后为空；`null` 抛 NPE |
| `prepared` | `DataKey<S>`（非 null） | `Optional<S>` | 对应刷怪正文及其同步回调内 | 原生/关闭路径、其他线程、正文之后为空；存在**不**代表筛选已执行 |
| `currentSpawnUnit` | — | `Optional<SpawnUnit>` | 与 `prepared` 相同 | 只有源 chunk 元数据，不是候选位置边界 |

`prepared` 不会被你自己启动的线程继承，也不是事件取消标志。

## 4. `Server` 全部方法

```java
int apiMajorVersion();
Set<Capability> capabilities();
boolean regionThreadingInstalled();
Phase currentPhase();
List<AdapterStatus> adapters();
List<BlockEntityTickAdapter.Status> blockEntityTickAdapters();
List<AuditedBlockEntityTickAdapter.Status> auditedBlockEntityTicks();
List<BlockEntityAdapters.Status> blockEntityAdapters();
Optional<BlockEntityAdapters.PhaseSummary> lastBlockEntityAdapterPhase();
BlockEntityOperations blockEntities();
List<ScheduledTickStatus> scheduledTicks();
```

| 方法 | 含义 | 单位 / 默认 | 常见误用 |
|---|---|---|---|
| `apiMajorVersion()` | 门面主版本 | `1` | 当成构建号 |
| `capabilities()` | 本服务器实现的协议 | `Set` | 当成“已启用” |
| `regionThreadingInstalled()` | 当前是否安装引擎 | boolean | 认为 `false` 会禁用 `ParallelApi` |
| `currentPhase()` | 调用线程的执行阶段 | enum | 把 `QUIESCENT_SERVER` 当修改许可 |
| `adapters()` | 刷怪准备注册者及累计结果 | 计数 | 把计数当生成实体数 |
| `blockEntityTickAdapters()` | v1 谓词适配器：检查、拒绝、原生完成、耗时 | 计数/纳秒 | 把 `checks` 当完成数 |
| `auditedBlockEntityTicks()` | 受审计适配器：出处/revision、`verified`、reason | 记录 | 把 `verified` 当运行期证明 |
| `blockEntityAdapters()` | C5 保守申请诊断 | 记录 | 以为 C5 会启用 worker |
| `lastBlockEntityAdapterPhase()` | 最近关闭的 C5 会话，仅声明波次 | `Optional` | 当成执行计划 |
| `blockEntities()` | 双 owner 操作服务 | 服务 | 以为跨区并行 |
| `scheduledTicks()` | block/fluid scheduled 阶段快照 | 列表 | 用空列表推断切片状态 |

所有列表/可选方法都有保守默认值（`List.of()` / `Optional.empty()`），旧核心不会抛 `AbstractMethodError`。

## 5. record 与枚举

### 5.1 `DataKey<S>`

```java
record DataKey<S>(ResourceLocation id, Class<S> snapshotType) {}
```

| 组件 | 约束 | 原因 |
|---|---|---|
| `id` | 非 null、带命名空间、每服务器唯一 | 适配器数据槽的身份 |
| `snapshotType` | 非 null 引用类型（不能是 primitive） | 发布时做运行时类型检查 |

### 5.2 `SpawnUnit` / `SpawnPlan`

```java
record SpawnUnit(ResourceKey<Level> dimension, int chunkX, int chunkZ, long gameTime) {}
record SpawnPlan(ResourceKey<Level> dimension, long gameTime) {}
```

| 字段 | 含义 | 不可当作 |
|---|---|---|
| `dimension` | 刷怪调用所属 level | chunk 加载句柄 |
| `chunkX`/`chunkZ` | 完整刷怪调用的**源** chunk | 候选位置边界 |
| `gameTime` | 计划/单元的逻辑游戏时间 | 墙钟截止时间 |

### 5.3 `Preparation<S>` 层次

```java
sealed interface Preparation<S> permits Ready, ServerRequired, PerUnit {}
record PerUnit<S>() implements Preparation<S> {}
record Ready<S>(S snapshot, boolean orderedFinalization) implements Preparation<S> { Ready(S snapshot); }
record ServerRequired<S>(ResourceLocation reason) implements Preparation<S> {}
```

| 结果 | 可由谁返回 | 效果 | 之后是否允许 |
|---|---|---|---|
| `PerUnit` | 仅 `preparePlan` | 先收集 chunk，再逐单元调用 `prepare` | 默认计划行为 |
| `Ready(snapshot)` | `prepare` 或 `preparePlan` | 冻结该范围数据 | 整计划数据也供串行 fallback 正文读取 |
| `Ready(snapshot, true)` | 仅 `preparePlan` | 请求有序最终化 | 需要 `ORDERED_SPAWN_FINALIZATION` |
| `ServerRequired(reason)` | `prepare` 或 `preparePlan` | 保留完整串行计划/单元 | reason 必须是有界命名空间标签 |

校验规则（抛异常或注册失败）：`snapshot != null`、`reason != null`、`orderedFinalization` 只能来自
`preparePlan`，逐单元 `prepare` 不得请求有序最终化。

### 5.4 `AdapterStatus`

```java
record AdapterStatus(ResourceLocation id, long preparedUnits, long serialUnits, long serialPlans,
                     long preparationNanos, Optional<ResourceLocation> lastSerialReason) {}
```

| 字段 | 含义 | 解读 |
|---|---|---|
| `id` | 适配器数据键 id | 与你自己的日志关联 |
| `preparedUnits` | 为 worker 准备的单元数 | 尝试次数，不是实体数 |
| `serialUnits` | 保留串行的单元数 | 回退量 |
| `serialPlans` | 在 cap 构造前被拒的整计划数 | 最强的回退信号 |
| `preparationNanos` | 累计准备墙钟时间 | 只用窗口差值 |
| `lastSerialReason` | 最近一次命名空间 reason | 无需按坐标生成标签即可诊断 |

用两次快照的差值算速率，不要跨窗口平均累计总量。

## 6. `Registrar` 的注册方法

```java
Set<Capability> capabilities();
<S> void register(int apiMajorVersion, DataKey<S> key, NaturalSpawnPreparation<S> preparation);
void claimNaturalSpawning(DataKey<?> key, String modId, BooleanSupplier verified);
void registerBlockEntityTicks(int apiMajorVersion, ResourceLocation id,
        Set<ResourceLocation> types, BlockEntityTickAdapter adapter);
void registerBlockEntityExecutionAdapter(int protocolVersion, ResourceLocation id,
        Set<ResourceLocation> types, BlockEntityExecutionAdapter adapter);
BlockEntityAdapters.Registration registerBlockEntityAdapter(int protocolVersion,
        BlockEntityAdapters.Descriptor descriptor, BlockEntityAdapter adapter);
```

| 方法 | 必需参数 | 事务规则 | 授予什么 |
|---|---|---|---|
| `register` | major `1`、唯一 key、准备回调 | 总数 ≤128；重复 id 使全部失败 | 一个数据槽 + 准备回调 |
| `claimNaturalSpawning` | 已注册 key、非空 `modId`、启动时 `verified` 为 true | 不可重复 claim；key 不存在即失败 | **仅**替代该模组的刷怪限制 |
| `registerBlockEntityTicks` | major `1`、唯一 id、1..128 个 type、adapter | 共用 128 预算/id 空间；type 重叠被拒 | 逐次准入检查（[tick](block-entity-ticks.zh.md)） |
| `registerBlockEntityExecutionAdapter` | protocol `1`、唯一 id/type、完整 audited execution adapter | 同一预算/id/type 冲突规则 | 区域或资源 Scope 原 tick 执行（[协议](block-entity-execution.zh.md)） |
| `registerBlockEntityAdapter` | protocol `1`、descriptor、adapter | 同一预算；C5 id/type 空间 | C5 保守申请诊断、串行 fence |

注册只在 `RegisterParallelAdaptersEvent` 内、服务器线程上有效。即使捕获嵌套失败，事务仍然无效——不能“吞掉”坏注册。

## 7. 示例

### 7.1 能力协商 + 读取快照

```java
static final ParallelApi.DataKey<Rules> RULES =
        new ParallelApi.DataKey<>(ResourceLocation.parse("example:rules"), Rules.class);

@SubscribeEvent
public static void register(RegisterParallelAdaptersEvent event) {
    var registrar = event.registrar();
    if (!registrar.capabilities().contains(ParallelApi.Capability.NATURAL_SPAWN_PREPARATION)) return;
    registrar.register(ParallelApi.MAJOR_VERSION, RULES, unit -> new ParallelApi.Ready<>(Rules.frozen()));
}

// 在对应刷怪正文期间运行的监听器内
ParallelApi.prepared(RULES).ifPresent(rules -> {
    // 只读；不要因为拿到快照就取消其他监听器
});
```

### 7.2 整计划快照 + 有序最终化 + 诚实的回退

```java
@Override
public Preparation<Rules> preparePlan(SpawnPlan plan) {
    if (!worldSupportsPlanRules(plan.dimension())) {
        return new ServerRequired<>(ResourceLocation.parse("example:world_dependent_rules"));
    }
    return new Ready<>(Rules.frozenFor(plan), true); // 请求有序最终化
}

@Override
public Preparation<Rules> prepare(SpawnUnit unit) {
    // 仅当 preparePlan 返回 PerUnit 时逐单元调用；保持纯净
    return Rules.covers(unit) ? new Ready<>(Rules.frozenFor(unit))
                              : new ServerRequired<>(ResourceLocation.parse("example:structure_unavailable"));
}
```

### 7.3 不保留活对象的诊断

```java
ParallelApi.forServer(server).ifPresent(api -> {
    long nativeDone = api.blockEntityTickAdapters().stream()
            .mapToLong(BlockEntityTickAdapter.Status::workerCompletions).sum();
    api.adapters().forEach(status -> LOG.info("{} prepared={} serialPlans={} lastReason={}",
            status.id(), status.preparedUnits(), status.serialPlans(), status.lastSerialReason()));
});
```

## 8. 对比：逐单元 / 整计划 / 有序最终化

| 属性 | 默认 `PerUnit` | 整计划 `Ready` | 整计划 `Ready(_, true)` |
|---|---|---|---|
| 准备粒度 | 每个源 chunk | 每个计划一次 | 每个计划一次 |
| 内存 | 每单元一个快照 | 一个共享快照 | 一个共享快照 |
| 串行 fallback 数据 | 逐单元丢弃 | 计划内保留 | 计划内保留 |
| worker 候选选择 | 是 | 是 | 是 |
| 原生最终化位置 | worker | worker | 服务器，其他 peer 停驻 |
| 顺序保证 | 相对原版无 | 相对原版无 | 单元批次内保留最终化顺序；RNG 不逐位一致 |
| 需要能力 | `NATURAL_SPAWN_PREPARATION` | 同上 | 另需 `ORDERED_SPAWN_FINALIZATION` |
| 适用 | 逐 chunk 规则 | 整包统一规则 | finalize 回调会修改共享状态 |

## 9. 失败模式

| 失败 | 表现 | 绝不会发生 |
|---|---|---|
| record 组件为 null | 构造时 NPE/`IllegalArgumentException` | 静默接受 |
| 主版本不匹配 | 注册失败 | 部分发布 |
| 重复 id 或 type | 注册整体失败 | 合并两个适配器 |
| 注册数 >128 | 注册失败 | 驱逐旧适配器 |
| `prepare` 抛异常 | 通过刷怪计划传播为失败 | 当成功或重试 |
| 之后修改快照 | 由你造成的未定义行为 | 运行期检测修改 |
| 在正文外读 `prepared` | `Optional.empty()` | 缓存或泄漏快照 |

## 10. 检查清单

- [ ] 事件内检查能力，保留串行路径。
- [ ] `DataKey` id 带命名空间且唯一；快照类型是引用类型。
- [ ] 准备中没有 RNG、事件、实体构造或世界写入。
- [ ] 快照深层不可变且脱离世界。
- [ ] 每个不支持的配置都返回 `ServerRequired`，reason 有界。
- [ ] 只在 `preparePlan` 请求 `orderedFinalization`。
- [ ] 诊断通过 `Server` 读取，绝不保留实体/chunk。
