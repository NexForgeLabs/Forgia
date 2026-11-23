/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour.TransportedResult;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import dev.forgia.compat.createparallel.ConcurrentBeltList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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

    @WrapMethod(method = "tick")
    private synchronized void forgiaParallel$syncTick(Operation<Void> original) {
        original.call();
    }

    @WrapMethod(method = "addItem")
    private synchronized void forgiaParallel$syncAddItem(TransportedItemStack newStack, Operation<Void> original) {
        original.call(newStack);
    }

    @WrapMethod(method = "canInsertAt")
    private synchronized boolean forgiaParallel$syncCanInsertAt(int segment, Operation<Boolean> original) {
        return original.call(segment);
    }

    @WrapMethod(method = "canInsertAtFromSide")
    private synchronized boolean forgiaParallel$syncCanInsertAtFromSide(int segment, Direction side, Operation<Boolean> original) {
        return original.call(segment, side);
    }

    @WrapMethod(method = "getStackAtOffset")
    private synchronized TransportedItemStack forgiaParallel$syncGetStackAtOffset(int offset, Operation<TransportedItemStack> original) {
        return original.call(offset);
    }

    @WrapMethod(method = "applyToEachWithin")
    private synchronized void forgiaParallel$syncApplyToEachWithin(float min, float max,
            Function<TransportedItemStack, TransportedResult> processFunction, Operation<Void> original) {
        original.call(min, max, processFunction);
    }

    @WrapMethod(method = "eject")
    private synchronized void forgiaParallel$syncEject(TransportedItemStack stack, Operation<Void> original) {
        original.call(stack);
    }

    @WrapMethod(method = "ejectAll")
    private synchronized void forgiaParallel$syncEjectAll(Operation<Void> original) {
        original.call();
    }

    @WrapMethod(method = "read")
    private synchronized void forgiaParallel$syncRead(CompoundTag nbt, HolderLookup.Provider registries, Operation<Void> original) {
        original.call(nbt, registries);
    }

    @WrapMethod(method = "write")
    private synchronized CompoundTag forgiaParallel$syncWrite(HolderLookup.Provider registries, Operation<CompoundTag> original) {
        return original.call(registries);
    }

    @WrapMethod(method = "getTransportedItems")
    private synchronized List<TransportedItemStack> forgiaParallel$syncGetTransportedItems(Operation<List<TransportedItemStack>> original) {
        return original.call();
    }
}
