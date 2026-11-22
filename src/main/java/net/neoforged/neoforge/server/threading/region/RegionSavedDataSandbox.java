/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Per-{@link DimensionDataStorage} region-scoped sandbox and shared-broadcast cache for {@link SavedData}.
 * <p>
 * When a native region worker queries or mutates {@code DimensionDataStorage}:
 * <ul>
 * <li>If the {@code dataId} is registered via {@link RegionAdapterApi#shareSavedData(String...)}, the worker
 *     reads and updates the globally published thread-safe mirror so all regions observe the shared state.</li>
 * <li>Otherwise, the worker operates on an isolated per-region replica keyed by its active
 *     {@link RegionCoordinate}, preventing both {@code RegionWorkerBoundaryException} crashes and
 *     unsynchronized {@code HashMap} corruption on the main thread's {@code DimensionDataStorage.cache}.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class RegionSavedDataSandbox {
    private static final Object FALLBACK_REGION_KEY = new Object();

    private final Map<String, SavedData> globalPublished = new ConcurrentHashMap<>();
    private final Map<String, SavedData> pendingMainThreadMerge = new ConcurrentHashMap<>();
    private final Map<Object, Map<String, SavedData>> regionLocalSandboxes = new ConcurrentHashMap<>();

    public void onMainThreadPublish(String id, @Nullable SavedData data) {
        if (id == null) {
            return;
        }
        if (data != null) {
            this.globalPublished.put(id, data);
        }
    }

    /**
     * Flushes any shared {@link SavedData} instances created by region workers into the main thread's
     * {@code cache} map while running on the server thread.
     */
    public void drainPendingToMainCache(Map<String, SavedData> mainCache) {
        if (this.pendingMainThreadMerge.isEmpty()) {
            return;
        }
        final var iterator = this.pendingMainThreadMerge.entrySet().iterator();
        while (iterator.hasNext()) {
            final var entry = iterator.next();
            mainCache.putIfAbsent(entry.getKey(), entry.getValue());
            iterator.remove();
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends SavedData> T getSandboxed(SavedData.Factory<T> factory, String id) {
        RegionAdapterApi.recordSandboxedSavedDataRead();
        if (RegionAdapterApi.isSharedSavedData(id)) {
            return (T) this.globalPublished.get(id);
        }
        final Map<String, SavedData> local = this.regionLocalSandboxes.get(currentRegionKey());
        if (local != null) {
            final SavedData existing = local.get(id);
            if (existing != null) {
                return (T) existing;
            }
        }
        // If global has already loaded it, materialize an isolated region-local replica so worker mutations
        // stay inside the current region and never race other workers.
        if (this.globalPublished.containsKey(id) && factory != null && factory.constructor() != null) {
            return (T) this.regionLocalMap().computeIfAbsent(id, ignored -> factory.constructor().get());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T extends SavedData> T computeIfAbsentSandboxed(SavedData.Factory<T> factory, String id) {
        RegionAdapterApi.recordSandboxedSavedDataRead();
        if (RegionAdapterApi.isSharedSavedData(id)) {
            final SavedData existing = this.globalPublished.get(id);
            if (existing != null) {
                return (T) existing;
            }
            final T created = factory.constructor().get();
            final SavedData winner = this.globalPublished.putIfAbsent(id, created);
            if (winner != null) {
                return (T) winner;
            }
            RegionAdapterApi.recordSandboxedSavedDataWrite();
            this.pendingMainThreadMerge.put(id, created);
            return created;
        }
        final Map<String, SavedData> local = this.regionLocalMap();
        final SavedData existing = local.get(id);
        if (existing != null) {
            return (T) existing;
        }
        RegionAdapterApi.recordSandboxedSavedDataWrite();
        return (T) local.computeIfAbsent(id, ignored -> factory.constructor().get());
    }

    public void setSandboxed(String id, SavedData data) {
        RegionAdapterApi.recordSandboxedSavedDataWrite();
        if (RegionAdapterApi.isSharedSavedData(id)) {
            this.globalPublished.put(id, data);
            this.pendingMainThreadMerge.put(id, data);
            for (final Map<String, SavedData> local : this.regionLocalSandboxes.values()) {
                local.remove(id);
            }
            return;
        }
        this.regionLocalMap().put(id, data);
    }

    public void clearRegion(RegionCoordinate coordinate) {
        if (coordinate != null) {
            this.regionLocalSandboxes.remove(coordinate);
        }
    }

    public void clearAll() {
        this.regionLocalSandboxes.clear();
        this.pendingMainThreadMerge.clear();
        this.globalPublished.clear();
    }

    private Map<String, SavedData> regionLocalMap() {
        return this.regionLocalSandboxes.computeIfAbsent(currentRegionKey(), ignored -> new ConcurrentHashMap<>());
    }

    private static Object currentRegionKey() {
        final RegionCoordinate coord = RegionThreadingHooks.currentRegionCoordinateOrNull();
        return coord != null ? coord : FALLBACK_REGION_KEY;
    }
}
