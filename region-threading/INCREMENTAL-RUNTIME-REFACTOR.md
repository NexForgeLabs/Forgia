# 渐进式运行时重构：Chunk 与诊断切片

日期：2026-11-16。目标是降低维护耦合，不重写 Minecraft/NeoForge，不新增并行准入，也不宣称性能收益。

## 方向与边界

采用模块化单体：稳定入口、按功能切片、受限 Host 接口、现有并发授权体系。先在原包内抽取职责，稳定后才考虑物理搬包或构建模块拆分。禁止为了测试或拆包而公开权限构造器。

目标逻辑结构：

```text
Minecraft / NeoForge / Mod
  -> 原 Patch / Hooks / ParallelApi
  -> RegionThreadingEngine（兼容门面）
  -> 各功能的阶段编排
       Chunk / Entity / BlockEntity / ScheduledTick / Spawn
  -> 原所有权、Batch、屏障及各阶段专属的交接协议
  -> 原 RegionWorkerExecutor / RegionWorkerPool

诊断只观察；Adapter 只提供受限描述或证据，不能签发执行权限。
```

这不是一个已经完成的通用框架：不同阶段仍保留各自的时序与准入，不能用一个万能 TickProcessor 替代它们。BE 专属 Ticket/Permit/Resource 协议也不会被套到所有 Tick 类型。

## 第一轮：构造期参数与 Chunk 编排

### 构造期参数

新增内部 `RegionThreadingOptions`，将原构造参数分成 `Workers` 和 `TickSlices`，并保留原 layout/entity-storage identity。`ServerLifecycleHooks` 在完成原有兼容判断后组装这些值。

所有旧 `RegionThreadingEngine` 构造器继续存在，转发到新的构造入口；自动线程数计算、配置默认值、实体存储选择以及启动后 spawning 收窄逻辑保持原行为。Options 不读取配置、不创建线程池，也不充当实时准入缓存。

### Chunk 阶段编排

新增包级私有 `RegionChunkTickCoordinator`。原 `RegionThreadingEngine.tickChunks` 入口委托给它，单个协调器和 Host 在 Engine 构造时创建，而不是按 chunk 创建。

```text
RegionChunkTickPlan
  -> 原 Engine.tickChunks
  -> RegionChunkTickCoordinator
       结束 plan 归因区间
       -> 创建原 RegionChunkTickBatch
       -> Host 调用原生命周期门控 publication
          - Level 已退休：计数并跳过
          - shutdown：沿原路径按收集顺序 inline
          - published：标记原 Batch 的执行线程
       -> 原 leaseGroups
       -> 原 runRegionBatch / submitAndAwait
       -> 原有序 completion / batch.finish / active removal
       -> 原失败报告策略
```

Host 是 Engine 内部的窄桥，不是第二个运行时。原 publication 结果枚举移为包级私有 `TickBatchPublication`，没有改变取值、决策、锁或权限。

本轮只抽出了阶段编排。Chunk admission、分组、Worker 正文、RNG、效果实现、统计字段和其他 Tick 阶段仍在原实现；不应把这个切片描述为全部 Chunk 模块或 Engine 已经迁完。

### 测试与依赖约束

新增 26 个测试用例：

- Options：13 项，覆盖独立开关映射、旧构造器默认值以及 spawning 初始值不是实时状态缓存。
- Chunk Coordinator：9 项，覆盖空输入、publication、单一 Batch、顺序 fallback、lease/join 异常、先 join 后完成、错误优先级和不重放。
- 架构边界：4 项，检查新 Coordinator 不直接依赖 Engine/具体 Pool/BE 授权后端，Options 不查询配置或保存世界运行时，以及 Pool 不反向依赖新功能组件。检查读取 class 常量池，无新增第三方测试依赖。

这些是首个切片的依赖约束，不是对现有整个 region 包已经无环的认证。测试留在原 unittest 包；私有协调器使用受控反射/Proxy 测试，不放宽生产可见性。

## 保持不变的安全合同

- 继续使用同一个原 Batch publication、active 集合、生命周期门控和 Worker Pool。
- Batch 在 lease acquisition 之前发布，卸载/关闭依然等待实际工作和必要交接完成。
- 原有序交接依次保留：section updates、removals、additions、block updates、lightning、fallback chunks、LevelTicks mutations。
- 已开始正文不会因异常在主线程重放；异常包装和原首个失败优先级不顺带修改。
- 不改 Vanilla patches、公开 v1 API、默认开关、预算或 Mod 适配准入。
- 不改 Canonical BE cursor、Ticket/Permit、Claims/Lease/Scope、Core verifier 或 optional runtime-proof 服务。

## 第一轮验证记录

环境：JDK 21，Gradle offline。默认 shell 的 JDK 26 没有用于本轮 Gradle 验证。

```bash
export JAVA_HOME=/home/yc/.jdks/jdk-21.0.12.1+1
export PATH="$JAVA_HOME/bin:$PATH"

# 修改生产代码前的定向基线：114 tests，0 failure/error/skip。
./gradlew --offline :tests:junitTest \
  --tests '*RegionChunkTickTest' --tests '*RegionChunkTickSharedStateTest' \
  --tests '*RegionThreadingEngineTest' --tests '*RegionWorkerPoolTest'

# 首轮迁移加 Options 回归：127 tests，0 failure/error/skip。
# 三个新增测试类：26 tests，0 failure/error/skip。

# 全量默认 JUnit 任务：1450 tests，0 failure/error/skip。
./gradlew --offline :tests:junitTest
```

默认任务原有的 benchmark tag 和独立 unknown-Furnace-Mixin 控制用例排除保持不变；被排除工作不是通过的用例。预先基线只运行了上述 114 项，不能把后续全量结果说成修改前的全量基线。

原始命令输出、汇总及失败尝试保留于本地忽略目录 `tmp/architecture-refactor/`。最初将白盒测试放进生产包导致 JPMS split-package，测试进程未启动；随后恢复独立 unittest 包。第一次迁移测试包后的编译又发现 package-private factory 调用，已改用原有 public record 构造器；没有跳过断言或扩大生产权限。

全仓 formatter 还会改动六个本轮无关的既有文件，已逐项核对并撤回这些副作用；本轮格式验收只选择修改的九个 Java 文件，不把它描述为全仓格式修复。全量 root/tests license 检查最初被既有 `LifecycleRetirementRaceTest.java` 的许可证头后缺失空行阻塞；仅补回这一空行，不改测试正文或断言，随后两项 license 检查通过。最终九文件格式检查、root/tests license 检查及再次完整 JUnit 运行均 exit 0。

本轮没有重复 C6 完整 GameTest/VM 证明验收、installed-server soak、多玩家测试或性能 A/B。JUnit 通过不能替代这些证据，也不建立新版本的 C6 认证或性能提升。

## 第二轮：按需诊断聚合

本轮新增包级私有 `RegionDiagnosticsCollector`，集中 Border、Block Event、Game Event、Save、分 slice block-update/failure、Level phase 与 Worker dispatch 的诊断组装。公开 `RegionThreadingDiagnostics` 的构造签名、访问器和统计含义不变。

### 组件边界

- 四个具名 Sources 分组借用原来的 43 个 `AtomicLong`；四个 slice 数组也借用原实例。原字段、递增位置和计数器存储继续留在 Engine，没有影子计数器、复制初始化或新增指标注册表。
- Collector 与四个 Sources 只在 Engine 构造时建立；普通 ticker 不增加采集调用。诊断调用按需生成既有输出记录/数组，phase 汇总另产生一个小的 `PhaseCounts` 值对象，不宣称诊断零分配。
- Worker dispatch 只传入原 Pool 的 `DispatchSnapshot` 值和原 packing 计数值，不传入 Pool 本身。phase 集合只在当前调用遍历，不保存在 Collector 中。
- 原数组访问器的行为不改：返回的 long 数组与计数器分离，但不是新承诺的深度不可变接口。每次新诊断读取新值，不复用调用方可能修改的旧数组。
- 诊断仍是逐项观测，不是跨字段的全局原子快照；没有为此加入锁、停顿、调度、世界回调或准入决策。

Sources 内部仍是原子计数器引用，按只读用途约束，不能把它描述为对不可信代码的权限沙箱；这些类型不进入 Mod API。架构测试限制该组件引用写入/派发方法，也限制其依赖 Engine、世界和执行授权实现。

这里刻意不把整个 `diagnostics()` 外壳搬走：实时 region/player/entity 索引取样及公开大记录的装配仍保留原入口，避免为了搬文件再增加一百多个转发 getter 或重复数据模型。新边界用于后续逐个迁移功能模块的观测部分，而不是另造一套监控系统。

### 第二轮测试

新增 `RegionThreadingDiagnosticsTest` 的 14 项用例，以及两项架构约束，总计新增 16 项：

- 用各不相同的值验证原 43 个计数器映射、实时读取、快照分离和原计数器未被重置。
- 验证 Collector 借用的原子计数器/数组确实是原实例，不持有世界、Pool、phase owner 或独立缓存。
- 验证四个 slice 数组的复制行为、多 Level phase 求和与清空后的实时更新。
- 验证调度输出的全部 17 个字段，并在 Fixed/Persistent 两个原 Pool 上核对观测不提交任务、不替换 Pool。
- 验证观察不会消费任务队列、改变已发布 region 列表或缓存 startup spawning 的旧选择。

本轮定向基线为 107 项；最终定向回归 147 项，0 failure/error/skip。最终本轮四文件格式检查及 root/tests license 检查均 exit 0。

首次包含完整默认 JUnit 的组合命令触发 900 秒工具时限；其之前完成的格式/许可证和 147 项定向结果保留，但完整 JUnit 没有生成结果。残留 XML 来自前面的定向任务，不能算作本次完整运行通过。原日志和 binary 输出保存在 `tmp/architecture-refactor/diagnostics/full-timeout-*`。

随后将外层运行预算设为 1500 秒，以只增加逐项进度日志的临时 init script 重跑完整默认 JUnit；没有改 JUnit 内部超时、过滤条件、断言、配置默认值或场景规模。该次在约 991 秒完成，实际 Gradle exit 0；122 个 suite 的新 XML 合计 1466 tests、0 failures、0 errors、0 skipped，且每个 XML 的更新时间晚于该次启动。默认 benchmark/独立控制场景的原排除仍不是通过记录。

正式结果见本地 `diagnostics/retry-status.json`、`diagnostics/full-junit-retry.log` 和 `diagnostics/full-junit-retry-results.json`（均位于 `tmp/architecture-refactor/`）。第一次超时没有被覆盖或重新标记为通过，也没有拿前面定向运行残留的 XML 凑完整结果。

本轮同样没有重跑 C6 完整 GameTest/VM 证明、安装服 soak 或性能 A/B，不构成新性能或兼容性认证。

## 后续顺序

1. 沿诊断分组边界继续整理观测外壳；涉及实时索引取样时保持原语义，不为搬代码添加庞大的转发层。
2. 根据单独的阶段契约，逐步迁移 Chunk 的外围准备/交接职责；不先搬动权限实现。
3. Entity、Spawn、ScheduledTick 各自独立迁移、回归和回退，不在一个提交里混改多个时序。
4. BE 与 C6 受审计路径最后独立处理，涉及绑定/字节码完整性边界时重新验收。

回退以单个切片的代码变更为单位，不新增双生产引擎开关，也不在同一份活世界上重复执行两条有副作用的路径。
