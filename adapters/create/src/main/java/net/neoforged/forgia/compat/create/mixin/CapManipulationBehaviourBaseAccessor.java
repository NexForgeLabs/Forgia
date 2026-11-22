/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CapManipulationBehaviourBase.class, remap = false)
public interface CapManipulationBehaviourBaseAccessor {
    @Accessor("findNewNextTick")
    boolean forgia$findNewNextTick();
}
