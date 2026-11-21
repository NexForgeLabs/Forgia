/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.IntConsumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.region.RegionAdjacencyColoring;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RegionP2OptimizationTest {
    @Test
    void adjacencyColoringSeparatesAllEightNeighborsOnContiguousGrid() {
        final ServerLevel overworld = mock(ServerLevel.class);
        final List<RegionCoordinate> grid = new ArrayList<>();
        for (int rx = 0; rx < 3; ++rx) {
            for (int rz = 0; rz < 3; ++rz) {
                grid.add(new RegionCoordinate(overworld, rx, rz));
            }
        }

        final List<List<RegionCoordinate>> waves = RegionAdjacencyColoring.color(grid, c -> c);
        assertTrue(waves.size() <= 4, "A 2D grid under 8-neighbor adjacency requires at most 4 colors, got " + waves.size());

        final Set<RegionCoordinate> seen = new HashSet<>();
        for (final List<RegionCoordinate> wave : waves) {
            for (int i = 0; i < wave.size(); ++i) {
                assertTrue(seen.add(wave.get(i)), "Duplicate region in coloring output");
                for (int j = i + 1; j < wave.size(); ++j) {
                    assertFalse(RegionAdjacencyColoring.areAdjacent(wave.get(i), wave.get(j)),
                            "Adjacent regions placed in same wave: " + wave.get(i) + " and " + wave.get(j));
                }
            }
        }
        assertEquals(grid.size(), seen.size());
    }

    @Test
    void adjacencyColoringPacksNonAdjacentAndCrossDimensionRegionsIntoOneWave() {
        final ServerLevel overworld = mock(ServerLevel.class);
        final ServerLevel nether = mock(ServerLevel.class);
        final List<RegionCoordinate> coords = List.of(
                new RegionCoordinate(overworld, 0, 0),
                new RegionCoordinate(overworld, 2, 0),
                new RegionCoordinate(overworld, 0, 2),
                new RegionCoordinate(overworld, 2, 2),
                new RegionCoordinate(nether, 0, 0));

        final List<List<RegionCoordinate>> waves = RegionAdjacencyColoring.color(coords, c -> c);
        assertEquals(1, waves.size());
        assertEquals(5, waves.get(0).size());
    }

    @Test
    void callerRunsStealOneClaimsUnstartedTailExactlyOnce() throws Exception {
        final Class<?> dispatcherClass = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkStealingDispatcher");
        final Constructor<?> ctor = dispatcherClass.getDeclaredConstructor(int.class, int.class, IntConsumer.class, IntConsumer.class);
        ctor.setAccessible(true);
        final Method runMethod = dispatcherClass.getDeclaredMethod("run", int.class);
        runMethod.setAccessible(true);
        final Method stealOneMethod = dispatcherClass.getDeclaredMethod("stealOne");
        stealOneMethod.setAccessible(true);

        final int taskCount = 64;
        final AtomicIntegerArray runs = new AtomicIntegerArray(taskCount);
        final AtomicInteger callerRuns = new AtomicInteger();
        final AtomicInteger steals = new AtomicInteger();
        final CountDownLatch runnerBlocked = new CountDownLatch(1);
        final CountDownLatch releaseRunner = new CountDownLatch(1);
        final Thread callerThread = Thread.currentThread();

        final IntConsumer work = index -> {
            runs.incrementAndGet(index);
            if (Thread.currentThread() == callerThread) {
                callerRuns.incrementAndGet();
            } else if (index == 0) {
                runnerBlocked.countDown();
                try {
                    releaseRunner.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        final Object dispatcher = ctor.newInstance(taskCount, 2, work, (IntConsumer) steals::addAndGet);
        final Thread worker = new Thread(() -> {
            try {
                runMethod.invoke(dispatcher, 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "test-worker-0");
        worker.start();

        assertTrue(runnerBlocked.await(5, TimeUnit.SECONDS));
        // Caller steals tasks from the tail while worker 0 is blocked on task 0
        int stolenByCaller = 0;
        for (int i = 0; i < 16; ++i) {
            if ((boolean) stealOneMethod.invoke(dispatcher)) {
                ++stolenByCaller;
            }
        }
        assertEquals(16, stolenByCaller);
        assertEquals(16, callerRuns.get());

        // Release worker and drain remaining tasks across worker + runner 1
        releaseRunner.countDown();
        runMethod.invoke(dispatcher, 1);
        worker.join(5000);

        // Drain any remaining via caller
        while ((boolean) stealOneMethod.invoke(dispatcher)) {
            // continue
        }

        for (int i = 0; i < taskCount; ++i) {
            assertEquals(1, runs.get(i), "Task " + i + " must execute exactly once");
        }
        assertTrue(steals.get() >= 16);
    }

    @Test
    void canonicalFenceTailCollapsesParallelBarrierRoundsAndRunsFencesInOrder() throws Exception {
        final Class<?> coordClass = Class.forName("net.neoforged.neoforge.server.threading.region.CanonicalBlockEntityCoordinator");
        final Class<?> driverClass = Class.forName("net.neoforged.neoforge.server.threading.region.CanonicalBlockEntityCoordinator$Driver");
        final Class<?> unitClass = Class.forName("net.neoforged.neoforge.server.threading.region.CanonicalBlockEntityCoordinator$Unit");
        final Constructor<?> coordCtor = coordClass.getDeclaredConstructor(List.class);
        coordCtor.setAccessible(true);
        final Method executeMethod = coordClass.getDeclaredMethod("execute", driverClass, boolean.class);
        executeMethod.setAccessible(true);

        // Build 7 synthetic admissions: indices 2 and 5 are serial fences; 0,1,3,4,6 are parallel units.
        final ServerLevel level = mock(ServerLevel.class);
        final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
        for (int i = 0; i < 7; ++i) {
            final TickingBlockEntity ticker = mock(TickingBlockEntity.class);
            ordered.add(new RegionBlockEntityTickAdmission(
                    level,
                    ticker,
                    net.minecraft.core.BlockPos.ZERO,
                    "test:be_" + i,
                    new RegionBlockEntityTickAdmission.Binding(ticker, () -> true),
                    RegionBlockEntityTickAdmission.Decision.ADMITTED,
                    (long) i));
        }

        final Set<Integer> fenceIndices = Set.of(2, 5);

        // 1. Default canonical mode (deferFencesToTail = false) -> 3 parallel waves + 2 interleaved fences
        final List<String> canonicalLog = new ArrayList<>();
        final AtomicInteger canonicalWaves = new AtomicInteger();
        final Object canonicalCoord = coordCtor.newInstance(ordered);
        executeMethod.invoke(canonicalCoord, buildDriver(driverClass, unitClass, ordered, fenceIndices, canonicalLog, canonicalWaves), false);
        assertEquals(3, canonicalWaves.get());
        assertEquals(List.of("P[0, 1]", "S[2]", "P[3, 4]", "S[5]", "P[6]"), canonicalLog);

        // 2. Fence-tail mode (deferFencesToTail = true) -> 1 parallel wave + serial tail in original order
        final List<String> tailLog = new ArrayList<>();
        final AtomicInteger tailWaves = new AtomicInteger();
        final Object tailCoord = coordCtor.newInstance(ordered);
        executeMethod.invoke(tailCoord, buildDriver(driverClass, unitClass, ordered, fenceIndices, tailLog, tailWaves), true);
        assertEquals(1, tailWaves.get());
        assertEquals(List.of("P[0, 1, 3, 4, 6]", "S[2]", "S[5]"), tailLog);
    }

    private static Object buildDriver(
            Class<?> driverClass,
            Class<?> unitClass,
            List<RegionBlockEntityTickAdmission> ordered,
            Set<Integer> fenceIndices,
            List<String> log,
            AtomicInteger waves) {
        return Proxy.newProxyInstance(driverClass.getClassLoader(), new Class<?>[] { driverClass }, (proxy, method, args) -> switch (method.getName()) {
            case "prepare" -> {
                final int idx = (int) args[0];
                if (fenceIndices.contains(idx)) yield null;
                final var adm = ordered.get(idx);
                yield Proxy.newProxyInstance(unitClass.getClassLoader(), new Class<?>[] { unitClass }, (uProxy, uMethod, uArgs) -> switch (uMethod.getName()) {
                    case "admission" -> adm;
                    case "inspection" -> null;
                    default -> null;
                });
            }
            case "mayAppend" -> true;
            case "prefixLimit" -> 100;
            case "executePrefix" -> {
                @SuppressWarnings("unchecked")
                final List<Object> prefix = (List<Object>) args[0];
                waves.incrementAndGet();
                final List<Long> seqs = new ArrayList<>(prefix.size());
                final Method admMethod = unitClass.getMethod("admission");
                admMethod.setAccessible(true);
                for (final Object u : prefix) {
                    seqs.add(((RegionBlockEntityTickAdmission) admMethod.invoke(u)).sequence());
                }
                log.add("P" + seqs);
                yield prefix.size();
            }
            case "serial" -> {
                log.add("S[" + args[0] + "]");
                yield null;
            }
            default -> null;
        });
    }
}
