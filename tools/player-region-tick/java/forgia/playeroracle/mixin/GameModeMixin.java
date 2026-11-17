/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerPlayerGameMode.class)
abstract class GameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @WrapMethod(method = "destroyBlock")
    private boolean oracleDestroy(BlockPos pos, Operation<Boolean> original) {
        boolean result = original.call(pos);
        Oracle.emit("DESTROY_RESULT", player, pos.toShortString() + ",result=" + result);
        return result;
    }
    @WrapMethod(method = "useItemOn")
    private InteractionResult oracleUse(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        InteractionResult result = original.call(player, level, stack, hand, hit);
        Oracle.emit("USE_BLOCK_RESULT", player, hit.getBlockPos().toShortString() + ",result=" + result);
        return result;
    }
}
