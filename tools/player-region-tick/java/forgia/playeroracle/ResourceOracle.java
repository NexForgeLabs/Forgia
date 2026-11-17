/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.BoolArgumentType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;

/** Explicit opt-in resource observations and cancellation fixture; grants no game/worker authority. */
public final class ResourceOracle {
    private static final Gson JSON = new Gson();
    private static BlockPos watched;
    private static boolean cancel;

    public static String item(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    public static Map<String, Integer> inventory(Container container) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (!stack.isEmpty()) result.merge(item(stack), stack.getCount(), Integer::sum);
        }
        return result;
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(
                Commands.literal("resource_oracle").requires(source -> source.hasPermission(4))
                        .then(Commands.literal("watch").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
                            watched = BlockPosArgument.getBlockPos(ctx, "pos").immutable(); return 1;
                        })))
                        .then(Commands.literal("cancel").then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            cancel = BoolArgumentType.getBool(ctx, "value"); return 1;
                        })))));
        NeoForge.EVENT_BUS.addListener((BlockEvent.BreakEvent event) -> {
            if (event.getPlayer() instanceof ServerPlayer player) {
                boolean cancelled = cancel && event.getPos().equals(watched);
                if (cancelled) event.setCanceled(true);
                Oracle.emit("BLOCK_BREAK_EVENT", player, event.getPos().toShortString() + ",cancel=" + cancelled);
            }
        });
        NeoForge.EVENT_BUS.addListener((BlockEvent.EntityPlaceEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("BLOCK_PLACE_EVENT", player, event.getPos().toShortString());
        });
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.RightClickBlock event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                // The clicked support is one below the watched placement position.
                boolean cancelled = cancel && (event.getPos().equals(watched) || event.getPos().above().equals(watched));
                if (cancelled) { event.setCancellationResult(InteractionResult.FAIL); event.setCanceled(true); }
                Oracle.emit("RIGHT_BLOCK_EVENT", player, event.getPos().toShortString() + ",cancel=" + cancelled);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Open event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("MENU_OPEN_EVENT", player, "container=" + event.getContainer().containerId);
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Close event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("MENU_CLOSE_EVENT", player, "container=" + event.getContainer().containerId);
        });
        NeoForge.EVENT_BUS.addListener((ItemEntityPickupEvent.Post event) -> {
            if (event.getPlayer() instanceof ServerPlayer player) Oracle.emit("ITEM_PICKUP_POST", player,
                    "target=" + event.getItemEntity().getId() + ",item=" + item(event.getOriginalStack()) + ",count=" + (event.getOriginalStack().getCount()-event.getCurrentStack().getCount()));
        });
        NeoForge.EVENT_BUS.addListener((PlayerXpEvent.PickupXp event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("XP_PICKUP_EVENT", player, "target=" + event.getOrb().getId() + ",value=" + event.getOrb().getValue());
        });
        NeoForge.EVENT_BUS.addListener((PlayerXpEvent.XpChange event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("XP_CHANGE_EVENT", player, "amount=" + event.getAmount());
        });
        NeoForge.EVENT_BUS.addListener((LivingDropsEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                Map<String,Integer> drops = new LinkedHashMap<>();
                for (ItemEntity drop : event.getDrops()) drops.merge(item(drop.getItem()), drop.getItem().getCount(), Integer::sum);
                Oracle.emit("DEATH_DROPS_EVENT", player, JSON.toJson(drops));
            }
        });
        NeoForge.EVENT_BUS.addListener((LivingExperienceDropEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) Oracle.emit("DEATH_XP_EVENT", player, "amount=" + event.getDroppedExperience());
        });
    }

    public static void snapshot(MinecraftServer server) {
        if (watched == null) return;
        var level = server.overworld();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) Oracle.emit("RESOURCE_STATE", entity, "mark");
        }
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("x", watched.getX()); row.put("y", watched.getY()); row.put("z", watched.getZ());
        row.put("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(watched).getBlock()).toString());
        row.put("state", level.getBlockState(watched).toString());
        var be = level.getBlockEntity(watched);
        row.put("blockEntity", be == null ? "none" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
        row.put("blockEntityInstance", be == null ? 0 : System.identityHashCode(be));
        row.put("container", be instanceof Container container ? inventory(container) : Map.of());
        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
            var coordinate = engine.regionCoordinateForDiagnostics(level, watched);
            row.put("regionX", coordinate.regionX()); row.put("regionZ", coordinate.regionZ());
            try {
                Field field = RegionThreadingEngine.class.getDeclaredField("regionizer"); field.setAccessible(true);
                Object regionizer = field.get(engine);
                Method get = regionizer.getClass().getDeclaredMethod("get", coordinate.getClass()); get.setAccessible(true);
                Object region = get.invoke(regionizer, coordinate);
                row.put("registeredOwner", region != null);
                if (region != null) {
                    Method method = region.getClass().getDeclaredMethod("coordinate"); method.setAccessible(true);
                    var actual = (net.neoforged.neoforge.server.threading.region.RegionCoordinate) method.invoke(region);
                    row.put("ownerRegionX", actual.regionX()); row.put("ownerRegionZ", actual.regionZ());
                }
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        Oracle.emit("BLOCK_STATE", null, JSON.toJson(row));
    }
}
