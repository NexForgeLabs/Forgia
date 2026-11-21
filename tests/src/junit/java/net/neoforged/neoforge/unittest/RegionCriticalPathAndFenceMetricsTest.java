/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import net.neoforged.neoforge.server.threading.region.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RegionCriticalPathAndFenceMetricsTest {
    @Test
    void criticalPathTracksMaxMeanAndSlowestRegion() {
        final var metrics = new RegionSliceCriticalPathMetrics();
        // Batch 1: 4 regions, slowest is overworld@2,3 at 12 ms, sum = 20 ms, barrier = 13 ms
        metrics.recordBatch(RegionTickSlice.ENTITY_TICK, 13_000_000L, 12_000_000L, 20_000_000L, 4, "minecraft:overworld@2,3");
        // Batch 2: 4 regions, slowest is overworld@9,9 at 24 ms, sum = 32 ms, barrier = 25 ms
        metrics.recordBatch(RegionTickSlice.ENTITY_TICK, 25_000_000L, 24_000_000L, 32_000_000L, 4, "minecraft:overworld@9,9");

        final var snap = metrics.snapshot(RegionTickSlice.ENTITY_TICK);
        assertEquals(2L, snap.batches());
        assertEquals(18.0, snap.avgCriticalPathMs(), 1e-6);
        assertEquals(24.0, snap.maxCriticalPathMs(), 1e-6);
        assertEquals(6.5, snap.avgMeanRegionMs(), 1e-6);
        assertEquals(19.0, snap.avgBarrierWaitMs(), 1e-6);
        assertEquals("minecraft:overworld@9,9", snap.slowestRegionId());
    }

    @Test
    void fenceMetricsRanksByTotalTimeAndBoundsCapacity() {
        final var fences = new RegionFenceMetrics();
        fences.recordFence("minecraft:overworld", 0, 0, "mod:slow_pipe", 5_000_000L);
        fences.recordFence("minecraft:overworld", 0, 0, "mod:slow_pipe", 7_000_000L);
        fences.recordFence("minecraft:overworld", 1, 2, "mod:fast_sensor", 1_000_000L);

        final var snap = fences.snapshot(10);
        assertEquals(3L, snap.totalFences());
        assertEquals(13_000_000L, snap.totalNanos());
        assertEquals(0L, snap.overflowEntries());
        assertEquals(2, snap.topEntries().size());
        assertEquals("mod:slow_pipe", snap.topEntries().get(0).tickerClass());
        assertEquals(2L, snap.topEntries().get(0).count());
        assertEquals(12_000_000L, snap.topEntries().get(0).totalNanos());
        assertEquals(7_000_000L, snap.topEntries().get(0).maxNanos());

        // Fill beyond MAX_ENTRIES and verify overflow is counted instead of growing unbounded
        for (int i = 0; i < RegionFenceMetrics.MAX_ENTRIES + 20; ++i) {
            fences.recordFence("minecraft:overworld", i, 0, "mod:ticker_" + i, 100L);
        }
        final var fullSnap = fences.snapshot(1000);
        assertEquals(RegionFenceMetrics.MAX_ENTRIES, fullSnap.topEntries().size());
        assertEquals(22L, fullSnap.overflowEntries());
    }
}
