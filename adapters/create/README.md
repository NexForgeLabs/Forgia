# Forgia Create Adapter 0.2.0

中文 | [English](../../docs/adapters/create-adapter.en.md) · [中文完整文档](../../docs/adapters/create-adapter.zh.md) · [适配器指南](../../docs/adapters/README.zh.md)

> 本页是源码旁的简短说明；参数表、对比、失败语义与验证范围以双语文档为准。

实验性独立适配模组，限定 **Minecraft 1.21.1 + Create 6.0.10**，需要提供
`AUDITED_BLOCK_ENTITY_TICKS` 的 Forgia 核心（本轮构建 `21.1.250.107-beta`）。

适配器通过公开的 [AuditedBlockEntityTickAdapter API](../../region-threading/AUDITED-BLOCK-ENTITY-TICK-API.md)
接入现有 canonical BE 阶段。**Create 的类、指纹、状态判断和资源足迹全部位于此 adapter；核心没有 Create 专用准入后端。**
原 Create jar 保留，适配器不包含或替换上游实现，也不自行创建调度器/线程池。

## 当前实际覆盖

| 对象 | 当前 canonical Level 路径 | 完整串行边界 |
|---|---|---|
| `create:simple_kinetic`（轴、小/大齿轮） | 已初始化、无外部回调的本地原始 tick | 初始化、速度传播、network dirty、周期校验、粒子发布、未知 behaviour/实现 |
| `create:encased_shaft`、`encased_cogwheel`、`encased_large_cogwheel`、`gearbox` | 同上 | 同上 |
| **`create:clutch`、`gearshift`** | **新增稳态原始 tick 支持** | 红石切换/源更新引起的拓扑分支，以及所有未闭合状态 |
| `create:water_wheel`、`large_water_wheel`、`millstone` | 当前仍完整串行 | 这些类型保留历史 v1 明确驱动的检查/测试；尚未为当前 canonical 路径提交完整正文合同 |
| 传送带、漏斗、工作盆、搅拌器、压块机、流体与存储网络 | 完整串行 | 共享库存/controller、配方、回调和外部写入者尚未闭合 |
| 移动装置 Entity、Deployer USE/PUNCH、列车 | 完整串行 | 世界操作、碰撞、乘客、假玩家与全局管理器需要各自协议 |

0.2.0 的关键变化是让第一批稳态类型进入**实际 `Level.tickBlockEntities` 路径**，而非仅增加 v1 名单。
原始 tick、父类 tick、已审查 behaviour/lazy tick 都保留。类型兼容不等于整条工业产线已经并行，
Worker 计数也不证明整服比串行更快。

当前核心还保留 **一个 canonical BE 阶段超过 2048 项就整体串行** 的门槛。线上诊断曾收集约 5592 项，
因此本版 adapter 不能单独让那种大阶段进入 Worker；需要后续通用有界窗口设计，不能只调高常量或按 chunk 绕过。
本版测试明确区分小阶段正向路径和这个仍然存在的规模限制。

本地闭合分支不读取动力网注册表，因此可与同网的其他串行机器共存；每个未知串行 fence 完整执行后，
核心重新检查后缀。水车/磨石的历史明确驱动仍保留整网预检，不能用本地传动分支的结论扩大其权限。

## 安装与配置

将 `forgia-create-adapter-0.2.0.jar` 与原 `create-1.21.1-6.0.10.jar` 一起放入测试实例的 `mods/`。
需使用提供上述新能力的 Forgia。适配器不打开任何管理员关闭的核心开关：

```toml
enableRegionThreadingBridge = true
enableRegionThreadingWorkers = true
enableRegionThreadingBlockEntityTicks = true
regionThreadingMode = "FIXED_3X3"
regionThreadingFixedRegionChunks = 8
```

沿用核心原有 interior 规则、预算、显式串行声明与兼容限制。8×8 区域的外沿一圈 chunk 仍保留串行。
不需要把 Create 类型加入 `admit_block_entities`。本接口不依赖 optional runtime-proof/VM agent；
它是显式安装已审核 adapter 的合同，不能与其他运行时证明后端的验收范围混淆。

所有默认开关保持原值。安装包、整包和线上升级均应按各自的验证流程完成；本地单元测试不代表线上已认证。

## API 与执行边界

`CreateTickAdmission` 实现公开的 `AuditedBlockEntityTickAdapter`，继续通过
`ParallelApi.Registrar.registerBlockEntityTicks` 注册：

- `audit()`：声明固定版本实现、精确 source/function 类、指纹和已审核 Mixin 来源，在服务器注册时冻结。
- `prepareCanonical()`：当前本地传动分支不需要整网扫描；不保留 live Level 或整网许可。
- `inspect(source, actualTickerClass)`：检查完整当前分支并列出 source、effect、behaviour 等真实 backing-root 的 READ/WRITE 身份。
- `check(source)`：原 v1 逐次拒绝门继续存在；成功后才可能运行原始 ticker，成功返回才计 completion。

核心只使用通用接口统一校验实现完整性、实际 wrapper/source/function/frame、生命周期、资源别名与新鲜状态。
它复用原 canonical Native prefix、Ticket、region ownership、physical join、完整 serial fence 和有序效果提交。
未知 callback 不会被拆掉；已开始的正文异常不在主线程重放。

实现指纹不能自动证明语义安全。完整分支、回调闭包、资源完整性和外部异步写入者控制由 adapter 的审计承担；
Java API 不是 sandbox。当前仍检查整个既有 Create 审计集合，任何未审核变换可能使本适配器保守地全部回退。
exact class 阻止未知子类继承合同，动态添加未知 behaviour 会在下一次调用前拒绝。

## 诊断

```text
/neoforge regionthreading compat
/neoforge regionthreading status raw
```

兼容诊断增加：

```text
Audited original-tick adapter forgia_create:steady_ticks:
  implementationsVerified=true, reason=VERIFIED_IMPLEMENTATIONS, audit=..., revision=...
Block entity tick adapter forgia_create:steady_ticks:
  checks=..., refused=..., nativeCompleted=..., prepareMs=..., checkMs=..., reasons={...}
```

`implementationsVerified` 只描述冻结实现的完整性，不能代替当前分支检查或成功完成。
`nativeCompleted` 才是 worker 上成功返回的原始 ticker 调用；提交、回退和类名单不计作完成。
使用同一窗口的增量配合全 tick MSPT/CPU/分配观察，不能从累计次数推导加速倍数。

## 本地构建与测试

使用 JDK 21，在仓库根目录运行。原 jar 只由本地提供，不提交或打包：

```sh
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:jar :neoforge:parallelApiJar :neoforge:installerJar

./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  :create-adapter:junitTest

# 同一原始正文测试选择 persistent pool：
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  -PcreateAdapterPersistentWorkers=true :create-adapter:junitTest

# 验证 canonical Create 与 Furnace/Resource 开关组合（保留原预算）：
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  -PcreateAdapterPersistentWorkers=true -PcreateAdapterFurnaceWaves=true \
  -PcreateAdapterResourceOwnership=true \
  :create-adapter:junitTest --tests '*CreateCanonicalIntegrationTest'

# 独立 JVM，注入未知 Mixin，必须观察到完整串行回退：
./gradlew --offline --no-configuration-cache \
  -PforgiaVersion=21.1.250.107-beta \
  -PcreateJar=/absolute/path/create-1.21.1-6.0.10.jar \
  -PcreateAdapterUnverifiedHookControl=true :create-adapter:junitTest
```

未提供原 Create jar 时编译/打包/真实 jar 测试明确跳过，不将其计为通过。
产物为 `adapters/create/build/libs/forgia-create-adapter-0.2.0.jar`。

新增 canonical 测试使用真实变换 Create 类、原始 SmartBlockEntityTicker、Core wrapper 和实际 Level 循环，
证明不同 region 的离合器与动力转向器原始正文同时执行，并检查回调边界重新准入、未知 behaviour/子类/函数、
serial fence 后缀重审与原始调用次数。世界/chunk 服务仍有测试替身，不能据此宣称整包多人或吞吐量验收。

独立的核心示例 adapter 不依赖 Create，验证同一 API 的并行、READ/WRITE 冲突、失效与注册事务；
架构测试限制通用核心组件引入目标模组依赖。

## 私有真实服务器 smoke

`tools/server_smoke.py` 在新目录复制只读输入 libraries，安装当前核心，仅加载原 Create 与 adapter，
检查两个水车／齿轮箱／磨石装置的产物、当前通用审计完整性和真实 worker completion。
不得指向生产实例目录。需要已经接受的 EULA：

```sh
python3 adapters/create/tools/server_smoke.py \
  --run-dir /absolute/new/private-create-smoke \
  --libraries /absolute/existing-server/libraries \
  --create-jar /absolute/path/create-1.21.1-6.0.10.jar \
  --eula /absolute/existing-server/eula.txt \
  --java /absolute/jdk21/bin/java
```

这里只检查功能和调用路径；整体产出由原加工串行完成，不能用传动 worker 完成数声称磨石加工已并行。

完整优化路线与本轮结果见 [优化方案](../../region-threading/CREATE-OPTIMIZATION-PLAN.md)。
历史 0.1.0 的独立明确驱动验证保留在 [原验证记录](../../region-threading/CREATE-ADAPTER-VALIDATION.md)，
不能将那些旧数据写成 0.2.0 canonical 或当前整合包的性能结果。
