/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(PlayerList.class)
abstract class PlayerListMixin {
    @WrapMethod(method = "tick")
    private void oraclePlayerList(Operation<Void> original) {
        Oracle.emit("PLAYERLIST_START", null, "PlayerList.tick");
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "PLAYERLIST_RETURN" : "PLAYERLIST_THROW", null, "PlayerList.tick"); }
    }
}
