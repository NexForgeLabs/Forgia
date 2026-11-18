/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import io.netty.util.ReferenceCountUtil;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/** Cancellable envelopes on the original PacketUtils executor, with bounded references and exact retain release. */
final class RegionPlayerPacketTasks {
    private static final int MAX_PENDING = 4096;
    private final Map<Connection, Set<Task>> pending = new IdentityHashMap<>();
    private final Map<Connection, Task> terminals = new IdentityHashMap<>();
    private int count;
    private long cancelled;
    private long overflows;
    private boolean closed;

    record Snapshot(int pending, int terminals, long cancelled, long overflows, boolean closed) {}

    synchronized Snapshot snapshot() {
        return new Snapshot(count, terminals.size(), cancelled, overflows, closed);
    }

    synchronized Runnable prepare(Connection connection, Object retained, Runnable original) {
        if (closed) return original;
        // The terminal already promises a protocol disconnect behind the accepted prefix. End the
        // original PacketUtils receive path before executeIfPossible, rather than enqueueing one
        // empty TickTask per later packet and merely moving the unbounded queue elsewhere.
        if (terminals.containsKey(connection)) throw net.minecraft.server.RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
        if (count >= MAX_PENDING) {
            if (terminals.size() >= MAX_PENDING) throw new java.util.concurrent.RejectedExecutionException("Player packet queue terminal limit");
            Task terminal = new Task(this, connection, () -> disconnectOverflow(connection), null, true);
            terminals.put(connection, terminal);
            overflows++;
            return terminal; // Submitted by the SAME original executeIfPossible, behind its accepted prefix.
        }
        ReferenceCountUtil.retain(retained);
        Task task = new Task(this, connection, original, retained, false);
        pending.computeIfAbsent(connection, key -> Collections.newSetFromMap(new IdentityHashMap<>())).add(task);
        count++;
        return task;
    }

    private static void disconnectOverflow(Connection connection) {
        Component reason = Component.literal("Player packet queue limit exceeded");
        if (connection.getPacketListener() instanceof ServerCommonPacketListenerImpl listener) listener.disconnect(reason);
        else connection.disconnect(reason);
    }

    synchronized void removed(Task task, Connection connection, boolean cancel) {
        if (task.terminal) {
            // Keep the connection blocked after its terminal runs, until ORIGINAL disconnection
            // retirement. Clearing an executed terminal early would reopen admission before close.
            if (cancel) terminals.remove(connection, task);
        } else {
            Set<Task> tasks = pending.get(connection);
            if (tasks != null && tasks.remove(task)) {
                count--;
                if (tasks.isEmpty()) pending.remove(connection);
            }
        }
        if (cancel) cancelled++;
    }

    synchronized void retire(Connection connection) {
        for (Task task : List.copyOf(pending.getOrDefault(connection, Set.of()))) task.cancel();
        Task terminal = terminals.get(connection);
        if (terminal != null) terminal.cancel();
        terminals.remove(connection);
    }

    synchronized void close() {
        closed = true;
        for (Connection connection : List.copyOf(pending.keySet())) retire(connection);
        for (Connection connection : List.copyOf(terminals.keySet())) retire(connection);
    }

    static final class Task implements Runnable {
        @Nullable
        private RegionPlayerPacketTasks issuer;
        @Nullable
        private Connection connection;
        @Nullable
        private Runnable original;
        @Nullable
        private Object retained;
        private final boolean terminal;

        Task(RegionPlayerPacketTasks issuer, Connection connection, Runnable original, @Nullable Object retained, boolean terminal) {
            this.issuer = issuer;
            this.connection = connection;
            this.original = original;
            this.retained = retained;
            this.terminal = terminal;
        }

        private record Begun(RegionPlayerPacketTasks issuer, Connection connection, Runnable original, @Nullable Object retained) {}

        @Nullable
        private synchronized Begun take() {
            if (original == null) return null;
            var value = new Begun(issuer, connection, original, retained);
            issuer = null;
            connection = null;
            original = null;
            retained = null;
            return value;
        }

        @Override
        public void run() {
            Begun value = take();
            if (value == null) return;
            value.issuer.removed(this, value.connection, false);
            try {
                value.original.run();
            } finally {
                ReferenceCountUtil.release(value.retained);
            }
        }

        void cancel() {
            Begun value = take();
            if (value == null) return;
            value.issuer.removed(this, value.connection, true);
            ReferenceCountUtil.release(value.retained);
        }
    }
}
