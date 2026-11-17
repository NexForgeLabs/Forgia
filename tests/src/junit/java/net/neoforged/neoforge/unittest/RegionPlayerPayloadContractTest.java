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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;
import org.junit.jupiter.api.Test;

/** Original public main-thread payload API, without any player worker or owner-executor exemption. */
class RegionPlayerPayloadContractTest {
    private static final class Loop extends ReentrantBlockableEventLoop<Runnable> {
        private final Thread publisher = Thread.currentThread();

        Loop() {
            super("P2 original server queue");
        }

        @Override
        protected Runnable wrapRunnable(Runnable task) {
            return task;
        }

        @Override
        protected boolean shouldRun(Runnable task) {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return this.publisher;
        }

        void drainOriginalQueue() {
            this.runAllTasks();
        }
    }

    private static ServerPayloadContext context(Loop loop) {
        var listener = mock(ServerCommonPacketListener.class);
        doReturn(loop).when(listener).getMainThreadEventLoop();
        return new ServerPayloadContext(listener, ResourceLocation.fromNamespaceAndPath("p2_contract", "unknown"));
    }

    @Test
    void mainThreadEnqueueKeepsSynchronousResultAndOriginalExceptionIdentity() {
        var loop = new Loop();
        var context = context(loop);
        List<String> effects = new ArrayList<>();
        var result = context.enqueueWork(() -> {
            effects.add("inline");
            return 37;
        });
        assertTrue(result.isDone());
        assertEquals(37, result.join());
        assertEquals(List.of("inline"), effects);
        var original = new IllegalStateException("original main-thread payload effect then failure");
        assertSame(original, assertThrows(IllegalStateException.class, () -> context.enqueueWork((Runnable) () -> {
            effects.add("before-throw");
            throw original;
        })));
        loop.drainOriginalQueue();
        assertEquals(List.of("inline", "before-throw"), effects, "A started callback is never queued again");
    }

    @Test
    void networkAndAsyncCallbacksRemainQueuedInOriginalOrderUntilPublisherRunsThem() throws Exception {
        var loop = new Loop();
        var context = context(loop);
        List<String> effects = new ArrayList<>();
        var pending = new ArrayList<CompletableFuture<?>>();
        var failure = new AtomicReference<Throwable>();
        var handler = new MainThreadPayloadHandler<CustomPacketPayload>((payload, originalContext) -> {
            assertTrue(loop.isSameThread());
            effects.add("main-payload");
            assertTrue(originalContext.enqueueWork((Runnable) () -> effects.add("nested-inline")).isDone());
        });
        Thread network = new Thread(() -> {
            try {
                pending.add(context.enqueueWork((Runnable) () -> effects.add("before")));
                handler.handle(mock(CustomPacketPayload.class), context);
                pending.add(context.enqueueWork(() -> {
                    effects.add("async-completion");
                    return 19;
                }));
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "P2 network and async producer");
        network.start();
        network.join(5000);
        assertFalse(network.isAlive());
        assertNull(failure.get());
        assertTrue(effects.isEmpty(), "A queued payload cannot execute on the network/async producer");
        assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));
        loop.drainOriginalQueue();
        assertEquals(List.of("before", "main-payload", "nested-inline", "async-completion"), effects);
        assertTrue(pending.stream().allMatch(CompletableFuture::isDone));
        assertEquals(19, pending.getLast().join());
        loop.drainOriginalQueue();
        assertEquals(4, effects.size());
    }
}
