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

    public enum Reason {
        CACHE_NULL,
        DEAD_REGION,
        NEAR_ENTITY,
        TRANSIENT_COOLDOWN,
        NON_SANDBOXED,
        CANONICAL_SERIAL,
        OTHER
    }

    public record Entry(
            String dimension,
            int regionX,
            int regionZ,
            String tickerClass,
            long count,
            long totalNanos,
            long maxNanos,
            Reason dominantReason) {
        public Entry(
                String dimension,
                int regionX,
                int regionZ,
                String tickerClass,
                long count,
                long totalNanos,
                long maxNanos) {
            this(dimension, regionX, regionZ, tickerClass, count, totalNanos, maxNanos, Reason.OTHER);
        }

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

    private static final class ImmutableKey {
        final String dimension;
        final int regionX;
        final int regionZ;
        final String tickerClass;
        final int hash;

        ImmutableKey(String dimension, int regionX, int regionZ, String tickerClass) {
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.tickerClass = tickerClass;
            this.hash = computeHash(dimension, regionX, regionZ, tickerClass);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof ImmutableKey other) {
                return this.hash == other.hash
                        && this.regionX == other.regionX
                        && this.regionZ == other.regionZ
                        && this.dimension.equals(other.dimension)
                        && this.tickerClass.equals(other.tickerClass);
            }
            if (obj instanceof LookupKey other) {
                return this.hash == other.hash
                        && this.regionX == other.regionX
                        && this.regionZ == other.regionZ
                        && this.dimension.equals(other.dimension)
                        && this.tickerClass.equals(other.tickerClass);
            }
            return false;
        }
    }

    private static final class LookupKey {
        String dimension;
        int regionX;
        int regionZ;
        String tickerClass;
        int hash;

        LookupKey set(String dimension, int regionX, int regionZ, String tickerClass) {
            this.dimension = dimension;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.tickerClass = tickerClass;
            this.hash = computeHash(dimension, regionX, regionZ, tickerClass);
            return this;
        }

        void clear() {
            this.dimension = null;
            this.tickerClass = null;
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof ImmutableKey other) {
                return this.hash == other.hash
                        && this.regionX == other.regionX
                        && this.regionZ == other.regionZ
                        && this.dimension.equals(other.dimension)
                        && this.tickerClass.equals(other.tickerClass);
            }
            if (obj instanceof LookupKey other) {
                return this.hash == other.hash
                        && this.regionX == other.regionX
                        && this.regionZ == other.regionZ
                        && this.dimension.equals(other.dimension)
                        && this.tickerClass.equals(other.tickerClass);
            }
            return false;
        }
    }

    private static int computeHash(String dimension, int regionX, int regionZ, String tickerClass) {
        int h = dimension.hashCode();
        h = 31 * h + regionX;
        h = 31 * h + regionZ;
        h = 31 * h + tickerClass.hashCode();
        return h;
    }

    private static final class Counter {
        private static final Reason[] REASONS = Reason.values();
        final LongAdder count = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();
        final java.util.concurrent.atomic.AtomicLongArray reasonCounts =
                new java.util.concurrent.atomic.AtomicLongArray(REASONS.length);

        void record(long nanos, Reason reason) {
            final long clamped = Math.max(0L, nanos);
            this.count.increment();
            this.totalNanos.add(clamped);
            this.reasonCounts.incrementAndGet((reason == null ? Reason.OTHER : reason).ordinal());
            long prev;
            do {
                prev = this.maxNanos.get();
                if (clamped <= prev) {
                    break;
                }
            } while (!this.maxNanos.compareAndSet(prev, clamped));
        }

        Reason dominantReason() {
            Reason best = Reason.OTHER;
            long bestCount = -1L;
            for (int i = 0; i < REASONS.length; ++i) {
                final long c = this.reasonCounts.get(i);
                if (c > bestCount) {
                    bestCount = c;
                    best = REASONS[i];
                }
            }
            return bestCount > 0L ? best : Reason.OTHER;
        }
    }

    private final ConcurrentHashMap<Object, Counter> entries = new ConcurrentHashMap<>();
    private final ThreadLocal<LookupKey> lookupKey = ThreadLocal.withInitial(LookupKey::new);
    private final LongAdder totalFences = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder overflowEntries = new LongAdder();

    public void recordFence(String dimension, int regionX, int regionZ, String tickerClass, long nanos) {
        this.recordFence(dimension, regionX, regionZ, tickerClass, nanos, Reason.CANONICAL_SERIAL);
    }

    public void recordFence(String dimension, int regionX, int regionZ, String tickerClass, long nanos, Reason reason) {
        final long clamped = Math.max(0L, nanos);
        this.totalFences.increment();
        this.totalNanos.add(clamped);
        final LookupKey lookup = this.lookupKey.get().set(dimension, regionX, regionZ, tickerClass);
        Counter counter = this.entries.get(lookup);
        lookup.clear();
        if (counter == null) {
            if (this.entries.size() >= MAX_ENTRIES) {
                this.overflowEntries.increment();
                return;
            }
            final ImmutableKey immutable = new ImmutableKey(dimension, regionX, regionZ, tickerClass);
            counter = this.entries.computeIfAbsent(immutable, ignored -> new Counter());
        }
        counter.record(clamped, reason);
    }

    public Snapshot snapshot(int limit) {
        final int clampedLimit = Math.max(0, limit);
        final List<Entry> all = new ArrayList<>(this.entries.size());
        for (final var pair : this.entries.entrySet()) {
            final ImmutableKey k = (ImmutableKey) pair.getKey();
            final Counter c = pair.getValue();
            all.add(new Entry(k.dimension, k.regionX, k.regionZ, k.tickerClass,
                    c.count.sum(), c.totalNanos.sum(), c.maxNanos.get(), c.dominantReason()));
        }
        all.sort(Comparator.comparingLong(Entry::totalNanos).reversed()
                .thenComparing(Comparator.comparingLong(Entry::count).reversed())
                .thenComparing(Entry::tickerClass));
        final List<Entry> top = all.size() <= clampedLimit ? all : new ArrayList<>(all.subList(0, clampedLimit));
        return new Snapshot(this.totalFences.sum(), this.totalNanos.sum(), this.overflowEntries.sum(), top);
    }
}
