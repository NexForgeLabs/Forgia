/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.cataclysm.mixin;

import com.github.L_Ender.cataclysm.world.data.CMWorldData;
import dev.forgia.compat.cataclysm.CMWorldDataCache;
import java.lang.invoke.VarHandle;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CMWorldData.class, remap = false)
public abstract class CMWorldDataMixin extends SavedData {
    private static final String FORGIA_IDENTIFIER = "cataclysm_world_data";

    @Inject(method = "get", at = @At("HEAD"), cancellable = true)
    private static void forgia$threadSafeGet(
            Level world, ResourceKey<Level> dim, CallbackInfoReturnable<CMWorldData> cir) {
        if (!(world instanceof ServerLevel serverLevel)) {
            cir.setReturnValue(null);
            return;
        }
        final MinecraftServer server = serverLevel.getServer();
        ServerLevel target = server.getLevel(dim);
        if (target == null) {
            target = server.overworld();
        }
        final CMWorldData cached = CMWorldDataCache.get(target);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        if (server.isSameThread()) {
            final CMWorldData loaded = target.getDataStorage().computeIfAbsent(CMWorldData.factory(), FORGIA_IDENTIFIER);
            if (loaded != null) {
                CMWorldDataCache.put(target, loaded);
            }
            cir.setReturnValue(loaded);
            return;
        }
        final ServerLevel preloadTarget = target;
        server.execute(() -> CMWorldData.get(preloadTarget, preloadTarget.dimension()));
        cir.setReturnValue(CMWorldDataCache.defaultReadOnly());
    }

    @Inject(method = "setLeviathanDefeatedOnce", at = @At("TAIL"))
    private void forgia$markLeviathanDirty(boolean defeatedOnce, CallbackInfo ci) {
        VarHandle.releaseFence();
        this.setDirty();
    }

    @Inject(method = "setIgnisDefeatedOnce", at = @At("TAIL"))
    private void forgia$markIgnisDirty(boolean defeatedOnce, CallbackInfo ci) {
        VarHandle.releaseFence();
        this.setDirty();
    }

    @Inject(method = "isLeviathanDefeatedOnce", at = @At("HEAD"))
    private void forgia$acquireLeviathanFence(CallbackInfoReturnable<Boolean> cir) {
        VarHandle.acquireFence();
    }

    @Inject(method = "isIgnisDefeatedOnce", at = @At("HEAD"))
    private void forgia$acquireIgnisFence(CallbackInfoReturnable<Boolean> cir) {
        VarHandle.acquireFence();
    }
}
