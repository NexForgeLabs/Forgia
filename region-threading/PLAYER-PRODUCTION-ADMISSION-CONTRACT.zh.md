# 玩家两阶段 production 准入合同

接续 05 最终收口（2026-11-17）：本受限合同的P2完整双入口已在`.126`完成验收，P3未开始；下表支持清单不扩大。478 JUnit、三个选定GameTest、完整schema3/4及故障/网络/生命周期矩阵通过，证据见实施报告末尾、`tmp/player-region-tick-p2-final05/receipt-final126.json`和`final-proof-audit-final126.json`。`.125`的完成草稿因NETWORK合同反例撤回，旧receipt.json和反例均保留。未知/不可检查扩展在freeze之前拒绝；后续逐调用capture/revalidate/consume继续完整复查。四模式每组500 NETWORK保持原Netty线程、500 MAIN回流保持Server线程，worker=0、deferred=0。溢出终止经当前common listener原协议disconnect，在accepted前缀和真实join后执行。默认false、未知扩展整次串行、开始后不重放、真实join后清理保持。


本合同交付 `RegionPlayerAdmissionPlan`、installed 环境检查与串行拒绝诊断，承接[完整空间集合](PLAYER-SPATIAL-FOOTPRINT-CONTRACT.zh.md)和[共享状态](PLAYER-SHARED-STATE-CONTRACT.zh.md)。首个保守 production 分支可为 **ENTITY 的完整原父调用**和 **CONNECTION 的完整原 Connection.tick/listener**分别捕获、复查。准入计划自身不运行正文、不创建池/lease/ledger、不改变默认 false，也不增加玩家 worker 计数。**P2 首版现已由 `RegionPlayerTickDispatch` 接入两个原位完整调用；`.121` 两种池的无观测 Mod 安装服已验证实际重叠。** 当前支持范围和完整验收的剩余项见[实施报告](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md)最新记录。

## 调用与失效边界

1. 原服务器静止窗口调用 `engine.capturePlayerAdmissionPlan(player, phase)`。依次检查依赖、显式 server-only 声明、实际 installed 环境/注册、玩家和连接可选分支，再捕获原 spatial/state 计划。`Capture` 返回计划，或者 `Refusal + detail`；检查不调用事件监听器、attachment supplier、tick 或 pending action。状态层允许在发布线程预热原 stat/attribute 缓存。
2. 使用 `plan.spatial().conflictEntry()` 和原连续前缀规划器，复用原 batch、`reservePlayerSpatial`、完整 RegionLeasePack 及 `lease.bindPlayerState(List.of(plan.state(), ...))`。state 与 admission 在捕获时一次绑定；资源 claim 同时包括新增的 SynchedEntityData、玩家 RNG、CombatTracker 和 PlayerChunkSender。没有新的权限或身份体系。
3. 发布前调用 `plan.revalidate()`，它重查整个 admission、spatial/state、实际 installed 环境、事件注册、精确声明及原对象身份。任何 serial fence 后重新捕获后缀；ENTITY 票据不能复用为 CONNECTION 票据。
4. 原 native worker 在空间 scope 内调用 `lease.openPlayerState(plan.state())`，在原阶段记账进入 STARTED 和原调用副作用**之前**，再次检查整个 admission 并单次消费状态计划。前置 open 失败尚未执行原正文；调度器可以真实 join/释放后完整串行。开始后保留原异常、真实 join 后清理，绝不重放。
5. 本计划还必须配合原 coordinator 的 server session、玩家实例、listener/connection、ledger publication、lifecycle generation、tick/phase 票据与网络静止窗口。spatial/state scope 都不能代替这些条件。关闭时 state → spatial → worker 物理返回/join → 发布线程释放 claim/lease → 完成父 batch。

## 实际 installed 环境及声明

- `ModList.get()` 的真实 mod 集合必须恰为 `minecraft`、`neoforge`。测试框架、oracle、Create 或自称 thread-safe 的 Mod 都返回 `UNKNOWN_MOD`，不按测试 Mod 的类型、ID/hash、单个事件或监听器数量授予特批。
- 检查 Mixin PREINIT/INIT/DEFAULT 已安装配置及 `Mixins.getConfigs()`；只接受本发行版的 `neoforge.mixins.json` 和 `mixinextras.init.mixins.json`。遍历 FML game ModuleLayer 及父层描述符的 transformation service、launch plugin、coremod provider；只接受当前已审计的 FML/NeoForge、access transformer、Mixin 内置 provider 类。未知配置/provider 返回 `UNKNOWN_TRANSFORM`。loader 未就绪、反射/模块检查不可用或 API 变化返回 `EXTENSION_INSPECTION_UNAVAILABLE`。
- 使用真实 `NeoForge.EVENT_BUS`。bus 必须为原 EventBus；通过原 ListenerList 检查具体事件及其继承注册。只认可 `EventBus.register` 为**精确原 NeoForgeEventHandler 实例**创建的原 EntityTick.Pre 注册：其 `moveRegionEntity(ServerPlayer)` 原分支立即返回。consumer、子类和其他注册都不继承该许可；检查从不运行、删除或替换监听器。
- 精确 entity type 声明优先；然后检查玩家类及所有父类的显式 server-only 声明。只允许独立玩家绕过由原 engine 自己安装的继承 `Player.class -> player_riding_stack` 默认保护。再次显式声明 Player.class，即便 reason 字符串相同，也不可绕过。骑乘链、FakePlayer、普通实体保护保持完整串行。
- 这是一份对已安装发行版/注册与原分支的合同，不是抵御任意恶意 JVM 内存改写的沙箱。没有向未知 extension 提供“自行声明安全”接口。

## 两个完整调用的可达路径

以下是两个原调用的保守条件的并集；某个条件在当前 tick 未触发，也不凭计数的模值跳过其检查。原查询、原返回值、事件位置和功能正文全部保留。

| 原调用/可达路径 | 支持分支与正文前拒绝 | 对象/同步效果及复查 |
| --- | --- | --- |
| ServerLevel 原 eligibility/guardEntityTick/tickNonPassenger → ServerPlayer.tick；EntityTick.Pre/Post | 调度保留原父调用及 eligibility、原序列、freeze/step 和 serial fence；未知 EntityTick 注册拒绝 | 原 coordinator 与 ledger；原 Player.class 整骑乘链保护保留 |
| gameMode.tick、warden tracker、无敌计数 | 精确 ServerPlayerGameMode，无 active/delayed destruction；未知破坏分支完整串行 | 原 gameMode/warden 对象 claim 与身份复查；tracker.tick 只改本地三个计数，不执行 tryWarn 全局查询 |
| InventoryMenu.broadcastChanges/stillValid | 精确本地原菜单、空 crafting/result/carried/remote slots、原 46 槽、原 listener/synchronizer；未知菜单、共享容器、额外回调拒绝 | 原菜单/容器/slots/集合/同步器均由 state 快照和 claim；原同步发送保留 |
| camera、fall/levitation/lava vehicle、portal/sleep | camera 为自己、无骑乘、无 portal/转维度、无睡眠、无 fall/levitation/vehicle 可选状态 | 不在 worker 执行 tracking、全局 sleep、传送或维度回调；未知分支整次串行 |
| TICK trigger → Listener.run → award/reward → XP；flushDirty | 原无玩家谓词 TICK，全部相关 criteria/requirements/reward 已捕获；无 display、loot、recipe、function，XP 总增量 ≤7；连接阶段 LOCATION 和 ENTER_BLOCK 注册均为空 | 原玩家 listener HashSet、generation/revision/成员、advancement 深层快照；原同步奖励/事件/进度、flushDirty/packet 保留。未知 predicate/function 完整串行 |
| Player.tick 与 Entity/LivingEntity 的原 hooks | 检查下面的完整事件并集；无未知 Mod/变换；拒绝玩家/level attachment | 具体 hook 仍在原方法同步执行；没有把任意 callback 延迟到 barrier |
| inventoryTick、complex item、equipment/use、enchantment tick/location、effects/particle | 库存/装备/use/最近装备及 lastItemInMainHand 全空；active effects、location enchantment 状态空；无飞行/冲刺/潜行/游泳、肩部实体 | 关闭所有 item/effect/装备 tick 与残留装备变化回调；不存在未知 item/effect 的“简化 tick” |
| baseTick 的呼吸/伤害/环境，aiStep/travel/姿态/尺寸 | 存活且满血、有限生命值、food≥2、满 air、无火/冻结/流体/窒息/边界伤害；原静止输入和完整惰性支撑；gravity=.08、scale=appliedScale=1、无强制姿态 | food 一次扣减后仍不会饥饿；不读取外来 hurt target。拒绝模型尺寸待刷新，避免正文扩大 footprint；world border 必须原类、静止且完整包含身体及余量 |
| block/流体/碰撞/支撑/step/insideBlock | 原 spatial 全集合与 loaded chunk；只有 AIR/STONE/DIRT/GRASS_BLOCK/BEDROCK、空 fluid/BE；步行发声阈值未到；无未知 block/fluid callback | 空间 lease 覆盖原完整查询和精确块范围；无普通 clamp、无截断碰撞。不存在伤害/step/game event 的外部共享效果 |
| pushEntities、playerTouch、物品/XP/其他玩家 | 完整 pickup/queryBounds 内有任何其他目标就整次拒绝（`TOUCH_CALLBACK` 或空间层更早的未知 callback 原因） | 原完整查询仍执行；不是忽略目标。此 production 分支尚不覆盖物品/XP 拾取或 PvP worker |
| food/stats/cooldowns、doTick health/food/air/armor/XP/level scoreboard | 原局部对象；低等级有界 XP；必经五个 CUSTOM stat 预热；对应 scoreboard objectives 全空 | 原 stat/cache handle、scoreboard revision、local objects claim 与复查；不创建分数、不同步排队代替返回值 |
| 属性更新、SynchedEntityData、CombatTracker、RNG | 原 attribute map/已物化实例，combat 非 active/takingDamage、last hurt targets 空，原玩家 RNG 与 level RNG 不同，level 已装 RegionAwareRandomSource，原 inactive profiler | 新局部对象加入原 state claim；原 level random router 仅在 state scope 内解析玩家 RNG，外部仍用原 RNG；不新建 RNG 权限体系 |
| Connection.flushQueue → 完整 listener.tick → flush/tickSecond/monitor | 精确原 TCP Connection/ServerGamePacketListenerImpl/有效 chunkSender；pendingActions 为空；无关闭/断线/重配置/teleport/floating/待答 keepalive/idle timeout/带宽 monitor | Connection/listener/chunkSender 均捕获身份，队列/状态重查，不运行任意 pending callback。完整 ack、reset、doTick 前后、authoritative position、spam/keepalive、channel flush/packet counters 保留 |

事件并集为 EntityTick.Pre/Post、PlayerTick.Pre/Post、LivingBreathe、LivingDrown、LivingHeal、LivingEquipmentChange、LivingJump、LivingFall、EntityEvent.Size、PlayerFlyableFall、PlayerXp.XpChange/LevelChange/PickupXp、AdvancementEarn/AdvancementProgress。它覆盖必经呼吸/玩家 tick/XP/advancement 与受限分支的姿态、尺寸及保守环境 hook。未知伤害、睡眠、破坏/物品使用、效果/装备、pickup/target 等路径由对应状态前置拒绝，不能仅凭这里几个事件无人监听而放行。

## 原共享对象与网络静止窗口

支持分支的可变本地原对象由 state 原子 claim，包括 player、advancements/criterion storage、stats、attributes/实例、food、cooldowns、inventory/menu/backing objects、gameMode/warden、listener/Connection，以及 admission 新增的 SynchedEntityData、玩家 RNG、CombatTracker、chunkSender。任意跨玩家别名拒绝整个组。level/chunk/entity 访问沿原完整空间协议；scoreboard/stat registry、criterion trigger/advancement tree、immutable registry/game rules 只在服务器静止窗口读取，写入点由既有 state 协议限定。EventBus 本身使用原线程安全发布/注册结构，准入每次检查当前实际注册；不是将 callback 的共享对象猜作本地。

连接网络对象不能仅靠“它属于这个玩家”证明静止。**此捕获结果必须在调度前配合网络门使用**：

- 原 PacketUtils/server queue 的游戏包、chat/filter 的 server continuation、NeoForge main-thread payload/enqueueWork 在 batch 中继续排队；barrier 不泵任意服务器 Runnable，含 chunk/server-service 旁路。
- Netty 的 keepalive、cookie/unexpected query、channel close/fault、register/unregister/common payload，以及未过 PacketUtils 的非法聊天/ack 等会访问连接字段/Channel attrs；它们必须与玩家批次互斥或使未开始票据失效。原 full Connection.tick 会更新与 Netty 共享的 sent/received counters；pendingActions 为空只关闭可执行 consumer，不能证明这些字段互斥。
- worker keepConnectionAlive 也可新发 challenge；“开始时 keepAlivePending=false”不表示整个正文期间不会收到回复。连接关闭/错误的原 disconnect/list removal 不能与 STARTED 正文并发保存或清理。需要真实 join 后执行原流程。
- 原发送的 Netty event loop、write/flush、计数/Channel attr 的合同须在两个原位入口任务中一起落地；原 ServerCommonPacketListenerImpl 的 server-thread flush 行为及 NeoForge main-thread payload 语义保持。

这些都是派发的**必需前置条件**，不能从 `Capture.plan()!=null` 推导其已经完成。P2 首版使用 `RegionPlayerNetworkGate` 关闭直接 Netty 回调准入：已有回调使本次 worker 准入失败，批次期间的新回调保序进入原服务器队列；原 worker join 不泵通用服务器任务。Connection 的原入站、异常、关闭和断线入口接入同一门，队列有界并在完成/异常路径释放 retain；worker 观察到断线后由原服务器队列执行生命周期移除。两池冒烟已观察到实际延后的回调和最终零 pending/readers，但完整停服、溢出、断线竞争及所有网络时序的安装服矩阵仍需后续验收。

状态命令 `neoforge regionthreading status players` 返回每阶段实际 worker/serial 次数、完整调用的线程和开始/返回区间、网络门与清理状态。`playerAdmissionRefusals()` 仅保存有限 phase/enum 计数，最近拒绝详情不含 live 玩家/Level 引用。

## 验证范围

实际命令、真实退出码、XML 与源码哈希见本项[实施记录](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md)及 `tmp/player-region-tick-continue02/admission/regression-final/` 的原始 XML/result.json。正向 installed fixture 在测试中模拟只有两个 base Mod 的观察，仍使用实际 FML ModuleLayer/provider 和真实 EventBus；生产源码没有测试许可。测试框架的实际 installed 环境作为未知 Mod 负控制保留。两池原 native scope 测试验证绑定后复查/单次消费与原同步 XP，不能当作完整玩家 tick 或安装服并行证据。未重跑旧 `.115` 安装基线，不作性能结论。

## 接续 04 的退役补足（`.123`，完整矩阵仍 partial）

网络门的延后任务现为可取消封装。原 `Connection.handleDisconnection` 在执行生命周期清理前、且必须在 player worker 真实 join 后，取消该连接尚未开始的回调及溢出终止任务；已开始回调保留原正文并在 finally 释放。引擎 `awaitActiveTickBatches` 后关闭门、清空待执行封装中的包/连接/闭包/门引用，服务器队列残留空封装不会继续执行游戏逻辑。普通与溢出任务的 executor 拒绝都回滚 retain 和登记。关闭和执行均受同一门锁保护；取消不提前释放 worker 的空间或状态 claim。

连接遍历只用**开始过**的缓存调用覆盖物理断开后的 eligibility，以便消费已执行结果或原异常。未开始的缓存若连接已断开，保持原遍历 remove/handleDisconnection 分支，不能因曾经被捕获而多执行一次原 Connection.tick。组件回归覆盖两种池部分提交、拒绝、state open 失效、原异常一次和真实 join，以及取消后任务字段无活引用；这不等于完整原正文 worker 故障或 GC 认证。

`.123` 上 schema 3/4 的 fixed/persistent × off/on 已分别完成 144/180 个功能与守恒场景，oracle 环境明确 UNKNOWN_MOD，两个阶段 worker 均为 0；同产物无 oracle 的两池 on 冒烟另行证明完整正文重叠、同区顺序。新的 production-only 生命周期语料增加真实 keepalive/聊天/ping、跨区步行、重连、保存恢复观察。各项最终目录、失败与仍未覆盖的网络/生命周期/P3–P5 以实施报告和接续 receipt 为准，不因这些增量扩大未知扩展的准入。

后续 `.124` 的确定性退役修正：stop-new-admission 不用于提前开启网络门，门持续到真实 join/thaw/close；已消费且仍属于原 lease 的 state scope 不因 socket 物理 open 位或 shutdown 标志失权，原对象身份/generation 仍匹配。`.123` 上四项原 native 两池反例失败留存，`.124` 修复后通过。无 oracle 的外部 JDI 原方法观察分别证明完整 worker 正文副作用后故障一次/不重放/真实 join，以及真实客户端在正文中关闭 socket 后原正文正常完成；无 Mod、Java agent、类重定义或准入例外。该证据及当前重生 CONNECTION worker=0 的限制见实施报告最新收口，不据此扩大通用事件/物品/Mod 权限。


重生的新实例准入已通过正常落地包进一步复验：vanilla forced respawn 位置 y=blockY+.1 未满足本分支的整数支撑门，ENTITY 可准入而 CONNECTION 正确 SPATIAL 拒绝；这不是把全局 OTHER 玩家 OPTIONAL_TICK_STATE 计数归给新实例。四组新隔离服保留该负窗口，再由正常客户端 position_look 落地至整数 blockY，两个池新实例两入口各77次完整 worker，off=0。没有放宽合同或修改生产产物；旧未落地语料仍保留原覆盖限制，最终结果见实施报告和 receipt。
