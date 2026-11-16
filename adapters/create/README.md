# Forgia Create Adapter 0.3.0

实验性独立适配模组，限定 **Minecraft 1.21.1 + Create 6.0.10**，需要提供
`BLOCK_ENTITY_EXECUTION_ADAPTERS` 的 Forgia 核心，本轮构建为 `21.1.250.108-beta`。

0.3.0 改用公开的 [BlockEntityExecutionAdapter API](../../docs/api/block-entity-execution.zh.md)，
将已审计的完整原始 tick 交给核心的 `RESOURCE_SCOPED` 路径。
**资源独立的机器可以在同一 chunk/区域内同时执行**；canonical 阶段超过 2048 项时改用有界窗口，
不会仅因阶段大小而整体串行。资源、更新和生命周期预算继续保留。

核心不依赖 Create 类或版本；原 Create jar 保留，adapter 不打包上游实现，不创建线程池或替换业务 tick。

## 当前覆盖

| 对象 | 原始 canonical tick | 完整串行边界 |
|---|---|---|
| 轴、小/大齿轮、包覆传动件、变速箱 | 已初始化、本地闭合的稳态分支，支持同区域资源并行 | 初始化、速度传播、network dirty、周期校验、粒子回调、未知 behaviour/实现 |
| 离合器、动力转向器 | 同上 | 红石切换和源更新导致的拓扑分支 |
| 水车、大水车、磨石 | canonical 路径保持串行 | 历史显式驱动测试保留，不等于当前加工分支已进入资源 Scope |
| 传送带、漏斗、工作盆、搅拌器、压块机、流体与存储网络 | 串行 | 共享库存/controller、配方、回调及外部写入者尚未闭合 |
| 移动装置、Deployer、列车 | 串行 | 需要各自的实体/世界操作协议 |

`inspect` 列出 source、behaviour map、effect handler 和每个 behaviour 的真实存储身份。
准备和完整重审在协调线程完成；worker 使用原 wrapper 的已捕获上下文、原 Ticket 和实际资源 Lease。
未知串行 fence 完成后，后缀重新检查。运行过部分正文后抛异常不会在主线程重放。

## 安装与配置

将 `forgia-create-adapter-0.3.0.jar` 与原 `create-1.21.1-6.0.10.jar` 一起放入测试实例 `mods/`，
配合上述新核心。API 兼容不是只看 NeoForge 基础版本号，adapter 启动时会检查新 capability。

沿用管理员的核心开关：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 8
```

继续遵守 interior、server-only、兼容限制和预算。新 adapter 的资源声明与 Scope 是其必需合同；
不依赖 vanilla Furnace Waves 的可选资源开关，也不需要 `admit_block_entities` 或 VM agent。
配置默认值没有变化。

## 构建与验证

使用 JDK 21，从仓库根目录运行，原 Create jar 只从本地提供：

```sh
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.108-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar

./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.108-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:junitTest
```

`-PcreateAdapterPersistentWorkers=true` 选择 persistent pool。
`-PcreateAdapterFurnaceWaves=true -PcreateAdapterResourceOwnership=true` 验证与现有原版资源波次共存。
`-PcreateAdapterUnverifiedHookControl=true` 在独立 JVM 注入未知 Mixin，验证完整串行回退。

测试直接使用真实 Create 类、原始 SmartBlockEntityTicker、Core wrapper 和实际 Level 循环。
新增同区域原始 tick 同时执行及 2050 个真实 Create ticker 的大阶段验证。
world/chunk 服务仍有测试替身；这些测试不能代替完整整合包吞吐、多人和长时 soak。

## 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

审计完整性与实际 worker completions 分开报告。`checks` 包含规划和执行前重审；
成功返回的原始 worker tick 才计 completion。使用相同窗口的 MSPT、CPU 和分配数据判断性能。

[0.2.0 说明](../../docs/adapters/create-adapter.zh.md)记录旧区域所有者路径及其历史验证，
其中的整阶段 2048 项门槛不适用于当前有界窗口核心。
