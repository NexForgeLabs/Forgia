# Canonical 协调器生命周期 / 已安装服务端耐久测试

[English](README.md) | 中文

该运行器使用已锁定的 Forgia NeoForge 安装器/universal 包驱动**已安装的 Minecraft 1.21.1 DedicatedServer**。它使用现有的混合原版数据包、RCON 与外部 JVM MXBean，不安装观测/测试模组，不注册 Adapter，也不修改生产白名单。JUnit、GameTest 与已安装服务端证据必须分开报告。

测试世界包含 64 个熔炉/高炉/烟熏炉、8 条闭合回路中的 32 个传输漏斗、8 个用于现有 Native 子集的充能禁用漏斗、酿造台、箱子、阳光探测器、真实红石时钟/灯以及 32 只持久化 NoAI 绵羊。8 个熔炉注册组由真实漏斗围栏隔开。每个精确计步窗口都会检查完整熔炉 NBT 与燃烧/熔炼/物品守恒、漏斗环守恒、普通实体年龄推进与 UUID 身份、数据包时钟/红石状态转换，以及汇总的真实 Worker/串行正文计数器。健康的核心运行要求原始 Native **与** Furnace Worker 完成计数均为正。

可选的 `--mod JAR SHA256` 目前是一个 **Create 6.0.10 加载夹具**，而非通用整合包生成器。它只复制原版已锁定的 Create jar（不含 Forgia Create 适配器）并构建两组水车/齿轮箱/石磨装置，不会授予这些 BE Worker 准入资格。运行时转换/证明或兼容性拒绝仍按拒绝如实记录。

请使用基于当前生产源码构建的隔离安装目录。运行器会拒绝不匹配的安装器/universal 哈希、缺失的权限类、已存在的输出世界以及未预先同意的 EULA 文件，并将游戏/RCON 端口绑定到 `localhost` 并生成私有 RCON 密码。典型命令如下：

```sh
python3 tools/canonical-be-lifecycle/installed_soak.py \
  --runs tmp/my-c41-core \
  --installation tmp/my-c41-installation \
  --classes tmp/my-c41-observer-classes \
  --eula /path/to/previously-accepted/eula.txt \
  --version 21.1.250.60-beta \
  --installer-sha256 INSTALLER_SHA256 --universal-sha256 UNIVERSAL_SHA256 \
  --windows 60 --window-ticks 1200 --checkpoint-every 10 \
  --unload-reload --restart-saved
```

独立观测器使用 JDK 21 编译：

```sh
javac --add-modules jdk.attach -d tmp/my-c41-observer-classes \
  tools/block-entity-resource-ab/JvmMetrics.java
```

添加 `--no-persistent-workers --no-resources` 可运行固定池/关闭资源的对照组。`--unload-reload` 复用既有区块卸载/重载验证协议；`--restart-saved` 会停服并用新 JVM 重新打开已保存世界并校验物品守恒与 32 个实体 UUID。

在冻结检查点，运行器获取真实线程转储与 **Full GC 后的存活类直方图**，要求零残留 canonical 内核/Ticket/Permit/Guard/effect/phase batch/resource Lease/Scope 对象且零运行中 Region。`RegionBlockEntityTickScope` 按设计每个 Worker 保留一个可复用空闲对象，由 `heap_scopes.py` 读取 HPROF 字段校验 `active=false` 且引用全为 `null`。

**堆转储可能包含世界数据与 RCON 凭据。** 请将所有存档、配置、原始日志、JMX 数据、转储与 jar 保存在被 git 忽略的 `tmp/` 目录中，切勿提交或上传。
