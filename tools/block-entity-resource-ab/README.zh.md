# 已安装服务端资源所有权 A/B 测试工具

[English](README.md) | 中文

这是一个使用锁定安装器与 universal 哈希的真实已安装 Minecraft 服务端夹具（历史命令默认针对冻结的生产 `.58` 二进制），用于在**两种条件均已启用 Furnace waves** 的前提下测量资源所有权的增量开销。它不构成整合包/多人结论，也不代表对原版/原版 NeoForge 的对比。

观测器是一个通过 JVM 内置本地管理服务连接的**外部只读本地 JMX 客户端**，在预热前启动。原版 100 槽 Minecraft tick 环与标准 JVM MXBean 提供完整服务端 tick 时长、CPU、GC、堆及主线程/Worker 内存分配数据；不安装辅助模组、Mixin、字节码插桩 agent 或新准入逻辑。

## 复现步骤

前置条件：JDK 21、预先同意的本地 `eula.txt`、已安装的库目录与 `projects/neoforge/build/libs/` 中的精确安装器。禁止将 `--runs` 指向真实存档。

```sh
export JAVA_HOME=/absolute/path/to/jdk-21
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p tmp/be-resource-tail/metrics-classes
javac --add-modules jdk.attach -d tmp/be-resource-tail/metrics-classes \
  tools/block-entity-resource-ab/{JvmMetrics,JfrEvents}.java
python3 -m unittest discover -s tools/block-entity-resource-ab -p 'test_*.py' -v

python3 -u tools/block-entity-resource-ab/server_ab.py \
  --runs tmp/be-resource-tail/matrix \
  --installation /absolute/path/to/private-installed-58 \
  --eula /absolute/path/to/already-accepted/eula.txt \
  --java "$JAVA_HOME/bin/java" --classes tmp/be-resource-tail/metrics-classes \
  --counts 2 64 256 --layouts chunk chunks regions --workers 1 2 4 8 \
  --warmup-ticks 400 --window-ticks 200 --windows 2

python3 tools/block-entity-resource-ab/export_results.py \
  tmp/be-resource-tail/matrix tmp/be-resource-tail/exported-matrix.json
```

完整矩阵按顺序执行，每组包含 400 个丢弃的预热 tick 与两个精确 200 tick 窗口（`--warmup-ticks` 与 `--window-ticks` 须为 200 的正整数倍）。`--rounds 2` 可在第二个独立 JVM 对中反转执行顺序。
