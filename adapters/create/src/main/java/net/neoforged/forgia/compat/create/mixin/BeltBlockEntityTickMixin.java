/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Inside an admitted RESOURCE_SCOPED wave, the coordinator has already proven that
 * {@code chunk.getBlockState(worldPosition) == this.getBlockState()} and no block writes
 * may execute concurrently. Avoid re-entering {@link Level#getBlockState(BlockPos)} for
 * this block entity's own position so steady belt ticks remain strictly resource-scoped.
 */
@Mixin(value = BeltBlockEntity.class, remap = false)
public abstract class BeltBlockEntityTickMixin extends KineticBlockEntity {
    protected BeltBlockEntityTickMixin(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    remap = true))
    private BlockState forgia$steadyOwnBlockState(Level level, BlockPos pos) {
        if (BlockEntityResourceOwnership.Scope.active() && pos.equals(this.worldPosition)) {
            return this.getBlockState();
        }
        return level.getBlockState(pos);
    }
}
