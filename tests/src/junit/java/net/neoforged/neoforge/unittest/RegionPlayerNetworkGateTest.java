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
}
