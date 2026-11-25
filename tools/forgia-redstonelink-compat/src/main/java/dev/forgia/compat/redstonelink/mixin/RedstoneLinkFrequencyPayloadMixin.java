/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.redstonelink.mixin;

import com.ggrgg.createredstonelinkgui.common.SableHelper;
import com.ggrgg.createredstonelinkgui.common.network.RedstoneLinkFrequencyPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = RedstoneLinkFrequencyPayload.class, remap = false)
public abstract class RedstoneLinkFrequencyPayloadMixin {
    @Redirect(
            method = "lambda$handleServer$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;distanceToSqr(DDD)D"))
    private static double forgia$sableAwareDistanceToSqr(ServerPlayer player, double x, double y, double z) {
        return SableHelper.distanceSquared(player.level(), player.position(), new Vec3(x, y, z));
    }
}
