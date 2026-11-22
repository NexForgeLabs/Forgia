/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.cataclysm;

import com.github.L_Ender.cataclysm.world.data.CMWorldData;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod("forgia_cataclysm_compat")
public final class ForgiaCataclysmCompat {
    public ForgiaCataclysmCompat() {
        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
    }

    private void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()) {
            CMWorldData.get(level, level.dimension());
        }
    }

    private void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CMWorldDataCache.remove(level);
        }
    }

    private void onServerStopped(ServerStoppedEvent event) {
        CMWorldDataCache.clear();
    }
}
