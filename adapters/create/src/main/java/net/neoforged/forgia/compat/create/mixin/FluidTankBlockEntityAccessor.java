/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FluidTankBlockEntity.class, remap = false)
public interface FluidTankBlockEntityAccessor {
    @Accessor("lastKnownPos")
    BlockPos forgia$lastKnownPos();

    @Accessor("updateConnectivity")
    boolean forgia$updateConnectivity();

    @Accessor("updateCapability")
    boolean forgia$updateCapability();

    @Accessor("syncCooldown")
    int forgia$syncCooldown();

    @Accessor("fluidLevel")
    LerpedFloat forgia$fluidLevel();
}
