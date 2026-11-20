/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Engine-owned, bounded execution bookkeeping for the two original player entrances. This is not an
 * executor or a world-access permission. All admission and handoff currently happens on the server thread;
 * original bodies remain serial until their complete callback and shared-state contract is closed.
 */
@ApiStatus.Internal
public final class RegionPlayerTickCoordinator {
    public enum Phase {
        ENTITY, CONNECTION, CONNECTION_BODY
    }

    public enum State {
        PREPARED, ADMITTED, STARTED, RETURNED, FAILED, JOINED, REJECTED, CANCELLED
    }

    private static final ThreadLocal<Plan> WORKER_INVOCATION = new ThreadLocal<>();
    private static volatile int invocationFallbackDepth;

    @Nullable
    private static Plan currentWorkerInvocation() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            return worker.playerTickInvocation;
        }
        return invocationFallbackDepth != 0 ? WORKER_INVOCATION.get() : null;
    }

    private static void setWorkerInvocation(Plan plan) {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.playerTickInvocation = plan;
            return;
        }
        synchronized (RegionPlayerTickCoordinator.class) {
            if (WORKER_INVOCATION.get() == null) ++invocationFallbackDepth;
            WORKER_INVOCATION.set(plan);
        }
    }

    private static void clearWorkerInvocation() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.playerTickInvocation = null;
            return;
        }
        synchronized (RegionPlayerTickCoordinator.class) {
            if (WORKER_INVOCATION.get() != null) {
                WORKER_INVOCATION.remove();
                --invocationFallbackDepth;
            }
        }
    }

    public enum Reason {
        DEPENDENCIES, FAKE_PLAYER, NOT_CURRENT_PLAYER, NO_IDENTITY, NO_OWNER, STALE,
        CAPACITY, DUPLICATE, RIDING, CALLBACK_CONTRACT_OPEN
    }

    /** Plain terminal metadata: no player, world, listener, connection, runnable or throwable references. */
    public record Execution(long id, UUID serverSession, UUID player, long lifecycleGeneration, long tick,
            Phase phase, State state, boolean started, String dimension, long identityEpoch,
            int playerInstance, int connectionInstance, int listenerInstance, @Nullable String failureClass,
            boolean worker, String thread, long startNanos, long returnNanos) {}

    public record PhaseCounters(long prepared, long started, long returned, long failed, long joined,
            long serial, long worker, long untrackedSerial, Map<Reason, Long> reasons) {}

    public record Snapshot(UUID serverSession, int owners, int pending, int running,
            long handoffs, long invalidations, List<Execution> recent, Map<Phase, PhaseCounters> phases) {}

    private static final int OWNER_LIMIT = 4096;
    private static final int PENDING_LIMIT = 4096;
    private static final int HISTORY_LIMIT = 256;
    private final RegionThreadingEngine engine;
    private final UUID serverSession = UUID.randomUUID();
    private final Map<UUID, Owner> owners = new HashMap<>();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Plan> pending = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private final Map<UUID, Starts> recentStarts = new HashMap<>();
    private final ArrayDeque<Execution> recent = new ArrayDeque<>();
    private final EnumMap<Phase, Counters> counters = new EnumMap<>(Phase.class);
    private long sequence;
    private long lifecycleSequence;
    private long handoffs;
    private long invalidations;
    private boolean closed;
    private long lastExpireTick = Long.MIN_VALUE;
    @Nullable
    private MinecraftServer lastExpireServer;
    @Nullable
    private WeakReference<MinecraftServer> serverIdentity;
    @Nullable
    private ServerLevel cachedDimensionLevel;
    @Nullable
    private String cachedDimensionName;
    private Reason captureRefusal = Reason.STALE;

    private static final class Counters {
        long prepared, started, returned, failed, joined, serial, worker, untrackedSerial;
        final EnumMap<Reason, Long> reasons = new EnumMap<>(Reason.class);

        void reason(Reason reason) {
            this.reasons.merge(reason, 1L, Long::sum);
        }

        PhaseCounters snapshot() {
            return new PhaseCounters(this.prepared, this.started, this.returned, this.failed,
                    this.joined, this.serial, this.worker, this.untrackedSerial, Map.copyOf(this.reasons));
        }
    }

    private static final class Starts {
        final WeakReference<ServerPlayer> player;
        final long[] ticks;

        synchronized boolean mark(Phase phase, long tick) {
            int index = phase.ordinal();
            if (ticks[index] == tick) return false;
            ticks[index] = tick;
            return true;
        }

        synchronized boolean expired(long tick) {
            if (this.player.get() == null) return true;
            for (long t : this.ticks) {
                if (t == tick) return false;
            }
            return true;
        }

        Starts(ServerPlayer player) {
            this.player = new WeakReference<>(player);
            this.ticks = new long[Phase.values().length];
            java.util.Arrays.fill(this.ticks, -1L);
        }
    }

    private static final class Owner {
        final Binding binding;
        final long generation;

        Owner(Binding binding, long generation) {
            this.binding = binding;
            this.generation = generation;
        }
    }

    /** Ledger identity, not its epoch ordering, distinguishes world incarnations. */
    private record Binding(MinecraftServer server, ServerPlayer player, ServerGamePacketListenerImpl listener,
            Connection connection, ServerLevel level, EntityIdentityLedger ledger, EntityIdentity identity,
            RegionCoordinate region) {
        boolean sameOwner(Binding other) {
            return this.server == other.server && this.player == other.player && this.listener == other.listener
                    && this.connection == other.connection && this.level == other.level && this.ledger == other.ledger
                    && this.identity.equals(other.identity) && this.region.equals(other.region);
        }
    }

    /** A single-use invocation record, not a scheduling or mutation capability. */
    public static final class Plan {
        private final RegionPlayerTickCoordinator issuer;
        private final long id;
        private final UUID uuid;
        private final long generation;
        private final long tick;
        private final Phase phase;
        private final String dimension;
        private final long identityEpoch;
        private final int playerInstance, connectionInstance, listenerInstance;
        @Nullable
        private volatile Binding binding;
        private volatile State state = State.PREPARED;
        private volatile boolean started;
        // Set by the publisher before submitting a prefix. It is a per-player duplicate guard,
        // shared with the serial path, but it grants no world or connection access.
        @Nullable
        private Starts prefixStarts;
        private boolean worker;
        private String thread = "";
        private long startNanos;
        private long returnNanos;
        @Nullable
        private String failureClass;

        private Plan(RegionPlayerTickCoordinator issuer, long id, Binding binding, long generation, long tick, Phase phase, String dimension) {
            this.issuer = issuer;
            this.id = id;
            this.uuid = binding.player.getUUID();
            this.generation = generation;
            this.tick = tick;
            this.phase = phase;
            this.binding = binding;
            this.dimension = dimension;
            this.identityEpoch = binding.identity.epoch();
            this.playerInstance = System.identityHashCode(binding.player);
            this.connectionInstance = System.identityHashCode(binding.connection);
            this.listenerInstance = System.identityHashCode(binding.listener);
        }

        public State state() {
            return this.state;
        }

        public long lifecycleGeneration() {
            return this.generation;
        }

        public Phase phase() {
            return this.phase;
        }

        public boolean started() {
            return this.started;
        }

        long workerBodyNanos() {
            return this.worker && this.returnNanos != 0 ? this.returnNanos - this.startNanos : 0;
        }

        public boolean retainsLiveBinding() {
            return this.binding != null;
        }
    }

    RegionPlayerTickCoordinator(RegionThreadingEngine engine) {
        this.engine = engine;
        for (Phase phase : Phase.values()) this.counters.put(phase, new Counters());
    }

    private static void serverThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Player tick plans require the original server thread");
    }

    private long tick(MinecraftServer server) {
        return Integer.toUnsignedLong(server.getTickCount());
    }

    @Nullable
    private Binding capture(ServerPlayer player, Connection connection) {
        MinecraftServer server = player.server;
        serverThread(server);
        this.captureRefusal = Reason.STALE;
        if (this.serverIdentity == null) this.serverIdentity = new WeakReference<>(server);
        else if (this.serverIdentity.get() != server) return null;
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            this.captureRefusal = Reason.FAKE_PLAYER;
            return null;
        }
        ServerGamePacketListenerImpl listener = player.connection;
        if (listener == null || listener.player != player || listener.getConnection() != connection
                || connection.getPacketListener() != listener || !connection.isConnected() || !listener.isAcceptingMessages() || player.isRemoved()
                || server.getPlayerList().getPlayer(player.getUUID()) != player) {
            this.captureRefusal = Reason.NOT_CURRENT_PLAYER;
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (this.engine.isLevelClosed(level)) return null;
        EntityIdentityLedger ledger = level.entityIdentityLedger();
        EntityIdentity identity = ledger.liveIdentity(player);
        RegionState region = this.engine.regionFor(player);
        if (identity == null || (identity.state() != EntityIdentity.State.ADMITTED && identity.state() != EntityIdentity.State.TRACKED)
                || identity.entityId() != player.getId() || !identity.uuid().equals(player.getUUID())) {
            this.captureRefusal = Reason.NO_IDENTITY;
            return null;
        }
        if (region == null || !this.engine.regionLayout().isInRegion(level, player.blockPosition(), region.coordinate())) {
            this.captureRefusal = Reason.NO_OWNER;
            return null;
        }
        return new Binding(server, player, listener, connection, level, ledger, identity, region.coordinate());
    }

    /** Capture at a quiescent original entrance; revalidation is also mandatory immediately before start. */
    @Nullable
    public synchronized Plan prepare(ServerPlayer player, Connection connection, Phase phase) {
        serverThread(player.server);
        this.expire(player.server);
        return this.prepareAfterExpiry(player, connection, phase);
    }

    /** Same publisher, frozen network and no intervening original bodies across this prefix. */
    synchronized boolean admitWorkerPrefix(List<RegionPlayerTickDispatch.Call> calls) {
        MinecraftServer server = calls.getFirst().player.server;
        serverThread(server);
        this.expire(server);
        for (int i = 0, size = calls.size(); i < size; i++) {
            var call = calls.get(i);
            if (call.player.server != server) return false;
            var ticket = this.preparePrefixCall(call.player, call.player.connection.getConnection(), call.phase, call.admission.spatial());
            call.ticket = ticket;
            if (ticket == null) return false;
            // prepare just observed exactly the binding, generation and tick admit would re-read.
            // Worker start still rechecks ownership, lifecycle and the frozen network boundary.
            Starts starts = this.findOrCreateStarts(ticket, call.player);
            if (starts == null) return false;
            ticket.prefixStarts = starts;
            ticket.state = State.ADMITTED;
        }
        return true;
    }

    @Nullable
    private Plan preparePrefixCall(ServerPlayer player, Connection connection, Phase phase, RegionPlayerSpatialPlan spatial) {
        if (spatial.singleRegionState == null || spatial.singlePlayerIdentity == null) {
            return this.prepareAfterExpiry(player, connection, phase);
        }
        Counters counts = this.counters.get(phase);
        Owner owner = this.owners.get(player.getUUID());
        Binding binding = null;
        if (owner != null && !this.closed && !this.engine.isShutDown()) {
            Binding existing = owner.binding;
            ServerGamePacketListenerImpl listener = player.connection;
            RegionState region = spatial.singleRegionState;
            EntityIdentity identity = spatial.singlePlayerIdentity;
            if (existing.server == player.server && existing.player == player && existing.listener == listener
                    && existing.connection == connection && existing.level == spatial.footprint().level()
                    && existing.identity.equals(identity) && region != null && !region.isDead()
                    && existing.region.equals(region.coordinate())
                    && listener != null && listener.player == player && listener.getConnection() == connection
                    && connection.getPacketListener() == listener && connection.isConnected() && listener.isAcceptingMessages()
                    && !player.isRemoved() && player.serverLevel() == existing.level
                    && !this.engine.isLevelClosed(existing.level)
                    && this.engine.regionLayout().isInRegion(existing.level, player.blockPosition(), existing.region)) {
                binding = existing;
            }
        }
        if (binding == null) {
            binding = this.closed || this.engine.isShutDown() ? null : this.captureFromSealedSpatial(player, connection, spatial);
            if (binding == null) {
                counts.reason(this.closed || this.engine.isShutDown() ? Reason.STALE : this.captureRefusal);
                return null;
            }
            if (owner == null || !owner.binding.sameOwner(binding)) {
                this.invalidateOwner(player.getUUID());
                if (this.owners.size() >= OWNER_LIMIT) {
                    counts.reason(Reason.CAPACITY);
                    return null;
                }
                owner = new Owner(binding, ++this.lifecycleSequence);
                this.owners.put(player.getUUID(), owner);
                this.handoffs++;
            }
        }
        if (this.pending.size() >= PENDING_LIMIT) {
            counts.reason(Reason.CAPACITY);
            return null;
        }
        String dimension = this.cachedDimensionLevel == binding.level && this.cachedDimensionName != null
                ? this.cachedDimensionName
                : (this.cachedDimensionName = (this.cachedDimensionLevel = binding.level).dimension().location().toString());
        Plan plan = new Plan(this, ++this.sequence, binding, owner.generation, this.tick(player.server), phase, dimension);
        this.pending.put(plan.id, plan);
        counts.prepared++;
        return plan;
    }

    @Nullable
    private Binding captureFromSealedSpatial(ServerPlayer player, Connection connection, RegionPlayerSpatialPlan spatial) {
        MinecraftServer server = player.server;
        this.captureRefusal = Reason.STALE;
        if (this.serverIdentity == null) this.serverIdentity = new WeakReference<>(server);
        else if (this.serverIdentity.get() != server) return null;
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer) {
            this.captureRefusal = Reason.FAKE_PLAYER;
            return null;
        }
        ServerGamePacketListenerImpl listener = player.connection;
        if (listener == null || listener.player != player || listener.getConnection() != connection
                || connection.getPacketListener() != listener || !connection.isConnected() || !listener.isAcceptingMessages() || player.isRemoved()) {
            this.captureRefusal = Reason.NOT_CURRENT_PLAYER;
            return null;
        }
        ServerLevel level = player.serverLevel();
        if (level != spatial.footprint().level() || this.engine.isLevelClosed(level)) return null;
        RegionState region = spatial.singleRegionState;
        EntityIdentity identity = spatial.singlePlayerIdentity;
        if (region == null || region.isDead() || identity == null
                || !this.engine.regionLayout().isInRegion(level, player.blockPosition(), region.coordinate())) {
            this.captureRefusal = Reason.NO_OWNER;
            return null;
        }
        return new Binding(server, player, listener, connection, level, level.entityIdentityLedger(), identity, region.coordinate());
    }

    @Nullable
    private Plan prepareAfterExpiry(ServerPlayer player, Connection connection, Phase phase) {
        Counters counts = this.counters.get(phase);
        Binding binding = this.closed || this.engine.isShutDown() ? null : this.capture(player, connection);
        if (binding == null) {
            counts.reason(this.closed || this.engine.isShutDown() ? Reason.STALE : this.captureRefusal);
            return null;
        }
        Owner owner = this.owners.get(player.getUUID());
        if (owner == null || !owner.binding.sameOwner(binding)) {
            this.invalidateOwner(player.getUUID());
            if (this.owners.size() >= OWNER_LIMIT) {
                counts.reason(Reason.CAPACITY);
                return null;
            }
            owner = new Owner(binding, ++this.lifecycleSequence);
            this.owners.put(player.getUUID(), owner);
            this.handoffs++;
        }
        if (this.pending.size() >= PENDING_LIMIT) {
            counts.reason(Reason.CAPACITY);
            return null;
        }
        String dimension = this.cachedDimensionLevel == binding.level && this.cachedDimensionName != null
                ? this.cachedDimensionName
                : (this.cachedDimensionName = (this.cachedDimensionLevel = binding.level).dimension().location().toString());
        Plan plan = new Plan(this, ++this.sequence, binding, owner.generation, this.tick(player.server), phase, dimension);
        this.pending.put(plan.id, plan);
        counts.prepared++;
        return plan;
    }

    private void expire(MinecraftServer server) {
        long tick = this.tick(server);
        if (!this.pending.isEmpty()) {
            for (Plan plan : new ArrayList<>(this.pending.values())) {
                if (plan.tick != tick || plan.binding == null || plan.binding.server != server) {
                    this.cancelIfUnstarted(plan);
                }
            }
        }
        if (this.lastExpireTick == tick && this.lastExpireServer == server) {
            return;
        }
        this.lastExpireTick = tick;
        this.lastExpireServer = server;
        this.recentStarts.values().removeIf(starts -> starts.expired(tick));
        this.owners.entrySet().removeIf(entry -> {
            Binding binding = entry.getValue().binding;
            boolean stale = binding.server != server || binding.player.isRemoved()
                    || server.getPlayerList().getPlayer(entry.getKey()) != binding.player;
            if (stale) this.invalidations++;
            return stale;
        });
    }

    /** Refuse a stale prepared call before any body effect. Never falls back or retries a started body. */
    public synchronized boolean admit(Plan plan) {
        this.issued(plan);
        Binding binding = plan.binding;
        if (binding == null) {
            if (plan.started) throw new IllegalStateException("Consumed player invocation cannot be replayed");
            return false;
        }
        serverThread(binding.server);
        if (plan.state != State.PREPARED) throw new IllegalStateException("Player invocation already admitted or consumed");
        Owner owner = this.owners.get(plan.uuid);
        Binding current = this.closed || this.engine.isShutDown() ? null : this.capture(binding.player, binding.connection);
        if (owner == null || owner.generation != plan.generation || current == null
                || !binding.sameOwner(current) || plan.tick != this.tick(binding.server)) {
            this.counters.get(plan.phase).reason(Reason.STALE);
            this.terminal(plan, State.REJECTED);
            return false;
        }
        plan.state = State.ADMITTED;
        return true;
    }

    private void issued(Plan plan) {
        if (plan.issuer != this) throw new IllegalArgumentException("Foreign player tick session");
    }

    synchronized boolean start(Plan plan) {
        this.issued(plan);
        Binding binding = plan.binding;
        if (binding == null) {
            if (plan.started) throw new IllegalStateException("Consumed player invocation cannot be replayed");
            return false;
        }
        serverThread(binding.server);
        if (plan.state != State.ADMITTED) throw new IllegalStateException("Player invocation is not admitted");
        Owner owner = this.owners.get(plan.uuid);
        Binding current = this.closed || this.engine.isShutDown() ? null : this.capture(binding.player, binding.connection);
        if (owner == null || owner.generation != plan.generation || current == null
                || !binding.sameOwner(current) || plan.tick != this.tick(binding.server)) {
            this.counters.get(plan.phase).reason(Reason.STALE);
            this.terminal(plan, State.REJECTED);
            return false;
        }
        return this.markStarted(plan, binding, false);
    }

    @Nullable
    private Starts findOrCreateStarts(Plan plan, ServerPlayer player) {
        Starts starts = this.recentStarts.get(plan.uuid);
        if (starts == null || starts.player.get() != player) {
            if (this.recentStarts.size() >= OWNER_LIMIT) {
                this.counters.get(plan.phase).reason(Reason.CAPACITY);
                this.terminal(plan, State.REJECTED);
                return null;
            }
            starts = new Starts(player);
            this.recentStarts.put(plan.uuid, starts);
        }
        return starts;
    }

    private static void beginBody(Plan plan, boolean worker) {
        plan.worker = worker;
        plan.thread = Thread.currentThread().getName();
        plan.startNanos = System.nanoTime();
        plan.started = true;
        plan.state = State.STARTED;
    }

    private boolean markStarted(Plan plan, Binding binding, boolean worker) {
        Starts starts = this.findOrCreateStarts(plan, binding.player);
        if (starts == null) return false;
        if (!starts.mark(plan.phase, plan.tick)) {
            this.counters.get(plan.phase).reason(Reason.DUPLICATE);
            this.terminal(plan, State.REJECTED);
            return false;
        }
        beginBody(plan, worker);
        Counters counts = this.counters.get(plan.phase);
        counts.started++;
        if (worker) counts.worker++;
        else {
            counts.serial++;
            counts.reason(binding.player.isPassenger() || !binding.player.getPassengers().isEmpty() ? Reason.RIDING : Reason.CALLBACK_CONTRACT_OPEN);
        }
        return true;
    }

    /** Requires the spatial/state scopes and the frozen original connection. */
    boolean startWorker(Plan plan) {
        this.issued(plan);
        Binding binding = plan.binding;
        RegionPlayerStateScope scope = RegionPlayerStateScope.current();
        if (!RegionWorkerScope.isNativeWorkerThread() || scope == null || currentWorkerInvocation() != null
                || !this.engine.playerNetworkGate().isFrozen()) {
            throw new IllegalStateException("Missing complete player worker boundary");
        }
        if (binding == null || this.engine.isShutDown()) return false;
        if (!this.engine.playerNetworkGate().workerConnectionFrozen(binding.connection)) {
            throw new IllegalStateException("Original player connection has no frozen worker boundary");
        }
        if (binding.player.connection != binding.listener || binding.listener.player != binding.player
                || binding.listener.getConnection() != binding.connection || binding.connection.getPacketListener() != binding.listener
                || !binding.connection.isConnected() || binding.player.serverLevel() != binding.level || binding.player.isRemoved()
                || plan.tick != this.tick(binding.server)) return false;
        if (!scope.bindsPlayer(binding.player)) return false;
        if (plan.prefixStarts == null && !this.engine.isOwnedByCurrentRegion(binding.player)) return false;

        Starts prefixStarts = plan.prefixStarts;
        if (prefixStarts != null) {
            // The publisher checked the owner generation for the prefix. Invalidation locks this
            // same plan before retiring it. Every unrelated Region uses its own duplicate guard.
            synchronized (plan) {
                if (plan.state != State.ADMITTED || !prefixStarts.mark(plan.phase, plan.tick)) return false;
                beginBody(plan, true);
            }
        } else {
            synchronized (this) {
                Owner owner = this.owners.get(plan.uuid);
                if (plan.state != State.ADMITTED || this.closed || owner == null || owner.generation != plan.generation
                        || !this.markStarted(plan, binding, true)) return false;
            }
        }
        setWorkerInvocation(plan);
        return true;
    }

    public static boolean executingWorker(ServerPlayer player, Phase phase) {
        Plan plan = currentWorkerInvocation();
        return plan != null && plan.phase == phase && plan.binding != null && plan.binding.player == player;
    }

    void workerReturned(Plan plan, @Nullable Throwable failure) {
        if (currentWorkerInvocation() != plan || !plan.started || plan.state != State.STARTED) throw new IllegalStateException("Foreign player worker return");
        clearWorkerInvocation();
        if (plan.prefixStarts != null) {
            recordBodyReturn(plan, failure);
        } else {
            synchronized (this) {
                this.returned(plan, failure);
            }
        }
    }

    synchronized Throwable finishWorkerPrefix(List<RegionPlayerTickDispatch.Call> calls) {
        Throwable cleanup = null;
        for (var call : calls) {
            call.admission.state().discardInvalidTemplate();
            if (call.ticket == null) continue;
            try {
                if (call.ticket.started()) this.joinWorker(call.ticket);
                else this.cancel(call.ticket);
            } catch (Throwable problem) {
                if (cleanup == null) cleanup = problem;
                else cleanup.addSuppressed(problem);
            }
        }
        return cleanup;
    }

    synchronized void joinWorker(Plan plan) {
        this.issued(plan);
        if (plan.binding != null) serverThread(plan.binding.server);
        if (plan.state != State.RETURNED && plan.state != State.FAILED) throw new IllegalStateException("Player worker has not physically returned");
        Counters counts = this.counters.get(plan.phase);
        if (plan.prefixStarts != null) {
            counts.started++;
            counts.worker++;
            if (plan.state == State.FAILED) counts.failed++;
            else counts.returned++;
        }
        counts.joined++;
        this.terminal(plan, State.JOINED);
    }

    /** P1 original-thread execution. A false result means no body started; exceptions propagate unchanged. */
    public boolean executeSerial(Plan plan, Runnable original) {
        if (!this.start(plan)) return false;
        Throwable failed = null;
        try {
            original.run();
        } catch (Throwable failure) {
            failed = failure;
            throw failure;
        } finally {
            this.complete(plan, failed);
        }
        return true;
    }

    synchronized void complete(Plan plan, @Nullable Throwable failure) {
        this.issued(plan);
        if (!plan.started || plan.state != State.STARTED) throw new IllegalStateException("Player body is not active");
        this.returned(plan, failure);
        this.counters.get(plan.phase).joined++;
        this.terminal(plan, State.JOINED);
    }

    private static void recordBodyReturn(Plan plan, @Nullable Throwable failure) {
        plan.returnNanos = System.nanoTime();
        plan.failureClass = failure == null ? null : failure.getClass().getName();
        plan.state = failure == null ? State.RETURNED : State.FAILED;
    }

    private void returned(Plan plan, @Nullable Throwable failure) {
        recordBodyReturn(plan, failure);
        Counters counts = this.counters.get(plan.phase);
        if (failure == null) counts.returned++;
        else counts.failed++;
    }

    public synchronized void recordUntrackedSerial(Phase phase, Reason reason) {
        Counters counts = this.counters.get(phase);
        counts.serial++;
        counts.untrackedSerial++;
        counts.reason(reason);
    }

    public synchronized boolean cancel(Plan plan) {
        this.issued(plan);
        if (plan.binding != null) serverThread(plan.binding.server);
        return this.cancelIfUnstarted(plan);
    }

    private boolean cancelIfUnstarted(Plan plan) {
        synchronized (plan) {
            if (plan.started || !this.pending.containsKey(plan.id)) return false;
            this.terminal(plan, State.CANCELLED);
            return true;
        }
    }

    private void terminal(Plan plan, State state) {
        this.pending.remove(plan.id);
        plan.binding = null;
        plan.state = state;
        this.recent.addLast(new Execution(plan.id, this.serverSession, plan.uuid, plan.generation, plan.tick,
                plan.phase, state, plan.started, plan.dimension, plan.identityEpoch, plan.playerInstance,
                plan.connectionInstance, plan.listenerInstance, plan.failureClass, plan.worker, plan.thread, plan.startNanos, plan.returnNanos));
        while (this.recent.size() > HISTORY_LIMIT) this.recent.removeFirst();
    }

    private void invalidateOwner(UUID uuid) {
        if (this.owners.remove(uuid) != null) this.invalidations++;
        for (Plan plan : new ArrayList<>(this.pending.values())) {
            if (plan.uuid.equals(uuid)) this.cancelIfUnstarted(plan);
        }
    }

    synchronized void invalidate(ServerPlayer player) {
        Owner owner = this.owners.get(player.getUUID());
        if (owner != null && owner.binding.player == player) this.invalidateOwner(player.getUUID());
    }

    synchronized void clearLevel(ServerLevel level) {
        if (this.pending.values().stream().anyMatch(plan -> plan.started && plan.binding != null && plan.binding.level == level)) {
            throw new IllegalStateException("Cannot retire an unjoined player invocation");
        }
        for (UUID uuid : this.owners.entrySet().stream().filter(entry -> entry.getValue().binding.level == level).map(Map.Entry::getKey).toList()) {
            this.invalidateOwner(uuid);
        }
    }

    synchronized void close() {
        if (this.pending.values().stream().anyMatch(plan -> plan.started)) throw new IllegalStateException("Player body has not joined");
        this.closed = true;
        for (Plan plan : new ArrayList<>(this.pending.values())) this.terminal(plan, State.CANCELLED);
        this.owners.clear();
        this.recentStarts.clear();
    }

    public synchronized Snapshot snapshot() {
        EnumMap<Phase, PhaseCounters> phases = new EnumMap<>(Phase.class);
        this.counters.forEach((phase, counts) -> {
            long active = 0, returned = 0, failed = 0;
            for (Plan plan : this.pending.values()) {
                if (plan.phase != phase || plan.prefixStarts == null) continue;
                State state = plan.state;
                if (state == State.STARTED || state == State.RETURNED || state == State.FAILED) active++;
                if (state == State.RETURNED) returned++;
                if (state == State.FAILED) failed++;
            }
            var joined = counts.snapshot();
            phases.put(phase, new PhaseCounters(joined.prepared(), joined.started() + active, joined.returned() + returned,
                    joined.failed() + failed, joined.joined(), joined.serial(), joined.worker() + active, joined.untrackedSerial(), joined.reasons()));
        });
        return new Snapshot(this.serverSession, this.owners.size(), this.pending.size(),
                (int) this.pending.values().stream().filter(plan -> plan.started).count(), this.handoffs,
                this.invalidations, List.copyOf(this.recent), Map.copyOf(phases));
    }
}
