/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.lootr.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "noobanidus.mods.lootr.common.api.data.NewTickingData$Section", remap = false)
public abstract class NewTickingDataSectionMixin {
    @WrapMethod(method = "completesAt")
    private synchronized long forgiaLootr$syncCompletesAt(UUID id, Operation<Long> original) {
        return original.call(id);
    }

    @WrapMethod(method = "setCompletesAt")
    private synchronized void forgiaLootr$syncSetCompletesAt(UUID id, long tick, Operation<Void> original) {
        original.call(id, tick);
    }

    @WrapMethod(method = "save(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;")
    private synchronized CompoundTag forgiaLootr$syncSave(CompoundTag tag, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return original.call(tag, registries);
    }
}
