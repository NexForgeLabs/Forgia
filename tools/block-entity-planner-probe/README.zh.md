# 方块实体规划器开销探针

[English](README.md) | 中文

这个独立的 JDK 21 工具用于对比现有规划器与仅返回有向依赖波次的实验性规划器。它不执行任何 Minecraft 正文、不授予所有权，也不改变生产调度器。参考规划器还会计算依赖边与连通分量统计；原型刻意将这部分诊断工作排除在执行结果之外。

该工具对 10,000 个随机种子用例验证精确的波次等价性，并分别检查每一类原始有向冲突（读/读、写/读、读/写、共享写、同资源读写、排他围栏及不同世界标识），同时验证 `A(X), B(X,Y), C(Y)` 反例与实测 64/256/1024/2048 单元模式。验证与输入构造均在计时之外完成。每次测量采用 12 轮预热对与 21 个交替执行顺序的样本，并读取当前线程的已分配字节计数器。

从仓库根目录使用 JDK 21 先编译核心，再运行探针：

```sh
./gradlew --offline --no-configuration-cache --no-parallel --max-workers=1 \
  -PforgiaVersion=21.1.250.55-beta :neoforge:compileJava

mkdir -p tmp/be-optimization/probe-classes
javac -proc:none -cp projects/neoforge/build/classes/java/main \
  -d tmp/be-optimization/probe-classes \
  tools/block-entity-planner-probe/PlannerCostProbe.java
java -Xms512m -Xmx1g -XX:+UseG1GC \
  -cp tmp/be-optimization/probe-classes:projects/neoforge/build/classes/java/main \
  PlannerCostProbe 10000 21 > tmp/be-optimization/planner-probe.jsonl
```

参数依次为随机用例数与样本数。输出为 JSON Lines。参见[已记录结果](../../region-threading/BLOCK-ENTITY-PLANNER-PROBE.json)与[优化方案](../../region-threading/BLOCK-ENTITY-OPTIMIZATION-PLAN.md)。此处结果不代表真实服务端 MSPT 测量或整体并行加速结论。
