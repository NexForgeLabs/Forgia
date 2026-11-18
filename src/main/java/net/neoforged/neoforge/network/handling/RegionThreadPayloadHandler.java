/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.network.handling;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter;
import org.jetbrains.annotations.ApiStatus;

/** Exact registered declaration. Used only by PayloadRegistrar.playToServerRegion. */
@ApiStatus.Internal
public final class RegionThreadPayloadHandler<T extends CustomPacketPayload> implements IPayloadHandler<T> {
    private final CustomPacketPayload.Type<T> type;
    private final Class<T> payloadClass;
    private final IRegionPayloadHandler<T> handler;

    public RegionThreadPayloadHandler(CustomPacketPayload.Type<T> type, Class<T> payloadClass, IRegionPayloadHandler<T> handler) {
        this.type = Objects.requireNonNull(type);
        this.payloadClass = Objects.requireNonNull(payloadClass);
        this.handler = Objects.requireNonNull(handler);
    }

    public ResourceLocation id() {
        return type.id();
    }

    public boolean matches(CustomPacketPayload payload) {
        return payload.getClass() == payloadClass && payload.type().equals(type);
    }

    @Override
    public void handle(T payload, IPayloadContext context) {
        if (!matches(payload)) throw new IllegalArgumentException("Declared payload type/class mismatch: " + id());
        RegionPlayerPacketRouter.handlePayload(this, payload, context, () -> {
            try (var local = new Context(context)) {
                handler.handle(payload, local);
            }
        });
    }

    private static final class Context implements IRegionPayloadContext, AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private IPayloadContext original;

        Context(IPayloadContext original) {
            this.original = original;
        }

        private IPayloadContext current() {
            if (original == null || Thread.currentThread() != owner) throw new IllegalStateException("Region payload context escaped its invocation");
            return original;
        }

        @Override
        public ServerPlayer player() {
            return (ServerPlayer) current().player();
        }

        @Override
        public boolean isRegionWorker() {
            current();
            return RegionPlayerPacketRouter.isPayloadWorker();
        }

        @Override
        public void reply(CustomPacketPayload payload) {
            current().reply(payload);
        }

        @Override
        public CompletableFuture<Void> enqueueWork(Consumer<IPayloadContext> task) {
            Objects.requireNonNull(task);
            IPayloadContext context = current();
            return RegionPlayerPacketRouter.enqueuePayloadWork(context, () -> task.accept(context));
        }

        @Override
        public void disconnect(Component reason) {
            enqueueWork(context -> context.disconnect(reason));
        }

        @Override
        public void close() {
            current();
            original = null;
        }
    }
}
