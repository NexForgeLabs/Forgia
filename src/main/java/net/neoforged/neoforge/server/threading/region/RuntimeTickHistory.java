/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Arrays;
import org.jetbrains.annotations.ApiStatus;

/**
 * Server-thread-owned, bounded raw tick history. Recording allocates nothing and never sorts or scans.
 * Queries use monotonic wall time, not an assumed 20 TPS. At most 12,000 ticks (192 KiB) are retained;
 * accelerated servers explicitly report when that cap truncates a requested interval.
 */
@ApiStatus.Internal
public final class RuntimeTickHistory {
    public static final int CAPACITY = 12_000;
    private final long[] times = new long[CAPACITY];
    private final long[] durations = new long[CAPACITY];
    private int cursor;
    private int size;
    private long lifetimeTicks;
    private long lifetimeOverruns;
    private boolean overwritten;

    public void record(long now, long duration) {
        if (duration < 0) throw new IllegalArgumentException("Negative tick duration");
        if (this.size > 0 && now < this.times[(this.cursor + CAPACITY - 1) % CAPACITY])
            throw new IllegalArgumentException("Nonmonotonic tick timestamp");
        this.overwritten |= this.size == CAPACITY;
        this.times[this.cursor] = now;
        this.durations[this.cursor] = duration;
        this.cursor = (this.cursor + 1) % CAPACITY;
        this.size = Math.min(CAPACITY, this.size + 1);
        ++this.lifetimeTicks;
        if (duration > 50_000_000L) ++this.lifetimeOverruns;
    }

    /** Exact nearest-rank percentiles of recorded ticks completed inside the requested wall interval. */
    public Summary snapshot(long now, int seconds) {
        if (seconds < 1 || seconds > 300) throw new IllegalArgumentException("History duration must be 1..300 seconds");
        final long cutoff = now - seconds * 1_000_000_000L;
        final long[] values = new long[this.size];
        int count = 0;
        long sum = 0, first = 0, last = 0, lastDuration = 0, overruns = 0;
        final int oldest = (this.cursor + CAPACITY - this.size) % CAPACITY;
        for (int index = 0; index < this.size; ++index) {
            final int slot = (oldest + index) % CAPACITY;
            final long time = this.times[slot];
            if (time <= cutoff || time > now) continue;
            if (count == 0) first = time;
            last = time;
            lastDuration = this.durations[slot];
            values[count++] = lastDuration;
            sum += lastDuration;
            if (lastDuration > 50_000_000L) ++overruns;
        }
        Arrays.sort(values, 0, count);
        return new Summary(seconds, count, count == 0 ? Double.NaN : sum / (double) count / 1e6,
                percentile(values, count, 0.50), percentile(values, count, 0.95), percentile(values, count, 0.99),
                count == 0 ? Double.NaN : values[count - 1] / 1e6, count == 0 ? Double.NaN : lastDuration / 1e6,
                count < 2 || last <= first ? Double.NaN : (count - 1) * 1e9 / (last - first),
                overruns, this.lifetimeTicks, this.lifetimeOverruns,
                count == 0 ? 0 : (now - first) / 1e9,
                this.overwritten && this.size > 0 && this.times[oldest] > cutoff);
    }

    private static double percentile(long[] values, int size, double percentile) {
        return size == 0 ? Double.NaN : values[Math.max(0, (int) Math.ceil(size * percentile) - 1)] / 1e6;
    }

    public record Summary(int requestedSeconds, int samples, double averageMs, double p50Ms, double p95Ms,
            double p99Ms, double maxMs, double lastMs, double observedTps, long overruns,
            long lifetimeTicks, long lifetimeOverruns, double coverageSeconds, boolean capacityLimited) {}
}
