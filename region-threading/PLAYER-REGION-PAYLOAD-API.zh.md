# 显式声明的服务端 PLAY payload worker API

2026-11-18，用户要求先提供开发者可声明的自定义包 worker API，再继续 P5。P4 基线已提交并推送 `9b4859bb830f3c0beb0dc2f7cd44eee32699dbdd`，产物 `.146`；本增量进行中，P5 尚未开始。保留所有旧证据，不操作现用服。

## 合同

新增 `PayloadRegistrar.playToServerRegion(type, codec, Payload.class, handler)`，只注册一个精确类、精确类型和精确处理器的服务端 PLAY 包。现有 MAIN 默认、显式 NETWORK、客户端/配置态、`enqueueWork` 不变。没有新增全局自动开关，依赖默认关闭的玩家 worker 功能及其前置条件。

这是开发者对**该处理器及其调用到的全部回调**的线程安全承诺，不是引擎自动证明任意 Mod 安全，也不是放行整个 Mod。原 ENTITY/CONNECTION、原版包与未声明自定义包仍遵守未知扩展整次串行规则；oracle 无特批。

沿用原服务器队列位置、唯一原生池、region admission、空间/state lease、owner 身份、网络门和真实 join。每个处理器物理返回后原服务器任务才能返回，后续队列任务不能越过它。此版本没有跨包并行吞吐承诺。

精确声明只替换该次处理器的未知扩展拒绝，不放宽玩家/菜单/物品/地图/计分板/进度/加载/边界/连接状态准入。未满足条件或未开始的拒绝走完整 MAIN fallback；开始后异常回到原 MAIN payload 错误边界，不能重放。掉线、重配置和旧玩家实例在开始前取消，引用由原有有界 envelope 释放。

`IRegionPayloadContext` 仅在同步处理器调用内有效：`player()`、`isRegionWorker()`、原连接 `reply`、`enqueueWork(Consumer<IPayloadContext>)` 和排 MAIN 的 `disconnect`。处理器不得持有上下文/玩家到调用结束之后，不得任意跨玩家/跨区/全局状态访问，不得直接传送/换维度/换菜单、触发未知生命周期操作，不能等待 MAIN future。需要这些能力时调用 `enqueueWork`；其异步任务使用原 MAIN 上下文，worker 不会泵任意主线程任务。范围检查失败/异常发生后的副作用不回滚。

解码结果应不可变或由该调用独占，不能从 codec 带入未声明的共享可变对象；包含引用计数 buffer 时，开发者须明确管理其所有权。队列只对 payload 本身的 `ReferenceCounted` 合同作 retain/release，不递归保活任意对象图。特别是后续 MAIN 任务捕获的数据，需要自己的保活/释放合同，Java lambda 不会自动延长底层 buffer 生命周期。

同步包处理器按原连接队列顺序完成；`enqueueWork` 保留原线程合同：MAIN fallback 内联，worker 来源排到原 MAIN 队列。因此后一个 MAIN fallback 的内联任务可以先于前一个 worker 已排队的 MAIN 任务执行；worker 来源的 MAIN 任务之间仍按提交序执行。需要同步 MAIN 顺序/返回值的操作应维持原 `playToServer` 默认 MAIN 注册，不能靠等待 worker future 实现。

独立网络兼容协议：该类声明调用冻结期间，原网络回调保序暂存，join 后回到**原 Connection 的 Netty event loop**；不能迁到服务器线程来运行显式 NETWORK handler。待恢复前缀尚未返回时不接纳新的 worker 调用。保留原有有界 4096 队列和 retain/取消边界。非声明玩家阶段继续原合同。

## 开发者示例

```java
event.registrar("1").playToServerRegion(
        MyPayload.TYPE, MyPayload.STREAM_CODEC, MyPayload.class,
        (payload, context) -> {
            // 已声明且经过审计的 owner-local 处理；可能在 MAIN fallback 执行。
            ServerPlayer sender = context.player();
            updateOwnerLocalState(sender, payload);
            // 全局/转换/未知操作回原 MAIN。不得在此 join/get 此 future。
            context.enqueueWork(main -> performGlobalOperation(main.player()));
        });
```

## 当前验证状态

首版生产/测试源码编译通过：`tmp/player-region-payload-api01/compile-command/result.json`，实际 exit=0。正在增加两池真实原引擎/原生池、精确注册反例、取消/保序/NETTY 恢复测试与真实 TCP 安装验证；尚未整体验收，不将 `.146` 旧通过当作本增量认证。

增量记录：`.148` 676 项/22 类受影响 JUnit 通过，首组 fixed/on 真实 TCP 验证 22 次声明包正文（21 worker、1 次真实 NETWORK_CALLBACKS 准入拒绝后 MAIN）、42 次普通 MAIN、50 次原 Netty NETWORK，两个玩家总 32 钻石/14 XP 守恒。一次故意 transfer 后异常没有重放，真实断线旧包未执行，重连使用新实例。首次独立 verdict 因验证器只预期一条错误日志而失败；原执行器和原 payload guard 各打印一次同一故障，随后保存独立修正版 verdict，不覆盖失败。`.149` 将 worker 排 MAIN 的任务纳入原有取消/有界 envelope，全部受影响测试通过。

审计新增 post-terminal retain 反例：网络队列已满并排好终止任务，先执行一条前缀使 pending 低于 4096 后再到达包，首版新增 terminal 检查位于 retain 之后，造成未挂入 envelope 的引用多 retain 一次。原 fail-first JUnit/日志/产物保留 `terminal-retain-counterexample/`，修正为 retain 前拒绝；`.150` 完整回归进行中。重入原网络回调的同连接同步操作也保持原调用语义，整段真实执行计数继续阻止 worker freeze。当前仍未整体验收，P5 未开始。

`.150` 683 项前的回归中，旧 P4 routes 报出真实 worker 覆盖回退：将所有 `executing` 网络门回调一概挡住，误拒绝了原服务器队列 replay 内的普通原版包。原 `compat2-routes`/`legacy-replay-counterexample` 的失败保留，后者先失败再修复。`.151` 仅用真实 Netty replay 的 pending/执行前缀阻止新冻结；legacy server replay 保持原协议。683 项/22 类 JUnit 和三个选定 GameTest 全通过，37 份生产源码冻结，产物安装器 SHA256 为 `05eec0287a0866cd0cd5607640f77dc9a5eb7afad72a48c47b5a03b0e801ee91`。

`.151` 四组声明 API 实际安装/正常 TCP 包/独立验收通过：每组 22 次测量正文、42 次默认 MAIN、50 次原 Netty NETWORK，两个 on 池分别 21/18 次实际 worker，其余真实 NETWORK_CALLBACKS 回主线程；off 两组 worker=0。四组总 32 钻石/14 XP、保存 NBT、原客户端精确槽位/回复/MAIN反馈一致；故意转移后抛异常不重放；掉线已排包不执行、重连新实例；两池各九项证据篡改负控制通过。MAIN 回流的早先“整体 completion 保序”验收假设不符合已声明的 inline/queued 语义，原失败和 revised verdict 分别保留，最终 verifier 改为逐次核对内联边界、worker 返回后执行及 worker 来源的提交序。

兼容验收进行中：`compat151-functional` 的两种 on 模式 schema3 72/schema4 90 及负控制通过，off 两组继续。`.151` 首轮旧 routes 的三个原移动、原 confirm、原 PICKUP 都已在 worker 正确执行；自动 post-confirm 落地包在 JDI 开销期间碰到原 keepalive，以 NETWORK_TRANSITION 合法完整串行，旧 verifier 不接受该夹具窗口。失败保留 `compat151-routes`，未放宽原 worker 断言/生产门；新 runner 和独立 wire 核对只等待两名客户端各完成一次真实原 keepalive 挑战/回复再 arm JDI，恢复矩阵 `compat151-routes-recovery01` 首组已通过。此前 `.150` functional 因本地 oracle jar 旧路径已不存在而在安装前失败；改为使用仍保留且 hash 完全一致的原 `.146` oracle。被替代的 `.150` coordinator/wrapper 终止和 unknown exit 单列，已开始的隔离 child runner 允许自行返回，均不混入最终认证。

接续中断后（2026-11-18）：`.151` routes 恢复矩阵四模式全通过；functional 已通过七个语料，最后 resources/off-persistent 安装器调用 Mojang 映射端点遇 DNS `UnknownHostException`；async/on-persistent 实际语料退出成功，但原独立 verifier 因 authlib 公钥请求的 `api.minecraftservices.com` DNS 异常堆栈严格拒绝。两次失败及所有退出/日志保留，不放宽原日志门；未发现活跃残留测试进程。

另新增真实 `MinecraftServer` executor 停止分支反例：停止后的 `submit` 因 `scheduleExecutables=false` 在调用者内联，worker-origin MAIN 逃逸任务可能在 worker 上执行。`stopped-main-escape-counterexample` 先失败并保存 XML；`.152` 统一使用 `CompletableFuture.runAsync(..., server::executeIfPossible)`，沿原服务器队列派发且停止时原拒绝，异常只经原 payload guard 记录，不重放。684 项/22 类完整受影响 JUnit 和三个选定 GameTest 全通过。`.152` 37 份生产源码冻结，安装器 SHA256 `29d5f44f4d269d7488dc8e5232fb360c316a368e37d615141302ac2912747c19`；manifest 显式审核且保留所有旧 hash gate。全新同产物四模式 API、完整 schema3/4、原 P4 routes/async/queue、双入口 smoke、MAIN/NETWORK 和共享箱子补充矩阵验收进行中。规模/长期恢复/性能 P5 尚未开始。

## 最终补充验收（.152；P5 前收口）

固定产物 `21.1.250.152-beta` 安装器 SHA256 `29d5f44f4d269d7488dc8e5232fb360c316a368e37d615141302ac2912747c19`，审核 manifest SHA256 `fca333dd8eaffdee7d41500e70351694fafba3e8a16b9db76f883601812d02eb`。统一回执 `tmp/player-region-payload-api01/acceptance152-01/receipt.json`、独立审计 `tmp/player-region-payload-api01/acceptance152-01/final-audit.json`，索引闭合 2916 个输入，37 份生产源码冻结。原 12 份 P4 Minecraft patch、生成源码及严格 patch archive 的 hash 未变；不是重新宣称一次未执行的 apply。正式 universal 只含新增三个 API 类，不含 fixture/oracle。

684 项/22 类受影响 JUnit 零失败/错误/跳过，三个选定 GameTest 通过；原测试中的明确 boom 与声明处理器故意异常分别核对，无未知后台异常。34 个同产物独立安装、142 条实际成功命令、208 项证明篡改负控制和最终重新计算的独立 verdict 全通过。

四模式声明 payload：每组测量窗口 22 次原 handler、42 次普通 MAIN 和 50 次显式 NETWORK；fixed/on 为 20/22 worker、persistent/on 为 19/22，剩余分别两/三次真实 NETWORK_CALLBACKS 在正文前拒绝后完整 MAIN，off 两组 worker=0。两池各九项新 API proof 负控制通过。原接收 ordinal 与独立解码 wire 对应、worker 的确位于原入队 handler 正文，MAIN 任务在 worker 真实返回后执行；MAIN fallback 内联语义保持。每组 32 钻石/14 XP、客户端精确槽位/回复/MAIN反馈与原保存 NBT 均一致，transfer 后原异常不重放。真实 worker 内断线后，旧已排包未执行，重连是新实例，退出后 queue/retains/owner/state claims 均清零。

同一 `.152` 完整 schema3 144/schema4 180（含跨区 PvP/实体、方块/实体方块、共享容器、拾取/XP、死亡重生等原场景）通过，原 `.115` hash gate 和历史 manifest 保留，oracle 全部 UNKNOWN_MOD/worker=0。原 P4 routes/async 四模式及两池 queue、完整 world/Connection smoke 四模式、原 MAIN/NETWORK 四模式、原共享单箱菜单/quickMove 四模式通过；每个 NETWORK 合同语料 500 个原 Netty handler/500 个 MAIN continuation，错序错线程零。共享箱子保留原菜单身份、事件/客户端同步和 11 钻石守恒。没有以 oracle 允许自身进入 worker，也没有通过删减游戏正文获得准入。

所有中间失败/被替代产物/旧世界保留：测试编译错误、原路径已不存在的 oracle、post-terminal retain、legacy replay 误拒绝、停止后 submit 内联、旧 verifier 对双日志及 inline/queued 的错误假设、JDI keepalive 窗口、外部 DNS 与调度器重新分流都分别记录。`.152` 最终接受只引用原始成功命令和重新核对的证据；被终止的协调器只用于分流，没有终止其游戏测试子进程，wrapper 失败/unknown 不冒充成功。此前 P4 原 persistent foreign-starvation stall 仍原因未明且 failed/excluded；本增量未宣称修复它。

边界：开发者声明包含该 handler 可达的全部 Mod 回调/变换安全承诺，不是任意 Mod 的自动安全证明。仅服务端 PLAY、同一个玩家局部有界状态；不支持任意外来玩家/全局/远距空间/同步生命周期调用。主线程同步返回值需求保持普通 MAIN 注册，worker 不等待 MAIN future；异步捕获 reference-counted 数据须独立管理 buffer 生命周期。该增量复验上述同产物兼容范围，没有重跑每一个历史 P2/P3 故障/生命周期语料，也没有 8/32 客户端、长期运行或性能认证。默认关闭、未知未声明扩展整次串行、开始后异常不重放、物理 join 后清理不变。P5 未开始，整体 partial。本增量未 commit/push/deploy，无现用服操作，无 reset/clean 或证据删除。
