# 方块实体资源所有权开销探针

[English](README.md) | 中文

这是一个独立的 JDK 21 **微基准合成开销探针**，不执行 Minecraft 正文、Worker 派发、外层 Region 获取、回调、跨核竞争或服务端 MSPT。它直接调用生产 Forgia 类，不使用 Mockito、复制的所有权实现或耗时通过/失败断言。

## 输入与测量阶段

共 12 组用例：`2/16/64/256` 单元 × `4/64/256` 共享 READ 对象。每个单元拥有自身的 source/input/output WRITE 以及一个公共的区域局部 chunk 形态 READ。

每个样本会测量（含内存分配）：
1. `capture`：构造完整 claim（共享模式下含一次新共享发布）。
2. `plan`：生产所有权一致性检查与依赖规划。
3. `revalidate`：执行**第二次新鲜捕获**与资源集合相等校验。
4. `reserve`：新建所有权协调器、生产全有或全无预留及租约。
5. `scope`：顺序进入每个租约、每单元五次 `require` 检查后退出。
6. `release`：关闭所有权。`total` 为第 1 步至第 6 步的直接计时。

默认先执行 **2,000 次预热/用例，再采样 101 次**，报告 p99/max。参见[调查与服务端 A/B](../../region-threading/BLOCK-ENTITY-RESOURCE-TAIL-AND-SERVER-AB.md)与[`.59` 热路径分配对比](../../region-threading/BLOCK-ENTITY-RESOURCE-HOT-PATH-REDUCTION.md)。完整编译与运行命令见 [English README](README.md)。
