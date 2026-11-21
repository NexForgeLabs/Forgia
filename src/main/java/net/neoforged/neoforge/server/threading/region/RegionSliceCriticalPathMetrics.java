/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-slice critical-path attribution: tracks the slowest region ({@code max_r t(r, s)}), mean region duration,
 * barrier wait time, and aggregate worker busy time across batches so bottleneck diagnostics can pinpoint which
 * slice and region dominate tick latency.
 */
@ApiStatus.Internal
public final class RegionSliceCriticalPathMetrics {
    public record SliceSnapshot(
            RegionTickSlice slice,
            long batches,
            double avgCriticalPathMs,
            double maxCriticalPathMs,
            double avgMeanRegionMs,
            double avgBarrierWaitMs,
            double avgWorkerBusyMs,
            @Nullable String slowestRegionId,
            double slowestRegionMaxMs) {}

    private static final class SliceCounters {
        final LongAdder batches = new LongAdder();
        final LongAdder totalCriticalPathNanos = new LongAdder();
        final LongAdder totalMeanRegionNanos = new LongAdder();
        final LongAdder totalBarrierWaitNanos = new LongAdder();
        final LongAdder totalWorkerBusyNanos = new LongAdder();
        final AtomicLong maxCriticalPathNanos = new AtomicLong();
        final AtomicReference<String> slowestRegionId = new AtomicReference<>();

        void record(long barrierWaitNanos, long maxGroupNanos, long sumGroupNanos, int groupCount, @Nullable String slowestRegion) {
            this.batches.increment();
            final long clampedBarrier = Math.max(0L, barrierWaitNanos);
            final long clampedMax = Math.max(0L, maxGroupNanos);
            final long clampedSum = Math.max(0L, sumGroupNanos);
            this.totalBarrierWaitNanos.add(clampedBarrier);
            this.totalCriticalPathNanos.add(clampedMax);
            this.totalWorkerBusyNanos.add(clampedSum);
            if (groupCount > 0) {
                this.totalMeanRegionNanos.add(clampedSum / groupCount);
            }
            if (clampedMax > 0L) {
                long prev;
                do {
                    prev = this.maxCriticalPathNanos.get();
                    if (clampedMax <= prev) {
                        break;
                    }
                } while (!this.maxCriticalPathNanos.compareAndSet(prev, clampedMax));
                if (clampedMax > prev && slowestRegion != null) {
                    this.slowestRegionId.set(slowestRegion);
                } else if (slowestRegion != null && this.slowestRegionId.get() == null) {
                    this.slowestRegionId.compareAndSet(null, slowestRegion);
                }
            }
        }

        SliceSnapshot snapshot(RegionTickSlice slice) {
            final long batchCount = this.batches.sum();
            final long maxNanos = this.maxCriticalPathNanos.get();
            if (batchCount <= 0L) {
                return new SliceSnapshot(slice, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, null, 0.0);
            }
            final double denom = batchCount * 1_000_000.0;
            return new SliceSnapshot(
                    slice,
                    batchCount,
                    this.totalCriticalPathNanos.sum() / denom,
                    maxNanos / 1_000_000.0,
                    this.totalMeanRegionNanos.sum() / denom,
                    this.totalBarrierWaitNanos.sum() / denom,
                    this.totalWorkerBusyNanos.sum() / denom,
                    this.slowestRegionId.get(),
                    maxNanos / 1_000_000.0);
        }
    }

    private final SliceCounters[] slices;

    public RegionSliceCriticalPathMetrics() {
        final RegionTickSlice[] values = RegionTickSlice.values();
        this.slices = new SliceCounters[values.length];
        for (int i = 0; i < values.length; ++i) {
            this.slices[i] = new SliceCounters();
        }
    }

    public void recordBatch(RegionTickSlice slice, long barrierWaitNanos, long maxGroupNanos, long sumGroupNanos,
            int groupCount, @Nullable String slowestRegionId) {
        this.slices[slice.ordinal()].record(barrierWaitNanos, maxGroupNanos, sumGroupNanos, groupCount, slowestRegionId);
    }

    public List<SliceSnapshot> snapshots() {
        final RegionTickSlice[] values = RegionTickSlice.values();
        final List<SliceSnapshot> out = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; ++i) {
            out.add(this.slices[i].snapshot(values[i]));
        }
        return out;
    }

    public SliceSnapshot snapshot(RegionTickSlice slice) {
        return this.slices[slice.ordinal()].snapshot(slice);
    }
}
