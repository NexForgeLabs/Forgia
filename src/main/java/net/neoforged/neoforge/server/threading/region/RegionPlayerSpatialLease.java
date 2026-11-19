/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * A spatial permission child of the ORIGINAL published batch. No executor, identity ledger or pool.
 * Reservation is on the publishing thread; all regions use RegionLeasePack and original releases.
 * A single native worker may enter it, and close cannot release it before that worker physically exits.
 * Callback/global-state/lifecycle admission is deliberately separate and still closed in P1.
 */
@ApiStatus.Internal
public final class RegionPlayerSpatialLease implements RegionOwnershipLeaseGroup, AutoCloseable {
    private static final ThreadLocal<RegionPlayerSpatialLease> CURRENT = new ThreadLocal<>();
    private static volatile int fallbackDepth;
    @Nullable
    private RegionThreadingEngine engine;
    @Nullable
    private RegionTickBatch batch;
    @Nullable
    private RegionOwnershipLeaseGroup effects;
    @Nullable
    private Thread publisher;
    @Nullable
    private Thread worker;
    private List<RegionPlayerSpatialPlan> plans;
    @Nullable
    private RegionPlayerSpatialPlan singlePlan;
    private List<RegionState> regions;
    @Nullable
    private RegionState singleRegion;
    private List<RegionCoordinate> coordinates;
    private Map<Entity, EntityIdentity> members;
    @Nullable
    private Entity singleMember;
    @Nullable
    private EntityIdentity singleMemberIdentity;
    private Long2ObjectMap<LevelChunk> chunks;
    private boolean closed;
    private boolean isolatedPrefix;
    private List<RegionPlayerStatePlan> statePlans = List.of();
    @Nullable
    private RegionPlayerStatePlan singleStatePlan;
    private List<Object> claimedResources = List.of();

    @Nullable
    private static RegionPlayerSpatialLease currentSlot() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            return worker.playerSpatialLease;
        }
        return fallbackDepth != 0 ? CURRENT.get() : null;
    }

    private static synchronized void incrementFallback() {
        ++fallbackDepth;
    }

    private static synchronized void decrementFallback() {
        --fallbackDepth;
    }

    /** Bind on the original publisher BEFORE dispatch. Aliased local objects refuse the whole group. */
    public RegionPlayerStatePlan.Refusal bindPlayerState(List<RegionPlayerStatePlan> plans) {
        return this.bindPlayerState(plans, false, false, false);
    }

    private RegionPlayerStatePlan.Refusal bindPlayerState(List<RegionPlayerStatePlan> plans, boolean freshSpatial, boolean sameSealedPrefix, boolean prefixClaimsHeld) {
        if (Thread.currentThread() != this.publisher || this.worker != null || this.closed || !this.statePlans.isEmpty())
            throw new IllegalStateException("State binding requires unstarted publisher-owned spatial lease");
        if (plans.isEmpty() || plans.size() != this.plans.size()) return RegionPlayerStatePlan.Refusal.STALE_STATE;
        for (int i = 0; i < plans.size(); i++) {
            var plan = plans.get(i);
            if (!plan.belongsTo(this.engine) || !this.plans.contains(plan.spatial())
                    || (!sameSealedPrefix && !(freshSpatial ? plan.revalidateLocal() : plan.revalidate()))) return RegionPlayerStatePlan.Refusal.STALE_STATE;
            for (int j = 0; j < i; j++) if (plans.get(j).spatial() == plan.spatial()) return RegionPlayerStatePlan.Refusal.STALE_STATE;
        }
        if (!prefixClaimsHeld) {
            List<Object> claimed = this.engine.claimPlayerState(this, plans);
            if (claimed == null) return RegionPlayerStatePlan.Refusal.SHARED_PLAYER_RESOURCE;
            this.claimedResources = claimed;
        }
        this.statePlans = List.copyOf(plans);
        this.singleStatePlan = this.statePlans.size() == 1 ? this.statePlans.getFirst() : null;
        this.isolatedPrefix = prefixClaimsHeld;
        if (sameSealedPrefix && plans.size() == 1) plans.getFirst().completeSealedLocalCheck();
        return RegionPlayerStatePlan.Refusal.NONE;
    }

    boolean hasStatePlan(RegionPlayerStatePlan plan) {
        if (this.closed) return false;
        if (this.singleStatePlan != null) return this.singleStatePlan == plan;
        return this.statePlans.contains(plan);
    }

    public RegionPlayerStateScope openPlayerState(RegionPlayerStatePlan plan) {
        return RegionPlayerStateScope.open(this, plan);
    }

    RegionPlayerStateScope openPlayerState(RegionPlayerStatePlan plan, boolean laneExtensionsVerified) {
        return RegionPlayerStateScope.open(this, plan, laneExtensionsVerified);
    }

    private RegionPlayerSpatialLease(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects,
            List<RegionPlayerSpatialPlan> plans, List<RegionState> regions) {
        this.engine = engine;
        this.batch = batch;
        this.effects = effects;
        this.publisher = Thread.currentThread();
        this.plans = List.copyOf(plans);
        this.singlePlan = this.plans.size() == 1 ? this.plans.getFirst() : null;
        this.regions = regions;
        this.singleRegion = regions.size() == 1 ? regions.getFirst() : null;
        this.coordinates = regions.size() == 1 ? List.of(regions.getFirst().coordinate()) : regions.stream().map(RegionState::coordinate).toList();
        if (plans.size() == 1) {
            var plan = plans.getFirst();
            this.members = plan.members();
            this.chunks = plan.chunks();
        } else {
            this.members = new IdentityHashMap<>();
            this.chunks = new Long2ObjectOpenHashMap<>();
            for (RegionPlayerSpatialPlan plan : plans) {
                this.members.putAll(plan.members());
                this.chunks.putAll(plan.chunks());
            }
        }
        if (this.members.size() == 1) {
            var entry = this.members.entrySet().iterator().next();
            this.singleMember = entry.getKey();
            this.singleMemberIdentity = entry.getValue();
        }
    }

    @Nullable
    static RegionPlayerSpatialLease acquire(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects, List<RegionPlayerSpatialPlan> plans) {
        return acquire(engine, batch, effects, plans, false);
    }

    @Nullable
    private static RegionPlayerSpatialLease acquire(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects,
            List<RegionPlayerSpatialPlan> plans, boolean sameSealedPrefix) {
        if (plans.isEmpty()) return null;
        java.util.Collection<RegionCoordinate> coordinates = plans.getFirst().footprint().regions();
        if (plans.size() == 1) {
            var plan = plans.getFirst();
            if (!plan.belongsTo(engine) || plan.footprint().level() != batch.level()) return null;
        } else {
            Set<RegionCoordinate> union = new LinkedHashSet<>(coordinates);
            for (RegionPlayerSpatialPlan plan : plans) {
                if (!plan.belongsTo(engine) || plan.footprint().level() != batch.level() || plan.phase() != plans.getFirst().phase()) return null;
                union.addAll(plan.footprint().regions());
            }
            coordinates = union;
        }
        if (coordinates.size() > RegionPlayerFootprint.MAX_REGIONS) return null;
        List<RegionState> states;
        if (plans.size() == 1 && plans.getFirst().singleRegionState != null) {
            RegionState state = plans.getFirst().singleRegionState;
            if (state.isDead()) return null;
            states = List.of(state);
        } else {
            states = new ArrayList<>(coordinates.size());
            for (RegionCoordinate coordinate : coordinates) {
                RegionState state = engine.regionStateFor(coordinate);
                if (state == null || state.isDead()) return null;
                states.add(state);
            }
        }
        batch.retainPlayerSpatialReservation();
        List<RegionState> leased = null;
        boolean transferred = false;
        try {
            if (states.size() == 1) {
                if (!states.getFirst().tryMarkRunning()) return null;
                leased = states;
            } else {
                leased = RegionLeasePack.tryAcquireAll(states, engine::releasePlayerSpatialRegion);
                if (leased == null) return null;
            }
            for (RegionPlayerSpatialPlan plan : plans) if (!plan.revalidate(sameSealedPrefix)) return null;
            RegionPlayerSpatialLease result = new RegionPlayerSpatialLease(engine, batch, effects, plans, leased);
            leased = null; // ownership moves to result, never a second lease source
            transferred = true;
            return result;
        } finally {
            if (leased != null) for (RegionState state : leased) engine.releasePlayerSpatialRegion(state);
            if (!transferred) batch.releasePlayerSpatialReservation();
        }
    }

    @Nullable
    static RegionPlayerSpatialLease acquireBound(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects,
            List<RegionPlayerStatePlan> plans, boolean sameSealedPrefix, boolean prefixClaimsHeld) {
        var spatialPlans = plans.size() == 1 ? List.of(plans.getFirst().spatial()) : plans.stream().map(RegionPlayerStatePlan::spatial).toList();
        RegionPlayerSpatialLease lease = acquire(engine, batch, effects, spatialPlans, sameSealedPrefix);
        if (lease == null) return null;
        boolean bound = false;
        try {
            // No original body, callback or executor runs between the post-acquire spatial check
            // and local binding. Public, separately-held leases still perform full revalidation.
            bound = lease.bindPlayerState(plans, true, sameSealedPrefix, prefixClaimsHeld) == RegionPlayerStatePlan.Refusal.NONE;
            return bound ? lease : null;
        } finally {
            if (!bound) lease.close();
        }
    }

    /** Does not start or count a player tick; the coordinator's actual phase ticket is still required. */
    public synchronized Scope open() {
        final Thread current = Thread.currentThread();
        if (this.closed || this.worker != null || !RegionWorkerScope.isNativeWorkerThread()
                || currentSlot() != null || RegionTickBatch.current() != this.batch)
            throw new IllegalStateException("Player spatial scope requires its original active batch and one native worker");
        int entered = 0;
        try {
            if (this.singleRegion != null) {
                this.singleRegion.beginOwnership();
                entered = 1;
            } else {
                for (RegionState state : this.regions) {
                    state.beginOwnership();
                    entered++;
                }
            }
            this.worker = current;
            RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(
                    (this.singleRegion != null ? this.singleRegion : this.regions.getFirst()).coordinate(), this);
            if (current instanceof RegionWorkerThread wt) {
                wt.playerSpatialLease = this;
            } else {
                incrementFallback();
                CURRENT.set(this);
            }
            return new Scope(this, ownership, current);
        } catch (Throwable failure) {
            for (int i = entered - 1; i >= 0; i--) this.regions.get(i).endOwnership();
            this.worker = null;
            throw failure;
        }
    }

    public static final class Scope implements AutoCloseable {
        @Nullable
        private RegionPlayerSpatialLease lease;
        private final RegionEntityOwnershipScope ownership;
        private final Thread owner;

        private Scope(RegionPlayerSpatialLease lease, RegionEntityOwnershipScope ownership, Thread owner) {
            this.lease = lease;
            this.ownership = ownership;
            this.owner = owner;
        }

        @Override
        public void close() {
            if (this.lease == null) return;
            final Thread currentThread = Thread.currentThread();
            if (currentThread != this.owner) throw new IllegalStateException("Foreign spatial scope close before worker return");
            RegionPlayerSpatialLease current = this.lease;
            if (RegionPlayerStateScope.hasThreadBinding()) throw new IllegalStateException("Player local-state scope has not returned");
            if (!RegionEntityOwnershipScope.isCurrentGroup(current)) throw new IllegalStateException("Player spatial scope must close in stack order");
            this.ownership.close();
            if (currentThread instanceof RegionWorkerThread wt) {
                wt.playerSpatialLease = null;
            } else {
                CURRENT.remove();
                decrementFallback();
            }
            synchronized (current) {
                if (current.singleRegion != null) {
                    current.singleRegion.endOwnership();
                } else {
                    for (int i = current.regions.size() - 1; i >= 0; i--) current.regions.get(i).endOwnership();
                }
                current.worker = null;
            }
            this.lease = null;
        }
    }

    @Nullable
    public static RegionPlayerSpatialLease current() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread wt) {
            if (!wt.inWorkerScope) return null;
            RegionPlayerSpatialLease lease = wt.playerSpatialLease;
            if (lease == null || lease.closed || lease.worker != wt) return null;
            RegionEntityOwnershipScope scope = wt.entityOwnershipScope;
            return scope != null && scope.isGroupAndLease(lease, wt.entityLeaseId) ? lease : null;
        }
        var lease = RegionEntityOwnershipScope.currentPlayerLease();
        return lease != null && !lease.closed && lease.worker == current
                && RegionWorkerScope.isNativeWorkerThread() ? lease : null;
    }

    ServerLevel level() {
        if (this.batch == null) throw new IllegalStateException("Closed player lease");
        return this.batch.level();
    }

    boolean ownsRegion(ServerLevel level, int x, int z) {
        if (this.singleRegion != null) {
            var coordinate = this.singleRegion.coordinate();
            return coordinate.level() == level && coordinate.regionX() == x && coordinate.regionZ() == z
                    && this.singleRegion.isOwnedByCurrentThread();
        }
        for (RegionState state : this.regions) {
            var coordinate = state.coordinate();
            if (coordinate.level() == level && coordinate.regionX() == x && coordinate.regionZ() == z) return state.isOwnedByCurrentThread();
        }
        return false;
    }

    /** Before every unstarted call in a conflict group; geometry can change after the preceding call. */
    boolean geometryCurrent(RegionPlayerSpatialPlan plan) {
        return this.geometryCurrent(plan, false);
    }

    boolean geometryCurrent(RegionPlayerSpatialPlan plan, boolean laneValidated) {
        if (this.singlePlan != null) return this.singlePlan == plan && plan.geometryCurrentForLane(laneValidated);
        return this.plans.contains(plan) && plan.geometryCurrentForLane(laneValidated);
    }

    boolean excludesOnlyMember(ServerLevel level, @Nullable Entity excluded, AABB box) {
        if (!this.isolatedPrefix || excluded == null || this.plans.size() != 1 || this.members.size() != 1 || this.plans.getFirst().isLocalEntity()) return false;
        var plan = this.plans.getFirst();
        // The publisher checked the complete physical query before leasing this single-player
        // region. A disjoint group cannot publish an additional entity into it while we own it.
        return excluded == plan.player() && plan.footprint().containsQuery(level, box);
    }

    List<RegionCoordinate> queryRegions(ServerLevel level, AABB box) {
        if (current() == this) {
            for (int i = 0; i < this.plans.size(); i++) {
                if (!this.plans.get(i).isLocalEntity() && this.plans.get(i).footprint().containsQuery(level, box)) return this.coordinates;
            }
        }
        throw new IllegalStateException("Player query expands its complete captured footprint");
    }

    /** Raw section access must cover exactly the complete set; a subset must never become a clamp. */
    public void requireSections(ServerLevel level, List<RegionCoordinate> coordinates, AABB box) {
        List<RegionCoordinate> complete = this.queryRegions(level, box);
        if (coordinates != complete && (coordinates.size() != complete.size()
                || (!coordinates.equals(complete) && !new java.util.HashSet<>(coordinates).equals(new java.util.HashSet<>(complete)))))
            throw new IllegalStateException("Player section accessor omitted part of its complete region set");
    }

    public void requireBlock(ServerLevel level, BlockPos pos) {
        this.requireBlock(level, pos.getX(), pos.getY(), pos.getZ());
    }

    public void requireBlock(ServerLevel level, int x, int y, int z) {
        if (current() != this) throw new IllegalStateException("No active player spatial lease");
        this.requireBlockBounds(level, x, y, z);
    }

    /** Validate both the current lease and its scalar footprint with one scope lookup. */
    public static boolean checkCapturedBlock(LevelChunk chunk, int x, int y, int z) {
        var lease = current();
        if (lease == null) return false;
        if (lease.batch == null || chunk.getLevel() != lease.batch.level()
                || lease.chunks.get(chunk.getPos().toLong()) != chunk) {
            throw new IllegalStateException("Player accessed a chunk outside its lease");
        }
        lease.requireBlockBounds(lease.batch.level(), x, y, z);
        return true;
    }

    private void requireBlockBounds(ServerLevel level, int x, int y, int z) {
        if (this.singlePlan != null) {
            if (!this.singlePlan.isLocalEntity() && this.singlePlan.footprint().containsBlock(level, x, y, z)) return;
        } else {
            for (int i = 0; i < this.plans.size(); i++) {
                if (!this.plans.get(i).isLocalEntity() && this.plans.get(i).footprint().containsBlock(level, x, y, z)) return;
            }
        }
        throw new IllegalStateException("Player block access expands its complete captured footprint: " + new BlockPos(x, y, z));
    }

    public boolean ownsCapturedChunk(LevelChunk chunk) {
        if (current() != this || this.batch == null || chunk.getLevel() != this.batch.level()) return false;
        return this.chunks.get(chunk.getPos().toLong()) == chunk;
    }

    public LevelChunk chunk(ServerLevel level, int x, int z) {
        if (current() != this || this.batch == null || this.batch.level() != level || !this.ownsRegion(level,
                Math.floorDiv(x, this.plans.getFirst().footprint().regionChunks()), Math.floorDiv(z, this.plans.getFirst().footprint().regionChunks())))
            throw new IllegalStateException("Player chunk access outside complete lease");
        LevelChunk chunk = this.chunks.get(ChunkPos.asLong(x, z));
        if (chunk == null) throw new IllegalStateException("Player chunk was not loaded at capture");
        return chunk;
    }

    boolean alreadyLoadedAtUnchangedPosition(Entity entity, double x, double y, double z) {
        if (entity.getClass() != net.minecraft.server.level.ServerPlayer.class || entity.getX() != x || entity.getY() != y || entity.getZ() != z) {
            return false;
        }
        for (RegionPlayerSpatialPlan plan : this.plans) {
            if (plan.player() == entity && plan.inspectedVanillaTerrain()
                    && RegionPlayerFootprint.contains(plan.footprint().blockBounds(), entity.getBoundingBox())) {
                LevelChunk chunk = this.chunks.get(entity.chunkPosition().toLong());
                return chunk != null && chunk.getClass() == LevelChunk.class && chunk.isLoaded();
            }
        }
        return false;
    }

    public void requirePosition(Entity entity, double x, double y, double z) {
        this.requireTarget(entity, "position mutation");
        AABB destination = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        if (!RegionPlayerFootprint.finite(destination) || this.plans.stream().noneMatch(plan -> !plan.isLocalEntity() && RegionPlayerFootprint.contains(plan.footprint().blockBounds(), destination)))
            throw new IllegalStateException("Player position mutation expands its captured footprint");
    }

    public void requireTarget(Entity target, String operation) {
        if (!this.owns(target)) throw new IllegalStateException("Player target outside captured identity/region set: " + operation);
    }

    @Nullable
    Entity lookup(ServerLevel level, int id, @Nullable UUID uuid) {
        if (this.batch == null || this.batch.level() != level) throw new IllegalStateException("Player lookup crossed level");
        for (Entity entity : this.members.keySet()) {
            if (uuid != null ? uuid.equals(entity.getUUID()) : entity.getId() == id) {
                this.requireTarget(entity, "entity lookup");
                return entity.isRemoved() ? null : entity;
            }
        }
        throw new IllegalStateException("Unbounded player identity lookup; cannot report a foreign live entity as absent");
    }

    @Override
    public boolean owns(Entity entity) {
        EntityIdentity identity = this.singleMember != null
                ? (entity == this.singleMember ? this.singleMemberIdentity : null)
                : this.members.get(entity);
        return current() == this && !this.closed && identity != null && this.batch != null && entity.level() == this.batch.level()
                && identity.uuid().equals(entity.getUUID()) && identity.entityId() == entity.getId()
                && this.ownsRegion(this.batch.level(), Math.floorDiv(SectionPos.blockToSectionCoord(entity.blockPosition().getX()), (this.singlePlan != null ? this.singlePlan : this.plans.getFirst()).footprint().regionChunks()),
                        Math.floorDiv(SectionPos.blockToSectionCoord(entity.blockPosition().getZ()), (this.singlePlan != null ? this.singlePlan : this.plans.getFirst()).footprint().regionChunks()));
    }

    @Override
    public boolean acceptsDeferredRemovals() {
        return !this.closed && this.effects != null && this.effects.acceptsDeferredRemovals();
    }

    @Override
    public boolean acceptsDeferredSectionUpdates() {
        return !this.closed && this.effects != null && this.effects.acceptsDeferredSectionUpdates();
    }

    @Override
    public boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        return this.owns(entity) && this.effects != null && this.effects.deferRemoval(entity, callback, reason);
    }

    @Override
    public boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long targetSection) {
        if (!this.owns(entity) || this.effects == null || this.batch == null) return false;
        int size = this.plans.getFirst().footprint().regionChunks();
        if (!this.ownsRegion(this.batch.level(), Math.floorDiv(SectionPos.x(targetSection), size), Math.floorDiv(SectionPos.z(targetSection), size)))
            throw new IllegalStateException("Player section publication expands its complete footprint");
        return this.effects.deferSectionUpdate(entity, callback, targetSection);
    }

    // Original player spawn/portal results cannot be turned into asynchronous acceptance.
    @Override
    public boolean deferAddition(Entity entity, List<Entity> graph) {
        return false;
    }

    @Override
    public boolean deferPortalTransition(Entity entity) {
        return false;
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        if (Thread.currentThread() != this.publisher || this.worker != null || !this.batch.isGroupsComplete()) throw new IllegalStateException("Spatial lease requires physical worker exit before publishing-thread release");
        this.closed = true;
        for (RegionState state : this.regions) this.engine.releasePlayerSpatialRegion(state);
        this.engine.releasePlayerState(this, this.claimedResources);
        this.claimedResources = List.of();
        this.statePlans = List.of();
        this.singleStatePlan = null;
        this.batch.releasePlayerSpatialReservation();
        this.regions = List.of();
        this.singleRegion = null;
        this.coordinates = List.of();
        this.plans = List.of();
        this.singlePlan = null;
        this.members = Map.of();
        this.singleMember = null;
        this.singleMemberIdentity = null;
        this.chunks = Long2ObjectMaps.emptyMap();
        this.effects = null;
        this.engine = null;
        this.batch = null;
        this.publisher = null;
    }
}
