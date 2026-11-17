/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
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

    public record Capture(@Nullable RegionPlayerSpatialPlan plan, Refusal refusal) {
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
    private final Map<Long, LevelChunk> chunks;
    private final AABB initialBox;

    private RegionPlayerSpatialPlan(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            RegionPlayerFootprint footprint, Map<Entity, EntityIdentity> members, Map<Long, LevelChunk> chunks) {
        this.engine = engine;
        this.player = player;
        this.phase = phase;
        this.footprint = footprint;
        this.members = Collections.unmodifiableMap(new IdentityHashMap<>(members));
        this.chunks = Map.copyOf(chunks);
        this.tick = Integer.toUnsignedLong(player.server.getTickCount());
        this.initialBox = player.getBoundingBox();
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

    Map<Long, LevelChunk> chunks() {
        return this.chunks;
    }

    /** Recheck before EACH unstarted original call; a previous member can change this player's velocity. */
    boolean revalidate() {
        if (!this.player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Spatial revalidation requires the publishing thread");
        if (!this.geometryCurrent() || this.player.server.getPlayerList().getPlayer(this.player.getUUID()) != this.player) return false;
        EntityIdentityLedger ledger = this.footprint.level().entityIdentityLedger();
        for (var entry : this.members.entrySet()) {
            Entity entity = entry.getKey();
            RegionState owner = this.engine.regionFor(entity);
            if (!entry.getValue().equals(ledger.liveIdentity(entity)) || owner == null
                    || !owner.coordinate().equals(this.engine.regionAt(this.footprint.level(), entity.blockPosition()))
                    || !this.footprint.regions().contains(owner.coordinate()))
                return false;
        }
        int indexed = 0;
        for (RegionCoordinate coordinate : this.footprint.regions()) {
            RegionState region = this.engine.regionStateFor(coordinate);
            if (region == null || region.isDead()) return false;
            for (Entity entity : region.indexedEntities()) {
                if (!this.members.containsKey(entity)) return false;
                indexed++;
            }
        }
        for (var entry : this.chunks.entrySet()) {
            if (this.footprint.level().getChunkSource().getChunkNow(ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey())) != entry.getValue()) return false;
        }
        if (indexed != this.members.size() || !knownBlocks(this.footprint, this.initialBox, this.phase)) return false;
        for (Entity entity : this.footprint.level().getEntities(this.player, this.footprint.queryBounds(), target -> true)) {
            if (!this.members.containsKey(entity)) return false;
        }
        return true;
    }

    /** Local geometry only; the coordinator's lifecycle/phase admission is a separate mandatory gate. */
    boolean geometryCurrent() {
        if (this.engine.isShutDown() || this.engine.isLevelClosed(this.footprint.level())
                || this.tick != Integer.toUnsignedLong(this.player.server.getTickCount())
                || this.player.serverLevel() != this.footprint.level() || !this.initialBox.equals(this.player.getBoundingBox())
                || intrinsicRefusal(this.player, this.phase) != Refusal.NONE)
            return false;
        return this.members.entrySet().stream().allMatch(entry -> entry.getKey().level() == this.footprint.level()
                && entry.getKey().getUUID().equals(entry.getValue().uuid()) && entry.getKey().getId() == entry.getValue().entityId());
    }

    private static Capture refused(Refusal reason) {
        return new Capture(null, reason);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Spatial capture requires the original server thread");
        if (!engine.playerCriterionStorageEnabled() || !engine.isEntityIndexPublished(player.serverLevel())) return refused(Refusal.DEPENDENCIES);
        if (!engine.isQuiescentForParallelApi()) return refused(Refusal.NOT_QUIESCENT);
        Refusal intrinsic = intrinsicRefusal(player, phase);
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
        AABB query = body.inflate(1, .5, 1);
        // Base/inside-block, fluid/eyes, poses, collision/support and listener noBlocksAround.
        // No displacement is possible in this admitted intrinsic profile (verified solid support).
        AABB blocks = body.inflate(1, 1, 1).expandTowards(0, -1, 0);
        final RegionPlayerFootprint footprint;
        try {
            footprint = RegionPlayerFootprint.of(level, engine.regionLayout().regionChunkSize(), query, blocks);
        } catch (IllegalArgumentException refusal) {
            return refused(Refusal.FOOTPRINT_TOO_LARGE);
        }
        Map<Long, LevelChunk> chunks = new LinkedHashMap<>();
        Map<Entity, EntityIdentity> members = new IdentityHashMap<>();
        EntityIdentityLedger ledger = level.entityIdentityLedger();
        for (RegionCoordinate coordinate : footprint.regions()) {
            RegionState region = engine.regionStateFor(coordinate);
            if (region == null || region.isDead()) return refused(Refusal.MISSING_REGION);
            int size = footprint.regionChunks();
            for (int x = coordinate.regionX() * size; x < (coordinate.regionX() + 1) * size; x++) {
                for (int z = coordinate.regionZ() * size; z < (coordinate.regionZ() + 1) * size; z++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) return refused(Refusal.UNLOADED_CHUNK);
                    chunks.put(ChunkPos.asLong(x, z), chunk);
                }
            }
            for (Entity entity : region.indexedEntities()) {
                if (entity.level() != level || engine.regionFor(entity) != region
                        || !engine.regionAt(level, entity.blockPosition()).equals(coordinate))
                    return refused(Refusal.OWNER_PUBLICATION_PENDING);
                EntityIdentity identity = ledger.liveIdentity(entity);
                if (!liveIdentity(identity, entity)) return refused(Refusal.NO_IDENTITY);
                if (entity.isMultipartEntity()) return refused(Refusal.MULTIPART_UNBOUNDED);
                if (entity.getBoundingBox().intersects(query) && entity != player
                        && entity.getClass() != ItemEntity.class && entity.getClass() != ExperienceOrb.class && entity.getClass() != ServerPlayer.class)
                    return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
                members.put(entity, identity);
                if (members.size() > 4096) return refused(Refusal.CAPACITY);
            }
        }
        if (!members.containsKey(player)) return refused(Refusal.NO_IDENTITY);
        // Compare against the ORIGINAL complete server query before any worker starts. A physical
        // bucket member absent from the canonical region/identity publication is not silently omitted.
        for (Entity target : level.getEntities(player, query, entity -> true)) {
            if (!members.containsKey(target)) return refused(Refusal.OWNER_PUBLICATION_PENDING);
        }
        if (!knownBlocks(footprint, body, phase)) return refused(Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN);
        return new Capture(new RegionPlayerSpatialPlan(engine, player, phase, footprint, members, chunks), Refusal.NONE);
    }

    private static boolean knownBlocks(RegionPlayerFootprint footprint, AABB body, RegionPlayerTickCoordinator.Phase phase) {
        ServerLevel level = footprint.level();
        AABB blocks = footprint.blockBounds();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(blocks.minX, blocks.minY, blocks.minZ), BlockPos.containing(blocks.maxX, blocks.maxY, blocks.maxZ))) {
            var state = level.getBlockState(pos);
            if (!(state.is(Blocks.AIR) || state.is(Blocks.STONE) || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.BEDROCK)))
                return false;
            if (!level.getFluidState(pos).isEmpty() || level.getBlockEntity(pos) != null) return false;
        }
        // Every column beneath the standing box has a full inert support cube. A zero-input,
        // zero-horizontal-velocity player with nonpositive Y velocity cannot leave this box.
        if (phase == RegionPlayerTickCoordinator.Phase.CONNECTION) {
            if (Math.abs(body.minY - Math.rint(body.minY)) > 1e-7) return false;
            for (int x = (int) Math.floor(body.minX); x <= Math.floor(body.maxX); x++) for (int z = (int) Math.floor(body.minZ); z <= Math.floor(body.maxZ); z++) {
                if (level.getBlockState(new BlockPos(x, (int) body.minY - 1, z)).isAir()) return false;
            }
        }
        return true;
    }

    static boolean liveIdentity(@Nullable EntityIdentity identity, Entity entity) {
        return identity != null && (identity.state() == EntityIdentity.State.ADMITTED || identity.state() == EntityIdentity.State.TRACKED)
                && identity.uuid().equals(entity.getUUID()) && identity.entityId() == entity.getId();
    }

    private static Refusal intrinsicRefusal(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
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
        if (player.containerMenu != player.inventoryMenu) return Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN;
        if (player.getPose() != Pose.STANDING || player.isSleeping() || player.isSpectator() || player.isFallFlying()
                || player.isAutoSpinAttack() || player.getAbilities().flying || !player.getActiveEffects().isEmpty()
                || player.isOnFire() || player.getTicksFrozen() != 0 || !player.getShoulderEntityLeft().isEmpty() || !player.getShoulderEntityRight().isEmpty())
            return Refusal.MOVEMENT_UNBOUNDED;
        if (phase == RegionPlayerTickCoordinator.Phase.CONNECTION && !player.forgiaStationarySpatialInputs()) return Refusal.MOVEMENT_UNBOUNDED;
        // Item/effect/custom callbacks are NOT proved by geometry. Empty inventory closes only
        // inventoryTick's intrinsic reach; the separate callback/global-state gate remains mandatory.
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (!player.getInventory().getItem(i).isEmpty()) return Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN;
        return Refusal.NONE;
    }
}
