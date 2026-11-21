/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ItemVaultBlockEntity.class, remap = false)
public interface ItemVaultBlockEntityAccessor {
    @Accessor("lastKnownPos")
    BlockPos forgia$lastKnownPos();

    @Accessor("updateConnectivity")
    boolean forgia$updateConnectivity();
}
