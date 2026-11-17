/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayer.class)
abstract class PlayerMixin {
    @WrapMethod(method = "tick")
    private void oracleEntity(Operation<Void> original) {
        var player = (ServerPlayer) (Object) this;
        Oracle.emit("ENTITY_START", player, "ServerPlayer.tick");
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "ENTITY_RETURN" : "ENTITY_THROW", player, "ServerPlayer.tick"); }
    }

    @WrapMethod(method = "doTick")
    private void oracleDoTick(Operation<Void> original) {
        var player = (ServerPlayer) (Object) this;
        Oracle.emit("DOTICK_START", player, "ServerPlayer.doTick");
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "DOTICK_RETURN" : "DOTICK_THROW", player, "ServerPlayer.doTick"); }
    }
}
