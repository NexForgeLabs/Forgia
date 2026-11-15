/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

@ForEachTest(groups = "level.region.c6worker")
public final class RealModFailureRecoveryGameTest {
    @GameTest(batch = "regionC6Failure", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "A complete original Mod body followed by an injected failure is never replayed, leaves no running authority, and the same real Level can run a fresh original phase")
    static void startedOriginalModIsNeverReplayedAndFreshPhaseRecovers(final DynamicTest test) {
        test.onGameTest(helper -> {
            var level = helper.getLevel();
            var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(engine != null, "Real engine missing");
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(block != Blocks.AIR, "Original upstream missing");
            int width = engine.diagnostics().regionChunkSize() * 16;
            var base = helper.absolutePos(BlockPos.ZERO);
            var a = new BlockPos(Math.floorDiv(base.getX(), width) * width + 10, 100, Math.floorDiv(base.getZ(), width) * width + 10);
            var b = a.offset(width, 0, 0);
            var positions = List.of(a, b);
            long keyA = a.asLong(), keyB = b.asLong();
            var injected = new IllegalStateException("C6-original-Mod-body-completed-before-injected-failure");
            var bodiesA = new AtomicInteger();
            var bodiesB = new AtomicInteger();
            var suffixBodies = new AtomicInteger();
            var recoverBodies = new AtomicInteger();
            final TickingBlockEntity[] suffix = { null };
            helper.addEndListener(passed -> {
                engine.setBlockEntityBodyObserverForTesting(null);
                if (suffix[0] != null) level.removeCollectedBlockEntityTickers(List.of(suffix[0]));
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
            }).thenWaitUntil(() -> {
                Object readyA = level.getBlockEntity(a), readyB = level.getBlockEntity(b);
                int readyCountA = readyA == null ? -1 : RealModWorkerMissionGameTest.counter(readyA), readyCountB = readyB == null ? -1 : RealModWorkerMissionGameTest.counter(readyB);
                helper.assertTrue(readyCountA >= 2 && readyCountB >= 2, "Wait for actual original canonical ticking eligibility A=" + readyCountA + " B=" + readyCountB + " stateA=" + level.getBlockState(a) + " stateB=" + level.getBlockState(b));
            }).thenExecute(() -> {
                Object sourceA = level.getBlockEntity(a), sourceB = level.getBlockEntity(b);
                helper.assertTrue(sourceA != null && sourceB != null, "Original sources missing");
                setCounter(sourceA, 1);
                setCounter(sourceB, 1);
                suffix[0] = new TickingBlockEntity() {
                    public void tick() {
                        suffixBodies.incrementAndGet();
                    }

                    public boolean isRemoved() {
                        return false;
                    }

                    public BlockPos getPos() {
                        return a;
                    }

                    public String getType() {
                        return "neotests:c6_unknown_suffix";
                    }
                };
                level.addBlockEntityTicker(suffix[0]);
                engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                    if (ticker == suffix[0]) {
                        body.run();
                        return;
                    }
                    long key = ticker.getPos().asLong();
                    if (key != keyA && key != keyB) {
                        body.run();
                        return;
                    }
                    helper.assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread() && BlockEntityResourceOwnership.Scope.active(), "Fault must occur in a real originally authorized Mod Worker");
                    body.run();
                    if (key == keyA) {
                        bodiesA.incrementAndGet();
                        throw injected;
                    }
                    bodiesB.incrementAndGet();
                });
                Throwable reported = null;
                try {
                    originalLevelPhase(level);
                } catch (RuntimeException failure) {
                    reported = failure;
                }
                System.out.println("C6_FAILURE_INJECTION_OBSERVATION reported=" + (reported == null ? "null" : reported.getClass().getName()) + " A=" + bodiesA.get() + " B=" + bodiesB.get() + " suffix=" + suffixBodies.get());
                if (reported != null) reported.printStackTrace(System.out);
                helper.assertTrue(reported != null && contains(reported, injected), "Actual Core phase must report the original failure");
                helper.assertTrue(bodiesA.get() == 1, "Failed started original body must not replay");
                helper.assertTrue(RealModWorkerMissionGameTest.counter(sourceA) == 2, "Failed body effect retained once");
                helper.assertTrue(bodiesB.get() <= 1 && RealModWorkerMissionGameTest.counter(sourceB) == 1 + bodiesB.get(), "Other started original body must be accounted once");
                helper.assertTrue(suffixBodies.get() == 0, "Unknown unstarted suffix must not run after started body failure");
                helper.assertTrue(!BlockEntityResourceOwnership.Scope.active(), "Coordinator cannot retain a Worker Scope");
                System.out.println("C6_FAILURE_POST_STATE " + engineState(engine));
                System.out.println("C6_BEFORE_FRESH_PHASE counterA=" + RealModWorkerMissionGameTest.counter(sourceA) + " counterB=" + RealModWorkerMissionGameTest.counter(sourceB));
                engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                    if (ticker == suffix[0]) {
                        body.run();
                        return;
                    }
                    long key = ticker.getPos().asLong();
                    if (key == keyA || key == keyB) {
                        System.out.println("C6_FRESH_BODY key=" + key + " worker=" + RegionThreadingHooks.isNativeRegionWorkerThread() + " scope=" + BlockEntityResourceOwnership.Scope.active());
                        helper.assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread() && BlockEntityResourceOwnership.Scope.active(), "Fresh phase must reacquire actual original authority");
                        body.run();
                        recoverBodies.incrementAndGet();
                    } else body.run();
                });
                setCounter(sourceA, 3);
                setCounter(sourceB, 3);
                originalLevelPhase(level);
                helper.assertTrue(recoverBodies.get() == 2 && RealModWorkerMissionGameTest.counter(sourceA) == 4 && RealModWorkerMissionGameTest.counter(sourceB) == 4, "Fresh original phase must execute once per source");
                helper.assertTrue(suffixBodies.get() == 1, "Suffix can run once only in the following fresh phase");
                engine.setBlockEntityBodyObserverForTesting(null);
                level.removeCollectedBlockEntityTickers(List.of(suffix[0]));
                suffix[0] = null;
                System.out.println("C6_REAL_MOD_FAILURE_RECOVERY failedOriginalBody=1 failedCounterDelta=1 suffixAfterFailure=0 freshOriginalWorkerBodies=2 freshCounterDelta=2 suffixInFreshPhase=1 sameRealLevel=true noReplay=true");
            }).thenSucceed();
        });
    }

    static String engineState(Object engine) {
        try {
            Field admission = engine.getClass().getDeclaredField("regionWorkAdmission");
            admission.setAccessible(true);
            Object boundary = admission.get(engine);
            Method owner = boundary.getClass().getDeclaredMethod("isOwner");
            owner.setAccessible(true);
            Field batches = engine.getClass().getDeclaredField("activeTickBatches");
            batches.setAccessible(true);
            Class<?> runtime = Class.forName("net.neoforged.forgia.runtimeproof.RuntimeProof", false, null);
            return "boundaryOwner=" + owner.invoke(boundary) + " activeBatches=" + ((java.util.Collection<?>) batches.get(engine)).size() + " vmAvailable=" + runtime.getMethod("available").invoke(null) + " enforcement=" + runtime.getMethod("enforcementAvailable").invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static void setCounter(Object source, int value) {
        try {
            Field field = source.getClass().getDeclaredField("ticks");
            field.setAccessible(true);
            field.setInt(source, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static boolean contains(Throwable actual, Throwable expected) {
        for (Throwable current = actual; current != null; current = current.getCause()) if (current == expected) return true;
        for (Throwable suppressed : actual.getSuppressed()) if (contains(suppressed, expected)) return true;
        return false;
    }

    static void originalLevelPhase(Level level) {
        try {
            Method original = Level.class.getDeclaredMethod("tickBlockEntities");
            original.setAccessible(true);
            original.invoke(level);
        } catch (InvocationTargetException reported) {
            if (reported.getCause() instanceof RuntimeException runtime) throw runtime;
            if (reported.getCause() instanceof Error error) throw error;
            throw new AssertionError(reported.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
