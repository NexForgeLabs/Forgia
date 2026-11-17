/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import forgia.playeroracle.ResourceOracle;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ChestMenu.class)
abstract class ChestMenuMixin {
    @WrapMethod(method = "quickMoveStack")
    private ItemStack oracleMove(Player player, int slot, Operation<ItemStack> original) {
        ItemStack result = original.call(player, slot);
        Oracle.emit("QUICK_MOVE_RESULT", player, "slot=" + slot + ",item=" + ResourceOracle.item(result) + ",count=" + result.getCount());
        return result;
    }
}
