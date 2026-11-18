/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate;
import org.junit.jupiter.api.Test;

class RegionPlayerNetworkGateTest {
    private final RegionPlayerNetworkGate gate = new RegionPlayerNetworkGate();
    private final MinecraftServer server = mock(MinecraftServer.class);
    private final Connection connection = mock(Connection.class);
    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

    RegionPlayerNetworkGateTest() {
        when(this.server.isSameThread()).thenReturn(true);
        doAnswer(call -> {
            this.queued.add(call.getArgument(0));
            return null;
        }).when(this.server).execute(any(Runnable.class));
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RegionPlayerNetworkGate.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(this.gate, args);
    }

    private boolean freeze() throws Exception {
        return (boolean) invoke("freeze", new Class<?>[] { MinecraftServer.class }, this.server);
    }

    private void thaw() throws Exception {
        invoke("thaw", new Class<?>[0]);
    }

    private RegionPlayerNetworkGate.Scope enter(Runnable work, Object retained) throws Exception {
        return (RegionPlayerNetworkGate.Scope) invoke("enter", new Class<?>[] { MinecraftServer.class, Connection.class, Runnable.class, Object.class }, this.server, this.connection, work, retained);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "success", "failure", "cancel", "close", "rejected" })
    void declaredPayloadCallbacksResumeOnOriginalNetworkLoopOnlyAfterThaw(String outcome) throws Exception {
        var channel = mock(io.netty.channel.Channel.class);
        var loop = mock(io.netty.channel.EventLoop.class);
        when(this.connection.channel()).thenReturn(channel);
        when(channel.eventLoop()).thenReturn(loop);
        var network = new ArrayDeque<Runnable>();
        doAnswer(call -> { network.add(call.getArgument(0)); return null; }).when(loop).execute(any(Runnable.class));
        assertTrue((boolean) invoke("freezePreservingNetworkThread", new Class<?>[] { MinecraftServer.class }, this.server));
        var packet = Unpooled.buffer(1);
        var observed = new ArrayList<Integer>();
        var sentinel = new IllegalArgumentException("network callback after side effect");
        assertNull(enter(() -> {
            observed.add(1);
            try (var replay = enter(() -> { throw new AssertionError("Original callback must not requeue itself"); }, null)) {
                assertNotNull(replay);
                assertFalse(freeze(), "The begun callback still owns the network boundary");
            } catch (Exception failure) { throw new AssertionError(failure); }
            if (outcome.equals("failure")) throw sentinel;
        }, packet));
        packet.release();
        assertNull(enter(() -> observed.add(2), null));
        assertTrue(network.isEmpty());
        assertTrue(this.queued.isEmpty(), "An explicit NETWORK callback is never put on the server executor");
        if (outcome.equals("rejected")) doThrow(new java.util.concurrent.RejectedExecutionException("closed event loop")).when(loop).execute(any(Runnable.class));
        if (outcome.equals("rejected")) {
            assertThrows(InvocationTargetException.class, this::thaw);
            assertEquals(0, packet.refCnt());
            assertEquals(0, this.gate.snapshot().pending());
            return;
        }
        thaw();
        assertFalse(freeze(), "A new worker cannot overtake queued NETWORK callbacks");
        assertNull(enter(() -> observed.add(3), null));
        if (outcome.equals("cancel")) invoke("cancel", new Class<?>[] { Connection.class }, this.connection);
        if (outcome.equals("close")) invoke("close", new Class<?>[0]);
        if (outcome.equals("failure")) assertSame(sentinel, assertThrows(IllegalArgumentException.class, () -> network.remove().run()));
        while (!network.isEmpty()) network.remove().run();
        assertEquals(outcome.equals("cancel") || outcome.equals("close") ? List.of() : List.of(1, 2, 3), observed);
        assertEquals(0, packet.refCnt());
        assertEquals(0, this.gate.snapshot().pending());
        if (!outcome.equals("close")) { assertTrue(freeze()); thaw(); }
    }

    @Test
    void runningDirectCallbackRefusesFreezeBeforeAnyBody() throws Exception {
        try (var callback = enter(() -> {}, null)) {
            assertNotNull(callback);
            assertFalse(freeze());
            assertEquals(1, this.gate.snapshot().readers());
        }
        assertTrue(freeze());
        thaw();
        assertEquals(0, this.gate.snapshot().readers());
    }

    @Test
    void deferredCallbacksStayInOrderEvenAfterThawAndReleaseTheirPacketReferences() throws Exception {
        List<Integer> observed = new ArrayList<>();
        var packet = Unpooled.buffer(1);
        assertTrue(freeze());
        assertNull(enter(() -> observed.add(1), packet));
        assertEquals(2, packet.refCnt());
        packet.release(); // Original SimpleChannelInboundHandler auto-release.
        thaw();
        assertNull(enter(() -> observed.add(2), null));
        assertEquals(List.of(), observed);
        this.queued.remove().run();
        assertEquals(0, packet.refCnt());
        assertNull(enter(() -> observed.add(3), null));
        while (!this.queued.isEmpty()) this.queued.remove().run();
        assertEquals(List.of(1, 2, 3), observed);
        assertEquals(0, this.gate.snapshot().pending());
        try (var callback = enter(() -> {}, null)) {
            assertNotNull(callback);
        }
    }

    @Test
    void originalFailureIsPreservedAndRetainedPacketIsReleased() throws Exception {
        assertTrue(freeze());
        RuntimeException sentinel = new RuntimeException("original inbound failure");
        var packet = Unpooled.buffer(1);
        assertNull(enter(() -> {
            throw sentinel;
        }, packet));
        packet.release();
        thaw();
        assertSame(sentinel, assertThrows(RuntimeException.class, () -> this.queued.remove().run()));
        assertEquals(0, packet.refCnt());
        assertEquals(0, this.gate.snapshot().pending());
    }

    @Test
    void queueOverflowIsOneTerminalDisconnectAndMemoryRemainsBounded() throws Exception {
        assertTrue(freeze());
        for (int i = 0; i < 4100; i++) assertNull(enter(() -> {}, null));
        assertEquals(4096, this.gate.snapshot().pending());
        assertEquals(4097, this.queued.size());
        thaw();
        while (!this.queued.isEmpty()) this.queued.remove().run();
        verify(this.connection, times(1)).disconnect(any(net.minecraft.network.chat.Component.class));
        assertEquals(0, this.gate.snapshot().pending());
    }

    @Test
    void pendingOverflowTerminalCannotLeakLaterPacketRetainsAfterPrefixStartsDraining() throws Exception {
        assertTrue(freeze());
        for (int i = 0; i < 4097; i++) assertNull(enter(() -> {}, null));
        thaw();
        this.queued.remove().run(); // The terminal remains; pending falls below the global limit.
        var late = Unpooled.buffer(1);
        assertNull(enter(() -> { throw new AssertionError("Cannot pass pending terminal"); }, late));
        try {
            assertEquals(1, late.refCnt(), "A rejected post-terminal packet was never retained");
        } finally {
            while (late.refCnt() > 0) late.release();
            while (!this.queued.isEmpty()) this.queued.remove().run();
        }
        assertEquals(0, this.gate.snapshot().pending());
    }

    @Test
    void legacyOriginalServerReplayCanAdmitItsOriginalOwnerPacket() throws Exception {
        assertTrue(freeze());
        assertNull(enter(() -> {
            try {
                assertTrue(freeze(), "The original server replay has no live Netty reader and may run its original owner packet");
                thaw();
            } catch (Exception failure) { throw new AssertionError(failure); }
        }, null));
        thaw();
        this.queued.remove().run();
        assertFalse(this.gate.snapshot().frozen());
    }

    @Test
    void overflowUsesCurrentProtocolListenerAfterEveryAcceptedCallback() throws Exception {
        assertTrue(freeze());
        var old = mock(net.minecraft.server.network.ServerGamePacketListenerImpl.class);
        var current = mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class);
        when(this.connection.getPacketListener()).thenReturn(old);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 4100; i++) assertNull(enter(effects::incrementAndGet, null));
        // The accepted prefix may contain the original configuration acknowledgement. The terminal
        // must send the current protocol's disconnect, without replaying the prefix or using old state.
        when(this.connection.getPacketListener()).thenReturn(current);
        doAnswer(call -> {
            assertEquals(4096, effects.get());
            assertEquals("Player network queue limit exceeded", ((net.minecraft.network.chat.Component) call.getArgument(0)).getString());
            return null;
        }).when(current).disconnect(any(net.minecraft.network.chat.Component.class));
        thaw();
        while (!this.queued.isEmpty()) this.queued.remove().run();
        verify(current, times(1)).disconnect(any(net.minecraft.network.chat.Component.class));
        verify(old, times(0)).disconnect(any(net.minecraft.network.chat.Component.class));
        verify(this.connection, times(0)).disconnect(any(net.minecraft.network.chat.Component.class));
        assertEquals(0, this.gate.snapshot().pending());
        assertEquals(0, this.gate.snapshot().terminals());
    }

    private void closeGate() throws Exception {
        invoke("close", new Class<?>[0]);
    }

    private void cancelConnection() throws Exception {
        invoke("cancel", new Class<?>[] { Connection.class }, this.connection);
    }

    private void assertInert(Runnable envelope) throws Exception {
        for (var field : envelope.getClass().getDeclaredFields()) {
            if (field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            assertNull(field.get(envelope), field.getName() + " must not keep live state in the server queue");
        }
        envelope.run();
    }

    @Test
    void closeAfterJoinCancelsRetainsAndClearsEvenUnconsumedServerEnvelopes() throws Exception {
        assertTrue(freeze());
        var packet = Unpooled.buffer(1);
        assertNull(enter(() -> {
            throw new AssertionError("Cancelled callback ran");
        }, packet));
        packet.release();
        assertThrows(InvocationTargetException.class, this::closeGate);
        assertEquals(1, packet.refCnt(), "Cannot release a packet before worker join/thaw");
        thaw();
        closeGate();
        closeGate();
        assertEquals(0, packet.refCnt());
        assertEquals(0, this.gate.snapshot().pending());
        assertEquals(1, this.gate.snapshot().cancelled());
        assertTrue(this.gate.snapshot().closed());
        assertFalse(freeze());
        assertInert(this.queued.remove());
        try (var scope = enter(() -> {}, null)) {
            assertNotNull(scope);
        }
    }

    @Test
    void disconnectCancelsOnlyItsOwnQueuedCallbacksAndTerminal() throws Exception {
        assertTrue(freeze());
        for (int i = 0; i < 4100; i++) assertNull(enter(() -> {
            throw new AssertionError("Retired callback ran");
        }, null));
        assertEquals(1, this.gate.snapshot().terminals());
        thaw();
        cancelConnection();
        assertEquals(4097, this.gate.snapshot().cancelled());
        assertEquals(0, this.gate.snapshot().pending());
        assertEquals(0, this.gate.snapshot().terminals());
        for (Runnable envelope : this.queued) assertInert(envelope);
        Connection other = mock(Connection.class);
        assertTrue(freeze());
        List<Integer> calls = new ArrayList<>();
        invoke("enter", new Class<?>[] { MinecraftServer.class, Connection.class, Runnable.class, Object.class }, this.server, other, (Runnable) () -> calls.add(1), null);
        thaw();
        cancelConnection();
        assertEquals(1, this.gate.snapshot().pending());
        while (!this.queued.isEmpty()) this.queued.remove().run();
        assertEquals(List.of(1), calls);
    }

    @Test
    void cancelledSubmissionReleasesPacketAndTerminalSubmissionCanRetry() throws Exception {
        assertTrue(freeze());
        var packet = Unpooled.buffer(1);
        RuntimeException rejected = new RuntimeException("server executor rejected");
        doThrow(rejected).when(this.server).execute(any(Runnable.class));
        assertSame(rejected, assertThrows(InvocationTargetException.class, () -> enter(() -> {}, packet)).getCause());
        assertEquals(1, packet.refCnt());
        packet.release();
        assertEquals(0, this.gate.snapshot().pending());
        doAnswer(call -> {
            this.queued.add(call.getArgument(0));
            return null;
        }).when(this.server).execute(any(Runnable.class));
        for (int i = 0; i < 4096; i++) enter(() -> {}, null);
        doThrow(rejected).when(this.server).execute(any(Runnable.class));
        assertSame(rejected, assertThrows(InvocationTargetException.class, () -> enter(() -> {}, null)).getCause());
        assertEquals(0, this.gate.snapshot().terminals());
        doAnswer(call -> {
            this.queued.add(call.getArgument(0));
            return null;
        }).when(this.server).execute(any(Runnable.class));
        enter(() -> {}, null);
        assertEquals(1, this.gate.snapshot().terminals());
        thaw();
        closeGate();
    }

    @Test
    void callbackRetiringItsConnectionDoesNotCancelItselfOrOvertakeItsSuccessors() throws Exception {
        List<Integer> calls = new ArrayList<>();
        var packet = Unpooled.buffer(1);
        assertTrue(freeze());
        enter(() -> {
            assertEquals(1, packet.refCnt());
            try {
                assertNull(enter(() -> calls.add(2), null));
                cancelConnection();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
            assertEquals(1, packet.refCnt());
            calls.add(1);
        }, packet);
        packet.release();
        thaw();
        while (!this.queued.isEmpty()) this.queued.remove().run();
        assertEquals(List.of(1), calls);
        assertEquals(0, packet.refCnt());
        assertEquals(0, this.gate.snapshot().pending());
    }

    @Test
    void repeatedOverflowDisconnectAndShutdownReleaseRetainsWithoutKeepingCapturedGraphs() throws Exception {
        var graphs = new ArrayList<java.lang.ref.WeakReference<byte[]>>();
        long expectedCancelled = 0;
        for (int cycle = 0; cycle < 16; cycle++) {
            assertTrue(freeze());
            List<io.netty.buffer.ByteBuf> retained = new ArrayList<>();
            for (int index = 0; index < 4120; index++) {
                var packet = Unpooled.buffer(1);
                byte[] graph = new byte[1024];
                graphs.add(new java.lang.ref.WeakReference<>(graph));
                assertNull(enter(() -> graph[0]++, packet));
                packet.release();
                retained.add(packet);
            }
            assertEquals(4096, this.gate.snapshot().pending());
            assertEquals(1, this.gate.snapshot().terminals());
            assertEquals(4097, this.queued.size());
            thaw();
            if (cycle % 2 == 0) {
                cancelConnection();
                expectedCancelled += 4097;
            } else {
                while (!this.queued.isEmpty()) this.queued.remove().run();
            }
            for (var packet : retained) assertEquals(0, packet.refCnt());
            for (Runnable envelope : this.queued) assertInert(envelope);
            this.queued.clear();
            assertEquals(0, this.gate.snapshot().pending());
            assertEquals(0, this.gate.snapshot().terminals());
            assertEquals(0, this.gate.snapshot().readers());
        }
        assertTrue(freeze());
        var packet = Unpooled.buffer(1);
        assertNull(enter(() -> {
            throw new AssertionError("shutdown callback ran");
        }, packet));
        packet.release();
        thaw();
        closeGate();
        assertEquals(0, packet.refCnt());
        assertEquals(expectedCancelled + 1, this.gate.snapshot().cancelled());
        for (Runnable envelope : this.queued) assertInert(envelope);
        verify(this.connection, times(8)).disconnect(any(net.minecraft.network.chat.Component.class));
        // Retained empty executor envelopes remain reachable during the bounded collection check.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (graphs.stream().anyMatch(reference -> reference.get() != null) && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(20);
        }
        assertTrue(graphs.stream().allMatch(reference -> reference.get() == null), "Cancelled/completed closures must release their captured object graphs");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "frozen", "reader", "deferred" })
    @org.junit.jupiter.api.Timeout(15)
    void retirementWaitsForActualThawAndOriginalCallbackReturnWithoutEarlyRetainRelease(String kind) throws Exception {
        var resource = Unpooled.buffer(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var closerEntered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        RegionPlayerNetworkGate.Scope reader = null;
        Thread callback = null;
        if (kind.equals("reader")) reader = enter(() -> {}, null);
        else {
            assertTrue(freeze());
            enter(() -> {
                try {
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                } catch (InterruptedException problem) {
                    throw new AssertionError(problem);
                }
            }, resource);
            resource.release();
            if (kind.equals("deferred")) {
                thaw();
                callback = new Thread(this.queued.remove(), "original deferred callback");
                callback.start();
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (callback.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) Thread.yield();
                assertEquals(Thread.State.TIMED_WAITING, callback.getState());
            }
        }
        Thread closer = new Thread(() -> {
            closerEntered.countDown();
            try {
                invoke("closeAfterCallbacks", new Class<?>[0]);
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "actual gate retirement");
        closer.start();
        assertTrue(closerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (closer.getState() != Thread.State.WAITING && System.nanoTime() < until) Thread.yield();
        assertEquals(Thread.State.WAITING, closer.getState());
        assertFalse(this.gate.snapshot().closed());
        if (!kind.equals("reader")) assertEquals(1, resource.refCnt());
        if (kind.equals("frozen")) thaw();
        if (reader != null) reader.close();
        release.countDown();
        if (callback != null) callback.join(5000);
        closer.join(5000);
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertTrue(this.gate.snapshot().closed());
        assertEquals(0, this.gate.snapshot().pending());
        if (kind.equals("reader")) resource.release();
        assertEquals(0, resource.refCnt());
        for (Runnable envelope : this.queued) assertInert(envelope);
    }

    @Test
    void currentOriginalCallbackCannotWaitForItsOwnRetirement() throws Exception {
        try (var reader = enter(() -> {}, null)) {
            assertEquals(true, invoke("isCallbackOnCurrentThread", new Class<?>[0]));
            assertThrows(InvocationTargetException.class, () -> invoke("closeAfterCallbacks", new Class<?>[0]));
            assertFalse(this.gate.snapshot().closed());
        }
        assertEquals(false, invoke("isCallbackOnCurrentThread", new Class<?>[0]));
        invoke("closeAfterCallbacks", new Class<?>[0]);
        assertTrue(this.gate.snapshot().closed());
    }
}
