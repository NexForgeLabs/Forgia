/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Phase 4A: Coordinates the Folia-style Unified Per-Region Tick Loop
 * ({@code Chunks -> Entities -> BlockEntities}) on a single worker thread per region tick.
 * <p>
 * When {@code enableUnifiedRegionTickLoop} is active (together with {@code enableIndependentRegionTickClock}
 * and {@code enableRegionSandboxAllBlockEntities}):
 * <ol>
 * <li>{@link RegionEntityTickCoordinator} stages each region's eligible chunk ticks and worker-eligible entity
 *     activations here during the level's entity slice (while still executing players and non-contained
 *     {@code serverThreadOnly} entities synchronously on the server thread with 0-tick lockstep boxes).</li>
 * <li>{@link RegionBlockEntityTickCoordinator} passes the current tick's {@code sandboxByRegion} chunks here
 *     and triggers {@link #dispatchUnifiedLevelTick}, which submits <b>one</b>
 *     {@link RegionIndependentTickScheduler.AsyncBatchItem} per active region.</li>
 * <li>Inside that single worker lease, the worker executes:
 *     <ul>
 *     <li><b>Stage 1</b>: Region Chunks (random block/fluid ticks, ice/snow, thunder)</li>
 *     <li><b>Stage 2</b>: Region Entities (including single-region contained moving structures when Phase 4B is enabled)</li>
 *     <li><b>Stage 3</b>: Region BlockEntities (sharing the active {@link RegionEntityOwnershipScope} so BE
 *         {@code getEntities(AABB)} queries observe any entities freshly queued by Stage 2/Stage 3 in the same tick,
 *         and sharing one {@code mutations} queue and one main-thread {@code commit} callback)</li>
 *     </ul>
 * </li>
 * <li>If {@code tickBlockEntities} is skipped for any reason (e.g., frozen tick rate, intervening level save,
 *     or end-of-tick cleanup), {@link #flushStagedWork(ServerLevel)} flushes the staged chunk and entity stages
 *     so no work is ever stranded.</li>
 * </ol>
 */
@ApiStatus.Internal
public final class RegionUnifiedTickCoordinator {
    public record StagedEntityWork(
            List<RegionEntityTickActivation> activations,
            int[] workerEligibleCounts,
            @Nullable Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion,
            @Nullable FusedRegionTickSession.PendingChunkWork pendingChunks) {}

    public record Snapshot(
            long unifiedWavesDispatched,
            long unifiedRegionsSubmitted,
            long earlyEntityFlushes) {}

    private final RegionThreadingEngine engine;
    private final Map<ServerLevel, StagedEntityWork> stagedByLevel = new ConcurrentHashMap<>();
    private final LongAdder unifiedWavesDispatched = new LongAdder();
    private final LongAdder unifiedRegionsSubmitted = new LongAdder();
    private final LongAdder earlyEntityFlushes = new LongAdder();

    RegionUnifiedTickCoordinator(RegionThreadingEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public boolean isUnifiedLoopActive(ServerLevel level) {
        return RegionThreadingConfig.unifiedRegionTickLoopEnabled()
                && this.engine.isIndependentRegionTickEnabled()
                && RegionThreadingConfig.regionSandboxAllBlockEntitiesEnabled()
                && !this.engine.shutdown
                && !this.engine.regionizer.isLevelClosed(level);
    }

    public boolean hasStagedWork(ServerLevel level) {
        return level != null && this.stagedByLevel.containsKey(level);
    }

    void stageEntityAndChunkWork(
            ServerLevel level,
            List<RegionEntityTickActivation> activations,
            int[] workerEligibleCounts,
            @Nullable Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion,
            @Nullable FusedRegionTickSession.PendingChunkWork pendingChunks) {
        final StagedEntityWork previous = this.stagedByLevel.put(
                level,
                new StagedEntityWork(activations, workerEligibleCounts, chunksByRegion, pendingChunks));
        if (previous != null) {
            // Defensive: flush any unconsumed prior stage before replacing.
            this.earlyEntityFlushes.increment();
            this.dispatchUnifiedWave(level, previous, Map.of());
        }
    }

    @Nullable
    StagedEntityWork takeStagedWork(ServerLevel level) {
        return level == null ? null : this.stagedByLevel.remove(level);
    }

    /**
     * Flushes any staged chunk + entity work for {@code level} without a BE stage (e.g., before save,
     * unload, or end-of-tick when {@code tickBlockEntities} did not run).
     */
    public void flushStagedWork(ServerLevel level) {
        final StagedEntityWork staged = this.takeStagedWork(level);
        if (staged != null) {
            this.earlyEntityFlushes.increment();
            this.dispatchUnifiedWave(level, staged, Map.of());
        }
    }

    public void clearLevel(ServerLevel level) {
        if (level != null) {
            this.stagedByLevel.remove(level);
        }
    }

    /**
     * Dispatches the unified {@code Chunks -> Entities -> BlockEntities} wave for {@code level}, combining
     * any staged chunk/entity activations from this tick with {@code sandboxByRegion}.
     */
    void dispatchUnifiedLevelTick(
            ServerLevel level,
            Map<RegionState, List<RegionBlockEntityTickChunk>> sandboxByRegion) {
        final StagedEntityWork staged = this.takeStagedWork(level);
        this.dispatchUnifiedWave(level, staged, sandboxByRegion);
    }

    private void dispatchUnifiedWave(
            ServerLevel level,
            @Nullable StagedEntityWork staged,
            Map<RegionState, List<RegionBlockEntityTickChunk>> sandboxByRegion) {
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level)) {
            if (staged != null) {
                for (int i = 0, size = staged.activations().size(); i < size; ++i) {
                    if (staged.workerEligibleCounts()[i] > 0) {
                        this.engine.skippedEntityTicks.addAndGet(staged.workerEligibleCounts()[i]);
                    }
                }
                if (staged.chunksByRegion() != null) {
                    for (final List<RegionChunkTickEntry> c : staged.chunksByRegion().values()) {
                        this.engine.skippedChunkTicks.addAndGet(c.size());
                    }
                }
            }
            if (!sandboxByRegion.isEmpty()) {
                for (final List<RegionBlockEntityTickChunk> beChunks : sandboxByRegion.values()) {
                    this.engine.skippedBlockEntityTicks.addAndGet(
                            RegionBlockEntityTickCoordinator.countBlockEntityTickers(beChunks));
                }
            }
            return;
        }

        final Map<RegionState, RegionEntityTickActivation> activationByRegion = new IdentityHashMap<>();
        final Map<RegionState, Integer> eligibleCountByRegion = new IdentityHashMap<>();
        final Map<RegionState, List<RegionChunkTickEntry>> chunksByRegion = new IdentityHashMap<>();
        int randomTickSpeed = 0;

        if (staged != null) {
            final List<RegionEntityTickActivation> activations = staged.activations();
            final int[] counts = staged.workerEligibleCounts();
            for (int i = 0, size = activations.size(); i < size; ++i) {
                final RegionEntityTickActivation act = activations.get(i);
                activationByRegion.put(act.region(), act);
                eligibleCountByRegion.put(act.region(), counts[i]);
            }
            if (staged.chunksByRegion() != null) {
                chunksByRegion.putAll(staged.chunksByRegion());
            }
            if (staged.pendingChunks() != null) {
                randomTickSpeed = staged.pendingChunks().randomTickSpeed();
            }
        }

        final Set<RegionState> allRegions = new LinkedHashSet<>();
        allRegions.addAll(activationByRegion.keySet());
        allRegions.addAll(chunksByRegion.keySet());
        allRegions.addAll(sandboxByRegion.keySet());

        if (allRegions.isEmpty()) {
            return;
        }

        final long now = System.nanoTime();
        final long gameTime = level.getGameTime();
        final int rts = randomTickSpeed;
        final int perRegionBudget = Math.max(128, this.engine.deferredBlockUpdateBudget / Math.max(1, allRegions.size()));
        final boolean borderUpdates = this.engine.borderUpdatesEnabled();

        final List<RegionIndependentTickScheduler.AsyncBatchItem> asyncItems = new ArrayList<>(allRegions.size());
        final List<RegionEntityTickBatch> entityBatches = new ArrayList<>(allRegions.size());
        final List<Integer> entityCounts = new ArrayList<>(allRegions.size());
        final List<Integer> chunkCounts = new ArrayList<>(allRegions.size());

        for (final RegionState region : allRegions) {
            if (region == null || region.isDead()) {
                continue;
            }
            final RegionEntityTickActivation activation = activationByRegion.get(region);
            final int workerEligibleEntities = eligibleCountByRegion.getOrDefault(region, 0);
            final List<RegionChunkTickEntry> regionChunks = chunksByRegion.get(region);
            final List<RegionBlockEntityTickChunk> regionBeChunks = sandboxByRegion.get(region);

            final boolean hasChunks = regionChunks != null && !regionChunks.isEmpty();
            final boolean hasEntities = activation != null && workerEligibleEntities > 0;
            final boolean hasBlockEntities = regionBeChunks != null && !regionBeChunks.isEmpty();

            if (!hasChunks && !hasEntities && !hasBlockEntities) {
                continue;
            }

            final RegionEntityTickBatch regionBatch = new RegionEntityTickBatch(level, perRegionBudget);
            regionBatch.setAsyncMainThreadWorkSink(task -> this.engine.independentTickScheduler.enqueueCommit(level, task));
            final FusedRegionTickBatch regionFused = hasChunks
                    ? new FusedRegionTickBatch(level, rts, perRegionBudget)
                    : null;
            final RegionEntityTickBatch.Group group = regionBatch.registerGroup(region, List.of());
            final Queue<Runnable> sharedMutations = regionFused != null
                    ? regionFused.regionTickMutations()
                    : new ConcurrentLinkedQueue<>();
            final RandomSource beRandom = hasBlockEntities
                    ? RandomSource.create(level.getSeed() ^ gameTime ^ ((long) region.coordinate().regionX() << 32) ^ region.coordinate().regionZ())
                    : null;
            final Runnable entityBody = hasEntities
                    ? this.engine.entityTickCoordinator().selfDrivenEntityTickGroup(group, activation, false, true)
                    : null;

            final Runnable unifiedBody = this.engine.withRegionMailboxDelivery(level, region, () -> {
                // Stage 1: Region Chunks (random block/fluid ticks, ice/snow, lightning)
                if (regionFused != null) {
                    this.engine.entityTickCoordinator().runFusedChunkStage(
                            regionFused, region, group, regionChunks, rts, false);
                }
                // Stage 2: Region Entities (same worker thread, sees Stage 1 block writes)
                if (entityBody != null) {
                    entityBody.run();
                }
                // Stage 3: Region BlockEntities (same worker thread, sees Stage 2 entity/block mutations
                // and pending region entity additions via the shared group ownership scope)
                if (hasBlockEntities) {
                    try (final RegionEntityOwnershipScope beOwnership =
                            RegionEntityOwnershipScope.open(region.coordinate(), group)) {
                        this.engine.blockEntityTickCoordinator().runUnifiedRegionBlockEntities(
                                level, region, regionBeChunks, beRandom, sharedMutations, borderUpdates);
                    }
                }
            });

            final Runnable wrappedWork = regionBatch.wrapAsyncGroupWork(unifiedBody);
            final Runnable unifiedCommit = () -> {
                try {
                    if (!this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                        if (hasEntities || hasBlockEntities) {
                            this.engine.applyDeferredPortalTransitions(regionBatch);
                            this.engine.applyDeferredEntitySectionUpdates(regionBatch);
                            this.engine.applyDeferredEntityRemovals(regionBatch);
                            this.engine.applyDeferredEntityAdditions(regionBatch);
                        }
                        if (hasChunks || hasEntities || hasBlockEntities) {
                            this.engine.applyDeferredBlockUpdates(regionBatch);
                        }
                        if (regionFused != null) {
                            this.engine.entityTickCoordinator().applyFusedChunkTickLightning(
                                    level, regionFused.lightningStrikes());
                            for (final RegionChunkTickEntry fc : regionFused.drainFallbackChunks()) {
                                this.engine.tickChunkOnServerThread(level, fc, regionFused.randomTickSpeed());
                            }
                        }
                        if (!sharedMutations.isEmpty()) {
                            this.engine.applyRegionTickMutations(sharedMutations);
                        }
                        if (hasEntities || hasBlockEntities) {
                            this.engine.entityTickCoordinator().tickSelfDrivenFallbacks(regionBatch);
                            regionBatch.runDeferredServerTasks();
                            this.engine.applyDeferredEntityUpdates(regionBatch);
                        }
                    }
                } finally {
                    regionBatch.finish();
                }
            };

            // Advance the region clock when BlockEntities are part of the unified tick (or when no BEs exist
            // in the level pass) so each region advances its local tick counter once per unified wave.
            final boolean advanceClock = hasBlockEntities || sandboxByRegion.isEmpty();
            asyncItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                    region,
                    "unified region tick",
                    wrappedWork,
                    unifiedCommit,
                    advanceClock,
                    now,
                    gameTime));
            entityBatches.add(regionBatch);
            entityCounts.add(workerEligibleEntities);
            chunkCounts.add(hasChunks ? regionChunks.size() : 0);
        }

        if (!asyncItems.isEmpty()) {
            this.unifiedWavesDispatched.increment();
            final var outcomes = this.engine.independentTickScheduler.submitBatchOptimized(asyncItems);
            int submittedCount = 0;
            for (int i = 0; i < outcomes.length; ++i) {
                if (outcomes[i] == RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED) {
                    ++submittedCount;
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
            if (submittedCount > 0) {
                this.unifiedRegionsSubmitted.add(submittedCount);
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.unifiedWavesDispatched.sum(),
                this.unifiedRegionsSubmitted.sum(),
                this.earlyEntityFlushes.sum());
    }
}
