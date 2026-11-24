/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.IntValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;
import net.neoforged.neoforge.common.NeoForgeConfig.RegionThreadingOwnershipViolationMode;
import net.neoforged.neoforge.server.threading.RegionThreadingSupport;
import net.neoforged.neoforge.server.threading.region.DynamicRegionTopology;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionOverloadGovernor;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import org.apache.commons.lang3.tuple.Pair;

/** Forgia's server configuration, isolated from the upstream NeoForge configuration. */
public final class ForgiaConfig {
    public static final String FILE_NAME = "forgia.toml";
    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        final Pair<Server, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER_SPEC = pair.getRight();
        SERVER = pair.getLeft();
    }

    private ForgiaConfig() {}

    public static boolean isServerConfigLoaded() {
        return SERVER_SPEC.isLoaded();
    }

    public static final class Server {
        public final BooleanValue enableRuntimeDiagnostics;
        public final BooleanValue enableRuntimeJvmSampling;
        public final IntValue runtimeHighRegionMspt;
        public final IntValue runtimeWorkerSaturationPercent;
        public final IntValue runtimeQueuePerWorker;
        public final IntValue runtimeSerialFallbackPercent;
        public final IntValue runtimeRegionImbalanceRatio;
        public final IntValue runtimeMainCpuPercent;
        public final IntValue runtimeHighServerMspt;

        public final BooleanValue enableRegionThreadingBridge;

        public final BooleanValue enableRegionThreadingWorkers;

        public final BooleanValue enableGlobalRegionThreadingWorker;

        public final BooleanValue enableRegionThreadingEntityTicks;
        /** Opt-in player phase planning; complete worker admission remains closed until its audit is implemented. */
        public final BooleanValue enableRegionThreadingPlayerTicks;

        public final BooleanValue enableRegionThreadingBlockEntityTicks;

        public final BooleanValue enableRegionThreadingFurnaceWorkWaves;

        public final BooleanValue enableRegionThreadingBlockEntityResources;

        public final BooleanValue enableRegionThreadingScheduledTicks;

        public final BooleanValue enableRegionThreadingFluidTicks;

        public final BooleanValue enableRegionThreadingRandomTicks;

        public final BooleanValue enableRegionThreadingSpawning;

        public final BooleanValue enableRegionThreadingPoi;

        public final BooleanValue enableRegionThreadingBorderUpdates;

        /**
         * Experimental: installs the region threading engine even when the known-conflict table would remove a
         * worker slice. Meant for the compatibility harness, which has to observe what a mod actually does
         * instead of trusting a static table; every conflict is still detected and reported.
         */
        public final BooleanValue regionThreadingForceWithKnownConflicts;

        public final BooleanValue enableRegionThreadingGameEvents;

        public final BooleanValue enableRegionThreadingPersistentWorkers;

        public final IntValue regionThreadingWorkerThreads;

        public final ModConfigSpec.EnumValue<RegionPolicy> regionThreadingMode;

        public final IntValue regionThreadingDynamicSectionShift;

        public final LongValue regionThreadingDynamicSplitDelayTicks;

        public final ModConfigSpec.EnumValue<RegionThreadingOwnershipViolationMode> regionThreadingOwnershipViolationMode;

        public final ModConfigSpec.EnumValue<RegionThreadingSupport> defaultRegionThreadingSupport;

        public final LongValue regionThreadingLongTickWarningNanos;

        public final IntValue regionThreadingMaxImmediateTasksPerDrain;

        public final IntValue regionThreadingMaxScheduledTasksPerDrain;

        public final BooleanValue enableRegionOverloadGovernor;

        public final LongValue regionOverloadBudgetNanos;

        public final IntValue regionOverloadEnterTicks;

        public final IntValue regionOverloadExitTicks;

        public final DoubleValue regionOverloadExitRatio;

        public final IntValue regionOverloadMaxPeriod;

        public final BooleanValue regionOverloadThrottlePlayerRegions;

        public final IntValue regionOverloadMaxCatchUpScheduledTicks;

        public final BooleanValue enableRegionThreadingCallerRuns;

        public final BooleanValue enableRegionThreadingParallelBorderFixpoint;

        public final BooleanValue enableCanonicalFenceTail;

        public final BooleanValue enableRegionThreadingFusedSlices;

        public final BooleanValue enableItemCleanup;

        public final IntValue itemCleanupIntervalSeconds;

        public final IntValue itemCleanupMinAgeSeconds;

        public final IntValue itemCleanupWarningSeconds;

        public final IntValue itemCleanupMaxPerRegion;

        public final BooleanValue itemCleanupIgnoreNamedItems;

        public final ConfigValue<List<? extends String>> itemCleanupWhitelist;

        public final BooleanValue enableMobPathfindingThrottle;

        public final ConfigValue<List<? extends String>> mobPathfindingCooldowns;

        public final IntValue mobPathfindingDefaultCooldownTicks;

        public final BooleanValue enableIndependentRegionTickClock;

        public final LongValue independentRegionTickIntervalNanos;

        public final IntValue independentRegionMaxCatchUpTicks;

        public final BooleanValue enableRegionSandboxAllBlockEntities;

        public final LongValue entityTickThrottleModerateNanos;

        public final LongValue entityTickThrottleSevereNanos;

        public final LongValue hotRegionEntityThresholdNanos;

        public final IntValue hotRegionMobPathfindingCooldownTicks;

        public final IntValue hotRegionCombatMobPathfindingCooldownTicks;

        public final LongValue slowPathfindingLearnThresholdNanos;

        public final LongValue severePathfindingLearnThresholdNanos;

        public final IntValue sandboxTransientCooldownTicks;

        public final IntValue sandboxPermanentDemotionThreshold;

        public final BooleanValue enableAsyncRegionSpawning;

        public final BooleanValue skipUnloadedNeighborhoodSpawning;

        public final BooleanValue enableAsyncRegionEntityTicks;
        public final BooleanValue enableSelectiveAsyncEntityTicks;

        public final BooleanValue enableAsyncRegionFluidTicks;

        public final BooleanValue enableLowTpsProbe;
        public final BooleanValue enableSandboxDemotionProbe;
        public final DoubleValue lowTpsProbeThreshold;
        public final IntValue lowTpsProbeDurationSeconds;
        public final IntValue lowTpsProbeCooldownSeconds;
        public final IntValue lowTpsProbeTopCount;

        public final LongValue independentRegionCommitDrainBudgetNanos;

        Server(ModConfigSpec.Builder builder) {
            enableRuntimeDiagnostics = builder.comment(
                    "=== [1] Diagnostics & Runtime Sampling / 诊断与性能采样 ===",
                    "Collect bounded native tick history and worker task timings. Disabling clears server history; essential scheduler/ownership counters remain active.",
                    "[中文] 是否采集原生 Tick 耗时历史与 Worker 任务统计（禁用后保留核心调度/所有权计数）。")
                    .define("runtimeDiagnosticsEnabled", true);
            enableRuntimeJvmSampling = builder.comment(
                    "Sample local JVM CPU, allocation and GC counters once per wall second.",
                    "[中文] 每秒采样一次 JVM 线程 CPU/分配速率/GC 指标；低开销追求极致性能可设为 false。")
                    .define("runtimeJvmSamplingEnabled", true);
            runtimeHighRegionMspt = builder.comment("HIGH_REGION_MSPT threshold for the latest recorded region tick, milliseconds; coverage is instrumented leased groups.")
                    .defineInRange("runtimeHighRegionMspt", 50, 1, 10000);
            runtimeWorkerSaturationPercent = builder.comment("WORKER_SATURATION threshold: aggregate sampled worker CPU divided by configured workers, percent.")
                    .defineInRange("runtimeWorkerSaturationPercent", 90, 1, 100);
            runtimeQueuePerWorker = builder.comment("QUEUE_CONGESTION threshold: observed pending tasks per configured worker. Snapshot evidence, not a root-cause diagnosis.")
                    .defineInRange("runtimeQueuePerWorker", 2, 1, 10000);
            runtimeSerialFallbackPercent = builder.comment("HIGH_SERIAL_FALLBACK threshold: lifetime entity and BE fallback bodies as a percent of those completed on workers or fallback, after at least 100 bodies.")
                    .defineInRange("runtimeSerialFallbackPercent", 50, 1, 100);
            runtimeRegionImbalanceRatio = builder.comment("REGION_LOAD_IMBALANCE threshold: largest mean region load divided by mean of sampled active regions, with a minimum 1ms largest region.")
                    .defineInRange("runtimeRegionImbalanceRatio", 4, 2, 1000);

            runtimeMainCpuPercent = builder.comment("MAIN_THREAD_BOTTLENECK requires main CPU at least this percent of one core and mean server MSPT at least runtimeHighServerMspt.")
                    .defineInRange("runtimeMainCpuPercent", 90, 1, 100);
            runtimeHighServerMspt = builder.comment("MAIN_THREAD_BOTTLENECK server mean MSPT threshold, milliseconds. A correlation warning, not a root-cause diagnosis.")
                    .defineInRange("runtimeHighServerMspt", 50, 1, 10000);
            enableLowTpsProbe = builder.comment(
                    "Automatically records a structured bottleneck snapshot (to console WARN and logs/forgia-low-tps-probe.jsonl) whenever global TPS stays below lowTpsProbeThreshold for lowTpsProbeDurationSeconds.",
                    "[中文] 低 TPS 自动探针：当全局 TPS 持续低于 lowTpsProbeThreshold 超过 lowTpsProbeDurationSeconds 秒时，自动记录一份全局最大性能瓶颈快照到控制台与 logs/forgia-low-tps-probe.jsonl。")
                    .define("enableLowTpsProbe", true);
            lowTpsProbeThreshold = builder.comment(
                    "Global TPS threshold below which the low-TPS probe begins timing an incident.",
                    "[中文] 触发低 TPS 探针计时的全局 TPS 阈值（默认 18.0）。")
                    .defineInRange("lowTpsProbeThreshold", 18.0D, 1.0D, 20.0D);
            lowTpsProbeDurationSeconds = builder.comment(
                    "Continuous seconds below lowTpsProbeThreshold required before emitting a probe report.",
                    "[中文] 全局 TPS 连续低于阈值多少秒后触发一次完整瓶颈记录（默认 30 秒）。")
                    .defineInRange("lowTpsProbeDurationSeconds", 30, 5, 300);
            lowTpsProbeCooldownSeconds = builder.comment(
                    "Minimum seconds between consecutive low-TPS probe reports during a sustained slowdown.",
                    "[中文] 持续卡顿期间两次探针记录之间的最小冷却间隔秒数（默认 60 秒）。")
                    .defineInRange("lowTpsProbeCooldownSeconds", 60, 10, 3600);
            lowTpsProbeTopCount = builder.comment(
                    "Number of top regions, main-thread phases, and slices included in each low-TPS probe report.",
                    "[中文] 每次低 TPS 探针报告中记录的最重区域 / 主线程阶段 / 切片数量（默认 5）。")
                    .defineInRange("lowTpsProbeTopCount", 5, 1, 20);
            enableSandboxDemotionProbe = builder.comment(
                    "Records deduplicated stack traces of Region Sandbox block-entity demotions and worker boundary violations to logs/forgia-sandbox-demotions.jsonl.",
                    "[中文] 沙箱降级堆栈探针：异步记录方块实体沙箱异常与边界回退的去重调用栈到 logs/forgia-sandbox-demotions.jsonl（默认开启）。")
                    .define("enableSandboxDemotionProbe", true);

            enableRegionThreadingBridge = builder
                    .comment("EXPERIMENTAL: Enables the temporary single-thread region threading bridge. This exposes the region scheduler API before the full Folia-style tick scheduler is ported.")
                    .translation("neoforge.configgui.enableRegionThreadingBridge")
                    .worldRestart()
                    .define("enableRegionThreadingBridge", false);

            enableRegionThreadingWorkers = builder
                    .comment("EXPERIMENTAL: Executes due region queue drains on native region worker threads instead of the server thread bridge. Requires enableRegionThreadingBridge. Unsafe until ownership guards are complete.")
                    .translation("neoforge.configgui.enableRegionThreadingWorkers")
                    .worldRestart()
                    .define("enableRegionThreadingWorkers", false);

            enableGlobalRegionThreadingWorker = builder
                    .comment("EXPERIMENTAL: Executes global-region queue drains on the native worker pool instead of the server thread bridge. Requires enableRegionThreadingBridge.")
                    .translation("neoforge.configgui.enableGlobalRegionThreadingWorker")
                    .worldRestart()
                    .define("enableGlobalRegionThreadingWorker", false);

            enableRegionThreadingEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge and a fixed region policy, because the region-owned entity storage this needs is the physical section partition of a fixed layout. Enabling it selects region-owned entity storage: a worker only reads the physical section bucket of the region it holds, every storage effect of its ticks - the position change's section publication and chunk acquisition, the removal's level callback and the admission of a spawned entity - is recorded and applied by the batch barrier on the server thread, and any other storage access from a worker is rejected and counted. With a dynamic layout, and with this switch off, the whole entity slice stays on the server thread.")
                    .translation("neoforge.configgui.enableRegionThreadingEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingEntityTicks", false);
            enableRegionThreadingPlayerTicks = builder
                    .comment("EXPERIMENTAL: Dispatches complete original player world and connection ticks through the existing region pool when the bounded spatial, local-state, callback and network contracts are satisfied. The initial production branch supports stationary players on inert loaded terrain with empty inventory, local menus and closed advancement/scoreboard paths; unknown mods, transforms, callbacks and unsupported state keep complete serial calls. Requires the bridge, a fixed layout, region-owned entity storage and the entity slice. Missing dependencies preserve complete original serial calls. The Workers key remains a queued-task drain setting, not a dependency of tick execution.")
                    .worldRestart()
                    .define("enableRegionThreadingPlayerTicks", false);

            enableRegionThreadingBlockEntityTicks = builder
                    .comment("EXPERIMENTAL: Ticks the real ServerLevel block entities of a tick on their owning region worker thread while the server thread waits for the batch. Requires enableRegionThreadingBridge. Only block entity types whose compatibility declaration proves that their server tick never queries or mutates entity storage, never spawns/removes an entity and writes only inside its region run on workers; every other type stays on the server thread. This is a bounded block-entity tick slice, not regionized world ticking.")
                    .translation("neoforge.configgui.enableRegionThreadingBlockEntityTicks")
                    .worldRestart()
                    .define("enableRegionThreadingBlockEntityTicks", false);

            enableRegionThreadingFurnaceWorkWaves = builder
                    .comment("LEGACY (fixed-layout only; ignored under DYNAMIC_CLUSTER): Execute verified vanilla furnace-family ticks in directed resource waves.")
                    .worldRestart()
                    .define("enableRegionThreadingFurnaceWorkWaves", false);

            enableRegionThreadingBlockEntityResources = builder
                    .comment("LEGACY (fixed-layout only; ignored under DYNAMIC_CLUSTER): Resource scopes inside legacy furnace work waves.")
                    .worldRestart()
                    .define("enableRegionThreadingBlockEntityResources", false);

            enableRegionThreadingScheduledTicks = builder
                    .comment("EXPERIMENTAL: Requests the bounded scheduled block and fluid tick slice. Requires enableRegionThreadingBridge. Production admits only complete block phases of vanilla redstone-lamp callbacks whose radius-two signal/shape footprint is loaded, contained in one fixed region and contains only audited inert vanilla blocks. Other block callbacks, fluids, unsafe footprints and over-budget phases retain the original serial LevelTicks loop and ordering. Diagnostics separate collected/admitted callbacks, native/serial completions, failures, skips, refusal reasons and timing. Default off.")
                    .translation("neoforge.configgui.enableRegionThreadingScheduledTicks")
                    .worldRestart()
                    .define("enableRegionThreadingScheduledTicks", false);

            enableRegionThreadingFluidTicks = builder
                    .comment(
                            "=== [2] Core Region Threading Slices / 核心区域并行切片 ===",
                            "Per-region parallel fluid tick execution under DYNAMIC_CLUSTER and fixed layouts while keeping redstone block ticks serial on the server thread.",
                            "[中文] 是否启用区域并行流体（水/岩浆等）计算，同时保持红石计划刻在主线程串行。")
                    .translation("neoforge.configgui.enableRegionThreadingFluidTicks")
                    .worldRestart()
                    .define("enableRegionThreadingFluidTicks", true);

            enableRegionThreadingRandomTicks = builder
                    .comment(
                            "EXPERIMENTAL: Ticks the chunks of a level on their owning region worker thread while the server thread waits for the batch. This is the chunk tick body - random block and fluid ticks, ice and snow, and thunder - not only the random ticks the key is named after. Requires enableRegionThreadingBridge. A chunk runs on a worker when its own chunk and the eight chunks around it are loaded, so a random tick can never force-load a chunk from a worker. A chunk of a region border is dispatched too: a block write of its body that lands in another region is recorded and applied by the region that owns the position on its own worker in the same tick, with the vanilla call, and a callback that reads that position back in the same tick observes the state before the write (counted as readAfterBlockWrite). A write that cannot be expressed as a position, a state and update flags - a block-entity mutation carrying data - keeps the existing no-replay refusal. A chunk whose neighborhood is not loaded stays on the server thread. Natural spawning, the inhabited time update, the custom spawners and every other global decision stay on the server thread. Every refusal is counted and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingRandomTicks")
                    .worldRestart()
                    .define("enableRegionThreadingRandomTicks", false);

            enableRegionThreadingSpawning = builder
                    .comment(
                            "EXPERIMENTAL: Runs the natural spawning of a level on the worker of the region that owns the chunk, while the server thread waits for the batch. The mob cap is the vanilla level-wide cap: the server thread counts the level's mobs per category once per tick and every region spawns against that count, so a mob that walks into the neighbouring region still holds its slot. A region spawns from a random source derived from the world seed, the dimension, the game time, the region and the chunk, and every mob a region spawns is admitted by the server thread at the barrier. Natural spawning requires enableRegionThreadingBridge and a fixed region policy (the physical entity sections of a level are partitioned per region only for a fixed layout). Only chunks whose eight neighboring chunks are loaded run on a worker; a chunk with an unloaded neighbor, a chunk whose region cannot be leased and a chunk the batch cannot admit stays on the server thread, where it spawns from the vanilla level-wide state exactly like vanilla. The spawn gate, the custom spawners and the per-chunk broadcast stay on the server thread. Every refusal is counted by reason and reported by the region threading diagnostics.")
                    .translation("neoforge.configgui.enableRegionThreadingSpawning")
                    .worldRestart()
                    .define("enableRegionThreadingSpawning", false);

            enableRegionThreadingPoi = builder
                    .comment(
                            "EXPERIMENTAL: Partitions the POI storage of a level per region so that the POI users (villagers, iron golems, raiders, bees, zombies and foxes) tick on their region worker again. A POI section belongs to the region that owns its chunk; each region publishes an immutable snapshot of the sections it wrote at the end of every region phase, and a query from a worker reads its own region's live sections plus the published snapshots of the regions its box overlaps, in vanilla's result order. A foreign answer is at most one publish old, free-ticket counts are live, and the village distance is answered from the published village-center index instead of draining the level-wide distance tracker. Requires enableRegionThreadingBridge and DYNAMIC_CLUSTER. Enabling enableRegionThreadingEntityTicks implies this storage, because a worker tick of a POI user reaches the level's POI manager. With the switch off, and without entity worker ticks, the vanilla PoiManager storage is used unchanged.")
                    .translation("neoforge.configgui.enableRegionThreadingPoi")
                    .worldRestart()
                    .define("enableRegionThreadingPoi", false);

            regionThreadingForceWithKnownConflicts = builder
                    .comment(
                            "EXPERIMENTAL: installs the region threading engine even when a known conflict would remove a worker slice, so a compatibility run can observe what a mod actually does. Every conflict is still detected and reported. Leave this off outside of measurement runs.")
                    .translation("neoforge.configgui.regionThreadingForceWithKnownConflicts")
                    .worldRestart()
                    .define("regionThreadingForceWithKnownConflicts", false);
            enableRegionThreadingBorderUpdates = builder
                    .comment(
                            "EXPERIMENTAL: Captures the neighbor updates a region worker cannot apply because they target another region, and applies them on the target region's own worker in the same tick. The fixpoint repeats until no border update remains or the level's max-chained-neighbor-updates limit is reached; the rest falls back to the target region's next tick through the region mailbox and is counted as delayed. Requires enableRegionThreadingBridge. With the switch off, a region context uses the unchanged vanilla neighbor collector and no border update is recorded.")
                    .translation("neoforge.configgui.enableRegionThreadingBorderUpdates")
                    .worldRestart()
                    .define("enableRegionThreadingBorderUpdates", false);

            enableRegionThreadingGameEvents = builder
                    .comment(
                            "EXPERIMENTAL: Captures a game event whose listeners belong to another region and delivers it on that region's own worker in the same tick, in posting order, instead of writing the foreign block entity from the posting worker. A game event reaches every listener within its notification radius (16 blocks for the common events, 32 for shriek), and a listener writes its own block entity, so with 3-chunk regions the dispatch crosses a border in the common case. A captured section column is replayed by the target region's worker with the vanilla listener registry, before the next slice of the level tick, so a sculk sensor records the vibration in the same game time and activates on the same tick as inline. Requires enableRegionThreadingBridge. The counters report every posted event, the columns delivered locally, deferred and delivered, and every refusal and drop. With the switch off the dispatch is exactly vanilla and therefore races as before: the posting worker visits every listener of the event's radius itself, including the listeners of other regions, and writes their block entities inline.")
                    .translation("neoforge.configgui.enableRegionThreadingGameEvents")
                    .worldRestart()
                    .define("enableRegionThreadingGameEvents", false);

            enableRegionThreadingPersistentWorkers = builder
                    .comment(
                            "EXPERIMENTAL: Dispatches through persistent workers with per-worker deques. Batches larger than the worker count submit at most one runner per worker; an idle runner steals half of another runner's unstarted task range. Explicit packed leases remain indivisible. This reduces per-task submission overhead while retaining each group's ownership and completion barrier. Requires enableRegionThreadingBridge. With the switch off, tasks use the existing fixed thread pool.")
                    .translation("neoforge.configgui.enableRegionThreadingPersistentWorkers")
                    .worldRestart()
                    .define("enableRegionThreadingPersistentWorkers", false);

            regionThreadingWorkerThreads = builder
                    .comment("EXPERIMENTAL: Number of native region worker threads reserved for the future region scheduler. A value of 0 uses max(1, available processors - 1).")
                    .translation("neoforge.configgui.regionThreadingWorkerThreads")
                    .worldRestart()
                    .defineInRange("regionThreadingWorkerThreads", 0, 0, 512);

            regionThreadingMode = builder
                    .comment("Region coordinate policy. DYNAMIC_CLUSTER (default) uses Folia-style dynamic merging and splitting over fixed (1 << regionThreadingDynamicSectionShift)-chunk sections. DYNAMIC is retained only for legacy compatibility.")
                    .translation("neoforge.configgui.regionThreadingMode")
                    .worldRestart()
                    .defineEnum("regionThreadingMode", RegionPolicy.DYNAMIC_CLUSTER);

            regionThreadingDynamicSectionShift = builder
                    .comment(
                            "=== [3] Region Layout & Worker Pool / 区域粒度与线程池 ===",
                            "Bit shift of one section edge in chunks for DYNAMIC_CLUSTER: 1 = 2x2 chunks (32 blocks), 2 = 4x4 chunks (64 blocks), 3 = 8x8 chunks (128 blocks).",
                            "[中文] 动态区域单格边长位移：1=2x2区块(细粒度，适合拆开大型工业区吃满多核)，2=4x4区块(默认平衡)，3=8x8区块。")
                    .translation("neoforge.configgui.regionThreadingDynamicSectionShift")
                    .worldRestart()
                    .defineInRange("regionThreadingDynamicSectionShift", DynamicRegionTopology.DEFAULT_SECTION_SHIFT, DynamicRegionTopology.MIN_SECTION_SHIFT, DynamicRegionTopology.MAX_SECTION_SHIFT);

            regionThreadingDynamicSplitDelayTicks = builder
                    .comment("EXPERIMENTAL: Hysteresis delay in ticks before an emptied section is detached from its dynamic region and checked for connected-component splitting when regionThreadingMode is DYNAMIC_CLUSTER.")
                    .translation("neoforge.configgui.regionThreadingDynamicSplitDelayTicks")
                    .worldRestart()
                    .defineInRange("regionThreadingDynamicSplitDelayTicks", DynamicRegionTopology.DEFAULT_SPLIT_DELAY_TICKS, 0L, 1200L);

            regionThreadingOwnershipViolationMode = builder
                    .comment("EXPERIMENTAL: Controls how region threading ownership assertion failures are handled.")
                    .translation("neoforge.configgui.regionThreadingOwnershipViolationMode")
                    .defineEnum("regionThreadingOwnershipViolationMode", RegionThreadingOwnershipViolationMode.WARN);

            defaultRegionThreadingSupport = builder
                    .comment("EXPERIMENTAL: Default region threading support status for mods that do not declare explicit metadata yet.")
                    .translation("neoforge.configgui.defaultRegionThreadingSupport")
                    .worldRestart()
                    .defineEnum("defaultRegionThreadingSupport", RegionThreadingSupport.UNKNOWN);

            regionThreadingLongTickWarningNanos = builder
                    .comment("EXPERIMENTAL: Logs a warning when a global or region queue drain exceeds this duration in nanoseconds. 0 disables warnings.")
                    .translation("neoforge.configgui.regionThreadingLongTickWarningNanos")
                    .defineInRange("regionThreadingLongTickWarningNanos", 0L, 0L, Long.MAX_VALUE);

            regionThreadingMaxImmediateTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum immediate region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxImmediateTasksPerDrain")
                    .defineInRange("regionThreadingMaxImmediateTasksPerDrain", 10000, 0, Integer.MAX_VALUE);

            regionThreadingMaxScheduledTasksPerDrain = builder
                    .comment("EXPERIMENTAL: Maximum due scheduled region tasks drained from one queue per tick. 0 disables the limit.")
                    .translation("neoforge.configgui.regionThreadingMaxScheduledTasksPerDrain")
                    .defineInRange("regionThreadingMaxScheduledTasksPerDrain", 10000, 0, Integer.MAX_VALUE);

            enableRegionOverloadGovernor = builder
                    .comment("EXPERIMENTAL: Isolates lagging regions by throttling their tick dispatch frequency so a single hot region does not drag down server-wide TPS. Effective only when regionThreadingMode is DYNAMIC_CLUSTER; ignored under DYNAMIC. Uses deterministic jittered scheduling so periodic modulo checks (e.g. gameTime % 20 or beacon % 80) never starve.")
                    .translation("neoforge.configgui.enableRegionOverloadGovernor")
                    .worldRestart()
                    .define("enableRegionOverloadGovernor", false);

            regionOverloadBudgetNanos = builder
                    .comment("EXPERIMENTAL: Per-region aggregate slice EWMA duration threshold in nanoseconds above which a region counts toward entering THROTTLED mode. Default 15000000 (15 ms).")
                    .translation("neoforge.configgui.regionOverloadBudgetNanos")
                    .worldRestart()
                    .defineInRange("regionOverloadBudgetNanos", RegionOverloadGovernor.Config.DEFAULT_BUDGET_NANOS, 1_000_000L, 1_000_000_000L);

            regionOverloadEnterTicks = builder
                    .comment("EXPERIMENTAL: Consecutive ticks a region's EWMA must exceed regionOverloadBudgetNanos before entering THROTTLED mode.")
                    .translation("neoforge.configgui.regionOverloadEnterTicks")
                    .worldRestart()
                    .defineInRange("regionOverloadEnterTicks", RegionOverloadGovernor.Config.DEFAULT_ENTER_TICKS, 1, 1200);

            regionOverloadExitTicks = builder
                    .comment("EXPERIMENTAL: Server-tick hysteresis duration a THROTTLED region's EWMA must remain below budget * regionOverloadExitRatio before returning to NORMAL.")
                    .translation("neoforge.configgui.regionOverloadExitTicks")
                    .worldRestart()
                    .defineInRange("regionOverloadExitTicks", RegionOverloadGovernor.Config.DEFAULT_EXIT_TICKS, 1, 6000);

            regionOverloadExitRatio = builder
                    .comment("EXPERIMENTAL: Fraction of regionOverloadBudgetNanos below which a THROTTLED region accumulates recovery credit.")
                    .translation("neoforge.configgui.regionOverloadExitRatio")
                    .worldRestart()
                    .defineInRange("regionOverloadExitRatio", RegionOverloadGovernor.Config.DEFAULT_EXIT_RATIO, 0.1, 0.95);

            regionOverloadMaxPeriod = builder
                    .comment("EXPERIMENTAL: Maximum dispatch period (1/N tick rate) applied to a THROTTLED region.")
                    .translation("neoforge.configgui.regionOverloadMaxPeriod")
                    .worldRestart()
                    .defineInRange("regionOverloadMaxPeriod", RegionOverloadGovernor.Config.DEFAULT_MAX_PERIOD, 2, 64);

            regionOverloadThrottlePlayerRegions = builder
                    .comment("EXPERIMENTAL: When true (default), regions containing players are throttled like any other overloaded region to protect server-wide TPS. When false, regions with players are exempted from throttling.")
                    .translation("neoforge.configgui.regionOverloadThrottlePlayerRegions")
                    .worldRestart()
                    .define("regionOverloadThrottlePlayerRegions", RegionOverloadGovernor.Config.DEFAULT_THROTTLE_PLAYER_REGIONS);

            regionOverloadMaxCatchUpScheduledTicks = builder
                    .comment("EXPERIMENTAL: Maximum scheduled block/fluid ticks re-scheduled per skipped tick for a THROTTLED region before excess ticks fall back.")
                    .translation("neoforge.configgui.regionOverloadMaxCatchUpScheduledTicks")
                    .worldRestart()
                    .defineInRange("regionOverloadMaxCatchUpScheduledTicks", RegionOverloadGovernor.Config.DEFAULT_MAX_CATCH_UP_SCHEDULED_TICKS, 1, 1_000_000);

            enableRegionThreadingCallerRuns = builder
                    .comment("EXPERIMENTAL: Allows the server thread to steal and execute one unstarted region group at a time from persistent work-stealing batches while waiting at the barrier (between pumping main-thread handoffs). Reduces barrier idle time on the server thread. Requires enableRegionThreadingPersistentWorkers. Default off.")
                    .translation("neoforge.configgui.enableRegionThreadingCallerRuns")
                    .define("enableRegionThreadingCallerRuns", false);

            enableRegionThreadingParallelBorderFixpoint = builder
                    .comment("EXPERIMENTAL: Uses 8-neighbor graph coloring on the first pass of cross-region border-update fixpoint propagation so non-adjacent target regions propagate in parallel on region workers instead of strictly one by one. Subsequent convergence passes and Seqlock foreign-read validation remain unchanged. Default off.")
                    .translation("neoforge.configgui.enableRegionThreadingParallelBorderFixpoint")
                    .define("enableRegionThreadingParallelBorderFixpoint", false);

            enableCanonicalFenceTail = builder
                    .comment("EXPERIMENTAL (opt-in, relaxes canonical BE order): Defers unproven serial-fence block entities to a single serial tail after parallel BE waves join, collapsing multiple barrier rounds into one. Trades strict canonical ordering between fenced and unfenced BEs for lower barrier overhead. Default off.")
                    .translation("neoforge.configgui.enableCanonicalFenceTail")
                    .define("enableCanonicalFenceTail", false);

            enableRegionThreadingFusedSlices = builder
                    .comment("EXPERIMENTAL (P3 slice fusion): Fuses eligible per-region CHUNK_TICK and self-driven ENTITY_TICK slices into a single worker task, single barrier, and single border-update fixpoint per tick when no block events intervene. Automatically flushes fail-closed before block events, saves, or end-of-tick, and falls back per-region on Seqlock foreign-read invalidation or deferred-update budget overflow. Default off.")
                    .translation("neoforge.configgui.enableRegionThreadingFusedSlices")
                    .define("enableRegionThreadingFusedSlices", false);

            enableItemCleanup = builder
                    .comment("Enables Forgia's multi-region aware dropped-item cleanup (scheduled sweeps and per-region emergency caps). Runs strictly on the server thread at quiescent tick boundaries with zero worker contention. Only clears items whose dropped age >= itemCleanupMinAgeSeconds and never clears persistent (age == -32768) items. Default off.")
                    .translation("neoforge.configgui.enableItemCleanup")
                    .define("enableItemCleanup", false);

            itemCleanupIntervalSeconds = builder
                    .comment("Interval in seconds between scheduled multi-region dropped-item sweeps (0 disables scheduled sweeps while keeping per-region cap active). Default 300.")
                    .translation("neoforge.configgui.itemCleanupIntervalSeconds")
                    .defineInRange("itemCleanupIntervalSeconds", 300, 0, 86400);

            itemCleanupMinAgeSeconds = builder
                    .comment("Minimum age in seconds an item must have been dropped on the ground before it is eligible for cleanup. Fresh drops younger than this threshold are never removed. Default 60.")
                    .translation("neoforge.configgui.itemCleanupMinAgeSeconds")
                    .defineInRange("itemCleanupMinAgeSeconds", 60, 0, 3600);

            itemCleanupWarningSeconds = builder
                    .comment("Seconds before a scheduled sweep to broadcast a countdown warning to players (0 disables warnings). Default 15.")
                    .translation("neoforge.configgui.itemCleanupWarningSeconds")
                    .defineInRange("itemCleanupWarningSeconds", 15, 0, 300);

            itemCleanupMaxPerRegion = builder
                    .comment("Per-region emergency cap on dropped ItemEntity count (0 disables). Checked once per second per level; when a single region exceeds this count, oldest eligible items (>= itemCleanupMinAgeSeconds) in that region only are removed until back under the cap. Default 256.")
                    .translation("neoforge.configgui.itemCleanupMaxPerRegion")
                    .defineInRange("itemCleanupMaxPerRegion", 256, 0, 100000);

            itemCleanupIgnoreNamedItems = builder
                    .comment("When true, dropped items with a custom name (e.g. anvil-renamed tools/weapons) are never cleared. Default true.")
                    .translation("neoforge.configgui.itemCleanupIgnoreNamedItems")
                    .define("itemCleanupIgnoreNamedItems", true);

            itemCleanupWhitelist = builder
                    .comment("List of item registry IDs (e.g. [\"minecraft:nether_star\", \"minecraft:elytra\"]) that are never removed by item cleanup.")
                    .translation("neoforge.configgui.itemCleanupWhitelist")
                    .defineListAllowEmpty("itemCleanupWhitelist", List::of, () -> "", element -> element instanceof String s && !s.isBlank());

            enableMobPathfindingThrottle = builder
                    .comment(
                            "=== [5] Adaptive Entity Throttle & Pathfinding / 实体自适应节流与寻路冷却 ===",
                            "Enables per-entity-type and hot-region cooldown throttling on PathNavigation.createPath.",
                            "[中文] 是否开启生物寻路冷却节流（避免复杂寻路卡顿 Region Worker）。")
                    .translation("neoforge.configgui.enableMobPathfindingThrottle")
                    .define("enableMobPathfindingThrottle", false);

            mobPathfindingCooldowns = builder
                    .comment(
                            "Per-entity-type minimum tick interval between full pathfinding computations, e.g. [\"primal:shark=40\"].",
                            "[中文] 指定生物类型的寻路冷却时间（格式：\"modid:entity=ticks\"）。")
                    .translation("neoforge.configgui.mobPathfindingCooldowns")
                    .defineListAllowEmpty("mobPathfindingCooldowns", () -> List.of("primal:shark=40"), () -> "primal:shark=40",
                            net.neoforged.neoforge.server.threading.region.MobPathfindingThrottle::isValidEntry);

            mobPathfindingDefaultCooldownTicks = builder
                    .comment(
                            "Optional global default pathfinding cooldown ticks for unlisted mobs (0 disables). Default 0.",
                            "[中文] 未列入名单生物的全局默认寻路冷却（0 表示不全局限制）。")
                    .translation("neoforge.configgui.mobPathfindingDefaultCooldownTicks")
                    .defineInRange("mobPathfindingDefaultCooldownTicks", 0, 0, 200);

            enableIndependentRegionTickClock = builder
                    .comment(
                            "=== [4] Independent Region Clock & Generic BE Sandbox / 独立区域时钟与通用方块实体沙盒 ===",
                            "EXPERIMENTAL (Phase 2): Enables Folia-style independent per-region asynchronous tick clocks so slow hotspot regions do not stall fast regions or the server thread.",
                            "[中文] 是否启用 Folia 式独立 Region 异步时钟（消除全局 BE 同步等待，慢区不拖累快区）。")
                    .translation("neoforge.configgui.enableIndependentRegionTickClock")
                    .define("enableIndependentRegionTickClock", false);

            independentRegionTickIntervalNanos = builder
                    .comment(
                            "Target tick interval in nanoseconds for each independent region clock (50000000 = 50ms / 20 TPS).",
                            "[中文] 独立 Region 时钟的目标周期（纳秒，默认 50000000 即 20 TPS）。")
                    .translation("neoforge.configgui.independentRegionTickIntervalNanos")
                    .defineInRange("independentRegionTickIntervalNanos", 50_000_000L, 1_000_000L, 1_000_000_000L);

            independentRegionMaxCatchUpTicks = builder
                    .comment(
                            "Maximum number of ticks a lagging independent region may catch up before its clock smoothly realigns. Default 4.",
                            "[中文] 滞后 Region 允许的最大追帧步数（默认 4）。")
                    .translation("neoforge.configgui.independentRegionMaxCatchUpTicks")
                    .defineInRange("independentRegionMaxCatchUpTicks", 4, 1, 100);

            enableRegionSandboxAllBlockEntities = builder
                    .comment(
                            "Admits all non-denylisted modded block entities into Region Sandbox parallel execution on region workers.",
                            "[中文] 默认将全服所有非黑名单方块实体放入 Region Worker 沙盒并行，遇到不兼容异常自动回退。")
                    .translation("neoforge.configgui.enableRegionSandboxAllBlockEntities")
                    .define("enableRegionSandboxAllBlockEntities", false);

            entityTickThrottleModerateNanos = builder
                    .comment(
                            "Region entity EWMA threshold (ns) above which non-player entities in that region run every 2nd tick. Default 15000000 (15ms).",
                            "[中文] 区域实体耗时 EWMA 超过此阈值（纳秒）时，该区非玩家实体降频为每 2 tick 运行一次（默认 15ms）。")
                    .defineInRange("entityTickThrottleModerateNanos", 15_000_000L, 1_000_000L, 500_000_000L);

            entityTickThrottleSevereNanos = builder
                    .comment(
                            "Region entity EWMA threshold (ns) above which non-player entities in that region run every 3rd tick. Default 28000000 (28ms).",
                            "[中文] 区域实体耗时 EWMA 超过此阈值（纳秒）时，该区非玩家实体降频为每 3 tick 运行一次（默认 28ms）。")
                    .defineInRange("entityTickThrottleSevereNanos", 28_000_000L, 1_000_000L, 1_000_000_000L);

            hotRegionEntityThresholdNanos = builder
                    .comment(
                            "Region entity EWMA threshold (ns) above which mobs in that region use lightweight pathfinding cooldowns. Default 5000000 (5ms).",
                            "[中文] 判定为实体热点区域的 EWMA 阈值（纳秒），超过后该区生物自动启用轻量寻路冷却（默认 5ms）。")
                    .defineInRange("hotRegionEntityThresholdNanos", 5_000_000L, 500_000L, 500_000_000L);

            hotRegionMobPathfindingCooldownTicks = builder
                    .comment(
                            "Pathfinding cooldown in ticks applied to idle/non-combat mobs inside hot entity regions. Default 4.",
                            "[中文] 热点区域内非战斗/游荡生物的寻路冷却 tick 数（默认 4，不影响移动流畅度）。")
                    .defineInRange("hotRegionMobPathfindingCooldownTicks", 4, 0, 100);

            hotRegionCombatMobPathfindingCooldownTicks = builder
                    .comment(
                            "Pathfinding cooldown in ticks applied to mobs actively targeting a player inside hot entity regions. Default 2.",
                            "[中文] 热点区域内正在追击玩家的生物寻路冷却 tick 数（默认 2）。")
                    .defineInRange("hotRegionCombatMobPathfindingCooldownTicks", 2, 0, 100);

            slowPathfindingLearnThresholdNanos = builder
                    .comment(
                            "Single PathFinder.findPath duration (ns) above which the mob type auto-learns a 10-tick cooldown. Default 3000000 (3ms).",
                            "[中文] 单次寻路耗时超过此值（纳秒）时，该生物类型自动学习 10 tick 寻路冷却（默认 3ms）。")
                    .defineInRange("slowPathfindingLearnThresholdNanos", 3_000_000L, 500_000L, 100_000_000L);

            severePathfindingLearnThresholdNanos = builder
                    .comment(
                            "Single PathFinder.findPath duration (ns) above which the mob type auto-learns a 20-tick cooldown. Default 8000000 (8ms).",
                            "[中文] 单次寻路耗时超过此值（纳秒）时，该生物类型自动学习 20 tick 寻路冷却（默认 8ms）。")
                    .defineInRange("severePathfindingLearnThresholdNanos", 8_000_000L, 1_000_000L, 500_000_000L);

            sandboxTransientCooldownTicks = builder
                    .comment(
                            "Cooldown in ticks before a sandboxed BE instance that hit a transient boundary violation returns to workers. Default 20.",
                            "[中文] 沙盒方块实体单实例偶发边界异常回退主线程后的冷却时间，到期自动重返 Worker（默认 20 tick）。")
                    .defineInRange("sandboxTransientCooldownTicks", 20, 1, 1200);

            sandboxPermanentDemotionThreshold = builder
                    .comment(
                            "Number of transient boundary violations for a BE type before permanently demoting it to the server thread. Default 16.",
                            "[中文] 同类型方块实体累计触发边界异常达到此次数后，永久拉黑至主线程并写入 regionthreading-learned.json（默认 16）。")
                    .defineInRange("sandboxPermanentDemotionThreshold", 16, 1, 1000);

            enableAsyncRegionSpawning = builder
                    .comment(
                            "Phase 3A: Dispatches natural spawning calculations asynchronously on region workers via RegionIndependentTickScheduler without blocking the server thread barrier. Requires enableIndependentRegionTickClock and enableRegionThreadingSpawning. Default true.",
                            "[中文] Phase 3A：将自然刷怪计算异步分发到 Region Worker，主线程不再等待刷怪 Barrier，新生物由异步提交队列安全入表（默认开启）。")
                    .define("enableAsyncRegionSpawning", true);

            skipUnloadedNeighborhoodSpawning = builder
                    .comment(
                            "Skips natural mob spawning in outer simulation-distance border chunks whose 3x3 neighbor chunks are not all loaded, instead of falling back to main-thread spawning and full-world entity scans. Default true.",
                            "[中文] 跳过模拟距离最外圈（3x3 邻域未全部加载）边缘区块的自然刷怪，避免回退主线程并触发全图实体扫描（默认开启）。")
                    .define("skipUnloadedNeighborhoodSpawning", true);

            enableAsyncRegionEntityTicks = builder
                    .comment(
                            "EXPERIMENTAL (Phase 3B): Dispatches non-player entity ticks asynchronously per region via RegionIndependentTickScheduler without blocking the server thread barrier. Default false.",
                            "[中文] 实验性（Phase 3B）：将各区域非玩家实体异步脱离 Barrier 分发到 Region Worker（默认关闭，使用带 Barrier 的多核实体并行）。")
                    .define("enableAsyncRegionEntityTicks", false);

            enableSelectiveAsyncEntityTicks = builder
                    .comment(
                            "Selectively dispatches player-free and contraption-free entity regions asynchronously without waiting on the main-thread barrier while keeping player/physics regions synchronized. Default true.",
                            "[中文] 选择性异步实体切片：在未开启全量 Phase 3B 时，将无玩家且无特殊物理耦合的区域优先放入异步队列脱离同步栅栏，仅对有玩家或特殊结构的区域保留同步栅栏（默认开启）。")
                    .define("enableSelectiveAsyncEntityTicks", true);

            enableAsyncRegionFluidTicks = builder
                    .comment(
                            "Phase 3C: Dispatches inland region fluid ticks asynchronously via RegionIndependentTickScheduler without blocking the server thread barrier; border fluid ticks stay serial on the server thread. Default false (parallel fluid workers stay barrier-synchronized with blockTicks unless explicitly enabled).",
                            "[中文] Phase 3C：将区域内陆流体计算异步脱离 Barrier 分发到 Region Worker（默认关闭，使用带 Barrier 的多核流体并行以避免与后续方块阶段并发写同一区块）。")
                    .define("enableAsyncRegionFluidTicks", false);

            independentRegionCommitDrainBudgetNanos = builder
                    .comment(
                            "Maximum nanoseconds per server tick spent draining async region worker commit queues (particles, deferred spawns, block events). Default 4000000 (4ms).",
                            "[中文] 主线程每 Tick 排空异步 Region 提交队列的最大时间预算（纳秒，默认 4ms，防止瞬时堆积导致单帧卡死）。")
                    .defineInRange("independentRegionCommitDrainBudgetNanos", 4_000_000L, 500_000L, 50_000_000L);
        }
    }
}
