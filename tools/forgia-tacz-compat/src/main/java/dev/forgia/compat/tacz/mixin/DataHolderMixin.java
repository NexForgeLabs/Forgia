/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.tacz.mixin;

import com.tacz.guns.entity.sync.core.DataEntry;
import com.tacz.guns.entity.sync.core.DataHolder;
import com.tacz.guns.entity.sync.core.SyncedDataKey;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DataHolder.class, remap = false)
public abstract class DataHolderMixin {
    @Shadow
    public Map<SyncedDataKey<?, ?>, DataEntry<?, ?>> dataMap;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void forgiaTacz$useConcurrentDataMap(CallbackInfo ci) {
        this.dataMap = new ConcurrentHashMap<>();
    }
}
