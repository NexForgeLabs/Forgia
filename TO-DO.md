# Forgia 后续优化与研究清单（TO-DO）

## 0. 待部署的已构建产物（Ready to Deploy）
当前本地已编译就绪（提交 `36b5c3a45`），下次停服维护窗口可直接上传替换：
- **核心端**：
  - `projects/neoforge/build/libs/neoforge-21.1.250.283-beta-universal.jar`
  - `/tmp/forgia-install-latest/libraries/net/neoforged/neoforge/21.1.250.283-beta/neoforge-21.1.250.283-beta-server.jar`
- **并行兼容模组**：
  - `tools/forgia-create-parallel-compat/build/libs/forgia-create-parallel-compat-1.0.0.jar`
- **已包含改动**：
  1. 精细拆解 `fxntstorage`：`RegionAdapterApi.denyRegionSandboxedBlockEntityType` 仅拦截 `storage_controller_entity` / `storage_interface_entity` / `storage_interface_filtered_entity`，放行 `storage_box_entity` 等到 Worker；
  2. 新增 `skipUnloadedNeighborhoodSpawning`（默认 `true`），跳过模拟距离最外圈邻域未全加载区块的自然刷怪，避免触发主线程 `fallbackState()` 全图实体扫描；
  3. 正式移除 `FIXED_3X3` 模式及 `regionThreadingFixedRegionChunks`、`enableRegionThreadingPackedRegionLeases`，并将 `PlayerTick` 阶段规划与 `FurnaceWorkWaves` 原生适配到 `DYNAMIC_CLUSTER`。
- **部署时注意**：停服后清理服务器 `/config/regionthreading-learned.json` 中残留的旧类型记录（如已修复的条目），再启动服务器。

---

## 1. 进一步减少模组对主线程的依赖（核心研究方向）

### 1.1 原生放行 NeoForge `FakePlayer` 区域沙箱权限（高收益、低风险）
- **背景**：`create:deployer`（机械手）及各类模组自动化放置/破坏器内部依赖 `net.neoforged.neoforge.common.util.FakePlayer`（继承自 `ServerPlayer`），在 Worker 上触发玩家状态保护后全数退回主线程。
- **方案**：
  - 在 `RegionPlayerStateScope`、`ServerPlayer` 边界守卫中对 `FakePlayer` 放开当前区域内自有状态读写（背包、手持物品、属性、位置）；
  - 仅将其向真实客户端发送的音效、粒子、方块更新包经由 `RegionAdapterApi.postGlobal` 延迟提交；
  - 100% 基于 NeoForge 自带类，完全符合核心模组无关原则。

### 1.2 跨区边界“有序双区租约 / 4 色棋盘波次”（消灭边界回退）
- **背景**：位于区域边界上的方块实体（跨区漏斗、溜槽、传送带、管道、机械合成器等）因读写相邻 1 格方块或 `BlockCapability` 触发 `storage_access` / `capability_access`，被永久固定到主线程 `beSerial`。
- **方案**：
  - 新增边界并行波次：按 `(regionX, regionZ)` 全局全序同时获取相邻区域租约（严格有序加锁防止 A-B 死锁），或按 4 色棋盘分组执行，让边界机器也能在 Worker 上安全并发读写两侧状态。

### 1.3 `LevelTickEvent` / `ServerTickEvent` 逐监听器剖析与跨维度并行
- **背景**：线上 `levelTickEvents` 曾达 `~5.27 ms/tick`、`serverTickEvents` 约 `1~2 ms/tick`，当前缺少逐监听器维度的细粒度耗时归因；同时 4 个维度的主线程准备阶段目前完全串行。
- **方案**：
  1. 增加轻量级事件监听器计时命令（如 `/forgia events top 10`），直接定位最耗时的模组事件监听器；
  2. 评估对彼此独立的维度并发派发维度级非全局准备任务（如 `chunkSource` 准备与互不干扰的维度事件）。

### 1.4 沙箱异常自动堆栈落盘 & `RegionAdapterApi` 并发工具原语
- **背景**：目前 `demoteSandboxedBlockEntityOnError` 仅在 `regionthreading-learned.json` 记录异常类名（如 `NullPointerException`），缺乏触发堆栈上下文；第三方模组常因全局 `HashMap`/`ArrayList` 单例引发并发异常。
- **方案**：
  1. 将沙箱降级的前几帧关键调用栈异步写入 `logs/forgia-sandbox-demotions.jsonl`，实现新模组问题一键归因；
  2. 在 `RegionAdapterApi` 中暴露标准分段锁（Striped Lock）和快照集合工具，降低 `tools/forgia-*-compat` 适配外部模组全局状态的开发成本。

### 1.5 基于 `DYNAMIC_CLUSTER` 的选择性异步实体切片（`ENTITY_TICK` 演进）
- **背景**：当前因主线程 `PlayerTick`、Contraption、Sable 物理与后台实体争抢租约，`enableAsyncRegionEntityTicks` 保持关闭，导致单个热点区（如 `overworld@-38,-54`）仍会在栅栏处拖慢全局 Tick。
- **方案**：
  - 探索将无玩家、无 Contraption/物理耦合的野外区域优先放入异步实体调度，仅对高耦合区域保留同步栅栏；
  - 评估在 `RegionOverloadGovernor` 中计入 `RegionIndependentTickScheduler` 异步方块实体耗时（可配置权重/开关），避免重负载基地长期绕过过载隔离。

---

## 2. 其余待观察性能点
- **验证 `21.1.250.283-beta` 线上表现**：观察 `fxntstorage:storage_box_entity` 放行与 `skipUnloadedNeighborhoodSpawning` 生效后的 `beSerial` 与 `chunkChunks` 下降幅度；
- **`RegionSpawnState.buildForPlan` 并行化**：将 `executeAsyncSpawnBatch` 中主线程串行遍历各区域构建刷怪状态的工作进一步移入 Worker；
- **Create 剩余主线程方块实体研究**：排查 `create:mechanical_crafter`、`create:mechanical_mixer`、`create:blaze_heater` 在 `forgia fences` 中的实际瓶颈，评估是否可在 `forgia-create-parallel-compat` 中安全并行化。
