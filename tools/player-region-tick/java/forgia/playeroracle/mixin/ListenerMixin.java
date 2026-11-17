/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class ListenerMixin {
    @WrapMethod(method = "tick")
    private void oracleListener(Operation<Void> original) {
        var player = ((ServerGamePacketListenerImpl) (Object) this).player;
        Oracle.emit("LISTENER_START", player, "ServerGamePacketListenerImpl.tick");
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "LISTENER_RETURN" : "LISTENER_THROW", player, "ServerGamePacketListenerImpl.tick"); }
    }

    private void packet(String kind, Object packet, Runnable original) {
        var player = ((ServerGamePacketListenerImpl) (Object) this).player;
        // The first Netty invocation enqueues and throws RunningOnDifferentThreadException.
        // Read live game state only in the original server-thread invocation.
        if (!player.server.isSameThread()) { original.run(); return; }
        Oracle.emit("PACKET_START", player, kind + ":" + System.identityHashCode(packet));
        boolean returned = false;
        try { original.run(); returned = true; }
        finally { Oracle.emit(returned ? "PACKET_RETURN" : "PACKET_THROW", player, kind + ":" + System.identityHashCode(packet)); }
    }

    @WrapMethod(method = "handleInteract")
    private void interact(ServerboundInteractPacket packet, Operation<Void> original) { packet("interact", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handleMovePlayer")
    private void move(ServerboundMovePlayerPacket packet, Operation<Void> original) { packet("move", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handlePlayerAction")
    private void action(ServerboundPlayerActionPacket packet, Operation<Void> original) { packet("action", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handleContainerClick")
    private void click(net.minecraft.network.protocol.game.ServerboundContainerClickPacket packet, Operation<Void> original) { packet("containerClick", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handleContainerClose")
    private void close(net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet, Operation<Void> original) { packet("containerClose", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handleClientCommand")
    private void command(net.minecraft.network.protocol.game.ServerboundClientCommandPacket packet, Operation<Void> original) { packet("clientCommand", packet, () -> original.call(packet)); }
    @WrapMethod(method = "handleUseItemOn")
    private void use(ServerboundUseItemOnPacket packet, Operation<Void> original) { packet("useItemOn", packet, () -> original.call(packet)); }
}
