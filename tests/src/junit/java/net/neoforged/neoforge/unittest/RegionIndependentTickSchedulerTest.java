/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionIndependentTickScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class RegionIndependentTickSchedulerTest {
    private static final BlockPos REGION_A_POS = new BlockPos(20, 64, 20);
    private static final BlockPos REGION_B_POS = new BlockPos(68, 64, 20);

    @Test
    void slowRegionDoesNotBlockFastRegionOrServerThread() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            final RegionIndependentTickScheduler scheduler = f.engine.independentTickScheduler();
            scheduler.setTimingForTesting(5_000_000L, 4); // 5ms independent tick interval for fast test

            final CountDownLatch slowEntered = new CountDownLatch(1);
            final CountDownLatch releaseSlow = new CountDownLatch(1);
            final AtomicInteger slowRuns = new AtomicInteger();
            final AtomicInteger fastRuns = new AtomicInteger();
            final List<Thread> commitThreads = new CopyOnWriteArrayList<>();

            final RegionCoordinate coordA = f.engine.regionLayout().regionAt(f.level, REGION_A_POS);
            final RegionCoordinate coordB = f.engine.regionLayout().regionAt(f.level, REGION_B_POS);

            // Round 1: dispatch both A (stalled) and B (fast)
            assertTrue(RegionAdapterApi.dispatchIndependentRegionTick(f.level, REGION_A_POS, () -> {
                assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                slowRuns.incrementAndGet();
                slowEntered.countDown();
                try {
                    assertTrue(releaseSlow.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, () -> commitThreads.add(Thread.currentThread())));

            assertTrue(slowEntered.await(5, TimeUnit.SECONDS), "slow region must start running on worker");
            assertTrue(scheduler.isRegionInFlight(coordA));

            // While Region A is completely stalled on Worker 1, drive 8 more scheduling rounds.
            // Region B must keep advancing independently and Region A must be skipped non-blockingly!
            for (int round = 0; round < 8; ++round) {
                RegionAdapterApi.dispatchIndependentRegionTick(f.level, REGION_A_POS, () -> slowRuns.incrementAndGet(), null);
                RegionAdapterApi.dispatchIndependentRegionTick(f.level, REGION_B_POS, () -> {
                    assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                    fastRuns.incrementAndGet();
                }, () -> commitThreads.add(Thread.currentThread()));
                Thread.sleep(10);
                scheduler.drainCompletedCommits(f.level, 0L);
            }

            assertTrue(scheduler.isRegionInFlight(coordA), "slow region should still be in flight");
            assertTrue(scheduler.skippedWhileBusy(coordA) >= 4, "slow region must be skipped non-blockingly while busy");
            assertTrue(fastRuns.get() >= 4, "fast region must advance independently without waiting for slow region; got " + fastRuns.get());
            assertEquals(1, slowRuns.get(), "slow region must not overlap with itself");

            // Release Region A and verify clean quiescence and main-thread commit draining
            releaseSlow.countDown();
            scheduler.awaitLevelQuiescent(f.level, false);

            assertFalse(scheduler.isRegionInFlight(coordA));
            assertFalse(scheduler.isRegionInFlight(coordB));
            assertEquals(1, slowRuns.get());
            assertTrue(commitThreads.size() >= 5);
            for (final Thread t : commitThreads) {
                assertSame(f.serverThread, t, "async commits must execute on the server thread");
            }

            final var snap = f.engine.independentTickSnapshot(5);
            assertTrue(snap.enabled());
            assertEquals(0, snap.inFlightRegions());
            assertTrue(snap.skippedBusyTicks() >= 4);
            assertTrue(snap.completedTicks() >= 5);
            assertEquals(0, snap.pendingCommits());
            f.clean();
        }
    }

    @Test
    void sandboxedBlockEntitiesRunAsynchronouslyWithoutBlockingServerBarrierWhenEnabled() throws Exception {
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:async_be");
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            f.engine.independentTickScheduler().setTimingForTesting(1_000_000L, 4);

            final CountDownLatch slowStarted = new CountDownLatch(1);
            final CountDownLatch releaseSlow = new CountDownLatch(1);
            final AtomicInteger fastCount = new AtomicInteger();

            f.script("SLOW_BE", REGION_A_POS, "test:async_be", () -> {
                slowStarted.countDown();
                try {
                    releaseSlow.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            f.script("FAST_BE", REGION_B_POS, "test:async_be", fastCount::incrementAndGet);

            // Server thread dispatches levelPhase() and returns IMMEDIATELY without waiting for SLOW_BE!
            final long start = System.nanoTime();
            f.levelPhase();
            final long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertTrue(slowStarted.await(5, TimeUnit.SECONDS), "SLOW_BE must execute asynchronously on a worker");
            assertTrue(elapsedMs < 500, "Server thread must not block on SLOW_BE barrier; elapsedMs=" + elapsedMs);

            // Drive a second tick while SLOW_BE is still blocked: FAST_BE runs again!
            Thread.sleep(10);
            f.levelPhase();
            releaseSlow.countDown();
            f.engine.independentTickScheduler().awaitLevelQuiescent(f.level, false);

            assertTrue(fastCount.get() >= 2, "FAST_BE should tick independently while SLOW_BE is stalled; got " + fastCount.get());
            f.clean();
        }
    }

    @Test
    void entitySliceThrottlesOnlyHotspotRegions() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            final RegionIndependentTickScheduler scheduler = f.engine.independentTickScheduler();
            final RegionCoordinate fast = f.engine.regionLayout().regionAt(f.level, new BlockPos(64, 64, 128));
            final RegionCoordinate slow = f.engine.regionLayout().regionAt(f.level, new BlockPos(192, 64, 256));
            final RegionCoordinate severe = f.engine.regionLayout().regionAt(f.level, new BlockPos(320, 64, 384));

            scheduler.recordEntitySliceDuration(fast, 3_000_000L);
            scheduler.recordEntitySliceDuration(slow, 10_000_000L);
            scheduler.recordEntitySliceDuration(severe, 18_000_000L);

            int fastThrottled = 0;
            int slowThrottled = 0;
            int severeThrottled = 0;
            for (long t = 0; t < 12; t++) {
                if (scheduler.shouldThrottleEntityTick(fast, t)) fastThrottled++;
                if (scheduler.shouldThrottleEntityTick(slow, t)) slowThrottled++;
                if (scheduler.shouldThrottleEntityTick(severe, t)) severeThrottled++;
            }
            assertEquals(0, fastThrottled, "Fast regions (<6ms) must run at full 20 TPS");
            assertEquals(6, slowThrottled, "6-14ms regions must run every 2nd tick");
            assertEquals(8, severeThrottled, ">14ms regions must run every 3rd tick");
        }
    }

    @Test
    void spawnStaggersAcrossTicks() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            final RegionIndependentTickScheduler scheduler = f.engine.independentTickScheduler();
            final RegionCoordinate normal = f.engine.regionLayout().regionAt(f.level, new BlockPos(640, 64, 1280));
            final RegionCoordinate heavy = f.engine.regionLayout().regionAt(f.level, new BlockPos(1920, 64, 2560));
            scheduler.recordEntitySliceDuration(normal, 2_000_000L);
            scheduler.recordEntitySliceDuration(heavy, 12_000_000L);

            int normalSkipped = 0;
            int heavySkipped = 0;
            for (long t = 0; t < 8; t++) {
                if (scheduler.shouldStaggerSpawn(normal, t)) normalSkipped++;
                if (scheduler.shouldStaggerSpawn(heavy, t)) heavySkipped++;
            }
            assertEquals(4, normalSkipped, "Normal regions stagger spawn every 2 ticks");
            assertEquals(6, heavySkipped, "Heavy regions (>8ms) stagger spawn every 4 ticks");
        }
    }
}
