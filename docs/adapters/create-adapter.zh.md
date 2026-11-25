# Create 并行兼容适配器（`forgia-create-parallel-compat`）与历史审计适配器

[English](create-adapter.en.md) | 中文 · [适配器索引](README.zh.md)
线上生产兼容模组源码：[`tools/forgia-create-parallel-compat`](https://github.com/NexForgeLabs/Forgia/tree/main/tools/forgia-create-parallel-compat) · 审计协议参考源码：[`adapters/create`](https://github.com/NexForgeLabs/Forgia/tree/main/adapters/create)

---

## 1. 线上生产版 Create 并行兼容适配器（`forgia-create-parallel-compat 1.0.0`）

在 Forgia Phase 2/3（`enableIndependentRegionTickClock = true`、`enableRegionSandboxAllBlockEntities = true`）下，**`forgia-create-parallel-compat`** 通过 `RegionAdapterApi` 与线程安全 Mixin，将 **Create 6.0.10 本体及 8 个主流机械动力附属模组命名空间**全面准入 Region Worker 并行执行——在大型机械动力工厂线上实测中实现 **99.89% 方块实体并行率**（累计 `2.75 亿+` 次 Worker 执行，零崩溃）。

### 已准入并行命名空间（共 9 个）

| 命名空间 | 对应模组 | Region Worker 沙箱状态 |
| :--- | :--- | :--- |
| `create` | 机械动力 Create (`6.0.10`) | **全命名空间准入**（传动轴、齿轮、变速箱、水车、传送带、漏斗、溜槽、搅拌器、压块机、机械手、工作盆、流体管道/泵/储罐、动力合成器、打包机、青蛙港等） |
| `createaddition` | 机械动力：工艺与附加 | **全命名空间准入** |
| `create_connected` | 机械动力：连接扩展 | **全命名空间准入** |
| `create_enchantment_industry` | 机械动力：附魔工业 | **全命名空间准入** |
| `create_dragons_plus` | Create: Dragons Plus | **全命名空间准入** |
| `createdieselgenerators` | 机械动力：柴油发电机 | **全命名空间准入** |
| `createpropulsion` | Create: Propulsion | **全命名空间准入** |
| `create_biotech` | Create: Biotech | **全命名空间准入** |
| `copycats` | 机械动力：伪装方块+ | **全命名空间准入** |

> **防误伤全局降级保护**：通过 `RegionAdapterApi.admitRegionSandboxedNamespace(...)` 显式准入的命名空间，若个别方块坐标出现瞬态异常，只会触发该单坐标的短时主线程冷却回退（`sandboxTransientCooldownTicks = 20`），绝不会将整类方块（如 `create:belt`）全局拉黑。

### 线程安全 Mixin 与核心防护

| 模块 | 目标类 | 解决的并发问题 |
| :--- | :--- | :--- |
| `TorquePropagatorMixin` | `TorquePropagator` | 将全局与维度级动力学网络 Map 替换为 `ConcurrentHashMap` |
| `KineticNetworkMixin` | `KineticNetwork` | 将网络内的 `sources` 与 `members` 替换为 `ConcurrentHashMap` |
| `BeltInventoryMixin` | `BeltInventory` | 将传送带 `items` 列表替换为线程安全的 `ConcurrentBeltList`，保护并发物品读写 |
| `GlobalLogisticsManagerMixin` | `GlobalLogisticsManager` | 初始化与世界加载后将 `logisticsNetworks` 升级为 `ConcurrentHashMap` |
| `RedstoneLinkNetworkHandlerMixin` | `RedstoneLinkNetworkHandler` | 并发化各维度的红石无线连接表 |
| `CreateAdvancementMixin` | `CreateAdvancement` | Worker 触发机械动力进度奖励时通过 `RegionAdapterApi.postGlobal` 延迟回主线程发放 |
| `SavedData` 共享 | `RegionAdapterApi.shareSavedData` | 跨区安全共享 `create_logistics` 与 `create_tracks` 世界存档数据 |
| `FakePlayer` 原生沙箱 | Forgia 内核（`FakePlayer` + `PlayerAdvancements`） | 允许机械手（`DeployerBlockEntity` / `DeployerFakePlayer`）原生在 Region Worker 上运行 |

### 动态拓扑守卫与主线程回退边界

与旧版“按机器类型一刀切禁止”不同，`kineticTopologySafe` 谓词**仅将正在发生跨区拓扑变更的那 1 个瞬态 Tick** 路由回主线程，其余 99.9% 的稳态运行期全部留在 Worker 并行：

| 条件 / 对象 | 执行线程 | 原因 |
| :--- | :--- | :--- |
| 未完成初始化的 `SmartBlockEntity`（`!initialized`） | 主线程（首 Tick） | 首次注册网络与 Behaviour |
| `networkDirty` / `updateSpeed` / `validationCountdown <= 1` | 主线程（瞬态 Tick） | 跨区转速传播与周期性动力学网络校验 |
| 失去动力源的孤立旋转件（`speed != 0 && source == null`） | 主线程（瞬态 Tick） | 拆掉动力源后干净执行跨区断链重算 |
| `GeneratingKineticBlockEntity.reActivateSource` / 玩家手摇 `HandCrankBlockEntity` | 主线程（瞬态 Tick） | 发电件重新激活与玩家交互 |
| `BeltBlockEntity` 且 `beltLength <= 0` | 主线程（瞬态 Tick） | 尚未初始化完或正在拆除的传送带控制器 |
| 移动动态结构实体（`AbstractContraptionEntity`、列车） | 主线程 | 跨区结构位移与方块/实体交互（`entityTickPolicy`） |
| `fxntstorage:storage_controller_entity` 与 `storage_interface_*` | 主线程（显式排除） | Tick 期间重建 `NonNullList` 槽位表（普通 `StorageBoxEntity` 仍在 Worker 并行） |

---

## 2. 历史参考：受审计稳态适配器（`adapters/create 0.2.0 / 0.3.0`）

> 以下保留 `adapters/create`（Phase 2/3 Region Sandbox 之前的 `AUDITED_BLOCK_ENTITY_TICKS` 协议）历史文档；最新审计执行适配器说明见 [`adapters/create/README.md`](https://github.com/NexForgeLabs/Forgia/blob/main/adapters/create/README.md)。

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
