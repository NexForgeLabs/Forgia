/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

@ForEachTest(groups = "level.region.c6worker")
public final class RealModWriterExclusionGameTest {
    @FunctionalInterface
    interface Write {
        void run() throws Throwable;
    }

    @GameTest(batch = "regionC6Writer", timeoutTicks = 900)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "External public/reflection/MethodHandle/VarHandle/Unsafe writers cannot mutate original Mod storage while actual Core Mod Workers own it")
    static void originalModWritersObeyTheActualReservedWorker(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var level = helper.getLevel().getServer().getLevel(net.minecraft.world.level.Level.END);
            helper.assertTrue(level != null, "The C6 End level must exist");
            var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(engine != null, "Real engine missing");
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(block != Blocks.AIR, "Original upstream missing");
            int width = engine.diagnostics().regionChunkSize() * 16;
            var a = new BlockPos(10010, 100, 10010);
            var b = a.offset(width, 0, 0);
            var positions = List.of(a, b);
            long packedA = a.asLong(), packedB = b.asLong();
            var entered = new CountDownLatch(1);
            var attempted = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var actorFailure = new AtomicReference<Throwable>();
            var refused = new AtomicInteger();
            var ownedBodies = new AtomicInteger();
            var oneBody = new AtomicInteger();
            var armed = new java.util.concurrent.atomic.AtomicBoolean();
            final Object[] source = { null };
            final Thread[] actor = { null };
            engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                long pos = ticker.getPos().asLong();
                if (pos != packedA && pos != packedB) {
                    body.run();
                    return;
                }
                if (RegionThreadingHooks.isNativeRegionWorkerThread()) {
                    helper.assertTrue(BlockEntityResourceOwnership.Scope.active(), "Actual resource Scope absent");
                    ownedBodies.incrementAndGet();
                    if (pos == packedA && armed.compareAndSet(true, false)) {
                        int before = RealModWorkerMissionGameTest.counter(source[0]);
                        entered.countDown();
                        await(attempted);
                        await(exited);
                        helper.assertTrue(actorFailure.get() == null, "External writer control failed: " + actorFailure.get());
                        helper.assertTrue(RealModWorkerMissionGameTest.counter(source[0]) == before, "Foreign writer changed owned counter");
                        body.run();
                        helper.assertTrue(RealModWorkerMissionGameTest.counter(source[0]) == before + 1, "Original authorized body must execute exactly once");
                        oneBody.incrementAndGet();
                        return;
                    }
                }
                body.run();
            });
            helper.addEndListener(passed -> {
                engine.setBlockEntityBodyObserverForTesting(null);
                if (actor[0] != null) {
                    entered.countDown();
                    try {
                        actor[0].join(10000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
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
                final Object first = level.getBlockEntity(a), second = level.getBlockEntity(b);
                helper.assertTrue(first != null && second != null
                        && RealModWorkerMissionGameTest.counter(first) >= 2 && RealModWorkerMissionGameTest.counter(second) >= 2,
                        "Wait for both original Mod sources to enter their real tick path");
                source[0] = first;
            }).thenExecute(() -> {
                try {
                    Object root = source[0];
                    Class<?> type = root.getClass();
                    Field ticks = type.getDeclaredField("ticks");
                    ticks.setAccessible(true);
                    Method original = type.getMethod("maintenanceTick");
                    var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
                    var call = lookup.findVirtual(type, "maintenanceTick", MethodType.methodType(void.class));
                    var setter = lookup.unreflectSetter(ticks);
                    VarHandle variable = lookup.unreflectVarHandle(ticks);
                    Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
                    Field singleton = unsafeType.getDeclaredField("theUnsafe");
                    singleton.setAccessible(true);
                    Object unsafe = singleton.get(null);
                    long offset = (Long) unsafeType.getMethod("objectFieldOffset", Field.class).invoke(unsafe, ticks);
                    Method put = unsafeType.getMethod("putInt", Object.class, long.class, int.class), volatilePut = unsafeType.getMethod("putIntVolatile", Object.class, long.class, int.class), cas = unsafeType.getMethod("compareAndSwapInt", Object.class, long.class, int.class, int.class);
                    var routes = new LinkedHashMap<String, Write>();
                    routes.put("publicMethod", () -> original.invoke(root));
                    routes.put("reflectionField", () -> ticks.setInt(root, 1234));
                    routes.put("methodHandleEntry", () -> call.invoke(root));
                    routes.put("methodHandleField", () -> setter.invoke(root, 1234));
                    routes.put("varHandle", () -> variable.set(root, 1234));
                    routes.put("varHandleVolatile", () -> variable.setVolatile(root, 1234));
                    routes.put("varHandleCAS", () -> variable.compareAndSet(root, 0, 1234));
                    routes.put("unsafe", () -> put.invoke(unsafe, root, offset, 1234));
                    routes.put("unsafeVolatile", () -> volatilePut.invoke(unsafe, root, offset, 1234));
                    routes.put("unsafeCAS", () -> cas.invoke(unsafe, root, offset, 0, 1234));
                    actor[0] = new Thread(() -> {
                        try {
                            await(entered);
                            if (BlockEntityResourceOwnership.Scope.active() || RegionThreadingHooks.isNativeRegionWorkerThread()) throw new AssertionError("Foreign thread inherited original authority");
                            for (var route : routes.entrySet()) {
                                boolean denied = false;
                                try {
                                    route.getValue().run();
                                } catch (Throwable problem) {
                                    Throwable cause = problem;
                                    while ((cause instanceof InvocationTargetException || cause instanceof InternalError) && cause.getCause() != null) cause = cause.getCause();
                                    if (cause instanceof IllegalStateException) denied = true;
                                    else throw problem;
                                }
                                if (!denied) throw new AssertionError("Unparticipating writer escaped: " + route.getKey());
                                refused.incrementAndGet();
                            }
                        } catch (Throwable problem) {
                            actorFailure.set(problem);
                        } finally {
                            attempted.countDown();
                            exited.countDown();
                        }
                    }, "C6-unparticipating-real-Mod-writer");
                    actor[0].start();
                    armed.set(true);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }).thenWaitUntil(() -> helper.assertTrue(oneBody.get() == 1, "Wait for actual originally authorized Mod Worker invocation")).thenExecute(() -> {
                helper.assertTrue(refused.get() == 10 && actorFailure.get() == null && ownedBodies.get() >= 2, "All original writer routes must refuse under two actual Workers");
                System.out.println("C6_REAL_MOD_WRITER_EXCLUSION actualWorkerPermit=true actualResourceScope=true foreignAuthority=false rejectedRoutes=" + refused.get() + " foreignCounterDelta=0 originalBodyCounterDelta=1 physicalActorExit=true noReplay=true");
            }).thenSucceed();
        });
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Timed out awaiting real writer boundary");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
