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
        private static final Stack EMPTY = new Stack(ItemStack.EMPTY);

        static Stack capture(ItemStack stack) {
            return stack == ItemStack.EMPTY ? EMPTY : new Stack(stack);
        }

        Stack(ItemStack stack) {
            this(stack, stack.getCount(), stack.getComponents());
        }

        boolean current(ItemStack stack, boolean mustBeEmpty) {
            if (stack != original) return false;
            if (this == EMPTY) return true;
            return stack.getCount() == count && stack.getComponents() == components && (mustBeEmpty ? stack.isEmpty() : supportedStack(stack));
        }
    }

    private final ServerPlayer player;
    private final int selected;
    private final Stack[] slots;
    private final Map<ItemEntity, Stack> pickups;

    private RegionPlayerInventoryPlan(ServerPlayer player, int selected, Stack[] slots, Map<ItemEntity, Stack> pickups) {
        this.player = player;
        this.selected = selected;
        this.slots = slots;
        this.pickups = pickups.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(pickups);
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
        var inventory = player.getInventory();
        int selected = inventory.selected;
        if (inventory.getContainerSize() != 41 || selected < 0 || selected >= 9) return null;
        Stack[] slots = new Stack[41];
        for (int i = 0; i < 41; i++) {
            ItemStack stack = inventory.getItem(i);
            if ((i == selected || i >= 36) ? !stack.isEmpty() : !supportedStack(stack)) return null;
            slots[i] = Stack.capture(stack);
        }
        Map<ItemEntity, Stack> pickups = new IdentityHashMap<>();
        for (var target : spatial.members().keySet()) {
            if (target instanceof ItemEntity item && target.getBoundingBox().intersects(spatial.footprint().queryBounds())) {
                if (!supportedStack(item.getItem()) || item.getItem().isEmpty() || item.getItem().is(Items.FILLED_MAP)) return null;
                pickups.put(item, new Stack(item.getItem()));
            }
        }
        var result = new RegionPlayerInventoryPlan(player, selected, slots, pickups);
        return result.capacityClosed() ? result : null;
    }

    private boolean capacityClosed() {
        if (pickups.isEmpty()) return true;
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
        return this.current(spatial, false);
    }

    boolean current(RegionPlayerSpatialPlan spatial, boolean rebindLocalTemplate) {
        var inventory = player.getInventory();
        int currentSlot = inventory.selected;
        Stack[] captured = this.slots;
        if (inventory.getContainerSize() != captured.length || currentSlot < 0 || currentSlot >= 9
                || (currentSlot != selected && (!rebindLocalTemplate || !this.pickups.isEmpty()))) return false;
        for (int i = 0; i < captured.length; i++) {
            if (!captured[i].current(inventory.getItem(i), i == currentSlot || i >= 36)) return false;
        }
        if (this.pickups.isEmpty() && spatial.members().size() == 1) {
            return true;
        }
        int found = 0;
        for (var entity : spatial.members().keySet()) {
            if (entity instanceof ItemEntity item && entity.getBoundingBox().intersects(spatial.footprint().queryBounds())) {
                Stack stack = pickups.get(item);
                if (stack == null || !stack.current(item.getItem(), false)) return false;
                found++;
            }
        }
        return found == pickups.size() && capacityClosed();
    }

    boolean captures(ItemEntity item) {
        return pickups.containsKey(item);
    }

    boolean owns(Object resource) {
        for (Stack stack : slots) if (ownsStack(stack.original(), resource)) return true;
        for (Stack stack : pickups.values()) if (ownsStack(stack.original(), resource)) return true;
        for (Object object : player.getInventory().forgiaPlayerLocalObjects()) if (object == resource) return true;
        return false;
    }

    private static boolean ownsStack(ItemStack stack, Object resource) {
        return !stack.isEmpty() && (stack == resource || stack.getComponents() == resource);
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
