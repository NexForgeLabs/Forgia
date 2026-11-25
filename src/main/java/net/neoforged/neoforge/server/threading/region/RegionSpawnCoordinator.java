/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates the natural spawning slice ({@link RegionTickSlice#SPAWN}) across synchronous and
 * asynchronous region worker execution paths, including ordered spawn finalization.
 * <p>
 * Extracted from {@link RegionThreadingEngine} to isolate spawn batch orchestration, region leasing,
 * worker body execution, and deferred side-effect commits behind a narrow {@link Host} contract.
 */
@ApiStatus.Internal
final class RegionSpawnCoordinator {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGION_LEASE_ATTEMPTS = 8;

    interface Host {
        boolean isShutdown();

        boolean isLevelClosed(ServerLevel level);

        boolean isIndependentRegionTickEnabled();

        @Nullable
        RegionIndependentTickScheduler independentTickScheduler();

        RegionLayout layout();

        RegionWorkerPool workerPool();

        int deferredBlockUpdateBudget();

        @Nullable
        RegionState getOrCreateRegion(RegionCoordinate coordinate);

        boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region);

        TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level);

        void retireTickBatch(RegionTickBatch batch);

        boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier);

        void addLeasedGroup(RegionState region, Runnable registration);

        void runRegionBatch(ServerLevel level, Runnable action);

        void deliverRegionMessages(ServerLevel level, RegionState region, Runnable after);

        void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action);

        void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations);

        void setCurrentSpawnRandom(@Nullable RandomSource random);

        void setCurrentSpawnNeighborhood(@Nullable RegionSpawnNeighborhood neighborhood);

        void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable);

        void removeIfEmpty(RegionState region);

        void countSkippedSpawnChunks(int count);

        void countUnleasedSpawnChunk();

        void countRefusedSpawnBlockUpdate();

        void countSpawnChunksOnWorkers(int count);

        void countTruncatedSpawnChunks(int count);

        boolean markSpawnWorkersNoticeLogged();

        void applyDeferredSpawnRemovals(RegionSpawnBatch batch);

        void applyDeferredSpawnAdditions(RegionSpawnBatch batch);

        void applyDeferredBlockUpdates(RegionTickBatch batch);

        void applyRegionTickMutations(Queue<Runnable> mutations);

        void recordOrderedSpawnTransaction(long serviceNanos);
    }

    private final Host host;

    RegionSpawnCoordinator(Host host) {
        this.host = host;
    }

    void spawnChunks(ServerLevel level, List<RegionSpawnChunk> chunks, RegionSpawnPlan plan, RegionTickBarrier barrier) {
        RegionSpawnAttribution.endPlanSegment();
        if (chunks.isEmpty()) {
            return;
        }

        if (this.host.isIndependentRegionTickEnabled()
                && RegionThreadingConfig.asyncRegionSpawningEnabled()
                && !RegionSpawnAttribution.isEnabled()) {
            this.executeAsyncSpawnBatch(level, plan, chunks);
            return;
        }

        final RegionSpawnBatch batch = new RegionSpawnBatch(level, plan.spawnFriendlies(), plan.spawnEnemies(),
                plan.spawnPersistent(), this.host.deferredBlockUpdateBudget());
        if (plan.orderedFinalization()) {
            batch.enableOrderedSpawnAdmissions(this.host.workerPool().threadCount(), () -> {
                if (this.host.isShutdown() || this.host.isLevelClosed(level)) {
                    throw new IllegalStateException("Ordered spawn level retired");
                }
                this.host.applyDeferredSpawnRemovals(batch);
                this.host.applyDeferredSpawnAdditions(batch);
                this.host.applyDeferredBlockUpdates(batch);
                this.host.applyRegionTickMutations(batch.regionTickMutations());
            });
        }
        final TickBatchPublication publication = this.host.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            this.host.countSkippedSpawnChunks(chunks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            for (final RegionSpawnChunk chunk : chunks) {
                plan.spawnInline(chunk.chunk());
            }
            return;
        }
        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionSpawnAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseSpawnGroups(batch, plan, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.host.runRegionBatch(level, () -> batch.submitAndAwait(this.host.workerPool(), barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = runCompletion(failure, () -> this.host.applyDeferredSpawnRemovals(batch));
                failure = runCompletion(failure, () -> this.host.applyDeferredSpawnAdditions(batch));
                failure = runCompletion(failure, () -> this.host.applyDeferredBlockUpdates(batch));
                failure = runCompletion(failure, () -> this.spawnFallbackChunks(batch, plan));
                failure = runCompletion(failure, () -> this.host.applyRegionTickMutations(batch.regionTickMutations()));
                batch.finish();
                this.host.retireTickBatch(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionSpawnAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    RegionThreadingHooks.SpawnFinalization deferAsyncOrderedSpawnFinalization(
            ServerLevel level, Mob mob, @Nullable SpawnGroupData groupData,
            ChunkAccess chunk, NaturalSpawner.AfterSpawnCallback afterSpawn, int total, int groupCount) {
        final var batch = RegionTickBatch.current();
        if (!(batch instanceof RegionSpawnBatch spawnBatch) || spawnBatch.level() != level
                || !spawnBatch.isAsyncOrderedFinalization()) {
            throw new IllegalStateException("No matching async ordered spawn group");
        }
        final Runnable commitAction = ParallelAdapterRuntime.PreparedSpawn.captureAsyncCommitContext(
                level, () -> {
                    if (this.host.isShutdown() || this.host.isLevelClosed(level)) {
                        return;
                    }
                    final long start = System.nanoTime();
                    try {
                        if (!mob.isRemoved()) {
                            EventHooks.finalizeMobSpawn(
                                    mob, level, level.getCurrentDifficultyAt(mob.blockPosition()),
                                    MobSpawnType.NATURAL, groupData);
                            if (!mob.isRemoved() && !mob.isSpawnCancelled()) {
                                level.addFreshEntityWithPassengers(mob);
                            }
                            afterSpawn.run(mob, chunk);
                        }
                    } finally {
                        this.host.recordOrderedSpawnTransaction(System.nanoTime() - start);
                    }
                });
        spawnBatch.enqueueDeferredOrderedFinalization(commitAction);
        final boolean stop = total >= EventHooks.getMaxSpawnClusterSize(mob);
        return new RegionThreadingHooks.SpawnFinalization(
                groupData, stop, !stop && mob.isMaxGroupSizeReached(groupCount));
    }

    private void leaseSpawnGroups(RegionSpawnBatch batch, RegionSpawnPlan plan, List<RegionSpawnChunk> pending, RegionTickBarrier barrier) {
        List<RegionSpawnChunk> remaining = pending;
        int batchedChunks = 0;
        final RegionIndependentTickScheduler scheduler = this.host.independentTickScheduler();
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionSpawnChunk> grouping = new RegionTickGrouping<>();
            final List<RegionSpawnChunk> retry = new ArrayList<>();
            for (final RegionSpawnChunk chunk : remaining) {
                final RegionState region = this.host.getOrCreateRegion(chunk.region(this.host.layout(), batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else if (this.host.shouldSkipOverloadedRegion(batch.level(), region)
                        || (RegionThreadingConfig.independentRegionTickClockEnabled()
                                && scheduler != null
                                && scheduler.shouldStaggerSpawn(region.coordinate(), batch.level().getGameTime()))) {
                    this.host.countSkippedSpawnChunks(1);
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionSpawnChunk> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            final LevelSpawnCapCounter sharedCapCounter = plan.capCounter();
            final boolean tryLeaseOnly = this.host.isIndependentRegionTickEnabled();
            for (final RegionState region : grouping.regions()) {
                final List<RegionSpawnChunk> regionChunks = grouping.items(region);
                if (batch.leases(region)) {
                    batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                    continue;
                }
                if (tryLeaseOnly) {
                    if (region.isDead() || !region.tryMarkRunning()) {
                        this.host.countSkippedSpawnChunks(regionChunks.size());
                        continue;
                    }
                } else if (!this.host.acquireRegionLease(region, barrier)) {
                    next.addAll(regionChunks);
                    continue;
                }
                leasedAny = true;
                batchedChunks += regionChunks.size();
                this.host.addLeasedGroup(region, () -> batch.addGroup(region, regionChunks,
                        () -> plan.buildRegionStateAsync(region, regionChunks, sharedCapCounter),
                        this::spawnGroup));
            }
            remaining = next;
            if (!leasedAny) {
                break;
            }
        }

        for (final RegionSpawnChunk chunk : remaining) {
            batch.addFallback(chunk);
            this.host.countUnleasedSpawnChunk();
        }
        if (batchedChunks > 0 && this.host.markSpawnWorkersNoticeLogged()) {
            LOGGER.info("Region natural spawning is now executing on region workers: {} chunks", batchedChunks);
        }
    }

    private void executeAsyncSpawnBatch(ServerLevel level, RegionSpawnPlan plan, List<RegionSpawnChunk> chunks) {
        if (this.host.isShutdown() || this.host.isLevelClosed(level)) {
            return;
        }
        final RegionIndependentTickScheduler scheduler = this.host.independentTickScheduler();
        if (scheduler == null) {
            return;
        }
        final RegionTickGrouping<RegionState, RegionSpawnChunk> grouping = new RegionTickGrouping<>();
        final long gameTime = level.getGameTime();
        for (final RegionSpawnChunk chunk : chunks) {
            final RegionState region = this.host.getOrCreateRegion(chunk.region(this.host.layout(), level));
            if (region == null || region.isDead()) {
                continue;
            }
            if (this.host.shouldSkipOverloadedRegion(level, region)
                    || scheduler.shouldStaggerSpawn(region.coordinate(), gameTime)) {
                this.host.countSkippedSpawnChunks(1);
                continue;
            }
            grouping.add(region, chunk);
        }
        if (grouping.isEmpty()) {
            return;
        }
        final int perRegionBudget = Math.max(64, this.host.deferredBlockUpdateBudget() / Math.max(1, grouping.regions().size()));
        final LevelSpawnCapCounter sharedCapCounter = plan.capCounter();
        final boolean orderedFinalization = plan.orderedFinalization();
        final int regionCount = grouping.regions().size();
        final List<RegionIndependentTickScheduler.AsyncBatchItem> spawnItems = new ArrayList<>(regionCount);
        final List<RegionSpawnBatch> spawnBatches = new ArrayList<>(regionCount);
        final List<Integer> spawnChunkCounts = new ArrayList<>(regionCount);
        for (final RegionState region : grouping.regions()) {
            final List<RegionSpawnChunk> regionChunks = grouping.items(region);
            final RegionSpawnBatch regionBatch = new RegionSpawnBatch(level, plan.spawnFriendlies(), plan.spawnEnemies(),
                    plan.spawnPersistent(), perRegionBudget);
            if (orderedFinalization) {
                regionBatch.enableAsyncOrderedFinalization();
            }
            regionBatch.setAsyncMainThreadWorkSink(task -> scheduler.enqueueCommit(level, task));
            final RegionSpawnBatch.Group group = regionBatch.createAsyncGroup(
                    region, regionChunks, () -> plan.buildRegionStateAsync(region, regionChunks, sharedCapCounter));
            final Runnable wrappedWork = regionBatch.wrapAsyncGroupWork(this.spawnGroup(group, false));
            final Runnable commit = () -> {
                try {
                    if (!this.host.isShutdown() && !this.host.isLevelClosed(level)) {
                        regionBatch.drainDeferredOrderedFinalizations();
                        this.host.applyDeferredSpawnRemovals(regionBatch);
                        this.host.applyDeferredSpawnAdditions(regionBatch);
                        this.host.applyDeferredBlockUpdates(regionBatch);
                        this.host.applyRegionTickMutations(regionBatch.regionTickMutations());
                    }
                } finally {
                    regionBatch.finish();
                }
            };
            spawnItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                    region, "async spawn", wrappedWork, commit, false, 0L, gameTime));
            spawnBatches.add(regionBatch);
            spawnChunkCounts.add(regionChunks.size());
        }
        int dispatchedChunks = 0;
        final var spawnOutcomes = scheduler.submitBatchOptimized(spawnItems);
        for (int i = 0; i < spawnOutcomes.length; ++i) {
            if (spawnOutcomes[i] == RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED) {
                dispatchedChunks += spawnChunkCounts.get(i);
            } else {
                this.host.countSkippedSpawnChunks(spawnChunkCounts.get(i));
                spawnBatches.get(i).finish();
            }
        }
        if (dispatchedChunks > 0 && this.host.markSpawnWorkersNoticeLogged()) {
            LOGGER.info("Region natural spawning is now executing asynchronously on region workers: {} chunks", dispatchedChunks);
        }
    }

    private Runnable spawnGroup(RegionSpawnBatch.Group group) {
        return this.spawnGroup(group, true);
    }

    private Runnable spawnGroup(RegionSpawnBatch.Group group, boolean manageRegionLifecycle) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.batch().level();
        final List<RegionSpawnChunk> chunks = group.chunks();
        final boolean spawnFriendlies = group.batch().spawnFriendlies();
        final boolean spawnEnemies = group.batch().spawnEnemies();
        final boolean spawnPersistent = group.batch().spawnPersistent();
        return () -> {
            final long start = System.nanoTime();
            final int[] spawned = { 0 };
            final int[] truncated = { 0 };
            RegionSpawnAdmissionBarrier.Worker admissionWorker = null;
            try {
                admissionWorker = group.batch().enterOrderedSpawnGroup();
                this.host.deliverRegionMessages(level, region, () -> {});
                if (manageRegionLifecycle) {
                    region.beginOwnership();
                }
                this.host.setCurrentRegionTickMutations(group.batch().regionTickMutations());
                try {
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    try {
                        final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                        try {
                            this.host.runInRegionContext(level, coordinate, region, () -> {
                                group.ensureBuilt();
                                for (final RegionSpawnChunk chunk : chunks) {
                                    group.batch().spawnAdmissionCheckpoint();
                                    if (!region.isOwnedByCurrentThread() || this.host.isLevelClosed(level)
                                            || group.batch().isAdditionSeamClosed()) {
                                        group.batch().addFallback(chunk);
                                        continue;
                                    }
                                    if (!group.batch().tryReserveBlockUpdates()) {
                                        group.batch().addFallback(chunk);
                                        this.host.countRefusedSpawnBlockUpdate();
                                        continue;
                                    }
                                    final RandomSource random = chunk.random(level, coordinate);
                                    this.host.setCurrentSpawnRandom(random);
                                    this.host.setCurrentSpawnNeighborhood(group.neighborhood());
                                    try {
                                        final Runnable body = () -> NaturalSpawner.spawnForChunk(level, chunk.chunk(), group.state(),
                                                spawnFriendlies, spawnEnemies, spawnPersistent);
                                        if (chunk.prepared() == null) {
                                            ParallelAdapterRuntime.runSerial(body);
                                        } else if (!chunk.prepared().run(level, body, !manageRegionLifecycle)) {
                                            this.host.countSkippedSpawnChunks(1);
                                            continue;
                                        }
                                        ++spawned[0];
                                    } catch (Throwable throwable) {
                                        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)
                                                && RegionWorkerBoundaryException.findBoundaryViolation(throwable) == null) {
                                            this.host.recordWorkerTickFailure(group.batch(), coordinate, throwable);
                                            return;
                                        }
                                        ++truncated[0];
                                    } finally {
                                        this.host.setCurrentSpawnNeighborhood(null);
                                        this.host.setCurrentSpawnRandom(null);
                                    }
                                }
                            });
                        } finally {
                            RegionNeighborUpdateScope.exit(previousNeighbors);
                        }
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.host.setCurrentRegionTickMutations(null);
                }
            } finally {
                try {
                    group.batch().exitOrderedSpawnGroup(admissionWorker);
                } finally {
                    if (manageRegionLifecycle) {
                        region.endOwnership();
                        region.clearRunning();
                        final long duration = System.nanoTime() - start;
                        region.tickMetrics().recordTick(duration);
                    }
                    RegionTickWatchdog.warnIfLongTick("spawning batch " + coordinate, System.nanoTime() - start);
                    this.host.countSpawnChunksOnWorkers(spawned[0]);
                    this.host.countTruncatedSpawnChunks(truncated[0]);
                    if (manageRegionLifecycle) {
                        this.host.removeIfEmpty(region);
                    }
                }
            }
        };
    }

    private void spawnFallbackChunks(RegionSpawnBatch batch, RegionSpawnPlan plan) {
        final ServerLevel level = batch.level();
        final Collection<RegionSpawnChunk> fallback = batch.fallbackChunks();
        if (fallback.isEmpty()) {
            return;
        }
        if (this.host.isLevelClosed(level)) {
            this.host.countSkippedSpawnChunks(fallback.size());
            fallback.clear();
            return;
        }
        for (final RegionSpawnChunk chunk : fallback) {
            plan.spawnInline(chunk.chunk());
        }
        fallback.clear();
    }

    private static @Nullable Throwable runCompletion(@Nullable Throwable failure, Runnable step) {
        try {
            step.run();
            return failure;
        } catch (Throwable throwable) {
            if (failure == null) {
                return throwable;
            }
            failure.addSuppressed(throwable);
            return failure;
        }
    }

    private static void throwUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region spawn batch failed", throwable);
    }
}
