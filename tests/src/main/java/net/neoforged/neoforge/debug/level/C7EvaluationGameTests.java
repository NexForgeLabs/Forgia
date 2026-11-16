/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.level;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.c7.BlockEntityExecutionSystem;
import net.neoforged.neoforge.server.threading.c7.C7Metrics;
import net.neoforged.neoforge.server.threading.c7.C7Runtime;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAttribution;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/** Opt-in real-server benchmark; it makes no claim about active recipes or mod-machine compatibility. */
@ForEachTest(groups = "level.c7")
public class C7EvaluationGameTests {
    private static final int WARMUP = 200;
    private static final int MEASURED = 600;
    private static final int INITIAL_LIT = 1000000;

    @GameTest(batch = "c7Evaluation", timeoutTicks = 4000)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Compares C6/C7/serial on the identical detached furnace-clock workload, default off")
    static void measuresIdenticalFurnaceClockWorkload(DynamicTest test) {
        test.onGameTest(helper -> {
            if (!Boolean.getBoolean("forgia.c7Evaluation")) {
                helper.succeed();
                return;
            }
            if (Boolean.getBoolean("forgia.c7AllVanilla")) {
                C7VanillaEvaluation.run(helper);
                return;
            }
            final var level = helper.getLevel();
            helper.assertTrue(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine, "region engine required");
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            final int side = Integer.getInteger("forgia.c7EvaluationSide", 32);
            helper.assertTrue(side >= 2 && side <= 64, "side must be 2..64");
            final var origin = helper.absolutePos(BlockPos.ZERO);
            final int baseX = Math.floorDiv(origin.getX(), 48) * 48 + 16;
            final int baseZ = Math.floorDiv(origin.getZ(), 48) * 48 + 16;
            final List<BlockPos> positions = new ArrayList<>();
            final List<ChunkPos> chunks = new ArrayList<>();
            for (int x = 0; x < side; ++x) for (int z = 0; z < side; ++z) positions.add(new BlockPos(baseX + x, 160, baseZ + z));
            for (int x = (baseX >> 4); x <= ((baseX + side - 1) >> 4); ++x)
                for (int z = (baseZ >> 4); z <= ((baseZ + side - 1) >> 4); ++z) chunks.add(new ChunkPos(x, z));
            final var sampler = new ServerLoadBenchmarkGameTests.TickSampler(WARMUP, MEASURED, null);
            final var beforeC7 = new AtomicReference<C7Metrics.Snapshot>();
            final var beforeC6 = new AtomicReference<RegionThreadingDiagnostics>();
            final var beforeFurnace = new AtomicReference<net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Snapshot>();
            final var beforeResources = new AtomicReference<net.neoforged.neoforge.server.threading.region.BlockEntityResourceMetrics.Snapshot>();
            final var beforeJvm = new AtomicReference<Map<String, Long>>();
            final var started = new AtomicLong();
            final var placementTick = new AtomicLong();
            final var windowEnd = new WindowEnd(sampler, engine, started);
            final var cleaned = new AtomicBoolean();
            final Runnable cleanup = () -> {
                if (!cleaned.compareAndSet(false, true)) return;
                NeoForge.EVENT_BUS.unregister(sampler);
                NeoForge.EVENT_BUS.unregister(windowEnd);
                RegionBlockEntityTickAttribution.setEnabled(false);
                for (final var position : positions) level.setBlock(position, Blocks.AIR.defaultBlockState(), 3);
                for (final var chunk : chunks) {
                    level.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
                    level.setChunkForced(chunk.x, chunk.z, false);
                }
            };
            helper.addEndListener(passed -> cleanup.run());
            sampler.onMeasuredWindowStart(() -> {
                beforeC7.set(engine.c7Metrics());
                beforeC6.set(engine.diagnostics());
                beforeFurnace.set(engine.furnaceWorkMetrics());
                beforeResources.set(engine.blockEntityResourceMetrics());
                beforeJvm.set(jvm());
                started.set(System.nanoTime());
                RegionBlockEntityTickAttribution.reset();
            });
            helper.startSequence()
                    .thenExecute(() -> {
                        for (final var chunk : chunks) {
                            level.setChunkForced(chunk.x, chunk.z, true);
                            level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 2, chunk, true);
                        }
                    })
                    .thenWaitUntil(() -> helper.assertTrue(chunks.stream().allMatch(chunk -> level.areEntitiesLoaded(chunk.toLong())), "chunks not entity-loaded"))
                    .thenExecute(() -> {
                        for (final var position : positions) {
                            level.setBlock(position, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT, true), 3);
                            final var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(position);
                            final var tag = new CompoundTag();
                            tag.putInt("BurnTime", INITIAL_LIT);
                            tag.putInt("CookTime", 40);
                            tag.putInt("CookTimeTotal", 200);
                            furnace.loadWithComponents(tag, level.registryAccess());
                        }
                        placementTick.set(level.getGameTime());
                        RegionBlockEntityTickAttribution.setEnabled(true);
                        sampler.startWindow(level.getGameTime());
                        NeoForge.EVENT_BUS.register(sampler);
                        NeoForge.EVENT_BUS.register(windowEnd);
                    })
                    .thenIdle(WARMUP + MEASURED + 1)
                    .thenExecute(() -> {
                        NeoForge.EVENT_BUS.unregister(sampler);
                        final long[] samples = sampler.sortedSamples();
                        helper.assertTrue(samples.length == MEASURED, "incomplete sample window");
                        helper.assertTrue(windowEnd.c7 != null, "missing exact window-end counters");
                        final var afterC7 = windowEnd.c7;
                        final var afterC6 = windowEnd.c6;
                        final var afterJvm = windowEnd.jvm;
                        final long wall = windowEnd.wall;
                        final var attribution = windowEnd.attribution;
                        final var report = new LinkedHashMap<String, Object>();
                        report.put("kind", "uncapped real GameTestServer full tick body, no players, empty-input furnace clocks only");
                        report.put("driver", C7Runtime.system());
                        report.put("c6FurnaceWaves", net.neoforged.neoforge.common.ForgiaConfig.SERVER.enableRegionThreadingFurnaceWorkWaves.get());
                        report.put("c6Resources", net.neoforged.neoforge.common.ForgiaConfig.SERVER.enableRegionThreadingBlockEntityResources.get());
                        report.put("seed", 4242424242L);
                        report.put("warmupTicks", WARMUP);
                        report.put("measuredTicks", samples.length);
                        report.put("furnaces", positions.size());
                        report.put("chunks", chunks.size());
                        report.put("workers", afterC6.workerThreadCount());
                        report.put("java", System.getProperty("java.runtime.version"));
                        report.put("processors", Runtime.getRuntime().availableProcessors());
                        report.put("msptAverage", Arrays.stream(samples).average().orElseThrow() / 1000000.0);
                        report.put("msptP95", samples[(int) Math.ceil(samples.length * .95) - 1] / 1000000.0);
                        report.put("msptP99", samples[(int) Math.ceil(samples.length * .99) - 1] / 1000000.0);
                        final long parallel = C7Runtime.system() == BlockEntityExecutionSystem.C7 ? afterC7.parallel() - beforeC7.get().parallel()
                                : afterC6.blockEntityTicksOnWorkers() - beforeC6.get().blockEntityTicksOnWorkers();
                        final long serial = C7Runtime.system() == BlockEntityExecutionSystem.C7 ? afterC7.serial() - beforeC7.get().serial()
                                : C7Runtime.system() == BlockEntityExecutionSystem.DISABLED ? (long) positions.size() * MEASURED
                                        : afterC6.fallbackBlockEntityTicks() - beforeC6.get().fallbackBlockEntityTicks();
                        report.put("parallelInvocations", parallel);
                        report.put("serialInvocations", serial);
                        report.put("parallelRatio", parallel + serial == 0 ? 0 : (double) parallel / (parallel + serial));
                        report.put("serialRatio", parallel + serial == 0 ? 0 : (double) serial / (parallel + serial));
                        report.put("c7Before", beforeC7.get());
                        report.put("c7After", afterC7);
                        report.put("c6FurnaceBefore", beforeFurnace.get());
                        report.put("c6FurnaceAfter", windowEnd.furnace);
                        report.put("c6ResourcesBefore", beforeResources.get());
                        report.put("c6ResourcesAfter", windowEnd.resources);
                        report.put("mods", net.neoforged.fml.ModList.get().getMods().stream().map(mod -> mod.getModId() + "@" + mod.getVersion()).sorted().toList());
                        report.put("c6PlanningMsPerTick", attribution.planNanos() / 1000000.0 / MEASURED);
                        report.put("c6LeaseMsPerTick", attribution.leaseNanos() / 1000000.0 / MEASURED);
                        report.put("c6HandoffMsPerTick", attribution.handoffNanos() / 1000000.0 / MEASURED);
                        report.put("jvmBefore", beforeJvm.get());
                        report.put("jvmAfter", afterJvm);
                        report.put("workerCpuUtilization", (afterJvm.get("workerCpuNanos") - beforeJvm.get().get("workerCpuNanos")) / (double) wall / afterC6.workerThreadCount());
                        report.put("processCpuCores", (afterJvm.get("processCpuNanos") - beforeJvm.get().get("processCpuNanos")) / (double) wall);
                        report.put("allocationBytes", afterJvm.get("allocationBytes") - beforeJvm.get().get("allocationBytes"));
                        report.put("heapDeltaBytes", afterJvm.get("heapBytes") - beforeJvm.get().get("heapBytes"));
                        final var first = (AbstractFurnaceBlockEntity) level.getBlockEntity(positions.getFirst());
                        final var clock = first.forgia$c7ClockSnapshot();
                        helper.assertTrue(clock != null && clock.cookingProgress() == 0, "invalid furnace result");
                        final long elapsedTicks = level.getGameTime() - placementTick.get();
                        helper.assertTrue(INITIAL_LIT - clock.litTime() == elapsedTicks, "duplicate/missing clock tick");
                        for (final var position : positions) {
                            final var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(position);
                            helper.assertTrue(clock.equals(furnace.forgia$c7ClockSnapshot()), "machine divergence at " + position);
                            for (int slot = 0; slot < 3; ++slot) helper.assertTrue(furnace.getItem(slot).isEmpty(), "inventory mutation");
                        }
                        report.put("finalClock", clock);
                        report.put("elapsedFixtureTicks", elapsedTicks);
                        report.put("ownershipViolations", afterC6.ownershipViolationCount() - beforeC6.get().ownershipViolationCount());
                        report.put("correctness", "all clocks advanced exactly once per game tick; all inventories empty");
                        if (C7Runtime.system() == BlockEntityExecutionSystem.C7) helper.assertTrue(parallel > 0, "C7 did not execute detached work");
                        try {
                            final String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
                            Files.writeString(Path.of("c7-evaluation.json"), json + "\n");
                            System.out.println("C7_EVALUATION " + json.replace('\n', ' '));
                        } catch (java.io.IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                        cleanup.run();
                    })
                    .thenSucceed();
        });
    }

    /** Capture exactly after the 600th sampled Post event, before the next tick can change counters. */
    public static final class WindowEnd {
        private final ServerLoadBenchmarkGameTests.TickSampler sampler;
        private final RegionThreadingEngine engine;
        private final AtomicLong started;
        C7Metrics.Snapshot c7;
        RegionThreadingDiagnostics c6;
        RegionBlockEntityTickAttribution.Snapshot attribution;
        net.neoforged.neoforge.server.threading.region.FurnaceWorkMetrics.Snapshot furnace;
        net.neoforged.neoforge.server.threading.region.BlockEntityResourceMetrics.Snapshot resources;
        Map<String, Long> jvm;
        long wall;

        WindowEnd(ServerLoadBenchmarkGameTests.TickSampler sampler, RegionThreadingEngine engine, AtomicLong started) {
            this.sampler = sampler;
            this.engine = engine;
            this.started = started;
        }

        @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
        public void onPost(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
            if (this.c7 != null || this.sampler.sampleCount() != MEASURED) return;
            this.wall = System.nanoTime() - this.started.get();
            this.c7 = this.engine.c7Metrics();
            this.c6 = this.engine.diagnostics();
            this.furnace = this.engine.furnaceWorkMetrics();
            this.resources = this.engine.blockEntityResourceMetrics();
            this.attribution = RegionBlockEntityTickAttribution.snapshot();
            this.jvm = jvm();
        }
    }

    static Map<String, Long> jvm() {
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long workerCpu = 0;
        for (final long id : threads.getAllThreadIds()) {
            final var info = threads.getThreadInfo(id);
            if (info != null && info.getThreadName().startsWith("NeoForge Region Worker")) workerCpu += Math.max(0, threads.getThreadCpuTime(id));
        }
        long allocation = 0;
        if (threads.isThreadAllocatedMemorySupported()) {
            if (!threads.isThreadAllocatedMemoryEnabled()) threads.setThreadAllocatedMemoryEnabled(true);
            for (final long bytes : threads.getThreadAllocatedBytes(threads.getAllThreadIds())) allocation += Math.max(0, bytes);
        }
        return Map.of("workerCpuNanos", workerCpu, "processCpuNanos", ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime(),
                "allocationBytes", allocation, "heapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }
}
