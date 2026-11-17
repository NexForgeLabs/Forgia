/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @WrapMethod(method = "tick")
    private void oracleConnection(Operation<Void> original) {
        var connection = (Connection) (Object) this;
        var player = connection.getPacketListener() instanceof ServerGamePacketListenerImpl listener ? listener.player : null;
        Oracle.emit("CONNECTION_START", player, "Connection.tick:" + System.identityHashCode(connection));
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "CONNECTION_RETURN" : "CONNECTION_THROW", player, "Connection.tick:" + System.identityHashCode(connection)); }
    }
}
