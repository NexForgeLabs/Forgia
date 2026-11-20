/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Spatial prerequisite for original player calls. Does not close callbacks/global state or admit workers. */
@ApiStatus.Internal
public final class RegionPlayerSpatialPlan {
    public enum Refusal {
        NONE, DEPENDENCIES, NOT_QUIESCENT, LIFECYCLE, RIDING, MOVEMENT_UNBOUNDED, ACTIVE_BLOCK_BREAK,
        CALLBACK_SPATIAL_BOUND_UNKNOWN, UNLOADED_CHUNK, MISSING_REGION, OWNER_PUBLICATION_PENDING,
        NO_IDENTITY, CAPACITY, FOOTPRINT_TOO_LARGE, MULTIPART_UNBOUNDED
    }

    public record Capture(@Nullable RegionPlayerSpatialPlan plan, Refusal refusal, String detail) {
        public Capture(@Nullable RegionPlayerSpatialPlan plan, Refusal refusal) {
            this(plan, refusal, refusal.name());
        }

        public Capture {
            if ((plan == null) == (refusal == Refusal.NONE)) throw new IllegalArgumentException("Spatial capture result must be explicit");
        }
    }

    private final RegionThreadingEngine engine;
    private final ServerPlayer player;
    private final RegionPlayerTickCoordinator.Phase phase;
    private final long tick;
    private final RegionPlayerFootprint footprint;
    private final Map<Entity, EntityIdentity> members;
    private final Long2ObjectMap<LevelChunk> chunks;
    private final AABB initialBox;
    private final boolean inspectedVanillaTerrain;
    private final long chunkGeneration;
    private final boolean localEntity;
    @Nullable
    final RegionState singleRegionState;
    @Nullable
    final EntityIdentity singlePlayerIdentity;
    private boolean needsTerrainValidation;

    private RegionPlayerSpatialPlan(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            RegionPlayerFootprint footprint, Map<Entity, EntityIdentity> members, Long2ObjectMap<LevelChunk> chunks,
            boolean inspectedVanillaTerrain, long chunkGeneration, boolean localEntity,
            @Nullable RegionState singleRegionState, @Nullable EntityIdentity singlePlayerIdentity) {
        this.engine = engine;
        this.player = player;
        this.phase = phase;
        this.footprint = footprint;
        // These maps are constructed by capture and transferred here, with no other writer.
        this.members = members.size() == 1 ? members : Collections.unmodifiableMap(members);
        this.chunks = chunks;
        this.tick = Integer.toUnsignedLong(player.server.getTickCount());
        this.initialBox = player.getBoundingBox();
        this.inspectedVanillaTerrain = inspectedVanillaTerrain;
        this.chunkGeneration = chunkGeneration;
        this.localEntity = localEntity;
        this.singleRegionState = singleRegionState;
        this.singlePlayerIdentity = singlePlayerIdentity;
    }

    boolean isLocalEntity() {
        return this.localEntity;
    }

    static Capture captureLocalEntity(RegionThreadingEngine engine, ServerPlayer player) {
        // ServerPlayer.tick has no movement or collision body in this profile. Only the
        // player participates in the lease, even when its region contains other entities.
        ServerLevel level = player.serverLevel();
        if (level.getClass() != ServerLevel.class || !engine.playerCriterionStorageEnabled()
                || !engine.isEntityIndexPublished(level) || engine.isLevelClosed(level)) return refused(Refusal.DEPENDENCIES);
        if (!engine.playerCaptureQuiescent()) return refused(Refusal.NOT_QUIESCENT);
        if (player.containerMenu != player.inventoryMenu) return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
        var intrinsic = intrinsicRefusal(player, RegionPlayerTickCoordinator.Phase.ENTITY, false);
        if (intrinsic != Refusal.NONE) return refused(intrinsic);
        RegionState region = engine.regionFor(player);
        if (region == null || region.isDead() || region.entityTickEntry(player) == null) return refused(Refusal.MISSING_REGION);
        if (!engine.regionLayout().isInRegion(level, player.blockPosition(), region.coordinate())) return refused(Refusal.OWNER_PUBLICATION_PENDING);
        EntityIdentity identity = level.entityIdentityLedger().liveIdentity(player);
        if (!liveIdentity(identity, player)) return refused(Refusal.NO_IDENTITY);
        var members = Collections.singletonMap((Entity) player, identity);
        var footprint = RegionPlayerFootprint.localEntity(level, engine.regionLayout().regionChunkSize(), region.coordinate(), player.getBoundingBox());
        return new Capture(new RegionPlayerSpatialPlan(engine, player, RegionPlayerTickCoordinator.Phase.ENTITY, footprint, members,
                Long2ObjectMaps.emptyMap(), true, Long.MIN_VALUE, true, region, identity), Refusal.NONE);
    }

    boolean inspectedVanillaTerrain() {
        return this.inspectedVanillaTerrain;
    }

    public RegionPlayerFootprint footprint() {
        return this.footprint;
    }

    public RegionPlayerTickCoordinator.Phase phase() {
        return this.phase;
    }

    public RegionPlayerConflictPlan.Entry conflictEntry() {
        return new RegionPlayerConflictPlan.Entry(this.player, this.engine.regionAt(this.footprint.level(), this.player.blockPosition()), this.footprint,
                this.members.keySet().stream().filter(e -> e.getBoundingBox().intersects(this.footprint.queryBounds())).toList());
    }

    boolean belongsTo(RegionThreadingEngine engine) {
        return this.engine == engine;
    }

    ServerPlayer player() {
        return this.player;
    }

    Map<Entity, EntityIdentity> members() {
        return this.members;
    }

    Long2ObjectMap<LevelChunk> chunks() {
        return this.chunks;
    }

    /** Recheck before EACH unstarted original call; a previous member can change this player's velocity. */
    boolean revalidate() {
        return this.revalidate(false);
    }

    boolean revalidate(boolean sameSealedPrefix) {
        if (!this.player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Spatial revalidation requires the publishing thread");
        if (!(sameSealedPrefix ? this.positionCurrent() : this.geometryCurrent(true)) || this.player.server.getPlayerList().getPlayer(this.player.getUUID()) != this.player) return false;
        EntityIdentityLedger ledger = this.footprint.level().entityIdentityLedger();
        if (sameSealedPrefix && this.singleRegionState != null && this.singlePlayerIdentity != null && this.members.size() == 1) {
            if (this.singleRegionState.isDead() || (!this.localEntity && this.singleRegionState.entityTickEntryCount() != 1)
                    || this.singleRegionState.entityTickEntry(this.player) == null) {
                return false;
            }
            if (!this.chunks.isEmpty()) {
                var chunkSource = this.footprint.level().getChunkSource();
                if (this.chunkGeneration != chunkSource.forgiaPlayerChunkGeneration()) {
                    var entries = Long2ObjectMaps.fastIterator(this.chunks);
                    while (entries.hasNext()) {
                        var entry = entries.next();
                        if (loadedChunk(chunkSource, entry.getLongKey(), this.inspectedVanillaTerrain) != entry.getValue()) return false;
                    }
                }
            }
            return true;
        }
        for (var entry : this.members.entrySet()) {
            Entity entity = entry.getKey();
            RegionState owner = this.engine.regionFor(entity);
            if (!entry.getValue().equals(ledger.liveIdentity(entity)) || owner == null
                    || !this.engine.regionLayout().isInRegion(this.footprint.level(), entity.blockPosition(), owner.coordinate())
                    || !this.footprint.regions().contains(owner.coordinate()))
                return false;
        }
        int indexed = 0;
        for (RegionCoordinate coordinate : this.footprint.regions()) {
            RegionState region = this.engine.regionStateFor(coordinate);
            if (region == null || region.isDead()) return false;
            if (this.members.size() == 1) {
                int count = region.entityTickEntryCount();
                if (count > 1 || (count == 1 && region.entityTickEntry(this.player) == null)) return false;
                indexed += count;
            } else {
                for (Entity entity : region.indexedEntities()) {
                    if (!this.members.containsKey(entity)) return false;
                    indexed++;
                }
            }
        }
        var chunkSource = this.footprint.level().getChunkSource();
        if (sameSealedPrefix && this.chunkGeneration == chunkSource.forgiaPlayerChunkGeneration()) {
            // A completed FULL observation cannot be replaced while the chunk source generation
            // is unchanged. Retain the live unload check for each captured chunk.
            for (LevelChunk chunk : this.chunks.values()) {
                if (!chunk.isLoaded()) return false;
            }
        } else {
            var entries = Long2ObjectMaps.fastIterator(this.chunks);
            while (entries.hasNext()) {
                var entry = entries.next();
                if (loadedChunk(chunkSource, entry.getLongKey(), this.inspectedVanillaTerrain) != entry.getValue()) return false;
            }
        }
        if (indexed != this.members.size()) return false;
        // The same frozen tick prefix has run only publisher capture and index publication.
        // Recheck indexed membership and exact chunk identities above; no gameplay or packet
        // ran since the terrain and physical section query were originally inspected.
        if (sameSealedPrefix) return true;
        if (this.localEntity || this.needsTerrainValidation) return false;
        if (!knownBlocks(this.player, this.footprint, this.initialBox, this.phase, this.chunks, this.inspectedVanillaTerrain)) return false;
        for (Entity entity : this.footprint.level().getEntities(this.player, this.footprint.queryBounds(), target -> true)) {
            if (!this.members.containsKey(entity)) return false;
        }
        return true;
    }

    /** Local geometry only; the coordinator's lifecycle/phase admission is a separate mandatory gate. */
    boolean geometryCurrent() {
        return !this.needsTerrainValidation && this.geometryCurrent(!this.inspectedVanillaTerrain, true);
    }

    boolean geometryCurrentForLane(boolean laneValidated) {
        return !this.needsTerrainValidation && this.geometryCurrent(!this.inspectedVanillaTerrain, !laneValidated);
    }

    private boolean geometryCurrent(boolean checkInventory) {
        return this.geometryCurrent(checkInventory, true);
    }

    private boolean geometryCurrent(boolean checkInventory, boolean checkMenu) {
        if (!this.positionCurrent() || intrinsicRefusal(this.player, this.phase, checkInventory, checkMenu) != Refusal.NONE) return false;
        if (this.singlePlayerIdentity != null && this.members.size() == 1) {
            return this.player.level() == this.footprint.level() && this.player.getId() == this.singlePlayerIdentity.entityId()
                    && this.player.getUUID().equals(this.singlePlayerIdentity.uuid());
        }
        for (var entry : this.members.entrySet()) {
            Entity entity = entry.getKey();
            if (entity.level() != this.footprint.level() || entity.getId() != entry.getValue().entityId()
                    || !entity.getUUID().equals(entry.getValue().uuid())) return false;
        }
        return true;
    }

    private boolean positionCurrent() {
        return !this.engine.isShutDown() && !this.engine.isLevelClosed(this.footprint.level())
                && this.tick == Integer.toUnsignedLong(this.player.server.getTickCount())
                && this.player.serverLevel() == this.footprint.level() && this.initialBox.equals(this.player.getBoundingBox());
    }

    private static Capture refused(Refusal reason) {
        return new Capture(null, reason);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        return capture(engine, player, phase, false);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase, boolean inspectedVanillaTerrain) {
        return capture(engine, player, phase, inspectedVanillaTerrain, false);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            boolean inspectedVanillaTerrain, boolean allowDeferredTerrain) {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Spatial capture requires the original server thread");
        if (!engine.playerCriterionStorageEnabled() || !engine.isEntityIndexPublished(player.serverLevel())) return refused(Refusal.DEPENDENCIES);
        if (!engine.playerCaptureQuiescent()) return refused(Refusal.NOT_QUIESCENT);
        boolean connectionLike = phase == RegionPlayerTickCoordinator.Phase.CONNECTION
                || phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY;
        boolean connectionBody = phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY;
        boolean checkInventoryNow = !(allowDeferredTerrain && inspectedVanillaTerrain && connectionLike
                && player.forgiaPlayerStateTemplate(phase.ordinal()) != null);
        Refusal intrinsic = intrinsicRefusal(player, phase, checkInventoryNow, checkInventoryNow);
        if (intrinsic != Refusal.NONE) return refused(intrinsic);
        ServerLevel level = player.serverLevel();
        if (!level.getPartEntities().isEmpty()) return refused(Refusal.MULTIPART_UNBOUNDED);
        AABB body = player.getBoundingBox();
        if (body.minY < level.getMinBuildHeight() || body.maxY > level.getMaxBuildHeight()
                || Math.abs(player.getX()) > 29_999_998 || Math.abs(player.getZ()) > 29_999_998
                || !Double.isFinite(player.getEyeY()) || player.getEyeY() < body.minY || player.getEyeY() > body.maxY)
            return refused(Refusal.MOVEMENT_UNBOUNDED);
        // Player.aiStep's exact non-riding pickup box also contains LivingEntity.pushEntities' box.
        // EntitySectionStorage adds its own -2/+2 horizontal, -4/+0 vertical search padding.
        AABB extent = RegionPlayerPacketPlan.bodyExtent(player, body);
        AABB query = extent.inflate(1, .5, 1);
        // BlockCollisions pads the swept and support queries by one cell plus epsilon.
        // Include that padding and the downward input of at most half a block.
        AABB blocks = connectionBody ? extent.inflate(1.000001, 1.000001, 1.000001).expandTowards(0, -0.5, 0)
                : RegionPlayerPacketPlan.blockExtent(player, RegionPlayerContainerPlan.blockBounds(player, extent.inflate(1, 1, 1).expandTowards(0, -1, 0)));
        final AABB effectBounds = RegionPlayerDamagePlan.extraLeaseBounds(player, blocks);
        final RegionPlayerFootprint footprint;
        try {
            footprint = RegionPlayerFootprint.of(level, engine.regionLayout().regionChunkSize(), query, blocks, effectBounds);
        } catch (IllegalArgumentException refusal) {
            return refused(Refusal.FOOTPRINT_TOO_LARGE);
        }
        int minFootprintChunkX = SectionPos.posToSectionCoord(Math.min(Math.min(blocks.minX, effectBounds.minX), query.minX - 2));
        int maxFootprintChunkX = SectionPos.posToSectionCoord(Math.max(Math.max(blocks.maxX, effectBounds.maxX), query.maxX + 2));
        int minFootprintChunkZ = SectionPos.posToSectionCoord(Math.min(Math.min(blocks.minZ, effectBounds.minZ), query.minZ - 2));
        int maxFootprintChunkZ = SectionPos.posToSectionCoord(Math.max(Math.max(blocks.maxZ, effectBounds.maxZ), query.maxZ + 2));
        ServerChunkCache chunkSource = level.getChunkSource();
        long chunkGeneration = inspectedVanillaTerrain && chunkSource.getClass() == ServerChunkCache.class && chunkSource.chunkMap != null
                ? chunkSource.forgiaPlayerChunkGeneration() : Long.MIN_VALUE;
        Long2ObjectMap<LevelChunk> chunks = null;
        Map<Entity, EntityIdentity> members = null;
        EntityIdentityLedger ledger = level.entityIdentityLedger();
        RegionState singleRegion = null;
        EntityIdentity playerIdentity = null;
        if (footprint.regions().size() == 1) {
            RegionCoordinate coordinate = footprint.regions().getFirst();
            RegionState region = engine.regionStateFor(coordinate);
            if (region == null || region.isDead()) return refused(Refusal.MISSING_REGION);
            singleRegion = region;
            if (chunkGeneration != Long.MIN_VALUE && region.cachedPlayerChunksGeneration == chunkGeneration && region.cachedPlayerChunks != null) {
                boolean allLoaded = true;
                for (LevelChunk chunk : region.cachedPlayerChunks.values()) {
                    if (!chunk.isLoaded()) {
                        allLoaded = false;
                        break;
                    }
                }
                if (allLoaded) chunks = region.cachedPlayerChunks;
            }
            if (chunks == null) {
                int size = footprint.regionChunks();
                int minX = connectionBody ? Math.max(coordinate.regionX() * size, minFootprintChunkX) : coordinate.regionX() * size;
                int maxX = connectionBody ? Math.min((coordinate.regionX() + 1) * size - 1, maxFootprintChunkX) : (coordinate.regionX() + 1) * size - 1;
                int minZ = connectionBody ? Math.max(coordinate.regionZ() * size, minFootprintChunkZ) : coordinate.regionZ() * size;
                int maxZ = connectionBody ? Math.min((coordinate.regionZ() + 1) * size - 1, maxFootprintChunkZ) : (coordinate.regionZ() + 1) * size - 1;
                Long2ObjectOpenHashMap<LevelChunk> map = new Long2ObjectOpenHashMap<>(Math.max(1, (maxX - minX + 1) * (maxZ - minZ + 1)));
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long position = ChunkPos.asLong(x, z);
                        LevelChunk chunk = chunkGeneration == Long.MIN_VALUE ? null : chunkSource.forgiaObservedPlayerChunk(position);
                        if (chunk == null) {
                            chunkGeneration = Long.MIN_VALUE;
                            chunk = loadedChunk(chunkSource, position, inspectedVanillaTerrain);
                        }
                        if (chunk == null || connectionBody && !chunk.isLoaded()) return missingChunk(player, x, z, coordinate, footprint, effectBounds, chunkSource.forgiaPlayerChunkGeneration());
                        map.put(position, chunk);
                    }
                }
                chunks = map;
                if (!connectionBody && chunkGeneration != Long.MIN_VALUE) {
                    region.cachedPlayerChunks = chunks;
                    region.cachedPlayerChunksGeneration = chunkGeneration;
                }
            }
            if (region.entityTickEntryCount() == 1 && region.entityTickEntry(player) != null) {
                if (player.level() != level || engine.regionFor(player) != region
                        || !engine.regionLayout().isInRegion(level, player.blockPosition(), coordinate))
                    return refused(Refusal.OWNER_PUBLICATION_PENDING);
                EntityIdentity identity = ledger.liveIdentity(player);
                if (!liveIdentity(identity, player)) return refused(Refusal.NO_IDENTITY);
                if (player.isMultipartEntity()) return refused(Refusal.MULTIPART_UNBOUNDED);
                playerIdentity = identity;
                members = Collections.singletonMap((Entity) player, identity);
            } else {
                members = new IdentityHashMap<>();
                for (Entity entity : region.indexedEntities()) {
                    if (entity.level() != level || engine.regionFor(entity) != region
                            || !engine.regionLayout().isInRegion(level, entity.blockPosition(), coordinate))
                        return refused(Refusal.OWNER_PUBLICATION_PENDING);
                    EntityIdentity identity = ledger.liveIdentity(entity);
                    if (!liveIdentity(identity, entity)) return refused(Refusal.NO_IDENTITY);
                    if (entity.isMultipartEntity()) return refused(Refusal.MULTIPART_UNBOUNDED);
                    if (entity.getBoundingBox().intersects(query) && entity != player
                            && entity.getClass() != ItemEntity.class && entity.getClass() != ExperienceOrb.class && entity.getClass() != ServerPlayer.class)
                        return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
                    if (entity == player) playerIdentity = identity;
                    members.put(entity, identity);
                    if (members.size() > 4096) return refused(Refusal.CAPACITY);
                }
            }
        } else {
            Long2ObjectOpenHashMap<LevelChunk> map = new Long2ObjectOpenHashMap<>(connectionBody
                    ? Math.max(4, (maxFootprintChunkX - minFootprintChunkX + 1) * (maxFootprintChunkZ - minFootprintChunkZ + 1))
                    : footprint.regions().size() * footprint.regionChunks() * footprint.regionChunks());
            members = new IdentityHashMap<>();
            for (RegionCoordinate coordinate : footprint.regions()) {
                RegionState region = engine.regionStateFor(coordinate);
                if (region == null || region.isDead()) return refused(Refusal.MISSING_REGION);
                int size = footprint.regionChunks();
                int minX = connectionBody ? Math.max(coordinate.regionX() * size, minFootprintChunkX) : coordinate.regionX() * size;
                int maxX = connectionBody ? Math.min((coordinate.regionX() + 1) * size - 1, maxFootprintChunkX) : (coordinate.regionX() + 1) * size - 1;
                int minZ = connectionBody ? Math.max(coordinate.regionZ() * size, minFootprintChunkZ) : coordinate.regionZ() * size;
                int maxZ = connectionBody ? Math.min((coordinate.regionZ() + 1) * size - 1, maxFootprintChunkZ) : (coordinate.regionZ() + 1) * size - 1;
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        long position = ChunkPos.asLong(x, z);
                        LevelChunk chunk = chunkGeneration == Long.MIN_VALUE ? null : chunkSource.forgiaObservedPlayerChunk(position);
                        if (chunk == null) {
                            chunkGeneration = Long.MIN_VALUE;
                            chunk = loadedChunk(chunkSource, position, inspectedVanillaTerrain);
                        }
                        if (chunk == null || connectionBody && !chunk.isLoaded()) return missingChunk(player, x, z, coordinate, footprint, effectBounds, chunkSource.forgiaPlayerChunkGeneration());
                        map.put(position, chunk);
                    }
                }
                for (Entity entity : region.indexedEntities()) {
                    if (entity.level() != level || engine.regionFor(entity) != region
                            || !engine.regionLayout().isInRegion(level, entity.blockPosition(), coordinate))
                        return refused(Refusal.OWNER_PUBLICATION_PENDING);
                    EntityIdentity identity = ledger.liveIdentity(entity);
                    if (!liveIdentity(identity, entity)) return refused(Refusal.NO_IDENTITY);
                    if (entity.isMultipartEntity()) return refused(Refusal.MULTIPART_UNBOUNDED);
                    if (entity.getBoundingBox().intersects(query) && entity != player
                            && entity.getClass() != ItemEntity.class && entity.getClass() != ExperienceOrb.class && entity.getClass() != ServerPlayer.class)
                        return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
                    if (entity == player) playerIdentity = identity;
                    members.put(entity, identity);
                    if (members.size() > 4096) return refused(Refusal.CAPACITY);
                }
            }
            chunks = map;
        }
        if (playerIdentity == null) return refused(Refusal.NO_IDENTITY);
        final Map<Entity, EntityIdentity> capturedMembers = members;
        // Compare against the complete server query before any worker starts. A physical bucket
        // member absent from the region index must never be silently omitted.
        if (inspectedVanillaTerrain && level.getClass() == ServerLevel.class && footprint.regions().size() == 1
                && level.entitySectionRegionChunkSize() == footprint.regionChunks()) {
            RegionCoordinate home = footprint.regions().getFirst();
            boolean[] missing = { false };
            level.forgiaVerifyPlayerSectionMembers(home.regionX(), home.regionZ(), query, section -> section.getEntities(query, entity -> {
                if (entity == player || capturedMembers.containsKey(entity)) return AbortableIterationConsumer.Continuation.CONTINUE;
                missing[0] = true;
                return AbortableIterationConsumer.Continuation.ABORT;
            }));
            if (missing[0]) return refused(Refusal.OWNER_PUBLICATION_PENDING);
        } else {
            for (Entity target : level.getEntities(player, query, entity -> true)) {
                if (!capturedMembers.containsKey(target)) return refused(Refusal.OWNER_PUBLICATION_PENDING);
            }
        }
        boolean deferTerrain = allowDeferredTerrain && inspectedVanillaTerrain && connectionLike
                && level.getClass() == ServerLevel.class && !level.isDebug() && footprint.regions().size() == 1 && members.size() == 1
                && player.containerMenu == player.inventoryMenu && !RegionPlayerDamagePlan.starving(player)
                && player.forgiaPlayerStateTemplate(phase.ordinal()) != null;
        if (!checkInventoryNow && !deferTerrain
                && (!RegionPlayerContainerPlan.supportedMenu(player)
                        || !(connectionBody
                                ? RegionPlayerInventoryPlan.supportedConnectionBodyInventory(player)
                                : RegionPlayerInventoryPlan.supportedInventory(player)))) {
            return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
        }
        if (!deferTerrain && !knownBlocks(player, footprint, body, phase, chunks, inspectedVanillaTerrain)) {
            return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
        }
        var plan = new RegionPlayerSpatialPlan(engine, player, phase, footprint, members, chunks, inspectedVanillaTerrain, chunkGeneration, false, singleRegion, playerIdentity);
        plan.needsTerrainValidation = deferTerrain;
        return new Capture(plan, Refusal.NONE);
    }

    boolean needsTerrainValidation() {
        return this.needsTerrainValidation;
    }

    boolean validateTerrain(boolean worker) {
        if (!this.needsTerrainValidation) return true;
        if (!(worker ? validateLeasedTerrain() : knownBlocks(this.player, this.footprint, this.initialBox, this.phase, this.chunks, true))) {
            return false;
        }
        this.needsTerrainValidation = false;
        return true;
    }

    /** Fast eye-block suffocation check using captured chunks, avoiding BlockPos.betweenClosedStream. */
    boolean isEyeInWall() {
        if (this.player.noPhysics) return false;
        if (this.chunks.isEmpty() || !this.inspectedVanillaTerrain) return this.player.isInWall();
        float f = this.player.getBbWidth() * 0.8F;
        double eyeY = this.player.getEyeY();
        double half = f * 0.5;
        int minX = net.minecraft.util.Mth.floor(this.player.getX() - half);
        int maxX = net.minecraft.util.Mth.floor(this.player.getX() + half);
        int minY = net.minecraft.util.Mth.floor(eyeY - 5.0E-7);
        int maxY = net.minecraft.util.Mth.floor(eyeY + 5.0E-7);
        int minZ = net.minecraft.util.Mth.floor(this.player.getZ() - half);
        int maxZ = net.minecraft.util.Mth.floor(this.player.getZ() + half);
        var level = this.footprint.level();
        if (level.isOutsideBuildHeight(minY) || level.isOutsideBuildHeight(maxY)) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = this.chunks.get(ChunkPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
                if (chunk == null || chunk.getClass() != LevelChunk.class) return this.player.isInWall();
                var sections = chunk.getSections();
                if (sections == null) return this.player.isInWall();
                for (int y = minY; y <= maxY; y++) {
                    int idx = chunk.getSectionIndex(y);
                    if (idx < 0 || idx >= sections.length) return this.player.isInWall();
                    var section = sections[idx];
                    if (section == null || (!section.hasOnlyAir() && !section.getBlockState(x & 15, y & 15, z & 15).isAir())) {
                        return this.player.isInWall();
                    }
                }
            }
        }
        return false;
    }

    private boolean validateLeasedTerrain() {
        // Each lane holds disjoint loaded chunk identities. Reads in this phase use those exact
        // chunks and their section palettes while every original body is still idle.
        AABB bounds = this.footprint.blockBounds();
        int minX = net.minecraft.util.Mth.floor(bounds.minX);
        int minY = net.minecraft.util.Mth.floor(bounds.minY);
        int minZ = net.minecraft.util.Mth.floor(bounds.minZ);
        int maxX = net.minecraft.util.Mth.floor(bounds.maxX);
        int maxY = net.minecraft.util.Mth.floor(bounds.maxY);
        int maxZ = net.minecraft.util.Mth.floor(bounds.maxZ);
        var level = this.footprint.level();
        if (level.isOutsideBuildHeight(minY) || level.isOutsideBuildHeight(maxY)
                || !allInertPalettes(this.chunks, minX, minY, minZ, maxX, maxY, maxZ)) return false;
        AABB body = this.initialBox;
        if (Math.abs(body.minY - Math.rint(body.minY)) > 1e-7) return false;
        int y = (int) body.minY - 1;
        for (int x = (int) Math.floor(body.minX); x <= Math.floor(body.maxX); x++) {
            for (int z = (int) Math.floor(body.minZ); z <= Math.floor(body.maxZ); z++) {
                LevelChunk chunk = this.chunks.get(ChunkPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
                if (chunk == null) return false;
                var section = chunk.getSection(chunk.getSectionIndex(y));
                if (section.hasOnlyAir() || section.getBlockState(x & 15, y & 15, z & 15).isAir()) return false;
            }
        }
        return true;
    }

    private static Capture missingChunk(ServerPlayer player, int x, int z, RegionCoordinate coordinate,
            RegionPlayerFootprint footprint, AABB effectBounds, long chunkGeneration) {
        AABB query = footprint.queryBounds();
        AABB blocks = footprint.blockBounds();
        boolean inQueryMargin = x >= SectionPos.posToSectionCoord(query.minX - 2) && x <= SectionPos.posToSectionCoord(query.maxX + 2)
                && z >= SectionPos.posToSectionCoord(query.minZ - 2) && z <= SectionPos.posToSectionCoord(query.maxZ + 2);
        boolean inBlockBounds = x >= SectionPos.posToSectionCoord(blocks.minX) && x <= SectionPos.posToSectionCoord(blocks.maxX)
                && z >= SectionPos.posToSectionCoord(blocks.minZ) && z <= SectionPos.posToSectionCoord(blocks.maxZ);
        double minX = Math.min(Math.min(blocks.minX, effectBounds.minX), query.minX - 2);
        double minZ = Math.min(Math.min(blocks.minZ, effectBounds.minZ), query.minZ - 2);
        double maxX = Math.max(Math.max(blocks.maxX, effectBounds.maxX), query.maxX + 2);
        double maxZ = Math.max(Math.max(blocks.maxZ, effectBounds.maxZ), query.maxZ + 2);
        boolean inside = x >= SectionPos.posToSectionCoord(minX) && x <= SectionPos.posToSectionCoord(maxX)
                && z >= SectionPos.posToSectionCoord(minZ) && z <= SectionPos.posToSectionCoord(maxZ);
        return new Capture(null, Refusal.UNLOADED_CHUNK, "UNLOADED_CHUNK"
                + " player=" + player.getBlockX() + "," + player.getBlockY() + "," + player.getBlockZ()
                + " chunk=" + x + "," + z
                + " region=" + coordinate.regionX() + "," + coordinate.regionZ()
                + " regions=" + footprint.regions().size()
                + " edgeChunks=" + footprint.regionChunks()
                + " insideFootprint=" + inside
                + " inQueryMargin=" + inQueryMargin
                + " inBlockBounds=" + inBlockBounds
                + " chunkGeneration=" + chunkGeneration);
    }

    @Nullable
    private static LevelChunk loadedChunk(ServerChunkCache source, long position, boolean inspectedVanillaTerrain) {
        // A positive FULL observation does not evict a gameplay entry from the vanilla cache.
        // Keep the original lookup for incomplete holders and alternate chunk sources.
        if (inspectedVanillaTerrain && source.getClass() == ServerChunkCache.class && source.chunkMap != null) {
            LevelChunk observed = source.forgiaObservedPlayerChunk(position);
            if (observed != null) return observed;
        }
        return source.getChunkNow(ChunkPos.getX(position), ChunkPos.getZ(position));
    }

    private static boolean knownBlocks(ServerPlayer player, RegionPlayerFootprint footprint, AABB body, RegionPlayerTickCoordinator.Phase phase,
            Long2ObjectMap<LevelChunk> chunks, boolean inspectedVanillaTerrain) {
        body = RegionPlayerPacketPlan.bodyExtent(player, body);
        ServerLevel level = footprint.level();
        AABB blocks = footprint.blockBounds();
        boolean standardLevel = inspectedVanillaTerrain && level.getClass() == ServerLevel.class && !level.isDebug();
        boolean customMenu = player.containerMenu != player.inventoryMenu;
        BlockPos min = BlockPos.containing(blocks.minX, blocks.minY, blocks.minZ);
        BlockPos max = BlockPos.containing(blocks.maxX, blocks.maxY, blocks.maxZ);
        if (level.isOutsideBuildHeight(min) || level.isOutsideBuildHeight(max)) return false;
        // The palette covers a superset of this box. A positive result merely selects the
        // exact cell scan; an unused unsafe palette entry must not reject an otherwise safe box.
        if ((!standardLevel || customMenu || !allInertPalettes(chunks, min, max)) && !knownBlockCells(player, chunks, min, max, standardLevel, customMenu)) return false;
        // The original movement handler can invoke server-only teleport correction on collision.
        // Audit the complete swept body before effects rather than grant teleport permission or
        // discover this branch after moving the player. Obstructed moves retain their full serial handler.
        if (RegionPlayerPacketPlan.preparesMovement(player) && !level.noCollision(player, body)) return false;
        // Every column beneath the standing box has a full inert support cube. A zero-input,
        // zero-horizontal-velocity player with nonpositive Y velocity cannot leave this box.
        if (phase == RegionPlayerTickCoordinator.Phase.CONNECTION
                || phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY) {
            if (Math.abs(body.minY - Math.rint(body.minY)) > 1e-7) return false;
            for (int x = (int) Math.floor(body.minX); x <= Math.floor(body.maxX); x++) for (int z = (int) Math.floor(body.minZ); z <= Math.floor(body.maxZ); z++) {
                if (level.getBlockState(new BlockPos(x, (int) body.minY - 1, z)).isAir()) return false;
            }
        }
        return true;
    }

    private static final java.util.function.Predicate<BlockState> UNSAFE_PLAYER_BLOCK = RegionPlayerSpatialPlan::unsafeBlock;

    private static boolean allInertPalettes(Long2ObjectMap<LevelChunk> chunks, BlockPos min, BlockPos max) {
        return allInertPalettes(chunks, min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    private static boolean allInertPalettes(Long2ObjectMap<LevelChunk> chunks, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int x = SectionPos.blockToSectionCoord(minX); x <= SectionPos.blockToSectionCoord(maxX); x++) {
            for (int z = SectionPos.blockToSectionCoord(minZ); z <= SectionPos.blockToSectionCoord(maxZ); z++) {
                LevelChunk chunk = chunks.get(ChunkPos.asLong(x, z));
                if (chunk == null || chunk.getClass() != LevelChunk.class) return false;
                var sections = chunk.getSections();
                if (sections == null || chunk.forgiaHasBlockEntityData()) return false;
                int first = chunk.getSectionIndex(minY), last = chunk.getSectionIndex(maxY);
                if (first < 0 || last >= sections.length) return false;
                for (int i = first; i <= last; i++) {
                    var section = sections[i];
                    if (section.getClass() != LevelChunkSection.class || !section.getStates().forgiaPaletteExcludes(UNSAFE_PLAYER_BLOCK)) return false;
                }
            }
        }
        return true;
    }

    private static boolean knownBlockCells(ServerPlayer player, Long2ObjectMap<LevelChunk> chunks, BlockPos min, BlockPos max, boolean standardLevel, boolean customMenu) {
        LevelChunk chunk = null;
        LevelChunkSection[] sections = null;
        Set<BlockPos> blockEntities = Set.of();
        long chunkKey = Long.MIN_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            long key = ChunkPos.asLong(pos);
            if (chunk == null || key != chunkKey) {
                chunk = chunks.get(key);
                if (chunk == null) return false;
                chunkKey = key;
                sections = standardLevel && chunk.getClass() == LevelChunk.class ? chunk.getSections() : null;
                if (sections != null) blockEntities = chunk.getBlockEntitiesPos(); // Includes pending NBT.
            }
            if (customMenu && RegionPlayerContainerPlan.knownChestAt(player, pos)) continue;
            int sectionIndex = sections == null ? -1 : chunk.getSectionIndex(pos.getY());
            if (sectionIndex >= 0 && sectionIndex < sections.length && sections[sectionIndex].getClass() == LevelChunkSection.class) {
                // Same coordinates, empty-section rule and palette as the exact vanilla chunk
                // accessors. The publisher sees one quiescent world; no worker guard is bypassed.
                var section = sections[sectionIndex];
                var state = section.hasOnlyAir() ? Blocks.AIR.defaultBlockState() : section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                if (!inertBlock(state) || !state.getFluidState().isEmpty()) return false;
                if (blockEntities.contains(pos) && chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE) != null) return false;
            } else {
                var level = player.serverLevel();
                var state = standardLevel ? chunk.getBlockState(pos) : level.getBlockState(pos);
                if (!inertBlock(state)) return false;
                if (standardLevel) {
                    if (!chunk.getFluidState(pos).isEmpty()) return false;
                    if (chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE) != null) return false;
                } else if (!level.getFluidState(pos).isEmpty() || level.getBlockEntity(pos) != null) return false;
            }
        }
        return true;
    }

    private static boolean unsafeBlock(BlockState state) {
        return !inertBlock(state) || !state.getFluidState().isEmpty();
    }

    static boolean isInertBlock(BlockState state) {
        return inertBlock(state);
    }

    private static boolean inertBlock(BlockState state) {
        return state.is(Blocks.AIR) || state.is(Blocks.STONE) || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.BEDROCK);
    }

    static boolean liveIdentity(@Nullable EntityIdentity identity, Entity entity) {
        return identity != null && (identity.state() == EntityIdentity.State.ADMITTED || identity.state() == EntityIdentity.State.TRACKED)
                && identity.uuid().equals(entity.getUUID()) && identity.entityId() == entity.getId();
    }

    private static Refusal intrinsicRefusal(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase, boolean checkInventory) {
        return intrinsicRefusal(player, phase, checkInventory, true);
    }

    private static Refusal intrinsicRefusal(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase, boolean checkInventory, boolean checkMenu) {
        if (player.getClass() != ServerPlayer.class || !RegionPlayerFootprint.finite(player.getBoundingBox())
                || player.getBoundingBox().getXsize() > 4 || player.getBoundingBox().getYsize() > 4 || player.getBoundingBox().getZsize() > 4)
            return Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN;
        if (player.isRemoved() || !player.isAlive() || player.getHealth() < player.getMaxHealth() || player.getMaxHealth() < 20
                || player.fallDistance != 0 || player.hurtTime != 0 || player.deathTime != 0
                || player.isChangingDimension() || player.portalProcess != null
                || player instanceof net.neoforged.neoforge.common.util.FakePlayer || player.getCamera() != player)
            return Refusal.LIFECYCLE;
        if (player.isPassenger() || !player.getPassengers().isEmpty()) return Refusal.RIDING;
        if (player.gameMode.forgiaHasActiveBlockDestruction()) return Refusal.ACTIVE_BLOCK_BREAK;
        if (checkMenu && (phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY
                ? player.containerMenu != player.inventoryMenu
                : !RegionPlayerContainerPlan.supportedMenu(player))) return Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN;
        if (player.getPose() != Pose.STANDING || player.isSleeping() || player.isSpectator() || player.isFallFlying()
                || player.isAutoSpinAttack() || player.getAbilities().flying || !player.getActiveEffects().isEmpty()
                || player.isOnFire() || player.getTicksFrozen() != 0 || !player.getShoulderEntityLeft().isEmpty() || !player.getShoulderEntityRight().isEmpty())
            return Refusal.MOVEMENT_UNBOUNDED;
        // The exact matching confirmation only reapplies the already-published destination; it
        // does not run physics. Vanilla teleport may have cleared onGround before the client ack.
        if ((phase == RegionPlayerTickCoordinator.Phase.CONNECTION
                || phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY)
                && !player.forgiaStationarySpatialInputs()
                && !RegionPlayerPacketPlan.permitsAwaitingTeleport(player))
            return Refusal.MOVEMENT_UNBOUNDED;
        // Exact default plain materials have a no-op vanilla inventoryTick. Equipment stays empty;
        // candidate capacity, original menu/criteria and callback admission remain separate gates.
        if (checkInventory && !(phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY
                ? RegionPlayerInventoryPlan.supportedConnectionBodyInventory(player)
                : RegionPlayerInventoryPlan.supportedInventory(player)))
            return Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN;
        return Refusal.NONE;
    }
}
