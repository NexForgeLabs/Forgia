# 玩家 Region Tick：源码调查与实施方案

日期：2026-11-17。Forgia 基线：`1943dc5919acea387bcac28352b2eeb748cadc63`。

状态：**设计，尚未实现或验收**。本次只增加方案文档，不改变玩家准入、包处理、配置默认值或现有运行时。
目标是让符合完整执行合同的真实玩家在所属区域的 worker 上执行原始 tick，逐步扩大覆盖，同时保留未适配功能的完整串行执行。
不是把几个算术计算派发到线程池后称作玩家并行。

实施进度与真实测试证据见 [实施报告](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md) 和 [机器 receipt](PLAYER-REGION-TICK-IMPLEMENTATION.zh.json)；以报告状态为准，设计原文保留历史说明。

## 1. 参考范围与结论

本地 Folia 参考位于 `/tmp/Forgia-Folia-reference-1.21.1`，从已有只读参考仓库 clone，分支 `dev/1.21.1`，
提交 `2e7bc0721af95196c85500c7bb136aeea0bc12ce`。没有查询最新版，也没有构建或运行 Folia。
以下行号均对应该提交的 `patches/server/0003-Threaded-Regions.patch`：

| 参考位置 | 实际机制 | 对 Forgia 的启示 |
|---|---|---|
| `TickThread`，约 86–280 | 按实体、坐标、AABB 和移动范围检查当前区域；线程名不授予权限 | 玩家拥有权必须覆盖实际访问的对象和邻域 |
| `RegionizedServer`，约 1959–2016 | 全局线程跳过区域拥有的游戏态连接，继续处理登录/配置态连接 | 玩家和连接需要一个明确、互斥的执行归属 |
| `RegionizedWorldData`，约 2929–3031、3180–3195、3341–3380 | 区域维护玩家和连接集合，merge/split 迁移它们，区域执行连接 tick | 迁移玩家位置却不迁移连接归属是不完整的 |
| `Connection`，约 7831–8160 | 发送队列单消费者、断线处理原子化等配套修改 | Netty 能发送数据，不等于连接及 listener 的全部状态已线程安全 |
| `PacketUtils`，约 8160–8202 | 游戏包转交实体调度器；登录/配置包交全局队列 | owner 执行入口必须跟随玩家，不能永久绑定旧坐标 |
| `MinecraftServer`，约 8870–8900 | 区域路径执行自己的连接 tick，玩家列表维护保留全局职责 | 连接模拟与服务器全局玩家管理需要分开 |
| `ServerPlayer`，约 12083–12490 | 重生/传送包含移除、加载、放置、完成和连接归属交接 | 传送是协议，不能只放宽 `setPos` 的线程检查 |
| `ServerGamePacketListenerImpl`，约 12717–13212 | 车辆位置、回调调度、断线和传送都有配套适配 | 不能只移动 `doTick` 调用点 |

这是设计参照，不复制 GPL Folia/Paper 的实现正文。Forgia 继续使用现有固定布局、统一 tick 时钟和屏障，
不在此工作中引入 Folia 的动态区域合并/拆分或独立区域时钟。

## 2. 当前 Forgia 的两个玩家入口

实际生成源码与持久补丁必须一起看；修改落在 `patches/` 和根目录 `src/`，不能只修改生成的 Minecraft 文件。

```text
MinecraftServer.tickChildren
  ├─ 每个 Level：LevelTickEvent.Pre
  │    └─ ServerLevel.tick
  │         ├─ 普通实体 region batch → join → handoff
  │         ├─ 玩家等完整串行 fallback
  │         │    └─ tickNonPassenger / tickPassenger → ServerPlayer.tick
  │         │         gameMode、菜单、相机、criteria、advancements
  │         └─ tickBlockEntities
  │    └─ LevelTickEvent.Post
  ├─ ServerConnectionListener.tick → Connection.tick
  │    └─ ServerGamePacketListenerImpl.tick
  │         ├─ ack、resetPosition、记录旧位置
  │         ├─ ServerPlayer.doTick → Player.tick → LivingEntity.tick
  │         │    PlayerTickEvent.Pre/Post、inventory、food、effects、拾取等
  │         └─ authoritative position、floating、vehicle、keepalive、idle
  ├─ PlayerList.tick
  └─ chunkSender.sendNextChunks / resumeFlushing
```

另有独立的包处理入口：`PacketUtils.ensureRunningOnSameThread` 把一般游戏包排到服务器事件循环；
聊天异步回调、NeoForge payload 和 `enqueueWork` 还有自己的回流入口。

当前保护并非只有一个 `instanceof Player`：

- `RegionEntityTickPolicy` 的 `Player.class -> player_riding_stack` 声明覆盖整条骑乘链。
- always-ticking 玩家在 self-driven 实体路径被交回服务器；冻结规则还特别保留玩家及其坐骑。
- `PlayerRegionTransitions` 只在 entity slice 开始的静止点发布玩家区域；它是映射跟踪，尚非连接迁移协议。
- `RegionEntityOwnershipScope` 不授予 `ServerPlayer.teleportTo/dismountTo/teleportRelative` 等完整操作。
- `RegionPlayerSnapshots.Entry` 含 live `ServerPlayer`；不可变列表和 AABB 不会冻结玩家的 health、inventory 等字段。

保留这两个 tick 阶段。不要把连接 tick 直接提前塞进实体循环，否则 `doTick` 相对 BE、Level Pre/Post、
其他维度以及网络确认的位置都会变化。先用测试冻结实际基线顺序，再扩展执行线程。

## 3. 建议的初版调度模型

### 3.1 同一引擎、两个原位执行阶段

增加内部玩家协调器（暂名 `RegionPlayerTickCoordinator`），作为现有 engine 的子模块。
它负责玩家计划、连接归属和一次执行记账，不拥有独立线程池、独立区域锁或第二份实体存储。

**世界阶段：** 在当前玩家 fallback 的实际位置调度 `ServerPlayer.tick` 对应的完整调用；普通实体 worker 已经汇合。
初版不把玩家放入正在执行的普通实体 batch，保持现有 AI/刷怪/BE 对玩家静止状态的依赖。
其他串行 fallback 若能观察或修改玩家，应作为完整串行 fence；不能把后面的玩家越过它提前执行。
收集时保留父调用与顺序，骑乘玩家不能再作为独立 `tickNonPassenger` 重复执行。

**连接阶段：** 在原 `ServerConnectionListener.tick` 位置收集游戏态连接，按当前玩家 owner 形成有界批次，
执行完整 `Connection.tick` / listener tick，而不是只移走 `player.doTick`。
登录、配置、未完成归属交接及未准入连接继续原串行路径。
保持连接遍历中串行 fence 的位置，先 join 前缀，再执行 fence，再重新检查后缀。
初版跨 Level 批次不重叠；同一 Level 同时仍只有一个 region batch。

每个区域内按该阶段确定的玩家顺序执行；只有足迹互不冲突的不同区域可以同时运行。
共享库存、共享目标、邻域相交或全局回调造成冲突时合并执行组，或在正文开始前完整转串行。
单个热点区域中的 100 名玩家不会因此拆成 100 个并发任务。

世界阶段与连接阶段分别准入、分别统计。两者都在 worker 完成之后，才可以宣称覆盖该玩家的两个原始 tick 入口。
世界阶段汇合后的传送/死亡/断线可能改变连接阶段准入；不能复用旧结论。

### 3.2 先保留包的服务器线程合同，再逐类支持 owner 包

第一版允许游戏包继续在服务器线程处理，只能在没有玩家 worker 持有相关权限的静止窗口执行。
当前 `RegionTickBarrier` 不泵 `MinecraftServer` 任务队列，这是可复用的基础，但仍须审计 chunk task、
内部 server-service、网络线程直接回调及第三方 executor，证明没有另一条玩家写入入口。
排队包在 worker 批次期间继续排队；不能借 chunk 服务泵顺便执行任意包或任意服务器 Runnable。
必须验证新增屏障对网络处理延迟的影响，不能只检查没有数据竞争。

后续才为已审核的游戏包添加玩家 owner 路由：

- 按连接接收序列排序，校验 listener/session、玩家实例和生命周期代次；迁区后在新 owner 重查。
- 同连接的全局包或未知 Mod payload 是顺序 fence，不能让后续 movement/click 越过它。
- 协议重配置、资源包状态、断线和异步聊天回调分别审计；不假设所有包都经由 `PacketUtils`。
- `MainThreadPayloadHandler` / `ServerPayloadContext.enqueueWork` 的现有服务器线程承诺不变。
  未声明的处理器继续主线程；若以后提供 owner executor，应是显式新能力。
- 需要保存原包及有引用计数资源时，明确 retain/release、取消和错误路径。队列溢出使用现有协议错误/限流语义，
  不静默丢移动包或库存操作，也不无界积压。

完整 owner 网络路由不是初版玩家 tick 的先决实现，但“串行包与 worker 的互斥证据”是。

## 4. 复用与扩展边界

| 现有模块 | 复用内容 | 需要增加或澄清 |
|---|---|---|
| `RegionThreadingEngine`、`RegionWorkAdmission`、batch publication | 安装、启动配置、一个活动批次、退役边界 | 两个玩家阶段接入；生命周期清理包含玩家批次 |
| `RegionWorkerPool`、persistent dispatcher、`RegionTickBarrier` | 两种池、真实 join、失败上报 | 玩家阶段的定向覆盖；不能因超时认为 worker 已停止 |
| `RegionState` / `RegionLayout` / `RegionLeasePack` | 固定区域映射、规范顺序的 all-or-nothing 排他获取 | pack 当前只是派发/排他机制，不自动授予多区域实体读写权限 |
| `RegionOwnershipLeaseGroup` / `RegionEntityOwnershipScope` | owned entity、延迟 section/removal/addition、关闭 scope | 为玩家组逐项实现支持的操作；无实体参数的全局 guard 继续拒绝 |
| `RegionEntityTickPlan/Batch` | 真实 tick 入口、fallback 和屏障后效果顺序 | 抽取可复用编排，不把 player 当普通 Mob 直接放行 |
| `PlayerRegionTransitions` | 静止点发布、唯一索引、维度身份交接、退役 | 两阶段间 owner 重查；不在 worker 修改服务器专用映射 |
| `EntityIdentityLedger`、transfer journal | 生命周期身份与已有区域迁移 | 增加连接/session 和逐阶段执行记账；现有单 Level token 不能冒充跨维度事务 |
| `EntityRegionScheduler` / `EntityRegionScheduledTask` | 实体迁区后任务重调度 | 现有任务 generation 是调度代次，不是连接/session/respawn epoch；不能原样用作包队列 |
| `EntityInteractionScheduler` / owner operations | 双对象操作、明确终态、屏障后执行 | 现有服务接纳 entity batch；玩家连接阶段需明确接入和同步返回合同 |
| `RegionPlayerSnapshots` / `RegionScopedEntityQuery` | 候选 AABB、身份查询、现有非玩家阶段 | live player 引用不能成为并行玩家状态快照 |
| `ForgiaConfig`、declarations、observability | 启动开关、兼容拒绝、原因计数、状态命令 | 玩家专用准入/拒绝与每阶段计数；保留精确实体类型声明优先级 |

BE canonical coordinator 的连续前缀、完整 fence、重审思路可以复用；
不直接把 BE 的 `BlockEntityExecutionAdapter`、Ticket 或资源 Proof 用作玩家权限。
玩家的世界访问、碰撞、包状态和生命周期并不满足 BE 本地资源合同。

## 5. 准入前必须闭合的访问

### 5.1 玩家邻域和跨区活引用

只拥有玩家中心所在 chunk 不够。`Player.aiStep` 对玩家/坐骑 AABB 扩展后查询实体，调用 `playerTouch`；
移动、碰撞、液体、环境伤害、吸取经验、物品拾取可能访问邻区。
正常过边界不能通过截断查询、漏掉碰撞、忽略拾取或减少搜索半径来“解决”。

初版可在计划阶段对**有已审计上界**的分支检查完整加载邻域和访问足迹，冲突组共用区域排他。
超过足迹界限或存在未知回调时，正文开始前完整串行。任意 Mod 回调没有有限半径保证，不能用固定 halo 假装证明。
若扩展多区域 scope，必须使实体查询、方块访问、section 发布及目标对象权限全部理解该集合；
只获取多个 `RegionState` lease、仍使用单区域 scope 是不完整实现。

玩家阶段与普通实体阶段先隔离，可保留普通 AI 使用 live 玩家引用的当前合同。
玩家阶段内部仍要排除玩家 A 读正在被 B 修改的活对象：
已知访问重叠则同组顺序执行；未知可达对象则完整 fence。
不能创建假的 `ServerPlayer` 当快照，也不能全局把外区玩家查询改成 null。
只有返回纯数据的新内部/公开查询才能使用完整不可变状态视图；现有返回活对象的 API 保持合同。

### 5.2 全局状态与同步结果

| 路径 | 具体风险 | 首版要求 |
|---|---|---|
| Scoreboard / stats / criteria / advancements | 玩家每 tick 的统计可访问全局 objective，奖励可执行命令或触发其他玩家写入 | 审计完整调用链；未知奖励/条件/回调完整串行，不能延迟回调后继续读旧结果 |
| Menu / inventory / capability | 两名玩家可打开同一箱子/机器，包装器可别名同一 backing storage | 已关闭的本地菜单/库存合同才准入；共享菜单等保留完整串行或冲突分组 |
| 地图及 complex item | 获取 map registry 安全不代表 map pixels、装饰和玩家 trackers 安全 | 初版未闭合路径完整串行；现有 map storage server-service 不构成全部地图权限 |
| Sleep / camera / chunk tracking | 睡眠影响 level；camera 可移动到外区；tracking 操作触达服务器存储 | 每条路径明确完整串行分支或专门协议，不仅放宽坐标 setter |
| Combat / PvP / pickup / XP | 目标、物品、玩家库存与同步布尔结果同时相关 | 同组拥有完整对象集合，或完整操作 fence；不重放伤害/拾取 |
| Events / attachments / equipment / potion / item ticks / Mixins | 已有同步回调可任意访问其他玩家/世界，类型哈希无法证明语义 | 所有可达扩展点纳入准入合同，未知分支保留串行 |

不能只凭“vanilla ServerPlayer 类”“没打开箱子”或“没有玩家专属事件监听器”证明完整 tick 可并行：
物品、效果、附加数据、criteria、继承、Mixin 及通用 living/entity hook 仍可能带入 Mod 代码。
需要逐条列出支持分支和已审计扩展点；没有闭合的分支保留原功能和完整串行路径。
默认不允许任意模组回调并发，这可能使部分整合包中的玩家全部回退，应在诊断中如实呈现。

若使用现有内部同步 server-service，必须证明服务期间请求方暂停、服务只访问约定对象、没有别的 worker 读写冲突，
且不会再等待持有该玩家/邻区 lease 的 worker。该机制不是允许 worker 任意阻塞另一个 region 的公共 API。
可观察同步结果的操作不能简单“排到屏障后再做”；无法证明的调用保留完整串行。

### 5.3 骑乘、FakePlayer 和兼容声明

初版保留骑乘玩家与整条 riding stack 的串行合同。不能为了使普通玩家进入 worker 就删除 `Player.class` 声明，
否则同时改变坐骑和 `FakePlayer` 的保护。后续在完整 riding graph 所有权协议通过后单独扩大覆盖。
FakePlayer 不属于网络玩家 owner 会话，继续沿用现有执行路径；Create Deployer 等也不因本功能而放行。
显式 server-only 类/类型声明优先于玩家 worker 准入，包括当前 entity-safety 兼容模组的范围。

## 6. 生命周期和至多一次执行

需要一个服务器会话内的玩家执行记录，关联现有实体身份，而不是创建第二份实体身份来源。
至少区分 server session、玩家实例、connection/listener 身份、world incarnation、lifecycle generation、
服务器 tick 和阶段（ENTITY / CONNECTION）。每个调用保留自己的原版 eligibility，不强制冻结/转态中的玩家执行。
Level ledger 的 epoch 不能跨两个 Level 直接当全局大小比较；重生同 UUID 的新实例不能复用旧任务权限。

最小状态流：`准备 → 已准入 → 正文开始 → 已返回 → 已汇合/完成`；
正文前拒绝才允许完整原路径执行。正文开始后失败保留原异常，由服务器上报并清理，不在主线程重跑。
线程池拒绝与取消必须区分“未开始”与“已经开始”；未开始的后缀可完整串行，已开始的必须真实 join。

以下操作在静止边界执行完整原流程，或在后续专门协议中拥有明确交接：

- 跨区域走动、同维度传送、下界/末地传送与目标 chunk 加载。
- 死亡、重生换实例、末地 credits、重新进入配置态。
- 断线、踢出、重连、维度卸载、停服和玩家保存。

`PlayerRegionTransitions` 的“下一次 entity slice 发布”不够支撑连接阶段：
两个阶段之间可能已移动或换维度，需要在玩家批次发布前执行有审计的静止点重查/更新。
世界 ENTITY tick 与连接 CONNECTION tick 使用不同记账键；维度循环先后不能让同一阶段重复调用。
原流程中已有的换维度时序需先记录为 oracle，再决定哪些重复属于原有行为、哪些是新调度缺陷。

涉及同步返回值的传送/骑乘 Mod API，初版保持完整串行调用；不能透明改为“已排队”并返回成功。
停服先停止新准入，等待活动正文真实返回，处理/终止待决交接，再保存和释放引用。
不能在 worker 未停止时清空权限或保存同一玩家。历史任务终态只保留有界元数据，不保留整个玩家/Level。

## 7. 实施拆分与每步交付

| 阶段 | 具体交付 | 完成条件 |
|---|---|---|
| P0：冻结基线 | 真实双入口/包/生命周期 trace；审计表列出每个 player tick 可达的扩展点、读写对象和准入条件 | off 模式顺序/次数 oracle，单人和多人已安装基线；没有宣称 worker 已实现 |
| P1：统一执行归属 | 两阶段 player plan、执行记录、连接归属静止交接、诊断，正文仍原线程 | 冻结/骑乘/换维度/重生/断线至多一次；旧回调失效；无默认值变化 |
| P2：首个完整 worker 分支 | 在上述两个原位置执行经过闭合审计的完整原始调用；复用池/lease/join，完整 serial fence | 两个不同区域真实玩家正文重叠；同一区域顺序；两入口计数正确；不通过删功能获得准入 |
| P3：实际游戏功能扩张 | 有界邻域/冲突组、共享库存、拾取/XP、伤害、计分板/地图等逐类协议 | 每项具有状态/守恒/事件/同步结果测试；未支持路径明确完整串行 |
| P4：owner 网络路由 | 已审核包与异步完成跟随玩家；未知/全局包顺序 fence | 移动/传送确认/库存/重配置原顺序与终态；不改变 NeoForge main-thread payload 默认合同 |
| P5：多人及性能验收 | 两池、真实网络客户端、混合模组、长时/恢复测试和 on/off 测量 | 正确性门通过后报告 worker 覆盖、串行原因与收益；不以空服或 FakePlayer 替代 |

P0/P1 是最先实施的具体工作，不能为了尽快看到 worker 计数跳过它们。
P2 的支持清单由 P0 审计确定；如果 vanilla 某条必经共享路径尚未闭合，应先实现其所有权协议，
不能把 player tick 主体换成简化副本或只运行一段算术。

建议只增加一个默认 `false` 的玩家 tick 启动开关（暂名 `enableRegionThreadingPlayerTicks`），
依赖 bridge、固定布局、region-owned 实体存储及所需实体切片。
具体配置门按当前引擎行为校验：现有 Workers 键是队列 drain 开关，不能凭名字当全部 tick 的总开关。
缺少前置条件时保留串行并报告原因；没有另一个隐式自动开启的开关。
普通实体、BE、spawn、POI 等切片的默认值和权限不变；关闭新开关应回到原调用路径。

## 8. 验收矩阵

已有 `RegionPlayerTransitionTest`、`RegionSelfDrivenEntityTickTest`、`RegionEntityTickPlanTest`、
`RegionEntityTickPolicyTest`、declarations、entity transfer、interaction completion、资源内核与 Create 测试继续作为回归门。
新增测试必须穿过真实调用入口，而不只是向 executor 提交几个 mock Runnable。

| 范围 | 必须验证 |
|---|---|
| 调度 | 同区域无并发；独立区域完整正文有实际重叠；有限 worker 数；两种池；拒绝/部分提交/异常真实 join |
| 双入口 | ENTITY 与 CONNECTION 分别计数；PlayerTick Pre/Post 在实际 doTick 内且顺序/次数保持；Level Pre/Post、BE 相对位置保持 |
| 原版 eligibility | freeze/step、暂停、死亡、spectator、unloaded chunk、riding、末地 credits、登录/配置态 |
| 网络 | 相同连接包不乱序；movement → teleport → confirm；vehicle/input；闲置/浮空判断；keepalive；发送/flush 顺序；批次期间无服务器队列旁路写入 |
| 边界 | 两侧、角落、负坐标、极限位移、未加载邻区、camera、碰撞/环境；不截断原查询或忽略交互 |
| 守恒 | 两人同箱子、同时拾同物品/XP、死亡掉落/重生；总物品/经验及事件次数；无复制和无额外丢失 |
| 全局与 Mod | scoreboard、奖励、地图、睡眠；MainThread payload；未知 item/effect/attachment/Mixin；同步返回值及取消结果 |
| 生命周期 | 两方向维度 tick 顺序、传送取消、断线重连、旧实例任务、Level unload、停机保存/重启、活动 scope/lease/队列零残留 |
| off 回归 | 原玩家调用路径、当前利维坦声明、FakePlayer、普通实体/BE/spawn/POI/红石和 Create 路径保持 |

真实安装服至少覆盖 1/8/32 个客户端、同一区域/分散区域/边界交互三类分布，
再增加共享机器、地图、骑乘、PVP 和传送负载；负载规模随可用资源调整并披露。
报告完整 tick MSPT mean/p95/p99、包响应延迟、分配、屏障开销和每阶段 worker/serial 覆盖。
只有总 tick 改善且网络尾延迟/功能门未回归，才形成该负载上的性能结论。
同区聚集负载预期可能无收益，必须保留测量结果。

所有测试和安装服运行均为后续工作，本设计没有执行它们，也不扩张已有 C6 或生命周期证书的范围。
