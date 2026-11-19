# forgia-compat

[English](README.md) | 中文

一个独立的 NeoForge 模组，用于帮助整合包在 Forgia 的并行区块 tick 下稳定运行。

## 缘由

Forgia 在 Worker 线程上执行区域的区块随机 tick。如果模组通过共享且未同步的数据结构处理这些 tick 触发的事件，就会发生竞态。在一个 178 个模组的整合包中首次观测到的实例为 `CommonHooks.handleBlockDrops` 中 `BlockDropsEvent.getDrops()` 返回 `null`。后续引擎审计在无 Connector 的情况下也复现了同一问题：`Block.capturedDrops` 是一个被并发和嵌套战利品计算共享的静态单例列表。引擎修复已将其替换为线程封闭、支持嵌套的捕获作用域与异常清理；参见[掉落物捕获审计](../../region-threading/BLOCK-DROP-CAPTURE.md)。

## 作用

该 jar 在区域 Worker 运行选定事件接缝时加锁，并提供历史空掉落列表兜底，防止抛出异常打断区块 tick。**这只是崩溃遏制，而非物品守恒证明**。该补丁在普通 NeoForge 核心上保持静默。

当前覆盖：

| 接缝 | 补丁 | 原因 |
|---|---|---|
| `CommonHooks.handleBlockDrops` | 在单一锁保护下派发，空掉落列表转为空集合 | 针对历史空掉落崩溃的兜底遏制；共享捕获问题需依赖引擎侧修复 |

## 构建与验证

```bash
./build.sh [install-dir] [out-jar]
```

`install-dir` 默认为 `/home/yc/Minecraft/forgia-vanilla`，仅作为编译 classpath（无需网络与 Gradle）。添加 `-Dmixin.debug.verbose=true -Dmixin.debug.export=true` 可通过 `logs/latest.log` 与 `javap` 验证 Mixin 是否生效。

添加新接缝前，请先定位实际的共享数据或所有权违规；若可变状态在事件派发前已创建，仅对派发加锁无法保护它。
