/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerConnectionListener.class)
abstract class ConnectionsMixin {
    @WrapMethod(method = "tick")
    private void oracleConnections(Operation<Void> original) {
        Oracle.emit("CONNECTIONS_START", null, "ServerConnectionListener.tick");
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "CONNECTIONS_RETURN" : "CONNECTIONS_THROW", null, "ServerConnectionListener.tick"); }
    }
}
