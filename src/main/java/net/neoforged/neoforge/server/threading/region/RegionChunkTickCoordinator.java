/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates the chunk random-tick slice ({@link RegionTickSlice#CHUNK_TICK}) across both
 * synchronous barrier batches and asynchronous {@link RegionIndependentTickScheduler} waves.
 * <p>
 * Owns region grouping, lease acquisition, async batch packing, and the per-region worker loop,
 * delegating level/engine hooks through {@link Host}.
 */
final class RegionChunkTickCoordinator {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGION_LEASE_ATTEMPTS = 8;

    interface Host {
        boolean isShutdown();

        boolean isLevelClosed(ServerLevel level);

        boolean isIndependentRegionTickEnabled();

        @Nullable
        RegionIndependentTickScheduler independentTickScheduler();

        RegionLayout layout();

        int blockUpdateEntryBudget();

        @Nullable
        RegionState getOrCreateRegion(RegionCoordinate coordinate);

        boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region);

        TickBatchPublication publish(RegionChunkTickBatch batch);

        void countSkippedChunks(int count);

        void countUnleasedChunkTick();

        void countChunkTicksOnWorkers(int count);

        void countTruncatedChunkTick();

        boolean markChunkTickWorkersNoticeLogged();

        void tickOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed);

        boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier);

        void addLeasedGroup(RegionState region, Runnable registration);

        Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body);

        void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action);

        void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations);

        void setCurrentChunkTickRandom(@Nullable RandomSource random);

        void setCurrentChunkTickPositionRandom(@Nullable RandomSource random);

        void setCurrentChunkTickLightning(@Nullable Queue<BlockPos> strikes);

        void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable);

        void removeIfEmpty(RegionState region);

        void applyAsyncCommitEffects(RegionChunkTickBatch batch);

        /** Returns only after all submitted work has physically joined, including on failure. */
        void submitAndAwait(RegionChunkTickBatch batch, RegionTickBarrier barrier);

        /** Performs the original ordered effects, finishes the original batch and retires its marker. */
        @Nullable
        Throwable complete(RegionChunkTickBatch batch, @Nullable Throwable failure);
    }

    private final Host host;

    RegionChunkTickCoordinator(Host host) {
        this.host = host;
    }

    void executeAndAwait(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed, RegionTickBarrier barrier) {
        RegionChunkTickAttribution.endPlanSegment();
        if (chunks.isEmpty()) {
            return;
        }
        if (!RegionChunkTickAttribution.isEnabled() && this.host.isIndependentRegionTickEnabled()) {
            this.executeAsyncChunkTickBatch(level, chunks, randomTickSpeed);
            return;
        }

        final RegionChunkTickBatch batch = new RegionChunkTickBatch(level, randomTickSpeed, this.host.blockUpdateEntryBudget());
        final TickBatchPublication publication = this.host.publish(batch);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // A retired level must not tick even the chunks collected before retirement.
            this.host.countSkippedChunks(chunks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // Shutdown refuses publication, but still executes collected work through the original inline path.
            for (final RegionChunkTickEntry chunk : chunks) {
                this.host.tickOnServerThread(level, chunk, randomTickSpeed);
            }
            return;
        }

        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionChunkTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseChunkTickGroups(batch, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.host.submitAndAwait(batch, barrier);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = this.host.complete(batch, failure);
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Region tick batch failed", failure);
        }
        batch.throwIfFailed();
    }

    private void leaseChunkTickGroups(RegionChunkTickBatch batch, List<RegionChunkTickEntry> pending, RegionTickBarrier barrier) {
        List<RegionChunkTickEntry> remaining = pending;
        int batchedChunks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionChunkTickEntry> grouping = new RegionTickGrouping<>();
            final List<RegionChunkTickEntry> retry = new ArrayList<>();
            for (final RegionChunkTickEntry chunk : remaining) {
                final RegionState region = this.host.getOrCreateRegion(chunk.region(this.host.layout(), batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else if (this.host.shouldSkipOverloadedRegion(batch.level(), region)) {
                    this.host.countSkippedChunks(1);
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionChunkTickEntry> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            final boolean tryLeaseOnly = this.host.isIndependentRegionTickEnabled();
            for (final RegionState region : grouping.regions()) {
                final List<RegionChunkTickEntry> regionChunks = grouping.items(region);
                if (batch.leases(region)) {
                    batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                    continue;
                }
                if (tryLeaseOnly) {
                    if (region.isDead() || !region.tryMarkRunning()) {
                        this.host.countSkippedChunks(regionChunks.size());
                        continue;
                    }
                } else if (!this.host.acquireRegionLease(region, barrier)) {
                    next.addAll(regionChunks);
                    continue;
                }
                leasedAny = true;
                batchedChunks += regionChunks.size();
                this.host.addLeasedGroup(region, () -> batch.addGroup(region, regionChunks,
                        group -> this.host.withRegionMailboxDelivery(batch.level(), region, this.chunkTickGroup(group, true))));
            }
            remaining = next;
            if (!leasedAny) {
                break;
            }
        }

        for (final RegionChunkTickEntry chunk : remaining) {
            batch.addFallback(chunk);
            this.host.countUnleasedChunkTick();
        }
        if (batchedChunks > 0 && this.host.markChunkTickWorkersNoticeLogged()) {
            LOGGER.info("Region chunk ticks are now executing on region workers: {} chunks", batchedChunks);
        }
    }

    private void executeAsyncChunkTickBatch(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed) {
        if (this.host.isShutdown() || this.host.isLevelClosed(level)) {
            this.host.countSkippedChunks(chunks.size());
            return;
        }
        final RegionIndependentTickScheduler scheduler = this.host.independentTickScheduler();
        if (scheduler == null) {
            this.host.countSkippedChunks(chunks.size());
            return;
        }
        final RegionTickGrouping<RegionState, RegionChunkTickEntry> grouping = new RegionTickGrouping<>();
        final long gameTime = level.getGameTime();
        for (final RegionChunkTickEntry chunk : chunks) {
            final RegionState region = this.host.getOrCreateRegion(chunk.region(this.host.layout(), level));
            if (region == null || region.isDead()) {
                this.host.countSkippedChunks(1);
                continue;
            }
            if (this.host.shouldSkipOverloadedRegion(level, region)) {
                this.host.countSkippedChunks(1);
                continue;
            }
            grouping.add(region, chunk);
        }
        if (grouping.isEmpty()) {
            return;
        }
        final int perRegionBudget = Math.max(64, this.host.blockUpdateEntryBudget() / Math.max(1, grouping.regions().size()));
        final int regionCount = grouping.regions().size();
        final List<RegionIndependentTickScheduler.AsyncBatchItem> chunkItems = new ArrayList<>(regionCount);
        final List<RegionChunkTickBatch> chunkBatches = new ArrayList<>(regionCount);
        final List<Integer> chunkCounts = new ArrayList<>(regionCount);
        for (final RegionState region : grouping.regions()) {
            final List<RegionChunkTickEntry> regionChunks = grouping.items(region);
            final RegionChunkTickBatch regionBatch = new RegionChunkTickBatch(level, randomTickSpeed, perRegionBudget);
            regionBatch.setAsyncMainThreadWorkSink(task -> scheduler.enqueueCommit(level, task));
            final RegionChunkTickBatch.Group group = regionBatch.createAsyncGroup(region, regionChunks);
            final Runnable wrappedWork = regionBatch.wrapAsyncGroupWork(
                    this.host.withRegionMailboxDelivery(level, region, this.chunkTickGroup(group, false)));
            final Runnable commit = () -> {
                try {
                    if (!this.host.isShutdown() && !this.host.isLevelClosed(level)) {
                        this.host.applyAsyncCommitEffects(regionBatch);
                    }
                } finally {
                    regionBatch.finish();
                }
            };
            chunkItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                    region, "async chunk", wrappedWork, commit, false, 0L, gameTime));
            chunkBatches.add(regionBatch);
            chunkCounts.add(regionChunks.size());
        }
        int dispatchedChunks = 0;
        final var chunkOutcomes = scheduler.submitBatchOptimized(chunkItems);
        for (int i = 0; i < chunkOutcomes.length; ++i) {
            if (chunkOutcomes[i] == RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED) {
                dispatchedChunks += chunkCounts.get(i);
            } else {
                this.host.countSkippedChunks(chunkCounts.get(i));
                chunkBatches.get(i).finish();
            }
        }
        if (dispatchedChunks > 0 && this.host.markChunkTickWorkersNoticeLogged()) {
            LOGGER.info("Region chunk ticks are now executing asynchronously on region workers: {} chunks", dispatchedChunks);
        }
    }

    private Runnable chunkTickGroup(RegionChunkTickBatch.Group group, boolean manageRegionLifecycle) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.batch().level();
        final List<RegionChunkTickEntry> chunks = group.chunks();
        final int randomTickSpeed = group.batch().randomTickSpeed();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
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
                                for (final RegionChunkTickEntry chunk : chunks) {
                                    if (!region.isOwnedByCurrentThread() || this.host.isLevelClosed(level)) {
                                        group.batch().addFallback(chunk);
                                        continue;
                                    }
                                    final RandomSource random = chunk.random(level);
                                    this.host.setCurrentChunkTickRandom(random);
                                    this.host.setCurrentChunkTickPositionRandom(random);
                                    this.host.setCurrentChunkTickLightning(group.batch().lightningStrikes());
                                    try {
                                        level.tickChunk(chunk.chunk(), randomTickSpeed);
                                        ++ticked[0];
                                    } catch (Throwable throwable) {
                                        if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)
                                                && RegionWorkerBoundaryException.findBoundaryViolation(throwable) == null) {
                                            this.host.recordWorkerTickFailure(group.batch(), coordinate, throwable);
                                            return;
                                        }
                                        this.host.countTruncatedChunkTick();
                                    } finally {
                                        this.host.setCurrentChunkTickLightning(null);
                                        this.host.setCurrentChunkTickPositionRandom(null);
                                        this.host.setCurrentChunkTickRandom(null);
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
                if (manageRegionLifecycle) {
                    region.endOwnership();
                    region.clearRunning();
                    final long duration = System.nanoTime() - start;
                    region.tickMetrics().recordTick(duration);
                    RegionTickWatchdog.warnIfLongTick("chunk tick batch " + coordinate, duration);
                }
                this.host.countChunkTicksOnWorkers(ticked[0]);
                if (manageRegionLifecycle) {
                    this.host.removeIfEmpty(region);
                }
            }
        };
    }
}
