/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Closes direct network callbacks while an original player phase owns its quiescent boundary.
 * Netty never waits for a worker or the server: callbacks keep their order on the existing server queue.
 * A callback already executing makes freeze fail before any player body starts.
 */
@ApiStatus.Internal
public final class RegionPlayerNetworkGate {
    private static final int MAX_PENDING = 4096;
    private static final Scope UNRESTRICTED = new Scope(null);
    private final Map<Connection, List<Deferred>> pending = new IdentityHashMap<>();
    private final Map<Connection, Deferred> overflow = new IdentityHashMap<>();
    private int readers;
    private int queued;
    private boolean frozen;
    private boolean closed;
    private int executing;
    private long deferred;
    private long cancelled;
    private long refusedFreezes;

    public record Snapshot(boolean frozen, int readers, int pending, long deferred, long refusedFreezes, boolean closed, int terminals, long cancelled) {}

    synchronized boolean freeze(MinecraftServer server) {
        if (!server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Network freeze requires the server publisher");
        if (this.closed || this.frozen || this.readers != 0) {
            this.refusedFreezes++;
            return false;
        }
        this.frozen = true;
        return true;
    }

    synchronized void thaw() {
        if (!this.frozen) throw new IllegalStateException("Player network boundary is not frozen");
        this.frozen = false;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this.frozen, this.readers, this.queued, this.deferred, this.refusedFreezes, this.closed, this.overflow.size(), this.cancelled);
    }

    /** Null means the complete callback has been queued, so the original entry must return now. */
    @Nullable
    public static Scope enter(Connection connection, Runnable original, @Nullable Object retained) {
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)
                || !(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
                // Admission stops before shutdown joins active workers. Their existing network fence
                // must survive that stop-new-work flag until thaw/close after the physical join.
                || !engine.playerTickPlanningRequested() || listener.player.server.isSameThread())
            return UNRESTRICTED;
        if (RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Direct network callback reached a player worker");
        return engine.playerNetworkGate().enter(listener.player.server, connection, original, retained);
    }

    @Nullable
    private synchronized Scope enter(MinecraftServer server, Connection connection, Runnable original, @Nullable Object retained) {
        if (this.closed) return UNRESTRICTED;
        if (!this.frozen && !this.pending.containsKey(connection) && !this.overflow.containsKey(connection)) {
            this.readers++;
            return new Scope(this);
        }
        if (this.queued >= MAX_PENDING) {
            // Terminal protocol error, never an invisible dropped movement/click. At most one terminal
            // continuation per connection; it runs after its earlier callbacks and every worker joined.
            if (!this.overflow.containsKey(connection)) {
                Deferred terminal = new Deferred(this, connection, () -> disconnectOverflow(connection), null, true);
                this.overflow.put(connection, terminal);
                this.submit(server, terminal);
            }
            return null;
        }
        ReferenceCountUtil.retain(retained);
        Deferred callback = new Deferred(this, connection, original, retained, false);
        this.pending.computeIfAbsent(connection, key -> new ArrayList<>()).add(callback);
        this.queued++;
        this.deferred++;
        this.submit(server, callback);
        return null;
    }

    private static void disconnectOverflow(Connection connection) {
        Component reason = Component.literal("Player network queue limit exceeded");
        // The terminal runs on the original server queue after every earlier callback and physical
        // worker join. Use the CURRENT protocol listener so play/config clients receive the original
        // common disconnect packet before the channel closes, including across reconfiguration.
        if (connection.getPacketListener() instanceof ServerCommonPacketListenerImpl listener) listener.disconnect(reason);
        else connection.disconnect(reason);
    }

    private void submit(MinecraftServer server, Deferred callback) {
        try {
            server.execute(callback);
        } catch (RuntimeException | Error failure) {
            callback.cancel();
            throw failure;
        }
    }

    /** Called by the original publisher after physical join, before lifecycle removal. */
    public static void retireConnection(Connection connection) {
        if (RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Cannot retire a worker connection before join");
        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) engine.playerNetworkGate().cancel(connection);
    }

    private synchronized void cancel(Connection connection) {
        if (this.frozen) throw new IllegalStateException("Cannot cancel player network callbacks before join");
        for (Deferred callback : List.copyOf(this.pending.getOrDefault(connection, List.of()))) callback.cancel();
        Deferred terminal = this.overflow.get(connection);
        if (terminal != null) terminal.cancel();
    }

    /** The engine has joined all player bodies. Queued server envelopes retain no live references. */
    synchronized void close() {
        if (this.frozen || this.executing != 0) throw new IllegalStateException("Cannot close active player network callbacks");
        this.closed = true;
        for (Connection connection : List.copyOf(this.pending.keySet())) this.cancel(connection);
        for (Deferred terminal : List.copyOf(this.overflow.values())) terminal.cancel();
    }

    private static final class Deferred implements Runnable {
        private volatile RegionPlayerNetworkGate gate;
        private Connection connection;
        private Runnable original;
        private Object retained;
        private final boolean terminal;
        private boolean running;

        private Deferred(RegionPlayerNetworkGate gate, Connection connection, Runnable original, @Nullable Object retained, boolean terminal) {
            this.gate = gate;
            this.connection = connection;
            this.original = original;
            this.retained = retained;
            this.terminal = terminal;
        }

        // All fields are protected by the gate; cancelled envelopes are inert even if the server's
        // executor retains them forever. The active callback holds its packet only until original return.
        private void cancel() {
            RegionPlayerNetworkGate current = this.gate;
            if (current == null || this.running) return;
            this.detach(current);
            current.cancelled++;
        }

        private void detach(RegionPlayerNetworkGate current) {
            if (this.terminal) current.overflow.remove(this.connection);
            else {
                List<Deferred> callbacks = current.pending.get(this.connection);
                callbacks.remove(this);
                if (callbacks.isEmpty()) current.pending.remove(this.connection);
                current.queued--;
            }
            ReferenceCountUtil.release(this.retained);
            this.gate = null;
            this.connection = null;
            this.original = null;
            this.retained = null;
        }

        @Override
        public void run() {
            RegionPlayerNetworkGate current = this.gate;
            if (current == null) return;
            Runnable body;
            synchronized (current) {
                if (this.gate == null || this.running) return;
                body = this.original;
                this.running = true;
                current.executing++;
            }
            try {
                body.run();
            } finally {
                synchronized (current) {
                    this.detach(current);
                    current.executing--;
                }
            }
        }
    }

    /** Connection loss is observed by the whole original tick; lifecycle removal runs after join. */
    public static boolean deferWorkerDisconnection(Connection connection) {
        if (!RegionWorkerScope.isNativeWorkerThread()) return false;
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)
                || !RegionPlayerTickCoordinator.executingWorker(listener.player, RegionPlayerTickCoordinator.Phase.CONNECTION))
            throw new IllegalStateException("No player session for deferred disconnection");
        RegionPlayerNetworkGate gate = ((RegionThreadingEngine) RegionThreadingManager.currentOrNull()).playerNetworkGate();
        gate.enter(listener.player.server, connection, connection::handleDisconnection, null);
        return true;
    }

    public static final class Scope implements AutoCloseable {
        @Nullable
        private RegionPlayerNetworkGate gate;

        private Scope(@Nullable RegionPlayerNetworkGate gate) {
            this.gate = gate;
        }

        @Override
        public void close() {
            RegionPlayerNetworkGate current = this.gate;
            if (current == null) return;
            synchronized (current) {
                current.readers--;
            }
            this.gate = null;
        }
    }
}
