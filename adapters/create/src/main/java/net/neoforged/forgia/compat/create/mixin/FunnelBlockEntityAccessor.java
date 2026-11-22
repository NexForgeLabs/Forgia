/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FunnelBlockEntity.class, remap = false)
public interface FunnelBlockEntityAccessor {
    @Accessor("extractionCooldown")
    int forgia$extractionCooldown();

    @Accessor("flap")
    LerpedFloat forgia$flap();
}
