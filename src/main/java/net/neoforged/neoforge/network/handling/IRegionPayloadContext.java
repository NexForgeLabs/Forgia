/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.network.handling;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.ApiStatus;

/** Invocation-scoped context for an explicitly declared server PLAY payload. */
@ApiStatus.NonExtendable
public interface IRegionPayloadContext {
    /** Sender of this exact session. Only owner-local operations covered by the declaration are allowed. */
    ServerPlayer player();

    /** Whether this invocation was admitted to a region worker; false means complete MAIN fallback. */
    boolean isRegionWorker();

    /** Sends a reply using the original connection send path. */
    void reply(CustomPacketPayload payload);

    /**
     * Runs work with the original MAIN payload context, after this worker physically returns.
     * Never wait for the returned future from a region handler. A MAIN fallback may execute inline,
     * just like {@link IPayloadContext#enqueueWork(Runnable)}. This is not an owner continuation API.
     * A pending worker-originated task is cancelled on session retirement; an already begun task
     * completes once. Retain/release any reference-counted payload data needed beyond the handler
     * using its own ownership API; a Java lambda does not extend a packet's buffer lifetime.
     */
    CompletableFuture<Void> enqueueWork(Consumer<IPayloadContext> task);

    /** Requests an original MAIN-thread disconnect after physical worker return. */
    void disconnect(Component reason);
}
