/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.stream.IntStream;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionThreadingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(20)
class RegionThreadingOptionsTest {
    @ParameterizedTest
    @MethodSource("selections")
    void groupedAndLegacyConstructorsApplyEverySelectionIndependently(int selection) throws Exception {
        final var workers = new RegionThreadingOptions.Workers(2, selected(selection, 0), selected(selection, 1),
                selected(selection, 2), selected(selection, 3));
        final var ticks = new RegionThreadingOptions.TickSlices(selected(selection, 4), selected(selection, 5),
                selected(selection, 6), selected(selection, 7), selected(selection, 8));
        final RegionLayout layout = RegionLayout.fixed(5);
        final RegionEntityStorageMode storage = RegionEntityStorageMode.REGION_OWNED;
        final var options = new RegionThreadingOptions(workers, ticks, layout, storage);
        final RegionThreadingEngine grouped = new RegionThreadingEngine(options);
        try {
            final RegionThreadingEngine legacy = new RegionThreadingEngine(workers.threads(), workers.regionTasks(), workers.globalTasks(),
                    ticks.entities(), ticks.blockEntities(), ticks.scheduledTicks(), ticks.chunkTicks(), ticks.spawning(),
                    workers.persistent(), workers.packedRegionLeases(), layout, storage);
            try {
                assertApplied(grouped, options);
                assertApplied(legacy, options);
            } finally {
                legacy.shutdown();
            }
        } finally {
            grouped.shutdown();
        }
    }

    @Test
    void legacyShortConstructorKeepsItsOriginalDefaults() throws Exception {
        final RegionThreadingEngine engine = new RegionThreadingEngine(1, false, false);
        try {
            assertEquals(RegionPolicy.DYNAMIC, engine.regionPolicy());
            assertSame(RegionEntityStorageMode.MAIN_THREAD_ONLY, field(engine, "entityStorageMode"));
            for (final String name : new String[] { "entityTickWorkerExecutionEnabled", "blockEntityTickWorkerExecutionEnabled",
                    "scheduledTickWorkerExecutionEnabled", "chunkTickWorkerExecutionEnabled", "spawnWorkerExecutionEnabled",
                    "packedRegionLeasesEnabled" }) {
                assertEquals(false, field(engine, name), name);
            }
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void startupSpawnAdmissionCanStillNarrowTheInitialSelection() throws Exception {
        final var options = new RegionThreadingOptions(new RegionThreadingOptions.Workers(1, false, false, false, false),
                new RegionThreadingOptions.TickSlices(false, false, false, false, true),
                RegionLayout.fixed(3), RegionEntityStorageMode.REGION_OWNED);
        final RegionThreadingEngine engine = new RegionThreadingEngine(options);
        try {
            engine.configureStartupSpawnWorkers(false);
            assertEquals(false, field(engine, "spawnWorkerExecutionEnabled"));
            assertTrue(options.ticks().spawning(), "the initial value is not a cache of live engine state");
            engine.configureStartupSpawnWorkers(true);
            assertEquals(true, field(engine, "spawnWorkerExecutionEnabled"));
            engine.shutdown();
            assertThrows(IllegalStateException.class, () -> engine.configureStartupSpawnWorkers(false));
        } finally {
            engine.shutdown();
        }
    }

    private static IntStream selections() {
        return IntStream.concat(IntStream.of(0, (1 << 9) - 1), IntStream.range(0, 9).map(bit -> 1 << bit));
    }

    private static boolean selected(int selection, int bit) {
        return (selection & (1 << bit)) != 0;
    }

    private static void assertApplied(RegionThreadingEngine engine, RegionThreadingOptions options) throws Exception {
        assertSame(options.layout(), engine.regionLayout());
        assertSame(options.entityStorageMode(), field(engine, "entityStorageMode"));
        assertEquals(options.workers().regionTasks(), field(engine, "workerExecutionEnabled"));
        assertEquals(options.workers().globalTasks(), field(engine, "globalWorkerExecutionEnabled"));
        assertEquals(options.workers().packedRegionLeases(), field(engine, "packedRegionLeasesEnabled"));
        assertEquals(options.ticks().entities(), field(engine, "entityTickWorkerExecutionEnabled"));
        assertEquals(options.ticks().blockEntities(), field(engine, "blockEntityTickWorkerExecutionEnabled"));
        assertEquals(options.ticks().scheduledTicks(), field(engine, "scheduledTickWorkerExecutionEnabled"));
        assertEquals(options.ticks().chunkTicks(), field(engine, "chunkTickWorkerExecutionEnabled"));
        assertEquals(options.ticks().spawning(), field(engine, "spawnWorkerExecutionEnabled"));
        final Object pool = field(engine, "workerPool");
        assertEquals(2, field(pool, "threadCount"));
        assertEquals(options.workers().persistent(), field(pool, "workers") != null);
        assertFalse((Boolean) field(engine, "shutdown"));
    }

    private static Object field(Object owner, String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
