/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.DynamicRegionTopology;
import net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionThreadingOptions;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Deterministic ownership audit for experimental {@link RegionPolicy#DYNAMIC_CLUSTER} dispatch.
 * <p>
 * Proves:
 * <ol>
 *     <li>Merge handoff preserves exclusive per-section leases, never releases old leases before physical worker
 *         completion, and never makes a stale topology snapshot valid.</li>
 *     <li>Split handoff creates disjoint ownership domains, completes pre-split queued work safely under its
 *         original lease, and leaks zero leases/reservations/references across repeated churn.</li>
 *     <li>Global phase boundaries prevent topology mutation while any batch or leased worker can still observe
 *         the previous topology, and serialize concurrent submissions around the boundary.</li>
 *     <li>Chunk load/unload during or adjacent to merge/split leaves no orphaned sections or duplicate dispatch,
 *         and level unload fences all admitted work touching loaded chunks.</li>
 *     <li>Cross-section access remains governed by section leases and admission rules; topology adjacency in
 *         {@link DynamicRegionTopology} never implies ownership permission.</li>
 * </ol>
 */
@Timeout(60)
class DynamicClusterOwnershipAuditTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };
    private static final int RANDOM_TICK_SPEED = 3;

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
    void twoSectionsMergeSplitMergeWithPausedWorkersPreservesExclusiveOwnership() throws Exception {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final ChunkPos leftChunk = new ChunkPos(1, 1);    // section (0,0)
        final ChunkPos bridgeChunk = new ChunkPos(8, 1);  // section (1,0)
        final ChunkPos rightChunk = new ChunkPos(17, 1);  // section (2,0)
        final BlockPos leftPos = new BlockPos(leftChunk.getMinBlockX(), 64, leftChunk.getMinBlockZ());
        final BlockPos bridgePos = new BlockPos(bridgeChunk.getMinBlockX(), 64, bridgeChunk.getMinBlockZ());
        final BlockPos rightPos = new BlockPos(rightChunk.getMinBlockX(), 64, rightChunk.getMinBlockZ());

        // Stage 1: two disjoint islands (0,0) and (2,0)
        this.engine.addRegionSection(level, leftChunk.x, leftChunk.z);
        this.engine.addRegionSection(level, rightChunk.x, rightChunk.z);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(2, topology.regionCount());
        final int preMergeLeftId = topology.regionIdAtSection(0, 0);
        final int preMergeRightId = topology.regionIdAtSection(2, 0);
        final long preMergeVersion = topology.topologyVersion();
        assertNotEquals(preMergeLeftId, preMergeRightId);

        // Pause workers on BOTH sections while holding leases, then queue a merge and attempt reconciliation.
        final CountDownLatch bothEntered = new CountDownLatch(2);
        final CountDownLatch releaseStage1 = new CountDownLatch(1);
        final Map<Long, AtomicInteger> activePerSection = new ConcurrentHashMap<>();
        final AtomicInteger maxOverlap = new AtomicInteger();
        final AtomicBoolean sawMutatedTopologyMidTask = new AtomicBoolean();
        final AtomicBoolean workerOwnershipViolation = new AtomicBoolean();

        this.engine.regionScheduler().execute(level, leftPos, () -> {
            recordExclusiveEntry(activePerSection, DynamicRegionTopology.sectionKey(0, 0), maxOverlap);
            try {
                if (!this.engine.isOwnedByCurrentRegion(level, leftPos)
                        || this.engine.isOwnedByCurrentRegion(level, rightPos)) {
                    workerOwnershipViolation.set(true);
                }
                bothEntered.countDown();
                awaitOrFail(releaseStage1, "stage 1 left worker release");
                if (topology.topologyVersion() != preMergeVersion
                        || topology.regionIdAtSection(0, 0) != preMergeLeftId
                        || topology.regionIdAtSection(2, 0) != preMergeRightId) {
                    sawMutatedTopologyMidTask.set(true);
                }
            } finally {
                recordExclusiveExit(activePerSection, DynamicRegionTopology.sectionKey(0, 0));
            }
        });
        this.engine.regionScheduler().execute(level, rightPos, () -> {
            recordExclusiveEntry(activePerSection, DynamicRegionTopology.sectionKey(2, 0), maxOverlap);
            try {
                if (!this.engine.isOwnedByCurrentRegion(level, rightPos)
                        || this.engine.isOwnedByCurrentRegion(level, leftPos)) {
                    workerOwnershipViolation.set(true);
                }
                bothEntered.countDown();
                awaitOrFail(releaseStage1, "stage 1 right worker release");
                if (topology.topologyVersion() != preMergeVersion
                        || topology.regionIdAtSection(0, 0) != preMergeLeftId
                        || topology.regionIdAtSection(2, 0) != preMergeRightId) {
                    sawMutatedTopologyMidTask.set(true);
                }
            } finally {
                recordExclusiveExit(activePerSection, DynamicRegionTopology.sectionKey(2, 0));
            }
        });

        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            final Future<?> drainStage1 = caller.submit(() -> this.engine.drainLevelRegionTasks(level));
            awaitOrFail(bothEntered, "both stage 1 workers should hold leases");
            assertEquals(2, this.engine.diagnostics().runningRegionCount(),
                    "both section leases must remain held while workers are paused");

            // Request bridge load + topology reconciliation while workers are paused: must NOT mutate!
            this.engine.addRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level);
            assertEquals(preMergeVersion, topology.topologyVersion(),
                    "topology must not mutate while workers hold leases");
            assertEquals(2, topology.regionCount(), "merge must wait until workers finish");
            assertEquals(2, this.engine.diagnostics().runningRegionCount(),
                    "old leases must not be released before workers physically finish");

            releaseStage1.countDown();
            drainStage1.get(10, TimeUnit.SECONDS);
            assertEquals(0, this.engine.diagnostics().runningRegionCount(),
                    "all leases must be released after physical worker join");
            assertFalse(sawMutatedTopologyMidTask.get(), "paused workers must observe a stable topology");
            assertFalse(workerOwnershipViolation.get(), "workers must own only their own section");
            assertEquals(1, maxOverlap.get(), "no two owners may concurrently hold the same section");

            // Stage 2: merge at quiescent global safe point
            this.engine.beginLevelTick(level);
            this.engine.endLevelTick(level);
            assertEquals(1, topology.regionCount());
            assertEquals(1L, topology.mergeCount());
            final int mergedId = topology.regionIdAtSection(0, 0);
            assertEquals(mergedId, topology.regionIdAtSection(1, 0));
            assertEquals(mergedId, topology.regionIdAtSection(2, 0));
            final long mergedVersion = topology.topologyVersion();
            assertTrue(mergedVersion > preMergeVersion);

            // Pause worker on merged island, unload bridge, attempt split before worker finishes.
            final CountDownLatch mergedEntered = new CountDownLatch(1);
            final CountDownLatch releaseStage2 = new CountDownLatch(1);
            this.engine.regionScheduler().execute(level, leftPos, () -> {
                recordExclusiveEntry(activePerSection, DynamicRegionTopology.sectionKey(0, 0), maxOverlap);
                try {
                    // Even after merge, holding (0,0) does NOT grant ownership of (1,0) or (2,0).
                    if (!this.engine.isOwnedByCurrentRegion(level, leftPos)
                            || this.engine.isOwnedByCurrentRegion(level, bridgePos)
                            || this.engine.isOwnedByCurrentRegion(level, rightPos)) {
                        workerOwnershipViolation.set(true);
                    }
                    mergedEntered.countDown();
                    awaitOrFail(releaseStage2, "stage 2 worker release");
                    if (topology.topologyVersion() != mergedVersion || topology.regionCount() != 1) {
                        sawMutatedTopologyMidTask.set(true);
                    }
                } finally {
                    recordExclusiveExit(activePerSection, DynamicRegionTopology.sectionKey(0, 0));
                }
            });

            final Future<?> drainStage2 = caller.submit(() -> this.engine.drainRegionTasks(level, leftPos));
            awaitOrFail(mergedEntered, "stage 2 worker should hold lease");
            assertEquals(1, this.engine.diagnostics().runningRegionCount());

            this.engine.removeRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level);
            assertEquals(mergedVersion, topology.topologyVersion(),
                    "split must not run while a worker holds a lease");
            assertEquals(1, topology.regionCount());

            releaseStage2.countDown();
            drainStage2.get(10, TimeUnit.SECONDS);
            assertEquals(0, this.engine.diagnostics().runningRegionCount());
            assertFalse(sawMutatedTopologyMidTask.get());
            assertFalse(workerOwnershipViolation.get());

            // Stage 3: split at quiescent global safe point -> disjoint ownership domains
            this.engine.beginLevelTick(level);
            this.engine.endLevelTick(level);
            assertEquals(2, topology.regionCount());
            assertEquals(1L, topology.splitCount());
            assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(1, 0));
            assertNotEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(2, 0));
            assertNotEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(0, 0));
            assertNotEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(2, 0));

            // Execute chunk ticks on both post-split islands and verify exclusive ownership.
            this.engine.beginLevelTick(level);
            try {
                final TickRecorder leftRec = new TickRecorder();
                final TickRecorder rightRec = new TickRecorder();
                final RegionChunkTickPlan plan = this.beginChunkPlan(level);
                plan.accept(chunk(leftChunk, leftRec, () -> assertChunkSectionExclusivity(level, leftPos, rightPos, activePerSection, maxOverlap)));
                plan.accept(chunk(rightChunk, rightRec, () -> assertChunkSectionExclusivity(level, rightPos, leftPos, activePerSection, maxOverlap)));
                plan.executeAndAwait(PARKING_BARRIER);
                assertEquals(RANDOM_TICK_SPEED, leftRec.ticks.size());
                assertEquals(RANDOM_TICK_SPEED, rightRec.ticks.size());
            } finally {
                this.engine.endLevelTick(level);
            }
            assertEquals(0, this.engine.diagnostics().runningRegionCount());

            // Stage 4: re-merge at global safe point
            this.engine.addRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level);
            this.engine.endLevelTick(level);
            assertEquals(1, topology.regionCount());
            assertEquals(2L, topology.mergeCount());
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(1, 0));
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(2, 0));
            assertEquals(1, maxOverlap.get());
            assertEquals(0, this.engine.diagnostics().runningRegionCount());
        }
    }

    @Test
    void diagonalAdjacencyAndCornerContactMergeAndSplitPreserveExclusiveOwnership() {
        this.installEngine(1, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final ChunkPos cornerA = new ChunkPos(1, 1);     // section (0,0)
        final ChunkPos diagBridge = new ChunkPos(9, 9);  // section (1,1) - corner-only contact with (0,0) and (2,2)
        final ChunkPos cornerB = new ChunkPos(17, 17);   // section (2,2)
        final ChunkPos negCorner = new ChunkPos(-7, -7); // section (-1,-1) - corner-only contact with (0,0)

        this.engine.addRegionSection(level, cornerA.x, cornerA.z);
        this.engine.addRegionSection(level, cornerB.x, cornerB.z);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(2, topology.regionCount());
        assertNotEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(2, 2));

        // Add corner-only bridge (1,1) and negative corner (-1,-1) -> all four merge diagonally!
        this.engine.addRegionSection(level, diagBridge.x, diagBridge.z);
        this.engine.addRegionSection(level, negCorner.x, negCorner.z);
        this.engine.beginLevelTick(level);
        try {
            assertEquals(1, topology.regionCount());
            final int unified = topology.regionIdAtSection(0, 0);
            assertEquals(unified, topology.regionIdAtSection(1, 1));
            assertEquals(unified, topology.regionIdAtSection(2, 2));
            assertEquals(unified, topology.regionIdAtSection(-1, -1));

            // With 1 worker, all 4 sections coalesce into the same loaded island task, yet each section
            // must execute under its own exclusive lease and never own a diagonally adjacent section.
            final Map<Long, AtomicInteger> activePerSection = new ConcurrentHashMap<>();
            final AtomicInteger maxOverlap = new AtomicInteger();
            final BlockPos posA = new BlockPos(cornerA.getMinBlockX(), 64, cornerA.getMinBlockZ());
            final BlockPos posBridge = new BlockPos(diagBridge.getMinBlockX(), 64, diagBridge.getMinBlockZ());
            final BlockPos posB = new BlockPos(cornerB.getMinBlockX(), 64, cornerB.getMinBlockZ());
            final BlockPos posNeg = new BlockPos(negCorner.getMinBlockX(), 64, negCorner.getMinBlockZ());

            final TickRecorder recA = new TickRecorder();
            final TickRecorder recBridge = new TickRecorder();
            final TickRecorder recB = new TickRecorder();
            final TickRecorder recNeg = new TickRecorder();
            final RegionChunkTickPlan plan = this.beginChunkPlan(level);
            plan.accept(chunk(cornerA, recA, () -> assertChunkSectionExclusivity(level, posA, posBridge, activePerSection, maxOverlap)));
            plan.accept(chunk(diagBridge, recBridge, () -> assertChunkSectionExclusivity(level, posBridge, posB, activePerSection, maxOverlap)));
            plan.accept(chunk(cornerB, recB, () -> assertChunkSectionExclusivity(level, posB, posBridge, activePerSection, maxOverlap)));
            plan.accept(chunk(negCorner, recNeg, () -> assertChunkSectionExclusivity(level, posNeg, posA, activePerSection, maxOverlap)));
            plan.executeAndAwait(PARKING_BARRIER);

            assertEquals(RANDOM_TICK_SPEED, recA.ticks.size());
            assertEquals(RANDOM_TICK_SPEED, recBridge.ticks.size());
            assertEquals(RANDOM_TICK_SPEED, recB.ticks.size());
            assertEquals(RANDOM_TICK_SPEED, recNeg.ticks.size());
            assertEquals(1, maxOverlap.get());
        } finally {
            this.engine.endLevelTick(level);
        }

        // Remove corner-only bridge (1,1) -> splits cleanly into {(-1,-1),(0,0)} and {(2,2)}.
        this.engine.removeRegionSection(level, diagBridge.x, diagBridge.z);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);
        assertEquals(2, topology.regionCount());
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(1, 1));
        assertEquals(topology.regionIdAtSection(-1, -1), topology.regionIdAtSection(0, 0));
        assertNotEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(2, 2));
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void activeRegionPhaseBlocksBeginLevelTickTopologyMutationUntilBatchJoins() throws Exception {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final ChunkPos leftChunk = new ChunkPos(1, 1);   // section (0,0)
        final ChunkPos bridgeChunk = new ChunkPos(8, 1); // section (1,0)
        final ChunkPos rightChunk = new ChunkPos(17, 1); // section (2,0)

        this.engine.addRegionSection(level, leftChunk.x, leftChunk.z);
        this.engine.addRegionSection(level, rightChunk.x, rightChunk.z);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(2, topology.regionCount());
        final long versionBefore = topology.topologyVersion();
        final int leftIdBefore = topology.regionIdAtSection(0, 0);

        final CountDownLatch workerInsideBatch = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final AtomicBoolean observedMutation = new AtomicBoolean();
        final AtomicBoolean pausedOnce = new AtomicBoolean();

        final TickRecorder recorder = new TickRecorder();
        final LevelChunk pausedChunk = chunk(leftChunk, recorder, () -> {
            if (pausedOnce.compareAndSet(false, true)) {
                workerInsideBatch.countDown();
                awaitOrFail(releaseWorker, "chunk tick worker release");
            }
            if (topology.topologyVersion() != versionBefore
                    || topology.regionIdAtSection(0, 0) != leftIdBefore
                    || topology.regionCount() != 2) {
                observedMutation.set(true);
            }
        });

        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            final Future<?> batchFuture = caller.submit(() -> {
                final RegionChunkTickPlan plan = this.beginChunkPlan(level);
                plan.accept(pausedChunk);
                plan.executeAndAwait(PARKING_BARRIER);
            });

            awaitOrFail(workerInsideBatch, "chunk tick worker must enter batch");
            assertEquals(1, this.engine.diagnostics().runningRegionCount());

            // Another thread loads a bridge and tries to trigger reconciliation mid-batch.
            this.engine.addRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level);
            assertEquals(versionBefore, topology.topologyVersion(),
                    "beginLevelTick must not mutate topology while a region batch is in flight");
            assertEquals(2, topology.regionCount());

            releaseWorker.countDown();
            batchFuture.get(10, TimeUnit.SECONDS);
            assertEquals(0, this.engine.diagnostics().runningRegionCount());
            assertFalse(observedMutation.get(), "worker in flight must never observe topology mutation");
            assertEquals(RANDOM_TICK_SPEED, recorder.ticks.size());

            // Now that the batch has physically joined, the next global safe point reconciles the merge.
            this.engine.beginLevelTick(level);
            this.engine.endLevelTick(level);
            assertEquals(1, topology.regionCount());
            assertTrue(topology.topologyVersion() > versionBefore);
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(2, 0));
        }
    }

    @Test
    void concurrentSubmissionsAroundGlobalPhaseBoundaryNeverObserveTornOrDualOwnership() throws Exception {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final BlockPos leftPos = new BlockPos(16, 64, 16);      // section (0,0)
        final BlockPos bridgePos = new BlockPos(8 * 16, 64, 16); // section (1,0)
        final BlockPos rightPos = new BlockPos(17 * 16, 64, 16); // section (2,0)

        this.engine.addRegionSection(level, 1, 1);
        this.engine.addRegionSection(level, 17, 1);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);

        final Map<Long, AtomicInteger> activePerSection = new ConcurrentHashMap<>();
        final AtomicInteger maxOverlap = new AtomicInteger();
        final AtomicInteger executed = new AtomicInteger();
        final AtomicInteger submitted = new AtomicInteger();
        final AtomicBoolean tornTopologyObserved = new AtomicBoolean();
        final AtomicBoolean crossOwnershipObserved = new AtomicBoolean();
        final AtomicBoolean stopSubmitters = new AtomicBoolean();

        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            final List<Future<?>> submitters = new ArrayList<>();
            for (int t = 0; t < 2; ++t) {
                final int threadIdx = t;
                submitters.add(pool.submit(() -> {
                    final BlockPos ownPos = threadIdx == 0 ? leftPos : rightPos;
                    final BlockPos otherPos = threadIdx == 0 ? rightPos : leftPos;
                    final int ownSecX = threadIdx == 0 ? 0 : 2;
                    final long secKey = DynamicRegionTopology.sectionKey(ownSecX, 0);
                    while (!stopSubmitters.get()) {
                        submitted.incrementAndGet();
                        this.engine.regionScheduler().execute(level, ownPos, () -> {
                            recordExclusiveEntry(activePerSection, secKey, maxOverlap);
                            try {
                                final long vStart = topology.topologyVersion();
                                final int idStart = topology.regionIdAtSection(ownSecX, 0);
                                if (!this.engine.isOwnedByCurrentRegion(level, ownPos)
                                        || this.engine.isOwnedByCurrentRegion(level, otherPos)
                                        || this.engine.isOwnedByCurrentRegion(level, bridgePos)) {
                                    crossOwnershipObserved.set(true);
                                }
                                Thread.yield();
                                final long vEnd = topology.topologyVersion();
                                final int idEnd = topology.regionIdAtSection(ownSecX, 0);
                                if (vStart != vEnd || idStart != idEnd) {
                                    tornTopologyObserved.set(true);
                                }
                                executed.incrementAndGet();
                            } finally {
                                recordExclusiveExit(activePerSection, secKey);
                            }
                        });
                        this.engine.drainRegionTasks(level, ownPos);
                    }
                }));
            }

            // Server thread drives repeated merge/split global boundaries concurrently with submissions.
            for (int cycle = 0; cycle < 20; ++cycle) {
                if ((cycle & 1) == 0) {
                    this.engine.addRegionSection(level, 8, 1);
                } else {
                    this.engine.removeRegionSection(level, 8, 1);
                }
                this.engine.beginLevelTick(level);
                this.engine.endLevelTick(level);
                LockSupport.parkNanos(500_000L);
            }

            stopSubmitters.set(true);
            for (final Future<?> future : submitters) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        // Final drain ensures every submitted task ran exactly once.
        this.engine.drainLevelRegionTasks(level);
        this.engine.drainLevelRegionTasks(level);

        assertEquals(submitted.get(), executed.get(), "every submitted task must execute exactly once");
        assertEquals(1, maxOverlap.get(), "no two workers may ever overlap on the same section");
        assertFalse(tornTopologyObserved.get(), "topology must never mutate while a worker task is executing");
        assertFalse(crossOwnershipObserved.get(), "workers must never own another section across merge/split");
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void preSplitQueuedWorkCompletesSafelyUnderOriginalLeaseAndStaleSnapshotIsInvalid() {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final BlockPos leftPos = new BlockPos(16, 64, 16);       // section (0,0)
        final BlockPos bridgePos = new BlockPos(8 * 16, 64, 16); // section (1,0)
        final BlockPos rightPos = new BlockPos(17 * 16, 64, 16); // section (2,0)

        this.engine.addRegionSection(level, 1, 1);
        this.engine.addRegionSection(level, 8, 1);
        this.engine.addRegionSection(level, 17, 1);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(1, topology.regionCount());
        final int preSplitIslandId = topology.regionIdAtSection(0, 0);
        assertEquals(preSplitIslandId, topology.regionIdAtSection(1, 0));
        assertEquals(preSplitIslandId, topology.regionIdAtSection(2, 0));

        // Queue work against all 3 sections BEFORE unloading the bridge and splitting.
        final List<String> ran = new CopyOnWriteArrayList<>();
        final AtomicBoolean ownershipOk = new AtomicBoolean(true);
        this.engine.regionScheduler().execute(level, leftPos, () -> {
            if (!this.engine.isOwnedByCurrentRegion(level, leftPos)
                    || this.engine.isOwnedByCurrentRegion(level, rightPos)) {
                ownershipOk.set(false);
            }
            ran.add("left");
        });
        this.engine.regionScheduler().execute(level, bridgePos, () -> {
            if (!this.engine.isOwnedByCurrentRegion(level, bridgePos)
                    || this.engine.isOwnedByCurrentRegion(level, leftPos)) {
                ownershipOk.set(false);
            }
            ran.add("bridge");
        });
        this.engine.regionScheduler().execute(level, rightPos, () -> {
            if (!this.engine.isOwnedByCurrentRegion(level, rightPos)
                    || this.engine.isOwnedByCurrentRegion(level, leftPos)) {
                ownershipOk.set(false);
            }
            ran.add("right");
        });

        // Unload bridge and reconcile split BEFORE draining the queued tasks.
        this.engine.removeRegionSection(level, 8, 1);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        assertEquals(2, topology.regionCount());
        assertEquals(1L, topology.splitCount());
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(1, 0));
        assertNotEquals(preSplitIslandId, topology.regionIdAtSection(2, 0));

        // Drain: all pre-split queued work completes cleanly under its own section lease,
        // and the emptied bridge section retires once its queued task finishes.
        this.engine.drainLevelRegionTasks(level);
        assertTrue(ownershipOk.get());
        assertEquals(3, ran.size());
        assertTrue(ran.containsAll(List.of("left", "bridge", "right")));
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(2, this.engine.diagnostics().regionCount(),
                "emptied bridge section must retire after its pre-split queued task completes");

        // Stale pre-split island ID must NOT see entities in the split-off right section or unloaded bridge.
        final FixedEntitySectionPartitions<Entity> partitions = new FixedEntitySectionPartitions<>(this.engine.regionLayout());
        final Entity eLeft = mock(Entity.class);
        final Entity eBridge = mock(Entity.class);
        final Entity eRight = mock(Entity.class);
        addEntityToSection(partitions, SectionPos.asLong(1, 4, 1), eLeft);
        addEntityToSection(partitions, SectionPos.asLong(8, 4, 1), eBridge);
        addEntityToSection(partitions, SectionPos.asLong(17, 4, 1), eRight);

        final List<Entity> foundUnderPreSplitId = new ArrayList<>();
        partitions.forEachAccessibleNonEmptySectionInDynamicRegion(
                topology,
                preSplitIslandId,
                new AABB(0, 0, 0, 20 * 16, 128, 32),
                section -> {
                    section.getEntities().forEach(foundUnderPreSplitId::add);
                    return AbortableIterationConsumer.Continuation.CONTINUE;
                });
        assertEquals(List.of(eLeft), foundUnderPreSplitId,
                "stale pre-split island ID must not grant visibility into split-off or unloaded sections");
    }

    @Test
    void chunkUnloadFencesAdmittedWorkAndLeavesNoOrphanedSections() throws Exception {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final BlockPos posA = new BlockPos(16, 64, 16);      // section (0,0)
        final BlockPos posB = new BlockPos(8 * 16, 64, 16);  // section (1,0)

        this.engine.addRegionSection(level, 1, 1);
        this.engine.addRegionSection(level, 8, 1);
        this.engine.beginLevelTick(level);
        this.engine.endLevelTick(level);

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(1, topology.regionCount());

        // 1. Unload a chunk while a worker holds a lease on its section:
        //    RegionState must NOT retire until the worker finishes, and topology must wait for safe point.
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final AtomicBoolean workerFinished = new AtomicBoolean();
        this.engine.regionScheduler().execute(level, posA, () -> {
            workerStarted.countDown();
            awaitOrFail(releaseWorker, "chunk unload worker release");
            workerFinished.set(true);
        });

        try (ExecutorService caller = Executors.newFixedThreadPool(2)) {
            final Future<?> drainFuture = caller.submit(() -> this.engine.drainRegionTasks(level, posA));
            awaitOrFail(workerStarted, "worker must hold lease before chunk unload");
            assertEquals(1, this.engine.diagnostics().runningRegionCount());

            this.engine.removeRegionSection(level, 1, 1);
            assertEquals(1, this.engine.diagnostics().runningRegionCount(),
                    "unloading chunk must not prematurely release an active lease");
            assertEquals(2, this.engine.diagnostics().regionCount(),
                    "leased RegionState must stay alive until worker physically completes");

            // 2. Level unload (clearLevelRegionTasks) must physically fence the in-flight worker!
            final CountDownLatch clearStarted = new CountDownLatch(1);
            final AtomicBoolean clearCompletedBeforeWorker = new AtomicBoolean();
            final Future<?> clearFuture = caller.submit(() -> {
                clearStarted.countDown();
                this.engine.clearLevelRegionTasks(level);
                if (!workerFinished.get()) {
                    clearCompletedBeforeWorker.set(true);
                }
            });

            awaitOrFail(clearStarted, "clearLevelRegionTasks should start");
            Thread.sleep(50L);
            assertFalse(clearFuture.isDone(), "level unload must block until admitted worker physically joins");

            releaseWorker.countDown();
            drainFuture.get(10, TimeUnit.SECONDS);
            clearFuture.get(10, TimeUnit.SECONDS);

            assertFalse(clearCompletedBeforeWorker.get(), "unload must never complete before admitted work finishes");
            assertNull(this.engine.dynamicTopologyOrNull(level), "level unload must clear dynamic topology");
            assertEquals(0, this.engine.diagnostics().runningRegionCount());
            assertEquals(0, this.engine.diagnostics().regionCount());

            // Post-unload chunk additions for the closed level must be rejected with zero orphans.
            this.engine.addRegionSection(level, 1, 1);
            assertNull(this.engine.dynamicTopologyOrNull(level));
            assertEquals(0, this.engine.diagnostics().regionCount());
        }
    }

    @Test
    void crossSectionAccessRemainsGovernedBySectionLeaseNotTopologyAdjacency() {
        this.installEngine(1, RegionLayout.dynamicCluster(3, 0L));
        final ServerLevel level = mockLevel();
        final ChunkPos sec00 = new ChunkPos(1, 1); // section (0,0)
        final ChunkPos sec10 = new ChunkPos(9, 1); // section (1,0) - orthogonally adjacent
        final ChunkPos sec11 = new ChunkPos(9, 9); // section (1,1) - diagonally adjacent
        final BlockPos pos00 = new BlockPos(sec00.getMinBlockX(), 64, sec00.getMinBlockZ());
        final BlockPos pos10 = new BlockPos(sec10.getMinBlockX(), 64, sec10.getMinBlockZ());
        final BlockPos pos11 = new BlockPos(sec11.getMinBlockX(), 64, sec11.getMinBlockZ());

        this.engine.addRegionSection(level, sec00.x, sec00.z);
        this.engine.addRegionSection(level, sec10.x, sec10.z);
        this.engine.addRegionSection(level, sec11.x, sec11.z);
        this.engine.beginLevelTick(level);
        try {
            final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
            assertNotNull(topology);
            assertEquals(1, topology.regionCount());
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(1, 0));
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(1, 1));

            // Even though all 3 sections are merged in DynamicRegionTopology and coalesced onto 1 worker,
            // each section's execution authority is strictly its own section lease.
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final TickRecorder rec00 = new TickRecorder();
            final TickRecorder rec10 = new TickRecorder();
            final TickRecorder rec11 = new TickRecorder();
            final RegionChunkTickPlan plan = this.beginChunkPlan(level);
            plan.accept(chunk(sec00, rec00, () -> verifyStrictSectionOwnership(level, pos00, List.of(pos10, pos11), failure)));
            plan.accept(chunk(sec10, rec10, () -> verifyStrictSectionOwnership(level, pos10, List.of(pos00, pos11), failure)));
            plan.accept(chunk(sec11, rec11, () -> verifyStrictSectionOwnership(level, pos11, List.of(pos00, pos10), failure)));
            plan.executeAndAwait(PARKING_BARRIER);

            assertNull(failure.get(), () -> "cross-section ownership leak: " + failure.get());
            assertEquals(RANDOM_TICK_SPEED, rec00.ticks.size());
            assertEquals(RANDOM_TICK_SPEED, rec10.ticks.size());
            assertEquals(RANDOM_TICK_SPEED, rec11.ticks.size());

            // RegionLayout.isInRegion (used by scheduled-tick / BE / game-event admission) must still reject
            // adjacent sections even when they belong to the same dynamic island.
            final RegionCoordinate coord00 = this.engine.regionLayout().regionAt(level, pos00);
            assertTrue(this.engine.regionLayout().isInRegion(level, pos00, coord00));
            assertFalse(this.engine.regionLayout().isInRegion(level, pos10, coord00));
            assertFalse(this.engine.regionLayout().isInRegion(level, pos11, coord00));

            // FixedEntitySectionPartitions per-region query must NOT consult adjacent section buckets
            // even when merged in DynamicRegionTopology.
            final FixedEntitySectionPartitions<Entity> partitions = new FixedEntitySectionPartitions<>(this.engine.regionLayout());
            final Entity e00 = mock(Entity.class);
            final Entity e10 = mock(Entity.class);
            addEntityToSection(partitions, SectionPos.asLong(sec00.x, 4, sec00.z), e00);
            addEntityToSection(partitions, SectionPos.asLong(sec10.x, 4, sec10.z), e10);
            final List<String> bucketsVisited = new ArrayList<>();
            partitions.setBucketAccessObserverForTesting((rx, rz) -> bucketsVisited.add(rx + "," + rz));
            final List<Entity> found = new ArrayList<>();
            partitions.forEachAccessibleNonEmptySectionInRegion(
                    0, 0, new AABB(0, 0, 0, 12 * 16, 128, 32),
                    section -> {
                        section.getEntities().forEach(found::add);
                        return AbortableIterationConsumer.Continuation.CONTINUE;
                    });
            assertEquals(List.of(e00), found);
            assertEquals(List.of("0,0"), bucketsVisited,
                    "per-region entity query must not cross into adjacent section bucket (1,0)");
        } finally {
            this.engine.endLevelTick(level);
        }
        assertEquals(0, this.engine.diagnostics().runningRegionCount());
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
    }

    @Test
    void repeatedMergeSplitChurnLeaksZeroLeasesReservationsOrRetainedReferences() {
        this.installEngine(2, RegionLayout.dynamicCluster(3, 1L));
        final ServerLevel level = mockLevel();
        final ChunkPos leftChunk = new ChunkPos(1, 1);   // section (0,0)
        final ChunkPos bridgeChunk = new ChunkPos(8, 1); // section (1,0)
        final ChunkPos rightChunk = new ChunkPos(17, 1); // section (2,0)
        final BlockPos leftPos = new BlockPos(leftChunk.getMinBlockX(), 64, leftChunk.getMinBlockZ());
        final BlockPos rightPos = new BlockPos(rightChunk.getMinBlockX(), 64, rightChunk.getMinBlockZ());

        final int cycles = 50;
        final AtomicInteger scheduledRuns = new AtomicInteger();

        for (int i = 0; i < cycles; ++i) {
            // 1. Two separate islands
            this.engine.addRegionSection(level, leftChunk.x, leftChunk.z);
            this.engine.addRegionSection(level, rightChunk.x, rightChunk.z);
            this.engine.beginLevelTick(level);
            try {
                final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
                assertNotNull(topology);
                assertEquals(2, topology.regionCount());
                this.engine.regionScheduler().execute(level, leftPos, scheduledRuns::incrementAndGet);
                this.engine.regionScheduler().execute(level, rightPos, scheduledRuns::incrementAndGet);
                this.engine.drainLevelRegionTasks(level);
            } finally {
                this.engine.endLevelTick(level);
            }

            // 2. Merge via bridge
            this.engine.addRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level);
            try {
                final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
                assertNotNull(topology);
                assertEquals(1, topology.regionCount());
                final TickRecorder leftRec = new TickRecorder();
                final TickRecorder rightRec = new TickRecorder();
                final RegionChunkTickPlan plan = this.beginChunkPlan(level);
                plan.accept(chunk(leftChunk, leftRec, null));
                plan.accept(chunk(rightChunk, rightRec, null));
                plan.executeAndAwait(PARKING_BARRIER);
                assertEquals(RANDOM_TICK_SPEED, leftRec.ticks.size());
                assertEquals(RANDOM_TICK_SPEED, rightRec.ticks.size());
            } finally {
                this.engine.endLevelTick(level);
            }

            // 3. Unload bridge -> wait 1-tick split delay -> split
            this.engine.removeRegionSection(level, bridgeChunk.x, bridgeChunk.z);
            this.engine.beginLevelTick(level); // stamp empty
            this.engine.endLevelTick(level);
            this.engine.beginLevelTick(level); // expire & split
            try {
                final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
                assertNotNull(topology);
                assertEquals(2, topology.regionCount());
            } finally {
                this.engine.endLevelTick(level);
            }

            // 4. Unload remaining chunks -> wait 1-tick split delay -> retire all
            this.engine.removeRegionSection(level, leftChunk.x, leftChunk.z);
            this.engine.removeRegionSection(level, rightChunk.x, rightChunk.z);
            this.engine.beginLevelTick(level); // stamp empty
            this.engine.endLevelTick(level);
            this.engine.beginLevelTick(level); // expire & retire
            this.engine.endLevelTick(level);
        }

        final DynamicRegionTopology topology = this.engine.dynamicTopologyOrNull(level);
        assertNotNull(topology);
        assertEquals(cycles * 2, scheduledRuns.get());
        assertEquals((long) cycles, topology.mergeCount());
        assertEquals((long) cycles, topology.splitCount());
        assertEquals(0, topology.regionCount(), "all islands must be retired after full unload");
        assertEquals(0, topology.activeSectionCount(), "zero retained section mappings after full unload");
        assertEquals(0, topology.loadedChunkCount(), "zero retained chunk keys after full unload");
        assertEquals(0, topology.pendingEmptySectionCount(), "zero pending empty timers after full unload");
        assertEquals(0, this.engine.diagnostics().runningRegionCount(), "zero leaked leases");
        assertEquals(0, this.engine.diagnostics().regionCount(), "zero retained RegionState instances");
        assertEquals(0, this.engine.diagnostics().ownershipViolationCount());
        assertEquals(0, this.engine.diagnostics().failedQueuedTaskCount());

        this.engine.clearLevelRegionTasks(level);
        assertNull(this.engine.dynamicTopologyOrNull(level));
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

    private RegionChunkTickPlan beginChunkPlan(ServerLevel level) {
        final RegionChunkTickPlan plan = this.engine.beginChunkTickPlan(level, RANDOM_TICK_SPEED, PARKING_BARRIER);
        assertNotNull(plan, "chunk tick worker execution must be enabled");
        return plan;
    }

    private void assertChunkSectionExclusivity(
            ServerLevel level,
            BlockPos ownPos,
            BlockPos foreignPos,
            Map<Long, AtomicInteger> activePerSection,
            AtomicInteger maxOverlap) {
        final int sx = this.engine.regionLayout().regionX(ownPos.getX() >> 4);
        final int sz = this.engine.regionLayout().regionZ(ownPos.getZ() >> 4);
        final long key = DynamicRegionTopology.sectionKey(sx, sz);
        recordExclusiveEntry(activePerSection, key, maxOverlap);
        try {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, ownPos),
                    "worker must own its own chunk section");
            assertFalse(this.engine.isOwnedByCurrentRegion(level, foreignPos),
                    "worker must not own a foreign chunk section");
        } finally {
            recordExclusiveExit(activePerSection, key);
        }
    }

    private void verifyStrictSectionOwnership(
            ServerLevel level,
            BlockPos ownPos,
            List<BlockPos> foreignPositions,
            AtomicReference<Throwable> failure) {
        try {
            assertTrue(this.engine.isOwnedByCurrentRegion(level, ownPos),
                    "worker must own its leased section " + ownPos);
            for (final BlockPos foreign : foreignPositions) {
                assertFalse(this.engine.isOwnedByCurrentRegion(level, foreign),
                        "topology adjacency must not grant ownership of " + foreign);
            }
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

    private static void recordExclusiveEntry(Map<Long, AtomicInteger> activePerSection, long secKey, AtomicInteger maxOverlap) {
        final int concurrent = activePerSection.computeIfAbsent(secKey, ignored -> new AtomicInteger()).incrementAndGet();
        maxOverlap.accumulateAndGet(concurrent, Math::max);
    }

    private static void recordExclusiveExit(Map<Long, AtomicInteger> activePerSection, long secKey) {
        final AtomicInteger counter = activePerSection.get(secKey);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    private static void addEntityToSection(FixedEntitySectionPartitions<Entity> partitions, long sectionId, Entity entity) {
        final EntitySection<Entity> section = partitions.getOrCreate(sectionId, id -> new EntitySection<>(Entity.class, Visibility.TICKING));
        section.add(entity);
    }

    private static ServerLevel mockLevel() {
        final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
        doReturn(InactiveProfiler.INSTANCE).when(level).getProfiler();
        doReturn("audit-level").when(level).toString();
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
            PARKING_BARRIER.await(invocation.getArgument(0));
            return null;
        }).when(chunkSource).managedBlockOnMainThread(any());
        doReturn(chunkSource).when(level).getChunkSource();
        setLevelField(level, "random", new RegionAwareRandomSource(RandomSource.create(4321L)));
        return level;
    }

    private static LevelChunk chunk(ChunkPos pos, TickRecorder recorder, Runnable onRandomTick) {
        final FluidState fluidState = mock(FluidState.class);
        when(fluidState.isRandomlyTicking()).thenReturn(false);
        final BlockState blockState = mock(BlockState.class);
        when(blockState.isRandomlyTicking()).thenReturn(true);
        when(blockState.getFluidState()).thenReturn(fluidState);
        doAnswer(invocation -> {
            if (onRandomTick != null) {
                onRandomTick.run();
            }
            recorder.ticks.add(invocation.getArgument(1, BlockPos.class).immutable());
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

    private static void awaitOrFail(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    private static final class TickRecorder {
        private final List<BlockPos> ticks = new CopyOnWriteArrayList<>();
    }
}
