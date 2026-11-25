/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "networksIn", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void forgia$networksInLazyConcurrent(LevelAccessor world, CallbackInfoReturnable<Map> cir) {
        if (connections == null || world == null) {
            return;
        }
        synchronized (connections) {
            Map map = (Map) connections.get(world);
            if (map == null) {
                map = new ConcurrentHashMap<>();
                connections.put(world, map);
            }
            cir.setReturnValue(map);
        }
    }

    private static volatile java.lang.reflect.Method forgia$getNetworkKeyMethod;

    @Inject(method = "getNetworkOf", at = @At("HEAD"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void forgia$getNetworkOfConcurrent(
            LevelAccessor world, IRedstoneLinkable actor, CallbackInfoReturnable<Set<IRedstoneLinkable>> cir) {
        if (connections == null || world == null || actor == null) {
            return;
        }
        Map networks;
        synchronized (connections) {
            networks = (Map) connections.get(world);
            if (networks == null) {
                networks = new ConcurrentHashMap<>();
                connections.put(world, networks);
            }
        }
        try {
            java.lang.reflect.Method m = forgia$getNetworkKeyMethod;
            if (m == null) {
                m = IRedstoneLinkable.class.getMethod("getNetworkKey");
                forgia$getNetworkKeyMethod = m;
            }
            Object key = m.invoke(actor);
            Set<IRedstoneLinkable> set = (Set<IRedstoneLinkable>) networks.computeIfAbsent(
                    key, k -> ConcurrentHashMap.newKeySet());
            cir.setReturnValue(set);
        } catch (ReflectiveOperationException ignored) {
            // Fall through to vanilla Create implementation on unexpected signature mismatch.
        }
    }
}
