# Forgia

[English](README.md) | 中文

**面向 NeoForge 1.21.1 的区域化、多线程 Minecraft 服务器。**

Forgia 把 [Folia](https://github.com/PaperMC/Folia) 的区域模型原生移植到 NeoForge：世界被划分为若干区域，
每个区域拥有自己的 chunk、实体、方块实体与兴趣点，各区域在 worker 线程上并行 tick。它是 NeoForge 的
分支，不是插件平台 —— 调度与所有权通过原生 NeoForge API 暴露，模组仍以模组方式运行。

> **项目状态：独立。** Forgia 最初是早期 Forgia 移植仓库的分支。上游停止更新后，本仓库成为延续：
> 以 NeoForge 21.1.x 为基线，区域化运行时在这里开发。哪些可用、测量了什么、还缺什么，见
> [region-threading/STATUS.zh.md](region-threading/STATUS.zh.md)。

---

首次受控部署请使用[生产就绪审计](region-threading/PRODUCTION-READINESS.md)、
[完整配置矩阵](region-threading/PRODUCTION-CONFIGURATION.md) 与
[canary/回滚流程](region-threading/CONTROLLED-CANARY.md)。C6 与停服真实 Level GC 已在其文档化范围内通过；
已安装 canary 整合包仍需自己的兼容与恢复演练。运行时 Mod 热卸载仍不支持，且**未认证**。

## 今天可用的能力

下表包含历史负载测量，不是 C6 canary 的性能结论。所有区域线程开关**默认关闭**。正文前准入可把不支持的工作
留在服务器线程；副作用开始后的拒绝或失败会被报告，不会重放整个 ticker。

| 领域 | 作用 | 实测 |
|---|---|---|
| **实体 tick** | 区域 tick 自己的实体；服务器线程不再遍历实体列表 | 1024 生物上 **2.5–4.5×**（4 worker） |
| **Chunk tick** | 随机 tick、冰霜/积雪、雷暴由拥有该 chunk 的区域执行，包括区域边界上的 chunk | 1.24–1.27×（100% 已派发 chunk 在 worker） |
| **自然刷怪** | 每区域刷怪正文，对原版 level 级 mob cap 记账 | 刷怪切片 4.6 → 2.8–3.9 ms |
| **兴趣点** | 区域分区存储 + 已发布快照；查询与原版一致 | 村民 **2.1–2.4×**，僵尸 **3.4×** |
| **方块实体** | 单一 canonical 阶段：窄化的原版 Hopper 分支、已验证的燃烧熔炉前缀与完整 Unknown/Create Serial Fence | C6 正确性已验收；当前不声明整服加速 |
| **红石** | 跨区更新通过 fixpoint 在**同一 tick** 收敛 | 49 种布局（开/关各一轮），5 880 次 tick 比较，与原版一致 |
| **跨区读** | Seqlock chunk 版本；过期读会重跑产生它的区域 | 0 撕裂读，0 过期取用 |
| **跨区模组操作** | 公开双 owner 方块实体操作、有界 sided Container 搬运与显式任务结果 | 实验性；worker 返回后操作串行化 |
| **Scheduled tick** | 已加载、区域内含的惰性足迹中的原版灯回调；其他阶段保留 live 串行队列 | 实验性；派发/完成/拒绝/计时诊断 |
| **全局状态** | 显式 global 阶段 + 有界跨区 mailbox | 从区域写天气/时间会 fail closed |
| **失败处理** | worker tick 失败由服务器线程报告，绝不在 worker 上继续构建；触到 lease 未持有 chunk 的 tick 截断一次，其类移到服务器线程 | 屏障等待 200 ms → **1.25 ms** |

正确性始终是验收标准：POI 管理器、红石组件与 chunk 随机 tick 都与**真实原版实现**逐 tick 比较，
而不是与预期比较。

## 尚未完成的部分

Forgia 不是完成态的 Folia 替代品。诚实的清单：

- **轻负载还赢不了。** 2048 漏斗产线 3.7–4.0 ms，inline 为 3.3 ms；4096 熔炉 2.7–3.3 ms 对 2.6 ms。
  worker 正文本身每 ticker 约是 inline 的 2.5 倍成本，持久线程池仍默认关闭，等待完整负载矩阵。
  [work-stealing 派发器](region-threading/WORK-STEALING-DISPATCHER.md) 现在批量提交并窃取未开始任务区段；
  其仅派发测量不构成整服加速。
- **生产 soak 是持平，不是领先。** 全部开启时落在 inline mspt 或以下（中位比值 0.983，p50 5.60 ms 对
  6.22 ms），但真实世界在 3-chunk 区域下没有足够并行工作获得更多收益。
- **漏斗与 pack 准入机器仍需内部规则**：它们会读写 live 邻居容器。熔炉族边界 chunk 现在可在开启边界传播
  且邻居已加载时运行；混合 chunk 仍保持整 chunk fallback。见
  [方块实体实现与后续步骤](region-threading/BLOCK-ENTITY-PARALLELISM.md)。
- **结构不感知区域。** 跨边界移动方块的活塞与 Create 式移动装置仍在服务器线程运行。
- **跨区 game-event 捕获（sculk）已实现且默认关闭。** 带 worker 回调的 inline 基线可能触达外部监听器；
  请显式验证目标配置。
- **玩家默认在服务器线程 tick；实验性玩家区域 tick 已实现。** 区域成员、骑乘、传送与维度交接已有有界测试；
  可通过 `enableRegionThreadingPlayerTicks`（P2–P5）与声明式区域 Worker 载荷 API（`IRegionPayloadHandler`）
  在 Worker 上执行已准入玩家（32 玩家 `n32-spread` 实测 `3.97 ms` 对串行 `4.24 ms`），未声明模组 fail-closed 回退主线程。
- **长跑与多人验收仍未完成。** 10 分钟 32 假人全并行耐久测试（`FullParallelSoakGameTests`，`DYNAMIC_CLUSTER`，7 Workers）
  实现 `0` 次所有权违规，且 P50（`-7.1%`）与 GC 耗时（`-48.8%`）优于原版串行，但轻载均值与长尾仍受协调与回退开销影响；
  任意 C6 canary 整合包仍需独立验证。
- **已验收固定布局中区域尺寸是静态的。** 实验性 `DYNAMIC_CLUSTER` 加载岛屿聚合调度已可在 C6 范围外启用；
  连续区域 tick 在现有顺序合同下已被调查并否定。

完整剩余计划见 [region-threading/STATUS.zh.md](region-threading/STATUS.zh.md#路线图)。
完整 Forgia API 清单（公开类型、方法签名、调度器、操作句柄、兼容声明与配置字段）见
[API 参考](region-threading/API-REFERENCE.md)。实验性公开模组集成 API 与 compile-only 门面见
[并行 API 指南](region-threading/MOD-PARALLEL-API.md)。完整跨区操作与 scheduled 回调准入/指标见
[操作指南](region-threading/CROSS-REGION-OPERATIONS.md)。更广的准备与并行刷怪过滤合同在
[API RFC](region-threading/MOD-PARALLEL-API-RFC.md) 中提出；这些后续阶段尚未实现。
[实验性 MobSpawnController 适配器](docs/adapters/mobspawncontroller-adapter.zh.md) 为 1.2.3 提供冻结规则与
已验证准入。有序 nearby/chance 实验**需显式开启且默认关闭**：本地整合包验证发现工作截断且没有已证实的加速。
默认自然刷怪保持串行；不支持的世界过滤、倍率与未验证原生钩子也保留完整串行计划。
[实验性 Create 6.0.10 适配器](docs/adapters/create-adapter.zh.md) 现在使用通用
[受审计原 ticker API](region-threading/AUDITED-BLOCK-ENTITY-TICK-API.md)，让稳态传动部件、离合器与动力
转向器进入 canonical Level 路径。所有 Create 实现/状态/足迹知识都在适配器内；Core 校验共享合同、绑定、
生命周期与所有权。适配器是显式审查过的信任合同，不是由类型/哈希自动证明。水车/磨石的 v1 检查仍是历史
明确驱动覆盖；移动装置与未知回调保持串行。此扩展不带来整服性能或已安装整合包兼容声明。

## 文档

受维护文档集位于 `docs/`（已发布至 [GitHub Pages](https://nexforgelabs.github.io/Forgia/)），以**中英成对**（`*.en.md` / `*.zh.md`）维护。`region-threading/` 目录保留为
证据档案（审计、验收运行、profiler 记录）。

| 从这里开始 | English | 中文 |
|---|---|---|
| 站点首页 / Home | [Home](docs/index.md) | [首页](docs/index.zh.md) |
| 架构对比：原版 vs Forgia | [Architecture](docs/architecture.en.md) | [架构对比](docs/architecture.zh.md) |
| 实测性能基准 | [Benchmarks](docs/benchmarks.en.md) | [性能基准](docs/benchmarks.zh.md) |
| 工程状态与测试回执 | [Status](docs/status.en.md) | [工程状态](docs/status.zh.md) |
| 文档索引 | [docs/README](docs/README.en.md) | [docs/README](docs/README.zh.md) |
| 面向模组的 API 入口矩阵 | [API overview](docs/api/README.en.md) | [API 总览](docs/api/README.zh.md) |
| `ParallelApi` 服务与准备结果 | [ParallelApi](docs/api/parallel-api.en.md) | [ParallelApi](docs/api/parallel-api.zh.md) |
| 刷怪准备与 claim | [Spawning](docs/api/natural-spawn-preparation.en.md) | [刷怪准备](docs/api/natural-spawn-preparation.zh.md) |
| 方块实体 tick 准入（v1 + 受审计） | [BE ticks](docs/api/block-entity-ticks.en.md) | [BE tick](docs/api/block-entity-ticks.zh.md) |
| 资源作用域 BE 执行契约 | [BE execution](docs/api/block-entity-execution.en.md) | [BE 执行契约](docs/api/block-entity-execution.zh.md) |
| 跨区双 owner 操作 | [BE operations](docs/api/block-entity-operations.en.md) | [BE 操作](docs/api/block-entity-operations.zh.md) |
| 调度器与实体交互 | [Schedulers](docs/api/schedulers-and-entity-interactions.en.md) | [调度器](docs/api/schedulers-and-entity-interactions.zh.md) |
| 编写适配器模组 | [Adapter guide](docs/adapters/README.en.md) | [适配器指南](docs/adapters/README.zh.md) |
| Create 适配器 0.3.0 | [Create](docs/adapters/create-adapter.en.md) | [Create](docs/adapters/create-adapter.zh.md) |
| MobSpawnController 适配器 0.3.0 | [MSC](docs/adapters/mobspawncontroller-adapter.en.md) | [MSC](docs/adapters/mobspawncontroller-adapter.zh.md) |
| 模块实现说明 | [Modules](docs/modules/README.en.md) | [模块](docs/modules/README.zh.md) |
| 命令、指标、配置 | [Observability](docs/modules/observability.en.md) | [观测与配置](docs/modules/observability.zh.md) |
| 完整 API 清单（证据档案） | [API-REFERENCE](region-threading/API-REFERENCE.md) | 同左 |

## 配置

Forgia 的区域线程设置位于 **`config/forgia.toml`**，与 `neoforge-server.toml` 分离。
所有启用开关**默认 `false`**。切回上游 NeoForge 不会改动 `forgia.toml`；再切回 Forgia 会读取已保存设置。

首次启动时若 `forgia.toml` 缺失，Forgia 只把自身键从 `neoforge-server.toml` 复制过来，随后 NeoForge
配置加载器会修正该文件。若上游已移除这些键，也会识别保存的 `neoforge-server.forgia.toml` 侧文件。
已存在的 `forgia.toml` 总是优先。同一迁移适用于世界的 `serverconfig` 目录，其 `forgia.toml` 按正常
SERVER 配置规则覆盖实例文件。迁移不修改 legacy 源文件。若两个 legacy 文件都不再保留设置，Forgia 使用
默认关闭配置；先前被擦除的值无法恢复。请在停服时修改引擎开关，下次启动生效。

以下是历史的 all-slice 示例。它与已验收 C6 profile（刷怪、边界更新与 game event 关闭）不同；准备 canary 时
请使用[受审计模板与限制](tools/production-readiness/README.md)：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingEntityTicks = true            # 选择 region-owned 实体存储
enableRegionThreadingBlockEntityTicks = true
enableRegionThreadingRandomTicks = true
enableRegionThreadingSpawning = true
enableRegionThreadingPoi = true                    # 由实体 tick 隐含
enableRegionThreadingBorderUpdates = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 3
regionThreadingWorkerThreads = 4
```

`regionThreadingWorkerThreads = 0` 表示“CPU 数减一”。实体 tick、刷怪与 POI 要求固定布局；其他项在缺少
前置条件时退化到原版路径。

另有默认关闭的 `enableRegionThreadingFurnaceWorkWaves` 实验，按资源波次执行已验证的燃烧原版熔炉族正文，
包括同一 chunk/区域内相互独立的实例。未知/燃料/通知操作是完整串行 fence；该模式下其他 BE 类型也串行。
使用前请阅读[精确准入、所有权与验证限制](region-threading/FURNACE-WORK-WAVES.md)。它不声明通用波次引擎
或已测服务器加速。后续[成本分析与优化计划](region-threading/BLOCK-ENTITY-OPTIMIZATION-PLAN.md) 把已验证
执行路径与剩余性能工作及其验收标准分开。

默认关闭的 `enableRegionThreadingBlockEntityResources` 在现有 Region 排除之下叠加
[通用资源身份、READ/WRITE claims、非阻塞 lease 与 scope](region-threading/GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md)，
并把熔炉波次作为首个已审计消费者迁移。它不接纳任意容器/capability/模组回调，也不启用跨区 worker 事务；
大型 proof 足迹最初有显著分配/规划成本。
[`.58` 优化](region-threading/BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION.md) 共享新鲜 READ 发布、
把执行规划与诊断分离并复用不可变权限表；成对机制测量不构成整服加速，也不改变默认关闭策略。
[尾延迟调查与已安装服务器 A/B](region-threading/BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md) 把旧 probe 的
阻塞 JIT 编译与冻结 `.58` 二进制上的整 tick 成本分开。资源开启层在 35/36 矩阵对与两次独立长窗口重复中
提高平均 MSPT；它仍默认关闭，没有支持更广 BE 准入的性能证据。
[`.59` 热路径成本削减](region-threading/BLOCK-ENTITY-RESOURCE-HOT-PATH-REDUCTION.md) 用不可变数组与调用本地
身份表取代基于节点的 proof 表与每单元 planner 展开。两个已安装服务器构建对确认资源层激活时分配更低；
动态 proof 与准入保持。这降低了现有层成本，不构成默认开启的理由。
[混合负载验证](region-threading/BLOCK-ENTITY-RESOURCE-MIXED-VALIDATION.md) 用其他串行方块实体、正常世界 tick
与动态状态变化检查冻结 `.59` 实现；其真实服务器 resource-on/off 证据与旧/新实现成本比较不同。有界 30 分钟
配对通过完整状态检查，但资源层开启时平均 MSPT 增加 18.8%、分配增加 82.5%。该选项仍默认关闭，BE 准入限制不变。
[`.60` 源码成本归因与优化](region-threading/BLOCK-ENTITY-RESOURCE-COST-ATTRIBUTION.md) 在阶段内共享未变的
不可变本地描述并复用已清空的 identity table 容量，同时保留每次新鲜审计与 validator。四个反向构建/条件对把
实测 ON 减 OFF 分配增量降低 35.1–36.6%；整 tick 延迟仍不一致，mean/p95 只在 1/4 构建对中改善，并在 10 分钟
开/关检查中回归。这是分配削减，不是已验证的服务器延迟改善或更广 BE 准入。

下一阶段始于[模组方块实体适配架构调查](region-threading/MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE.md)。
额外 Resource Ownership MSPT 调优已冻结。早先的[首次执行合同迁移](region-threading/BLOCK-ENTITY-EXECUTION-CONTRACT.md)
让 native adapter tick 与受保护 Furnace Waves 共享内部 ticket/permit 生命周期，并在 Region owner 与从属
资源工作之间显式排除。该检查点中，审计、开关、串行 fallback 与提交仍在原驱动中，没有新 Mod BE 准入或通用
adapter 注册协议。Core 1244、resource-on 181、persistent 199、实际未知 Furnace Mixin 1、独立 Create 16
与未知 Create Mixin 1 通过；整服性能仍未测量。

[Canonical BE Coordinator 设计](region-threading/CANONICAL-BLOCK-ENTITY-COORDINATOR.md) 最初只是文档计划。
[C1](region-threading/CANONICAL-BLOCK-ENTITY-C1-TESTS.md) 记录了八个启用的顺序/fence 失败；
[C2](region-threading/CANONICAL-BLOCK-ENTITY-C2-COLLECTION.md) 在 pending merge/onLoad 后收集实际 Level
成员，修复了其中五个。[C3 桥审计](region-threading/CANONICAL-BLOCK-ENTITY-C3-NATIVE-BLOCKERS.md) 与
[生产效果清单](region-threading/CANONICAL-BLOCK-ENTITY-C3A-NATIVE-EFFECT-INVENTORY.md) 定位了剩余的准入与
效果时序缺口。这些报告保留其历史红色结果。

生产现已具备[最小原版 Hopper 准入](region-threading/CANONICAL-BLOCK-ENTITY-C3A2-MINIMUM-NATIVE-ADMISSION.md)、
[自有调用提交](region-threading/CANONICAL-BLOCK-ENTITY-C3B-INVOCATION-EFFECT-BRIDGE.md)、
[canonical Native cursor](region-threading/CANONICAL-BLOCK-ENTITY-C3C-NATIVE-COORDINATOR.md)，以及
[熔炉集成进同一阶段](region-threading/CANONICAL-BLOCK-ENTITY-C4-UNIFIED-FURNACE.md)。
实际 Level 路径对 Native、Furnace 与完整 Serial Fence 使用一个 active batch/kernel/Ticket 集与 cursor。
Worker 前缀连续，在 Unknown 之前 join 并提交，并重新证明后缀。原版 cooling/disabled Hopper 与现有受保护
熔炉队列在混合单批次阶段中各自展示真实不同 Region overlap=2；未知 capability/回调/资源路径完全串行。
真实 level-end 效果保留其时序。全部原 C1 目标通过；完整 core 1314 与每个定向 worker/资源设置 248 通过，
零失败/错误/跳过。默认值、预算与原预期不变；C5/Create 与额外 MSPT 调优仍在此迁移之外。

[更早的 C4.1 生命周期与已安装服务器 soak 验证](region-threading/CANONICAL-BLOCK-ENTITY-C41-LIFECYCLE-AND-SOAK.md)
修复了清理 monitor 之前的生命周期自等待，并在 Native adapter 准备前发布同一阶段。完整 core 1328 与三个
定向 262 运行通过，保留原预期。真实已安装 Minecraft 1.21.1 运行包括 60 分钟 core 混合 soak、chunk 卸载/重载、
已保存世界新 JVM 重启与 10 分钟原始 Create 负载。在 85400 个已检查 tick/10322400 正文中，33 个清理快照显示
零 live 调用权限/批次；有界空闲 Native scope 字段单独验证。Create 变换保持保守串行，无 Adapter 扩张。
**单独的完整 GameTest 门仍未变绿：** 旧 Worker 合同预期失败，额外 all-slice 尝试在测试植物回调中崩溃。
这些结果与诊断 ERROR/FATAL 输出被保留并分类；这不是全绿兼容性或性能声明。该历史检查点在 GameTest 门未绿时停止。

[C4.2 GameTest 兼容与回归](region-threading/CANONICAL-BLOCK-ENTITY-C42-GAMETEST-COMPATIBILITY.md) 更新旧驱动
夹具，以验证真实 Native/Furnace 前缀、完整 Serial Fence、原正文计数与通知。两个完整未过滤并行 profile
（Persistent/资源开启 与 Fixed/关闭）各通过全部 176 个已注册 required GameTest，exit 0 且无崩溃。
现有植物生长回归在 bridge 关闭时也通过；方向崩溃来自测试土壤中无效的遮挡检查。opt-in
`:tests:runCanonicalGameTestServer` profile 启用 POI/实体/chunk 前置条件，并证明 25 chunk/13 BE/9 实体被重建，
原保存状态/ticket/UUID 完整。当前村民 POI 与 feature-flag 测试观察真实 Worker/整合包行为。POI JUnit 夹具中
异步参考写入竞态通过在比较文件前 join 两个 IO future 修复。完整 core 1328 与三个相关 329 运行通过，保留原
canonical 断言。framework XML 仍列出 4 个 Not Processed 条目并单独记录；报告保留所有失败尝试与 legacy
测试 Mod 诊断错误。生产源码/补丁/准入/默认值/预算/API 不变。该 C4.2 检查点在 C5/Create 协议工作、性能调优
或重复长时已安装 soak 之前停止。

[C6 运行时实现](region-threading/MOD-WORKER-RUNTIME-DESIGN.md) 增加 opt-in Linux/HotSpot21 VM 证据/访问服务、
窄 Core 闭包验证器与转换为现有 Claims/Lease/Kernel 路径的新鲜 manifest。
[最终 C6 验收](region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md) 为 **PASS**：完整未过滤 Fixed 与 Persistent
profile 各记录 170 通过、0 失败、10 逻辑跳过，Gradle/服务器退出码 0。四个 framework-only ID 在其单独重叠人口
中仍为 Not Processed。[独立生命周期证书](region-threading/LIFECYCLE-CERTIFICATION-2026-11-15.md) 在两个池上
通过真实 Level GC 直至停服退役；真实运行时 Mod 热卸载仍不支持且**未认证**。未知/周期路径与 Create 仍是完整
Serial Fence。默认保持关闭；这些结果既不建立通用整合包兼容性，也不建立已安装 canary 或 MSPT 改善。
[复现工具](tools/runtime-proof/README.md) 保留每个证据层的精确范围与输入。

已发布的 [C6.2 调查](region-threading/MOD-CLOSURE-LINKAGE-AND-WRITER-BOUNDARY.md) 与
[首个目标审计](region-threading/FIRST-TRUSTED-MOD-EXECUTION-CLOSURE.md) 仍是生产运行时实现之前的历史证据。
其零 Worker 与 missing-verifier 陈述描述的是更早检查点。

更早的 [C5.1–C5.5 Core 执行证明就绪](region-threading/CORE-MOD-EXECUTION-PROOF-READINESS.md) 在原 kernel Ticket
上增加独立绑定/生命周期拒绝门。它在 adapter 回调后重新检查精确 source 与注册；被拒工作仍是完整 Serial Fence，
不重放。该 C5 检查点没有正向 Mod 闭包后端或 adapter 声明到 Claims 的转换器。现有 Native/Furnace 权限保留。
报告把实际最终测试、现有资源预留覆盖与首个 Worker 启用 Dedicated Adapter 的仅设计前置条件分开。
最终 Core 1400/1400、三个相关 419/419 配置与两个完整并行 GameTest 套件各 177/177 required 通过，
每个完整套件保留四个 framework NotProcessed 条目。不由此得出 MSPT 声明。

更早的 [C5 通用 Mod Adapter 架构](region-threading/GENERAL-MOD-ADAPTER-ARCHITECTURE.md) 增加仅 Java 的申请 API、
服务器作用域注册/退役与一个附着于原 canonical 阶段的短生命周期会话。执行/资源/依赖/生命周期描述与 Dedicated
审计元数据使用现有注册事务与总 128 上限；v1 API major 与签名保持兼容。C5 没有通用 Worker proof 后端，
因此每个 C5 目标仍是完整 Serial Fence。Inspection 在其原串行 Permit 内、物理 join/必要提交后的实际 cursor
处运行，随后完整原 ticker 执行一次；声明永不铸造 Claims/Lease/Permit 或选择池/提交。清理与回调失败保留
恰好一次语义，并清除借用的 subject 与临时资源。现有 Native Hopper/Furnace proof 与授权保留。不含具体
Create/Mekanism/AE2 adapter、更广 Modded BE Worker 准入、默认/预算变更、Bootstrap 重写或 MSPT 优化。
更早报告记录其 C5 检查点测试：完整 core 1375/1375、三个相关 394/394 配置与两个未过滤完整并行 GameTest
profile 各 177/177 required 通过，exit 0/noCrash。真实已注册 Mod 请求让未知共享回调保持串行，同时 Native
overlap 2 保持；混合 Native/Furnace 在一个批次内 overlap 2/2。全部 1328 个 C4.2 与原 1244 个 JUnit 用例
重跑/通过。四个 framework Not Processed 条目与保留的失败 probe 仍明确存在，同时保留现有 adapter/Mixin
兼容检查。新 20 类 API 包仅依赖 JDK/注解即可编译；60 类 facade 不含运行时权限。C5 到此基础为止；
不增加已安装服务器或整 tick 性能声明。

诊断：`/neoforge regionthreading status` 打印每区域 MSPT/TPS、每个切片的 worker 与 fallback 计数、
边界更新与外部读计数，以及每次拒绝发生的原因。没有任何东西被静默丢弃。

Folia 1.21.1 源码对比、当前交互 API 与剩余所有权/传送工作见
[region-threading/FOLIA-REFERENCE.md](region-threading/FOLIA-REFERENCE.md)。逐事故对比与更广修复优先级见
[region-threading/RECENT-BUG-CLASSES.md](region-threading/RECENT-BUG-CLASSES.md)。保特性适配候选及其行为限制见
[region-threading/FOLIA-PRESERVING-ADAPTATIONS.md](region-threading/FOLIA-PRESERVING-ADAPTATIONS.md)。
最新有界崩溃/逻辑审计及其确认未解决的阻塞见
[region-threading/CRASH-AND-LOGIC-AUDIT.md](region-threading/CRASH-AND-LOGIC-AUDIT.md)。
实体/机器部分 tick 重放移除与显式 ticket 支持的 owner 完成合同见
[region-threading/CHUNK-OWNER-OPERATIONS.md](region-threading/CHUNK-OWNER-OPERATIONS.md)。
整体生物/物品拾取准入、守恒检查及其延迟时序合同见
[region-threading/MOB-ITEM-PICKUP.md](region-threading/MOB-ITEM-PICKUP.md)。已加载模组子世界 chunk 与 Sable
plot 间隙的 worker 查找兼容见 [region-threading/SABLE-LOADED-CHUNKS.md](region-threading/SABLE-LOADED-CHUNKS.md)。

## 构建

```
./gradlew --offline :neoforge:setup          # 把补丁应用到 projects/
./gradlew --offline build                    # 构建 loader
./gradlew --offline :tests:junitTest         # 当前注册的 JUnit 套件
./gradlew --offline :tests:runGameTestServer # 默认 GameTest profile，区别于 opt-in C6 验收
```

需要 JDK 21。仓库是补丁源码式 NeoForge 树：原版源码在 `projects/neoforge/src/main/java`（生成），
补丁集在 `patches/`，Forgia 运行时在 `src/main/java/net/neoforged/neoforge/server/threading`。

基准与 soak：

```
./gradlew --offline :tests:runGameTestServer -PserverBenchmark=true \
    -PgameTestFilter=benchmarkEntity -PserverBenchmarkZombies=1024
./gradlew --offline :tests:runSoakGameTestServer        # 6000 tick 生产 soak
./gradlew --offline :tests:runSoakGameTestServerReference
```

历史非服务基准使用低暂停收集器（`-XX:+UseZGC -XX:+ZGenerational`）；2 GiB 堆上的 G1 可能在该负载下掩盖效果。
C6 可选原生服务 profile 以 G1 与压缩指针验收；复现该证据时请保留其
[VM 限制](region-threading/PRODUCTION-CONFIGURATION.md)。更换收集器需要单独验证。

## 一页架构

- **区域**是固定、不重叠的 N×N chunk 网格（`regionThreadingFixedRegionChunks`）。每个区域有一个 lease；
  只有 lease 持有者可以 tick 其 chunk、实体、方块实体、刷怪与 POI。
- **服务器线程**构建计划、租出每个区域、每区域提交一个任务、在屏障等待并运行 handoff：延迟 section 更新、
  移除、准入、方块更新、传送门转换、边界更新与传播 fixpoint。
- **Worker** 在任务期间拥有其区域，自行枚举自己的实体、ticker、chunk 与 POI，而不是接收逐单元列表。
- **所有跨区内容都是延迟的**，绝不竞争：邻居更新、方块写入、读取、POI 查询、game event。每项都有有界队列、
  计数器与 fallback。
- **Fail closed**：任何无法证明安全的工作 —— 未知模组回调、没有 region-owned 存储的 level、无法租出的区域 ——
  都在服务器线程运行。

[增量运行时重构](region-threading/INCREMENTAL-RUNTIME-REFACTOR.md) 记录 chunk 阶段与诊断抽取、兼容边界、
依赖检查与验证限制。现有入口、执行权限与默认关闭准入不变。

更深入的笔记在代码旁（`src/main/java/net/neoforged/neoforge/server/threading/region/*.java`），
以及 `docs/modules/` 的模块实现说明中；每个里程碑的设计与证据记录在 `region-threading/` 档案里。

## 许可证与来源

Forgia 是 NeoForge（LGPL-2.1）与早期 Forgia 移植仓库的分支；此处新增的区域化运行时使用同一许可证。
上游 NeoForge 署名保留在源码头与（存在时的）[NOTICE](NOTICE.md) 中。本仓库不再跟踪已停止更新的上游
Forgia 项目。
