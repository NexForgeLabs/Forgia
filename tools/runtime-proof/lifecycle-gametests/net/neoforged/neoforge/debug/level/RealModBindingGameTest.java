/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickAdmission;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/** Real original-object preflight tests only: no Ticket/Claims/Lease is created by this fixture. */
@ForEachTest(groups = "level.region.c6binding")
public final class RealModBindingGameTest {
    @GameTest(batch = "regionC6Binding", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Core manifests reject actual original delegate rebind, replacement and inconsistent Level; actual coordinates stay frozen")
    static void originalModBindingChangesInvalidateCoreManifest(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var level = helper.getLevel().getServer().getLevel(Level.END);
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            final var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(level != null && engine != null && block != Blocks.AIR, "Original Mod and Level required");
            final var positions = List.of(new BlockPos(40010, 100, 40010), new BlockPos(40058, 100, 40010));
            final var first = positions.getFirst();
            final var replacedSource = new AtomicReference<Object>();
            helper.addEndListener(passed -> {
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
            }).thenWaitUntil(() -> helper.assertTrue(positions.stream().allMatch(p -> level.getBlockEntity(p) != null
                    && RealModWorkerMissionGameTest.counter(level.getBlockEntity(p)) >= 2), "Wait for original sources"))
                    .thenExecute(() -> {
                        try {
                            final var integrity = Class.forName("net.neoforged.neoforge.server.threading.region.CoreModRuntimeIntegrity").getDeclaredMethod("verify");
                            integrity.setAccessible(true);
                            helper.assertTrue((Boolean) integrity.invoke(null), "Actual Core integrity required");
                            final var source = level.getBlockEntity(first);
                            RealModFailureRecoveryGameTest.setCounter(source, 1);
                            final var ticker = level.snapshotBlockEntityTickers().stream().filter(t -> t.getPos().equals(first)).findFirst().orElseThrow();
                            final Object manifest = prepare(engine, admission(level, ticker));
                            helper.assertTrue(manifest != null && fresh(manifest, engine), "Positive Core-generated manifest required");
                            helper.assertTrue(RealModWorkerMissionGameTest.counter(source) == 1, "Preparation may not execute the original body");
                            final Class<?> runtime = Class.forName("net.neoforged.forgia.runtimeproof.RuntimeProof", false, null);
                            final Class<?> checkType = Class.forName("net.neoforged.forgia.runtimeproof.RuntimeProof$AccessCheck", false, null);
                            final var rogueCalls = new java.util.concurrent.atomic.AtomicInteger();
                            final Object rogue = java.lang.reflect.Proxy.newProxyInstance(null, new Class<?>[] {checkType}, (proxy, method, arguments) -> {
                                rogueCalls.incrementAndGet();
                                return true;
                            });
                            final Method protect = runtime.getMethod("protect", Object.class, checkType);
                            helper.assertTrue(!(Boolean) protect.invoke(null, source, rogue)
                                    && !(Boolean) protect.invoke(null, source.getBlockPos(), rogue) && rogueCalls.get() == 0,
                                    "A foreign callback must not replace actual mutable or frozen Core root interception");
                            final var frame = LevelChunk.captureCoreTickerFrame(ticker);
                            final Method rebind = ticker.getClass().getDeclaredMethod("rebind", TickingBlockEntity.class);
                            rebind.setAccessible(true);
                            rebind.invoke(ticker, frame.delegate());
                            helper.assertTrue(!frame.current() && !fresh(manifest, engine), "Even same-delegate rebind must invalidate the old frame");
                            final Object rebound = prepare(engine, admission(level, ticker));
                            helper.assertTrue(rebound != null && fresh(rebound, engine), "A newly checked original binding may prepare again");
                            final var oldPosition = source.getBlockPos();
                            final var coordinate = net.minecraft.core.Vec3i.class.getDeclaredField("x");
                            coordinate.setAccessible(true);
                            final int originalX = oldPosition.getX(), originalY = oldPosition.getY(), originalZ = oldPosition.getZ();
                            boolean frozen = false;
                            try {
                                coordinate.setInt(oldPosition, originalX + 1);
                            } catch (Throwable failure) {
                                Throwable cause = failure;
                                while ((cause instanceof java.lang.reflect.InvocationTargetException || cause instanceof InternalError) && cause.getCause() != null)
                                    cause = cause.getCause();
                                if (!(cause instanceof IllegalStateException)) throw new AssertionError("Unexpected frozen-coordinate failure", failure);
                                frozen = true;
                            }
                            helper.assertTrue(frozen && source.getBlockPos() == oldPosition && oldPosition.getX() == originalX
                                    && oldPosition.getY() == originalY && oldPosition.getZ() == originalZ,
                                    "Actual Core position storage must remain frozen even on coordinator; independent scalar coordinates must not change");
                            final var originalLevel = source.getLevel();
                            try {
                                source.setLevel(helper.getLevel());
                                helper.assertTrue(!fresh(rebound, engine) && prepare(engine, admission(level, ticker)) == null, "Inconsistent actual Level cannot inherit proof");
                            } finally {
                                source.setLevel(originalLevel);
                            }
                            helper.assertTrue(RealModWorkerMissionGameTest.counter(source) == 1, "Refused preflight must have no original-body effects");
                            replacedSource.set(source);
                            level.setBlock(first, Blocks.AIR.defaultBlockState(), 3);
                            level.setBlock(first, block.defaultBlockState(), 3);
                            helper.assertTrue(!fresh(rebound, engine), "Replacement must invalidate the original manifest");
                            helper.assertTrue(level.getBlockEntity(first) != source, "Replacement must have a distinct real source");
                        } catch (ReflectiveOperationException failure) {
                            throw new AssertionError(failure);
                        }
                    }).thenWaitUntil(() -> helper.assertTrue(level.getBlockEntity(first) != null
                            && level.getBlockEntity(first) != replacedSource.get()
                            && RealModWorkerMissionGameTest.counter(level.getBlockEntity(first)) >= 2, "Replacement must tick through its own original binding"))
                    .thenExecute(() -> System.out.println("C6_REAL_MOD_BINDING sameDelegateRebindRefused=true foreignCallbackReplacementRefused=true replacementRefused=true inconsistentLevelRefused=true actualPositionFrozen=true prepareBodyDelta=0 noFixtureTicketOrLease=true"))
                    .thenSucceed();
        });
    }

    private static RegionBlockEntityTickAdmission admission(net.minecraft.server.level.ServerLevel level, TickingBlockEntity ticker) {
        final var frame = LevelChunk.captureCoreTickerFrame(ticker);
        return new RegionBlockEntityTickAdmission(level, ticker, ticker.getPos(), ticker.getType(),
                new RegionBlockEntityTickAdmission.Binding(frame.delegate(), frame::current,
                        new RegionBlockEntityTickAdmission.CoreBinding(level, ticker, frame.delegate(), frame.version())),
                RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED, 0);
    }

    private static Object prepare(RegionThreadingEngine engine, RegionBlockEntityTickAdmission admission) throws ReflectiveOperationException {
        final var field = engine.getClass().getDeclaredField("coreModPreparation");
        field.setAccessible(true);
        final Object preparation = field.get(engine);
        final var method = preparation.getClass().getDeclaredMethod("prepare", RegionBlockEntityTickAdmission.class,
                Class.forName("net.neoforged.neoforge.server.threading.region.FurnaceResourcePreparation"));
        method.setAccessible(true);
        return method.invoke(preparation, admission, null);
    }

    private static boolean fresh(Object manifest, RegionThreadingEngine engine) throws ReflectiveOperationException {
        final Method method = manifest.getClass().getDeclaredMethod("fresh", RegionThreadingEngine.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(manifest, engine);
    }
}
