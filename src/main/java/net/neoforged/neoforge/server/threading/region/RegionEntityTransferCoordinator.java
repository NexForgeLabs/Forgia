/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.server.threading.BlockEntityOperationRuntime;
import net.neoforged.neoforge.server.threading.EntityInteractionScheduler;
import net.neoforged.neoforge.server.threading.EntityInteractionTask;
import net.neoforged.neoforge.server.threading.EntityRegionScheduler;
import net.neoforged.neoforge.server.threading.GlobalRegionScheduler;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.RegionScheduler;
import net.neoforged.neoforge.server.threading.RegionThreading;
import net.neoforged.neoforge.server.threading.RegionThreadingContext;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter.Access;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter.Mode;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.AdapterDescriptor;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Inspection;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Outcome;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Refresh;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Resources;
import net.neoforged.neoforge.server.threading.api.BlockEntityOperations;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Coordinates deferred entity section/addition/removal/portal commits, deferred entity moves,
 * entity region transfers (including riding-graph validation), and entity task lifecycle notifications.
 * <p>
 * Extracted from {@link RegionThreadingEngine} to isolate the ~1,140-line entity transfer and
 * deferred-mutation commit subsystem.
 */
@ApiStatus.Internal
final class RegionEntityTransferCoordinator {
    private final RegionThreadingEngine engine;

    RegionEntityTransferCoordinator(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    void applyDeferredEntityUpdates(RegionEntityTickBatch batch) {
        final List<DeferredEntityMove> waiting = this.pendingDeferredEntityMovesSnapshot();
        this.applyDeferredEntityUpdateBatch(batch.drainDeferredUpdates(), batch.level());
        for (final DeferredEntityMove move : waiting) {
            if (this.claimPendingDeferredEntityMove(move)) {
                this.applyDeferredEntityUpdate(move.entity(), move.destination(), move.attempts());
            }
        }
    }

    /**
     * Publishes the deferred ownership updates drained from one barrier, in one regionizer write scope.
     * <p>
     * An update the barrier cannot trust to the batch - a removed or cross-level entity, a destination that
     * was not captured in the entity's current level, a shut-down engine or a barrier that is not the server
     * thread - keeps the per-entity path, which is what the barrier did for every update before the batch
     * existed. Every other update is handed to
     * {@link ThreadedRegionizer#publishEntityMoves(List)}: the regionizer validates the whole list and then
     * publishes the untracked entities and the no-ops under one write lock, so the common case of a tick that
     * only re-observes the region of every entity costs one write-lock scope and no mapping write at all.
     * <p>
     * The moves that name another region than the published one are not written by the batch. They are
     * returned as transfers and keep the recorded transfer transaction, which never writes a region that a
     * worker is ticking and defers a busy handoff to the next barrier, exactly like before. A batch the
     * regionizer refused as a whole (a closed regionizer or level identity, a level mismatch, a retired
     * destination) is counted and published per entity instead, which keeps the legacy behavior of a barrier
     * that cannot batch.
     */
    private void applyDeferredEntityUpdateBatch(List<RegionEntityTickBatch.DeferredEntityUpdate> updates, ServerLevel batchLevel) {
        if (updates.isEmpty()) {
            return;
        }
        final List<ThreadedRegionizer.OwnerMove> moves = new ArrayList<>(updates.size());
        final List<RegionEntityTickBatch.DeferredEntityUpdate> perEntity = new ArrayList<>(0);
        final boolean batchable = this.canBatchDeferredEntityMoves(batchLevel);
        for (final RegionEntityTickBatch.DeferredEntityUpdate update : updates) {
            if (batchable && this.isBatchableDeferredEntityMove(update)) {
                moves.add(new ThreadedRegionizer.OwnerMove(update.entity(), update.destination()));
            } else {
                perEntity.add(update);
            }
        }
        if (!moves.isEmpty()) {
            final ThreadedRegionizer.MoveBatchResult result = this.engine.regionizer.publishEntityMoves(moves);
            if (result.applied()) {
                this.publishDeferredEntityMoveBatch(result);
            } else {
                this.engine.refusedDeferredEntityMoveBatchCount.incrementAndGet();
                for (final ThreadedRegionizer.OwnerMove move : moves) {
                    this.applyDeferredEntityUpdate(move.entity(), move.destination(), 0);
                }
            }
        }
        for (final RegionEntityTickBatch.DeferredEntityUpdate update : perEntity) {
            this.applyDeferredEntityUpdate(update.entity(), update.destination(), 0);
        }
    }

    /**
     * Applies the result of a batch publication: counts it, notifies the entities that have scheduled tasks
     * and hands the cross-region moves to the recorded transfer transaction.
     * <p>
     * A move notification only reaches the registered wrappers of its own entity, so exactly the entities that
     * have one are notified. That includes the unchanged moves: a task whose entity stayed in its region does
     * not need the notification, but a task that was scheduled in a region the entity has already left is
     * migrated by it, so the notification is never dropped for an entity that has a task. An entity without a
     * wrapper has nothing to migrate, so the scan is skipped for it - which is the whole population of a tick
     * in the common case.
     */
    private void publishDeferredEntityMoveBatch(ThreadedRegionizer.MoveBatchResult result) {
        this.engine.batchedDeferredEntityMoveCount.addAndGet(result.published().size() + result.transfers().size());
        this.engine.unchangedDeferredEntityMoveCount.addAndGet(result.unchanged().size());
        if (!this.engine.entityTasksByEntity.isEmpty()) {
            for (final Entity entity : result.published()) {
                this.notifyEntityMoved(entity);
            }
            for (final Entity entity : result.unchanged()) {
                this.notifyEntityMoved(entity);
            }
        }
        for (final ThreadedRegionizer.OwnerMove move : result.transfers()) {
            this.applyDeferredEntityUpdate(move.entity(), move.destination(), 0);
        }
    }

    /**
     * {@return true when this barrier may hand its deferred updates to the regionizer batch}
     * <p>
     * The regionizer storage is server-owned, and the batch publishes mappings on the thread that calls it. A
     * barrier that is not the server thread owning the level of the batch, or that runs after the engine shut
     * down, therefore keeps the per-entity publication instead.
     */
    private boolean canBatchDeferredEntityMoves(ServerLevel batchLevel) {
        if (this.engine.shutdown) {
            return false;
        }
        final MinecraftServer server = batchLevel.getServer();
        return server != null && server.isSameThread();
    }

    /**
     * {@return true when {@code update} can be published by the regionizer batch}
     * <p>
     * The update carries the destination its deferring thread captured, so the batch only needs the entity to
     * be alive, added to a server level, and to name that level: a removed entity is published as a removal
     * and a destination outside the entity's current level keeps the vanilla publish from the live position,
     * both exactly like the per-entity path does.
     */
    private boolean isBatchableDeferredEntityMove(RegionEntityTickBatch.DeferredEntityUpdate update) {
        final RegionCoordinate destination = update.destination();
        if (destination == null || update.entity().isRemoved()) {
            return false;
        }
        return update.entity().level() instanceof ServerLevel level && destination.level() == level;
    }

    /**
     * Applies the section publications and target chunk acquisitions of a finished entity batch.
     * <p>
     * A worker that moves an entity added to its level cannot publish the entity section or acquire the
     * target chunk, because both are server-owned storage. It records the level callback the inline vanilla
     * move would have invoked and the section of the position the move reached, and this barrier runs both
     * effects on the server thread. The publication reconciles the section membership with the entity's
     * final position, so several moves of one tick each invoke it but only the first one moves the
     * membership; the chunk acquisition of every recorded move still runs, exactly like the inline calls
     * the worker skipped.
     * <p>
     * Every entry is applied on its own: a publication or chunk acquisition that throws - a modded
     * {@code EntityEvent.EnteringSection} listener or a level callback of the level - drops and counts that
     * one entry and leaves the entries behind it untouched, because their entities carry the same moved
     * position. The first failure is rethrown afterwards, so the batch reports it through the same
     * completion path as every other server-thread step.
     * <p>
     * An update that cannot be applied is dropped without touching storage and counted in
     * {@link RegionThreadingDiagnostics#droppedDeferredEntitySectionUpdates()}: the engine shut down, the
     * level identity closed, the batch closed its section seam, the entity was removed or left the level
     * between the move and the barrier, or its publication threw. The barrier runs before the ownership
     * handoff of the same batch, so a section update is always published before a transfer commit can move
     * the entity to another region.
     */
    void applyDeferredEntitySectionUpdates(RegionEntityTickBatch batch) {
        this.applyDeferredSectionUpdates(batch.level(), batch.drainDeferredSectionUpdates(), batch.isSectionUpdateSeamClosed());
    }

    /**
     * Applies the portal transitions of the worker-ticked entities of {@code batch} on the server thread.
     * <p>
     * A worker never resolves a portal transition: the destination search reads the {@code PoiManager} of the
     * destination level, which is one storage per level and is not partitioned per region. The worker records
     * the transition instead and the barrier runs the vanilla resolution here, once per entity per tick.
     * <p>
     * The transitions run first in the handoff, because the transition is the earliest level effect of the
     * tick it belongs to: {@code Entity#baseTick} runs it before the rest of the body. An entity the barrier
     * cannot transition (it was removed, it left the level, the level identity retired or the engine shut
     * down) keeps its own portal state, so the next tick of the entity records the transition again instead of
     * losing it; the refusal is counted.
     */
    void applyDeferredPortalTransitions(RegionEntityTickBatch batch) {
        final ServerLevel level = batch.level();
        final List<Entity> transitions = batch.drainDeferredPortalTransitions();
        if (transitions.isEmpty()) {
            return;
        }
        // Every drained record is one deferred transition, and every one of them is either applied or
        // refused below, so the counters never lose one silently.
        this.engine.deferredPortalTransitionCount.addAndGet(transitions.size());
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level)) {
            this.engine.refusedPortalTransitionCount.addAndGet(transitions.size());
            return;
        }
        Throwable failure = null;
        for (final Entity entity : transitions) {
            if (entity.isRemoved() || entity.level() != level) {
                this.engine.refusedPortalTransitionCount.incrementAndGet();
                continue;
            }
            try {
                entity.completePortalTransition(level);
                this.engine.appliedPortalTransitionCount.incrementAndGet();
            } catch (Throwable throwable) {
                this.engine.refusedPortalTransitionCount.incrementAndGet();
                failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
            }
        }
        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
    }

    /**
     * Applies the section updates of the moves that a chunk tick worker deferred to the barrier.
     * <p>
     * A precipitation tick pushes the entities standing in the snow layer it raises out of the block, which is
     * an owned move of the chunk tick group, so it is deferred and applied exactly like the move of an entity
     * during its own tick.
     */
    void applyDeferredChunkTickSectionUpdates(RegionChunkTickBatch batch) {
        this.applyDeferredSectionUpdates(batch.level(), batch.drainDeferredSectionUpdates(), batch.isSectionUpdateSeamClosed());
    }

    /**
     * Applies one drain of deferred section updates on the server thread.
     *
     * @see #applyDeferredEntitySectionUpdates(RegionEntityTickBatch)
     */
    void applyDeferredSectionUpdates(ServerLevel level, List<RegionDeferredEntitySectionUpdates.DeferredSectionUpdate> updates, boolean seamClosed) {
        if (updates.isEmpty()) {
            return;
        }
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level) || seamClosed) {
            this.engine.droppedDeferredSectionUpdateCount.addAndGet(updates.size());
            return;
        }
        Throwable failure = null;
        long lastChunkKey = Long.MIN_VALUE;
        for (final RegionDeferredEntitySectionUpdates.DeferredSectionUpdate update : updates) {
            final Entity entity = update.entity();
            if (entity.isRemoved() || entity.level() != level
                    || (!entity.isAddedToLevel() && entity.getLevelCallback() == net.minecraft.world.level.entity.EntityInLevelCallback.NULL)
                    || entity.getLevelCallback() != update.callback()) {
                this.engine.droppedDeferredSectionUpdateCount.incrementAndGet();
                this.engine.droppedDeferredSectionUpdateForRemovedEntityCount.incrementAndGet();
                continue;
            }
            try {
                // The callback itself proves installed storage, even if a mod cleared the lifecycle marker.
                // Revalidate identity so an old callback cannot publish after detachment/replacement.
                update.callback().onMove();
                if (entity.isAddedToLevel()) {
                    final int chunkX = SectionPos.x(update.targetSection());
                    final int chunkZ = SectionPos.z(update.targetSection());
                    final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                    if (chunkKey != lastChunkKey) {
                        level.getChunk(chunkX, chunkZ);
                        lastChunkKey = chunkKey;
                    }
                }
                this.engine.appliedDeferredSectionUpdateCount.incrementAndGet();
            } catch (Throwable throwable) {
                // The entry did not complete, so it is dropped and counted; the remaining entries still run
                // because their entities were moved by the same batch and must not keep an unpublished
                // section. The first failure is reported once the whole batch was served.
                this.engine.droppedDeferredSectionUpdateCount.incrementAndGet();
                failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
            }
        }
        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
    }

    /**
     * Applies the removals of an owned entity that a worker deferred to the barrier.
     * <p>
     * A worker that removes an entity it owns writes only the entity's own removal state; the level callback
     * of the removal is server-owned storage, so it runs here on the server thread. The callback is the normal
     * removal path: it publishes the departure in the identity ledger, removes the entity from its section,
     * stops its ticking and tracking (which fires {@code EntityLeaveLevelEvent} and retires the region mapping),
     * runs the destruction callback and clears the entity's level callback. Applying it here therefore leaves
     * the storage, the identity ledger, the tracking state and the region index exactly as a server-thread
     * removal does.
     * <p>
     * The step runs after the deferred section updates, so a removal removes the entity from the section its
     * final position was published in, and before the deferred ownership publication, so the handoff observes
     * the removed entity and publishes its departure instead of a region mapping.
     * <p>
     * An entry that cannot be applied is a counted no-op and never touches storage: the entity was revived
     * ({@code !isRemoved()}), the server thread already applied the removal (the section manager replaced the
     * level callback the worker recorded), the entity left the level, or its level changed. A callback that throws drops and counts that one entry and leaves the entries behind it
     * untouched, because they name other entities; the first failure is rethrown afterwards through the same
     * completion path as every other server-thread step.
     */
    void applyDeferredEntityRemovals(RegionEntityTickBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Applies the removals of an owned entity that a block-entity worker deferred to the barrier.
     * <p>
     * A hopper that absorbs an item entity of its region removes it through the same vanilla funnel, so the
     * removal is deferred and applied exactly like the removal of a dying entity: the worker writes the
     * entity's own removal state, and this step runs the level callback on the server thread after the batch.
     */
    void applyDeferredBlockEntityRemovals(RegionBlockEntityTickBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * Applies the removals of an owned entity that a chunk tick worker deferred to the barrier.
     * <p>
     * A random tick that removes an entity of its region removes it through the same vanilla funnel, so the
     * removal is deferred and applied exactly like the removal of a dying entity: the worker writes the entity's
     * own removal state, and this step runs the level callback on the server thread after the batch.
     */
    void applyDeferredChunkTickRemovals(RegionChunkTickBatch batch) {
        this.applyDeferredRemovals(batch.level(), batch.drainDeferredRemovals(), batch.isRemovalSeamClosed());
    }

    /**
     * {@return the number of deferred entity removals this engine applied or repeated so far}
     * <p>
     * The difference of two reads around a drain is the number of removals that drain carried, which the
     * opt-in attribution reports as the count of the deferred removal step.
     */
    long deferredRemovalCount() {
        return this.engine.appliedDeferredRemovalCount.get() + this.engine.repeatedDeferredRemovalCount.get();
    }

    /**
     * Applies one drain of deferred removals on the server thread.
     *
     * @see #applyDeferredEntityRemovals(RegionEntityTickBatch)
     */
    void applyDeferredRemovals(ServerLevel level, RegionDeferredEntityRemovals.Drain drain, boolean seamClosed) {
        this.applyDeferredRemovals(level, drain, seamClosed, false);
    }

    /** Committed player pickup effects drain before the active parent fence is released. */
    void applyDeferredRemovals(ServerLevel level, RegionDeferredEntityRemovals.Drain drain, boolean seamClosed, boolean committedPlayerEffects) {
        if (committedPlayerEffects && (!level.getServer().isSameThread() || RegionWorkerScope.isNativeWorkerThread()
                || this.engine.activeTickBatches.stream().noneMatch(batch -> batch.level() == level && batch.isExecutingOnCurrentThread() && !batch.isComplete())))
            throw new IllegalStateException("Committed player removals require their active publisher fence");
        this.engine.repeatedDeferredRemovalCount.addAndGet(drain.repeats());
        if (drain.removals().isEmpty()) {
            return;
        }
        if ((!committedPlayerEffects && this.engine.shutdown) || this.engine.regionizer.isLevelClosed(level) || seamClosed) {
            if (committedPlayerEffects) throw new IllegalStateException("Committed player removal fence was closed before drain");
            this.engine.droppedDeferredRemovalCount.addAndGet(drain.removals().size());
            return;
        }
        Throwable failure = null;
        for (final RegionDeferredEntityRemovals.DeferredRemoval removal : drain.removals()) {
            final Entity entity = removal.entity();
            if (!entity.isRemoved()
                    || entity.level() != level
                    || removal.callback() == net.minecraft.world.level.entity.EntityInLevelCallback.NULL
                    || entity.getLevelCallback() != removal.callback()) {
                // The removal was already applied by the server thread (the section manager replaced the
                // level callback it recorded with the NULL callback), the entity was revived, its level
                // changed or it left the level: the deferred removal is a no-op, never a second removal.
                this.engine.repeatedDeferredRemovalCount.incrementAndGet();
                continue;
            }
            try {
                removal.callback().onRemove(removal.reason());
                this.engine.appliedDeferredRemovalCount.incrementAndGet();
            } catch (Throwable throwable) {
                this.engine.droppedDeferredRemovalCount.incrementAndGet();
                failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
            }
        }
        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
    }

    /**
     * Applies the admissions of new entities that a worker deferred to the barrier.
     * <p>
     * A worker that spawns an entity inside its leased region cannot admit it: the admission writes the
     * level's UUID set, its visible lookup, the entity section, the ticking list, the tracking and the region
     * index. The worker therefore constructs and configures the entity - the position writes of a pending
     * entity are field writes and are granted - and records the whole riding graph. This barrier runs the
     * unmodified {@code ServerLevel.addEntity} for every member of the graph on the server thread, so the
     * admission is written exactly like a server-thread spawn: the join event, the identity ledger, the
     * section of the entity's final position, the tracking callbacks, the ticking list and the region index.
     * <p>
     * The members are admitted in the recorded self-then-passengers order, so a vehicle is in the level before
     * its passengers. A graph is admitted whole or counted as dropped; a member the vanilla path refuses after
     * the graph was admitted partially stays admitted, because an admission cannot be rolled back, but the
     * record is counted as dropped so the loss is never silent.
     * <p>
     * An admission that cannot be applied is dropped without touching storage and counted in
     * {@link RegionThreadingDiagnostics#deferredEntityAdditionsDropped()}: the engine shut down, the level
     * identity closed, the entity was removed or left the level between the record and the barrier, a member
     * of the graph is already in the level, or a member's admission threw. The step runs after the deferred
     * removals, so a record whose entity was removed meanwhile is dropped by the state the removal left, and
     * before the fallback ticks and the ownership publication, so both observe the admitted entity.
     */
    void applyDeferredEntityAdditions(RegionEntityTickBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    /**
     * Applies the admissions that a chunk tick worker deferred to the barrier.
     * <p>
     * A random tick that hatches an egg or otherwise spawns an entity is a worker spawn of the chunk tick
     * group, so it is deferred and admitted exactly like the spawn of an entity tick.
     */
    void applyDeferredChunkTickAdditions(RegionChunkTickBatch batch) {
        this.applyDeferredAdditions(batch.level(), batch.drainDeferredAdditions(), batch.isAdditionSeamClosed());
    }

    /**
     * Applies one drain of deferred admissions on the server thread.
     *
     * @see #applyDeferredEntityAdditions(RegionEntityTickBatch)
     */
    void applyDeferredAdditions(ServerLevel level, RegionDeferredEntityAdditions.Drain drain, boolean seamClosed) {
        this.engine.recordedDeferredAdditionCount.addAndGet(drain.recorded());
        if (drain.additions().isEmpty()) {
            return;
        }
        Throwable failure = null;
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level) || seamClosed) {
            try {
                this.engine.droppedDeferredAdditionCount.addAndGet(drain.additions().size());
                this.engine.entityAdmissionDiagnostics.record(this.engine.shutdown ? RegionEntityAdmissionDiagnostics.Reason.SHUTDOWN
                        : this.engine.regionizer.isLevelClosed(level) ? RegionEntityAdmissionDiagnostics.Reason.LEVEL_CLOSED
                                : RegionEntityAdmissionDiagnostics.Reason.SEAM_CLOSED,
                        drain.additions().size());
            } catch (Throwable throwable) {
                failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
            }
            for (final var addition : drain.additions()) {
                failure = RegionThreadingEngine.accumulateFailure(failure, addition.completeRemaining(DeferredEntityAdmissionScope.Result.ABANDONED));
            }
            if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
            return;
        }
        for (final RegionDeferredEntityAdditions.DeferredEntityAddition addition : drain.additions()) {
            try {
                if (!this.canAdmitDeferredEntityAddition(level, addition)) {
                    this.engine.droppedDeferredAdditionCount.incrementAndGet();
                    failure = RegionThreadingEngine.accumulateFailure(failure, addition.completeRemaining(DeferredEntityAdmissionScope.Result.REJECTED));
                    continue;
                }
                int admittedMembers = 0;
                Entity rejectedMember = null;
                boolean admissionFailed = false;
                for (final Entity member : addition.graph()) {
                    // Earlier join/onAdded/receipt callbacks may remove, attach or move the suffix to
                    // another level. Whole-graph preflight cannot authorize that later admission.
                    final RegionEntityAdmissionDiagnostics.Reason changed = this.deferredEntityAdmissionReason(level, member);
                    if (changed != null) {
                        rejectedMember = member;
                        this.engine.entityAdmissionDiagnostics.record(changed, member, addition.graph().size());
                        failure = RegionThreadingEngine.accumulateFailure(failure, addition.complete(member, DeferredEntityAdmissionScope.Result.REJECTED));
                        continue;
                    }
                    final boolean admitted;
                    try {
                        admitted = level.addFreshEntity(member);
                    } catch (Throwable throwable) {
                        failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
                        // A throw can occur after storage mutation (for example onAddedToLevel). FAILED is not
                        // a cancellation and must not be used to release a slot as though nothing happened.
                        failure = RegionThreadingEngine.accumulateFailure(failure, addition.complete(member, DeferredEntityAdmissionScope.Result.FAILED));
                        this.engine.droppedDeferredAdditionCount.incrementAndGet();
                        this.engine.entityAdmissionDiagnostics.record(RegionEntityAdmissionDiagnostics.Reason.EXCEPTION, addition.root(), addition.graph().size());
                        admissionFailed = true;
                        break;
                    }
                    if (admitted) {
                        ++admittedMembers;
                    } else {
                        // Vanilla may refuse a duplicate or a cancelled join event. Do not retry a refusal:
                        // doing so would bypass mod spawning policy or post the join event twice.
                        rejectedMember = member;
                    }
                    failure = RegionThreadingEngine.accumulateFailure(failure, addition.complete(member, admitted
                            ? DeferredEntityAdmissionScope.Result.ADMITTED
                            : DeferredEntityAdmissionScope.Result.REJECTED));
                }
                if (admissionFailed) continue;
                if (rejectedMember == null) {
                    this.engine.appliedDeferredAdditionCount.incrementAndGet();
                } else {
                    this.engine.droppedDeferredAdditionCount.incrementAndGet();
                    this.engine.entityAdmissionDiagnostics.record(admittedMembers == 0 ? RegionEntityAdmissionDiagnostics.Reason.VANILLA_REFUSED
                            : RegionEntityAdmissionDiagnostics.Reason.PARTIAL_ADMISSION, rejectedMember, addition.graph().size());
                }
            } catch (Throwable throwable) {
                failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
            } finally {
                // Releases an unattempted suffix even if validation/diagnostics failed unexpectedly.
                // Already-resolved receipts are never called twice.
                failure = RegionThreadingEngine.accumulateFailure(failure, addition.completeRemaining(DeferredEntityAdmissionScope.Result.ABANDONED));
            }
        }
        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
    }

    /**
     * {@return true when the barrier can still admit the recorded riding graph of {@code addition}}
     * <p>
     * The whole graph is validated before the first member is admitted, so the common drop reasons - the
     * entity was removed or discarded by the worker after it recorded the admission, its level changed, a
     * member is already in the level - never leave half a graph behind.
     */
    private boolean canAdmitDeferredEntityAddition(ServerLevel level, RegionDeferredEntityAdditions.DeferredEntityAddition addition) {
        for (final Entity member : addition.graph()) {
            final RegionEntityAdmissionDiagnostics.Reason reason = this.deferredEntityAdmissionReason(level, member);
            if (reason != null) {
                this.engine.entityAdmissionDiagnostics.record(reason, member, addition.graph().size());
                return false;
            }
        }
        return true;
    }

    @Nullable
    private RegionEntityAdmissionDiagnostics.Reason deferredEntityAdmissionReason(ServerLevel level, Entity member) {
        return member.isRemoved() ? RegionEntityAdmissionDiagnostics.Reason.REMOVED
                : RegionEntityOwnershipScope.hasLevelEffects(member) ? RegionEntityAdmissionDiagnostics.Reason.ALREADY_ADDED
                        : member.level() != level ? RegionEntityAdmissionDiagnostics.Reason.LEVEL_CHANGED : null;
    }

    /**
     * Closes the deferred seams of every active batch when shutdown starts.
     * <p>
     * A shut-down engine never runs a barrier again, so a batch that still ticks must not accept another
     * section update or another removal: the next worker move or removal is refused by the storage guard
     * instead of being granted and dropped. The updates and removals that already wait are released and
     * counted, so no entity reference stays reachable from the batch and nothing is applied after the
     * shutdown. A block-entity batch only defers removals, so that is the only seam it closes; a spawn batch
     * defers admissions and removals and closes both.
     */
    void closeDeferredSeams() {
        for (final RegionTickBatch active : this.engine.activeTickBatches) {
            active.closeSpawnAdmissions();
            if (active instanceof RegionEntityTickBatch entityBatch) {
                entityBatch.closeInteractions(EntityInteractionTask.Reason.SHUTDOWN);
                this.engine.refusedPortalTransitionCount.addAndGet(entityBatch.closeDeferredPortalTransitions());
                this.engine.droppedDeferredSectionUpdateCount.addAndGet(entityBatch.closeDeferredSectionUpdates());
                this.releaseDeferredRemovals(entityBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = entityBatch.closeDeferredAdditions();
                this.engine.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.engine.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
                this.engine.entityAdmissionDiagnostics.record(RegionEntityAdmissionDiagnostics.Reason.SHUTDOWN, releasedAdditions.additions().size());
            } else if (active instanceof RegionBlockEntityTickBatch blockEntityBatch) {
                blockEntityBatch.closeOperations(BlockEntityOperations.Reason.SHUTDOWN);
                this.releaseDeferredRemovals(blockEntityBatch.closeDeferredRemovals());
            } else if (active instanceof RegionChunkTickBatch chunkTickBatch) {
                this.engine.droppedDeferredSectionUpdateCount.addAndGet(chunkTickBatch.closeDeferredSectionUpdates());
                this.releaseDeferredRemovals(chunkTickBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = chunkTickBatch.closeDeferredAdditions();
                this.engine.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.engine.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
                this.engine.entityAdmissionDiagnostics.record(RegionEntityAdmissionDiagnostics.Reason.SHUTDOWN, releasedAdditions.additions().size());
            } else if (active instanceof RegionSpawnBatch spawnBatch) {
                this.releaseDeferredRemovals(spawnBatch.closeDeferredRemovals());
                final RegionDeferredEntityAdditions.Drain releasedAdditions = spawnBatch.closeDeferredAdditions();
                this.engine.recordedDeferredAdditionCount.addAndGet(releasedAdditions.recorded());
                this.engine.droppedDeferredAdditionCount.addAndGet(releasedAdditions.additions().size());
                this.engine.entityAdmissionDiagnostics.record(RegionEntityAdmissionDiagnostics.Reason.SHUTDOWN, releasedAdditions.additions().size());
            }
        }
    }

    /**
     * Counts the removals a closed seam released as dropped and the repeats it counted.
     */
    private void releaseDeferredRemovals(RegionDeferredEntityRemovals.Drain released) {
        this.engine.droppedDeferredRemovalCount.addAndGet(released.removals().size());
        this.engine.repeatedDeferredRemovalCount.addAndGet(released.repeats());
    }

    /**
     * Applies one deferred ownership change at the entity batch barrier.
     * <p>
     * This is the per-entity path of the barrier: the ownership updates of a barrier are published by
     * {@link #applyDeferredEntityUpdateBatch(List, ServerLevel)}, and this path serves the updates that batch
     * cannot carry (a removed or cross-level entity, a refused batch) and the recorded transfers, busy retries
     * and no-ops it hands back.
     * <p>
     * The destination was captured by the deferring thread and is committed through the recorded transfer
     * transaction, so the barrier decides it exactly once. The transaction is a bookmark, not a new
     * atomicity claim: only the region-index mapping moves, and the vanilla section, identity, tracking and
     * network side effects stay caller-owned.
     * <p>
     * Every outcome that cannot use the transaction keeps the previous behavior of publishing the deferred
     * move from the live position and is counted in {@link RegionThreadingDiagnostics#legacyDeferredEntityMoveCount()}:
     * a removed or cross-level entity, a shut-down engine, a level the current thread does not own, a refused
     * recording (an untracked entity, a riding graph that spans levels or lost its identity epoch, a closed
     * level, a full journal) and any other terminal commit result. None of them is retried with a destination
     * derived a second time. An update that names the region the entity is already mapped to is the no-op
     * case of the per-tick move hook: it is published the same way but is not counted as a fallback.
     * <p>
     * {@link RegionEntityTransferResult#REGION_BUSY} is the one transient answer: the same captured
     * destination is applied again at the next barrier, at most {@link #RegionThreadingEngine.MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS}
     * times, so a leased region delays the handoff instead of dropping it. Once the bound is reached the move
     * is published through the legacy path as well, which keeps the pre-transaction behavior for a region
     * that stays busy.
     */
    private void applyDeferredEntityUpdate(Entity entity, @Nullable RegionCoordinate destination, int attempts) {
        if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
            this.publishEntityRemoval(entity);
            return;
        }
        if (!this.canRecordDeferredEntityMove(level, destination)) {
            this.publishLegacyDeferredEntityMove(entity);
            return;
        }
        final RegionState source = this.engine.regionizer.regionFor(entity);
        if (source == null || source.coordinate().equals(destination)) {
            // The deferred update names the region the entity is already mapped to. That is the common case of
            // the per-tick move hook, which defers on every entity tick; publishing it keeps the
            // pre-transaction behavior, including the scheduled-task notification, without booking a transfer
            // that changes nothing.
            this.engine.publishEntityMove(entity);
            return;
        }
        final RegionEntityTransferToken token = this.recordEntityRegionTransfer(entity, destination);
        if (token == null) {
            this.publishLegacyDeferredEntityMove(entity);
            return;
        }
        final RegionEntityTransferResult result = this.commitEntityRegionTransfer(token);
        if (result == RegionEntityTransferResult.COMMITTED) {
            return;
        }
        if (result == RegionEntityTransferResult.REGION_BUSY && attempts < RegionThreadingEngine.MAX_DEFERRED_ENTITY_MOVE_ATTEMPTS) {
            this.engine.retriedDeferredEntityMoveCount.incrementAndGet();
            synchronized (this.engine.admissionLock) {
                this.engine.pendingDeferredEntityMoves.add(new DeferredEntityMove(entity, destination, attempts + 1));
            }
            return;
        }
        this.publishLegacyDeferredEntityMove(entity);
    }

    /**
     * {@return true when the barrier can book the deferred move of an entity in {@code level}}
     * <p>
     * The recorded API rejects a caller it cannot trust with an exception, so the barrier checks the same
     * preconditions first: the engine is running, the destination was captured in the entity's level, and the
     * current thread is the server thread owning that level. A level without an owning server, or a batch
     * driven from another thread, therefore keeps the pre-transaction publish instead of failing the whole
     * batch for a handoff that simply cannot be booked.
     */
    private boolean canRecordDeferredEntityMove(ServerLevel level, @Nullable RegionCoordinate destination) {
        if (this.engine.shutdown || destination == null || destination.level() != level) {
            return false;
        }
        final MinecraftServer server = level.getServer();
        return server != null && server.isSameThread();
    }

    /**
     * {@return a snapshot of the deferred entity moves that wait for a busy region}
     */
    private List<DeferredEntityMove> pendingDeferredEntityMovesSnapshot() {
        synchronized (this.engine.admissionLock) {
            return List.copyOf(this.engine.pendingDeferredEntityMoves);
        }
    }

    /**
     * Removes {@code move} from the pending list so it is attempted exactly once per barrier.
     *
     * @return true when the move was still waiting, i.e. no newer capture of its entity pruned it
     */
    private boolean claimPendingDeferredEntityMove(DeferredEntityMove move) {
        synchronized (this.engine.admissionLock) {
            return this.engine.pendingDeferredEntityMoves.remove(move);
        }
    }

    /**
     * Publishes a deferred move that the recorded transfer transaction could not carry, exactly like before
     * the transaction existed, and counts it so a transaction that refuses handoffs stays observable.
     */
    private void publishLegacyDeferredEntityMove(Entity entity) {
        this.engine.legacyDeferredEntityMoveCount.incrementAndGet();
        this.engine.publishEntityMove(entity);
    }

    /**
     * One deferred entity move that waits for its busy source or destination region.
     *
     * @param destination the region captured when the move was deferred; it is never re-derived
     * @param attempts    the number of barriers this move was already retried at
     */
    record DeferredEntityMove(Entity entity, RegionCoordinate destination, int attempts) {}

    /**
     * Records a cross-region entity transfer decision without mutating any index.
     * <p>
     * The token records the entity, its current published identity epoch, the current source region and
     * the supplied destination region. The caller owns the vanilla entity, world and network side effects;
     * it records the intent first and commits the token on the server thread afterwards. A commit never
     * reads the live position again, so the destination stays the one recorded here even when the entity
     * moves in between.
     * <p>
     * Recording is a server-owned storage operation: it is rejected for a native region worker before it
     * reads the identity ledger, and for any other thread except the server thread owning the entity's
     * level. It answers {@code null} when no transfer can be recorded - no native engine ledger, an
     * untracked or removed entity, a graph member without a current identity, a graph that is not confined
     * to this level, the same source and destination region, a destination in another level, or a full
     * journal - and the caller then keeps the vanilla path. The vanilla cross-level transfer is therefore
     * never interrupted by an exception from this bookmark; a destination in another level and a riding
     * graph that spans levels are simply not part of the region transaction.
     *
     * @throws IllegalStateException when the caller is a native region worker, or any other thread except
     *                               the server thread owning the entity's level
     */
    @ApiStatus.Internal
    @Nullable
    public RegionEntityTransferToken recordEntityRegionTransfer(Entity entity, RegionCoordinate destinationRegion) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.recordEntityRegionTransfer");
        Objects.requireNonNull(destinationRegion, "destinationRegion");
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(level, "RegionThreadingEngine.recordEntityRegionTransfer");
        if (this.engine.shutdown || entity.isRemoved()) {
            return null;
        }
        if (destinationRegion.level() != level) {
            return null;
        }
        final RegionState source = this.engine.regionizer.regionFor(entity);
        if (source == null || source.isDead() || source.coordinate().equals(destinationRegion)) {
            return null;
        }
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return null;
        }
        final RegionEntityTransferEpochSource epochs = RegionEntityTransferEpochSource.of(level.entityIdentityLedger());
        final long identityEpoch = epochs.epoch(uuid);
        if (identityEpoch == RegionEntityTransferEpochSource.MISSING_EPOCH) {
            return null;
        }
        final List<RegionEntityTransferToken.Member> ridingGraph = new ArrayList<>();
        if (!this.collectRidingGraphMembers(topmostRidingEntity(entity), level, epochs, ridingGraph)) {
            return null;
        }
        final RegionEntityTransferToken token = new RegionEntityTransferToken(
                this.engine.nextEntityTransferTokenId.incrementAndGet(),
                RegionEntityTransferToken.Operation.REGION_MOVE,
                entity,
                uuid,
                identityEpoch,
                source.coordinate(),
                destinationRegion,
                ridingGraph);
        synchronized (this.engine.admissionLock) {
            // The graph reads above run outside the admission lock so they never delay admissions. The
            // insertion reuses the admission serialization that a level clear and a shutdown use for
            // their journal cleanup, so a decision either becomes visible before that cleanup and is
            // cleared by it, or it observes the retired level/engine here and is not recorded at all.
            if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level)) {
                return null;
            }
            if (this.engine.entityTransferJournal.record(token)) {
                return token;
            }
        }
        final long rejected = this.engine.entityTransferJournal.rejectedRecordCount();
        if (Long.bitCount(rejected) == 1) {
            // Rate-limited by the rejection count itself: a full journal must be observable without
            // flooding the log once per refused transfer.
            RegionThreadingEngine.LOGGER.warn("Entity transfer journal is full ({} pending decisions), recording token {} for {} was refused; {} recordings were refused so far",
                    RegionEntityTransferJournal.MAX_PENDING_TOKENS, token.tokenId(), uuid, rejected);
        }
        return null;
    }

    /**
     * {@return the maximum number of recorded transfers that wait for a commit}
     * <p>
     * Recording a transfer beyond this bound is refused and counted, so the bound is shared with the
     * mechanics tests instead of being duplicated there.
     */
    @ApiStatus.Internal
    public static int maxPendingEntityTransfers() {
        return RegionEntityTransferJournal.MAX_PENDING_TOKENS;
    }

    /**
     * Commits one recorded transfer on the server thread.
     * <p>
     * A native region worker is rejected with the named operation before the journal or the regionizer is
     * touched, and every other caller except the server thread owning the token's level is rejected before
     * the journal entry is claimed. The recorded values are validated in order and a mismatch aborts without
     * a mutation. A replay of a committed token answers {@link RegionEntityTransferResult#ALREADY_COMMITTED}
     * and writes nothing a second time; a token another commit is still applying answers
     * {@link RegionEntityTransferResult#IN_PROGRESS} and keeps its pending entry.
     * <p>
     * A commit never writes while the recorded source or destination region is ticking or submitted to a
     * worker; that answers {@link RegionEntityTransferResult#REGION_BUSY}. A written mapping is never
     * reported as a failure: post-commit bookkeeping failures are logged and the token stays
     * {@link RegionEntityTransferResult#COMMITTED}. A completion that a level clear or a shutdown already
     * retired does not re-create its terminal journal entry.
     */
    @ApiStatus.Internal
    public RegionEntityTransferResult commitEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.commitEntityRegionTransfer");
        if (token == null) {
            return RegionEntityTransferResult.UNKNOWN_TOKEN;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(token.level(), "RegionThreadingEngine.commitEntityRegionTransfer");
        final RegionEntityTransferJournal.Claim claim = this.engine.entityTransferJournal.claim(token);
        if (!claim.claimed()) {
            return this.lifecycleTransferResult(token, claim.terminal());
        }
        final RegionEntityTransferResult result;
        try {
            result = this.applyRecordedEntityTransfer(token);
        } catch (Throwable throwable) {
            this.engine.entityTransferJournal.aborted(token, RegionEntityTransferResult.COMMIT_FAILED);
            RegionThreadingEngine.LOGGER.warn("Entity transfer token {} failed before its region mapping was written", token.tokenId(), throwable);
            return RegionEntityTransferResult.COMMIT_FAILED;
        }
        if (result == RegionEntityTransferResult.COMMITTED) {
            this.engine.entityTransferJournal.completed(token);
        } else {
            this.engine.entityTransferJournal.aborted(token, result);
        }
        return result;
    }

    /**
     * Releases a recorded transfer decision that will never be committed.
     * <p>
     * The pending entry is released without touching any index, so a caller that gave up on the vanilla
     * side of a transfer does not leave a decision behind that would keep rejecting recordings once the
     * journal bound is reached. A replay of an already terminal token answers its recorded result, so an
     * abort is idempotent. A native region worker and any other caller that is not the server thread
     * owning the token's level are rejected before the pending entry is released.
     */
    @ApiStatus.Internal
    public RegionEntityTransferResult abortEntityRegionTransfer(@Nullable RegionEntityTransferToken token) {
        RegionThreadingHooks.assertServerEntityStorageAccess("RegionThreadingEngine.abortEntityRegionTransfer");
        if (token == null) {
            return RegionEntityTransferResult.UNKNOWN_TOKEN;
        }
        RegionThreadingHooks.assertServerThreadEntityTransfer(token.level(), "RegionThreadingEngine.abortEntityRegionTransfer");
        return this.lifecycleTransferResult(token, this.engine.entityTransferJournal.abort(token));
    }

    /**
     * {@return the terminal result to report for a journal answer that lost its entry to a lifecycle step}
     * <p>
     * The journal clears pending entries when a level closes or the engine shuts down, so an entry that is
     * gone has to report that lifecycle reason instead of collapsing it into an unknown token.
     */
    private RegionEntityTransferResult lifecycleTransferResult(RegionEntityTransferToken token, @Nullable RegionEntityTransferResult terminal) {
        if (terminal == RegionEntityTransferResult.UNKNOWN_TOKEN) {
            if (this.engine.shutdown) {
                return RegionEntityTransferResult.ENGINE_SHUT_DOWN;
            }
            if (this.engine.regionizer.isLevelClosed(token.level())) {
                return RegionEntityTransferResult.LEVEL_CLOSED;
            }
        }
        return terminal != null ? terminal : RegionEntityTransferResult.UNKNOWN_TOKEN;
    }

    /** {@return the number of recorded transfers that still wait for a commit} */
    @ApiStatus.Internal
    public int pendingEntityTransferCount() {
        return this.engine.entityTransferJournal.pendingCount();
    }

    /** {@return the number of terminal transfer results kept for idempotent replays} */
    @ApiStatus.Internal
    public int rememberedEntityTransferResultCount() {
        return this.engine.entityTransferJournal.rememberedCount();
    }

    private RegionEntityTransferResult applyRecordedEntityTransfer(RegionEntityTransferToken token) {
        if (token.entity().isRemoved()) {
            return RegionEntityTransferResult.ENTITY_REMOVED;
        }
        if (this.engine.shutdown) {
            return RegionEntityTransferResult.ENGINE_SHUT_DOWN;
        }
        final ServerLevel level = token.level();
        if (this.engine.regionizer.isLevelClosed(level)) {
            return RegionEntityTransferResult.LEVEL_CLOSED;
        }
        if (token.destinationRegion().level() != level) {
            // Recording never accepts a destination in another level, so such a token cannot name a region
            // of this level's index: it is as unresolved as a retired destination.
            return RegionEntityTransferResult.DESTINATION_INACTIVE;
        }
        final RegionEntityTransferEpochSource epochs = RegionEntityTransferEpochSource.of(level.entityIdentityLedger());
        if (epochs.epoch(token.uuid()) != token.identityEpoch()) {
            return RegionEntityTransferResult.STALE_IDENTITY_EPOCH;
        }
        final RegionState source = this.engine.regionizer.get(token.sourceRegion());
        if (source == null || source.isDead()) {
            return RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
        }
        final RegionState destination = this.engine.regionizer.get(token.destinationRegion());
        if (destination == null || destination.isDead()) {
            return RegionEntityTransferResult.DESTINATION_INACTIVE;
        }
        final RegionEntityTransferResult graphResult = this.validateRidingGraph(token, epochs);
        if (graphResult != null) {
            return graphResult;
        }
        final List<Entity> entities = new ArrayList<>(token.ridingGraph().size());
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            entities.add(member.entity());
        }
        final RegionEntityTransferResult outcome = transferResult(this.engine.regionizer.commitTransfer(token.sourceRegion(), token.destinationRegion(), entities, level));
        if (outcome != RegionEntityTransferResult.COMMITTED) {
            return outcome;
        }
        // The recorded mapping is written. Nothing below may downgrade the terminal answer: the deferred
        // handoff and the scheduled-task notifications are bookkeeping of an already committed transfer.
        try {
            this.discardPendingDeferredEntityUpdates(level, entities);
        } catch (Throwable throwable) {
            RegionThreadingEngine.LOGGER.warn("Entity transfer token {} could not discard the deferred region updates of its entities", token.tokenId(), throwable);
        }
        for (final Entity moved : entities) {
            try {
                this.notifyEntityMoved(moved);
            } catch (Throwable throwable) {
                RegionThreadingEngine.LOGGER.warn("Entity transfer token {} could not notify the scheduled tasks of a moved entity", token.tokenId(), throwable);
            }
        }
        return RegionEntityTransferResult.COMMITTED;
    }

    /**
     * Maps the outcome of the regionizer write to the transfer result.
     * <p>
     * The regionizer resolves the outcome under its write lock, so every reason a recorded transfer was not
     * applied keeps its own name here instead of collapsing into a missing mapping.
     */
    private static RegionEntityTransferResult transferResult(ThreadedRegionizer.TransferOutcome outcome) {
        return switch (outcome) {
            case COMMITTED -> RegionEntityTransferResult.COMMITTED;
            case SOURCE_MAPPING_CHANGED -> RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
            case DESTINATION_INACTIVE -> RegionEntityTransferResult.DESTINATION_INACTIVE;
            case REGION_BUSY -> RegionEntityTransferResult.REGION_BUSY;
            case LEVEL_CLOSED -> RegionEntityTransferResult.LEVEL_CLOSED;
        };
    }

    /**
     * Drops the deferred ownership handoffs of {@code entities} that are still pending in an entity batch of
     * {@code level}.
     * <p>
     * A commit runs on the server thread while the entity tick batch is finishing its server-thread work, so
     * publishing an older deferred update afterwards would move the entity away from the region that was
     * just committed. The committed decision wins.
     */
    private void discardPendingDeferredEntityUpdates(ServerLevel level, List<Entity> entities) {
        for (final RegionTickBatch batch : this.engine.activeTickBatches) {
            if (batch.level() == level && batch instanceof RegionEntityTickBatch entityBatch) {
                entityBatch.discardDeferredUpdates(entities);
            }
        }
        // A move that waits for a busy region carries the destination of the older decision too, so the
        // committed transfer has to drop it as well; otherwise the retry would overwrite the commit.
        final Set<Entity> discarded = Collections.newSetFromMap(new IdentityHashMap<>());
        discarded.addAll(entities);
        synchronized (this.engine.admissionLock) {
            this.engine.pendingDeferredEntityMoves.removeIf(move -> discarded.contains(move.entity()));
        }
    }

    /**
     * Validates that the recorded riding graph is unchanged and still belongs to the recorded source.
     * <p>
     * An added or removed member, or a member in another level, rejects the whole token as
     * {@link RegionEntityTransferResult#RIDING_GRAPH_SPLIT}. A member whose region moved away from the
     * recorded source keeps the graph intact but makes this a different transfer, so it is reported as
     * {@link RegionEntityTransferResult#SOURCE_MAPPING_CHANGED} - that covers a normal merge of the source
     * region and a second token for an entity an earlier commit already moved. A graph is never transferred
     * partially.
     */
    @Nullable
    private RegionEntityTransferResult validateRidingGraph(RegionEntityTransferToken token, RegionEntityTransferEpochSource epochs) {
        if (token.ridingGraph().isEmpty()) {
            return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
        }
        final Set<Entity> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            recorded.add(member.entity());
        }
        final Set<Entity> current = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!collectCurrentRidingGraph(token.ridingGraph().get(0).entity(), current) || !current.equals(recorded)) {
            return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
        }
        for (final RegionEntityTransferToken.Member member : token.ridingGraph()) {
            final Entity entity = member.entity();
            if (entity.isRemoved()) {
                return RegionEntityTransferResult.ENTITY_REMOVED;
            }
            if (entity.level() != token.level()) {
                return RegionEntityTransferResult.RIDING_GRAPH_SPLIT;
            }
            final RegionState region = this.engine.regionizer.regionFor(entity);
            if (region == null || !region.coordinate().equals(token.sourceRegion())) {
                return RegionEntityTransferResult.SOURCE_MAPPING_CHANGED;
            }
            if (!member.uuid().equals(entity.getUUID()) || epochs.epoch(member.uuid()) != member.identityEpoch()) {
                return RegionEntityTransferResult.STALE_IDENTITY_EPOCH;
            }
        }
        return null;
    }

    /**
     * Collects the recorded riding graph of one level.
     * <p>
     * The recorded operation is a region move inside {@code level}, so every member has to belong to that
     * level. A graph that spans levels is refused before anything is recorded: no level clear could release
     * a decision that touches members of two levels, so such a token would stay pending until shutdown.
     *
     * @return {@code false} when the graph leaves {@code level} or a member has no published identity
     */
    private static boolean collectRidingGraphMembers(Entity entity, ServerLevel level, RegionEntityTransferEpochSource epochs, List<RegionEntityTransferToken.Member> members) {
        if (entity.level() != level) {
            return false;
        }
        final UUID uuid = entity.getUUID();
        if (uuid == null) {
            return false;
        }
        final long identityEpoch = epochs.epoch(uuid);
        if (identityEpoch == RegionEntityTransferEpochSource.MISSING_EPOCH) {
            return false;
        }
        members.add(new RegionEntityTransferToken.Member(entity, uuid, identityEpoch));
        for (final Entity passenger : entity.getPassengers()) {
            if (passenger == null || !collectRidingGraphMembers(passenger, level, epochs, members)) {
                return false;
            }
        }
        return true;
    }

    private static boolean collectCurrentRidingGraph(Entity entity, Set<Entity> members) {
        if (!members.add(entity)) {
            return true;
        }
        for (final Entity passenger : entity.getPassengers()) {
            if (passenger == null || !collectCurrentRidingGraph(passenger, members)) {
                return false;
            }
        }
        return true;
    }

    private static Entity topmostRidingEntity(Entity entity) {
        Entity current = entity;
        final Set<Entity> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(current);
        while (true) {
            final Entity vehicle = current.getVehicle();
            if (vehicle == null || !vehicle.hasPassenger(current) || !visited.add(vehicle)) {
                return current;
            }
            current = vehicle;
        }
    }

    /**
     * Notifies the registered scheduled tasks of {@code entity} that its region may have moved.
     * <p>
     * Only the wrappers of this entity are reached, so an entity without a wrapper costs one lookup and
     * nothing else. {@link EntityRegionScheduledTask#onEntityMoved()} migrates a task that waits in another
     * region and returns early when the entity still resolves to the region the task was scheduled for, so a
     * notification for an unchanged mapping is never a migration.
     */
    void notifyEntityMoved(Entity entity) {
        final Set<EntityRegionScheduledTask> tasks = this.engine.entityTasksByEntity.get(entity);
        if (tasks == null) {
            return;
        }
        for (final EntityRegionScheduledTask task : List.copyOf(tasks)) {
            task.onEntityMoved();
        }
    }

    void cancelEntityTasks(Entity entity) {
        final Set<EntityRegionScheduledTask> tasks = this.engine.entityTasksByEntity.get(entity);
        if (tasks == null) {
            return;
        }
        for (final EntityRegionScheduledTask task : List.copyOf(tasks)) {
            task.cancel();
        }
    }

    /**
     * {@return every registered wrapper, so a lifecycle cleanup that retires a whole level or the engine
     * itself can cancel them}
     */
    List<EntityRegionScheduledTask> entityTaskSnapshot() {
        final List<EntityRegionScheduledTask> snapshot = new ArrayList<>();
        for (final Set<EntityRegionScheduledTask> tasks : this.engine.entityTasksByEntity.values()) {
            snapshot.addAll(tasks);
        }
        return snapshot;
    }

    /** {@return the number of registered wrappers, one per scheduled entity task} */
    int entityTaskCount() {
        int count = 0;
        for (final Set<EntityRegionScheduledTask> tasks : this.engine.entityTasksByEntity.values()) {
            count += tasks.size();
        }
        return count;
    }

    /**
     * Records that a move notification had to migrate a scheduled task to the region that now contains its
     * entity.
     */
    void recordEntityTaskMigration() {
        this.engine.entityTaskMigrationCount.incrementAndGet();
    }

    void publishEntityRemoval(Entity entity) {
        if (entity instanceof ServerPlayer player) this.engine.playerTicks.invalidate(player);
        RegionState region = this.engine.regionizer.regionFor(entity);
        if (region == null && entity.level() instanceof ServerLevel level) {
            region = this.engine.regionizer.get(this.engine.layout.regionAt(level, entity.blockPosition()));
        }
        this.cancelEntityTasks(entity);
        this.engine.regionizer.removeEntity(entity);
        if (region != null) {
            this.engine.regionizer.removeIfEmpty(region);
        } else if (entity.level() instanceof ServerLevel level) {
            // Scheduling alone can keep an otherwise untracked entity's region alive.
            final RegionState scheduledRegion = this.engine.regionizer.get(this.engine.layout.regionAt(level, entity.blockPosition()));
            if (scheduledRegion != null) {
                this.engine.regionizer.removeIfEmpty(scheduledRegion);
            }
        }
    }

    void unregisterEntityTask(EntityRegionScheduledTask task) {
        this.engine.entityTasksByEntity.computeIfPresent(task.entity(), (entity, tasks) -> {
            tasks.remove(task);
            return tasks.isEmpty() ? null : tasks;
        });
    }
}
