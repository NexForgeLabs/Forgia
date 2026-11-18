当前结论（2026-11-18）：**P3已按设计第7节的逐类有界协议完成统一验收，最终产物21.1.250.134-beta；停在P4前，P4/P5未开始。整体设计仍partial。** XP末次/单球、普通材料库存拾取、共享单箱子原菜单tick、非显示计分板、真实背包地图及一次非致命自饥饿伤害均有完整原worker正文、事件/同步结果、客户端反馈和守恒证据。一般PvP/实体/方块/开箱点击等正常包继续原服务器fence，未知/未支持路径完整串行，不能将P3完成理解为任意玩法或Mod可并行。

同产物557项受影响JUnit、3项选定GameTest、28份P3四模式语料（56玩法子场景）、schema3 144/schema4 180、13套P2兼容/故障/网络/退役矩阵及321项proof篡改负控制通过；96份独立verdict和88套隔离安装由最终审计闭合。四模式A owner(0,0)正常攻击B owner(1,0)均health20→19、客户端health19和一个damage包；两池两入口跨区真实重叠，同区0。oracle仍UNKNOWN_MOD/worker=0。

产物`tmp/player-region-tick-p3-final02/artifacts/neoforge-21.1.250.134-beta-installer.jar`，SHA256 `87680bb6ee3ff05b059a2bf4ac5aae6442387565801877a0cf577b2af513a670`。最终机器回执`tmp/player-region-tick-p3-final02/acceptance-01/receipt.json`、`final-audit.json`，接受路径映射`tmp/player-region-tick-p3-final02/accepted-evidence.json`。详细边界和全部失败见[当前P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾。默认false、未知扩展freeze前拒绝、开始后不重放、真实join后清理保持。保留全部源码、世界、失败及旧manifest/hash gate；未commit/push/deploy、reset/clean或操作现用服。以下旧“当前”段落均保留历史。

---

当前P3进度（2026-11-18）：`.134`已实现XP末次/单球、普通材料库存拾取、共享单箱子菜单、非显示计分板、真实背包地图和一次非致命自饥饿伤害的有界原正文协议。557项受影响JUnit、3项选定GameTest通过，统一四模式玩法/schema3/4/完整P2安装矩阵正在验收，**P3整体尚未收口**。地图原地换ID反例在`.134`正文前拒绝；外部XP观察器开销失败保留，新目录复验继续。详见[当前P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾；最终证据根`tmp/player-region-tick-p3-final02/`。P4/P5未开始。以下旧“当前”段落全部保留历史，不覆盖本段。

---

当前P3进度（2026-11-17）：**整体仍进行中，用户要求自主完成整个P3，P4/P5不开始。** `.128`已通过两池on的末次/单球XP原worker拾取与真实join后存储移除；`.129`已通过两池on的普通材料原worker拾取/Pre/Post/库存/统计/客户端反馈与守恒。`.130`共享单箱子已通过214项聚焦/524项受影响JUnit，真实客户端与原菜单/事件观察正在收尾；`.131`不显示计分板候选正在构建。中间增量的off、schema3/4、GameTest及完整P2安装矩阵尚未重新验收，最终P3必须同产物复验。详见[P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾；`tmp/player-region-tick-p3-02/receipt.json`、`p3-03/receipt.json`。历史`.127`及P2证据保留。默认关闭、未知扩展整次串行、开始后不重放、真实join后清理保持。

---

当前P3进度（2026-11-17）：**首个受限XP拾取增量已通过，整体P3仍partial。** `.127`已在两种原生池的完整Connection/doTick中实际拾取合并XP球的非末次份额；最后一次、单球、外来观察者和超预算仍整次串行。493 JUnit、四模式XP及P2 smoke、同产物完整schema3 144/schema4 180和独立负控制通过。详细范围、失败、未覆盖项及下步见[ P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾。机器回执`tmp/player-region-tick-p3-01/receipt.json`，最终审计`tmp/player-region-tick-p3-01/final-audit.json`。默认false、未知扩展整次串行、开始后不重放、真实join后清理保持；P4/P5未开始。

---

当前工作（2026-11-17）：用户已授权开始 P3。P3 实际玩法 worker 扩展现为 in progress，首个候选为合并 XP 球的非末次拾取；详细范围、发现和未完成验证见 [P3 实施记录](PLAYER-REGION-TICK-P3.zh.md)。下面“停在 P2 / P3 未开始”等措辞均保留为此前验收历史，不再是当前任务限制。

---

后续维护（2026-11-17）：用户已另行授权将全部更改提交/推送GitHub并清理WSL测试文件。源码及精简验收证据已推送，已删除约74.4 GiB的隔离测试世界、重复依赖、构建输出与Gradle/Node缓存。当前最终产物保留于 `tmp/retained-player-region-tick-2026-11-17/artifacts/neoforge-21.1.250.126-beta-installer.jar`；原始日志/帧/失败证据23376文件已压缩并逐项校验，其中最终3409原始输入全部保留。Git内精简回执见 `region-threading/evidence/player-region-tick-p2-126/`。原验收回执中的HEAD、未提交/未推送、旧路径存在等字段仅描述验收当时状态。WSL内部空间已释放；D盘仍需离线VHD压缩，当前尚未执行停止Debian的操作，见cleanup-2026-11-17.json。P3仍未开始。

---

当前最终结论（2026-11-17）：**P2 已按设计第 7 节首个受限完整双入口合同完成验收，最终产物为 21.1.250.126-beta。P3 未开始，按用户要求在 P3 开始前停止。整体设计仍 partial（P3–P5）。**

`.125` 的完成草稿已被真实 NETWORK 线程合同反例撤回；旧 receipt.json 和所有失败/中断证据保持原样。
当前结论以实施报告末尾“.126 最终 P2 收口”及 `tmp/player-region-tick-p2-final05/receipt-final126.json`、
`tmp/player-region-tick-p2-final05/final-proof-audit-final126.json` 为准。下文早期状态均保留为历史。

---

当前产物：`tmp/player-region-tick-p2-final05/final126/artifacts/neoforge-21.1.250.126-beta-installer.jar`，SHA256 `cc0c9a4277a8cb6b882e68ad913968ab168380b6bae15a000162aaa6d07405aa`。manifest为`tools/player-region-tick/reviewed-artifacts/21.1.250.126-beta.json`，SHA256 `b43c22a51fd9bc5b6598a789202c82f740ed87d1fe85d8ff595f61039e3472d1`。

478 JUnit、三个选定GameTest、schema3 144/schema4 180、243 proof负控制和68份独立verdict全部通过。四模式真实A owner(0,0)→B owner(1,0)攻击均health20→19、客户端health19且一个damage packet；实体/方块/BE/共享容器/物品XP守恒通过。oracle始终UNKNOWN_MOD/worker=0；P3玩法worker未扩张。

两处生产改动：溢出用当前common listener的原协议disconnect；未知/不可检查扩展在freeze前拒绝，保留后续capture/consume。四模式各500 NETWORK回调保持原Netty线程、500 MAIN回流保持Server线程，错序/错线程/deferred均0。.125反例和撤回草稿保留。

矩阵路径映射见`final126/accepted-evidence.json`：已完成七矩阵在`final126/`，恢复后的smoke/socket/timers/lifecycle/respawn/phase-order在全新`final126/recovery01/`。旧后台消失后的不完整目录/退出未知wrapper原样保留，不计通过。原receipt.json不改；当前新回执receipt-final126.json与final-proof-audit-final126.json。

保持默认false、未知扩展整次串行、开始后不重放、真实join后清理；无oracle特批。GUI暂停未图形实测，memory拒绝；活动Level退役为原引擎/native池证据，无任意动态Mod认证或长期性能结论。源码/世界/失败全保留；HEAD未变，未暂存、commit/push/deploy或操作现用服。用户要求P3开始前停下，**不得自动推进P3**。

以下所有旧“当前”措辞均为保留历史，由本段及实施报告末尾的.126结论替代。

---

最终 NETWORK 审计最新状态：P2 当前仍在 `.126` 收口复验，P3 未开始。下方 `.125` complete 为被后续反例撤回的历史草稿，其通过矩阵和失败证据全保留。
`.125` 原 UNKNOWN_MOD 拒绝前冻结会把显式 NETWORK payload 从 Netty 转到 Server thread，普通包/JDI 只读观察已复现。
`.126` 已把全局 extension 拒绝放在 freeze 之前，保留全部后续 capture/consume；首次 478 JUnit 通过，四模式500 NETWORK +500 MAIN回流及完整 .126 schema/故障/生命周期正在重跑。
候选 installer SHA256 `cc0c9a4277a8cb6b882e68ad913968ab168380b6bae15a000162aaa6d07405aa`；新证据 `tmp/player-region-tick-p2-final05/final126/`。
原 .125 receipt.json 不改写，最终新回执必须另存并明确 supersede。全部失败保留，不 commit/push/deploy，不操作现用服，不启动 P3。

---

接续 05 最新结论（2026-11-17）：**P2 已按设计第 7 节首个受限完整双入口的范围完成验收。用户要求 P3 开始前停止；P3 未开始。整体设计仍 partial（P3–P5）。**

当前最终产物为 **21.1.250.125-beta**，installer SHA256 `744391b0c02afb2699de74f3de1292c3945515f89add247981dc9fff67749776`，归档 `tmp/player-region-tick-p2-final05/final125/artifacts/`。显式审核 manifest `tools/player-region-tick/reviewed-artifacts/21.1.250.125-beta.json`，SHA256 `6c99f7eba6d3fef2757a253c13e680224f87983c8cda38dc7cfa4aa5c4db07ed`。

最终实施结论在 PLAYER-REGION-TICK-IMPLEMENTATION.zh.md 末尾；机器回执 `tmp/player-region-tick-p2-final05/receipt.json` 和 `final-proof-audit.json`。所有失败和旧状态均保留，不把旧 `.124` 或失败 matrix-result 当作当前结论。

唯一生产改动：网络队列溢出 terminal 在真实 join 与 accepted 前缀后，使用当前 common listener 的原 disconnect，先发明确协议原因再关 TCP；配置态替换 listener 亦经测试。默认 false、未知扩展整次串行、开始后不重放、真实 join 后清理保持。没有 oracle worker 特批。

最终 `.125`：476 JUnit、三个选定 GameTest、schema3 144 + schema4 180、243 proof 负控制通过。两池两入口实际 worker 重叠且同区零重叠；四组 A owner(0,0) 攻击 B owner(1,0)，B health20→19、一个真实 damage packet。schema3/4 加载 oracle，UNKNOWN_MOD/worker=0，只是玩法/事件/守恒兼容证据。两池两阶段正常/溢出网络、独立组和同组原正文异常、真实 TCP loss、四模式异步聊天/转换/End credits/配置取消/原计时器、5钻石7XP重连及下界往返和保存重启、新实例落地后各77次双入口 worker 均通过。活动 Level clear/retains/GC 由原引擎入口+native池测试闭合。集成 memory Connection 仍拒绝；GUI 暂停未实测，原暂停控制流经源审计。无性能结论。

源、世界、原帧、命令、非零退出及无退出码的被终止 debugger wrapper 都保留；后者不算通过。未 commit/push/deploy 或操作现用服。**不要自动推进 P3。**

下面为接续 04 及更早历史：

---

接续 04 最新状态（2026-11-17）：整体仍 partial。主 Agent 直接完成，无 SubAgent；旧正文在后文保留历史语境。

当前最终产物已从 `.121` 推进为 **21.1.250.124-beta**，installer SHA256 `bec193f7f14402542fbc041f6bb0a730b909d0359c3a39b5e7958a203bc2809a`；同字节归档 `tmp/player-region-tick-continue04/final124/artifacts/`。最新完整实施记录见 PLAYER-REGION-TICK-IMPLEMENTATION.zh.md 的“接续 04 最终结果”，机器回执 `tmp/player-region-tick-continue04/receipt.json`。不要用后文历史 `.121` 作为当前最终产物。

本次修复：可取消网络回调/retain/溢出回滚；断线后未开始缓存调用不覆盖原遍历资格；shutdown stop-new-admission 不提前开网络门；原已消费 state scope 不因物理 socket loss/shutdown 失权，未开始票据仍拒绝。原 .123 四项 native 退役反例失败及所有中间目录保留。

当前证据：417 项主回归 + 独立 4 项 LifecycleRetirementRaceTest 全过；三个选定 GameTest 全过，其中 physicalEntitySectionCrossing 原夹具加载 200-tick 失败保留，只把测试等待上限增至 600，加载和功能断言不变。26 个 patch/generated 字节一致。fixed/persistent × off/on 的 schema3 144 场景、schema4 180 场景和 100 负控制全部通过；8 组原阶段/event/BE/PlayerList 独立顺序校验通过。两套 verifier 原 `.115` hash gate 保留，`.122/.123/.124` 显式 manifest 是测试工具静态审核注册，不影响生产准入。oracle 两入口 UNKNOWN_MOD、worker=0，无任何特批。

同 `.124` 无 oracle 四组 smoke 全过：on-fixed 独立区每阶段 238 worker、ENTITY/CONNECTION 重叠 80/94，同区各 238/0；on-persistent 独立区各242/121，同区各244/0；off=0。四组正常攻击 A owner(0,0)→B owner(1,0)、health20→19、受害端一个 damage packet。四组 production-only keepalive/chat/ping/真实跨区步行、重连、五钻石七XP拾取、原客户端进门下界往返、保存重启通过；含实际原帧/服务端状态，少量 ping latency 只是活性样本。

新增外部 JDI 隔离探针无游戏 Mod/Java agent/类重定义/准入例外：两池×两阶段完整原 worker 正文副作用后异常一次、另组 join、无重放、真实 join 后引用释放均通过独立 verifier；另四组真实客户端在已开始 worker 内关闭 TCP，实际 socket=false、原 state scope 仍有效、正常原正文返回、deferred 回调最终为零。不要把调试器暂停时间当性能证据。第一版 socket 脚本误调用 worker-fault runner 的重复故障结果不计为 socket 证据，真正 socket 证据目录带 `server-worker-socket-v2-`。

重生结果已完成进一步定位和正常落地复验：旧四组语料在 vanilla forced respawn y=100.1 停留，ENTITY 有88/87次worker、CONNECTION=0。初步 OPTIONAL_TICK_STATE 是包含另一持剑玩家的全局统计，不能归因新实例；只读 JDI 对目标玩家原捕获返回 ENTITY=NONE、CONNECTION=SPATIAL:CALLBACK_SPATIAL_BOUND_UNKNOWN，实际因为整数脚底支撑门。保持该负例，在 `server-respawn-settled-{mode}` 四组新安装中仅用一个普通 position_look 落地包到 y=100，两池新实例两个入口各77次worker、off=0，原致死/重生/新旧实例/退出清理都过。最终 `.124` 产物和生产准入没有因此修改。旧未落地语料、枚举检查失败等全部保留。

下界 off-persistent 的首个 cooldown 正值采样断言因原玩家10-tick冷却已结束而失败，最终复核 `independent-verdict-final.json` 明确记 already-zero-at-first-sample，不改旧失败。

下一步仍优先闭合 P2：Level unload/重配置/末地/传送取消、同组原正文失败及后缀、全部 eligibility、长期 overflow/shutdown/GC 退役、任意 main-thread payload/聊天过滤/混合包时序。再推进 P3 完整玩法覆盖、P4 owner包路由、P5 1/8/32 真客户端多分布/性能/长期恢复；当前没有收益结论。保持默认false、未知扩展整次串行、开始后不重放、真实join后清理。

保留整个工作树和所有失败；不 reset/clean、commit/push、操作现用服。用新的隔离目录，所有长命令 run_command.py 记实际argv/日志/退出码；JDK21、离线Gradle。下面是接续04之前的历史交接：

---

继续 /home/yc/Minecraft/Forgia 的玩家 Region Tick，按 region-threading/PLAYER-REGION-TICK-DESIGN.zh.md 推进。不要使用 SubAgent，直接自己实现、运行和验证。用户特别要求：真实协议客户端 A 站在 Region A，能否实际攻击 Region B 的 B，以及跨区实体、方块、实体方块和容器操作是否真的成功；核对服务端状态、事件、客户端反馈和物品/XP 守恒。

先读以下内容，不要从头重做：
- region-threading/PLAYER-REGION-TICK-IMPLEMENTATION.zh.md 最后的 P2 首版记录。
- region-threading/PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md、PLAYER-SHARED-STATE-CONTRACT.zh.md、PLAYER-SPATIAL-FOOTPRINT-CONTRACT.zh.md。
- tools/player-region-tick/README.md 的 P2 段落及历史 schema 3/4 套件说明。
- tmp/player-region-tick-continue03/receipt.json、当前 git diff 和新增未跟踪源码。

当前整体仍 partial，但本轮约定的“双入口首版接入与定向验证”已经完成。P0/P1、空间/共享状态/production 准入已实现；P2 首个受限 production 分支现已在两个原位入口执行完整原始正文：世界 fallback 的 guardEntityTick/tickNonPassenger→ServerPlayer.tick，以及原连接遍历里的完整 Connection.tick→listener→doTick 和前后逻辑。复用唯一原 engine、pool、lease、barrier、ledger，保留连续前缀、冲突合并、同区顺序、完整串行 fence、正文前拒绝与真实 join 后清理。默认 false 开关为 enableRegionThreadingPlayerTicks。未知 Mod/变换/监听器和不支持状态整次串行。

关键新代码：RegionPlayerTickDispatch、RegionPlayerNetworkGate、RegionPlayerTickCoordinator 的 worker 开始/返回/主线程 join；RegionThreadingEngine.dispatchPlayerPrefix/tickPlayerWorldSequence/PlayerConnectionPass。持久 Minecraft 改动在 patches/，不要只改 projects/neoforge/src 生成源码。网络门覆盖 Connection 原入站/异常/关闭回调，已有回调使本次 worker 准入失败；批次中的新回调 retain 后保序排入原服务器队列，完成/异常释放，4096 上限溢出走终止断线。worker join 不泵通用服务器任务。完整关闭/取消/退役与引用释放仍需系统性审查和验收，不能仅凭稳态无残留宣布生命周期完成。

已验证最终产物：projects/neoforge/build/libs/neoforge-21.1.250.121-beta-installer.jar，SHA256 6c09c5292778ec36a3f61c95a2ae8a1e15e3cd3c2a4650c97c6bee6f7483b761；证据归档副本在 tmp/player-region-tick-continue03/artifacts/。399 项相关 JUnit、零失败/错误/跳过，及 3 项选定 GameTest 通过。26 个 changed/untracked Minecraft patch 与原 apply 产物及生成源码逐字节一致；定向格式检查、git diff --check、工具语法检查通过。

同一 .121 新安装 fixed/persistent×off/on 四组，无任何 oracle Mod、测试变换或测试专用准入。两个真实 TCP 客户端的 production smoke 和存储语料复核均通过。独立区域采样：fixed ENTITY/CONNECTION 各 274 次 worker 正文，分别 80/105 个 tick 有实际区间重叠；persistent 各 246 次，分别 117/120 个 tick 重叠。同区窗口每阶段各 242 次 worker 正文，重叠均 0。off 的玩家 worker 均 0。四组 A=(47.5,100,24.5)、B=(49.5,100,24.5)，实际 owner (0,0)/(1,0)，普通客户端攻击使 B health 20→19，受害客户端各 1 个 damage_event 并收到 health=19。最终 owner/pending/running、network pending/readers、active batch/state claims 均 0，server/client exit 均 0。

P2 smoke 的局限务必保留：首个分支为静止、空库存、惰性加载地形、可选 criteria/scoreboard 路径闭合的原版状态。fixture 授予全部原版进度并清理累计 XP；这没有删除或跳过游戏正文，但不证明普通新玩家或未知 Mod 可并行。执行时间证据来自有界 production 内部完整调用记录，未安装独立方法观测 Mod；本套件没有服务端 damage-event 计数，也没有性能结论。已有 .115 串行基线曾通过 144 战斗/实体场景和 180 方块/容器/拾取/死亡重生场景，不能冒充 .121 的完整复验。两套旧 verifier 固定 .115 hash：迁移到新产物应增加显式审核 manifest，保留旧 hash gate，不要直接删除检查，更不能特批 oracle Mod 进入 worker。

下一步优先：
1. 先审查并补足 P2：部分提交/拒绝、前置 open 失败、不同组失败与原异常、完整世界和连接正文故障探针、原阶段/事件顺序、Netty/聊天/keepalive/payload 顺序及等待、断线/停服期间网络队列的取消/retain 释放/有界引用退役、同区和跨区传送、双向维度、重生旧实例、重连、Level unload、保存重启。保留未知扩展整次串行，不吞异常或重放开始后的 Tick。
2. 在同一最终产物上迁移并运行 schema 3/4 的正负坐标/角落/双向 PvP、普通实体攻击/挤奶、survival 挖掘/放置、BE/共享箱子、物品/XP 竞争及死亡重生守恒；动作必须发正常客户端包，命令仅建 fixture/独立读状态。
3. 按设计 P3 扩大支持路径；P4 owner 网络路由可独立后续，不能改变 NeoForge main-thread payload 默认合同。正确性门通过后再做 P5 的 1/8/32 真协议客户端、同区/分散/边界、两池/off-on 的 MSPT/包延迟/覆盖测量，未覆盖或无收益照实报告。

保留整个当前工作树、未跟踪文件和所有失败证据，不 reset/clean、commit/push 或操作现用服。尤其保留用户原有 PLAYER-WORKER-EXECUTION-AUDIT.md、RegionThreadingDeclarationsTest.java、设计文档和 tools/forgia-entity-safety/。不要覆盖 tmp/player-region-tick-* 旧证据，创建新的独立目录。tools/player-region-tick/.gitignore 的 !*.py 必须保留，否则根 .gitignore 会漏掉 Python 工具。JDK 为 /home/yc/.local/opt/jdk-21.0.13+9；使用 Gradle 离线缓存，长命令由 run_command.py 记录 argv、日志和真实退出码。用户已授权自主实现及隔离服验证，不反复索取例行许可；准确区分本阶段完成与全设计尚未完成。
