package com.example.cleanprobe.mixin;

import com.example.cleanprobe.CleanModTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CleanModTarget.class)
public final class CleanModTargetMixin {
    @Inject(method = "value", at = @At("RETURN"), cancellable = true)
    private static void forgiaCleanProbe(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(cir.getReturnValue() + 1);
    }
}
