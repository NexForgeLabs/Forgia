# Forgia 文档

[English](README.en.md) | 中文

本目录是 Forgia 的**受维护文档集**。每个页面都有 `*.en.md` / `*.zh.md` 一对文件，二者描述同一份合同；
出现分歧时以英文页为参考文本，并要求在同一次提交中修复两边。

`region-threading/` 目录保留**证据档案**（审计、验收运行、profiler 记录、实验报告），它不是教程，也不逐页翻译。
要了解“怎么工作 / 怎么调用”，看本目录；要核对“某项声明背后的测量”，看 `region-threading/`。

---

## 1. 我需要看哪一页？

| 我想…… | 阅读 | 主要类型 |
|---|---|---|
| 了解 Forgia 当前状态与验证范围 | [README](index.md)、[STATUS](status.zh.md) | — |
| 先用 3D 动画过一遍执行流程 | [3D 架构动画流](visualizer/index.html)（交互式架构演示；展示概念流程，不代表兼容认证） | — |
| 了解实验性玩家调度 | [玩家 Tick](modules/player-ticks.zh.md) | `ENTITY`、`CONNECTION`、`CONNECTION_BODY` |
| 开启服务器 / 调优 `config/forgia.toml` | [配置与硬件调优](configuration.zh.md)、[观测与配置](modules/observability.zh.md) | `forgia.toml`、`/forgia` |
| 调用面向模组的 API | [API 总览](api/README.zh.md) | `ParallelApi` |
| 在主线程准备刷怪数据、在 worker 执行正文 | [刷怪准备](api/natural-spawn-preparation.zh.md) | `NaturalSpawnPreparation`、`Ready`、`ServerRequired` |
| 让我模组的方块实体原 ticker 在 worker 上运行 | [方块实体 tick 准入](api/block-entity-ticks.zh.md) | `BlockEntityTickAdapter`、`AuditedBlockEntityTickAdapter` |
| 跨区域搬运物品 / 执行双 owner 操作 | [方块实体操作](api/block-entity-operations.zh.md) | `BlockEntityOperations` |
| 在 region / 实体 / 全局调度器上安排任务 | [调度器](api/schedulers-and-entity-interactions.zh.md) | `RegionScheduler`、`EntityRegionScheduler`、`GlobalRegionScheduler` |
| 为第三方模组编写适配器 | [适配器指南](adapters/README.zh.md) | `RegisterParallelAdaptersEvent` |
| 安装或验证 Create 适配器 | [Create 适配器](adapters/create-adapter.zh.md) | `forgia_create:steady_ticks` |
| 安装或验证 MobSpawnController 适配器 | [MSC 适配器](adapters/mobspawncontroller-adapter.zh.md) | `forgia_mobspawncontroller` |
| 理解引擎内部实现 | [模块文档](modules/README.zh.md) | `RegionThreadingEngine`、`CanonicalBlockEntityCoordinator` |
| 看测量本身而不是摘要 | [证据档案](status.en.md) | — |

## 2. 文档结构

```
docs/
├── index.{md,zh.md}                      站点首页
├── README.{en,zh}.md                     索引、约定、状态图例
├── architecture.{en,zh}.md               原版 NeoForge vs Forgia 架构对比
├── benchmarks.{en,zh}.md                 实测性能与耐久基准
├── configuration.{en,zh}.md              全部 36 项 forgia.toml 配置与硬件调优指南
├── status.{en,zh}.md                     工程状态与验收回执
├── visualizer/                           3D 架构动画页（单文件 HTML，非 MkDocs 页面）
├── api/                                  面向模组的公开合同
│   ├── README.{en,zh}.md                 入口矩阵、生命周期、编译配置
│   ├── parallel-api.{en,zh}.md           ParallelApi、Server、Capability、Phase、DataKey
│   ├── natural-spawn-preparation.{en,zh}.md
│   ├── block-entity-ticks.{en,zh}.md     v1 谓词 + 受审计原 ticker 合同
│   ├── block-entity-execution.{en,zh}.md 资源作用域 BE 执行契约
│   ├── block-entity-operations.{en,zh}.md
│   └── schedulers-and-entity-interactions.{en,zh}.md
├── adapters/                             随仓库发布的适配器模组与编写指南
│   ├── README.{en,zh}.md                 适配器类型、选择矩阵、信任模型
│   ├── create-adapter.{en,zh}.md
│   └── mobspawncontroller-adapter.{en,zh}.md
└── modules/                              各子系统的实现说明
    ├── README.{en,zh}.md                 模块地图与状态矩阵
    ├── region-engine.{en,zh}.md
    ├── entity-ticks.{en,zh}.md
    ├── player-ticks.{en,zh}.md
    ├── chunk-ticks.{en,zh}.md
    ├── natural-spawning-and-poi.{en,zh}.md
    ├── block-entities.{en,zh}.md
    ├── cross-region-and-scheduled.{en,zh}.md
    └── observability.{en,zh}.md
```

## 3. 状态图例

每个页面都标注所述内容的状态。这些标签不是宣传分级，而是说明“目前有多少证据”。

| 标签 | 含义 | 可以假设什么 |
|---|---|---|
| **基本稳定** | 已随多个构建发布、并有测试覆盖的公开表面。 | 不升主版本就不改签名。 |
| **实验性** | `@ApiStatus.Experimental`，需要 capability，通常还有配置开关。 | 次版本可能改行为；必须按 capability 协商。 |
| **已验证（范围）** | 在明确记录的构建、负载和配置内通过指定验证（C6 历史验收；C7 188 Mod 生产验证）。 | 证据只适用于该范围，不代表所有整合包。 |
| **不支持** | 代码或提案里存在，但不是合同。 | 不要依赖。 |
| **未实现** | RFC 中描述但无代码。 | 没有可调用入口。 |

## 4. API 读者基本规则

1. **协商，不要猜测。** 先看 `ParallelApi.MAJOR_VERSION`，再看 `Server.capabilities()`；不要按 Forgia
   构建号推断行为。
2. **能力 ≠ 已启用。** capability 表示服务器实现了该协议；配置开关表示该切片在运行。二者都不授予世界所有权。
3. **注册是合同，不是证明。** 核心校验实现完整性、绑定、生命周期和声明足迹，但无法证明任意 Java 安全。
4. **跨区域不得阻塞。** 不要在 region 正文里等待 worker、其他区域或服务结果；先提交，再在后续安全 tick 轮询。
5. **不要把活对象当权限。** `Reference`、快照、上下文只描述“当下这个实例”，不把修改权延长到后续 tick 或别的线程。
6. **至多一次，不重放。** 已开始的正文失败只报告、不重跑；已产生部分效果的操作不重试。
7. **失败即回退串行。** 未知回调、未知类、未加载邻域、未验证变换 → 完整走原服务器线程路径。

## 5. 构建 compile-only API 门面

```sh
./gradlew --offline :neoforge:parallelApiJar
# → projects/neoforge/build/libs/*-parallel-api.jar
```

以 `compileOnly` 使用。**不要** shade、重定位或 jar-in-jar 第二份：运行类由 Forgia 服务端提供。
目前没有已冻结的独立 Maven 坐标。

```groovy
dependencies {
    compileOnly files('libs/forgia-parallel-api.jar')
}
```

需要同时兼容原版 NeoForge 的模组，应把 Forgia 专用入口放在可选模组守卫之后，不要在会在原版加载的类里直接引用可选 Forgia 类型。

## 6. 相关目录

| 路径 | 内容 |
|---|---|
| `region-threading/` | 审计、验收记录、profiler JSON、实验报告、Folia/上游参考 |
| `examples/parallel-api/` | 仅编译的 `ContainerLinkExample.java` |
| `adapters/` | 已发布适配器模组及其 smoke 工具 |
| `testframework/`、`tests/` | 单元/GameTest 测试框架与套件 |
| `tools/` | 基准、探针、整合包矩阵与就绪度控制器 |

## 7. 关于已删除的临时产物

文档整理时删除了本地 `tmp/`（profiler/基准草稿，约 31 GB）、`tests/runs/`（GameTest 运行输出）和
`.pi/tasks/`（代理任务笔记），并已加入 gitignore。`region-threading/` 下部分证据文档仍链接到这些路径，
这些链接属于**历史记录**，文件已不在仓库中。新的测量产物请放在仓库外或 `tmp/`（已被忽略），
维护文档请引用证据文档而不是本地路径。
