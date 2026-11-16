/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;

/** Separate immutable original-function token; C6's ticker token and task state are untouched. */
public final class C7FurnaceProgram {
    private static final BlockEntityTicker<AbstractFurnaceBlockEntity> TICKER = AbstractFurnaceBlockEntity::serverTick;

    private C7FurnaceProgram() {}

    public static BlockEntityTicker<AbstractFurnaceBlockEntity> ticker() {
        return TICKER;
    }
}
