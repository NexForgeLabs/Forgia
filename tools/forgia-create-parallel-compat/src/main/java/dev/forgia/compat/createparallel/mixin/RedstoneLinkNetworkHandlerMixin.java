/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RedstoneLinkNetworkHandler.class, remap = false)
public abstract class RedstoneLinkNetworkHandlerMixin {
    @Shadow
    @SuppressWarnings("rawtypes")
    static Map connections;

    @Inject(method = "onLoadWorld", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings("unchecked")
    private void forgia$onLoadWorldConcurrent(LevelAccessor world, CallbackInfo ci) {
        if (connections != null) {
            synchronized (connections) {
                connections.put(world, new ConcurrentHashMap<>());
            }
        }
        ci.cancel();
    }
}
