# 整合包测试工具

[English](README.md) | 中文

这些工具用于在已安装的服务端副本中选择 jar 进行测试。单次成功启动仅证明该选定组合与构建可启动，不代表所有组合兼容；缺失依赖、超时与关闭的 Worker 切片均不算兼容性通过。

## 负载测试

`run_loadtest.sh` 调用 `loadtest.py`，**每次仅运行一个服务端**，请显式使用 JDK 21：

```bash
# mods-list.txt 每行一个精确 jar 文件名（需包含完整依赖）
tools/modpack-matrix/run_loadtest.sh /path/to/installed-pack /path/to/mods-list.txt smoke-001 1 30

# smoke 通过后运行 30 分钟负载测试，每 5 分钟自动采样一次
tools/modpack-matrix/run_loadtest.sh /path/to/installed-pack /path/to/mods-list.txt load-001 30 300
```

位置参数后可附加 `--java /path/to/java`、`--runs /disk/runs`、`--boot-timeout 360`、`--warmup 30`、`--loader-version VERSION` 以及 `--no-regions`（串行对照）。每个实例拥有私有的配置、KubeJS 脚本、mods、存档与日志，仅共享只读库文件。

测试夹具包含 36 个已加载区块、4 个高空平台、12 个带标签生物（含村民）、作物、容器/漏斗/熔炉产线以及跨区域边界的比较器读取箱子。PASS 要求实体、方块实体、区块与刷怪 Worker 计数均推进，且无安全计数失败、物品守恒并干净停服。

每次运行的输出目录包含：`result.json`、`commands.jsonl`、`diagnostics.raw.txt`、`samples.jsonl` / `samples.csv`、`server.log`、`mods-list.txt` 与保留的存档。单元测试：

```bash
python3 -m unittest discover -s tools/modpack-matrix -p test_loadtest.py
```

早期的 `run_group.sh`、`scan.py` 与 `bisect_mods.py` 仅用于启动分组排查，请使用上述负载测试脚本获取实际运行时测量数据。
