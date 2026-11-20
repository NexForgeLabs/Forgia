/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.debug.mixin;

import forgia.neotests.mixin.MixinTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MixinTargets.InterfaceTarget.class)
public interface InterfaceTestMixin {
    @Inject(at = @At("HEAD"), method = "getNumber", cancellable = true)
    default void numberProvider(String argument, CallbackInfoReturnable<Integer> cir) {
        if (argument.equals(MixinTargets.InterfaceTarget.MAXINT)) {
            cir.setReturnValue(Integer.MAX_VALUE);
        }
    }
}
