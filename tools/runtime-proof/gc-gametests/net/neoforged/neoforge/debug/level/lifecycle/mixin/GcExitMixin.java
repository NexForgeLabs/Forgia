/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level.lifecycle.mixin;

import net.neoforged.neoforge.debug.level.lifecycle.RealLevelGcGameTest;

import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Preserve shutdown and its original exit status, allowing the server thread to physically return. */
@Mixin(GameTestServer.class)
public abstract class GcExitMixin {
    @Redirect(method = "onServerExit", at = @At(value = "INVOKE", target = "Ljava/lang/System;exit(I)V"))
    private void lifecycle$observeAfterActualShutdown(int originalExitCode) {
        RealLevelGcGameTest.observeAfterShutdown((GameTestServer) (Object) this, originalExitCode);
    }
}
