# 可选 VM 证据与原始 Mod 集成验证工具

[English](README.md) | 中文

`C6 FINAL ARCHITECTURE ACCEPTANCE: PASS`

[最终验收报告](../../region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.md) 与 [机器回执](../../region-threading/C6-FINAL-ACCEPTANCE-2026-11-15.json) 记录了干净源码 `afa7e4bc781257a756a3d0f6040cc64eebc84d62` 上的完整未过滤 Persistent 与 Fixed profile 结果（各 180 项 GameTest / 74 批次：170 通过 / 0 失败 / 10 跳过 / 0 Not Processed / 0 Aborted）。

该可选 Linux/HotSpot JDK 21 运行时为既有 Core 调用内核提供已安装方法证据与对象访问检查，不创建调度器、调用 Ticket、资源预留或执行权限。普通启动不加载该服务。

## VM 组件控制测试

需要 JDK 21 及其头文件、C 编译器与**全新的**输出目录：

```sh
export JAVA_HOME=/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
python3 tools/runtime-proof/verify_runtime.py \
  --java-home "$JAVA_HOME" \
  --output tmp/runtime-proof-components-new
```

## 关联原始 Mod 测试

所有运行目录必须为新建目录，并保留 stdout、原始 XML 与真实进程退出码：

```sh
./gradlew --offline --no-configuration-cache --no-parallel --console=plain \
  --max-workers=1 -PforgiaVersion=21.1.250.60-beta \
  -I tools/runtime-proof/real_mod.gradle \
  -PtrustedModJar=/local/game_console-0.31.0-alpha.61.jar \
  -PtrustedModRun=tmp/runtime-proof-associated-new/server \
  -PgameTestFilter=saveReload,regionC6Worker,regionC6Writer,regionC6Failure \
  :tests:runCanonicalGameTestServer
```

更多细节与保留约束请参阅 [English README](README.md)。
