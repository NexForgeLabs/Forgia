/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.ApiStatus;

/** Inspects exact original storage; no inventory callback, copy/add simulation or gameplay effect. */
@ApiStatus.Internal
public final class RegionPlayerInventoryPlan {
    private record Stack(ItemStack original, int count, Object components) {
        Stack(ItemStack stack) {
            this(stack, stack.getCount(), stack.getComponents());
        }

        boolean current(ItemStack stack) {
            return stack == original && stack.getCount() == count && stack.getComponents() == components && supportedStack(stack);
        }
    }

    private final ServerPlayer player;
    private final int selected;
    private final List<Stack> slots;
    private final Map<ItemEntity, Stack> pickups;

    private RegionPlayerInventoryPlan(ServerPlayer player, Map<ItemEntity, Stack> pickups) {
        this.player = player;
        this.selected = player.getInventory().selected;
        this.slots = new ArrayList<>();
        for (int i = 0; i < 41; i++) this.slots.add(new Stack(player.getInventory().getItem(i)));
        this.pickups = java.util.Collections.unmodifiableMap(new IdentityHashMap<>(pickups));
    }

    public static boolean supportedStack(ItemStack stack) {
        if (stack == null) return false;
        if (stack.isEmpty()) return true;
        if (RegionPlayerMapPlan.supportedStack(stack)) return true;
        Item item = stack.getItem();
        return item.getClass() == Item.class && (item == Items.DIAMOND || item == Items.IRON_INGOT || item == Items.GOLD_INGOT)
                && stack.getCount() > 0 && stack.getCount() <= 64 && stack.getComponentsPatch().isEmpty() && !stack.isFramed();
    }

    public static boolean supportedInventory(ServerPlayer player) {
        var inventory = player.getInventory();
        if (inventory.getContainerSize() != 41 || inventory.selected < 0 || inventory.selected >= 9) return false;
        for (int i = 0; i < 41; i++) {
            ItemStack stack = inventory.getItem(i);
            if ((i == inventory.selected || i >= 36) ? !stack.isEmpty() : !supportedStack(stack)) return false;
        }
        return true;
    }

    static RegionPlayerInventoryPlan capture(RegionPlayerSpatialPlan spatial) {
        ServerPlayer player = spatial.player();
        if (!supportedInventory(player)) return null;
        Map<ItemEntity, Stack> pickups = new IdentityHashMap<>();
        for (var target : spatial.members().keySet()) {
            if (target instanceof ItemEntity item && target.getBoundingBox().intersects(spatial.footprint().queryBounds())) {
                if (!supportedStack(item.getItem()) || item.getItem().isEmpty() || item.getItem().is(Items.FILLED_MAP)) return null;
                pickups.put(item, new Stack(item.getItem()));
            }
        }
        var result = new RegionPlayerInventoryPlan(player, pickups);
        return result.capacityClosed() ? result : null;
    }

    private boolean capacityClosed() {
        // Only existing non-equipped stacks may receive the pickup. The sum is independent of
        // original query order, and prevents Inventory.add from selecting any empty equipment slot.
        Map<Item, Integer> room = new IdentityHashMap<>();
        var inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && i != inventory.selected) room.merge(stack.getItem(), 64 - stack.getCount(), Integer::sum);
        }
        for (var entry : pickups.entrySet()) {
            ItemStack stack = entry.getKey().getItem();
            int available = room.getOrDefault(stack.getItem(), 0) - stack.getCount();
            if (available < 0) return false;
            room.put(stack.getItem(), available);
        }
        return true;
    }

    boolean current(RegionPlayerSpatialPlan spatial) {
        var inventory = player.getInventory();
        if (!supportedInventory(player) || inventory.selected != selected) return false;
        for (int i = 0; i < slots.size(); i++) if (!slots.get(i).current(inventory.getItem(i))) return false;
        int found = 0;
        for (var entity : spatial.members().keySet()) {
            if (entity instanceof ItemEntity item && entity.getBoundingBox().intersects(spatial.footprint().queryBounds())) {
                Stack stack = pickups.get(item);
                if (stack == null || !stack.current(item.getItem())) return false;
                found++;
            }
        }
        return found == pickups.size() && capacityClosed();
    }

    boolean captures(ItemEntity item) {
        return pickups.containsKey(item);
    }

    List<Object> resources() {
        List<Object> objects = new ArrayList<>(player.getInventory().forgiaPlayerLocalObjects());
        for (Stack stack : slots) add(objects, stack.original());
        for (Stack stack : pickups.values()) add(objects, stack.original());
        return objects;
    }

    private static void add(List<Object> objects, ItemStack stack) {
        if (!stack.isEmpty()) {
            objects.add(stack);
            objects.add(stack.getComponents());
        }
    }
}
