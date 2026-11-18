/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/** A cancellable envelope in the ORIGINAL ordered chat chain, never a second executor or queue. */
final class RegionPlayerChatCompletion implements Consumer<FilteredText> {
    @Nullable
    private RegionThreadingEngine engine;
    @Nullable
    private RegionPlayerPacketRouter issuer;
    @Nullable
    private WeakReference<ServerGamePacketListenerImpl> listener;
    @Nullable
    private WeakReference<ServerPlayer> player;
    @Nullable
    private WeakReference<Connection> connection;
    @Nullable
    private Consumer<FilteredText> original;
    @Nullable
    private RegionPlayerPacketRouter.Received receipt;

    RegionPlayerChatCompletion(RegionThreadingEngine engine, RegionPlayerPacketRouter issuer, ServerGamePacketListenerImpl listener, @Nullable RegionPlayerPacketRouter.Received receipt, Consumer<FilteredText> original) {
        this.engine = engine;
        this.issuer = issuer;
        this.listener = new WeakReference<>(listener);
        this.player = new WeakReference<>(listener.player);
        this.connection = new WeakReference<>(listener.getConnection());
        this.original = original;
        this.receipt = receipt;
    }

    @Override
    public void accept(FilteredText value) {
        ServerGamePacketListenerImpl live;
        Connection channel;
        Consumer<FilteredText> callback;
        RegionThreadingEngine owner;
        ServerPlayer expected;
        RegionPlayerPacketRouter issuer;
        RegionPlayerPacketRouter.Received receipt;
        synchronized (this) {
            if (original == null) return;
            live = listener.get();
            channel = connection.get();
            callback = original;
            owner = engine;
            expected = player.get();
            issuer = this.issuer;
            receipt = this.receipt;
            cancel(); // The original local frame owns the begun callback until physical return.
        }
        issuer.consumed(this, channel);
        if (live != null && !live.player.server.isSameThread()) throw new IllegalStateException("Original chat continuation left its server executor");
        try (var arrival = RegionPlayerPacketRouter.receive(receipt)) {
            if (live != null && expected != null && live.player == expected && live.getConnection() == channel
                    && channel.getPacketListener() == live && channel.isConnected() && live.isAcceptingMessages()
                    && value.getClass() == FilteredText.class && owner.tryRunPlayerChatCompletion(live, () -> callback.accept(value)))
                return;
            callback.accept(value); // Unknown/stale/unstarted is the complete original serial continuation.
        }
    }

    synchronized void cancel() {
        original = null;
        engine = null;
        issuer = null;
        listener = null;
        player = null;
        connection = null;
        receipt = null;
    }
}
