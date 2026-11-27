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

        public final IntValue itemCleanupEmergencyHardCap;

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

        public final IntValue sandboxEntityProximityLockstepRadius;

        public final IntValue sandboxSideEffectEscalationTicks;

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

        public final BooleanValue enableUnifiedRegionTickLoop;
        public final BooleanValue enableSingleRegionContraptionWorkers;
        public final IntValue unifiedRegionEntityContainmentMarginBlocks;

        public final IntValue sandboxMaxInstanceBackoffTicks;
        public final IntValue sandboxHopperPlayerSafeRadiusBlocks;
        public final IntValue autosaveMaxQuiesceWaitMillis;
        public final LongValue blockEventsPerRegionBudgetNanos;
        public final IntValue maxSyncNavigationRecomputesPerTick;
        public final BooleanValue enableAsyncInlandBlockScheduledTicks;
        public final IntValue inlandBlockScheduledTickMarginBlocks;
        public final LongValue blockScheduledTickSerialBudgetNanos;
        public final BooleanValue enableSlimeWorkerTicks;
        public final BooleanValue enableHighDensityEntityTrackerThrottle;
        public final IntValue entityTrackerDenseChunkThreshold;
        public final IntValue entityTrackerSevereChunkThreshold;

        public final BooleanValue enableFastChunkPaletteSerialization;
        public final BooleanValue enableAsyncChunkLoadDeserialize;
        public final LongValue chunkUnloadTickBudgetNanos;
        public final IntValue chunkUnloadBacklogMaxPerTick;
        public final BooleanValue enableChunkUnloadSpinGuard;
        public final LongValue incrementalAutosaveBudgetNanos;
        public final BooleanValue enableAsyncPlayerDataSave;
        public final BooleanValue enableParallelWorldGen;
        public final IntValue parallelWorldGenThreads;
        public final BooleanValue enableNonBlockingStructureLookups;
        public final BooleanValue enableAsyncAdvancementSave;

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

            itemCleanupEmergencyHardCap = builder
                    .comment(
                            "Per-region absolute hard cap on dropped ItemEntity count (0 disables). When a single region suddenly bursts past this threshold (e.g. exploded chest or clogged mob farm), oldest items are discarded immediately regardless of itemCleanupMinAgeSeconds (custom-named and whitelisted items are still protected) to prevent chunk-tracking/GC lag spikes. Default 512.",
                            "[中文] 单区域掉落物紧急硬上限（0 为关闭）。当单个区域瞬间爆出超过此数量的掉落物时，立即忽略最小存在时间清理最旧的超出部分（仍保留命名物品与白名单物品），防止瞬间爆掉落物拖累全服发包与 GC。默认 512。")
                    .translation("neoforge.configgui.itemCleanupEmergencyHardCap")
                    .defineInRange("itemCleanupEmergencyHardCap", 512, 0, 100000);

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

            sandboxEntityProximityLockstepRadius = builder
                    .comment(
                            "Spatial radius (in blocks) around active non-player, non-projectile, non-item server-thread-only entities (e.g. moving contraptions) within which adjacent sandboxed block entities co-schedule on the server thread for lockstep interaction (0 disables). Only nearby BEs are affected; the rest of the region stays on worker threads. Default 4.",
                            "[中文] 活跃的非玩家/非抛射物/非掉落物主线程实体（如移动动态结构）周围的局部锁步半径（单位：格，0 为关闭）。仅该半径内紧邻的沙盒方块实体临时与主线程同帧步调，同区域其余方块实体仍留在 Worker 并行，避免单点拖慢全服（默认 4）。")
                    .defineInRange("sandboxEntityProximityLockstepRadius", 4, 0, 32);

            sandboxSideEffectEscalationTicks = builder
                    .comment(
                            "Transient server-thread cooldown (in ticks) applied to a single sandboxed block entity instance when it spawns a non-trivial entity (not ItemEntity/ExperienceOrb) on a worker thread (0 disables). Never permanently demotes the BE type. Default 60.",
                            "[中文] 沙盒方块实体单实例在 Worker 上触发复杂实体生成（非掉落物/经验球）时，仅对该单实例临时升级主线程的冷却时长（单位：tick，0 为关闭），不会永久拉黑该方块实体类型（默认 60）。")
                    .defineInRange("sandboxSideEffectEscalationTicks", 60, 0, 600);

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

            enableUnifiedRegionTickLoop = builder
                    .comment(
                            "=== [6] Phase 4 Unified Per-Region Tick Loop / Phase 4 区域统一单波 Tick 循环 ===",
                            "Phase 4A: Executes each region's Chunks -> Entities -> BlockEntities sequentially inside a single asynchronous worker wave per region tick, eliminating inter-slice phase skew and halving worker task submissions. Requires enableIndependentRegionTickClock and enableRegionSandboxAllBlockEntities. Default false.",
                            "[中文] Phase 4A：启用 Folia 式单区域统一 Tick 循环（同一 Worker 线程按原版顺序依次执行：区域区块随机刻 -> 区域实体 -> 区域方块实体），彻底消除跨阶段相位差与重复任务派发（默认 false）。")
                    .translation("neoforge.configgui.enableUnifiedRegionTickLoop")
                    .define("enableUnifiedRegionTickLoop", false);

            enableSingleRegionContraptionWorkers = builder
                    .comment(
                            "Phase 4B: Allows region-contained moving-structure entities (whose inflated AABB + velocity remain strictly within one owned region by unifiedRegionEntityContainmentMarginBlocks) to tick inside Stage 2 of their owning region's unified worker loop instead of falling back to the server thread. Default false.",
                            "[中文] Phase 4B：允许完全处于单一区域内部（包围盒+速度留有安全边距）的移动动态结构实体在所属 Region Worker 的统一循环 Stage 2 中原位运行，与同区域的动力/逻辑方块实体保持同一线程零延迟步调（默认 false）。")
                    .translation("neoforge.configgui.enableSingleRegionContraptionWorkers")
                    .define("enableSingleRegionContraptionWorkers", false);

            unifiedRegionEntityContainmentMarginBlocks = builder
                    .comment(
                            "Safety margin (in blocks) from region borders required for a region-contained moving-structure entity to tick on its owning region worker under enableSingleRegionContraptionWorkers. Entities closer to a border hand off to the server thread with 0-tick lockstep. Default 8.",
                            "[中文] Phase 4B 移动动态结构实体留在 Region Worker 运行所需的距区域边界最小安全边距（单位：格，默认 8；靠近或跨越边界时自动 0-tick 同步切回主线程）。")
                    .translation("neoforge.configgui.unifiedRegionEntityContainmentMarginBlocks")
                    .defineInRange("unifiedRegionEntityContainmentMarginBlocks", 8, 1, 32);

            sandboxMaxInstanceBackoffTicks = builder
                    .comment(
                            "=== [7] Single-Chunk Lag Isolation & Time-Slicing / 单区块卡顿隔离与主线程切片 ===",
                            "Maximum exponential backoff cooldown (in ticks) for a single block-entity coordinate that repeatedly hits spatial/capability boundary exceptions, instead of pinning it permanently on the server thread. Default 1200 (60s).",
                            "[中文] 单坐标沙盒方块实体反复触发空间/能力边界异常后的最大指数退避冷却时长（单位：tick，默认 1200 即 60 秒；到期后允许重新尝试回 Worker）。")
                    .defineInRange("sandboxMaxInstanceBackoffTicks", 1200, 20, 72000);

            sandboxHopperPlayerSafeRadiusBlocks = builder
                    .comment(
                            "Player-free safety radius (in blocks) required for an interior open-top hopper to run item-pickup on its owning Region Worker under the Phase 4 unified loop. Default 8.",
                            "[中文] Phase 4 统一循环下，内陆裸露漏斗在 Region Worker 原位吸取掉落物所需的周围无玩家安全半径（单位：格，默认 8；0 表示不限制）。")
                    .defineInRange("sandboxHopperPlayerSafeRadiusBlocks", 8, 0, 64);

            autosaveMaxQuiesceWaitMillis = builder
                    .comment(
                            "Maximum milliseconds an incremental (non-flush) autosave waits for slow async regions to finish before skipping busy regions' chunks (keeping them unsaved for the next cycle) instead of stalling server TPS. 0 disables the timeout. Default 15.",
                            "[中文] 增量自动保存（非 flush）等待慢速异步 Region 静默的最大毫秒数（默认 15ms；超时仍在运行的慢区将保留未保存标记留待下轮，不再拖垮全服 TPS；0 表示无限等待）。")
                    .defineInRange("autosaveMaxQuiesceWaitMillis", 15, 0, 10000);

            blockEventsPerRegionBudgetNanos = builder
                    .comment(
                            "Maximum nanoseconds per server tick spent executing block events (e.g. piston pushes) for a single region before deferring remaining events of that region to the next tick without dropping them. 0 disables the cap. Default 2000000 (2ms).",
                            "[中文] 主线程每 Tick 处理单个 Region 方块事件（如活塞推动）的最大耗时预算（纳秒，默认 2ms；超出部分顺延至下一帧而不丢失事件；0 表示不限制）。")
                    .defineInRange("blockEventsPerRegionBudgetNanos", 2_000_000L, 0L, 50_000_000L);

            maxSyncNavigationRecomputesPerTick = builder
                    .comment(
                            "Maximum synchronous PathNavigation.recomputePath() calls executed on the server thread per sendBlockUpdated; excess mobs are marked for deferred recomputation on their owning Region Worker. Default 16.",
                            "[中文] 单次方块更新在主线程同步重算寻路的最大生物数量（默认 16；超出部分标记延迟到生物所属 Region Worker 下一帧异步重算）。")
                    .defineInRange("maxSyncNavigationRecomputesPerTick", 16, 0, 10000);

            enableAsyncInlandBlockScheduledTicks = builder
                    .comment(
                            "EXPERIMENTAL: Coalesces inland single-region scheduled block ticks (whose footprint stays >= inlandBlockScheduledTickMarginBlocks inside one region with 3x3 loaded chunks) into that region's async worker tick. Default false.",
                            "[中文] 实验性：将严格位于单 Region 内陆（距边界 >= inlandBlockScheduledTickMarginBlocks 且 3x3 邻域已加载）的方块计划刻合流至所属 Region 异步 Worker 执行（默认 false）。")
                    .define("enableAsyncInlandBlockScheduledTicks", false);

            inlandBlockScheduledTickMarginBlocks = builder
                    .comment(
                            "Safety margin (in blocks) from region borders required for a scheduled block tick to qualify as inland under enableAsyncInlandBlockScheduledTicks. Default 6.",
                            "[中文] 启用内陆方块计划刻异步化时，计划刻位置距区域边界的最小安全边距（单位：格，默认 6）。")
                    .defineInRange("inlandBlockScheduledTickMarginBlocks", 6, 2, 15);

            blockScheduledTickSerialBudgetNanos = builder
                    .comment(
                            "Maximum nanoseconds per server tick spent executing serial block scheduled ticks for a single region before rescheduling remaining ticks of that region to the next tick. 0 disables the cap. Default 2000000 (2ms).",
                            "[中文] 主线程串行方块计划刻单个 Region 每帧最大耗时预算（纳秒，默认 2ms；超出部分无损推迟至下一 Tick；0 表示不限制）。")
                    .defineInRange("blockScheduledTickSerialBudgetNanos", 2_000_000L, 0L, 50_000_000L);

            enableSlimeWorkerTicks = builder
                    .comment(
                            "Allows Slime and MagmaCube entities to tick on Region Workers while deferring death-split child spawns through the safe admission queue. Default true.",
                            "[中文] 允许史莱姆与岩浆怪在 Region Worker 上并行执行，死亡分裂子体通过延迟队列安全入世（默认 true）。")
                    .worldRestart()
                    .define("enableSlimeWorkerTicks", true);

            enableHighDensityEntityTrackerThrottle = builder
                    .comment(
                            "Adaptively reduces ChunkMap entity-tracker client packet sync frequency in dense chunks (players, cross-border moves, and player vehicles always sync every tick; server logic ticks are never skipped). Default true.",
                            "[中文] 开启高密度区块客户端实体追踪发包自适应降频（玩家、跨区移动与载人载具始终满频同步，且绝不跳过任何服务端实体逻辑帧；默认 true）。")
                    .define("enableHighDensityEntityTrackerThrottle", true);

            entityTrackerDenseChunkThreshold = builder
                    .comment(
                            "Non-player entity count in a single chunk above which entity-tracker sync runs every 2nd tick. Default 64.",
                            "[中文] 单区块追踪实体数超过此阈值时，普通实体客户端同步包降为每 2 tick 发送一次（默认 64）。")
                    .defineInRange("entityTrackerDenseChunkThreshold", 64, 8, 10000);

            entityTrackerSevereChunkThreshold = builder
                    .comment(
                            "Non-player entity count in a single chunk above which entity-tracker sync runs every 4th tick. Default 256.",
                            "[中文] 单区块追踪实体数超过此阈值时，普通实体客户端同步包降为每 4 tick 发送一次（默认 256）。")
                    .defineInRange("entityTrackerSevereChunkThreshold", 256, 16, 10000);

            enableFastChunkPaletteSerialization = builder
                    .comment(
                            "=== [8] Chunk IO & Serialization Optimization / 区块读写与卸载序列化优化 ===",
                            "Bypasses Mojang DFU RecordCodecBuilder/KeyDispatchCodec overhead when reading and writing standard BlockState PalettedContainers in ChunkSerializer by using a direct NBT reader/writer with cached BlockState tags, automatically falling back to vanilla Codec for unknown/invalid entries. Default true.",
                            "[中文] 在 ChunkSerializer 读写方块状态调色板时绕过 Mojang DFU Codec 开销，使用缓存 BlockState NBT 的直接编解码器（遇到未知/异常条目自动回退原版 Codec；默认 true）。")
                    .define("enableFastChunkPaletteSerialization", true);

            enableAsyncChunkLoadDeserialize = builder
                    .comment(
                            "Pre-decodes chunk section BlockState and Biome PalettedContainers on the IO/background thread before handing the chunk to the server thread for ChunkSerializer.read(), cutting main-thread chunk load CPU by 70-85% while keeping POI, LightEngine, and ChunkDataEvent.Load on the server thread. Default true.",
                            "[中文] 在区块从磁盘读出后、交还主线程之前，在后台线程预解码所有 Section 的方块与群系调色板并统计方块计数（POI/光照/事件仍在主线程执行，完全保持原版语义；默认 true）。")
                    .define("enableAsyncChunkLoadDeserialize", true);

            chunkUnloadTickBudgetNanos = builder
                    .comment(
                            "Maximum nanoseconds per server tick spent inside ChunkMap.processUnloads() before yielding remaining unloads to the next tick (saveAllChunks flush is unaffected). 0 disables the cap. Default 4000000 (4ms).",
                            "[中文] 主线程每 Tick 在 ChunkMap.processUnloads() 中处理区块卸载与保存的最大时间预算（纳秒，默认 4ms；超出部分顺延至下一帧，避免跑图卸载时吃满整个 50ms 预算；强制保存 saveAllChunks 不受影响；0 表示不限制）。")
                    .defineInRange("chunkUnloadTickBudgetNanos", 4_000_000L, 0L, 50_000_000L);

            chunkUnloadBacklogMaxPerTick = builder
                    .comment(
                            "Maximum number of forced over-budget unload tasks drained per tick in ChunkMap.processUnloads() when unloadQueue exceeds the vanilla 2000 backlog threshold. Prevents 200-400ms single-tick unload spikes during multi-player exploration. 0 restores vanilla unbounded backlog drain. Default 16.",
                            "[中文] 当 ChunkMap.unloadQueue 超过原版 2000 积压阈值时，单 Tick 允许超出时间预算强制排空的卸载任务硬上限（默认 16；0 表示恢复原版无上限排空），防止多人跑图时单帧同步保存数百区块引发 200-400ms 卡顿。")
                    .defineInRange("chunkUnloadBacklogMaxPerTick", 16, 0, 2000);

            enableChunkUnloadSpinGuard = builder
                    .comment(
                            "Snapshots unloadQueue size at the start of each ChunkMap.processUnloads() tick so ChunkHolders that are not yet isReadyForSaving() and re-enqueue themselves via scheduleUnload() cannot spin repeatedly within the same tick. Default true.",
                            "[中文] 在每 Tick 开始处理 ChunkMap.processUnloads() 时记录当前卸载队列长度快照，防止尚未 isReadyForSaving() 的区块在同一 Tick 内通过 scheduleUnload() 反复重入队空转烧光主线程预算（默认 true）。")
                    .define("enableChunkUnloadSpinGuard", true);

            incrementalAutosaveBudgetNanos = builder
                    .comment(
                            "Maximum nanoseconds per server tick spent inside non-flush ChunkMap.saveAllChunks(false) during routine 5-minute autosaves; remaining dirty chunks continue saving incrementally via ChunkMap.processUnloads(). Flush saves (/save-all flush and server shutdown) are unaffected. 0 disables the cap. Default 4000000 (4ms).",
                            "[中文] 定期 5 分钟自动存档（非强制 flush）期间 ChunkMap.saveAllChunks(false) 在单 Tick 内的最大时间预算（纳秒，默认 4ms；其余脏区块由后续帧的 processUnloads 渐进保存；关服与 /save-all flush 不受影响；0 表示不限制）。")
                    .defineInRange("incrementalAutosaveBudgetNanos", 4_000_000L, 0L, 50_000_000L);

            enableAsyncPlayerDataSave = builder
                    .comment(
                            "Snapshots player CompoundTag on the server thread in PlayerDataStorage.save() and offloads GZIP compression and atomic file replacement to Util.ioPool() serialized per player UUID (awaited before load() for the same UUID and flushed synchronously on shutdown). Default true.",
                            "[中文] 在 PlayerDataStorage.save() 中由主线程完成玩家 NBT 快照后，将 GZIP 压缩与原子写盘异步卸载到 Util.ioPool()（按玩家 UUID 保序，重连 load() 前自动等待写入完成，关服时同步落盘；默认 true），消除每 5 分钟全服玩家存档引发的 1-2 秒主线程卡顿。")
                    .define("enableAsyncPlayerDataSave", true);

            enableParallelWorldGen = builder
                    .comment(
                            "Enables C2ME-style parallel chunk generation across multiple worker threads using spatial area locks (by ChunkStep write radius) instead of ChunkMap's single-threaded worldgen mailbox. Default true.",
                            "[中文] 启用类似 C2ME 的多核并行区块生成（按 ChunkStep 写入半径进行空间区域加锁，替代 ChunkMap 单线程 worldgen 邮箱，大幅加速新玩家进服、切维度与跑图时的区块生成；默认 true）。")
                    .define("enableParallelWorldGen", true);

            parallelWorldGenThreads = builder
                    .comment(
                            "Maximum concurrent worker threads used for C2ME-style parallel chunk generation when enableParallelWorldGen is true (0 = auto: clamp(availableProcessors / 2, 2, 8)). Default 0.",
                            "[中文] 开启并行区块生成时使用的最大并发线程数（0 表示自动，取 clamp(CPU核心数 / 2, 2, 8)；默认 0）。")
                    .defineInRange("parallelWorldGenThreads", 0, 0, 32);

            enableNonBlockingStructureLookups = builder
                    .comment(
                            "Uses non-loading chunk lookups (load = false) in StructureManager.startsForStructure() / fillStartsForStructure() when querying a live ServerLevel, preventing main-thread managedBlock stalls on unloaded structure origin chunks (WorldGenRegion during chunk generation is unaffected). Default true.",
                            "[中文] 在 ServerLevel 上通过 StructureManager 查询结构时使用非加载查询（load = false），跳过尚未加载的结构原点区块，避免主线程被 managedBlock 阻塞（世界生成阶段的 WorldGenRegion 不受影响；默认 true）。")
                    .define("enableNonBlockingStructureLookups", true);

            enableAsyncAdvancementSave = builder
                    .comment(
                            "Snapshots player advancement progress on the server thread in PlayerAdvancements.save() and offloads DFU Codec encoding + GSON serialization + atomic file replacement to Util.ioPool() serialized per player file. Default true.",
                            "[中文] 在 PlayerAdvancements.save() 中由主线程做轻量进度快照后，将 DFU Codec 编码与 GSON 写盘异步卸载到 Util.ioPool()（按文件保序，重连读取前自动等待，关服同步落盘；默认 true），消除每 5 分钟及新玩家进服时的成就保存卡顿。")
                    .define("enableAsyncAdvancementSave", true);
        }
    }
}
