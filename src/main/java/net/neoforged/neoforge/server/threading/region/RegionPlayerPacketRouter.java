/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * First bounded owner packet protocol. Original PacketUtils queue positions remain sequence fences:
 * one audited packet joins physically before that server task returns. There is no second packet queue,
 * receiver-thread change, early success, or owner permission for unknown packets/async continuations.
 */
@ApiStatus.Internal
public final class RegionPlayerPacketRouter {
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Received> RECEIVING = new ThreadLocal<>();
    private final Map<Connection, Long> consumedReceives = new IdentityHashMap<>();
    private final Map<Connection, java.util.Set<RegionPlayerChatCompletion>> chats = new IdentityHashMap<>();
    private int chatPending;
    private long chatCancelled;
    private final RegionPlayerPacketTasks tasks = new RegionPlayerPacketTasks();
    private static final int HISTORY_LIMIT = 128;
    private long sequence;
    private long workers;
    private long refused;
    private long failed;
    private final ArrayDeque<Execution> recent = new ArrayDeque<>();

    public record Execution(long sequence, long receiveSequence, UUID player, String packet, String dimension, int regionX, int regionZ,
            int initialRegionX, int initialRegionZ, UUID serverSession, long identityEpoch, long criterionGeneration,
            int playerInstance, int listenerInstance, int connectionInstance,
            boolean started, String thread, long startNanos, long returnNanos, @Nullable String failureClass) {}

    public record Snapshot(long attempted, long workers, long refused, long failed, int connections, int chatPending, long chatCancelled,
            RegionPlayerPacketTasks.Snapshot tasks, List<Execution> recent) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(sequence, workers, refused, failed, consumedReceives.size(), chatPending, chatCancelled, tasks.snapshot(), List.copyOf(recent));
    }

    /** Original channel arrival metadata only. No world/ledger lookup or callback on the Netty thread. */
    public record Received(Connection connection, Packet<?> packet, PacketListener listener, @Nullable ServerPlayer player, long sequence) {}

    public static Received received(Connection connection, Packet<?> packet, long sequence) {
        PacketListener listener = connection.getPacketListener();
        return new Received(connection, packet, listener, listener instanceof ServerGamePacketListenerImpl play ? play.player : null, sequence);
    }

    public static final class ReceiveScope implements AutoCloseable {
        @Nullable
        private final Received previous;

        private ReceiveScope(@Nullable Received received) {
            previous = RECEIVING.get();
            if (received == null) RECEIVING.remove();
            else RECEIVING.set(received);
        }

        @Override
        public void close() {
            if (previous == null) RECEIVING.remove();
            else RECEIVING.set(previous);
        }
    }

    public static ReceiveScope receive(@Nullable Received received) {
        return new ReceiveScope(received);
    }

    @Nullable
    public static Received receipt(Packet<?> packet, PacketListener listener) {
        Received received = RECEIVING.get();
        return received != null && received.packet == packet && received.listener == listener ? received : null;
    }

    synchronized boolean consume(@Nullable Received received) {
        if (received == null) return true; // Internal synchronous preparation; public packet entry requires a receipt.
        long prior = consumedReceives.getOrDefault(received.connection, 0L);
        if (received.sequence <= prior || received.sequence <= 0 || !consumedReceives.containsKey(received.connection) && consumedReceives.size() >= 4096) return false;
        consumedReceives.put(received.connection, received.sequence);
        return true;
    }

    synchronized boolean register(RegionPlayerChatCompletion completion, Connection connection) {
        if (chatPending >= 4096) return false; // Keep the original serial continuation, never drop it.
        chats.computeIfAbsent(connection, key -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(completion);
        chatPending++;
        return true;
    }

    synchronized void consumed(RegionPlayerChatCompletion completion, Connection connection) {
        var pending = chats.get(connection);
        if (pending != null && pending.remove(completion)) {
            chatPending--;
            if (pending.isEmpty()) chats.remove(connection);
        }
    }

    synchronized void retire(Connection connection) {
        tasks.retire(connection);
        consumedReceives.remove(connection);
        var pending = chats.remove(connection);
        if (pending != null) for (var completion : pending) {
            completion.cancel();
            chatPending--;
            chatCancelled++;
        }
    }

    synchronized void close() {
        tasks.close();
        for (Connection connection : List.copyOf(chats.keySet())) retire(connection);
        consumedReceives.clear();
    }

    public static Runnable packetTask(Packet<?> packet, PacketListener listener, @Nullable Received receipt, Runnable original) {
        if (receipt == null || receipt.packet != packet || receipt.listener != listener || receipt.player == null
                || !(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) || !engine.playerTickPlanningRequested())
            return original;
        return engine.playerPackets().tasks.prepare(receipt.connection, packet, original);
    }

    public static void rejectedTask(Runnable task) {
        if (task instanceof RegionPlayerPacketTasks.Task pending) pending.cancel();
    }

    public static java.util.function.Consumer<net.minecraft.server.network.FilteredText> chatCompletion(
            ServerGamePacketListenerImpl listener, java.util.function.Consumer<net.minecraft.server.network.FilteredText> original) {
        return chatCompletion(listener, null, original);
    }

    public static java.util.function.Consumer<net.minecraft.server.network.FilteredText> chatCompletion(
            ServerGamePacketListenerImpl listener, @Nullable Received receipt, java.util.function.Consumer<net.minecraft.server.network.FilteredText> original) {
        if (!listener.player.server.isSameThread() || !(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
                || !engine.playerCriterionStorageEnabled() || RegionPlayerExtensionSnapshot.capture().refusal() != RegionPlayerAdmissionPlan.Refusal.NONE)
            return original;
        if (receipt != null && (receipt.listener != listener || receipt.player != listener.player || receipt.connection != listener.getConnection())) return original;
        // A signed broadcast can overflow any recipient's pending-signature cache and synchronously
        // disconnect that foreign session. This first protocol only admits unsigned original chat;
        // the complete signed consumer stays at its original server FutureChain position.
        if (receipt != null && (!(receipt.packet instanceof net.minecraft.network.protocol.game.ServerboundChatPacket chat) || chat.signature() != null)) return original;
        var completion = new RegionPlayerChatCompletion(engine, engine.playerPackets(), listener, receipt, original);
        return engine.playerPackets().register(completion, listener.getConnection()) ? completion : original;
    }

    /** Called inside the ORIGINAL server queue task and its original shouldHandle/error boundary. */
    public static <T extends PacketListener> boolean tryHandle(Packet<T> packet, T listener) {
        return tryHandle(packet, listener, receipt(packet, listener));
    }

    public static <T extends PacketListener> boolean tryHandle(Packet<T> packet, T listener, @Nullable Received received) {
        if (!(listener instanceof ServerGamePacketListenerImpl play) || play.player == null
                || !play.player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()
                || !RegionPlayerPacketPlan.known(packet)
                || !(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine))
            return false;
        if (received == null || received.packet != packet || received.listener != listener || received.player != play.player
                || received.connection != play.getConnection() || received.sequence <= 0)
            return false;
        try (var scope = receive(received)) {
            return engine.tryRunPlayerPacket(packet, play, () -> packet.handle(listener));
        }
    }

    /** Only the exact packet/listener under this consumed original state lease passes PacketUtils. */
    public static boolean isCurrent(Packet<?> packet, PacketListener listener) {
        Call call = CURRENT.get();
        return call != null && call.packet != null && call.packet == packet && call.listener == listener && call.started
                && call.player == call.listener.player && call.player.connection == listener
                && RegionWorkerScope.isNativeWorkerThread() && RegionPlayerStateScope.current() != null;
    }

    public static boolean isCurrent(Packet<?> packet, PacketListener listener, net.minecraft.util.thread.BlockableEventLoop<?> executor) {
        Call call = CURRENT.get();
        return call != null && executor == call.player.server && isCurrent(packet, listener);
    }

    static boolean supported(Packet<?> packet, ServerGamePacketListenerImpl listener) {
        return RegionPlayerPacketPlan.capture(packet, listener) != null;
    }

    /** Exact synchronous global fragment; worker is parked, no other group or server queue is pumped. */
    public static boolean moveTrackingOnPublisher(net.minecraft.server.level.ChunkMap map, ServerPlayer player) {
        if (!RegionWorkerScope.isNativeWorkerThread()) return false;
        Call call = CURRENT.get();
        if (call == null || !call.plan.movement || call.player != player || player.serverLevel().getChunkSource().chunkMap != map)
            throw new IllegalStateException("Uncaptured player tracking service");
        if (++call.trackingCalls != 1) throw new IllegalStateException("Player tracking service budget exceeded");
        call.requestPublisher(() -> map.move(player));
        return true;
    }

    public static boolean chatBroadcastOnPublisher(ServerGamePacketListenerImpl listener, net.minecraft.network.chat.PlayerChatMessage message, Runnable original) {
        if (!RegionWorkerScope.isNativeWorkerThread()) return false;
        Call call = CURRENT.get();
        if (call == null || call.packet != null || call.listener != listener || call.player != listener.player
                || !message.sender().equals(call.player.getUUID()) || ++call.chatBroadcasts != 1)
            throw new IllegalStateException("Uncaptured original chat broadcast service");
        call.requestPublisher(original);
        return true;
    }

    synchronized long next() {
        return ++sequence;
    }

    synchronized void finish(long sequence, Call call) {
        if (call.started) workers++;
        else refused++;
        if (call.failure != null) failed++;
        var home = call.admission.spatial().conflictEntry().home();
        recent.addLast(new Execution(sequence, call.received == null ? 0 : call.received.sequence, call.player.getUUID(), call.packet == null ? "ASYNC_FILTERED_CHAT" : call.packet.getClass().getName(),
                call.player.serverLevel().dimension().location().toString(), home.regionX(), home.regionZ(),
                call.initialRegion.regionX(), call.initialRegion.regionZ(), call.serverSession, call.identityEpoch, call.criterionGeneration,
                System.identityHashCode(call.player), System.identityHashCode(call.listener), System.identityHashCode(call.listener.getConnection()),
                call.started, call.thread, call.startNanos, call.returnNanos,
                call.failure == null ? null : call.failure.getClass().getName()));
        while (recent.size() > HISTORY_LIMIT) recent.removeFirst();
    }

    /** Lives only until the publisher's physical join. History contains no live game references. */
    static final class Call {
        @Nullable
        final Packet<?> packet;
        final ServerGamePacketListenerImpl listener;
        final ServerPlayer player;
        final RegionPlayerAdmissionPlan admission;
        final RegionPlayerPacketPlan plan;
        @Nullable
        final Received received;
        final RegionCoordinate initialRegion;
        final UUID serverSession;
        final long identityEpoch;
        final long criterionGeneration;
        final Runnable original;
        boolean started;
        String thread = "";
        long startNanos;
        long returnNanos;
        @Nullable
        Throwable failure;
        private final AtomicReference<PublisherRequest> service = new AtomicReference<>();
        private int trackingCalls;
        private int chatBroadcasts;

        Call(@Nullable Packet<?> packet, ServerGamePacketListenerImpl listener, RegionPlayerAdmissionPlan admission, RegionPlayerPacketPlan plan, Runnable original) {
            this.packet = packet;
            this.listener = listener;
            this.player = listener.player;
            this.admission = admission;
            this.plan = plan;
            this.received = packet == null ? RECEIVING.get() : receipt(packet, listener);
            this.initialRegion = admission.spatial().conflictEntry().home();
            this.serverSession = ((RegionThreadingEngine) RegionThreadingManager.currentOrNull()).playerTicks().snapshot().serverSession();
            this.identityEpoch = admission.spatial().members().get(player).epoch();
            this.criterionGeneration = player.getAdvancements().forgiaCriterionListeners().generation();
            this.original = original;
        }

        void run(RegionPlayerSpatialLease lease) {
            if (!RegionWorkerScope.isNativeWorkerThread()) return; // Original pool rejection fallback is pre-body.
            try (var preparation = plan.prepare(); var spatial = lease.open()) {
                RegionPlayerStateScope state;
                try {
                    state = lease.openPlayerState(admission.state());
                } catch (IllegalStateException refusal) {
                    return;
                }
                try (state) {
                    if (listener.player != player || player.connection != listener || !plan.current()) return;
                    if (CURRENT.get() != null) throw new IllegalStateException("Nested owner packet invocation");
                    CURRENT.set(this);
                    started = true;
                    thread = Thread.currentThread().getName();
                    startNanos = System.nanoTime();
                    try {
                        original.run();
                    } catch (Throwable problem) {
                        failure = problem;
                    } finally {
                        returnNanos = System.nanoTime();
                        CURRENT.remove();
                    }
                }
            }
        }

        void await(RegionTaskBatch.Barrier barrier) {
            boolean interrupted = false;
            while (!barrier.isComplete()) {
                PublisherRequest request = service.getAndSet(null);
                if (request != null) request.run();
                else LockSupport.parkNanos(100_000L);
                if (Thread.interrupted()) interrupted = true;
            }
            barrier.awaitCompletion();
            if (interrupted) Thread.currentThread().interrupt();
            if (service.get() != null) throw new IllegalStateException("Unconsumed player service after physical return");
        }

        private void requestPublisher(Runnable original) {
            PublisherRequest request = new PublisherRequest(original);
            if (!service.compareAndSet(null, request)) throw new IllegalStateException("Overlapping player services");
            try {
                request.result.join(); // Completion only by the original publisher, never a general executor.
            } catch (java.util.concurrent.CompletionException wrapped) {
                Throwable cause = wrapped.getCause();
                if (cause instanceof RuntimeException exception) throw exception;
                if (cause instanceof Error error) throw error;
                throw wrapped;
            }
        }

        private record PublisherRequest(Runnable original, CompletableFuture<Void> result) {
            PublisherRequest(Runnable original) {
                this(original, new CompletableFuture<>());
            }

            void run() {
                try {
                    original.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }
        }
    }
}
