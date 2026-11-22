/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = MechanicalCrafterBlockEntity.class, remap = false)
public interface MechanicalCrafterBlockEntityAccessor {
    @Accessor("countDown")
    int forgia$countDown();

    @Accessor("wasPoweredBefore")
    boolean forgia$wasPoweredBefore();
}
