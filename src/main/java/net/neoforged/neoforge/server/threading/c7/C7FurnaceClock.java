/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

/** Immutable, detached empty-input furnace state. Never contains a world/BE/item/capability reference. */
public record C7FurnaceClock(int litTime, int cookingProgress, int cookingTotalTime) {
    public C7FurnaceClock advance() {
        if (this.litTime == 1) throw new IllegalStateException("Extinguishing is not a detached operation");
        final int progress = this.litTime > 1 ? 0
                : this.cookingProgress > 0
                        ? net.minecraft.util.Mth.clamp(this.cookingProgress - 2, 0, this.cookingTotalTime)
                        : this.cookingProgress;
        return new C7FurnaceClock(this.litTime > 1 ? this.litTime - 1 : this.litTime, progress, this.cookingTotalTime);
    }
}
