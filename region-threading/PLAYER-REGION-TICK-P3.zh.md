# 玩家 Region Tick：P3 实际玩法扩展

2026-11-17 用户已明确授权开始 P3，解除之前停在 P2 的限制。P2 .126 的受限合同及验收结论作为历史基线保留；P3 已在 .134 按设计第7节逐类有界协议完成同产物最终验收；P4/P5未开始。当前源码基线为 5cb387bfa31d2dd441e500f483d833b6d73d38f9。

P3 的交付是逐类扩大完整玩家 worker 调用的实际可达玩法：有界邻域/冲突组、共享库存、拾取和 XP、伤害、计分板/地图。每项需要真实状态、守恒、事件和同步结果验证；未支持分支整次串行。P4 owner 包路由和 P5 性能/长期负载不包含在本增量中。未知 Mod 整次串行、默认 false、开始后不重放、真实物理返回/join 后清理继续保持。

## 首个增量：合并 XP 球的非末次原始拾取

首个候选分支仍要求原静止空库存玩家、原惰性加载邻域和已有完整空间/共享状态合同。只允许精确原版 ExperienceOrb、原 EntityType.EXPERIENCE_ORB、无 attachment/骑乘、正 XP、原 count>=2；所有候选 XP 与奖励总预算保持<=7。单个球和最后一次拾取仍整次串行，因为原玩家派发器的 deferRemoval/acceptsDeferredRemovals 尚未开放。未删减或替换原 playerTouch、repairPlayerItems、giveExperiencePoints、事件、计数递减、发包或原查询；count>=2 本身证明本次完整正文不会进入 discard 分支。

原 LivingEntity.take 会通过 ChunkMap.TrackedEntity.seenBy 广播；Connection.sendPacket 直接递增 sentPackets，发送到另一玩家的 Connection 可能与其完整 Connection.tick 并发。新增窄读取 forgiaHasExclusiveEntityWatcher，要求 entityMap 中的对象身份与原 seenBy 恰好一个精确当前 listener；空/多个/旧 listener/换实例均不允许。原广播正文保持不变，所有通知仍在原位置写入已 claim 的本玩家 Connection。native 读取还要求已取得完整玩家空间 lease 并拥有实体和玩家；非 publisher 普通线程拒绝。该检查在捕获、发布前与原 worker 消费前复查，静止窗口内没有 tracker 写入；原玩家派发器仍不开放任何 removal。

state 计划新增当前触碰范围内 XP 候选集合的精确复查，防止原持有 region 中的球在捕获后进入查询范围而绕过已捕获预算。原 XP 数据、对象权限、listener/connection 身份、已安装扩展检查全部保留。

## 当前验证状态

- 已完成源码调用链与两处边界审计，候选生产改动及聚焦测试已写入；尚未通过构建和真实 installed worker 验证。
- 需要验证：两种池 original playerTouch 的非末次 worker 拾取；Count 2→1、玩家 XP 增量与原 Collect/Experience 包；Count=1 时原完整串行移除、总 XP 守恒；其他观察者/未知 listener/附件/变更候选/超预算等前置拒绝。
- 完整 ServerPlayer/Connection 正文的实际调用线程需用无 Mod 的原 TCP 客户端和外部原方法观察证明；单元测试的模拟 installed base 环境不算生产 worker 证据。
- 必须保留 P2 的未知扩展拒绝在网络 freeze 之前的合同；不得特批 oracle Mod。

## 磁盘与证据约束

证据根 tmp/player-region-tick-p3-01/。P2 全量旧测试世界和重复依赖已按用户指令清理，原日志/帧保留在约378 MiB压缩包中，最终七个产物仍保留。当前 compact-result.json 记录用户运行的离线压缩退出-1，不能报告为成功；开始 P3 时 D 盘实际只剩约11 GiB。禁用 Gradle build cache，避免全量解压旧证据，控制少量隔离服及依赖副本；新失败保留紧凑原始证据。缓存清理后需恢复构建依赖，不再假定 --offline 可用。未操作现用服，不使用 SubAgent。


## 用户暂停（2026-11-17）

用户要求先暂停，以离线压缩WSL磁盘。已停止本次Gradle daemon，当前源码和全部构建/测试命令结果保留于tmp/player-region-tick-p3-01/，暂停快照见pause-20261117.json、paused-worktree.diff和paused-status.txt。P3仍未完成；用户明确恢复前不启动后续实现或测试。当前结果尚不作为installed worker验收结论。


## 用户恢复（2026-11-17）

用户已明确要求继续，解除前述暂停。恢复时D盘约11 GiB。暂停前首次构建在测试编译阶段失败：broadcast(Entity,CustomPacketPayload)与broadcast(Entity,Packet<?>)的Mockito any()重载歧义，尚未运行JUnit；原日志、退出1及当时测试源码保留。仅将该测试参数指定为Packet<?>后重新验证，不将失败构建中的旧XML计为通过。


## 初次真实客户端反例及夹具调整（2026-11-17）

493项受影响JUnit通过，候选.127构建成功。首轮on-fixed-01四场景的原TCP输入、球Count消耗和最终XP守恒均完成，但isolated的Count2→1首次拾取实际为Server thread，独立verifier明确失败，不能计入worker成功；结果及完整栈保留。原拒绝增量含首个移动tick的OPTIONAL_TICK_STATE。

不放宽生产准入，重试将正常移动后的合并球夹具从Count2改为Count3：允许刚完成位移、尚不符合静止合同的首个调用继续原串行拾取，随后在原状态稳定后，独立要求Count2→1存在真实worker副作用，最后Count1→0必须仍为Server thread，总XP21。负控制的外来观察者同样Count3，单球Count1、超预算Value8保持；正常游戏动作仍只发一个position_look，未跳过任何拾取。使用新隔离世界on-fixed-02；旧失败世界和证据保留。

硬链接依赖选项已用于新安装：91第三方jar共享inode，3个neoforge核心jar保持私有；只链接不可变第三方jar，安装器可改写的核心/argfile/元数据均复制。原seed依赖哈希已记录，后续最终核对。


## 安装工具的共享输入缺陷与隔离修复（2026-11-17）

首次P2 smoke在登录时出现NoClassDefFoundError: GameProtocols，崩溃报告保留于server-smoke-on-fixed-01/crash-reports/。当时并行安装直接硬链接种子libraries中的jar；原installer的BUNDLER_EXTRACT、split/rename等processor可能截断重写共享jar，即使最后字节相同，也会破坏另一个已运行JVM的类加载。此为本轮新增测试工具缺陷，前文安装前共享91jar的方案撤回。

现修复为：安装前所有输入均复制为私有inode；原installer成功退出之后，才对字节校验完全一致的第三方jar去重共享。net/minecraft生成jar、net/neoforged/neoforge核心和全部元数据保持私有；失败installer永不发布共享inode。三个安装器重写反例/默认私有/失败隔离单测通过。种子94jar的原哈希没有变化，Minecraft jar全量CRC检查通过。

旧XP四模式线程/守恒观察虽曾全部通过，但为排除此安装机制影响，最终验收不采用旧linked语料；在全新recheck01/重跑XP四模式、P2 smoke四模式、完整schema3/4。独立XP verifier强制installerInputsPrivate=true。旧functional矩阵在资源套件被中断并明确failed；中断期间一个包装命令缺少最终退出记录，按exit unknown保留，不补造退出码。原失败和所有世界不删除、不复用。


重跑中的持久池P2 smoke实际两入口并行及跨区攻击成功，但原严格verifier因启动时authlib后台获取Mojang公钥收到非JSON文本（Gson JsonSyntaxException）而拒绝整份语料。原日志/failed verdict保留，没有改动/放宽旧校验；在recheck01/smoke-retry01全新目录重试持久池及随后off两组。此启动外部服务异常不作为玩家worker故障，也不能把该失败verdict算通过。


## P3 首个增量验收：合并 XP 球的非末次拾取（2026-11-17）

**本增量通过；整体 P3 仍 partial。** `.127` 只扩大了已审计的合并原版 XP 球非末次拾取，未交付一般移动、
库存/物品拾取、共享容器、一般伤害、计分板/地图；P4/P5 未开始。所有未支持状态保持完整原串行。
默认 false、未知扩展在网络 freeze 之前整次拒绝、开始后不重放、真实物理返回/join 后清理均保持。

生产改动限于两个 plan 类和一个持久 Minecraft patch：RegionPlayerAdmissionPlan 允许精确 ExperienceOrb、
原 EntityType、无 attachment/骑乘、正值且满足已有总增量≤7预算、Count≥2；RegionPlayerStatePlan 复查完整触碰 XP 候选集合，
不允许后进入查询范围的对象绕过捕获预算；ChunkMap 新增精确 entityMap/seenBy 身份检查，要求观察连接恰好为拾取者当前 listener。
发布线程捕获与 native consume 均复查；native 读取还必须处于原完整空间 lease，拥有球和玩家。
原球拾取、事件、mending 的空装备分支、XP/等级、计数递减和 Collect 广播正文全部执行；没有简化副本、截断查询或包重路由。

Count≥2 保证本次完整原正文不会进入 discard。原玩家派发器的 removal 接缝继续关闭，Count=1/单球整次串行。
有其他观察者、空/旧观察连接、超预算、未知事件/扩展、非空库存等均拒绝。本增量没有把所有观察者连接称为安全，
也没有把通知延迟到服务器队列来改变同步顺序。

### 实际原始正文与守恒

四模式 `fixed/persistent × off/on`，每模式四场景：孤立合并球、外来观察者、单球和超预算，共16场景通过。
两个普通 TCP 客户端、无 oracle/测试 Mod。游戏动作由一个正常 position_look 包发起；命令只建夹具和读独立状态。
原 Player 查询触碰框完整包含另一 Region 的球：玩家 x=47.5、实际 owner(0,0)，球服务端原 NBT 与客户端出生包均为
x=48.5/y=100/z=24.5，对应 Region(1,0)。原 UUID 与 JDI 入口/返回身份匹配，原 Collect ID 与出生包 ID 匹配。

两池 on 的 Count3/value7 原调用链均为：移动后的第一调用 Count3→2、XP0→7 在 Server thread；
静止条件满足后，**Count2→1、XP7→14 在实际 NeoForge Region Worker**；最后 Count1→0、XP14→21 在 Server thread。
原栈包含 Connection.tick → listener → ServerPlayer.doTick → Player/LivingEntity.tick/aiStep → Player.touch → ExperienceOrb.playerTouch，
worker 栈还必须包含 RegionPlayerTickDispatch.runOriginal；没有侧任务冒充完整正文。
每次入口/返回均检查 `playerXp + remainingCount × value = 21`，最终球消失、三个实际 Collect 包、客户端 totalExperience=21。
实际 XP delay 导致的无副作用调用同样保留，不伪造或删除。关闭模式所有效果均为 Server thread。
外来观察者的21 XP、单球7 XP、超预算16 XP均守恒，且 on/off 都无 worker 拾取；外来观察者确实收到原广播的3个 Collect 包。

### 同产物回归与证据

- 493 项 JUnit、20 类，零失败/错误/跳过；包括原完整受影响回归和新增15项XP/晚失效测试。
- 四模式 P2 production-only smoke 全部通过，两个入口有跨区原正文重叠、同区重叠0；四组 A owner(0,0)攻击B owner(1,0)，
  服务端health20→19、客户端health19且恰好一个伤害包。
- 同一`.127`完整schema3 **144**场景、schema4 **180**场景、100功能负控制、40原阶段负控制全部通过。
  实体/PvP/方块/BE/共享容器/物品XP竞争/死亡重生的原状态、事件、原帧与资源守恒均保持。
  oracle仍UNKNOWN_MOD，两阶段worker=0；这些是玩法兼容性证明，不是上述玩法获得worker支持。
- XP独立校验10项负控制通过，覆盖丢失worker、末次/外来观察者错误进入worker、重复XP、缺原完整栈/返回、
  篡改原出生位置/UUID、原始Collect数量和客户端XP。manifest六项、committed NDJSON三项工具单测通过。
- 三个安装隔离反例测试通过；安装前所有输入私有，成功后只共享校验一致的第三方依赖。
  种子94jar哈希保持，Minecraft/核心jar的inode在16套接受的隔离服中均私有。
- 持久ChunkMap patch严格非更新apply成功、应用archive与生成源码逐字节一致；全部Python/Node工具语法通过。
  原`.115` verifier、默认false、原engine/network/extension门及设计文档保持不变。

| 模式 | ENTITY worker | ENTITY重叠 | CONNECTION worker | CONNECTION重叠 | 同区两阶段重叠 | 跨区攻击 |
| --- | --- | --- | --- | --- | --- | --- |
| on-fixed | 242 | 74 | 242 | 96 | 0/0 | 20→19 |
| on-persistent | 244 | 116 | 244 | 113 | 0/0 | 20→19 |
| off-fixed | 0 | 0 | 0 | 0 | 0/0 | 20→19 |
| off-persistent | 0 | 0 | 0 | 0 | 0/0 | 20→19 |

没有重新运行选定GameTest及整个P2故障/生命周期矩阵；其历史完整认证属于`.126`。
本增量只报告上述实际复验，不引用旧3项GameTest作为`.127`新通过。没有性能、广泛尾延迟或长期soak结论。
JDI仅为loopback外部只读断点，没有Mod、Java instrumentation agent、类重定义、权限例外或游戏状态写入；其暂停时间不作为性能数据。

最终产物：`tmp/player-region-tick-p3-01/artifacts/neoforge-21.1.250.127-beta-installer.jar`，SHA256 `b5cd2be82ad217b2d15c82a4bc329b633ac78b305c7091c673ea40d9219afdb3`。
显式审核manifest：`tools/player-region-tick/reviewed-artifacts/21.1.250.127-beta.json`，SHA256 `fb5ab729a900f19b9d81e8582e29e662aaf0534d6e2e725352dd605fec55ec56`；
主Agent源码/产物审核，不声称人工或独立审批，历史`.115` gate和所有旧manifest保留。
当前通过语料只采用 `tmp/player-region-tick-p3-01/recheck01/`；P2 smoke三模式重试映射由smoke-final-result.json显式给出。
最终24份独立verdict、源码/输入/命令hash与失败退出记录见 `tmp/player-region-tick-p3-01/receipt.json` 和 `final-audit.json`。

### 失败保留与磁盘

编译匹配器歧义、离线缺依赖、Mockito夹具错误及其失败XML/源码、最初Count2缺worker证据全部保留。
安装前直接硬链接的测试工具缺陷导致另服GameProtocols类加载崩溃；原失败矩阵/崩溃/中断wrapper保留，
该安装机制下的旧XP通过观察不计最终接受语料。修复后全新重跑。一个中断包装缺少真实退出码，按exit unknown保留。
隔离修复后一次持久池smoke因authlib启动后台从Mojang取得非JSON文本而被原严格校验拒绝；没有放宽校验或改旧日志，
全新三模式重试通过，最初失败状态原样保留。

本轮证据目录当前约 3.19 GiB（含所有失败、私有Minecraft产物及新世界），避免恢复旧66 GiB测试目录。
原P2压缩证据和最终产物继续保留，未操作现用服、未使用SubAgent、未commit/push/deploy或reset/clean。
下一步仍在P3：闭合单球/最后一次XP的移除和tracking发布、多个观察者连接的原顺序与互斥，再扩展一般物品/库存/共享容器/伤害等。


## 第二增量进行中：末次 XP 移除（2026-11-17）

用户明确要求自主完成整个 P3，P4/P5 不开始。新增隔离证据目录 `tmp/player-region-tick-p3-02/`，首个增量的 .127 产物、回执和通过语料保持历史不变。

候选 .128 开放 count>=1 的精确原版 XP 拾取，仅允许当前已消费 state 计划捕获的球、原 callback 身份和 DISCARDED 原因。
XP、Collect、原实体 removed 标记仍在原 playerTouch 中同步完成；section/tracking/ledger 的原 onRemove 回调使用既有 deferred-removal kernel，
在该前缀真实物理 join 后且原 publisher batch fence 尚有效时提交。已发生的拾取在 stop-new-admission 后仍须提交，不能丢掉已经转移 XP 的球的存储移除。
普通非玩家 batch 的关闭/丢弃合同保持；玩家提交路径另外要求真实 publisher 与原 active batch，Level 退役/关闭若提前发生明确失败而不静默丢弃。

事件 union 增加 EntityLeaveLevelEvent；唯一内置 NeoForgeEventHandler 的精确注册在原 publisher 回调位置移除 region 映射，不授权 worker 执行未知 listener。
首轮 build-xp-01 69项失败：加入事件检查后内置 leave handler 也遭拒绝；第二轮 build-xp-02 仍69项失败，进一步确认 Bus8 对不可取消事件会在 dispatch cache 解包 SubscribeEventListener。
已审计原 bus 字节码：该 final wrapper 的 getWithoutCheck 只返回原 handler，不执行回调；现在同时匹配精确内置注册及其解包身份，未知消费者仍拒绝。
两轮原 XML/日志/真实退出1保留，不算验收通过。正在重跑实际 native 原拾取、正文后异常、停服等待和幂等存储提交测试，并准备正常 TCP + 外部只读原方法移除观察。
整体 P3 仍进行中；此处候选源码尚未构成安装服验收结论。其余物品/库存/共享容器/伤害/计分板地图路径尚未闭合。


第二增量更新：修正测试父围栏/网络冻结夹具后，177项聚焦JUnit及498项受影响回归均通过（build-xp-06、regression-xp-01）。
候选.128 installer `bce32854a9d7eb929e2b7a243e4e26e74fd14d5cf608b7d15764812ab1d7f4f9`，尚未最终验收。
fixed真实原方法观察已出现Count1→0/XP14→21的完整worker调用，随后原publisher onRemove返回并清空levelCallback。
首轮单球测试夹具失败：原Player.aiStep对XP候选随机选择，两个同时可触碰的球不能保证先消费primer；独立观察拒绝该场景。
另发现新观察工具仅按Server线程匹配onRemove退出，会把后来另一实体的退出错误关联目标，已增加原callback实体身份匹配。
不改生产门；改为正常客户端先移动到46.5只拾取47.5的1XP primer，外部只读原返回断点暂停时发送下一正常移动包到47.5，
恢复后原takeXpDelay=2为单球6XP留出静止准入窗口。保留首轮失败/混合工具版本语料，最终只采用新隔离目录重跑的完整证据。


## 第二增量两池通过及第三增量进行中（2026-11-17）

`.128` 末次/单球XP的两池on模式四场景独立校验通过，原状态/资源和原Collect/移除帧一致。isolated每组3次效果，其中2次worker，末次worker；single以原1XP primer之后的6XP单球为目标，完整worker一次。外来观察者和超预算所有效果仍Server thread。原storage onRemove在worker返回之后、原前缀publisher上各一次，levelCallback=NULL。17项证据负控制拒绝，包括缺worker、缺/重复/早执行/错实体/错线程的存储回调、残留callback、改原Collect/XP/移除帧。498项完整受影响JUnit通过；未在此中间产物新跑off、GameTest、schema3/4或完整P2安装矩阵。回执 `tmp/player-region-tick-p3-02/receipt.json`，整体P3仍进行中。

第三增量候选`.129`：精确原版Item材料diamond/iron_ingot/gold_ingot、默认components，仅非选中库存格，装备/选中格/旧装备状态仍空。原inventory.tick和菜单同步正文保持；物品候选只能完全合并到已有同材料非装备栈，按全部候选总容量保守检查，不模拟/替换Inventory.add，也不删改查询顺序。未知thrower/cachedThrower、外来target、外来观察连接、额外callback/INVENTORY_CHANGED监听器均完整串行。原ITEM_PICKED_UP stat只在publisher预热原handle；库存背板、栈、components及菜单远端快照加入原身份资源claim，未开始再次核对栈身份/数量/候选集合。原ItemEntity.playerTouch及Pre/Post、原拷贝、Inventory.add、Collect、原移除、统计/菜单反馈保持。

189项聚焦JUnit通过，包括2个native原物品合并和10个晚失效反例；四个持久patch严格apply且与生成源码逐字节一致。两次新测试失败保留：错误使用不存在的engine.entityRemoved辅助方法（编译失败）；mock ServerPlayer只绑定getStats而没绑定原awardStat直接读取的stats字段（两项NPE）。修正测试夹具后build-inventory-03通过。尚未完成真实TCP验收，不称库存/物品分支已交付。


## 第三增量两池通过：原普通材料拾取（2026-11-17）

`.129` 两池 on 模式五场景通过独立校验，510项受影响JUnit通过；16项证据负控制全部拒绝。
positive普通客户端移动到x47.5 owner(0,0)，原x48.5 owner(1,0)钻石5个合并到原非装备slot9的1个，完整worker原playerTouch将1→6。
原Pre DEFAULT先于add，原Post看到originalCopy=5/remaining=0/玩家库存6，原ITEM_PICKED_UP统计恰加5；原Collect=5，原storage callback在真实返回之后publisher一次，原移除包在Collect之后。最终XP0。
foreign-watcher/capacity/component/equipment四场景保持完整Server thread，功能照常成功，各5个资源不丢失不复制；外来观察者收到原Collect。
每组五次实际拾取累计保存统计diamond25，正常客户端request_stats收到5/10/15/20/25，独立原帧重建客户端库存与服务端逐场一致。
PickupDelay=40的无副作用原调用及其Pre同样记录，未删/跳过任何查询或事件。JDI只读外部观察，精确目标原调用期间启用方法退出探针，不写游戏态、不安装Mod。
首轮过宽EventHooks退出探针造成RCON超时，原失败/世界保留；两份接受语料在新目录使用按目标原调用线程、原调用存活期间启用的观察器。
回执`tmp/player-region-tick-p3-03/receipt.json`；未把off、schema3/4、GameTest或完整P2安装矩阵的旧通过计入中间`.129`。

第四增量候选`.130`在`tmp/player-region-tick-p3-04/`：精确单ChestBlockEntity/ChestMenu，完整原stillValid所需块进入footprint，
菜单原listener/synchronizer/backing storage检查，共享箱子及其栈components按原身份加入冲突边、同lease明确双方声明才允许共享claim。
未知本地别名仍拒绝。原开箱/点击包仍服务器线程互斥，owner包路由未开始；需要实际证明带共享菜单的完整玩家tick、原返回和同步反馈，不能将串行点击称worker点击。
候选仍在构建/审计，尚未验收；整体P3保持进行中。


第四增量聚焦回归：214项JUnit通过，新增共享资源按身份合并、两池两玩家同一箱子claim并原序native菜单调用、原stillValid=true/slot同步、9种晚失效反例。未知本地资源别名原拒绝测试保持通过。五个持久Minecraft patch严格apply/generated一致。build-container-02新夹具13项NPE已保留，原因是when(getBlockState(any()))重设桩时先执行旧answer的null参数，改为doAnswer后build-container-03全部通过。安装服客户端共享箱子原开箱/点击/同步/事件正在验证，不计已通过。


第四增量继续：524项完整受影响JUnit通过。fixed-02外部原事件观察已正确得到2Open/2Close、单箱子同一identity、两菜单不同identity和完整两阶段原worker调用；独立校验因原版点击者预测抑制回包的正常合同拒绝，不将旧失败改写。
normal reopen补充真实权威window_items确认点击者获得11钻石；非点击者仍必须收到实际空槽更新。fixed-03功能校验通过，但当时已开始编辑下一计分板候选，源码快照混入较晚candidate，明确不采用为产物源码闭合验收。
新增显式production-source-root，冻结`.130`原始production源从source130读取；未改源只接受HEAD逐字节相同，新candidate文件不进入旧产品快照。fixed-04/persistent-01新隔离目录重跑以闭合源/产物对应。

第五增量候选`.131`：不显示、不tracked、displayAutoUpdate=false的精确ServerScoreboard原必经objective；仅原createData安装的唯一ScoreboardSaveData::setDirty精确callback，未知dirty listener拒绝。保留原forAllObjectives/getOrCreatePlayerScore/ScoreAccess/dirty同步路径，显式共享整个board/backing集合/save-data并合并冲突组，空objective不加入全局资源边。首轮214项原回归通过，但四个新patch hunk原始行号有误，strict apply拒绝；日志/patch/XML保留。按原interface-injected-sources.jar准确行号修正，新增native原统计分数同步/dirty和晚失效测试正在重跑。尚不构成计分板验收。


## 第四增量两池通过：原共享单箱子菜单（2026-11-17）

`.130`接受语料fixed-04/persistent-01独立校验通过，分别97/103次原菜单调用，其中93/99次真实worker；两名原玩家跨owner(0,0)/(1,0)，箱子恰一个原实例identity，两个菜单原实例各自不同。双方ENTITY原broadcastChanges/stillValid及CONNECTION原stillValid完整栈、同步true返回和原箱子11→0均可见，同箱子方法无跨worker重叠。正常客户端开箱事件Open=2、关箱Close=2均在原服务器包处理栈；没有把点击路由到worker。
正常B shift-click将11钻石取走；A必须收到实际slot0空栈通知。B原预测正确，vanilla suppressRemoteUpdates允许省去冗余回包；正常关箱再开箱的权威window_items独立确认B背包11、箱子0，A背包0，双方XP0。并非凭发送成功或预测确认资源。11项证据篡改负控制拒绝，524项完整受影响JUnit、5个持久patch/generated一致。回执`tmp/player-region-tick-p3-04/receipt.json`。
中间`.130`未新跑off/schema3/4/GameTest/完整P2安装矩阵；最终P3尚待统一产物全部复验。未知菜单/BE/栈/listener/资源别名仍完整串行。

`.131`计分板候选221项聚焦JUnit通过，原4个patch hunk按正确源行号后严格apply成功、7patch/generated一致。
fixed真实原观察已经出现同一board/saveData上p3_time原统计分数+1和p3_xp=7→14→21，后两次是完整Connection worker，原dirty callback每次在score返回之前同步执行。
首个verifier错误要求B在objective创建后从未变化的0XP也必须初始化为分数0；原vanilla只在lastRecordedExperience变化时更新，因此B原response为none is set，保存中同样不存在该项。
保留失败verdict，只校正为严格检查该原无记录结果/保存无额外XP；v2独立校验通过，生产不变。persistent原观察与完整回归继续中，整体P3仍进行中。


## 第五/六增量两池通过及非致命伤害进行中（2026-11-18）

`.131`不显示计分板两池on独立通过、531项受影响JUnit通过、9项proof负控制拒绝。原分数消费者和saveData dirty同步，未跳过原迭代或把同步效果排队。回执`tmp/player-region-tick-p3-05/receipt.json`。

`.132`真实未选中单地图两池on独立通过：地图由普通use_item正常创建，两个正常inventory click从hotbar0移到backpack slot9，实际原map_id=0且保存文件map_0.dat存在；没有无效ID/null替代。原完整Connection/doTick→Inventory.tick→MapItem.inventoryTick→tickCarriedBy及原getUpdatePacket都在worker，单个原MapItemSavedData、单本玩家carrier，普通客户端转头90度让原方向0→4、dirty及原地图包产生。实际客户端收到原图标方向0/4，保存像素和逐个原始map patch重建的客户端16384像素完全一致，未选中分支没有terrain update，地图物品1个与XP0守恒。
230项聚焦、540项受影响JUnit通过，9个持久patch/generated严格一致；10项proof负控制拒绝。初版专用帧decoder漏掉use_item的0x39，原failed verifier保留，仅增加精确原包ID后v2通过；未修改生产或伪造客户端操作。回执`tmp/player-region-tick-p3-06/receipt.json`。当前中间产物仍未新跑off、schema3/4、GameTests或完整P2安装矩阵，最终P3必须统一产物复验。

第七增量候选`.133`在`tmp/player-region-tick-p3-07/`：一名真实玩家的满血20、无装备/效果/攀爬/吸收/活跃战斗、原food0时，一次原starve source的非致命1伤害预算。只接受服务器PlayerList恰为该玩家、原tracker无其他观察连接，关闭伤害声音和通知的外来接收对象；不是一般PvP worker。完整原GameEvent通知半径16所枚举的所有chunk/section纳入同一既有空间lease，原registry在publisher只物化缓存并要求精确空Euclidean registry及无pending/processing，原post/visit仍执行，不删/跳过游戏事件。Damage/Shield/Invulnerability/VanillaGameEvent/Sound等原可达事件并集加入未知listener拒绝。真实健康/原返回/事件/客户端damage/health/统计/声音/游戏事件尚待验证，不称伤害分支已交付。


## 2026-11-18 恢复及最终审核发现

用户清理磁盘后要求继续；D盘约104GiB可用。中断前的install-damage-on-fixed-03无result/exit且没有存活进程，按interrupted/exit unknown保留，不复用。首次伤害runner未清除授予全部进度带来的XP，.133正常STATE:XP_SHARED_SOUND拒绝；另有原玩家NBT修改被vanilla拒绝、只读观察器Float包装读取错误、夹具damageContainers实际Stack误设ArrayDeque等失败全部保留。使用原hunger效果耗尽食物、清除效果，正常客户端移动和原FoodData.tick触发饥饿，不写玩家NBT或命令damage。fixed-06原完整worker hurt→20→19/return true、六类hook、游戏事件、声音广播原序、客户端一个damage/health19、保存damage_taken10与空库存XP0全部独立通过，11项proof负控制拒绝。246项聚焦、556项受影响JUnit通过；3个选定GameTest在补齐缓存缺失DevLaunch依赖后通过。首次offline GameTest缺依赖失败保留。此为中间.133证据，最终统一矩阵仍进行。

最终源码复核发现MapId原地内容变更未使旧地图计划失效：components对象不变时原身份检查不足。新增mapId晚失效反例map-id-counterexample-01确实失败（8项中1项），原XML/源码保留。现在current逐格检查实际MAP_ID与捕获id相等、同一张/非选中/非装备/精确支持栈，未知id在正文前拒绝，不等到原getMapData在已开始正文抛错。生产候选推进.134，全新最终证据目录tmp/player-region-tick-p3-final02；.133的functional/smoke子矩阵允许完成，两个仅负责等待/启动后续矩阵的wrapper被终止并记录，不停止正在执行的原服务器、不丢旧语料。旧.133结果只作为中间兼容证据，最终全部类别和原schema3/4/P2矩阵必须在.134重跑。


## .134 同产物最终验收进行中（2026-11-18）

最终候选安装器为 `tmp/player-region-tick-p3-final02/artifacts/neoforge-21.1.250.134-beta-installer.jar`，SHA256 `87680bb6ee3ff05b059a2bf4ac5aae6442387565801877a0cf577b2af513a670`；显式审核 manifest 为 `tools/player-region-tick/reviewed-artifacts/21.1.250.134-beta.json`，SHA256 `7b7a091e349fbe7b4509bb81a71047cb627f3eba6a34d6f67cb0ec6e562fb721`。23份生产源码/持久patch已冻结，247项聚焦/557项完整受影响JUnit及3项选定GameTest通过，9个持久patch严格apply/应用archive/generated逐字节一致。聚焦与完整回归计数有重叠，不相加。

统一矩阵正在复验XP、普通材料、共享单箱子、计分板、背包真实地图、非致命饥饿及外来玩家负例，每类fixed/persistent × off/on；另重跑全部schema3/4和13套P2网络/正文故障/退役/重生/保存等矩阵。`.133`和各中间产物结果不混入`.134`最终接受清单。

首次`.134`经验球观察因过宽JDI MethodExitRequest持续拦截准入读取，正常客户端在动作处理前超时；`gameplay/`及其真实非零退出保留。外部观察器现仅在精确目标/primer的原playerTouch期间和精确目标storage callback期间，对当前线程启用退出事件，原返回后立即删除；没有改变生产代码、客户端超时或校验条件。新目录`gameplay-retry01/`的fixed-on四场景及17项proof篡改负控制已通过，余项继续。当前不是整体验收完成结论。


统一矩阵进展：`.134`完整schema3 144/schema4 180及100功能+40阶段负控制全部通过，8份oracle语料仍UNKNOWN_MOD/worker=0。无Mod的两池on六类正向及外来玩家伤害负例已全部通过；off两模式继续。fixed原完整双入口各242次worker，各119个真实重叠tick，同区各244次worker/重叠0；A owner(0,0)攻击B owner(1,0)使服务端/客户端health20→19且一个damage包。P3最终收口仍等待其余off及完整P2故障/网络/生命周期复验。


P3的28份四模式新语料、56玩法子场景与74项proof负控制已全部通过；当前仍等待P2完整同产物矩阵和最终审计。为使用玩法测试结束后空出的资源，只对顺序排程Python wrapper PID8271发送SIGTERM，实际-15记入orchestration-retired02.json，未向服务器/客户端/观察器或进程组发信号；正在执行的fault子矩阵原样结束，其余8矩阵由两条新隔离链并行启动。此wrapper不计通过，各矩阵仍独立记录真实argv/exit/verdict，全部失败保留。


当前结论（2026-11-18）：**P3已按设计第7节的逐类有界协议完成统一验收，最终产物21.1.250.134-beta；停在P4前，P4/P5未开始。整体设计仍partial。** XP末次/单球、普通材料库存拾取、共享单箱子原菜单tick、非显示计分板、真实背包地图及一次非致命自饥饿伤害均有完整原worker正文、事件/同步结果、客户端反馈和守恒证据。一般PvP/实体/方块/开箱点击等正常包继续原服务器fence，未知/未支持路径完整串行，不能将P3完成理解为任意玩法或Mod可并行。

同产物557项受影响JUnit、3项选定GameTest、28份P3四模式语料（56玩法子场景）、schema3 144/schema4 180、13套P2兼容/故障/网络/退役矩阵及321项proof篡改负控制通过；96份独立verdict和88套隔离安装由最终审计闭合。四模式A owner(0,0)正常攻击B owner(1,0)均health20→19、客户端health19和一个damage包；两池两入口跨区真实重叠，同区0。oracle仍UNKNOWN_MOD/worker=0。

产物`tmp/player-region-tick-p3-final02/artifacts/neoforge-21.1.250.134-beta-installer.jar`，SHA256 `87680bb6ee3ff05b059a2bf4ac5aae6442387565801877a0cf577b2af513a670`。最终机器回执`tmp/player-region-tick-p3-final02/acceptance-01/receipt.json`、`final-audit.json`，接受路径映射`tmp/player-region-tick-p3-final02/accepted-evidence.json`。详细边界和全部失败见[当前P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾。默认false、未知扩展freeze前拒绝、开始后不重放、真实join后清理保持。保留全部源码、世界、失败及旧manifest/hash gate；未commit/push/deploy、reset/clean或操作现用服。
## P3 .134 最终收口（2026-11-18）

本节是当前最终结论，前文`.127`—`.133`及`.134`进行中记录均保留历史。P3交付的是设计第7节要求的逐类有界协议和未支持时完整串行路径；P4 owner包路由与P5规模/性能不在本次交付中。

| 类别 | 两池on实际原worker证据 | 同产物off/拒绝与守恒 |
| --- | --- | --- |
| 邻域/冲突组/共享库存 | 原空间完整集合/lease，加显式共享资源身份冲突边，同lease双方声明才可别名；原序完整调用 | 原本地未知别名仍整组拒绝；同区/共享原对象无跨worker重叠 |
| XP | 原Count3价值7三次拾取、末两次worker含末次；primer1XP后的单球6XP worker | 四模式共16子场景，外来观察连接/超预算串行，21/7/16XP及原Collect/移除反馈守恒 |
| 普通材料 | 非装备原diamond1→6；原Pre/Post、Inventory.add、统计/menu、Collect及publisher存储移除 | 四模式共20子场景，外来观察者/容量/components/装备串行；逐场及保存统计25、XP0 |
| 共享单箱子 | 两个跨owner玩家、一个原ChestBlockEntity、两个原ChestMenu，完整ENTITY/CONNECTION菜单tick与同步stillValid=true | 正常客户端开/关各2事件，普通shift-click取11钻石，另端空槽通知、点击者reopen权威库存11；XP0。开/点击仍原服务器包fence |
| 计分板 | 实际未tracked的原objective，同一board/saveData，原consumer/score/dirty同步 | 时间原分数+1；XP7→14→21、保存一致。B从未变动的0XP正确保持无该分数 |
| 背包地图 | 普通use_item创建真实map0、两普通click移到slot9，原inventoryTick/carrier/getUpdatePacket完整worker，转头使原图标0→4 | 四模式实际地图包/dirty与单carrier；16384像素客户端重建=原保存，地图1个/XP0，非选中原分支无terrain update |
| 非致命伤害 | 唯一玩家的原FoodData.tick→hurt(starve,1)，health20→19/返回true；6原hook、gameEvent和原sound广播 | 正/外来玩家负例四模式共8份，外来玩家完整串行；一个damage包、health19、保存damage_taken10，空库存/XP0 |

所有玩法操作均来自普通真实TCP客户端包；命令只建fixture、冻结观察边界或独立读原状态。外部loopback JDI正向观察只读原字段/方法入口返回，不安装游戏Mod、Java agent、类重定义或worker准入例外。原开始后故障套件明确使用独立JDI异常注入，不混作只读正向观察。JDI暂停时间不作为性能证据。

| 模式 | ENTITY worker | ENTITY重叠tick | CONNECTION worker | CONNECTION重叠tick | 同区两入口重叠 | 跨区health / damage包 |
| --- | --- | --- | --- | --- | --- | --- |
| on-fixed | 242 | 119 | 242 | 119 | 0/0 | 20→19 / 1 |
| on-persistent | 242 | 120 | 242 | 118 | 0/0 | 20→19 / 1 |
| off-fixed | 0 | 0 | 0 | 0 | 0/0 | 20→19 / 1 |
| off-persistent | 0 | 0 | 0 | 0 | 0/0 | 20→19 / 1 |

同产物回归门：557项受影响JUnit、3项选定GameTest；9个持久patch严格apply并与应用archive/generated逐字节一致；23项生产源/patch始终匹配显式manifest。聚焦247与557重叠，不相加。另有6项manifest、3项committed-NDJSON、3项安装隔离单测通过，Python/Node语法及JDI编译通过。完整schema3/4正负坐标、角落、双向PvP/实体、survival方块、BE/共享容器、竞争拾取、死亡重生状态/事件/原客户端反馈/物品XP守恒通过，oracle仍UNKNOWN_MOD、两入口worker0，无特批。

13套P2矩阵：functional、smoke、network、same-group、fault、socket、transitions、contract、network-contract、timers、lifecycle、respawn、phase-order全部同`.134`新安装通过，真实命令退出均0。网络正常/溢出、完整原正文故障一次/无重放/真实join、同组后缀、socket实际关闭后scope有效、显式NETWORK/MAIN线程合同、聊天/keepalive/payload、末地credits/重配置/取消、原计时器、维度往返/重连/保存重启、落地新实例双入口、原PlayerTick/BE/Level顺序均按各原verifier检查。最终零owners/pending/running/claims/batches/network retains以独立退出读值核对；活动Level退役及已发生拾取后故障/stop-new-admission/提交幂等仍由两池原native kernel测试闭合，不声称新增了安装服拾取后故障注入或任意动态Mod认证。

最终96份独立verdict、321项proof篡改负控制、88套隔离安装均由显式接受映射逐项审核。种子94jar哈希未改，所有接受安装的Minecraft/核心jar inode独立；第三方依赖仅在成功安装后校验一致再共享。所有真实命令/闭合原输入/源/安装隔离索引及哈希见acceptance-01。

失败与未覆盖：地图原地换ID的`.133`反例确实失败，`.134`开始前拒绝；最终首次经验球过宽JDI导致正常客户端超时的语料和非零退出保留，缩小为精确原调用/线程期间观察后在全新gameplay-retry01四模式通过，未调大超时或放宽断言；P3玩法结束后，为并行剩余两条独立验收链，有意只终止原顺序排程wrapper，实际退出-15记录在orchestration-retired02.json，正在执行的fault子场景未被中止；该wrapper不计通过，接受依据仍是每个矩阵的独立真实命令退出。早期编译/夹具/离线缺依赖/观察器/预测回包校验/中断wrapper等失败均保留。最终审计工具曾在编辑时少一个右括号，AST检查发现后修正，未用于执行验收；原错误说明及按单行修正精确复原的失败工具源保留于audit-tool-syntax-failure/，不属于生产或游戏功能故障。未知退出不计通过，旧`.133`/中间产物不计`.134`最终接受矩阵。原P2压缩证据不展开、不删除。

支持边界：P2的静止输入/完整惰性加载地形、空装备/效果、低等级有界XP与受限criterion/奖励前提继续有效。真实正向fixture预授全部进度并归零XP以关闭未审计predicate/reward；不删除原正文，也不保证普通新玩家任意状态都能进入worker。普通材料安装正向实际使用diamond，iron/gold为相同精确Item分支的源审计支持，不冒称另有安装正向语料。一般PvP/实体攻击/方块破坏放置、容器开关点击、骑乘/移动/传送/维度/重生等仍在原静止服务器fence执行；装备/效果/未知物品/双箱子/任意共享机器、显示计分板、选中地图地形更新/多carrier、一般伤害等未获worker协议。P3安装正向worker语料主要在+X边界，负坐标/角落的完整兼容场景由schema3/4复验，不能据此扩大新worker路径的安装覆盖声明。无1/8/32客户端P5规模、混合Mod并行、长期soak、MSPT收益/广泛尾延迟或图形GUI暂停结论。默认false保持。

**P3按上述设计范围完成；按用户任务边界停在P4之前。** 未commit/push/deploy、reset/clean、清理失败或操作现用服。


最终汇总审计补足：首次审计把原故障verifier的`passed_expected_failure`误当未通过；第二次额外汇总断言未区分ENTITY原guardEntityTick包装的`net.minecraft.ReportedException`与CONNECTION原`java.lang.RuntimeException`。两次审计真实exit1及当时工具源保留在final-audit-command、final-audit-retry01-command、audit-verdict-contract-failure、audit-verdict-contract-failure02；接受目录在这两次均未创建，未修改生产/游戏输入/原verifier。按原合同精确区分后，final-audit-retry02-command实际exit0，acceptance-01最终审计通过：96份独立verdict、308条实际验收命令、321项proof负控制、88套隔离服、9998份闭合输入；46条非零/中断命令记录保留，审计自身当时尚在执行的包装另作明确元数据，实际最终退出0。
