# 性能对比：原版 / 串行 vs. Forgia

<div class="lang-pill-bar">
  <a href="../benchmarks.en/">English</a>
  <a class="active" href="../benchmarks.zh/">中文</a>
</div>

Forgia 明确区分 **区域线程已取得显著加速的场景**（高密度实体、多人分散玩家调度、随机刻、自然刷怪）与 **单线程仍更快的场景**（轻负载静止方块实体）。以下所有基准均保留完整状态或 NBT 等价性校验，且所有并行开关默认保持 `false`。

---

## 1. 核心负载对比总览

<div class="perf-grid">
  <div class="perf-card">
    <div class="perf-card-top">
      <h3>1024 实体切片（牛 / 村民）</h3>
      <span class="perf-delta">加速 2.1× – 4.5×</span>
    </div>
    <p class="perf-sub">4 核 8 线程，JDK 21，ZGC，固定 3×3 区域，4 Workers（MSPT 越低越好）。</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">1024 牛 · 关闭（串行）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">27.3 mspt</span></div>
      <div class="bar-row"><span class="bar-label">1024 牛 · 区域化</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:22%"></div></div><span class="bar-val">6.0 mspt</span></div>
      <div class="bar-row"><span class="bar-label">1024 村民 · 关闭</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:100%"></div></div><span class="bar-val">54.5 mspt</span></div>
      <div class="bar-row"><span class="bar-label">1024 村民 · 区域化</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:47%"></div></div><span class="bar-val">25.7 mspt</span></div>
    </div>
    <p class="perf-note">200 tick 预热 + 600 tick 测量，两次重复；村民场景含床与工作站 POI 查询。</p>
  </div>

  <div class="perf-card">
    <div class="perf-card-top">
      <h3>32 真实 TCP 客户端（n32-spread）</h3>
      <span class="perf-delta">比 .154 快 14.7× · 超越串行 6.4%</span>
    </div>
    <p class="perf-sub">32 名玩家分散在不同区域，10s 预热 + 30s 测量，持久化 Worker（MSPT 越低越好）。</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">.154 Persistent 开</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">58.30 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 串行（关）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:14%"></div></div><span class="bar-val">4.24 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 2 Workers 开</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:14%"></div></div><span class="bar-val">4.22 ms</span></div>
      <div class="bar-row"><span class="bar-label">.221 4 Workers 开</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:12%"></div></div><span class="bar-val">3.97 ms</span></div>
    </div>
    <p class="perf-note">两阶段调度开销从 2.450 降至 0.918 ms/tick；Ping p99 从 314.11 ms 降至 6.35 ms。</p>
  </div>

  <div class="perf-card">
    <div class="perf-card-top">
      <h3>64 燃烧熔炉（Resources 开启）</h3>
      <span class="perf-delta">快 1.67× · 分配降 72%</span>
    </div>
    <p class="perf-sub">Seed 813584 平坦地形，400 预热 + 2×400 测量 tick，4 Workers，2 GiB G1，NBT 全量等价。</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">.98 Lookahead 复用前</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">10.36 ms</span></div>
      <div class="bar-row"><span class="bar-label">7efd88d 优化前基线</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:22%"></div></div><span class="bar-val">2.24 ms</span></div>
      <div class="bar-row"><span class="bar-label">Forgia 7efd88d 优化</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:13%"></div></div><span class="bar-val">1.34 ms</span></div>
      <div class="bar-row"><span class="bar-label">Forgia 串行 / 原版</span><div class="bar-track"><div class="bar-fill bar-fill--serial" style="width:4%"></div></div><span class="bar-val">0.35 / 0.22 ms</span></div>
    </div>
    <p class="perf-note">分配速率 253 → 18.68 → 5.18 MiB/s（较基线降为 1/3.61）。极轻静止方块实体仍是串行更快。</p>
  </div>

  <div class="perf-card">
    <div class="perf-card-top">
      <h3>6000 Tick 混合生产长稳（Soak）</h3>
      <span class="perf-delta perf-delta--neutral">均值较关闭 0.976×</span>
    </div>
    <p class="perf-sub">自然生成地形，含村民、生物、漏斗/熔炉线、农场、跨边界红石钟与移动假玩家。</p>
    <div class="bar-group">
      <div class="bar-row"><span class="bar-label">延迟跨区写入前（开）</span><div class="bar-track"><div class="bar-fill bar-fill--before" style="width:100%"></div></div><span class="bar-val">7.35–8.28 ms</span></div>
      <div class="bar-row"><span class="bar-label">同版关闭（串行）</span><div class="bar-track"><div class="bar-fill bar-fill--stock" style="width:82%"></div></div><span class="bar-val">6.25–6.70 ms</span></div>
      <div class="bar-row"><span class="bar-label">延迟写入+解除内部（开）</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:80%"></div></div><span class="bar-val">6.13–6.72 ms</span></div>
      <div class="bar-row"><span class="bar-label">持久池 + 打包租约</span><div class="bar-track"><div class="bar-fill bar-fill--forgia" style="width:84%"></div></div><span class="bar-val">6.69 ms</span></div>
    </div>
    <p class="perf-note">0 所有权违规、0 tick 失败、6200/6200 红石一致、100% 已派发区块跑在 Worker 上。</p>
  </div>
</div>

---

## 2. 子系统切片对比（4 核 8 线程 ZGC 套件）

| 负载 | 关闭（服务器线程） | 区域化（4 Workers） | 加速比 |
|---|---:|---:|---:|
| 1024 牛 | 27.3 / 19.3 mspt | 6.0 / 5.8 mspt | **4.5× / 3.4×** |
| 1024 僵尸 | 22.8 / 22.2 mspt | 6.6 / 8.8 mspt | **3.4× / 2.5×** |
| 1024 村民（床 + 工作站） | 54.5 / 52.0 mspt | 25.7 / 22.0 mspt | **2.1× / 2.4×** |
| 1024 chunk 随机 tick（9×9） | 5.59 / 5.87 ms 切片 | 4.80 / 4.64 ms | **1.15×–1.27×** |
| 1024 chunk 随机 tick（3×3，解除内部规则） | 6.88–6.92 ms 切片 | 5.54 ms | **1.24×–1.25×** |
| 289 chunk 自然刷怪 | 4.13–4.62 ms 切片 | 2.79–3.93 ms | **1.15×–1.65×** |
| 2048 漏斗 | 3.27 mspt | 3.74–3.97 mspt | 0.82×–0.87×（更慢 · **待优化**） |
| 4096 熔炉（`7efd88d` 之前历史基线） | 2.60 mspt | 2.70–3.34 mspt | 0.78×–0.96×（更慢 · **待优化**） |

---

## 3. 方块实体热路径优化专项（`main @ 7efd88d`）

使用 `tools/performance/controlled_server.py` 测量（8 个区域共 64 个持续燃烧原版熔炉、完整 3×3 已加载邻域、400 预热 + 2×400 测量 tick、4 持久化 Worker、开启 `enableRegionThreadingBlockEntityResources = true`、2 GiB G1 堆）。每次运行均核验每窗口 128 个产物、库存守恒、`furnace.nativeCompleted == 25600`、`resource.refusedWaves == 0`，且完整前后 NBT SHA256（`bc74013f2c5b`、`2ca340b85b64`）完全一致。

| 版本 / 条件（Window 0 / Window 1） | 均值 (ms) | P50 (ms) | P95 (ms) | P99 (ms) | 主线程 CPU (s) | Worker CPU (s) | 分配速率 (MiB/s) |
|---|---:|---:|---:|---:|---:|---:|---:|
| `.98` Lookahead 复用之前 | 10.38 / 10.43 | 10.13 / 10.11 | 10.87 / 11.49 | 25.05 / 24.18 | 4.42 / 4.69 | 0.79 / 0.82 | 252.82 / 253.40 |
| `7efd88d` 优化前本地基线 | 2.264 / 2.215 | 2.227 / 2.167 | 2.468 / 2.453 | 3.343 / 3.338 | 1.852 / 1.944 | 0.864 / 0.921 | 18.72 / 18.64 |
| `7efd88d` 优化后（首轮测得） | 1.202 / 1.098 | 1.173 / 1.083 | 1.364 / 1.213 | 1.818 / 1.607 | 1.425 / 1.465 | 1.056 / 1.067 | 5.25 / 5.22 |
| `7efd88d` 最终提交版 | **1.354 / 1.330** | **1.233 / 1.259** | **1.671 / 1.724** | **2.175 / 2.509** | 1.517 / 1.613 | 1.128 / 1.239 | **5.20 / 5.16** |
| 同版 Forgia 串行（关闭 `--bridge`） | 0.354 / 0.342 | 0.297 / 0.305 | 0.555 / 0.517 | 0.989 / 0.821 | 1.483 / 1.840 | 0.000 / 0.000 | 0.46 / 0.44 |
| 原版 NeoForge `21.1.249`（历史对照） | 0.193–0.260 | 0.182–0.240 | 0.239–0.313 | 0.528–0.615 | 1.056–1.097 | 0.000 | 0.37–0.40 |

**结论**：相对优化前 `BlockEntityResources` 基线，`7efd88d` 将 64 熔炉 MSPT 均值降低 **40.1%（快 1.67 倍）**、P99 降低 **1.43 倍**、分配速率降低 **72.3%（降至 1/3.61）**。同时我们如实标注（**待优化**）：在 64 熔炉这类极轻的静止方块实体负载上，4 线程同步、资源 proof 与 join 的固定开销仍使 worker 路径比同版串行（`0.348 ms`）慢约 **3.86 倍**，因此方块实体开关继续保持默认关闭。

---

## 4. 32 人玩家调度专项优化（`.221` P5）

32 个真实 TCP 客户端 `verify_player_scale.py`（`n32-spread`，10s 预热 + 30s 测量，跨区攻击 `20→19` 验证通过）：

| 版本与线程池配置 | Tick 均值 (ms) | Tick P50 (ms) | Tick P99 (ms) | Ping P99 (ms) | 对比结论 |
|---|---:|---:|---:|---:|---|
| `.154` Persistent 开 | 58.297 | 55.900 | 90.793 | 314.110 | 调度优化前历史基线 |
| `.221` Persistent 关（串行，2 轮均值） | 4.237 | 4.073 | 6.134 | 6.027 | 同版串行基线 |
| `.221` Persistent 开（2 Workers，r2 / 2 轮均值） | 4.223 / 4.332 | 4.082 / 4.182 | 5.670 / 6.114 | 6.179 / 6.290 | 与串行持平（比 `.154` 开快 ~13.5×） |
| `.221` Persistent 开（4 Workers） | **3.965** | **3.800** | 6.517 | 6.346 | **比同版串行降 6.4%（P50 降 6.7%）/ 比 `.154` 开快 14.7×** |

---

## 5. 10 分钟 32 Fake Player 全并行 vs 原版串行综合耐久（`FullParallelSoakGameTests`）

使用 `tests/src/main/java/net/neoforged/neoforge/debug/level/FullParallelSoakGameTests.java`（`:tests:runFullParallelSoakGameTestServer` 与 `:tests:runFullSerialSoakGameTestServer`，相同世界种子 `4242424242`、32 个 `ServerPlayer` + `EmbeddedChannel` 持续移动/破坏/放置方块，时长 600 秒）：

| 指标 | Forgia 全并行 (`DYNAMIC_CLUSTER`, 7 Workers) | 原版串行 (`Bridge = false`) | 状态 / 对比 |
|---|---:|---:|---|
| 运行时长 / 总 Tick | 600.02 s / 11,873 | 600.04 s / 11,859 | +0.12% 吞吐 |
| 平均 TPS | **19.79** | 19.76 | 持平 |
| **P50 MSPT（中位数）** | **10.78 ms** | 11.61 ms | **-7.1%（优于串行）** |
| **Mean MSPT（均值）** | 13.20 ms | **12.37 ms** | +6.7%（**待优化**） |
| **P95 MSPT** | 27.75 ms | **25.03 ms** | +10.9%（**待优化**） |
| **P99 MSPT** | 45.75 ms | **37.78 ms** | +21.1%（**待优化**） |
| **Max MSPT（峰值）** | **1026.41 ms** | 1110.14 ms | **-7.5%（优于串行）** |
| **GC 次数 / 耗时** | **52 次 / 914 ms** | 63 次 / 1,786 ms | **GC 耗时 -48.8%** |
| **结束堆内存** | **1,278 MiB** | 1,543 MiB | **-17.1%（优于串行）** |
| 所有权违规 | **0** | N/A | `entityTicksOnWorkers=102,920` |

### 不如原版串行及待优化项目

1. **轻负载全开关开启的均值与尾延迟开销（Mean +6.7% / P95 +10.9% / P99 +21.1%）— 待优化**：在 ~11–13 ms/tick 的轻负载下，多切片屏障等待与协调开销高于并行收益。
2. **方块实体与计划刻回退率高（`fallbackBlockEntityTicks=2,166,896`、`fallbackScheduledTicks=13,565`）— 待优化**：混合场景下未命中准入前缀仍支付了协调器检查成本，需降低空跑/回退路径的固定开销。
3. **历史玩家 Tick 回退。** 该构建对 `add_pack_finders_test` 等测试模组记录 `UNKNOWN_MOD`。当前构建根据回调和变换影响面判断准入。[玩家 Tick](modules/player-ticks.zh.md)
4. **实体主线程占比与压测攻击/岛合并覆盖（`serverThreadOnlyEntityTicks=379,968`、`attacks=0`、`merges=0`）— 待优化**：需继续缩减被迫停留在主线程的实体类型占比，并增强 `FullParallelSoakGameTests` 的实体攻击判定与跨 Section 岛合并/拆分触发率。

### 120 秒 32 玩家复杂行为：调度开销优化前后与区域模式对比（`DYNAMIC_CLUSTER` 8×8 / 4×4 vs `FIXED_3X3` vs 原版串行）

在增强后的 `FullParallelSoakGameTests`（同种子 `4242424242`、32 名玩家含移动探索、跨区岛合并/拆分、超过 4,000 次实体攻击与 2,800+ 次方块破坏/放置、全部归因探针开启，4 核 8 线程 i5-1135G7，JDK 21）下，完成 `$O(1)$` 进度监听器校验、状态模板增量刷新、单波次玩家派发、LPT 蛇形批调度与 `SPAWN`/`CHUNK_TICK` 邻域缓存共享后的实测对比：

| 指标 | Forgia 优化前 (`DYNAMIC_CLUSTER` 8×8) | **Forgia 优化后 (`DYNAMIC_CLUSTER` 8×8, `shift=3`)** | **Forgia 优化后 (`DYNAMIC_CLUSTER` 4×4, `shift=2`)** | Forgia 优化后 (`FIXED_3X3` 固定 3×3) | 原版串行 (`Bridge = false`) |
|---|---:|---:|---:|---:|---:|
| **区域拓扑** | 98 sections → 14 岛 (`merges=13`) | 98 sections → **14 动态岛** (`merges=13`) | **32 动态岛** (`merges=2`) | **239 个固定区域**（无合并） | 单线程无分区 |
| **合并节省任务提交** | 54,226 | **54,226** | **45,688** | 0 | - |
| **总 Tick 数（120s）** | 1,103 | **1,368 (+24.0%)** | 1,291 | 1,254 | 1,425 |
| **平均 TPS** | 9.19 | **11.40 (+24.0%)** | 10.75 | 10.44 | 11.87 |
| **Mean MSPT** | 84.29 ms | **56.14 ms (-33.4%)** | 60.16 ms | 56.67 ms | 54.32 ms |
| **P50 MSPT** | 63.45 ms | **44.32 – 46.52 ms (-30.1%)** | 44.72 ms | 45.50 ms | 32.43 ms |
| **P95 MSPT** | 144.66 ms | **101.27 – 101.95 ms (-30.0%)** | 110.12 ms | 122.16 ms | 76.94 ms |
| **P99 MSPT** | 234.99 ms | **163.63 – 172.51 ms (-30.4%)** | 167.13 ms (-28.9%) | 172.26 ms | 123.55 ms |
| **Max MSPT** | 2,971.13 ms | **1,784.80 – 3,561.12 ms** | 2,810.72 ms | **1,181.43 ms** | 6,328.14 ms |
| **`ENTITY_TICK.plan`** | 0.75 ms/tick | 0.68 – 0.70 ms/tick | **0.46 ms/tick** | 0.52 ms/tick | - |
| **`ENTITY_TICK.await`** | 22.76 ms/tick | **21.55 – 22.85 ms/tick** | 22.45 ms/tick | 23.53 ms/tick | - |
| **`ENTITY_TICK.handoff`** | 26.00 ms/tick | **4.90 – 5.30 ms/tick (-81.2%)** | 5.27 ms/tick | 5.25 ms/tick | - |
| **玩家 `ENTITY` Worker 执行** | 22,628（~5.4 波/tick） | **28,922（86.3%，1.00 波/tick）** | 24,590（75.8%，1.00 波/tick） | 21,099（69.3%，1.00 波/tick） | 0 |
| **所有权违规** | **0** | **0** | **0** | **0** | - |

---

## 6. 复现工具与证据文档

- 基准工具位于 [`tools/performance/`](https://github.com/NexForgeLabs/Forgia/tree/main/tools/performance) 与 [`tools/player-region-tick/`](https://github.com/NexForgeLabs/Forgia/tree/main/tools/player-region-tick)。
- 原始测量记录与已知限制见 [`region-threading/PERFORMANCE-BENCHMARKS.md`](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PERFORMANCE-BENCHMARKS.md)、[`region-threading/PLAYER-REGION-TICK-P5.zh.md`](https://github.com/NexForgeLabs/Forgia/blob/main/region-threading/PLAYER-REGION-TICK-P5.zh.md) 与 [项目状态](status.zh.md)。
- Forgia 不做“全整合包必然快于原版”的宣传；任何实验性开关都应在真实目标负载下实测评估。
