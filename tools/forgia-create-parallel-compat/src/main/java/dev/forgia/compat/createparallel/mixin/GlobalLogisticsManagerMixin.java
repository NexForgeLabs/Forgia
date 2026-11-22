/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.logistics.packagerLink.GlobalLogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.LogisticsNetwork;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlobalLogisticsManager.class, remap = false)
public abstract class GlobalLogisticsManagerMixin {
    @Shadow
    public Map<UUID, LogisticsNetwork> logisticsNetworks;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void forgia$concurrentLogisticsInit(CallbackInfo ci) {
        this.logisticsNetworks = new ConcurrentHashMap<>();
    }

    @Inject(method = "levelLoaded", at = @At("TAIL"))
    private void forgia$concurrentLogisticsAfterLoad(LevelAccessor world, CallbackInfo ci) {
        if (this.logisticsNetworks != null && !(this.logisticsNetworks instanceof ConcurrentHashMap)) {
            this.logisticsNetworks = new ConcurrentHashMap<>(this.logisticsNetworks);
        }
    }
}
