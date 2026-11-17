# 玩家空间权限合同（TODO 3）

这是原 engine 的空间前置增量，不是单独授予完整玩家 worker 的证书。本模块完成时的 P1 两阶段仍在原线程；**其后 P2 首版已组合 production/state/network 合同并接入两个完整 worker 调用，当前证据见[实施报告](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md)最新记录**。默认开关仍为 false，原 eligibility、维度/BE/PlayerList 顺序保持。不能仅凭本合同中的原版类、空库存或惰性方块放行完整正文。

实现：`RegionPlayerFootprint`、`RegionPlayerSpatialPlan`、`RegionPlayerSpatialLease`、`RegionPlayerScopedQuery`、`RegionPlayerConflictPlan`。调度接口仍在唯一 `RegionThreadingEngine` 中，复用原 `RegionLeasePack` / `RegionState` / `RegionTickBatch` / `RegionEntityOwnershipScope`，没有第二池、第二身份 ledger、玩家替身或独立实体存储。

## 已闭合的首个几何分支

`capturePlayerSpatialPlan(player, ENTITY|CONNECTION)` 只捕获空间计划，不执行或统计玩家 tick。它要求原 server thread、原 engine 静止点、有效 fixed layout / REGION_OWNED / entity slice / player 开关及已发布实体 index。身份来自原 Level 的 `EntityIdentityLedger`，同时核对对象 UUID/int ID 和原 region mapping；不跨 Level 比较 epoch。

两个阶段共同拒绝：非精确原版 ServerPlayer（这里只限制可达空间正文，不代替回调合同）、FakePlayer、死亡/受伤状态、实例不在原 Level、传送/portal、骑乘链、外部 camera、活跃方块破坏/延迟破坏、shared menu、非站立/睡眠/spectator/飞行/自动旋转、效果/火焰/冻结、肩部实体、非空库存、未知尺寸或越世界边缘。骑乘查询的原 union/inflate 在几何接口中可完整表达，但**本次 production 捕获拒绝整条骑乘链**，没有缩小其查询半径或删除原 Player.class 声明。

CONNECTION 另要求原 `ServerPlayer.forgiaStationarySpatialInputs()`：无 jumping、xxa/yya/zza 输入、位置/头部插值、stuckSpeedMultiplier；onGround；水平速度严格 0，竖直速度有限且在 [-0.5,0]。脚下每个 bounding-box 列都须是已加载的完整惰性支撑方块，feet 为整数 Y。原 `LivingEntity.travel` 的无输入地面分支在调用 move 时以当前 delta 移动；负竖直 delta 被完整支撑面阻挡，水平无位移，不依赖下一 tick 的重力值。未知运动、流体、自旋、骑乘、特殊块、任意 Mod 空间回调在正文前保持完整串行。

ENTITY 原 `ServerPlayer.tick` 不调用 Player/LivingEntity 的物理正文；其 active game-mode destruction 和 camera 迁移被预先排除。仍保守使用同一完整邻域；menu、criteria、属性和回调的共享状态权不由几何推导。

| 原路径 | 捕获的空间边界/处理 |
| --- | --- |
| Player.aiStep 普通 playerTouch | **原** player BB.inflate(1,0.5,1)，包含所有活实体及原拾取/经验候选；不截断，不漏邻区，不把外区玩家返回 null |
| 骑乘 playerTouch | 几何 of 可接收完整 player/vehicle union.inflate(1,0,1)；当前 production 捕获整次 RIDING 拒绝 |
| LivingEntity.pushEntities | 原 body BB；包含于上述原查询框，目标属于同一 lease 集合；相交/共享目标归同组顺序执行 |
| Entity.move / collide / inside-block / support / fluid eyes / pose / listener noBlocksAround | 无位移站立分支，block bounds 为 body.inflate(1,1,1).expandTowards(0,-1,0)，原生碰撞/支撑搜索完全位于其中；精确块/流体访问有运行时 guard |
| 实体 section 搜索 | 对原 query 额外计入原生 X/Z -2/+2、Y -4/+0 的 section 候选余量；所需 region 全租借，不能只租 query 中心区域 |
| 巨大实体 / multipart | Level 具有任何 PartEntity 即拒绝；候选原 region 身份与完整 server query 对照，未知 playerTouch 类型整次串行；不截断 multipart 结果 |
| 物品/XP/其他玩家 | 捕获原活对象与唯一 ledger 身份。空装备仅排除本分支固有 mending/物品 tick 空间扩张，不能闭合一般 callback/global state |
| active gameMode / portal / camera / death / shoulder / 未知环境 | 完整 serial fence，不把远方目标、传送或死亡搜索假装放入固定 halo |

整个集合上限沿用原 `RegionLeasePack.MAX_REGIONS_PER_TASK=16`。为保守关闭直接 chunk 读取，捕获涉及 region 中**全部 chunk** 的原 non-loading getChunkNow 引用；缺任一 chunk/region、DYNAMIC/未发布 index、旧 owner 与当前坐标不一致都拒绝，绝不加载邻区来制造准入。实际 block 邻域仅支持 AIR/STONE/DIRT/GRASS_BLOCK/BEDROCK 且无流体/BE；不支持的块保留原串行行为。这个支持表不是 arbitrary mod callback 白名单。

## 完整集合权限

只有原发布 batch 的内部 `reservePlayerSpatial(parent,effects,plans)` 能取得 lease；它要求原引擎 server admission、父 batch 已发布/原调用方执行中/前序所有组已 join、无其他普通实体或 BE 活跃 worker、同一 Level 和同一阶段。两阶段各自捕获/重审，不复用世界阶段的结论代替连接阶段。

- 所有原 RegionState 按 canonical X/Z 用原 RegionLeasePack 一次获取；缺任一 lease 全部回滚，绝不拿到半集合就开始正文。
- reservation pin 计入**原父 batch**，未物理退出并释放前 batch.finish 和 P1 父 invocation.close 都拒绝；不能提前从 engine 活跃集合移除。
- 只在原 batch.groupWork 内的原 native worker 上 open。每个 RegionState 开始/结束原 ownership；借用原 RegionEntityOwnershipScope 生命周期，嵌套 scope 不继承权限。发布线程不能 open，其他线程不能关闭，活跃 worker 未退出不能 release；退出 finally 后当前权限为空。
- entity/block/chunk/section/query/lookup 均检查整个集合。原参数无对象的 global storage guard 保持无条件拒绝，不赋予任意 worker 全局权。
- entity 目标必须是捕获时的原对象/UUID/int ID，并位于租借集合；新增、换实例、缺身份或缺 region publication 的目标不被当作 absent/null。位置更新的目标 AABB 还必须落在原精确 block bounds 内；越界在原 setPosRaw 的副作用前失败。
- section/removal 通过传入的原 batch effects seam 延迟原存储发布；不提供新 admission/portal 或把同步结果改为“异步接受”。批次 effects 和 phase/lifecycle 票据仍由后续调度实现负责。
- 块/流体/BE 引用检查精确足迹；chunk lookup 从已捕获的完整加载集合返回，不能走 mod 非加载服务旁路；level-wide 玩家列表、getAll/无 AABB type query、未闭合 POI/全局奖励仍完整串行。运行中触发这些路径会失败并保留原异常，不能补租、重放或吞掉。

`RegionPlayerScopedQuery` 完全绕过普通实体的 clamp-and-count / player snapshot union。查询原 AABB 先通过**整个**捕获范围检查，再访问物理 buckets；类型、predicate、consumer、excluded、max/abort 和返回活对象合同保留。多个 bucket 的 section IDs 合并后按原全局 signed packed-long 顺序访问，因此不会因 region 遍历顺序改变“先触碰谁”。直接用单 bucket accessor 偷取完整玩家查询的子集也会拒绝。普通实体原 RegionScopedEntityQuery 路径及其计数/快照/截断合同保持不变。

## 冲突前缀和重审

`RegionPlayerConflictPlan.prefix(ordered)` 只处理同 Level、已捕获的连续前缀；null footprint、跨 Level 或超过 16-region 的冲突组在其首成员之前形成完整 fence。同区、共享目标对象身份、lease halo 任一相交会传递合并为同一执行组，组内保留原输入顺序；没有冲突的组才能交给后续两个入口的原池调度。

调用方必须 join 前缀后执行原 serial fence，并**重新捕获**后缀。提交前/获取 lease 后重复核对原 identity/index/mapping、加载 chunk 引用、完整原查询、支撑/惰性块和玩家几何；每个组中下一未开始调用还需重查 geometry。前一个玩家把另一个玩家推成非零水平速度等情况必须在后者开始前回原完整串行路径，而非开始后补跑。该规划器不运行、丢弃或重排任何原 eligibility/fence/BE/维度顺序。

TODO 2 已观察到同一死亡 item 越界后到下一次 entity publication 之间仍保留旧 indexed owner。空间捕获对此返回 OWNER_PUBLICATION_PENDING：本项不复制实体或创建第二身份，不把原始 index 和当前中心任取其一当权限。实际 worker 派发前必须在静止点完成原 publication，再重审完整 footprint。

## 当前限制与下游接口

P1 没有自动调用 reserve/open，没有真实玩家正文被派发，也没有 worker 计数增加。本项 native-pool 测试只认证空间能力和原 batch/ownership/query/guard，不是完整 ServerPlayer.tick 或 Connection.tick 并行验收。两个原始正文、未知 Mod 同步回调、共享菜单/奖励/scoreboard 等仍由后续 TODO 4/5 闭合，否则完整 serial。TODO 6/7 应在原位置复用这些接口派发完整父调用，分别记账和 join，不得将 scope 测试中的人工断言体冒充游戏正文。

构建、聚焦 JUnit、原 ownership/query/interaction 回归、严格非更新 patch apply 和逐字节生成源码对比的具体命令及结果见实施报告和 `tmp/player-region-tick-continue01/todo3/`。未重建/替换历史 `.115` installer，也未重新运行没有代码变化的网络基线；最终新 worker 产物必须复跑 schema 3/4 套件并加调度/生命周期/网络静止互斥及完整性能验收。
