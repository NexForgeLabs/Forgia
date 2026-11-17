/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playerfault;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

/** Explicit unknown extension: never eligible for a player worker. Original synchronous callback fault. */
@Mod("forgia_player_world_fault")
public final class WorldFault {
    private static String armed;
    private static int injected;

    private static void save(String file, Object value) {
        try {
            Path path = Path.of(file);
            if (Files.exists(path)) throw new IllegalStateException("Preserve existing original world fault evidence");
            Files.writeString(path, new Gson().toJson(value));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public WorldFault() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("player_world_fault").requires(source -> source.hasPermission(4))
                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            armed = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
                            ctx.getSource().sendSuccess(() -> Component.literal("One original world criterion callback fault armed"), false);
                            return 1;
                        }))));
        NeoForge.EVENT_BUS.addListener((AdvancementEvent.AdvancementProgressEvent event) -> {
            if (!(event.getEntity() instanceof ServerPlayer player) || !player.getGameProfile().getName().equals(armed)
                    || !event.getAdvancement().id().toString().equals("forgia_player:tick") || !event.getProgressType().toString().equals("GRANT")) return;
            armed = null;
            injected++;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("injected", injected);
            evidence.put("thread", Thread.currentThread().getName());
            evidence.put("serverThread", player.server.isSameThread());
            evidence.put("tick", player.server.getTickCount());
            evidence.put("uuid", player.getUUID().toString());
            evidence.put("criterionAlreadyDone", player.getAdvancements().getOrStartProgress(event.getAdvancement()).isDone());
            evidence.put("experience", player.totalExperience);
            save("world-fault-effect.json", evidence);
            throw new IllegalStateException("player_world_fault_after_original_criterion_progress");
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            if (injected != 1) return;
            if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) throw new IllegalStateException("Missing original engine at world-fault unwind");
            save("world-fault-unwound.json", Map.of("diagnostics", engine.playerTickDiagnostics(event.getServer()), "injected", injected));
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            if (injected == 1 && RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)
                save("world-fault-stopped.json", Map.of("phases", engine.playerTicks().snapshot(), "network", engine.playerNetworkGate().snapshot(),
                        "diagnostics", engine.playerTickDiagnostics(event.getServer()), "injected", injected, "lifecycleEvent", "ServerStopped"));
        });
    }
}
