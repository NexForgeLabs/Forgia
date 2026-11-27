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
    private static final Set<String> SANDBOX_DENIED_NAMESPACES = ConcurrentHashMap.newKeySet();
    private static final Set<String> SANDBOX_DENIED_BE_TYPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> SANDBOX_DENIED_FLUIDS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REGION_CONTAINED_ENTITY_TYPES = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> SERVER_THREAD_ONLY_ENTITY_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, java.util.function.BiPredicate<ServerLevel, BlockPos>> SANDBOX_FLUID_PREDICATES = new ConcurrentHashMap<>();
    private static final Map<String, java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity>> SANDBOX_NAMESPACE_PREDICATES = new ConcurrentHashMap<>();
    private static final Map<String, java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity>> SANDBOX_TYPE_PREDICATES = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>>> TRANSIENT_BE_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>> TRANSIENT_BE_BOUNDARY_COUNTS = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, double[]> SERVER_THREAD_ENTITY_LOCKSTEP_BOXES = new ConcurrentHashMap<>();
    private static final int PERMANENT_DEMOTION_THRESHOLD = 16;
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

    public static void denyRegionSandboxedNamespace(String... namespaces) {
        for (final String namespace : namespaces) {
            if (namespace != null && !namespace.isBlank()) {
                SANDBOX_DENIED_NAMESPACES.add(namespace.trim());
            }
        }
    }

    /**
     * Excludes one or more specific block entity registry IDs (e.g. {@code "modid:controller"})
     * from parallel region sandbox execution, routing them to the server thread while allowing
     * other block entity types from the same namespace to run on region workers.
     */
    public static void denyRegionSandboxedBlockEntityType(String... blockEntityTypes) {
        for (final String type : blockEntityTypes) {
            if (type != null && !type.isBlank()) {
                SANDBOX_DENIED_BE_TYPES.add(type.trim());
            }
        }
    }

    /**
     * Excludes one or more fluid registry IDs (e.g. {@code "modid:acid"}) or mod namespaces
     * (e.g. {@code "modid"}) from parallel region fluid worker execution, routing them to the server thread.
     */
    public static void denyRegionSandboxedFluid(String... fluidIdsOrNamespaces) {
        for (final String entry : fluidIdsOrNamespaces) {
            if (entry != null && !entry.isBlank()) {
                SANDBOX_DENIED_FLUIDS.add(entry.trim());
            }
        }
    }

    /**
     * Registers a per-position safety predicate for a fluid registry ID or mod namespace.
     * Returning {@code false} routes that fluid tick cleanly to the server thread for that tick.
     */
    public static void registerFluidSandboxPredicate(
            String fluidIdOrNamespace,
            java.util.function.BiPredicate<ServerLevel, BlockPos> canRunOnWorker) {
        Objects.requireNonNull(fluidIdOrNamespace, "fluidIdOrNamespace");
        Objects.requireNonNull(canRunOnWorker, "canRunOnWorker");
        SANDBOX_FLUID_PREDICATES.put(fluidIdOrNamespace.trim(), canRunOnWorker);
    }

    public static boolean canRunSandboxedFluid(
            ServerLevel level,
            BlockPos pos,
            net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == null) {
            return false;
        }
        if (SANDBOX_DENIED_FLUIDS.isEmpty() && SANDBOX_FLUID_PREDICATES.isEmpty()) {
            return true;
        }
        final var key = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
        if (key == null) {
            return true;
        }
        final String fullId = key.toString();
        final String namespace = key.getNamespace();
        if (SANDBOX_DENIED_FLUIDS.contains(fullId) || SANDBOX_DENIED_FLUIDS.contains(namespace)) {
            return false;
        }
        final var byId = SANDBOX_FLUID_PREDICATES.get(fullId);
        if (byId != null && !byId.test(level, pos)) {
            return false;
        }
        final var byNs = SANDBOX_FLUID_PREDICATES.get(namespace);
        return byNs == null || byNs.test(level, pos);
    }

    public static void registerSandboxTypePredicate(
            String blockEntityTypeId,
            java.util.function.Predicate<net.minecraft.world.level.block.entity.BlockEntity> canRunOnWorker) {
        Objects.requireNonNull(blockEntityTypeId, "blockEntityTypeId");
        Objects.requireNonNull(canRunOnWorker, "canRunOnWorker");
        SANDBOX_TYPE_PREDICATES.put(blockEntityTypeId.trim(), canRunOnWorker);
    }

    public static boolean hasSandboxTypePredicate(String blockEntityTypeId) {
        return blockEntityTypeId != null && SANDBOX_TYPE_PREDICATES.containsKey(blockEntityTypeId.trim());
    }

    /**
     * Registers one or more moving-structure entity type IDs (e.g. {@code "modid:contraption"}) as eligible for
     * Phase 4B single-region worker admission when {@code enableSingleRegionContraptionWorkers} is enabled and
     * the entity's inflated AABB + velocity remain strictly within one leased region.
     * <p>
     * When Phase 4B is disabled or the entity nears/crosses a region border, it automatically routes to the
     * server thread with 0-tick lockstep proximity co-scheduling.
     */
    public static void registerRegionContainedEntityType(String... entityTypeIds) {
        Objects.requireNonNull(entityTypeIds, "entityTypeIds");
        for (final String id : entityTypeIds) {
            if (id != null && !id.isBlank()) {
                final String trimmed = id.trim();
                REGION_CONTAINED_ENTITY_TYPES.add(trimmed);
                SERVER_THREAD_ONLY_ENTITY_TYPES.putIfAbsent(trimmed, "region_contained_moving_structure");
            }
        }
        applyRegisteredServerThreadOnlyEntitiesToCurrentEngine();
    }

    /**
     * Registers an entity type ID that must execute on the server thread unless admitted by Phase 4B
     * single-region containment.
     */
    public static void registerServerThreadOnlyEntityType(String entityTypeId, String reason) {
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(reason, "reason");
        final String trimmed = entityTypeId.trim();
        final String trimmedReason = reason.trim();
        if (!trimmed.isEmpty() && !trimmedReason.isEmpty()) {
            SERVER_THREAD_ONLY_ENTITY_TYPES.put(trimmed, trimmedReason);
            applyRegisteredServerThreadOnlyEntitiesToCurrentEngine();
        }
    }

    public static boolean isRegionContainedEntityType(@org.jetbrains.annotations.Nullable String entityTypeId) {
        return entityTypeId != null && REGION_CONTAINED_ENTITY_TYPES.contains(entityTypeId.trim());
    }

    public static boolean isRegionContainedEntity(@org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity entity) {
        if (entity == null) {
            return false;
        }
        final var key = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null && REGION_CONTAINED_ENTITY_TYPES.contains(key.toString());
    }

    public static Map<String, String> registeredServerThreadOnlyEntityTypes() {
        return Map.copyOf(SERVER_THREAD_ONLY_ENTITY_TYPES);
    }

    public static Set<String> registeredRegionContainedEntityTypes() {
        return Set.copyOf(REGION_CONTAINED_ENTITY_TYPES);
    }

    private static void applyRegisteredServerThreadOnlyEntitiesToCurrentEngine() {
        final RegionThreading threading = RegionThreadingManager.currentOrNull();
        if (threading instanceof RegionThreadingEngine engine) {
            engine.applyAdapterRegisteredEntityPolicies();
        }
    }

    public static boolean recordTransientBoundaryFallback(ServerLevel level, BlockPos pos, String type, long cooldownTicks) {
        if (level == null || pos == null || type == null) {
            return false;
        }
        final long posKey = pos.asLong();
        final long chunkKey = ChunkPos.asLong(pos);
        final int count = TRANSIENT_BE_BOUNDARY_COUNTS
                .computeIfAbsent(level, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(posKey, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
        final ConcurrentHashMap<Long, Long> byPos = TRANSIENT_BE_COOLDOWNS
                .computeIfAbsent(level, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
        final int threshold = net.neoforged.neoforge.server.threading.region.RegionThreadingConfig.sandboxPermanentDemotionThreshold();
        final long baseCooldown = Math.max(1L, cooldownTicks);
        if (count >= threshold) {
            final long maxBackoff = Math.max(baseCooldown,
                    net.neoforged.neoforge.server.threading.region.RegionThreadingConfig.sandboxMaxInstanceBackoffTicks());
            final int shift = Math.min(12, count - threshold + 4);
            final long backoff = Math.min(maxBackoff, baseCooldown << shift);
            byPos.put(posKey, level.getGameTime() + backoff);
            return false;
        }
        byPos.put(posKey, level.getGameTime() + baseCooldown);
        return true;
    }

    public static boolean isTransientSandboxedFallback(@org.jetbrains.annotations.Nullable ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        final ConcurrentHashMap<Long, ConcurrentHashMap<Long, Long>> byChunk = TRANSIENT_BE_COOLDOWNS.get(level);
        if (byChunk == null || byChunk.isEmpty()) {
            return false;
        }
        final long chunkKey = ChunkPos.asLong(pos);
        final ConcurrentHashMap<Long, Long> byPos = byChunk.get(chunkKey);
        if (byPos == null || byPos.isEmpty()) {
            return false;
        }
        final long key = pos.asLong();
        final Long until = byPos.get(key);
        if (until == null) {
            return false;
        }
        if (level.getGameTime() >= until) {
            byPos.remove(key, until);
            if (byPos.isEmpty()) {
                byChunk.remove(chunkKey, byPos);
            }
            return false;
        }
        return true;
    }

    /**
     * Records a non-permanent transient server-thread fallback for a single block entity instance
     * (e.g. when it triggers a structural side effect such as spawning a non-trivial entity).
     * Unlike {@link #recordTransientBoundaryFallback}, this never permanently demotes the BE type.
     */
    public static void recordTransientSideEffectFallback(ServerLevel level, BlockPos pos, long cooldownTicks) {
        if (level == null || pos == null || cooldownTicks <= 0L) {
            return;
        }
        final long chunkKey = ChunkPos.asLong(pos);
        final ConcurrentHashMap<Long, Long> byPos = TRANSIENT_BE_COOLDOWNS
                .computeIfAbsent(level, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(chunkKey, k -> new ConcurrentHashMap<>());
        final long until = level.getGameTime() + cooldownTicks;
        byPos.merge(pos.asLong(), until, Math::max);
    }

    public static void clearTransientSandboxedFallbackAt(@org.jetbrains.annotations.Nullable ServerLevel level, @org.jetbrains.annotations.Nullable BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        final long posKey = pos.asLong();
        final long chunkKey = ChunkPos.asLong(pos);
        final var byChunk = TRANSIENT_BE_COOLDOWNS.get(level);
        if (byChunk != null) {
            final var byPos = byChunk.get(chunkKey);
            if (byPos != null) {
                byPos.remove(posKey);
                if (byPos.isEmpty()) {
                    byChunk.remove(chunkKey, byPos);
                }
            }
        }
        final var counts = TRANSIENT_BE_BOUNDARY_COUNTS.get(level);
        if (counts != null) {
            counts.remove(posKey);
        }
    }

    public static void clearTransientSandboxedFallbacksInChunk(@org.jetbrains.annotations.Nullable ServerLevel level, long chunkKey) {
        if (level == null) {
            return;
        }
        final var byChunk = TRANSIENT_BE_COOLDOWNS.get(level);
        if (byChunk == null) {
            return;
        }
        final var removed = byChunk.remove(chunkKey);
        if (removed != null && !removed.isEmpty()) {
            final var counts = TRANSIENT_BE_BOUNDARY_COUNTS.get(level);
            if (counts != null) {
                for (final Long posKey : removed.keySet()) {
                    counts.remove(posKey);
                }
            }
        }
    }

    /**
     * Publishes the current tick's inflated AABBs (flat array of 6 doubles per box:
     * {@code minX, minY, minZ, maxX, maxY, maxZ}) for active non-player, non-projectile,
     * non-item server-thread-only entities in {@code level}.
     */
    public static void updateServerThreadEntityLockstepBoxes(ServerLevel level, @org.jetbrains.annotations.Nullable double[] flatBoxes) {
        if (level == null) {
            return;
        }
        if (flatBoxes == null || flatBoxes.length == 0) {
            SERVER_THREAD_ENTITY_LOCKSTEP_BOXES.remove(level);
        } else {
            SERVER_THREAD_ENTITY_LOCKSTEP_BOXES.put(level, flatBoxes);
        }
    }

    /**
     * {@return true when {@code pos} lies within the proximity lockstep radius of any active
     * server-thread-only entity in {@code level}, so that only adjacent BEs co-schedule on the
     * server thread without stalling the rest of the region}
     */
    public static boolean isNearServerThreadEntity(@org.jetbrains.annotations.Nullable ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        final double[] boxes = SERVER_THREAD_ENTITY_LOCKSTEP_BOXES.get(level);
        if (boxes == null || boxes.length == 0) {
            return false;
        }
        final double x = pos.getX() + 0.5;
        final double y = pos.getY() + 0.5;
        final double z = pos.getZ() + 0.5;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (x >= boxes[i] && x <= boxes[i + 3]
                    && y >= boxes[i + 1] && y <= boxes[i + 4]
                    && z >= boxes[i + 2] && z <= boxes[i + 5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fast chunk-level pre-check on the server thread before dispatching a chunk's sandboxed BEs
     * asynchronously. Returns {@code true} only when this chunk overlaps an active server-thread
     * entity lockstep box or when this specific chunk has active transient per-position fallbacks, so those
     * specific BEs can be routed into {@code serialAdmissions} on the <em>current</em> tick with
     * zero 1-tick {@code postGlobal} phase skew.
     */
    public static boolean chunkNeedsSynchronousSandboxSplit(
            @org.jetbrains.annotations.Nullable ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return false;
        }
        final var byChunk = TRANSIENT_BE_COOLDOWNS.get(level);
        if (byChunk != null && !byChunk.isEmpty()) {
            final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
            final var chunkCooldowns = byChunk.get(chunkKey);
            if (chunkCooldowns != null && !chunkCooldowns.isEmpty()) {
                return true;
            }
        }
        final double[] boxes = SERVER_THREAD_ENTITY_LOCKSTEP_BOXES.get(level);
        if (boxes == null || boxes.length == 0) {
            return false;
        }
        final double minX = chunkX << 4;
        final double maxX = minX + 16.0;
        final double minZ = chunkZ << 4;
        final double maxZ = minZ + 16.0;
        for (int i = 0; i + 5 < boxes.length; i += 6) {
            if (boxes[i + 3] >= minX && boxes[i] <= maxX
                    && boxes[i + 5] >= minZ && boxes[i + 2] <= maxZ) {
                return true;
            }
        }
        return false;
    }

    public static void clearTransientSandboxedFallbacksForLevel(ServerLevel level) {
        if (level != null) {
            TRANSIENT_BE_COOLDOWNS.remove(level);
            TRANSIENT_BE_BOUNDARY_COUNTS.remove(level);
            SERVER_THREAD_ENTITY_LOCKSTEP_BOXES.remove(level);
        }
    }

    public static boolean canRunSandboxedBlockEntityInstance(
            String type,
            @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.entity.BlockEntity be) {
        if (type == null || be == null) {
            return true;
        }
        if (be.getLevel() instanceof ServerLevel sl
                && (isTransientSandboxedFallback(sl, be.getBlockPos())
                        || isNearServerThreadEntity(sl, be.getBlockPos()))) {
            return false;
        }
        if ("minecraft:hopper".equals(type) && !isHopperSandboxSafe(be)) {
            return false;
        }
        final var typePredicate = SANDBOX_TYPE_PREDICATES.get(type);
        if (typePredicate != null && !typePredicate.test(be)) {
            return false;
        }
        if (SANDBOX_NAMESPACE_PREDICATES.isEmpty()) {
            return true;
        }
        final int colon = type.indexOf(':');
        if (colon <= 0) {
            return true;
        }
        final var predicate = SANDBOX_NAMESPACE_PREDICATES.get(type.substring(0, colon));
        return predicate == null || predicate.test(be);
    }

    private static boolean isHopperSandboxSafe(net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        final BlockPos pos = be.getBlockPos();
        final var threading = RegionThreadingManager.currentOrNull();
        final boolean interior;
        if (threading instanceof net.neoforged.neoforge.server.threading.region.RegionThreadingEngine engine) {
            final var layout = engine.regionLayout();
            final var owner = layout.regionAt(level, pos);
            interior = layout.isInRegion(level, (pos.getX() - 1) >> 4, (pos.getZ() - 1) >> 4, owner)
                    && layout.isInRegion(level, (pos.getX() + 1) >> 4, (pos.getZ() - 1) >> 4, owner)
                    && layout.isInRegion(level, (pos.getX() - 1) >> 4, (pos.getZ() + 1) >> 4, owner)
                    && layout.isInRegion(level, (pos.getX() + 1) >> 4, (pos.getZ() + 1) >> 4, owner);
        } else {
            interior = true;
        }
        if (!interior) {
            return false;
        }
        // Open-top hoppers pull ground ItemEntities via region-scoped entity queries.
        // Under Phase 4 unified loop (with active RegionEntityOwnershipScope) and no nearby players,
        // the owning worker holds exclusive entity-bucket & item-removal deferral authority.
        final BlockPos above = pos.above();
        if (level.getBlockState(above).isAir() && level.getBlockEntity(above) == null) {
            final boolean allowedScope = net.neoforged.neoforge.server.threading.RegionThreadingHooks.isNativeRegionWorkerThread()
                    ? net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope.hasActiveLease(level)
                    : net.neoforged.neoforge.server.threading.region.RegionThreadingConfig.unifiedRegionTickLoopEnabled();
            if (!allowedScope) {
                return false;
            }
            final int safeRadius = net.neoforged.neoforge.server.threading.region.RegionThreadingConfig.sandboxHopperPlayerSafeRadiusBlocks();
            if (safeRadius > 0) {
                final double maxDistSq = (double) safeRadius * safeRadius;
                for (final net.minecraft.server.level.ServerPlayer player : level.players()) {
                    if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= maxDistSq) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static Set<String> sandboxAdmittedNamespaces() {
        return Set.copyOf(SANDBOX_ADMITTED_NAMESPACES);
    }

    public static boolean isExplicitlySandboxedBlockEntity(@org.jetbrains.annotations.Nullable String type) {
        if (type == null || SANDBOX_DENIED_BE_TYPES.contains(type)) {
            return false;
        }
        final int colon = type.indexOf(':');
        if (colon > 0 && SANDBOX_DENIED_NAMESPACES.contains(type.substring(0, colon))) {
            return false;
        }
        if (SANDBOX_ADMITTED_BE_TYPES.contains(type)) {
            return true;
        }
        return colon > 0 && SANDBOX_ADMITTED_NAMESPACES.contains(type.substring(0, colon));
    }

    public static boolean isRegionSandboxedBlockEntity(String type) {
        if (type == null || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(type)
                || SANDBOX_DENIED_BE_TYPES.contains(type)) {
            return false;
        }
        final int nsEnd = type.indexOf(':');
        if (nsEnd > 0 && SANDBOX_DENIED_NAMESPACES.contains(type.substring(0, nsEnd))) {
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
