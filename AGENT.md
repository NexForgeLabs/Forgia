# AGENT.md — Forgia Engineering Rules & Discipline

## 1. 第一纪律：永远不要让核心兼容特定模组（Core Must Stay Mod-Agnostic）

- **严禁在 Forgia 核心（`src/main/...`、`patches/...`、`projects/neoforge/...`）中硬编码任何第三方模组名称、命名空间、方块实体 ID、实体 ID 或模组专属类名/逻辑**。
- 当某个模组与区域多线程核心不兼容时，正确的处理流程是：
  1. **补齐通用 Adapter API**：如果现有 `RegionAdapterApi` / `ParallelApi` 缺少所需的通用隔离、跨区通信、事件路由、沙盒谓词或回退能力，先在核心中设计并暴露**模组无关（Mod-Agnostic）的通用 API**；
  2. **编写独立的 Adapter Compat Mod**：在 `tools/forgia-*-compat` 或 `adapters/*` 中编写独立的兼容模组（通过 Mixin、并发容器替换、以及调用 `RegionAdapterApi`），让**模组去适配核心**，而不是让核心去迎合模组。

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
