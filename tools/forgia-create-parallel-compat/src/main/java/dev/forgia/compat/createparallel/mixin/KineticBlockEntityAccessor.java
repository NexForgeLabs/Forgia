/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel.mixin;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = KineticBlockEntity.class, remap = false)
public interface KineticBlockEntityAccessor {
    @Accessor("validationCountdown")
    int forgiaParallel$validationCountdown();

    @Accessor("updateSpeed")
    boolean forgiaParallel$updateSpeed();

    @Accessor("speed")
    float forgiaParallel$speed();

    @Accessor("source")
    net.minecraft.core.BlockPos forgiaParallel$source();
}
