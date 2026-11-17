当前最终结论（2026-11-17）：**P2 已按设计第 7 节首个受限完整双入口合同完成验收，最终产物为 21.1.250.126-beta。P3 未开始，按用户要求在 P3 开始前停止。整体设计仍 partial（P3–P5）。**

`.125` 的完成草稿已被真实 NETWORK 线程合同反例撤回；旧 receipt.json 和所有失败/中断证据保持原样。
当前结论以本文末尾“.126 最终 P2 收口”及 `tmp/player-region-tick-p2-final05/receipt-final126.json`、
`tmp/player-region-tick-p2-final05/final-proof-audit-final126.json` 为准。下文早期状态均保留为历史。

---

最终审计补充（当前仍在核对 P2）：`.125` 已通过的有限矩阵和收口草稿保留；显式 NETWORK payload 在线程冻结/整次拒绝之间的边界正在另做正常客户端复现。完成该审计前不把下文 `.125` 收口草稿视为最终发布结论。P3 未开始。

# 玩家 Region Tick 实施与验收记录

最新结论（接续 05，2026-11-17）：**P2 已按设计第 7 节首个受限完整双入口的范围完成验收；P3 未开始，按用户要求停在这里。整体设计仍 partial（P3–P5 留待后续）。**
最终产物 `21.1.250.125-beta`，installer SHA256 `744391b0c02afb2699de74f3de1292c3945515f89add247981dc9fff67749776`。
完整收口见本文末尾「P2 最终验收结论」；回执 `tmp/player-region-tick-p2-final05/receipt.json`。
以下较早的「当前/下一步」均保留各次实施时的历史语境，以本条和最后收口为准。


原目标 run：`goal-2026-11-16T18-28-20-556Z-d7a3841a`；尝试 1–3 的历史负责范围为合并 TODO 1（P0→P1→P2）。
续作 run：`goal-2026-11-16T22-31-56-455Z-5f270fb0`，按十个独立增量推进，已交付 TODO 1–2 网络交互基线及 TODO 3 完整空间足迹、多区权限与冲突组。
当前接续 run：`2026-11-17T02-37-21-743Z-9f3f0ab7`，本项 **TODO 1：收尾当前共享状态前置协议已完成**（承接上一轮中断的 TODO 4）。调用合同及当前证据见文末；独立前置增量完成不等于双入口玩家 worker 已完成。

当前整体状态：**partial；P2 双入口受限 production 分支已实现，接续 04 的最终 `.124` 补足原正文故障、物理断线/停服退役竞态和四模式 schema 3/4 完整功能复验；完整 P2 生命周期/网络矩阵及 P3–P5 尚未完成。当前状态以文末接续 04 收口和 receipt 为准，历史记录保留。**
`tmp/pi-goal-task/goal-2026-11-16T18-28-20-556Z-d7a3841a/GOAL_SPEC.json`。
基线 `main @1943dc5919acea387bcac28352b2eeb748cadc63`。未提交、推送或操作现用服。

## 范围及证据约定

| 规格/门 | 本项交付及状态 |
| --- | --- |
| REQ-1 / AC-1 / MS-2 | 双入口 worker 为最终必须结果；当前未完成，P0 证据不可替代 P2 |
| REQ-2 / AC-2 / MS-1 / VG-1 | 完整阅读 249 行设计、672 行规格，按 P0→P1→P2 推进，保留原规格及 run ID |
| REQ-3 / AC-4 / DC-3 / PC-3 / VG-4 | P1 使用唯一 engine、现有 region mapping、实体 ledger、admission gate 与 batch 生命周期；无第二个池、实体存储或 worker 权限 |
| REQ-4 / AC-3 / DC-1 / DC-2 / VG-3 | 新目录安装→启动→trace→TCP 客户端→实际状态→正常停服；文本报告成功/失败与退出码，无新 UI |
| REQ-5 / AC-5 / VG-2 | 尝试 1–3 历史 259 项 JUnit、3 项既有 GameTest、两池开启/关闭模式原入口和同步 advancement 奖励 oracle、9 项证据控制；续作当前结果见文末，不替代 P2 worker 验证 |
| REQ-6 / AC-6 / VG-5 | 无营销需求，不适用；没有开展发布活动 |
| PC-1 / PC-2 | 未完成 P2 时始终标 partial，不称完整 MVP 或全设计完成 |

未发现仓库及上级适用 AGENTS.md。已有用户修改保持原样，初始状态、diff 和关键文件 SHA256 位于
`tmp/player-region-tick-worker01/evidence/`；原审计、declarations 测试与 entity-safety 工具未改。设计原文仅在状态处追加报告/receipt 链接，历史段落保留。
所有长命令由 `tools/player-region-tick/run_command.py` 启动一次，落盘原 argv、cwd、PID、日志、真实退出码及耗时，工具短轮询观察。

## P0 原入口与未闭合访问表

以下列出实际生成源码及已有补丁的可达路径。**这张清单记录风险和后续准入条件，不授予并发权限**。
原始两个正文不做裁剪；无法满足合同的调用必须在正文开始前整次串行。

| 原始位置/可达路径 | 对象及同步结果 | 准入前需闭合的合同 | 当前支持 |
| --- | --- | --- | --- |
| ServerLevel 原遍历 / self-driven fallback→guardEntityTick→tickNonPassenger | removed、discard/freeze、checkDespawn、距离 gate、vehicle、passengers、父调用异常报告 | 保留原 eligibility、原顺序、父调用和骑乘递归；普通实体 join 后仍维持连续前缀/fence | 原串行 |
| ServerPlayer.tick→ServerPlayerGameMode.tick | 方块破坏进度、BlockState、BlockEvent、破坏返回结果和发包 | 未破坏/已审核操作；回调和完整 block footprint 权限，不允许只移动 doTick | 原串行 |
| WardenSpawnTracker.tick | 玩家 tracker、当前位置、level 时间等 | tracker 局部状态和实际 world 查询，无隐藏共享 mutation | 原串行 |
| AbstractContainerMenu.broadcastChanges/stillValid/closeContainer | 玩家菜单、slots、remote slots、同步器、capability、共享容器 | 明确 backing storage 别名及菜单有效性同步结果；未知 menu/监听器 fence | 原串行 |
| camera、absMoveTo、ChunkSource.move、setCamera | 外区 live entity、玩家位置、chunk tracking、发送状态 | camera 自身或拥有完整对象与 tracking 协议；不能放宽全局 guard | 原串行 |
| TICK/LEVITATION/FALL/vehicle criteria→PlayerAdvancements | 全局 CriterionTrigger 映射、玩家 progress、奖励、命令/函数、同步事件 | 审计全部触发器/条件/奖励；每玩家字段不同不等于 registry/奖励独立 | 原串行 |
| updatePlayerAttributes / advancements.flushDirty | AttributeInstance、dirty set、connection | 精确本地属性/发送合同和可达回调 | 原串行 |
| ServerConnectionListener.tick→Connection.tick | synchronized 连接列表、iterator、isConnecting/isConnected、pending actions、listener、disconnection、flush、packet counters | 完整父调用，登录/配置/断线 fence；不可只派发 listener.doTick；保留异常→原断线流程 | 原串行 |
| ServerGamePacketListenerImpl.tick | block ack、firstGood 坐标、xo/yo/zo、floating/vehicle、spam/keepalive/idle | 连接与玩家同一静止归属；完整 tick，包括 doTick 返回后的 authoritative position 和断线 | 原串行 |
| ServerPlayer.doTick→Player.tick→LivingEntity/Entity.tick | Pre/Post、food/stats、sleep、effects、equipment、attributes、attachments、姿态、碰撞/液体/环境/随机数 | 所有同步 hook/未知 mixin 和活对象访问纳入合同；不能只检查 PlayerTickEvent | 原串行 |
| Player.aiStep→Inventory.tick / ItemStack.inventoryTick | item/custom extension、inventory、selected slot、mod components | 精确所有物品及已安装正文；空槽可证明不调用物品，但不自动闭合其他路径 | 原串行 |
| Player.aiStep→附近实体→playerTouch | ItemEntity/XP/普通实体/其他玩家、inventory、经验、同步成功返回 | 完整 AABB/骑乘扩展、完整多区 scope 和冲突组；无截断、无 null 外区活引用 | 原串行 |
| Player.tick food/stat/cooldowns / doTick scoreboard、complex item | 全局 Scoreboard objectives、map pixels/trackers、stats、全局 criteria、packet | shared registry 的只读/变更静止合同及共享奖励协议；复杂物品/地图未闭合 fence | 原串行 |
| PacketUtils / 游戏 handler→move/interact/useItemOn/action | target、玩家/连接、碰撞、方块、库存、NeoForge hooks、同步取消结果 | 初版 server queue 在玩家 batch 时不泵，直接网络/聊天/内部服务旁路单独审核；完整包顺序与延迟证据 | 原服务器线程 |
| 死亡/respawn/dimension/teleport/logout/reconnect/save | 实体身份、listener、connection、Level incarnation、新旧玩家实例 | session+实例+连接+独立 lifecycle generation；Level epoch 仅限自身，旧 callbacks 失效；停服真实 join 后保存 | 原生命周期 |
| Player.class / PartEntity / FakePlayer / Deployer / compatibility | riding stack、精确类/类型 server-only 声明 | 保留显式声明优先级及 FakePlayer 原路径；不删除 Player.class 保护 | 原保护保留 |

当前没有任何经过以上全部条件闭合并准入 worker 的分支。尝试 2 新增唯一默认 false 的 `enableRegionThreadingPlayerTicks`，目前只启用 P1 原线程规划/记账，诊断明确 `serial_planning, workerAdmission=false`；没有假 worker 计数。
P0 代表性 oracle 已冻结；P1 的 engine session、独立 lifecycle generation 与两阶段元数据已落地，没有复用 entity scheduler generation，也不跨 Level 比较 epoch 大小。

## 尝试 1：P0 检查及失败记录

- `build-p0-01`：exit 1，init 脚本在 buildSrc 查找不存在的 neoforge project；修复为 findProject guard。
- `build-p0-02`：exit 1，测试 source set 缺 MixinExtras；增加该 source set compileOnly 依赖。
- `build-p0-03`：exit 0，JDK 21.0.13+9，离线构建 installer + opt-in oracle Mod + 聚焦 JUnit。
  真实 argv/完整日志在 `tmp/player-region-tick-worker01/build-p0-03/`。
  RegionPlayerTransitionTest 6、RegionSelfDrivenEntityTickTest 84、RegionEntityTickPlanTest 37、RegionEntityTickPolicyTest 11、RegionThreadingDeclarationsTest 8；共 146，0 failed/error/skipped。
  原始 XML 及 summary 已复制到该目录 `junit/`，不依赖下次 Gradle 覆盖的 build/test-results。
- 所有 `install-*`：exit 0；`baseline-fixed-01` exit 1（未响应 NeoForge 配置 ping），`baseline-fixed-02` exit 1（第三方 stdout 诊断文本被误作 JSON）。修复了正常 pong 及分离原始 stdout/控制回复；没有屏蔽异常或改服务端逻辑。
- `baseline-fixed-03` exit 0：最初 schema 1 oracle；`verify-fixed-03` exit 1，原版空维度超过空闲阈值后不进入 BE tick，最初校验错误地要求所有维度都有 BE；修正为保留真实可选 BE 顺序后 `verify-fixed-03-v2` exit 0。较弱的 schema 1 只有布局坐标和成员数，不能单独证明实际 owner 坐标。
- `build-oracle-schema2` 和 `build-oracle-schema2-final`：exit 0；只重建 opt-in 测试 Mod，增加现有原始 regionFor 映射的只读诊断和 eligibility 字段，不新增权限。初始测试 Mod 已归档，最终 Mod hash 见 receipt。
- `baseline-fixed-04` / `baseline-persistent-01`：exit 1；骑乘实际成功，断言误匹配 “now riding”，真实原版反馈 “started riding”。修正断言；同时按正常客户端下一 simulation step 延迟位置回复，处理 flags 对象的 `_value`，避免与 teleport 同 tick 的旧 authoritative reset 竞争。前述失败均正常停服/save，server exit 0。
- `baseline-fixed-05` / `baseline-persistent-02`：exit 0；schema 2、实际原始归属诊断、spectator/骑乘、全收发与 raw frame 顺序记录、双方向伤害及生命周期；server/client 均 exit 0。
- `verify-fixed-05` / `verify-persistent-02` 及增加真实客户端 damage/health 包校验的 `verify-*-feedback`：均 exit 0。最终以 `oracle-fixed-05-feedback.json`、`oracle-persistent-02-feedback.json` 为准。
- `gametest-regression-fixed`：exit 0，独立新目录，3 个选择的 GameTest（physicalEntitySectionCrossing、blockEntityOwnerOperations、unsafeTickerForcesWholeRegionFallback）均通过。框架另有 testFeatureGating 自动通过；唯一注册项为 4 Passed、0 Failed、173 Not Processed、2 Disabled。XML 含 group 重复行，顶层 231/0 failure/227 skipped，不把它当 231 个已执行测试。完整原始 XML/summary 保留在 `gametest-fixed-server/`。
- `verifier-controls`：exit 0，7 项；基于上述真实语料，故意移除完整 Connection 入口、重复伤害事件、伪造 layout/owner 一致性、移除真实客户端 health 包、旧实例再次开始、截断 trace 均被拒绝，未修改原语料。这些是证据工具测试，不是玩家 worker 测试。

所有尝试的原 argv、退出码、耗时与日志由同名命令目录保存；机器 receipt 枚举全部尝试，不只保存最后通过的一次。

## 真实安装服 off 结果

两服均实际安装 Forgia 21.1.250.109-beta，JDK 21.0.13+9，3 chunk 固定区域边长（48 blocks）、2 workers、bridge/既有 entity slice 开启，玩家仍走原服务器线程。pool 开关分别 false/true；没有新的玩家 on 模式或 worker 准入。
最终目录：`tmp/player-region-tick-worker01/server-fixed-05/`、`tmp/player-region-tick-worker01/server-persistent-02/`。

A/B 为不同真实 TCP 客户端 OracleA/OracleB。跨区攻击前 A=(47,100,8.5)、B=(49,100,8.5)，间距 2 blocks；原 engine 实际映射与布局一致，分别为 overworld (0,0)/(1,0)，成员数各 1。反向攻击前重置相同坐标。没有直接调用 hurt 或 FakePlayer 代替攻击；RCON 只建 fixture/传送及独立读取 health，攻击为客户端标准 use_entity(mouse=1) 包。

| pool 配置 | 场景 | 服务端 health | 伤害 Post 事件 / 受害客户端 damage_event | 受害客户端 health | 首个 damage / health 包响应 ms |
| --- | --- | --- | --- | --- | --- |
| fixed | A→B 跨区 | B 20→19 | 1 / 1 | 19 | 15.485 / 21.404 |
| fixed | B→A 跨区 | A 20→19 | 1 / 1 | 19 | 1.551 / 48.645 |
| fixed | A→B 同区 | B 19→18 | 1 / 1 | 18 | 1.802 / 42.200 |
| persistent | A→B 跨区 | B 20→19 | 1 / 1 | 19 | 16.657 / 36.813 |
| persistent | B→A 跨区 | A 20→19 | 1 / 1 | 19 | 1.665 / 28.702 |
| persistent | A→B 同区 | B 19→18 | 1 / 1 | 18 | 1.846 / 21.310 |

同区控制组坐标 A=(44,100,8.5)、B=(46,100,8.5)，原 owner 均 (0,0)。远程拒绝组 A=(41,100,8.5)、B=(54,100,8.5)，13 blocks；两池 B health 均保持 18→18，伤害事件 0。没有扩大原版触及范围。
这些响应是同一个客户端进程单调时钟的 send→真实 receive 差值，每格仅 1 次样本；RCON 轮询观测约 52ms 的另一口径单列在 json，不混成网络 RTT。无足够样本的 p95/p99，无性能改善结论。

| 证据 | fixed-05 | persistent-02 |
| --- | --- | --- |
| server trace 行数 | 18,516 | 18,835 |
| 完整 ENTITY_START 总数 | 884 | 887 |
| 完整 Connection.tick 总数（包括非游戏态/转态） | 890 | 896 |
| 实际 doTick / PlayerTick Pre / Post | 881 / 881 / 881 | 884 / 884 / 884 |
| 单人 / 双人稳定完整 tick 窗口 | 19 / 19 | 19 / 19 |
| 冻结 / spectator / 骑乘窗口 | 9 / 9 / 9 | 9 / 9 / 9 |
| 两次换维度、1 次 clone/respawn、新实例重连 | 通过 | 通过 |
| 旧 respawn/logout 实例在之后再次开始 ENTITY/CONNECTION | 0 | 0 |
| server / client exit | 0 / 0 | 0 / 0 |
| 玩家正文 worker 次数 / 并行重叠 | 0 / 0 | 0 / 0 |

全程 trace 的服务器正文及所记录包 handler 均在原 Server thread；稳态每玩家每阶段恰好一次。两个阶段的总体计数不要求相等：连接还包括登录/配置/转态，实际调用可能不进入 doTick；独立校验只对原 eligibility 满足的稳定窗口强制一一对应。顺序为所在维度 ENTITY 返回→BE（原有被跳过分支保持跳过）→Level Post→所有维度完成→完整连接/listener/doTick→PlayerList。各进程的 HashMap 维度遍历顺序不被误当成跨进程固定值；相对 fence 保持。

**协议客户端限制：** Vanilla minecraft-protocol 库每次连接对 NeoForge recipe list 打印 1 个 PartialReadError，两服各 3 个（含重连），原 stdout 完整保留。schema 2 在解码前记录所有 raw frame id/大小/SHA256，所测伤害/health/movement/lifecycle 包正常解码；没有声称 recipe/crafting 完整兼容。最终所有被测伤害必须同时满足服务端真实 health、Post 事件数、客户端实际 damage_event 及 update_health，只有发包或快照不能通过。

## 尝试 1：基线构建产物与复跑

这是**原基线 installer**，不包含玩家 worker 功能：
`projects/neoforge/build/libs/neoforge-21.1.250.109-beta-installer.jar`，
SHA256 `e8c5f1f85f000c0289f96f990170d3640eba0964506bcd7c079c3e7f7d16c484`。
最终隔离测试 Mod `projects/neoforge/build/libs/forgia-player-oracle-1.0.jar`，
SHA256 `0391add572a6666546ec9fe836dad4a98d010a95557bc890e4efb432f1fdbe24`。
installer 及两版 fixture 另保存在 evidence 根的 `artifacts/`，receipt 包含安装时与实际运行 Mod hash。

[工具 README](../tools/player-region-tick/README.md) 提供完整新目录构建、npm ci、安装、两池选择、启动/等待、独立校验及停服/恢复步骤。精确本次命令以各 `command.json` 为准。工具源码、lockfile、server 原始日志、客户端 stdout/stderr、包序、原始 trace、XML、全部失败 exit 均保留在 `tmp/player-region-tick-worker01/`。
尝试 1 没有 Minecraft 持久源码改动。尝试 2 的 Minecraft 入口改动已同时落在下述 `patches/` 与根 `src/` 的桥接/协调器中；生成源码由补丁逐字节重建验证。

## 尝试 2：P1 原入口串行实现

实现与证据目录：`tmp/player-region-tick-worker01-attempt2/`，保留了尝试 1 报告与 receipt 的副本，没有覆盖旧日志。

- `RegionPlayerTickCoordinator.java`：唯一 engine 下的 ENTITY / CONNECTION 计划；关联 engine server-session、实际 server/player/listener/connection、Level 及现有 EntityIdentityLedger 身份、独立 lifecycle generation、tick 和 phase。epoch 只与自身 ledger 中的当前 publication 比较，不作跨维度大小排序。
- 在准备、准入、正文开始前分别重查连接、当前 PlayerList 实例、实体 publication 和原 region mapping；使用原 PlayerRegionTransitions 在静止阶段交接，未新增玩家索引或存储。
- 活跃 owner / pending 各限 4096，终态历史限 256；终态记录只保留 UUID、数字、enum、dimension 字符串和异常类名。完成/取消/拒绝清除 live binding；最近开始记录使用 weak instance identity，identityHashCode 仅作诊断，不授予权限。
- 世界阶段通过 `ServerPlayer.java.patch` 在 **原 public tick 内**增加进入/失败/退出 scope，全部原指令仍在原方法，父调用、骑乘 eligibility、原 Mixin 调用位置及顺序保留。中间曾将正文搬入 helper，复核后废弃；最终 `.111` 已移除该做法。
- 连接阶段 `ServerConnectionListener.java.patch` 在原调用位置保留完整 `Connection.tick`，包括 listener、doTick 前后、flush/disconnection/计数；没有只搬 doTick。
- scope 复用原 `RegionWorkAdmission` 与实际 batch publication/完成 latch。已经 join 的普通实体父 batch 直接复用；独立原入口以现有 batch 生命周期保护串行正文，没有派发空任务来伪造 worker。未知正文目前全部原线程执行，P2 准入保持关闭。
- 开始后异常保持原 Throwable，记录 FAILED/物理结束/JOINED，清理失败仅附加到原异常；不重放。取消只接纳未开始计划；停服/Level clear 等待真实正文结束后清理。原 Player.class riding-stack、FakePlayer、精确 server-only 声明和无参数全局 guard 保留。
- 默认 false 配置及启动/原 status raw 诊断位于 ForgiaConfig、ServerLifecycleHooks、RegionThreadingCommand；缺 bridge / fixed layout / owned storage / effective entity slice 保留完整串行，没有自动开启依赖。Workers 仍是队列 drain 键，不作为 tick 总开关。

中间通过版本（scope 修正后、同区传送失效补齐前）installer：`projects/neoforge/build/libs/neoforge-21.1.250.111-beta-installer.jar`，SHA256
`1e7249ae408a4aad43372badcc1760a6f290fb424d8fa0db6a166e8a418b03b9`。
测试 Mod：`projects/neoforge/build/libs/forgia-player-oracle-1.0.jar`，SHA256
`16db31ebc8a55a35d76ea37007f22143db0b2f974c877a9e4e05aa139e97e383`。
这是 **P1 串行规划产物，不是玩家 worker 产物**。本次最终 `.112` 副本及 manifest 保存位置见下段。

`p1-scope-build-05` exit 0：16 项新账本/Scope 控制 + 原 146 项回归，共 162，0 failures/errors/skipped。覆盖两阶段、旧 listener/重生实例、跨 Level、准入后失效、取消、重复计划、原异常对象不重放、有界历史、Level 退役、外线程拒绝、真实并发 shutdown 等待正文返回、缺 owned storage 和默认关闭。组件 seam 的 Runnable 仅验证状态机；真实正文证据来自下面安装服。
`patches-final-check` exit 0：严格重新应用持久补丁后，两个 Minecraft 文件与生成源码逐字节一致。格式检查只作用于本任务改动的源文件，没有格式化用户 declarations 修改。

| 最终 `.111` 安装服 | ENTRY 实际情况 | 结果 |
| --- | --- | --- |
| server-planning-fixed | ENTITY 900 tracked + 3 untracked serial；CONNECTION 900 tracked | 所有 tracked 已返回/JOINED，worker 0；owner/pending/running 0；实际 pool=false |
| server-planning-persistent | ENTITY 893 tracked + 3 untracked serial；CONNECTION 893 tracked | 所有 tracked 已返回/JOINED，worker 0；owner/pending/running 0；实际 pool=true |
| server-off-fixed | 原完整 ENTITY 901 / doTick 898；账本未启用 | 原 off oracle 通过，server/client exit 0 |
| server-off-persistent | 原完整 ENTITY 902 / doTick 899；账本未启用 | 原 off oracle 通过，server/client exit 0 |

各服均使用两个真实 TCP 客户端并通过相同次数/顺序、冻结/spectator/骑乘、换维度/死亡重生/旧实例/重连、实际映射和健康值 oracle。每服跨区 A→B、B→A 均 20→19，各只有 1 个服务端 Post 与受害客户端 damage_event；同区 B 19→18；13 格目标 18→18/0 伤害。P1 开关为 true 的模式依然是串行，不把它当成 P2 worker-on 证据。ENTITY 的 3 次 NOT_CURRENT_PLAYER 调用保留原版完整串行正文，显式记为 untracked，不能以丢弃正文使计数看起来一致。

`connection-failure` exit 0（预期错误探针）：真实客户端进入完整 Connection.tick→listener→doTick→Player.tick 后，在真实 Pre 事件一次性抛异常。服务端保留原 ReportedException 并按原连接异常路径断开客户端；注入副作用 1 次，CONNECTION/LISTENER/DOTICK_THROW 各 1 次，同一 tick 没有第二个该调用开始。连接账本 started 20、returned 19、failed 1、joined 20，pending/running/owners 0；server/client exit 0。这不是 mock executor，也没有清空失败计数。

最终源码 3 项既有 GameTest 回归通过；`planning-verifier-controls` 的 7 项证据控制通过。完整 argv、XML、原始 stdout、所有首次失败及最终退出码均由机器 receipt 链接。
本轮首次编译错误为枚举名 ENTITY 应为 ENTITY_TICK；首次新增测试 12 failures 均来自测试 fixture 反射 final 字段未 setAccessible；首次 patch apply 的两个新 hunk 行号错误。均保留原日志/XML/rejects 后修复并重测，没有把失败目录改成成功。

## 尝试 2：最终 `.112` 交付证据

复核补齐同一 region 内的生命周期失效：ServerPlayer 原同步 teleportTo/teleportRelative、dismount、死亡与原维度切换 seam 在正文副作用前取消未开始计划；配置态 `isAcceptingMessages=false` 也使旧游戏态计划失效。所有原同步返回值和完整正文不变，默认关闭不改变生命周期权限。直接 listener 回调、credits 和完整生命周期矩阵的 worker 准入合同仍列为后续门，不能把这份 P1 记账当完整 worker 安全证书。

`p1-lifecycle-build-06` exit 0，最终源码 **246 项 JUnit，0 failures/errors/skipped**：18 项 P1、新增同区传送旧计划与配置态失效；原 146 项，加 LifecycleRetirementRaceTest 4、EntityIdentityLedgerTest 12、RegionThreadingEngineTest 60、RegionThreadingArchitectureTest 6。原始 XML/summary 已复制到该命令目录，不依赖 Gradle 下次覆盖。该命令也完成格式、严格 patch apply、最终 installer 与测试 Mod 构建；持久补丁逐字节还原两个 Minecraft 源码。

最终可安装产物 `projects/neoforge/build/libs/neoforge-21.1.250.112-beta-installer.jar`，SHA256
`9c43d86eadd2d8600faa5642cd71d9dbc2d1af5ade3540b82a71801a71a3dd4b`。
测试 Mod SHA256 仍为 `16db31ebc8a55a35d76ea37007f22143db0b2f974c877a9e4e05aa139e97e383`；两者均保存在 `tmp/player-region-tick-worker01-attempt2/artifacts/`。
新目录安装和 `--player-planning` / `--persistent` / 关闭恢复、原 status raw、独立 trace 验证与异常探针的完整命令见更新后的工具 README 及本次各 `command.json`。

| `.112` 最终安装目录（本尝试目录下） | 原始 ENTITY / doTick | P1 账本 | 清理与退出 |
| --- | --- | --- | --- |
| server-final-fixed | 1011 / 1008 | ENTITY 1008 tracked+3 untracked；CONNECTION 1008；各 tracked 全 returned/JOINED | owner/pending/running 0；server/client 0/0；实际 persistent=false |
| server-final-persistent | 994 / 992 | ENTITY 993 tracked+1 untracked；CONNECTION 992；各 tracked 全 returned/JOINED | owner/pending/running 0；server/client 0/0；实际 persistent=true |
| server-final-off-fixed | 922 / 919 | 默认关闭，原始路径，未启用账本 | 原 oracle 通过；server/client 0/0 |
| server-final-off-persistent | 917 / 914 | 关闭，原始路径，未启用账本 | 原 oracle 通过；server/client 0/0 |

开启与关闭四组都验证原方法 Pre/Post、BE/维度/连接/PlayerList 顺序、固定 region 实际归属、freeze/spectator/骑乘、换维度、重生旧实例失效、重连。同前述坐标设置，A→B、B→A 均 health 20→19，伤害 Post 与受害客户端 damage_event 各 1；同区 B 19→18；远程 13 格 B 18→18 且无伤害。P1 开启仍为串行，玩家 worker 次数和重叠均 0。

| 配置 | A→B 跨区 damage / health 响应 ms | B→A 跨区 damage / health 响应 ms | 同区 damage / health 响应 ms |
| --- | --- | --- | --- |
| fixed P1 | 23.508 / 31.390 | 2.781 / 47.099 | 11.259 / 21.297 |
| persistent P1 | 44.577 / 80.737 | 1.744 / 20.116 | 19.606 / 55.616 |
| fixed off | 14.720 / 46.191 | 1.506 / 37.719 | 1.585 / 30.621 |
| persistent off | 14.684 / 40.775 | 1.751 / 31.789 | 1.967 / 25.046 |

每格仅一次协议包样本，是同客户端进程 send→receive 的实测差值；启动、换维度与磁盘保存负载同时存在，不据此推导收益或 p95/p99。NeoForge recipe list 的第三方库 PartialReadError 原 stdout 保留，所测伤害/health 正常解码，未声称完整 crafting 客户端兼容。

独立最终校验器要求账本 serial=tracked started+untracked，而且等于原方法 trace 的实际完整入口数；不是用账本自证次数。`final-verifier-controls` 7 项正负控制通过。

`final-connection-failure` exit 0：同一最终 `.112` 真实连接探针产生恰好 1 次原预期异常；连接账本 prepared/started 22、returned 21、failed 1、joined 22，原 ReportedException 保留、客户端被原连接错误处理断开。注入副作用 1 次，没有第二次同调用开始，pending/running/owners 0，server/client exit 0/0。

`final-gametest` 首次 exit 1：所选 3 项中 2 passed，unsafeTickerForcesWholeRegionFallback 因 “mixed chunks have not loaded” 失败，保留该目录全部原始日志/XML。其他独立测试服全部退出后，代码和断言不变，新目录单独执行 `final-gametest-retry`，所选 3 项通过；实际退出码与目录由 receipt 记录。没有修改原测试、放宽断言、覆盖失败或把失败解释为已确定的环境问题；根因未确定，单次复跑通过不证明该 chunk 加载偶发失败已修复。两次 GameTest 都不是玩家 worker 测试。

## 尝试 3：simple-criterion 监听器归属前置协议

本轮没有派发完整玩家 worker。继续 P2 调查时确认 `SimpleCriterionTrigger` 全局对象的 IdentityHashMap 同时保存所有玩家的可变监听器集合，而 `PlayerAdvancements.award` 在同步奖励前会回调该 trigger 移除监听器。单有 region lease 不能使这份全局存储安全。本轮实现其玩家本地存储分支，并验证原始同步行为；不能据此放行任意条件、奖励、scoreboard 命令或事件回调。

新增 `src/main/java/net/neoforged/neoforge/server/threading/region/PlayerCriterionListeners.java`，由每个**原始** PlayerAdvancements 持有；监听器按原 trigger 对象身份存储，仍使用原 HashSet，而原 SimpleCriterionTrigger 内的筛选、临时选中列表与同步 Listener.run/award/reward 正文保留。未增加全局锁、executor、第二份实体身份或 ServerPlayer 替身；没有把回调排到屏障后或吞异常，也不复制集合来改变原重入 ConcurrentModificationException 行为。

模式由原 owner 创建时捕获。有效 fixed-layout / REGION_OWNED / entity slice 依赖未满足，或 `enableRegionThreadingPlayerTicks=false` 时，原 `SimpleCriterionTrigger.players` 字段及原全局 map 路径完整保留；有效启用规划的 owner 使用自己的存储。engine 退役后 owner 不会切换到另一份表，重载/stopListening/原 setPlayer 继续原生命周期。仍只有同一个默认 false 的玩家配置，没有自动启用实体/存储依赖。native worker 对本地存储的读写依然通过原无参数 global guard 被拒绝，**存储归属不等于 player worker 权限**。

Minecraft 持久修改新增于 `patches/net/minecraft/server/PlayerAdvancements.java.patch` 和 `patches/net/minecraft/advancements/critereon/SimpleCriterionTrigger.java.patch`，根 src 为上述存储/桥接。add/remove/removeAll 保持原方法内的统一尾部返回位置，未新增早退绕过原尾部回调。已有 ServerPlayer / Connection 两入口补丁未移位。严格 applyPatches 后，四个 Minecraft 文件与生成源码逐字节一致。

同时收紧 P1 计划的 publication 检查：只接纳 ADMITTED/TRACKED，要求原 ledger publication 的 UUID 和 int entity ID 均与实际玩家一致。DEPARTING 即使仍返回 isLive，也不能作为可执行计划；未发布的数值 ID 改变同样在首个正文效果前拒绝。两项新控制验证该行为，未把 Level epoch 用作跨世界会话代次。

首个完整回归构建 `criterion-final-build-07` exit 0：258 项 JUnit，0 failures/errors/skipped。补齐实际依赖门后 `criterion-final-build-08` exit 0：**最终 259 项 JUnit，0 failures/errors/skipped**，没有移除原用例。其中 `PlayerCriterionListenersTest` 11 项覆盖原触发器/进度/XP 奖励、重复监听器、撤销/重载、原 setPlayer 归属、原谓词异常对象、重入集合异常、32 个独立 owner 的并发注册/筛选、关闭路径和两池 native 拒绝；`RegionPlayerTickCoordinatorTest` 增至 20。32-owner 项是组件并发测试，**不是 32 网络玩家负载，也不是完整玩家正文并行**。原 246 项相关回归继续通过。

最终 installer：`projects/neoforge/build/libs/neoforge-21.1.250.115-beta-installer.jar`，SHA256
`04d6c8edb1959eee74f90f67971f21c1de1d7bdb482380a1344ffc8007010c8c`。
测试 Mod：`projects/neoforge/build/libs/forgia-player-oracle-1.0.jar`，SHA256
`be1df2dd53bcfc1b6922c021d1072aa9fde033cf19273bc3210fe0530f796758`。
这是 P1 串行规划及存储前置协议产物，仍没有玩家 worker。副本与本轮证据保存在 `tmp/player-region-tick-worker01-attempt3/`，前次报告和 receipt 已另存，未覆盖历史日志。

中间 `.114` 的真实安装服使用两个 TCP 协议客户端和测试 datapack 的 `minecraft:tick` criterion。其原奖励含 3 XP 与执行函数 `scoreboard players add @s forgia_criterion 1`；脚本只创建空 objective、正常登录玩家、读取状态及撤销 A 的 criterion，没有直接授予测试 advancement、设置经验或代写奖励分值。

| 最终安装服（本轮目录下） | 实际监听器存储 | 原首轮奖励 A/B | 仅撤销 A 后 A/B | 重复或跨玩家污染 |
| --- | --- | --- | --- | --- |
| server-final-on-fixed | 不同原 advancement 实例；local=true，各 50 个 trigger；全局 TICK 表无 owner | XP 3/3；计分 1/1 | XP 6/3；计分 2/1 | 0 |
| server-final-on-persistent | 不同原 advancement 实例；local=true，各 50 个 trigger；全局 TICK 表无 owner | XP 3/3；计分 1/1 | XP 6/3；计分 2/1 | 0 |
| server-final-off-fixed | 关闭，原全局 trigger map 路径；local=false | XP 3/3；计分 1/1 | XP 6/3；计分 2/1 | 0 |
| server-final-off-persistent | 关闭，原全局 trigger map 路径；local=false | XP 3/3；计分 1/1 | XP 6/3；计分 2/1 | 0 |

独立 validator 对照单独的 `player-criterion-storage.json`、原服务器命令响应与原 trace：A 的事件恰为 GRANT→REVOKE→GRANT，B 只有 GRANT；每次 GRANT 均位于完整原 ENTITY_START/RETURN 之间。奖励后移除监听器，后续 tick、换维度、死亡重生、重连没有再触发测试奖励；不把收到包当成奖励成功。9 项证据工具控制包括删除原进度事件和伪造重复奖励分值，均要求拒绝。
四组还重复现有双入口/原事件和阶段顺序、固定实际 region 归属、跨区双向 PvP/同区/远程拒绝及代表性生命周期 oracle。玩家正文继续原线程，worker 次数和重叠为 0；本轮不作 MSPT 或速度收益结论，也不将 XP 奖励验证扩张为死亡掉落/容器守恒认证。

首次失败和修复保留：`criterion-build-01/02` 的 patch hunk 行号/计数错误及 rejects；`criterion-build-03` 在测试启动时遇到 split package 模块冲突，未执行用例，不伪计为游戏测试失败；新测试移到原 unittest 包，使用现有池的反射构造做拒绝控制。`criterion-build-04` 30 项中 8 个 fixture 失败（LootContext 缺 server registry、Mockito 嵌套 stubbing），补齐后 `criterion-build-05` 30/0；后续 `.113/.114/.115` 聚焦全回归均通过。`.113` 安装过的两个目录保留为中间准备结果，`.114` 四组完整运行与校验通过的证据保留。最终 `.115` 补依赖门后使用全新目录重验，见 receipt 最终产物矩阵；没有复用原世界覆盖中间结果。原失败不会被后续成功覆盖。

## 尝试 1–3 结束时尚待完成（历史合并 TODO 范围）

P0 的代表性双入口、网络伤害和生命周期 oracle 已完成；完整 eligibility 矩阵（暂停/step、未加载邻区、死亡 tick 各边界、末地 credits、取消传送、共享状态/未知回调、保存重启等）仍需补齐。静态可达路径表还不是全路径并发安全证书。

P1 原线程计划、归属及 Scope 已实现，完成了上述代表性真实入口与状态机验证；未把完整 eligibility/全生命周期矩阵称已认证。P2（两个原位完整 worker、逐阶段真实 worker 拒绝/取消/异常 join、不重放及关闭清理）**尚未实现**。本项未达到 TODO 1 的 Done when；不能进入 TODO 2 的通过前置门，也不能把串行规划模式跨区攻击成功当玩家并行验收。

后续仍继续 TODO 1：读取当前 P1 代码及原入口证据，补齐 P0/P1 未覆盖分支，闭合 vanilla 必经共享路径及同步 callback 合同，再实现首个 P2 worker 分支。不要从本测试 Mod 的类型、hash 或事件监听器数量推导 worker 权限。未知回调在正文开始前整次串行；当前 Player.class riding-stack、FakePlayer、精确 server-only 兼容声明与全局 guard 均保留。

P3/P4/P5 属于后续 TODO。当前未实现/未验证 on 模式跨区 PvP、普通实体伤害/交互、跨界破坏/放置、实体方块/共享容器/拾取/XP 守恒、角落/负坐标矩阵、8/32 客户端、玩家 worker 与网络旁路互斥、scope/lease 零残留及性能收益。完整 MSPT mean/p95/p99 未测，不作性能结论。所有未通过项在机器 receipt 明确列出。

## 续作 TODO 1：真实 TCP 战斗及普通实体交互基线（完成）

本节为 `goal-2026-11-16T22-31-56-455Z-5f270fb0` 十项增量中的第一项；上节「不能进入 TODO 2」属于此前合并 P0→P1→P2 任务的历史结论。本次独立 TODO 1 的 Done when **已满足，可进入新 TODO 2**，整体目标仍 partial，P2 玩家 worker 尚未实现。本项只修改 `tools/player-region-tick/` 和实施报告，未修改 core/patches、默认配置、现用服或用户已有修改，没有 commit/push。开始与结束的 tracked diff 逐字节相同，证据为本目录 `start.diff` / `end-tracked.diff`。

全部新证据保存在 `tmp/player-region-tick-continue01/todo1/`，未覆盖前三次成果。完整机器收据：[receipt-v2.json](../tmp/player-region-tick-continue01/todo1/receipt-v2.json)，SHA256 `9f46633282b642c6f3051e3d28881c09f6fd6689a6f31d90d02fb2722ac7cf52`。收据包括逐次命令/真实退出码、每个场景反馈延迟、工具源码清单和原始证据 SHA256；旧 receipt 保留，v2 只澄清 recipe 异常标题数与堆栈文字出现次数的区别。

新增 `run_interactions.py`、`verify_interactions.py`、`decode_interaction_frames.cjs` 和 `test_interaction_validator.py`，扩展原客户端与 opt-in oracle。所有游戏操作均来自两个实际 TCP 客户端 OracleA/OracleB 的标准 `use_entity`：攻击 mouse=1，右键 mouse=0/hand=0。命令只建立地面、坐标、health/item/tag fixture 或读取状态，不调用 hurt/kill 代替攻击。原完整 `Player.interactOn` 只调用一次并观测同步结果；`oracle_cancel` 标签仅让测试监听器明确取消对应 fixture 的事件，不授予任何 production/worker 权限。

每服 **36 场景**：20 次双向 PvP（正/负 X、零边界、正/负 Z、正/负/混合角落、同区、13 格拒绝），6 次跨区 cow 攻击、6 次跨区挤奶（正/负 X 和正角落，两方向），以及攻击取消、挤奶取消、空手 PASS、远程挤奶拒绝各 1 次。整数命令 X/Z 使用原版方块居中规则，例如 47→47.5；角落小数保持精确值。跨区正常距离为 2 或约 2.263 blocks，实际坐标和原 engine owner 独立核对；13 格控制没有扩大原触及范围。

| 配置 | 新安装目录（均在本证据目录下） | 场景/真实伤害/挤奶 | trace / 客户端记录行 | server/client exit | 独立 verdict |
| --- | --- | --- | --- | --- | --- |
| fixed，P1 off | `server-off-fixed-02` | 36 / 24 / 6 | 40,591 / 6,971 | 0 / 0 | passed |
| fixed，P1 on | `server-on-fixed-01` | 36 / 24 / 6 | 40,909 / 6,987 | 0 / 0 | passed |
| persistent，P1 off | `server-off-persistent-01` | 36 / 24 / 6 | 40,745 / 6,961 | 0 / 0 | passed |
| persistent，P1 on | `server-on-persistent-01` | 36 / 24 / 6 | 40,789 / 6,975 | 0 / 0 | passed |

共 **144 个场景、96 次真实伤害、24 次真实挤奶**。每次成功攻击要求：最终真实 health 下降、恰好 1 个匹配 source/target 的服务端 `LivingDamageEvent.Post`，且事件位于原始包 handler 内；受害客户端收到恰好 1 个对应 damage_event，PvP 还要求实际 update_health 与最终 health 一致。cow 攻击均 10→9；其 health 由服务端读取，伤害反馈来自攻击客户端实际收到的 cow damage_event。PvP 首次 20→19，后续连续扣血，远程对照保持 11→11，事件/客户端 damage 均为 0。

挤奶必须同时满足 cow health 不变、原同步返回 `CONSUME`、真实总库存 bucket 1→milk_bucket 1，以及客户端实际库存槽内 1 个 milk_bucket；服务端独立原 NBT 命令结果也须一致。取消攻击无伤害，取消挤奶同步 `FAIL` 且保留 bucket；空手同步 `PASS` 且空库存；13 格挤奶既无交互正文/事件，也无物品变化。仅发包或 ack 不能通过。

原始包证据包含收/发**解压后的协议帧字节 base64、长度与 SHA256**，并保留解码包与双方 client marker。单独 Node 进程从字节重新解码被测 use_entity、damage、health、inventory 包，Python 校验器再与原事件、原 handler、实际坐标/索引 owner、前后状态及命令读取结果对照；没有把 runner 的 passed 字段作为充分证据。这不是 TCP 抓包，也不声称完整 recipe/crafting 解码兼容。每服两个连接各有 1 个原 recipe PartialReadError 诊断标题，原 stdout 原样保留；收据同时明确标题计数 2、含堆栈的文字出现次数 4。

每组语料运行 10 项负控制，**共 40 项全部拒绝**：缺受害客户端 health、缺实际 damage、重复服务端 damage、伪造 engine owner、缺最终普通实体状态、远程伪成功、缺真实 milk 库存、伪造同步取消返回、raw action 与解码包不一致、原帧损坏。序号会在删除/插入控制后重排，不能只靠序号断裂拒绝缺证据。完整结果位于 `controls-01`、`controls-on-fixed-01`、`controls-off-persistent-01`、`controls-on-persistent-01`。

P1 on 的实际账本：fixed ENTITY started/joined 2225、serial 2227（2 个 untracked 原入口保留），CONNECTION started/joined/serial 2228；persistent ENTITY started/joined 2220、serial 2222（2 个 untracked），CONNECTION 2222。两阶段 worker 都是 **0**，所有观察到的玩家正文和包 handler 仍在原 Server thread。P1 off 未启用账本，实际原方法 trace 仍存在。四服最终 owners/pending/running 均 0；此处仅证实 P1 记账清理，不扩张为未来 worker scope/lease 清理认证。

本次只执行 `:neoforge:playerOracleJar`，`build-oracle-01` exit 0；core compile 为 up-to-date，未重新构建 core/installer。实际安装沿用 `.115`：

- installer：`projects/neoforge/build/libs/neoforge-21.1.250.115-beta-installer.jar`，SHA256 `04d6c8edb1959eee74f90f67971f21c1de1d7bdb482380a1344ffc8007010c8c`；副本在新证据 `artifacts/`。
- 新 opt-in oracle：`tmp/player-region-tick-continue01/todo1/artifacts/forgia-player-oracle-1.0.jar`，SHA256 `92ac64a4cddc8b2a7133689177edf83fb64e7eda8d89b2d23bcad4cd725889b2`。
- 实际安装 server jar SHA256 `52883505f0dffe9bbc15d1246bf0dfb0eaf904f2551d8f2a3e21f349f7d0f61f`，universal jar SHA256 `f6eb03586719a5429404201bbcd1cd3b615ac57e8be6a89ce7cb8bbbbea9c3e0`；后者与 installer 嵌入字节一致。校验还核对落盘配置及实际 engine 的 pool/P1 模式。

本增量未改 core，不重复运行历史 259 JUnit/3 GameTest，也不将这些历史数字当成本次结果。当前 focused 验证为 oracle 编译、四组网络矩阵及独立 verdict、40 项拒绝控制，以及 Python/Node 语法检查。所有正式工具源码通过 `git check-ignore` 检查（预期 exit 1、无忽略源码），node_modules/pycache 保持忽略；源码快照在 `final-sources/`。

失败保持原样：`network-off-fixed-01` exit 1，已完成 20 次 PvP 后在线 trace 读取遇到正在写入的半行 JSON，server/client 仍 0/0；修复为只读已完成行后换新目录重跑，最终 verifier 仍强制完整 trace。`verify-off-fixed-02` exit 1，整数 X/Z 被原版居中而最初断言使用字面值；按原命令语义修正后 `verify-off-fixed-02-v2` exit 0，复用未改变的成功游戏语料。`matrix-01` 因该 verifier 失败 exit 1，后续三组在 `matrix-02` 完成且 exit 0。所有失败命令、原 stdout、状态、退出码与新通过结果并存，没有覆盖或把超时当成功。

REQ-1/AC-1/MS-2 对应真实客户端游戏行为增量，REQ-2/AC-2/VG-1 对应本项完成与有界后续交接，REQ-5/AC-5/VG-2/VG-3 对应原帧/状态/事件/反馈对照和拒绝控制。整体 AC-1 中完整玩家 worker 仍未满足，不能据此宣称 P2/P3/P4/P5 完成。

**交接 TODO 2：** 复用 README 的新目录安装、`run_interactions.py` 与 schema 3。每个场景保留服务端及客户端 `<label>_before/after`、标准操作包、原 handler/事件、独立最终状态与库存；为 block/container/item/XP 增加对应状态和守恒 verifier 分支。当前 `.115` hash gate 是基线固定输入，后续真正 worker installer 需显式更新审核后的 artifact manifest，保留完全相同的几何/交互场景。破坏/放置、实体方块/共享容器、拾取/XP 属于新 TODO 2；production 多区访问/回调合同、两个完整 worker 入口、异常 join、网络静止互斥、完整生命周期及最终性能属于后续 TODO。本项没有启动或修复这些下游工作。

## 续作 TODO 2：跨界方块、共享容器、物品/XP 及死亡重生基线（完成）

本项 Done when **已满足，可进入 TODO 3**；整体目标保持 partial，完整玩家 worker 仍未实现。新增观测、真实协议场景及独立守恒校验均位于 `tools/player-region-tick/`，没有修改 core/patches、默认准入或用户原有修改，没有 commit/push。新证据独立保存于 `tmp/player-region-tick-continue01/todo2/`，TODO 1 与此前失败语料保持原样。

最终机器收据：[receipt.json](../tmp/player-region-tick-continue01/todo2/receipt.json)，SHA256 `fc7c21e9a211ab529b1b5cd8546c38327340827773e9daec155229df7441412f`。收据包括每次 argv/真实 exit、四组实际安装模式、最终原始证据和源码 SHA256、失败原因、scope 与下游交接。最终独立 verdict 均为对应安装目录 `resource-run/independent-verdict-final.json`；`verify-latest-*` 与 `controls-latest-*` 保存统一最终校验器的实际命令结果。

每服 **45 场景，共 180 场景通过**：

- 正/负 X、正/负角落的两侧各执行 survival start/finish 挖掘、放置、箱子打开与 shift-click，共 32 场景。破坏后真实 dirt→air，原 Break 事件和同步返回匹配，地面/库存所得恰好 1；放置后 air→dirt，原 Place 事件、客户端实际块更新及 dirt 2→1 扣减一致。BE 打开/点击验证实际 chest 实例及库存，无命令代写被测结果。
- 挖掘/放置的同步取消与远距拒绝共 4 场景。取消保持原 `false` / `FAIL`，无额外块、物品或 XP 变化；远距请求不进入成功正文/事件。客户端 ack 不能替代最终状态或扣增证据。
- 正 X 与负角落的两个真实客户端打开同一箱子，再在同一控制回合分别发送普通 QUICK_MOVE，竞争唯一 11-diamond stack，共 4 场景。两次原 quickMoveStack 返回总和恰为 11，两个玩家与箱子总量保持 11。正确预测的 click 本可不返回 slot 更新，所以客户端用正常 close/use 包重开，读取真实完整库存；两人的原始 window_items 与最终服务端槽位分别一致。
- 两客户端同时靠近同一 5-diamond item 和同一 7-XP orb，共 2 场景。两人实际到达 x=47.7 / 48.3、y=100、z=8.5，处于相邻实际 owner；各目标恰好一个成功拾取事件、对应真实 collect 反馈及库存/XP 更新，目标消失，总量不变。
- 真实 TCP PvP 致死、标准 respawn、按实际掉落坐标发送正常移动包回收，共 3 场景。没有命令杀死被测玩家或执行拾取。原 damage、掉落、clone/respawn 均唯一，受害客户端真实 health=0 后收到 respawn。fixture 为 5 diamonds、一级且 0 level-progress（当前 7 XP）；原版死亡掉落恰为 7 XP，重生 0，回收后总量仍为 5 diamonds/7 XP。测试同时保留攻击者的 1 把剑，数量无额外变化；剑的正常耐久变化不被误作物品丢失。

| 配置 | 最终新目录（本证据目录下） | 场景 | trace / 客户端记录行 | server/client exit | 最新独立 verdict |
| --- | --- | --- | --- | --- | --- |
| fixed，P1 off | `server-final-off-fixed` | 45 | 45,020 / 7,986 | 0 / 0 | passed |
| fixed，P1 on | `server-final-on-fixed` | 45 | 43,600 / 7,849 | 0 / 0 | passed |
| persistent，P1 off | `server-final-off-persistent` | 45 | 44,636 / 8,015 | 0 / 0 | passed |
| persistent，P1 on | `server-final-on-persistent` | 45 | 43,428 / 7,921 | 0 / 0 | passed |

四服原玩家正文及记录的包 handler 仍是原 Server thread，两个阶段 **worker=0**。fixed P1 ENTITY started/joined 2357、serial 2359（保留 2 个 untracked 原入口），CONNECTION 2357；persistent P1 ENTITY started/joined 2348、serial 2350（2 个 untracked），CONNECTION 2348。off 模式账本未启用，原入口 trace 仍保留。四服最终 owners/pending/running 均 0；这只证明当前 P1 记账清理，不冒充未来 worker lease/scope 清理认证。

新增 `run_resources.py`、`resource_scenarios.py`、`verify_resources.py`、`decode_resource_frames.cjs`、`test_resource_validator.py`，扩展原客户端批量标准包、实际菜单跟踪，以及 opt-in ResourceOracle / GameModeMixin / ChestMenuMixin / 原 listener 观测。每个 `<label>_before/after` 之间只允许状态读取命令；fixture setblock/fill、item/level 设置和清理旧 fixture item/orb 的 kill 均发生在窗口外，不替代被测挖掘、放置、死亡、转移或拾取。

独立校验从实际原帧 base64/长度/hash 再解码标准包，核对原 handler 和完整 doTick 中的事件、实际目标坐标/engine 注册 owner、同一个 BE、客户端块和槽位/collect/XP/respawn、原 NBT 与 experience/block 查询响应。每格都比较两玩家、光标、被测容器/块、全部活地面物品/XP 的前后总量；runner 自述的读取结果还必须逐字匹配单独 commands.ndjson 原响应。死亡后旧实例仍留有的 XP 字段不计为可用 XP，level/points 与原查询独立一致。原版 advancement 可能在 click 中同步产生多个 amount=0 XP 回调，原事件全部保留；非零 7-XP 获得仍要求恰好一次并位于完整原 doTick 内。

发现并保留一个实际归属时序边界：最终 persistent/off 的同一掉落物在 tick 1127 已移动到 region (0,0)，原 engine 索引仍为 spawn region (1,0)；后续快照在同一 UUID/实例上变为正确 owner (0,0)。两个紧邻标记记录了这一次交接。最终 verifier 不伪造即时 owner，而要求原始 spawn 帧、同一实体身份、至多一个相邻区跨度及后续真实 owner publication 互相印证，并在 `observedGroundOwnerHandoffs` 显式报告；初始拾取目标、静止玩家及块仍严格检查实际 owner。此观察不是 worker 权限，TODO 3 应据此在静止捕获/完整足迹中处理尚未发布的移动，不能把中心坐标或旧 index 直接当并发权限。

每组 **15 项负控制，共 60 项全部拒绝**：仅 ack 无实际块更新、放置漏扣、重复地面物品、重复非零 XP / XP orb、取消后仍变更、伪造 block owner / transient ground owner、缺原 Place 事件、同一共享 stack 两赢家、缺最终 BE 状态、重复死亡掉落、缺真实 XP / collect 反馈、原帧操作不一致。删插控制重排序号，不能只靠序号断裂拒绝。另有 **2 项目录保护控制**：两个套件共享 trace 文件，误复用另一套件的安装目录会在启动前拒绝，原 trace hash 保持不变。完整功能套件因此要求每个 suite × mode 都使用独立新目录。

只构建 opt-in oracleJar：`build-oracle-01` exit 0，新增 chest quick-move 观测后 `build-oracle-02` exit 0；core compile 为 up-to-date，未重建 core/installer。沿用 `.115` installer SHA256 `04d6c8edb1959eee74f90f67971f21c1de1d7bdb482380a1344ffc8007010c8c`。最终 oracle 位于 `tmp/player-region-tick-continue01/todo2/artifacts/forgia-player-oracle-1.0.jar`，SHA256 `2501b46eca78d1912cb30d4892f06bed46797c75f4b90d4e07d9eb8b5bca71fe`；installer 副本同在 artifacts。四组实际安装 jar/hash/config/engine pool 逐项核对。历史 259 JUnit/3 GameTest 本项没有重跑或重新计作当前通过数。每服两个连接的原 recipe PartialReadError 诊断标题均为 2，stdout 完整保留，不声称完整 recipe/crafting 解码兼容。

所有失败与中间成功目录保留：

- `verify-off-fixed-01` exit 1：runner 虽 exit 0，拾取移动尚未完成传送静止归属且旧 fixture 堵路，死亡掉落也可出平台；这份语料未被当作成功。修正等待、清空通路和按真实坐标回收后换新目录。
- `verify-off-fixed-02` / `-v2` exit 1：分别是误把原版 amount=0 advancement XP 当拾取、respawn wire enum 与等价 numeric 0 的比较；纠正原版语义后 `-v3` exit 0，未改原语料或丢事件。
- `verify-off-persistent-01` exit 1：随机掉落沿 Z 出窄平台，物品总量仍守恒但未实际回收；扩宽平台后统一执行最终四组，未放宽回收断言。
- `verify-final-off-persistent` exit 1：对移动掉落物要求即时 index=coordinate 过强；独立 spawn/后续 publication 证据闭合后 `-v2` exit 0，并显式保留交接诊断。`matrix-01`、`matrix-final` 和早期 `finish-evidence` 都因相应失败以 exit 1 停止；最后配置由 `matrix-last` 完成。统一最终四组 verifier/60 控制在 `finish-latest` 全通过，没有将任何缺退出码/失败解释为成功。

源码/Node/Python 检查通过，新正式源码均未被忽略，node_modules/pycache 继续排除；源码快照在 `final-sources/`，已有 tracked diff 与本项开始逐字节相同。REQ-1/AC-1/MS-2 对应本增量真实游戏行为，REQ-5/AC-5/VG-2/VG-3 对应原始包/事件/owner/状态及守恒和负控制；独立 TODO 2 的 Done when 完成，不等于整体 AC-1 中完整玩家 worker 已完成。

**交接 TODO 3：** 使用 schema 3 的战斗/实体套件和 schema 4 的资源套件，按 README 为每个套件和模式创建独立安装。后续 worker artifact 需要显式更新审核后的 hash/manifest 和阶段调度断言，保持同一标准操作、几何、原始证据和守恒 verifier。下一项只实施原 engine 的完整空间足迹、多区权限与冲突组；本项没有开始该实现。XP 仅认证本 fixture 的 level 0/1、单个 7-XP orb 和一级死亡；其他等级的原版 XP 损失、mending、任意 mod item/effect、完整生命周期/网络静止互斥、双入口真实 worker 及性能仍待后续验收，整体保持 partial。

## 续作 TODO 3：完整空间集合权限与冲突前缀（完成）

本项 Done when **已满足，可进入 TODO 4**；整体目标仍 partial，世界/连接两个完整玩家 worker 入口尚未实现。交付的是可复用的空间前置协议，不是新的 player worker 准入开关。P1 `true` 仍运行原线程、worker=0；没有新 installer/安装服验收或性能结论。唯一原 engine、原 pool、原 ledger、原 RegionLeasePack、原 batch 和原 ownership scope 保留。

新证据位于 `tmp/player-region-tick-continue01/todo3/`，完整机器收据 [receipt.json](../tmp/player-region-tick-continue01/todo3/receipt.json)，SHA256 `65872201b117ff3e0dea3cb4fa80d8cab8205865a504963e925b5c9c1ba9ca1f`。详细支持/拒绝路径、原调用边界与下游协议见 [空间权限合同](PLAYER-SPATIAL-FOOTPRINT-CONTRACT.zh.md)。没有覆盖 TODO 1–2 的网络原帧、报告或失败目录；没有 commit/push、现用服操作或改动 Pi 编排器。

新增根源码 `RegionPlayerFootprint`、`RegionPlayerSpatialPlan`、`RegionPlayerSpatialLease`、`RegionPlayerScopedQuery`、`RegionPlayerConflictPlan`，以及两项聚焦测试类。持久 Minecraft 修改落在 patches 中，生成源码仅经原 apply/setup 同步，没有生成目录单独修改。实际核心行为：

- 首个已审计几何分支为普通站立、无输入/插值/跳跃/骑乘/camera/portal/active destruction、空装备/效果、完整惰性支撑面的静止空间分支。原 Player.aiStep 查询保持 BB.inflate(1,.5,1)，含原 LivingEntity.pushEntities；原 section 的 X/Z -2/+2、Y -4/+0 搜索余量一并租借。块/环境/流体/碰撞/支撑/姿态/连接 noBlocksAround 的完整范围保守捕获，未知/无界/未加载/超过集合上限在正文前整次串行。
- 几何接口可表达原骑乘 union/inflate，不缩小其半径；production 捕获当前整条 riding chain 拒绝，Player.class/FakePlayer/精确 server-only 声明未删除。只凭这些几何条件不能闭合全部可达 Mod 回调或全局状态；后续未满足 callback/global-state gate 的完整正文仍串行。
- 全部 region 中的原已加载 chunk 引用、原对象/唯一 ledger identity、原 index 与当前位置相互核对；任一缺失全拒绝。调用原完整 server query 与 index 对照，不能遗漏物理 bucket 中尚未正确 publication 的目标。TODO 2 观察到的移动掉落旧 owner 返回 OWNER_PUBLICATION_PENDING，不伪造 index 或用中心坐标越权。
- `reservePlayerSpatial` 必须挂在原 engine 发布中的父 batch/原 server admission 下，原普通实体/BE groups 已物理 join 后，按原 RegionLeasePack canonical order all-or-nothing 获取完整集合。没有第二池或新实体身份。原 batch 持有 reservation pin；未退出/释放时 finish 和 P1 父 invocation.close 拒绝，不能提前从活跃集合清掉父 batch。
- 只在原 parent.groupWork 的原 native worker 中 open；RegionState 原 ownership 逐个建立/回收，借用原 RegionEntityOwnershipScope 的当前作用域。发布线程/外线程/脱离原 batch 不能 open，其他线程不能 close，持有 worker 不能提前释放；异常保留原 Throwable，finally 退出后权限为空。scope 不增加玩家 worker 计数。
- entity、精确块/流体/BE、chunk、完整 section/query 集合、目标 mutation 和原 section/removal 发布都理解整个集合。Level 与直接 LevelChunk 读写均检查精确块边界；实体位置的目标 AABB 即便仍在已租 region 内，超出足迹也在 setPosRaw 副作用前拒绝。非成员目标/未知 ID 查找拒绝，不将外区活玩家返回 null。原无对象 global guard 没有放宽；新 admission/portal/无界玩家列表/POI/global state 仍拒绝。
- 新完整查询路径不走普通实体 clamp 或 player snapshot union。所有物理 buckets 的原 section IDs 合并后按原 signed packed-long 顺序访问，保留 predicate/consumer/type/excluded/max/abort 与活对象身份；直接单 bucket/subset accessor 不能偷换完整查询。普通实体既有 clamp/count/快照合同保持不变。
- 连续前缀规划器对同区、共享目标、halo 相交做传递合并，组内原顺序；null footprint/跨 Level/超过 16-region 的冲突组在第一成员处形成完整 serial fence。调用方必须 join 前缀→执行原 fence→重新捕获后缀，不提前安排越过 fence 的玩家。获取前后重审 identity/index/mapping/chunk/原完整查询/环境；组中每个未开始原调用还须再核对几何，不能重跑已开始正文。

本次实际验证（保留每次 XML，数字不可相加当作互异测试总数）：

| 命令目录 | 当前验证范围 | 结果 |
| --- | --- | --- |
| `tests-02` | 初始空间/冲突 fixture 修正 | 20 tests，0 failures/errors/skipped，exit 0 |
| `regression-01` | 原 ownership lease/实体 mutation、普通 clamp query、RegionLeasePack、物理 partitions、实体 plan/policy/declarations、hopper/BE、interaction completion、P1 与新空间 | 160 tests，0 failures/errors/skipped，exit 0 |
| `final-focused` | 加入捕获后变更、原父 batch pin、正负角落/两池/精确位置边界后 | 59 tests，0 failures/errors/skipped，exit 0 |
| `final-regression` | 最终 LevelChunk 直接边界防护及最终源码：新 27 项 + P1 20 + 原 ownership lease 12 + 原 query 6 + 原 entity ownership 12 | **77 tests，0 failures/errors/skipped，exit 0** |
| `format-check-final` | 本项修改根 Java 与两项测试的定向格式检查 | exit 0 |
| `patch-reapply-final` / `setup-final` / `patch-byte-comparison-final` | updating=false 非模糊原 ACCESS apply，独立 setup，逐字节对照 | exit 0；**14 个改动/未跟踪 Minecraft patch 的应用源码与生成源码一致** |

27 项新增用例覆盖正/负边界与角落、原 section 搜索余量、完整骑乘几何表达但生产拒绝、跨区活玩家查询/ID/UUID、原全局 signed section 顺序与 max/abort、原 native fixed/persistent 两池 scope、精确块/chunk/目标 mutation、任一 lease 忙/缺 loaded chunk 全退、查询/目标位置扩大副作用前拒绝、共享目标/halo 传递分组、原顺序/serial fence/跨维度/超容量、旧 owner publication、新目标或 chunk 变化使未开始 reservation 失效、原 batch pin、scope 错误线程/异常清理及原异常对象、不放宽全局 guard。native 测试的断言体只认证空间能力，不是完整 ServerPlayer.tick/Connection.tick 正文或 2 网络玩家并行证据。

验证工具新增 `tools/player-region-tick/verify_patch_sources.py`，读取当前 changed + untracked Minecraft patches 并将原 apply 产物和生成 Java 逐字节比较；拒绝覆盖旧 verdict。新 Python/Java/资源源码均可纳入版本控制，node_modules/pycache 保持忽略。`final-sources/` 保存本次源码快照，`source-ignore-check.json` 和收据包含 SHA256；用户原 audit、declarations 测试及 entity-safety 修改保留，没有格式化用户 declarations 文件。未重新宣称历史 259 JUnit/3 GameTest，也未因空间组件测试重复安装旧 `.115` 或重跑无代码变更的原网络矩阵。

本项产物仅是开发/JUnit jar 与已应用持久源码档案，**不是安装服 worker 交付**：

- `projects/neoforge/build/libs/neoforge-21.1.250.116-beta.jar`，保留副本 `tmp/player-region-tick-continue01/todo3/artifacts/neoforge-21.1.250.116-beta.jar`，SHA256 `2442be8496e4647ddc5e34bdfc0601fe39dd6a648363bb1efa6dcbab471812b1`。
- `tmp/player-region-tick-continue01/todo3/artifacts/patched-sources.jar`，SHA256 `403ac0ae243ec1a5860e52e88baaf9968a9c7f94e54faac6fa651d98673b2418`。
- 旧 `.115` installer SHA256 仍为 `04d6c8edb1959eee74f90f67971f21c1de1d7bdb482380a1344ffc8007010c8c`，未重建或替换。本项没有启动测试服。

失败完整保留：`compile-01` exit 1（补丁已应用到 archive，但生成项目源码尚未 setup，新接口未解析）；`compile-02` exit 1（Gradle 禁止同次 setup/compile 的隐式输入依赖）；分为独立命令后 `compile-03` exit 0。`tests-01` 20 tests/6 failures，来自测试 fixture 的后加玩家未发布、先 busy 后父 admission、Mockito restub 空参数以及冲突链坐标不相交；保留 XML，修正后通过。`format-01` exit 1 为错误 task 名，`format-02` exit 1 为新测试 wildcard import；改为实际 javaImmaculateApply 与显式 import 后通过。最终所有检查 argv/exit 均在收据中；没有吞掉源码异常、重放原正文或修改被测原行为使失败消失。

REQ-1/AC-1/MS-2 的本项空间前置已交付，REQ-3/AC-4/VG-4 对应唯一原 engine/pool/leases/batch/ledger/scope 与完整集合权限，VG-2 对应当前正负/越界/冲突/回滚/异常/零权限残留及受影响回归。worker 自评认为本增量 Done when 满足；这不是独立第三方批准，也不宣称整体 AC-1 完成。

**交接 TODO 4–7：** 先在本空间合同上闭合必经 criteria/奖励/scoreboard/shared state（TODO 4），再把全部同步回调扩展点落实为 production 准入/整次串行原因（TODO 5）。两个原位完整正文调度（TODO 6/7）复用 `capturePlayerSpatialPlan`、原父 batch 下的 `reservePlayerSpatial`、`RegionPlayerConflictPlan.prefix` 与原 effects seam；必须保留独立 ENTITY/CONNECTION 票据、真实 pool/join 和未开始回退。当前 stationary 分支支持保守且要求涉及 region 的所有 chunk 已加载；未知运动/环境/回调不能由固定 halo 证明。后续最终 installer、两套真实 TCP schema 3/4、完整生命周期/包静止互斥和性能仍需统一验收。没有开始修改这些后续项。

## 当前接续 TODO 1：共享状态前置协议收尾（完成）

本项 Done when **已满足**。承接上一轮 TODO 4 中断现场，现有 `RegionPlayerStatePlan/Scope/Hooks`、原对象归属补丁及失效测试已形成可供准入/调度直接调用的独立增量。当前接续 run 为 `2026-11-17T02-37-21-743Z-9f3f0ab7`；attempt 1 因 worker 会话超时未交结果，但实现、格式、patch 重建和回归已完成并留有真实退出码；attempt 2 核对现有源码/证据、补查两个相关测试类格式和 patch 一致性，完成合同及本报告。没有将会话异常解释成源码完成或源码失败。

调用顺序和支持边界见[共享状态合同](PLAYER-SHARED-STATE-CONTRACT.zh.md)。机器收据为 [receipt.json](../tmp/player-region-tick-continue02/state-closeout-02/receipt.json)，SHA256 `b9b104346c36a6a42f6754d551d5981e071e915aa8bd9ae319c30a93984ba57f`；收据含实际 argv/exit 引用、XML 校验和、36 份交付源码/合同快照和当前 SHA256。此前空间/网络证据、`continue01/todo4` 的 compile 失败、`state-tests-01` 的 38 项通过记录及 `state-final` 的全部结果原样保留。

具体完成内容：

- `engine.capturePlayerStatePlan(spatial)` 返回计划或明确串行原因。`lease.bindPlayerState(plans)` 在原发布线程、原空间 lease 和父 batch 下原子 claim 全部本地原对象；跨玩家对象别名拒绝整个组。派发前 `plan.revalidate()`，原 worker 在原调用记账进入 STARTED 和副作用之前 `lease.openPlayerState(plan)` 再查并单次消费。scope 不运行 tick，也不增加完整玩家 worker 计数。
- 捕获并复查 advancements、stats、food、cooldowns、attributes 及已物化实例、inventory、gameMode、warden tracker、listener/Connection，以及原菜单 backing storage、槽位、跟踪集合、listener/synchronizer 的身份。对象替换、额外监听器、共享容器、carried/result 或远端物品、可选状态变化均在正文前失效。状态 claim 与 scope 按真实 worker 返回顺序清理；外部线程或空间 scope 提前关闭不获权限。
- 原 `SimpleCriterionTrigger` 的 HashSet 归属每个原 PlayerAdvancements，执行仍用原集合。snapshot 覆盖所有 trigger 的 set 引用、成员身份、revision 与 lifecycle generation；直接清空/替换原 set 成员也会使未开始计划失效。正常 award 开始后移除监听器仍保持原同步语义，不被捕获 revision 阻止；异常和重入的原 ConcurrentModificationException 不吞掉。
- TICK 只支持无玩家谓词的原 TriggerInstance，并核对 advancement 的全部 criteria、display、奖励及深层 requirements。捕获后 criteria 身份、requirements 内容或 reward 可变列表变化会失效。LOCATION 在连接阶段必须为空，不因捕获时 tickCount 尚未到模 20 边界而放行。
- 原 `trigger → Listener.run → award → reward.grant → giveExperiencePoints` 及 stats、food/cooldown、菜单同步正文保留。空 loot/recipes/function、总增量不超过 7 XP 的有界低等级分支保持同步进度和经验结果。函数奖励在任何原进度/XP 副作用前返回 `GLOBAL_REWARD`，完整串行原路径仍先授予 XP 再执行函数一次；没有排队代替返回值或开始后重放。未知 item pickup 回调仍在正文前拒绝。
- 发布线程预热必经五个原 CUSTOM stat handle 和属性实例；worker 不初始化共享 StatType/ObjectiveCriteria 缓存。相关 objectives 必须为空，原创建/移除导致 revision 变化；scope 内空查询保持零次 consumer 调用、不创建分数。未知 objective、外来 reward/本地对象、生命周期操作和任意服务器任务泵均无权限。默认 false、缺依赖串行、Player.class 整骑乘链/FakePlayer/精确 server-only 声明与原全局 guard 保持。

本项实际验证如下；复用证据已核对当前九个相关 Java 文件均早于通过的回归运行，当前 patch/generated 哈希也与已有证明一致。计数重叠，不可相加当作互异测试总数。

| 证据目录（均在 `tmp/player-region-tick-continue02/`） | 验证范围 | 结果 |
| --- | --- | --- |
| `state-final/tests-01` | RegionPlayerSpatialLeaseTest 56 + PlayerCriterionListenersTest 11，含原 native fixed/persistent pool owner scope | **67 tests，0 failures/errors/skipped，exit 0** |
| `state-final/regression-01` | 上述两类，加 footprint、phase coordinator、原 ownership lease/实体 ownership、query、engine、declarations 与 architecture，共十类 | **201 tests，0 failures/errors/skipped，exit 0** |
| `state-final/patch-apply-final` / `setup-final` | 持久 patch 以 `-Pupdating=false` 执行原非 FUZZY ACCESS apply，再独立 setup 生成源码 | 两步 exit 0 |
| `state-closeout-02/patch-compare` | 再次逐字节比较 applied archive 与当前 generated Java，覆盖 changed + untracked patches | **24 个文件一致，exit 0** |
| `state-closeout-02/format-check` | 六个状态/lease/engine 根 Java 和两个相关 JUnit 类的定向 Immaculate 检查 | exit 0 |

正控制穿过原 criteria/award/XP/stat/menu 方法和原 native worker 的空间/状态 scope，确认同步结果、award 至多一次及退出后 claim 为零；函数奖励的负控制确认正文前拒绝后串行功能完整。失效控制覆盖绑定前及绑定后对象、API/raw listener、奖励列表、objective、XP、连接变化，真实 InventoryMenu 的六种变更，以及深层 requirements/criteria 变更。两池组件测试不等于真实网络玩家完整 tick 并行。

`git diff --check`、补丁比较脚本 Python 语法检查和全部快照源码的 ignore 检查通过；`tools/player-region-tick/.gitignore` 的 `!*.py` 保留。已应用源码 archive SHA256 为 `43ab7ac34be1363ad957b64c1f373794592cae014deaff460720293fc467d9f8`。没有重装旧 `.115`、重跑旧网络矩阵、构建新 installer、启动测试服、操作现用服或 commit/push。以上为 worker 自审和组件回归，未声称独立 reviewer 批准或性能收益。

**下游交接：** 共享状态前置增量已 done；仍需对整个 production 分支穷尽未知事件/item/effect/attachment/Mixin 等可达扩展点并在正文前完整 serial 拒绝，再接入世界/连接两个原位完整调用。调度必须将 openPlayerState 失败与 STARTED 后异常区分，保留独立阶段票据、原 pool/lease/barrier/ledger、真实 join、串行 fence 与后缀重捕获；完整调用的 eligibility、父调用、doTick 前后及 BE/Level PrePost/维度/PlayerList 顺序不可改变。最终安装服真实协议客户端、网络静止互斥和生命周期验收仍属后续任务，当前两个完整玩家 worker 入口均为 0，整体目标继续 partial。

## 当前接续 TODO 2：两阶段 production 准入（组件验证完成）

`RegionPlayerAdmissionPlan` 组合实际已安装扩展、原玩家/连接生命周期、完整空间及共享状态捕获。两阶段均在正文开始前复查；精确 server-only 声明优先，未知 Mod、变换、可达事件监听器、attachments、菜单/装备/流体/战斗/连接转态等保持完整串行。支持范围及每条原始调用的条件见 [production 准入合同](PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md)。此增量只提供调度所需的准入计划，尚未执行完整玩家 worker。

最终证据位于 `tmp/player-region-tick-continue02/admission/`：`tests-09` 的 100 项测试与 `regression-final` 的 17 类 **391 项回归均通过，0 failures/errors/skipped、exit 0**，两者计数重叠。XML 已归档；定向格式、严格 apply/setup 及 24 个持久补丁与生成源码的逐字节比较均通过。早期编译/fixture 失败、全部实际命令和退出码保留。主会话复核上述 XML、最终 result.json 及当前 diff 后接续；不再重复安装旧 `.115` 基线。

下一步将该准入接入世界和连接两个原位完整调用，保留独立阶段票据、连续前缀/完整 fence、同区顺序与真实 join。安装服上的 production 环境仍需验证；组件测试不替代真实网络玩家两入口并行、包静止互斥、生命周期或最终性能验收。

## 本轮收口：P2 双入口首版接入与定向验证（完成，按用户要求停止）

本节为当前状态，前述 worker=0 的段落保留其历史语境。用户要求由主 Agent 直接完成当前阶段，并在完成后停止、提供另一 Agent 可用的 prompt。本轮没有继续使用 SubAgent；现在收口到 **P2 首个受限 production 分支的双入口调度与定向验证**，不宣称完整 P2 验收矩阵或 P3–P5 完成。交接文件：[PLAYER-REGION-TICK-HANDOFF.zh.md](PLAYER-REGION-TICK-HANDOFF.zh.md)。

证据根目录 `tmp/player-region-tick-continue03/`，机器收据 `receipt.json`；所有前次目录和失败结果原样保留。最终产物为 `projects/neoforge/build/libs/neoforge-21.1.250.121-beta-installer.jar`，SHA256 **`6c09c5292778ec36a3f61c95a2ae8a1e15e3cd3c2a4650c97c6bee6f7483b761`**，同字节归档于证据根的 `artifacts/`。没有提交、推送、部署或改动现用服务器。

### 实现与权限边界

- `RegionPlayerTickDispatch` 运行完整原调用。世界阶段在 self-driven 普通实体已 join/发布后的原 fallback 序列中只收集连续玩家，保留非玩家/拒绝调用的完整 fence；运行原 `guardEntityTick → tickNonPassenger → ServerPlayer.tick`。连接阶段通过 `PlayerConnectionPass` 在原连接遍历位置派发完整 `Connection.tick → listener.tick → doTick`，每个结果回到其原 try/catch 位置处理；没有只搬走 doTick。
- 唯一原 engine、pool、RegionTaskBatch、RegionLeasePack、多区域 spatial/state scope 与实体 ledger 保留。每波至多原 pool worker 数量的调用，冲突分组内部原序执行；所有组先检查再一起开始，拒绝的 inline 任务不运行玩家正文。join 使用真实完成屏障，期间不泵通用服务器任务或 chunk task。完整支持分支预先拥有全部已加载 chunk，禁止同步 server service。
- `RegionPlayerTickCoordinator` 独立记录 ENTITY/CONNECTION 的 worker 开始、原返回/失败与发布线程物理 join。原世界方法内的 P1 hook 识别当前 worker 票据，避免二次记账。开始后失败不串行重跑；终态只留有界元数据、实际线程及 start/return 时间。连接在 join 后、原遍历尚未消费结果前物理断开，也会消费已完成结果，避免再次调用或遗漏原错误处理。
- `RegionPlayerNetworkGate` 将直接 Netty 入口纳入静止边界。已有回调使本次 freeze 失败；已冻结或尚有早先回调排队时，新回调在同一锁内 retain 并保序进入原服务器队列。入站、异常、关闭/断线入口使用该门；完成/异常释放引用，4096 上限溢出走明确终止断线。Netty 不等待持有玩家权限的 worker；原异步聊天完成仍返回原 server executor。关闭开关走原路径。
- 新生产诊断为 `/neoforge regionthreading status players`，返回实际阶段计数、至多 256 条完整调用记录、最新拒绝原因、网络门与 live batch/state claim。配置说明和启动诊断已更新，玩家开关仍默认 false、缺依赖不自动开启；FakePlayer、骑乘整链、精确 server-only 声明及未知 Mod/transform/callback 保持完整串行。
- 真实启动发现并修正两项漏审路径：块访问需按原扫描的 `floor(min)..floor(max)` 整数格核对，不能拿格原点与小数 min 比较；原碰撞正文会触发 ENTER_BLOCK，即使监听器表为空。现在连接准入要求 LOCATION/ENTER_BLOCK 注册为空，原空触发器仍正常执行并同步返回，不截断碰撞或删除 criteria；非空 ENTER_BLOCK 在正文前完整串行。

### 同一最终 `.121` 安装服结果

每组均为全新安装、两个真实 TCP 协议客户端、只加载 minecraft/neoforge，**没有 oracle Mod、独立方法观测变换或测试专用 worker 许可**。安装与运行 hash 由独立 verifier 对照 installer 内 universal 原字节。目录均在证据根下的 `server-final-{mode}/worker-smoke/`；各自 `independent-verdict.json` 通过，server/client exit 均为 0。

| mode | 独立区 ENTITY / CONNECTION 的采样完整 worker 调用 | 出现两人原正文区间重叠的 tick 数 ENTITY / CONNECTION | 同区每阶段采样 worker 调用 / 重叠 | 跨区攻击 B health |
| --- | --- | --- | --- | --- |
| on-fixed | 274 / 274 | 80 / 105 | 242 / 0 | 20→19 |
| on-persistent | 246 / 246 | 117 / 120 | 242 / 0 | 20→19 |
| off-fixed | 0 / 0 | 0 / 0 | 0 / 0 | 20→19 |
| off-persistent | 0 / 0 | 0 / 0 | 0 / 0 | 20→19 |

独立区实际 A=(24.5,100,24.5)、B=(120.5,100,24.5)，owner 为 (0,0)/(2,0)；同区 B=(30.5,100,24.5)，两者均 owner (0,0)。时间区间由 production 的完整原调用前后记录，不在正文内加睡眠或假任务；同区校验排除该窗口之前残留的历史记录。采样不等于完整性能测量，四服并行运行的调度差异也不能据此比较池的速度。

攻击前 A=(47.5,100,24.5)、B=(49.5,100,24.5)，实际 owner (0,0)/(1,0)，距离 2。A 发标准 use_entity 攻击包，四组 B 服务端 health 均 **20→19**，受害客户端各恰好 **1 个 damage_event** 并收到 health=19。核对了原 RCON 响应和客户端包记录，未调用 hurt/kill 替代攻击；游戏包仍在 worker 真实 join 后的原服务器静止窗口执行。此套件没有服务端 damage-event 监听器，因此不声称它额外验证了服务端事件次数。

两组 on 实际延后网络回调分别 12、9 次，最终四组 owner/pending/running、network pending/readers、active batch/state claims 均 0，网络门已解冻。原客户端 recipe PartialReadError 输出完整保留；所测 damage/health 包正常解码，不认证完整 crafting/recipe 协议。

测试 fixture 正常授予全部原版进度、清空库存及累计 XP，再建立静止惰性平台，使首个保守分支可达；没有删除、替换或跳过原 tick/事件/查询。普通新玩家的 LOCATION/ENTER_BLOCK 未闭合时可能整次串行，本轮不宣称其并行覆盖率。先前 `.115` 的 144 个战斗/实体与 180 个资源/守恒场景属于串行基线，**尚未在 `.121` 全面复验**。

### 当前回归与失败留存

- `regression-final`：**18 类、399 项 JUnit，0 failures/errors/skipped，exit 0**，完整 XML 和统计在该目录 `junit/`、`junit-summary.json`。覆盖新增网络门的 FIFO/retain/异常释放/溢出，原 native 两池票据的失败一次/物理 join 前后/不重放，整数空间边界，以及原身份、玩家转换、实体 plan/policy/ownership/query、engine、architecture 与 declarations 等回归。
- `gametest-final`：3 项选定 GameTest（physicalEntitySectionCrossing、blockEntityOwnerOperations、unsafeTickerForcesWholeRegionFallback）均通过，Gradle/server exit 0。另有自动 feature-gating 通过；其他未处理/disabled 测试保留在原框架报告，不把 XML 的重复 group 行或未处理项计为已运行。此选择回归不是全量 GameTest 或玩家 worker 故障/生命周期矩阵。
- `format-check-final` exit 0；`patch-consistency-closeout.json` 确认 **26 个** changed/untracked Minecraft patch 的 applied archive 与当前 generated Java 逐字节一致。`git diff --check`、新 Python 工具语法和 ignore 检查通过。
- `smoke-fixed-01`：累计 XP 未被普通 set 命令清除，未准入；修复 fixture，不放宽 XP 安全门。`smoke-fixed-02`：原碰撞整数格被错误拒绝，原连接完整失败并断开，未重放；修复格范围。`smoke-fixed-03`：漏接原 ENTER_BLOCK 空触发器，保留原失败后补齐前置条件和同步空路径。`smoke-fixed-04`：游戏与两入口实际执行无异常，但旧校验器把独立区历史区间算入后续同区窗口；修正窗口筛选后，对同一未修改语料复核为独立区各 242 worker/121 重叠、同区各 242/0。该命令的原失败退出码没有改写；最后四组以统一当前工具全新运行通过。
- `patch-apply-02/-03` 的补丁空白格式尝试失败日志保留，恢复合法 hunk 后 `-04` 与 final 的严格 `updating=false` apply/setup 均通过；最终源码逐字节证明未因处理 patch 文件空白而改变 MC 正文。

### 交接范围

**本轮阶段停止；整体仍 partial。** 下一 Agent 应先审查并补齐 P2 的提交拒绝/部分提交、完整世界和连接原正文故障探针、原阶段/事件顺序、网络/聊天/keepalive/payload 时序、活动断线/停服期间队列取消与 retain 释放、迁区/传送/维度/重生/旧实例/保存恢复矩阵，再继续 P3–P5。稳态清零不能替代完整退役/GC 认证，也没有复用或扩张历史 C6/生命周期证书。

优先把 schema 3/4 工具通过显式审核的 artifact manifest 接到新产物，复验用户关心的实体、方块、BE/共享容器与守恒；保留旧 `.115` hash gate，不特批 oracle Mod 进 worker。当前 production-only smoke 的时间记录来自内部完整调用记账，尚无独立方法级观测器；P4 owner 网络路由、未知模组并行与 1/8/32 客户端性能/长时恢复均未验收。本轮没有收益结论。


## 接续 04：P2 退役审查与新产物功能复验（进行中）

证据根为 `tmp/player-region-tick-continue04/`；主 Agent 直接操作，无 SubAgent。启动时保存 `initial.diff` 和未跟踪文件清单；旧工作树和全部证据保留，没有 reset/clean、提交、推送或操作现用服。

源码审查发现原网络门的延后 Runnable 只有被服务器执行时才释放 retain；若停服不再消费队列，包、Connection 与原闭包会被残留任务持有。正在增加可取消的封装，由原断线处理（worker 必须先 join）取消该连接尚未开始的回调；引擎 awaitActiveTickBatches 后统一关闭网络门。取消会清除封装中的 gate/connection/original/retained，留在服务器队列中的封装为空操作；已开始回调继续原正文并 finally 释放，不能因取消重放。溢出终止任务也纳入取消和提交失败回滚。

计划产物版本为 `.122`，尚未完成构建或验收。`patch-apply-01` 因新增行后的显式偏移不一致失败；误提前启动的 `setup-01` 同样失败，两份退出码均保留。修正偏移后严格 `updating=false` 的 `patch-apply-02` 和独立 `setup-02` exit 0；`patch-consistency-01.json` 确认 26 个持久 patch 与生成源码逐字节一致。组件测试正在执行，暂不宣布通过。

schema 3/4 原 `.115` hash 检查保留。新增显式 CLI 的审核 manifest 路径，要求 manifest 内容 SHA256 被工具源码注册、安装时归档且安装后的 server/universal/oracle 字节匹配；新产物语料额外读取原生产诊断，要求两个阶段 UNKNOWN_MOD 拒绝、worker=0 和最终清零。该 manifest 只迁移功能兼容性复验，不为 oracle 增加任何 worker 权限。尚未运行的新产物矩阵、完整正文故障/网络时序/生命周期及 P3–P5 仍为未覆盖。

中途进展：`.122` 的 `build-regression-02` 共 411 项 JUnit、0 failures/errors/skipped；installer `b6ca213c9a1b1df8d0408550d2ddf69b52cb7af0e6bcaae87899e4e58374c744`。`build-regression-01` 的两项新测试失败来自漏算夹具父调用，修正断言后重跑通过；失败 XML 保留。`.122` 的 four-mode 无 oracle worker smoke 已全过；schema 3/4 初步运行通过部分模式，仍进行中。

进一步原连接遍历审查发现：后续缓存的**未开始**调用在 socket 已关闭时也使 `hasCompleted` 返回 true，可能覆盖原遍历的最新连接资格检查。已修正为仅开始过的调用才必须消费；未开始且已断线的缓存被丢弃，回原 remove/handleDisconnection 分支。新增 started/unstarted 负控制，未重放正文。该修复推进最终候选为 `.123`；`.122` manifest 与全部中间证据保留，新候选必须重新构建、审核独立 manifest 并完整复跑，不能借用 `.122` 结果当作最终证据。

接续验证进展：`.123` installer SHA256 `632cf358a52524eac18e53432ba7c7fbeca646a0cd02a50d9c2ab8a0160a661b`，独立 manifest `tools/player-region-tick/reviewed-artifacts/21.1.250.123-beta.json`（内容 SHA256 `c9a440f0483374d1e202a5195c25df32f8d0b61bfbf8f7921fd470988e6d7ea5`）。`build-regression-123` 的 18 类 413 项 JUnit，failures/errors/skipped 均 0；`final123/patch-consistency.json` 的 26 个 patch/generated 字节一致。审核由主 Agent 对源码、构建和安装字节完成，没有声称独立或人工批准。

`.122` 完整中间矩阵保留在 `matrix-result.json`：四组 worker smoke、144 个 schema 3、两组 persistent 的 schema 4 通过；两组 fixed 的资源语料因原挖掘掉落在独立 NBT 读之后、最终 mark 之前被拾取，未通过独立库存一致性检查。失败不是通过放宽校验器改成成功；新 runner 把破块后的观察等待从 0.5 秒增至 2 秒，保留原动作、事件、原始客户端反馈和物品/XP 守恒断言，在 `.123` 新目录重跑。旧 `.115` 两类语料用新工具与原 hash gate 复核仍通过（`legacy-schema3/4`）。

`.123` 第一组无 oracle production 生命周期已通过（`final123/server-lifecycle-on-fixed/worker-lifecycle/`）：6 组正常 register→chat→ping→unregister 客户端帧，原服务端聊天记录和客户端聊天/ping 反馈分别保序，两人各至少一个真实 keepalive challenge/reply；两阶段窗口各 730 次完整 worker 调用、各 365 对实际重叠。A 的正常移动包使 x=47.5→48.25、owner X=0→1；断线后旧 owner 消失、重连换实例；正常拾取的 5 钻石、7 XP 经重连与带连接停服/保存/重启仍各自相同，地上目标已消失，两个 server exit 和 client exit 均 0。11 个协议证据负控制均拒绝。该组不证明 arbitrary NeoForge main-thread payload 的回调顺序、每种关闭竞争或 GC 退役；当时取消计数为 0，不把稳态清零算作取消竞争证明。其余模式与真正客户端进门的双向下界传送、原世界/连接正文故障回退探针仍在执行。

### 活动退役竞态的确定性复现与 `.124` 修复

在 `.123` 上补充了两种池的四项定向复现（`retirement-reproduction-123-v2/junit/`，4 tests / 4 failed，原始失败保留）：物理 socket 关闭会使已消费的本地 state scope 变为 null；外部 `engine.shutdown()` 在 worker 仍执行时设置 `shutdown=true`，原网络门因动态 `playerCriterionStorageEnabled()` 为 false 而返回 unrestricted，直接执行本应延后的 Netty 回调。首次复现编译因测试直接引用 package-private 方法失败，日志保留；改用既有反射测试方法后取得上述真实反例。

`.124` 修复只改变两条生命周期边界：网络门按启动时 requested 标志继续保护已有 fence，直到 join/thaw/close；已消费且仍处于原 native lease 的 state scope 不因 stop-new-admission 或物理 socket open 位变化而失权，原 player/listener/connection/level/generation 身份仍逐项匹配。未开始的 capture/consume/ticket 仍按 shutdown/断线拒绝；原完整 Connection.tick 自己观察失联，并将生命周期处理延后至 join，不修改原同步结果或重放正文。新增测试在真实外部 shutdown 线程、原 native pool/lease/state/ledger 下执行原 food/cooldown 方法，并实际排入一个 retain 回调，验证 shutdown 等待、真实返回/join 后取消释放、空封装无副作用。

`final124/build-regression` 18 类 **417 项 JUnit，0 failures/errors/skipped**；同目录存完整 XML。`.124` installer SHA256 **`bec193f7f14402542fbc041f6bb0a730b909d0359c3a39b5e7958a203bc2809a`**，manifest `tools/player-region-tick/reviewed-artifacts/21.1.250.124-beta.json` SHA256 `6e43b4b70c0047a5a2f0572e6dc6d1fecf56028217aa045238a841c7f6716d38`；26 个 patch/generated 字节一致。正在对该最终候选完整复跑，`.123` 的全功能矩阵、四组原客户端下界双向传送和其余中间证据保留，不冒充 `.124` 验收。

`.123` 的两个世界正文故障探针确实在原 ServerPlayer.tick→TICK criterion 回调中各抛出一次、原 ENTITY_THROW 和 JOINED/failed=1 已留证，但 runner 要求的 `ServerStopping` 文件不存在，故判失败。原 MinecraftServer 崩溃分支不发该事件，只发 `ServerStopped`；新观测 Mod 显式记录实际事件及完整 stopped diagnostics，`.124` 新目录重跑，未改写旧失败结果。`.123` 两组完整 Connection.tick 故障回退探针通过。它们都加载未知 Mod，严格串行；不能称作完整 worker 正文故障验收。

`.123` 选定 GameTest 中 physicalEntitySectionCrossing 曾因“both entity chunks must be loaded”失败，原加载失败日志保留，另外两项通过。未放宽原断言或改掉测试源码；`.124` 将用独立新目录逐项运行三个回归，以分开观察夹具加载与真实行为。完整生命周期/P3–P5 仍 partial。

## 接续 04 最终结果：`.124` P2 故障/退役补足和完整功能复验（整体仍 partial）

最终 installer：`projects/neoforge/build/libs/neoforge-21.1.250.124-beta-installer.jar`；SHA256 **`bec193f7f14402542fbc041f6bb0a730b909d0359c3a39b5e7958a203bc2809a`**。同字节归档在 `tmp/player-region-tick-continue04/final124/artifacts/`；审核 manifest 保留 `.122`、`.123`，当前 `.124` 文件为 `tools/player-region-tick/reviewed-artifacts/21.1.250.124-beta.json`，源码注册其完整内容 SHA256。主 Agent 独立操作，没有 SubAgent、reset/clean、commit/push、部署或现用服操作。旧回执/工作树/失败目录都保留；新回执在证据根 `receipt.json`。

### 最终实现和回归

- 网络回调采用可取消封装；断线只在原清理位置、真实 join 后取消尚未开始的回调和溢出任务。引擎关闭在 awaitActiveTickBatches 后释放 retain 并清空队列封装中的原闭包、包、Connection、gate。已开始回调完整执行并 finally 释放；正文不会因取消重放。普通和 overflow executor 拒绝都回滚登记。原 4096 上限、顺序、默认关闭与未知扩展整次串行保留。
- 已捕获但未开始的缓存连接若已断开，不覆盖原遍历 eligibility；已开始的结果仍消费一次。物理 socket 关闭和 shutdown stop-new-admission 不撤销原已消费 lease/state scope；新准入仍拒绝，已开始正文保持原身份边界到真实返回。
- `final124/build-regression/junit-summary.json`：**18 类 417 tests，failures/errors/skipped 均 0**。另 `lifecycle-regression-and-physical-gametest/junit/` 的 4 项既有 LifecycleRetirementRaceTest 全过（独立类别，未混入 417）。新增两池 partial submit/拒绝/抛错、state open 失效、开始后异常一次、socket loss、shutdown 等待/网络门/retain 取消有确定性正负控制。`.123` 上四项退役复现失败保留；不把失败改写为通过。
- `format-check`、`diff-check-final`、Python/JS 语法和 `patch-consistency-closeout.json` 通过；26 个持久 changed/untracked patch 与 generated Java 逐字节一致。physicalEntitySectionCrossing 在原 200-tick 夹具加载等待失败；只将测试等待上限增至 600，原“两个实体 chunk 必须加载”与跨区存储断言未变，新的私有目录通过。blockEntityOwnerOperations 与 unsafeTickerForcesWholeRegionFallback 原样各自通过。共三项选定 GameTest，不是全量 GameTest 认证；旧加载失败全部留存。

### 最终产物四模式、原协议功能和原顺序

`final124/server-{smoke,interactions,resources,dimensions}-{mode}/` 均为不同新安装，mode 为 on-fixed/on-persistent/off-fixed/off-persistent。schema 3/4 必须显式指定当前 manifest；不指定仍用原 `.115` hash gate。校验 installed server/universal/oracle 字节、归档工具、原生产状态响应、实际池和开关。on 的两阶段均实际 UNKNOWN_MOD 拒绝，完整玩家 worker=0，没有 oracle 特批。

| 最终 `.124` 证据 | 四模式结果 | 实际认证范围 |
| --- | --- | --- |
| schema 3 interactions | **4×36=144 场景，全过**；每组 10 项负控制全拒绝 | 正负 X/Z、零边界、正负/混合角落、同区、远距、双向 PvP；普通实体攻击/挤奶、同步取消/FAIL/PASS、真实 health/owner、原事件与客户端原始帧 |
| schema 4 resources | **4×45=180 场景，全过**；每组 15 项负控制全拒绝 | survival 挖掘/放置、方块和 BE 状态、共享箱子/同时 quick-move、物品/XP 竞争、真实死亡/clone/respawn/恢复；两玩家+cursor+容器+地上物品/XP 总量守恒 |
| 原阶段独立校验 | 8 组功能语料 phase-order 全过；每组 5 项负控制全拒绝 | 所有完整 server frame 的 Level Pre/Post、原 BE、世界调用、完整连接遍历、listener/doTick、PlayerTick Pre/Post、PlayerList fence 和旧实例不再开始调用；oracle 场景严格串行 |
| production-only smoke | **四组全过**；无 oracle、无 debugger | 完整双入口 worker 与同区顺序；普通客户端跨区攻击服务端及受害客户端 health 20→19，每组恰好一个 victim damage packet |
| production-only 网络/生命周期/下界往返 | **四组独立 verifier 全过**，以 `independent-verdict-final.json` 为最终判定 | 实际 chat/ping/register/unregister 帧和服务端/客户端顺序、timed keepalive；普通客户端跨区步行、重连换实例；5 钻石/7 XP 经拾取、重连、原传送门双向换维度、停服保存重启保持，实际无地上残余 |

on-fixed 独立区 ENTITY/CONNECTION 各 238 次完整 worker，实际重叠分别 80/94；同区各 238 次、重叠均 0。on-persistent 独立区各 242 次、重叠各 121；同区各 244 次、重叠均 0。off 四阶段 worker 均 0。攻击几何 A=(47.5,100,24.5)、B=(49.5,100,24.5)，实际 owner (0,0)/(1,0)，距离 2。**A 在 Region A 确实能通过正常攻击包伤到 Region B 的 B**。未知扩展功能在真实 join 后的完整串行 fence 执行；功能成功不等于该动作本身已在 worker 并行。

网络测试的六个 ping 客户端往返延迟由原帧时间独立导出，仅为少量活性样本；同机有其他隔离测试，不据此比较池/开关收益或声称性能无回归。聊天异步完成和 ping 各自保序，不要求异步聊天在后续 ping 之前返回。register/unregister 有真实正常客户端帧，但没有注册一个任意 NeoForge main-thread payload callback，因此不认证该 callback 的一般顺序或 owner 路由。

### 无 oracle 的完整 worker 原正文故障与实际 socket 竞争

新增 opt-in 外部 JDI 工具 `jdi/PlayerTickFault.java`、`jdi/PlayerSocketClose.java`；仅给测试自有 JVM 增加 loopback JDWP，**无游戏 Mod、Java agent、类重定义或准入特批**。生产 core 与 manifest 字节相同。断点等待/调试器方法观察的时间不进入稳态重叠或性能证据。

- `server-worker-fault-{ENTITY,CONNECTION}-{on-fixed,on-persistent}/worker-fault/`：四组 `passed_expected_failure`，独立 verifier 均通过，8 项证据负控制每组全拒绝。断点实际处于原 RegionPlayerTickDispatch.runOriginal→ServerPlayer.tick 或完整 Connection.tick 原栈中；先观察原 world spawn-invulnerability 计数 -1，或完整 connection listener 的 tickCount +1，然后外部注入一次 RuntimeException。原引擎保留失败且无重放，在服务器线程真实 join 后 binding=null；同 tick 的另一无冲突组均正常 JOINED。ENTITY 走原 Ticking entity 崩溃，CONNECTION 保留原错误处理并向真实客户端发恰好一个断线包。最终原 batch/state claim/owners/pending/running/network refs 均 0、gate closed。
- `server-worker-socket-v2-{ENTITY,CONNECTION}-{on-fixed,on-persistent}/worker-socket-close/`：四组均通过独立 verifier。原 worker 产生上述原副作用后，正常客户端主动 TCP 断开；外部观察实际 connection.isConnected=false，当前原 state scope 仍有效。恢复原正文正常返回，完整调用仅一次、无 failure、服务器线程 JOINED 后原绑定释放；实际 deferred 网络回调最终清零，取消计数分别 ENTITY fixed/persistent=2/2、CONNECTION fixed/persistent=1/0。6 项 socket 证据负控制拒绝；0 取消的那一组如实保留，不声称每组都碰到取消路径。
- 第一版 socket 矩阵脚本误指向既有 worker-fault runner，实际重复执行四组 expected-failure，虽然该程序 exit 0，**不计为正常 socket-close 证据**；完整目录保留。修正脚本后以 `v2` 全新目录完成上述正常断线竞争。原脚本/argv 清楚区分二者。
- oracle + world-fault Mod 的原世界 criterion 回调故障两池也以 `.124` 新目录通过；真实完整 Connection.tick 的未知扩展故障回退两池通过。这是完整串行兼容失败证据，与无 Mod native worker 故障明确分开。

### 重生覆盖和仍需继续的工作

`server-respawn-{mode}/worker-respawn/` 的四组真实跨区致死 use_entity→标准 client_command respawn、服务端 health 20→0→20、原始客户端伤害/重生反馈、旧/新实例和最终清零均通过最终 `independent-verdict-v2.json`。原校验器把 raw decoder 的 `perform_respawn` 枚举名当成数字 0，且强制两阶段都立刻重新准入，四组原失败保留。修正 raw 枚举核对后，**on-fixed/on-persistent 新实例 ENTITY 分别观察到 88/87 次 worker；CONNECTION 均 0**。初步看到的 OPTIONAL_TICK_STATE 是全局每阶段计数，不能归因到这个新玩家；下文只读诊断已定位实际玩家拒绝为 SPATIAL:CALLBACK_SPATIAL_BOUND_UNKNOWN。该未落地负例保留，不能把其连接 worker=0 改称双阶段 re-admission。

整体仍 **partial**，不宣布全设计或全 P2/P3–P5 完成。后续重点：普通游戏覆盖；活动 scope 的 Level unload/重配置/末地 credits/双向末地和传送取消；同组前成员真实原正文失败/后缀资格和全部 eligibility；完整 shutdown callback/队列饱和长期压力及有界 GC 退役（现仅组件引用字段清理和定向真实竞争）；任意 NeoForge main-thread payload/异步聊天过滤与混合包 fence 的全面时序。正确性矩阵闭合后再逐类推进 P3 的物品/共享库存/伤害/计分板/地图支持、P4 owner 网络路由及 P5 的 1/8/32 真客户端、多分布、MSPT/尾延迟/覆盖/长期恢复测量。默认 false、未知扩展整次串行、开始后不重放、真实 join 后清理必须保留。


### 重生诊断更正与正常落地接续（四模式最终通过）

`final124/server-respawn-diagnostic-v2/worker-respawn/original-state-diagnostic.json` 由无 Mod 隔离 JVM 的只读外部 JDI 记录**目标玩家本人的**原捕获结果：ENTITY=NONE，CONNECTION=SPATIAL / CALLBACK_SPATIAL_BOUND_UNKNOWN。先前 OPTIONAL_TICK_STATE 是整阶段最后/累计拒绝，包含持剑/力量效果的另一玩家，不能用来解释新实例。该归因已更正，没有放宽准入。

服务端原快照与客户端位置都为 y=100.1；原 ServerPlayer.findRespawnAndUseSpawnBlock 的 forced 分支确实把出生点放在 blockY+0.1。当前测试客户端只确认服务器传送并回发该坐标，没有模拟下一步落地，因此 CONNECTION 的 knownBlocks 要求整数脚底高度正确拒绝。正在增加独立新安装：保留未落地窗口和原拒绝，再让正常 position_look 包从 y=100.1→100.0，检查实际新实例在两个完整入口重新进入原 worker。命令不传送/写入测得结果，生产代码和 `.124` 产物均不修改；旧四组失败/功能成功和两个诊断目录保留。

重生接续最终通过：`final124/server-respawn-settled-{mode}/worker-respawn/independent-verdict.json` 四组新隔离服均通过。on-fixed/on-persistent 的初始未落地窗口新实例 ENTITY 各 88、CONNECTION 各 0；发出唯一一个普通 position_look 落地包、实际 y=100 后，两个池的 **ENTITY/CONNECTION 各 77 次完整 worker**，无重复/失败、原身份均为新实例。off 两组两个窗口均为 0。原致死/标准重生/唯一原始帧/服务端与客户端反馈、物品为空/XP=0 和退出清理保持。该工具增量没有修改 `.124` 生产代码或准入，消除了“新实例本身不能重新准入”的假设；非整数脚底的几何分支仍未支持、正确完整串行。旧未落地语料的 partial 结论与所有旧失败保留，最终 receipt 将两组语料分别列出。

接续 04 收口到上述 P2 增量和最终产物复验；仍不声称完整 P2、P3–P5 或性能验收完成。最新交接、机器回执及源码快照已更新。后续从剩余矩阵继续，不重建/篡改历史 hash gate 或删除已失败证据。

## P2 最终收口进行中（接续 05，2026-11-17；P3 前停止）

用户本次范围明确为「完成整个 P2，在 P3 开始前停下」。新证据根目录为
`tmp/player-region-tick-p2-final05/`，初始 138 个 dirty/untracked 文件及完整 diff 已独立快照。
不使用 SubAgent；保留全部历史工作树和所有失败，不 commit/push/deploy，不操作现用服。

P2 仍以设计第 7 节「首个经过闭合审计的完整双入口分支」为边界。普通移动、库存、伤害、共享容器等
worker 支持扩张属于 P3，本次不放宽现有准入。未知 Mod、变换和监听器保持整次串行，默认 false。

已新增：两池×两阶段的 open 前失效、部分提交拒绝/提交异常、取消和正文开始后原异常矩阵；活动玩家 scope
期间 Level unload 必须等待原正文/实际 join/发布线程清理的四项测试；16 轮每轮 4120 个网络回调的
溢出/取消/完成及 retain、空 envelope、捕获对象的有界 GC 退役检查。`.124` 已通过 469 项完整 JUnit。
新增 payload 合同测试之后的两次编译失败复制了旧 XML，**这些旧 XML 不计作该次新测试成功**；以各命令实际退出为准。

四组 `.124` 同冲突组完整原正文 JDI 故障均过：实体原故障终止世界遍历、不运行后缀；连接原故障保持
原 disconnect，后缀在原连接 try/catch 位置完整串行一次。原独立组故障、真实 socket loss 与稳态重叠证据继续保留。

真实网络溢出复验发现生产缺口：旧 terminal 调 `Connection.disconnect` 直接关 TCP，4096 个已接收回调
按序返回，但没有发送协议断线原因包。保留 `.124` 失败 verifier 和真实原帧。修复为在 terminal 的原服务器
队列位置调用 **当前** `ServerCommonPacketListenerImpl.disconnect`，先走原协议包/flush/关闭流程；旧 listener
可能已切配置态，因此不捕获旧 listener。没有当前 common listener 时保留 raw close。增加当前协议替换及顺序测试。
正在生成候选 `.125`；完成新产物功能/故障/网络/生命周期复验前不宣布 P2 done。

驱动错误同样全部保留：最初 RCON 15 秒保存超时；重配置未完成时误发游戏态包；普通 JS number
写 i64 ping 实际编码为零；两个独立 verifier 拒绝；末地初始夹具出口被到达平台生成覆盖（离线保存区块中
102/49/0 为 AIR），以及原 credits 尚未到达就断言。新驱动使用原世界 gametime 连续推进证明越过转换、
独立确认出口方块、普通客户端移动和等待真实 win_game 包，不修改生产准入或游戏正文。

四组未知 Mod 的 payload/聊天过滤/重配置/取消/末地/credits 语料已经通过独立校验；它们均 worker=0，
只证明同步回调与未知扩展串行合同。ServerStopped 事件先于 core finally 的 engine.close，事件处检查零活动引用，
**不要求此时 gate.closed 已为 true**；最终 gate.close 另由原 JDI shutdown 位置检查。

接续 05 中间复验更新：`.125` 的 476 项 JUnit 已过（增加 camera/dead/removed/idle 的双计划拒绝检查）；
完整 fixed/persistent × off/on 的 schema 3 144 场景、schema 4 180 场景、100 负控制及八组原阶段/event
顺序与 40 阶段负控制全部通过。oracle 仍 UNKNOWN_MOD、两阶段 worker=0。
两池×两入口×正常/溢出的 8 组无 Mod 网络探针已过，原 256/4096 个 accepted ping 回复严格保序，
原 worker 暂停期间无回复；溢出 4200 个普通请求后一个明确协议 kick，最终队列/retains/claims 归零。
四组同组故障、四组独立组完整原正文故障均过，分别含 32 项负控制；原异常保留，已开始正文不重放。
四组 production-only 与四组 unknown-contract-Mod 转换矩阵、四组真实浮空及原一分钟 idle 踢出也通过。

外部只读 JDI 另已观察 `.125` 的 30 个完整服务器帧：实际 native EntityTick/PlayerTick Pre/Post，
完整 doTick/Connection/dispatch 调用栈，三个 Level 与 BE、连接和 PlayerList 的原相对位置均正确；
没有安装事件 Mod 或改写类，七项篡改控制拒绝。四模式同类观察与三个既有 GameTest 正在继续。

负控制还发现并修复独立 verifier 的代次边界问题：配置态返回的新 listener 会复用 teleportId=1，
旧检查可能借用新代次同 ID 的确认来证明早先的包。新检查要求每个原 position 在下个 position 之前
具有自己的对应确认。所有旧结果和控制失败保留，八组转换原语料以新 `causal-ack-verdict.json`
重新复核；未改变原帧或服务器执行。另一个最初的聊天负控制改动了 B 的广播副本，现明确选择 A 的
实际 raw frame；保留原控制失败。剩余生命周期与回执审计完成前整体 P2 仍标进行中。


## .125 收口草稿（最终 NETWORK 审计已撤回其完成判定；通过的有限证据保留）

**P2 已完成。** 判定范围是设计第 7 节「首个经过闭合审计的完整 worker 分支」及其必须的
调度、故障、网络互斥和生命周期合同。保守支持清单保持不变；没有开始 P3 的玩法 worker 扩张、
P4 owner 包路由或 P5 的多人性能/长期 soak。整体设计仍 partial，不把 P2 完成写成全设计完成。

### 交付及唯一生产改动

最终 installer 为 `projects/neoforge/build/libs/neoforge-21.1.250.125-beta-installer.jar`，SHA256
`744391b0c02afb2699de74f3de1292c3945515f89add247981dc9fff67749776`；同字节归档
`tmp/player-region-tick-p2-final05/final125/artifacts/`。显式审核 manifest 为
`tools/player-region-tick/reviewed-artifacts/21.1.250.125-beta.json`，manifest SHA256
`6c99f7eba6d3fef2757a253c13e680224f87983c8cda38dc7cfa4aa5c4db07ed`。
审核由本主 Agent 根据源代码、patch、构建与安装字节进行，不声称人工或第三方批准。

相对接续 04 的生产代码只修改 `RegionPlayerNetworkGate`：旧 overflow terminal 直接关闭 TCP，
客户端没有明确断线原因包；新 terminal 在 accepted 回调前缀全部完成、真实 worker join 后，
经**当前** play/config common listener 的原 `disconnect` 发原因包，再按原 send/flush/关闭流程终止。
队列内的配置确认可替换 listener，故不捕获旧 listener；没有 common listener 才走原 raw close。
`.124` 的真实 raw-close 失败及 verifier 拒绝保留。新增单测验证替换到配置态 listener 后的原顺序。

默认仍 false；未知 Mod、Mixin/transform、监听器、未闭合状态整次串行；已开始正文异常不重放；
权限、原对象 claim、retains 和原 batch 只在物理返回/join 后清理。没有新增池、身份源、owner executor
或 oracle worker 特批。原 `.115` verifier 文件逐字节保持不变，所有旧 manifest 和旧证据保留。

### 验收矩阵及证据类型

| P2 项目 | 最终证据 | 结论 |
|---|---|---|
| 两入口完整正文、两池、独立区重叠和同区顺序 | `.125` 无 Mod 四组 smoke，原完整调用元数据及实际 TCP；额外原方法 JDI 栈/副作用 | 通过 |
| 部分提交/拒绝、open 前失效、取消 | 两池×两阶段 20 项矩阵；原 native pool/RegionTaskBatch/lease/coordinator 与真实 join | 通过 |
| 原正文异常、不同组/同组后缀、至多一次 | 两池×两阶段各 4 组独立组和同组安装服探针，64 篡改控制 | 通过；世界原异常终止遍历，连接后缀原位串行一次 |
| freeze/step、spectator、riding、死亡/credits/登录/配置资格 | 四组无 Mod 转换、四组真实死亡重生、四组未知 Mod 配置握手；相应票据及拒绝测试 | 通过；保留原资格，不强制 tick 已移除实例 |
| camera、未加载邻区、可选环境、idle/floating、memory 等准入 | 完整 spatial/state、原注册和两个阶段的前置失效/拒绝测试；idle/floating 另有真实计时器 | 通过；不截断查询，不把不支持路径放进 worker |
| 主线程 payload、enqueueWork、过滤聊天及混合包 | 四组 UNKNOWN_MOD 的原 MAIN handler/同步内联与取消事件；本地 HTTP 原异步过滤链、原帧独立 decode | 通过；各原流/因果边界保序，保留 vanilla 异步语义 |
| batch 期间网络静止窗口、溢出与有界释放 | 两池×两入口×正常/溢出 8 组真实 TCP/JDI；16×4120 回调压力、refCnt、空 envelope 和捕获图 GC | 通过；无正文暂停期间提前回复，溢出一个协议 kick |
| TCP 关闭、活动 Level 退役、停服取消/清理 | 两池×两入口 4 组物理 socket loss；真实引擎 clearLevelRegionTasks 的 4 个活动 native scope 退役测试；原 shutdown 位置观察 | 通过；活动 scope 保持到真实返回，发布者再退役 |
| 同区/跨区传送、取消、维度/重配置/重生旧实例 | 四组 production-only、四组 unknown-contract-Mod 的转换；四组 Nether、四组死亡重生；精确代次/身份测试 | 通过；旧票据不能授予新实例权限 |
| 保存、断线重连及重启恢复 | `.125` 四组真实拾取 5 钻石/7 XP、重连、下界往返、save/stop/restart 的原状态与原帧 | 通过 |
| 原阶段/event 位置与次数 | schema 3/4 八组独立阶段校验；无 Mod 四模式各 30 完整帧的原 EventHooks/完整 doTick 栈 JDI；另保留早期 30 帧 | 通过；有玩家世界 BE 位置保持；空世界按原 300-tick 资格跳过 BE |
| 关闭模式及既有实体/BE/兼容保护 | 原路径四组 off/on 功能回归、476 JUnit、三个选定 GameTest；保护源码与初始快照一致 | 通过 |

暂停边界的证明类型明确保留：此 P2 分支拒绝 integrated memory Connection，两个 admission 计划的拒绝已测试；
原 `IntegratedServer.tickServer` 暂停分支只调用原 `tickPaused` 统计，不进入这两个新派发入口，源码未修改。
本轮没有启动图形集成客户端做 GUI 暂停实测，也没有声明其本地连接获得 worker 支持。
Level unload 的活动作用域证据是原引擎生命周期入口与实际 native pool/lease 的竞态测试；没有把它称为
任意动态维度 Mod 的并行卸载证书。未知 Mod 始终整次串行。长期任意扩展/网络洪泛/GC soak 留在 P5。

### 最终产物上的游戏结果

无 Mod `on-fixed` 独立区两阶段各 242 次 worker，ENTITY/CONNECTION 真实重叠计数 107/109；
`on-persistent` 各 248 次，重叠 49/77。同区两池各阶段各 242 次，重叠均为 0。off 两组玩家 worker=0。
四组正常客户端 A=(47.5,100,24.5)、owner(0,0) → B=(49.5,100,24.5)、owner(1,0)，距离 2，
**B 服务端 health 20→19，客户端 health=19，恰好一个 damage packet**。攻击包继续在原服务器线程静止边界处理。

同一个 `.125` 的 schema 3 共 **144** 项真实 TCP 战斗/实体场景，schema 4 共 **180** 项方块、BE、
共享容器、拾取、XP 和死亡重生/守恒场景全部通过；100 个功能负控制、40 个原阶段负控制通过。
这些套件加载 oracle，因此按原生产门拒绝 UNKNOWN_MOD，两个入口 **worker=0**。
它们证明实际跨区实体、方块、实体方块、共享箱子操作和资源守恒在新产物上保持正确；不作为这些玩法
已在 worker 内执行的证据，不据此放宽 P3 准入。

新的死亡重生四模式保留 y=100.1 的原负窗口，再用一个普通落地包到 y=100；on 两池新实例均观察到
ENTITY/CONNECTION 各 77 次 worker，旧实例不再开始调用，off=0。原始 health 20→0→20、死亡/重生
原包、物品/XP 与退出清理均独立检查。End credits 同样经过原 win_game 和标准 respawn 包及新实例。

四组计时器等待原一分钟 idle，没有加速时钟；每组一个真实浮空 kick 和一个真实闲置 kick。
8 组网络暂停探针中正常 accepted 前缀各 256 个 ping，溢出各发 4200 个、accepted 4096 个，
所有 accepted 回复严格按原顺序，暂停窗口无提前回复，溢出恰好一个带明确原因的原协议 kick。
这些外部暂停数据不计作延迟或性能测量。

### 回归与审计

- **476 项 JUnit**，20 类，零失败/错误/跳过。包括原完整回归、两阶段/两池故障、活动卸载、同步 payload
  返回与原异常、内联/异步 queue、retains 与有界 GC。源码级审核支持清单不变。
- 三个选定 GameTest 均过：physicalEntitySectionCrossing、blockEntityOwnerOperations、unsafeTickerForcesWholeRegionFallback。
  原物理加载 timeout 600 保持不变，没有本轮放宽 deadline。
- 26 个持久 Minecraft patch 与应用 archive/生成源码逐字节一致；定向格式、diff、全部 Python/Node 工具语法过。
- 最终索引包含 **65 份独立验收 verdict**（含八份 oracle 阶段校验和一份早期补充观察），及
  **243 项 proof 负控制**；manifest 六项和增量 NDJSON reader 三项工具单测另记。
- 四模式各 30 帧的原钩子观察覆盖玩家完整 native/serial 调用和原空世界资格；没有安装 Mod、Java agent、
  类重定义或准入例外。实际重叠另来自未开调试器的 production smoke。

失败与修正完整保留于新根目录及旧继续目录：构建/泛型/格式夹具错误、保存 RCON 超时、配置未完成即发游戏态包、
JS number/i64 零 ID、早建 End 出口被平台覆盖/过早读反馈、错误的全局 CALLBACK running=0 假设、
ServerStopped 早于 core finally-close、跨 listener 重用 teleportId 的 verifier 问题、聊天负控制选错副本、
空维度 BE 错误假设，以及 socket 探针读取未换行半行的驱动竞争。最终接受的重试均使用全新安装或独立新 verifier 输出。
`socket/matrix-result.json` 和最初 phase-order 失败状态没有改写成通过，最终组合/重试另有明确 verdict。
旧 socket 失败中的一个 debugger wrapper 被终止，原退出码没有可恢复记录；该项按 **aborted/exit unknown**
保留，未计作通过。最终检查本轮所属 server/debugger JVM 均已退出。

准确的命令索引、非零命令及该未知退出项以 `receipt.json` / `final-proof-audit.json` 为准。
没有 commit、push、部署或现用服操作；所有初始源码文件、用户保护文件及全部失败证据保留。

### 停止位置

**P3 尚未开始。** 仍未交付的完整玩法 worker 支持、owner 包路由、1/8/32 真客户端多分布、
MSPT/tail latency/allocation 和长期恢复/soak 属于 P3–P5。没有加速或广泛网络延迟非回归结论。
P2 已在上述受限合同内收口；后续须由用户另行决定是否开始 P3。


## .125 最终 NETWORK 审计反例与 .126 修复（当前复验中）

原 `NETWORK` payload 回调的线程合同确有缺口，已用普通 TCP payload 和只读外部 JDI 观察确定复现：
`tmp/player-region-tick-p2-final05/server-network-contract-repro125/network-contract/`。Mod 为 UNKNOWN_MOD，
玩家 worker=0；暂停原 dispatch 的成功 freeze 之后、未知 Mod 捕获拒绝之前，发一个原 payload，
其回调经 Deferred 在 Server thread 执行。原计数 total=1/onServer=1/onNetwork=0/main=1，
原线程/调用栈及当时 core/driver/Mod 源快照均保留。不是 oracle worker 特批，也没有改写运行类。

`.126` 在 `RegionThreadingEngine.dispatchPlayerPrefix` 创建网络边界之前先检查全局 extension snapshot。
未知/无法检查的环境直接完整串行并记录原拒绝；后面的逐调用 capture/consume 仍完整复查。
因此未知扩展的显式 NETWORK handler 保持原 Netty loop，不再因一次最终被拒绝的尝试转到 Server thread。
保留 `.125` current-listener 协议溢出修复，其他生产准入清单不扩大。新增两个阶段的 refusal-before-freeze
单测，并要求实际 prefix 的拒绝计数增加，避免把没进入 prefix 的空测试当证明。

候选 `.126` installer SHA256 `cc0c9a4277a8cb6b882e68ad913968ab168380b6bae15a000162aaa6d07405aa`。
原 `.125` 的 receipt/65 verdict/所有反例均保留，**其 P2 complete 草稿被本审计撤回，不能作为当前最终结论**。
新的完整复验位于 `tmp/player-region-tick-p2-final05/final126/`；P3 未开始。


### .126 接续复验恢复记录（2026-11-17）

恢复时原后台进程已不存在，smoke/socket/timers 的后续 install 和顶层 orchestration 缺少最终退出记录。所有中断目录原样保留，标记 interrupted/exit unknown，不补造退出码，不接受为完整通过。已完成的 functional/network/same-group/fault/transitions/network-contract/contract 七个矩阵继续有效；其余在全新 `final126/recovery01/` 安装重跑，并继续 lifecycle/respawn/phase-order、三个 GameTest 和独立负控制。478 JUnit、manifest 六项、NDJSON 三项、初始138文件保留与保护文件未变、两处生产改动、26 patch/archive/generated 字节检查已通过。当前仍复验中，P3 未开始。


### .126 复验进度：最后重生矩阵（2026-11-17）

13矩阵中的12个已完整通过：functional、network、same-group、fault、transitions、network-contract、contract，以及recovery01下smoke、socket、timers、lifecycle、phase-order。三个选定GameTest和243项proof负控制全部通过。尚在跑最后四模式respawn，当前不宣布P2最终完成。两池smoke独立区ENTITY/CONNECTION：fixed各248次worker、重叠95/98；persistent各236、重叠73/84；同区fixed各242、persistent各232，重叠均0。四组正常跨区攻击health20→19、客户端19且一个damage packet。四模式save/stop/restart、下界往返、5钻石7XP守恒均已通过。P3未开始。


## .126 最终 P2 收口（2026-11-17；在 P3 前停止）

**P2 complete，限于设计第 7 节“首个经过闭合审计的完整 worker 分支”；整体 partial，P3/P4/P5 未开始。**
两个原位入口分别执行完整世界 guardEntityTick/tickNonPassenger → ServerPlayer.tick 与完整 Connection.tick/listener/doTick，
复用唯一原 engine、池、lease、barrier 和 ledger。首个支持分支仍为静止、空库存、惰性已加载地形、可选状态闭合；
未支持状态与未知扩展整次串行。默认 false、开始后不重放、真实物理返回/join 后清理全部保持。
本次没有实现 P3 玩法 worker 扩张、P4 owner 包路由或 P5 性能阶段。

最终 installer：`tmp/player-region-tick-p2-final05/final126/artifacts/neoforge-21.1.250.126-beta-installer.jar`，
SHA256 `cc0c9a4277a8cb6b882e68ad913968ab168380b6bae15a000162aaa6d07405aa`。
universal SHA256 `3d0ec4cfc9d03a6d6132ed5cc5d9226d93c5584d52dcd886662dc0c38e2f622b`。
显式审核 manifest 为 `tools/player-region-tick/reviewed-artifacts/21.1.250.126-beta.json`，
SHA256 `b43c22a51fd9bc5b6598a789202c82f740ed87d1fe85d8ff595f61039e3472d1`。
这是主 Agent 的源码/产物审核，不声称人工或独立审核；保留所有旧 manifest 和原 `.115` hash gate。

### 两处生产修复及被撤回结论

相对接续 05 初始快照，生产源码仅变动 RegionPlayerNetworkGate 和 RegionThreadingEngine。
溢出 terminal 在 accepted 回调前缀和真实 join 之后，使用**当前** play/configuration common listener 的原 disconnect，
使客户端收到明确协议原因后关闭 TCP；没有 common listener 时才走原连接关闭。

最终审计确认 `.125` 在 UNKNOWN_MOD 拒绝前先冻结网络，会把显式 NETWORK handler 从原 Netty loop 移到 Server thread，
即使 worker=0 也改变了合同。一个普通 TCP payload 和只读外部 JDI 已独立确认该缺陷，原计数/栈/core/源码与
`server-network-contract-repro125/network-contract/independent-defect-verdict.json` 均保留，明确为 defect_confirmed_not_accepted。
因此 `.125` complete 草稿及旧 receipt.json 是**已撤回的历史结论**，不能冒充当前完成证据。

`.126` 在创建 freeze 边界前先拒绝未知/不可检查扩展，后续逐调用 capture/revalidate/consume 仍完整复查。
新增两个阶段的实际 prefix refusal-before-freeze 测试，要求拒绝计数增加且从未调用 freeze。
最终四模式每组 500 个普通 NETWORK payload 保持原 Netty loop，500 个 enqueueWork 保持原 Server thread，
错误线程、错序、deferred callback 均为 0。未知 Mod 的两个入口 worker=0；没有 oracle 或测试 Mod 特批。

### P2 必须项与最终证据

| 范围 | 最终证据与结果 |
| --- | --- |
| 两池、两入口完整原正文与同区顺序 | 无 Mod 四模式 TCP smoke 全过；on 两阶段独立区有真实重叠，同区重叠 0；off worker=0 |
| 部分提交、拒绝、前置 open、后缀与至多一次 | 两池两阶段 native/lease/coordinator 回归；未开始可整次串行，开始后原异常且不重放 |
| 原完整正文故障 | 两池×两阶段独立组 4 组、同组 4 组原正文副作用后异常；原异常/原断线、其他组 join、后缀和引用释放均过；64 负控制 |
| 原阶段及事件顺序 | schema 3/4 八组原顺序校验；无 Mod 四模式每组 30 个完整原服务器帧 JDI，原 Pre/Post、Level/BE、Connection/PlayerList 位置与调用栈均过 |
| freeze/step、spectator、riding、死亡/credits/配置等 eligibility | 四组 production-only 转换、四组 UNKNOWN_MOD 合同、四组原死亡重生；完整票据/拒绝测试通过，保留原资格 |
| memory、camera、未加载邻区、可选状态/环境 | 两阶段完整拒绝及捕获/复查测试通过；不截断查询，不把未支持状态放进 worker |
| MAIN payload、显式 NETWORK、异步聊天、keepalive、混合包 | MAIN 同步内联/结果/原异常与取消测试，NETWORK 四组500+500；原 HTTP 异步过滤链、keepalive 和原帧独立 decode/因果顺序全部通过 |
| 原 idle/floating 计时器 | 四模式等待原一分钟闲置与实际浮空 kick，各一个原协议原因，不加速时钟 |
| 网络冻结、保序、溢出 | 两池×两入口×正常/溢出 8 组 TCP/JDI；正常256回复、溢出4200输入/4096 accepted前缀按序，暂停窗口无提前回复，溢出一个明确 kick |
| 断线/停服取消、retain 与退役 | 两池×两入口4组 worker 内物理TCP loss；16×4120 callback压力/refCnt/空envelope/GC，原 shutdown位置与活动scope真实join后清理通过 |
| 传送、取消、维度/末地/重配置、新旧实例 | 四组production-only和四组未知Mod合同；四组下界往返及四组死亡重生，End win_game/标准respawn；旧票据不授权新实例 |
| 保存恢复与重连 | 四模式真实拾取5钻石/7XP、步行、keepalive/chat/ping、重连、下界往返、save/stop/restart原状态和原帧通过 |
| 活动 Level 清理 | 原 clearLevelRegionTasks 入口与两池两阶段实际活动 native lease/scope竞态测试通过，真实返回后退役 |
| 跨区玩法与守恒 | 同一`.126`完整schema3 144项、schema4 180项及100功能负控制全过，原服务端状态/事件/客户端反馈与物品XP总量独立检查 |
| 回归及持久改动 | 478 JUnit（20类，零失败/错误/跳过）、三个选定GameTest；26 patch/archive/generated逐字节一致；138初始源码文件保留、7保护文件未变、旧`.115`verifier未变 |

阶段顺序和NETWORK反例使用loopback外部只读断点。故障矩阵在原正文已产生实际副作用的断点处，
通过JDI ThreadReference.stop注入RuntimeException以验证原异常路径；网络/socket探针暂停原正文、读取原诊断并发送正常TCP包。
这些探针均无游戏Mod、Java instrumentation agent、类重定义或准入例外。
原空维度在300 empty ticks后可以不执行BE，观察记录实际emptyTime并验证原资格；有玩家世界仍严格保持BE fence。
调试器暂停时间不作为重叠、延迟或性能证据。真实重叠来自无调试器的production smoke。

### 用户关心的实际跨区结果

四模式正常客户端 A=(47.5,100,24.5)、owner(0,0) 攻击 B=(49.5,100,24.5)、owner(1,0)，距离2：
**服务端 B health 20→19，受害客户端 health=19，恰好一个真实 damage packet。**
游戏操作均为正常客户端包；命令仅用于夹具/独立状态读取，原服务器控制的配置转换仍由原任务启动。

| 模式 | 独立区 ENTITY worker | ENTITY 重叠 | CONNECTION worker | CONNECTION 重叠 | 同区 ENTITY worker | 同区 CONNECTION worker | 同区两阶段重叠 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| on-fixed | 248 | 95 | 248 | 98 | 242 | 242 | 0 / 0 |
| on-persistent | 236 | 73 | 236 | 84 | 232 | 232 | 0 / 0 |

off两组两个阶段均worker=0。完整schema3/4覆盖正负坐标、角落、双向PvP、普通实体攻击/挤奶、survival挖掘/放置、
实体方块/共享箱子、物品/XP竞争及死亡重生守恒，全部通过。oracle始终UNKNOWN_MOD，worker=0；
这些是新产物的玩法/事件/反馈/守恒兼容性证明，不能宣称上述玩法已在worker执行。

重生保留原y=100.1负窗口，再由普通落地position包到y=100；新实例两个入口重新准入、旧实例不再开始，off=0。
保存恢复独立检查五钻石、七XP与实际维度状态，不以命令成功字符串代替原客户端反馈。

### 完整证据、失败与未覆盖项

`final126/accepted-evidence.json` 收录 **68份独立验收verdict**（60份主语料、8份oracle原阶段），
**243项proof负控制**：功能100、oracle阶段40、native故障64、socket6、原阶段8、网络13、转换9、计时器3。
manifest六项和NDJSON reader三项工具单测另计。完整命令、源码快照、闭合原输入、installed core与归档hash索引
见 `receipt-final126.json`；最终逐项复核见 `final-proof-audit-final126.json`。旧receipt.json不改写。

本轮恢复时上一轮后台进程已不存在，原`.126`的smoke/socket/timers及顶层等待wrapper没有完整退出记录，
全部保持为interrupted/exit unknown，不补造退出码、不接受为完整通过。剩余六矩阵在全新`final126/recovery01/`
安装重跑；当前通过的matrix-result与68份verdict指向明确的新路径。更早`.124/.125`所有失败、反例、
驱动/校验器重试以及被终止且退出码未知的debugger wrapper均保留。没有把原失败matrix改写成通过。
机器回执列出实际已记录的非零命令和退出未知项，不声称所有历史命令都有退出码。
最终证据收集首轮误把转换负控制分配写成production4/contract5，实际原结果为3/6、合计仍9。
保留收集失败日志及source-at-failure.py，仅修正索引计数后以collect-evidence-command-v2重跑通过；原语料/verdict不变。

集成memory连接仍拒绝；GUI暂停只有原控制流源码审计和拒绝测试，未启动图形集成客户端重放。
Level卸载只证明原引擎入口和native池/lease的活动退役，不是任意动态维度Mod的并行卸载证书。
压力与GC验证有界；长期任意网络/扩展soak以及1/8/32客户端、多分布、MSPT/p95/p99、分配和收益属于P5。
本轮没有加速或广泛尾延迟结论。完整玩法worker扩张属于P3，owner网络路由属于P4。
客户端第三方协议库的历史recipe/SlotComponent PartialReadError诊断保持原日志；实际测试操作、响应和包序由原始帧独立解码及服务端/事件状态核对。本轮未修改该协议库。

所有源码、世界和失败证据保留，未reset/clean、commit/push/deploy或操作现用服；未使用SubAgent。
最终审计检查本轮所属server/debugger JVM已退出、HEAD未变且未暂存。**到此停止，不开始P3。**
