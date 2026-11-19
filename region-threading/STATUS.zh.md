# Forgia 状态

[English](STATUS.md) | 中文

下表中的历史负载均在 4 核/8 线程机器、JDK 21、分代 ZGC、固定 3×3 chunk 区域（除非另述）、4 个 worker、
200 tick 预热 + 600 tick 测量、两次重复下测得。“关闭”指同一构建关闭区域线程。这些不是新的 C6/canary
性能结论；已验收的可选服务配置使用其单独记录的 G1/压缩指针配置。

`.107` 通用扩展另提供 [受审计原 ticker API](AUDITED-BLOCK-ENTITY-TICK-API.md)：显式安装的受审计 adapter
通过同一 registrar 提供实现/分支/资源合同，核心不保存 Mod 类名或专用兼容逻辑。
[Create 0.2.0](../adapters/create/README.md) 是首个消费者，范围为稳态传动/离合器/动力转向器；
原始 C5 request-only 接口仍保持完整 serial fence。结果和未覆盖项见[本轮方案](CREATE-OPTIMIZATION-PLAN.md)。
这条新路线是明确的 trusted-adapter 合同，不将类指纹表述为自动语义证明。

## 10 分钟 32 Fake Player 全并行 vs 原版串行耐久测试（`FullParallelSoakGameTests`）

使用 [`FullParallelSoakGameTests.java`](../tests/src/main/java/net/neoforged/neoforge/debug/level/FullParallelSoakGameTests.java) 在同一种子（`4242424242`）下完成了 600 秒对照：

- **Forgia 全并行（`DYNAMIC_CLUSTER`，7 Workers，全开关开启）**：`11,873` ticks，`19.79 TPS`，P50 `10.78 ms`、Mean `13.20 ms`、P95 `27.75 ms`、P99 `45.75 ms`、Max `1026.41 ms`，GC `52 次 / 914 ms`，结束堆 `1,278 MiB`，`entityTicksOnWorkers = 102,920`，**`ownershipViolations = 0`**。
- **原版串行（`Bridge = false`）**：`11,859` ticks，`19.76 TPS`，P50 `11.61 ms`、Mean `12.37 ms`、P95 `25.03 ms`、P99 `37.78 ms`、Max `1110.14 ms`，GC `63 次 / 1,786 ms`，结束堆 `1,543 MiB`。
- **优于原版串行**：P50 MSPT（`-7.1%`）、Max MSPT（`-7.5%`）、GC 耗时（`-48.8%`）、结束堆内存（`-17.1%`）。
- **不如原版串行 / 待优化**：
  1. 轻负载下 Mean（`+6.7%`）、P95（`+10.9%`）、P99（`+21.1%`）MSPT 偏高（屏障与协调固定开销大于单核未饱和收益）；
  2. 方块实体与计划刻回退占比高（`fallbackBlockEntityTicks = 2,166,896`、`fallbackScheduledTicks = 13,565`）；
  3. 玩家 Tick 因存在未声明模组（`UNKNOWN_MOD:add_pack_finders_test`）全部回退主线程；
  4. 实体主线程占比仍偏高（`serverThreadOnlyEntityTicks = 379,968`），且本轮测试脚本的攻击判定（`attacks = 0`）与岛合并/拆分触发（`merges = 0`）有待加强。

## 测量过的性能工作与运行时命令 —— 有界实现

[性能报告](PERFORMANCE-OPTIMIZATION-REPORT.md)、[受控测量](PERFORMANCE-BENCHMARKS.md)、
[回归分析](PERFORMANCE-REGRESSION-ANALYSIS.md) 与 [运行时命令指南](RUNTIME-OBSERVABILITY.md) 记录了本轮工程会话。
它移除了 deadline 之前的 drain 提交，只在未执行的 canonical 前缀内复用熔炉 catalog lookahead，
保留新鲜的 proof/fence/alias 边界，并新增原生 tick 历史、区域排名、Worker/池计数与 `/forgia` 管理命令。
固定池关闭时会物理 join 其原始 Thread；历史被限制在拥有它的服务器线程内，并持有弱服务器标识。

等价的已安装 64 熔炉夹具在 14 个 JVM/28 个窗口上验证了完整的 before/after NBT/产出相等。
优化后的 worker 路径四个未采样均值窗口从 10.28–10.43 ms 降到 2.34–3.09 ms；分配从约 253 降到 19 MiB/s。
在这个轻负载上，原版 NeoForge 与 Forgia 串行仍明显更快。这是消除一项已测回归，不是“普遍快于原版”或
整合包结论。原始失败记录仍保留，并与后续成功尝试明确区分。精确的最终验证与源码/产物回执在性能报告中；
冻结的 C6/生命周期证书不变。

最终源码验证包括 Fixed 与 Persistent 各自独立通过 1,424 项 JUnit、每个 profile 四个原版 Mod GameTest，
以及两者实际停服 Level GC 认证。选定的 15 批次 Fixed GameTest 通过；Persistent 尝试保留 13/15 与 14/15
结果（chunk 加载/超时前置条件）。稍后一次未监控的 6000 tick soak 也未通过一条 live-bat 区域映射不变量；
其计时被排除在通过性比较之外。这使当前混合世界正确性门仍处于开启状态。完整命令、真实退出码、源码身份、
测量与失败证据都绑定在[会话回执](PERFORMANCE-SESSION-RECEIPT.json)中。

[生成](CHUNK-GENERATION-PARALLELISM-AUDIT.md)、[加载](CHUNK-LOADING-PARALLELISM-AUDIT.md) 与
[玩家执行](PLAYER-WORKER-EXECUTION-AUDIT.md) 都有明确的安全矩阵/边界。没有声明新的生成、重建或玩家 tick 准入。
现有原版 noise/biome/I/O future 与玩家快照查询被与新实现的工作区分开。未知回调/Create 仍串行。
区域指标明确披露遗漏的 canonical BE/串行 fallback 覆盖与不可用值；不引入替代调度器、线程池、
Ticket/资源权限、效果队列或 Mod 热卸载支持。

## 受控 canary 准备 —— 源码保留；已安装服务器门仍未通过

[生产就绪报告](PRODUCTION-READINESS.md)、[完整配置矩阵](PRODUCTION-CONFIGURATION.md)、
[canary/回滚流程](CONTROLLED-CANARY.md) 与 [只读审计工具/模板](../tools/production-readiness/README.md)
覆盖首次受控部署。一次全新 fetch 使 `origin/main` 停在
`a45a3bddf595dde0a4e390f4fdaf05fb2bf832a1`。全部 1955 个已认证生产文件匹配指纹
`f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787`；123 个 C6 与 372 个生命周期证据
引用在本地校验通过，两个可选运行时产物 pin 也匹配。[机器复审](PRODUCTION-READINESS-AUDIT.json)
记录这些检查，但不声明新的运行时测试。

矩阵覆盖全部 24 个 TOML 键（16 个默认 false 的开关）、编译期预算、原生服务限制、proof/fence 门与
退役/恢复。Fixed 与 Persistent 模板保留精确的 C6 值；另有显式串行模板支撑基线与回滚。
队列 drain 的 Worker 开关不是 tick 切片总开关；实例/世界配置优先级与 native agent 移除需要停服、
新 JVM 回滚。本次准备未执行已安装 canary 包、多人/长时 soak、恢复演练或硬杀负载。这些仍是上线门。
C6/生命周期证书与生产默认值、权限和准入不变。未发生部署、C7 或新的 Worker 启用 Adapter。

## 生命周期认证 —— 真实 Level GC 已认证；运行时 Mod 热卸载未认证

这是与下方冻结 C6 验收分开的结果。[生命周期报告](LIFECYCLE-CERTIFICATION-2026-11-15.md)、
[机器回执](LIFECYCLE-CERTIFICATION-2026-11-15.json) 与 [所有权/转换审计](LIFECYCLE-AUDIT.md) 记录：

- **真实 Level GC：已认证**，使用现有 Fixed 与 Persistent 池。两者都走原始 Mod Worker 路径和正常
  服务器 Level 卸载/关闭/停机。全部 28 个被跟踪引用（包括三个实际 Level）清除并入队；四次实际回收
  给出三次连续完整观察。关闭引擎与六个实际访问回调保持强可达；每个 profile 有十二个外部回调读写检查
  被拒绝。
- **运行时 Mod 热卸载：未认证。** 已安装的 FML 4.0.42/ModLauncher 11.0.5 没有受支持的运行时
  Mod 卸载/重载边界。同一原始 Mod 容器与定义类加载器在服务器退役后仍加载。四个真实热卸载/重载场景
  均为 Not Processed，从未作为通过覆盖。
- 新的确定性组件竞态覆盖排队/重绑/运行/释放工作、生产卸载回调、重复卸载、清理期间停机，以及每个
  保留的旧回调在零禁止状态变更下尝试。精确的选定回归计数与真实退出码在单独回执中。

生产代码、native/verified-Core 产物、canonical profile 定义与两份 C6 文档均不变；全部 123 个冻结回执
引用校验通过。没有 C6 证据失效。这些 opt-in 测试、诊断与文档变更不需要也未运行另一对完整未过滤 C6
profile。原始预启动、编译、完整性拒绝与失败 GC 证据仍保留，包括按堆识别的观察者 Thread/启动 lambda
保留路径。停服图已认证；live-server 维度分离与 Mod 类卸载没有。未发生生产部署、第二个 Worker Adapter、
兼容性扩张或 C7。

## C6 验收 —— 完整未过滤 Persistent 与 Fixed profile 通过

`C6 FINAL ARCHITECTURE ACCEPTANCE: PASS`

[最终验收报告](C6-FINAL-ACCEPTANCE-2026-11-15.md) 与 [机器回执](C6-FINAL-ACCEPTANCE-2026-11-15.json)
记录了两个完整 profile：干净 `main`、源码 HEAD `afa7e4bc781257a756a3d0f6040cc64eebc84d62`。
Persistent 在 Fixed 开始前完成；两者都自然完成保存、报告与服务器关闭。各自实际 Gradle 与服务器退出码为 0。

| 完整 GameTest profile | 通过 | 失败 | 跳过 | Not Processed | Aborted |
|---|---:|---:|---:|---:|---:|
| Persistent — 180 个函数 / 74 批次 |170|0|10|0|0|
| Fixed — 180 个函数 / 74 批次 |170|0|10|0|0|

重叠的 framework 人口独立统计为每个 profile **168 通过 / 0 失败 / 10 跳过 / 4 Not Processed / 0 Aborted**。
十个未改动的禁用 soak/benchmark/hard-kill 负载与四个 framework-only 手工/注册/事件 ID 仍不计入通过。
原始 tracker 的 180 成功、framework 178 成功与 234 行重复 XML 都不是验收通过总数。精确 ID 与诊断在回执中。
两个 profile 保持相同的完整批次成员、相同的测试定义哈希与既有的 failure-injection-last 不变量；
两者资源都开启，唯一配置差异是 persistent Workers。

生产与测试夹具均未改动。生产 revision `0ede20061`、测试源码 revision `39352097f` 与生产指纹
`f770fde7f5440e8859f51015b12d39f492eaf5b975df62b580137fb65b706787` 不变。28 个 VM 命令步骤
（18 个运行时控制）、419 例 Java 权限基线、3 个弱 owner/默认关闭测试、11 个相关原版 Mod 用例及其
148 正文/计数增量（72 Worker/76 Serial，重叠 2）仍然有效；它们未单独重跑。历史失败/中止 profile 与
新的 observer-only 预启动失败均保留。最终原始证据：`tmp/c6-profile-pair-20261115-2205/`。

在冻结的 C6 检查点，整服真实 Level GC 与独立运行时 Mod 热卸载尚未验证。上面的独立生命周期结果现在
认证停服真实 Level GC；Mod 热卸载仍不支持。Level 清除/停机、绑定预检、回调保留单测与 VM 组件保持
各自范围。Core 仍是唯一 coordinator/Kernel/Ticket/Claims/reservation/Lease/Scope/pool 权限；
物理 join 与有序清理、未知/Create Serial Fence、默认关闭服务与 Native/Furnace/v1 准入都不变。
未发生生产部署、第二个 Worker 启用 Mod Adapter 或 C7。

## 历史：可选 Mod 运行时实现检查点 —— C6 验收尚未完成

[运行时设计与检查点](MOD-WORKER-RUNTIME-DESIGN.md) 现在包含生产 Core 代码、可选的 Linux/JDK21
bootstrap/JVMTI 服务与仓库自有的 opt-in 测试工具。实际安装的隐藏代码从 VM 读取。一个小的语义闭包被
转换为原始 Claims、reservation、Lease、Scope 与 Kernel 权限；Mod 身份或 Adapter 声明都不能认证它。
未知与周期路径保持完整 Serial Fence。没有可选服务启动时，Mod ticker 保持串行。

早先完整真实 Mod 检查点展示了两个实际 Region Worker/重叠 2、十条被拒绝的外部写入路径，以及失败清理后
不重放的新 Worker 执行。后续多批次试点还检查原始 Ticket/Claims/Lease 身份与退役，但在最后批次完成前
被中止，不构成完整通过。最新边界修复了一个可单独复现的嵌套回调绕过，并冻结精确 Core BlockPos 存储，
而不是监视所有坐标读取。其组件控制不能替代一次新的真实 Mod 运行。源码/结果哈希、真实退出码与早期候选
边界见[机器回执](MOD-WORKER-RUNTIME-CHECKPOINT.json)。

**这是实现检查点，不是已完成的 C6 架构验收。** 剩余工作包括最终 revision 的 Mod 重测、排队/释放/卸载/
重绑/别名用例、弱 owner 生命周期控制、两个完整未过滤 GameTest profile 与最终 Mod 架构验收。现有默认开关、
阶段预算、注册上限与 Native/Furnace 准入保持。不声明第二个 Worker Adapter/C7、已安装服务器验证或 MSPT 改善。
[复现说明](../tools/runtime-proof/README.md)需要本地原始上游 jar，并保留私有原始证据。

## 历史：早期 C6.2 调查检查点 —— 可选运行时实现之前

下节记录已发布基线 `2fe7ff74a`；其 missing-verifier/零 Worker 状态对当前代码已被上面的实现检查点取代。

[C6.2 续作](MOD-CLOSURE-LINKAGE-AND-WRITER-BOUNDARY.md) 开发了一个私有的原 JVM factory linkage 实验，
并在真实 LevelChunk 绑定内观察精确的隐藏 ticker。原始 piq_fc_arcade0.31.0-alpha.61 对仍完全串行；
最终完整 profile 观察到 84/84/84 与 82/82/82 的 body/counter/linkage 总数，workers 为 0。
运行时写入方法快照在受控负向 actor 执行前与已审计原文匹配。当原始 canonical Serial Permit 暂停时，
该独立测试 actor 没有 Permit、Scope 或 Worker 身份，却仍调用一次公开原方法；随后的 canonical body
再增加一次更新，总计 2。这确立了缺失的 writer 参与前置条件，而不是 reserved-Lease 失败或生产 Worker 授权。
它不声明存在上游异步写入者。

C6.1 原始源码调查已交付。C6.2 **仍缺少生产 Core Closure Verifier、Trusted Manifest 与完整的独立
writer 排除**。C6.3 Claims 转换、C6.4 Mod Worker 与 C6.5 首个 Mod 架构验证未完成。描述性函数来源记录、
运行时快照或 Dedicated 断言都不授权执行。所有 Canonical Mod ticker 仍串行；有意的测试专用外部方法调用
明确在 Core 权限之外。

新的实际回归：Core 1400/1400，全部 1400 用例结果/重数与旧 XML 一致；84 canonical 与 Native/Furnace
overlap 2/2 保持。两个完整未过滤私有 observer+原版 Mod GameTest profile 各通过 179/179 required，
exit 0/noCrash。其 233 个分组条目/181 个唯一 ID 保留 4 个 NotProcessed；移除两个新私有审计 ID 后，
全部 231 个旧 framework 行与之前 177 required 基线一致。标准随仓库发布的测试不变。早先三个 419 配置/
legacy adapter 结果保留为历史不变基线证据，不是新的续作运行。最终默认编译移除私有类。

[首个目标审计](FIRST-TRUSTED-MOD-EXECUTION-CLOSURE.md) 保留更早的隐藏定义反例、备选候选与初始串行/
回归结果。新的 agent/JDK bootstrap 桥、审计测试、jar 与原始证据仍在本地；生产 API/构建/补丁/准入/默认值/
预算或现有测试字节均未改变。未进行已安装服务器验证、第二个 Adapter/C7 或 MSPT 声明。

## 历史：Core 控制的 Mod 执行证明 —— C5.1–C5.5

[Core proof 就绪报告](CORE-MOD-EXECUTION-PROOF-READINESS.md) 审计现有权限链，并在每个原始调用 Ticket
上增加 Core 拒绝门。inspection 与每个 validator 都留在实际 canonical Serial Fence 内；Core 在回调后
独立重捕获精确 source/delegate/Level 绑定与注册生命周期。pending/failed 检查不能成功，也不能被提升为
Worker 权限；退役遵循原 kernel。一次审计后冻结竞态在其小的发布锁修正前被复现。每个实际注册的测试 Mod
正文都断言原 kernel 拒绝。

在 C5 检查点**没有正向 Mod 闭包后端**：声明、CURRENT、Dedicated 元数据与复制的资源身份永远不会变成
Claims、Reservation 或 Lease。现有 Native/Furnace proof 与实际所有权仍是唯一 Worker 路径。任意 JVM 闭包、
动态/共享 handler、异步写入者与在途 Mod 版本保护未认证。可以开发首个串行 Dedicated Adapter；
Worker 启用的 Dedicated Adapter 仍需要窄的独立闭包与 manifest/转换实现。最终矩阵与精确的已实现/仅设计
边界见报告。不含 C6、具体 Mod Adapter、Bootstrap 重写、已安装服务器 soak 或 MSPT 优化。
最终 Core 1400/1400 与三个相关 419/419 配置通过；两个完整未过滤并行 GameTest 套件各 177/177 required
通过，exit 0/noCrash。全部 1375 个 C5 基线用例与原 1328/1244 用例实际重跑/通过；每配置 84 个原 canonical
与 Native/Furnace overlap 2/2 保持。每个完整 GameTest XML 保持 231 分组条目/179 唯一 ID/4 NotProcessed，
从不称为“全部执行”。现有 Create 16+unknown 1、MobSpawnController 25+unknown 1 与 unknown Furnace 1 通过；
API 60 类 facade/20 类纯包与全部基线公开二进制描述符已验证。红色冻结/夹具/解析器证据保留。

## 历史：通用 Mod Adapter —— C5 保守申请基础

[C5 架构与验证](GENERAL-MOD-ADAPTER-ARCHITECTURE.md) 增加平台中立的申请 API、服务器拥有的注册/生命周期
管理与一个附着于原已发布 canonical 批次的短生命周期申请会话。`BlockEntityAdapter`、`BlockEntityAdapters`
与 `DedicatedBlockEntityAdapter` 描述精确目标 id、执行申请、资源、当前依赖与审计出处。新可选方法/能力
保留 v1 API major 1，并共用原 128 注册上限与 id/type 事务。不新增具体 Dedicated Adapter、池、调度器、
Ticket、Permit、Lease 或效果队列。

**C5 注册目标仍是完整 Serial Fence：没有通用 Worker proof 后端。** inspection 在物理 join/必要提交后、
于现有串行 Permit 内、实际 cursor 处运行，随后完整原 ticker 执行一次。未知资源/provider 不探测；
声明/CURRENT/audit 元数据永不变成 Claims 或权限。现有 identity planning 仅用于诊断。回调与所有临时
subject/资源引用在失败、失效、注销与阶段关闭时退役；清理失败仍可见，且不跳过其他清理。Dedicated 审计在
registry 锁外运行，回调后重复 live/identity/共享预算校验。生命周期锁顺序修正是带红/绿证据的独立生产提交。
未注册的 Native Hopper/Furnace 路径保留全部原 proof、准入、物理 join 与提交行为。最终完整 core 1375/1375
与三个相关 394/394 配置通过，包括全部 1328 个 C4.2 用例/原 1244 与每配置 84 个原 canonical。
两个未过滤完整并行 GameTest 各通过 177/177 required，exit 0/noCrash；framework XML 231 条目/179 唯一 ID/
4 NotProcessed 仍明确。真实 Mod 启动注册让未知共享回调保持串行，同时 Native overlap 2 保持；
混合 JUnit Native/Furnace 在一个批次内 overlap 2/2。Legacy Create 16+unknown 1、MobSpawnController 25+
unknown 1 与 unknown Furnace 1 也通过。所有失败原型、一次真实 probe 崩溃与稀疏随机采样夹具失败均保留；
最终完整游戏使用完整隔离 section，同时保留每个原 worker/所有权断言。纯 C5 API 20 类无需 Minecraft 即可编译；
60 类 facade 字节与编译输出一致且不含 runtime。未改动准入/默认值/预算，未重写 Bootstrap、未集成 C6/Create、
未做 MSPT 优化。C5 到此为止；命令/原始证据与手工/framework/已安装服务器覆盖限制见报告。

## 历史：C4.2 必需 GameTest 门检查点

[C4.2 兼容与回归](CANONICAL-BLOCK-ENTITY-C42-GAMETEST-COMPATIBILITY.md) 通过实际合同/夹具验证解决了
四个 C4.1 required 失败与 all-slice 植物崩溃。两个完整未过滤并行 profile（Persistent/资源开启 与
Fixed/关闭）各通过 176/176 required GameTest，真实 Gradle exit 0，无崩溃报告。framework XML 另有
230 个用例条目（178 个唯一测试 ID，分组条目重复）、226 通过与 4 Not Processed；旧 dumper 的空可选标签
被保留，不称为已执行通过。手工/client 与 legacy 仅日志测试 Mod 诊断仍是明确限制。

自定义 BE 仍是完整服务器 Serial Fence，带恰好一次跨 Region 传输与真实 Native 前缀/后缀工作。
单熔炉仍串行；两个独立原版燃烧熔炉运行实际细粒度 Worker 正文、熔炼一次并提交通知。同一 Region 的信标
不会污染无关前缀。观察者记录每正文线程、canonical Fence 顺序与真实生产准入后的燃烧/烹饪/库存守恒。
没有测试授予权限。植物夹具支持显式方向、保留错误土壤/方向检查，并在 bridge 关闭时仍通过原版 Cactus
回调存活/生长。Regionized POI 村民被精确实体 Post 事件观察到，其真实 HOME/JOB_SITE 记忆在 Worker 上；
feature flag 同时检查实际 all-flags GameTest Level 与 live 选定整合包请求。

显式 `:tests:runCanonicalGameTestServer` profile 提供 POI/实体/chunk/BE/Furnace 前置条件。真实磁盘重载
重建 25 chunk/13 BE/9 实体，并保留全部旧 block/NBT/UUID/position/POI/ticket/Region 断言。一个相关 JUnit
格式夹具未 join 的引用 IO 被修正，不改变其完整比较断言；全部 1328 个 C4.1 用例与三个扩展 329 运行通过，
每配置 84 canonical。每次失败尝试、命令、退出码、XML 与原 C4.1 崩溃均保留。所有生产 Java/补丁/API/准入/
默认值/预算/Adapter 保持逐字节不变，因此没有生产修复或长时 soak 重跑。C4.2 到此为止；无 C5/Create Adapter/
MSPT 工作或通用整合包认证。

## 历史：C4.1 生命周期修复与已安装 soak 检查点 —— GameTest 门未变绿

[C4.1 生命周期与 soak 证据](CANONICAL-BLOCK-ENTITY-C41-LIFECYCLE-AND-SOAK.md) 修复了在获取清理引擎 monitor
之前的服务器/Worker 自等待，并用同一已发布阶段批次保护 Native prepare 回调。每个生产修复独立提交，
带真实失败先行 probe；现有测试预期、授权、队列、API、默认值、预算保持。十四个新调用用例覆盖混合失败/恢复、
实际池关闭/拒绝、chunk 绑定替换/pending 成员、Region 退役/合并、完整阶段清理与 200 个连续混合阶段。
完整 core 1328 与每个其他 worker/资源设置 262 通过，0 失败/错误/跳过；全部 1244 原用例与 C1 目标重跑/通过。

精确源码的已安装 Minecraft 1.21.1/G1/4-Worker 运行完成 60 分钟混合 core soak 与 10 分钟原始 Create 负载，
外加真实 chunk holder 卸载/重载与已保存世界新 JVM 重启。85400 个已检查活跃 tick/10322400 正文、
33 个静止快照与 14 个直接堆字段审计未保留任何调用权限/批次；RegionState 计数与实际 regionizer 集合一致，
Worker 数保持 4。四个空闲 Native scope 对象/128B 是有界可复用缓存，字段为 inactive/null 引用，不是 live 权限。
主 core 墙钟 3832.426 s；Create 长运行墙钟 780.320 s。变换后的 Create 在 UNVERIFIED_RUNTIME 下完全串行，
无 Create adapter/协议认证。无未捕获已安装 tick 异常；18 条 ERROR/FATAL 与 9 条原版 perf dummy Level 测试报告一致，
73 条 WARN 已分类并保留。计时是进度数据，不是加速声明。

**完整 GameTest 仍未变绿。** 两次完成的 BE 聚焦 framework 运行各有 4 个 required 失败（旧的声明 source/
单熔炉/区域 fallback Worker 合同与保存/重载配置）；额外的 all-slice 运行在测试支撑方块的 Cactus 方向回调中崩溃。
原始测试被保留，另有原始 XML/摘要/崩溃证据；没有为制造绿色结果而关闭并行开关。C4.1 实现/soak 工作在这个
明确未完成的 GameTest 门处停止；无 C5/C6/MSPT 扩张。

## 历史：C4 单阶段 Native/Furnace 集成检查点

[C4 实现与验证](CANONICAL-BLOCK-ENTITY-C4-UNIFIED-FURNACE.md) 让实际 Level 路径在 Furnace Waves 关闭或开启时
都经过一个阶段 Coordinator/active batch/kernel/Ticket 集/cursor。原始 Native 与受保护 Furnace 正文在各自
原有粗/细粒度授权下使用独立连续前缀；Unknown/拒绝/绑定变化形成完整 join/commit 的 Serial Fence，随后做
新鲜后缀检查。原熔炉审计、整阶段别名、资源预算、依赖/validator/all-or-none 预留、完整 ticker 与 canonical
通知保持。每阶段 mailbox 回调在服务器上、原 lease 下、新鲜 proof 之前运行；真实 level-end 时序、RNG 与
操作状态保留。完整 core 1314/1314 与三个定向 248/248 运行通过，0 失败/错误/跳过；全部 1244 原用例实际重跑，
全部原 C1 目标通过。混合单批次测试显示实际不同 Region Native overlap 2 与 Furnace overlap 2、一个观察到的
active batch 与原 Ticket；整个夹具状态与独立串行 oracle 相等。未知 capability/共享 handler/Generic 回调保持串行。
Native 仅限精确 cooling/disabled Hopper 与原 PrimaryLevelData 时钟；Furnace 仍是先前受保护队列。默认值/预算/
API 不变，保留 legacy 内部 explicit/indexed 合同桥；无 C5/Create/MSPT 扩张。

## 历史：C3-C Native cursor 检查点

[C3-C Native Coordinator](CANONICAL-BLOCK-ENTITY-C3C-NATIVE-COORDINATOR.md) 拥有一个阶段 batch/kernel/Ticket 集
与 canonical cursor。它只在全部 fresh/reservation/v1 owner 检查后派发已证明的连续前缀，在完整 Serial Fence
前物理 join/commit，并重新证明未开始的后缀。一个参与的服务器工作准入边界阻止无关 Region/GlobalRegion 工作
在阶段期间启动；这不授予世界权限。原 Region lease 在 join/commit 期间保持持有；阶段 RNG 跨 Fence 存活。
全部原 C1 目标预期通过。完整 core 1309 与三个定向 243 运行通过，0 失败/错误/跳过；全部 1244 原用例重执行。
七个新 coordinator 用例加真实 Native overlap 2 通过。Furnace Waves 开启仍在 C4 前使用其受保护桥；
默认值/预算/API/Create 不变。

## 历史：C3-B 调用效果检查点

[C3-B 最小效果桥](CANONICAL-BLOCK-ENTITY-C3B-INVOCATION-EFFECT-BRIDGE.md) 在原 Ticket 上以 generation/ordinal/
effect index 记录续作，在持有原 Region lease 时按 canonical 顺序物理 join 并提交。实际 level-end 实体/级联
producer 保留 Post-before-drain 时序。未知正文即使 v1 ADMITTED 为 true 仍串行；v1 检查只收窄 core 已证明队列。
五个新真实路径用例与先前 Native 可见性通过。完整 core 1302 = 1299 通过/3 个启用 C3-C 目标，0 错误/跳过；
全部 1244 原用例实际重跑/通过。其他三个配置各 236 = 233 通过/3 目标。C3-C cursor 与 C4 仍待完成；
默认值/预算/API/操作队列/RNG/通知顺序不变。

## 历史：C3-A2 最小 Native 子集检查点

[C3-A2 最小 Native 准入](CANONICAL-BLOCK-ENTITY-C3A2-MINIMUM-NATIVE-ADMISSION.md) 为精确 Hopper cooldown>1
或 disabled 分支增加生产级原函数/绑定/运行时/新鲜分支检查。两个队列都在实际 Level 路径上以真实不同 Region
Worker overlap=2 执行原正文；活跃未知 provider、共享 handler 与替换函数完全串行。原 C1 断言保持；
Native 夹具现在使用原 Hopper ticker 与准入后观察。完整 core 1297：1293 通过/4 个后阶段目标失败，
0 错误/跳过，全部 1244 原用例通过。Fixed/Persistent × Resources 关/开实际重跑，包括 12 个 Native 准入/
库存调用通过。C3-B/C3-C 与 C4 仍待完成；尚无统一 cursor 或调用效果桥。默认值、预算、API 与 Create 不变。

## 历史：C3-A 清单检查点 —— 历史阻塞 proof 门

[C2 实现与验证记录](CANONICAL-BLOCK-ENTITY-C2-COLLECTION.md) 在 pending merge 与新鲜 onLoad 后捕获实际
Level ticker 列表，只在成员锁下复制引用，并为每个条目分配一个阶段 ordinal。缓存的创建序列不再定义该
Level 路径的顺序。Native canonical fallback 现在按 ordinal 合并，最初不合格的后缀条目仍可进行新鲜服务器
资格检查。C1 八个失败中的五个被修复；三个 native fence/前缀断言仍启用。八个新收集用例通过。
原 Native/Furnace/Adapter/kernel/资源/生命周期回归在真实 fixed/persistent × resources off/on 上重跑，
完整 core 结果在报告中。尚无统一 Coordinator；两个执行驱动与其旧 indexed/explicit helper 桥都保留。
准入、开关与预算不变。[C3 native 效果桥审计](CANONICAL-BLOCK-ENTITY-C3-NATIVE-BLOCKERS.md) 复现了
level-end 可见性缺口。后续 [C3-A 生产清单](CANONICAL-BLOCK-ENTITY-C3A-NATIVE-EFFECT-INVENTORY.md)
追踪 16 个效果类/31 个源文件，将调用效果与后续阶段时序分离，并通过真实 capability dispatch 测试原 Hopper
ticker。一个未审计 provider 在 native Worker 上执行；非修改共享 handler probe 确认两个不同 Region 在同一
根上重叠。仍缺少完整的正文前回调/根证明。停在 C3-A；未进入 C3-B/C3-C、C4 与 C5。
六个新调用产生 5 通过/1 失败；完整 core 重跑 1291 = 1286 通过/5 目标失败，0 错误/跳过，全部 1244 原用例通过。
同样 5 个失败在 fixed/persistent × resource off/on 复现；原 C1 断言不变，其中 C2 修复 8 个中的 5 个，3 个仍红。
四个新的保留桥保护覆盖真实跨 Region Hopper 重叠、任务失败/恰好一次/level-end 顺序、scheduled mutation/事件
时序与 Region RNG 连续性。未改生产代码、默认值、预算或准入；未实现新桥或 Coordinator。无兼容性、性能或
已安装服务器声明。

## Canonical Block Entity Coordinator —— C1 行为测试（历史基线）

[C1 执行与失败记录](CANONICAL-BLOCK-ENTITY-C1-TESTS.md) 使用实际 Level 串行循环、原 native/Furnace 驱动、
完整原熔炉正文、受控固定派发与带 latch 的真实 persistent worker，增加 31 个行为用例。每个
fixed/persistent × resource-off/on 配置有 23 个新用例通过、8 个目标合同失败：跨 chunk/全局 fence 顺序、
外部插入、拒绝后前缀、混合 native 顺序与由 fence 改变的资格。这些失败在普通 JUnit 任务中仍启用；
这是为下一次迁移记录的红色基线，不是全通过 coordinator 结果。22 个受保护用例覆盖实际前缀 join/commit、
新鲜拒绝、v1 回调、正文/fence/commit 失败、中断/取消与卸载/停机清理；混合 Furnace 目标也通过。
生产/API/补丁/构建/配置/准入与现有测试不变。无新 coordinator、Create 支持、已安装服务器或性能声明。

## Canonical Block Entity Coordinator —— C0 设计阶段

[源码地图、接口设计、迁移与测试计划](CANONICAL-BLOCK-ENTITY-COORDINATOR.md) 基于 `233905042`。
它识别出不同的 native group/fallback 与 Furnace wave/commit 驱动、混合序列出处、缺少 fence 后 v1 准备保证
与 native 效果队列顺序限制。提案使用真实 level ticker-list 顺序、在现有 ticket/permit/guard 之上的共享
coordinator、连续 worker 前缀与带新鲜后缀审计的完整 serial fence。Furnace 准备、资源所有权与通知提交仍受保护；
Create 仍独立。该 C0 轮只改三个文档文件，无新运行时/测试结果。Resource Ownership MSPT 调优保持冻结；
C1 现在先建立行为测试，再做新的生产路由。

## Modded 方块实体多线程适配

该阶段始于基于 `4ea694316` 的[源码架构调查](MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE.md)。
[首次执行合同迁移](BLOCK-ENTITY-EXECUTION-CONTRACT.md) 现在让 native adapter tick 与受保护 Furnace Waves
共享内部 batch/ticket/permit 实现。Region guard 将粗粒度所有权排除在从属资源正文之外，要求真实 Scope 返回，
并在 canonical 提交前于 join 后封印；失败/退役 ticket 不能重放。现有准备、回调时序、派发、开关、串行 fallback
与提交实现保持。没有新 adapter 注册协议、BE 准入、capability/库存权限或多 home 支持。
Core 1244、普通池 resource-on 181、persistent 199、实际未知 Furnace Mixin 1、独立 Create 16 与未知 Create
Mixin 1 通过，另有格式/许可证与 API facade 检查。这些是变换 JVM 正确性测试，不是新的已安装服务器或性能测量。
额外 Resource Ownership MSPT 调优冻结；单一 canonical 通用 coordinator 仍是后续迁移。

## 通用方块实体资源所有权

`.57-beta` 的[第一阶段资源所有权基础](GENERAL-BLOCK-ENTITY-RESOURCE-OWNERSHIP.md) 增加对象身份、有界
READ/WRITE claims、非阻塞波次预留与从属于现有 Region 排除的一次性 scope。熔炉波次是首个已审计消费者；
独立的 `enableRegionThreadingBlockEntityResources` 开关默认仍为 false。这不接纳任意容器、漏斗、capability
或 Create 回调。[`.58` 性能续作](BLOCK-ENTITY-RESOURCE-OWNERSHIP-OPTIMIZATION.md) 只在新鲜已审计准备内
共享不可变 READ 足迹，将紧凑执行波次与图诊断分离，并避免每 lease 的权限表复制。完整机制成对 p50/p95 与
分配记录包含新鲜复核；它们不是服务器 MSPT 或整服加速。
后续[尾延迟调查与真实 `.58` 已安装服务器 A/B](BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md) 使用固定
2 GiB G1 堆、资源开/关且两者都启用 waves，以及 1/2/4/8 workers；这些定义与开篇段落及下方表格中的历史
测量分开。它完成 36 对资源开/关与两次独立长窗口反向顺序重复。资源开启在 35/36 短矩阵对中提高平均 MSPT；
两次长重复也都回归，因此准入扩张与默认开启仍不受支持。完整 native tick 值、CPU/GC/阶段计数与等价检查在
[服务器性能记录](BLOCK-ENTITY-RESOURCE-SERVER-AB-PROFILE.json)中。
[`.59` 分配与验证成本续作](BLOCK-ENTITY-RESOURCE-HOT-PATH-REDUCTION.md) 保留全部当前操作验证，并用不可变
数组与打包身份表减少 proof 表构建。两个独立构建对，2/64/256 熔炉与 4 workers，相对 resource-on `.58`
降低真实分配与平均 MSPT；默认值与 BE 准入不变。见[完整成本数据](BLOCK-ENTITY-RESOURCE-HOT-PATH-PROFILE.json)，
包括对照与独立重复。[混合负载验证](BLOCK-ENTITY-RESOURCE-MIXED-VALIDATION.md) 在冻结 `.59` 二进制上完成
16 个已验收已安装服务器进程、94 个窗口与独立的 30 分钟开/关检查。受支持熔炉正文与保守串行 BE fence、
世界/实体 tick 与合法动态变化共存；完整状态/工作检查通过。资源开启在 3/4 短独立对中使 mean/p95 回归；
长对使 mean 增加 18.8%、p95 增加 34.2%、分配增加 82.5%。被 fence 的连通 Hopper 管线仍串行。
这个有界验证在已审计范围内加强正确性证据；它不支持默认开启、任意回调安全或 BE 准入扩张。完整记录在
[混合负载 profile](BLOCK-ENTITY-RESOURCE-MIXED-PROFILE.json)，与下方历史整区 soak 分开。
[`.60` 成本归因续作](BLOCK-ENTITY-RESOURCE-COST-ATTRIBUTION.md) 先追踪新鲜 catalog、Claims/Binding 与
规划/预留成本，再共享同阶段不可变本地描述并复用已清空的 scratch 容量。它保留当前 validator、一次性 lease、
生命周期/Region 检查、默认关闭与相同 BE 准入。四个反向独立构建/条件对把资源分配增量从 3.54–3.61 降到
2.28–2.30 MB/s（35.1–36.6%）。mean/p95 只在 1/4 构建对中改善；八波 fence 控制的延迟回归。10 分钟开/关
检查使 mean 增加 16.9%、p95 增加 51.9%、p99 增加 146.3%、分配增加 51.2%，因此没有建立稳定的整 tick
延迟收益。Core 1230、fixed 121、persistent 121、实际 unknown-Mixin 1 与 Python 19 通过；混合/动态/管线
回归保留，包括异步卸载/重载后一个明确的单正文路由差异。见[完整数据](BLOCK-ENTITY-RESOURCE-COST-PROFILE.json)。

## 切片

| 负载 | 关闭（服务器线程） | 区域化 | 加速 |
|---|---|---|---|
| 1024 牛 | 27.3 / 19.3 mspt | 6.0 / 5.8 mspt | 4.5× / 3.4× |
| 1024 僵尸 | 22.8 / 22.2 | 6.6 / 8.8 | 3.4× / 2.5× |
| 1024 村民（床 + 工作站） | 54.5 / 52.0 | 25.7 / 22.0 | 2.1× / 2.4× |
| 1024 chunk 随机 tick（9×9 区域） | 5.59 / 5.87 ms 切片 | 4.80 / 4.64 | 1.15–1.27× |
| 1024 chunk 随机 tick（3×3 区域，解除内部规则） | 6.88–6.92 ms 切片 | 5.54 | 1.24–1.25× |
| 289 chunk 自然刷怪 | 4.13–4.62 ms 切片 | 2.79–3.93 | 1.15–1.65× |
| 2048 漏斗 | 3.27 mspt | 3.74–3.97 | 0.82–0.87×（更慢） |
| 4096 熔炉 | 2.60 mspt | 2.70–3.34 | 0.78–0.96×（更慢，轻负载改动后 −19%） |

## 正确性证据

| 领域 | 如何证明 |
|---|---|
| POI | 随机世界与真实单线程 `PoiManager` 比较：每种查询、每个区域视角、区域尺寸 3/4/8 —— 相同记录、相同顺序、相同空闲 ticket |
| 红石 | 49 种真实方块布局跨边界偏移 1..15，开/关各跑 60 tick：5 880 次逐位置比较方块状态、方块实体状态、首次出现 tick 与方块事件流位置 |
| Chunk 随机 tick | 每 chunk 派生被证明与区域布局、worker 数、tick 顺序与线程无关 |
| 刷怪 | 区域状态精确划分 level 计数；level 级 cap 用原子 check-and-reserve 强制 |
| 跨区读 | Seqlock：绝不撕裂；过期读会重跑产生它的区域（关闭检查时测试失败） |
| 生产 soak | 生成地形 6 000 tick，含村民、生物、漏斗/熔炉线、农场、跨边界红石钟与移动假玩家：0 所有权违规、0 tick 失败、物品守恒、6 200/6 200 tick 红石一致 |
| Game event | 六个 sculk 测试把完整逐 tick 轨迹与 inline 运行比较（能量、比较器、校准过滤、双区域并列、双边界半径、尖啸体召唤监守者）；重放被短路时全部失败 |
| 保存与崩溃 | 保存/重载比较 114 方块、13 方块实体、9 实体 UUID、10 POI 记录及其 ticket 与重建区域映射；tick 中途硬杀在三个方向恢复（开→开、开→关、关→开） |
| 玩家 | 行走、1000 格传送、批次中传送、下界往返与两名玩家交换区域都让玩家恰好属于一个区域 |
| 保存与重载 | 整个夹具世界保存、卸载并从磁盘重载：每个方块、方块实体、实体 UUID 与位置、POI 记录及其空闲 ticket 数与区域映射相等，覆盖区域线程开与关及两种配置 |
| 崩溃恢复 | 三对共六次运行在 tick 中途杀 JVM 并在同一世界重启：存档点完整、无重复 UUID、无泄漏 ticket、无悬空延迟、后续 600 tick 绿色 |

## 区域 worker 的实体查询面

modded tick 能到达的每个实体查询入口都从 worker 可读数据回答，且按操作计数
（`/neoforge regionthreading status` 的 `Region entity queries:` 行）。

| 入口 | worker 回答 | 计数 |
|---|---|---|
| `LevelEntityGetter.get(int)`、`ServerLevel.getEntity(int)`、`getEntityOrPart(int)` | level index 发布后，该区域的实体 index | `local`；其他区域实体、本批次延迟移除的实体、index 未发布的 level 记 `refused` |
| `LevelEntityGetter.get(UUID)`、`ServerLevel.getEntity(UUID)` | level index 发布后，该区域实体 index（弹射物 owner 解析） | `local`；同上 `refused` |
| `LevelEntityGetter.getAll()`、`ServerLevel.getAllEntities()` | 该区域实体 index，按 tick-list 顺序 | `local`；level index 含更多实体时 `truncated` |
| `get(EntityTypeTest, consumer)`、`ServerLevel.getEntities(EntityTypeTest, Predicate, List, int)` | 该区域实体 index | `local`；同上 `truncated` |
| `get(AABB, Consumer)`、`get(EntityTypeTest, AABB, Consumer)`、`Level.getEntities(...)` | 该区域物理 section bucket，盒按 lease 裁剪；`Level.getEntities` 还追加 lease 已回答的 multipart owner 的 parts（原版追加 level 的 parts） | `local`；裁剪时 `truncated` |
| `getEntitiesOfClass(Player.class, box)` 及每个 `Player` 子类查询 | 区域 bucket 加上 level 已发布快照中记录包围盒与盒相交的玩家；bucket 已回答的玩家不重复加入 | `local` + `published` |
| `players()`、`getNearestPlayer`、`getPlayerByUUID`、`getNearbyPlayers`、`hasNearbyAlivePlayer`、`getPlayers`、`getRandomPlayer` | level 的每 tick 玩家快照；无已发布快照的 level 其 worker 被拒绝，绝不拿到 live 列表 | `published`；无快照时 `refused` |
| `EntityStorageGuardedGetter`（section manager 自身视图） | 仍拒绝：manager 没有 level，因此没有可回答的 lease | `refused` |
| 实体 transfer journal、identity ledger、section partitions、实体准入 | 仍拒绝：它们修改服务器拥有状态，不是查询 | 违规计数 |

玩家快照在 level tick 头部由服务器线程发布（`RegionPlayerSnapshots`），每 tick 替换（绝不修改）一次，
level 卸载时丢弃。它最多落后一个 tick：发布后加入、离开或换维度的玩家不在其中，记录的包围盒是 tick 开始时
玩家拥有的那个。盒查询与该记录包围盒相交，因此裁剪玩家身体的盒会像原版 section 扫描一样回答。
调用者收到的实体是 live 的，因此其自身谓词与目标测试像原版循环一样读取当前状态。快照存在的原因是玩家是
始终 tick 的实体，只有服务器线程 tick 它：它不在任何 worker 的区域，而 level 的 live 玩家列表是 worker
绝不能遍历的可变 `ArrayList`。没有快照的 level 的 worker —— level tick 从未通过 bridge 打开，或其状态在
卸载时被清除 —— 会以与其他存储 guard 相同的 `IllegalStateException` 被拒绝并计为 `refused`；
它绝不回退到 live 列表，后者留给每个非 worker 调用者。

Multipart parts 由两个 `Level.getEntities` 形状从 lease 已回答的 owner 追加，因为原版在其 section 扫描后
追加 `Level.getPartEntities()` —— 服务器线程的 live part 映射 —— 而 worker 不能读该映射。part 属于其 owner
的区域，因此 lease 外 owner 的 part 与 owner 一样留在回答之外。`LevelEntityGetter` 的盒形状保持仅实体，
与原版一致。

## 生产 soak（全部开启，3-chunk 区域）

| 里程碑 | 开（mspt 均值） | 关（mspt 均值） | 比值 |
|---|---|---|---|
| 延迟跨区写入之前 | 7.35–8.28 | 6.48–7.60 | 0.97–1.23×（中位慢 1.14×） |
| 延迟跨区写入 + 解除内部规则之后 | 6.13–6.72 | 6.25–6.70 | **中位 0.983，均值 0.976，范围 0.941–1.014** |
| 持久 worker + 打包 lease 之后（默认关闭） | 6.69 | 7.35（区域化基线） | −9% |

第一行在跨区写入路径存在之前测得：3-chunk 区域下只有区域中心 chunk 的八个邻居都在内部，因此 89% 的
chunk tick 与 94% 的方块实体 tick 留在服务器线程，而屏障成本已全额支付。解除该规则 —— worker 在 lease
外的写入现在被记录并由目标区域在同一 tick 应用 —— 把 **100% 已派发 chunk tick** 移到 worker
（1.31 M tick，零边界 fallback），soak 现在落在 inline 或以下，中位 p50 为 5.60 ms 对 6.22 ms。

历史方块实体 soak 在共享内部规则下约 8% 在 worker 上。熔炉族正文现在有更窄的边界准入：开启边界传播且
邻居 chunk 已加载时，它们可在 worker 上 tick 自己的库存/方块，并延迟邻居/比较器效果。漏斗与 pack 准入
机器保持内部规则，因为它们读写 live 邻居容器。混合 chunk 保持整 chunk fallback。新边界路径尚未基准测试；
合同、验证边界以及后续跨区/模组 API 与 scheduled tick 工作见
[BLOCK-ENTITY-PARALLELISM.md](BLOCK-ENTITY-PARALLELISM.md)。

## 跨区操作与 scheduled 回调

实验性公开门面现在在静止服务器完成阶段包含完整双 owner 方块实体操作与有界 sided Container 搬运。
调用者获得明确的至多一次任务结果；worker 受理不是同步搬运结果。引用保留精确实例身份，不能授权同位置替换。
任意模组机器 tick 仍受其现有兼容准入约束。见[协议与示例](CROSS-REGION-OPERATIONS.md)。

生产 scheduled tick 准入支持完整仅灯阶段，足迹为已加载、region 内含的半径 2 惰性原版方块。
未知方块回调、流体、混合阶段与不安全邻域保留原 live 串行 LevelTicks 队列。回调失败被报告，不重放、
不静默成功截断。每阶段计数与有界 p50/p95/p99 计时通过 ParallelApi 与 `regionthreading compat` 暴露。
这些是正确性/协议里程碑；上方历史性能表不构成新路径的加速结论。

## 结构声明：测量过，但未实现

活塞移动运行在服务器线程的方块事件阶段内，而一个 level tick 的每个方块事件都在那里串行：
`PistonBaseBlock.triggerEvent` 在服务器线程解析结构并写方块。声明本应增加的机制 —— 认领移动触及的 chunk、
在 region worker 上运行移动、让目标区域跳过被认领的 chunk —— 在实现之前就用 `benchmarkPiston` GameTest
测量过：128 个粘性活塞、20 tick 农场时钟、1..12 方块的柱、半数跨 3-chunk 区域边界推动、200 tick 预热 +
600 tick 测量。下表是拒绝该声明的测量（f58c3a883）；它报告的阶段此后已变为静止作用域，见下一节。

| profile | 移动/tick | 方块事件阶段 ms/tick | 占 50 ms tick | mspt 均值 | p95 | p99 |
|---|---|---|---|---|---|---|
| 关（原版） | 13.0 | 0.82 | 1.6 % | 2.78 | 10.4 | 13.2 |
| 开，边界更新关闭 | 13.0 | 0.76 | 1.5 % | 4.66 | 10.8 | 16.5 |
| 开，每次移动都在其区域内 | 13.0 | 0.76 | 1.5 % | 4.61 | 12.7 | 17.5 |
| 开（全部） | 14.4 | 2.18 | 4.4 % | 5.92 | 22.8 | 38.9 |
| 关，512 活塞 | 51.9 | 1.66 | 3.3 % | 3.95 | 16.3 | 27.0 |

移动本身耗时 30–60 µs，因此对每 tick 已经移动一列的负载，该阶段只占 tick 的 1.5%；区域化跨边界路径为
跨越边界的那半数移动每 tick 增加 1.4 ms 的捕获与 fixpoint 工作，这正是声明必须消除却未能消除的成本。
该阶段还位于 chunk tick 批次与同一 level tick 的实体/方块实体切片之间，因此移动运行时没有区域切片在跑，
声明今天没有可排除的对象；在 worker 上运行移动首先需要把阶段移入批次，这会改变 tick 顺序。
因此该机制未实现；数字与推理记录在当时的 `.pi/tasks/structure-claims.md`（临时笔记，不在仓库中；机制本身未实现）。

## 方块事件阶段：静止作用域，不捕获

方块事件阶段是 level tick 中唯一不是区域切片的部分。它在 tick 的每个区域批次返回后，在服务器线程上 drain
该 level 的有序方块事件集（`ServerLevel.tick`：方块 tick、袭击、chunk source，然后方块事件），因此没有
worker 在运行，也没有区域被其他线程租出：整个 level 对该阶段是静止的。因此它作为一个作用域运行
（`RegionThreadingEngine.beginBlockEventPhase`），对其事件运行的每个区域各取一次 lease，保持到阶段结束，
且不捕获任何边界更新。事件正文对另一区域的写入就是原版 inline 调用：没有任何东西排入 fixpoint，因此该阶段
不运行 fixpoint，正文读回它刚写入的内容。其他每个服务器线程区域 lease（scheduled tick 或方块实体 fallback）
仍像以前一样捕获并 flush，每个 worker 也一样。

两项测量推动了它。第一，每事件成本：该阶段曾为每个事件取 lease、运行一个事件并 flush 一次边界 fixpoint，
在 128 活塞时 2.68 ms/tick、512 活塞时 7.88 ms/tick，而同一负载在单一区域内只需 0.67/1.53 ms。
第二，事件流：该阶段比 inline 运行多产生 12.5% 的方块事件。一个柱跨边界的西向粘性活塞把移动活塞写到了
边界外一格；该写入被捕获，于是活塞自己的邻居回调把头部位置读回为移动*之前*的方块，据此解析结构，并在
同一 tick 为同一活塞排入第二个伸出事件。Inline 下同一回调读到移动活塞，解析失败，不会排第二个事件。
阶段内联应用写入后，事件流逐位置与 inline 匹配。

同构建同配置的成对 A/B（`benchmarkPiston`，200 tick 预热 + 600 tick 测量，半数移动跨边界，4 workers，
3×3 区域）：

| profile | 方块事件 | 事件/tick | 阶段 ms/tick | 阶段最大 ms | mspt 均值 | p50 | p95 | p99 |
|---|---|---|---|---|---|---|---|---|
| 128，阶段捕获（之前） | 8 640 | 14.35 | 2.68 | 78.4 | 4.84 | 1.22 | 26.5 | 38.7 |
| 128，静止作用域（之后） | 7 680 | 12.76 | 0.53 | 10.4 | 3.85 | 1.40 | 19.2 | 26.2 |
| 128，inline（关） | 7 680 | 12.76 | 0.82 | 12.6 | 2.30 | 0.77 | 9.9 | 13.0 |
| 128，区域化，无边界更新 | 7 680 | 12.76 | 0.67 | 11.4 | 2.36 | 1.19 | 8.0 | 12.9 |
| 512，阶段捕获（之前） | 34 560 | 57.41 | 7.88 | 113.7 | 12.01 | 1.20 | 80.0 | 107.8 |
| 512，静止作用域（之后） | 30 720 | 51.03 | 1.36 | 26.2 | 8.11 | 1.02 | 53.8 | 71.7 |
| 512，inline（关） | 30 720 | 51.03 | 1.40 | 38.4 | 3.32 | 0.73 | 14.4 | 22.6 |
| 512，区域化，无边界更新 | 30 720 | 51.03 | 1.53 | 31.4 | 3.82 | 0.91 | 16.1 | 26.8 |

阶段成本现在与仅内部/inline 成本相当，两个规模下事件数也等于 inline 数；同构建在 128 活塞的最终确认运行
测得 0.47 ms/tick 与 7 680 事件。区域化 mspt 仍高于 inline，因为活塞负载由串行的服务器线程移动本身主导
（同一批行显示 4-worker 引擎上每 tick 移动一列的负载 p95 达 19–54 ms），而不是该阶段。剩余的方块事件工作
就是移动：它在服务器线程上运行，因为阶段位于每个批次之外，这正是结构声明必须消除的 tick 顺序约束。

同构建回归检查：漏斗 2048（方块实体切片，运行时关闭作用域做前后配对）4.35/4.92 对 4.83/4.57 mspt 均值，
即差异不超过运行间波动；熔炉 4096 与实体负载完全不触发方块事件，因此阶段作用域无法进入。生产 soak 在其窗口
报告 `phases=19875/leases=0/events=0/fixpoints=0` —— 阶段每个 level tick 作为作用域打开，其红石钟不触发
方块事件，无阶段进入 fixpoint —— 并且绿色：0 所有权违规、0 tick 失败、物品守恒、红石在 6 200/6 200 tick
一致，6.53 mspt 均值（p50 5.84，p95 10.1，p99 13.8）对关闭线程参考的 6.35（5.93/9.2/12.0）。
该阶段仍串行的唯一一类工作就是移动本身，它在服务器线程运行是因为阶段位于每个区域批次之外。

## 屏障记账：区域为什么不能跑在前面（A3，已否定）

`RegionBarrierAttribution`（默认关闭）把每个切片每个批次的等待拆成 **dispatch**（提交到首组启动）、
**span**（首组到末组结束 —— worker makespan）与 **tail**（末组结束到等待结束），并记录屏障后的
**handoff** 与两次屏障之间的服务器线程区间。`dispatch + span + tail` 等于 await，因此 `dispatch + tail`
是没有 worker 工作能解释的等待部分。在基线上测量，200 tick 预热 + 600 tick 测量，两次重复，ZGC；
份额是里程碑关注的量，毫秒来自一次会话，其绝对值比上方表格高 30–90%，因为机器上还有其他工作在跑。

| 负载 | mspt | await 合计 | span 合计 | 开销合计 | 开销占比 | await 占比 |
|---|---|---|---|---|---|---|
| 生产 soak，3-chunk，4w | 11.774 / 10.470 | 5.556 / 4.835 | 4.412 / 3.609 | 1.143 / 1.224 | 9.7 % / 11.7 % | 47.2 % / 46.2 % |
| 9x9 chunk tick，1024 chunks | 6.512 / 5.904 | 3.582 / 3.354 | 3.358 / 3.106 | 0.224 / 0.248 | 3.4 % / 4.2 % | 55.0 % / 56.8 % |
| 熔炉 4096，3-chunk | 5.103 | 2.463 | 2.170 | 0.293 | 5.7 % | 48.3 % |
| 漏斗 2048，3-chunk | 6.203 | 2.989 | 2.678 | 0.311 | 5.0 % | 48.2 % |
| 1024 僵尸，3-chunk | 26.769 / 27.793 | 23.408 / 24.226 | 23.092 / 23.915 | 0.317 / 0.310 | 1.2 % / 1.1 % | 87.4 % / 87.2 % |

soak 的每切片 p50（实体 2.576 / 2.120 await 对 2.400 / 1.952 span；chunk 0.488 / 0.464 对 0.360 / 0.312；
spawn 0.792 / 0.672 对 0.624 / 0.544；方块实体 0.232 / 0.232 对 0.120 / 0.104；传播 0.232 / 0.256 对
0.080 / 0.072）与均值形状一致。

结论是屏障等待就是工作本身：其中 75–99% 是该切片自身组的 makespan，剩余 1.1–11.7% 的 tick 是 dispatch 与
完成检测，而 persistent-worker 与 packed-lease 提交路径（A2）已经消除了后者。朴素上界 —— 两条 lane 完全
重叠，可为 soak 或 chunk-tick 节省 34–48% —— 不可达，因为服务器线程在两次屏障之间的工作*构建下一批次*：
切片 k+1 的计划由切片 k handoff 写入的状态计算，handoff 必须落在切片 k+1 正文运行之前，而跨 tick 边界的
区间包含下一 tick 首批次的收集。完整表格、逐状态顺序表（游戏时间、日时间、天气、`LevelTicks`、
`ChunkMap`/tickets、POI tickets、方块事件、`ServerLevel` 计数器、每 chunk 种子、刷怪 cap、mailbox、
跨区写入、game-event 重放）以及双 tick 窗口会使其可观察的内容列表在 `tmp/continuous-tick/SUMMARY.md`；
否证见 `region-threading/EXPERIMENTS.md` §5。

## 保存、重载与崩溃恢复

开启区域线程保存的世界会重载到相同状态，硬杀留下的世界会加载到其最后存档点的状态。两者都是被证明的，
不是论证的：一个 JUnit 测试让区域化 POI 存储经历一次保存与重载，并在同一目录上与 vanilla manager 比较；
一个 GameTest 保存整个夹具世界、卸载每个 chunk 并从磁盘加载回来；三对运行在活动中间杀 JVM，并检查下一个
进程加载什么。

### 什么是权威的，什么是派生的

| 状态 | 种类 | 所在位置 | 跨保存/加载 |
|---|---|---|---|
| Chunk 方块状态、方块实体、实体 | 权威 | region 文件 + entity 文件，由原版保存写入 | 在存档点写入，由 chunk 加载读回 |
| POI 记录及其空闲 ticket 数 | 权威 | level 的 POI region 文件，由 `RegionPoiStorage.saveColumn`（`RegionPoiStorage.java:684`）以原版 `Sections`/`DataVersion` 格式写入 | 由 level tick、其 chunk 保存与 `flushAll` 写入；由 `loadColumn`（`:530`）读取 |
| 区域映射（chunk -> 区域坐标） | 派生 | `RegionLayout`（`RegionLayout.java`） | 位置的纯函数；无需持久化 |
| Section partitions、准入缓存、ticker 索引 | 派生 | `FixedEntitySectionPartitions`、`RegionBlockEntityTickChunkCache`、`RegionEntityTickIndex` | 随 chunk 加载与实体 tick 重建 |
| 实体 identity ledger（`EntityIdentityLedger`） | 派生 | 内存，每 level 一个 | 加载时从原版 UUID 集重建；死掉的进程不留下任何东西 |
| 延迟方块更新、实体加入、移除、section 更新 | 每 tick 批次 | `RegionTickBatch` 及其延迟队列 | 由该批次屏障在其发布的 level tick 内 drain；存档点为空 |
| 边界更新 fixpoint 队列、mailbox、待处理实体传输 | 跨 tick | `RegionThreadingEngine.borderUpdates`、`RegionMailbox`、`RegionEntityTransferJournal` | 设计上的延迟工作；崩溃会丢失它，正如从未运行的 tick 的方块更新 |
| 区域实体存储模式、worker 池、lease | 派生 | `RegionWorkerPool`、`RegionState` | 加载时重建 |

### 保存能观察到什么

`ServerLevel.save` 用 `RegionThreadingHooks.beginLevelSave` / `endLevelSave`
（`patches/net/minecraft/server/level/ServerLevel.java.patch`）括住保存。`beginLevelSave`
（`RegionThreadingEngine.java:6457`）断言保存观察到已完成的 level tick：

* 有批次在飞是违规，计入 `savesWithBatchInFlight`。它在服务器线程保存时不可能发生：服务器线程自己发布并
  等待批次，因此只能在两个 level tick 之间保存，而原版自动保存在 `tickChildren` 返回后运行。
* 屏障尚未应用的延迟效果计入 `savesWithTickDeferredWork`。每个每 tick 队列都由其自身批次的屏障 drain，
  因此生产环境中两个计数都为零；可能破坏这一点的机制缝隙会被计数，而不是静默产生半应用的世界。
* 跨 tick 工作（延迟到下一 tick 的边界更新、mailbox 消息、待处理传输决定与 tick 尚未写入的 POI 列）计入
  `savesWithCrossTickDeferredWork`、`crossTickDeferredEntries` 与 `dirtyPoiColumns`。这不是违规 —— 是设计
  跨 tick 边界延迟的工作 —— 但崩溃会丢失它，因此被计数而不是不可见。

计数器在 `RegionThreadingDiagnostics.SaveCounters`（`RegionThreadingDiagnostics.java:390`），由保存/重载
GameTest 与硬杀测试断言。

### 一次修复，以及为什么需要它

原版 `SectionStorage` 在其自己的 `IOWorker` 上调度列写入，其 `close()` 会丢弃该 worker 尚未拾取的写入，
而 `ServerLevel.save(flush=true)` 只等待 chunk 与实体 IO。因此最后 tick 的一次 POI 写入可能因干净停机或
一次报告已 flush 的保存而丢失。`RegionPoiStorage.flushAll()`（`RegionPoiStorage.java:663`）写入剩余列并
等待其 IO worker；它在每次 flushing 保存（`endLevelSave`，`RegionThreadingEngine.java:6508`）与 `close()`
（`:674`）时运行。从不崩溃的世界只在存档点多一次 IO 等待（夹具运行中 10–20 ms），别无其他：无行为变化、
无数据变化，level 数据与之前相同，因为等待只是排序已经调度的写入。开关关闭时原版 POI 路径不受影响。

### 证据

| 内容 | 测试 | 结果 |
|---|---|---|
| POI 保存/重载等价 | `RegionPoiSaveReloadTest`（JUnit）：同一脚本的加入、移除与 ticket 变更分别进入 vanilla `PoiManager` 与区域化存储，两者关闭并重开，然后逐记录、逐 ticket 比较 | 4 个测试：重载后的记录、类型、空闲 ticket 与村庄距离与 vanilla manager 相等；存储的 `Sections` compound 与 vanilla 字节相等；每个重载 section 从其 chunk 所属区域回答，零服务器线程 fallback；未 tick 的 close 保留存储持有的每一列 |
| 整个世界的保存/重载 | `savesAndReloadsRegionThreadingState`（GameTest，批次 `saveReload`）：跨两个区域边界的 5x5 chunk，含床与工作站、带物品堆的容器、告示牌、蜂巢、钟、5x5 农场、跨边界拉杆电路、3 村民与 6 牛；保存、释放每个 ticket、等到 chunk source 报告每个 chunk 都不存在、再强制加载、比较 | 重载前后 114 方块、13 方块实体、9 实体、10 POI 记录与 2 个已取 ticket 相等；0 存档点违规 |
| 区域线程开与关的相同状态 | `runSaveReloadGameTestServer` 与 `runSaveReloadGameTestServerReference` | 参考运行的重载状态与开启运行的重载状态逐位置相等（114/13/9/10） |
| 硬杀，同配置两次 | 在同一世界目录先 `runCrashPrepareGameTestServer` 再 `runCrashRecoverGameTestServer` | 世界加载、存档点完整、9 个唯一实体 UUID、无泄漏 ticket、无遗留延迟、后续 600 tick 绿色 |
| 硬杀，存档开启、加载关闭 | `runCrashSaveOnGameTestServer` 然后 `runCrashLoadOffGameTestServer` | 同上 |
| 硬杀，存档关闭、加载开启 | `runCrashSaveOffGameTestServer` 然后 `runCrashLoadOnGameTestServer` | 同上 |

杀是真硬杀：prepare 阶段保存、记录该存档点状态、继续 tick，然后从另一线程在服务器线程正处于 tick 中途时
调用 `Runtime.halt(0)`。存档点之后没有任何 shutdown hook、chunk/实体/POI 写入或任何 flush 运行。
下一个进程加载的就是存档点，这正是原版对同一情况的保证：从未返回的 tick 的方块更新、物品传输、实体移动
或 POI 写入会丢失，而存档点之前已应用的不会。没有实体被加入两次（实体的持久身份是某个 chunk 实体列表中
的 UUID，从静止状态写入一次），没有身份预留存活（ledger 是派生的，每进程重建），没有 POI ticket 泄漏
（ticket 是记录中的 `free_tickets` 字段，随其列写入，记录在 chunk 加载时由 `checkConsistencyWithBlocks`
这一原版机制与方块状态核对）。

### 保存与加载墙钟时间

`saveReload` 夹具，5x5 chunk，3-chunk 区域，25 chunk 强制加载，一次 flushing 保存加该区域完整卸载与重载：

| profile | 保存（ms） | 重载（ms） | 方块 | 方块实体 | 实体 | POI 记录 | 已取 ticket |
|---|---|---|---|---|---|---|---|
| 开（4 workers） | 765 | 8 | 114 | 13 | 9 | 10 | 2 |
| 关（vanilla） | 754 | 243 | 114 | 13 | 9 | 10 | 2 |

保存时间由 25 chunk 的原版 chunk 保存主导，两侧相同。重载是同一批 chunk 的强制加载：区域线程开启时 chunk
任务派发到区域 worker，关闭时留在服务器线程，这就是这个小型夹具 30 倍差异的来源，不是对大型世界的声明。
同一世界的硬杀恢复（加载、比较、600 tick）每对耗时 20–30 s，其中 20 TPS 下 600 tick 占主导。

## 路线图

| # | 里程碑 | 状态 |
|---|---|---|
| A1 | 延迟跨区方块写入，然后放宽内部 chunk 规则 | **完成**（e82a76c47） |
| A2 | 持久 worker + 多区域组 lease（轻负载开销） | **完成，默认关闭**（af9a44429）：熔炉 −19%，漏斗 −6%，但实体切片因每 worker 槽位失去 executor 的动态均衡而损失 2.3–2.7 ms |
| A2b | work-stealing 派发与每 ticker wrapper 归因 | **派发器已实现，默认关闭**：持久批次使用有界 runner 并窃取一半未开始区段；熔炉波次使用同一派发器。[实现与仅派发测量](WORK-STEALING-DISPATCHER.md)。完整负载矩阵性能验收仍待完成。 |
| A3 | 连续区域 tick（用 tick 时钟替代每 tick 屏障） | **已否定**（已测量，见下）：屏障等待中 75–99% 是 worker makespan，屏障自身成本占 tick 的 1.1–11.7%，A2 的提交路径已消除；朴素重叠上界不可达，因为服务器线程在两次屏障之间的工作生产下一批次 |
| A4 | 动态区域尺寸（按负载拆分/合并） | 计划中 |
| B1 | 跨区 game event（sculk 振动） | **完成**（dbebf9803）：逐列捕获、在目标 worker 上同 tick 重放，soak 中 1650 事件/5657 延迟列，零丢失。发现并修复了 worker 发布事件此前被静默丢弃的问题 |
| B2 | 结构 lease（活塞多方块移动、Create 移动装置） | 已测量，不值得（见上） |
| B3 | 玩家区域化（传送、传送门、跨维度） | **完成**（f2f529272）：按类型显式转换、恰好一个区域审计、跨维度 ledger 交接，soak 中 6356 条断言 0 失败 |
| B4 | 大型随机红石 soak | 计划中 |
| B5 | 区域作用域实体查询面 + 每 tick 玩家快照 | **完成**：`getAll`、类型过滤与盒形状以及每个玩家查找都由 lease 或已发布快照回答；区域 bucket 已回答的玩家不重复加入，被移除的实体不从 index 回答，`Level.getEntities` 追加 lease 已回答 owner 的 parts，而外部实体的 by-id/by-uuid 查找、无 lease 的 worker、共享存储 level 与区域 index 未发布的 level 仍以操作名拒绝 |
| C1 | 区域化状态的保存/加载与崩溃恢复 | **完成** |
| C2 | 长时 soak（6+ 小时，漂移与泄漏检测） | 计划中 —— 6000 tick soak 是绿色，但六小时是另一种 regime |
| C3 | 多人并发（玩家、视距、tickets） | 计划中 |
| D1 | 模组兼容声明（策略注册表、冲突检测） | 计划中 |
| D2 | 170 模组整合包 bring-up（裸装 → 全部关闭 → 全部开启 → 逐个） | 计划中 |
| D3 | 自动化性能回归基线 | 计划中 |
| D4 | 文档与生产配置指南 | 初始源码审计、配置矩阵、模板与 canary/回滚流程已准备；已安装服务器门仍未通过。见[就绪度](PRODUCTION-READINESS.md)。 |

依赖主线：A1 → A2 → A2b → A4，然后 B1 → B2 → B3 → B4，然后 C1 → C2 → C3，然后 D1 → D2 → D3 → D4。
A3 因其第一阶段测量被移出主线（见下方“屏障记账”）：剩余杠杆是 worker makespan，不是屏障调度。

## 已知限制

- 区域尺寸是静态的；变热的区域无法拆分。
- 每 tick 屏障限制轻负载性能，但下方记账表明可恢复它的不是调度改动：等待中 75–99% 是切片的 worker
  makespan，屏障自身成本（dispatch + 完成检测）占 tick 的 1.1–11.7%。区域不能跑在服务器线程之前，
  因为服务器线程在两次屏障之间的工作用上一次屏障 handoff 写入的状态构建下一批次。
- 原始内部 chunk 变更（方块实体映射、section 数组、原始 heightmap 数组）在 seqlock 合同之外；
  只覆盖正常入口。
- worker 在 lease 外的写入延迟到目标区域的同 tick fixpoint；无法表达为（位置、状态、flags）的写入 ——
  带数据的方块实体、方块实体移除、仅 heightmap 写入、多位置移动 —— 仍被拒绝。
- 回调在 lease 外写入后又在同一 tick 读同一位置，会看到写入前状态。会计数，绝不静默。方块事件阶段豁免，
  因为它不捕获任何东西：其写入是 inline 的，事件正文读回它写入的内容。
- 持久 worker 池与打包 lease 默认关闭。上方历史切片测量早于[批量 work-stealing 派发器](WORK-STEALING-DISPATCHER.md)；
  该派发器改善了其合成派发负载，但完整切片/整服性能矩阵仍需重跑。
- 跨区效果按目标区域排序，不是单一原版全局顺序。
- 超出已准入半径的模组回调被拒绝，而不是分析。触及此类 chunk 的实体 tick 被截断并计入
  `truncatedEntityTicks`，其运行时类被声明为 server-thread-only，原因 `chunk_unavailable`，因此损失每类
  发生一次，后续 tick 在读取合法的位置运行。被截断的 tick 丢失而非重放：读取失败时其正文已经写入实体字段
  与延迟效果。四次生产崩溃（`crash-2026-11-09_11.45.26`、`11.51.15`、`2026-11-08_03.33.25`、
  `2026-11-10_05.59.18`）都走这条路径。
- worker 的玩家视图是 level tick 的快照：成员与记录的包围盒最多落后一个 tick。必须看到当前 tick 加入玩家的
  查找不会由快照回答，而无快照 level 的 worker 被拒绝，而不是读取 live 列表。
- 区域作用域的 `getAll()` 返回 level 实体 tick index 持有的、worker 所在区域的实体，而不是 level 的整个
  实体视图；截断计数器报告每次两者不同的调用。
- 区域作用域的盒查询追加它已回答的 multipart owner 的 parts，而不是 level 的每个 part：lease 外 owner 的
  part 与 owner 一样在回答之外。

## 补丁放置说明

- `LevelChunk.removeBlockEntity`：`removeBlockEntityTicker` 调用位于新增的 `beginChunkMutation`/
  `endChunkMutation` 作用域内，而不是像原版那样在其之后。它有意留在那里 —— ticker 移除会提升 chunk 的
  ticker revision 并重绑被移除的 wrapper，因此 chunk mutation 覆盖两者 —— 补丁带有说明注释。这不是漂移：
  方块实体消失后 worker 不能留下过期 ticker。

