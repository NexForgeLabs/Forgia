/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.TorquePropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = TorquePropagator.class, remap = false)
public abstract class TorquePropagatorMixin {
    @Shadow
    static Map<LevelAccessor, Map<Long, KineticNetwork>> networks;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void forgia$concurrentOuterNetworks(CallbackInfo ci) {
        networks = new ConcurrentHashMap<>();
    }

    @Inject(method = "onLoadWorld", at = @At("HEAD"), cancellable = true)
    private void forgia$onLoadWorldConcurrent(LevelAccessor world, CallbackInfo ci) {
        networks.put(world, new ConcurrentHashMap<>());
        ci.cancel();
    }

    @Inject(method = "getOrCreateNetworkFor", at = @At("HEAD"), cancellable = true)
    private void forgia$getOrCreateNetworkForConcurrent(KineticBlockEntity be, CallbackInfoReturnable<KineticNetwork> cir) {
        final Long id = be.network;
        final net.minecraft.world.level.Level level = ((net.minecraft.world.level.block.entity.BlockEntity) (Object) be).getLevel();
        final Map<Long, KineticNetwork> map = networks.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        if (id == null) {
            cir.setReturnValue(null);
            return;
        }
        final KineticNetwork network = map.computeIfAbsent(id, key -> {
            final KineticNetwork created = new KineticNetwork();
            created.id = key;
            return created;
        });
        cir.setReturnValue(network);
    }
}
