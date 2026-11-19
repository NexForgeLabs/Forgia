# Parallel API 仅编译期示例

[English](README.md) | 中文

`ContainerLinkExample.java` 仅依赖 Minecraft/NeoForge 类型与公开的 Forgia 门面。它在服务器处于静止状态时建立目标引用，从源端自身的已准入 ticker 中提交一次完整的有界传输，并在不阻塞 worker 的前提下轮询其显式结果。

通过 `./gradlew --offline :neoforge:parallelApiJar` 构建门面 jar，并将生成的 `projects/neoforge/build/libs/*-parallel-api.jar` 与常规 NeoForge 编译依赖一起作为 `compileOnly` 引入。不要打包第二份 API 类。可移植模组必须在确认存在该可选 capability 之后，再加载这个 Forgia 专用入口。

该示例并不宣称机器 ticker 的其余部分也是 worker 安全的。请审计其本地回调并单独配置显式类型准入。目标被替换后需重新建立连接；不要重试已部分应用状态的失败操作，也不要把入队成功当作传输完成。

关于侧边/槽位规则、生命周期、失败与线程契约，请参阅[完整协议](../../region-threading/CROSS-REGION-OPERATIONS.md)。真实服务端 GameTest `blockEntityOwnerOperations` 提供了另一个已注册的示例方块实体，并通过同一公开 API 验证原生 worker 提交。
