/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lifetime C7-only counters. C6 diagnostic objects are never read or mutated. */
public final class C7Metrics {
    final LongAdder phases = new LongAdder();
    final LongAdder collected = new LongAdder();
    final LongAdder parallel = new LongAdder();
    final LongAdder serial = new LongAdder();
    final LongAdder skipped = new LongAdder();
    final LongAdder dependencies = new LongAdder();
    final LongAdder graphNanos = new LongAdder();
    final LongAdder waves = new LongAdder();
    final LongAdder waveTasks = new LongAdder();
    final AtomicLong maxWave = new AtomicLong();
    final AtomicLong maxConcurrent = new AtomicLong();
    final LongAdder workerNanos = new LongAdder();
    final LongAdder workerCapacityNanos = new LongAdder();
    final LongAdder conflicts = new LongAdder();
    final LongAdder dynamic = new LongAdder();
    final LongAdder demotions = new LongAdder();
    final LongAdder schedulingNanos = new LongAdder();
    final LongAdder executionNanos = new LongAdder();
    final LongAdder preparationNanos = new LongAdder();
    final LongAdder commitNanos = new LongAdder();
    final LongAdder failed = new LongAdder();
    final LongAdder rejected = new LongAdder();
    final LongAdder invalidated = new LongAdder();
    final LongAdder rejectedPlans = new LongAdder();
    final LongAdder phaseNanos = new LongAdder();
    final LongAdder collectionNanos = new LongAdder();
    final LongAdder proofNanos = new LongAdder();
    private final java.util.Map<C7Refusal, LongAdder> refusals = new java.util.EnumMap<>(C7Refusal.class);
    private final java.util.Map<C7Resource.Kind, LongAdder> undeclaredKinds = new java.util.EnumMap<>(C7Resource.Kind.class);

    public C7Metrics() {
        for (final var reason : C7Refusal.values()) this.refusals.put(reason, new LongAdder());
        for (final var kind : C7Resource.Kind.values()) this.undeclaredKinds.put(kind, new LongAdder());
    }

    void refusal(C7Refusal reason) {
        this.refusals.get(reason).increment();
    }

    void undeclared(C7Resource resource) {
        this.undeclaredKinds.get(resource.kind()).increment();
    }

    private static <K> java.util.Map<K, Long> counts(java.util.Map<K, LongAdder> counters) {
        final var result = new java.util.LinkedHashMap<K, Long>();
        counters.forEach((key, value) -> {
            if (value.sum() != 0) result.put(key, value.sum());
        });
        return java.util.Map.copyOf(result);
    }

    private final java.util.concurrent.ConcurrentHashMap<String, TypeCounters> types = new java.util.concurrent.ConcurrentHashMap<>();

    static final class TypeCounters {
        final LongAdder collected = new LongAdder();
        final LongAdder parallel = new LongAdder();
        final LongAdder serial = new LongAdder();
        final LongAdder skipped = new LongAdder();
        final LongAdder failed = new LongAdder();
        final java.util.Map<C7Refusal, LongAdder> serialReasons = new java.util.EnumMap<>(C7Refusal.class);
        final java.util.Map<C7Refusal, LongAdder> proofRefusals = new java.util.EnumMap<>(C7Refusal.class);

        TypeCounters() {
            for (final var reason : C7Refusal.values()) {
                this.serialReasons.put(reason, new LongAdder());
                this.proofRefusals.put(reason, new LongAdder());
            }
        }

        TypeSnapshot snapshot() {
            return new TypeSnapshot(this.collected.sum(), this.parallel.sum(), this.serial.sum(), this.skipped.sum(), this.failed.sum(),
                    counts(this.serialReasons), counts(this.proofRefusals));
        }
    }

    TypeCounters type(String type) {
        final var existing = this.types.get(type);
        if (existing != null) return existing;
        synchronized (this.types) {
            // One bounded overflow bucket, including hostile/unbounded diagnostic labels.
            return this.types.computeIfAbsent(this.types.size() >= 127 ? "<other>" : type, key -> new TypeCounters());
        }
    }

    void refusal(String type, C7Refusal reason) {
        this.refusal(reason);
        this.type(type).proofRefusals.get(reason).increment();
    }

    private java.util.Map<String, TypeSnapshot> typeSnapshots() {
        final var result = new java.util.TreeMap<String, TypeSnapshot>();
        this.types.forEach((type, counters) -> result.put(type, counters.snapshot()));
        return java.util.Collections.unmodifiableMap(result);
    }

    public record TypeSnapshot(long collected, long parallel, long serial, long skipped, long failed,
            java.util.Map<C7Refusal, Long> serialReasons, java.util.Map<C7Refusal, Long> proofRefusals) {}

    public record Snapshot(long phases, long collected, long parallel, long serial, long skipped,
            long dependencies, long graphNanos, long waves, long waveTasks, long maxWave, long maxConcurrent,
            long workerNanos, long workerCapacityNanos, long conflicts, long dynamicDiscoveries, long demotions,
            long schedulingNanos, long executionNanos, long preparationNanos, long commitNanos, long failed,
            long rejected, long invalidated, long rejectedPlans, long phaseNanos, long collectionNanos, long proofNanos,
            java.util.Map<C7Refusal, Long> proofRefusals, java.util.Map<C7Resource.Kind, Long> undeclaredKinds,
            java.util.Map<String, TypeSnapshot> types) {
        public double averageWaveSize() {
            return this.waves == 0 ? 0 : (double) this.waveTasks / this.waves;
        }

        /** Compute wall-time occupancy, NOT CPU utilization. Queueing and wait reduce this ratio. */
        public double workerUtilization() {
            return this.workerCapacityNanos == 0 ? 0 : (double) this.workerNanos / this.workerCapacityNanos;
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(this.phases.sum(), this.collected.sum(), this.parallel.sum(), this.serial.sum(),
                this.skipped.sum(), this.dependencies.sum(), this.graphNanos.sum(), this.waves.sum(),
                this.waveTasks.sum(), this.maxWave.get(), this.maxConcurrent.get(), this.workerNanos.sum(),
                this.workerCapacityNanos.sum(), this.conflicts.sum(), this.dynamic.sum(), this.demotions.sum(),
                this.schedulingNanos.sum(), this.executionNanos.sum(), this.preparationNanos.sum(), this.commitNanos.sum(),
                this.failed.sum(), this.rejected.sum(), this.invalidated.sum(), this.rejectedPlans.sum(),
                this.phaseNanos.sum(), this.collectionNanos.sum(), this.proofNanos.sum(), counts(this.refusals), counts(this.undeclaredKinds), this.typeSnapshots());
    }
}
