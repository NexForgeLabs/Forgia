/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickEntry;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Phase-order contracts; real worker/lease/world behavior remains covered by RegionChunkTickTest. */
@Timeout(10)
class RegionChunkTickCoordinatorTest {
    private static final RegionTickBarrier BARRIER = condition -> assertTrue(condition.getAsBoolean());
    private static final int RANDOM_TICK_SPEED = 3;

    @Test
    void emptyCollectionNeverPublishesOrStartsWork() {
        final RecordingHost host = new RecordingHost();
        execute(host, List.of());
        assertEquals(List.of(), host.events);
        assertEquals(0, host.budgetReads);
        assertNull(host.batch);
    }

    @Test
    void publishedBatchIsTheSameThroughLeaseJoinAndCompletion() {
        final RecordingHost host = new RecordingHost();
        final List<RegionChunkTickEntry> chunks = chunks();
        execute(host, chunks);
        assertEquals(List.of("publish", "lease", "join", "complete"), host.events);
        assertSame(chunks, host.leasedChunks);
        assertEquals(RANDOM_TICK_SPEED, call(host.batch, "randomTickSpeed"));
        assertEquals(1, host.budgetReads);
        assertTrue((Boolean) call(host.batch, "isComplete"));
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void retiredLevelSkipsAllCollectedChunksWithoutLeasingOrFallback() {
        final RecordingHost host = new RecordingHost();
        host.publication = "LEVEL_CLOSED";
        execute(host, chunks());
        assertEquals(List.of("publish", "skip"), host.events);
        assertEquals(2, host.skipped);
        assertTrue(host.inline.isEmpty());
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void shutdownUsesTheOriginalInlinePathInCollectionOrder() {
        final RecordingHost host = new RecordingHost();
        host.publication = "SHUTDOWN";
        final List<RegionChunkTickEntry> chunks = chunks();
        execute(host, chunks);
        assertEquals(List.of("publish", "inline", "inline"), host.events);
        assertEquals(chunks, host.inline);
        assertEquals(0, host.skipped);
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void leasingFailureStillJoinsBeforeCompletingAndNeverReplaysInline() {
        final RecordingHost host = new RecordingHost();
        final RuntimeException failure = new IllegalStateException("lease failed after partial setup");
        host.leaseFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> execute(host, chunks())));
        assertEquals(List.of("publish", "lease", "join", "complete"), host.events);
        assertSame(failure, host.completionInput);
        assertTrue(host.inline.isEmpty());
        assertTrue((Boolean) call(host.batch, "isComplete"));
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void joinFailureStillCompletesAndKeepsTheOriginalThrowable() {
        final RecordingHost host = new RecordingHost();
        final RuntimeException failure = new IllegalStateException("join failure");
        host.joinFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> execute(host, chunks())));
        assertSame(failure, host.completionInput);
        assertEquals(List.of("publish", "lease", "join", "complete"), host.events);
        assertTrue((Boolean) call(host.batch, "isComplete"));
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void anEarlierLeaseFailureRetainsTheExistingFailurePrecedence() {
        final RecordingHost host = new RecordingHost();
        final RuntimeException first = new IllegalStateException("lease");
        host.leaseFailure = first;
        host.joinFailure = new IllegalArgumentException("join");
        assertSame(first, assertThrows(IllegalStateException.class, () -> execute(host, chunks())));
        assertEquals(0, first.getSuppressed().length, "this extraction must not silently change failure aggregation policy");
        assertEquals(List.of("publish", "lease", "join", "complete"), host.events);
    }

    @Test
    void fatalErrorsAreRethrownOnlyAfterTheBatchHasCompleted() {
        final RecordingHost host = new RecordingHost();
        final AssertionError failure = new AssertionError("worker setup");
        host.leaseFailure = failure;
        assertSame(failure, assertThrows(AssertionError.class, () -> execute(host, chunks())));
        assertTrue((Boolean) call(host.batch, "isComplete"));
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    @Test
    void checkedCompletionFailureRetainsTheOriginalWrapperAndCause() {
        final RecordingHost host = new RecordingHost();
        host.completionFailure = new IOException("handoff");
        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> execute(host, chunks()));
        assertEquals("Region tick batch failed", failure.getMessage());
        assertSame(host.completionFailure, failure.getCause());
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue((Boolean) call(host.batch, "isComplete"));
        assertFalse((Boolean) call(host.batch, "isExecutingOnCurrentThread"));
    }

    private static void execute(RecordingHost host, List<RegionChunkTickEntry> chunks) {
        try {
            final Class<?> coordinator = Class.forName("net.neoforged.neoforge.server.threading.region.RegionChunkTickCoordinator");
            final Class<?> bridge = Class.forName(coordinator.getName() + "$Host");
            final Constructor<?> constructor = coordinator.getDeclaredConstructor(bridge);
            constructor.setAccessible(true);
            call(constructor.newInstance(host.proxy(bridge)), "executeAndAwait", host.level, chunks, RANDOM_TICK_SPEED, BARRIER);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object call(Object target, String name, Object... arguments) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (final Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
                try {
                    method.setAccessible(true);
                    return method.invoke(target, arguments);
                } catch (InvocationTargetException exception) {
                    if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
                    if (exception.getCause() instanceof Error error) throw error;
                    throw new AssertionError(exception.getCause());
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            }
        }
        throw new AssertionError("Missing method " + name);
    }

    private static List<RegionChunkTickEntry> chunks() {
        return List.of(chunk(1), chunk(4));
    }

    private static RegionChunkTickEntry chunk(int x) {
        final LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getPos()).thenReturn(new ChunkPos(x, 1));
        return new RegionChunkTickEntry(chunk.getPos().toLong(), chunk);
    }

    private static final class RecordingHost {
        private final ServerLevel level = mock(ServerLevel.class);
        private final List<String> events = new ArrayList<>();
        private final List<RegionChunkTickEntry> inline = new ArrayList<>();
        private String publication = "PUBLISHED";
        private Object batch;
        private List<RegionChunkTickEntry> leasedChunks;
        private int skipped;
        private int budgetReads;
        private Throwable leaseFailure;
        private Throwable joinFailure;
        private Throwable completionFailure;
        private Throwable completionInput;

        public int blockUpdateEntryBudget() {
            ++this.budgetReads;
            return 1024;
        }

        public Object publish(Object batch) {
            this.events.add("publish");
            this.batch = batch;
            assertSame(this.level, call(batch, "level"));
            assertFalse((Boolean) call(batch, "isExecutingOnCurrentThread"));
            try {
                final Class<?> type = Class.forName("net.neoforged.neoforge.server.threading.region.TickBatchPublication");
                final var field = type.getDeclaredField(this.publication);
                field.setAccessible(true);
                return field.get(null);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        public void countSkippedChunks(int count) {
            this.events.add("skip");
            this.skipped += count;
        }

        public void tickOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed) {
            this.events.add("inline");
            assertSame(this.level, level);
            assertEquals(RANDOM_TICK_SPEED, randomTickSpeed);
            this.inline.add(chunk);
        }

        public void leaseGroups(Object batch, List<RegionChunkTickEntry> chunks, RegionTickBarrier barrier) {
            this.events.add("lease");
            assertSame(this.batch, batch);
            assertSame(BARRIER, barrier);
            assertTrue((Boolean) call(batch, "isExecutingOnCurrentThread"));
            this.leasedChunks = chunks;
            throwIfPresent(this.leaseFailure);
        }

        public void submitAndAwait(Object batch, RegionTickBarrier barrier) {
            this.events.add("join");
            assertSame(this.batch, batch);
            assertSame(BARRIER, barrier);
            assertTrue((Boolean) call(batch, "isExecutingOnCurrentThread"));
            call(batch, "submitAndAwait", (RegionWorkerExecutor) (description, task) -> {
                task.run();
                return true;
            }, barrier);
            throwIfPresent(this.joinFailure);
        }

        public Throwable complete(Object batch, Throwable failure) {
            this.events.add("complete");
            assertSame(this.batch, batch);
            assertTrue((Boolean) call(batch, "isExecutingOnCurrentThread"));
            assertTrue((Boolean) call(batch, "isGroupsComplete"), "completion must follow physical join");
            this.completionInput = failure;
            call(batch, "finish");
            call(batch, "clearExecutingOnCurrentThread");
            return this.completionFailure != null ? this.completionFailure : failure;
        }

        @SuppressWarnings("unchecked")
        private Object proxy(Class<?> bridge) {
            return Proxy.newProxyInstance(bridge.getClassLoader(), new Class<?>[] { bridge }, (proxy, method, arguments) -> {
                return switch (method.getName()) {
                    case "blockUpdateEntryBudget" -> this.blockUpdateEntryBudget();
                    case "publish" -> this.publish(arguments[0]);
                    case "countSkippedChunks" -> {
                        this.countSkippedChunks((Integer) arguments[0]);
                        yield null;
                    }
                    case "tickOnServerThread" -> {
                        this.tickOnServerThread((ServerLevel) arguments[0], (RegionChunkTickEntry) arguments[1], (Integer) arguments[2]);
                        yield null;
                    }
                    case "leaseGroups" -> {
                        this.leaseGroups(arguments[0], (List<RegionChunkTickEntry>) arguments[1], (RegionTickBarrier) arguments[2]);
                        yield null;
                    }
                    case "submitAndAwait" -> {
                        this.submitAndAwait(arguments[0], (RegionTickBarrier) arguments[1]);
                        yield null;
                    }
                    case "complete" -> this.complete(arguments[0], (Throwable) arguments[1]);
                    case "toString" -> "Recording chunk host";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(method.getName());
                };
            });
        }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) throw runtimeException;
            if (failure instanceof Error error) throw error;
        }
    }
}
