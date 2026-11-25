/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.tacz.mixin;

import com.tacz.guns.entity.sync.core.SyncedEntityData;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = SyncedEntityData.class, remap = false)
public abstract class SyncedEntityDataMixin {
    @Shadow
    private boolean dirty;

    @Redirect(
            method = "set",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z")
    )
    private boolean forgiaTacz$synchronizedAddDirtyEntity(List<Entity> dirtyEntities, Object entity) {
        synchronized (this) {
            this.dirty = true;
            return dirtyEntities.add((Entity) entity);
        }
    }
}
