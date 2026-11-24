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

### 1.1 [已完成] 原生放行 NeoForge `FakePlayer` 区域沙箱权限
- 已在 `21.1.250.286-beta` 落地并上线验证：全局 MSPT 从 `48.56ms` 降至 `39.35ms`，方块实体并行率提升至 `89.0%`。

### 1.2 [暂缓] 跨区边界“有序双区租约 / 4 色棋盘波次”
- 实测 `forgia fences` 数据表明：所有边界串行方块实体实际执行总计仅占 `0.85 ms/tick`（`create:belt` 约 `0.54 ms/tick`），收益远低于主线程规划与刷怪计数开销，暂缓实施。

### 1.3 [已完成剖析] `LevelTickEvent` / `ServerTickEvent` 逐监听器剖析与跨维度并行
- 已在 `21.1.250.287-beta` 落地 `/forgia events start|stop|top|reset`（`TickEventProfiler`）；
- 实测 6,287 Tick 所有模组 `ServerTick` + `LevelTick` 监听器合计仅 `1.332 ms/tick` 且高度分散，跨维度事件并行暂无必要。

### 1.4 [已完成堆栈探针] 沙箱异常自动堆栈落盘 & `RegionAdapterApi` 并发工具原语
- 已在 `21.1.250.287-beta` 落地 `SandboxDemotionProbe`（`logs/forgia-sandbox-demotions.jsonl`）；
- 并发原语（分段锁 / 快照集合）保留待后续新模组适配时按需补充。

### 1.5 [进行中 / 高优] 削减主线程规划与准备开销（`tickBlockEntities` + `RegionSpawnState`）
- 将沙箱方块实体的逐实例状态检查（`canRunSandboxedBlockEntityInstance`）从主线程 `prepareNative` 下放到 Worker 侧执行前；
- 将异步刷怪的 `RegionSpawnState.buildForPlan`（区域实体计数与局部邻域构建）从主线程下放到 8 个 Region Worker 并行执行。

### 1.5 基于 `DYNAMIC_CLUSTER` 的选择性异步实体切片（`ENTITY_TICK` 演进）
- **背景**：当前因主线程 `PlayerTick`、Contraption、Sable 物理与后台实体争抢租约，`enableAsyncRegionEntityTicks` 保持关闭，导致单个热点区（如 `overworld@-38,-54`）仍会在栅栏处拖慢全局 Tick。
- **方案**：
  - 探索将无玩家、无 Contraption/物理耦合的野外区域优先放入异步实体调度，仅对高耦合区域保留同步栅栏；
  - 评估在 `RegionOverloadGovernor` 中计入 `RegionIndependentTickScheduler` 异步方块实体耗时（可配置权重/开关），避免重负载基地长期绕过过载隔离。

---

## 2. 其余待研究性能优化方向
- **边界区块 `CHUNK_TICK` 上 Worker**：当前 `4×4` 网格下约 `45%` 的区块处于区域边界（`boundary 278,788` vs `workers 339,217`），随机刻留在主线程；后续可借助 `RegionNeighborUpdateScope` / 4 色棋盘波次将其下放到 Worker。
- **跨维度 `ServerLevel` 独立准备阶段并行化**：探索在 `DimensionDataStorage` 安全前提下，将多维度的 `chunkSource` 准备等互不干扰阶段并发派发。
- **`Lootr` `NewTickingData` 并发保护**：在外部兼容模组中为 `NewTickingData` 的全局 `Object2ObjectOpenHashMap` 加并发保护，将 `lootr` 容器也放回 Worker。
- **Create 剩余主线程方块实体研究**：排查 `create:mechanical_crafter`、`create:mechanical_mixer`、`create:blaze_heater` 等在少数场景回退的原因。
