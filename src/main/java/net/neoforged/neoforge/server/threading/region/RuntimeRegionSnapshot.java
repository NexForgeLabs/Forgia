/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/** Immutable region observation. Negative workload counts mean unavailable, never an assumed zero. */
@ApiStatus.Internal
public record RuntimeRegionSnapshot(RegionTickSnapshot ticks, double p50Ms, long groupTicks, long overruns,
        long mainThreadNanos, long workerNanos, long serialGroups, long workerGroups,
        int loadedChunks, int entities, int players, int blockEntities, int tickingChunks,
        int pendingWork, String lifecycle, boolean running, boolean submitted) {
    public String id() {
        return this.ticks.describeRegion();
    }
}
