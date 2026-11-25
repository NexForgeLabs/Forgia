/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates cross-region border update fixpoint convergence and cross-region game event delivery.
 * <p>
 * Extracted from {@link RegionThreadingEngine} to isolate foreign-read invalidation reruns, graph-colored
 * parallel propagation waves, game-event column replays, and delayed mailbox handoffs.
 */
@ApiStatus.Internal
final class RegionBorderPropagationCoordinator {
    interface Host {
        boolean isShutdown();

        boolean isLevelClosed(ServerLevel level);

        boolean borderUpdatesEnabled();

        boolean crossRegionGameEventsEnabled();

        boolean foreignReadRerunEnabled();

        boolean parallelBorderFixpointEnabled();

        boolean activeFusedBatch();

        boolean hasBlockEntityFallbackPhase();

        boolean hasBlockEventPhase();

        void countBlockEventPhaseFixpoint();

        int borderUpdateBudgetForTesting();

        int deferredBlockUpdateBudget();

        RegionLayout layout();

        RegionWorkerPool workerPool();

        @Nullable
        RegionIndependentTickScheduler independentTickScheduler();

        FusedRegionTickSession fusedSession();

        @Nullable
        RegionBorderUpdates borderUpdates(ServerLevel level);

        RegionBorderUpdates borderQueue(ServerLevel level);

        @Nullable
        RegionBorderGameEvents borderGameEvents(ServerLevel level);

        @Nullable
        Set<RegionState> regionsWithForeignReads(ServerLevel level);

        @Nullable
        RegionState getRegion(RegionCoordinate coordinate);

        RegionMailbox mailbox(ServerLevel level);

        @Nullable
        RegionTickBarrier levelBarrier(ServerLevel level);

        TickBatchPublication publishTickBatch(RegionTickBatch batch, ServerLevel level);

        void retireTickBatch(RegionTickBatch batch);

        boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier);

        void addLeasedGroup(RegionState region, Runnable registration);

        void beginRegionPhase(ServerLevel level);

        void endRegionPhase(ServerLevel level);

        void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable action);

        void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations);

        void setCurrentPropagationOrigin(@Nullable BlockPos pos);

        void applyDeferredBlockUpdates(RegionTickBatch batch);

        void applyRegionTickMutations(Queue<Runnable> mutations);

        void removeIfEmpty(RegionState region);

        void countBorderUpdatesDropped(int count);

        void countBorderBlockWriteDropped();

        void countBorderUpdateApplied(boolean isBlockWrite);

        void countBorderUpdateRegionRerun();

        void countBorderUpdatePasses(int passes);

        void countBorderUpdatesDelayed(int count);

        void countBorderUpdatesOverflowed();

        void countForeignReadInvalidatedPass();

        void countForeignReadRegionRerun();

        void countForeignReadSeedsDropped(int count);

        void countGameEventDeliveryPass();

        void countGameEventSectionDelivered();

        void countGameEventSectionsDropped(int count);

        void countGameEventSectionDroppedNoChunk();

        void countGameEventSectionDroppedNoRegion();
    }

    private final Host host;

    RegionBorderPropagationCoordinator(Host host) {
        this.host = host;
    }

    void runBorderUpdateFixpoint(ServerLevel level) {
        if (!this.host.borderUpdatesEnabled()) {
            return;
        }
        if (this.host.hasBlockEntityFallbackPhase()) {
            return;
        }
        if (this.host.hasBlockEventPhase()) {
            this.host.countBlockEventPhaseFixpoint();
        }
        final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidated = this.invalidatedForeignReads(level);
        final RegionBorderUpdates updates = this.host.borderUpdates(level);
        final List<RegionBorderUpdates.BorderUpdate> pending = updates == null ? List.of() : updates.drain();
        final List<RegionBorderUpdates.BorderUpdate> overflow = updates == null ? List.of() : updates.drainOverflow();
        if (pending.isEmpty() && overflow.isEmpty() && invalidated.isEmpty()) {
            return;
        }
        if (this.host.isLevelClosed(level) || this.host.isShutdown()) {
            this.host.countBorderUpdatesDropped(pending.size() + overflow.size());
            if (updates != null) {
                updates.discard();
            }
            return;
        }
        this.delayBorderUpdates(level, overflow);

        final int budget = this.chainedUpdateBudget(level);
        final RegionTickBarrier barrier = this.borderFixpointBarrier(level);
        int passes = 0;
        int applied = 0;
        final List<RegionBorderUpdates.BorderUpdate> deferred = new ArrayList<>();
        Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> reruns = invalidated;
        if (!reruns.isEmpty()) {
            this.host.countForeignReadInvalidatedPass();
            if (this.host.activeFusedBatch()) {
                final long gameTime = level.getGameTime();
                for (final RegionState invalidatedRegion : reruns.keySet()) {
                    this.host.fusedSession().recordForeignReadInvalidation(invalidatedRegion.coordinate(), gameTime);
                }
            }
        }
        List<RegionBorderUpdates.BorderUpdate> nextPending = pending;
        while ((!nextPending.isEmpty() || !reruns.isEmpty()) && passes < budget && applied < budget) {
            passes++;
            final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> byRegion = this.groupBorderUpdatesByRegion(level, nextPending);
            for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> rerun : reruns.entrySet()) {
                final RegionState region = rerun.getKey();
                if (region.isDead()) {
                    this.host.countBorderUpdatesDropped(rerun.getValue().size());
                    continue;
                }
                byRegion.merge(region, rerun.getValue(), (existing, added) -> {
                    existing.addAll(added);
                    return existing;
                });
            }
            reruns = new LinkedHashMap<>();
            final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> ranThisPass = new LinkedHashMap<>();
            final boolean useParallelFirstPass = passes == 1
                    && byRegion.size() > 1
                    && (this.host.parallelBorderFixpointEnabled() || RegionThreadingConfig.parallelBorderFixpointEnabled());
            if (useParallelFirstPass) {
                final List<List<RegionState>> colorWaves = RegionAdjacencyColoring.color(byRegion.keySet(), RegionState::coordinate);
                for (final List<RegionState> wave : colorWaves) {
                    final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> wavePayloads = new LinkedHashMap<>();
                    for (final RegionState region : wave) {
                        final List<RegionBorderUpdates.BorderUpdate> regionUpdates = byRegion.get(region);
                        if (applied >= budget) {
                            deferred.addAll(regionUpdates);
                            continue;
                        }
                        final int allowed = Math.min(regionUpdates.size(), budget - applied);
                        final List<RegionBorderUpdates.BorderUpdate> apply = regionUpdates.subList(0, allowed);
                        if (allowed < regionUpdates.size()) {
                            deferred.addAll(regionUpdates.subList(allowed, regionUpdates.size()));
                        }
                        applied += apply.size();
                        wavePayloads.put(region, apply);
                    }
                    if (wavePayloads.isEmpty()) {
                        continue;
                    }
                    if (wavePayloads.size() == 1) {
                        final var single = wavePayloads.entrySet().iterator().next();
                        if (this.runBorderPropagationPass(level, single.getKey(), single.getValue(), barrier)) {
                            this.host.countBorderUpdateRegionRerun();
                            ranThisPass.put(single.getKey(), single.getValue());
                        } else {
                            applied -= single.getValue().size();
                            deferred.addAll(single.getValue());
                        }
                    } else {
                        final Set<RegionState> leased = this.runParallelBorderPropagationWave(level, wavePayloads, barrier);
                        for (final var entry : wavePayloads.entrySet()) {
                            if (leased.contains(entry.getKey())) {
                                this.host.countBorderUpdateRegionRerun();
                                ranThisPass.put(entry.getKey(), entry.getValue());
                            } else {
                                applied -= entry.getValue().size();
                                deferred.addAll(entry.getValue());
                            }
                        }
                    }
                }
            } else {
                for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> entry : byRegion.entrySet()) {
                    final List<RegionBorderUpdates.BorderUpdate> regionUpdates = entry.getValue();
                    if (applied >= budget) {
                        deferred.addAll(regionUpdates);
                        continue;
                    }
                    final int allowed = Math.min(regionUpdates.size(), budget - applied);
                    final List<RegionBorderUpdates.BorderUpdate> apply = regionUpdates.subList(0, allowed);
                    if (allowed < regionUpdates.size()) {
                        deferred.addAll(regionUpdates.subList(allowed, regionUpdates.size()));
                    }
                    if (this.runBorderPropagationPass(level, entry.getKey(), apply, barrier)) {
                        applied += apply.size();
                        this.host.countBorderUpdateRegionRerun();
                        ranThisPass.put(entry.getKey(), apply);
                    } else {
                        deferred.addAll(apply);
                    }
                }
            }
            boolean passInvalidated = false;
            for (final Map.Entry<RegionState, List<RegionBorderUpdates.BorderUpdate>> entry : ranThisPass.entrySet()) {
                final List<RegionBorderUpdates.BorderUpdate> payload = this.invalidatedForeignRead(level, entry.getKey(), entry.getValue());
                if (payload == null) {
                    continue;
                }
                passInvalidated = true;
                reruns.put(entry.getKey(), payload);
                this.host.countForeignReadRegionRerun();
                if (this.host.activeFusedBatch()) {
                    this.host.fusedSession().recordForeignReadInvalidation(entry.getKey().coordinate(), level.getGameTime());
                }
            }
            if (passInvalidated) {
                this.host.countForeignReadInvalidatedPass();
            }
            nextPending = updates == null ? List.of() : updates.drain();
        }
        if (!nextPending.isEmpty()) {
            deferred.addAll(nextPending);
        }
        for (final List<RegionBorderUpdates.BorderUpdate> leftover : reruns.values()) {
            deferred.addAll(leftover);
        }
        this.host.countBorderUpdatePasses(passes);
        if (!deferred.isEmpty()) {
            this.delayBorderUpdates(level, deferred);
        }
    }

    void runCrossRegionGameEvents(ServerLevel level) {
        if (!this.host.crossRegionGameEventsEnabled()) {
            return;
        }
        final RegionBorderGameEvents queue = this.host.borderGameEvents(level);
        if (queue == null || queue.isEmpty()) {
            return;
        }
        if (this.host.isLevelClosed(level) || this.host.isShutdown()) {
            this.host.countGameEventSectionsDropped(queue.drain().size());
            queue.discard();
            return;
        }
        final int budget = this.chainedUpdateBudget(level);
        final RegionTickBarrier barrier = this.borderFixpointBarrier(level);
        int passes = 0;
        while (passes < budget) {
            final List<RegionBorderGameEvents.SectionColumn> pending = queue.drain();
            if (pending.isEmpty()) {
                return;
            }
            passes++;
            this.host.countGameEventDeliveryPass();
            boolean ranAnyWorkerDelivery = false;
            for (final Map.Entry<RegionState, List<RegionBorderGameEvents.SectionColumn>> entry : this
                    .groupGameEventsByRegion(level, pending).entrySet()) {
                final RegionState region = entry.getKey();
                final List<RegionBorderGameEvents.SectionColumn> columns = entry.getValue();
                if (!this.hasActiveGameEventListeners(level, columns)) {
                    this.completeEmptyGameEventColumns(level, columns);
                    continue;
                }
                ranAnyWorkerDelivery = true;
                if (!this.runGameEventDeliveryPass(level, region, columns, barrier)) {
                    this.host.countGameEventSectionsDropped(columns.size());
                }
            }
            if (ranAnyWorkerDelivery) {
                this.runBorderUpdateFixpoint(level);
            }
        }
        final int leftover = queue.drain().size();
        if (leftover > 0) {
            this.host.countGameEventSectionsDropped(leftover);
        }
    }

    void discardForeignReads(RegionState region) {
        final int dropped = region.discardForeignReads();
        if (dropped > 0) {
            this.host.countForeignReadSeedsDropped(dropped);
        }
    }

    private Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidatedForeignReads(ServerLevel level) {
        final Set<RegionState> regions = this.host.regionsWithForeignReads(level);
        if (regions == null || regions.isEmpty()) {
            return Map.of();
        }
        Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> invalidated = null;
        for (final RegionState region : List.copyOf(regions)) {
            if (region.isRunning() && !region.isDead()) {
                continue;
            }
            regions.remove(region);
            if (region.isDead()) {
                this.discardForeignReads(region);
                continue;
            }
            final List<RegionBorderUpdates.BorderUpdate> payload = this.invalidatedForeignRead(level, region, List.of());
            if (payload != null) {
                if (invalidated == null) {
                    invalidated = new LinkedHashMap<>();
                }
                invalidated.put(region, payload);
                this.host.countForeignReadRegionRerun();
            }
        }
        return invalidated == null ? Map.of() : invalidated;
    }

    @Nullable
    private List<RegionBorderUpdates.BorderUpdate> invalidatedForeignRead(ServerLevel level, RegionState region,
            List<RegionBorderUpdates.BorderUpdate> applied) {
        final RegionState.ForeignReadSnapshot snapshot = region.consumeForeignReadsAndSeeds();
        final boolean changed = this.host.foreignReadRerunEnabled() && this.foreignReadVersionsChanged(level, snapshot.reads());
        final List<RegionBorderUpdates.BorderUpdate> payload = changed ? this.rerunPayload(applied, snapshot.seeds()) : null;
        if (snapshot.droppedSeeds() > 0) {
            this.host.countForeignReadSeedsDropped(snapshot.droppedSeeds());
        }
        return payload == null || payload.isEmpty() ? null : payload;
    }

    private boolean foreignReadVersionsChanged(ServerLevel level, Long2LongMap reads) {
        if (reads.isEmpty()) {
            return false;
        }
        final ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource == null) {
            return false;
        }
        boolean changed = false;
        for (final Long2LongMap.Entry read : reads.long2LongEntrySet()) {
            if (currentChunkVersion(chunkSource, read.getLongKey()) != read.getLongValue()) {
                changed = true;
            }
        }
        return changed;
    }

    private static long currentChunkVersion(@Nullable ServerChunkCache chunkSource, long chunkKey) {
        if (chunkSource == null) {
            return -1L;
        }
        final LevelChunk chunk = chunkSource.getChunkNow(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        return chunk == null ? -1L : chunk.chunkVersion();
    }

    private List<RegionBorderUpdates.BorderUpdate> rerunPayload(List<RegionBorderUpdates.BorderUpdate> applied,
            LongArrayList seeds) {
        final List<RegionBorderUpdates.BorderUpdate> payload = new ArrayList<>(applied);
        if (seeds.isEmpty()) {
            return payload;
        }
        final LongOpenHashSet covered = new LongOpenHashSet(applied.size());
        for (final RegionBorderUpdates.BorderUpdate update : applied) {
            covered.add(update.pos().asLong());
        }
        for (final long seed : seeds) {
            if (covered.add(seed)) {
                payload.add(new RegionBorderUpdates.SelfUpdate(BlockPos.of(seed)));
            }
        }
        return payload;
    }

    private Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> groupBorderUpdatesByRegion(ServerLevel level,
            List<RegionBorderUpdates.BorderUpdate> updates) {
        final Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> byRegion = new LinkedHashMap<>();
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            if (update instanceof RegionBorderUpdates.BlockWrite && !level.isLoaded(update.pos())) {
                this.host.countBorderUpdatesDropped(1);
                this.host.countBorderBlockWriteDropped();
                continue;
            }
            final RegionState region = this.host.getRegion(this.host.layout().regionAt(level, update.pos()));
            if (region == null || region.isDead()) {
                this.host.countBorderUpdatesDropped(1);
                continue;
            }
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(update);
        }
        return byRegion;
    }

    private boolean runBorderPropagationPass(ServerLevel level, RegionState region, List<RegionBorderUpdates.BorderUpdate> updates,
            RegionTickBarrier barrier) {
        return this.runPropagationPass(level, region, batch -> this.borderPropagationGroup(batch, region, updates), barrier);
    }

    private boolean runPropagationPass(ServerLevel level, RegionState region,
            Function<RegionBorderPropagationBatch, Runnable> body, RegionTickBarrier barrier) {
        final RegionBorderPropagationBatch batch = new RegionBorderPropagationBatch(level, this.host.deferredBlockUpdateBudget());
        final TickBatchPublication publication = this.host.publishTickBatch(batch, level);
        if (publication != TickBatchPublication.PUBLISHED) {
            return false;
        }
        batch.markExecutingOnCurrentThread();
        Throwable failure = null;
        boolean ran = false;
        try {
            if (this.host.acquireRegionLease(region, barrier)) {
                ran = true;
                this.host.addLeasedGroup(region, () -> batch.addGroup(region, body.apply(batch)));
                this.host.beginRegionPhase(level);
                try {
                    batch.submitAndAwait(this.host.workerPool(), barrier);
                } catch (Throwable throwable) {
                    failure = throwable;
                } finally {
                    this.host.endRegionPhase(level);
                }
            }
        } catch (Throwable throwable) {
            failure = accumulateFailure(failure, throwable);
        } finally {
            failure = runCompletion(failure, () -> this.host.applyDeferredBlockUpdates(batch));
            failure = runCompletion(failure, () -> this.host.applyRegionTickMutations(batch.regionTickMutations()));
            batch.finish();
            this.host.retireTickBatch(batch);
            batch.clearExecutingOnCurrentThread();
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
        return ran;
    }

    private Set<RegionState> runParallelBorderPropagationWave(
            ServerLevel level,
            Map<RegionState, List<RegionBorderUpdates.BorderUpdate>> wavePayloads,
            RegionTickBarrier barrier) {
        final RegionBorderPropagationBatch batch = new RegionBorderPropagationBatch(level, this.host.deferredBlockUpdateBudget());
        final TickBatchPublication publication = this.host.publishTickBatch(batch, level);
        if (publication != TickBatchPublication.PUBLISHED) {
            return Set.of();
        }
        batch.markExecutingOnCurrentThread();
        final Set<RegionState> leased = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failure = null;
        try {
            for (final RegionState region : RegionLeasePack.canonicalOrder(wavePayloads.keySet())) {
                if (this.host.acquireRegionLease(region, barrier)) {
                    leased.add(region);
                    final List<RegionBorderUpdates.BorderUpdate> updates = wavePayloads.get(region);
                    this.host.addLeasedGroup(region, () -> batch.addGroup(region, this.borderPropagationGroup(batch, region, updates)));
                }
            }
            if (!leased.isEmpty()) {
                this.host.beginRegionPhase(level);
                try {
                    batch.submitAndAwait(this.host.workerPool(), barrier);
                } catch (Throwable throwable) {
                    failure = throwable;
                } finally {
                    this.host.endRegionPhase(level);
                }
            }
        } catch (Throwable throwable) {
            failure = accumulateFailure(failure, throwable);
        } finally {
            failure = runCompletion(failure, () -> this.host.applyDeferredBlockUpdates(batch));
            failure = runCompletion(failure, () -> this.host.applyRegionTickMutations(batch.regionTickMutations()));
            batch.finish();
            this.host.retireTickBatch(batch);
            batch.clearExecutingOnCurrentThread();
        }
        if (failure != null) {
            throwUnchecked(failure);
        }
        batch.throwIfFailed();
        return leased;
    }

    private Runnable borderPropagationGroup(RegionBorderPropagationBatch batch, RegionState region,
            List<RegionBorderUpdates.BorderUpdate> updates) {
        return this.propagationGroup(batch, region, "border propagation", level -> {
            for (final RegionBorderUpdates.BorderUpdate update : updates) {
                this.host.setCurrentPropagationOrigin(update.pos());
                try {
                    update.apply(level);
                } catch (Throwable throwable) {
                    if (!RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)) {
                        throw throwable;
                    }
                } finally {
                    this.host.setCurrentPropagationOrigin(null);
                }
                this.host.countBorderUpdateApplied(update instanceof RegionBorderUpdates.BlockWrite);
            }
        });
    }

    private Runnable propagationGroup(RegionBorderPropagationBatch batch, RegionState region, String label,
            Consumer<ServerLevel> work) {
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = batch.level();
        return () -> {
            final long start = System.nanoTime();
            try {
                region.beginOwnership();
                this.host.setCurrentRegionTickMutations(batch.regionTickMutations());
                try {
                    final RegionNeighborUpdateScope previousNeighbors = RegionNeighborUpdateScope.enter(new RegionNeighborUpdateScope());
                    try {
                        this.host.runInRegionContext(level, coordinate, region, () -> work.accept(level));
                    } finally {
                        RegionNeighborUpdateScope.exit(previousNeighbors);
                    }
                } finally {
                    this.host.setCurrentRegionTickMutations(null);
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick(label + " " + coordinate, duration);
                this.host.removeIfEmpty(region);
            }
        };
    }

    private int chainedUpdateBudget(ServerLevel level) {
        final int testing = this.host.borderUpdateBudgetForTesting();
        if (testing >= 0) {
            return testing;
        }
        final int configured = level.maxChainedNeighborUpdates();
        return configured >= 0 ? configured : SharedConstants.MAX_CHAINED_NEIGHBOR_UPDATES;
    }

    private RegionTickBarrier borderFixpointBarrier(ServerLevel level) {
        final RegionTickBarrier barrier = this.host.levelBarrier(level);
        final RegionIndependentTickScheduler scheduler = this.host.independentTickScheduler();
        return barrier != null ? barrier : condition -> {
            while (!condition.getAsBoolean()) {
                if (scheduler != null && level != null) {
                    scheduler.drainCompletedCommits(level, 500_000L);
                }
                LockSupport.parkNanos(100_000L);
            }
        };
    }

    private boolean hasActiveGameEventListeners(ServerLevel level, List<RegionBorderGameEvents.SectionColumn> columns) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        if (chunkSource == null) {
            return true;
        }
        for (final RegionBorderGameEvents.SectionColumn column : columns) {
            final ChunkAccess chunk = chunkSource.getChunkNow(column.chunkX(), column.chunkZ());
            if (chunk == null) {
                continue;
            }
            for (int sectionY = column.minSectionY(); sectionY <= column.maxSectionY(); sectionY++) {
                if (!chunk.getListenerRegistry(sectionY).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void completeEmptyGameEventColumns(ServerLevel level, List<RegionBorderGameEvents.SectionColumn> columns) {
        final ServerChunkCache chunkSource = level.getChunkSource();
        for (final RegionBorderGameEvents.SectionColumn column : columns) {
            if (chunkSource != null && chunkSource.getChunkNow(column.chunkX(), column.chunkZ()) != null) {
                this.host.countGameEventSectionDelivered();
            } else {
                this.host.countGameEventSectionsDropped(1);
                this.host.countGameEventSectionDroppedNoChunk();
            }
        }
    }

    private Map<RegionState, List<RegionBorderGameEvents.SectionColumn>> groupGameEventsByRegion(ServerLevel level,
            List<RegionBorderGameEvents.SectionColumn> columns) {
        final Map<RegionState, List<RegionBorderGameEvents.SectionColumn>> byRegion = new LinkedHashMap<>();
        for (final RegionBorderGameEvents.SectionColumn column : columns) {
            final RegionState region = this.host.getRegion(this.host.layout().regionAt(level, column.chunkX(), column.chunkZ()));
            if (region == null || region.isDead()) {
                this.host.countGameEventSectionsDropped(1);
                this.host.countGameEventSectionDroppedNoRegion();
                continue;
            }
            byRegion.computeIfAbsent(region, key -> new ArrayList<>()).add(column);
        }
        return byRegion;
    }

    private boolean runGameEventDeliveryPass(ServerLevel level, RegionState region, List<RegionBorderGameEvents.SectionColumn> columns,
            RegionTickBarrier barrier) {
        return this.runPropagationPass(level, region, batch -> this.gameEventDeliveryGroup(batch, region, columns), barrier);
    }

    private Runnable gameEventDeliveryGroup(RegionBorderPropagationBatch batch, RegionState region,
            List<RegionBorderGameEvents.SectionColumn> columns) {
        return this.propagationGroup(batch, region, "game event delivery", level -> {
            final GameEventDispatcher dispatcher = new GameEventDispatcher(level);
            for (final RegionBorderGameEvents.SectionColumn column : columns) {
                if (dispatcher.postCaptured(column.event(), column.source(), column.context(), column.chunkX(), column.chunkZ(),
                        column.minSectionY(), column.maxSectionY())) {
                    this.host.countGameEventSectionDelivered();
                } else {
                    this.host.countGameEventSectionsDropped(1);
                    this.host.countGameEventSectionDroppedNoChunk();
                }
            }
        });
    }

    private void delayBorderUpdates(ServerLevel level, List<RegionBorderUpdates.BorderUpdate> updates) {
        if (updates.isEmpty()) {
            return;
        }
        final Map<RegionCoordinate, List<RegionBorderUpdates.BorderUpdate>> byTarget = new LinkedHashMap<>();
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            final RegionState region = this.host.getRegion(this.host.layout().regionAt(level, update.pos()));
            if (region == null || region.isDead()) {
                this.host.countBorderUpdatesDropped(1);
                continue;
            }
            byTarget.computeIfAbsent(region.coordinate(), key -> new ArrayList<>()).add(update);
        }
        if (byTarget.isEmpty()) {
            return;
        }
        final RegionMailbox mailbox = this.host.mailbox(level);
        for (final Map.Entry<RegionCoordinate, List<RegionBorderUpdates.BorderUpdate>> entry : byTarget.entrySet()) {
            final List<RegionBorderUpdates.BorderUpdate> message = List.copyOf(entry.getValue());
            final RegionMailbox.PostResult result = mailbox.post(entry.getKey(), () -> this.recordDelayedBorderUpdates(level, message));
            if (result == RegionMailbox.PostResult.ACCEPTED) {
                this.host.countBorderUpdatesDelayed(message.size());
            } else {
                this.host.countBorderUpdatesDropped(message.size());
            }
        }
    }

    private void recordDelayedBorderUpdates(ServerLevel level, List<RegionBorderUpdates.BorderUpdate> updates) {
        final RegionBorderUpdates queue = this.host.borderQueue(level);
        for (final RegionBorderUpdates.BorderUpdate update : updates) {
            if (queue.record(update) == RegionBorderUpdates.RecordResult.OVERFLOW) {
                this.host.countBorderUpdatesOverflowed();
            }
        }
    }

    private static Throwable accumulateFailure(@Nullable Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static @Nullable Throwable runCompletion(@Nullable Throwable failure, Runnable step) {
        try {
            step.run();
            return failure;
        } catch (Throwable throwable) {
            return accumulateFailure(failure, throwable);
        }
    }

    private static void throwUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region border propagation failed", throwable);
    }
}
