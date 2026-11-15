/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

@ForEachTest(groups = "level.region.c6worker")
public final class RealModWorkerMissionGameTest {
    @GameTest(batch = "regionC6Worker", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Original upstream Mod ticker must run under original Core resources on two real Region Workers with physical overlap")
    static void originalThirdPartyTickerReceivesRealCoreWorkerAuthority(final DynamicTest test) {
        test.onGameTest(helper -> {
            var level = helper.getLevel();
            var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(engine != null, "Actual canonical bridge required");
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(block != Blocks.AIR, "Original upstream Mod must load");
            int width = engine.diagnostics().regionChunkSize() * 16;
            var anchor = helper.absolutePos(BlockPos.ZERO);
            var a = new BlockPos(Math.floorDiv(anchor.getX(), width) * width + 10, 100, Math.floorDiv(anchor.getZ(), width) * width + 10);
            var b = a.offset(width, 0, 0);
            var positions = List.of(a, b);
            final long packedA = a.asLong(), packedB = b.asLong();
            var bodies = new AtomicInteger();
            var workers = new AtomicInteger();
            var serial = new AtomicInteger();
            var active = new AtomicInteger();
            var overlap = new AtomicInteger();
            var paired = new ConcurrentHashMap<Long, CountDownLatch>();
            final Object[] sources = new Object[2];
            final Object[] functions = new Object[2];
            final int[] initial = new int[2];
            final var originalAuthority = new AtomicReference<Object[]>();
            engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                long packed = ticker.getPos().asLong();
                int index = packed == packedA ? 0 : packed == packedB ? 1 : -1;
                if (index < 0) {
                    body.run();
                    return;
                }
                boolean worker = RegionThreadingHooks.isNativeRegionWorkerThread();
                bodies.incrementAndGet();
                if (worker) {
                    helper.assertTrue(BlockEntityResourceOwnership.Scope.active(), "Original Mod Worker lacks actual Resource Scope");
                    var source = LevelChunk.actualBlockEntitySource(ticker);
                    var function = LevelChunk.actualBlockEntityFunction(ticker);
                    helper.assertTrue(function != null && function.getClass().isHidden(), "Actual upstream hidden function required");
                    if (functions[index] == null) functions[index] = function;
                    helper.assertTrue(functions[index] == function, "Original function identity must remain unchanged");
                    if (originalAuthority.get() == null) {
                        final Object[] captured = RealModLeaseAssertions.capture();
                        if (originalAuthority.compareAndSet(null, captured)) RealModLeaseAssertions.assertRunningExclusion(captured);
                    }
                    BlockEntityResourceOwnership.Scope.require(level, source, BlockEntityResourceOwnership.Access.WRITE);
                    workers.incrementAndGet();
                    int now = active.incrementAndGet();
                    overlap.accumulateAndGet(now, Math::max);
                    try {
                        var latch = paired.computeIfAbsent(level.getGameTime(), ignored -> new CountDownLatch(2));
                        latch.countDown();
                        helper.assertTrue(latch.await(10, TimeUnit.SECONDS), "Second independently authorized Region body did not overlap");
                        body.run();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    } finally {
                        active.decrementAndGet();
                    }
                } else {
                    serial.incrementAndGet();
                    body.run();
                }
            });
            helper.addEndListener(passed -> {
                engine.setBlockEntityBodyObserverForTesting(null);
                for (var p : positions) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                    level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
                }
            });
            helper.startSequence().thenExecute(() -> {
                for (var p : positions) {
                    level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
                    level.setBlock(p, block.defaultBlockState(), 3);
                }
            }).thenExecuteAfter(10, () -> {
                for (int i = 0; i < 2; i++) {
                    sources[i] = level.getBlockEntity(positions.get(i));
                    helper.assertTrue(sources[i] != null, "Original source missing");
                    initial[i] = counter(sources[i]);
                }
                bodies.set(0);
                workers.set(0);
                serial.set(0);
            }).thenWaitUntil(() -> {
                for (int i = 0; i < 2; i++) helper.assertTrue(counter(sources[i]) - initial[i] >= 40, "Wait for actual complete original increments");
            }).thenExecute(() -> {
                int total = 0;
                for (int i = 0; i < 2; i++) {
                    helper.assertTrue(level.getBlockEntity(positions.get(i)) == sources[i], "Original storage identity changed");
                    total += counter(sources[i]) - initial[i];
                }
                if (!level.getServer().isSameThread()) throw new IllegalStateException("Mod runtime inspection left the coordinator");
                System.out.println("C6_MISSION_COUNTS bodies=" + bodies.get() + " workers=" + workers.get() + " serial=" + serial.get());
                helper.assertTrue(total == bodies.get(), "Original body count and actual private counter delta must match");
                helper.assertTrue(workers.get() > 0 && serial.get() > 0, "Worker counter path and complete periodic Serial Fence both required");
                helper.assertTrue(overlap.get() == 2 && active.get() == 0, "Physical two-Region overlap and join required");
                RealModLeaseAssertions.assertRetired(originalAuthority.get());
                System.out.println("C6_FIRST_ORIGINAL_MOD_WORKERS sources=2 differentRegions=true completeBodies=" + bodies.get() + " counterDelta=" + total + " actualWorkerBodies=" + workers.get() + " serialMaintenanceBodies=" + serial.get() + " overlap=" + overlap.get() + " originalKernel=true realResourceScope=true noReplacementTicker=true");
            }).thenSucceed();
        });
    }

    static int counter(Object source) {
        try {
            Field f = source.getClass().getDeclaredField("ticks");
            f.setAccessible(true);
            return f.getInt(source);
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError(failed);
        }
    }
}
