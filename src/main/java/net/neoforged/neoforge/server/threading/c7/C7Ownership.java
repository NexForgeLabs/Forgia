/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/** Coordinator-only outer Region lease. Detached workers NEVER receive a region ownership context. */
@FunctionalInterface
public interface C7Ownership {
    void run(BlockPos position, Runnable action);

    default <T> T call(BlockPos position, Supplier<T> action) {
        final Object[] value = new Object[1];
        this.run(position, () -> value[0] = action.get());
        return (T) value[0];
    }
}
