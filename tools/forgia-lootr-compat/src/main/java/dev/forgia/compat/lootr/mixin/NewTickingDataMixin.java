/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.lootr.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import noobanidus.mods.lootr.common.api.data.NewTickingData;
import noobanidus.mods.lootr.common.api.data.TickingData;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = NewTickingData.class, remap = false)
public abstract class NewTickingDataMixin {
    private static final Object FORGIA_LOOTR_LOCK = new Object();

    @WrapMethod(method = "getCached")
    private static String forgiaLootr$syncGetCached(UUID id, Operation<String> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            return original.call(id);
        }
    }

    @WrapMethod(method = "getBaseFileName")
    private static String forgiaLootr$syncGetBaseFileName(UUID id, Operation<String> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            return original.call(id);
        }
    }

    @WrapMethod(method = "howLongUntilComplete")
    private long forgiaLootr$syncHowLongUntilComplete(MinecraftServer server, UUID id, Operation<Long> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            return original.call(server, id);
        }
    }

    @WrapMethod(method = "setCompletesIn")
    private void forgiaLootr$syncSetCompletesIn(MinecraftServer server, UUID id, long duration, Operation<Void> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            original.call(server, id, duration);
        }
    }

    @WrapMethod(method = "clearTicking")
    private void forgiaLootr$syncClearTicking(MinecraftServer server, UUID id, Operation<Void> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            original.call(server, id);
        }
    }

    @WrapMethod(method = "migrateOldData")
    private void forgiaLootr$syncMigrateOldData(MinecraftServer server, TickingData oldData, Operation<Void> original) {
        synchronized (FORGIA_LOOTR_LOCK) {
            original.call(server, oldData);
        }
    }
}
