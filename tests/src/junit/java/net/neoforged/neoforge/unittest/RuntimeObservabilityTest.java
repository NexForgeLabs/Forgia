/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.command.ForgiaCommand;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBottleneckAnalysis;
import net.neoforged.neoforge.server.threading.region.RegionTickSnapshot;
import net.neoforged.neoforge.server.threading.region.RegionTickStatus;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RuntimeObservability;
import net.neoforged.neoforge.server.threading.region.RuntimeRegionSnapshot;
import net.neoforged.neoforge.server.threading.region.RuntimeTickHistory;
import net.neoforged.neoforge.server.threading.region.WorkerRuntimeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class RuntimeObservabilityTest {
    @AfterEach
    void clearEngine() {
        RegionThreadingManager.clear();
    }

    @Test
    void exactPercentilesAndOverrunsMatchAnIndependentSortedOracle() {
        final var history = new RuntimeTickHistory();
        final long first = 1_000_000_000L;
        for (int i = 1; i <= 100; ++i) history.record(first + i * 50_000_000L, i * 1_000_000L);
        final var result = history.snapshot(first + 100 * 50_000_000L, 10);
        assertEquals(100, result.samples());
        assertEquals(50.5, result.averageMs());
        assertEquals(50, result.p50Ms());
        assertEquals(95, result.p95Ms());
        assertEquals(99, result.p99Ms());
        assertEquals(100, result.maxMs());
        assertEquals(50, result.overruns());
        assertEquals(20, result.observedTps());
    }

    @Test
    void windowsUseWallTimeAndHandleIdleTimeWithoutInventingSamples() {
        final var history = new RuntimeTickHistory();
        for (int i = 1; i <= 100; ++i) history.record(i * 100_000_000L, i * 1_000_000L);
        assertEquals(10, history.snapshot(10_000_000_000L, 1).samples());
        assertEquals(10, history.snapshot(10_000_000_000L, 10).observedTps());
        assertEquals(0, history.snapshot(20_000_000_000L, 1).samples());
        assertTrue(Double.isNaN(history.snapshot(20_000_000_000L, 1).p99Ms()));
        assertEquals(100, history.snapshot(20_000_000_000L, 60).samples());
    }

    @Test
    void historyIsBoundedAndReportsCapacityTruncation() {
        final var history = new RuntimeTickHistory();
        for (int i = 1; i <= RuntimeTickHistory.CAPACITY + 100; ++i) history.record(i * 1_000_000L, i);
        final var result = history.snapshot((RuntimeTickHistory.CAPACITY + 100L) * 1_000_000L, 300);
        assertEquals(RuntimeTickHistory.CAPACITY, result.samples());
        assertTrue(result.capacityLimited());
        assertEquals(RuntimeTickHistory.CAPACITY + 100L, result.lifetimeTicks());
        assertFalse(history.snapshot((RuntimeTickHistory.CAPACITY + 100L) * 1_000_000L, 1).capacityLimited());
    }

    @Test
    void invalidDurationsTimestampsAndWindowsAreRefused() {
        final var history = new RuntimeTickHistory();
        assertThrows(IllegalArgumentException.class, () -> history.record(1, -1));
        history.record(10, 0);
        assertThrows(IllegalArgumentException.class, () -> history.record(9, 0));
        assertThrows(IllegalArgumentException.class, () -> history.snapshot(10, 0));
        assertThrows(IllegalArgumentException.class, () -> history.snapshot(10, 301));
    }

    @Test
    void regionMedianOverrunsAndExecutionDistributionMatchKnownGroupInputs() throws Exception {
        final var metrics = new net.neoforged.neoforge.server.threading.region.RegionTickMetrics();
        try {
            for (int tick = 1; tick <= 100; ++tick) {
                metrics.beginTick(tick, tick * 50_000_000L);
                metrics.recordTick(tick * 1_000_000L);
            }
            metrics.beginTick(101, 101 * 50_000_000L);
            final var snapshot = metrics.getClass().getDeclaredMethod("runtimeSnapshot", String.class,
                    int.class, int.class, int.class, int.class, int.class, int.class, String.class, boolean.class, boolean.class);
            snapshot.setAccessible(true);
            final var region = (net.neoforged.neoforge.server.threading.region.RuntimeRegionSnapshot) snapshot.invoke(metrics,
                    "test:dimension", 0, 0, 2, 3, -1, 4, "READY", false, false);
            assertEquals(50, region.p50Ms());
            assertEquals(50, region.overruns());
            assertEquals(100, region.serialGroups());
            assertEquals(0, region.workerGroups());
            assertEquals(5_050_000_000L, region.mainThreadNanos());
            assertEquals(0, region.workerNanos());
            assertEquals(-1, region.blockEntities());
            assertEquals(4, region.pendingWork());
        } finally {
            metrics.release();
        }
    }

    @Test
    void tickBoundaryReadsTheNativeRingSlotRatherThanTimingThePostEvent() {
        final var server = mock(MinecraftServer.class);
        final var ring = new long[100];
        ring[7] = 123_456_789L;
        when(server.getTickCount()).thenReturn(7);
        when(server.getTickTimesNanos()).thenReturn(ring);
        assertTrue(RuntimeObservability.enabled(), "native-ring accuracy fixture requires collection enabled");
        final long started = System.nanoTime();
        RuntimeObservability.recordServerTick(server);
        final var result = RuntimeObservability.history(server, 1);
        assertEquals(1, result.samples(), () -> "Native sample missing after " + (System.nanoTime() - started)
                + "ns; enabled=" + RuntimeObservability.enabled() + "; 10s=" + RuntimeObservability.history(server, 10));
        assertEquals(123.456789, result.averageMs(), 1e-9);
        RuntimeObservability.clear(server);
        assertEquals(0, RuntimeObservability.history(server, 1).samples());
    }

    @Test
    void differentServerThreadsCannotReplaceOrClearEachOthersHistory() throws Exception {
        final var first = mock(MinecraftServer.class);
        final var second = mock(MinecraftServer.class);
        final var firstRing = new long[100];
        final var secondRing = new long[100];
        firstRing[0] = 1_000_000;
        secondRing[0] = 9_000_000;
        when(first.getTickTimesNanos()).thenReturn(firstRing);
        when(second.getTickTimesNanos()).thenReturn(secondRing);
        RuntimeObservability.recordServerTick(first);
        final var recorded = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var other = new Thread(() -> {
            try {
                RuntimeObservability.recordServerTick(second);
                recorded.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                final var history = RuntimeObservability.history(second, 10);
                assertEquals(1, history.samples());
                assertEquals(9, history.averageMs());
                RuntimeObservability.clear(first);
                assertEquals(1, RuntimeObservability.history(second, 10).samples());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                RuntimeObservability.clear(second);
            }
        }, "independent-server-observer-control");
        other.start();
        try {
            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            final var history = RuntimeObservability.history(first, 10);
            assertEquals(1, history.samples());
            assertEquals(1, history.averageMs());
            RuntimeObservability.clear(second);
            assertEquals(1, RuntimeObservability.history(first, 10).samples());
        } finally {
            release.countDown();
            other.join(5_000);
            RuntimeObservability.clear(first);
        }
        assertFalse(other.isAlive());
        assertEquals(null, failure.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void taskCountersQueueDepthFailureAndPhysicalJoinAreObservedOnBothPools(boolean persistent) throws Exception {
        final var type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final var constructor = type.getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        final Object pool = constructor.newInstance(1, persistent);
        final Method execute = type.getDeclaredMethod("execute", String.class, Runnable.class);
        final Method close = type.getDeclaredMethod("close");
        final Method snapshot = type.getDeclaredMethod("runtimeSnapshot");
        execute.setAccessible(true); close.setAccessible(true); snapshot.setAccessible(true);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try {
            execute.invoke(pool, "held", (Runnable) () -> {
                started.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            execute.invoke(pool, "failure", (Runnable) () -> { throw new IllegalStateException("expected monitoring failure control"); });
            execute.invoke(pool, "success", (Runnable) () -> {});
            final var held = (WorkerRuntimeSnapshot) snapshot.invoke(pool);
            assertEquals(3, held.submitted());
            assertEquals(1, held.busy());
            assertEquals(2, held.queued());
            assertEquals(0, held.completed());
            assertFalse(held.joined());
            release.countDown();
            close.invoke(pool);
            final var done = (WorkerRuntimeSnapshot) snapshot.invoke(pool);
            assertEquals(3, done.completed());
            assertEquals(1, done.failed());
            assertEquals(0, done.queued());
            assertEquals(0, done.busy());
            assertTrue(done.joined());
            assertTrue(done.executionNanos() > 0);
            assertTrue(done.queueWaitNanos() > 0);
            assertEquals(false, execute.invoke(pool, "refused", (Runnable) () -> fail("Rejected task ran")));
            assertEquals(1, ((WorkerRuntimeSnapshot) snapshot.invoke(pool)).rejected());
        } finally {
            release.countDown();
            close.invoke(pool);
        }
    }

    @Test
    void disabledTaskTimingRetainsExactSchedulerCounters() throws Exception {
        final var type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final var constructor = type.getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        final Object pool = constructor.newInstance(1, true);
        final Method execute = type.getDeclaredMethod("execute", String.class, Runnable.class);
        final Method close = type.getDeclaredMethod("close");
        final Method timing = type.getDeclaredMethod("setRuntimeTimingEnabled", boolean.class);
        final Method snapshot = type.getDeclaredMethod("runtimeSnapshot");
        execute.setAccessible(true);
        close.setAccessible(true);
        timing.setAccessible(true);
        snapshot.setAccessible(true);
        timing.invoke(pool, false);
        try {
            final var finished = new CountDownLatch(1);
            execute.invoke(pool, "untimed", (Runnable) finished::countDown);
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            close.invoke(pool);
            final var result = (WorkerRuntimeSnapshot) snapshot.invoke(pool);
            assertEquals(1, result.submitted());
            assertEquals(1, result.completed());
            assertEquals(0, result.timedTasks());
            assertEquals(0, result.executionNanos());
            assertEquals(0, result.queueWaitNanos());
            assertTrue(result.joined());
        } finally {
            close.invoke(pool);
        }
    }

    @Test
    void workerCpuReadIsBracketedByIndependentMxBeanReadsWhileBlockedTimeIsWallOnly() throws Exception {
        final var type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool");
        final var constructor = type.getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        final Object pool = constructor.newInstance(1, true);
        final Method execute = type.getDeclaredMethod("execute", String.class, Runnable.class);
        final Method close = type.getDeclaredMethod("close");
        final Method snapshot = type.getDeclaredMethod("runtimeSnapshot");
        execute.setAccessible(true);
        close.setAccessible(true);
        snapshot.setAccessible(true);
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        try {
            execute.invoke(pool, "blocked", (Runnable) () -> {
                started.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            final var first = (WorkerRuntimeSnapshot) snapshot.invoke(pool);
            assertEquals(1, first.busy());
            final var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            assertTrue(bean.isThreadCpuTimeSupported());
            assertTrue(bean.isThreadCpuTimeEnabled());
            final long id = first.workers().getFirst().id();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (bean.getThreadInfo(id).getThreadState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < deadline, "worker did not enter its controlled wait");
                Thread.onSpinWait();
            }
            final long before = bean.getThreadCpuTime(id);
            final var observed = ((WorkerRuntimeSnapshot) snapshot.invoke(pool)).workers().getFirst();
            final long after = bean.getThreadCpuTime(id);
            assertTrue(observed.cpuNanos() >= before && observed.cpuNanos() <= after);
            assertTrue(observed.busy());
            assertEquals("TIMED_WAITING", observed.state());
            assertEquals(0, observed.completed());
            assertEquals(0, observed.executionNanos(), "unfinished task wall time is not misreported as completed CPU");
        } finally {
            release.countDown();
            close.invoke(pool);
        }
    }

    @Test
    void administratorPermissionAndMetricCompletionAreEnforced() throws Exception {
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        ForgiaCommand.register(dispatcher);
        final var denied = mock(CommandSourceStack.class);
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("forgia performance", denied));
        final var allowed = mock(CommandSourceStack.class);
        when(allowed.hasPermission(2)).thenReturn(true);
        final var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse("forgia region top p", allowed)).get(5, TimeUnit.SECONDS);
        assertEquals(List.of("p50", "p95", "p99", "pending"), suggestions.getList().stream().map(s -> s.getText()).toList());
    }

    @Test
    void regionListsArePaginatedEmptyRetiredAndUnavailableMetricsAreExplicit() throws Exception {
        final var dispatcher = new CommandDispatcher<CommandSourceStack>();
        ForgiaCommand.register(dispatcher);
        final var source = mock(CommandSourceStack.class);
        when(source.hasPermission(2)).thenReturn(true);
        final var lines = new ArrayList<String>();
        doAnswer(invocation -> {
            lines.add(((Supplier<Component>) invocation.getArgument(0)).get().getString());
            return null;
        }).when(source).sendSuccess(any(), eq(false));
        final var engine = new RegionThreadingEngine(1, false, false);
        RegionThreadingManager.install(engine);
        final var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        assertEquals(1, dispatcher.execute("forgia regions", source));
        assertTrue(lines.getFirst().contains("Regions=0"));
        for (int i = 0; i < 25; ++i) engine.addRegionSection(level, i * 100, 0);
        lines.clear();
        assertEquals(1, dispatcher.execute("forgia regions 2", source));
        assertEquals(11, lines.size());
        assertTrue(lines.getFirst().contains("page=2/3"));
        assertTrue(lines.get(1).contains("current=N/A"));
        lines.clear();
        assertEquals(0, dispatcher.execute("forgia region top blockentities", source));
        assertTrue(lines.getFirst().contains("N/A"));
        engine.clearLevelRegionTasks(level);
        assertEquals(0, engine.runtimeRegionSnapshots().size());
    }

    @Test
    void regionBottleneckAnalysisComputesPerRegionTpsAndIdentifiesDraggingRegion() {
        final var globalHealthy = new RuntimeTickHistory.Summary(10, 100, 12.0, 10.0, 15.0, 18.0, 20.0, 12.0, 20.0, 0, 100, 0, 10.0, false);
        final var lightRegion = snapshot("minecraft:overworld", 0, 0, 2.0, 20.0, 10, 16);
        final var healthy = RegionBottleneckAnalysis.analyze(globalHealthy, List.of(lightRegion), 50, 50, 4);
        assertEquals(RegionBottleneckAnalysis.BottleneckType.HEALTHY, healthy.verdict());
        assertEquals(20.0, healthy.topRegion().capacityTps(), 1e-6);

        final var globalRegionLag = new RuntimeTickHistory.Summary(10, 100, 85.0, 80.0, 95.0, 100.0, 110.0, 85.0, 11.76, 100, 100, 100, 10.0, false);
        final var slowRegion = snapshot("minecraft:overworld", -19, -27, 75.0, 11.76, 248, 64);
        final var regionDom = RegionBottleneckAnalysis.analyze(globalRegionLag, List.of(lightRegion, slowRegion), 50, 50, 4);
        assertEquals(RegionBottleneckAnalysis.BottleneckType.REGION_DOMINATED, regionDom.verdict());
        assertEquals("minecraft:overworld@-19,-27", regionDom.topRegion().id());
        assertEquals(1000.0 / 75.0, regionDom.topRegion().capacityTps(), 1e-6);
        assertTrue(regionDom.summary().contains("minecraft:overworld@-19,-27"));

        final var globalMixedLag = new RuntimeTickHistory.Summary(10, 100, 68.3, 68.0, 81.6, 88.4, 91.4, 63.5, 14.3, 100, 100, 100, 10.0, false);
        final var hotspotRegion = snapshot("minecraft:overworld", -19, -27, 25.05, 14.3, 248, 64);
        final var mixed = RegionBottleneckAnalysis.analyze(globalMixedLag, List.of(lightRegion, hotspotRegion), 50, 50, 4);
        assertEquals(RegionBottleneckAnalysis.BottleneckType.MIXED_REGION_AND_MAIN, mixed.verdict());
        assertEquals(20.0, mixed.topRegion().capacityTps(), 1e-6);
        assertEquals(14.3, mixed.topRegion().observedTps(), 1e-6);
        assertTrue(mixed.estimatedSerialAndMainMs() > 40.0);

        final var globalMainLag = new RuntimeTickHistory.Summary(10, 100, 65.0, 64.0, 72.0, 75.0, 80.0, 65.0, 15.38, 100, 100, 100, 10.0, false);
        final var mainOnly = RegionBottleneckAnalysis.analyze(globalMainLag, List.of(lightRegion), 50, 50, 4);
        assertEquals(RegionBottleneckAnalysis.BottleneckType.MAIN_THREAD_SERIAL, mainOnly.verdict());
    }

    private static RuntimeRegionSnapshot snapshot(String dim, int x, int z, double avgMs, double observedTps, int entities, int chunks) {
        final var ticks = new RegionTickSnapshot(x, z, dim, 50, avgMs, avgMs * 1.1, avgMs * 1.2, avgMs * 1.3, avgMs, observedTps, 0, RegionTickStatus.OK);
        return new RuntimeRegionSnapshot(ticks, avgMs, 50, 0, 0L, (long) (avgMs * 1_000_000L * 50), 0, 50, chunks, entities, 0, 0, 0, 0, "READY", false, true);
    }
}
