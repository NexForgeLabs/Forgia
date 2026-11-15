/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest.bench;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import net.neoforged.neoforge.server.threading.region.RegionTickMetrics;
import net.neoforged.neoforge.server.threading.region.RuntimeTickHistory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Explicit timing/allocation harness, excluded from correctness tests; no machine-dependent pass target. */
@Tag("benchmark")
class RuntimeObservabilityBenchmark {
    private static volatile long sink;

    @Test
    void boundedHistoryAndRegionRecordingOverhead() {
        final var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        final int ticks = 2_000_000;
        for (int repeat = 0; repeat < 5; ++repeat) {
            for (final boolean enabled : new boolean[] { false, true }) {
                final var history = new RuntimeTickHistory();
                final long beforeAllocation = bean.getCurrentThreadAllocatedBytes();
                final long start = System.nanoTime();
                long checksum = 0;
                for (int tick = 0; tick < ticks; ++tick) {
                    if (enabled) history.record(tick * 50_000_000L, tick % 100 * 1_000_000L);
                    checksum += tick;
                }
                sink = checksum;
                final long elapsed = System.nanoTime() - start;
                final long bytes = bean.getCurrentThreadAllocatedBytes() - beforeAllocation;
                System.out.printf(Locale.ROOT, "MONITOR_HISTORY repeat=%d enabled=%s nsPerTick=%.3f allocatedBytes=%d samples=%d%n",
                        repeat, enabled, elapsed / (double) ticks, bytes, history.snapshot((ticks - 1L) * 50_000_000L, 300).samples());
            }
        }
        final var region = new RegionTickMetrics();
        for (int tick = 0; tick < 100_000; ++tick) { region.beginTick(tick, tick * 50_000_000L); region.recordTick(1_000_000L); }
        final long allocatedBefore = bean.getCurrentThreadAllocatedBytes();
        final long start = System.nanoTime();
        for (int tick = 100_000; tick < 100_000 + ticks; ++tick) {
            region.beginTick(tick, tick * 50_000_000L);
            region.recordTick(1_000_000L);
        }
        final long elapsed = System.nanoTime() - start;
        final long allocated = bean.getCurrentThreadAllocatedBytes() - allocatedBefore;
        System.out.printf(Locale.ROOT, "MONITOR_REGION nsPerTick=%.3f allocatedBytes=%d%n",
                elapsed / (double) ticks, allocated);
        region.release();
    }
}
