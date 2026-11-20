# Create 适配器 0.2.0 —— 受审计的稳态动力 tick

> 本页保留 0.2.0 的历史说明。当前 0.3.0 已迁移到 execution adapter，支持同区域资源并行和大阶段窗口；以[当前源码说明](https://github.com/NexForgeLabs/Forgia/blob/main/adapters/create/README.md)为准。

[English](create-adapter.en.md) | 中文 · [适配器索引](README.zh.md)
源码：[`adapters/create`](../../adapters/create) · 协议：[`AUDITED_BLOCK_ENTITY_TICKS`](../api/block-entity-ticks.zh.md)
证据：[CREATE-ADAPTER-VALIDATION](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CREATE-ADAPTER-VALIDATION.md)、[CREATE-OPTIMIZATION-PLAN](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/CREATE-OPTIMIZATION-PLAN.md)

状态：**实验性。** 适配器 `0.2.0`，Minecraft **1.21.1**，Create **6.0.10**，核心需提供
`AUDITED_BLOCK_ENTITY_TICKS`（本轮基于 `21.1.250.107-beta` 构建）。必须保留原 Create jar，且绝不打包进适配器。

---

## 1. 何时使用

| 适用 | 原因 |
|---|---|
| 运行 Create 6.0.10，希望稳态动力方块实体进入 region worker | 这些类型的整条原 ticker 分支已审计为本地、无回调 |
| 基地以轴、齿轮、变速箱、离合器为主，且 BE 阶段**较小** | canonical 阶段必须 ≤2048 条目 |
| 能接受水车、磨石、传送带、漏斗、工作盆、移动装置保持串行 | 它们的完整合同尚未闭合 |
| 需要针对已覆盖类型的 `nativeCompleted` 计数 | 诊断按适配器隔离且有界 |

| 不要用 | 原因 |
|---|---|
| 整合包对 Create 添加了未知 Mixin/coremod | 未知变换 → 完整串行回退 |
| BE 阶段超过 2048 条目 | 无论适配器如何，核心都让整个阶段串行 |
| 期待移动装置、列车、Deployer 或传送带并行 | 0.2.0 范围之外 |
| 需要已认证的多人吞吐量 | 本地测试不是服务器认证 |

## 2. 为什么需要这个适配器

Create 的动力网络是共享可变状态（`TorquePropagator`、behaviour 映射、network dirty 标记）。把所有 Create
都放 worker 会与这些状态竞争。适配器改为只审计“初始化后整条原 ticker 分支是本地”的子集：它不读取动力网
注册表、不发布回调，只触碰自身方块实体和已审查的 Accessor 字段。子集之外全部是完整串行 fence，核心随后
重新检查后缀。

## 3. 覆盖矩阵

| 对象 | 当前 canonical Level 路径 | 完整串行边界 |
|---|---|---|
| `create:simple_kinetic`（轴、小/大齿轮） | 初始化后本地原 tick，无外部回调 | 初始化、速度传播、network dirty、周期校验、粒子发布、未知 behaviour/实现 |
| `create:encased_shaft`、`encased_cogwheel`、`encased_large_cogwheel`、`gearbox` | 同上 | 同上 |
| `create:clutch`、`create:gearshift` | **0.2.0 新增：** 稳态原 tick | 红石切换/源更新引起的拓扑分支，以及所有未闭合状态 |
| `create:water_wheel`、`large_water_wheel`、`millstone` | 仍完整串行 | 保留历史 v1 明确驱动的检查/测试；尚无完整 canonical 正文合同 |
| 传送带、漏斗、工作盆、搅拌器、压块机、流体/存储网络 | 完整串行 | 共享库存/controller、配方、回调、外部写入者 |
| 移动装置实体、Deployer USE/PUNCH、列车 | 完整串行 | 世界操作、碰撞、乘客、假玩家、全局管理器 |

覆盖是**按类型，不是按产线**。已覆盖类型位于大阶段时仍因 2048 条目预算而串行。

## 4. 安装与配置

1. 安装提供 `AUDITED_BLOCK_ENTITY_TICKS` 的 Forgia 核心。
2. 把 `forgia-create-adapter-0.2.0.jar` **和** `create-1.21.1-6.0.10.jar` 放入 `mods/`。
3. 打开引擎（适配器本身不打开任何开关）：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 8
```

| 注意 | 说明 |
|---|---|
| **不要**把 Create 类型加入 `admit_block_entities` | 受审计准入是逐次调用，不是类型白名单 |
| interior 规则仍生效 | 8×8 区域 chunk 区域的外沿一圈保持串行 |
| 核心既有预算不变 | 阶段 >2048 条目 → 整阶段串行 |
| 不需要 runtime-proof/VM agent | 这是显式安装适配器的合同 |

## 5. API 使用

`CreateTickAdmission` 实现 `AuditedBlockEntityTickAdapter`，通过
`ParallelApi.Registrar.registerBlockEntityTicks` 注册：

| 方法 | Create 专用行为 |
|---|---|
| `audit()` | 声明固定版本实现、精确 source/function 类、指纹和已审查 Mixin 来源；注册时冻结 |
| `prepareCanonical(level)` | 本地动力分支不需要整网扫描；不保留 live level 或整网许可 |
| `inspect(source, tickerImplementation)` | 检查完整当前分支，列出真实 READ/WRITE backing root（source、effect/behaviour 身份） |
| `check(source)` | v1 逐次拒绝门；只有成功后才可能运行原 ticker，且成功返回才计完成 |

只在检查 `ParallelApi.Capability.AUDITED_BLOCK_ENTITY_TICKS` 之后注册：

```java
if (registrar.capabilities().contains(ParallelApi.Capability.AUDITED_BLOCK_ENTITY_TICKS)) {
    registrar.registerBlockEntityTicks(ParallelApi.MAJOR_VERSION,
            ResourceLocation.parse("forgia_create:steady_ticks"),
            STEADY_TYPES, new CreateTickAdmission());
}
```

完整性、绑定、生命周期、别名冲突和新鲜度由核心处理；Create 专用条件永远不进核心。

## 6. 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

```text
Audited original-tick adapter forgia_create:steady_ticks:
  implementationsVerified=true, reason=VERIFIED_IMPLEMENTATIONS, audit=..., revision=...
Block entity tick adapter forgia_create:steady_ticks:
  checks=..., refused=..., nativeCompleted=..., prepareMs=..., checkMs=..., reasons={...}
```

| 字段 | 含义 | 不是 |
|---|---|---|
| `implementationsVerified` | 冻结清单的完整性 | 当前分支或已完成 |
| `refused` | 被保留为串行的调用数 | 错误数 |
| `nativeCompleted` | 在 worker 上成功返回的原 ticker 数 | 提交数或类型名单 |

用增量配合整 tick MSPT/CPU/分配观察；绝不要从累计计数推导加速倍数。

## 7. 构建与测试

```sh
# 构建适配器 + 门面 + 安装器（Create jar 仅本地提供，不提交）
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar

# 用真实 Create jar 跑集成测试
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:junitTest

# 相同正文跑持久线程池
./gradlew ... -PcreateAdapterPersistentWorkers=true :create-adapter:junitTest

# canonical Create + Furnace/Resource 开关组合（保留既有预算）
./gradlew ... -PcreateAdapterPersistentWorkers=true -PcreateAdapterFurnaceWaves=true \
  -PcreateAdapterResourceOwnership=true \
  :create-adapter:junitTest --tests '*CreateCanonicalIntegrationTest'

# 负对照：注入未知 Mixin，必须观察到完整串行回退
./gradlew ... -PcreateAdapterUnverifiedHookControl=true :create-adapter:junitTest
```

| 产物 | 路径 |
|---|---|
| 适配器 jar | `adapters/create/build/libs/forgia-create-adapter-0.2.0.jar` |
| 缺少 Create jar | 编译/打包/真实 jar 测试明确跳过——**不**计为通过 |

## 8. 对比：0.1.0 vs 0.2.0

| 属性 | 0.1.0 | 0.2.0 |
|---|---|---|
| 接入点 | 历史 v1 明确驱动路径 | 实际 `Level.tickBlockEntities` canonical 路径 |
| 到达 worker 的类型 | 仅 v1 名单 | 稳态动力 + encased 变体 + clutch/gearshift 稳态 |
| 审计清单 | 无 | 实现 + source/function 类 + 已审查 Mixin |
| 足迹 | 不声明 | READ/WRITE backing root |
| 水车/磨石 | 明确驱动 | 保留历史检查；仍串行 |
| 失败行为 | v1 拒绝 | 新鲜足迹检查、前缀拆分、串行 fence 后缀 |

## 9. 限制与预期

| 限制 | 影响 |
|---|---|
| 2048 条目 canonical 阶段 | 大型基地仍串行；适配器无法提高预算 |
| interior 环 | 边界 chunk 保持串行 |
| Create 上的未知 Mixin/coremod | 整个审计集合被重查；未知 → 串行 |
| 动态新增 behaviour/子类 | 下次调用前拒绝 |
| 移动装置/列车/Deployer/传送带 | 不覆盖 |
| 本地测试 | 证明 API 路径，不证明整包吞吐 |

## 10. 验证状态

| 声明 | 证据 |
|---|---|
| canonical 路径准入在真实变换类上工作 | `CreateCanonicalIntegrationTest`（真实 ticker、Core wrapper、实际 Level 循环） |
| 不同 region 的稳态动力正文同时执行 | canonical 集成测试 |
| 未知 behaviour/子类/function 与 serial-fence 后缀 | 集成测试 + 负对照 |
| 核心侧受审计合同不依赖 Create | 核心 `AuditedBlockEntityTickAdapterTest` |
| 真实服务器可加载并完成 | 私有 `tools/server_smoke.py`（非生产实例） |

未建立：整合包兼容性、整服加速、多人认证。
