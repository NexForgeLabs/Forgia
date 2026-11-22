/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import net.createmod.catnip.data.Couple;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PumpBlockEntity.class, remap = false)
public interface PumpBlockEntityAccessor {
    @Accessor("sidesToUpdate")
    Couple<MutableBoolean> forgia$sidesToUpdate();

    @Accessor("scheduleFlip")
    boolean forgia$scheduleFlip();
}
