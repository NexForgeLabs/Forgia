/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/** Isolated destructive-lifecycle test worlds; always uses the existing fixed executor and authority. */
@ForEachTest(groups = "level.region.c6lifecycle")
public final class RealModLifecycleGameTest {
    @GameTest(batch = "regionC6Lifecycle", timeoutTicks = 1200)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Queued/running original Mod work retains exclusion until actual join during Level clear or engine shutdown")
    static void originalModLifecycleWaitsForPhysicalJoin(final DynamicTest test) {
        test.onGameTest(helper -> {
            final String scenario = System.getProperty("forgia.runtimeProof.lifecycleCase", "");
            helper.assertTrue(List.of("queued-unload", "queued-shutdown", "running-unload", "running-shutdown", "releasing-unload", "releasing-shutdown").contains(scenario), "Explicit lifecycle scenario required");
            final boolean queued = scenario.startsWith("queued"), releasing = scenario.startsWith("releasing"), shutdown = scenario.endsWith("shutdown");
            final Thread coordinator = Thread.currentThread();
            final var level = helper.getLevel().getServer().getLevel(Level.END);
            final var engine = (RegionThreadingEngine) RegionThreadingManager.currentOrNull();
            helper.assertTrue(level != null && engine != null, "Real Level and engine required");
            final var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(block != Blocks.AIR, "Original artifact missing");
            final var a = new BlockPos(30010, 100, 30010);
            final var positions = List.of(a, a.offset(engine.diagnostics().regionChunkSize() * 16, 0, 0));
            helper.startSequence().thenExecute(() -> {
                for (var p : positions) {
                    level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
                    level.setBlock(p, block.defaultBlockState(), 3);
                }
            }).thenWaitUntil(() -> helper.assertTrue(positions.stream().allMatch(p -> level.getBlockEntity(p) != null
                    && RealModWorkerMissionGameTest.counter(level.getBlockEntity(p)) >= 2), "Wait for original sources"))
                    .thenExecute(() -> {
                        final List<Object> roots = positions.stream().map(p -> (Object) level.getBlockEntity(p)).toList();
                        roots.forEach(root -> RealModFailureRecoveryGameTest.setCounter(root, 1));
                        final var parked = new CountDownLatch(2);
                        final var release = new CountDownLatch(1);
                        final var crossChecked = new CountDownLatch(2);
                        final var completed = new AtomicInteger();
                        final var workers = new AtomicInteger();
                        final var serial = new AtomicInteger();
                        final var foreignRefusals = new AtomicInteger();
                        final var crossRegionRefusals = new AtomicInteger();
                        final var controllerFailure = new AtomicReference<Throwable>();
                        final var cleanupFailure = new AtomicReference<Throwable>();
                        final var cleanupReturned = new CountDownLatch(1);
                        final Object[] pool = {null};
                        final ExecutorService[] originalExecutor = {null};
                        try {
                            if (queued || releasing) {
                                pool[0] = field(engine, "workerPool").get(engine);
                                final Field executor = field(pool[0], "executor");
                                originalExecutor[0] = (ExecutorService) executor.get(pool[0]);
                                helper.assertTrue(originalExecutor[0] != null, "Queued control requires existing fixed pool");
                                if (queued) executor.set(pool[0], new ParkedExecutor(originalExecutor[0], parked, release));
                                else executor.set(pool[0], new ReleasingExecutor(originalExecutor[0], parked, release));
                            }
                        } catch (ReflectiveOperationException problem) {
                            throw new AssertionError(problem);
                        }
                        engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                            if (positions.stream().noneMatch(p -> p.asLong() == ticker.getPos().asLong())) {
                                body.run();
                                return;
                            }
                            final boolean worker = RegionThreadingHooks.isNativeRegionWorkerThread();
                            helper.assertTrue(worker ? BlockEntityResourceOwnership.Scope.active()
                                    : queued && Thread.currentThread() == coordinator && !BlockEntityResourceOwnership.Scope.active(),
                                    "A started body needs its actual Core authority; only an unstarted queued refusal may fall back serial");
                            if (worker) {
                                final Object otherRoot = ticker.getPos().equals(positions.getFirst()) ? roots.get(1) : roots.get(0);
                                try {
                                    refuseWriters(otherRoot, crossRegionRefusals);
                                } catch (Exception failure) {
                                    throw new AssertionError("Cross-Region writer control failed", failure);
                                } finally {
                                    crossChecked.countDown();
                                }
                                await(crossChecked);
                            }
                            if (!queued && !releasing) {
                                parked.countDown();
                                await(release);
                            }
                            body.run();
                            completed.incrementAndGet();
                            if (worker) workers.incrementAndGet();
                            else serial.incrementAndGet();
                        });
                        final Thread cleanup = new Thread(() -> {
                            try {
                                if (shutdown) engine.shutdown();
                                else engine.clearLevelRegionTasks(level);
                            } catch (Throwable failure) {
                                cleanupFailure.set(failure);
                            } finally {
                                cleanupReturned.countDown();
                            }
                        }, "C6-real-Mod-lifecycle-cleanup");
                        final Thread controller = new Thread(() -> {
                            try {
                                await(parked);
                                if (releasing) {
                                    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                    boolean sealing = false;
                                    while (!sealing && System.nanoTime() < deadline) {
                                        for (var frame : coordinator.getStackTrace())
                                            if (frame.getMethodName().equals("sealForCommit")) sealing = true;
                                        Thread.yield();
                                    }
                                    if (!sealing) throw new AssertionError("Coordinator did not reach post-join guard sealing");
                                }
                                refuseWriters(roots.getFirst(), foreignRefusals);
                                cleanup.start();
                                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                boolean waiting = false;
                                while (!waiting && System.nanoTime() < deadline && cleanupReturned.getCount() != 0) {
                                    for (var frame : cleanup.getStackTrace())
                                        if (frame.getMethodName().equals("awaitActiveTickBatches")) waiting = true;
                                    Thread.yield();
                                }
                                if (!waiting || cleanupReturned.getCount() == 0) throw new AssertionError("Lifecycle cleanup failed to wait inside the actual published phase");
                                refuseWriters(roots.getFirst(), foreignRefusals);
                            } catch (Throwable failure) {
                                controllerFailure.set(failure);
                            } finally {
                                release.countDown();
                            }
                        }, "C6-real-Mod-lifecycle-controller");
                        controller.start();
                        try {
                            RealModFailureRecoveryGameTest.originalLevelPhase(level);
                        } finally {
                            release.countDown();
                            engine.setBlockEntityBodyObserverForTesting(null);
                            try {
                                controller.join(15000);
                                if (cleanup.getState() != Thread.State.NEW) cleanup.join(15000);
                                if (queued || releasing) field(pool[0], "executor").set(pool[0], originalExecutor[0]);
                            } catch (ReflectiveOperationException | InterruptedException failure) {
                                throw new AssertionError(failure);
                            }
                        }
                        helper.assertTrue(!controller.isAlive() && !cleanup.isAlive() && cleanupReturned.getCount() == 0, "Both control threads must physically return");
                        helper.assertTrue(controllerFailure.get() == null && cleanupFailure.get() == null, "Lifecycle controls failed: " + controllerFailure.get() + " / " + cleanupFailure.get());
                        helper.assertTrue(completed.get() == 2 && roots.stream().allMatch(root -> RealModWorkerMissionGameTest.counter(root) == 2), "Only two original authorized increments may remain");
                        helper.assertTrue(foreignRefusals.get() == 4 && !BlockEntityResourceOwnership.Scope.active(), "No foreign writer or coordinator resource authority");
                        helper.assertTrue(crossRegionRefusals.get() == workers.get() * 2, "Every actual Worker must refuse the other Region's original source");
                        helper.assertTrue(queued || workers.get() == 2 && serial.get() == 0, "Running/releasing original bodies must remain Worker executions");
                        System.out.println("C6_REAL_MOD_LIFECYCLE scenario=" + scenario + " originalWorkerBodies=" + workers.get()
                                + " serialBodies=" + serial.get() + " crossRegionRejectedRoutes=" + crossRegionRefusals.get()
                                + " completeOriginalBodies=2 counterDelta=2 foreignWrites=0 rejectedRoutes=4 cleanupWaitObserved=true physicalJoin=true noReplay=true");
                        helper.succeed();
                    });
        });
    }

    private static void refuseWriters(Object root, AtomicInteger refused) throws Exception {
        final var field = root.getClass().getDeclaredField("ticks");
        field.setAccessible(true);
        final var original = root.getClass().getMethod("maintenanceTick");
        for (int route = 0; route < 2; route++) {
            try {
                if (route == 0) field.setInt(root, 1234);
                else original.invoke(root);
                throw new AssertionError("Foreign original writer accepted");
            } catch (Throwable failure) {
                Throwable cause = failure;
                while ((cause instanceof InvocationTargetException || cause instanceof InternalError) && cause.getCause() != null)
                    cause = cause.getCause();
                if (!(cause instanceof IllegalStateException)) throw new AssertionError("Unexpected foreign writer failure", failure);
                refused.incrementAndGet();
            }
        }
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Lifecycle gate timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    /** Holds the actual Region monitor after the original task returns, pausing guard sealing. */
    private static final class ReleasingExecutor extends AbstractExecutorService {
        private final ExecutorService original;
        private final CountDownLatch parked, release;

        ReleasingExecutor(ExecutorService original, CountDownLatch parked, CountDownLatch release) {
            this.original = original;
            this.parked = parked;
            this.release = release;
        }

        private static Object actualRegion(Runnable task) {
            final var pending = new java.util.ArrayDeque<Object>();
            final var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
            pending.add(task);
            try {
                while (!pending.isEmpty() && seen.size() < 64) {
                    final Object current = pending.removeFirst();
                    if (!seen.add(current)) continue;
                    final String name = current.getClass().getName();
                    if (name.endsWith("BlockEntityExecutionEngine$RegionGuard")) return field(current, "region").get(current);
                    final boolean group = name.endsWith("RegionTaskBatch$Group");
                    if (!(current instanceof Runnable) && !group) continue;
                    for (final Field field : current.getClass().getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        final Object value = field.get(current);
                        if (value instanceof Runnable || value != null && (value.getClass().getName().endsWith("RegionTaskBatch$Group")
                                || value.getClass().getName().endsWith("BlockEntityExecutionEngine$RegionGuard"))) pending.add(value);
                        else if (group && value instanceof List<?> works) pending.addAll(works);
                    }
                }
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
            throw new AssertionError("Cannot identify the original submitted invocation's actual Region guard");
        }

        @Override
        public void execute(Runnable task) {
            final Object home = actualRegion(task);
            this.original.execute(() -> {
                synchronized (home) {
                    task.run();
                    this.parked.countDown();
                    await(this.release);
                }
            });
        }

        @Override
        public void shutdown() { this.original.shutdown(); }
        @Override
        public List<Runnable> shutdownNow() { return this.original.shutdownNow(); }
        @Override
        public boolean isShutdown() { return this.original.isShutdown(); }
        @Override
        public boolean isTerminated() { return this.original.isTerminated(); }
        @Override
        public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException { return this.original.awaitTermination(time, unit); }
    }

    /** Delegation only: no new pool, thread, Ticket, reservation or Worker grant. */
    private static final class ParkedExecutor extends AbstractExecutorService {
        private final ExecutorService original;
        private final CountDownLatch parked, release;

        ParkedExecutor(ExecutorService original, CountDownLatch parked, CountDownLatch release) {
            this.original = original;
            this.parked = parked;
            this.release = release;
        }

        @Override
        public void execute(Runnable task) {
            this.original.execute(() -> {
                this.parked.countDown();
                await(this.release);
                task.run();
            });
        }

        @Override
        public void shutdown() { this.original.shutdown(); }
        @Override
        public List<Runnable> shutdownNow() { return this.original.shutdownNow(); }
        @Override
        public boolean isShutdown() { return this.original.isShutdown(); }
        @Override
        public boolean isTerminated() { return this.original.isTerminated(); }
        @Override
        public boolean awaitTermination(long time, TimeUnit unit) throws InterruptedException { return this.original.awaitTermination(time, unit); }
    }
}
