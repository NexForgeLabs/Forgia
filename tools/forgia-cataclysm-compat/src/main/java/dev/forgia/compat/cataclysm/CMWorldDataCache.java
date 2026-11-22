/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.cataclysm;

import com.github.L_Ender.cataclysm.world.data.CMWorldData;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Thread-safe per-ServerLevel cache for {@link CMWorldData} so parallel region workers
 * (such as NaturalSpawner spawn checks) never enter {@code DimensionDataStorage.computeIfAbsent}.
 */
public final class CMWorldDataCache {
    private static final ConcurrentHashMap<ServerLevel, CMWorldData> BY_LEVEL = new ConcurrentHashMap<>();
    private static volatile CMWorldData defaultReadOnly;

    private CMWorldDataCache() {}

    public static CMWorldData get(ServerLevel level) {
        return BY_LEVEL.get(level);
    }

    public static void put(ServerLevel level, CMWorldData data) {
        if (level != null && data != null) {
            BY_LEVEL.put(level, data);
        }
    }

    public static void remove(ServerLevel level) {
        if (level != null) {
            BY_LEVEL.remove(level);
        }
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    public static CMWorldData defaultReadOnly() {
        CMWorldData current = defaultReadOnly;
        if (current == null) {
            current = CMWorldData.factory().constructor().get();
            defaultReadOnly = current;
        }
        return current;
    }
}
