/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.tacz.mixin;

import com.tacz.guns.entity.sync.core.DataEntry;
import com.tacz.guns.entity.sync.core.DataHolder;
import com.tacz.guns.entity.sync.core.SyncedEntityData;
import com.tacz.guns.event.SyncedEntityDataEvent;
import com.tacz.guns.network.message.ServerMessageUpdateEntityData;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SyncedEntityDataEvent.class, remap = false)
public final class SyncedEntityDataEventMixin {
    @Inject(method = "onServerTick", at = @At("HEAD"), cancellable = true)
    private static void forgiaTacz$synchronizedOnServerTick(ServerTickEvent.Post event, CallbackInfo ci) {
        final SyncedEntityData instance = SyncedEntityData.instance();
        final List<Entity> snapshot;
        synchronized (instance) {
            if (!instance.isDirty()) {
                ci.cancel();
                return;
            }
            final List<Entity> dirtyEntities = instance.getDirtyEntities();
            if (dirtyEntities.isEmpty()) {
                instance.setDirty(false);
                ci.cancel();
                return;
            }
            snapshot = new ArrayList<>(dirtyEntities);
            dirtyEntities.clear();
            instance.setDirty(false);
        }

        for (final Entity entity : snapshot) {
            final DataHolder holder = instance.getDataHolder(entity);
            if (holder == null || !holder.isDirty()) {
                continue;
            }
            final List<DataEntry<?, ?>> entries = holder.gatherDirty();
            if (entries.isEmpty()) {
                continue;
            }
            final List<DataEntry<?, ?>> selfEntries = entries.stream()
                    .filter(entry -> entry.getKey().syncMode().isSelf())
                    .collect(Collectors.toList());
            if (!selfEntries.isEmpty() && entity instanceof ServerPlayer serverPlayer) {
                PacketDistributor.sendToPlayer(serverPlayer, new ServerMessageUpdateEntityData(entity.getId(), selfEntries));
            }
            final List<DataEntry<?, ?>> trackingEntries = entries.stream()
                    .filter(entry -> entry.getKey().syncMode().isTracking())
                    .collect(Collectors.toList());
            if (!trackingEntries.isEmpty()) {
                PacketDistributor.sendToPlayersTrackingEntity(entity, new ServerMessageUpdateEntityData(entity.getId(), trackingEntries));
            }
            holder.clean();
        }
        ci.cancel();
    }
}
