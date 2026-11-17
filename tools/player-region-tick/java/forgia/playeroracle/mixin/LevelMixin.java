/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package forgia.playeroracle.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import forgia.playeroracle.Oracle;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
abstract class LevelMixin {
    @WrapMethod(method = "tickBlockEntities")
    private void oracleBlockEntities(Operation<Void> original) {
        var level = (Level) (Object) this;
        if (level.isClientSide()) { original.call(); return; }
        Oracle.emit("BE_START", null, level.dimension().location().toString());
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { Oracle.emit(returned ? "BE_RETURN" : "BE_THROW", null, level.dimension().location().toString()); }
    }
}
