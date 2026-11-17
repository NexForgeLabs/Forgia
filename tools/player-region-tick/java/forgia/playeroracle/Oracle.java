/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

/** Test-only, bounded disk trace; no world writes, schedulers or authorization. */
@Mod("forgia_player_oracle")
public final class Oracle {
    private static final Gson JSON = new Gson();
    private static final long LIMIT = 500_000;
    private static BufferedWriter writer;
    private static long sequence;
    private static volatile boolean active;
    private static volatile MinecraftServer server;
    private static String failConnectionPlayer;
    private static int injectedFailures;
    // Read-only diagnostics of the engine's existing authoritative mapping; no move/publish calls.
    private static final Method REGION_FOR = method(RegionThreadingEngine.class, "regionFor", Entity.class);

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Oracle cannot inspect original region membership", ex); }
    }

    public Oracle() {
        ResourceOracle.install();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("player_oracle").requires(source -> source.hasPermission(4))
                        .then(Commands.literal("start").executes(ctx -> {
                            synchronized (Oracle.class) {
                                if (writer != null) throw new IllegalStateException("Trace already opened; use a fresh server directory");
                                try { writer = Files.newBufferedWriter(Path.of("player-oracle.ndjson")); }
                                catch (IOException ex) { throw new IllegalStateException(ex); }
                                server = ctx.getSource().getServer();
                                active = true;
                            }
                            emit("TRACE_START", null, "off baseline; full original methods");
                            ctx.getSource().sendSuccess(() -> Component.literal("Player oracle started; bounded trace, no worker admission"), false);
                            return 1;
                        }))
                        .then(Commands.literal("criterion_storage").executes(ctx -> {
                            try {
                                Map<String, Object> evidence = new LinkedHashMap<>();
                                var playersField = net.minecraft.advancements.critereon.SimpleCriterionTrigger.class.getDeclaredField("players");
                                playersField.setAccessible(true);
                                var globalTickPlayers = (Map<?, ?>) playersField.get(net.minecraft.advancements.CriteriaTriggers.TICK);
                                for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                                    Map<String, Object> owner = new LinkedHashMap<>();
                                    var advancements = player.getAdvancements();
                                    var storage = advancements.forgiaCriterionListeners();
                                    owner.put("advancementInstance", System.identityHashCode(advancements));
                                    owner.put("localStorage", storage.enabled());
                                    owner.put("localTriggers", storage.triggerCount());
                                    var local = storage.get(net.minecraft.advancements.CriteriaTriggers.TICK);
                                    owner.put("localTickListeners", local == null ? 0 : local.size());
                                    owner.put("legacyGlobalContainsOwner", globalTickPlayers.containsKey(advancements));
                                    owner.put("totalExperience", player.totalExperience);
                                    evidence.put(player.getGameProfile().getName(), owner);
                                }
                                Path output = Path.of("player-criterion-storage.json");
                                if (Files.exists(output)) throw new IllegalStateException("Preserve prior criterion storage evidence");
                                Files.writeString(output, JSON.toJson(evidence));
                            } catch (IOException | ReflectiveOperationException error) { throw new IllegalStateException(error); }
                            ctx.getSource().sendSuccess(() -> Component.literal("Original player criterion storage saved"), false);
                            return 1;
                        }))
                        .then(Commands.literal("fail_connection").then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            failConnectionPlayer = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
                            ctx.getSource().sendSuccess(() -> Component.literal("One deliberate original PlayerTick.Pre failure armed"), false);
                            return 1;
                        })))
                        .then(Commands.literal("planning").executes(ctx -> {
                            if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) {
                                throw new IllegalStateException("No original engine installed");
                            }
                            try {
                                Map<String, Object> evidence = new LinkedHashMap<>();
                                evidence.put("requested", engine.playerTickPlanningRequested());
                                evidence.put("injectedFailures", injectedFailures);
                                evidence.put("snapshot", engine.playerTicks().snapshot());
                                evidence.put("persistentWorkers", engine.diagnostics().workerDispatch().persistentWorkers());
                                Files.writeString(Path.of("player-planning.json"), JSON.toJson(evidence));
                            } catch (IOException error) { throw new IllegalStateException(error); }
                            ctx.getSource().sendSuccess(() -> Component.literal("Player planning snapshot saved; workerAdmission=false"), false);
                            return 1;
                        }))
                        .then(Commands.literal("stop").executes(ctx -> {
                            emit("TRACE_STOP", null, "requested");
                            close();
                            ctx.getSource().sendSuccess(() -> Component.literal("Player oracle stopped and flushed"), false);
                            return 1;
                        }))
                        .then(Commands.literal("mark").then(Commands.argument("label", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                            emit("MARK", null, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "label"));
                            for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) emit("STATE", player, "mark");
                            for (var level : ctx.getSource().getServer().getAllLevels()) {
                                for (Entity entity : level.getAllEntities()) {
                                    if (entity.getTags().contains("oracle_target")) emit("TARGET_STATE", entity, "mark");
                                }
                            }
                            ResourceOracle.snapshot(ctx.getSource().getServer());
                            flush();
                            return 1;
                        })))));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> emit("SERVER_PRE", null, ""));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> emit("SERVER_POST", null, ""));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Pre event) -> { if (!event.getLevel().isClientSide()) emit("LEVEL_PRE", null, event.getLevel().dimension().location().toString()); });
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> { if (!event.getLevel().isClientSide()) emit("LEVEL_POST", null, event.getLevel().dimension().location().toString()); });
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Pre event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                emit("PLAYER_PRE", player, "");
                if (player.getGameProfile().getName().equals(failConnectionPlayer)) {
                    failConnectionPlayer = null;
                    injectedFailures++;
                    emit("INJECTED_FAILURE", player, "player_oracle_connection_after_pre:" + injectedFailures);
                    throw new IllegalStateException("player_oracle_connection_after_pre");
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerTickEvent.Post event) -> { if (event.getEntity() instanceof ServerPlayer player) emit("PLAYER_POST", player, ""); });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer) {
                boolean cancel = event.getTarget().getTags().contains("oracle_cancel");
                if (cancel) event.setCanceled(true);
                emit("ATTACK_EVENT", event.getTarget(), "source=" + event.getEntity().getId() + ",cancel=" + cancel);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) -> {
            if (event.getEntity() instanceof ServerPlayer) {
                boolean cancel = event.getTarget().getTags().contains("oracle_cancel");
                if (cancel) {
                    event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
                    event.setCanceled(true);
                }
                emit("INTERACT_EVENT", event.getTarget(), "source=" + event.getEntity().getId() + ",cancel=" + cancel + ",result=" + event.getCancellationResult());
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingDamageEvent.Post event) -> {
            Entity source = event.getSource().getEntity();
            if (event.getEntity() instanceof ServerPlayer || source instanceof ServerPlayer) {
                emit("DAMAGE_POST", event.getEntity(), "source=" + (source == null ? "none" : source.getId()) + ",damage=" + event.getNewDamage());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementProgressEvent event) -> {
            if (event.getAdvancement().id().toString().equals("forgia_player:tick")) {
                emit("CRITERION_PROGRESS", event.getEntity(), event.getProgressType() + ":" + event.getCriterionName());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> emit("LOGIN", event.getEntity(), ""));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> emit("LOGOUT", event.getEntity(), ""));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> emit("DIMENSION", event.getEntity(), event.getFrom().location() + "->" + event.getTo().location()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> emit("RESPAWN", event.getEntity(), ""));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.Clone event) -> emit("CLONE", event.getEntity(), "oldInstance=" + System.identityHashCode(event.getOriginal())));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> close());
    }

    public static synchronized void emit(String kind, Entity entity, String detail) {
        if (!active) return;
        if (sequence >= LIMIT) throw new IllegalStateException("Player oracle trace budget exhausted; refusing silent truncation");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema", 2);
        row.put("seq", ++sequence);
        row.put("nano", System.nanoTime());
        row.put("tick", server.getTickCount());
        row.put("kind", kind);
        row.put("thread", Thread.currentThread().getName());
        row.put("serverThread", server.isSameThread());
        row.put("detail", detail);
        if (entity != null) {
            row.put("id", entity.getId());
            row.put("uuid", entity.getUUID().toString());
            row.put("instance", System.identityHashCode(entity));
            row.put("dimension", entity.level().dimension().location().toString());
            row.put("x", entity.getX()); row.put("y", entity.getY()); row.put("z", entity.getZ());
            row.put("removed", entity.isRemoved());
            row.put("type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            row.put("tags", entity.getTags());
            if (entity instanceof LivingEntity living) row.put("health", living.getHealth());
            if (entity instanceof ServerPlayer player && (kind.equals("STATE") || kind.equals("INTERACT_RESULT"))) {
                Map<String, Integer> inventory = new LinkedHashMap<>();
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    var stack = player.getInventory().getItem(slot);
                    if (!stack.isEmpty()) inventory.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
                }
                row.put("inventory", inventory);
                row.put("selectedSlot", player.getInventory().selected);
                row.put("totalExperience", player.totalExperience);
                row.put("experienceLevel", player.experienceLevel);
                row.put("experienceProgress", player.experienceProgress);
                row.put("menuId", player.containerMenu.containerId);
                row.put("carriedItem", ResourceOracle.item(player.containerMenu.getCarried()));
                row.put("carriedCount", player.containerMenu.getCarried().getCount());
            }
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity drop) {
                row.put("item", ResourceOracle.item(drop.getItem())); row.put("count", drop.getItem().getCount());
            }
            if (entity instanceof net.minecraft.world.entity.ExperienceOrb orb) {
                row.put("value", orb.getValue());
                row.put("count", orb.saveWithoutId(new net.minecraft.nbt.CompoundTag()).getInt("Count"));
            }
            if (entity instanceof ServerPlayer player) {
                row.put("name", player.getGameProfile().getName());
                row.put("spectator", player.isSpectator());
                row.put("passenger", player.isPassenger());
                row.put("vehicleId", player.getVehicle() == null ? null : player.getVehicle().getId());
                row.put("sleeping", player.isSleeping());
                row.put("changingDimension", player.isChangingDimension());
                row.put("listener", System.identityHashCode(player.connection));
                row.put("connection", player.connection == null ? 0 : System.identityHashCode(player.connection.getConnection()));
                row.put("identity", player.serverLevel().entityIdentityLedger().liveIdentity(player));
                if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine && server.isSameThread()) {
                    var coordinate = engine.regionCoordinateForDiagnostics(player.serverLevel(), player.blockPosition());
                    row.put("regionX", coordinate.regionX()); row.put("regionZ", coordinate.regionZ());
                    row.put("indexedRegions", engine.indexedRegionCount(player));
                    try {
                        Object region = REGION_FOR.invoke(engine, player);
                        if (region != null) {
                            var actual = (net.neoforged.neoforge.server.threading.region.RegionCoordinate) method(region.getClass(), "coordinate").invoke(region);
                            row.put("ownerRegionX", actual.regionX()); row.put("ownerRegionZ", actual.regionZ());
                            row.put("ownerDimension", actual.level().dimension().location().toString());
                        }
                    } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Oracle original membership read failed", ex); }
                }
            }
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel level
                    && RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine && server.isSameThread()) {
                var coordinate = engine.regionCoordinateForDiagnostics(level, entity.blockPosition());
                row.put("regionX", coordinate.regionX()); row.put("regionZ", coordinate.regionZ());
                row.put("indexedRegions", engine.indexedRegionCount(entity));
                try {
                    Object region = REGION_FOR.invoke(engine, entity);
                    if (region != null) {
                        var actual = (net.neoforged.neoforge.server.threading.region.RegionCoordinate) method(region.getClass(), "coordinate").invoke(region);
                        row.put("ownerRegionX", actual.regionX()); row.put("ownerRegionZ", actual.regionZ());
                        row.put("ownerDimension", actual.level().dimension().location().toString());
                    }
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException("Oracle original membership read failed", ex); }
            }
        }
        try { writer.write(JSON.toJson(row)); writer.newLine(); }
        catch (IOException ex) { throw new IllegalStateException("Player oracle disk trace failed", ex); }
    }

    private static synchronized void flush() {
        try { writer.flush(); }
        catch (IOException ex) { throw new IllegalStateException(ex); }
    }

    private static synchronized void close() {
        active = false;
        if (writer != null) {
            try { writer.close(); }
            catch (IOException ex) { throw new IllegalStateException(ex); }
        }
    }
}
