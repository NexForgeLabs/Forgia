/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Construction-time settings for the native runtime, grouped by responsibility.
 * <p>
 * Compatibility decisions and entity-storage selection remain at the existing bootstrap call site. This
 * value neither reads configuration nor caches live admission decisions; mutable runtime switches retain
 * their existing engine fields and lifecycle. It is internal implementation, not part of the Mod API.
 */
@ApiStatus.Internal
public record RegionThreadingOptions(
        Workers workers,
        TickSlices ticks,
        RegionLayout layout,
        RegionEntityStorageMode entityStorageMode,
        RegionOverloadGovernor.Config overload) {
    public RegionThreadingOptions(
            Workers workers,
            TickSlices ticks,
            RegionLayout layout,
            RegionEntityStorageMode entityStorageMode) {
        this(workers, ticks, layout, entityStorageMode, RegionOverloadGovernor.Config.disabled());
    }

    public RegionThreadingOptions {
        if (overload == null) {
            overload = RegionOverloadGovernor.Config.disabled();
        }
    }

    /** Worker-pool and region-task dispatch settings; no executor is created by this value. */
    public record Workers(
            int threads,
            boolean regionTasks,
            boolean globalTasks,
            boolean persistent,
            boolean packedRegionLeases) {}

    /** Initial selection of the bounded tick slices, after the existing compatibility checks. */
    public record TickSlices(
            boolean entities,
            boolean blockEntities,
            boolean scheduledTicks,
            boolean chunkTicks,
            boolean spawning) {}
}
