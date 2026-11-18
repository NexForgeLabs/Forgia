# 适配器模组 —— 架构、选择矩阵与编写指南

[English](README.en.md) | 中文 · [API 索引](../api/README.zh.md)
证据：[GENERAL-MOD-ADAPTER-ARCHITECTURE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/GENERAL-MOD-ADAPTER-ARCHITECTURE.md)、[MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/MODDED-BLOCK-ENTITY-ADAPTATION-ARCHITECTURE.md)

状态：已发布适配器均为**实验性**。核心**没有任何目标模组专用后端**：目标模组的类、指纹、分支条件和
资源足迹全部位于适配器 jar。

---

## 1. 适配器是什么、不是什么

| 是 | 不是 |
|---|---|
| 一个单独安装的模组 jar，通过 `RegisterParallelAdaptersEvent` 声明已审计工作 | 针对每个模组的核心补丁 |
| 目标模组审计的所有者（类、Mixin、分支、资源、版本） | 沙箱，或任意 Java 安全的证明 |
| 把类型/id 注册进既有 canonical 阶段 | 线程池、调度器、lease 或执行权限的创建者 |
| 必须能回退：拒绝 → 完整原服务器线程路径 | 允许重放已开始的正文 |
| 与目标模组构建版本严格绑定 | 对其他版本的兼容承诺 |

核心只校验通用事实：实现完整性、实际 wrapper/source/function 绑定、生命周期、资源身份/别名、新鲜度与
跨 owner 冲突。它复用既有 Native 前缀、`InvocationTicket`、region guard/permit、物理 join 与有序效果发布。

## 2. 适配器表面 —— 对比

| 表面 | 能力 | 适配器声明什么 | 目前能做到 | 成本 | 文档 |
|---|---|---|---|---|---|
| 刷怪准备 | `NATURAL_SPAWN_PREPARATION`、`..._PLAN_PREPARATION`、`..._CLAIMS`、`ORDERED_SPAWN_FINALIZATION` | 纯快照、可选 claim + 有序最终化 | 冻结规则的刷怪正文在 worker 上 | 中 | [指南](../api/natural-spawn-preparation.zh.md) |
| v1 BE tick 谓词 | `BLOCK_ENTITY_TICK_ADMISSION` | 逐次 `Decision` | 拒绝门；仅谓词不授予 canonical 路径 | 低 | [指南](../api/block-entity-ticks.zh.md) |
| 受审计原 ticker | `AUDITED_BLOCK_ENTITY_TICKS` | 清单 + READ/WRITE 足迹 | canonical 阶段中原 ticker 在 worker 上 | 高 | [指南](../api/block-entity-ticks.zh.md) |
| C5 通用/专用申请 | `GENERAL_BLOCK_ENTITY_ADAPTERS` | descriptor、资源、依赖 | 仅诊断；目标仍是完整串行 fence | 中 | [API 参考 §4.8](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/API-REFERENCE.md) |
| 仅操作集成 | `BLOCK_ENTITY_OPERATIONS` | 不注册；使用 `blockEntities()` | 跨区完成交互，不并行 tick | 低 | [指南](../api/block-entity-operations.zh.md) |

## 3. 选择矩阵

| 你的目标 | 推荐路径 | 为什么不用另一种 |
|---|---|---|
| 机器初始化后 tick 是本地 | 受审计原 ticker | v1 谓词到不了 canonical Level 路径 |
| 只需便宜地说“现在不行” | v1 谓词 | 受审计要求完整清单与足迹 |
| 机器周期性访问邻居容器 | 仅操作 + 本地受审计 tick | 并行 tick 不能合法读外部根 |
| 刷怪监听器只用整合包配置 | 刷怪准备 | tick 适配器不影响刷怪 |
| finalize 回调必须看到真实加入/取消 | 有序最终化 | worker 最终化无法保证共享名额顺序 |
| 类似 MobSpawnController 且有结构过滤 | 目前无受支持路径，保持串行 | 结构/倍率协议未实现 |
| 只是想在机器间搬物品 | `blockEntities().transfer` | 审计双方 ticker 既无必要也更高风险 |

## 4. 注册生命周期与预算

| 阶段 | 事实 |
|---|---|
| 事件 | `NeoForge.EVENT_BUS` 上的 `RegisterParallelAdaptersEvent`，biome modifiers 之后、世界 tick 之前 |
| 范围 | 服务器线程，仅在事件内；之后保留 registrar 会失败 |
| 事务 | 所有适配器全有或全无；即使捕获失败也使事务无效 |
| 顺序 | 回调按 id 排序 |
| 预算 | 总注册 ≤128（刷怪 + BE 共用） |
| id | 每服务器唯一；由适配器模组命名空间化 |
| 类型 | 每 BE 适配器 ≤128；不得与其他 BE 注册重叠 |
| 冻结 | 事件之后冻结，不支持动态注册 |
| 对配置的影响 | 无：注册绝不启用切片或解除冲突 |

## 5. 信任模型

| 层次 | 责任方 |
|---|---|
| 语义完整性（每个分支、回调、继承 tick、RNG 路径） | 适配器作者 |
| 版本固定与字节变化检测 | 清单哈希 + 已审查 Mixin 集合 |
| 绑定到精确实例/wrapper/function/chunk | 核心 |
| 每次正文前的足迹新鲜度 | 核心 + 适配器 `inspect` |
| 跨 owner 别名冲突 | 核心（READ/WRITE 引用身份） |
| 外部异步写入者 | 适配器审计；核心看不到 |
| 整合包级验证 | 你在真实安装服务器上完成 |

哈希证明的是*实现字节*，不是安全行为。绿色单元测试不是整合包认证。

## 6. 新增适配器（仓库布局）

```
adapters/<name>/
├── build.gradle                     # 通过 -P<name>Jar 提供目标 jar；缺失时跳过测试
├── README.md                        # 简短，链接到 docs/adapters/<name>.zh.md
├── src/main/java/.../               # 适配器 + Mixin accessor、package-info
├── src/main/resources/              # mixins json + neoforge.mods.toml
├── src/junit/java/.../tests/        # 用真实目标 jar 的集成测试
│   └── hooks/                       # 未审查 Mixin 负对照
└── tools/                           # 私有真实服务器 smoke 脚本
```

Gradle 工程在 `settings.gradle` 中注册为 `:create-adapter`、`:mobspawncontroller-adapter`。

### 编写步骤

1. **审计目标** —— 枚举原 ticker 类、继承 tick、behaviour、lazy tick、回调、RNG 使用、共享/外部根，
   以及整合包可能添加的每种变换。
2. **按选择矩阵确定表面**；先写拒绝条件。
3. **实现清单** —— 精确 source/function 类、实现哈希、已审查 Mixin。
4. **实现 `inspect`** —— 任何未闭合分支返回 `null`；以 WRITE 包含 source 及每个真实 backing root；
   绝不在 worker 上查询活世界。
5. **注册** —— 在事件内、能力检查之后；保留串行路径。
6. **测试**：先串行等价（开关关闭），再真实重叠（开关开启），再做负对照（未知 Mixin、未知子类/function、
   根变化、重入重复注册）。
7. **加诊断** —— 只用有界枚举；不含位置、类型或网络 id。
8. **写模块文档对** —— `docs/adapters/` 下中英各一份，并从简短 README 链接。
9. **记录范围** —— 版本、配置开关、预算、**不**覆盖的内容。

## 7. 已发布适配器

| 适配器 | 目标 | 使用能力 | 默认状态 | 文档 |
|---|---|---|---|---|
| Create 0.2.0 | Create 6.0.10（MC 1.21.1） | `AUDITED_BLOCK_ENTITY_TICKS` | 仅稳态动力类型；其余完整串行 | [Create](create-adapter.zh.md) |
| MobSpawnController 0.3.0 | MSC 1.2.3 | 刷怪准备 + 有序最终化 + claim | **默认串行**；有序模式需 `-Dforgia.mscOrderedSpawning=true` | [MSC](mobspawncontroller-adapter.zh.md) |

## 8. 验证清单

- [ ] 已协商能力；注册只在事件内。
- [ ] id/type 带命名空间、唯一、在 128 预算内。
- [ ] 每个不支持的配置都在副作用**之前**回退。
- [ ] 清单覆盖精确类与已审查 Mixin；未知变换 → 串行。
- [ ] 足迹以 WRITE 包含 source 及所有真实根；别名已解析。
- [ ] `prepare`/`check`/`inspect` 内没有活世界查询、RNG、加载、调度或阻塞。
- [ ] 已开始正文绝不重放；失败如实报告。
- [ ] 切片开关关闭时串行等价测试通过。
- [ ] 开关开启时真实跨 region 重叠测试通过。
- [ ] 负对照（未知 Mixin/子类/根）保持串行。
- [ ] 诊断有界；已写双语文档；版本与限制已记录。

## 9. 核心永远不会提供

| 不提供 | 后果 |
|---|---|
| “让我的模组并行”的认证 | 你必须自己审计目标模组 |
| 给适配器的 lease/Permit/权限构造器 | 适配器不能铸造执行权 |
| 第二套调度器/线程池/效果队列 | 一切都走既有 canonical 阶段 |
| 已开始正文的重放 | 失败是终态并如实报告 |
| 按适配器提高 2048 条目 canonical 预算 | 大阶段需要通用有界窗口协调器 |
| 对任意反射/异步写入者的自动支持 | 超出信任模型范围 |
