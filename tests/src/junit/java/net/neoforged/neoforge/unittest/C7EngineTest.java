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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.neoforged.neoforge.server.threading.c7.C7AccessScope;
import net.neoforged.neoforge.server.threading.c7.C7Engine;
import net.neoforged.neoforge.server.threading.c7.C7Footprint;
import net.neoforged.neoforge.server.threading.c7.C7Options;
import net.neoforged.neoforge.server.threading.c7.C7Resource;
import net.neoforged.neoforge.server.threading.c7.C7Task;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class C7EngineTest {
    private static final C7Options OPTIONS = new C7Options(C7Options.Mode.PARALLEL, 2, 64, 64, 4096, 64,
            Long.MAX_VALUE, false, true, false);
    private static final RegionTickBarrier BARRIER = done -> {
        while (!done.getAsBoolean()) Thread.onSpinWait();
    };

    private static C7Resource key() {
        return new C7Resource(C7EngineTest.class, new Object(), C7Resource.Kind.GLOBAL);
    }

    private static C7Options options(int waves, int tasks, int resources, long straggler) {
        return new C7Options(C7Options.Mode.PARALLEL, 2, 64, waves, tasks, resources, straggler, false, true, false);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "worker coordination timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static RegionWorkerExecutor executor(ExecutorService pool) {
        return (description, work) -> {
            pool.execute(work);
            return true;
        };
    }

    private static final class Task implements C7Task {
        final Object identity = new Object();
        final C7Resource key = key();
        final Thread coordinator = Thread.currentThread();
        final AtomicInteger computes = new AtomicInteger();
        int value;
        int serials;
        int commits;
        boolean current = true;
        boolean prepareRefused;
        Runnable compute = () -> {};
        Runnable committed = () -> {};
        Runnable serial = () -> {};
        BooleanSupplier valid = () -> true;
        C7Footprint footprint = new C7Footprint(Map.of(this.key, C7Footprint.Access.WRITE), true);

        @Override
        public Object identity() {
            return this.identity;
        }

        @Override
        public C7Footprint footprint() {
            return this.footprint;
        }

        @Override
        public boolean current() {
            return this.current;
        }

        @Override
        public void serial() {
            ++this.serials;
            ++this.value;
            this.serial.run();
        }

        @Override
        public Prepared prepare() {
            if (this.prepareRefused) return null;
            final int snapshot = this.value;
            return new Prepared() {
                @Override
                public C7Task.Computation computation() {
                    return scope -> {
                        computes.incrementAndGet(); compute.run();
                        return scope.read(key, () -> snapshot + 1);
                    };
                }

                @Override
                public boolean valid() {
                    return valid.getAsBoolean() && value == snapshot;
                }

                @Override
                public void commit(Object result) {
                    assertEquals(coordinator, Thread.currentThread(), "live commit must stay on coordinator");
                    ++commits;
                    value = (Integer) result;
                    committed.run();
                }
            };
        }
    }

    @Test
    void independentTasksActuallyOverlapAndCommitExactlyOnce() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            final var together = new CountDownLatch(2);
            final Runnable rendezvous = () -> {
                together.countDown();
                await(together);
            };
            a.compute = rendezvous;
            b.compute = rendezvous;
            engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER);
            assertEquals(2, engine.metrics().maxConcurrent());
            assertEquals(2, engine.metrics().parallel());
            assertEquals(2, engine.metrics().types().get("<internal>").collected());
            assertEquals(2, engine.metrics().types().get("<internal>").parallel());
            assertTrue(engine.metrics().types().get("<internal>").serialReasons().isEmpty());
            assertEquals(1, a.commits);
            assertEquals(1, b.commits);
            assertEquals(0, a.serials + b.serials);
            assertEquals(1, a.value);
            assertEquals(1, b.value);
        }
    }

    @Test
    void commitOrderRemainsCanonicalAcrossDifferentReadyWaves() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var order = new ArrayList<String>();
            final var a = new Task();
            final var b = new Task();
            final var c = new Task();
            b.footprint = a.footprint;
            a.committed = () -> order.add("a");
            a.serial = a.committed;
            b.committed = () -> order.add("b");
            b.serial = b.committed;
            c.committed = () -> order.add("c");
            c.serial = c.committed;
            engine.execute(List.of(a, b, c), OPTIONS, executor(pool), BARRIER);
            assertEquals(List.of("a", "b", "c"), order);
            assertEquals(1, a.value);
            assertEquals(1, b.value);
            assertEquals(1, c.value);
            assertTrue(engine.metrics().dependencies() >= 1);
        }
    }

    @Test
    void unknownSerialFenceNeverOverlapsWorkers() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var active = new AtomicInteger();
            final var a = new Task();
            final var b = new Task();
            final var fence = new Task();
            a.compute = active::incrementAndGet;
            b.compute = active::incrementAndGet;
            a.committed = active::decrementAndGet;
            b.committed = active::decrementAndGet;
            fence.footprint = C7Footprint.unknown();
            fence.serial = () -> assertEquals(0, active.get());
            engine.execute(List.of(a, b, fence), OPTIONS, executor(pool), BARRIER);
            assertEquals(1, fence.serials);
            assertEquals(0, fence.computes.get());
        }
    }

    @Test
    void undeclaredAccessAbortsBeforeCommitAndDemotesOnlyFutureInvocation() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.footprint = new C7Footprint(Map.of(key(), C7Footprint.Access.READ), true);
            assertThrows(C7AccessScope.UndeclaredAccessException.class, () -> engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER));
            assertEquals(0, a.commits + b.commits + a.serials + b.serials);
            assertEquals(1, engine.metrics().dynamicDiscoveries());
            assertEquals(1L, engine.metrics().undeclaredKinds().get(C7Resource.Kind.GLOBAL));
            assertEquals(1, engine.metrics().types().get("<internal>").failed());
            assertEquals(1, engine.metrics().failed());
            engine.execute(List.of(a), OPTIONS, executor(pool), BARRIER);
            assertEquals(1, a.serials);
            assertEquals(1, a.computes.get(), "no same-invocation replay");
        }
    }

    @Test
    void workerFailureDoesNotRetryOrCommitAnyResultOfThatWave() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.compute = () -> {
                throw new IllegalStateException("injected compute failure");
            };
            assertThrows(IllegalStateException.class, () -> engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER));
            assertEquals(0, a.value + b.value);
            assertEquals(0, a.serials + b.serials);
            assertEquals(1, engine.metrics().failed());
        }
    }

    @Test
    void validationFailureIsReportedAndNeverReplayed() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.valid = () -> {
                throw new IllegalStateException("validation callback failure");
            };
            assertThrows(IllegalStateException.class, () -> engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER));
            assertEquals(0, a.value + b.value);
            assertEquals(0, a.serials + b.serials);
            assertEquals(1, engine.metrics().failed());
            assertEquals(1, engine.metrics().types().get("<internal>").failed());
        }
    }

    @Test
    void partialCommitFailureIsNeverRetried() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.committed = () -> {
                throw new IllegalStateException("after first live write");
            };
            assertThrows(IllegalStateException.class, () -> engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER));
            assertEquals(1, a.value);
            assertEquals(0, b.value);
            assertEquals(1, a.commits);
            assertEquals(0, a.serials + b.serials);
            assertEquals(1, engine.metrics().failed());
        }
    }

    @Test
    void staleDetachedSnapshotCanBeDiscardedBeforeAnOriginalSerialBody() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            final RegionTickBarrier invalidation = done -> {
                BARRIER.await(done);
                a.value = 10;
            };
            engine.execute(List.of(a, b), OPTIONS, executor(pool), invalidation);
            assertEquals(11, a.value);
            assertEquals(1, a.serials);
            assertEquals(0, a.commits);
            assertEquals(1, b.value);
            assertEquals(1, engine.metrics().invalidated());
        }
    }

    @Test
    void unsupportedFrontierDiscardsLaterSpeculationAndRecapturesAfterSerialEffects() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            final var c = new Task();
            final var d = new Task();
            b.footprint = a.footprint;
            b.prepareRefused = true;
            b.serial = () -> c.value = 20;
            engine.execute(List.of(a, b, c, d), OPTIONS, executor(pool), BARRIER);
            assertEquals(1, b.serials);
            assertEquals(21, c.value);
            assertEquals(1, c.commits);
            assertEquals(0, c.serials);
            assertEquals(2, c.computes.get(), "only detached arithmetic was recomputed, never a live tick");
        }
    }

    @Test
    void removedTaskIsSkippedRatherThanRetried() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.current = false;
            engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER);
            assertEquals(0, a.value);
            assertEquals(1, b.value);
            assertEquals(1, engine.metrics().skipped());
        }
    }

    @Test
    void rejectionAndAcceptThenThrowCannotDuplicateAComputation() {
        for (int kind = 0; kind < 3; ++kind) {
            final int rejection = kind;
            try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
                final var a = new Task();
                final var b = new Task();
                final RegionWorkerExecutor rejecting = (description, work) -> {
                    if (rejection > 0) pool.execute(work);
                    if (rejection == 2) throw new IllegalStateException("accepted then threw");
                    return false;
                };
                engine.execute(List.of(a, b), OPTIONS, rejecting, BARRIER);
                assertEquals(1, a.computes.get());
                assertEquals(1, b.computes.get());
                assertEquals(1, a.commits);
                assertEquals(1, b.commits);
                assertEquals(2, engine.metrics().rejected());
            }
        }
    }

    @Test
    void barrierFailureStillPhysicallyJoinsEveryAcceptedRunner() throws Exception {
        try (final var pool = Executors.newFixedThreadPool(2); final var caller = Executors.newSingleThreadExecutor(); final var engine = new C7Engine()) {
            final var started = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var a = new Task();
            final var b = new Task();
            a.compute = () -> {
                started.countDown();
                await(release);
            };
            final var future = caller.submit(() -> engine.execute(List.of(a, b), OPTIONS, executor(pool), done -> {
                throw new IllegalStateException("barrier failure");
            }));
            try {
                await(started);
                assertFalse(future.isDone(), "accepted runner is still physically active");
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertEquals(0, a.commits + b.commits + a.serials + b.serials);
        }
    }

    @Test
    void workerCannotReenterOrCloseItsOwnEngine() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            a.compute = () -> {
                assertThrows(IllegalStateException.class, engine::close);
                assertThrows(IllegalStateException.class, () -> engine.execute(List.of(), OPTIONS, executor(pool), BARRIER));
            };
            engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER);
            assertEquals(2, engine.metrics().parallel());
        }
    }

    @Test
    void stragglersAreDemotedOnTheNextTickNotReplayed() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var a = new Task();
            final var b = new Task();
            engine.execute(List.of(a, b), options(64, 64, 64, 1), executor(pool), BARRIER);
            engine.execute(List.of(a, b), OPTIONS, executor(pool), BARRIER);
            assertEquals(2, engine.metrics().demotions());
            assertEquals(1, a.computes.get());
            assertEquals(1, b.computes.get());
            assertEquals(2, a.value);
            assertEquals(2, b.value);
        }
    }

    @Test
    void taskWaveAndResourceLimitsFallBackBeforeAnyEffects() {
        try (final var pool = Executors.newFixedThreadPool(2)) {
            for (int kind = 0; kind < 3; ++kind) {
                try (final var engine = new C7Engine()) {
                    final var a = new Task();
                    final var b = new Task();
                    if (kind == 0) b.footprint = a.footprint;
                    if (kind == 2) for (final var task : List.of(a, b)) task.footprint = new C7Footprint(Map.of(task.key,
                            C7Footprint.Access.WRITE, key(), C7Footprint.Access.READ), true);
                    final var limits = options(kind == 0 ? 1 : 64, kind == 1 ? 1 : 64, kind == 2 ? 1 : 64, Long.MAX_VALUE);
                    engine.execute(List.of(a, b), limits, executor(pool), BARRIER);
                    assertEquals(0, a.computes.get() + b.computes.get());
                    assertEquals(2, engine.metrics().serial());
                }
            }
        }
    }

    @Test
    void shadowAndClosedEnginesCannotDispatchWork() {
        final var engine = new C7Engine();
        final var a = new Task();
        final var b = new Task();
        final RegionWorkerExecutor forbidden = (description, work) -> {
            fail("must not dispatch");
            return false;
        };
        final var shadow = new C7Options(C7Options.Mode.SHADOW, 2, 64, 64, 64, 64, Long.MAX_VALUE, false, true, true);
        engine.execute(List.of(a, b), shadow, forbidden, BARRIER);
        assertEquals(2, engine.metrics().serial());
        assertTrue(engine.metrics().graphNanos() > 0);
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.execute(List.of(), OPTIONS, forbidden, BARRIER));
    }

    @Test
    void oversizedAndConservativeTailsDoNotEvenInvokeProofCallbacks() {
        final var serial = new AtomicInteger();
        final C7Task forbiddenProof = new C7Task() {
            @Override
            public Object identity() {
                return this;
            }

            @Override
            public C7Footprint footprint() {
                throw new AssertionError("proof callback after hard refusal");
            }

            @Override
            public boolean current() {
                return true;
            }

            @Override
            public Prepared prepare() {
                throw new AssertionError("prepare after hard refusal");
            }

            @Override
            public void serial() {
                serial.incrementAndGet();
            }
        };
        final var first = new Task();
        try (final var engine = new C7Engine()) {
            engine.execute(List.of(first, forbiddenProof), options(64, 1, 64, Long.MAX_VALUE),
                    (description, work) -> {
                        throw new AssertionError("must remain serial");
                    }, BARRIER);
            assertEquals(1, first.serials);
            assertEquals(1, serial.get());
        }
        first.footprint = C7Footprint.unknown();
        final var conservative = new C7Options(C7Options.Mode.PARALLEL, 2, 64, 64, 64, 64, Long.MAX_VALUE, false, true, true);
        try (final var engine = new C7Engine()) {
            engine.execute(List.of(first, forbiddenProof), conservative,
                    (description, work) -> {
                        throw new AssertionError("must remain serial");
                    }, BARRIER);
            assertEquals(2, first.serials);
            assertEquals(2, serial.get());
        }
    }

    @Test
    void duplicateIdentityIsRejectedBeforeAnyWork() {
        try (final var engine = new C7Engine()) {
            final var a = new Task();
            assertThrows(IllegalArgumentException.class, () -> engine.execute(List.of(a, a), OPTIONS, (description, work) -> false, BARRIER));
            assertEquals(0, a.value);
            assertEquals(0, engine.metrics().collected());
        }
    }

    @Test
    void hundredsOfIndependentTasksCompleteExactlyOnceWithBoundedLanes() {
        try (final var pool = Executors.newFixedThreadPool(2); final var engine = new C7Engine()) {
            final var tasks = new ArrayList<Task>();
            for (int i = 0; i < 1000; ++i) tasks.add(new Task());
            engine.execute(tasks, OPTIONS, executor(pool), BARRIER);
            for (final var task : tasks) assertEquals(1, task.value);
            assertEquals(1000, engine.metrics().collected());
            assertEquals(1000, engine.metrics().parallel());
            assertTrue(engine.metrics().maxConcurrent() <= 2);
            assertTrue(engine.metrics().maxWave() <= 64);
        }
    }
}
