# Create 优化与适配扩展方案

基线：`main` 的 `88566baaf`；实验分支 `feature/create-performance-and-adapter`。目标版本为 Minecraft 1.21.1 / Create 6.0.10。线上证据来自 2026-11-16 的 Forgia `.101` 诊断，本地原始结果位于忽略目录 `tmp/live-performance-20261116/`。

本方案同时覆盖 Create 方块实体（机器）和移动装置实体。前者的原始 ticker、动力网与库存需要完整所有权；后者还涉及乘客、碰撞、方块操作、假玩家和同步，不能通过取消回退名单获得权限。

## 目标与优先级

| 阶段 | 具体问题 | 核心改动与 adapter 职责 | 验收 |
|---|---|---|---|
| P0：秒级尖峰 | DeployerFakePlayer 初始化读取大量进度；装置开始跟踪时重复进入该路径 | 核心将 advancement 大映射解码从逐项线性查重改为哈希查重；保持 DataFixer、文件与原进度语义。Adapter 后续检查同步期间是否有不必要初始化 | 与原 codec 的正常/错误/partial/重复键/顺序完全对照；真实进度规模的独立性能测量 |
| P1：真实 Create 稳态准入 | 旧 v1 adapter 的 ADMITTED 不能通过 canonical Level 的正文合同检查 | 核心新增模组无关的 AuditedBlockEntityTickAdapter API 与通用执行桥，复用 Native prefix、Ticket、region lease、physical join、fence 与提交；所有 Create 实现指纹、状态条件和资源足迹由 adapter 提供 | 真实 SmartBlockEntityTicker + 实际 Level 循环中有两个不同 region 原始正文同时执行；未知变换/子类/回调分支完整串行；独立示例 adapter 也能使用同一 API |
| P2：可解释的成本 | BE Worker=0 与大量 fallback 不能说明谁耗时 | 补齐 canonical phase 的收集、准入、正文、协调、提交及按类型的有界计时；默认关闭高粒度采样，累计指标常数开销 | 总阶段时间与完整 native MSPT 对齐；重叠的 region 时间不相加当整服耗时；采样开销单独对照 |
| P2a：大阶段串行门槛 | 当前 canonical Coordinator 在实际阶段超过 2048 项时整体串行；线上曾收集约 5592 项 | 后续在通用核心实现有界窗口，复用一个 batch/cursor，保持跨窗口依赖、serial fence 和整阶段资源预算；adapter 不绕过上限 | 2048/2049、大混合阶段与跨窗口 alias/fence/生命周期对照；整阶段产出和原顺序一致；本轮不放宽这个旧门槛 |
| P3：加工机器 | 磨石、压块机、搅拌器等真正加工涉及库存、配方和回调 | 先闭合单次操作的输入/输出/共享容器资源，保留原顺序和随机数；从独立完整工作组件开始，不把整条动力网一次性当并行单位 | 相同世界完整 NBT、物品/流体/应力守恒、原回调顺序、动态状态变化与生命周期对照 |
| P4：装置 Entity | 旋转/移动装置、列车、机械手 USE/PUNCH 覆盖多个 owner | 先优化原串行序列化与假玩家生命周期；纯计算采用 detached 输入与权威校验提交。完整交互协议另行设计 | 加载/跟踪/重复同步/保存/拆装/乘客/跨区碰撞/取消与失败，不重放已产生效果的操作 |
| P5：部署收益 | Worker 覆盖率不等于加速 | 同一初始存档、完整产出、固定活动脚本进行 serial/core-only/adapter 对照，独立 JVM 逆序重复 | 包含准备与回退的全 tick mean/P95/P99、CPU、分配、GC；轻负载不退化；不从模拟测试推整服倍数 |

## 线上证据决定的方向

- 实测三次 Create 装置同步尖峰约 1.1–1.3 s，主线程 CPU 接近 wall，GC=0；栈包含 `Contraption.writeNBT -> DeployerMovementBehaviour.getPlayer -> PlayerAdvancements.load -> BaseMapCodec/Object2ObjectArrayMap`。
- 周期保存也有约 0.44–1.12 s 停顿，涉及玩家数据/进度编码和压缩写入。解码优化不能被描述为已经解决保存成本。
- 约六分钟内，实体/区块执行计数的 Worker 占比分别约 97.45% / 99.9985%，BE Worker=0。
- 九个 Worker 平均合计约 0.83 核、配置容量利用率约 9.2%。先解决串行路径与真实准入，再决定池与 region 粒度。
- 线上 canonical BE 阶段曾收集约 5592 项；现有 `CanonicalBlockEntityCoordinator` 对 `ordered.size() > 2048` 直接使用完整串行调用。这是独立于 adapter 能力的限制，即使安装本轮 adapter，超大阶段也不会因此获得 Worker 执行。线上未知实现/未安装适配器同样是限制，不将一个门槛表述为所有回退的唯一原因。
- 线上 Furnace Waves / Resource Ownership 关闭。本方案不会将其他配置的证明/规划开销当成当前已确认根因。

## 第一轮实现边界

### 核心：严格大映射 Codec

在 `PlayerAdvancements.Data.CODEC` 的单个大映射使用 hash-based 查重。保留原 key/value codec、DataFixer 包装、错误组合、部分结果、首值重复策略和不可变插入顺序；编码仍使用原 BaseMapCodec。禁止用忽略错误、跳过 FakePlayer 进度、清空文件或共享 PlayerAdvancements 活对象换取速度。

正常情况下查重从 O(n²) 降到期望 O(n)。需要用计数型 key 对照验证算法复杂度，另做与实际 advancement codec 相同形状的耗时测量；不在正确性测试里使用硬件相关耗时阈值。

### 核心：通用 audited original-tick API

核心不包含 Create 类型名单、指纹、字段名或 Mod 专属执行后端。通用 `AuditedBlockEntityTickAdapter` 继承原逐次准入接口，新增冻结的 implementation audit、canonical preparation 和新鲜 branch/READ-WRITE backing-root inspection。Create adapter 通过现有 registrar 注册，所有 Mod 知识仍在 `adapters/create`。

这是显式安装并信任已审核 adapter 的合同。完整分支语义、回调闭包和外部写入者控制由 adapter 的审计负责；核心统一校验实现完整性、实际绑定、生命周期、当前 footprint 与跨 owner 别名冲突。Hash 本身不证明代码安全，Java API 也不是 sandbox。旧 v1 predicate 和 C5 description 不能自动升级成这个更强合同。

第一批适合验证的对象是轴/齿轮、包裹轴/齿轮、齿轮箱、离合器和动力转向器。其合格分支只更新自身 Kinetic/Smart/behaviour/effect 的标量状态，不访问动力网注册表、邻居库存或世界回调。

正向准入必须同时满足：

1. 已安装相应 adapter，原开关/布局/内部区块规则通过；管理员显式串行声明优先。
2. 当前真实 Core wrapper/source/function/frame 对应，function 是固定 Create `SmartBlockEntityTicker`；同位置替换、不同函数和未知子类不能继承证明。
3. adapter 提供已审核目标实现、允许的原始函数类、源码出处与固定指纹；核心通过通用 API 校验实际实现与未审核变换。ADMITTED 或类型名单本身不能替代完整分支合同。
4. 已初始化，speed update/network dirty/validation/particle 等会进入外部回调的边界均在正文前拒绝。behaviour 与 effect 必须是已审核精确类，并由这个 source 独占。
5. 在实际 cursor 和 held-region 执行前重查。未知串行 fence 结束后重新证明 suffix，不保留跨 fence 的安全结论。
6. 复用原 canonical Native prefix 的一次 batch、Ticket、region ownership、检查闸门、physical join 和有序效果提交；不得创建第二个调度器或授权系统。
7. 新 audited adapter 与 Furnace Waves 可共存：只调用其 `prepareCanonical`；旧 v1 adapter 的 Furnace prepare=0/完整串行规则保留。原 2048 项 whole-phase 门槛、资源预算与单炉回退不变。

外部动力网中存在其他机器不必自动排除一个已证明完全不读写动力网的本地分支。旧磨石/水车路径的整网预检仍保留，其状态依赖不能随之放宽。canonical preflight 应能区别本地闭合分支和历史驱动，避免为了无关网络扫描付费。

### Adapter：覆盖范围递进

| 对象 | 第一轮目标 | 暂保留的串行边界 |
|---|---|---|
| 轴/齿轮/包裹传动/齿轮箱 | 从旧显式驱动推进到实际 canonical Level 路径 | 初始化、周期校验、速度/拓扑更新、粒子发布、未知 behaviour/变换 |
| 离合器/动力转向器 | 增加稳态支持；红石切换完成后可重新准入 | 切换导致的拓扑更新、未闭合的 inherited body |
| 水车/磨石 | 保留原 v1 接口验证，不冒称已通过新 canonical 证明 | 流量检查、配方、产出、RNG、外部库存与完整加工 |
| 带、漏斗、溜槽、工作盆 | 先按资源/正文成本选取下一批 | 共享 controller/容器/capability 尚未闭合 |
| Deployer/移动装置 Entity/列车 | 本轮核心解码优化覆盖其串行热点；后续评审序列化优化 | 原始世界操作、乘客、碰撞、网络与移动结构权限 |

## 完整验证矩阵

### 正确性

- Codec：空/大映射、JsonOps/NbtOps、非法输入、键规范化冲突、partial key/value、错误文本与生命周期、不可变结果/条目顺序、编码 prefix。
- Create：实际变换 jar、真实绑定函数和 Level membership；独立 region overlap；原始正文次数；原顺序 serial fence；红石/dirty/校验/未知 behaviour 动态切换；同位置 replacement、ticker rebind、未知子类、未知 Mixin、失败不重放。
- 混合 Native Hopper/Create/Furnace/Unknown 的 canonical phase，卸载/关闭必须等 physical join 与必要提交，原断言不能被改成“允许丢 tick”。
- Core 全量 JUnit、与 Create 无关的独立 adapter/资源别名/无效指纹/注册事务测试、禁止通用核心类依赖 Create 的架构断言、Create 独立 JVM 的 unknown-Mixin 控制、formatter/license、安装包和 adapter jar 内容检查。

### 性能

分开报告机制成本、真实安装服和整包负载。CPU/分配/GC 测量区间与 native ring 区间分别记录。至少包括：轻传动、真实加工、混合机器、拓扑频繁变化、独立/集中布局、玩家移动/加载与装置重复同步。每项使用相同完成工作和存档状态，独立 JVM 逆序重复。任何开启后回退到不同工作量或 correctness 不通过的 run 都保留并排除出收益结论。

第一轮通过功能测试不意味着整合包已获新认证或比原版更快。默认开关不改；线上升级、重启与 A/B 部署是后续独立动作。

## 实施与验证记录

### 本轮已完成

- 核心新增模组无关的 `AuditedBlockEntityTickAdapter`、`AuditedTickImplementation`、`AuditedBlockEntityPreparation`，复用原 registrar / canonical Native prefix / Ticket / region leases / physical join。通用组件无 Create class、type id、指纹或字段判断。
- 所有 Create 知识留在 `adapters/create`。0.2.0 新增离合器／动力转向器，并让已有稳态传动对象通过实际 canonical Level 路径执行；检查粒子、dirty network、未知 behaviour／子类／ticker function 和 serial fence 后缀。
- 独立 counter adapter 在没有 Create 的核心测试环境通过同一 API，覆盖两 Region 并行、READ/WRITE alias（包括同 Region）、footprint 变化／非法范围、实现指纹变化、函数实例状态、注册重入／退休及失败不重放。
- 新合同可与 Furnace Waves/Resource 开关共存；旧 v1 preparation 与 Hopper 原生合同保持原来的优先级和拒绝行为。新 adapter inspection 只在原 batch publication 后运行。
- `PlayerAdvancements.Data.CODEC` 改用严格 hash 查重，DataFixer 和保存格式不变；没有跳过或清空 FakePlayer 的进度数据，也没有把 Create 专用补丁写进核心。

### 最终源码验证

JDK 21、Gradle offline、同一工作树顺序运行。正式记录在本地忽略目录 `tmp/create-optimization/`；各次命令、实际 exit、按本次启动时间筛选并复制的新 XML 均保留，不能用后续覆盖的默认 XML 目录拼结果。

| 检查 | 实际结果 | 本地证据 |
|---|---|---|
| 固定池完整默认核心 JUnit | **1492 tests，0 failure/error/skip** | `freeze-full-fixed.exit.json` / 对应 `core/` XML |
| Create 完整默认适配器测试 | **25 tests，0 failure/error/skip** | 同次 receipt 的 `create/` XML |
| Persistent + Furnace/Resources 相关核心 | **124 tests，0 failure/error/skip** | `freeze-persistent-combination.exit.json` |
| 相同组合的真实 Create canonical 路径 | **8 tests，0 failure/error/skip** | 同次 receipt 的 Create XML |
| 独立 JVM 真实未知 Mixin 控制 | **2 tests，0 failure/error/skip**；通用 audit 拒绝且完整原 body 串行 | `freeze-unknown-create.exit.json` |
| 本轮 Java 格式、root/tests/adapter license | **exit 0** | `freeze-format` 和 `freeze-package` |
| 源 patch 应用、installer/facade/adapter 打包 | **exit 0** | `freeze-package.exit.json` |
| 私有真实安装服 smoke | **PASS**：801 natural ticks、1572 native completions、两组磨石实际产出、server exit 0 | `installed-smoke-v1/summary.json` / 原始命令与日志 |
| jar 内容与通用核心目标依赖检查 | **PASS** | `artifacts.json` / `verify-artifacts.py` |

默认任务原有的 benchmark/独立 unknown-Furnace-Mixin 排除不计为通过。上述测试不等于完整 GameTest、C6/VM runtime-proof、生命周期重新认证或整合包多人验收。World/chunk 测试替身仍明确存在。

### 独立机制性能测量

`AdvancementCodecBenchmark` 使用生成的同一 JSON 输入、真实 `ResourceLocation` / `AdvancementProgress` codec，先验证完整重新编码结果一致，预热后六轮交错逆序；所有输入在计时前创建，没有同时运行其他构建/测试。单 JVM、单机器，分配只统计调用线程，不能称为独立服务器重复或线上 MSPT。

| 外层进度条目数 | 原解码中位 ms | 新解码中位 ms | 原／新中位分配 bytes |
|---:|---:|---:|---:|
| 1024 | 31.649 | 8.464 | 4,023,512 / 4,039,696 |
| 8192 | 1621.405 | 19.257 | 29,427,000 / 28,836,928 |

正式完整测量：`codec-benchmark-v2`，exit 0。1024 条新路径分配略增，不能声称所有规模都降低分配。
这是特定解码热点的改善，编码/保存路径仍沿用原实现；不能据此认为周期保存停顿已解决，也不计算整服加速倍数。

### 保留的失败与修正

- 初始 patch 行号不匹配、setup 与 compile 同 Gradle invocation 的既有隐式依赖检查失败，后改为正确源坐标并分开执行；原失败记录保留。
- 初始 Codec 对照把 `DataResult.Error` 的 error supplier 身份当值比较，修为比较 result/partial/error message/lifecycle，保留全部语义要求；原 3 项失败保留。
- 初次完整回归 3 项旧 Hopper/v1 case 失败，原因是新分派先于原生合同；恢复已有 native proof 优先，原断言未降低，后续全量通过。
- 新 Create fixture 首次有 Mockito stubbing 顺序问题；修复 fixture，未替换真实 ticker 或放宽生产可见性。
- 混合炉子测试首次把 wrapper 当 BE 读字段；改从 fixture 原有 source 表读取，保留原进度断言。
- 一次打包任务因格式检查失败，后 scoped formatter 与完整 package 检查通过；没有把失败任务的部分 jar 当最终验收。
- 初始 32768 条机制基准观察到原路径单次超过 100 秒，主动中止并保留 exit 1/日志。正式基准固定为 1024/8192，单独完整重跑，旧大规模数据未混入正式结果。

### 产物与仍开放的验收

当前构建核心 `21.1.250.107-beta`，adapter `0.2.0`。产物清单与完整 hash 见本地 `artifacts.json`：

| 产物 | SHA-256 |
|---|---|
| `neoforge-21.1.250.107-beta-installer.jar` | `17c1d71ee169bb4b4f82b755ca3e8e5f23ee4037c9cc92d1461a6b4161abe85e` |
| `neoforge-21.1.250.107-beta-universal.jar` | `ec4d751a8a499642936614b190d4cb241523830b59e77abbe1cc04a7b922fbc3` |
| `neoforge-21.1.250.107-beta-parallel-api.jar` | `2ea48296fc67fd8fb044d340c3f824c6cd9bec75f208110ad87949fac8fe15c4` |
| `forgia-create-adapter-0.2.0.jar` | `806f20f150d42aadfd220e3686e004ade6a889ab97433d73364b66f2e2a66cac` |

原 Create 6.0.10 jar 指纹为 `ef87fe5709f1ba1f5b8bb20a2925b5afb4669e178fd6d8bf10c167759eefe37a`，不打包入本轮产物。

私有 smoke 使用新的本地实例，原 libraries 只作复制缓存，不打开输入存档。实际启动日志确认 `.107`，安装后
universal 与 adapter 的 hash 均和上表相同。两组真实水车／齿轮箱／磨石完成 801 个自然 tick，
worker completion=1572、fallback BE=3234，ownership violations 与 entity/chunk truncations 为 0，
原 tick failure 字段全部为 0；两组磨石都有真实麦粉产出，保存/停服 exit 0。传动 Worker 与串行加工的范围不混淆。

该 smoke 没有玩家、附属 Mod、秒级尖峰复现场景或相同工作量串行性能对照。日志保留 Ponder refmap、
annotations class lookup、终端能力、union URL、私有离线测试配置及默认配置补全等启动 WARN，Create conflict
报告为 measured-compatible；未出现 ERROR/FATAL。不能把 smoke 说成“无任何诊断警告”或整包性能验收。

仍开放：大于 2048 项的通用有界窗口、全部机器成本归因、加工组件/更多 BE 与移动装置 Entity 协议、实际整合包变换兼容、相同存档/活动的独立 JVM 性能 A/B、多玩家与长 soak。本轮没有改线上配置、上传核心或重启线上，也没有提交 git commit。
