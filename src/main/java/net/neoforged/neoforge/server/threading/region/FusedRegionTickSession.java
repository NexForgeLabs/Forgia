/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-level staging and telemetry for P3 slice fusion ({@code enableRegionThreadingFusedSlices}).
 * <p>
 * Stages eligible {@link RegionTickSlice#CHUNK_TICK} entries until {@link RegionTickSlice#ENTITY_TICK}
 * runs in the same tick so both slices execute sequentially per region inside a single worker task,
 * a single barrier, and a single border-update fixpoint. Any intervening block event, level save,
 * level unload, shutdown, or end-of-tick flushes staged chunks fail-closed through the standard
 * chunk-tick coordinator.
 */
@ApiStatus.Internal
public final class FusedRegionTickSession {
    public static final long FOREIGN_READ_COOLDOWN_TICKS = 100L;

    public record PendingChunkWork(
            List<RegionChunkTickEntry> chunks,
            int randomTickSpeed,
            RegionTickBarrier barrier) {}

    public record Snapshot(
            long fusedBatches,
            long fusedRegions,
            long barriersSaved,
            long earlyFlushes,
            long budgetFallbacks,
            long foreignReadFallbacks,
            int activeCooldownRegions) {}

    private final Map<ServerLevel, PendingChunkWork> stagedChunks = new ConcurrentHashMap<>();
    private final Map<RegionCoordinate, Long> nonFusableUntilTick = new ConcurrentHashMap<>();
    private final LongAdder fusedBatches = new LongAdder();
    private final LongAdder fusedRegions = new LongAdder();
    private final LongAdder barriersSaved = new LongAdder();
    private final LongAdder earlyFlushes = new LongAdder();
    private final LongAdder budgetFallbacks = new LongAdder();
    private final LongAdder foreignReadFallbacks = new LongAdder();

    public FusedRegionTickSession() {}

    public void stageChunks(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed, RegionTickBarrier barrier) {
        final PendingChunkWork previous = this.stagedChunks.put(level, new PendingChunkWork(List.copyOf(chunks), randomTickSpeed, barrier));
        if (previous != null) {
            throw new IllegalStateException("Staged fused chunk work already pending for level " + level.dimension().location());
        }
    }

    @Nullable
    public PendingChunkWork takeStagedChunks(ServerLevel level) {
        return this.stagedChunks.remove(level);
    }

    public boolean hasStagedChunks(ServerLevel level) {
        return this.stagedChunks.containsKey(level);
    }

    public boolean isNonFusable(RegionCoordinate coordinate, long gameTime) {
        final Long until = this.nonFusableUntilTick.get(coordinate);
        if (until == null) {
            return false;
        }
        if (gameTime >= until) {
            this.nonFusableUntilTick.remove(coordinate, until);
            return false;
        }
        return true;
    }

    public void recordForeignReadInvalidation(RegionCoordinate coordinate, long gameTime) {
        this.nonFusableUntilTick.put(coordinate, gameTime + FOREIGN_READ_COOLDOWN_TICKS);
        this.foreignReadFallbacks.increment();
    }

    public void clearLevel(ServerLevel level) {
        this.stagedChunks.remove(level);
        this.nonFusableUntilTick.keySet().removeIf(coord -> coord.level() == level);
    }

    public void recordFusedBatch(int regionCount) {
        this.fusedBatches.increment();
        this.fusedRegions.add(regionCount);
        this.barriersSaved.increment();
    }

    public void recordEarlyFlush() {
        this.earlyFlushes.increment();
    }

    public void recordBudgetFallback() {
        this.budgetFallbacks.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                this.fusedBatches.sum(),
                this.fusedRegions.sum(),
                this.barriersSaved.sum(),
                this.earlyFlushes.sum(),
                this.budgetFallbacks.sum(),
                this.foreignReadFallbacks.sum(),
                this.nonFusableUntilTick.size());
    }
}
