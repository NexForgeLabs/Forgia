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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int FAST_READERS_BLOCKED = Integer.MIN_VALUE;
    private static final ThreadLocal<Scope> FAST_CALLBACK = new ThreadLocal<>();
    private static final Scope UNRESTRICTED = new Scope(null);
    private final Map<Connection, List<Deferred>> pending = new IdentityHashMap<>();
    private final Map<Connection, Deferred> overflow = new IdentityHashMap<>();
    private final java.util.Set<Connection> completedConnections = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    // Low bits hold physically active direct callbacks. The high bit closes the fast path.
    private final AtomicInteger fastReaders = new AtomicInteger();
    private int readers;
    private boolean closing;
    // The publisher installs the participant set while direct callbacks are idle.
    @Nullable
    private volatile Map<Connection, LocalPause> localPauses;
    private int queued;
    private volatile boolean frozen;
    private boolean preserveNetworkThread;
    private int networkPending;
    private static final ThreadLocal<Replay> REPLAY = new ThreadLocal<>();
    private boolean closed;
    private int executing;
    private final Map<Thread, Integer> callbackThreads = new IdentityHashMap<>();
    private long deferred;
    private long cancelled;
    private long refusedFreezes;

    public record Snapshot(boolean frozen, int readers, int pending, long deferred, long refusedFreezes, boolean closed, int terminals, long cancelled) {}

    synchronized boolean freeze(MinecraftServer server) {
        return this.freeze(server, false);
    }

    synchronized boolean freezePreservingNetworkThread(MinecraftServer server) {
        return this.freeze(server, true);
    }

    synchronized boolean freezeLocalWave(MinecraftServer server, List<Connection> connections) {
        var pauses = new IdentityHashMap<Connection, LocalPause>(connections.size());
        for (Connection connection : connections) {
            if (pauses.put(connection, new LocalPause()) != null) return false;
        }
        if (!this.freeze(server, true)) return false;
        this.localPauses = pauses;
        return true;
    }

    private boolean freeze(MinecraftServer server, boolean preserveNetworkThread) {
        if (!server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Network freeze requires the server publisher");
        if (this.closed || this.closing || this.frozen || this.readers != 0 || this.networkPending != 0
                || preserveNetworkThread && (this.executing != 0 || this.queued != 0 || !this.overflow.isEmpty())
                || !this.blockFastReadersIfIdle()) {
            this.refusedFreezes++;
            return false;
        }
        this.frozen = true;
        this.preserveNetworkThread = preserveNetworkThread;
        return true;
    }

    synchronized void thaw() {
        if (!this.frozen) throw new IllegalStateException("Player network boundary is not frozen");
        this.frozen = false;
        this.preserveNetworkThread = false;
        this.completedConnections.clear();
        this.localPauses = null;
        this.reopenFastReaders();
        // Explicitly declared payloads may coexist with NETWORK handlers. Those callbacks resume
        // on their ORIGINAL event loop after physical join; they never become server-thread handlers.
        if (this.networkPending == 0) {
            this.notifyAll();
            return;
        }
        List<Deferred> resume = new ArrayList<>();
        this.pending.values().forEach(resume::addAll);
        resume.addAll(this.overflow.values());
        Throwable failure = null;
        for (Deferred callback : resume) {
            if (callback.networkThread && !callback.scheduled) {
                try {
                    this.submit(null, callback);
                } catch (RuntimeException | Error problem) {
                    if (failure == null) failure = problem;
                    else failure.addSuppressed(problem);
                }
            }
        }
        this.notifyAll();
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
    }

    boolean isFrozen() {
        return this.frozen;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this.frozen, this.readers + this.fastReaderCount(), this.queued, this.deferred, this.refusedFreezes, this.closed, this.overflow.size(), this.cancelled);
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
    private Scope enter(MinecraftServer server, Connection connection, Runnable original, @Nullable Object retained) {
        int count = this.fastReaders.get();
        while (count >= 0) {
            if (this.fastReaders.compareAndSet(count, count + 1)) {
                var scope = new Scope(this, FAST_CALLBACK.get());
                FAST_CALLBACK.set(scope);
                return scope;
            }
            count = this.fastReaders.get();
        }
        synchronized (this) {
            return this.enter(server, connection, original, retained, false);
        }
    }

    private int fastReaderCount() {
        return this.fastReaders.get() & Integer.MAX_VALUE;
    }

    private boolean blockFastReadersIfIdle() {
        return this.fastReaders.get() == FAST_READERS_BLOCKED || this.fastReaders.compareAndSet(0, FAST_READERS_BLOCKED);
    }

    private void reopenFastReaders() {
        if (!this.closed && !this.closing && !this.frozen && this.queued == 0 && this.overflow.isEmpty()) {
            this.fastReaders.compareAndSet(FAST_READERS_BLOCKED, 0);
        }
    }

    @Nullable
    private Scope enter(MinecraftServer server, Connection connection, Runnable original, @Nullable Object retained, boolean publisherOnly) {
        if (publisherOnly && !this.pauses(connection)) throw new IllegalStateException("Player disconnection requires a frozen worker connection");
        Replay replay = REPLAY.get();
        if (replay != null && replay.gate == this && replay.connection == connection) {
            // The physically executing callback owns this exact connection until return. Its nested
            // disconnect/send failures must retain original synchronous behavior too; the active
            // callback count prevents any worker freeze across the whole dynamic call stack.
            return UNRESTRICTED;
        }
        if (this.closed) return UNRESTRICTED;
        var pauses = this.localPauses;
        var local = pauses == null ? null : pauses.get(connection);
        // Commit to the queue under the gate. Either the worker returns first and this
        // callback runs directly, or it sees QUEUED and waits for the envelope installation.
        boolean paused = local == null ? this.pauses(connection) : local.markQueued();
        if (!paused && !this.pending.containsKey(connection) && !this.overflow.containsKey(connection)) {
            this.readers++;
            this.callbackThreads.merge(Thread.currentThread(), 1, Integer::sum);
            return new Scope(this);
        }
        List<Deferred> previous = this.pending.get(connection);
        Deferred terminal = this.overflow.get(connection);
        boolean networkThread = paused && this.preserveNetworkThread
                || previous != null && !previous.isEmpty() && previous.getFirst().networkThread
                || terminal != null && terminal.networkThread;
        if (this.queued >= MAX_PENDING) {
            // Terminal protocol error, never an invisible dropped movement/click. At most one terminal
            // continuation per connection; it runs after its earlier callbacks and every worker joined.
            if (!this.overflow.containsKey(connection)) {
                Deferred overflow = new Deferred(this, connection, () -> disconnectOverflow(connection), null, true, networkThread, null);
                this.overflow.put(connection, overflow);
                if (networkThread) this.networkPending++;
                this.submit(server, overflow);
            }
            return null;
        }
        // Once terminal, no later callback can pass or create another queued envelope.
        if (terminal != null) return null;
        ReferenceCountUtil.retain(retained);
        Deferred callback = new Deferred(this, connection, original, retained, false, networkThread, publisherOnly && networkThread ? server : null);
        this.pending.computeIfAbsent(connection, key -> new ArrayList<>()).add(callback);
        this.queued++;
        this.deferred++;
        if (networkThread) this.networkPending++;
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

    private boolean pauses(Connection connection) {
        if (!this.frozen) return false;
        var pauses = this.localPauses;
        if (pauses == null) return !this.completedConnections.contains(connection);
        var local = pauses.get(connection);
        return local != null && local.get() != LocalPause.RETURNED;
    }

    boolean workerConnectionFrozen(Connection connection) {
        var pauses = this.localPauses;
        if (pauses == null) return this.frozen;
        var local = pauses.get(connection);
        return local != null && local.get() != LocalPause.RETURNED;
    }

    /** The last native scope for this connection has physically returned in a local-only wave. */
    void resumeCompletedConnection(Connection connection) {
        var pauses = this.localPauses;
        var local = pauses == null ? null : pauses.get(connection);
        // A quiet connection completes without taking the shared gate. When a callback
        // has reserved a queue position, the gate serializes its insertion and submission.
        if (local != null && local.compareAndSet(LocalPause.PAUSED, LocalPause.RETURNED)) return;
        synchronized (this) {
            if (!this.frozen || !this.preserveNetworkThread || this.overflow.containsKey(connection)) return;
            if (pauses != null) {
                if (local == null || !local.compareAndSet(LocalPause.QUEUED, LocalPause.RETURNED)) return;
            } else if (!this.completedConnections.add(connection)) return;
            this.resumeDeferredConnection(connection);
        }
    }

    private void resumeDeferredConnection(Connection connection) {
        List<Deferred> callbacks = this.pending.get(connection);
        if (callbacks == null) return;
        // Keep the FIFO order of the original connection. A rejected event loop closes its
        // queued envelopes through the same cancel path used at final thaw.
        for (Deferred callback : List.copyOf(callbacks)) {
            if (!callback.scheduled) this.submit(null, callback);
        }
    }

    private void submit(@Nullable MinecraftServer server, Deferred callback) {
        if (callback.networkThread && this.pauses(callback.connection)) return;
        callback.scheduled = true;
        try {
            if (callback.networkThread) callback.connection.channel().eventLoop().execute(callback);
            else server.execute(callback);
        } catch (RuntimeException | Error failure) {
            callback.cancel();
            throw failure;
        }
    }

    /** Called by the original publisher after physical join, before lifecycle removal. */
    public static void retireConnection(Connection connection) {
        if (RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Cannot retire a worker connection before join");
        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
            engine.playerNetworkGate().cancel(connection);
            engine.retirePlayerPackets(connection);
        }
    }

    private synchronized void cancel(Connection connection) {
        if (this.frozen) throw new IllegalStateException("Cannot cancel player network callbacks before join");
        for (Deferred callback : List.copyOf(this.pending.getOrDefault(connection, List.of()))) callback.cancel();
        Deferred terminal = this.overflow.get(connection);
        if (terminal != null) terminal.cancel();
    }

    /** The engine has joined all player bodies. Queued server envelopes retain no live references. */
    synchronized void close() {
        if (this.frozen || this.executing != 0 || this.readers != 0 || !this.blockFastReadersIfIdle())
            throw new IllegalStateException("Cannot close active player network callbacks");
        this.closed = true;
        for (Connection connection : List.copyOf(this.pending.keySet())) this.cancel(connection);
        for (Deferred terminal : List.copyOf(this.overflow.values())) terminal.cancel();
    }

    synchronized boolean isCallbackOnCurrentThread() {
        for (Scope scope = FAST_CALLBACK.get(); scope != null; scope = scope.previous) {
            if (scope.gate == this) return true;
        }
        return this.callbackThreads.containsKey(Thread.currentThread());
    }

    /**
     * A completed player batch may still be inside its publisher's thaw/finally or an outer original
     * network callback. Shutdown cannot release those references until they physically return.
     * No tasks are pumped, no timeout grants completion, and interruption is restored after cleanup.
     */
    synchronized void closeAfterCallbacks() {
        if (this.isCallbackOnCurrentThread()) throw new IllegalStateException("Cannot wait for the current network callback");
        this.closing = true;
        this.fastReaders.getAndUpdate(state -> state | FAST_READERS_BLOCKED);
        boolean interrupted = false;
        while (this.frozen || this.executing != 0 || this.readers != 0 || this.fastReaderCount() != 0) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        try {
            this.close();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void callbackReturned(Thread thread) {
        int count = this.callbackThreads.get(thread);
        if (count == 1) this.callbackThreads.remove(thread);
        else this.callbackThreads.put(thread, count - 1);
        this.notifyAll();
    }

    private static final class Deferred implements Runnable {
        private volatile RegionPlayerNetworkGate gate;
        private Connection connection;
        private Runnable original;
        private Object retained;
        private final boolean terminal;
        private final boolean networkThread;
        // A worker may detect physical socket loss while the original event loop is frozen.
        // Its publisher-only cleanup queues behind that connection's earlier network callbacks.
        @Nullable
        private MinecraftServer publisherAfterNetwork;
        private boolean onPublisher;
        private boolean scheduled;
        private boolean running;

        private Deferred(RegionPlayerNetworkGate gate, Connection connection, Runnable original, @Nullable Object retained, boolean terminal, boolean networkThread, @Nullable MinecraftServer publisherAfterNetwork) {
            this.gate = gate;
            this.connection = connection;
            this.original = original;
            this.retained = retained;
            this.terminal = terminal;
            this.networkThread = networkThread;
            this.publisherAfterNetwork = publisherAfterNetwork;
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
            if (this.networkThread) current.networkPending--;
            this.gate = null;
            this.connection = null;
            this.original = null;
            this.retained = null;
            this.publisherAfterNetwork = null;
            current.reopenFastReaders();
        }

        @Override
        public void run() {
            RegionPlayerNetworkGate current = this.gate;
            if (current == null) return;
            Runnable body;
            Replay previous = REPLAY.get();
            synchronized (current) {
                if (this.gate == null || this.running) return;
                MinecraftServer publisher = this.publisherAfterNetwork;
                if (publisher != null) {
                    this.publisherAfterNetwork = null;
                    this.onPublisher = true;
                    try {
                        publisher.execute(this);
                    } catch (RuntimeException | Error failure) {
                        this.cancel();
                        throw failure;
                    }
                    return;
                }
                body = this.original;
                this.running = true;
                current.executing++;
                current.callbackThreads.merge(Thread.currentThread(), 1, Integer::sum);
                if (this.networkThread && !this.onPublisher) REPLAY.set(new Replay(current, this.connection));
            }
            try {
                body.run();
            } finally {
                if (previous == null) REPLAY.remove();
                else REPLAY.set(previous);
                synchronized (current) {
                    this.detach(current);
                    current.executing--;
                    current.callbackReturned(Thread.currentThread());
                }
            }
        }
    }

    private static final class LocalPause extends AtomicInteger {
        private static final int PAUSED = 0;
        private static final int QUEUED = 1;
        private static final int RETURNED = 2;

        boolean markQueued() {
            return this.get() == QUEUED || this.compareAndSet(PAUSED, QUEUED);
        }
    }

    private static final class Replay {
        private final RegionPlayerNetworkGate gate;
        private final Connection connection;

        private Replay(RegionPlayerNetworkGate gate, Connection connection) {
            this.gate = gate;
            this.connection = connection;
        }
    }

    /** Connection loss is observed by the whole original tick; lifecycle removal runs after join. */
    public static boolean deferWorkerDisconnection(Connection connection) {
        if (!RegionWorkerScope.isNativeWorkerThread()) return false;
        if (!(connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener)
                || !RegionPlayerTickCoordinator.executingWorker(listener.player, RegionPlayerTickCoordinator.Phase.CONNECTION))
            throw new IllegalStateException("No player session for deferred disconnection");
        RegionPlayerNetworkGate gate = ((RegionThreadingEngine) RegionThreadingManager.currentOrNull()).playerNetworkGate();
        synchronized (gate) {
            gate.enter(listener.player.server, connection, connection::handleDisconnection, null, true);
        }
        return true;
    }

    public static final class Scope implements AutoCloseable {
        @Nullable
        private RegionPlayerNetworkGate gate;
        @Nullable
        private Thread owner;
        @Nullable
        private Scope previous;
        private boolean fast;

        private Scope(@Nullable RegionPlayerNetworkGate gate) {
            this.gate = gate;
            this.owner = gate == null ? null : Thread.currentThread();
        }

        private Scope(RegionPlayerNetworkGate gate, @Nullable Scope previous) {
            this(gate);
            this.previous = previous;
            this.fast = true;
        }

        @Override
        public void close() {
            RegionPlayerNetworkGate current = this.gate;
            if (current == null) return;
            if (this.fast) {
                if (FAST_CALLBACK.get() != this) throw new IllegalStateException("Network callback scopes must close in their original order");
                if (this.previous == null) FAST_CALLBACK.remove();
                else FAST_CALLBACK.set(this.previous);
                this.previous = null;
                this.gate = null;
                this.owner = null;
                if (current.fastReaders.decrementAndGet() == FAST_READERS_BLOCKED) {
                    synchronized (current) {
                        current.notifyAll();
                    }
                }
                return;
            }
            synchronized (current) {
                current.readers--;
                current.callbackReturned(this.owner);
            }
            this.gate = null;
            this.owner = null;
        }
    }
}
