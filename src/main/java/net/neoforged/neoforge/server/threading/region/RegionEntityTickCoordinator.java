/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.BlockEntityOperationRuntime;
import net.neoforged.neoforge.server.threading.EntityInteractionScheduler;
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.GlobalRegionScheduler;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionScheduler;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Access;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.AdapterDescriptor;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Resources;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates the entity tick slice ({@link RegionTickSlice#ENTITY_TICK}) across self-driven region ticks,
 * selective asynchronous entity waves, fused chunk-tick stages, per-entity batches, and server-thread fallbacks.
 * <p>
 * Extracted from {@link RegionThreadingEngine} to isolate the ~1,600-line entity tick execution subsystem.
 */
@ApiStatus.Internal
final class RegionEntityTickCoordinator {
    private final RegionThreadingEngine engine;

    RegionEntityTickCoordinator(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    void tickSelfDrivenEntities(ServerLevel level, RegionTickBarrier barrier) {
        if (this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)) {
            this.engine.independentTickScheduler.drainCompletedCommits(
                    level, RegionThreadingConfig.independentRegionCommitDrainBudgetNanos());
        }
        FusedRegionTickSession.PendingChunkWork pendingChunks = this.engine.fusedSession.takeStagedChunks(level);
        final List<RegionState> regions = new ArrayList<>();
        this.engine.regionizer.collectActiveEntityTickRegions(level, regions);
        if (regions.isEmpty()) {
            if (pendingChunks != null) {
                if (this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)) {
                    final Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion = new java.util.IdentityHashMap<>();
                    final List<RegionChunkTickEntry> nonFusable = new ArrayList<>();
                    final long gameTime = level.getGameTime();
                    for (final RegionChunkTickEntry entry : pendingChunks.chunks()) {
                        final RegionState region = this.engine.regionizer.getOrCreate(entry.region(this.engine.layout, level));
                        if (region == null || region.isDead() || this.engine.fusedSession.isNonFusable(region.coordinate(), gameTime)) {
                            nonFusable.add(entry);
                        } else if (this.engine.shouldSkipOverloadedRegion(level, region)) {
                            this.engine.skippedChunkTicks.incrementAndGet();
                        } else {
                            chunksByRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(entry);
                        }
                    }
                    if (!nonFusable.isEmpty()) {
                        this.engine.chunkTickCoordinator.executeAndAwait(level, nonFusable, pendingChunks.randomTickSpeed(), pendingChunks.barrier());
                    }
                    if (!chunksByRegion.isEmpty()) {
                        this.engine.unifiedTickCoordinator.stageEntityAndChunkWork(
                                level, List.of(), new int[0], chunksByRegion, pendingChunks);
                    }
                } else {
                    this.engine.fusedSession.recordEarlyFlush();
                    this.engine.chunkTickCoordinator.executeAndAwait(level, pendingChunks.chunks(), pendingChunks.randomTickSpeed(), pendingChunks.barrier());
                }
            }
            return;
        }

        if (pendingChunks != null) {
            long totalEntities = 0L;
            for (final RegionState region : regions) {
                totalEntities += region.tickingEntityCount();
            }
            final long neededBudget = (long) pendingChunks.chunks().size() * RegionTickSlice.CHUNK_TICK.reservationPerUnit()
                    + totalEntities * RegionTickSlice.ENTITY_TICK.reservationPerUnit();
            if (neededBudget > this.engine.deferredBlockUpdateBudget) {
                this.engine.fusedSession.recordBudgetFallback();
                this.engine.chunkTickCoordinator.executeAndAwait(level, pendingChunks.chunks(), pendingChunks.randomTickSpeed(), pendingChunks.barrier());
                pendingChunks = null;
            }
        }

        final boolean unifiedLoopActive = this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level);
        Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion = null;
        if (pendingChunks != null) {
            final long gameTime = level.getGameTime();
            chunksByRegion = new java.util.IdentityHashMap<>();
            final List<RegionChunkTickEntry> nonFusableChunks = new ArrayList<>();
            for (final RegionChunkTickEntry entry : pendingChunks.chunks()) {
                final RegionState region = this.engine.regionizer.getOrCreate(entry.region(this.engine.layout, level));
                if (region == null || region.isDead()) {
                    nonFusableChunks.add(entry);
                } else if (this.engine.shouldSkipOverloadedRegion(level, region)) {
                    this.engine.skippedChunkTicks.incrementAndGet();
                } else if (!unifiedLoopActive
                        && RegionThreadingConfig.independentRegionTickClockEnabled()
                        && this.engine.independentTickScheduler != null
                        && this.engine.independentTickScheduler.shouldThrottleEntityTick(region.coordinate(), gameTime)) {
                    nonFusableChunks.add(entry);
                } else if (this.engine.fusedSession.isNonFusable(region.coordinate(), gameTime)) {
                    nonFusableChunks.add(entry);
                } else {
                    chunksByRegion.computeIfAbsent(region, ignored -> new ArrayList<>()).add(entry);
                }
            }
            if (!nonFusableChunks.isEmpty()) {
                this.engine.chunkTickCoordinator.executeAndAwait(level, nonFusableChunks, pendingChunks.randomTickSpeed(), pendingChunks.barrier());
            }
            if (chunksByRegion.isEmpty()) {
                pendingChunks = null;
                chunksByRegion = null;
            }
        }

        final FusedRegionTickBatch fusedBatch = pendingChunks != null
                ? new FusedRegionTickBatch(level, pendingChunks.randomTickSpeed(), this.engine.deferredBlockUpdateBudget)
                : null;
        final RegionEntityTickBatch batch = fusedBatch != null
                ? fusedBatch
                : new RegionEntityTickBatch(level, this.engine.deferredBlockUpdateBudget);
        if (fusedBatch == null && this.tryDispatchPlayerOnlyLevel(level, regions, batch)) {
            return;
        }
        // The chunk-load answers are shared by every region of the pass and by every following pass of the
        // same loaded chunk generation, so the level's loaded chunks are asked once instead of once per
        // neighborhood.
        final RegionThreadingEngine.ChunkLoadAnswers chunkAnswers = this.chunkLoadAnswers(level);
        final Long2ByteMap chunkLoaded = chunkAnswers.answers;
        final Long2ByteMap neighborhoodLoaded = chunkAnswers.neighborhoodAnswers;
        final boolean hasMailbox = this.engine.mailboxes.containsKey(level);
        final List<RegionEntityTickActivation> activations = new ArrayList<>(regions.size());
        for (final RegionState region : regions) {
            if (this.engine.shouldSkipOverloadedRegion(level, region)
                    || (!unifiedLoopActive
                    && RegionThreadingConfig.independentRegionTickClockEnabled()
                    && this.engine.independentTickScheduler != null
                    && this.engine.independentTickScheduler.shouldThrottleEntityTick(region.coordinate(), level.getGameTime()))) {
                final int preservedPlayers = this.collectSkippedRegionPlayers(batch, region);
                this.engine.skippedEntityTicks.addAndGet(Math.max(0, region.tickingEntityCount() - preservedPlayers));
                continue;
            }
            if (!hasMailbox && this.collectServerThreadOnlyRegion(batch, region)) {
                continue;
            }
            final RegionEntityTickActivation activation = RegionEntityTickActivation.activate(level, region, this.engine.layout, chunkLoaded, neighborhoodLoaded);
            if (activation == null) {
                // The region's chunk rectangle cannot be decided on the server thread: a region without a
                // loaded chunk section, or a merged rectangle beyond the snapshot bound. Its entities are
                // ticked on the server thread instead of being dropped.
                this.engine.fallbackEntityTickRegions.incrementAndGet();
                this.addRegionFallback(batch, region);
                continue;
            }
            if (activation.eligibleChunkCount() == 0) {
                // Nothing in the region may be ticked this tick, but the vanilla body still runs the despawn
                // check of its entities, so the region is dispatched like any other.
                this.engine.entityTickRegionsWithoutEligibleChunk.incrementAndGet();
            }
            activations.add(activation);
        }
        RegionEntityTickAttribution.endPlanSegment();
        if (activations.isEmpty() && fusedBatch == null) {
            // Every region was handed to the server thread: no batch has to be published for this tick.
            this.tickSelfDrivenFallbacks(batch);
            return;
        }
        if (activations.isEmpty() && this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)) {
            this.tickSelfDrivenFallbacks(batch);
            this.engine.unifiedTickCoordinator.stageEntityAndChunkWork(
                    level, List.of(), new int[0], chunksByRegion, pendingChunks);
            return;
        }

        if (this.engine.isIndependentRegionTickEnabled()
                && !RegionEntityTickAttribution.isEnabled()
                && this.engine.entityTickFailureObserverForTesting == null
                && !this.engine.shutdown
                && !this.engine.regionizer.isLevelClosed(level)) {
            if (this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)
                    || RegionThreadingConfig.asyncRegionEntityTicksEnabled()
                    || RegionThreadingConfig.selectiveAsyncEntityTicksEnabled()) {
                this.executeAsyncSelfDrivenEntities(level, activations, chunksByRegion, pendingChunks, batch, true);
                return;
            }
        }

        final TickBatchPublication publication = this.engine.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after the activation. Its region state is retired and no batch may be
            // published for it: the activated entities must not be ticked or handed to the server-thread
            // fallback after their level unloaded.
            for (final RegionEntityTickActivation activation : activations) {
                this.engine.skippedEntityTicks.addAndGet(activation.region().tickingEntityCount());
            }
            if (chunksByRegion != null) {
                for (final List<RegionChunkTickEntry> c : chunksByRegion.values()) {
                    this.engine.skippedChunkTicks.addAndGet(c.size());
                }
            }
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            for (final RegionEntityTickActivation activation : activations) {
                this.addRegionFallback(batch, activation.region());
            }
            this.tickSelfDrivenFallbacks(batch);
            if (chunksByRegion != null && pendingChunks != null) {
                for (final List<RegionChunkTickEntry> c : chunksByRegion.values()) {
                    for (final RegionChunkTickEntry entry : c) {
                        this.engine.tickChunkOnServerThread(level, entry, pendingChunks.randomTickSpeed());
                    }
                }
            }
            return;
        }
        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionEntityTickAttribution.isEnabled();
        Throwable failure = null;
        int fusedRegionCount = 0;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                for (final RegionEntityTickActivation activation : activations) {
                    final RegionState region = activation.region();
                    final List<RegionChunkTickEntry> regionChunks = chunksByRegion != null ? chunksByRegion.remove(region) : null;
                    if (!this.engine.acquireRegionLease(region, barrier)) {
                        this.engine.fallbackEntityTickRegions.incrementAndGet();
                        this.addRegionFallback(batch, region);
                        if (fusedBatch != null && regionChunks != null) {
                            fusedBatch.addFallbackChunks(regionChunks);
                        }
                        continue;
                    }
                    this.engine.entityTickRegionsOnWorkers.incrementAndGet();
                    if (fusedBatch != null && regionChunks != null && !regionChunks.isEmpty()) {
                        ++fusedRegionCount;
                        final int rts = fusedBatch.randomTickSpeed();
                        this.engine.addLeasedGroup(region, () -> batch.addSelfDrivenGroup(activation,
                                group -> this.engine.withRegionMailboxDelivery(batch.level(), region, () -> {
                                    this.runFusedChunkStage(fusedBatch, region, group, regionChunks, rts, false);
                                    this.selfDrivenEntityTickGroup(group, activation).run();
                                })));
                    } else {
                        this.engine.addLeasedGroup(region, () -> batch.addSelfDrivenGroup(activation,
                                group -> this.engine.withRegionMailboxDelivery(batch.level(), region, this.selfDrivenEntityTickGroup(group, activation))));
                    }
                }
                if (fusedBatch != null && chunksByRegion != null && !chunksByRegion.isEmpty()) {
                    final int rts = fusedBatch.randomTickSpeed();
                    for (final var entry : chunksByRegion.entrySet()) {
                        final RegionState region = entry.getKey();
                        final List<RegionChunkTickEntry> regionChunks = entry.getValue();
                        if (!this.engine.acquireRegionLease(region, barrier)) {
                            fusedBatch.addFallbackChunks(regionChunks);
                            continue;
                        }
                        ++fusedRegionCount;
                        this.engine.addLeasedGroup(region, () -> fusedBatch.addChunkOnlyGroup(region,
                                group -> this.engine.withRegionMailboxDelivery(batch.level(), region,
                                        () -> this.runFusedChunkStage(fusedBatch, region, group, regionChunks, rts, true))));
                    }
                }
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            if (fusedBatch != null) {
                this.engine.activeFusedBatch = true;
            }
            try {
                this.engine.runRegionBatch(level, () -> batch.submitAndAwait(this.engine.workerPool, barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (fusedBatch != null) {
                    this.engine.activeFusedBatch = false;
                    this.engine.fusedSession.recordFusedBatch(fusedRegionCount);
                }
                if (attribution) {
                    RegionEntityTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = System.nanoTime();
            long fallbackDuration = 0L;
            try {
                // The portal transition of a worker-ticked entity runs first: it is the earliest level effect
                // of the tick it belongs to, and the destination search may read the POI manager of the
                // destination level, which only the server thread may touch.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredPortalTransitions(batch));
                final long sectionStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntitySectionUpdates(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordSectionUpdates(System.nanoTime() - sectionStart);
                    }
                }
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityRemovals(batch));
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityAdditions(batch));
                // The light rechecks and block updates of the blocks the workers wrote are the level-wide effects
                // of their tick, so they run before the server-thread fallback ticks of this batch observe the
                // written state.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockUpdates(batch));
                if (fusedBatch != null) {
                    final FusedRegionTickBatch completedFused = fusedBatch;
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.applyFusedChunkTickLightning(level, completedFused.lightningStrikes()));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> {
                        for (final RegionChunkTickEntry fallbackChunk : completedFused.drainFallbackChunks()) {
                            this.engine.tickChunkOnServerThread(level, fallbackChunk, completedFused.randomTickSpeed());
                        }
                    });
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyRegionTickMutations(completedFused.regionTickMutations()));
                }
                final long fallbackStart = System.nanoTime();
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.tickSelfDrivenFallbacks(batch));
                } finally {
                    fallbackDuration = System.nanoTime() - fallbackStart;
                    this.engine.mainThreadPhaseMetrics.record(RegionMainThreadPhaseMetrics.Phase.ENTITY_FALLBACKS, fallbackDuration);
                    if (attribution) {
                        RegionEntityTickAttribution.recordFallbacks(fallbackDuration);
                    }
                }
                final long deferredTasksStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, batch::runDeferredServerTasks);
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordDeferredTasks(System.nanoTime() - deferredTasksStart);
                    }
                }
                final long publicationStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityUpdates(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordDeferredPublication(System.nanoTime() - publicationStart);
                    }
                }
                batch.finish();
                this.engine.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                final long totalHandoff = System.nanoTime() - handoffStart;
                this.engine.mainThreadPhaseMetrics.record(RegionMainThreadPhaseMetrics.Phase.ENTITY_HANDOFF,
                        Math.max(0L, totalHandoff - fallbackDuration));
                if (attribution) {
                    RegionEntityTickAttribution.recordHandoff(totalHandoff);
                }
            }
        }

        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Executes the fused {@link RegionTickSlice#CHUNK_TICK} stage for one region inside a
     * {@link FusedRegionTickBatch}. When {@code releaseLeaseWhenDone} is {@code false}, the caller
     * immediately runs {@link #selfDrivenEntityTickGroup} under the same region lease and that stage
     * releases {@code region.clearRunning()}.
     */
    void runFusedChunkStage(
            FusedRegionTickBatch batch,
            RegionState region,
            RegionOwnershipLeaseGroup group,
            List<RegionChunkTickEntry> chunks,
            int randomTickSpeed,
            boolean releaseLeaseWhenDone) {
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = batch.level();
        final long start = System.nanoTime();
        final int[] ticked = { 0 };
        final boolean acquiredOwnership = !region.isOwnedByCurrentThread();
        try {
            if (acquiredOwnership) {
                region.beginOwnership();
            }
            this.engine.currentRegionTickMutations.set(batch.regionTickMutations());
            try {
                final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                try {
                    final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                            for (final RegionChunkTickEntry chunk : chunks) {
                                if (!region.isOwnedByCurrentThread() || this.engine.regionizer.isLevelClosed(level)) {
                                    batch.addFallbackChunk(chunk);
                                    continue;
                                }
                                final RandomSource random = chunk.random(level);
                                this.engine.currentChunkTickRandom.set(random);
                                this.engine.currentChunkTickPositionRandom.set(random);
                                this.engine.currentChunkTickLightning.set(batch.lightningStrikes());
                                try {
                                    level.tickChunk(chunk.chunk(), randomTickSpeed);
                                    ++ticked[0];
                                } catch (Throwable throwable) {
                                    if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                                        this.engine.recordWorkerTickFailure(batch, coordinate, null, throwable);
                                        return;
                                    }
                                    this.engine.truncatedChunkTicks.incrementAndGet();
                                } finally {
                                    this.engine.currentChunkTickLightning.remove();
                                    this.engine.currentChunkTickPositionRandom.remove();
                                    this.engine.currentChunkTickRandom.remove();
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
                this.engine.currentRegionTickMutations.remove();
            }
        } finally {
            if (acquiredOwnership) {
                region.endOwnership();
            }
            if (releaseLeaseWhenDone) {
                region.clearRunning();
            }
            final long duration = System.nanoTime() - start;
            region.tickMetrics().recordTick(duration);
            if (this.engine.layout.isDynamicCluster()) {
                region.dispatchCosts().set(RegionTickSlice.CHUNK_TICK.ordinal(), duration);
            }
            RegionTickWatchdog.warnIfLongTick("fused chunk tick " + coordinate, duration);
            this.engine.chunkTicksOnWorkers.addAndGet(ticked[0]);
            if (releaseLeaseWhenDone) {
                this.engine.regionizer.removeIfEmpty(region);
            }
        }
    }

    void applyFusedChunkTickLightning(ServerLevel level, Queue<BlockPos> strikes) {
        if (strikes.isEmpty()) {
            return;
        }
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level)) {
            strikes.clear();
            return;
        }
        BlockPos rolled;
        while ((rolled = strikes.poll()) != null) {
            level.tickLightning(rolled);
            this.engine.chunkTickLightningStrikes.incrementAndGet();
        }
    }

    /** In a player-only slice the ordinary worker groups would only collect the same fallbacks. */
    private boolean tryDispatchPlayerOnlyLevel(ServerLevel level, List<RegionState> regions, RegionEntityTickBatch batch) {
        if (!this.engine.playerCriterionStorageEnabled() || this.engine.mailboxes.containsKey(level) || !this.engine.isQuiescentForParallelApi()) {
            return false;
        }
        for (var region : regions) {
            if (region.serverThreadOnlyEntityCount() != region.tickingEntityCount()) return false;
        }
        if (RegionPlayerExtensionSnapshot.capture().refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) return false;

        var ordered = new ArrayList<Entity>();
        for (var region : regions) {
            for (var entry : region.entityTickEntries()) {
                if (!entry.ticking()) continue;
                if (!entry.serverThreadOnly() || entry.entity().getClass() != ServerPlayer.class) return false;
                ordered.add(entry.entity());
            }
        }
        var publication = this.engine.publishTickBatch(batch, level);
        if (publication != TickBatchPublication.PUBLISHED) return false;
        RegionEntityTickAttribution.endPlanSegment();
        this.engine.serverThreadOnlyEntityTicks.addAndGet(ordered.size());
        batch.markExecutingOnCurrentThread();
        batch.enableExternalWaves();
        try {
            this.engine.tickPlayerWorldSequence(batch, ordered);
        } finally {
            batch.completeExternalWaves();
            batch.finish();
            this.engine.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
        }
        return true;
    }

    /**
     * Collects a region whose ticking entities are all {@code serverThreadOnly} (such as players) directly
     * into the server-thread fallback of {@code batch} without activating a chunk rectangle, leasing the
     * region, or waking a worker thread only to hand the same entities back.
     */
    private boolean collectServerThreadOnlyRegion(RegionEntityTickBatch batch, RegionState region) {
        final int ticking = region.tickingEntityCount();
        if (ticking <= 0 || region.serverThreadOnlyEntityCount() != ticking) {
            return false;
        }
        final List<RegionEntityTickEntry> entries = region.entityTickEntries();
        for (int i = 0, size = entries.size(); i < size; ++i) {
            final RegionEntityTickEntry entry = entries.get(i);
            if (entry.ticking() && !entry.serverThreadOnly()) {
                return false;
            }
        }
        int added = 0;
        for (int i = 0, size = entries.size(); i < size; ++i) {
            final RegionEntityTickEntry entry = entries.get(i);
            if (entry.ticking()) {
                batch.addSelfDrivenFallback(entry.entity());
                ++added;
            }
        }
        if (added > 0) {
            this.engine.serverThreadOnlyEntityTicks.addAndGet(added);
        }
        return true;
    }

    /**
     * Hands off any ticking {@link ServerPlayer} inside an overload-throttled region to the server-thread
     * fallback so player movement, packet handling, and {@code ServerPlayerGameMode.tick()} block-breaking
     * progress never fall behind client ticks.
     */
    private int collectSkippedRegionPlayers(RegionEntityTickBatch batch, RegionState region) {
        final List<RegionEntityTickEntry> entries = region.entityTickEntries();
        int playersAdded = 0;
        for (int i = 0, size = entries.size(); i < size; ++i) {
            final RegionEntityTickEntry entry = entries.get(i);
            if (!entry.ticking()) {
                continue;
            }
            final Entity entity = entry.entity();
            if (carriesPlayer(entity)) {
                if (!ridesLiveVehicle(entity)) {
                    batch.addSelfDrivenFallback(entity);
                    ++playersAdded;
                }
            } else if ((entry.serverThreadOnly() || entity instanceof ServerPlayer) && !ridesLiveVehicle(entity)) {
                batch.addSelfDrivenFallback(entity);
                ++playersAdded;
            }
        }
        if (playersAdded > 0) {
            this.engine.serverThreadOnlyEntityTicks.addAndGet(playersAdded);
        }
        return playersAdded;
    }

    /**
     * Collects players, player-carrying vehicles, and server-thread-only policy entities (e.g. Create contraptions,
     * projectiles) for immediate synchronous execution on the server thread before dispatching the region's
     * remaining worker-eligible entities asynchronously.
     *
     * @return the number of remaining worker-eligible ticking entities in {@code region}
     */
    private int collectSynchronousRegionEntities(RegionEntityTickBatch batch, RegionState region) {
        final List<RegionEntityTickEntry> entries = region.entityTickEntries();
        final boolean singleRegionContraptions = RegionThreadingConfig.singleRegionContraptionWorkersEnabled();
        final int marginBlocks = singleRegionContraptions ? RegionThreadingConfig.unifiedRegionEntityContainmentMarginBlocks() : 8;
        int syncPlayersAdded = 0;
        int workerEligible = 0;
        for (int i = 0, size = entries.size(); i < size; ++i) {
            final RegionEntityTickEntry entry = entries.get(i);
            if (!entry.ticking()) {
                continue;
            }
            final Entity entity = entry.entity();
            if (entry.serverThreadOnly() || entity instanceof ServerPlayer) {
                if (!ridesLiveVehicle(entity)) {
                    batch.addSelfDrivenFallback(entity);
                }
                ++syncPlayersAdded;
                continue;
            }
            if (carriesPlayer(entity)) {
                if (!ridesLiveVehicle(entity)) {
                    batch.addSelfDrivenFallback(entity);
                }
                ++syncPlayersAdded;
                continue;
            }
            if (!ridesLiveVehicle(entity)) {
                if (singleRegionContraptions && isSingleRegionContainedEntityEligible(batch.level(), region, entity, marginBlocks)) {
                    ++workerEligible;
                    continue;
                }
                if (this.engine.entityTickPolicy.recordServerThreadOnly(entity) != null) {
                    if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isRegionContainedEntity(entity)) {
                        this.pinCrossBorderTouchingSections(batch.level(), region, entity, marginBlocks);
                    }
                    batch.addSelfDrivenFallback(entity);
                    continue;
                }
            }
            ++workerEligible;
        }
        if (syncPlayersAdded > 0) {
            this.engine.serverThreadOnlyEntityTicks.addAndGet(syncPlayersAdded);
        }
        return workerEligible;
    }

    /**
     * Phase 4B: Checks whether a region-contained moving-structure entity's AABB inflated by
     * {@code marginBlocks + velocity} lies strictly within {@code region}'s chunk bounds so it can
     * execute in Stage 2 of {@code region}'s unified worker tick loop with zero thread split against
     * Stage 3 BlockEntities.
     */
    boolean isSingleRegionContainedEntityEligible(
            @Nullable ServerLevel level,
            RegionState region,
            Entity entity,
            int marginBlocks) {
        if (level == null || region == null || entity == null || entity.isRemoved() || entity.level() != level) {
            return false;
        }
        if (!net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isRegionContainedEntity(entity)) {
            return false;
        }
        final net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        if (box == null) {
            return false;
        }
        final net.minecraft.world.phys.Vec3 delta = entity.getDeltaMovement();
        final double vx = delta != null && Double.isFinite(delta.x) ? Math.abs(delta.x) : 0.0;
        final double vz = delta != null && Double.isFinite(delta.z) ? Math.abs(delta.z) : 0.0;
        if (vx > 16.0 || vz > 16.0) {
            return false;
        }
        final double padX = Math.max(1, marginBlocks) + vx;
        final double padZ = Math.max(1, marginBlocks) + vz;
        final int minChunkX = ((int) Math.floor(box.minX - padX)) >> 4;
        final int maxChunkX = ((int) Math.floor(box.maxX + padX)) >> 4;
        final int minChunkZ = ((int) Math.floor(box.minZ - padZ)) >> 4;
        final int maxChunkZ = ((int) Math.floor(box.maxZ + padZ)) >> 4;
        final RegionLayout layout = this.engine.layout;
        final RegionCoordinate coord = region.coordinate();
        return layout.isInRegion(level, minChunkX, minChunkZ, coord)
                && layout.isInRegion(level, maxChunkX, minChunkZ, coord)
                && layout.isInRegion(level, minChunkX, maxChunkZ, coord)
                && layout.isInRegion(level, maxChunkX, maxChunkZ, coord);
    }

    /**
     * Phase 4C: When a moving structure touches or crosses a region border, pins the two adjacent sections
     * together in the regionizer / dynamic topology for a hysteresis window so split checks are deferred
     * while the cross-border structure is active.
     */
    private void pinCrossBorderTouchingSections(
            @Nullable ServerLevel level,
            RegionState region,
            Entity entity,
            int marginBlocks) {
        if (level == null || region == null || entity == null) {
            return;
        }
        final net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        if (box == null) {
            return;
        }
        final double pad = Math.max(1, marginBlocks);
        final int minChunkX = ((int) Math.floor(box.minX - pad)) >> 4;
        final int maxChunkX = ((int) Math.floor(box.maxX + pad)) >> 4;
        final int minChunkZ = ((int) Math.floor(box.minZ - pad)) >> 4;
        final int maxChunkZ = ((int) Math.floor(box.maxZ + pad)) >> 4;
        final RegionLayout layout = this.engine.layout;
        final RegionCoordinate owner = region.coordinate();
        final long untilGameTime = level.getGameTime() + Math.max(40L, layout.dynamicSplitDelayTicks());
        final int[][] corners = {
                { minChunkX, minChunkZ },
                { maxChunkX, minChunkZ },
                { minChunkX, maxChunkZ },
                { maxChunkX, maxChunkZ }
        };
        for (final int[] corner : corners) {
            final RegionCoordinate target = layout.regionAt(level, corner[0], corner[1]);
            if (!target.equals(owner)) {
                this.engine.pinAdjacentSectionsTogether(level, owner, target, untilGameTime);
            }
        }
    }

    /**
     * {@return the chunk-load answers of the level's current loaded-chunk generation}
     * <p>
     * A new generation starts when the level reports a different loaded chunk count; the map is then rebuilt
     * from the server thread as the pre-load margin asks its chunks.
     */
    private RegionThreadingEngine.ChunkLoadAnswers chunkLoadAnswers(ServerLevel level) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        final int loadedChunkCount = chunkSource == null ? 0 : chunkSource.getLoadedChunksCount();
        RegionThreadingEngine.ChunkLoadAnswers answers = this.engine.chunkLoadAnswers.get(level);
        if (answers == null || answers.loadedChunkCount != loadedChunkCount) {
            answers = new RegionThreadingEngine.ChunkLoadAnswers(loadedChunkCount);
            this.engine.chunkLoadAnswers.put(level, answers);
        }
        return answers;
    }

    /**
     * Hands every ticking entity of {@code region} to the server thread.
     * <p>
     * This is the counted per-region fallback: the region's entity tick could not be driven on a worker this
     * tick, so its entities keep the vanilla server-thread body instead of being dropped.
     */
    private void addRegionFallback(RegionEntityTickBatch batch, RegionState region) {
        for (final RegionEntityTickEntry entry : region.entityTickEntries()) {
            if (entry.ticking()) {
                batch.addSelfDrivenFallback(entry.entity());
            }
        }
    }

    /**
     * Ticks the entities of this pass that a worker handed back, on the server thread.
     */
    void tickSelfDrivenFallbacks(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        final List<Entity> deferred = batch.deferredUpdateFallbackEntities();
        final List<Entity> selfDriven = batch.selfDrivenFallbackEntities();
        publishServerThreadEntityLockstepBoxes(level, deferred, selfDriven);
        if (!this.engine.playerCriterionStorageEnabled()) {
            for (final Entity entity : deferred) this.tickEntityServerSide(level, entity);
            for (final Entity entity : selfDriven) this.tickEntityServerSide(level, entity);
            return;
        }
        // Self-driven fallbacks arrive from concurrent workers in arbitrary order; separate candidate players
        // into a contiguous list so tickPlayerWorldSequence can dispatch them in a single parallel wave.
        final boolean runsNormally = level.tickRateManager() == null || level.tickRateManager().runsNormally();
        final List<Entity> players = new ArrayList<>();
        for (final Entity entity : deferred) {
            if (isPlayerEntityWaveCandidate(level, entity, runsNormally)) {
                players.add(entity);
            } else {
                this.tickEntityServerSide(level, entity);
            }
        }
        for (final Entity entity : selfDriven) {
            if (isPlayerEntityWaveCandidate(level, entity, runsNormally)) {
                players.add(entity);
            } else {
                this.tickEntityServerSide(level, entity);
            }
        }
        if (!players.isEmpty()) {
            if (players.size() > 1) {
                players.sort(java.util.Comparator.comparingInt(Entity::getId));
            }
            this.engine.tickPlayerWorldSequence(batch, players);
        }
    }

    private static void publishServerThreadEntityLockstepBoxes(ServerLevel level, List<Entity> deferred, List<Entity> selfDriven) {
        final int radius = RegionThreadingConfig.sandboxEntityProximityLockstepRadius();
        if (radius <= 0) {
            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.updateServerThreadEntityLockstepBoxes(level, null);
            return;
        }
        int count = 0;
        for (int i = 0, s = deferred.size(); i < s; ++i) {
            if (isLockstepProximityEntity(level, deferred.get(i))) {
                ++count;
            }
        }
        for (int i = 0, s = selfDriven.size(); i < s; ++i) {
            if (isLockstepProximityEntity(level, selfDriven.get(i))) {
                ++count;
            }
        }
        if (count == 0) {
            net.neoforged.neoforge.server.threading.api.RegionAdapterApi.updateServerThreadEntityLockstepBoxes(level, null);
            return;
        }
        final double[] flat = new double[count * 6];
        int idx = 0;
        for (int i = 0, s = deferred.size(); i < s; ++i) {
            final Entity entity = deferred.get(i);
            if (isLockstepProximityEntity(level, entity)) {
                idx = writeInflatedBox(flat, idx, entity, radius);
            }
        }
        for (int i = 0, s = selfDriven.size(); i < s; ++i) {
            final Entity entity = selfDriven.get(i);
            if (isLockstepProximityEntity(level, entity)) {
                idx = writeInflatedBox(flat, idx, entity, radius);
            }
        }
        net.neoforged.neoforge.server.threading.api.RegionAdapterApi.updateServerThreadEntityLockstepBoxes(level, flat);
    }

    private static boolean isLockstepProximityEntity(ServerLevel level, Entity entity) {
        if (entity == null || entity.isRemoved() || entity.level() != level) {
            return false;
        }
        if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isRegionContainedEntity(entity)) {
            return true;
        }
        if (entity instanceof net.minecraft.world.entity.player.Player
                || entity instanceof net.minecraft.world.entity.LivingEntity
                || entity instanceof net.minecraft.world.entity.item.ItemEntity
                || entity instanceof net.minecraft.world.entity.ExperienceOrb
                || entity instanceof net.minecraft.world.entity.projectile.Projectile
                || entity instanceof net.minecraft.world.entity.vehicle.Boat
                || entity instanceof net.minecraft.world.entity.decoration.HangingEntity
                || entity instanceof net.minecraft.world.entity.Display
                || entity instanceof net.minecraft.world.entity.Marker
                || entity instanceof net.minecraft.world.entity.AreaEffectCloud) {
            return false;
        }
        return entity.getDeltaMovement().lengthSqr() > 1.0E-6
                || entity.getX() != entity.xo
                || entity.getY() != entity.yo
                || entity.getZ() != entity.zo;
    }

    private static int writeInflatedBox(double[] flat, int idx, Entity entity, double radius) {
        final net.minecraft.world.phys.AABB box = entity.getBoundingBox();
        flat[idx] = box.minX - radius;
        flat[idx + 1] = box.minY - radius;
        flat[idx + 2] = box.minZ - radius;
        flat[idx + 3] = box.maxX + radius;
        flat[idx + 4] = box.maxY + radius;
        flat[idx + 5] = box.maxZ + radius;
        return idx + 6;
    }

    private static boolean isPlayerEntityWaveCandidate(ServerLevel level, Entity entity, boolean runsNormally) {
        return entity instanceof ServerPlayer player
                && player.getClass() == ServerPlayer.class
                && !player.isRemoved()
                && player.level() == level
                && !player.isPassenger()
                && player.getPassengers().isEmpty()
                && level.isInEntityTickingRange(player.chunkPosition().toLong())
                && !RegionEntityTickActivation.isFrozen(player, runsNormally);
    }

    /**
     * Runs the whole vanilla entity body for {@code entity} on the server thread: the discard predicate, the
     * frozen predicate, the despawn check, the entity ticking range, the riding stack and the non-passenger
     * tick.
     * <p>
     * This is the server-thread path of an entity whose region could not be enumerated or that a worker handed
     * back, so it reproduces the vanilla pass instead of only its tick.
     */
    void tickEntityServerSide(ServerLevel level, Entity entity) {
        if (entity.isRemoved() || entity.level() != level) {
            this.engine.skippedEntityTicks.incrementAndGet();
            return;
        }
        if (this.engine.regionizer.isLevelClosed(level)) {
            this.engine.skippedEntityTicks.incrementAndGet();
            return;
        }
        final MinecraftServer server = level.getServer();
        if (RegionEntityTickActivation.shouldDiscard(entity, server == null || server.isSpawningAnimals(), server == null || server.areNpcsEnabled())) {
            entity.discard();
            return;
        }
        if (RegionEntityTickActivation.isFrozen(entity, level.tickRateManager() == null || level.tickRateManager().runsNormally())) {
            return;
        }
        entity.checkDespawn();
        if (entity.isRemoved()) {
            return;
        }
        if (!level.isInEntityTickingRange(entity.chunkPosition().toLong())) {
            return;
        }
        if (ridesLiveVehicle(entity)) {
            return;
        }
        if (entity.getVehicle() != null) {
            entity.stopRiding();
        }
        if (entity.isRemoved() || entity instanceof PartEntity<?>) {
            return;
        }
        level.guardEntityTick(level::tickNonPassenger, entity);
        this.engine.fallbackEntityTicks.incrementAndGet();
    }


    void tickEntities(ServerLevel level, List<Entity> accepted, RegionTickBarrier barrier) {
        this.engine.flushStagedFusedChunks(level);
        if (accepted.isEmpty()) {
            return;
        }

        final RegionEntityTickBatch batch = new RegionEntityTickBatch(level, this.engine.deferredBlockUpdateBudget);
        final List<Entity> pending = new ArrayList<>(accepted.size());
        for (final Entity entity : accepted) {
            if (entity.isRemoved() || entity.level() != level) {
                // The entity or its level went away between the snapshot and the batch; the vanilla
                // inline path would have skipped it as well.
                this.engine.skippedEntityTicks.incrementAndGet();
                continue;
            }
            pending.add(entity);
        }
        if (pending.isEmpty()) {
            return;
        }

        final TickBatchPublication publication = this.engine.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was accepted. Its region state is retired
            // and no batch may be published for it: the accepted entities must not be ticked or
            // handed to the vanilla inline fallback after their level unloaded.
            this.engine.skippedEntityTicks.addAndGet(pending.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the accepted entities still
            // have to tick, but no batch may be published after shutdown started.
            for (final Entity entity : pending) {
                this.tickEntityInline(level, entity);
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (deferred work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        // The opt-in attribution is decided once per batch, never per entity: a disabled attribution only
        // costs this one volatile read and takes no timestamp.
        final boolean attribution = RegionEntityTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseEntityTickGroups(batch, pending, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.engine.runRegionBatch(level, () -> batch.submitAndAwait(this.engine.workerPool, barrier));
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            // The deferred work after the barrier is measured as its own segment, so the fallback ticks and
            // the section publication are reported instead of being folded into the barrier wait.
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                // The portal transition of a worker-ticked entity runs first, exactly like in the self-driven
                // slice: it is the earliest level effect of the tick it belongs to, and the destination search
                // may read the POI manager of the destination level.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredPortalTransitions(batch));
                // The section publication and the target chunk acquisition of a worker entity move are the
                // vanilla effects of the move itself, so they run before any other server-thread work observes
                // the entity and before the ownership handoff of the batch commits a new region.
                final long sectionStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntitySectionUpdates(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordSectionUpdates(System.nanoTime() - sectionStart);
                    }
                }
                // The removal of an owned entity is the other vanilla storage effect a worker may defer. It
                // runs after the section updates, so the removal removes the entity from the section it was
                // published in, and before the ownership publication, so the handoff observes the removed
                // entity and publishes its departure instead of a region mapping.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityRemovals(batch));
                // The admission of a new entity is the third deferred vanilla storage effect. It runs after
                // the removals, so a record whose entity was removed before the barrier is dropped by the
                // removal it observes, and after the section updates, so an entity admitted here lands in the
                // section bucket its final position names and not in one a pending update is about to move.
                // It runs before the fallback ticks, so a graph admitted in this tick is visible to the
                // server-thread ticks of the same batch, and before the ownership publication, so the
                // handoff tracks the new entity.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityAdditions(batch));
                // The light rechecks and block updates of the blocks the workers wrote are the level-wide effects
                // of their tick, so they run before the server-thread fallback ticks of this batch observe the
                // written state.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockUpdates(batch));
                final long fallbackStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.tickFallbackEntities(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordFallbacks(System.nanoTime() - fallbackStart);
                    }
                }
                final long deferredTasksStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, batch::runDeferredServerTasks);
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordDeferredTasks(System.nanoTime() - deferredTasksStart);
                    }
                }
                // The ownership publication is timed on its own inside the handoff, so the cost of the
                // deferred ownership handoff is separable from the fallback entity ticks that share the
                // handoff segment.
                final long publicationStart = attribution ? System.nanoTime() : 0L;
                try {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredEntityUpdates(batch));
                } finally {
                    if (attribution) {
                        RegionEntityTickAttribution.recordDeferredPublication(System.nanoTime() - publicationStart);
                    }
                }
                batch.finish();
                this.engine.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            } finally {
                if (attribution) {
                    RegionEntityTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    /**
     * Groups {@code pending} by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt, because
     * a merge or split re-points the tracked entities to the surviving region. Anything that cannot be
     * resolved to a live, leasable region is recorded as a server-thread fallback, and so is every entity
     * whose own chunk neighborhood is not loaded enough to tick on a worker.
     */
    private void leaseEntityTickGroups(RegionEntityTickBatch batch, List<Entity> pending, RegionTickBarrier barrier) {
        final List<Entity> unloadedNeighborhood = new ArrayList<>();
        final List<Entity> tickable = new ArrayList<>(pending.size());
        this.splitPreloadRefusedEntities(batch.level(), pending, tickable, unloadedNeighborhood);
        List<Entity> remaining = tickable;
        int batchedEntities = 0;
        for (int attempt = 0; attempt < RegionThreadingEngine.MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, Entity> grouping = new RegionTickGrouping<>();
            final List<Entity> retry = new ArrayList<>();
            for (final Entity entity : remaining) {
                final RegionState region = this.engine.regionizer.regionFor(entity);
                if (region == null || region.isDead()) {
                    retry.add(entity);
                } else if (this.engine.shouldSkipOverloadedRegion(batch.level(), region)) {
                    this.engine.skippedEntityTicks.incrementAndGet();
                } else {
                    grouping.add(region, entity);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<Entity> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            // The regions are leased in one canonical order and split into balanced packs, so one task of this
            // slice can hold several regions. A slice with fewer regions than workers is not packed and keeps the
            // unpacked submission of one task per region.
            for (final List<RegionState> pack : this.engine.regionPacks(grouping.regions())) {
                final List<RegionState> toLease = new ArrayList<>(pack.size());
                for (final RegionState region : pack) {
                    if (batch.leases(region)) {
                        // An earlier group of this same batch already owns the lease. It cannot be
                        // acquired again and must never be awaited on this thread.
                        batch.group(region).ifPresent(group -> group.addEntities(grouping.items(region)));
                        continue;
                    }
                    toLease.add(region);
                }
                if (toLease.isEmpty()) {
                    continue;
                }
                if (toLease.size() == 1) {
                    if (this.leaseEntityGroup(batch, grouping, toLease.get(0), barrier, next)) {
                        leasedAny = true;
                        batchedEntities += grouping.size(toLease.get(0));
                    }
                    continue;
                }
                final List<RegionState> leased = RegionLeasePack.tryAcquireAll(toLease, this.engine::releaseRegionLease);
                if (leased == null) {
                    // The pack could not take every region: it released the ones it took again, and every
                    // region of the pack is leased through the unpacked path, which waits for a region a worker
                    // still holds instead of failing on it. The fallback is counted.
                    this.engine.packedLeaseFallbacks.incrementAndGet();
                    for (final RegionState region : toLease) {
                        if (this.leaseEntityGroup(batch, grouping, region, barrier, next)) {
                            leasedAny = true;
                            batchedEntities += grouping.size(region);
                        }
                    }
                    continue;
                }
                leasedAny = true;
                for (final RegionState region : leased) {
                    batchedEntities += grouping.size(region);
                }
                this.addPackedEntityGroups(batch, grouping, leased);
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final Entity entity : unloadedNeighborhood) {
            batch.addFallback(entity);
        }
        for (final Entity entity : remaining) {
            batch.addFallback(entity);
            this.engine.fallbackEntityTicks.incrementAndGet();
        }
        if (batchedEntities > 0 && this.engine.entityTickWorkersNoticeLogged.compareAndSet(false, true)) {
            RegionThreadingEngine.LOGGER.info("Region entity ticks are now executing on region workers: {} entities", batchedEntities);
        }
    }

    /**
     * Leases one region of an entity batch and submits its group as its own task.
     *
     * @return {@code true} when the region was leased and its group submitted, {@code false} when its entities
     *         were recorded for the next attempt
     * @see #leaseBlockEntityGroup the same unpacked path of the block-entity slice
     */
    private boolean leaseEntityGroup(RegionEntityTickBatch batch, RegionTickGrouping<RegionState, Entity> grouping, RegionState region, RegionTickBarrier barrier, List<Entity> retry) {
        if (!this.engine.acquireRegionLease(region, barrier)) {
            retry.addAll(grouping.items(region));
            return false;
        }
        this.engine.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region),
                group -> this.engine.withRegionMailboxDelivery(batch.level(), region, this.entityTickGroup(group))));
        return true;
    }

    /**
     * Registers the groups of one leased pack and submits them as one worker task.
     * <p>
     * A failure while a group is built releases every lease of the pack, exactly like the block-entity pack: a
     * region must never stay TICKING behind a group no worker will run.
     */
    private void addPackedEntityGroups(RegionEntityTickBatch batch, RegionTickGrouping<RegionState, Entity> grouping, List<RegionState> pack) {
        final List<Runnable> works = new ArrayList<>(pack.size());
        try {
            for (final RegionState region : pack) {
                final RegionEntityTickBatch.Group group = batch.registerGroup(region, grouping.items(region));
                works.add(batch.packedWork(group, leased -> this.engine.withRegionMailboxDelivery(batch.level(), region, this.entityTickGroup(leased))));
            }
        } catch (Throwable throwable) {
            for (final RegionState region : pack) {
                this.engine.releaseRegionLease(region);
            }
            throw throwable;
        }
        this.engine.packedTasks.incrementAndGet();
        this.engine.packedRegions.addAndGet(pack.size());
        batch.addPackedTask("entity tick pack of " + pack.size(), works);
    }

    /**
     * Splits {@code pending} into the entities whose own chunk neighborhood is loaded enough to tick on a
     * worker and the ones that have to tick on the server thread.
     * <p>
     * Runs on the server thread before any group is submitted. A chunk counts as loaded when the chunk
     * source can hand out its {@code FULL} chunk without loading one, which is exactly what the worker-side
     * immediate lookup of {@code ServerChunkCache.getChunk} answers. Nothing is force-loaded. The check is
     * per entity, so an entity at the edge of the loaded area falls back alone while the rest of its region
     * stays on the workers; a whole region was refused only because one of its chunks was missing, which is
     * what made the edge entities the dominant server-thread cost of the handoff.
     * <p>
     * The neighborhood of a chunk is a pure function of its coordinate, so it is computed once per chunk and
     * shared by every entity standing in it, and the answer of the chunk source for one chunk is shared by
     * every neighborhood that contains it. Both memos are batch local, so the server thread asks the chunk
     * source once per distinct chunk of the batch instead of once per entity. The split happens once per
     * batch: no entity moves before its lease (the server thread only awaits region work in between), a chunk
     * that was missing is only ever loaded later, and a refused entity therefore never reaches a worker in
     * this tick.
     *
     * @param level    the level whose chunk source answers the check
     * @param pending  the entities of the batch, in snapshot order
     * @param tickable receives the entities whose neighborhood is loaded
     * @param refused  receives the entities that fall back to the server thread
     */
    private void splitPreloadRefusedEntities(ServerLevel level, List<Entity> pending, List<Entity> tickable, List<Entity> refused) {
        final Long2ByteMap chunkLoaded = new Long2ByteOpenHashMap();
        chunkLoaded.defaultReturnValue(RegionThreadingEngine.NEIGHBORHOOD_UNKNOWN);
        final Long2ByteMap neighborhoodLoaded = new Long2ByteOpenHashMap();
        neighborhoodLoaded.defaultReturnValue(RegionThreadingEngine.NEIGHBORHOOD_UNKNOWN);
        for (final Entity entity : pending) {
            // The cached block position of the entity: it is the chunk the entity's tick starts from and
            // reading it allocates nothing, unlike deriving a chunk position per entity.
            final BlockPos pos = entity.blockPosition();
            final int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            final int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            if (this.engine.isEntityTickNeighborhoodLoaded(level, chunkX, chunkZ, chunkLoaded, neighborhoodLoaded)) {
                tickable.add(entity);
            } else {
                refused.add(entity);
                this.engine.chunkPreloadRefusedEntityTicks.incrementAndGet();
            }
        }
    }


    private Runnable entityTickGroup(RegionEntityTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final List<Entity> entities = group.entities();
        final ServerLevel level = group.level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                // The group random is installed once for the whole worker tick loop, so every direct
                // level.random read of an entity tick resolves this group's source through the level
                // random wrapper instead of the shared vanilla one. The loop body itself writes no
                // thread-local.
                this.engine.currentEntityTickRandom.set(random);
                try {
                    // The entity ownership lease covers exactly this worker tick loop: it is opened after the
                    // region lease and closed before it, so a worker task outside the loop never sees it.
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    final BiConsumer<Entity, Throwable> failureObserver = this.engine.entityTickFailureObserverForTesting;
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, group), () -> {
                            for (final Entity entity : entities) {
                                if (!group.batch().tryReserveBlockUpdates()) {
                                    // The batch cannot reserve the block-write effects of this entity's tick, so
                                    // the tick is refused before its body starts and runs on the server thread
                                    // after the batch, where its writes need no record.
                                    group.batch().addDeferredUpdateFallback(entity);
                                    this.engine.countRefusedDeferredBlockUpdate(RegionTickSlice.ENTITY_TICK);
                                    continue;
                                }
                                if (!prepareOwnedEntityTick(entity)) {
                                    continue;
                                }
                                try {
                                    // The guarded part of the vanilla pass is exactly the non-passenger tick:
                                    // a failure of the despawn check or of the discard predicate above keeps
                                    // propagating like vanilla lets it propagate.
                                    tickEntityBody(level, entity);
                                } catch (Throwable throwable) {
                                    if (this.engine.truncateIfChunkUnavailable(entity, throwable)) {
                                        // The tick reached a chunk the lease does not hold. Its body already
                                        // wrote entity fields and deferred effects, so it is not replayed: the
                                        // tick is lost and the class is declared server-thread-only, which
                                        // makes every later tick of it run where the read is legal.
                                        continue;
                                    }
                                    if (failureObserver != null) {
                                        // Diagnostics only: the observer records the rejected operation and the
                                        // group keeps ticking its remaining entities instead of stopping at the
                                        // first one.
                                        failureObserver.accept(entity, throwable);
                                        continue;
                                    }
                                    if (this.engine.truncateIfBoundaryViolation(entity, throwable)) {
                                        // The tick crossed a worker-forbidden storage or player-state boundary.
                                        // Truncate this tick and dynamically fall back the runtime class to the
                                        // server thread instead of crashing the server.
                                        continue;
                                    }
                                    // The failure is recorded for the server thread instead of being turned into
                                    // a crash report on this worker; the group stops here, exactly like the
                                    // vanilla entity pass stops at its first failing entity.
                                    this.engine.recordWorkerTickFailure(group.batch(), coordinate, entity, throwable);
                                    return;
                                }
                                ++ticked[0];
                            }
                        });
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.engine.currentEntityTickRandom.remove();
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("entity batch " + coordinate, duration,
                        () -> "entities=" + ticked[0] + " regionEntities=" + region.entityTickEntryCount()
                                + " regionChunks=" + region.chunkCount());
                this.engine.entityTicksOnWorkers.addAndGet(ticked[0]);
                this.engine.regionizer.removeIfEmpty(region);
            }
        };
    }

    /**
     * Builds the worker task of one self-driven region group.
     * <p>
     * The task enumerates the entity tick index of the region it leased - the server thread handed it nothing
     * but the lease and the activation snapshot - revalidates the ownership of every entry, and runs the
     * vanilla entity body of the region's ticking entities inside the region scope. This is what removes the
     * per-entity server-thread collection from the entity pass: the server thread never iterated the level's
     * entity tick list for this region.
     */
    private void executeAsyncSelfDrivenEntities(
            ServerLevel level,
            List<RegionEntityTickActivation> activations,
            @Nullable Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion,
            @Nullable FusedRegionTickSession.PendingChunkWork pendingChunks,
            RegionEntityTickBatch playerBatch,
            boolean drainRemainingChunks) {
        // 1. Tick all players, player-carrying vehicles, and server-thread-only policy entities (e.g. contraptions)
        // synchronously on the server thread at full 20 TPS.
        final int[] workerEligibleCounts = new int[activations.size()];
        for (int i = 0, size = activations.size(); i < size; ++i) {
            workerEligibleCounts[i] = this.collectSynchronousRegionEntities(playerBatch, activations.get(i).region());
        }
        this.tickSelfDrivenFallbacks(playerBatch);
        playerBatch.finish();

        // Phase 4A: When the unified per-region tick loop is active, stage the chunk + entity activations
        // so RegionUnifiedTickCoordinator executes Chunks -> Entities -> BlockEntities in a single worker wave.
        if (this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)) {
            this.engine.unifiedTickCoordinator.stageEntityAndChunkWork(
                    level, activations, workerEligibleCounts, chunksByRegion, pendingChunks);
            return;
        }

        // 2. Dispatch remaining worker-eligible entities (+ fused chunk random ticks) asynchronously per region without barrier.
        final long gameTime = level.getGameTime();
        final int perRegionBudget = Math.max(128, this.engine.deferredBlockUpdateBudget / Math.max(1, activations.size()));
        final int randomTickSpeed = pendingChunks != null ? pendingChunks.randomTickSpeed() : 0;
        final List<RegionIndependentTickScheduler.AsyncBatchItem> asyncItems = new ArrayList<>(activations.size());
        final List<RegionEntityTickBatch> entityBatches = new ArrayList<>(activations.size());
        final List<Integer> entityCounts = new ArrayList<>(activations.size());
        final List<Integer> chunkCounts = new ArrayList<>(activations.size());
        for (int i = 0, size = activations.size(); i < size; ++i) {
            final RegionEntityTickActivation activation = activations.get(i);
            final RegionState region = activation.region();
            final List<RegionChunkTickEntry> regionChunks = chunksByRegion != null ? chunksByRegion.remove(region) : null;
            final boolean hasChunks = regionChunks != null && !regionChunks.isEmpty();
            if (workerEligibleCounts[i] <= 0 && !hasChunks) {
                continue;
            }
            final RegionEntityTickBatch regionBatch = new RegionEntityTickBatch(level, perRegionBudget);
            regionBatch.setAsyncMainThreadWorkSink(task -> this.engine.independentTickScheduler.enqueueCommit(level, task));
            final FusedRegionTickBatch regionFused = hasChunks
                    ? new FusedRegionTickBatch(level, randomTickSpeed, perRegionBudget)
                    : null;
            final RegionEntityTickBatch.Group group = regionBatch.registerGroup(region, List.of());
            final Runnable entityBody = this.selfDrivenEntityTickGroup(group, activation, false, true);
            final Runnable work = regionFused != null
                    ? this.engine.withRegionMailboxDelivery(level, region, () -> {
                        this.runFusedChunkStage(regionFused, region, group, regionChunks, randomTickSpeed, false);
                        entityBody.run();
                    })
                    : this.engine.withRegionMailboxDelivery(level, region, entityBody);
            final Runnable wrappedWork = regionBatch.wrapAsyncGroupWork(work);
            final Runnable commit = () -> {
                try {
                    if (!this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                        this.engine.applyDeferredPortalTransitions(regionBatch);
                        this.engine.applyDeferredEntitySectionUpdates(regionBatch);
                        this.engine.applyDeferredEntityRemovals(regionBatch);
                        this.engine.applyDeferredEntityAdditions(regionBatch);
                        this.engine.applyDeferredBlockUpdates(regionBatch);
                        if (regionFused != null) {
                            this.applyFusedChunkTickLightning(level, regionFused.lightningStrikes());
                            for (final RegionChunkTickEntry fc : regionFused.drainFallbackChunks()) {
                                this.engine.tickChunkOnServerThread(level, fc, regionFused.randomTickSpeed());
                            }
                            this.engine.applyRegionTickMutations(regionFused.regionTickMutations());
                        }
                        this.tickSelfDrivenFallbacks(regionBatch);
                        regionBatch.runDeferredServerTasks();
                        this.engine.applyDeferredEntityUpdates(regionBatch);
                    }
                } finally {
                    regionBatch.finish();
                }
            };
            asyncItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                    region, "async entity", wrappedWork, commit, false, 0L, gameTime));
            entityBatches.add(regionBatch);
            entityCounts.add(workerEligibleCounts[i]);
            chunkCounts.add(0);
        }
        // Any remaining chunk-only regions without active entity activations
        if (drainRemainingChunks && chunksByRegion != null && !chunksByRegion.isEmpty() && pendingChunks != null) {
            for (final var entry : chunksByRegion.entrySet()) {
                final RegionState region = entry.getKey();
                final List<RegionChunkTickEntry> regionChunks = entry.getValue();
                if (regionChunks == null || regionChunks.isEmpty()) continue;
                final FusedRegionTickBatch regionFused = new FusedRegionTickBatch(level, randomTickSpeed, perRegionBudget);
                final RegionEntityTickBatch regionBatch = new RegionEntityTickBatch(level, perRegionBudget);
                regionBatch.setAsyncMainThreadWorkSink(task -> this.engine.independentTickScheduler.enqueueCommit(level, task));
                final RegionEntityTickBatch.Group group = regionBatch.registerGroup(region, List.of());
                final Runnable wrappedWork = regionBatch.wrapAsyncGroupWork(
                        this.engine.withRegionMailboxDelivery(level, region,
                                () -> this.runFusedChunkStage(regionFused, region, group, regionChunks, randomTickSpeed, false)));
                final Runnable commit = () -> {
                    try {
                        if (!this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                            this.engine.applyDeferredBlockUpdates(regionBatch);
                            this.applyFusedChunkTickLightning(level, regionFused.lightningStrikes());
                            for (final RegionChunkTickEntry fc : regionFused.drainFallbackChunks()) {
                                this.engine.tickChunkOnServerThread(level, fc, regionFused.randomTickSpeed());
                            }
                            this.engine.applyRegionTickMutations(regionFused.regionTickMutations());
                        }
                    } finally {
                        regionBatch.finish();
                    }
                };
                asyncItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                        region, "async chunk", wrappedWork, commit, false, 0L, gameTime));
                entityBatches.add(regionBatch);
                entityCounts.add(0);
                chunkCounts.add(regionChunks.size());
            }
        }
        if (!asyncItems.isEmpty()) {
            final var outcomes = this.engine.independentTickScheduler.submitBatchOptimized(asyncItems);
            for (int i = 0; i < outcomes.length; ++i) {
                if (outcomes[i] == RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED) {
                    if (entityCounts.get(i) > 0) {
                        this.engine.entityTickRegionsOnWorkers.incrementAndGet();
                    }
                } else {
                    if (entityCounts.get(i) > 0) {
                        this.engine.skippedEntityTicks.addAndGet(entityCounts.get(i));
                    }
                    if (chunkCounts.get(i) > 0) {
                        this.engine.skippedChunkTicks.addAndGet(chunkCounts.get(i));
                    }
                    entityBatches.get(i).finish();
                }
            }
        }
    }

    private Runnable selfDrivenEntityTickGroup(RegionEntityTickBatch.Group group, RegionEntityTickActivation activation) {
        return this.selfDrivenEntityTickGroup(group, activation, true, false);
    }

    Runnable selfDrivenEntityTickGroup(RegionEntityTickBatch.Group group, RegionEntityTickActivation activation,
            boolean manageRegionLifecycle, boolean skipPlayersAlreadyTicked) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            // Per-entity timing for the long-tick warning, only when the warning is on: a server that does not ask
            // for it pays nothing, and a batch that trips the warning names the three entities that cost the most.
            final long warningThreshold = RegionThreadingConfig.longTickWarningNanos();
            final long[] slowestNanos = new long[3];
            final Entity[] slowestEntities = new Entity[3];
            try {
                if (manageRegionLifecycle) {
                    region.beginOwnership();
                }
                // The group random is installed once for the whole worker tick loop, exactly like the
                // per-entity plan does for the group it was handed.
                this.engine.currentEntityTickRandom.set(random);
                try {
                    // The index is enumerated once per group: the tick list of the region cannot change
                    // underneath this loop, because entity moves are deferred to the barrier and a leased
                    // region cannot merge, split or transfer.
                    final List<RegionEntityTickEntry> entries = region.entityTickEntries();
                    final List<Entity> tickable = new ArrayList<>(entries.size());
                    final boolean singleRegionContraptions = RegionThreadingConfig.singleRegionContraptionWorkersEnabled();
                    final int marginBlocks = singleRegionContraptions ? RegionThreadingConfig.unifiedRegionEntityContainmentMarginBlocks() : 8;
                    int localServerThreadOnly = 0;
                    for (final RegionEntityTickEntry entry : entries) {
                        if (!entry.ticking()) {
                            // Mapped to the region but not in the level's entity tick list: the vanilla pass
                            // does not tick it, and neither does the region.
                            continue;
                        }
                        if (entry.serverThreadOnly()) {
                            // An always-ticking entity (a player) is ticked by the server thread, never by a
                            // region worker: its tick reaches the connection and the chunk source.
                            if (!skipPlayersAlreadyTicked) {
                                group.batch().addSelfDrivenFallback(entry.entity());
                            }
                            ++localServerThreadOnly;
                            continue;
                        }
                        if (carriesPlayer(entry.entity())) {
                            // A vehicle ticks its passengers recursively, so a boat, a minecart or a Create seat
                            // that carries a player would tick that player on the worker even though a player is
                            // declared server-thread-only. The whole riding stack is pinned at the vehicle.
                            if (!skipPlayersAlreadyTicked) {
                                group.batch().addSelfDrivenFallback(entry.entity());
                            }
                            ++localServerThreadOnly;
                            continue;
                        }
                        if (!ridesLiveVehicle(entry.entity())) {
                            final boolean containedMovingStructure = singleRegionContraptions
                                    && this.isSingleRegionContainedEntityEligible(level, region, entry.entity(), marginBlocks);
                            if (!containedMovingStructure) {
                                // The per-type policy is consulted before the body starts. A declared type is
                                // ticked on the server thread after the batch instead of on this worker; an
                                // entity that rides a live vehicle is decided at the vehicle, whose entry covers
                                // the riding stack.
                                if (skipPlayersAlreadyTicked) {
                                    if (this.engine.entityTickPolicy.reasonFor(entry.entity()) != null) {
                                        continue;
                                    }
                                } else if (this.engine.entityTickPolicy.recordServerThreadOnly(entry.entity()) != null) {
                                    group.batch().addSelfDrivenFallback(entry.entity());
                                    continue;
                                }
                            }
                        }
                        tickable.add(entry.entity());
                    }
                    if (localServerThreadOnly > 0) {
                        this.engine.serverThreadOnlyEntityTicks.addAndGet(localServerThreadOnly);
                    }
                    if (!tickable.isEmpty()) {
                        // The ownership lease covers exactly the entities of this region. Installing them
                        // before the first tick keeps the ownership check of every mutation of the loop and
                        // covers the passenger stacks the group ticks through their vehicle.
                        group.addEntities(tickable);
                    }
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    final BiConsumer<Entity, Throwable> failureObserver = this.engine.entityTickFailureObserverForTesting;
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, group), () -> {
                            for (final Entity entity : tickable) {
                                if (!group.batch().tryReserveBlockUpdates()) {
                                    // The batch cannot reserve the block-write effects of this entity's tick, so
                                    // the tick is refused before its body starts and runs on the server thread
                                    // after the batch, where its writes need no record.
                                    group.batch().addDeferredUpdateFallback(entity);
                                    this.engine.countRefusedDeferredBlockUpdate(RegionTickSlice.ENTITY_TICK);
                                    continue;
                                }
                                if (!this.prepareSelfDrivenEntityTick(level, entity, activation, group)) {
                                    continue;
                                }
                                final long entityStart = warningThreshold > 0L ? System.nanoTime() : 0L;
                                try {
                                    // The guarded part of the vanilla pass is exactly the non-passenger tick:
                                    // a failure of the discard predicate, the despawn check or the riding
                                    // stack above keeps propagating like vanilla lets it propagate.
                                    tickEntityBody(level, entity);
                                } catch (Throwable throwable) {
                                    if (this.engine.truncateIfChunkUnavailable(entity, throwable)) {
                                        // The tick reached a chunk the lease does not hold. Its body already
                                        // wrote entity fields and deferred effects, so it is not replayed: the
                                        // tick is lost and the class is declared server-thread-only, which
                                        // makes every later tick of it run where the read is legal.
                                        continue;
                                    }
                                    if (failureObserver != null) {
                                        // Diagnostics only: the observer records the rejected operation and the
                                        // group keeps ticking its remaining entities instead of stopping at the
                                        // first one.
                                        failureObserver.accept(entity, throwable);
                                        continue;
                                    }
                                    if (this.engine.truncateIfBoundaryViolation(entity, throwable)) {
                                        // The tick crossed a worker-forbidden storage or player-state boundary.
                                        // Truncate this tick and dynamically fall back the runtime class to the
                                        // server thread instead of crashing the server.
                                        continue;
                                    }
                                    // The failure is recorded for the server thread instead of being turned into
                                    // a crash report on this worker; the group stops here, exactly like the
                                    // vanilla entity pass stops at its first failing entity.
                                    this.engine.recordWorkerTickFailure(group.batch(), coordinate, entity, throwable);
                                    return;
                                }
                                ++ticked[0];
                                if (warningThreshold > 0L) {
                                    recordSlowEntity(slowestEntities, slowestNanos, System.nanoTime() - entityStart, entity);
                                }
                            }
                        });
                    } finally {
                        ownership.close();
                    }
                } finally {
                    this.engine.currentEntityTickRandom.remove();
                }
            } finally {
                final long duration = System.nanoTime() - start;
                if (manageRegionLifecycle) {
                    region.endOwnership();
                    region.clearRunning();
                    region.tickMetrics().recordTick(duration);
                }
                if (this.engine.independentTickScheduler != null) {
                    this.engine.independentTickScheduler.recordEntitySliceDuration(coordinate, duration);
                }
                RegionTickWatchdog.warnIfLongTick("entity batch " + coordinate, duration,
                        () -> "entities=" + ticked[0] + " regionEntities=" + region.entityTickEntryCount()
                                + " regionChunks=" + region.chunkCount()
                                + " slowest=" + describeSlowEntities(slowestEntities, slowestNanos));
                this.engine.entityTicksOnWorkers.addAndGet(ticked[0]);
                if (manageRegionLifecycle) {
                    this.engine.regionizer.removeIfEmpty(region);
                }
            }
        };
    }

    /**
     * Runs the vanilla preconditions of one entity of a leased region on the worker.
     * <p>
     * The preconditions are the ones the vanilla {@code ServerLevel} entity pass runs before it ticks: the
     * discard predicate, the frozen predicate, the despawn check, the entity ticking range of the entity's
     * chunk and the riding stack. The two server-owned reads of that pass - the ticking range and the
     * entity-load neighborhood of the chunk guard - are answered from the activation snapshot the server
     * thread built before the lease.
     * <p>
     * An entity whose chunk cannot be decided from the snapshot, or whose chunk guard would need an unloaded
     * chunk, is handed to the server thread instead of being dropped. The entity body has not run for it at
     * that point, so the handoff runs all of it.
     * <p>
     * The non-passenger tick itself is not run here: it is the part of the vanilla pass that
     * {@code Level#guardEntityTick} wraps, so the caller runs it and decides what a failure of it means - a
     * recorded failure for the server thread, or a rejected operation for the diagnostics observer.
     *
     * @return {@code true} when the caller has to run the vanilla non-passenger tick for {@code entity}
     */
    private boolean prepareSelfDrivenEntityTick(ServerLevel level, Entity entity, RegionEntityTickActivation activation, RegionEntityTickBatch.Group group) {
        if (entity.isRemoved()) {
            return false;
        }
        // The cached chunk position of the entity: the chunk its tick starts from, read once for the
        // activation snapshot and the ticking range check, exactly like the vanilla body reads it before it
        // ticks.
        final int chunk = activation.indexOf(entity.chunkPosition());
        if (chunk < 0) {
            // The entity left the region's chunk rectangle between its last mapping publication and this
            // tick, so its chunk cannot be decided here. The server thread ticks it and republishes its
            // mapping there.
            group.batch().addSelfDrivenFallback(entity);
            this.engine.entityTicksOutsideRegion.incrementAndGet();
            return false;
        }
        if (!activation.isNeighborhoodLoaded(chunk)) {
            // The chunk guard would have to ask the chunk source for a chunk the tick may read; the entity is
            // ticked on the server thread instead of blocking the worker on the chunk source.
            group.batch().addSelfDrivenFallback(entity);
            this.engine.chunkPreloadRefusedEntityTicks.incrementAndGet();
            return false;
        }
        if (activation.shouldDiscard(entity)) {
            // The vanilla body discards the entity instead of ticking it. On a worker the removal itself is
            // deferred to the barrier, exactly like every other removal of a leased region.
            entity.discard();
            return false;
        }
        if (activation.isFrozen(entity)) {
            return false;
        }
        try {
            entity.checkDespawn();
        } catch (RegionWorkerBoundaryException boundary) {
            // A mod listener on MobDespawnEvent (e.g. InControl reading DimensionDataStorage when its
            // filtered despawn rule cache is cold) crossed a worker-forbidden boundary before the entity
            // tick body started. Service checkDespawn() on the server thread immediately so any lazy
            // global rule cache is populated for the rest of the batch; if servicing is unavailable,
            // hand the entity back to the server-thread fallback pass.
            try {
                this.engine.runOnServerThread(group.batch().level(), () -> {
                    entity.checkDespawn();
                    return null;
                });
            } catch (RuntimeException fallbackRequired) {
                group.batch().addSelfDrivenFallback(entity);
                return false;
            }
        }
        if (entity.isRemoved()) {
            return false;
        }
        if (!activation.isEntityTicking(chunk)) {
            // Outside the vanilla entity ticking range: the despawn check above is all the vanilla body does,
            // and the entity is not ticked.
            return false;
        }
        if (ridesLiveVehicle(entity)) {
            // Ticked as part of the vehicle stack, which the group ticks through the vehicle.
            return false;
        }
        if (entity.getVehicle() != null) {
            entity.stopRiding();
        }
        if (entity.isRemoved() || entity instanceof PartEntity<?>) {
            return false;
        }
        return true;
    }

    /**
     * Runs the part of the vanilla entity pass that {@code Level#guardEntityTick} wraps - the non-passenger tick -
     * on the worker, with the entity timing that the guard records around it.
     * <p>
     * The guard's crash report is not built here: a failure propagates to the caller, which records it for the
     * server thread instead, see {@link RegionTickFailure}.
     */
    private static void tickEntityBody(ServerLevel level, Entity entity) {
        TimeTracker.ENTITY_UPDATE.trackStart(entity);
        try {
            level.tickNonPassenger(entity);
        } finally {
            TimeTracker.ENTITY_UPDATE.trackEnd(entity);
        }
    }

    /**
     * {@return true when a player rides {@code entity}, directly or through a nested stack}
     * <p>
     * A vehicle ticks its passengers recursively, so the per-type declaration of a player is not enough: the
     * vehicle decides where its stack ticks. A seat of a moving structure carried a player on a region worker and
     * its tick cost 16 ms, most of it work that belongs to the server thread.
     */
    private static boolean carriesPlayer(Entity entity) {
        for (final Entity passenger : entity.getPassengers()) {
            if (passenger instanceof net.minecraft.world.entity.player.Player || carriesPlayer(passenger)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return true when {@code entity} is ticked through the vehicle that still carries it}
     * <p>
     * A passenger is not ticked on its own: the tick of its vehicle runs the whole vanilla tick of every
     * passenger ({@code Entity#rideTick}), so the vehicle is the entry the entity slices decide on. A
     * vehicle that was removed or that no longer carries the entity is not a live ride: the vanilla body
     * stops the ride and ticks the entity itself.
     */
    private static boolean ridesLiveVehicle(Entity entity) {
        final Entity vehicle = entity.getVehicle();
        return vehicle != null && !vehicle.isRemoved() && vehicle.hasPassenger(entity);
    }

    /**
     * Runs the vanilla preconditions of one worker entity tick.
     *
     * @return {@code true} when the entity still has to be ticked
     */
    /**
     * Keeps the three slowest entities of a batch, largest first, for the long-tick warning.
     * <p>
     * A warning that says only "27 entities took 57 ms" cannot name the cause; the entity that costs 50 of those
     * milliseconds can. The slots are plain arrays because the worker loop owns them for the batch.
     */
    private static void recordSlowEntity(Entity[] entities, long[] nanos, long took, Entity entity) {
        if (took <= nanos[2]) {
            return;
        }
        nanos[2] = took;
        entities[2] = entity;
        for (int i = 2; i > 0 && nanos[i] > nanos[i - 1]; i--) {
            final long swapNanos = nanos[i - 1];
            nanos[i - 1] = nanos[i];
            nanos[i] = swapNanos;
            final Entity swapEntity = entities[i - 1];
            entities[i - 1] = entities[i];
            entities[i] = swapEntity;
        }
    }

    /** {@return a short "type=took ms" list of the slowest entities of a batch} */
    private static String describeSlowEntities(Entity[] entities, long[] nanos) {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < entities.length; i++) {
            if (entities[i] == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(", ");
            }
            text.append(entities[i].getType()).append('=')
                    .append(String.format(java.util.Locale.ROOT, "%.1f", nanos[i] / 1_000_000.0)).append("ms");
        }
        return text.length() == 0 ? "none" : text.toString();
    }

    private static boolean prepareOwnedEntityTick(Entity entity) {
        if (entity.isRemoved()) {
            return false;
        }
        entity.checkDespawn();
        return !entity.isRemoved();
    }

    /**
     * Ticks entities refused before their body, because a region could not be leased safely or because the
     * deferred block-update bound could not reserve their effects, on the server thread after the batch.
     */
    private void tickFallbackEntities(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        for (final Entity entity : batch.fallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
        for (final Entity entity : batch.deferredUpdateFallbackEntities()) {
            this.tickEntityInline(level, entity);
        }
    }

    private void tickEntityInline(ServerLevel level, Entity entity) {
        if (entity.isRemoved() || entity.level() != level) {
            return;
        }
        if (this.engine.regionizer.isLevelClosed(level)) {
            // A fallback tick is only reached for an accepted snapshot; once the level unloaded, the
            // entity belongs to a retired identity and must not tick on the server thread either.
            this.engine.skippedEntityTicks.incrementAndGet();
            return;
        }
        entity.checkDespawn();
        if (entity.isRemoved()) {
            return;
        }
        level.guardEntityTick(level::tickNonPassenger, entity);
        this.republishServerThreadEntityMove(level, entity);
    }

    /**
     * Republishes the region mapping of an entity the server thread just ticked, when its tick moved it out
     * of the region it is mapped to.
     * <p>
     * A worker tick defers every position write and the batch barrier publishes the captured destination, so
     * the mapping of a worker-ticked entity is maintained by the entity slice. A server-thread tick - the
     * pre-load fallback, the chunk fallback, a declared server-thread-only type and the always-ticking player
     * - runs the unmodified vanilla body, whose position write reaches the level callback directly and never
     * the entity batch. Without this publication the mapping of such an entity keeps the region it started in
     * while the entity walks away, and the region index would group and lease it by a region that no longer
     * contains it.
     * <p>
     * The common case - the entity stayed inside its region - costs one read-lock lookup and one coordinate
     * comparison and writes nothing. Only a move that really crosses a region border takes the write lock and
     * notifies the entity's scheduled tasks, exactly like the deferred path does.
     */
    private void republishServerThreadEntityMove(ServerLevel level, Entity entity) {
        if (this.engine.shutdown || entity.isRemoved() || entity.level() != level) {
            return;
        }
        final RegionState mapped = this.engine.regionizer.regionFor(entity);
        if (mapped == null) {
            // The entity is not mapped at all (it never started ticking in a region); publish the first
            // mapping so the index knows it.
            this.engine.publishEntityMove(entity);
            return;
        }
        if (mapped.coordinate().equals(this.engine.layout.regionAt(level, entity.blockPosition()))) {
            return;
        }
        this.engine.publishEntityMove(entity);
    }

}
