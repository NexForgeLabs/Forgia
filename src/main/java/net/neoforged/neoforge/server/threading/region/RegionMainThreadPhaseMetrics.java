/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;
import org.jetbrains.annotations.ApiStatus;

/**
 * Zero-allocation main-thread phase timing breakdown for {@code /forgia bottleneck}.
 * Attributes the non-worker serial wall time across BE serial execution, entity handoff,
 * entity fallbacks, border fixpoint, chunk main-thread work, and spawn main-thread work.
 */
@ApiStatus.Internal
public final class RegionMainThreadPhaseMetrics {
    public enum Phase {
        BE_SERIAL("beSerial"),
        ENTITY_HANDOFF("entityHandoff"),
        ENTITY_FALLBACKS("entityFallbacks"),
        BORDER_FIXPOINT("borderFixpoint"),
        CHUNK_MAIN("chunkMain"),
        SPAWN_MAIN("spawnMain");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public record PhaseSnapshot(
            Phase phase,
            long calls,
            long totalNanos,
            long maxNanos) {
        public double totalMs() {
            return this.totalNanos / 1_000_000.0;
        }

        public double avgPerCallMs() {
            return this.calls > 0L ? (this.totalNanos / 1_000_000.0) / this.calls : 0.0;
        }

        public double maxMs() {
            return this.maxNanos / 1_000_000.0;
        }
    }

    private static final int COUNT = Phase.values().length;
    private final AtomicLongArray calls = new AtomicLongArray(COUNT);
    private final AtomicLongArray totalNanos = new AtomicLongArray(COUNT);
    private final AtomicLongArray maxNanos = new AtomicLongArray(COUNT);

    public RegionMainThreadPhaseMetrics() {}

    public void record(Phase phase, long nanos) {
        final int idx = phase.ordinal();
        final long clamped = Math.max(0L, nanos);
        this.calls.incrementAndGet(idx);
        this.totalNanos.addAndGet(idx, clamped);
        long prev;
        do {
            prev = this.maxNanos.get(idx);
            if (clamped <= prev) {
                break;
            }
        } while (!this.maxNanos.compareAndSet(idx, prev, clamped));
    }

    public List<PhaseSnapshot> snapshots() {
        final Phase[] phases = Phase.values();
        final List<PhaseSnapshot> result = new ArrayList<>(COUNT);
        for (int i = 0; i < COUNT; ++i) {
            final long c = this.calls.get(i);
            if (c > 0L) {
                result.add(new PhaseSnapshot(phases[i], c, this.totalNanos.get(i), this.maxNanos.get(i)));
            }
        }
        return result;
    }
}
