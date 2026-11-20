# 实验性玩家 Tick

[English](player-ticks.en.md) | 中文 · [模块地图](README.zh.md)

玩家默认在服务器线程执行 tick。区域引擎、实体 worker 切片、受支持的布局和区域所有的实体存储均已启用时，可通过重启生效的 `enableRegionThreadingPlayerTicks` 开关调度已准入的玩家调用。32 人 GameTest 在夹具中启用了动态布局支持。[配置与诊断](observability.zh.md)

## 执行顺序

| 阶段 | 原始调用与执行线程 |
|---|---|
| `ENTITY` | 通过准入的实体 tick 可以在区域批次中执行。菜单同步和进度 tick 仍由原始 `ServerPlayer.tick()` 负责。 |
| `CONNECTION` | 服务器线程遍历连接并执行完整的 `Connection.tick()`，包括队列、监听器及 channel 尾部。 |
| `CONNECTION_BODY` | 区域 worker 可在原监听器调用点执行 `ServerPlayer.doTick()`。服务器线程等待正文返回，再继续位置校正、floating 检查、keepalive 和 idle 处理。拒绝时在原调用点串行执行。 |

阶段协调器、空间租约、玩家状态作用域和网络门覆盖 worker 执行及返回后的清理。等待期间，服务器线程通过现有批次同步发送已准入正文的血量、经验和冷却包。每次调用至多启动一次正文。正文启动后的异常在 join 后沿原连接异常处理路径传播。[监听器补丁](https://github.com/NexForgeLabs/Forgia/blob/main/patches/net/minecraft/server/network/ServerGamePacketListenerImpl.java.patch) · [批次实现](https://github.com/NexForgeLabs/Forgia/blob/main/src/main/java/net/neoforged/neoforge/server/threading/region/RegionTickBatch.java)

准入检查当前玩家和连接、背包与菜单对象、可达回调和进度触发器，以及已加载区块足迹。无法确定边界的移动、可达的未知回调、需要 tracking 广播的装备变化、活体接触目标、生命周期切换和其他不受支持的状态走串行路径。诊断会区分捕获拒绝与准备后、启动前取消的调用。

生产连接遍历在进入下一条连接前等待当前正文返回。批处理夹具验证了独立区域可以重叠、同一区域串行。受原遍历顺序约束，生产连接正文的跨区重叠仍未实现。

## 验证

在仓库根目录使用 JDK 21 执行。记录使用 JDK 21.0.13+9，三条命令的退出码均为 0。

```bash
export JAVA_TOOL_OPTIONS=-Dforgia.playerPrefixTiming=true
timeout 120s ./gradlew :neoforge:setup --offline --console=plain
timeout 180s ./gradlew :tests:junitTest --tests '*RegionPlayer*' --offline --console=plain
timeout 360s ./gradlew :tests:runFullParallelSoakGameTestServer -PfullSoakSeconds=20 --offline --console=plain
```

玩家 JUnit 全部 433 项通过。GameTest 测量结束时 32 人均在线，所有权违规、活动批次、状态 claim、网络待处理回调与 reader 均为零。连接阶段测试验证了处理发送队列、worker 单次调用、同步交回发包、位置校正、空闲断线及 channel flush 的顺序。[JUnit 夹具](https://github.com/NexForgeLabs/Forgia/blob/main/tests/src/junit/java/net/neoforged/neoforge/unittest/RegionPlayerSpatialLeaseTest.java)

| 阶段 | Worker | 服务器线程 | Worker 覆盖率 |
|---|---:|---:|---:|
| `ENTITY` | 328 | 20 | 94.3% |
| `CONNECTION` | 0 | 576 | 0% |
| `CONNECTION_BODY` | 9 | 567 | 1.6% |

历史 ENTITY 覆盖率为 71.8–71.9%。本轮 17 个测量 tick 的平均 MSPT 为 528.9 ms，早期两轮分别为 167.3 和 232.8 ms。正文阶段累计提交和等待耗时 29.1 ms，其中 worker 正文累计 17.1 ms。退出时 Overworld 区块 flush 耗时 158.4 秒，发生在测量窗口之外。MSPT 升高的原因尚未确定。[机器可读回执](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/evidence/player-connection-body/summary.json)

使用 `/neoforge regionthreading status players` 查看各阶段计数、拒绝详情和清理状态。启用 JVM 计时开关时，命令还会显示各阶段耗时。正文计数应与外层连接计数分别读取。
