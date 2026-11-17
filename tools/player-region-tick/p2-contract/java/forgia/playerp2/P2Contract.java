/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playerp2;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

/** Unknown Mod by design. Tests synchronous results and the unchanged server-thread payload API. */
@Mod("forgia_player_p2_contract")
public final class P2Contract {
    private static long sequence;
    private static boolean cancelTeleport, cancelDimension;

    public record Probe(String text) implements CustomPacketPayload {
        public static final Type<Probe> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("forgia_p2", "probe"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Probe> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Probe::text, Probe::new);
        @Override
        public Type<Probe> type() { return TYPE; }
    }

    private static synchronized void record(String event, ServerPlayer player, Object detail) {
        var row = new LinkedHashMap<String, Object>();
        row.put("seq", ++sequence);
        row.put("event", event);
        row.put("thread", Thread.currentThread().getName());
        row.put("serverThread", player.server.isSameThread());
        row.put("tick", player.server.getTickCount());
        row.put("uuid", player.getUUID().toString());
        row.put("instance", System.identityHashCode(player));
        row.put("dimension", player.serverLevel().dimension().location().toString());
        row.put("x", player.getX());
        row.put("detail", detail);
        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
            row.put("running", engine.playerTicks().snapshot().running());
            row.put("frozen", engine.playerNetworkGate().snapshot().frozen());
        }
        try { Files.writeString(Path.of("p2-contract.ndjson"), new Gson().toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void handle(Probe payload, IPayloadContext context) {
        var player = (ServerPlayer) context.player();
        String text = payload.text();
        record("payload_enter", player, text);
        if (!player.server.isSameThread()) throw new AssertionError("MainThreadPayloadHandler contract changed");
        var future = context.enqueueWork(() -> record("enqueue_inline", player, text));
        record("payload_return", player, text + ":done=" + future.isDone());
        player.sendSystemMessage(Component.literal("p2_ack:" + text));
        switch (text) {
            case "cancel_tp" -> cancelTeleport = true;
            case "cancel_dimension" -> cancelDimension = true;
            case "reconfigure" -> player.connection.switchToConfig();
            case "throw" -> context.enqueueWork(() -> { throw new IllegalStateException("p2_original_main_payload_failure"); });
            default -> {}
        }
    }

    public P2Contract(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").optional().playToServer(Probe.TYPE, Probe.CODEC, P2Contract::handle));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) -> event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("p2_return_config").requires(source -> source.hasPermission(4)).executes(ctx -> {
                    int count = 0;
                    for (var connection : ctx.getSource().getServer().getConnection().getConnections()) {
                        if (connection.getPacketListener() instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl listener) {
                            listener.startConfiguration();
                            count++;
                        }
                    }
                    int actual = count;
                    ctx.getSource().sendSuccess(() -> Component.literal("Original configuration started: " + actual), false);
                    return count;
                })));

        NeoForge.EVENT_BUS.addListener((EntityTeleportEvent.TeleportCommand event) -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            boolean cancel = cancelTeleport;
            cancelTeleport = false;
            event.setCanceled(cancel);
            record("teleport_command", player, "target=" + event.getTarget() + ":cancelled=" + event.isCanceled());
        });
        NeoForge.EVENT_BUS.addListener((EntityTravelToDimensionEvent event) -> {
            if (!(event.getEntity() instanceof ServerPlayer player)) return;
            boolean cancel = cancelDimension;
            cancelDimension = false;
            event.setCanceled(cancel);
            record("travel", player, event.getDimension().location() + ":cancelled=" + event.isCanceled());
        });
        NeoForge.EVENT_BUS.addListener((ServerChatEvent event) -> record("chat", event.getPlayer(), event.getRawText()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> record("login", (ServerPlayer) event.getEntity(), "login"));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> record("logout", (ServerPlayer) event.getEntity(), "logout"));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> record("respawn", (ServerPlayer) event.getEntity(), "end=" + event.isEndConquered()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
                try { Files.writeString(Path.of("p2-stopped.json"), new Gson().toJson(engine.playerTickDiagnostics(event.getServer())), StandardOpenOption.CREATE_NEW); }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
        });
    }
}
