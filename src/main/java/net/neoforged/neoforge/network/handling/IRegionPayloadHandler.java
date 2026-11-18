/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.network.handling;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * An explicit promise that this server PLAY handler, including every callback it invokes, is safe
 * within the sender's captured local player scope, including installed transformations and listeners
 * reached by that handler. A declaration does not approve the mod's ticks,
 * other payloads, listeners or transformations. Rejected admission runs the same handler on MAIN.
 *
 * <p>Do not access other players, global mutable state or uncaptured world objects, change the player
 * identity/position/menu, or synchronously wait for server work. Use {@link IRegionPayloadContext#enqueueWork}
 * for those operations. Never retain the context or player beyond this synchronous invocation.
 * A thrown handler is never replayed, including when it already changed state.
 */
@FunctionalInterface
public interface IRegionPayloadHandler<T extends CustomPacketPayload> {
    void handle(T payload, IRegionPayloadContext context);
}
