# Execution adapter 与 Create 0.3.0 实现记录

日期：2026-11-17。核心构建 `21.1.250.108-beta`，Create adapter `0.3.0`，目标 Minecraft `1.21.1` / Create `6.0.10`。

## 交付行为

- 新公开 `BlockEntityExecutionAdapter` 与独立 capability/注册方法，复用现有 server-scoped 冻结事务和 audited 实现清单。
- 核心在已发布的同一 canonical phase 准备与重审完整分支，原 Ticket 在正文开始前绑定内部 execution evidence。
- `REGION_OWNER` 与 `RESOURCE_SCOPED` 使用同一原始 ticker 授权内核。资源模式允许同一 chunk/区域内独立实例同时执行；旧 predicate/C5 请求不升级权限。
- 资源 root 按引用比较，依赖版本必须附着在 footprint 内；跨波的可写 root 使用稳定逻辑区域记账，避免空 RegionState 回收重建导致误拒绝。
- 最多 2048 候选的连续规划窗口替代整阶段大小门槛。独立 scoped 调用分到不超过 worker 数量的任务，每个正文仍持有独立一次性 Lease/Scope。
- 保留整阶段逻辑资源预算，耗尽后不随窗口重置。Furnace 规划输入有界，但 fresh 库存别名检查仍覆盖原整阶段。
- adapter 准备失败、检查失败、正文部分生效失败与 cleanup 失败均进入原阶段清理，已开始正文禁止重放；卸载/关闭等待真实 worker 返回。
- Create 稳态传动件、clutch、gearshift 改用 scoped 原 tick 执行。加工、拓扑、未知 callback 和其它未闭合分支继续完整串行。

实际合同与示例见[中文使用说明](../docs/api/block-entity-execution.zh.md)、[英文使用说明](../docs/api/block-entity-execution.en.md)和 [Create 当前说明](../adapters/create/README.md)。设计草案中的泛型上下文、不透明 resolver 和通用效果 profile 尚未实现。

## 验证与证据位置

本地原始日志、JUnit XML 和私有服务器记录位于 `build/execution-adapter-validation/`。该目录为构建产物，不提交。

测试包括真实 Minecraft Core wrapper、原函数、实际 Level 入口和实际 worker pool；world/chunk 部分使用测试替身。独立核心 example 不依赖 Create。新接口测试明确检查同区域并发、跨区域并发、READ/READ、WRITE 冲突、依赖变化、mailbox 更新、完整串行 fence、注册事务、预算、线程池拒绝、cleanup 和生命周期。

最终矩阵及产物 hash 另保存于上述目录的 `receipt.json`。验收时区分：

| 范围 | 验证含义 |
|---|---|
| 完整核心 JUnit | 当前源码默认回归；正常任务排除 benchmark 与独立未知 Furnace Mixin 控制 |
| 完整 Create 默认 / persistent | 同一新 adapter 的 canonical 与历史显式驱动原测试 |
| 核心 persistent + resources | canonical、execution adapter、C5、资源内核、双 BE 操作组合 |
| Create canonical + persistent + Furnace/resources | 实际 canonical 入口与现有原版资源配置共存 |
| Create 未知 Mixin 独立 JVM | 未审核变换后完整串行回退，worker completion 为零 |
| 私有安装服 | 安装器实际安装启动、Create 原装置产出、实际 worker completion、无违规/失败和正常退出 |
| 格式、license 与 jar 内容 | 改动 Java 的格式、既有 license 检查，facade 不含内部授权实现，adapter 不打包上游或 API 副本 |

2050 个独立原始 ticker 的测试验证两个有界窗口、四个 worker 任务提交，以及每个原始正文一次。共享写资源的重复 prefix 验证整阶段预算耗尽后后缀保持串行。窗口后的完整 fence 验证后续分支从新状态重新检查。

本轮最终结果：

| 验证 | 结果 |
|---|---|
| 完整核心 JUnit | 1516/1516，零失败/错误/跳过 |
| 完整 Create 默认 pool | 27/27，零失败/错误/跳过 |
| 完整 Create persistent pool | 27/27，零失败/错误/跳过 |
| 核心 persistent + resources 相关组合 | 249/249，零失败/错误/跳过 |
| Create canonical + persistent + Furnace/resources | 10/10，零失败/错误/跳过 |
| Create 未知 Mixin 控制 | 2/2，完整串行回退 |
| 原版 Furnace 未知 Mixin 控制 | 1/1，既有拒绝边界保持 |
| 格式与 license | PASS；formatter 仅覆盖本任务改动 Java，未改用户已有测试改动 |
| 私有安装服最终构建 | PASS；801 个自然 tick、3146 次成功 worker tick，正常产出、零违规/失败、exit 0 |
| facade/adapter 内容与已安装 universal hash | PASS |

正常构建命令使用 JDK 21、`--offline --no-configuration-cache -PforgiaVersion=21.1.250.108-beta`；Create 相关任务另传本地 `-PcreateJar`。产物为 `projects/neoforge/build/libs/neoforge-21.1.250.108-beta-installer.jar`、同目录 `*-parallel-api.jar` 和 `adapters/create/build/libs/forgia-create-adapter-0.3.0.jar`。

## 探索失败与限制

初轮真实测试发现：阶段 writer-home 表使用瞬态 RegionState 身份，会使回收重建后的同一区域调用误拒绝；已改为 RegionCoordinate 身份，并保留跨 home 拒绝。mailbox 测试最初错误使用普通 region task queue，后改为实际 RegionMailbox。混合 Furnace 测试最初把两个会通知的熔炉放在彼此两格通知范围内，核心正确拒绝；fixture 调整为有审计允许的间距。失败日志/XML 未当作通过。

完整 JUnit 默认 512m 堆在大量绑定/世界替身后出现反复 full GC，取消该次运行后将测试堆调整为 2g。该调整仅作用于 JUnit 测试任务。

把 Furnace Waves 配置应用到整套历史 Create 显式驱动测试时，其原路径不再执行旧 adapter 准备，10 个依赖旧准入的断言失败；该失败矩阵保存在 `create-persistent-full-legacy-config-failure/`，不计作通过。当前新接口的配置共存按实际 canonical suite 验证；历史显式驱动的完整 suite 在默认与 persistent、Furnace Waves 关闭时分别验证。未通过修改旧断言隐藏这一区别。

私有安装服没有玩家/addon，功能 smoke 不证明重负载收益或任意整合包兼容。磨石装置的产出由完整原加工串行路径提供，不能由传动件 completion 推导加工已经并行。当前没有 MSPT 加速倍数结论，也没有部署到线上实例。

首版仍要求完整受信任 adapter 审计、无状态 ticker、最多 64 root；通用 Capability resolver、多 home、自动外部写入者拦截、任意通知/事件效果和 compute/apply 属于后续协议。原始成员/Ticket 快照仍 O(N)，窗口限制的是规划与活动资源规模。
