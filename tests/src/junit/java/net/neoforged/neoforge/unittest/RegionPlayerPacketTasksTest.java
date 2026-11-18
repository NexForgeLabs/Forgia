/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.netty.buffer.Unpooled;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Original-executor envelopes: reference ownership, failure, cancellation race and bounded overflow. */
@Timeout(15)
class RegionPlayerPacketTasksTest {
    private Object registry() throws Exception {
        var constructor = Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerPacketTasks").getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(owner, args);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException error) throw error;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw wrapped;
        }
    }

    private Runnable task(Object registry, Connection connection, Object retained, Runnable original) throws Exception {
        return (Runnable) invoke(registry, "prepare", new Class<?>[] { Connection.class, Object.class, Runnable.class }, connection, retained, original);
    }

    private void cleared(Runnable task) throws Exception {
        for (String name : new String[] { "issuer", "connection", "original", "retained" }) {
            var field = task.getClass().getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(task), name);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "success", "failure", "cancel", "retire", "close" })
    void originalEnvelopeRetainsExactlyOnceAndClearsEvenAfterFailure(String outcome) throws Exception {
        Object registry = registry();
        Connection connection = mock(Connection.class);
        var resource = Unpooled.buffer(1);
        var calls = new AtomicInteger();
        RuntimeException sentinel = new IllegalArgumentException("original packet effect");
        Runnable task = task(registry, connection, resource, () -> {
            calls.incrementAndGet();
            if (outcome.equals("failure")) throw sentinel;
        });
        assertEquals(2, resource.refCnt());
        resource.release();
        if (outcome.equals("cancel")) invoke(task, "cancel", new Class<?>[0]);
        else if (outcome.equals("retire")) invoke(registry, "retire", new Class<?>[] { Connection.class }, connection);
        else if (outcome.equals("close")) invoke(registry, "close", new Class<?>[0]);
        if (outcome.equals("failure")) assertSame(sentinel, assertThrows(IllegalArgumentException.class, task::run));
        else task.run();
        task.run();
        invoke(task, "cancel", new Class<?>[0]);
        assertEquals(outcome.equals("success") || outcome.equals("failure") ? 1 : 0, calls.get());
        assertEquals(0, resource.refCnt());
        cleared(task);
        Object snapshot = invoke(registry, "snapshot", new Class<?>[0]);
        assertEquals(0, invoke(snapshot, "pending", new Class<?>[0]));
    }

    @Test
    void cancellingAlreadyBegunEnvelopeWaitsForOriginalFinallyToReleaseRetain() throws Exception {
        Object registry = registry();
        Connection connection = mock(Connection.class);
        var resource = Unpooled.buffer(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Runnable task = task(registry, connection, resource, () -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        });
        resource.release();
        Thread original = new Thread(task, "original packet executor test");
        original.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        invoke(registry, "retire", new Class<?>[] { Connection.class }, connection);
        invoke(task, "cancel", new Class<?>[0]);
        assertEquals(1, resource.refCnt(), "A begun body still owns its retained input");
        cleared(task);
        release.countDown();
        original.join(5000);
        assertFalse(original.isAlive());
        assertEquals(0, resource.refCnt());
    }

    @Test
    void overflowTerminatesAfterAcceptedPrefixAndCannotReopenBeforeDisconnection() throws Exception {
        Object registry = registry();
        Connection connection = mock(Connection.class);
        ServerCommonPacketListenerImpl listener = mock(ServerCommonPacketListenerImpl.class);
        when(connection.getPacketListener()).thenReturn(listener);
        var calls = new AtomicInteger();
        var disconnects = new AtomicInteger();
        doAnswer(call -> {
            assertEquals(4096, calls.get());
            assertTrue(call.getArgument(0).toString().contains("Player packet queue limit exceeded"));
            disconnects.incrementAndGet();
            return null;
        }).when(listener).disconnect(any(net.minecraft.network.chat.Component.class));
        var prefix = new ArrayList<Runnable>();
        for (int i = 0; i < 4096; i++) prefix.add(task(registry, connection, null, calls::incrementAndGet));
        var rejected = Unpooled.buffer(1);
        Runnable terminal = task(registry, connection, rejected, () -> fail("Overflow packet cannot run"));
        assertEquals(1, rejected.refCnt());
        rejected.release();
        assertThrows(net.minecraft.server.RunningOnDifferentThreadException.class, () -> task(registry, connection, null, () -> fail("Later packet cannot pass terminal")));
        for (Runnable task : prefix) task.run();
        terminal.run();
        terminal.run();
        assertEquals(1, disconnects.get());
        assertThrows(net.minecraft.server.RunningOnDifferentThreadException.class, () -> task(registry, connection, null, () -> fail("Admission cannot reopen before original disconnect retirement")));
        invoke(registry, "retire", new Class<?>[] { Connection.class }, connection);
        cleared(terminal);
        Object snapshot = invoke(registry, "snapshot", new Class<?>[0]);
        assertEquals(0, invoke(snapshot, "pending", new Class<?>[0]));
        assertEquals(0, invoke(snapshot, "terminals", new Class<?>[0]));
    }
}
