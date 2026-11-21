/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jetbrains.annotations.ApiStatus;

/**
 * Bounded per-(dimension, region, ticker-class) accounting for canonical block-entity serial fences.
 * Used by {@code /forgia fences} to identify which modded tickers fragment parallel BE waves.
 */
@ApiStatus.Internal
public final class RegionFenceMetrics {
    public static final int MAX_ENTRIES = 512;

    public record Entry(
            String dimension,
            int regionX,
            int regionZ,
            String tickerClass,
            long count,
            long totalNanos,
            long maxNanos) {
        public double totalMs() {
            return this.totalNanos / 1_000_000.0;
        }

        public double maxMs() {
            return this.maxNanos / 1_000_000.0;
        }

        public String regionId() {
            return this.dimension + "@" + this.regionX + "," + this.regionZ;
        }
    }

    public record Snapshot(
            long totalFences,
            long totalNanos,
            long overflowEntries,
            List<Entry> topEntries) {}

    private record Key(String dimension, int regionX, int regionZ, String tickerClass) {}

    private static final class Counter {
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();

        void record(long nanos) {
            final long clamped = Math.max(0L, nanos);
            this.count.increment();
            this.totalNanos.add(clamped);
            long prev;
            do {
                prev = this.maxNanos.get();
                if (clamped <= prev) {
                    break;
                }
            } while (!this.maxNanos.compareAndSet(prev, clamped));
        }
    }

    private final ConcurrentHashMap<Key, Counter> entries = new ConcurrentHashMap<>();
    private final LongAdder totalFences = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder overflowEntries = new LongAdder();

    public void recordFence(String dimension, int regionX, int regionZ, String tickerClass, long nanos) {
        final long clamped = Math.max(0L, nanos);
        this.totalFences.increment();
        this.totalNanos.add(clamped);
        final Key key = new Key(dimension, regionX, regionZ, tickerClass);
        Counter counter = this.entries.get(key);
        if (counter == null) {
            if (this.entries.size() >= MAX_ENTRIES) {
                this.overflowEntries.increment();
                return;
            }
            counter = this.entries.computeIfAbsent(key, ignored -> new Counter());
        }
        counter.record(clamped);
    }

    public Snapshot snapshot(int limit) {
        final int clampedLimit = Math.max(0, limit);
        final List<Entry> all = new ArrayList<>(this.entries.size());
        for (final var pair : this.entries.entrySet()) {
            final Key k = pair.getKey();
            final Counter c = pair.getValue();
            all.add(new Entry(k.dimension(), k.regionX(), k.regionZ(), k.tickerClass(),
                    c.count.sum(), c.totalNanos.sum(), c.maxNanos.get()));
        }
        all.sort(Comparator.comparingLong(Entry::totalNanos).reversed()
                .thenComparing(Comparator.comparingLong(Entry::count).reversed())
                .thenComparing(Entry::tickerClass));
        final List<Entry> top = all.size() <= clampedLimit ? all : new ArrayList<>(all.subList(0, clampedLimit));
        return new Snapshot(this.totalFences.sum(), this.totalNanos.sum(), this.overflowEntries.sum(), top);
    }
}
