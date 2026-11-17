/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Player.class)
abstract class InteractionMixin {
    @WrapMethod(method = "interactOn")
    private InteractionResult oracleInteract(Entity target, InteractionHand hand, Operation<InteractionResult> original) {
        InteractionResult result = original.call(target, hand);
        if ((Object) this instanceof ServerPlayer player) {
            Oracle.emit("INTERACT_RESULT", player, "target=" + target.getId() + ",hand=" + hand + ",result=" + result);
        }
        return result;
    }
}
