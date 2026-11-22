/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EncasedFanBlockEntity.class, remap = false)
public interface EncasedFanBlockEntityAccessor {
    @Accessor("airCurrentUpdateCooldown")
    int forgia$airCurrentUpdateCooldown();

    @Accessor("entitySearchCooldown")
    int forgia$entitySearchCooldown();

    @Accessor("updateAirFlow")
    boolean forgia$updateAirFlow();
}
