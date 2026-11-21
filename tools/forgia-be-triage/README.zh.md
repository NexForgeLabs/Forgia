# Forgia 模组方块实体离线字节码分诊工具 (`forgia-be-triage`)

不加载、不执行第三方模组代码，直接解析模组 JAR（含嵌套 Jar-in-Jar）的 JVM `.class` 常量池、类继承链、`Code` 字节码指令与 `BootstrapMethods`（`invokedynamic` lambda 目标），对每个具体 `BlockEntity` 子类的 tick 入口构建有界调用图并输出三态分类：

- **`DEFINITELY_SERIAL`**：稳态 tick 路径包含必须主线程执行的硬串行 sink（生成/查询实体、`SavedData`、`PlayerList`、`EventBus.post`、发包广播、写静态字段等）。可安全输出到 `regionthreading-declarations.json` 的 `block_entities` 列表（只收窄，零风险）。
- **`LOCAL_CANDIDATE`**：调用图闭合且仅访问自身字段/本地状态（无副作用时推荐 `RESOURCE_SCOPED`；包含 `setChanged` / 世界方块通知时推荐 `REGION_OWNER`）。
- **`ADAPTER_CANDIDATE`**：稳态 tick 路径仅访问本地状态，但在 `initialize()` / `attachKinetics()` / `KineticEffectHandler.tick()` 等首 tick 初始化或周期性特效分支中含有可由 `check(be)` 守卫拦截的主线程操作。工具会自动生成包含真实类 SHA-256 指纹清单和守卫提示注释的 `BlockEntityExecutionAdapter` Java 骨架供人工审计。
- **`UNKNOWN`**：稳态路径包含未解析的接口分派、`getCapability`、邻居 `getBlockEntity`、反射/`MethodHandle` 或有子类覆盖，保持默认串行并列出全部阻塞原因。

## 用法

```bash
python3 tools/forgia-be-triage/triage.py /path/to/mods \
  --json build/be-triage/report.json \
  --declarations build/be-triage/regionthreading-declarations.json \
  --skeleton-dir build/be-triage/skeletons
```

## 自测

```bash
python3 -m unittest discover -s tools/forgia-be-triage -p "test_*.py"
```
