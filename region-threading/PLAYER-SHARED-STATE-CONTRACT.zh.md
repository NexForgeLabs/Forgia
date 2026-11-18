声明 payload API 增量（2026-11-18，`.152`）：显式 `playToServerRegion` 只对精确注册的单个服务端 PLAY 处理器及其传递回调提供开发者承诺，沿用原 admission/空间-state lease/池/队列位置和真实 join。其他 Mod 玩家 Tick、原版/未声明包仍使用下文未知扩展拒绝；默认 MAIN 和显式 NETWORK 不变。MAIN 逃逸使用原 executeIfPossible，worker 返回后执行且退役取消/停止拒绝。完整合同与验证范围见 [API 记录](PLAYER-REGION-PAYLOAD-API.zh.md)。P5 未开始；以下旧阶段合同保留历史。

P4最终`.146`按当前逐类有界合同完成统一验收，停止在P5前。完整结果/约束与失败见[P4记录](PLAYER-REGION-TICK-P4.zh.md)最后收口，机器回执`tmp/player-region-tick-p4-final04/acceptance-01/receipt.json`。无碰撞有限移动、匹配已生效目标确认、原材料库存/共享单箱及无签名低spam原FutureChain完成具备owner协议；所有未闭合路径完整原串行。默认false、未知扩展freeze前串行、MAIN/NETWORK原合同、无开始后重放与真实join后清理保持。下文进行中/旧产物措辞保留历史。

---

当前P4 `.144`候选补足：有限原移动/确认/材料库存/共享单箱点击及原过滤聊天completion复用当前spatial/state lease。原ChunkMap tracking和全局聊天广播只在worker同步暂停时由原publisher执行精确一次服务，不泵通用队列；未知可达事件监听器freeze前整次串行。原任务/聊天封装取消清空引用、retains一次释放、shutdown等实际thaw/原回调返回。详细边界见[production合同](PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md)开头及[P4记录](PLAYER-REGION-TICK-P4.zh.md)末尾。644 JUnit/3选定GameTest通过，完整同产物矩阵进行中，P5未开始。以下P3/P2状态保留历史。

---

# 玩家共享状态前置协议

P3最终`.134`已按当前逐类有界合同完成统一验收，P4/P5未开始；下文进行中/旧产物措辞保留历史。最新收口与限制见[当前P3实施记录](PLAYER-REGION-TICK-P3.zh.md)末尾及`tmp/player-region-tick-p3-final02/acceptance-01/receipt.json`。

P3候选`.134`已逐类补充原普通材料库存、XP末次存储移除、共享单箱子、非显示计分板、真实背包地图及一次非致命自饥饿伤害；统一安装服验收进行中，见[当前production合同](PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md)与[P3记录](PLAYER-REGION-TICK-P3.zh.md)。这些新增计划加入同一state捕获/claim/consume链；显式共享对象参与原冲突组，同lease内双方明确声明才可别名，普通本地别名仍整组拒绝。已发生的XP/物品原效果对应的storage回调在真实join后且原publisher fence有效时恰一次提交，即使stop-new-admission；不是延后同步XP/事件/返回值。地图原地换ID在未开始时拒绝。下文`.127`和P2“空库存/空objective/不支持末次”等保留历史，由当前production合同覆盖。


P3首个增量（`.127`）：预算仍为单次完整调用最多7XP，候选集合现在按当前完整触碰框精确复查，拒绝捕获后新进入的球。production仅允许Count≥2且原观察连接只有拾取者自身的精确原球；原修补/XP/计数/发包正文保持，Count1/单球/任意外来回调和一般物品仍完整串行。此增量不扩展共享容器或一般库存；完整验收与未覆盖项见PLAYER-REGION-TICK-P3.zh.md末尾。


本合同交付 `RegionPlayerStatePlan/Scope/Hooks`，承接[空间集合合同](PLAYER-SPATIAL-FOOTPRINT-CONTRACT.zh.md)。它可由后续准入与调度直接调用；当前接续 TODO 2 已通过[两阶段 production 准入](PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md)将完整分支检查绑定到本状态计划的捕获/复查和开始前消费。完整玩家 worker 已由 P2 首版调度调用本协议，实际两池证据见[实施报告](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md)最新记录。默认 false 的原开关、唯一 engine/pool/lease/batch/ledger 及 Player.class 整骑乘链保护均保留。

## 调用顺序

1. 在原服务器静止窗口分别为 ENTITY / CONNECTION 捕获 `engine.capturePlayerSpatialPlan`，再调用 `engine.capturePlayerStatePlan(spatial)`。`Capture` 明确返回计划或串行原因。不同阶段重新捕获，不复用旧状态。完整生产分支使用 `engine.capturePlayerAdmissionPlan(player, phase)` 统一完成这些检查，随后传递其 `spatial()/state()`；单独状态计划仅证明本节前置协议。
2. 在原发布中父 batch 下 `reservePlayerSpatial`，再 `lease.bindPlayerState(plans)`。必须为该 lease 的每个空间计划绑定恰好一个状态计划。绑定前再次验证全部状态；原对象别名冲突使整个组返回 `SHARED_PLAYER_RESOURCE`，不留下部分 claim。`conflictsWith` 可供前缀规划使用。
3. 发布线程可用公开 `plan.revalidate()` 在派发前再审。原 worker 在 `lease.open()` 内、**原玩家调用记账进入 STARTED 和正文副作用之前**调用 `lease.openPlayerState(plan)`。它重新核对对象、监听器、奖励、菜单、scoreboard revision、XP 和几何，并且只消费一次计划。开 scope 失败只表示尚未开始的调用无权限；调度器必须区分正文前回退与开始后失败。
4. scope 内执行原始完整方法；scope 本身不调用 tick、不伪计 worker。用 try-with-resources 先退出 state，再退出 spatial；真实 worker 返回/join 后由发布线程释放 lease 和资源 claim。不得在 state scope 存活时关闭 spatial 或父 batch，不得开始后重放。

原 `PlayerAdvancements` 的 generation 与原 phase/lifecycle ticket 都是必需条件。此处的状态 snapshot 不替代协调器会话、当前 PlayerList 实例、ledger publication、独立生命周期代次及网络互斥检查。

## 已闭合的状态分支

- 玩家本地原对象包括 advancements/listener storage、stats、attributes 及全部已物化实例、food、cooldowns、inventory、gameMode、warden tracker、listener/Connection，以及原 InventoryMenu 的 backing containers、slots、同步器/监听器和可变跟踪集合。按对象身份捕获和 claim；不创建替身对象执行。槽位替换、容器别名或集合/实例改变会使未开始计划失效。
- 菜单仅接受当前原 InventoryMenu、本玩家原 Inventory、空 crafting/result/carried、46 个原菜单槽位、槽位指向本地容器，以及原玩家精确 listener/synchronizer。额外监听器、数据槽、进行中的 quickcraft、残留远端物品或共享菜单完整串行。这里的结构检查仍需后续 callback/变换准入配合，不能据此允许任意 Slot/Mixin 正文。
- 原 SimpleCriterionTrigger 使用每个原 PlayerAdvancements 的监听器存储；snapshot 覆盖全部 trigger 的原 set 引用、成员身份、revision 与 lifecycle generation。API 注册/移除和直接修改原 HashSet 都会在下一次未开始检查被发现。snapshot 仅验证，不用于执行、排序或替换原集合。
- TICK 只接受没有玩家谓词的原 PlayerTrigger.TriggerInstance；其 advancement 的全部 criteria 也必须属于该分支。display/未知条件完整串行。连接阶段要求 LOCATION 和 ENTER_BLOCK 注册均为空；空 ENTER_BLOCK 原触发器仍由碰撞正文正常调用并同步返回，不跳过原碰撞或事件。不依赖捕获时 tickCount 的模 20 值；doTick 内 tickCount 会先增加。fall/levitation/vehicle 的可选状态必须为空，其他注册不删除。
- 奖励只接受空 loot/recipes/function、0–7 XP 的原对象。捕获 advancement criteria 的身份、深层 requirements 内容和奖励对象，并复查可变列表；award 前再次核对。直接调用未捕获 reward 无权限。未知函数奖励在任何原进度/XP效果之前返回 `GLOBAL_REWARD`，随后原串行路径仍同步完成进度、XP、函数，既不延迟也不重放。
- 玩家起始 level 0–3、total XP 0–27、有限且在 [0,1) 的 progress；附近原 orb 的值和可达奖励之和不得超过 7。scope 内 XP 总增量上限为 7，避免触达 level 5 的全局升级声音分支；item pickup 回调尚未闭合，正文前返回 `ITEM_PICKUP_CALLBACKS`。经验事件与 orb 的其他扩展点仍需 callback 准入。
- 发布线程预热五个原必经 CUSTOM stat handle 和全部可用属性实例/值；worker 的 StatType 查询只返回原 handle，未知缓存查询拒绝。所有对应 stat/health/food/air/armor/experience/level objective 必须为空。scoreboard 原 add/removeObjective 使 revision 单调变化；绑定后变化也拒绝。scope 内空 objective 查询保持原零次 consumer 调用，不排队写入或创建分数。objective 创建/移除仍禁止 native worker。

原 criteria 筛选、HashSet 迭代/重入异常、Listener.run、award/revoke、进度事件、reward.grant、giveExperiencePoints、stats、food/cooldown 和菜单同步正文均保留；异常继续抛出原对象。正常 award 移除监听器属于开始后的合法原效果，不会被捕获时 revision 错误阻止。scope 不泵任意服务器服务任务，无对象全局 guard 继续拒绝。

## 需要后续共同满足的条件

本状态合同没有为第三方事件/item/effect/attachment/Mixin 或任何未知同步回调授予权限。[production 合同](PLAYER-PRODUCTION-ADMISSION-CONTRACT.zh.md)已实现明确的 base 环境、空物品/效果、本地菜单和简单奖励受限分支；它在本状态协议之上拒绝未知路径，并将 SynchedEntityData、玩家 RNG、CombatTracker、chunkSender 纳入同一对象 claim。原 `level.random` 路由在 state scope 内解析为该玩家 RNG。完整世界/连接原位 worker 派发已由 P2 首版实现；下述历史 67/201 项是状态模块自身的组件证据，不能替代最新安装服结果。完整 ServerPlayer.tick 父调用和完整 Connection.tick/listener/doTick 前后顺序保持，调度使用原 phase ticket、原 pool/barrier 与真实 join，最新定向验收和剩余矩阵见实施报告。

本增量验证与命令/真实退出码见[实施报告](PLAYER-REGION-TICK-IMPLEMENTATION.zh.md)文末。`tmp/player-region-tick-continue02/state-final/` 保留当前 67 项聚焦测试及 201 项受影响回归（计数重叠，均零失败/错误/跳过），覆盖原 native fixed/persistent 池的 owner scope、同步奖励、失效检查、别名拒绝与资源释放。`tmp/player-region-tick-continue02/state-closeout-02/receipt.json` 记录收尾核对、源码哈希与已有测试证据；本次另行通过六个根 Java、两个相关测试类的格式检查及 24 个持久 patch/generated 的逐字节校验。历史网络基线未重跑；本段为原状态模块增量完成时的证据，完整玩家 worker 的当前次数以最新 P2 记录为准。
