# 生产就绪复审工具

[English](README.md) | 中文

本工具包仅读取当前检出代码与冻结证书，不启动/安装服务器、不修改线上配置/存档、不构建二进制、不加载原生服务，也不直接认证上线部署。需要 Python **3.11+** 与 Git。

在仓库根目录先执行 fetch，再输出到**新的**证据文件：

```sh
git fetch --prune origin
python3 tools/production-readiness/audit.py \
  --output /absolute/private/new-run/source-audit.json
```

在持有证据的机器上可要求同时校验私有保留证据树与本地构建产物：

```sh
python3 tools/production-readiness/audit.py \
  --verify-retained-evidence --require-build-artifacts \
  --output /absolute/private/new-run/full-preservation-audit.json
```

源码保留校验通过返回 0；任何校验失败或缺失必要输入返回非零。`--output` 拒绝覆盖已有路径。

`config/` 目录包含覆盖**全部 24 个配置键**的参考模板：
- [serial.toml](config/serial.toml)：全部 16 个布尔开关为 `false`，用于同构建串行基线/回滚。
- [c6-fixed.toml](config/c6-fixed.toml)：C6 已验收的 Fixed 配置值（资源开关开启）。
- [c6-persistent.toml](config/c6-persistent.toml)：C6 已验收的 Persistent 配置值。

单元测试：

```sh
python3 -m unittest discover -s tools/production-readiness -p test_audit.py -v
```

相关文档：[就绪报告](../../region-threading/PRODUCTION-READINESS.md)、[配置矩阵](../../region-threading/PRODUCTION-CONFIGURATION.md)、[Canary/回滚流程](../../region-threading/CONTROLLED-CANARY.md)、[运行时证明工具](../runtime-proof/README.md)、[整合包负载测试](../modpack-matrix/README.md)。
