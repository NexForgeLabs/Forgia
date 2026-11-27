/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionIndependentTickScheduler;
import net.neoforged.neoforge.server.threading.region.RegionThreadingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class RegionLagChunkIsolationTest {
    private static final BlockPos REGION_A_POS = new BlockPos(20, 64, 20);
    private static final BlockPos REGION_B_POS = new BlockPos(148, 64, 20);

    @Test
    void chunkIndexedCooldownsDoNotContaminateOtherChunksAndClearOnRemoval() {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final AtomicLong gameTime = new AtomicLong(100L);
            when(f.level.getGameTime()).thenAnswer(inv -> gameTime.get());

            final BlockPos chunkOnePos = new BlockPos(20, 64, 20); // chunk (1, 1)
            RegionAdapterApi.recordTransientSideEffectFallback(f.level, chunkOnePos, 40L);

            assertTrue(RegionAdapterApi.chunkNeedsSynchronousSandboxSplit(f.level, 1, 1),
                    "Chunk with active transient BE cooldown must split its BEs");
            assertFalse(RegionAdapterApi.chunkNeedsSynchronousSandboxSplit(f.level, 9, 9),
                    "Healthy chunk without cooldowns must NOT be forced into synchronous split");

            RegionAdapterApi.clearTransientSandboxedFallbackAt(f.level, chunkOnePos);
            assertFalse(RegionAdapterApi.chunkNeedsSynchronousSandboxSplit(f.level, 1, 1),
                    "Removing the block entity must immediately clear its transient fallback entry");

            final BlockPos chunkTwoPos = new BlockPos(52, 64, 52); // chunk (3, 3)
            RegionAdapterApi.recordTransientBoundaryFallback(f.level, chunkTwoPos, "test:be", 40L);
            assertTrue(RegionAdapterApi.isTransientSandboxedFallback(f.level, chunkTwoPos));
            RegionAdapterApi.clearTransientSandboxedFallbacksInChunk(f.level, ChunkPos.asLong(3, 3));
            assertFalse(RegionAdapterApi.isTransientSandboxedFallback(f.level, chunkTwoPos),
                    "Clearing chunk block entities must purge all per-chunk fallback cooldowns");
        }
    }

    @Test
    void boundedIncrementalAutosaveSkipsBusyAsyncRegionWithoutBlockingMainThread() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            final RegionIndependentTickScheduler scheduler = f.engine.independentTickScheduler();
            scheduler.setTimingForTesting(5_000_000L, 4);

            final CountDownLatch stalledStarted = new CountDownLatch(1);
            final CountDownLatch releaseStalled = new CountDownLatch(1);

            assertTrue(RegionAdapterApi.dispatchIndependentRegionTick(f.level, REGION_A_POS, () -> {
                stalledStarted.countDown();
                try {
                    assertTrue(releaseStalled.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, null));

            assertTrue(stalledStarted.await(5, TimeUnit.SECONDS));
            final RegionCoordinate coordA = f.engine.regionLayout().regionAt(f.level, REGION_A_POS);
            assertTrue(scheduler.isRegionInFlight(coordA));

            final long startMs = System.nanoTime();
            RegionThreadingHooks.beginLevelSave(f.level, false);
            try {
                final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);
                assertTrue(elapsedMillis < 2_000L,
                        "Incremental autosave must bound quiescence wait instead of blocking on stalled async region; took " + elapsedMillis + "ms");
                assertTrue(RegionThreadingHooks.isChunkInBusyAsyncRegion(f.level, new ChunkPos(REGION_A_POS)),
                        "Chunks inside stalled async region must be marked busy so ChunkMap skips saving them mid-mutation");
                assertFalse(RegionThreadingHooks.isChunkInBusyAsyncRegion(f.level, new ChunkPos(REGION_B_POS)),
                        "Chunks in healthy quiescent regions must remain eligible for incremental autosave");
            } finally {
                RegionThreadingHooks.endLevelSaveQuiescence(f.level);
                releaseStalled.countDown();
            }

            scheduler.awaitLevelQuiescent(f.level, false);
            assertFalse(scheduler.isRegionInFlight(coordA));
        }
    }

    @Test
    void asyncDrainLevelRegionTasksDispatchesHealthyRegionWhileStalledRegionRuns() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            final RegionIndependentTickScheduler scheduler = f.engine.independentTickScheduler();
            scheduler.setTimingForTesting(5_000_000L, 4);

            final CountDownLatch stalledStarted = new CountDownLatch(1);
            final CountDownLatch releaseStalled = new CountDownLatch(1);
            final CountDownLatch healthyTaskRan = new CountDownLatch(1);
            final AtomicBoolean ranOnWorker = new AtomicBoolean(false);

            assertTrue(RegionAdapterApi.dispatchIndependentRegionTick(f.level, REGION_A_POS, () -> {
                stalledStarted.countDown();
                try {
                    assertTrue(releaseStalled.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, null));
            assertTrue(stalledStarted.await(5, TimeUnit.SECONDS));

            f.engine.regionScheduler().execute(f.level, REGION_B_POS, () -> {
                ranOnWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                healthyTaskRan.countDown();
            });

            final long drainStart = System.nanoTime();
            RegionThreadingHooks.drainLevelRegionTasks(f.level);
            final long drainMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - drainStart);
            assertTrue(drainMillis < 2_000L,
                    "drainLevelRegionTasks hook must return non-blockingly under independent region clock; took " + drainMillis + "ms");
            assertTrue(healthyTaskRan.await(5, TimeUnit.SECONDS),
                    "Queued task in healthy Region B must execute asynchronously while Region A is stalled");
            assertTrue(ranOnWorker.get(), "Healthy region queued task must execute on a native region worker");

            releaseStalled.countDown();
            scheduler.awaitLevelQuiescent(f.level, false);
        }
    }

    @Test
    void perRegionBlockEventsTimeSlicingDefersExcessWithoutDroppingAnyEvent() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, true);

            final ServerLevel level = mock(ServerLevel.class, CALLS_REAL_METHODS);
            final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
            final List<BlockEventData> toReschedule = new ArrayList<>();
            setField(ServerLevel.class, level, "blockEvents", blockEvents);
            setField(ServerLevel.class, level, "blockEventsToReschedule", toReschedule);
            doReturn(true).when(level).shouldTickBlocksAt(any(BlockPos.class));

            final Block dummyBlock = mock(Block.class);
            final BlockState slowStateA = mock(BlockState.class);
            final BlockState fastStateB = mock(BlockState.class);
            final AtomicInteger regionARuns = new AtomicInteger();
            final AtomicInteger regionBRuns = new AtomicInteger();

            when(slowStateA.is(dummyBlock)).thenReturn(true);
            when(slowStateA.triggerEvent(any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
                regionARuns.incrementAndGet();
                Thread.sleep(3); // ~3ms > 2ms per-region budget
                return false;
            });
            when(fastStateB.is(dummyBlock)).thenReturn(true);
            when(fastStateB.triggerEvent(any(), any(), anyInt(), anyInt())).thenAnswer(inv -> {
                regionBRuns.incrementAndGet();
                return false;
            });

            org.mockito.Mockito.doAnswer(inv -> {
                final BlockPos pos = inv.getArgument(0);
                return pos.getX() >= 128 ? fastStateB : slowStateA;
            }).when(level).getBlockState(any(BlockPos.class));

            // Queue 3 slow events in Region A and 1 fast event in Region B
            blockEvents.add(new BlockEventData(new BlockPos(20, 64, 20), dummyBlock, 0, 0));
            blockEvents.add(new BlockEventData(new BlockPos(21, 64, 20), dummyBlock, 0, 0));
            blockEvents.add(new BlockEventData(new BlockPos(22, 64, 20), dummyBlock, 0, 0));
            blockEvents.add(new BlockEventData(REGION_B_POS, dummyBlock, 0, 0));

            final Method runBlockEvents = ServerLevel.class.getDeclaredMethod("runBlockEvents");
            runBlockEvents.setAccessible(true);

            // Tick 1: Region A exceeds 2ms after its 1st event, deferring its remaining 2 events,
            // while Region B's event still runs in Tick 1!
            runBlockEvents.invoke(level);
            assertEquals(1, regionBRuns.get(), "Healthy Region B block event must execute in Tick 1 despite Region A exhausting its budget");
            assertEquals(1, regionARuns.get(), "Region A must stop after exhausting its 2ms slice in Tick 1");
            assertEquals(2, blockEvents.size(), "Deferred Region A events must remain queued with zero event loss");

            // Tick 2 & Tick 3 drain the remaining 2 slow events cleanly
            runBlockEvents.invoke(level);
            runBlockEvents.invoke(level);
            assertEquals(3, regionARuns.get(), "All deferred Region A block events must eventually execute without loss");
            assertTrue(blockEvents.isEmpty(), "Block event queue must drain completely");
        }
    }

    @Test
    void configDefaultsAndPathNavigationDeferralBehaveAsExpected() {
        assertEquals(1200, RegionThreadingConfig.sandboxMaxInstanceBackoffTicks());
        assertEquals(8, RegionThreadingConfig.sandboxHopperPlayerSafeRadiusBlocks());
        assertEquals(15, RegionThreadingConfig.autosaveMaxQuiesceWaitMillis());
        assertEquals(2_000_000L, RegionThreadingConfig.blockEventsPerRegionBudgetNanos());
        assertEquals(16, RegionThreadingConfig.maxSyncNavigationRecomputesPerTick());
        assertEquals(6, RegionThreadingConfig.inlandBlockScheduledTickMarginBlocks());
        assertEquals(2_000_000L, RegionThreadingConfig.blockScheduledTickSerialBudgetNanos());
        assertTrue(RegionThreadingConfig.slimeWorkerTicksEnabled());
        assertTrue(RegionThreadingConfig.highDensityEntityTrackerThrottleEnabled());
        assertEquals(64, RegionThreadingConfig.entityTrackerDenseChunkThreshold());
        assertEquals(256, RegionThreadingConfig.entityTrackerSevereChunkThreshold());

        final PathNavigation nav = mock(PathNavigation.class, CALLS_REAL_METHODS);
        nav.deferRecomputePath();
        assertFalse(nav.shouldRecomputePath(new BlockPos(0, 64, 0)),
                "Deferred PathNavigation must suppress synchronous main-thread recompute until worker tick");
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
