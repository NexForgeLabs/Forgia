/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionMailbox;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickCompatibility;
import org.jetbrains.annotations.ApiStatus;

/**
 * Region-scoped sandbox and cross-region broadcast adapter API.
 * <p>
 * By default, when code runs on a native region worker thread without an explicit adapter:
 * <ul>
 * <li>{@code DimensionDataStorage} accesses operate on a region-local sandbox replica of {@code SavedData}
 *     rather{ than throwing {@code RegionWorkerBoundaryException};</li>
 * <li>Cross-region {@code BlockCapability} / {@code getBlockEntity} queries return {@code null} instead of
 *     crashing the server;</li>
 * <li>{@code ServerLevel.players()} returns an immutable view of players inside the current region.</li>
 * </ul>
 * Compat mods that need a resource or state mutation to propagate across all regions or to a specific
 * target region use {@link #shareSavedData(String...)}, {@link #broadcastGlobal(ServerLevel, Consumer)},
 * {@link #sendToRegion(ServerLevel, BlockPos, Runnable)}, or {@link #bindVirtualRegionAffinity(ServerLevel, ChunkPos, BlockPos)}.
 */
@ApiStatus.Experimental
public final class RegionAdapterApi {
    private static final Set<String> SHARED_SAVED_DATA_IDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> SANDBOX_ADMITTED_BE_TYPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> SANDBOX_ADMITTED_NAMESPACES = ConcurrentHashMap.newKeySet();
    private static final Map<String, java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity>> SANDBOX_NAMESPACE_PREDICATES = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Map<Long, BlockPos>> VIRTUAL_CHUNK_AFFINITY = new ConcurrentHashMap<>();

    private static volatile boolean regionScopedPlayerViewEnabled = Boolean.parseBoolean(
            System.getProperty("neoforge.regionthreading.sandboxPlayerView", "true"));
    private static volatile boolean sandboxSavedDataEnabled = Boolean.parseBoolean(
            System.getProperty("neoforge.regionthreading.sandboxSavedData", "true"));
    private static volatile boolean sandboxCapabilityEnabled = Boolean.parseBoolean(
            System.getProperty("neoforge.regionthreading.sandboxCapability", "true"));
    private static volatile boolean regionSandboxAllAdmittedBlockEntities = Boolean.parseBoolean(
            System.getProperty("neoforge.regionthreading.sandboxBlockEntities", "true"));

    private static final AtomicLong SANDBOXED_SAVED_DATA_READS = new AtomicLong();
    private static final AtomicLong SANDBOXED_SAVED_DATA_WRITES = new AtomicLong();
    private static final AtomicLong SANDBOXED_CAPABILITY_SKIPS = new AtomicLong();
    private static final AtomicLong SANDBOXED_PLAYER_LOOKUPS = new AtomicLong();
    private static final AtomicLong BROADCAST_MESSAGES_SENT = new AtomicLong();
    private static final AtomicLong REGION_MESSAGES_SENT = new AtomicLong();

    static {
        // Default globally-shared SavedData IDs that represent server-wide boss/raid milestones.
        SHARED_SAVED_DATA_IDS.add("cataclysm_world_data");
        SHARED_SAVED_DATA_IDS.add("raids");
        SHARED_SAVED_DATA_IDS.add("raids_end");

        final String configuredShared = System.getProperty("neoforge.regionthreading.sharedSavedData", "");
        if (!configuredShared.isBlank()) {
            for (final String token : configuredShared.split(",")) {
                final String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    SHARED_SAVED_DATA_IDS.add(trimmed);
                }
            }
        }

        final String configuredBe = System.getProperty("neoforge.regionthreading.sandboxBlockEntityTypes", "");
        if (!configuredBe.isBlank()) {
            for (final String token : configuredBe.split(",")) {
                final String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    SANDBOX_ADMITTED_BE_TYPES.add(trimmed);
                }
            }
        }

        final String configuredNs = System.getProperty("neoforge.regionthreading.sandboxNamespaces", "");
        if (!configuredNs.isBlank()) {
            for (final String token : configuredNs.split(",")) {
                final String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    SANDBOX_ADMITTED_NAMESPACES.add(trimmed);
                }
            }
        }
    }

    private RegionAdapterApi() {}

    /**
     * Declares one or more {@code SavedData} storage identifiers as globally shared across all regions.
     * <p>
     * Any region worker querying a shared identifier receives the published level-wide instance and its updates
     * are broadcast to all regions. Undeclared identifiers remain isolated to the calling region's local replica.
     */
    public static void shareSavedData(String... dataIds) {
        Objects.requireNonNull(dataIds, "dataIds");
        for (final String id : dataIds) {
            if (id != null && !id.isBlank()) {
                SHARED_SAVED_DATA_IDS.add(id.trim());
            }
        }
    }

    public static void unshareSavedData(String dataId) {
        if (dataId != null) {
            SHARED_SAVED_DATA_IDS.remove(dataId.trim());
        }
    }

    public static boolean isSharedSavedData(String dataId) {
        return dataId != null && SHARED_SAVED_DATA_IDS.contains(dataId);
    }

    public static Set<String> sharedSavedDataIds() {
        return Set.copyOf(SHARED_SAVED_DATA_IDS);
    }

    /**
     * Admits one or more block-entity type IDs (e.g. {@code "farmersdelight:cooking_pot"}) to run in parallel
     * on region workers under region-ownership sandbox isolation.
     */
    public static void admitRegionSandboxedBlockEntities(String... blockEntityTypes) {
        Objects.requireNonNull(blockEntityTypes, "blockEntityTypes");
        for (final String type : blockEntityTypes) {
            if (type != null && !type.isBlank()) {
                SANDBOX_ADMITTED_BE_TYPES.add(type.trim());
            }
        }
    }

    /**
     * Admits all block-entity types belonging to the given mod namespace(s) (e.g. {@code "create"})
     * to run in parallel on region workers under region-ownership sandbox isolation.
     * Types explicitly listed in {@link RegionTickCompatibility#serverThreadOnlyTypes()} always take precedence.
     */
    public static void admitRegionSandboxedNamespace(String... namespaces) {
        Objects.requireNonNull(namespaces, "namespaces");
        for (final String ns : namespaces) {
            if (ns != null && !ns.isBlank()) {
                SANDBOX_ADMITTED_NAMESPACES.add(ns.trim());
            }
        }
    }

    public static void removeRegionSandboxedNamespace(String namespace) {
        if (namespace != null) {
            SANDBOX_ADMITTED_NAMESPACES.remove(namespace.trim());
        }
    }

    /**
     * Registers a per-instance safety predicate for a sandboxed mod namespace.
     * Returning {@code false} for a specific tick routes that single block entity to the server thread
     * for that tick (e.g., during cross-region kinetic network topology propagation) while keeping
     * all steady-state ticks parallel on region workers.
     */
    public static void registerSandboxPredicate(
            String namespace,
            java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity> canRunOnWorker) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(canRunOnWorker, "canRunOnWorker");
        SANDBOX_NAMESPACE_PREDICATES.put(namespace.trim(), canRunOnWorker);
    }

    public static boolean canRunSandboxedBlockEntityInstance(
            String type,
            @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.entity.BlockEntity be) {
        if (type == null || be == null || SANDBOX_NAMESPACE_PREDICATES.isEmpty()) {
            return true;
        }
        final int colon = type.indexOf(':');
        if (colon <= 0) {
            return true;
        }
        final var predicate = SANDBOX_NAMESPACE_PREDICATES.get(type.substring(0, colon));
        return predicate == null || predicate.test(be);
    }

    public static Set<String> sandboxAdmittedNamespaces() {
        return Set.copyOf(SANDBOX_ADMITTED_NAMESPACES);
    }

    public static boolean isRegionSandboxedBlockEntity(String type) {
        if (type == null || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(type)) {
            return false;
        }
        if (Boolean.getBoolean("neoforge.regionthreading.sandboxAllBlockEntities")
                || (net.neoforged.neoforge.common.ForgiaConfig.isServerConfigLoaded()
                        && net.neoforged.neoforge.common.ForgiaConfig.SERVER.enableRegionSandboxAllBlockEntities.get())) {
            // Keep moving pistons on the server thread to preserve vanilla block-pushing invariants.
            return !"minecraft:piston".equals(type) && !"minecraft:moving_piston".equals(type);
        }
        if (SANDBOX_ADMITTED_BE_TYPES.contains(type)
                || (regionSandboxAllAdmittedBlockEntities && RegionTickCompatibility.admittedBlockEntityTypes().contains(type))) {
            return true;
        }
        if (!SANDBOX_ADMITTED_NAMESPACES.isEmpty()) {
            final int colon = type.indexOf(':');
            if (colon > 0 && SANDBOX_ADMITTED_NAMESPACES.contains(type.substring(0, colon))) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> sandboxAdmittedBlockEntityTypes() {
        return Set.copyOf(SANDBOX_ADMITTED_BE_TYPES);
    }

    public static boolean isSandboxSavedDataEnabled() {
        return sandboxSavedDataEnabled;
    }

    public static void setSandboxSavedDataEnabled(boolean enabled) {
        sandboxSavedDataEnabled = enabled;
    }

    public static boolean isSandboxCapabilityEnabled() {
        return sandboxCapabilityEnabled;
    }

    public static void setSandboxCapabilityEnabled(boolean enabled) {
        sandboxCapabilityEnabled = enabled;
    }

    public static boolean isRegionScopedPlayerViewEnabled() {
        return regionScopedPlayerViewEnabled;
    }

    public static void setRegionScopedPlayerViewEnabled(boolean enabled) {
        regionScopedPlayerViewEnabled = enabled;
    }

    public static boolean isRegionSandboxAllAdmittedBlockEntities() {
        return regionSandboxAllAdmittedBlockEntities;
    }

    public static void setRegionSandboxAllAdmittedBlockEntities(boolean enabled) {
        regionSandboxAllAdmittedBlockEntities = enabled;
    }

    /**
     * {@return true when Phase 2 independent per-region asynchronous tick clocks are enabled}
     */
    public static boolean isIndependentRegionTickEnabled() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        return threading instanceof RegionThreadingEngine engine && engine.isIndependentRegionTickEnabled();
    }

    /**
     * Dispatches an asynchronous, non-blocking tick for the region owning {@code pos} if its independent
     * region clock is due and not currently busy. Returns {@code true} if submitted to a worker.
     */
    public static boolean dispatchIndependentRegionTick(
            ServerLevel level,
            BlockPos pos,
            Runnable regionBody,
            @org.jetbrains.annotations.Nullable Runnable serverThreadCommit) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(regionBody, "regionBody");
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (!(threading instanceof RegionThreadingEngine engine)) {
            regionBody.run();
            if (serverThreadCommit != null) {
                serverThreadCommit.run();
            }
            return true;
        }
        final BlockPos resolved = resolveAffinityPos(level, pos);
        return engine.dispatchIndependentRegionTick(level, resolved, regionBody, serverThreadCommit)
                == net.neoforged.neoforge.server.threading.region.RegionIndependentTickScheduler.DispatchOutcome.SUBMITTED;
    }

    /**
     * Broadcasts an action to every active region of {@code level} through its {@link RegionMailbox}.
     * Each region executes the action under its own ownership at its next mailbox delivery boundary.
     *
     * @return the number of active regions that accepted the broadcast
     */
    public static int broadcastGlobal(ServerLevel level, Consumer<RegionThreadingContext> action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(action, "action");
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (!(threading instanceof RegionThreadingEngine engine)) {
            action.accept(threading != null ? threading.currentContext() : null);
            return 1;
        }
        final int accepted = engine.broadcastToLevelRegions(level, action);
        if (accepted > 0) {
            BROADCAST_MESSAGES_SENT.addAndGet(accepted);
        }
        return accepted;
    }

    /**
     * Posts a server-wide action to the global phase mailbox of {@code level}, executing on the server thread
     * at the next quiescent global boundary when no region workers are in flight.
     */
    public static boolean postGlobal(ServerLevel level, Runnable action) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(action, "action");
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (!(threading instanceof RegionThreadingEngine engine)) {
            if (level.getServer().isSameThread()) {
                action.run();
            } else {
                level.getServer().execute(action);
            }
            return true;
        }
        final RegionMailbox.PostResult res = engine.mailbox(level).postGlobal(action);
        if (res == RegionMailbox.PostResult.ACCEPTED) {
            BROADCAST_MESSAGES_SENT.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Routes {@code task} to the region owning {@code targetPos} in {@code level}.
     * <p>
     * If the current thread already holds the tick ownership of the target region, {@code task} runs immediately
     * inline. Otherwise it is posted to the target region's {@link RegionMailbox} to execute at its next
     * delivery boundary.
     */
    public static boolean sendToRegion(ServerLevel level, BlockPos targetPos, Runnable task) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(targetPos, "targetPos");
        Objects.requireNonNull(task, "task");
        final BlockPos resolved = resolveAffinityPos(level, targetPos);
        if (RegionThreadingHooks.isRegionTickOwnedByCurrentThread(level, resolved)) {
            task.run();
            return true;
        }
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (!(threading instanceof RegionThreadingEngine engine)) {
            if (level.getServer().isSameThread()) {
                task.run();
            } else {
                level.getServer().execute(task);
            }
            return true;
        }
        if (engine.postToRegion(level, resolved, task)) {
            REGION_MESSAGES_SENT.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * Binds a virtual/sub-level chunk (such as a Sable plot chunk) to the region owning {@code hostWorldPos}.
     * Region boundary checks for positions inside {@code virtualChunk} will resolve against {@code hostWorldPos}.
     */
    public static void bindVirtualRegionAffinity(ServerLevel level, ChunkPos virtualChunk, BlockPos hostWorldPos) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(virtualChunk, "virtualChunk");
        Objects.requireNonNull(hostWorldPos, "hostWorldPos");
        VIRTUAL_CHUNK_AFFINITY
                .computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                .put(virtualChunk.toLong(), hostWorldPos.immutable());
    }

    public static void unbindVirtualRegionAffinity(ServerLevel level, ChunkPos virtualChunk) {
        if (level == null || virtualChunk == null) return;
        final Map<Long, BlockPos> map = VIRTUAL_CHUNK_AFFINITY.get(level);
        if (map != null) {
            map.remove(virtualChunk.toLong());
        }
    }

    public static void clearLevelAffinity(ServerLevel level) {
        if (level != null) {
            VIRTUAL_CHUNK_AFFINITY.remove(level);
        }
    }

    public static BlockPos resolveAffinityPos(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return pos;
        final Map<Long, BlockPos> map = VIRTUAL_CHUNK_AFFINITY.get(level);
        if (map == null || map.isEmpty()) return pos;
        final BlockPos host = map.get(ChunkPos.asLong(pos));
        return host != null ? host : pos;
    }

    @ApiStatus.Internal
    public static void recordSandboxedSavedDataRead() {
        SANDBOXED_SAVED_DATA_READS.incrementAndGet();
    }

    @ApiStatus.Internal
    public static void recordSandboxedSavedDataWrite() {
        SANDBOXED_SAVED_DATA_WRITES.incrementAndGet();
    }

    @ApiStatus.Internal
    public static void recordSandboxedCapabilitySkip() {
        SANDBOXED_CAPABILITY_SKIPS.incrementAndGet();
    }

    @ApiStatus.Internal
    public static void recordSandboxedPlayerLookup() {
        SANDBOXED_PLAYER_LOOKUPS.incrementAndGet();
    }

    public static long sandboxedSavedDataReads() {
        return SANDBOXED_SAVED_DATA_READS.get();
    }

    public static long sandboxedSavedDataWrites() {
        return SANDBOXED_SAVED_DATA_WRITES.get();
    }

    public static long sandboxedCapabilitySkips() {
        return SANDBOXED_CAPABILITY_SKIPS.get();
    }

    public static long sandboxedPlayerLookups() {
        return SANDBOXED_PLAYER_LOOKUPS.get();
    }

    public static long broadcastMessagesSent() {
        return BROADCAST_MESSAGES_SENT.get();
    }

    public static long regionMessagesSent() {
        return REGION_MESSAGES_SENT.get();
    }
}
