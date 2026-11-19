/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.DynamicRegionTopology;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionThreadingOptions;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Scalability, fairness, worker-utilization, topology-churn, and matched layout comparison audit for
 * experimental {@link RegionPolicy#DYNAMIC_CLUSTER}.
 */
@Timeout(90)
class DynamicClusterScalabilityAuditTest {
    private static final RegionTickBarrier SPIN_PARK_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            Thread.onSpinWait();
        }
    };
    private static final int RANDOM_TICK_SPEED = 1;

    private RegionThreadingEngine engine;

    @AfterEach
    void tearDown() {
        RegionThreadingManager.clear();
        if (this.engine != null) {
            this.engine.shutdown();
            this.engine = null;
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void fairnessHotIslandPlusQuietIslandsAndMigratingWorkload() {
        final int workers = 4;
        final int islandCount = 8;
        final RegionLayout layout = RegionLayout.dynamicCluster(3, 40L);
        this.installEngine(workers, layout);
        final ServerLevel level = mockLevel();

        // Island 0 is a large hot island (8 sections); islands 1..7 are small quiet islands (2 sections each).
        // Each island is separated by 3 sections (24 chunks) so all 8 remain distinct loaded islands.
        final List<LevelChunk> chunks = new ArrayList<>();
        final int[] perIslandSectionCounts = new int[islandCount];
        final AtomicInteger hotIsland = new AtomicInteger(0);
        final AtomicLong batchStartNanos = new AtomicLong();
        final AtomicInteger[] islandTickExecutions = new AtomicInteger[islandCount];
        final List<Long>[] islandWaitNanos = new List[islandCount];
        final List<Long>[] islandCompleteNanos = new List[islandCount];
        final AtomicLong[] perTickIslandFirstStart = new AtomicLong[islandCount];
        final AtomicLong[] perTickIslandLastEnd = new AtomicLong[islandCount];
        for (int i = 0; i < islandCount; ++i) {
            islandTickExecutions[i] = new AtomicInteger();
            islandWaitNanos[i] = new ArrayList<>();
            islandCompleteNanos[i] = new ArrayList<>();
            perTickIslandFirstStart[i] = new AtomicLong(Long.MAX_VALUE);
            perTickIslandLastEnd[i] = new AtomicLong(0L);
        }

        for (int island = 0; island < islandCount; ++island) {
            final int sections = island == 0 ? 8 : 2;
            perIslandSectionCounts[island] = sections;
            final int baseSectionX = island * 4;
            for (int s = 0; s < sections; ++s) {
                final int chunkX = (baseSectionX + (s % 2)) * 8;
                final int chunkZ = (s / 2) * 8;
                final int islandIdx = island;
                final boolean heavySection = (s == 0);
                this.engine.addRegionSection(level, chunkX, chunkZ);
                chunks.add(chunk(new ChunkPos(chunkX, chunkZ), () -> {
                    final long now = System.nanoTime();
                    perTickIslandFirstStart[islandIdx].accumulateAndGet(now, Math::min);
                    // Hot island's primary section does 350us of work; quiet sections do 15us.
                    final long workNanos = (islandIdx == hotIsland.get() && heavySection) ? 350_000L : 15_000L;
                    busySpinNanos(workNanos);
                    perTickIslandLastEnd[islandIdx].accumulateAndGet(System.nanoTime(), Math::max);
                    islandTickExecutions[islandIdx].incrementAndGet();
                }));
            }
        }

        // Phase A: 30 ticks with island 0 hot; Phase B: 30 ticks with hot spot rotating every 5 ticks.
        final int totalTicks = 60;
        for (int tick = 0; tick < totalTicks; ++tick) {
            if (tick >= 30) {
                hotIsland.set(((tick - 30) / 5) % islandCount);
            }
            for (int i = 0; i < islandCount; ++i) {
                perTickIslandFirstStart[i].set(Long.MAX_VALUE);
                perTickIslandLastEnd[i].set(0L);
            }
            this.engine.beginLevelTick(level);
            try {
                batchStartNanos.set(System.nanoTime());
                this.runChunkTick(level, chunks);
            } finally {
                this.engine.endLevelTick(level);
            }

            final long start = batchStartNanos.get();
            for (int i = 0; i < islandCount; ++i) {
                final long firstStart = perTickIslandFirstStart[i].get();
                final long lastEnd = perTickIslandLastEnd[i].get();
                assertTrue(firstStart >= start && firstStart != Long.MAX_VALUE,
                        "Island " + i + " starved on tick " + tick);
                islandWaitNanos[i].add(Math.max(0L, firstStart - start));
                islandCompleteNanos[i].add(Math.max(0L, lastEnd - start));
            }
        }

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(islandCount, topology.regionCount(), "All 8 islands should remain distinct");
        assertTrue(topology.coalescedSubmissions() > 0, "Expected coalesced submissions under 8-island load");

        final StringBuilder report = new StringBuilder("[AUDIT:FAIRNESS] workers=" + workers + " islands=" + islandCount + " ticks=" + totalTicks + "\n");
        for (int i = 0; i < islandCount; ++i) {
            // Every eligible section on every island must execute every single tick (0 starvation).
            assertEquals(totalTicks * perIslandSectionCounts[i], islandTickExecutions[i].get(),
                    "Island " + i + " missed eligible work");
            final Stats waitStats = Stats.of(islandWaitNanos[i]);
            final Stats completeStats = Stats.of(islandCompleteNanos[i]);
            report.append(String.format(Locale.ROOT,
                    "  island=%d sections=%d wait_us(mean=%.1f p50=%.1f p95=%.1f max=%.1f) complete_us(mean=%.1f p95=%.1f max=%.1f)%n",
                    i, perIslandSectionCounts[i],
                    waitStats.meanUs, waitStats.p50Us, waitStats.p95Us, waitStats.maxUs,
                    completeStats.meanUs, completeStats.p95Us, completeStats.maxUs));
            // Bounded maximum wait: no quiet island may wait indefinitely or starve.
            assertTrue(waitStats.p95Us < 50_000.0,
                    "Island " + i + " p95 wait exceeded 50ms bound: " + waitStats.p95Us + "us");
        }
        System.out.print(report);
    }

    @Test
    void workerUtilizationAcrossOneTwoFourEightIslands() {
        final int workers = 4;
        final int[] islandCounts = { 1, 2, 4, 8 };
        final StringBuilder report = new StringBuilder("[AUDIT:UTILIZATION] workers=" + workers + "\n");

        for (final int islandCount : islandCounts) {
            this.tearDown();
            this.installEngine(workers, RegionLayout.dynamicCluster(3, 40L));
            final ServerLevel level = mockLevel();
            final List<LevelChunk> chunks = new ArrayList<>();
            final Set<String> activeThreads = ConcurrentHashMap.newKeySet();
            final AtomicLong totalWorkerBusyNanos = new AtomicLong();

            // Always 16 total sections distributed evenly across islandCount islands so every case has enough
            // work to saturate all 4 workers.
            final int sectionsPerIsland = 16 / islandCount;
            for (int island = 0; island < islandCount; ++island) {
                final int baseSectionX = island * 8;
                for (int s = 0; s < sectionsPerIsland; ++s) {
                    final int chunkX = (baseSectionX + (s % 4)) * 8;
                    final int chunkZ = (s / 4) * 8;
                    this.engine.addRegionSection(level, chunkX, chunkZ);
                    chunks.add(chunk(new ChunkPos(chunkX, chunkZ), () -> {
                        activeThreads.add(Thread.currentThread().getName());
                        final long t0 = System.nanoTime();
                        busySpinNanos(1_500_000L);
                        totalWorkerBusyNanos.addAndGet(System.nanoTime() - t0);
                    }));
                }
            }

            // Warmup 3 ticks, measure 15 ticks.
            for (int t = 0; t < 3; ++t) {
                this.engine.beginLevelTick(level);
                try {
                    this.runChunkTick(level, chunks);
                } finally {
                    this.engine.endLevelTick(level);
                }
            }
            activeThreads.clear();
            totalWorkerBusyNanos.set(0L);
            final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
            assertNotNull(topology);
            final long savedBefore = topology.coalescedSubmissions();
            final long wallStart = System.nanoTime();
            final int measuredTicks = 15;
            for (int t = 0; t < measuredTicks; ++t) {
                this.engine.beginLevelTick(level);
                try {
                    this.runChunkTick(level, chunks);
                } finally {
                    this.engine.endLevelTick(level);
                }
            }
            final long wallNanos = System.nanoTime() - wallStart;
            final long saved = topology.coalescedSubmissions() - savedBefore;
            final double utilization = (double) totalWorkerBusyNanos.get() / (wallNanos * (double) workers);

            report.append(String.format(Locale.ROOT,
                    "  islands=%d sectionsPerIsland=%d distinctWorkers=%d/%d utilization=%.2f submissionsSaved=%d wallMs=%.2f%n",
                    islandCount, sectionsPerIsland, activeThreads.size(), workers, utilization, saved, wallNanos / 1e6));

            assertEquals(islandCount, topology.regionCount());
            assertTrue(activeThreads.size() >= Math.min(workers, 2),
                    "Expected parallel worker usage for " + islandCount + " island(s), got " + activeThreads.size());
            assertTrue(saved > 0, "Expected coalescing to save submissions when 16 sections > 4 workers");
            assertTrue(utilization > 0.25, "Worker utilization collapsed to " + utilization + " for " + islandCount + " islands");
        }
        System.out.print(report);
    }

    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void orderedSpawningSubmissionsKeepIndependentTasksAndMakeConcurrentProgress() throws Exception {
        // Verify that even when all 4 sections belong to the SAME loaded island, enabling ordered spawn
        // admissions prevents groupByLoadedIsland coalescing so all 4 participants can rendezvous concurrently
        // on 4 workers without deadlocking.
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 40L);
        for (int s = 0; s < 4; ++s) {
            topology.addChunk(s * 8, 0);
        }
        topology.reconcile(1L);
        assertEquals(1, topology.regionCount(), "All 4 adjacent sections must form one loaded island");

        final ServerLevel level = mockLevel();
        final Class<?> batchClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionTickBatch");
        final Class<?> spawnBatchClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionSpawnBatch");
        final var ctor = spawnBatchClass.getDeclaredConstructor(ServerLevel.class, boolean.class, boolean.class, boolean.class, int.class);
        ctor.setAccessible(true);

        // Test BOTH call orders:
        // (1) enableOrderedSpawnAdmissions before useLoadedIslandDispatch
        // (2) useLoadedIslandDispatch before enableOrderedSpawnAdmissions
        final Method enableOrdered = batchClass.getDeclaredMethod("enableOrderedSpawnAdmissions", int.class, Runnable.class);
        enableOrdered.setAccessible(true);
        final Method useIslandDispatch = batchClass.getDeclaredMethod("useLoadedIslandDispatch", DynamicRegionTopology.class);
        useIslandDispatch.setAccessible(true);
        final Field dispatchTopologyField = batchClass.getDeclaredField("dispatchTopology");
        dispatchTopologyField.setAccessible(true);

        for (final boolean enableFirst : new boolean[] { true, false }) {
            final Object batch = ctor.newInstance(level, true, true, false, 16);
            if (enableFirst) {
                enableOrdered.invoke(batch, 4, (Runnable) () -> {});
                useIslandDispatch.invoke(batch, topology);
            } else {
                useIslandDispatch.invoke(batch, topology);
                enableOrdered.invoke(batch, 4, (Runnable) () -> {});
            }
            assertNull(dispatchTopologyField.get(batch),
                    "Ordered spawn batch must never retain dispatchTopology (enableFirst=" + enableFirst + ")");
        }

        // Verify that a RegionTaskBatch without groupByLoadedIsland allows 4 tasks from the same island
        // to rendezvous concurrently on a 4-worker pool.
        final var poolClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final var poolCtor = poolClass.getDeclaredConstructor(int.class);
        poolCtor.setAccessible(true);
        final var pool = (net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor) poolCtor.newInstance(4);
        final Method closePool = poolClass.getDeclaredMethod("close");
        closePool.setAccessible(true);
        try {
            final CyclicBarrier rendezvous = new CyclicBarrier(4);
            final RegionTaskBatch taskBatch = new RegionTaskBatch();
            for (int i = 0; i < 4; ++i) {
                taskBatch.add("ordered-spawn-" + i, () -> {
                    try {
                        rendezvous.await(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            final RegionTaskBatch.Barrier barrier = taskBatch.submit(pool);
            barrier.awaitCompletion();
            barrier.throwIfFailed();
        } finally {
            closePool.invoke(pool);
        }
    }

    @Test
    void topologyChurnStabilityHysteresisAndUnloadBaseline() {
        // 1. High-frequency bridge oscillation (period < splitDelayTicks) must NOT cause merge/split thrashing.
        final DynamicRegionTopology hysteresisTopology = new DynamicRegionTopology(3, 1, 40L);
        hysteresisTopology.addChunk(0, 0);   // Island A at section (0,0)
        hysteresisTopology.addChunk(16, 0);  // Island B at section (2,0)
        hysteresisTopology.reconcile(1L);
        assertEquals(2, hysteresisTopology.regionCount());

        // Bridge loads at tick 2 -> 1 merge.
        hysteresisTopology.addChunk(8, 0);
        hysteresisTopology.reconcile(2L);
        assertEquals(1, hysteresisTopology.regionCount());
        assertEquals(1L, hysteresisTopology.mergeCount());
        assertEquals(0L, hysteresisTopology.splitCount());

        // Repeatedly unload and reload bridge every 10 ticks (< 40 tick split delay) for 200 ticks.
        for (long tick = 3L; tick <= 202L; ++tick) {
            if ((tick - 3L) % 20L == 0L) {
                hysteresisTopology.removeChunk(8, 0);
            } else if ((tick - 3L) % 20L == 10L) {
                hysteresisTopology.addChunk(8, 0);
            }
            hysteresisTopology.reconcile(tick);
        }
        assertEquals(1, hysteresisTopology.regionCount(), "Hysteresis should keep bridged islands stable");
        assertEquals(1L, hysteresisTopology.mergeCount(), "No extra merges under fast oscillation");
        assertEquals(0L, hysteresisTopology.splitCount(), "No split oscillation under fast oscillation");

        // 2. Compare splitDelayTicks (0 vs 10 vs 40) and sectionShift (2 vs 3 vs 4) under sustained travel.
        final StringBuilder report = new StringBuilder("[AUDIT:CHURN]\n");
        for (final int shift : new int[] { 2, 3, 4 }) {
            for (final long delay : new long[] { 0L, 10L, 40L }) {
                final DynamicRegionTopology topo = new DynamicRegionTopology(shift, 1, delay);
                final int secSize = 1 << shift;
                // Two anchors separated by 2 sections, with a traveler oscillating across the gap every 30 ticks.
                topo.addChunk(0, 0);
                topo.addChunk(2 * secSize, 0);
                topo.reconcile(1L);
                for (long tick = 2L; tick <= 300L; ++tick) {
                    final long phase = tick % 30L;
                    if (phase == 2L) {
                        topo.addChunk(secSize, 0);
                    } else if (phase == 12L) {
                        topo.removeChunk(secSize, 0);
                    }
                    topo.reconcile(tick);
                }
                report.append(String.format(Locale.ROOT,
                        "  shift=%d delay=%d merges=%d splits=%d regions=%d activeSections=%d%n",
                        shift, delay, topo.mergeCount(), topo.splitCount(), topo.regionCount(), topo.activeSectionCount()));
                if (delay >= 20L) {
                    // Gap between unload (phase 12) and next load (phase 2 of next 30-tick cycle) is 20 ticks < 40.
                    assertEquals(0L, topo.splitCount(), "delay=" + delay + " must suppress 20-tick oscillation");
                }
            }
        }
        System.out.print(report);

        // 3. Full engine churn + complete unload must return topology, regions, leases, and batches to zero baseline.
        this.installEngine(4, RegionLayout.dynamicCluster(3, 5L));
        final ServerLevel level = mockLevel();
        final List<ChunkPos> loaded = new ArrayList<>();
        for (int cycle = 0; cycle < 20; ++cycle) {
            final int offset = cycle * 4;
            for (int s = 0; s < 6; ++s) {
                final ChunkPos pos = new ChunkPos((offset + s) * 8, 0);
                this.engine.addRegionSection(level, pos.x, pos.z);
                loaded.add(pos);
            }
            this.engine.beginLevelTick(level);
            try {
                final List<LevelChunk> batchChunks = new ArrayList<>();
                for (final ChunkPos pos : loaded) {
                    batchChunks.add(chunk(pos, () -> {}));
                }
                this.runChunkTick(level, batchChunks);
            } finally {
                this.engine.endLevelTick(level);
            }

            // Unload all chunks from this cycle.
            for (final ChunkPos pos : loaded) {
                this.engine.removeRegionSection(level, pos.x, pos.z);
            }
            loaded.clear();
            // Advance past splitDelayTicks (5) so every emptied section detaches and retires.
            for (int wait = 0; wait <= 6; ++wait) {
                this.engine.beginLevelTick(level);
                this.engine.endLevelTick(level);
            }
            final DynamicRegionTopology topo = this.engine.dynamicTopologyOrNull(level);
            assertNotNull(topo);
            assertEquals(0, topo.regionCount(), "Leaked dynamic region after unload in cycle " + cycle);
            assertEquals(0, topo.activeSectionCount(), "Leaked active section after unload in cycle " + cycle);
            assertEquals(0, topo.loadedChunkCount(), "Leaked loaded chunk after unload in cycle " + cycle);
            assertEquals(0, topo.pendingEmptySectionCount(), "Leaked pending empty section in cycle " + cycle);
            assertEquals(0, this.engine.diagnostics().regionCount(), "Leaked RegionState after unload in cycle " + cycle);
            assertEquals(0, this.engine.diagnostics().runningRegionCount(), "Leaked lease after unload in cycle " + cycle);
            assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        }

        // Level unload must also clean up topology completely.
        this.engine.clearLevelRegionTasks(level);
        assertNull(this.engine.dynamicTopologyOrNull(level));
        assertEquals(0, this.engine.diagnostics().regionCount());
    }

    @Test
    void matchedLayoutComparisonAndSoakReport() {
        final RegionLayout[] layouts = {
                RegionLayout.FIXED_3X3,
                RegionLayout.DYNAMIC,
                RegionLayout.dynamicCluster(3, 40L)
        };
        final int workers = 4;
        final int warmupTicks = 15;
        final int measuredTicks = 40;
        final int runs = 3;
        final int soakTicks = 120;

        final StringBuilder report = new StringBuilder(
                "[AUDIT:LAYOUT_COMPARISON] workers=" + workers + " warmup=" + warmupTicks
                        + " measured=" + measuredTicks + "x" + runs + " soak=" + soakTicks + "\n");

        for (final RegionLayout layout : layouts) {
            final List<Long> allTickNanos = new ArrayList<>(measuredTicks * runs);
            long totalAllocBytes = 0L;
            long totalGcCount = 0L;
            long totalGcMs = 0L;
            double utilSum = 0.0;
            long lastMerges = 0L;
            long lastSplits = 0L;
            long lastSaved = 0L;

            for (int run = 0; run < runs; ++run) {
                final RunResult result = this.runMatchedWorkload(layout, workers, warmupTicks, measuredTicks);
                allTickNanos.addAll(result.tickNanos);
                totalAllocBytes += result.allocatedBytes;
                totalGcCount += result.gcCount;
                totalGcMs += result.gcTimeMs;
                utilSum += result.workerUtilization;
                lastMerges = result.merges;
                lastSplits = result.splits;
                lastSaved = result.submissionsSaved;
            }

            final RunResult soak = this.runMatchedWorkload(layout, workers, 10, soakTicks);
            final Stats agg = Stats.of(allTickNanos);
            final Stats soakStats = Stats.of(soak.tickNanos);

            report.append(String.format(Locale.ROOT,
                    "  layout=%-15s runs(mean=%.1fus p50=%.1fus p95=%.1fus p99=%.1fus max=%.1fus util=%.2f allocKiB=%.0f gc=%d/%dms merges=%d splits=%d saved=%d) "
                            + "soak(mean=%.1fus p95=%.1fus p99=%.1fus max=%.1fus maxRegions=%d)%n",
                    layout.policy().name(),
                    agg.meanUs, agg.p50Us, agg.p95Us, agg.p99Us, agg.maxUs,
                    utilSum / runs, totalAllocBytes / 1024.0, totalGcCount, totalGcMs,
                    lastMerges, lastSplits, lastSaved,
                    soakStats.meanUs, soakStats.p95Us, soakStats.p99Us, soakStats.maxUs, soak.maxRegionCount));

            assertEquals(0, soak.finalRegionCount, "Leaked regions after soak unload for " + layout.policy());
            assertEquals(0, soak.finalRunningRegions, "Leaked leases after soak unload for " + layout.policy());
            assertEquals(0, soak.ownershipViolations, "Ownership violations for " + layout.policy());
            assertTrue(soak.maxRegionCount <= 64, "Unbounded region growth during soak: " + soak.maxRegionCount);
        }
        System.out.print(report);
    }

    private RunResult runMatchedWorkload(RegionLayout layout, int workers, int warmupTicks, int measuredTicks) {
        this.tearDown();
        this.installEngine(workers, layout);
        final ServerLevel level = mockLevel();

        // 4 islands (4 sections each = 16 sections), island 0 has 1 hot section, plus a bridge chunk that
        // periodically loads and unloads to exercise churn.
        final List<LevelChunk> baseChunks = new ArrayList<>();
        final List<ChunkPos> basePositions = new ArrayList<>();
        final AtomicLong workerBusyNanos = new AtomicLong();
        final AtomicInteger tickExecutions = new AtomicInteger();

        for (int island = 0; island < 4; ++island) {
            final int baseX = island * 24; // 3 sections apart in 8x8 -> sections 0..1, 3..4, 6..7, 9..10; bridge at section 2 (chunk 16)
            for (int s = 0; s < 4; ++s) {
                final ChunkPos pos = new ChunkPos(baseX + (s % 2) * 8, (s / 2) * 8);
                final boolean hot = (island == 0 && s == 0);
                this.engine.addRegionSection(level, pos.x, pos.z);
                basePositions.add(pos);
                baseChunks.add(chunk(pos, () -> {
                    final long t0 = System.nanoTime();
                    busySpinNanos(hot ? 600_000L : 80_000L);
                    workerBusyNanos.addAndGet(System.nanoTime() - t0);
                    tickExecutions.incrementAndGet();
                }));
            }
        }
        final ChunkPos bridgePos = new ChunkPos(16, 0);
        final LevelChunk bridgeChunk = chunk(bridgePos, () -> {
            final long t0 = System.nanoTime();
            busySpinNanos(80_000L);
            workerBusyNanos.addAndGet(System.nanoTime() - t0);
            tickExecutions.incrementAndGet();
        });

        // Warmup
        for (int t = 0; t < warmupTicks; ++t) {
            this.engine.beginLevelTick(level);
            try {
                this.runChunkTick(level, baseChunks);
            } finally {
                this.engine.endLevelTick(level);
            }
        }

        final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean ? bean : null;
        final long allocBefore = threadBean != null && threadBean.isThreadAllocatedMemorySupported()
                ? threadBean.getCurrentThreadAllocatedBytes() : 0L;
        final long gcCountBefore = totalGcCount();
        final long gcMsBefore = totalGcTimeMs();
        workerBusyNanos.set(0L);

        final List<Long> tickNanos = new ArrayList<>(measuredTicks);
        int maxRegionCount = 0;
        boolean bridgeLoaded = false;
        final long wallStart = System.nanoTime();
        for (int t = 0; t < measuredTicks; ++t) {
            // Periodic travel bridge churn every 50 ticks.
            if (t % 50 == 10 && !bridgeLoaded) {
                this.engine.addRegionSection(level, bridgePos.x, bridgePos.z);
                bridgeLoaded = true;
            } else if (t % 50 == 20 && bridgeLoaded) {
                this.engine.removeRegionSection(level, bridgePos.x, bridgePos.z);
                bridgeLoaded = false;
            }
            final List<LevelChunk> activeChunks = bridgeLoaded ? append(baseChunks, bridgeChunk) : baseChunks;
            final int expected = activeChunks.size();
            tickExecutions.set(0);

            final long t0 = System.nanoTime();
            this.engine.beginLevelTick(level);
            try {
                this.runChunkTick(level, activeChunks);
            } finally {
                this.engine.endLevelTick(level);
            }
            tickNanos.add(System.nanoTime() - t0);

            assertEquals(expected, tickExecutions.get(), "Missed chunk tick on tick " + t + " for " + layout.policy());
            maxRegionCount = Math.max(maxRegionCount, this.engine.diagnostics().regionCount());
        }
        final long wallNanos = System.nanoTime() - wallStart;
        final long allocAfter = threadBean != null && threadBean.isThreadAllocatedMemorySupported()
                ? threadBean.getCurrentThreadAllocatedBytes() : 0L;
        final long gcCount = totalGcCount() - gcCountBefore;
        final long gcMs = totalGcTimeMs() - gcMsBefore;
        final double util = (double) workerBusyNanos.get() / (wallNanos * (double) workers);

        final DynamicRegionTopology topo = this.engine.dynamicTopologyOrNull(level);
        final long merges = topo != null ? topo.mergeCount() : 0L;
        final long splits = topo != null ? topo.splitCount() : 0L;
        final long saved = topo != null ? topo.coalescedSubmissions() : 0L;

        // Unload all chunks and verify clean return to baseline.
        if (bridgeLoaded) {
            this.engine.removeRegionSection(level, bridgePos.x, bridgePos.z);
        }
        for (final ChunkPos pos : basePositions) {
            this.engine.removeRegionSection(level, pos.x, pos.z);
        }
        this.engine.clearLevelRegionTasks(level);
        final var diag = this.engine.diagnostics();
        return new RunResult(
                tickNanos,
                Math.max(0L, allocAfter - allocBefore),
                gcCount,
                gcMs,
                util,
                merges,
                splits,
                saved,
                maxRegionCount,
                diag.regionCount(),
                diag.runningRegionCount(),
                diag.ownershipViolationCount());
    }

    private void runChunkTick(ServerLevel level, List<LevelChunk> chunks) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, SPIN_PARK_BARRIER);
        for (final LevelChunk chunk : chunks) {
            plan.accept(chunk);
        }
        plan.executeAndAwait(SPIN_PARK_BARRIER);
    }

    private static List<LevelChunk> append(List<LevelChunk> base, LevelChunk extra) {
        final List<LevelChunk> copy = new ArrayList<>(base.size() + 1);
        copy.addAll(base);
        copy.add(extra);
        return copy;
    }

    private static void busySpinNanos(long durationNanos) {
        final long deadline = System.nanoTime() + durationNanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static long totalGcCount() {
        long count = 0L;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long c = gc.getCollectionCount();
            if (c > 0L) count += c;
        }
        return count;
    }

    private static long totalGcTimeMs() {
        long ms = 0L;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long t = gc.getCollectionTime();
            if (t > 0L) ms += t;
        }
        return ms;
    }

    private void installEngine(int workerCount, RegionLayout layout) {
        final RegionThreadingOptions options = new RegionThreadingOptions(
                new RegionThreadingOptions.Workers(workerCount, true, false, false, false),
                new RegionThreadingOptions.TickSlices(false, false, false, true, false),
                layout,
                RegionEntityStorageMode.forConfiguration(true, false, layout));
        this.engine = new RegionThreadingEngine(options);
        RegionThreadingManager.install(this.engine);
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("scalability-audit-level").when(level).toString();
        doReturn(1234L).when(level).getSeed();
        doReturn(20L).when(level).getGameTime();
        when(level.dimension()).thenReturn(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse("minecraft:overworld")));
        doReturn(8).when(level).entitySectionRegionChunkSize();
        doReturn(false).when(level).isRaining();
        doReturn(false).when(level).isThundering();
        doNothing().when(level).tickPrecipitation(any());
        doNothing().when(level).tickLightning(any());
        final GameRules gameRules = mock(GameRules.class);
        when(gameRules.getInt(GameRules.RULE_RANDOMTICKING)).thenReturn(RANDOM_TICK_SPEED);
        when(gameRules.getBoolean(GameRules.RULE_DOMOBSPAWNING)).thenReturn(false);
        doReturn(gameRules).when(level).getGameRules();
        final ServerChunkCache chunkSource = mock(ServerChunkCache.class);
        doReturn(true).when(chunkSource).hasChunk(anyInt(), anyInt());
        doAnswer(invocation -> {
            SPIN_PARK_BARRIER.await(invocation.getArgument(0));
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        return level;
    }

    private static LevelChunk chunk(ChunkPos pos, Runnable onRandomTick) {
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(false);
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            onRandomTick.run();
            return null;
        }).when(blockState).randomTick(any(ServerLevel.class), any(BlockPos.class), any(RandomSource.class));
        final LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.isRandomlyTicking()).thenReturn(true);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenReturn(blockState);
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(pos);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionYFromSectionIndex(0)).thenReturn(4);
        return chunk;
    }

    private static void setLevelField(ServerLevel level, String name, Object value) {
        try {
            final Field field = net.minecraft.world.level.Level.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(level, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record RunResult(
            List<Long> tickNanos,
            long allocatedBytes,
            long gcCount,
            long gcTimeMs,
            double workerUtilization,
            long merges,
            long splits,
            long submissionsSaved,
            int maxRegionCount,
            int finalRegionCount,
            int finalRunningRegions,
            long ownershipViolations) {}

    private record Stats(double meanUs, double p50Us, double p95Us, double p99Us, double maxUs) {
        static Stats of(List<Long> samples) {
            final long[] sorted = new long[samples.size()];
            long sum = 0L;
            for (int i = 0; i < samples.size(); ++i) {
                final long v = samples.get(i);
                sorted[i] = v;
                sum += v;
            }
            Arrays.sort(sorted);
            return new Stats(
                    (double) sum / sorted.length / 1_000.0,
                    percentile(sorted, 0.50) / 1_000.0,
                    percentile(sorted, 0.95) / 1_000.0,
                    percentile(sorted, 0.99) / 1_000.0,
                    sorted[sorted.length - 1] / 1_000.0);
        }

        private static long percentile(long[] sorted, double p) {
            final int index = Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1);
            return sorted[Math.max(0, index)];
        }
    }
}
