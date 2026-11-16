/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingDiagnostics;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionThreadingOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Observation contracts shared by the original engine and its extracted diagnostics collector. */
@Timeout(20)
class RegionThreadingDiagnosticsTest {
    private final RegionThreadingEngine engine = new RegionThreadingEngine(1, false, false);

    @AfterEach
    void closeEngine() {
        this.engine.shutdown();
    }

    @Test
    void diagnosticsReadsOriginalCountersFreshWithoutResettingThem() throws Exception {
        final AtomicLong entities = counter(this.engine, "entityTicksOnWorkers");
        final AtomicLong chunks = counter(this.engine, "chunkTicksOnWorkers");
        final AtomicLong messages = counter(this.engine, "mailboxAcceptedMessages");
        entities.set(11L);
        chunks.set(23L);
        messages.set(37L);
        final RegionThreadingDiagnostics first = this.engine.diagnostics();
        assertEquals(11L, first.entityTicksOnWorkers());
        assertEquals(23L, first.chunkTicksOnWorkers());
        assertEquals(37L, first.mailboxAcceptedMessages());

        entities.addAndGet(5L);
        chunks.addAndGet(7L);
        messages.addAndGet(9L);
        final RegionThreadingDiagnostics second = this.engine.diagnostics();
        assertEquals(16L, second.entityTicksOnWorkers());
        assertEquals(30L, second.chunkTicksOnWorkers());
        assertEquals(46L, second.mailboxAcceptedMessages());
        assertEquals(16L, entities.get());
        assertEquals(30L, chunks.get());
        assertEquals(46L, messages.get());
        assertEquals(11L, first.entityTicksOnWorkers(), "published observations are values, not live counters");
        assertEquals(23L, first.chunkTicksOnWorkers());
        assertEquals(37L, first.mailboxAcceptedMessages());
    }

    @Test
    void emptyAndRetiredRegionObservationsRemainImmutableValues() {
        final RegionThreadingDiagnostics empty = this.engine.diagnostics();
        assertTrue(empty.regionTicks().isEmpty());
        assertEquals(0, empty.regionMetrics().regionCount());
        assertThrows(UnsupportedOperationException.class, () -> empty.regionTicks().add(null));

        final ServerLevel level = level();
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, () -> {});
        final RegionThreadingDiagnostics queued = this.engine.diagnostics();
        assertEquals(1, queued.regionCount());
        assertEquals(1, queued.regionTicks().size());
        assertEquals(1, queued.regionMetrics().regionCount());
        assertThrows(UnsupportedOperationException.class, () -> queued.regionTicks().clear());
        this.engine.clearLevelRegionTasks(level);
        assertEquals(0, this.engine.diagnostics().regionCount());
        assertEquals(1, queued.regionTicks().size(), "retiring the level must not mutate an earlier observation");
        assertTrue(empty.regionTicks().isEmpty());
    }

    @Test
    void observationDoesNotExecuteOrDrainQueuedWork() {
        final ServerLevel level = level();
        final AtomicInteger runs = new AtomicInteger();
        this.engine.globalScheduler().execute(runs::incrementAndGet);
        this.engine.regionScheduler().execute(level, BlockPos.ZERO, runs::incrementAndGet);
        for (int index = 0; index < 5; ++index) {
            final RegionThreadingDiagnostics diagnostics = this.engine.diagnostics();
            assertEquals(1, diagnostics.regionCount());
            assertEquals(0, diagnostics.runningRegionCount());
            assertEquals(0, diagnostics.submittedRegionCount());
            assertEquals(0, runs.get());
        }
        this.engine.drainGlobalTasks();
        this.engine.drainLevelRegionTasks(level);
        assertEquals(2, runs.get());
    }

    @Test
    void diagnosticsObservesLiveStartupSpawnSelection() {
        assertFalse(this.engine.diagnostics().spawnWorkerExecutionEnabled());
        this.engine.configureStartupSpawnWorkers(true);
        assertTrue(this.engine.diagnostics().spawnWorkerExecutionEnabled());
        assertTrue(this.engine.diagnostics().spawnWorkerDispatchEffective());
        this.engine.configureStartupSpawnWorkers(false);
        assertFalse(this.engine.diagnostics().spawnWorkerExecutionEnabled());
        assertFalse(this.engine.diagnostics().spawnWorkerDispatchEffective());
    }

    @ParameterizedTest
    @MethodSource("aggregateMappings")
    void groupedSnapshotsPreserveEveryOriginalCounterMapping(String accessor, List<String> fields) throws Exception {
        for (int index = 0; index < fields.size(); ++index) {
            counter(this.engine, fields.get(index)).set(101L + index * 13L);
        }
        final Object first = RegionThreadingDiagnostics.class.getMethod(accessor).invoke(this.engine.diagnostics());
        final RecordComponent[] components = first.getClass().getRecordComponents();
        assertEquals(fields.size(), components.length);
        for (int index = 0; index < fields.size(); ++index) {
            assertEquals(101L + index * 13L, ((Number) components[index].getAccessor().invoke(first)).longValue(), fields.get(index));
            counter(this.engine, fields.get(index)).addAndGet(1000L);
        }
        final Object second = RegionThreadingDiagnostics.class.getMethod(accessor).invoke(this.engine.diagnostics());
        for (int index = 0; index < fields.size(); ++index) {
            assertEquals(1101L + index * 13L, ((Number) components[index].getAccessor().invoke(second)).longValue(), fields.get(index));
            assertEquals(1101L + index * 13L, counter(this.engine, fields.get(index)).get(), "observation must not reset a counter");
            assertEquals(101L + index * 13L, ((Number) components[index].getAccessor().invoke(first)).longValue(), "old grouped values remain detached");
        }
    }

    @Test
    void perSliceArraysAreFreshAndDetachedFromTheOriginalCounters() throws Exception {
        final String[] names = { "appliedDeferredBlockUpdates", "droppedDeferredBlockUpdates", "refusedDeferredBlockUpdates", "workerTickFailures" };
        final List<AtomicLong[]> counters = new java.util.ArrayList<>();
        for (int group = 0; group < names.length; ++group) {
            final AtomicLong[] values = (AtomicLong[]) field(this.engine, names[group]);
            counters.add(values);
            for (int index = 0; index < values.length; ++index) values[index].set(100L * (group + 1) + index);
        }
        final RegionThreadingDiagnostics first = this.engine.diagnostics();
        final List<long[]> arrays = List.of(first.deferredBlockUpdates().applied(), first.deferredBlockUpdates().dropped(),
                first.deferredBlockUpdates().refused(), first.tickFailures().recorded());
        for (int group = 0; group < arrays.size(); ++group) {
            for (int index = 0; index < arrays.get(group).length; ++index) {
                assertEquals(100L * (group + 1) + index, arrays.get(group)[index]);
            }
            // Existing record accessors expose their own arrays; preserve that API while never exposing atomics.
            arrays.get(group)[0] = -1L;
            assertEquals(100L * (group + 1), counters.get(group)[0].get());
            counters.get(group)[0].incrementAndGet();
        }
        final RegionThreadingDiagnostics second = this.engine.diagnostics();
        final List<long[]> refreshed = List.of(second.deferredBlockUpdates().applied(), second.deferredBlockUpdates().dropped(),
                second.deferredBlockUpdates().refused(), second.tickFailures().recorded());
        for (int group = 0; group < refreshed.size(); ++group) {
            assertNotSame(arrays.get(group), refreshed.get(group));
            assertEquals(100L * (group + 1) + 1, refreshed.get(group)[0]);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void dispatchAggregationReadsTheExistingPoolAndPackingCounters(boolean persistent) throws Exception {
        final var options = new RegionThreadingOptions(new RegionThreadingOptions.Workers(1, false, false, persistent, true),
                new RegionThreadingOptions.TickSlices(false, false, false, false, false),
                RegionLayout.fixed(3), RegionEntityStorageMode.MAIN_THREAD_ONLY);
        final RegionThreadingEngine other = new RegionThreadingEngine(options);
        try {
            final Object originalPool = field(other, "workerPool");
            counter(other, "packedTasks").set(17L);
            counter(other, "packedRegions").set(31L);
            counter(other, "packedLeaseFallbacks").set(5L);
            final var before = other.workerRuntimeSnapshot();
            final var dispatch = other.diagnostics().workerDispatch();
            assertEquals(persistent, dispatch.persistentWorkers());
            assertTrue(dispatch.packedRegionLeases());
            assertEquals(17L, dispatch.packedTasks());
            assertEquals(31L, dispatch.packedRegions());
            assertEquals(5L, dispatch.partialPackFallbacks());
            counter(other, "packedTasks").incrementAndGet();
            assertEquals(18L, other.diagnostics().workerDispatch().packedTasks());
            assertEquals(17L, dispatch.packedTasks());
            assertSame(originalPool, field(other, "workerPool"));
            final var after = other.workerRuntimeSnapshot();
            assertEquals(before.submitted(), after.submitted());
            assertEquals(before.completed(), after.completed());
        } finally {
            other.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void phaseAggregationReadsAllCurrentLevelsWithoutRetainingOrChangingThem() throws Exception {
        final Map<ServerLevel, Object> phases = (Map<ServerLevel, Object>) field(this.engine, "levelTickPhases");
        final Class<?> phaseType = Class.forName("net.neoforged.neoforge.server.threading.region.LevelTickPhaseState");
        final var constructor = phaseType.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Object first = constructor.newInstance();
        final Object second = constructor.newInstance();
        final String[] names = { "globalPhaseCount", "regionPhaseCount", "regionPhasesWithoutGlobalPhase", "abandonedGlobalSegments" };
        final long[][] counts = { { 11L, 17L, 23L, 31L }, { 37L, 41L, 43L, 47L } };
        for (int index = 0; index < names.length; ++index) {
            counter(first, names[index]).set(counts[0][index]);
            counter(second, names[index]).set(counts[1][index]);
        }
        phases.put(level(), first);
        phases.put(level(), second);
        final var observed = this.engine.diagnostics();
        assertEquals(48L, observed.globalPhases());
        assertEquals(58L, observed.regionPhases());
        assertEquals(66L, observed.regionPhasesWithoutGlobalPhase());
        assertEquals(78L, observed.abandonedGlobalSegments());
        for (int index = 0; index < names.length; ++index) {
            assertEquals(counts[0][index], counter(first, names[index]).get());
            assertEquals(counts[1][index], counter(second, names[index]).get());
        }
        phases.clear();
        final var retired = this.engine.diagnostics();
        assertEquals(0L, retired.globalPhases());
        assertEquals(0L, retired.regionPhases());
        assertEquals(0L, retired.regionPhasesWithoutGlobalPhase());
        assertEquals(0L, retired.abandonedGlobalSegments());
        assertEquals(48L, observed.globalPhases());
    }

    @Test
    void collectorBorrowsTheExactOriginalCounterAndArrayIdentities() throws Exception {
        final Object collector = field(this.engine, "diagnosticsCollector");
        for (final String group : List.of("border", "blockEvents", "gameEvents", "saves")) {
            final Object source = field(collector, group);
            for (final RecordComponent component : source.getClass().getRecordComponents()) {
                final var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertSame(counter(this.engine, component.getName()), accessor.invoke(source), component.getName());
            }
        }
        final String[][] arrays = {
                { "applied", "appliedDeferredBlockUpdates" },
                { "dropped", "droppedDeferredBlockUpdates" },
                { "refused", "refusedDeferredBlockUpdates" },
                { "failures", "workerTickFailures" }
        };
        for (final String[] pair : arrays) assertSame(field(this.engine, pair[1]), field(collector, pair[0]));
        for (final Field source : collector.getClass().getDeclaredFields()) {
            if (source.isSynthetic()) continue;
            final Class<?> type = source.getType();
            assertTrue(type == AtomicLong[].class || type.isRecord() && type.getEnclosingClass() == collector.getClass(),
                    () -> "collector must not retain a Level, phase owner, pool or independent cache: " + source);
        }
    }

    @Test
    void immutableDispatchInputPreservesEveryOutputField() throws Exception {
        final Class<?> inputType = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool$DispatchSnapshot");
        final RecordComponent[] inputFields = inputType.getRecordComponents();
        final Class<?>[] parameterTypes = new Class<?>[inputFields.length];
        final Object[] values = new Object[inputFields.length];
        final Map<String, Object> expected = new java.util.HashMap<>();
        for (int index = 0; index < inputFields.length; ++index) {
            assertEquals(long.class, inputFields[index].getType());
            parameterTypes[index] = long.class;
            values[index] = 101L + index * 13L;
            expected.put(inputFields[index].getName(), values[index]);
        }
        final var constructor = inputType.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        final Object dispatch = constructor.newInstance(values);
        final Class<?> collectorType = Class.forName("net.neoforged.neoforge.server.threading.region.RegionDiagnosticsCollector");
        final var aggregate = collectorType.getDeclaredMethod("workerDispatch", inputType, long.class, long.class, long.class, boolean.class, boolean.class);
        aggregate.setAccessible(true);
        final Object observed = aggregate.invoke(null, dispatch, 17L, 31L, 5L, true, false);
        expected.put("packedTasks", 17L);
        expected.put("packedRegions", 31L);
        expected.put("partialPackFallbacks", 5L);
        expected.put("persistentWorkers", true);
        expected.put("packedRegionLeases", false);
        for (final RecordComponent component : observed.getClass().getRecordComponents()) {
            assertTrue(expected.containsKey(component.getName()), component.getName());
            assertEquals(expected.get(component.getName()), component.getAccessor().invoke(observed), component.getName());
        }
    }

    private static Stream<Arguments> aggregateMappings() {
        return Stream.of(
                Arguments.of("borderUpdates", List.of(
                        "borderUpdatesRecorded",
                        "borderUpdatesApplied",
                        "borderUpdatesDelayed",
                        "borderUpdatesDropped",
                        "borderUpdatePasses",
                        "borderUpdateRegionsRerun",
                        "borderUpdateWorstPasses",
                        "borderUpdatesOverflowed",
                        "foreignChunkReads",
                        "readOfUnownedChunk",
                        "readOfVersionlessChunk",
                        "foreignReadInvalidatedPasses",
                        "foreignReadRegionReruns",
                        "foreignReadSeedsDropped",
                        "borderBlockWritesRecorded",
                        "borderBlockWritesApplied",
                        "borderBlockWritesOverflowed",
                        "borderBlockWritesDropped",
                        "readAfterDeferredBlockWrite")),
                Arguments.of("blockEventPhase", List.of(
                        "blockEventPhases",
                        "blockEventPhaseLeases",
                        "blockEventPhaseReleases",
                        "blockEventPhaseEvents",
                        "blockEventPhaseFixpoints",
                        "blockEventPhaseLeaseFallbacks",
                        "blockEventPhaseNanos",
                        "blockEventPhaseMaxNanos")),
                Arguments.of("gameEvents", List.of(
                        "gameEventsPosted",
                        "gameEventSectionsLocal",
                        "gameEventSectionsDeferred",
                        "gameEventSectionsDelivered",
                        "gameEventSectionsRefused",
                        "gameEventSectionsDropped",
                        "gameEventSectionsDroppedNoRegion",
                        "gameEventSectionsDroppedNoChunk",
                        "gameEventDeliveryPasses")),
                Arguments.of("saveCounters", List.of(
                        "levelSaves",
                        "levelSavesWithBatchInFlight",
                        "levelSavesWithTickDeferredWork",
                        "levelSavesWithCrossTickDeferredWork",
                        "levelSaveCrossTickDeferredEntries",
                        "levelSaveDirtyPoiColumns",
                        "levelSaveFlushedPoiColumns")));
    }

    private static ServerLevel level() {
        final ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        return level;
    }

    private static AtomicLong counter(Object owner, String name) throws Exception {
        return (AtomicLong) field(owner, name);
    }

    private static Object field(Object owner, String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
