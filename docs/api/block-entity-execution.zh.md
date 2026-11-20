# 方块实体原始 tick 执行适配器

状态：实验性，`BlockEntityExecutionAdapter.PROTOCOL_VERSION = 1`。需要核心能力 `BLOCK_ENTITY_EXECUTION_ADAPTERS`。

该接口将完整、经过审计的原始 ticker 接入 canonical 方块实体阶段。原版和第三方类型都可以注册；核心负责实际调用身份、区域排他、资源冲突、执行前重审、worker、真实汇合与失败清理。安装 adapter 不会打开管理员关闭的引擎或方块实体开关。

## 注册与接口

在 `RegisterParallelAdaptersEvent` 中使用：

```java
var registrar = event.registrar();
if (registrar.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_EXECUTION_ADAPTERS)) {
    registrar.registerBlockEntityExecutionAdapter(
            BlockEntityExecutionAdapter.PROTOCOL_VERSION,
            ResourceLocation.parse("example:local_machines"),
            Set.of(ResourceLocation.parse("example:machine")),
            new MachineAdapter());
}
```

实现 `BlockEntityExecutionAdapter`，提供：

| 方法 | 合同 |
|---|---|
| `audit()` | 复用 `AuditedBlockEntityTickAdapter.Audit`：固定审计版本、精确 source/ticker、实现指纹和允许的 Mixin 来源 |
| `executionMode()` | 注册时冻结：`REGION_OWNER` 或 `RESOURCE_SCOPED` |
| `prepareCanonical(level)` | 当前已发布阶段的服务器线程，只读准备；不要把成功结论跨串行屏障缓存 |
| `check(source)` | 完整当前分支的结构化准入或拒绝，使用现有 `Decision` |
| `inspect(source, tickerClass)` | 完整 backing-root READ/WRITE 足迹；未知或不完整则返回 null |
| `inspectExecution(source, tickerClass)` | 默认组合 check 和 inspect；需要版本依赖时返回额外 `Dependency(identity, revision)` |
| `phaseClosed(successful)` | 阶段工作汇合、必要提交后清理自己的数据；失败也调用，准备抛异常也不会跳过清理 |

canonical 新执行路径的准备、check、inspect 和重审均在协调线程完成，任何正文开始前完成整波检查。旧显式驱动仍沿用继承的 audited/predicate 合同及其原回调时机；新注册不改变该历史入口。开发者应优先测试实际 `Level.tickBlockEntities` 路径。

注册使用同一服务器会话、冻结事务、128 个外部 adapter 总预算及 id/type 冲突规则。新 execution adapter 必须用新注册方法；旧 predicate、旧 C5 或 dedicated 标记不会因此获得并行权限。

## 执行模式

`REGION_OWNER` 在不同区域之间并行，区域内仍由一个 owner 顺序执行。它不提供外部网络或世界全局资源权限。

`RESOURCE_SCOPED` 由协调线程持有外层区域排他，每个 worker 只进入自己声明资源的 Scope。资源独立的方块实体即使在同一 chunk/区域也可以同时执行。该模式只支持完整的本地状态分支：不允许世界/provider 查询、RNG、事件、通知、packet、排程或任意主线程续体。

两种模式共用原始阶段、cursor、Ticket、Permit 和 worker pool。当前按连续无冲突 prefix 执行，不跨未知串行调用重排工作。

## 资源和依赖

当前版本复用 audited `Proof`，至多 64 个 `Storage(identity, READ/WRITE)`。必须列出 source 本身的 WRITE，以及正文到达的全部可变根。不同 wrapper 指向同一库存时，须列出真正 backing root；不能把不同 wrapper 当作独立资源。

核心拒绝重复 root、缺少 source WRITE、整世界/server/chunk、实体和其他 BE 对象。WRITE root 不能在阶段后续波次取得不同的逻辑 home；空 RegionState 的回收重建不会改变逻辑区域身份。共享 READ 仍依赖 adapter 对外部写入者的完整控制，不能靠缓存有效或一次快照代替。

额外依赖示例：

```java
return new BlockEntityExecutionAdapter.Inspection(
        proof, Decision.ADMITTED,
        List.of(new BlockEntityExecutionAdapter.Dependency(topologyRoot, topologyRevision)));
```

依赖 root 必须也出现在 footprint 中，且不能重复。重审时版本、root 身份和访问模式必须一致。这样同波的写入者也会与读依赖冲突，而不只是比较一个旧版本号。外部变更如何更新版本，属于 adapter 的审计责任。

资源身份按引用比较，不调用模组对象的 equals/hashCode。公开 `Proof` 只是申请；核心创建内部 claims、核对真实源和绑定，再保留整波资源。开发者没有 Lease、Permit 或任意 home 的构造接口。

## 大阶段、失败与诊断

canonical 阶段按最多 2048 个候选的窗口规划，大于 2048 项不再因此整体串行。每波还受 worker 数、模式和资源冲突限制。阶段资源预算不随窗口重置，既有 deferred-update 预算继续生效；预算不足的未开始后缀保持完整串行。

当前只对计划/保留资源设窗口，原阶段成员和 Ticket 仍是 O(N) 快照。`RegionBlockEntityWorkGraph` 的诊断采样仍有界，实际执行总量应读取引擎与 adapter completion 计数。

返回拒绝时完整原始 ticker 在服务器执行一次；检查回调异常会中止阶段。正文部分生效后发生异常，核心等待真实返回并清理资源，禁止主线程重放。

诊断复用 `blockEntityTickAdapters()`、`auditedBlockEntityTicks()` 和现有 regionthreading 命令。`checks` 包含规划和重审，`workerCompletions` 只计实际成功返回的原始 worker tick。它们不表示整服加速倍数。

这是受信任 adapter 的审计合同，并非任意 Java 的自动安全证明。首版保留无状态 ticker 限制；通用 Capability resolver、自动外部写入拦截、多 home 事务及通用效果提交不在当前能力内。

独立范例与验证见 [BlockEntityExecutionAdapterTest](https://github.com/NexForgeLabs/Forgia/blob/main/tests/src/junit/java/net/neoforged/neoforge/unittest/BlockEntityExecutionAdapterTest.java)；真实目标模组接入见 [CreateTickAdmission](https://github.com/NexForgeLabs/Forgia/blob/main/adapters/create/src/main/java/net/neoforged/forgia/compat/create/CreateTickAdmission.java)。
