# AGENT.md — Forgia Engineering Rules & Discipline

## 1. 第一纪律：永远不要让核心兼容特定模组（Core Must Stay Mod-Agnostic）

- **严禁在 Forgia 核心（`src/main/...`、`patches/...`、`projects/neoforge/...`）中硬编码任何第三方模组名称、命名空间、方块实体 ID、实体 ID 或模组专属类名/逻辑**。
- 当某个模组与区域多线程核心不兼容时，正确的处理流程是：
  1. **补齐通用 Adapter API**：如果现有 `RegionAdapterApi` / `ParallelApi` 缺少所需的通用隔离、跨区通信、事件路由、沙盒谓词或回退能力，先在核心中设计并暴露**模组无关（Mod-Agnostic）的通用 API**；
  2. **编写独立的 Adapter Compat Mod**：在独立的兼容模组仓库（`NexForgeLabs/forgia-compat-mods`）或 `adapters/*` 中编写独立的兼容模组（通过 Mixin、并发容器替换、以及调用 `RegionAdapterApi`），让**模组去适配核心**，而不是让核心去迎合模组。

---

## 2. 构建、补丁与部署纪律

1. **Minecraft 原版类补丁同步**：
   - 修改 `projects/neoforge/src/main/java/net/minecraft/...` 后，必须运行 `./gradlew :neoforge:genPatches` 更新 `patches/.../*.patch`，否则 `:neoforge:installerJar` 不会将改动打入 `server.jar` 的 binpatches。
2. **Java 工具链**：
   - 构建与测试统一使用 JDK 21：`-Dorg.gradle.java.home="$JAVA_HOME"`。
3. **无硬编码可调阈值**：
   - 所有影响调度、节流、冷却、容忍阈值的参数必须暴露在 `ForgiaConfig`（`config/forgia.toml`）中，并附带按分类排版的中英双语注释。
4. **安全停服部署**：
   - 覆盖运行中的 `server.jar` / `universal.jar` 前必须先执行 `save-all` 并确认服务器已完全 `offline`，避免 Zip 索引变化引发 `NoClassDefFoundError`。

---

## 3. 服务器卡顿排查与 Spark 火焰图解析规范

当用户反馈服务器卡顿、TPS 不稳或 MSPT（P95 / Max）偏高时，**优先通过 Spark 内置 Java 探针（`--force-java-sampler`）获取火焰图进行分析**（避免 JDK 25 等环境下 async-profiler 采集不到数据的问题）：

1. **优先采集命令**：
   - 定位 P95 / Max 尖峰（推荐）：
     ```text
     spark profiler start --only-ticks-over 45 --timeout 20 --force-java-sampler
     ```
   - 排查整体负载或 Region Worker 耗时：
     ```text
     spark profiler start --thread * --timeout 20 --force-java-sampler
     ```
   - 采样完成后，从 `logs/latest.log` 获取上传链接 `https://spark.lucko.me/<ID>`。

2. **拉取元数据与原始火焰图数据**：
   - **元数据 JSON**（TPS / MSPT / 堆内存 / 捕获 Tick 数）：
     ```bash
     curl -sL "https://spark.lucko.me/<ID>?raw=1" -o /tmp/spark_meta.json
     ```
   - **原始火焰图 Protobuf（`SamplerData`）**（须使用 `GET`，`bytebin.lucko.me` 不支持 `HEAD`）：
     ```bash
     curl -sL "https://bytebin.lucko.me/<ID>" -o /tmp/spark.pb
     ```

3. **Python 零依赖解析 `SamplerData` Protobuf 协议**：
   - 顶层 `field 2`（wire type `2`）：repeated `ThreadNode`。
   - `ThreadNode`：`field 1` = 线程名（UTF-8 string），`field 3` = repeated `StackTraceNode`。
   - `StackTraceNode`：`field 3` = `className`（UTF-8 string），`field 4` = `methodName`（UTF-8 string），`field 8`（wire type `2`）= packed little-endian `double`（`<d`）时间数组（逐 8 字节解包求和即为该节点累计耗时 ms；兼容旧格式 `field 1` 单个 `<d`）。
   - 按 `className.methodName` 聚合排序，区分 `Server thread` 与 `NeoForge Region Worker` 热点及第三方模组包名；必要时结合 `logs/tick-diagnostics/flight-*.txt` 与 `logs/forgia-low-tps-probe.jsonl` 交叉定位单帧长卡顿（如定时存档、区块卸载积压、租约阻塞）。
