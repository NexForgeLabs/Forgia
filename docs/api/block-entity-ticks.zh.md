# 方块实体 tick 准入（v1 谓词 + 受审计原 ticker）

[English](block-entity-ticks.en.md) | 中文 · [API 索引](README.zh.md)
源码：[`BlockEntityTickAdapter.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/BlockEntityTickAdapter.java)、[`AuditedBlockEntityTickAdapter.java`](../../src/main/java/net/neoforged/neoforge/server/threading/api/AuditedBlockEntityTickAdapter.java)
证据：[AUDITED-BLOCK-ENTITY-TICK-API](../../region-threading/AUDITED-BLOCK-ENTITY-TICK-API.md)、[BLOCK-ENTITY-PARALLELISM](../../region-threading/BLOCK-ENTITY-PARALLELISM.md)

状态：**实验性**，能力 `BLOCK_ENTITY_TICK_ADMISSION`（v1）与 `AUDITED_BLOCK_ENTITY_TICKS`（受审计）。
注册不会启用切片；必须同时打开 `enableRegionThreadingBlockEntityTicks`。

---

## 1. 何时用哪个合同

| 你的情况 | 使用合同 | 原因 |
|---|---|---|
| 能证明**整条**原 ticker 正文是 region 本地的，包括继承 tick、behaviour、lazy tick 和第三方变换 | **受审计**（`AuditedBlockEntityTickAdapter`） | 受审计合同提供 canonical 路径所需的清单与 READ/WRITE 足迹 |
| 只需要便宜的逐次拒绝门，并接受 canonical 路径仍可能串行 | **v1**（`BlockEntityTickAdapter`） | v1 注册二进制兼容，但不会自动获得 canonical 执行合同 |
| 机器读写外部容器/共享网络 | **都不要用** | 未知或共享资源必须保留完整串行 ticker |
| 无法枚举所有分支/回调 | **都不要用** | 返回拒绝，不要猜 |
| 需要跨区域效果而不是并行 tick | [方块实体操作](block-entity-operations.zh.md) | 不同协议：至多一次操作，不是所有权 |

## 2. 为什么是逐次准入而不是类型白名单

类型白名单只说“这个类通常没问题”，这并不成立：同一个类可能正在初始化、拓扑变更、执行回调或持有共享网络。
反过来，逐次检查不能缓存，因为状态每 tick 都在变。

所以引擎在原始 ticker 之前、**在 region 所有权下**问：“这次具体调用整条正文是否闭合？”准入永不缓存；
只有类型资格、内部 chunk 规则和未声明类型的整 chunk 回退会缓存。

## 3. 注册

```java
registrar.registerBlockEntityTicks(
        ParallelApi.MAJOR_VERSION,                        // 1
        ResourceLocation.parse("example:steady_machine"),  // 唯一 id
        Set.of(ResourceLocation.parse("example:machine")), // 1..128 个 type
        adapter);                                          // v1 或受审计实例
```

| 参数 | 约束 | 说明 |
|---|---|---|
| `apiMajorVersion` | `1` | 不匹配则注册失败 |
| `id` | 每服务器唯一、带命名空间 | 与刷怪适配器共用 id 空间 |
| `types` | 非空、≤128、与其他 BE 注册不重叠 | 重叠 type 集合被拒 |
| `adapter` | 实现 v1 或受审计合同 | 每个 id 一个实例 |
| 预算 | 总注册数 ≤128 | 与刷怪准备共用 |

服务器显式串行声明（`entities`/`block_entities`）和兼容/冲突策略仍优先。注册不会解除 `DISABLE_ENGINE`，
也不会替你打开 `enableRegionThreadingBlockEntityTicks`。

## 4. v1 合同 —— `BlockEntityTickAdapter`

```java
@FunctionalInterface
public interface BlockEntityTickAdapter {
    default void prepare(ServerLevel level) {}
    Decision check(BlockEntity blockEntity);

    enum Decision { ADMITTED, INITIALIZING, TOPOLOGY_CHANGE, CALLBACK_REQUIRED, UNKNOWN_CLASS,
                    UNKNOWN_BEHAVIOUR, SHARED_RESOURCE, INVALID_INSTANCE, UNVERIFIED_TRANSFORMATIONS }

    record Status(ResourceLocation id, Set<ResourceLocation> types, long checks, long refusals,
                  long workerCompletions, long preparationNanos, long checkNanos,
                  Map<Decision, Long> reasons) {}
}
```

| 方法 | 线程 / 频率 | 允许的工作 | 必须返回 |
|---|---|---|---|
| `prepare(ServerLevel)` | 静止服务器，每个非空 level 批次一次 | 只读已加载资源；发布脱离世界的不可变元数据 | 有界预检；不加载 chunk、不修改 |
| `check(BlockEntity)` | Region worker，每个原 ticker 之前 | 只读传入实例自有字段 + 已准备元数据 | 只有整条正文闭合时才 `ADMITTED` |

| `Decision` | 含义 | 典型原因 |
|---|---|---|
| `ADMITTED` | 本次整条 tick 正文是 region 本地的 | 稳态、仅本地库存 |
| `INITIALIZING` | 对象仍在构造/水合 | 加载/放置后的首个 tick |
| `TOPOLOGY_CHANGE` | 网络/邻居拓扑正在重写 | 红石切换、网络重建 |
| `CALLBACK_REQUIRED` | 某分支需要服务器侧回调/监听器 | 第三方钩子、事件派发 |
| `UNKNOWN_CLASS` | ticker/behaviour 类不在审计范围 | 模组新增了子类/behaviour |
| `UNKNOWN_BEHAVIOUR` | 未知 behaviour / lazy tick 路径 | 动态注册 |
| `SHARED_RESOURCE` | 读写外部或共享根 | 邻居容器、动力网络 |
| `INVALID_INSTANCE` | 已移除、被替换、level/位置不符 | 与移除竞争 |
| `UNVERIFIED_TRANSFORMATIONS` | Mixin/coremod 集合与审计集合不同 | 整合包新增了变换 |

除 `ADMITTED` 外，其他结果都保留**完整原 tick**，在 worker 返回后由服务器执行一次。拒绝必须发生在任何副作用之前；
异常是失败，绝不是许可。

## 5. 受审计合同 —— `AuditedBlockEntityTickAdapter`

```java
public interface AuditedBlockEntityTickAdapter extends BlockEntityTickAdapter {
    Audit audit();
    default void prepareCanonical(ServerLevel level) { prepare(level); }
    @Nullable Proof inspect(BlockEntity source, Class<?> tickerImplementation);

    record Implementation(Class<?> type, String sha256, Set<String> allowedMixins) {}
    record Audit(String reference, String revision, Set<Class<?>> sourceClasses,
                 Set<Class<?>> tickerClasses, List<Implementation> implementations) {}
    record Status(ResourceLocation id, String reference, String revision, boolean verified, String reason) {}
    enum Access { READ, WRITE }
    record Storage(Object identity, Access access) {}
    record Proof(List<Storage> storage) {}
}
```

| 方法 | 线程 / 生命周期 | 必须返回 |
|---|---|---|
| `audit()` | 注册服务器线程，一次 | 冻结清单：有界 reference/revision、精确 source/function 类、实现哈希与允许的 Mixin |
| `prepareCanonical(level)` | 原始已发布阶段中的静止服务器 | 有界只读准备；不异步调度、不加载 chunk、不缓存过期成功 |
| `inspect(source, tickerImplementation)` | 批次发布后的服务器 cursor，以及持有 region 的前缀检查（可在 worker 上） | 分支未闭合返回 `null`；否则返回完整的当前 READ/WRITE 足迹 |
| `check(source)` | Region worker，正文之前 | v1 拒绝门；核心随后重新校验绑定与足迹 |

### 5.1 清单校验（构造时强制）

| 组件 | 规则 |
|---|---|
| `reference` | 非空，≤512 字符 |
| `revision` | 非空，≤128 字符 |
| `sourceClasses` | 非空，≤128，必须包含精确 source 祖先 |
| `tickerClasses` | 非空，≤128，必须包含精确 function 祖先 |
| `implementations` | 非空，≤256，每个到达的实现一个 |
| `Implementation.type` | 非 null |
| `Implementation.sha256` | 小写十六进制，正好 64 字符 |
| `Implementation.allowedMixins` | ≤64 项，每项非空且 ≤256 字符 |
| `Proof.storage` | 非空，≤64 项；identity 与 access 非 null |

缺失元数据、字节变化或未知变换都会让目标保持串行。首个合同不接受含可变实例存储的 function 类。

### 5.2 足迹规则

| 规则 | 原因 |
|---|---|
| 把精确 `source` 以 `WRITE` 列入 | 正文会修改自己的方块实体 |
| 分支到达的每个可变 backing root 都必须列出 | 包装对象必须指向真正的共享根 |
| 只读共享根用 `READ` | 允许并行读 |
| identity 按引用比较，绝不调用 `equals`/`hashCode` | 模组对象不能靠相等性伪造所有权 |
| READ/WRITE 重叠会拆成前缀波次，即使同 region | 早先写入可能使后续分支失效 |
| READ/READ 可保持并行 | 无冲突 |
| 整个世界/服务器/chunk 声明、实体根、外部 BE 根 | 不授权 region 本地正文 |
| 新鲜检查必须返回相同的根与模式 | identity 变化时拒绝未开始的前缀 |

## 6. 执行顺序

| 阶段 | 线程 | 发生什么 |
|---|---|---|
| 1 | 服务器 | 原 canonical 阶段构建 BE 批次；执行 `prepare`/`prepareCanonical` |
| 2 | 服务器 | 捕获原 wrapper/delegate/source/function/chunk/frame 与注册身份 |
| 3 | Worker | 发布批次、投递 mailbox、检查候选、按足迹冲突分波 |
| 4 | Worker | 持有 region lease 下逐次 `check` + 新鲜复核 |
| 5 | Worker | **原始**完整 ticker 执行一次 |
| 6 | Worker | 物理 join；提交延迟发布/移除 |
| 7 | 服务器 | 任何未知项作为完整串行 fence 执行；之后重新检查后缀 |
| 8 | 服务器 | 已开始正文的失败被报告；tick 永不重放 |

桥接层不创建独立权限：复用既有 Native 前缀、`InvocationTicket`、region guard/permit、lease 与任务池。
适配器拿不到 engine、ticket、region state 或 lease 构造器。

## 7. 示例

### 7.1 v1 谓词适配器（最小）

```java
public final class SteadyGate implements BlockEntityTickAdapter {
    @Override
    public void prepare(ServerLevel level) {
        // 本批次只读预检；只发布不可变元数据
    }

    @Override
    public Decision check(BlockEntity be) {
        if (!(be instanceof MyMachine machine)) return Decision.UNKNOWN_CLASS;
        if (machine.isInitializing()) return Decision.INITIALIZING;
        if (machine.networkDirty()) return Decision.TOPOLOGY_CHANGE;
        return machine.usesLocalStorageOnly() ? Decision.ADMITTED : Decision.SHARED_RESOURCE;
    }
}
```

### 7.2 受审计适配器骨架

```java
public final class SteadyTicks implements AuditedBlockEntityTickAdapter {
    private static final Set<Class<?>> SOURCES = Set.of(MyMachineBlockEntity.class);
    private static final Set<Class<?>> TICKERS = Set.of(MyMachineTicker.class);

    @Override
    public Audit audit() {
        return new Audit("docs/steady-machine-audit", "3",
                SOURCES, TICKERS,
                List.of(new Implementation(MyMachineTicker.class, sha256Of(MyMachineTicker.class),
                        Set.of("mymod.mixin.MachineTickerMixin"))));
    }

    @Override
    public void prepareCanonical(ServerLevel level) {
        // 有界、只读；绝不保留 level
    }

    @Override
    public @Nullable Proof inspect(BlockEntity source, Class<?> tickerImplementation) {
        if (!(source instanceof MyMachineBlockEntity machine)) return null;
        if (!TICKERS.contains(tickerImplementation)) return null;
        if (!machine.branchIsAuditedAndSteady()) return null;

        return new Proof(List.of(
                new Storage(machine, Access.WRITE),            // 源实例本身
                new Storage(machine.energyBuffer(), Access.WRITE),
                new Storage(machine.localRecipeCache(), Access.READ)));
    }

    @Override
    public Decision check(BlockEntity be) {
        return be instanceof MyMachineBlockEntity m && m.isSteady()
                ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
    }
}
```

注册方式与 v1 完全相同；先协商 `AUDITED_BLOCK_ENTITY_TICKS`。

## 8. 对比

| 属性 | v1 谓词 | 受审计原 ticker |
|---|---|---|
| 注册 API | `registerBlockEntityTicks` | 同一方法，传入受审计实例 |
| 能力 | `BLOCK_ENTITY_TICK_ADMISSION` | `AUDITED_BLOCK_ENTITY_TICKS`（+ v1） |
| 清单 | 无 | 精确 source/function 类 + 实现 SHA-256 + 允许的 Mixin |
| 足迹 | 不声明 | 最多 64 个 READ/WRITE backing root |
| 核心校验 | 实例/类型/位置/level/绑定 ticker + 谓词 | v1 全部 **加上** 清单完整性、绑定、新鲜足迹、别名/冲突分波 |
| canonical Level 路径 | 仅谓词不足以获得 | 是 |
| 未知分支 | 串行 | 串行（`null` proof） |
| 适用 | 便宜的拒绝门 / 旧适配器 | 已审计的稳态机器 |
| 成本 | 低 | 高（必须枚举所有分支与根） |

## 9. 不可协商的限制

| 限制 | 影响 |
|---|---|
| 大 canonical 阶段 | 当前核心按至多 2048 个候选的窗口规划，整阶段资源与更新预算继续生效 |
| 内部 chunk 规则 | 邻居 chunk 在 lease 外的 BE 保持串行，除非其协议允许边界准入 |
| 未知类/子类/function | 串行；精确类阻止合同被继承 |
| 动态新增 behaviour/lazy tick | 下次调用前拒绝 |
| 含可变实例存储的 function 类 | 受审计合同拒绝 |
| 哈希匹配 | 只证明实现字节，**不**证明语义安全 |
| 适配器信任 | 安装适配器即信任其审计；这不是 JVM sandbox |

## 10. 失败语义

| 失败 | 结果 | 绝不 |
|---|---|---|
| `check`/`inspect` 抛异常 | 报告为失败 | 转成许可或“成功拒绝” |
| 正文前拒绝 | 稍后由服务器执行完整原 tick | 部分正文 |
| 正文开始后抛异常 | join/清理后报告 | 重放整个 ticker |
| 新鲜检查结果不同 | 拒绝未开始的前缀 | 用过期证明启动正文 |
| 批次中停机/卸载 | 保留原物理完成边界 | 遗留 lease |
| 相同描述符二次注册 | 作为新候选被拒/忽略 | 继承旧身份 |

## 11. 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

```text
Audited original-tick adapter forgia_create:steady_ticks:
  implementationsVerified=true, reason=VERIFIED_IMPLEMENTATIONS, audit=..., revision=...
Block entity tick adapter forgia_create:steady_ticks:
  checks=..., refused=..., nativeCompleted=..., prepareMs=..., checkMs=..., reasons={...}
```

| 字段 | 含义 | 不是 |
|---|---|---|
| `implementationsVerified` | 注册时冻结清单的完整性 | 当前分支安全或已完成 |
| `checks` / `refusals` | 调用计数 | 实体数 |
| `nativeCompleted` | 在 worker 上成功返回的正文数 | 提交数、回退或类名单 |
| `prepareMs` / `checkMs` | 累计墙钟时间 | 每 tick 成本 |

## 12. 检查清单

- [ ] 已协商能力、理解切片开关、保留串行路径。
- [ ] type id 不重叠，每适配器 ≤128，适配器 id 唯一。
- [ ] `prepare`/`prepareCanonical` 只读，不加载/调度/等待。
- [ ] `check`/`inspect` 只读自有字段与不可变元数据。
- [ ] 所有继承 tick、behaviour、lazy tick、回调与 RNG 分支都已审计。
- [ ] `Proof` 以 WRITE 包含精确 source 及每个真实 backing root。
- [ ] 拒绝返回有界 `Decision`，不为每个位置生成标签。
- [ ] 已开始正文绝不重放；失败如实报告。
- [ ] 在真实整合包上验证开关关闭（串行等价）与开启（重叠执行）。
