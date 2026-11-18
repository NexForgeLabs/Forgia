/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;

/** Real original map inventory/carrier/update state; no terrain reach is admitted for a backpack map. */
final class RegionPlayerMapPlan {
    private final ServerPlayer player;
    @Nullable
    private final MapId id;
    @Nullable
    private final MapItemSavedData data;
    private final List<Object> resources;

    private RegionPlayerMapPlan(ServerPlayer player, @Nullable MapId id, @Nullable MapItemSavedData data) {
        this.player = player;
        this.id = id;
        this.data = data;
        this.resources = data == null ? List.of() : List.copyOf(data.forgiaPlayerSharedObjects());
    }

    static boolean supportedStack(ItemStack stack) {
        var id = stack.get(DataComponents.MAP_ID);
        return stack.getItem() == Items.FILLED_MAP && stack.getItem().getClass() == MapItem.class && stack.getCount() == 1
                && id != null && id.id() >= 0 && !stack.isFramed() && stack.getComponentsPatch().entrySet().size() == 1
                && stack.getComponentsPatch().entrySet().stream().allMatch(entry -> entry.getKey() == DataComponents.MAP_ID && entry.getValue().isPresent());
    }

    @Nullable
    static RegionPlayerMapPlan capture(ServerPlayer player) {
        MapId id = null;
        MapItemSavedData data = null;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.FILLED_MAP)) {
                if (id != null || slot == inventory.selected || slot >= 36 || !supportedStack(stack)) return null;
                id = stack.get(DataComponents.MAP_ID);
                data = player.serverLevel().getMapData(id);
                if (data == null || !data.forgiaHasLocalPlayerCarrier(player)) return null;
            }
        }
        return new RegionPlayerMapPlan(player, id, data);
    }

    boolean current() {
        int found = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(Items.FILLED_MAP)) {
                if (data == null || slot == inventory.selected || slot >= 36 || !supportedStack(stack)
                        || !id.equals(stack.get(DataComponents.MAP_ID)) || ++found > 1)
                    return false;
            }
        }
        if (data == null) return found == 0;
        return found == 1 && data.forgiaHasLocalPlayerCarrier(player) && data.colors == resources.get(1)
                && (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread() || player.serverLevel().getMapData(id) == data);
    }

    MapItemSavedData require(ServerLevel level, MapId id) {
        if (level != player.serverLevel() || data == null || !this.id.equals(id))
            throw new IllegalStateException("Uncaptured original player map binding");
        return data;
    }

    void checkCarrier(MapItemSavedData data, net.minecraft.world.entity.player.Player player) {
        if (this.data != data || this.player != player) throw new IllegalStateException("Foreign original map carrier/data");
    }

    List<Object> sharedResources() {
        return resources;
    }
}
