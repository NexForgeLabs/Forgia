# Forgia Entity Safety 0.1.0

中文 | [English](README.md)

面向 Forgia 1.21.1 的服务端兼容模组。默认只声明
`cataclysm:the_leviathan`（灾变的利维坦）在主线程执行完整 Tick。

灾变 3.33 的 `TentacleHoldattack` 会调用 `ServerPlayer.startRiding`，
间接改变玩家姿态和位置。利维坦在区域工作线程 Tick 时，这会触发 Forgia 的
外部实体操作保护。本模组在 Tick 开始前把该 Boss 留在主线程，保留抓取技能、
同步返回值和每次操作只执行一次的语义。

其他实体继续使用当前调度策略，包括灾变的其他实体。已有骑乘链包含利维坦时，
Forgia 原有的骑乘规则也会把该链留在主线程。开销范围是利维坦完整 Tick 及这种
骑乘链；没有关闭实体并行、放宽存储保护、吞异常或重跑已开始的 Tick。
实际整服并行比例和性能变化仍需实测。

## 安装与确认

1. 把 `forgia-entity-safety-0.1.0.jar` 放入服务端 `mods`，客户端不需要安装。
2. 重启服务器，保留 `enableRegionThreadingEntityTicks = true`。
3. 执行 `/neoforge regionthreading compat`，确认出现
   `cataclysm:the_leviathan`，原因 `forgia_entity_safety:leviathan_player_grab`，
   来源 `forgia_entity_safety:forgia-entity-safety.json`。
4. 在测试战斗中触发触手抓取，结合 `/neoforge regionthreading status`
   检查该原因的串行 Tick 计数，以及其他实体仍在工作线程执行。

依赖 Forgia 的 `regionThreadingDeclarations` 机制，已核对
`21.1.250.101-beta` 包含所需 API。模组只包含声明资源，不注入 Mixin，
不依赖灾变类进行编译。可选灾变依赖范围为 3.33 及以后；已诊断的版本为 3.33，
后续版本的技能变化仍需验证。未安装灾变时，Forgia 会报告并忽略未知实体声明。
普通 NeoForge 或缺少声明机制的旧 Forgia 无法应用此修复。

## 为以后确认的问题增加定向保护

在 `config/regionthreading-declarations.json` 中合并对应实体 ID，例如：

```json
{
  "entities": [
    {
      "type": "anothermod:confirmed_unsafe_entity",
      "reason": "pack:confirmed_foreign_entity_mutation"
    }
  ]
}
```

替换为真实 ID，保存后重启。实例配置与模组内置的利维坦声明会合并加载。
不要使用整个命名空间或公共基类来扩大范围，否则会让无关实体也串行化。

任意已执行一半的 Java Tick 无法透明迁移线程。以后通用修复应在完整操作
产生副作用前进行线程交接，或使用这种精确的 Tick 前准入声明。
本模组只覆盖列出的实体，不承诺处理任意模组事件、AI 回调、Chunk Tick
或其他存储保护异常。

## 验证结果

- 声明与实体调度相关的 **19 项 JUnit 测试通过**。新增场景使用同一 Java 类的
  1 个受保护实体与 99 个其他模组类型实体，确认受保护正文在主线程执行一次，
  其余 99 个正文各在工作线程执行一次，归属违规为零。这是保护范围测试，
  不代表线上实际并行百分比。
- 隔离测试服使用 Forgia `21.1.250.101-beta`、灾变 `3.33`、Lionfish API `3.1`、
  Curios `9.5.1+1.21.1` 与 Java `21.0.13`，成功加载最终 JAR，
  确认正好 1 条声明生效、0 条被忽略。
- 放入利维坦、深潜者和牛各 1 只，均设置 NoAI；运行计数为
  **706 次 Worker Tick、353 次串行 Tick**，全部 353 次串行均归因于
  `forgia_entity_safety:leviathan_player_grab`。状态为 clean，正常停服 exit 0。
- 此运行验证调度与 Boss 常规 Tick，尚未复现真实玩家被抓取的战斗，
  也不构成完整线上整合包兼容性认证。

随本次构建保留的本地验证材料位于 `build/verification/`。

## 构建

需要 Python 3.11 或以后，无需联网、Java 编译或 Gradle：

```bash
python3 tools/forgia-entity-safety/build.py --install-dir /path/to/forgia-server
```

产物位于 `tools/forgia-entity-safety/build/libs/forgia-entity-safety-0.1.0.jar`，
附带 SHA-256 校验文件。安装目录检查只读取依赖，不会安装模组或修改服务器。
