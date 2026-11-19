# P5 多人、性能与长期恢复验收

2026-11-18 用户授权开始 P5。API 增量已提交并推送 `188cdcf71b3b88440916bba64b007a0dca4dfc54`，当前基线产物 `21.1.250.152-beta`，安装器 SHA256 `29d5f44f4d269d7488dc8e5232fb360c316a368e37d615141302ac2912747c19`。本阶段进行中，整体仍 partial，不预先宣称性能收益或长期稳定。

## 约束与验收范围

保持默认 false；未知 Mod 的完整玩家 ENTITY/CONNECTION 与未声明包整次串行；显式 payload API 只声明精确单个处理器，不审批整个 Mod。原 MAIN/NETWORK、原正文、不重放及真实 join 后清理保持。无 SubAgent，无 reset/clean，无现用服操作；原失败和新测试目录全部保留。

资源观测：WSL 8 个逻辑 CPU、约 9.7 GiB RAM、8 GiB swap；开始时本文件系统约 857 GiB 可用。采用新隔离根 `tmp/player-region-tick-p5-01/`，沿用正式 `.152`；先完成真实客户端规模工具/测量正确性，不用 FakePlayer 或空服代替多人。资源配置、负载时长、实际收到包及串行比例分别披露。

计划矩阵：1/8/32 真实 TCP 客户端 × 同区域/独立分散区域/边界配对 × fixed/persistent × off/on。每个测量窗口先准备地形、登录、完成明确记录的旧有受限准入 fixture 并预热，再测原网络包/玩家双入口及资源守恒。相同区域/混合 Mod 合法串行与负收益结果必须保留。

性能只在独立正确性 verifier 通过后解释。完整 tick 来自原 MinecraftServer 的 JMX `tickTimes` 原始 100 槽 **纳秒**环（当前原实现注释描述 ms，但实际 `tickTimesNanos` 是 ns）；外部采样保存原环并重建被更新槽，不将均值周期事件冒充逐 tick 样本。包延迟来自正常 TCP ping 请求和响应，另外核对动作反馈。JVM 原生 ThreadMXBean 分配/CPU、GC/heap 数据单列；已退役线程未采到的分配计作下界限制，不假造精确总量。主线程栈采样可估计等待覆盖，不能把等待真实游戏工作的时间等同纯屏障开销；若未获得完整屏障时间，明确未覆盖，不由总 MSPT 推导假值。

后续单列混合 Mod 正确性、实际共享机器/地图/骑乘/PvP/传送负载、多人断线重连和保存重启，以及有明确时长的长稳/泄漏观测。历史 P4 persistent foreign-starvation 探针未解释 stall 仍 failed/excluded，先用保留原严格探针及新 thread dump 诊断复验，不把重复通过当作已查明根因。

## 当前状态

已读设计第 7/8 节、API 最终回执与现有运行工具。已有 modpack loadtest 以 FakePlayer 建立负载，不符合本 P5 真实多人要求，不能直接复用其 PASS；旧 run_soak 含重建目录和强杀控制流，也不用于本阶段。正在实现独立真实客户端规模工具、只读 JVM 监控和严格验收器；尚未执行规模/性能/长稳认证。

首轮工具试跑：`server-pilot01` / `run-pilot01` / `verify-pilot01`，1 与 8 名实际 TCP 客户端，分散区域，3 秒预热/6 秒测量，共两窗已独立通过。原始 JMX 纳秒环、ThreadMXBean、GC、精确请求/回复 wire、原诊断/资源读取和退出都保留；两阶段确有 worker，退出队列/owners/state claims 为零。这是采样/负载工具试跑，不是性能认证。7 项测量单测通过，覆盖环绕、仅计实际槽更新、相同环不重复计样本、采样断档/整环覆盖拒绝、精确分位数、分配回退拒绝和 barrier 等待覆盖不冒充纯开销。

`scale-matrix01` 被替代并保留：同区地形夹具原来按区块去重，但只铺首位玩家的 9×9 小平台，后续同区玩家脚下可能漏铺。发现后终止该隔离协调器、向该隔离 runner 发 KeyboardInterrupt，经其原 finally 正常停服；真实 server/client/sampler exit 均为 0，runner 失败/协调器中断状态保留，未混入认证。改为完整区块支撑面后启动全新 `scale-matrix02`。此外 off 模式原来不建立 player coordinator，原计数确实全零；验证器不能把“计数不存在”判成“玩家不 Tick”，也不能伪造 serial 次数。off 明确报告 serial=null（未测量），worker=0，实际 liveness/动作和完整 Tick 环仍独立验证。

当前全新矩阵：`.152` 两池/off-on 顺序运行（off-fixed → on-fixed → on-persistent → off-persistent），每配置 1/8/32 × same/spread/boundary 九窗，10 秒预热/30 秒测量，单测试服务器运行避免跨服竞争。所有普通动作来自客户端 movement/hotbar/ping，边界另用正常攻击包核对 A→B、B health20→19。16 钻石/7 XP/20 health 每玩家在窗口前后原读核对。样本轮询、客户端与 sampler 仍与服务器共享 WSL CPU，披露其测量成本；单次固定顺序是探索数据，不授予收益证书。

另外只读发现普通 FerriteCore/ModernFix/Jade 现成 jar，已记录精确 hash 的 `mixed-mods-planned.json`；尚未启动混合 Mod 验收，不据此声称安装/玩法通过。Create/共享机器的真实 Mod 客户端路径仍待独立验证，不能以当前原版协议客户端冒充 Mod 客户端认证。

`scale-matrix02` fixed/off、fixed/on 两组各九窗已完成且独立通过正确性：1/8/32 的真实登录、客户端背包和保存 NBT、16 钻石/7 XP/20 health、同区无同阶段正文重叠、退出零残留；8 与 32 人的 A→另一 Region 的 B 正常攻击均 health20→19/一个原 damage feedback。开启完整调用 worker 比例约 96%–99.7%，原 phase/网络读者冲突的拒绝保留完整串行。

**第一轮 fixed 对照出现明显退化，性能门未通过**：32 人同区完整 Tick mean off/on 为 6.280/56.111 ms，p99 11.536/70.948 ms，真实 ping p99 5.571/1287.848 ms；32 人分散 Tick mean 3.917/51.373 ms，ping p99 6.644/164.005 ms。各窗口都保留，不因无收益或尾延迟变差而删除。单次固定顺序短窗只形成该轮观测，不能据此推断所有玩法/硬件；更不能宣称性能提升。persistent 对照继续。

规模对照结束并独立收口后，排队进行新目录的三普通 Mod（FerriteCore/ModernFix/Jade）×四模式八人分散兼容；预期未知 Mod 完整玩家 Tick 始终串行，不特批。再在新无 Mod 32 人分散目录开启原 JVM Flight Recorder 进行热点诊断，录制期退出/完整 wire/守恒仍验证；该录制不计入正常性能 A/B。精确屏障 CPU 开销、受控重复、长期恢复及真实 Create/共享机器混合玩法仍未覆盖；整体 P5 不得标完成。

## 停电暂停与新目录恢复（2026-11-18）

用户停电请求后已中止隔离协调器与排队任务，向正在运行的 persistent runner 发出 KeyboardInterrupt。恢复前检查没有本 P5 遗留进程；但原 finally 的 `stop` 复用被打断的 RCON 连接，读到旧响应并抛 `Unexpected command reply id/type`，最终 runner result 缺失。服务器日志记录全部维度保存完成，但不能从日志补造进程退出码。原 wrapper exit=1/协调器 -15、缺失最终 result 的事实及已完成窗口记于 `tmp/player-region-tick-p5-02/resume.json`。测试工具已增加 RCON 收尾失败后记录原错并经该隔离服 console 正常 stop 的后备路径，以及 cleanup 异常仍写最终失败 result；无生产逻辑变更。

`scale-matrix02` 的完整 fixed 两组证据保留；被打断的 persistent 组不能计整组通过。用户随后明确“你继续”，在全新 `tmp/player-region-tick-p5-02/` 重新安装并运行两套 persistent 配置，原目录与失败全部保留。整体 P5 仍 partial，先前 fixed 性能退化结论不撤销。

## 操作正确性优先（2026-11-18）

用户明确先验证操作，调度开销后续再降；目前无生产 Java/patch 优化。`p5-02` 的 persistent 新跑在 32 人预热时一客户端断线，服务端资源读取报 `No entity was found`，原 runner/server/client/sampler 结果与包全保留，不能接受整组。原包显示该客户端最终收到 `disconnect.timeout`；其 keepalive challenge 的 ID 时间与客户端收到时间相差约 15.6 秒，客户端收到后立即回包，JMX 同段仍持续 Tick。具体网络/夹具/负载原因尚未确定，不得写成已修复。排队 JFR 在用户新指令前已经启动并因 32 人超时失败，该失败不作性能证据；后续性能诊断已延后。

同一队列的三 Mod 首组 off-fixed 在登录即失败：Jade `CommonProxy.playerJoin` 向未协商其通道的原版协议客户端发送 `jade:server_ping_v1`，由原 `NetworkRegistry.checkPacket` 拒绝。无 worker 特批或通道检查旁路；该组合与真实 Jade/Create 客户端玩法均未获认证。

新增 8 真 TCP 客户端原包操作/恢复工具 `run_player_operations.py`，先做 native persistent 试跑。`tmp/player-region-tick-p5-operations01` 保留四对跨区攻击、共享箱子/漏斗输送竞争、挖掘结果，但整套 **failed**：放置夹具仍留有下方箱子，且 B 占据目标格，普通包未放置成功。此前进度提到“放置通过”过早，正式结论在此纠正。新夹具仅在测量前恢复惰性支撑并移开 B，不改原包/碰撞逻辑；在全新 `operations02` 继续。地图、恢复、独立 verifier 及四模式尚待完整通过，不能以这些中间副作用声明整套完成。

`operations02` 原运行通过，第一次 verifier 误把恢复窗口的 `save-all flush` 当作游戏写结果而拒绝；原失败保留，仅在恢复窗口明确允许这一条原保存命令，新独立 verdict 通过（八操作、两轮/12秒负载、两次boot退出0）。`operations03` 增加跨区挤奶、正常骑乘/下车、双向传送确认后，11类操作、两轮重连与保存重启均通过；12项原包/状态 proof 篡改全部拒绝。这些是短夹具试跑，不是长稳认证。

`operations04` 新 native persistent/on 通过11类操作、四对A→B跨区攻击、真实原门下界往返、两次同维度传送、三轮/60秒八人正常movement/slot/ping负载、三轮全部断线/重连、旧实例不复用与一次停服保存/重启，全部原包独立复核通过。相同范围在 FerriteCore+ModernFix persistent/on 通过（两轮/20秒），完整玩家两阶段worker=0、UNKNOWN_MOD拒绝计数增长。无Mod准入例外。API产物不变，37个生产源hash逐项与`.152` manifest一致；对保留同字节schema3 144/schema4 180原始语料重新独立解码/事件/状态/守恒复核通过，报告`operations04/schema34-reverified.json`，明确不是新跑8台安装服。

native及两Mod的off-persistent实际完整运行结束，但原首次 `create_and_store_map` 准备快照处于骑乘后command teleport的位置更新与owner索引发布之间（ownerCurrent=false），严格verifier拒绝，旧矩阵保留failed。新增操作前有限等待原owner稳定，保存所有准备快照，不修改原owner/索引/游戏逻辑，不跳过动作后检查。在全新`operations05`重跑native其余三模式；`correctness32-02`使用四个独立Node协议进程（每进程至多8人，保留逐进程原帧再按原单调时间合并）检查32人是否仍超时；之后`operations06`重跑两Mod其余三模式。测试服严格顺序运行。原单进程32人超时原因仍未判定，分进程方案是测试客户端隔离，不是服务端优化或问题已修复的声明。四模式全部闭合、Create/Jade真实Mod客户端、长期恢复/泄漏与性能认证仍未完成。

native其余三模式已通过上述完整动作/恢复（`operations05`）。进一步审查发现：headless客户端发送正确预测的 QUICK_MOVE 后，服务端可能无需再发 changed-slot 回包，而工具没有像图形客户端一样应用本地预测，旧快照仍混有旧容器槽。因此旧八人 schema2 的共享容器结果只证明原服务端守恒/动作及部分反馈，不能声称精确客户端背包也一致。schema3 新语料增加正常 close/use 重开，要求原 wire 的完整 window_items 与客户端背包/服务端库存一致；已有native on-fixed新语料通过。较早缺失该项的模式在`operations07`按每配置新安装补跑，旧结果/失败均不改写。挖掘/放置额外核对原客户端block_change，挤奶核对milk_bucket set_slot，地图核对真实背包filled_map，所有操作XP恒7；恢复窗口之外禁止写结果命令。

`correctness32-02` 最初两个fixed模式因新控制汇总器将子进程非JSON stdout当作控制回复而失败（真实server/sampler exit0、客户端关闭失败原退出保留）。与原Session读取约定一致，现将完整子进程stdout另存，非控制诊断不冒充requestId响应；实际协议client error/end/raw记录仍严格拒绝。保留诊断显示Node的NeoForge配方包解析 `PartialReadError`，与所验证原版玩法反馈不同，**不证明全部NeoForge配方/GUI客户端协议已支持**。两套persistent继续；`correctness32-03`在操作矩阵后仅重跑未完整通过的模式，保留逐子进程帧并逐条核对合并记录，无放宽超时或生产准入。

## 八人操作矩阵统一审计与32人恢复

`operations07/acceptance01` 已统一独立通过八个最终配置：native × 四模式、FerriteCore+ModernFix × 四模式。最终选择优先使用schema3（包含正常重开后的客户端精确容器/背包复核）；原`operations04/05/06`中较早只有部分反馈的语料保留且不混入这项证书。显式每配置映射 `operations07/accepted-evidence.json`，记录原安装/run/verify真实退出，不改写任何旧矩阵失败。

实际范围：8名TCP玩家/安装，11类操作×8=88个窗口，四对跨区A→B/配置共32次命中、每受害者20→19和恰好一个原damage反馈；共享箱子双人竞争与漏斗→箱子机器、跨区挖掘/放置/挤奶、正常上船/下车、原地图创建与背包点击均保持。每配置2次同维度teleport/confirm，8套原门下界往返，合计17轮全部玩家断线/重连、8次保存/停服/重启（16次boot退出0），全部玩家客户端Inventory/XP、服务端资源与真实保存NBT一致，真实join后队列/owner/state计数为零。保留采样中的完整phase记录无同玩家同tick重复开始；本套件没有新做逐包refCnt或heap可达性证明。未知两Mod完整两入口worker=0、UNKNOWN_MOD增长；未授予任何Mod整体worker许可。120项原包、客户端/服务器资源、owner、teleport/dimension、mount、退出/队列篡改全部拒绝。

产物仍`.152`，37个生产源码hash与显式审核manifest一致，Minecraft/core jar字节核对并维持私有inode；本轮无生产Java/patch变更或调度优化。首轮审计输入闭合1056项；最终审计另记 `acceptance02`，保留`acceptance01`。这是**有界八人操作与恢复**，不等同多小时soak、任意Mod/全部GUI协议、Create/Jade客户端玩法或P5完成。所列schema3 144/schema4 180是对同产物保留原语料离线新复核，不伪称本轮新跑。

32人分进程的两套persistent（`correctness32-02`）各same/spread/boundary三窗独立通过，跨区攻击20→19、同区原正文无重叠、server/client/sampler exit0和退出零残留；后续`correctness32-03` on-fixed也通过。

但`correctness32-03` off-fixed **failed**：边界窗口后RCON关闭，原server exit1、sampler exit1（client0），watchdog在Region普通任务drain等待处触发。不能宣布四模式通过或原因已修复。诊断 `off-fixed-interruption-diagnostic.json` 显示独立JMX有495.760秒及62.405秒断档，前段Server CPU仅31.117ms、两个worker合计约2.073ms，GC所有count/time不变；推测环境暂停/全局调度中断，但根因未证实，也不能仅凭等待栈认定Region死锁。原日志中的557.57秒是真实watchdog日志值；crash Error文本误以ns/1000格式化成557570624.00秒是原NeoForge报告单位错误，非本次修复范围。全部崩溃、线程、原包和失败退出保留。在新`correctness32-04`仅重跑未通过off-fixed，并显式继承其他三个完整通过模式；不改变watchdog/keepalive阈值，不关闭worker或放宽验证器。

## 本轮最终结论（2026-11-18）

用户要求的“先操作验证、后续降低调度开销”本轮已完成**以下有界验证**，整体P5仍partial，未进入调度优化。产物仍`21.1.250.152-beta`，生产37源hash不变，HEAD仍`188cdcf71`。无新JUnit/GameTest运行；684 JUnit/3 GameTest属于此前API验收，不作为本轮新增测试计数。本轮测量工具7项单测和中断RCON收尾1项单测通过；19个工具源码语法检查、git diff --check通过。

八人操作最终回执：`tmp/player-region-tick-p5-operations07/acceptance02/receipt.json`，SHA256 `93966079864aee732edacb3cc24882192c4fc5d01d844d422acfb71b4eb04427`。8配置/88操作窗/32次跨区命中/17轮重连/16次boot、120项篡改负控制、精确客户端与服务端及保存NBT守恒通过。1016项输入关闭校验；旧`acceptance01`及全部更早失败/局限保留。

32人新off-fixed完整运行与独立验证退出0，same/spread/boundary三窗全过；最后统一审计采用显式`correctness32-04/accepted-evidence.json`：fixed/off来自新`04`，fixed/on来自`03`，persistent/off-on来自`02`。**四模式×三分布共12窗**，每服4个独立真实协议客户端进程、每进程8人；逐条校验原子进程帧与按原单调时间合并的帧一致。同区采样无正文重叠，两池/on分散区有完整worker正文；四模式边界正常攻击均20→19、原受害端一次damage反馈，退出账本/队列为零、server/client/sampler均0。8项原生测量proof负控制通过。最终回执`tmp/player-region-tick-p5-correctness32-04/acceptance01/receipt.json`，SHA256 `81071b31c642735e73ef4ad7ede3da211e20b287ba4c1f8d0ca0840bdb6a19c8`；516项输入闭合。

两组证书合计128项proof负控制、36次真实跨区命中。只证明所列客户端拓扑/短窗/原版操作与两个普通服务端Mod组合；原单进程32人keepalive超时、watchdog/采样断档原因、历史P4诊断stall均仍未解释，不以新目录通过声称根因修复。原失败或退出未知wrapper共28项在本轮总状态中显式保留，不接受为成功。Jade/Create真实Mod客户端、一般Mod玩法/完整GUI协议、多小时长稳、heap可达性/逐包retain释放、受控反复on/off性能和精确屏障开销仍待完成。先前fixed性能退化结论保留，无收益认证。

最终汇总与失败路径索引：`tmp/player-region-tick-p5-operations07/closeout-state.json`。所有本轮隔离server/client/sampler、协调器和等待脚本已退出，进程审计为0。保留原工作树/失败/世界/原包，不reset/clean、commit/push/deploy或操作现用服；默认false、未知扩展整次串行、原MAIN/NETWORK、开始后不重放及真实join后清理合同保持。

## P5 异常追查（2026-11-18）

原 32 人失败可在冻结 `.152` 上用单 Node 进程、persistent/on、32 人分散夹具再次触发。原进度命令批量授予 32 人全部进度并广播公告，客户端解码到 120,610 条 `system_chat`，抓包待写队列峰值约 306 MiB。该进程的诊断定时器最长间隔 17.95 秒，同期独立宿主采样最长间隔约 267 ms。TCP 队列有积压；最长原 keepalive challenge 生成到客户端解码为 21.36 秒，客户端收到的原 `disconnect.timeout` 共 10 次，runner exit=1。此延迟是发送端生成到接收端解码的端到端观测，不能全部归为网络传输或某一段实现开销。

只在授予进度的夹具窗口关闭 `announceAdvancements`，保留原进度授予，结束后恢复原规则；抓包、协议响应、超时阈值和游戏负载不变。对照仍为同一 `.152`、同一单进程 32 人、同一 persistent/on 分散窗口，原始 `system_chat` 降至 496 条、最长 keepalive 生成到解码降至 18.76 ms、无超时，30 秒窗口和独立验收通过；随后 `.152` fixed/on 的同区、分散、边界各 60 秒均通过，边界正常攻击仍 20→19。结果支持本次可复现的超时由进度广播和抓包处理洪峰触发，旧失败保留，不能推广到所有 keepalive 超时。测试矩阵现默认关闭这一段公告；`--broadcast-advancement-fixture` 用于复现原设置。汇总拒绝混用广播开关、诊断采样或客户端拓扑不同的运行。

旧 32 人 off-fixed watchdog 另作调查：四个独立 Node 客户端原帧也出现同期断档，三个各为约 496 秒与 62 秒，另一个连续约 558 秒，与 JMX 两次断档时间吻合。这补强了共享环境停顿的判断，仍不足以确定宿主层原因；一次 Region 等待栈不能证明死锁，也不将该失败改记为通过。

修正 `ServerWatchdog.java.patch` 崩溃报告把纳秒按 `k / 1000` 写成秒、并硬编码 0.05 秒的问题。现在用正确单位分别显示耗时、当前 Tick 时间预算和实际 watchdog 配置阈值，触发条件不变。候选 `21.1.250.153-beta` 构建成功，严格 `applyPatches` 应用产物与生成源码一致。仅在隔离诊断服使用 3 秒测试阈值，JDI 暂停主线程，验证真实 watchdog exit=1，日志和崩溃报告均显示 3.00 秒，报告另列 Tick 预算 0.05 秒和配置上限 3.00 秒。另用正常默认测试阈值的 `.153` persistent/on 单进程 32 人边界跑 60 秒，独立核对跨区攻击 20→19、客户端伤害反馈、物品/XP 与保存结果、退出零残留，均通过。此候选未做完整 schema 3/4 复验，此前 `.152` 的产物审核及验收范围不变。

本轮证据 `tmp/player-region-tick-p5-diagnose01/diagnostic-receipt.json`，含原包、采样、退出码、JDI 探针及 `.153` 封存产物。7 项测量工具单测和 1 项停服工具单测、语法检查、`git diff --check` 通过。静音夹具下 `.152` 32 人开启池的窗口平均 MSPT 仍约 51–65 ms，不能以 keepalive 恢复掩盖此前性能退化。调度优化未开始；旧宿主中断根因、Jade/Create 实际客户端和长稳仍未闭合。失败和工作树保留，未提交、推送或操作现用服。

## `.154` 交接：行为回归通过，高延迟待专项优化

候选 `21.1.250.154-beta` 复用不可变的模块服务描述和反射元数据，并减少正文权限查询临时拼接资源列表。Mod ID、Mixin 配置、实时监听器和库存身份仍按原门禁读取；没有改变租约、网络队列或物理 join。开关仍默认关闭，未知扩展仍走完整原串行调用。

同一 `.154` 已通过 407 项 JUnit、四模式 144 个 schema 3 / 180 个 schema 4 场景，以及单进程 32 个真实 TCP 客户端的四模式 × 同区、分散、边界 12 个窗口。边界操作由普通客户端包触发并核查伤害反馈、资源及保存结果。产物哈希、运行记录和完整测量摘要见 [`.154` 交接数据](evidence/player-region-tick-p5-154/summary.json)，原安装、帧和日志在 `tmp/player-region-tick-p5-opt01/`。

| 池与开关 | 分散：平均 MSPT / ping p99（ms） | 同区 | 边界 |
| --- | ---: | ---: | ---: |
| `.153` fixed 开 | 75.89 / 512.22 | 102.60 / 566.20 | 107.41 / 644.79 |
| `.154` fixed 开 | 56.06 / 343.12 | 63.44 / 337.49 | 56.16 / 214.11 |
| `.154` fixed 关 | 6.09 / 9.70 | 6.46 / 16.36 | 5.59 / 13.43 |
| `.154` persistent 开 | 58.30 / 314.11 | 69.93 / 392.77 | 64.19 / 346.36 |
| `.154` persistent 关 | 5.90 / 11.12 | 11.80 / 449.94 | 6.44 / 27.97 |

各配置只有一次顺序执行的 30 秒窗口。`.154` fixed 开启的均值和 ping 尾延迟低于 `.153` 的对应试次，但 `.154` 开启的平均 MSPT 仍明显高于关闭；persistent 关闭同区 ping p99 的异常值也保留。尚无重复对照、精确屏障成本或相对关闭的收益认证。`.153` persistent 基线在测试后的 `save-all flush` 达到 RCON 控制端 15 秒期限时失败，线程栈显示正在等待区块写盘，故不列入对照。现在只为该显式存档命令设 90 秒测试端预算，随后恢复原超时，不重发命令；游戏 keepalive/watchdog 阈值不变，新预算尚未完成该场景的实服复验。

按用户要求停止本轮专项前的回归队列。`.154` fixed 开启组延长恢复测试已走过 11 个操作窗口及 1 个重连周期，随后中断；原 runner 非零、服务端和客户端退出均为 0，不计该套通过。另一个池的延长恢复、`.154` 混合 Mod 操作和 payload 安装回归、旧外来观察者饥饿场景未运行。此前 `.152` 的对应证据范围不变；真实 Create/Jade 客户端、多小时长稳和旧 watchdog 环境断档根因仍未闭合。

下一会话从 `.154` 开始：先用相同 32 人夹具重复两池 on/off 对照，并将主线程准入、租约前后校验、提交到真实 join 和 join 后清理分别计时，再决定是否合并重复校验。较早 JFR 的热区见 `tmp/player-region-tick-p5-opt01/profile-summary.txt`；诊断录制并非正常对照。保留高尾延迟、写盘失败和中断原证据，无隔离测试进程继续运行。

## `.221` 调度延迟专项优化（2026-11-19）

在保持默认关闭、未知 Mod/监听器/回调完整串行、租约与物理 join 合同不变的前提下，完成纯 Java 调度与准入热路径专项优化，候选 `21.1.250.221-beta` 通过全部 **414 项 JUnit**（`*RegionPlayer*` + `*RegionPayload*`）及 32 真实 TCP 客户端 `verify_player_scale.py` 验收（含 `spread` / `same` / `boundary` 三分布及跨区攻击 `20→19`）。

主要改动：
- 新增专用 `RegionWorkerThread`，将 `RegionWorkerScope`、`RegionTickBatch`、`RegionEntityOwnershipScope`（原始 `long` 租约号 + 线程本地递增）、`RegionPlayerSpatialLease`、`RegionPlayerStateScope`、`RegionPlayerTickCoordinator.WORKER_INVOCATION`、`RegionWorkerPool.RuntimeCounters` 从高频 `ThreadLocal` 探针迁移为线程直访字段（非 Worker 线程保留回退路径）。
- `RegionWorkerPool.preWake` 在 `dispatchPlayerPrefix` 入口提前唤醒持久化 Worker，将 ~90 µs OS futex 唤醒与主线程准入/租约重叠；`RegionTaskBatch.Barrier.awaitCompletion()` 与 `StartGate.ready()` 引入有界自旋后回退阻塞。
- `RegionPlayerExtensionSnapshot` 缓存不可变 `firstUnknownProvider` 并对 37 个事件的实时 `ListenerList.getListeners()` 数组做引用恒等快速路径；`RegionPlayerAdmissionPlan` 的 19 个 `IntrinsicFields` 从反射 `Field` 升级为 `VarHandle` 并消除 `mainSupportingBlockPos.stream()`；`RegionPlayerSpatialPlan.isEyeInWall()` 直接查已捕获 chunk 替代 `BlockPos.betweenClosedStream`。
- `ServerPlayer.forgiaMatchesLocalTemplateResources` 将多趟容器/属性/同步器与局部对象引用校验合并为单趟零分配遍历，消除 `InventoryMenu` / `AbstractContainerMenu` 中 `.stream().allMatch(...)`；模板新增 O(1) 开放寻址 `ownedTable` 替代 `owns()` 线性扫描；单 Region 9-chunk 按 generation 缓存、单成员/单 section bucket 快路径、`Long2ObjectOpenHashMap` 替代 `pending` 装箱、复用未变 `owner.binding` 及批量 `finishWorkerPrefix`。

32 真实 TCP 客户端 `n32-spread`（10s 预热 / 30s 测量，全部通过 `verify_player_scale.py`）：

| 版本与配置 | Tick Mean (ms) | Tick p50 (ms) | Tick p99 (ms) | Ping p99 (ms) | 相对同版串行 / `.154` 开 |
| --- | ---: | ---: | ---: | ---: | --- |
| `.154` persistent 关（历史串行基线） | 5.896 | 5.695 | 9.103 | 11.117 | — |
| `.154` persistent 开（历史开启基线） | 58.297 | 55.900 | 90.793 | 314.110 | — |
| `.221` persistent 关（串行，2 轮均值） | 4.237 | 4.073 | 6.134 | 6.027 | 串行基线（较 `.154` 关降 28.1%） |
| `.221` persistent 开（2 Workers，r2 / 2 轮均值） | 4.223 / 4.332 | 4.082 / 4.182 | 5.670 / 6.114 | 6.179 / 6.290 | 与串行基本持平 / 较 `.154` 开快 ~13.5× |
| `.221` persistent 开（4 Workers） | **3.965** | **3.800** | 6.517 | 6.346 | **较同版串行降 6.4%（p50 降 6.7%）** / 较 `.154` 开快 ~14.7× |

`prefixTiming`（2 Workers，`n32-spread`）显示两阶段合计总调度开销由接手基线 `ab212` 的 **2.450 ms/tick** 降至 **0.918 ms/tick**（`workerWake` 186→42 µs、`barrierWake` 75→6 µs、`workerWrapper` 1.207→0.273 ms），其中 `CONNECTION` 单阶段壁钟（1.105–1.123 ms）已低于其串行正文（1.217–1.228 ms）。本次测试临时目录 `tmp/player-region-tick-latency02/` 已在验收记录后清理；多小时长稳、真实 Create/Jade 客户端与混合 Mod 回归范围仍沿用前文说明。
