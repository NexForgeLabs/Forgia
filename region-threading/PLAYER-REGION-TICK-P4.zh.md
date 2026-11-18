# 玩家 Region Tick：P4 owner 网络路由

2026-11-18用户授权推送P3并开始P4。P3已提交38b2e985a并推送origin/main，完整验收摘要在region-threading/evidence/player-region-tick-p3-134；本地原始证据和失败不删。P4现in progress，首条owner包独立证明已通过，但`.136`转换off-persistent存在真实普通物品合并崩溃，未整体验收；P5未开始。

P4依据设计第3.2/7节：已审核包与异步完成跟随当前玩家owner，未知/全局包为顺序fence；移动→传送→确认、库存、重配置原顺序和终态不变。未知扩展保持freeze前整次串行；MainThreadPayloadHandler/ServerPayloadContext.enqueueWork原服务器线程合同不变。默认false，不另加自动开启配置。

## 首个候选 .135：原快捷栏空槽切换包

`PacketUtils`的原executeIfPossible队列位置、shouldHandleMessage检查、异常处理/onPacketError和OOM报告均保留。在原服务器try/catch内尝试完整原packet.handle；已开始原handler在worker返回后把原异常带回该服务器catch，不重放。只有精确ServerboundSetCarriedItemPacket、slot0..8、原当前/目标均空且无use item，配合完整P3 production/state/spatial合同方可进入worker。未知包/有物品目标/非法slot/未知Mod/关闭模式/陈旧身份/非静止窗口完整串行。

复用原engine、RegionWorkAdmission、网络gate、RegionTickBatch、RegionPlayerSpatialLease/StateScope和两池，未创建第二个包队列或线程池。每个包只在自己的原server queue位置派发并真实join，后续原包/异步服务器任务不能越过该位置。原Netty接收顺序仍由原队列保证，诊断sequence是已派发包的执行序号，不冒充全协议接收序号。未知/全局包与现有聊天/过滤/MAIN payload continuation仍原服务器任务fence；目前没有把这些异步回流调度到owner。

原handler的selected写入、stopUsingItem空状态分支、lastActionTime均保留。只有精确packet/listener、当前原player、已consume原state scope及原server event loop相匹配时，worker的同一次PacketUtils.ensureRunningOnSameThread检查通过；不是把isSameThread全局改为true，也不允许递归处理另一包或取得其他event loop权限。

完整准入在原包实际队列执行时重新捕获PlayerList当前实例、listener/connection、world ledger身份和原空间owner，不把接收时旧坐标绑定成长期权限。该包不迁移位置/维度。尚无移动/传送确认/库存点击/重配置或一般async owner执行权限；这些后续协议和P4整体验收未完成。

候选源码首次编译和原RegionPlayerSpatialLeaseTest回归通过（build-first）；新增两池真实原PacketUtils排队/原handler、正文后异常不重放与原publisher错误处理、未开始目标/实例晚失效、未知/占槽/非法/off拒绝测试正在构建。未验收候选产物，不以组件测试冒充真实TCP。新证据根tmp/player-region-tick-p4-01，所有失败保留，不操作现用服。


## 首次真实TCP反例与 .136 补足

`.135`原受影响570项JUnit通过，但真实TCP on-fixed独立verifier拒绝最后一次空槽包仍Server thread：P2网络门把接收回调延后到原server queue后，原Connection.genericsFtw直接调用handler，PacketUtils不再进行二次排队，首版仅覆盖其排队lambda而漏接这条分支。原失败日志、JDI/包/世界和verdict保留于p4-01/gameplay，不修改verifier的worker要求或普通客户端操作。

候选推进`.136`：在原Connection.genericsFtw保留原shouldHandle与异常边界，server queue中的直接派发尝试同一owner协议，worker返回后原回调继续，不二次排队、不越过后续包。Netty原入口不处理owner状态，只有原服务器执行位置可捕获和派发；未知扩展检查在库存/use等新读取和freeze之前。原PacketUtils排队分支继续存在，worker仅精确同包/listener/server loop可穿过ensure。增加两池直接回调的完整原handler、正文后异常、不二次enqueue及线程池拒绝后清理再串行一次测试。

早期新增测试漏import编译失败、生成PacketUtils未同步导致4项测试失败、Connection持久patch的offset与hunk行数strict apply拒绝均保留；修正后第一次`.136`244项聚焦测试通过，新增拒绝/错误清理测试和最终完整回归/installer正在构建。整体P4仍进行中；尚无全P4验收。


`.136`完整受影响576项JUnit通过，两个持久Minecraft patch与strict apply archive/generated逐字节一致；Python/Node/JDI静态检查通过。installer SHA256 `4440e1ebcf522231a0655b3508ef2c69fb39b30a877ab88471c6ecd9b899757d`；显式产物manifest `tools/player-region-tick/reviewed-artifacts/21.1.250.136-beta.json` SHA256 `ac35832382f2b3d9703660230424793e12d160476aac56e645fe40de6fef2c8f`。不修改历史`.115` gate或旧manifest，不给oracle权限。

首轮`.136`fixed原包/六个统计fence和9项proof负控制通过；persistent相同功能结果却因authlib启动访问api.minecraftservices.com的DNS错误被原严格日志校验拒绝，旧失败保留。新persistent重试又捕获首包因Netty reader尚未返回而合法串行；这不是漏接或丢包，生产freeze门保持。新外部只读观察器在原server tryRunPlayerPacket入口读取gate.readers，等待已接收回调物理返回，再恢复原准入；无字段写入/方法调用/权限例外，所有worker/包序列断言保持不变。四模式从新目录gameplay-causal重跑，不复用混合观察器源。另一旧off-persistent启动RCON完整帧提前关闭也保留真实失败，不计通过。当前两池on与off-fixed新的独立verdict通过，off-persistent及兼容矩阵继续。


## 本次P4起步结果（2026-11-18，整体partial）

P3提交`38b2e985a250b74b8863a40c4552b735f116b710`已推送并核对origin/main。P4新源码保持本地未提交；用户本次“推送然后开始P4”的推送动作对应已完成的P3，未将后续候选误标成已验收P4。

`.136`：576项受影响JUnit/20类零失败错误跳过；新增原包权限仅允许精确空槽快捷栏切换，复用原server queue位置/唯一engine池/原spatial和state lease，异常不重放、原错误边界与真实join保持。2个持久patch严格apply/generated一致，原默认false/未知扩展freeze前串行/MAIN payload合同和旧hash gates保留。

采用`tmp/player-region-tick-p4-02/gameplay-causal/`的四模式最终独立语料：每组普通TCP按槽位1→2→非法9→有物品3→0→1，交错6个REQUEST_STATS原包；两池on分别3次完整原handler worker，非法/占槽及瞬时旧装备状态完整串行；off全部Server thread。统计fence6次均原服务器线程按序看到实际selected状态，实际客户端6个statistics响应，原vanilla不会冗余echo成功切槽；最终原服务器和保存SelectedItemSlot1、diamond1在slot3、XP0。只读JDI显示原handler入口/返回及完整packet.handle栈，不是替代算术。9项proof篡改负控制拒绝。

同`.136`已通过NETWORK/MAIN四模式：每组500 NETWORK/500 MAIN、错序错线程和意外deferred均0；无Mod双入口四模式smoke通过，两池完整world/Connection仍跨区重叠、同区0，四组A owner(0,0)正常攻击B owner(1,0)均health20→19/一个damage包。转换矩阵on-fixed/on-persistent/off-fixed独立通过。

**转换off-persistent真实失败：** `transitions/server-off-persistent/crash-reports/crash-2026-11-18_08.55.09-server.txt`记录末地arrival平台生成后的圆石item（id18，x≈99/z≈.52）在普通实体self-driven worker执行`ItemEntity.mergeWithNeighbours→tryToMerge→merge→Entity.discard`，存储guard拒绝并按原异常停止服务器；随后stopServer卸载chunk期间watchdog再次报告，`crash-2026-11-18_08.57.09-server.txt`保留。原RCON在等待末地状态时超时、run实际exit1；原日志/世界/所有包/命令/崩溃全保留。该组requested=false，原栈不经过新owner包路由；尚未在旧`.134`独立复现，**不能据此断言既有缺陷或排除候选影响**，也不能重试成功便抹去本例。暂不扩大owner支持、不削弱原存储guard/不截断物品查询。

当前机器回执`tmp/player-region-tick-p4-02/acceptance-first-increment/receipt.json`状态为`P4_first_owner_packet_proof_passed_integration_partial_with_failed_transition`，仅15份通过verdict/15隔离服归入已通过清单，失败第16套单列integrationFailure；1542份闭合输入及源码/命令hash保留，`increment-audit-command`实际exit0只是如实分类证据的审计通过，不是P4或整套转换通过。候选`.136`不替代P3`.134`的完整验收结论。

仍未覆盖：移动、跨region位置提交和owner重查、传送confirm、库存点击/共享容器包、重配置包、异步完成owner执行、完整接收序列/长期队列等P4协议；`.136`未重跑schema3/4、选定GameTest或全部P2/P3类别矩阵，不引用旧通过作为新认证。没有包并发/性能或P5结论。

下一步优先保留并复现普通item合并触碰未拥有对象的权限失败（核对空间query返回与batch.members、原目标的tick/storage归属及回调），分别在`.134`/`.136`关闭玩家模式比对；需要完整对象协议或正文前完整串行，不允许开始后重放、跳过合并或放宽guard。排清后再推进移动/传送confirm/库存/async的owner协议及完整P4验收。所有测试JVM已退出，未清理失败、未操作现用服、无SubAgent。


## P4 扩展至 .143 候选（统一验收尚未完成）

`.136`普通物品合并反例已定位：原region query可能返回存储中未进入本次ticking成员的ItemEntity，原合并先改栈再discard而触碰未拥有存储。`.134/.136`的相关policy class字节相同（审计在`tmp/player-region-tick-p4-03/item-merge-counterexample02`），但这不撤销候选真实失败。保留原查询和存储guard；`.137`在完整原普通item调用开始前，对下次tickCount为偶数的可能合并调用设置`item_merge_sync_fence`，原possible 2/40 cadence均被覆盖，完整串行而非开始后重放。7项fail-first中4项失败证据与修正测试保留；`.137`persistent off/on原转换runner均exit0，尚非最终产物验收。

`.138–.142`增量实现原队列位置的有限移动/跨Region发布、当前目标已生效的匹配传送确认、本地普通材料PICKUP和共享单箱QUICK_MOVE。逐包完整原handler真实join，未增加第二个队列/池。ChunkMap.move和聊天原全局广播用精确同步publisher服务，worker在原调用点暂停，publisher仅服务当前请求，不泵通用任务。真正接收ordinal在原Connection.channelRead0分配一次，P2门延后不重新编号；旧player/listener/connection/receipt在开始前拒绝。原FutureChain中的过滤聊天completion按完成时当前owner重捕获；接收ordinal只作origin元数据，不能把合法晚完成误判成重放。

新增原PacketUtils任务可取消封装：4096待执行上限，retain/release一次，溢出终止在已接纳前缀之后发原协议disconnect，直至实际退役前不重新开放；running任务保留局部引用到原finally。原聊天chain待完成封装同样有界、断线/关闭可取消并清空引用。native聚焦回归已分别覆盖，尚需最终同产物全回归。

`.142`实际TCP fixed on原路由runner与异步runner均exit0（`p4-08/server-routes-on-fixed01`及`server-async-on-fixed`）：原移动跨0→1区域、匹配确认、5钻石slot9→cursor→slot10；HTTP过滤先挂起三条正常聊天，普通移动先跨区，再释放结果，三条原consumer在当前region1 worker，原全局广播publisher且原顺序保持。另一玩家过滤未完成时正常断线，pending1→0/cancelled1且无广播。这里只记录原runner/JDI/帧观察；独立verifier和四模式矩阵未闭合，不据此宣布P4完成。

最终审计发现packet/service可达事件并集遗漏：ServerChat、ItemStackedOnOther、ChunkWatch/UnWatch、ChunkTicketLevelUpdated、Start/StopTracking、EnteringSection、NeighborNotify。新增9项反例在`.142`全部失败，原XML和旧源码保留`p4-08/event-union-counterexample`；`.143`补全正文前未知监听器拒绝。只接受此前精确内置EntityTick.Pre/Leave身份，不给新增事件监听器或oracle例外。`.143`构建中；独立原路由/async/真实队列压力验证、完整P2/P3/schema3/4/GameTest与最终审计仍未完成。默认false/P5未开始，全部失败世界保留。


## .143 统一验收中的新增反例（仍未收口）

`.143`304项聚焦/533项首轮扩展JUnit表面通过、fixed原路由独立证据及11项负控制通过、fixed/persistent异步独立证据及11项控制通过；固定池原PacketUtils压力4096、前缀耗尽后原协议终止已独立通过及10项篡改控制。以上不替代完整P4认证。

persistent压力runner首包恰进入原P2 Deferred网络路径，后续4200包因此依序积压在原P2门，未形成预期的PacketUtils压力，观察器超时失败并全保留（final01/queue/server-on-persistent）；不能把它计为P4队列验证。夹具后续给两名玩家装备剑，使两个玩家tick都整次串行，关闭该可选freeze窗口，再用正常包独立压原PacketUtils队列；没有修改生产门或对观察器特批。

读取JUnit原system-err发现旧`finalXpStorageCommitsOnceAfterPhysicalReturnIncludingFailureAndShutdown`两种池的后台shutdown异常未被JUnit感知：原parent.close完成通知之后、人工冻结门thaw之前，shutdown.close抛`Cannot close active player network callbacks`，旧P3 XML也保留同样未捕获异常。新增主测试线程的异常捕获断言，先保存fail-first，再实现真正等待网络原回调/解冻的停服关闭边界。`.143`由此不作为最终产物；其chain协调器已停止（真实-15退出保留），当前已启动的完整functional suite允许自行结束，不操作现用服/不删任何证据。P5未开始。


`.143`新增捕获断言的6项原XP测试中，两项shutdown实际失败（failures=2）；反例XML/源码在`final01/shutdown-counterexample`。`.144`为engine关闭新增真正等待网络freeze、已开始原Deferred及直接reader物理返回的边界，期间不执行服务器任务、不按超时释放，恢复中断标记；close的原前置拒绝保留，新增当前callback self-wait检查在shutdown状态变化之前拒绝。每条当前callback的线程登记只活到原finally，reader scope关闭清空引用。XP测试人工冻结夹具现在与真实dispatcher同样在父完成后实际thaw，然后等待shutdown，主线程明确断言后台无异常。新增frozen/reader/deferred真实等待与self-wait测试；全体聚焦回归和构建进行中。


## .144 最终候选冻结与同产物验证（进行中）

installer SHA256 `cfbe350359a4ce37735ef46b72ce6f6418d74527aa3d3f245f5a6d9d4579bd14`，新根`tmp/player-region-tick-p4-final02`。显式审核manifest `.144` SHA256 `51885dcfb4f94a1993b5755e2a16af2f58451f34db57ef57113f1d15639d85b2`，32份生产源码（包含P3合同）冻结；所有旧manifest及`.115` gate保留。

343项聚焦回归、644项/21类完整受影响JUnit及3项选定GameTest已通过。完整JUnit wrapper因一律拒绝`Exception in thread`的额外审计退出1，真实Gradle已成功且644 XML全部零失败/错误/跳过；后续独立`junit-audit-command`明确只识别旧`failedTickReleasesTheRegionLease`主动注入的唯一`IllegalStateException: boom`，核对原传播、running/submitted归零和lease可复用断言，以及旧`.134`同样日志；未知后台异常0，XP停服异常0。原wrapper失败不改写，GameTest首次因该wrapper停止的失败也保留，恢复命令实际exit0。

首组`.144`原路由通过，但verifier最初误要求传送确认后自动落地包必定worker；真实原vanilla teleport清除onGround，自动落地若在下个物理tick前同队列执行，SPATIAL:MOVEMENT_UNBOUNDED整次串行是正确合同。独立verifier仍强制3个跨区移动/匹配确认/首个库存PICKUP全worker，且只允许此特定自动落地在原capture明确给出上述拒绝时完整串行，普通包/同步结果/owner与保存断言保持。旧失败verdict保留，新`independent-verdict-final.json`为通过复核。

共享箱子最初verifier将原开箱初始化的broadcastChanges（PACKET标签但原openMenu栈）误算作点击，已根据原handleContainerClick完整栈精确区分；原箱子shift-click确在worker，原stillValid真、count11→0、库存11和两个客户端反馈均通过。原失败verdict保留，独立最终verdict和13项负控制通过。其他模式和P2/P3所有矩阵继续，不以中间通过宣称P4完成。

新校验器把每条owner执行/聊天completion的receiveSequence与保存的完整handshake/login/config/play原始出站帧逐连接ordinal精确对应，核对原方法开始/返回区间被实际worker区间包含；不只检查序号正值或runner计数。普通游戏操作仍全部来自正常TCP包，命令只作夹具和独立读取。


## 原执行器空任务上限反例与 .145（最终同产物验收继续）

`.144`的任务登记虽然最多4096，但terminal之后的每个包仍返回一个空Runnable再排入原executeIfPossible，等于把无界积压转移到原TickTask队列。新增测试穿过完整原PacketUtils入站入口，4200个原包实际排4200个任务，应为4096正文+1 terminal；fail-first实际1 test/1 failure，XML/源码保留`final02/empty-task-counterexample02`。首版测试曾直接访问package-private snapshot导致2项编译错误，原编译日志同样保留，不冒充测试反例。

`.145`对已明确terminal的连接，在原PacketUtils准备阶段直接抛回原RunningOnDifferentThread控制流异常，**不再调用原executeIfPossible排空任务**。已接纳4096正文及唯一协议terminal仍原序运行，terminal直到实际断线退役前保持，不吞正常可接纳包，不新增独立队列。344聚焦、645项/21类完整受影响JUnit（零失败/错误/跳过，原boom仅为显式注入负例，未知后台异常0）和3选定GameTest通过。新installer SHA256 `15a239612d71bb5e32b92f24948761feb873e667e926c12bb215c28e38617c97`，manifest SHA256 `614e45a50a7cee55ccbbcf9994e61fdcd2192a3e2e9626decda550dbd1497e62`；新根`tmp/player-region-tick-p4-final03`，32生产源码冻结。`.145`两池真实TCP队列压力已独立通过，原prefix耗尽/明确协议断线/队列退役零残留均通过。

`.144`已完成四模式routes/async/hotbar、两池队列，以及完整XP/材料若干P3矩阵等中间证据保留；其协调器已记录停止，原已启动隔离矩阵允许自行完成。它们不混入`.145`最终接受清单，所有真实exit、失败verdict和世界保留。

`.145`首组共享箱子状态/同步/11钻石守恒与原quickMove worker都正确，但A的一次ENTITY/CONNECTION调用完整串行，因此旧“全测量窗口worker”要求拒绝；315次/阶段worker不能掩盖该反例。原观察器没有采到这一调用的精确拒绝原因，不能用全局refusals替A归因。保留`final03/p3-chest`失败。新观察器为原菜单入口补读本listener的keepAlivePending/awaitingTeleport；新正向夹具只等待两名客户端各完成一次**真实原自动keepalive挑战/回复**再测量，以避开JDI设置碰上正常15秒challenge的可选准入窗。原worker/原正文/事件/反馈/守恒断言全部保持，不放宽verifier或生产门，不写连接字段，精确原keepalive帧也独立解码核对。完整P3恢复到全新`final03/p3-recovery01`矩阵，其他`.145`P2/P4矩阵继续；整体仍未最终收口，P5未开始。


`.145`四模式routes/async/hotbar、两池队列及四模式生产smoke全部通过；fixed双入口独立区各244调用/各121重叠，同区各242/0；persistent独立区各244调用/各96重叠，同区各240/0。四模式A region(0,0)正常TCP攻击B region(1,0)均health20→19、受害端一个damage包/health19。P4四类原协议篡改控制最终routes12/async12/queue10/hotbar9全拒绝；共享箱子13项控制同样通过。

P3新矩阵的首组饥饿夹具失败保留：设置hunger后、原JDI source program尚在编译/attach时第一次原伤害已经发生；观察器实际记录第二次starve health19→18，严格原state/一次伤害/保存/stat校验拒绝。`tick freeze`按原eligibility不停止玩家原Connection/doTick，因此不能作为观察器准备的玩家暂停。`.145`生产并未重放伤害；不能把被漏观察的首击算成worker证据。旧`final03/p3-recovery01/server-damage-on-fixed`全保留，verdict不改。修正仅将只读观察器在hunger前先arm，保持全部原伤害/事件/返回/客户端/守恒断言、普通包触发与完整原正文要求；新`p3-damage-recovery01`、foreign和后续三模式在新目录继续。接受路径计划显式保留已完成五类on-fixed语料，并映射新的damage/后续矩阵，仍非最终验收。


## .146：聊天退役预算与碰撞纠正的正文前闭合（最终统一验收进行中）

`.145`补充审计发现：原detectRateSpam会在chatSpamTickCount+20>200时同步disconnect；带签名广播还可能因任一收件人的pending签名缓存超限而同步退役该外来连接。这些分支不属于worker暂停期间的精确publisher服务合同。新增两池spam/signed四项原准入反例全部失败（`final03/chat-budget-counterexample`）；不把正常低流量聊天通过扩大成已审核所有签名/限流状态。

另一个正文前反例：空间足迹虽完整且块为原STONE，扫掠体可能遇到墙，完整原移动handler会走server-only teleport纠正。新增9项移动反例中collision一项失败（`final03/collision-counterexample`），其余8项仍拒绝。`.146`在捕获/复查完整扫掠体时调用原noCollision，受阻则完整原handler串行；不放宽teleport权限、不截断碰撞、不开始后回放。

`.146`只让无签名的原过滤聊天completion进入owner，并在正文前要求chatSpamTickCount0..180；原+20到200的精确边界通过两池原detectRateSpam测试。签名聊天和可能限流断线的完整consumer仍在原FutureChain/server位置运行，默认MAIN合同不变。共351聚焦、652项/21类完整受影响JUnit、3选定GameTest通过，未知后台异常0（原明确注入boom仍单列）。32份生产源码冻结、12份持久Minecraft patch严格apply/generated一致。

最终候选`21.1.250.146-beta`：installer SHA256 `e8482c4830517fc6d18af6b84a2b6e672a136e46595eb1036c2675fe6650bc4a`；显式manifest SHA256 `99671241fb4de384ded730a8bf458e3956cca7bc04c00f32d8f174a3ac3d00ac`；统一新根`tmp/player-region-tick-p4-final04`。`.145`已完成的原schema/转换/场景和全部失败保持原样，不混入`.146`接受清单。

真实routes场景新增普通客户端撞墙包，原handler在server完成位置纠正、客户端正常确认且不断线，health/物品/XP不变；新的独立JDI/原始帧/服务端读取证据单列jdi-wall。首组on-fixed已独立通过，包括6个原worker handler及一次完整原墙体纠正；两池队列已通过，其他模式/全部兼容矩阵继续。P4未最终收口、P5未开始。

应用daemon重启中断了顶层run_command记录父进程，已有隔离矩阵子进程仍正常运行；恢复后核对了实际进程及所有已存在result，未重复启动或覆盖。顶层缺失exit的wrapper仅记unknown、不算通过；后续接受只选择具有实际exit0的子命令和完成矩阵。恢复审计`final04/daemon-recovery-01.json`保留。未reset/clean/commit/push，不操作现用服。


`.146`完整schema3 144/schema4 180及四模式阶段顺序/资源守恒已通过；四模式转换（包括历史item merge崩溃的off-persistent）通过。P3前13份接受场景均通过。persistent外来观察者饥饿场景第一次原hurt/JDI完整返回20→19、debugger实际exit0，但随后的RCON tick freeze超时，框架最终终止隔离JVM（-9）；原目录`final04/p3/server-damage-foreign-on-persistent`不计通过，原日志/帧/JDI/世界完整保留。该次未在终止前取得线程栈，**阻塞原因未确定，不以健康重试断言已修复或归因外部环境**。

新的Session工具在RCON OSError/超时时、原终止清理之前先用只读jcmd Thread.print -l保存真实PID/命令/线程转储/真实退出或诊断错误，不改变游戏正文/准入或放宽超时断言。新隔离`p3-foreign-persistent-recovery01`同产物原严格场景通过；余下两种off模式独立新目录继续，接受计划明确映射已成功的原13份及新恢复项。另安排两轮同场景诊断复测，旧未解释超时仍单列，不删除失败或伪造原wrapper退出。当前仍在最终兼容验收，P5未开始。


## 最终 P4 收口（.146，同产物统一验收完成；停在 P5 前）

当前最终产物`21.1.250.146-beta`，installer SHA256 `e8482c4830517fc6d18af6b84a2b6e672a136e46595eb1036c2675fe6650bc4a`，归档`tmp/player-region-tick-p4-final04/artifacts/neoforge-21.1.250.146-beta-installer.jar`。显式审核manifest `tools/player-region-tick/reviewed-artifacts/21.1.250.146-beta.json` SHA256 `99671241fb4de384ded730a8bf458e3956cca7bc04c00f32d8f174a3ac3d00ac`。最终机器回执`tmp/player-region-tick-p4-final04/acceptance-01/receipt.json`、闭合审计`tmp/player-region-tick-p4-final04/acceptance-01/final-audit.json`，接受路径`tmp/player-region-tick-p4-final04/accepted-evidence.json`。早期候选、非零/无exit的wrapper、错误verdict及全部世界保持原样，不混用版本或抹除失败。

P4按设计第7节的**逐类有界 owner 网络协议**完成：在原服务器executor位置重新校验当前session/实例/listener/connection/ledger并执行完整原handler/continuation，真实join再返回。空槽快捷栏、有限水平无碰撞移动及跨Region发布、已生效目标的精确确认、本地普通材料PICKUP、共享单箱QUICK_MOVE、无签名且限流预算内的原FutureChain完成均有实际原正文证据。接收序号精确对应原handshake/login/config/play帧；晚完成聊天在当前owner执行，保留原chain顺序。原tracking/全局广播只作当前worker暂停时的一次精确publisher服务，不泵任意服务器/chunk任务，不增加队列/池或公共owner executor。

最终全部新增回归已纳入**652项/21类JUnit，零失败、错误、跳过**；351聚焦、3选定GameTest、12持久patch严格apply/generated及32份生产源码冻结通过。原明确注入的`boom`有传播/lease回收断言并单列，未知后台异常0。原执行器4200接收只接纳4096正文+1 terminal，terminal之后不再排空TickTask；等待实际thaw/原直接及Deferred回调返回后才关闭，self-wait在状态变化前拒绝。所有running引用留到原finally，尚未开始封装断线/停服取消，最终player owner/state claims/packet queues/receipts/chat/network引用归零。

同一`.146`完整schema3 144、schema4 180，全部13套P2兼容/原正文故障/部分提交与原阶段/NETWORK-MAIN/转换/计时器/断线/重生/保存恢复矩阵，28份P3及14份P4语料通过。原普通物品merge反例涉及的off-persistent转换在最终产物重验通过，完整原合并调用在副作用前串行而非跳过、放宽存储guard或重放。110份独立verdict、102套唯一接受隔离安装、369项proof篡改负控制闭合；seed 94份jar未变，core/Minecraft均私有inode。原`.115` gate和所有旧manifest不改；oracle两入口UNKNOWN_MOD、worker=0。

四模式普通TCP A region(0,0)攻击B region(1,0)均health20→19、受害端恰一个damage包/health19；两个池两入口有真实跨区重叠，同区0。完整旧schema实体/方块/BE/共享容器操作和物品/XP守恒复验通过；共享箱子原quickMove另有worker正文、同步返回、两端反馈及11钻石守恒。新增原撞墙包四模式均完整server纠正、客户端正常confirm、不断线且health/5钻石/XP不变。具体每模式原始结果见接受路径和独立verdict，不以线程名称或helper副作用替代原正文。

保持默认false、未知扩展freeze前整次串行、原NeoForge MAIN/NETWORK payload合同、开始后异常不重放、真实join后清理。**签名聊天、可能触发限流/收件人退役的路径、碰撞纠正、远距/垂直/车辆/未知包/Mod回调、传送/维度/重生/重配置仍保留完整原server fence**。P4完成不表示任意玩法/Mod均可worker并行；每个包当前仍在原task位置同步join，无包并发或速度收益结论。

整体设计仍partial，仅剩P5规模/混合负载/性能及长时恢复验收等后续工作；本次未开始P5。仅有限真实TCP/JDI与原native测试，不宣称8/32客户端、长期压测、任意动态Mod或GUI暂停认证。daemon恢复造成的顶层无exit wrapper按unknown保留，最终接受只使用实际成功的子命令。P3的既有推送保留；P4本地未commit/push/deploy，无现用服操作，无reset/clean/证据删除。

保留例外：一次persistent外来观察者JDI场景在原hurt20→19返回后发生RCON及客户端keepalive停顿，框架终止JVM；原始第一次无终止前线程栈，原因未确定。该目录继续failed/excluded，未被验收覆盖。映射的新恢复场景及两轮独立严格诊断均通过，不能据此断言旧停顿已修复或保证无长期停顿；详见preserved-stall-assessment.json及receipt.preservedUnexplainedProbeStall。
