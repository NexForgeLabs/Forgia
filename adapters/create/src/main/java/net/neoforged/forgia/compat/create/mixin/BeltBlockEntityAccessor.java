/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BeltBlockEntity.class, remap = false)
public interface BeltBlockEntityAccessor {
    @Accessor("beltLength")
    int forgia$beltLength();

    @Accessor("itemHandler")
    IItemHandler forgia$itemHandler();

    @Accessor("inventory")
    BeltInventory forgia$inventory();
}
