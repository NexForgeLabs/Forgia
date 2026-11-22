/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BeltInventory.class, remap = false)
public interface BeltInventoryAccessor {
    @Accessor("items")
    List<TransportedItemStack> forgia$items();

    @Accessor("toInsert")
    List<TransportedItemStack> forgia$toInsert();

    @Accessor("toRemove")
    List<TransportedItemStack> forgia$toRemove();

    @Accessor("lazyClientItem")
    TransportedItemStack forgia$lazyClientItem();

    @Accessor("beltMovementPositive")
    boolean forgia$beltMovementPositive();
}
