# 刷怪准备、claim 与有序最终化

[English](natural-spawn-preparation.en.md) | 中文 · [API 索引](README.zh.md)
源码：[`ParallelApi.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/api/ParallelApi.java)、[`ParallelAdapterRuntime.java`](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/ParallelAdapterRuntime.java)
证据：[MOD-PARALLEL-API](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MOD-PARALLEL-API.md)、[NEARBY-SPAWN-ADMISSION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/NEARBY-SPAWN-ADMISSION.md)

状态：**实验性。** 自然刷怪有自己的配置开关（`enableRegionThreadingSpawning`），**默认关闭且不在已验收 C6 配置内**。
注册永不自动启用它。

---

## 1. 何时使用 / 何时不要用

| 适用 | 原因 |
|---|---|
| 模组的刷怪监听器只需要不可变规则数据（难度、生物群系标签、整合包配置） | 正文读快照，而不是在 worker 上查询活对象 |
| 希望整份刷怪计划使用同一个冻结规则 revision | `preparePlan` 返回整计划 `Ready` |
| 最终化会修改共享状态，或必须看到真实加入/取消结果 | 请求 `orderedFinalization`，由服务器执行原生续跑 |
| 只涉及原版全局 mob cap | claim 只能接管**该模组**的刷怪限制 |

| 不要用 | 原因 |
|---|---|
| 准备阶段需要结构查询或 chunk 加载 | 没有结构快照/缓存服务，加载被禁止 |
| 想让 `spawn_multiplier` 或结构过滤并行 | 该协议未实现（见 MSC 适配器） |
| 想要“第三方监听器安全”的认证 | 注册是作者合同，不是自动证明 |
| 引擎/切片关闭 | 注册仍发生，但没有 worker 路径可喂 |

## 2. 为什么拆成 plan + unit

原版在遍历 chunk 之前就决定 cap 与 state。如果只有逐 chunk 准备，同一计划里两个 chunk 可能看到不同规则 revision，
cap 决策会不一致。拆分后：

* `preparePlan` —— cap/state 构造前的一次决策：`PerUnit`、整计划 `Ready` 或 `ServerRequired`。
* `prepare` —— 逐源 chunk，只在 `PerUnit` 模式下到达，用于确实因 chunk 而异的数据。

整计划 `Ready` 也会供给**同一计划**的串行 fallback 正文，因此被拒的 chunk 仍使用与 worker 相同的规则。

## 3. 注册规则

```java
public final class RegisterParallelAdaptersEvent extends ServerLifecycleEvent {
    public ParallelApi.Registrar registrar();
    public MinecraftServer getServer();
}
```

在 `NeoForge.EVENT_BUS` 上、biome modifiers 与 `ServerAboutToStartEvent` 之后、世界 tick 之前触发；无区域引擎的服务器同样触发。

| 规则 | 值 / 后果 |
|---|---|
| 有效范围 | 事件内、服务器线程、冻结前；保留 registrar 之后再调用会失败 |
| 注册总数 | ≤ 128，与方块实体适配器共用，id 唯一 |
| 顺序 | 按 `id` 排序执行，不按订阅顺序 |
| 版本 | `apiMajorVersion` 必须等于 `ParallelApi.MAJOR_VERSION` |
| 原子性 | 重复 id/type、版本不符或超额使整次注册失败，不发布任何内容 |
| 嵌套失败 | 即便捕获异常，也不会放行已失败的注册 |
| 对引擎的影响 | 无。注册不打开切片、不解除兼容拒绝 |

## 4. 你实际要设的参数

| 参数 | 类型 | 约束 | 后果 |
|---|---|---|---|
| `apiMajorVersion` | `int` | 必须为 `1` | 不匹配则注册失败 |
| `key` | `DataKey<S>` | 非空 id + 引用类型 `snapshotType` | 数据槽身份与运行时类型检查 |
| `preparation` | `NaturalSpawnPreparation<S>` | `prepare` 抽象，`preparePlan` 默认 `PerUnit` | 两个决策点 |
| `snapshot` | `S` | 深层不可变、脱离世界 | 正文可读的内容 |
| `orderedFinalization` | `boolean` | 只能由 `preparePlan` 返回 | 把原生最终化移到服务器 |
| `reason` | `ResourceLocation` | 带命名空间、有界 | 回退诊断；绝不为每个坐标生成 |
| `modId`（claim） | `String` | 非空，需匹配已注册 key 的模组限制 | claim 的作用域 |
| `verified`（claim） | `BooleanSupplier` | 启动时必须返回 true | 否则注册失败 |

## 5. 准备的纯净规则

| 允许 | 禁止 | 原因 |
|---|---|---|
| 读自己的不可变配置/编解码数据 | 消费 `Level.random` 或任何游戏 RNG | worker 正文必须与顺序无关 |
| 预计算查找表 | 发事件、构造实体 | 准备在活跃计划内运行 |
| 复制 primitive/不可变集合 | 保留 `Entity`、`LevelChunk`、可变 NBT、`IItemHandler` | 运行时类型检查无法证明不可变 |
| 发布只含不可变值的 `record` | 在单元之间共享可变集合或 RNG | 后续修改对核心不可见 |
| 有界工作 | 阻塞、sleep、安排异步工作 | 准备不会被抢占，也没有硬预算 |

异常、`null` 返回和错误快照类型会传播为失败，绝不转成串行成功或自动重试。

## 6. 有序最终化到底发生了什么

| 步骤 | 线程 | 执行内容 |
|---|---|---|
| 1 | Region worker | 原生候选选择循环，读取冻结快照 |
| 2 | Region worker | 设置协作停点，所有参与刷怪 worker 停驻 |
| 3 | 服务器线程 | 规则判断前发布此前延期的效果 |
| 4 | 服务器线程 | 完整原生 `finalizeSpawn`、passenger admission、`afterSpawn`、cluster/group-limit 回调 |
| 5 | 服务器线程 | 真实的加入/取消结果对下一个候选可见 |
| 6 | worker | 携带同一规则 revision、unit 上下文和随机数流继续；prepared 正文只开始一次 |

| 保证 | 不保证 |
|---|---|
| 两个候选不会同时抢走最后一个附近名额 | 原版全局候选顺序 / RNG 序列逐位一致 |
| 被取消的生成仍按原版消耗 RNG | 零成本：停点、发布和最终化都是真实开销 |
| 已开始的正文不会重放 | 失败正文可以重试 |
| 不支持的配置在**副作用之前**被拒 | 所有第三方监听器都安全 |

被线程池拒绝的组通过同一屏障在提交线程执行；异常、停机、中断都不重放已产生副作用的操作。跨组事务按提交顺序执行。

## 7. claim 的范围与限制

```java
registrar.claimNaturalSpawning(RULES, "example", () -> verifyInstalledVersion("1.2.3"));
```

| claim 替代 | claim 不替代 |
|---|---|
| 该模组的 `DISABLE_SPAWNING` 兼容动作 | `DISABLE_ENGINE`（例如 c2me） |
| 默认“该模组保持串行”的决定 | 管理员关闭的切片或开关 |
| 其他什么都没有 | 其他模组的限制或事件监听器 |

claim 是适配器作者的显式合同：必须引用已注册 key、不可重复、启动时 `verified` 必须成功。未知原生注入仍应回退。

## 8. 示例

### 8.1 逐 chunk 规则数据（默认协议）

```java
static final ParallelApi.DataKey<RuleSet> RULES =
        new ParallelApi.DataKey<>(ResourceLocation.parse("example:spawn_rules"), RuleSet.class);

@SubscribeEvent
public static void onRegister(RegisterParallelAdaptersEvent event) {
    event.registrar().register(ParallelApi.MAJOR_VERSION, RULES,
            unit -> new ParallelApi.Ready<>(RuleSet.forChunk(unit.dimension(), unit.chunkX(), unit.chunkZ())));
}

// 在正文期间运行的刷怪监听器内
ParallelApi.prepared(RULES).ifPresent(rules -> applyFilter(rules));
```

### 8.2 整计划规则 + 有序最终化

```java
@Override
public Preparation<RuleSet> preparePlan(SpawnPlan plan) {
    if (!packConfigIsUniform(plan.dimension())) {
        return new ServerRequired<>(ResourceLocation.parse("example:world_dependent_rules"));
    }
    if (!registrarCapabilities.contains(ParallelApi.Capability.ORDERED_SPAWN_FINALIZATION)) {
        return new Ready<>(RuleSet.forPlan(plan)); // 普通整计划快照
    }
    return new Ready<>(RuleSet.forPlan(plan), true);
}

@Override
public Preparation<RuleSet> prepare(SpawnUnit unit) {
    return new ServerRequired<>(ResourceLocation.parse("example:per_unit_disabled"));
}
```

## 9. 作用域与生命周期

| 事实 | 后果 |
|---|---|
| `prepared(key)` 的作用域是当前正文线程 | 不要交给你启动的线程 |
| 作用域覆盖整个 `spawnForChunk` 调用 | 存在不代表筛选已执行或生物会加入 |
| 串行回退隐藏外层作用域，但保留整计划数据 | 每计划一个规则 revision |
| 单元会校验服务器、维度与 gameTime | 过期单元被跳过，绝不跨 tick 排队 |
| 服务停止后旧单元失效 | 同一 JVM 重启会建立独立服务 |
| 公开快照值可作为纯数据保存 | 保存不延长期限或所有权 |

## 10. 三种执行模式对比

| 属性 | 逐单元 + worker 最终化（默认） | 整计划 + worker 最终化 | 整计划 + 有序最终化 |
|---|---|---|---|
| 能力 | `NATURAL_SPAWN_PREPARATION` | 另需 `NATURAL_SPAWN_PLAN_PREPARATION` | 另需 `ORDERED_SPAWN_FINALIZATION` |
| 规则 revision | 每 chunk | 每计划 | 每计划 |
| 候选选择 | worker | worker | worker |
| 原生最终化 | worker | worker | 服务器，peer 停驻 |
| 共享名额安全 | 仍受原版 level cap 约束 | 同上 | 显式有序 |
| 串行 fallback 数据 | 丢弃 | 保留 | 保留 |
| 成本特征 | 协调最少 | 低 | 停点 + 发布 + 主线程续跑 |
| 适用 | 数据因 chunk 而异 | 整包统一规则 | finalize 修改共享状态 / 结果必须可见 |

## 11. 诊断与失败

| 信号 | 含义 | 处理 |
|---|---|---|
| `preparedUnits` 增长而 `serialUnits` 也增长 | 准备成功但准入仍被拒 | 看 `lastSerialReason` |
| `serialPlans` 增长 | 计划在 cap 构造前被拒 | 修不支持的配置，不要放宽快照 |
| 刷怪开启但 worker 比例为 0 | 切片关闭、兼容拒绝或所有计划串行 | 查 `compat`、引擎开关、冲突 |
| 监听器里 `prepared` 为空 | 线程/作用域错误或走原生路径 | 只在正文内读取 |
| 注册抛异常 | 重复 id/type、版本、上限或 claim 误用 | 修清单；不要捕获后继续 |

| 绝不要 | 原因 |
|---|---|
| 重试部分生效的刷怪正文 | 效果不会回滚 |
| 把 `prepared` 当事件取消标志 | 存在不能证明其他监听器 |
| 为每个坐标生成 reason | 无界标签；用命名空间 |
| 认为有序模式更快 | 停点与发布都有真实开销 |

## 12. 检查清单

- [ ] `preparePlan` 对每个不支持的配置返回 `ServerRequired`。
- [ ] 快照深层不可变且不含活对象。
- [ ] 只有 `preparePlan` 返回 `Ready(..., true)`。
- [ ] claim 引用已注册 key 且版本已验证。
- [ ] reason 带命名空间且有界。
- [ ] 先在刷怪开关**关闭**时验证行为。
