# Forgia adapters / Forgia 适配器

English | [中文](#中文) · [Docs](../docs/README.en.md) · [Adapter guide](../docs/adapters/README.en.md)

Forgia core has **no target-mod-specific backend**. Each supported mod gets a separately installed adapter
jar that declares its audited work through `RegisterParallelAdaptersEvent`; the core verifies only generic
facts (implementation integrity, binding, lifecycle, footprint, conflicts).

| Adapter | Target | Capability | Default | Documentation |
|---|---|---|---|---|
| **Create 0.2.0** | Create 6.0.10, MC 1.21.1 | `AUDITED_BLOCK_ENTITY_TICKS` | Steady kinetic parts only; everything else serial | [EN](../docs/adapters/create-adapter.en.md) · [中文](../docs/adapters/create-adapter.zh.md) · [source](create/README.md) |
| **MobSpawnController 0.3.0** | MSC 1.2.3, MC 1.21.1 | Natural-spawn preparation + ordered finalization + claim | **Serial by default**; ordered mode is opt-in and not recommended | [EN](../docs/adapters/mobspawncontroller-adapter.en.md) · [中文](../docs/adapters/mobspawncontroller-adapter.zh.md) · [source](mobspawncontroller/README.md) |

| Layout | Contents |
|---|---|
| `create/` | Create adapter: `CreateTickAdmission`, `CreateAudit`, mixin accessors, JUnit integration tests, private server smoke |
| `mobspawncontroller/` | MSC adapter: rule snapshots, spawn-preparation wiring, mixins, JUnit integration tests |
| Gradle projects | `:create-adapter` → `adapters/create`, `:mobspawncontroller-adapter` → `adapters/mobspawncontroller` |

Before writing a new adapter, read the [adapter guide](../docs/adapters/README.en.md): surface comparison,
decision matrix, trust model, registration budgets, authoring steps and the verification checklist.

---

## 中文

Forgia 核心**没有任何目标模组专用后端**。每个受支持的模组使用单独安装的适配器 jar，通过
`RegisterParallelAdaptersEvent` 声明已审计工作；核心只校验通用事实（实现完整性、绑定、生命周期、足迹、冲突）。

| 适配器 | 目标 | 能力 | 默认 | 文档 |
|---|---|---|---|---|
| **Create 0.2.0** | Create 6.0.10，MC 1.21.1 | `AUDITED_BLOCK_ENTITY_TICKS` | 仅稳态动力部件，其余完整串行 | [中文](../docs/adapters/create-adapter.zh.md) · [EN](../docs/adapters/create-adapter.en.md) · [源码](create/README.md) |
| **MobSpawnController 0.3.0** | MSC 1.2.3，MC 1.21.1 | 刷怪准备 + 有序最终化 + claim | **默认串行**；有序模式需显式开启且不建议 | [中文](../docs/adapters/mobspawncontroller-adapter.zh.md) · [EN](../docs/adapters/mobspawncontroller-adapter.en.md) · [源码](mobspawncontroller/README.md) |

| 目录 | 内容 |
|---|---|
| `create/` | Create 适配器：`CreateTickAdmission`、`CreateAudit`、Mixin accessor、JUnit 集成测试、私有服务器 smoke |
| `mobspawncontroller/` | MSC 适配器：规则快照、刷怪准备接线、Mixin、JUnit 集成测试 |
| Gradle 工程 | `:create-adapter` → `adapters/create`，`:mobspawncontroller-adapter` → `adapters/mobspawncontroller` |

编写新适配器前请阅读[适配器指南](../docs/adapters/README.zh.md)：表面选择矩阵、信任模型、注册预算、
编写步骤与验证清单。
