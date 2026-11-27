package com.forgia.villageropt.mixin;

import com.moepus.byepregen.palette.arena.codec.ByteStreamTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = ByteStreamTag.class, remap = false)
public abstract class MixinByePregenByteStreamTagOpt {

    /**
     * @author Forgia
     * @reason Disable expensive Thread.currentThread().getStackTrace() on every ByteStreamTag lazy materialization.
     */
    @Overwrite
    private static void warnMaterialize() {
        // No-op: capturing a 96-frame stack trace on every chunk section read causes 200-400ms main-thread spikes.
    }
}
