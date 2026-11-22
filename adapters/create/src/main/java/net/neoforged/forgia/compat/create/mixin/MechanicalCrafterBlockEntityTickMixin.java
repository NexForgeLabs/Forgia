/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Inside an admitted RESOURCE_SCOPED wave, the coordinator has already verified on the quiescent
 * server thread that {@code level.hasNeighborSignal(worldPosition) == this.wasPoweredBefore}
 * and no block writes may run concurrently. Return {@code this.wasPoweredBefore} directly inside
 * the worker scope so steady idle/waiting/countdown crafter ticks never query neighbor chunks.
 */
@Mixin(value = MechanicalCrafterBlockEntity.class, remap = false)
public abstract class MechanicalCrafterBlockEntityTickMixin extends KineticBlockEntity {
    @Shadow
    protected boolean wasPoweredBefore;

    protected MechanicalCrafterBlockEntityTickMixin(BlockEntityType<?> typeIn, BlockPos pos, BlockState state) {
        super(typeIn, pos, state);
    }

    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;hasNeighborSignal(Lnet/minecraft/core/BlockPos;)Z",
                    remap = true))
    private boolean forgia$steadyNeighborSignal(Level level, BlockPos pos) {
        if (BlockEntityResourceOwnership.Scope.active() && pos.equals(this.worldPosition)) {
            return this.wasPoweredBefore;
        }
        return level.hasNeighborSignal(pos);
    }
}
