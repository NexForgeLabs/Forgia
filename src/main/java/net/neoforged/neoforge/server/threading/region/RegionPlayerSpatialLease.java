/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
    private List<RegionState> regions;
    private Map<Entity, EntityIdentity> members;
    private Map<Long, LevelChunk> chunks;
    private boolean closed;
    private List<RegionPlayerStatePlan> statePlans = List.of();

    /** Bind on the original publisher BEFORE dispatch. Aliased local objects refuse the whole group. */
    public RegionPlayerStatePlan.Refusal bindPlayerState(List<RegionPlayerStatePlan> plans) {
        if (Thread.currentThread() != this.publisher || this.worker != null || this.closed || !this.statePlans.isEmpty())
            throw new IllegalStateException("State binding requires unstarted publisher-owned spatial lease");
        if (plans.isEmpty() || plans.size() != this.plans.size()
                || plans.stream().map(RegionPlayerStatePlan::spatial).distinct().count() != plans.size())
            return RegionPlayerStatePlan.Refusal.STALE_STATE;
        for (var plan : plans) if (!this.plans.contains(plan.spatial()) || !plan.revalidate()) return RegionPlayerStatePlan.Refusal.STALE_STATE;
        if (!this.engine.claimPlayerState(this, plans)) return RegionPlayerStatePlan.Refusal.SHARED_PLAYER_RESOURCE;
        this.statePlans = List.copyOf(plans);
        return RegionPlayerStatePlan.Refusal.NONE;
    }

    boolean hasStatePlan(RegionPlayerStatePlan plan) {
        return !this.closed && this.statePlans.contains(plan);
    }

    public RegionPlayerStateScope openPlayerState(RegionPlayerStatePlan plan) {
        return RegionPlayerStateScope.open(this, plan);
    }

    private RegionPlayerSpatialLease(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects,
            List<RegionPlayerSpatialPlan> plans, List<RegionState> regions) {
        this.engine = engine;
        this.batch = batch;
        this.effects = effects;
        this.publisher = Thread.currentThread();
        this.plans = List.copyOf(plans);
        this.regions = regions;
        this.members = new IdentityHashMap<>();
        this.chunks = new LinkedHashMap<>();
        for (RegionPlayerSpatialPlan plan : plans) {
            this.members.putAll(plan.members());
            this.chunks.putAll(plan.chunks());
        }
    }

    @Nullable
    static RegionPlayerSpatialLease acquire(RegionThreadingEngine engine, RegionTickBatch batch, RegionOwnershipLeaseGroup effects, List<RegionPlayerSpatialPlan> plans) {
        if (plans.isEmpty()) return null;
        Set<RegionCoordinate> coordinates = new LinkedHashSet<>();
        for (RegionPlayerSpatialPlan plan : plans) {
            if (!plan.belongsTo(engine) || plan.footprint().level() != batch.level() || plan.phase() != plans.getFirst().phase() || !plan.revalidate()) return null;
            coordinates.addAll(plan.footprint().regions());
        }
        if (coordinates.size() > RegionPlayerFootprint.MAX_REGIONS) return null;
        List<RegionState> states = new ArrayList<>();
        for (RegionCoordinate coordinate : coordinates) {
            RegionState state = engine.regionStateFor(coordinate);
            if (state == null || state.isDead()) return null;
            states.add(state);
        }
        batch.retainPlayerSpatialReservation();
        List<RegionState> leased = null;
        boolean transferred = false;
        try {
            leased = RegionLeasePack.tryAcquireAll(states, engine::releasePlayerSpatialRegion);
            if (leased == null) return null;
            for (RegionPlayerSpatialPlan plan : plans) if (!plan.revalidate()) return null;
            RegionPlayerSpatialLease result = new RegionPlayerSpatialLease(engine, batch, effects, plans, leased);
            leased = null; // ownership moves to result, never a second lease source
            transferred = true;
            return result;
        } finally {
            if (leased != null) for (RegionState state : leased) engine.releasePlayerSpatialRegion(state);
            if (!transferred) batch.releasePlayerSpatialReservation();
        }
    }

    /** Does not start or count a player tick; the coordinator's actual phase ticket is still required. */
    public synchronized Scope open() {
        if (this.closed || this.worker != null || !RegionWorkerScope.isNativeWorkerThread()
                || CURRENT.get() != null || RegionTickBatch.current() != this.batch)
            throw new IllegalStateException("Player spatial scope requires its original active batch and one native worker");
        int entered = 0;
        try {
            for (RegionState state : this.regions) {
                state.beginOwnership();
                entered++;
            }
            this.worker = Thread.currentThread();
            RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(this.regions.getFirst().coordinate(), this);
            CURRENT.set(this);
            return new Scope(this, ownership, Thread.currentThread());
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
            if (Thread.currentThread() != this.owner) throw new IllegalStateException("Foreign spatial scope close before worker return");
            RegionPlayerSpatialLease current = this.lease;
            if (RegionPlayerStateScope.hasThreadBinding()) throw new IllegalStateException("Player local-state scope has not returned");
            if (!RegionEntityOwnershipScope.isCurrentGroup(current)) throw new IllegalStateException("Player spatial scope must close in stack order");
            this.ownership.close();
            CURRENT.remove();
            synchronized (current) {
                for (int i = current.regions.size() - 1; i >= 0; i--) current.regions.get(i).endOwnership();
                current.worker = null;
            }
            this.lease = null;
        }
    }

    @Nullable
    public static RegionPlayerSpatialLease current() {
        RegionPlayerSpatialLease lease = CURRENT.get();
        return lease != null && !lease.closed && lease.worker == Thread.currentThread()
                && RegionWorkerScope.isNativeWorkerThread() && RegionEntityOwnershipScope.isCurrentGroup(lease) ? lease : null;
    }

    ServerLevel level() {
        if (this.batch == null) throw new IllegalStateException("Closed player lease");
        return this.batch.level();
    }

    boolean ownsRegion(ServerLevel level, int x, int z) {
        return this.regions.stream().anyMatch(state -> state.coordinate().level() == level && state.coordinate().regionX() == x
                && state.coordinate().regionZ() == z && state.isOwnedByCurrentThread());
    }

    /** Before every unstarted call in a conflict group; geometry can change after the preceding call. */
    boolean geometryCurrent(RegionPlayerSpatialPlan plan) {
        return this.plans.contains(plan) && plan.geometryCurrent();
    }

    List<RegionCoordinate> queryRegions(ServerLevel level, AABB box) {
        if (current() != this || this.plans.stream().noneMatch(plan -> plan.footprint().containsQuery(level, box)))
            throw new IllegalStateException("Player query expands its complete captured footprint");
        return this.regions.stream().map(RegionState::coordinate).toList();
    }

    /** Raw section access must cover exactly the complete set; a subset must never become a clamp. */
    public void requireSections(ServerLevel level, List<RegionCoordinate> coordinates, AABB box) {
        List<RegionCoordinate> complete = this.queryRegions(level, box);
        if (coordinates.size() != complete.size() || !new java.util.HashSet<>(coordinates).equals(new java.util.HashSet<>(complete)))
            throw new IllegalStateException("Player section accessor omitted part of its complete region set");
    }

    public void requireBlock(ServerLevel level, BlockPos pos) {
        if (current() != this || this.plans.stream().noneMatch(plan -> plan.footprint().containsBlock(level, pos)))
            throw new IllegalStateException("Player block access expands its complete captured footprint: " + pos);
    }

    public LevelChunk chunk(ServerLevel level, int x, int z) {
        if (current() != this || this.batch == null || this.batch.level() != level || !this.ownsRegion(level,
                Math.floorDiv(x, this.plans.getFirst().footprint().regionChunks()), Math.floorDiv(z, this.plans.getFirst().footprint().regionChunks())))
            throw new IllegalStateException("Player chunk access outside complete lease");
        LevelChunk chunk = this.chunks.get(ChunkPos.asLong(x, z));
        if (chunk == null) throw new IllegalStateException("Player chunk was not loaded at capture");
        return chunk;
    }

    public void requirePosition(Entity entity, double x, double y, double z) {
        this.requireTarget(entity, "position mutation");
        AABB destination = entity.getBoundingBox().move(x - entity.getX(), y - entity.getY(), z - entity.getZ());
        if (!RegionPlayerFootprint.finite(destination) || this.plans.stream().noneMatch(plan -> RegionPlayerFootprint.contains(plan.footprint().blockBounds(), destination)))
            throw new IllegalStateException("Player position mutation expands its captured footprint");
    }

    public void requireTarget(Entity target, String operation) {
        if (current() != this || !this.owns(target)) throw new IllegalStateException("Player target outside captured identity/region set: " + operation);
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
        EntityIdentity identity = this.members.get(entity);
        return current() == this && !this.closed && identity != null && this.batch != null && entity.level() == this.batch.level()
                && identity.uuid().equals(entity.getUUID()) && identity.entityId() == entity.getId()
                && this.ownsRegion(this.batch.level(), Math.floorDiv(SectionPos.blockToSectionCoord(entity.blockPosition().getX()), this.plans.getFirst().footprint().regionChunks()),
                        Math.floorDiv(SectionPos.blockToSectionCoord(entity.blockPosition().getZ()), this.plans.getFirst().footprint().regionChunks()));
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
        this.engine.releasePlayerState(this);
        this.statePlans = List.of();
        this.batch.releasePlayerSpatialReservation();
        this.regions = List.of();
        this.plans = List.of();
        this.members = Map.of();
        this.chunks = Map.of();
        this.effects = null;
        this.engine = null;
        this.batch = null;
        this.publisher = null;
    }
}
