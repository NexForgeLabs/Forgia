# MobSpawnController 适配器 0.3.0 —— 有序刷怪最终化

[English](mobspawncontroller-adapter.en.md) | 中文 · [适配器索引](README.zh.md)
源码：[`adapters/mobspawncontroller`](../../adapters/mobspawncontroller) · 协议：[刷怪准备](../api/natural-spawn-preparation.zh.md)
证据：[MOBSPAWNCONTROLLER-LOCAL-RESULTS](../../region-threading/MOBSPAWNCONTROLLER-LOCAL-RESULTS.md)、[MOBSPAWNCONTROLLER-ADAPTER-AUDIT](../../region-threading/MOBSPAWNCONTROLLER-ADAPTER-AUDIT.md)、[NEARBY-SPAWN-ADMISSION](../../region-threading/NEARBY-SPAWN-ADMISSION.md)

状态：**实验性，不建议用于生产。** 适配器 `0.3.0`，Minecraft **1.21.1**，MobSpawnController **1.2.3**。
需要包含有序刷怪最终化协议的核心。

> **默认不接管刷怪。** 实测 A/B 更慢，并出现数千次刷怪正文截断。默认保留 `DISABLE_SPAWNING`
> （自然刷怪完整串行）。`-Dforgia.mscOrderedSpawning=true` 仅供实验。

---

## 1. 何时使用 —— 先说诚实的结论

| 情况 | 建议 |
|---|---|
| 运行 MSC 1.2.3，想让自然刷怪上 worker | **不要。** 默认串行；没有已认证的加速 |
| 研究附近数量限制下的刷怪并行 | 在一次性测试实例里打开有序开关 |
| 整合包使用结构/光照/高度/季节条件，或 `spawn_multiplier != 1` | 保持串行；这些协议未实现 |
| 想知道适配器是否被准入 | 看 `ready`/`serialPlans` 与 reason 字符串 |

## 2. 为什么要有序最终化而不是普通并行刷怪

MSC 的价值在于在 `FinalizeSpawn` 强制附近数量限制与取消规则。如果候选在六个 worker 上独立选择并最终化，
两个候选可能都认为自己拿到了最后一个附近名额。有序最终化把**候选选择**留在 worker，把完整原生最终化
（`finalizeSpawn`、passenger admission、`afterSpawn`、cluster/group-limit 回调）放到服务器执行，同时所有
参与刷怪 worker 停驻。下一个候选随后看到真实的加入/取消结果。

## 3. 实测结果（启用前必读）

60 秒，同世界、同规则：

| 指标 | 有序并行 | 串行对照 |
|---|---:|---:|
| 中位 MSPT | 39.3 | 17.8 |
| 自然生成实体 | 210 | 283 |
| 截断刷怪 chunk | **5294** | 0 |
| 所有权违规 | 0 | 0 |

| 注意 | 说明 |
|---|---|
| 这个比值不是受控性能结论 | 有序轮次与 Gradle 测试重叠 |
| 截断已足以阻止验收 | 5294 次正文截断 + 4437 次外部 chunk 读拒绝 |
| 停点、主线程查询和任务派发都有真实成本 | 尚未定量归因 |

## 4. 支持矩阵

| 配置 / 操作 | 行为 |
|---|---|
| 没有 `natural-settings` 条目 | 冻结生成开关与属性映射；沿用原有 worker 路径 |
| natural 设置仅含 `chance`、`max_nearby`、`nearby_radius`、`spawn_types`、`excluded_spawn_types` | 仅在 `-Dforgia.mscOrderedSpawning=true` 时生效：候选选择与位置检查在 worker；原规则评估、finalize、真实加入及加入后计数回调在安全停点的主线程执行。默认仍串行 |
| 结构、光照、高度、季节等其他 natural 条件 | 在核心 cap 构造前保留完整原生串行计划 |
| `spawn_multiplier != 1` | 完整原生串行计划；沿用原权重/cap 协议 |
| 未验证的 `NaturalSpawner` Mixin | natural 设置保持串行，原因 `unverified_spawn_hooks` |
| prepared body 之外的非自然生成 | 原模组路径与实时规则 |
| 配置热改 | 已准入计划固定原 revision；后续计划读取新 revision |
| 空结构选择列表 | 立即返回 false；非空列表仍走原方法 |
| 异常 / 嵌套生成 | 恢复原模组 boost ThreadLocal，不遗留到下一个任务 |

这不是“整个模组都在 worker 上跑”。只要存在附近数量限制，最终规则判断与真实加入结果就必须是同一个有序操作。

## 5. 协议细节

| 步骤 | 位置 | 发生什么 |
|---|---|---|
| 1 | 事件总线 | 原 `FinalizeSpawn` 监听器保留在原位置；不复制 evaluator，不重发事件 |
| 2 | 计划 | `RULES`、natural 设置与属性映射冻结为计划级不可变视图 |
| 3 | 准备 | `NATURAL_SPAWN_SETTINGS` 数据源重定向；规则逻辑仍由原模组执行 |
| 4 | Worker | 候选选择循环执行；核心按真实 worker 组设置协作停点 |
| 5 | 屏障 | 只有实际开始的组登记为参与方 |
| 6 | 服务器 | 完整 finalize、passenger admission、`afterSpawn`、cluster/group-limit 回调 |
| 7 | 服务器 | 最终化前发布此前延期的 block/entity/tick 效果，使规则看到先前修改 |
| 8 | 服务器 | 同一随机数源与 prepared 数据续跑；不在 `SERVER_SERVICE` 阶段重新准备 |
| 9 | 服务器 | `NaturalSpawnBoost.ACTIVE_LEVEL` 在 worker 与续跑路径分别保存/恢复 |
| 10 | 全部 | 线程池拒绝、普通主线程请求、屏障关闭和刷怪异常各有独立处理 |

属性覆盖仍在原监听器位置执行一次。取消生成保留原行为与 RNG 消耗。跨组事务按提交顺序执行；
不承诺原版全局候选顺序 / RNG 序列逐位一致。

## 6. 启用方式

```toml
enableRegionThreadingSpawning = true
enableRegionThreadingWorkers = true
regionThreadingMode = "FIXED_3X3"
```

```
-Dforgia.mscOrderedSpawning=true
```

| 要求 | 说明 |
|---|---|
| 核心 | 必须包含有序刷怪最终化协议 |
| Claim | 适配器必须通过启动验证并调用 `claimNaturalSpawning` |
| claim 替代什么 | **仅**该模组的 `DISABLE_SPAWNING`；绝不解除 `DISABLE_ENGINE`（如 c2me）或管理员关闭的切片 |
| 验证 | 未知注入来源/版本回退串行 |
| 额外审查 | ByePregen 1.1.2.1（chunk cache/最近玩家）与 YUNG Better Nether Fortresses 1.21.1-NeoForge-3.1.5（要塞候选选择）为已审查调用点；未知来源回退 |

## 7. 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

| 输出 | 含义 | 不是 |
|---|---|---|
| `ready` / `serial` / `serialPlans` | 已准备单元 / 串行单元 / 被拒计划 | 已生成实体数 |
| 准备耗时 | 累计准备墙钟时间 | 每 tick 成本 |
| 最近原因 | `forgia_mobspawncontroller:world_dependent_rules`、`...:unverified_spawn_hooks` | 按坐标生成的标签 |
| `Ordered spawn finalization: transactions=..., serverMs=...` | 续跑次数（含拒绝/取消）与主线程正文时间 | 总成本（不含等待、派发与延期效果发布） |

刷怪 worker 打开但 `serialPlans` 持续增长且真实 worker 次数为零，说明仍在串行回退。整服计时必须来自
整 tick MSPT，而不是适配器计数。

## 8. 构建与测试

```sh
# 构建适配器 + 门面 + 安装器
./gradlew :mobspawncontroller-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar \
  -PforgiaVersion=21.1.250.48-beta --offline

# 用真实 1.2.3 jar 跑集成测试
./gradlew :mobspawncontroller-adapter:junitTest \
  -PforgiaVersion=21.1.250.48-beta \
  -PmobSpawnControllerJar=/absolute/path/mobspawncontroller-neoforge-1.21.1-1.2.3.jar --offline

# 负对照：移除适配器模组，预期恰好两个空列表用例失败
./gradlew ... -PmscAdapterNegativeControl=true --offline

# 负对照：注入未审查 Mixin，预期在计划开始前回退
./gradlew ... -PmscUnverifiedHookControl=true --offline
```

| 产物 | 路径 |
|---|---|
| 适配器 jar | `adapters/mobspawncontroller/build/libs/forgia-mobspawncontroller-adapter-0.3.0.jar` |
| 缺少 MSC jar | 集成任务明确跳过；不算通过 |
| 绝不打包 | 原 jar、反编译源码、API 副本、测试类 |

## 9. 对比：三种模式

| 属性 | 原版串行（默认） | 有序并行（可选） | 完全并行（未实现） |
|---|---|---|---|
| 候选选择 | 服务器 | Worker | Worker |
| 附近数量规则评估 | 服务器 | 服务器（安全停点） | Worker（会竞争） |
| 原生最终化 | 服务器 | 服务器，peer 停驻 | Worker |
| 共享最后名额正确性 | 保证 | 由顺序保证 | 没有配额协议就无法保证 |
| 实测 MSPT（60 秒夹具） | 17.8 | 39.3 | — |
| 截断正文 | 0 | 5294 | — |
| 状态 | **默认、安全** | 实验性，不推荐 | 范围之外 |

## 10. 限制

| 限制 | 后果 |
|---|---|
| 结构/光照/高度/季节条件 | 串行 |
| `spawn_multiplier != 1` | 串行 |
| 未验证的 `NaturalSpawner` 修改 | 串行 |
| chunk/region 边界截断 | 阻止行为验收 |
| 未建立性能收益 | 不要为吞吐启用 |
| 单元/假玩家测试 | 不是多人长跑或整合包认证 |

## 11. 在任何世界启用前的检查清单

- [ ] 只在一次性实例；生产保持默认串行路径。
- [ ] 核心包含有序最终化协议；适配器启动验证通过。
- [ ] `claimNaturalSpawning` 已验证；无 c2me/`DISABLE_ENGINE` 冲突。
- [ ] 所有 natural 设置都在支持字段子集内；倍率为 1。
- [ ] 检查 `serialPlans`/`serial` 计数；`unverified_spawn_hooks` 未反复出现。
- [ ] 测量整 tick MSPT 与截断计数，而不是适配器总量。
