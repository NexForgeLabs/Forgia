# 受控已安装服务端性能工具

[English](README.md) | 中文

`controlled_server.py` 复用了 `tools/block-entity-resource-ab/server_ab.py` 的已安装服务端控制器、一次性测试世界、精确调度冻结、完整 NBT 采集以及外部原生 tick 环/JMX 观测器。它支持原版 NeoForge、Forgia 串行、Forgia Worker 以及可选运行时诊断，无需辅助模组或新的执行子系统。每次调用都需要一个新的 `--runs` 目录、显式锁定的已安装 universal SHA256 以及预先同意的本地 EULA。绝不要将其指向生产存档。已安装的库以只读方式共享；存档/配置/日志为每次运行私有。请按顺序运行各条件，并在第二个独立 JVM 轮次中反转执行顺序。

```sh
# JDK 21；本地编译观测器（不计入基准耗时）：
javac --add-modules jdk.attach -d tmp/perf-observer tools/block-entity-resource-ab/JvmMetrics.java tools/block-entity-resource-ab/JfrEvents.java
python3 -u tools/performance/controlled_server.py \
  --runs /absolute/new/disposable/evidence \
  --installation /absolute/private/installation \
  --version 21.1.250.99-beta --universal-sha256 <exact-installed-core-hash> \
  --eula /path/to/already/accepted/eula.txt --java /path/to/jdk21/bin/java \
  --classes /absolute/path/to/tmp/perf-observer \
  --bridge --diagnostics --warmup-ticks 400 --window-ticks 400 --windows 2

python3 -m unittest discover -s tools/performance -p 'test_*.py'
python3 tools/performance/export_controlled.py --output /path/to/combined.json /path/to/run-a/result.json /path/to/run-b/result.json
```

省略 `--bridge` 可运行串行 Forgia；使用 `--stock` 配合原版版本号/哈希可运行原版 NeoForge。省略 `--diagnostics` 可关闭可选的服务端历史/JVM/任务计时，同时保留正确性计数器。`--jfr /path/to/profile.jfc` 会在预热后启动**单独的诊断运行**，并额外丢弃 200 tick。主导出器会拒绝带有采样的数据，切勿将采样运行与非采样运行的耗时混池比较。

测试夹具：种子 `813584`、超平坦、无在线玩家/模组、跨越 8 个固定区域邻域的 64 个持续燃烧原版熔炉、4 个 Worker、2 GiB 固定 G1 堆、精确 400 tick 预热与两个精确 400 tick 产出/守恒窗口（默认 64 可调为 `1..256`）。Worker 桥开启时会检查原始正文总计数；所有构建都必须产出完全等价的前后 NBT。`export_controlled.py` 会拒绝缺失/失败/采样运行或任何负载/状态/产出不匹配，并保留二进制与证据哈希。同一 JVM 内的连续窗口不构成独立 JVM 重复。

观测器仅在可保守证明的活跃区间内重建原生 100 槽 tick 环，排除冻结、快照与命令格式化阶段。端点相等可能掩盖单 tick，因此报告的环 TPS 仅为观测值，并非精确的数据包或调度追踪。主线程/Worker CPU 与内存分配各有独立观测区间。GC MXBean 时间不等同于 GC 停顿时长。阻塞监视器进入次数不代表锁等待时长。本静态熔炉夹具不推断任何玩家开销、区块生成吞吐或加载吞吐。

原始命令响应包含对新诊断命令的控制台校验，保存在私有证据中。公开的机器结果仅包含有界摘要、精确 NBT 等价判定与文件哈希，不含 RCON 凭据或世界数据。本地证据与会话命令位于 `tmp/performance-session`；保留的失败记录不得覆盖或重命名标签。
