/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Exact single-chest tick/read/synchronization contract; original clicks remain at their packet fence. */
@ApiStatus.Internal
public final class RegionPlayerContainerPlan {
    private final ServerPlayer player;
    @Nullable
    private final ChestMenu menu;
    @Nullable
    private final ChestBlockEntity chest;
    private final List<Object> local;
    private final List<Object> shared;

    private RegionPlayerContainerPlan(ServerPlayer player, @Nullable ChestMenu menu, @Nullable ChestBlockEntity chest) {
        this.player = player;
        this.menu = menu;
        this.chest = chest;
        this.local = menu == null ? List.of() : List.copyOf(menu.forgiaPlayerLocalMenuObjects());
        this.shared = chest == null ? List.of() : List.copyOf(chest.forgiaPlayerSharedObjects());
    }

    @Nullable
    public static ChestBlockEntity exactChest(ServerPlayer player) {
        if (player.containerMenu == null || player.containerMenu.getClass() != ChestMenu.class) return null;
        ChestMenu menu = (ChestMenu) player.containerMenu;
        if (menu.getRowCount() != 3 || menu.getContainer().getClass() != ChestBlockEntity.class) return null;
        ChestBlockEntity chest = (ChestBlockEntity) menu.getContainer();
        if (chest.getType() != BlockEntityType.CHEST || chest.getLevel() != player.serverLevel() || chest.isRemoved() || chest.hasAttachments()) return null;
        var state = chest.getBlockState();
        if (!state.is(Blocks.CHEST) || state.getValue(ChestBlock.TYPE) != ChestType.SINGLE || state.getValue(ChestBlock.WATERLOGGED)) return null;
        BlockPos pos = chest.getBlockPos();
        if (!player.serverLevel().hasChunkAt(pos) || !player.canInteractWithBlock(pos, 4.0)
                || new AABB(pos).intersects(player.getBoundingBox().inflate(.01)))
            return null;
        return chest;
    }

    public static boolean supportedMenu(ServerPlayer player) {
        if (player.containerMenu == player.inventoryMenu) return true;
        ChestBlockEntity chest = exactChest(player);
        if (chest == null) return false;
        ChestMenu menu = (ChestMenu) player.containerMenu;
        if (menu.slots.size() != 63 || !menu.getCarried().isEmpty()) return false;
        for (int i = 0; i < 63; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.getClass() != Slot.class || slot.container != (i < 27 ? chest : player.getInventory())
                    || slot.getContainerSlot() != (i < 27 ? i : i < 54 ? i - 18 : i - 54))
                return false;
        }
        return chest.forgiaHasPlainPlayerInventory();
    }

    public static AABB blockBounds(ServerPlayer player, AABB original) {
        ChestBlockEntity chest = exactChest(player);
        return chest == null ? original : original.minmax(new AABB(chest.getBlockPos()));
    }

    public static boolean knownChestAt(ServerPlayer player, BlockPos pos) {
        ChestBlockEntity chest = exactChest(player);
        return chest != null && chest.getBlockPos().equals(pos)
                && player.serverLevel().getBlockState(pos) == chest.getBlockState()
                && player.serverLevel().getBlockEntity(pos) == chest;
    }

    @Nullable
    static RegionPlayerContainerPlan capture(ServerPlayer player) {
        if (!supportedMenu(player)) return null;
        ChestBlockEntity chest = exactChest(player);
        return new RegionPlayerContainerPlan(player, chest == null ? null : (ChestMenu) player.containerMenu, chest);
    }

    boolean current() {
        if (menu == null) return player.containerMenu == player.inventoryMenu;
        return player.containerMenu == menu && exactChest(player) == chest && supportedMenu(player)
                && knownChestAt(player, chest.getBlockPos())
                && same(local, menu.forgiaPlayerLocalMenuObjects()) && same(shared, chest.forgiaPlayerSharedObjects());
    }

    private static boolean same(List<Object> before, List<Object> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    List<Object> localResources() {
        if (menu == null) return List.of();
        List<Object> result = new ArrayList<>(local);
        result.add(menu);
        return result;
    }

    List<Object> sharedResources() {
        return shared;
    }
}
