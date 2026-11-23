/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import dev.forgia.compat.createparallel.ConcurrentBeltList;
import java.util.List;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BeltInventory.class, remap = false)
public abstract class BeltInventoryMixin {
    @Shadow
    @Final
    @Mutable
    private List<TransportedItemStack> items;

    @Shadow
    @Final
    @Mutable
    List<TransportedItemStack> toInsert;

    @Shadow
    @Final
    @Mutable
    List<TransportedItemStack> toRemove;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void forgiaParallel$initConcurrentLists(BeltBlockEntity belt, CallbackInfo ci) {
        this.items = new ConcurrentBeltList<>();
        this.toInsert = new ConcurrentBeltList<>();
        this.toRemove = new ConcurrentBeltList<>();
    }
}
