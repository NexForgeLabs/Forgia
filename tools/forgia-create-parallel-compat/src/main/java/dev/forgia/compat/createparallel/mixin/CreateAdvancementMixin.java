/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.foundation.advancement.CreateAdvancement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CreateAdvancement.class, remap = false)
public abstract class CreateAdvancementMixin {
    @Inject(method = "isAlreadyAwardedTo", at = @At("HEAD"), cancellable = true)
    private void forgia$deferCheckOnWorker(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (RegionThreadingHooks.isNativeRegionWorkerThread()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "awardTo", at = @At("HEAD"), cancellable = true)
    private void forgia$deferAwardToGlobal(Player player, CallbackInfo ci) {
        if (!RegionThreadingHooks.isNativeRegionWorkerThread()) {
            return;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            final CreateAdvancement self = (CreateAdvancement) (Object) this;
            RegionAdapterApi.postGlobal(serverPlayer.serverLevel(), () -> {
                if (!serverPlayer.isRemoved()) {
                    self.awardTo(serverPlayer);
                }
            });
        }
        ci.cancel();
    }
}
