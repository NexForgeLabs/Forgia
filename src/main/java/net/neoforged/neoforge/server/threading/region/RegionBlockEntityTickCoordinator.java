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
 * Coordinates the block-entity tick slice across indexed sandbox dispatch, canonical native/furnace/mod
 * prefix waves, work-graph execution, and server-thread fallback phases.
 * <p>
 * Extracted from {@link RegionThreadingEngine} to isolate the ~2,700-line block-entity execution
 * subsystem into a dedicated package-private coordinator.
 */
@ApiStatus.Internal
final class RegionBlockEntityTickCoordinator {
    private final RegionThreadingEngine engine;

    RegionBlockEntityTickCoordinator(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier) {
        this.tickBlockEntities(level, chunks, barrier, false);
    }

    void tickBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier, boolean canonicalSequence) {
        if (chunks.isEmpty()) {
            if (this.engine.unifiedTickCoordinator.hasStagedWork(level)) {
                this.engine.unifiedTickCoordinator.dispatchUnifiedLevelTick(level, Map.of());
            }
            return;
        }
        if (canonicalSequence) {
            this.engine.unifiedTickCoordinator.flushStagedWork(level);
            this.tickCanonicalBlockEntities(level, chunks, barrier);
            return;
        }
        if (this.engine.isIndependentRegionTickEnabled() && RegionThreadingConfig.regionSandboxAllBlockEntitiesEnabled()) {
            this.tickIndexedSandboxBlockEntities(level, chunks);
            return;
        }
        this.engine.unifiedTickCoordinator.flushStagedWork(level);
        if (this.engine.furnaceWorkWavesEnabled && this.engine.layout.isDynamicCluster()) {
            this.tickFurnaceWorkWaves(level, chunks, barrier, canonicalSequence);
            return;
        }

        if (!this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
            ParallelAdapterRuntime.prepareBlockEntityTicks(level);
        }
        final RegionBlockEntityTickBatch batch = new RegionBlockEntityTickBatch(level, this.engine.deferredBlockUpdateBudget, chunks,
                () -> !this.engine.regionizer.isLevelClosed(level), pos -> this.engine.regionizer.get(this.engine.layout.regionAt(level, pos)));
        batch.canonicalSequence(canonicalSequence);
        final TickBatchPublication publication = this.engine.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // The level clear completed after this snapshot was collected. Its region state is retired
            // and no batch may be published for it: the collected tickers must not tick for a level
            // that already unloaded.
            this.engine.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
            batch.execution().close();
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // The engine was shut down between the snapshot and the batch; the collected tickers still
            // have to tick, but no batch may be published after shutdown started.
            try {
                this.tickBlockEntityChunksInline(batch, chunks, null);
            } finally {
                batch.execution().close();
            }
            return;
        }
        // This thread owns the batch until it finished; a shutdown or unload requested from inside it
        // (fallback work included) cannot wait for a batch that only this thread can complete.
        batch.markExecutingOnCurrentThread();
        // The opt-in attribution is decided once per batch, never per ticker: a disabled attribution only
        // costs this one volatile read and takes no timestamp.
        final boolean attribution = RegionBlockEntityTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            batch.setWorkGraph(this.engine.blockEntityWorkGraph.begin(level, chunks));
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.leaseBlockEntityTickGroups(batch, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            final long awaitCpuStart = attribution ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() : 0L;
            try {
                // The barrier is the level's main-thread chunk-source block, so the batch waits by polling the
                // main-thread queue. Counting the polls and the CPU time of the wait shows how much of the wait
                // the server thread runs instead of parking, which is what competes with the workers.
                final Runnable work = () -> batch.submitAndAwait(this.engine.workerPool, attribution ? condition -> barrier.await(() -> {
                    RegionBlockEntityTickAttribution.recordAwaitPoll();
                    return condition.getAsBoolean();
                }) : barrier);
                if (canonicalSequence) {
                    this.engine.beginRegionPhase(level);
                    try {
                        work.run();
                    } finally {
                        // All groups physically returned; retain their original region leases until the
                        // invocation effects commit. Global mailbox/POI publication follows lease release.
                        this.engine.levelTickPhaseState(level).endRegionPhase();
                    }
                } else this.engine.runRegionBatch(level, work);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    final long awaitEnd = System.nanoTime();
                    RegionBlockEntityTickAttribution.recordAwait(awaitEnd - awaitStart,
                            RegionBlockEntityTickAttribution.currentThreadCpuNanos() - awaitCpuStart);
                    // The slowest group bounds the wait the workers caused; the rest of the await is dispatch
                    // and completion, which is what a barrier-model fix could remove.
                    RegionBlockEntityTickAttribution.recordBatchMakespan(batch.slowestGroupNanos());
                    // Splits the wait into the time until the first group started, the span the workers ran
                    // in and the time until the wait noticed the last group finished.
                    RegionBlockEntityTickAttribution.recordBatchPhases(awaitStart, awaitEnd,
                            batch.firstGroupStartNanos(), batch.lastGroupEndNanos());
                    RegionBlockEntityTickAttribution.recordBatchSubmit(batch.submitNanos());
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                // The light rechecks and block updates of the blocks the tickers wrote are the level-wide effects
                // of their ticker body, and that body runs before the removal of an item a hopper absorbed, so
                // they run first. They are not one of the timed handoff steps: the step timers below split the
                // removal, fallback and mutation work, and this segment is the vanilla publication of the batch.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockUpdates(batch));
                // Every handoff step is timed on its own when the attribution is enabled, so the deferred
                // work of a batch is attributable per step instead of as one block.
                final long removalStart = attribution ? System.nanoTime() : 0L;
                final long removalsBefore = attribution ? this.engine.deferredRemovalCount() : 0L;
                // The removal of an owned item entity is the one vanilla storage effect a block-entity
                // worker may defer. It runs before the fallback ticks, so a hopper that the server thread
                // ticks after the batch no longer sees the item entity a worker already absorbed.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockEntityRemovals(batch));
                final long operationStart = attribution ? System.nanoTime() : 0L;
                final long operationRequests = attribution ? batch.pendingOperationCount() : 0L;
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyBlockEntityOperations(batch));
                if (canonicalSequence) {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.commitInvocationEffects(batch, Long.MAX_VALUE));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.releaseCanonicalNativeRegions(batch));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.drainGlobalMessages(level));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> RegionPoiStorages.publishLevel(level));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, batch::reportTickFailures);
                }
                final long fallbackStart = attribution ? System.nanoTime() : 0L;
                final long removals = attribution ? this.engine.deferredRemovalCount() - removalsBefore : 0L;
                if (!canonicalSequence || failure == null) failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.tickFallbackBlockEntities(batch));
                final long mutationStart = attribution ? System.nanoTime() : 0L;
                final long fallbackTickers = attribution ? countBlockEntityTickers(batch.fallbackChunks()) + batch.fallbackTickers().size() : 0L;
                final long mutations = attribution ? batch.regionTickMutations().size() : 0L;
                // The scheduled ticks a ticker started through the neighbor update of its own setChanged()
                // were recorded on the workers; they mutate LevelTicks here, on the server thread.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyRegionTickMutations(batch.regionTickMutations()));
                final long finishStart = attribution ? System.nanoTime() : 0L;
                if (batch.workGraph() != null) {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, batch.workGraph()::finish);
                }
                batch.closeOperations(BlockEntityOperations.Reason.BATCH_CLOSED);
                failure = RegionThreadingEngine.runBatchCompletion(failure, batch.execution()::close);
                batch.finish();
                this.engine.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
                if (attribution) {
                    final long finishEnd = System.nanoTime();
                    RegionBlockEntityTickAttribution.recordHandoffRemovals(operationStart - removalStart, removals);
                    RegionBlockEntityTickAttribution.recordHandoffOperations(fallbackStart - operationStart, operationRequests);
                    RegionBlockEntityTickAttribution.recordHandoffFallback(mutationStart - fallbackStart, fallbackTickers);
                    RegionBlockEntityTickAttribution.recordHandoffMutations(finishStart - mutationStart, mutations);
                    RegionBlockEntityTickAttribution.recordHandoffFinish(finishEnd - finishStart);
                }
            } finally {
                if (attribution) {
                    RegionBlockEntityTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure != null) {
            RegionThreadingEngine.throwUnchecked(failure);
        }
        batch.throwIfFailed();
    }

    private record NativeInvocation(RegionBlockEntityTickAdmission admission, Inspection inspection,
            NativeBlockEntityPreparation.Candidate proof, RegionState region) implements CanonicalBlockEntityCoordinator.Unit {}

    private record FurnaceInvocation(RegionBlockEntityTickAdmission admission, Inspection inspection,
            FurnaceWorkPreparation.Candidate candidate, @Nullable BlockEntityResourceOwnership.Claims claims) implements CanonicalBlockEntityCoordinator.Unit {}

    private record ExecutionInvocation(RegionBlockEntityTickAdmission admission, Inspection inspection,
            ExecutionBlockEntityPreparation.Manifest manifest, BlockEntityResourceOwnership.Claims claims) implements CanonicalBlockEntityCoordinator.Unit {}

    private record ModInvocation(RegionBlockEntityTickAdmission admission, Inspection inspection,
            CoreModPreparation.Manifest manifest, BlockEntityResourceOwnership.Claims claims) implements CanonicalBlockEntityCoordinator.Unit {}

    @Nullable
    RegionState modHome(ServerLevel level, BlockPos position) {
        return this.engine.regionizer.getOrCreate(this.engine.layout.regionAt(level, position));
    }

    boolean modServerAccessAllowed(ServerLevel level, BlockPos position) {
        if (level.getServer() == null || !level.getServer().isSameThread() || RegionWorkerScope.isNativeWorkerThread()) return false;
        final var region = this.engine.regionizer.get(this.engine.layout.regionAt(level, position));
        return region == null || !region.isRunning() || region.isOwnedByCurrentThread();
    }

    private record NativeEntry(int offset, NativeInvocation invocation) {}

    private static final class NativePrefixGate {
        final java.util.concurrent.CountDownLatch checked;
        final java.util.concurrent.atomic.AtomicInteger admitted;
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        NativePrefixGate(int groups, int units) {
            this.checked = new java.util.concurrent.CountDownLatch(groups);
            this.admitted = new java.util.concurrent.atomic.AtomicInteger(units);
        }

        void refuse(int offset) {
            this.admitted.accumulateAndGet(offset, Math::min);
        }

        void awaitChecks() {
            boolean interrupted = false;
            while (this.checked.getCount() != 0) {
                try {
                    this.checked.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private boolean canonicalBoundaryIdle() {
        if (!this.engine.activeTickBatches.isEmpty() || this.engine.globalRegion.isRunning() || this.engine.globalRegion.isWorkerSubmitted()) return false;
        final AtomicBoolean idle = new AtomicBoolean(true);
        this.engine.regionizer.forEach(region -> {
            if (region.isRunning() || region.isWorkerSubmitted()) idle.set(false);
        });
        return idle.get();
    }

    private void enterCanonicalBoundary(ServerLevel level, RegionTickBarrier barrier) {
        final var server = level.getServer();
        if ((server != null && !server.isSameThread()) || RegionWorkerScope.isNativeWorkerThread()
                || this.engine.regionWorkAdmission.isOwner())
            throw new IllegalStateException("Canonical BE phase requires its quiescent server coordinator");
        if (!this.engine.regionWorkAdmission.tryEnter(this::canonicalBoundaryIdle)) {
            try {
                barrier.await(() -> {
                    if (this.engine.independentTickScheduler != null) {
                        this.engine.independentTickScheduler.drainCompletedCommits(level, 500_000L);
                    }
                    if (this.engine.regionWorkAdmission.isOwner()) return true;
                    for (final var active : this.engine.activeTickBatches) active.servicePendingServerWork();
                    return this.engine.regionWorkAdmission.tryEnter(this::canonicalBoundaryIdle);
                });
            } catch (Throwable failure) {
                if (this.engine.regionWorkAdmission.isOwner()) this.engine.regionWorkAdmission.exit();
                throw failure;
            }
        }
    }

    private static final Inspection SERIAL_FENCE_INSPECTION = Inspection.serialFence();
    private static final Inspection SANDBOX_NATIVE_INSPECTION = new Inspection(
            new AdapterDescriptor(
                    ResourceLocation.parse("forgia:region_sandbox_tick"),
                    Set.of(ResourceLocation.parse("forgia:region_sandbox_tick")),
                    Mode.REGION_OWNER),
            Resources.REGION_LOCAL,
            Outcome.CANDIDATE);

    private void tickCanonicalBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier) {
        final long canonicalBeStart = System.nanoTime();
        this.enterCanonicalBoundary(level, barrier);
        final boolean furnaces = this.engine.furnaceWorkWavesEnabled && this.engine.layout.isDynamicCluster();
        boolean hasWorkerCandidate = this.engine.coreModPreparation != null
                && this.engine.blockEntityResourcesEnabled
                && CoreModTickerVerifier.available()
                && CoreModRuntimeIntegrity.verify();
        if (!hasWorkerCandidate) {
            final boolean coreAdaptersUsable = AuditedBlockEntityPreparation.isCoreCurrent();
            outer:
            for (int c = 0; c < chunks.size(); ++c) {
                final List<RegionBlockEntityTickAdmission> admissions = chunks.get(c).admissions();
                for (int a = 0; a < admissions.size(); ++a) {
                    final RegionBlockEntityTickAdmission adm = admissions.get(a);
                    final var decision = adm.decision();
                    if (decision == RegionBlockEntityTickAdmission.Decision.ADMITTED
                            || decision == RegionBlockEntityTickAdmission.Decision.ADMITTED_WITH_BORDER_UPDATES
                            || (decision == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED && coreAdaptersUsable)
                            || (furnaces && LevelChunk.furnaceWorkSource(adm.ticker()) != null)) {
                        hasWorkerCandidate = true;
                        break outer;
                    }
                }
            }
        }
        if (!hasWorkerCandidate) {
            this.tickCanonicalBlockEntitiesPureSerial(level, chunks, canonicalBeStart, furnaces);
            return;
        }
        RegionBlockEntityTickBatch batch = null;
        RegionBlockEntityWorkGraph.Phase graph = null;
        boolean published = false;
        final List<BlockEntityExecutionAdapter> executionCleanup = new ArrayList<>();
        final long resourcePhaseStart = furnaces && this.engine.blockEntityResourcesEnabled ? System.nanoTime() : 0L;
        final int[] furnaceCandidates = { 0 };
        final long[] furnacePreparationNanos = { 0L };
        final Set<RegionBlockEntityTickAdmission> counted = furnaces ? java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()) : null;
        final boolean[] hadWorkerPrefix = { false };
        Throwable failure = null;
        try {
            batch = new RegionBlockEntityTickBatch(level, this.engine.deferredBlockUpdateBudget, chunks,
                    () -> !this.engine.regionizer.isLevelClosed(level), pos -> this.engine.regionizer.get(this.engine.layout.regionAt(level, pos)));
            batch.canonicalSequence(true);
            batch.enableExternalWaves();
            batch.holdsServerAdmissionFence(true);
            final var publication = this.engine.publishTickBatch(batch, level);
            if (publication == TickBatchPublication.LEVEL_CLOSED) {
                this.engine.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
            } else if (publication == TickBatchPublication.SHUTDOWN) {
                this.tickBlockEntityChunksInline(batch, chunks, null);
            } else {
                published = true;
                batch.markExecutingOnCurrentThread();
                this.engine.activeCanonicalBeDimensionId = level.dimension().location().toString();
                final var chunkRegions = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<RegionState>(chunks.size());
                this.engine.activeCanonicalBeChunkRegions = chunkRegions;
                if (this.engine.layout.isDynamicCluster()) {
                    this.engine.activeCanonicalBeRegionCosts = new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>();
                }
                // Publish this same phase before any arbitrary prepare callback: unload/shutdown must
                // await preparation too. Legacy adapter preparation still stays outside furnace waves;
                // explicitly audited integrations use their own generic canonical preparation contract.
                if (!this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                    ParallelAdapterRuntime.prepareCanonicalBlockEntityTicks(level, furnaces, executionCleanup);
                }
                graph = this.engine.blockEntityWorkGraph.begin(level, chunks);
                batch.setWorkGraph(graph);
                final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
                boolean orderedMonotonic = true;
                boolean anyWorkerCandidate = this.engine.coreModPreparation != null && this.engine.blockEntityResourcesEnabled;
                long lastSeq = -1L;
                for (int c = 0; c < chunks.size(); ++c) {
                    final RegionBlockEntityTickChunk chunk = chunks.get(c);
                    final List<RegionBlockEntityTickAdmission> admissions = chunk.admissions();
                    final RegionState chunkRegion = this.engine.regionizer.get(chunk.region(this.engine.layout, level));
                    if (chunkRegion != null && !chunkRegion.isDead()) {
                        chunkRegions.put(chunk.chunkKey(), chunkRegion);
                        if (this.engine.overloadGovernor != null && this.engine.shouldSkipOverloadedRegion(level, chunkRegion)) {
                            this.engine.skippedBlockEntityTicks.addAndGet(admissions.size());
                            continue;
                        }
                    }
                    for (int a = 0; a < admissions.size(); ++a) {
                        final RegionBlockEntityTickAdmission adm = admissions.get(a);
                        final long seq = adm.sequence();
                        if (seq <= lastSeq) orderedMonotonic = false;
                        lastSeq = seq;
                        if (!anyWorkerCandidate
                                && (adm.decision() != RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED
                                        && adm.decision() != RegionBlockEntityTickAdmission.Decision.NOT_INTERIOR_CHUNK
                                        || (furnaces && LevelChunk.furnaceWorkSource(adm.ticker()) != null))) {
                            anyWorkerCandidate = true;
                        }
                        ordered.add(adm);
                    }
                }
                if (!orderedMonotonic) {
                    ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
                }
                final var phase = batch;
                final var sample = graph;
                if (!anyWorkerCandidate) {
                    for (int i = 0, size = ordered.size(); i < size; ++i) {
                        final var admission = ordered.get(i);
                        phase.execution().reinspectUnstarted(admission, SERIAL_FENCE_INSPECTION);
                        if (furnaces) this.engine.furnaceWorkMetrics.serial();
                        this.engine.fallbackBlockEntityTicks.incrementAndGet();
                        this.tickBlockEntitySerial(phase, admission, sample, null);
                    }
                } else {
                // One preparation budget/home table/scratch for the whole canonical phase; a new
                // prefix never resets logical uses or borrows an earlier successful resource proof.
                final var resources = furnaces && this.engine.blockEntityResourcesEnabled ? new FurnaceResourcePreparation(level, this.engine.layout) : null;
                final int[] modBindings = { 0 };
                final var executionAdapters = new ExecutionBlockEntityPreparation(this.engine, uses -> {
                    if (modBindings[0] > BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE - uses) {
                        modBindings[0] = BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE + 1;
                        return false;
                    }
                    modBindings[0] += uses;
                    return resources == null || resources.charge(uses);
                });
                final boolean modRuntime = this.engine.coreModPreparation != null && this.engine.blockEntityResourcesEnabled && CoreModRuntimeIntegrity.verify();
                new CanonicalBlockEntityCoordinator(ordered).execute(new CanonicalBlockEntityCoordinator.Driver() {
                    // Only share the read-only lookahead of one still-unexecuted contiguous prefix.
                    // Every execution/Serial Fence below discards it; the existing held-region fresh
                    // preparation, whole-phase alias check and reservation still run before any body.
                    private boolean lookaheadDirty;
                    private int lastPreparedIndex = -1;
                    private boolean lastPreparedEligible;
                    private FurnaceWorkPreparation furnaceLookahead;
                    private Set<net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity> furnaceAliases;
                    private java.util.IdentityHashMap<Object, Access> executionRoots;
                    private BlockEntityResourceOwnership.PrefixIndependence prefixResources;
                    private it.unimi.dsi.fastutil.longs.LongOpenHashSet furnacePositions;
                    private Set<RegionState> nativeRegions;
                    private Set<Object> nativeSources;
                    private List<AuditedBlockEntityPreparation.Candidate> auditedNativeCandidates;
                    private long lastPrepareChunkKey = Long.MIN_VALUE;
                    @Nullable
                    private LevelChunk lastPrepareChunk;
                    @Nullable
                    private RegionState lastPrepareRegion;

                    private void discardLookahead() {
                        this.lastPreparedIndex = -1;
                        if (!this.lookaheadDirty) return;
                        this.lookaheadDirty = false;
                        this.furnaceLookahead = null;
                        this.furnaceAliases = null;
                        this.executionRoots = null;
                        if (this.prefixResources != null) this.prefixResources.reset();
                        if (this.furnacePositions != null) this.furnacePositions.clear();
                        if (this.nativeRegions != null) this.nativeRegions.clear();
                        if (this.nativeSources != null) this.nativeSources.clear();
                        if (this.auditedNativeCandidates != null) this.auditedNativeCandidates.clear();
                    }

                    @Override
                    public CanonicalBlockEntityCoordinator.Unit prepare(int index) {
                        final var admission = ordered.get(index);
                        final boolean eligible = phase.eligibleNow(admission);
                        this.lastPreparedIndex = index;
                        this.lastPreparedEligible = eligible;
                        if (!eligible) return null;
                        final var executionBinding = admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                                ? ParallelAdapterRuntime.auditedBlockEntityTickBinding(admission)
                                : null;
                        if (executionBinding != null && executionBinding.adapter() instanceof BlockEntityExecutionAdapter) {
                            final var manifest = executionAdapters.prepare(admission);
                            if (manifest == null) return null;
                            final var claims = manifest.claims();
                            if (!claims.complete()) return null;
                            final var descriptor = executionBinding.descriptor();
                            final var inspection = new Inspection(descriptor,
                                    descriptor.mode() == Mode.RESOURCE_SCOPED ? Resources.ADAPTER_CLAIMS : Resources.REGION_LOCAL, Outcome.CANDIDATE);
                            final var ticket = phase.execution().reinspectUnstarted(admission, inspection);
                            phase.execution().proveExecution(admission, manifest, claims);
                            if (descriptor.mode() == Mode.REGION_OWNER) phase.execution().enableInvocationEffects(ticket);
                            this.lookaheadDirty = true;
                            return new ExecutionInvocation(admission, inspection, manifest, claims);
                        }
                        if (modRuntime) {
                            final var manifest = RegionBlockEntityTickCoordinator.this.engine.coreModPreparation.prepare(admission, resources);
                            if (manifest != null) {
                                final var claims = manifest.claims(RegionBlockEntityTickCoordinator.this.engine);
                                modBindings[0] += claims.resourceCount();
                                if (modBindings[0] > BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE
                                        || resources != null && !resources.charge(claims.resourceCount()))
                                    return null;
                                final var descriptor = new AdapterDescriptor(ResourceLocation.parse("forgia:verified_mod_counter"),
                                        Set.of(ResourceLocation.parse(admission.type())), Mode.RESOURCE_SCOPED);
                                final var inspection = new Inspection(descriptor, Resources.MOD_CLAIMS, Outcome.CANDIDATE);
                                phase.execution().reinspectUnstarted(admission, inspection);
                                phase.execution().proveMod(admission, manifest, claims);
                                this.lookaheadDirty = true;
                                return new ModInvocation(admission, inspection, manifest, claims);
                            }
                        }
                        if (furnaces && LevelChunk.furnaceWorkSource(admission.ticker()) != null) {
                            final long started = System.nanoTime();
                            this.lookaheadDirty = true;
                            if (this.furnaceLookahead == null) {
                                this.furnaceLookahead = new FurnaceWorkPreparation(level, RegionBlockEntityTickCoordinator.this.engine.layout, resources != null);
                            }
                            final var candidate = this.furnaceLookahead.prepare(admission);
                            if (candidate != null && this.furnaceAliases == null)
                                this.furnaceAliases = FurnaceWorkPreparation.aliased(ordered);
                            if (candidate == null) RegionBlockEntityTickCoordinator.this.engine.furnaceWorkMetrics.refused(this.furnaceLookahead.refusal());
                            if (candidate == null || this.furnaceAliases.contains(candidate.source())) {
                                if (candidate != null) RegionBlockEntityTickCoordinator.this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.SHARED_INVENTORY);
                                furnacePreparationNanos[0] += System.nanoTime() - started;
                                return null;
                            }
                            final var claims = resources == null ? null : resources.captureForPlan(candidate);
                            if (resources != null && (resources.budgetExceeded() || !claims.complete())) {
                                RegionBlockEntityTickCoordinator.this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.RESOURCE_PROOF);
                                furnacePreparationNanos[0] += System.nanoTime() - started;
                                return null;
                            }
                            final var inspection = new Inspection(FURNACE_EXECUTION,
                                    resources == null ? Resources.FURNACE_LOCAL : Resources.FURNACE_CLAIMS, Outcome.CANDIDATE);
                            phase.execution().reinspectUnstarted(admission, inspection);
                            if (counted.add(admission)) ++furnaceCandidates[0];
                            furnacePreparationNanos[0] += System.nanoTime() - started;
                            return new FurnaceInvocation(admission, inspection, candidate, claims);
                        }
                        if (furnaces && admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                                && !AuditedBlockEntityPreparation.hasCandidate(admission))
                            return null;
                        final var pos = admission.position();
                        final int cx = pos.getX() >> 4;
                        final int cz = pos.getZ() >> 4;
                        final long ck = ChunkPos.asLong(cx, cz);
                        final LevelChunk cachedChunk;
                        final RegionState region;
                        if (ck == this.lastPrepareChunkKey && this.lastPrepareRegion != null && !this.lastPrepareRegion.isDead()) {
                            cachedChunk = this.lastPrepareChunk;
                            region = this.lastPrepareRegion;
                        } else {
                            final var chunkSource = level.getChunkSource();
                            cachedChunk = chunkSource == null ? null : chunkSource.getChunkNow(cx, cz);
                            region = RegionBlockEntityTickCoordinator.this.engine.regionizer.getOrCreate(RegionBlockEntityTickCoordinator.this.engine.layout.regionAt(level, pos));
                            this.lastPrepareChunkKey = ck;
                            this.lastPrepareChunk = cachedChunk;
                            this.lastPrepareRegion = region;
                        }
                        if (region == null || region.isDead()) return null;
                        final var proof = NativeBlockEntityPreparation.prepare(admission, cachedChunk);
                        if (proof == null) return null;
                        if (proof instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate) {
                            this.lookaheadDirty = true;
                            return new NativeInvocation(admission, SANDBOX_NATIVE_INSPECTION, proof, region);
                        }
                        final var inspection = inspectNativeBlockEntity(admission);
                        if (inspection.outcome() != Outcome.CANDIDATE) return null;
                        final var ticket = phase.execution().reinspectUnstarted(admission, inspection);
                        phase.execution().enableInvocationEffects(ticket);
                        this.lookaheadDirty = true;
                        return new NativeInvocation(admission, inspection, proof, region);
                    }

                    @Override
                    public int prefixLimit(CanonicalBlockEntityCoordinator.Unit first) {
                        if (first instanceof NativeInvocation nativeInv
                                && nativeInv.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate
                                && RegionBlockEntityTickCoordinator.this.engine.isIndependentRegionTickEnabled()) {
                            return Integer.MAX_VALUE;
                        }
                        return first instanceof ModInvocation || first instanceof ExecutionInvocation execution
                                && execution.inspection().descriptor().mode() == Mode.REGION_OWNER
                                        ? RegionBlockEntityTickCoordinator.this.engine.workerPool.threadCount()
                                        : RegionBlockEntityWorkGraph.MAX_UNITS;
                    }

                    @Override
                    public boolean mayAppend(List<CanonicalBlockEntityCoordinator.Unit> prefix, CanonicalBlockEntityCoordinator.Unit next) {
                        this.lookaheadDirty = true;
                        if (prefix.getFirst() instanceof ExecutionInvocation first) {
                            if (!(next instanceof ExecutionInvocation candidate)
                                    || first.inspection().descriptor().mode() != candidate.inspection().descriptor().mode())
                                return false;
                            if (this.executionRoots == null) this.executionRoots = new java.util.IdentityHashMap<>(first.manifest().roots());
                            for (final var entry : candidate.manifest().roots().entrySet()) {
                                final var previous = this.executionRoots.get(entry.getKey());
                                if (previous != null && (entry.getValue() == Access.WRITE
                                        || previous == Access.WRITE))
                                    return false;
                            }
                            if (first.inspection().descriptor().mode() == Mode.REGION_OWNER)
                                for (final var unit : prefix)
                                    if (((ExecutionInvocation) unit).manifest().home() == candidate.manifest().home()) return false;
                            this.executionRoots.putAll(candidate.manifest().roots());
                            return true;
                        }
                        if (prefix.getFirst() instanceof ModInvocation firstMod) {
                            if (!(next instanceof ModInvocation nextMod) || prefix.size() >= RegionBlockEntityTickCoordinator.this.engine.workerPool.threadCount()) return false;
                            if (this.prefixResources == null) this.prefixResources = new BlockEntityResourceOwnership.PrefixIndependence();
                            if (prefix.size() == 1) {
                                this.prefixResources.reset();
                                if (!this.prefixResources.tryAppend(firstMod.claims())) return false;
                            }
                            return this.prefixResources.tryAppend(nextMod.claims());
                        }
                        if (prefix.getFirst() instanceof FurnaceInvocation firstFurnace) {
                            if (!(next instanceof FurnaceInvocation nextFurnace)) return false;
                            if (resources == null) {
                                if (this.furnacePositions == null) this.furnacePositions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                                if (prefix.size() == 1) {
                                    this.furnacePositions.clear();
                                    this.furnacePositions.add(firstFurnace.admission().position().asLong());
                                }
                                return this.furnacePositions.add(nextFurnace.admission().position().asLong());
                            }
                            if (this.prefixResources == null) this.prefixResources = new BlockEntityResourceOwnership.PrefixIndependence();
                            if (prefix.size() == 1) {
                                this.prefixResources.reset();
                                if (!this.prefixResources.tryAppend(firstFurnace.claims())) return false;
                            }
                            return this.prefixResources.tryAppend(nextFurnace.claims());
                        }
                        if (!(next instanceof NativeInvocation nextNative)) return false;
                        final var firstNative = (NativeInvocation) prefix.getFirst();
                        if (firstNative.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate
                                && nextNative.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate
                                && RegionBlockEntityTickCoordinator.this.engine.isIndependentRegionTickEnabled()) {
                            return true;
                        }
                        if (this.nativeRegions == null) {
                            this.nativeRegions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                            this.nativeSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                        }
                        if (prefix.size() == 1) {
                            this.nativeRegions.clear();
                            this.nativeSources.clear();
                            if (this.auditedNativeCandidates != null) this.auditedNativeCandidates.clear();
                            this.nativeRegions.add(firstNative.region());
                            this.nativeSources.add(firstNative.proof().source());
                            if (firstNative.proof() instanceof AuditedBlockEntityPreparation.Candidate audited) {
                                if (this.auditedNativeCandidates == null) this.auditedNativeCandidates = new ArrayList<>();
                                this.auditedNativeCandidates.add(audited);
                            }
                        }
                        final int maxRegions = RegionBlockEntityTickCoordinator.this.engine.workerPool.threadCount();
                        if (!this.nativeRegions.contains(nextNative.region()) && this.nativeRegions.size() >= maxRegions) return false;
                        if (this.nativeSources.contains(nextNative.proof().source())) return false;
                        if (nextNative.proof() instanceof AuditedBlockEntityPreparation.Candidate nextAudited) {
                            for (int i = 0; i < prefix.size(); ++i) {
                                if (((NativeInvocation) prefix.get(i)).proof().conflictsWith(nextAudited)) return false;
                            }
                            if (this.auditedNativeCandidates == null) this.auditedNativeCandidates = new ArrayList<>();
                            this.auditedNativeCandidates.add(nextAudited);
                        } else if (this.auditedNativeCandidates != null && !this.auditedNativeCandidates.isEmpty()) {
                            for (int i = 0; i < this.auditedNativeCandidates.size(); ++i) {
                                if (this.auditedNativeCandidates.get(i).conflictsWith(nextNative.proof())) return false;
                            }
                        }
                        this.nativeRegions.add(nextNative.region());
                        this.nativeSources.add(nextNative.proof().source());
                        return true;
                    }

                    @Override
                    public boolean maySkipInFenceTail(List<CanonicalBlockEntityCoordinator.Unit> prefix, CanonicalBlockEntityCoordinator.Unit next) {
                        if (next.getClass() != prefix.getFirst().getClass()) {
                            return true;
                        }
                        if (next instanceof NativeInvocation nextNative && this.nativeRegions != null) {
                            // A unit belonging to a region outside the current 8-region gang wave can be skipped
                            // into the next wave without violating intra-region ordering.
                            return !this.nativeRegions.contains(nextNative.region())
                                    && this.nativeRegions.size() >= RegionBlockEntityTickCoordinator.this.engine.workerPool.threadCount();
                        }
                        return false;
                    }

                    @Override
                    public int executePrefix(List<CanonicalBlockEntityCoordinator.Unit> prefix) {
                        this.discardLookahead();
                        hadWorkerPrefix[0] = true;
                        if (prefix.getFirst() instanceof ExecutionInvocation)
                            return executeCanonicalExecutionPrefix(phase, prefix, sample, barrier);
                        if (prefix.getFirst() instanceof ModInvocation)
                            return executeCanonicalModPrefix(phase, prefix, sample, barrier);
                        if (prefix.getFirst() instanceof FurnaceInvocation)
                            return executeCanonicalFurnacePrefix(phase, ordered, prefix, sample, barrier, resources);
                        return executeCanonicalNativePrefix(phase, prefix, sample, barrier);
                    }

                    @Override
                    public void serial(int index) {
                        final Boolean knownEligible = this.lastPreparedIndex == index ? Boolean.valueOf(this.lastPreparedEligible) : null;
                        this.discardLookahead();
                        final var admission = ordered.get(index);
                        phase.execution().reinspectUnstarted(admission, SERIAL_FENCE_INSPECTION);
                        if (furnaces) RegionBlockEntityTickCoordinator.this.engine.furnaceWorkMetrics.serial();
                        RegionBlockEntityTickCoordinator.this.engine.fallbackBlockEntityTicks.incrementAndGet();
                        tickBlockEntitySerial(phase, admission, sample, knownEligible);
                    }
                }, this.engine.canonicalFenceTailEnabled || RegionThreadingConfig.canonicalFenceTailEnabled());
                }
            }
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            if (batch != null) {
                final var phase = batch;
                // All prefix tasks have physically joined before their helpers return or throw.
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.commitInvocationEffects(phase, Long.MAX_VALUE));
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockUpdates(phase));
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyRegionTickMutations(phase.regionTickMutations()));
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.releaseCanonicalNativeRegions(phase));
                if (published && hadWorkerPrefix[0]) {
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.drainGlobalMessages(level));
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> RegionPoiStorages.publishLevel(level));
                }
                if (graph != null) failure = RegionThreadingEngine.runBatchCompletion(failure, graph::finish);
                final boolean executionSuccessful = failure == null;
                for (final var adapter : executionCleanup)
                    failure = RegionThreadingEngine.runBatchCompletion(failure, () -> adapter.phaseClosed(executionSuccessful));
                executionCleanup.clear();
                batch.closeOperations(BlockEntityOperations.Reason.BATCH_CLOSED);
                failure = RegionThreadingEngine.runBatchCompletion(failure, batch.execution()::close);
                batch.completeExternalWaves();
                batch.finish();
                this.engine.activeTickBatches.remove(batch);
                batch.clearExecutingOnCurrentThread();
            }
            final var beCosts = this.engine.activeCanonicalBeRegionCosts;
            this.engine.activeCanonicalBeRegionCosts = null;
            this.engine.activeCanonicalBeDimensionId = null;
            this.engine.activeCanonicalBeChunkRegions = null;
            if (beCosts != null && published) {
                this.flushCanonicalBlockEntityRegionCosts(level, beCosts);
            }
            if (furnaces) this.engine.furnaceWorkMetrics.prepared(furnaceCandidates[0], furnacePreparationNanos[0]);
            if (resourcePhaseStart != 0L) {
                this.engine.blockEntityResourceMetrics.prepared(furnaceCandidates[0], furnacePreparationNanos[0]);
                this.engine.blockEntityResourceMetrics.finished(System.nanoTime() - resourcePhaseStart);
            }
            this.engine.mainThreadPhaseMetrics.record(RegionMainThreadPhaseMetrics.Phase.BE_SERIAL,
                    System.nanoTime() - canonicalBeStart);
            this.engine.regionWorkAdmission.exit();
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    /**
     * Zero-wrapper pure-serial fast path for {@link #tickCanonicalBlockEntities} when no block entity
     * in {@code chunks} is eligible for worker execution. Bypasses {@link BlockEntityExecutionEngine},
     * per-BE {@code InvocationTicket}/{@code Permit} allocations and monitors, {@code blockEntityWorkGraph},
     * and {@link CanonicalBlockEntityCoordinator} while preserving canonical sequence order, overload
     * governor throttling, {@code /forgia fences} accounting, and per-region cost feedback.
     */
    private void tickCanonicalBlockEntitiesPureSerial(
            ServerLevel level,
            List<RegionBlockEntityTickChunk> chunks,
            long canonicalBeStart,
            boolean furnaces) {
        final it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<RegionState> beCosts =
                this.engine.layout.isDynamicCluster() ? new it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<>() : null;
        boolean levelActive = false;
        long executed = 0L;
        this.engine.activeCanonicalPureSerial = true;
        try {
            if (this.engine.regionizer.isLevelClosed(level)) {
                this.engine.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
                return;
            }
            levelActive = true;
            final String dimId = level.dimension().location().toString();
            final var chunkRegions = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<RegionState>(chunks.size());
            final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
            boolean orderedMonotonic = true;
            long lastSeq = -1L;
            for (int c = 0; c < chunks.size(); ++c) {
                final RegionBlockEntityTickChunk chunk = chunks.get(c);
                final List<RegionBlockEntityTickAdmission> admissions = chunk.admissions();
                final RegionState chunkRegion = this.engine.regionizer.get(chunk.region(this.engine.layout, level));
                if (chunkRegion != null && !chunkRegion.isDead()) {
                    chunkRegions.put(chunk.chunkKey(), chunkRegion);
                    if (this.engine.overloadGovernor != null && this.engine.shouldSkipOverloadedRegion(level, chunkRegion)) {
                        this.engine.skippedBlockEntityTicks.addAndGet(admissions.size());
                        continue;
                    }
                }
                for (int a = 0; a < admissions.size(); ++a) {
                    final RegionBlockEntityTickAdmission adm = admissions.get(a);
                    final long seq = adm.sequence();
                    if (seq <= lastSeq) orderedMonotonic = false;
                    lastSeq = seq;
                    ordered.add(adm);
                }
            }
            if (!orderedMonotonic) {
                ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
            }
            long prevSeq = -1L;
            final Set<TickingBlockEntity> seenTickers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>(ordered.size()));
            for (int i = 0, size = ordered.size(); i < size; ++i) {
                final RegionBlockEntityTickAdmission adm = ordered.get(i);
                final long seq = adm.sequence();
                if (seq <= prevSeq) {
                    throw new IllegalArgumentException("Noncanonical invocation ordinals");
                }
                if (!seenTickers.add(adm.ticker())) {
                    throw new IllegalArgumentException("Duplicate canonical ticker membership");
                }
                prevSeq = seq;
            }
            for (int i = 0, size = ordered.size(); i < size; ++i) {
                final RegionBlockEntityTickAdmission admission = ordered.get(i);
                ++executed;
                if (furnaces) this.engine.furnaceWorkMetrics.serial();
                final TickingBlockEntity ticker = admission.ticker();
                if (ticker.isRemoved()) {
                    level.removeCollectedBlockEntityTickers(List.of(ticker));
                    continue;
                }
                if (this.engine.regionizer.isLevelClosed(level)) {
                    this.engine.skippedBlockEntityTicks.incrementAndGet();
                    continue;
                }
                final BlockPos pos = ticker.getPos();
                final long chunkKey = ChunkPos.asLong(pos);
                if (!level.shouldTickBlocksAt(pos) || !level.areEntitiesLoaded(chunkKey)) {
                    continue;
                }
                final long fenceStart = System.nanoTime();
                try {
                    this.runBlockEntityBody(ticker, null, false);
                } finally {
                    final long elapsed = System.nanoTime() - fenceStart;
                    RegionState region = chunkRegions.get(chunkKey);
                    final int regionX;
                    final int regionZ;
                    if (region != null && !region.isDead()) {
                        final RegionCoordinate coord = region.coordinate();
                        regionX = coord.regionX();
                        regionZ = coord.regionZ();
                    } else {
                        final RegionCoordinate coord = this.engine.layout.regionAt(level, pos);
                        regionX = coord.regionX();
                        regionZ = coord.regionZ();
                        region = this.engine.regionizer.get(coord);
                    }
                    final String typeName = ticker.getType();
                    final String tickerClass = (typeName != null && !typeName.isEmpty())
                            ? typeName
                            : ticker.getClass().getName();
                    this.engine.fenceMetrics.recordFence(dimId, regionX, regionZ, tickerClass, elapsed);
                    if (beCosts != null && region != null && !region.isDead()) {
                        beCosts.addTo(region, elapsed);
                    }
                }
            }
        } finally {
            this.engine.activeCanonicalPureSerial = false;
            if (executed > 0L) {
                this.engine.fallbackBlockEntityTicks.addAndGet(executed);
            }
            if (beCosts != null && levelActive) {
                this.flushCanonicalBlockEntityRegionCosts(level, beCosts);
            }
            this.engine.mainThreadPhaseMetrics.record(RegionMainThreadPhaseMetrics.Phase.BE_SERIAL,
                    System.nanoTime() - canonicalBeStart);
            this.engine.regionWorkAdmission.exit();
        }
    }

    private void flushCanonicalBlockEntityRegionCosts(
            ServerLevel level,
            it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap<RegionState> beCosts) {
        final int sliceIndex = RegionTickSlice.BLOCK_ENTITY_TICK.ordinal();
        final long gameTime = level.getGameTime();
        for (final var entry : beCosts.object2LongEntrySet()) {
            final RegionState region = entry.getKey();
            if (region.isDead()) continue;
            final long clamped = Math.clamp(entry.getLongValue(), 1L, 1_000_000_000L);
            region.dispatchCosts().updateAndGet(sliceIndex, estimate -> estimate == 0L ? clamped : (3L * estimate + clamped + 2L) / 4L);
        }
        for (final RegionState region : this.engine.regionizer.regions()) {
            if (region.isDead() || region.coordinate().level() != level || beCosts.containsKey(region)) continue;
            final var costs = region.dispatchCostsOrNull();
            if (costs == null || costs.get(sliceIndex) == 0L) continue;
            final var overload = region.overloadStateOrNull();
            if (overload != null && overload.lastEvaluatedGameTime() == gameTime && overload.skipCurrentTick()) continue;
            costs.updateAndGet(sliceIndex, estimate -> (3L * estimate) / 4L);
        }
    }

    private boolean furnacePrefixIndependent(ServerLevel level, List<CanonicalBlockEntityCoordinator.Unit> units,
            @Nullable FurnaceResourcePreparation resources) {
        final BlockEntityWorkPlanner.ExecutionPlan plan;
        if (resources == null) {
            final List<BlockEntityWorkPlanner.Unit> footprints = new ArrayList<>();
            for (final var unit : units) {
                final var admission = unit.admission();
                footprints.add(new BlockEntityWorkPlanner.Unit(
                        Set.of(new BlockEntityWorkPlanner.Resource(level, BlockEntityWorkPlanner.Kind.CHUNK, ChunkPos.asLong(admission.position()))),
                        Set.of(new BlockEntityWorkPlanner.Resource(level, BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, admission.position().asLong())), false));
            }
            plan = BlockEntityWorkPlanner.planExecution(footprints);
        } else {
            final var claims = units.stream().map(unit -> ((FurnaceInvocation) unit).claims()).toList();
            final var proof = resources.plan(claims);
            if (proof.claims().stream().anyMatch(claim -> !claim.complete())) return false;
            plan = proof.dependencies();
        }
        // A directed plan may legally have holes; a canonical cursor may not. Cut before the first
        // dependent member instead of executing a later independent member around it.
        return plan.waves().size() == 1 && plan.waves().getFirst().size() == units.size();
    }

    private int executeCanonicalExecutionPrefix(RegionBlockEntityTickBatch batch, List<CanonicalBlockEntityCoordinator.Unit> units,
            @Nullable RegionBlockEntityWorkGraph.Phase graph, RegionTickBarrier barrier) {
        final var invocations = units.stream().map(unit -> (ExecutionInvocation) unit).toList();
        final boolean scoped = invocations.getFirst().inspection().descriptor().mode() == Mode.RESOURCE_SCOPED;
        final int groups = Math.min(invocations.size(), this.engine.workerPool.threadCount());
        final List<RegionState> leased = new ArrayList<>();
        final Map<RegionState, BlockEntityExecutionEngine.RegionGuard> guards = new java.util.IdentityHashMap<>();
        BlockEntityResourceOwnership ownership = null;
        final var gate = new NativePrefixGate(groups, invocations.size());
        Throwable failure = null;
        boolean workPhase = false;
        boolean submitted = false;
        try {
            final Set<RegionState> homes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (final var unit : invocations) homes.add(unit.manifest().home());
            for (final var home : RegionLeasePack.canonicalOrder(homes)) {
                if (!this.engine.acquireRegionLease(home, barrier)) return 0;
                leased.add(home);
            }
            batch.externalWaveActive(true);
            this.engine.beginRegionPhase(batch.level());
            workPhase = true;
            for (final var home : leased) this.deliverCanonicalMailbox(batch, home);
            // All adapter callbacks finish while every participant is quiescent, before publishing
            // any worker. A changed branch/footprint refuses this still-unstarted prefix.
            for (final var unit : invocations)
                if (!unit.manifest().fresh() || !batch.eligibleNow(unit.admission())) return 0;
            final Map<RegionState, RandomSource> randoms = new java.util.IdentityHashMap<>();
            if (!scoped) for (final var home : leased) randoms.put(home, batch.canonicalRandom(home));
            final List<BlockEntityResourceOwnership.Lease> grants;
            if (scoped) {
                final Map<Object, java.util.function.BooleanSupplier> boundaries = new java.util.IdentityHashMap<>();
                for (final var home : leased) {
                    guards.put(home, batch.execution().subordinate(home));
                    boundaries.put(home, () -> home.isRunning() && !home.hasOwner() && !home.isDead());
                }
                ownership = new BlockEntityResourceOwnership(batch.level(), boundaries,
                        () -> !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(batch.level()));
                grants = ownership.tryAcquireAll(invocations.stream().map(ExecutionInvocation::claims).toList());
                if (grants == null) return 0;
            } else {
                grants = List.of();
            }
            final var work = new RegionTaskBatch();
            for (int group = 0; group < groups; ++group) {
                final int first = group;
                work.add("Audited original BE invocation group", batch.groupWork(() -> {
                    if (!RegionWorkerScope.isNativeWorkerThread()) {
                        gate.refuse(0);
                        gate.checked.countDown();
                        return;
                    }
                    try {
                        // Check every original binding before any body. Each unit enters its own
                        // one-shot resource Lease only when its actual original ticker starts.
                        for (int index = first; index < invocations.size(); index += groups) {
                            if (index >= gate.admitted.get()) break;
                            if (!invocations.get(index).manifest().workerCurrent() || !batch.tryReserveBlockUpdates()) {
                                gate.refuse(index);
                                break;
                            }
                        }
                    } catch (Throwable failed) {
                        gate.failure.compareAndSet(null, failed);
                        gate.refuse(0);
                    } finally {
                        gate.checked.countDown();
                    }
                    gate.awaitChecks();
                    for (int index = first; index < gate.admitted.get(); index += groups) {
                        if (gate.failure.get() != null) break;
                        final var unit = invocations.get(index);
                        final var home = unit.manifest().home();
                        final var resources = scoped ? grants.get(index) : null;
                        boolean owner = false;
                        BlockEntityExecutionEngine.RegionGuard guard = null;
                        BlockEntityResourceOwnership.Scope scope = null;
                        BlockEntityExecutionEngine.Permit permit = null;
                        boolean completed = false;
                        try {
                            if (scoped) {
                                scope = resources.enter();
                                guard = guards.get(home);
                            } else {
                                home.beginOwnership();
                                owner = true;
                                guard = batch.execution().regionOwner(home);
                                this.engine.currentBlockEntityTickRandom.set(randoms.get(home));
                            }
                            final var admission = unit.admission();
                            permit = batch.execution().permit(batch.execution().ticket(admission), guard,
                                    new Refresh(unit.inspection(), Outcome.CANDIDATE), resources);
                            if (permit == null) throw new IllegalStateException("Execution adapter lost its original pre-start evidence");
                            if (scoped) {
                                try (final var wrapper = CoreModWorkScope.open(unit.manifest())) {
                                    this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                                            batch.level(), home.coordinate(), home, null), () -> this.runBlockEntityBody(admission.ticker(), graph, true));
                                }
                            } else {
                                try (final var wrapper = RegionBlockEntityTickScope.open(admission, home)) {
                                    this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                                            batch.level(), home.coordinate(), home, null), () -> this.runBlockEntityBody(admission.ticker(), graph, true));
                                }
                            }
                            completed = true;
                            ParallelAdapterRuntime.completeBlockEntityTick(admission, unit.inspection().descriptor());
                            this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                        } catch (Throwable failed) {
                            if (this.engine.truncateIfBlockEntityBoundaryViolation(unit.admission(), failed)) {
                                continue;
                            }
                            gate.failure.compareAndSet(null, failed);
                            gate.refuse(0);
                            this.engine.recordWorkerTickFailure(batch, home.coordinate(), null, failed);
                        } finally {
                            try {
                                if (scope != null) scope.close();
                            } finally {
                                try {
                                    if (permit != null) {
                                        if (completed) permit.succeeded();
                                        permit.close();
                                    }
                                } finally {
                                    if (owner) {
                                        this.engine.currentBlockEntityTickRandom.remove();
                                        try {
                                            if (guard != null) guard.close();
                                        } finally {
                                            home.endOwnership();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }));
            }
            final var joined = work.submit(this.engine.workerPool);
            submitted = true;
            batch.activeCanonicalBarrier(joined);
            try {
                barrier.await(() -> {
                    if (this.engine.independentTickScheduler != null) {
                        this.engine.independentTickScheduler.drainCompletedCommits(batch.level(), 500_000L);
                    }
                    return joined.isComplete();
                });
            } catch (Throwable failed) {
                failure = failed;
            } finally {
                joined.awaitCompletion();
                batch.activeCanonicalBarrier(null);
            }
            try {
                joined.throwIfFailed();
            } catch (Throwable failed) {
                failure = RegionThreadingEngine.accumulateFailure(failure, failed);
            }
            failure = RegionThreadingEngine.accumulateFailure(failure, gate.failure.get());
        } catch (Throwable failed) {
            failure = RegionThreadingEngine.accumulateFailure(failure, failed);
        } finally {
            for (final var guard : guards.values()) failure = RegionThreadingEngine.runBatchCompletion(failure, guard::sealForCommit);
            if (ownership != null) failure = RegionThreadingEngine.runBatchCompletion(failure, ownership::close);
            for (final var guard : guards.values()) failure = RegionThreadingEngine.runBatchCompletion(failure, guard::close);
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.commitInvocationEffects(batch, units.getLast().admission().sequence() + 1));
            for (int index = leased.size() - 1; index >= 0; --index) {
                final var home = leased.get(index);
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.releaseRegionLease(home));
            }
            if (workPhase) this.engine.levelTickPhaseState(batch.level()).endRegionPhase();
            batch.externalWaveActive(false);
            failure = RegionThreadingEngine.runBatchCompletion(failure, batch::reportTickFailures);
            if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
        }
        return submitted ? gate.admitted.get() : 0;
    }

    private int executeCanonicalModPrefix(RegionBlockEntityTickBatch batch, List<CanonicalBlockEntityCoordinator.Unit> units,
            @Nullable RegionBlockEntityWorkGraph.Phase graph, RegionTickBarrier barrier) {
        if (units.size() < 2 || !CoreModTickerVerifier.available()) return 0;
        final List<ModInvocation> modUnits = units.stream().map(unit -> (ModInvocation) unit).toList();
        final List<RegionState> leased = new ArrayList<>();
        final Map<RegionState, BlockEntityExecutionEngine.RegionGuard> guards = new java.util.IdentityHashMap<>();
        BlockEntityResourceOwnership ownership = null;
        final List<BlockEntityResourceOwnership.Lease> grants;
        final var gate = new NativePrefixGate(modUnits.size(), modUnits.size());
        Throwable failure = null;
        boolean workPhase = false;
        boolean submitted = false;
        try {
            final Set<RegionState> homes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (final var unit : modUnits) homes.add(unit.manifest().home());
            for (final var home : RegionLeasePack.canonicalOrder(homes)) {
                if (!this.engine.acquireRegionLease(home, barrier)) return 0;
                leased.add(home);
            }
            batch.externalWaveActive(true);
            this.engine.beginRegionPhase(batch.level());
            workPhase = true;
            for (final var home : leased) this.deliverCanonicalMailbox(batch, home);
            for (final var unit : modUnits) if (!unit.manifest().fresh(this.engine) || !unit.manifest().eligible(batch)) return 0;
            final Map<Object, java.util.function.BooleanSupplier> boundaries = new java.util.IdentityHashMap<>();
            for (final var home : leased) {
                Object resourceHome = null;
                for (final var unit : modUnits) if (unit.manifest().home() == home) {
                    resourceHome = unit.manifest().resourceHome();
                    break;
                }
                if (resourceHome == null) throw new IllegalStateException("Missing Mod resource home");
                final var guard = batch.execution().subordinate(home, resourceHome);
                guards.put(home, guard);
                boundaries.put(resourceHome, () -> home.isRunning() && !home.hasOwner() && !home.isDead());
            }
            ownership = new BlockEntityResourceOwnership(batch.level(), boundaries,
                    () -> !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(batch.level()));
            if (!net.neoforged.forgia.runtimeproof.RuntimeProof.beginCoreReservation()) return 0;
            try {
                grants = ownership.tryAcquireAll(modUnits.stream().map(ModInvocation::claims).toList());
            } finally {
                net.neoforged.forgia.runtimeproof.RuntimeProof.endCoreReservation();
            }
            if (grants == null) return 0;
            final var work = new RegionTaskBatch();
            for (int index = 0; index < modUnits.size(); ++index) {
                final int offset = index;
                final var unit = modUnits.get(index);
                final var resources = grants.get(index);
                final var guard = guards.get(unit.manifest().home());
                work.add("Core verified original Mod invocation", batch.groupWork(() -> {
                    if (!RegionWorkerScope.isNativeWorkerThread()) {
                        gate.refuse(0);
                        gate.checked.countDown();
                        return;
                    }
                    boolean checked = false;
                    BlockEntityResourceOwnership.Scope scope = null;
                    BlockEntityExecutionEngine.Permit permit = null;
                    boolean completed = false;
                    try {
                        scope = resources.enter();
                        try {
                            if (!unit.manifest().workerCurrent() || !batch.tryReserveBlockUpdates()) gate.refuse(offset);
                        } catch (Throwable failed) {
                            gate.failure.compareAndSet(null, failed);
                            gate.refuse(0);
                        } finally {
                            checked = true;
                            gate.checked.countDown();
                        }
                        gate.awaitChecks();
                        if (gate.failure.get() != null || offset >= gate.admitted.get()) return;
                        final var admission = unit.admission();
                        permit = batch.execution().permit(batch.execution().ticket(admission), guard,
                                new Refresh(unit.inspection(), Outcome.CANDIDATE), resources);
                        if (permit == null) throw new IllegalStateException("Mod original Ticket lost its pre-start proof");
                        try (final var wrapper = CoreModWorkScope.open(unit.manifest())) {
                            this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                                    batch.level(), unit.manifest().home().coordinate(), unit.manifest().home(), null),
                                    () -> this.runBlockEntityBody(admission.ticker(), graph, true));
                        }
                        completed = true;
                        this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                    } catch (Throwable failed) {
                        if (this.engine.truncateIfBlockEntityBoundaryViolation(unit.admission(), failed)) {
                            return;
                        }
                        gate.failure.compareAndSet(null, failed);
                        gate.refuse(0);
                        this.engine.recordWorkerTickFailure(batch, unit.manifest().home().coordinate(), null, failed);
                    } finally {
                        if (!checked) gate.checked.countDown();
                        try {
                            if (scope != null) scope.close();
                        } finally {
                            if (permit != null) {
                                if (completed) permit.succeeded();
                                permit.close();
                            }
                        }
                    }
                }));
            }
            final var joined = work.submit(this.engine.workerPool);
            submitted = true;
            batch.activeCanonicalBarrier(joined);
            try {
                barrier.await(() -> {
                    if (this.engine.independentTickScheduler != null) {
                        this.engine.independentTickScheduler.drainCompletedCommits(batch.level(), 500_000L);
                    }
                    return joined.isComplete();
                });
            } catch (Throwable failed) {
                failure = failed;
            } finally {
                joined.awaitCompletion();
                batch.activeCanonicalBarrier(null);
            }
            try {
                joined.throwIfFailed();
            } catch (Throwable failed) {
                failure = RegionThreadingEngine.accumulateFailure(failure, failed);
            }
            failure = RegionThreadingEngine.accumulateFailure(failure, gate.failure.get());
        } catch (Throwable failed) {
            failure = RegionThreadingEngine.accumulateFailure(failure, failed);
        } finally {
            for (final var guard : guards.values()) failure = RegionThreadingEngine.runBatchCompletion(failure, guard::sealForCommit);
            if (ownership != null) failure = RegionThreadingEngine.runBatchCompletion(failure, ownership::close);
            for (final var guard : guards.values()) failure = RegionThreadingEngine.runBatchCompletion(failure, guard::close);
            for (int index = leased.size() - 1; index >= 0; --index) {
                final var region = leased.get(index);
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.releaseRegionLease(region));
            }
            if (workPhase) this.engine.levelTickPhaseState(batch.level()).endRegionPhase();
            batch.externalWaveActive(false);
            failure = RegionThreadingEngine.runBatchCompletion(failure, batch::reportTickFailures);
            if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
        }
        return submitted ? gate.admitted.get() : 0;
    }

    private int executeCanonicalFurnacePrefix(RegionBlockEntityTickBatch batch, List<RegionBlockEntityTickAdmission> ordered,
            List<CanonicalBlockEntityCoordinator.Unit> units, @Nullable RegionBlockEntityWorkGraph.Phase graph,
            RegionTickBarrier barrier, @Nullable FurnaceResourcePreparation resources) {
        final int count = units.size();
        if (count < 2) {
            this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.SINGLE_UNIT);
            return 0;
        }
        final List<RegionBlockEntityTickAdmission> waveOrdered = new ArrayList<>(count);
        final List<FurnaceWorkPreparation.Candidate> candidates = new ArrayList<>(count);
        final List<BlockEntityResourceOwnership.Claims> claims = resources == null ? List.of() : new ArrayList<>(count);
        final List<Integer> wave = new ArrayList<>(count);
        for (int i = 0; i < count; ++i) {
            final var furnace = (FurnaceInvocation) units.get(i);
            waveOrdered.add(furnace.admission());
            candidates.add(furnace.candidate());
            if (resources != null) claims.add(furnace.claims());
            wave.add(i);
        }
        final List<FurnaceWorkResult> results = new ArrayList<>(count);
        batch.externalWaveActive(true);
        this.engine.beginRegionPhase(batch.level());
        try {
            // The existing bridge still performs a whole-wave fresh audit, alias pass, every validator
            // and all-or-none reservation before any original body. It uses this phase's same kernel.
            this.executeFurnaceWave(batch, waveOrdered, candidates, wave, results, graph, barrier, resources, claims, ordered);
            batch.reportTickFailures();
            if (results.isEmpty()) {
                this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.REVALIDATION);
                return 0;
            }
            return count;
        } finally {
            this.engine.levelTickPhaseState(batch.level()).endRegionPhase();
            batch.externalWaveActive(false);
        }
    }

    private int executeCanonicalNativePrefix(RegionBlockEntityTickBatch batch, List<CanonicalBlockEntityCoordinator.Unit> units,
            @Nullable RegionBlockEntityWorkGraph.Phase graph, RegionTickBarrier barrier) {
        final Map<RegionState, List<NativeEntry>> groups = new java.util.IdentityHashMap<>();
        boolean allSandboxed = this.engine.isIndependentRegionTickEnabled();
        for (int offset = 0; offset < units.size(); ++offset) {
            final var unit = (NativeInvocation) units.get(offset);
            if (allSandboxed && !(unit.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate)) {
                allSandboxed = false;
            }
            groups.computeIfAbsent(unit.region(), unused -> new ArrayList<>()).add(new NativeEntry(offset, unit));
        }
        if (allSandboxed) {
            return this.dispatchIndependentNativeWave(batch, groups, units.size(), graph);
        }
        this.engine.workerPool.preWake(groups.size());
        final var gate = new NativePrefixGate(groups.size(), units.size());
        Throwable failure = null;
        boolean workPhase = false;
        try {
            boolean leasedAll = true;
            for (final var region : RegionLeasePack.canonicalOrder(groups.keySet())) {
                if (!this.engine.acquireRegionLease(region, barrier)) {
                    gate.refuse(0);
                    leasedAll = false;
                    break;
                }
                batch.holdCoordinatorRegion(region);
            }
            if (leasedAll) {
                batch.externalWaveActive(true);
                this.engine.beginRegionPhase(batch.level());
                workPhase = true;
                for (final var region : RegionLeasePack.canonicalOrder(groups.keySet())) {
                    this.deliverCanonicalMailbox(batch, region);
                }
                final RegionTaskBatch work = new RegionTaskBatch();
                for (final var entry : groups.entrySet()) {
                    final var region = entry.getKey();
                    final RandomSource random = batch.canonicalRandom(region);
                    work.add("canonical native BE prefix", batch.groupWork(() -> this.runCanonicalNativeGroup(batch, region,
                            random, entry.getValue(), gate, graph)));
                }
                final var joined = work.submit(this.engine.workerPool);
                batch.activeCanonicalBarrier(joined);
                try {
                    barrier.await(() -> {
                        if (this.engine.independentTickScheduler != null) {
                            this.engine.independentTickScheduler.drainCompletedCommits(batch.level(), 500_000L);
                        }
                        batch.servicePendingServerWork();
                        return joined.isComplete();
                    });
                } catch (Throwable throwable) {
                    failure = throwable;
                } finally {
                    joined.awaitCompletion();
                    batch.activeCanonicalBarrier(null);
                }
                try {
                    joined.throwIfFailed();
                } catch (Throwable throwable) {
                    failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
                }
                failure = RegionThreadingEngine.accumulateFailure(failure, gate.failure.get());
            }
        } catch (Throwable throwable) {
            failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
        } finally {
            if (workPhase) this.engine.levelTickPhaseState(batch.level()).endRegionPhase();
            batch.externalWaveActive(false);
            final long before = units.getLast().admission().sequence() + 1;
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.commitInvocationEffects(batch, before));
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyDeferredBlockUpdates(batch));
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.applyRegionTickMutations(batch.regionTickMutations()));
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.releaseCanonicalNativeRegions(batch));
            failure = RegionThreadingEngine.runBatchCompletion(failure, batch::reportTickFailures);
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
        return gate.admitted.get();
    }

    private int dispatchIndependentNativeWave(
            RegionBlockEntityTickBatch batch,
            Map<RegionState, List<NativeEntry>> groups,
            int totalUnits,
            @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        final long now = System.nanoTime();
        final ServerLevel level = batch.level();
        final long gameTime = level.getGameTime();
        this.engine.workerPool.preWake(groups.size());
        for (final var entry : groups.entrySet()) {
            final RegionState region = entry.getKey();
            final List<NativeEntry> entries = entry.getValue();
            final RandomSource random = batch.canonicalRandom(region);
            final Queue<Runnable> mutations = new ConcurrentLinkedQueue<>();
            this.engine.independentTickScheduler.submitIfDue(region, () -> {
                this.engine.currentBlockEntityTickRandom.set(random);
                this.engine.currentRegionTickMutations.set(mutations);
                try {
                    for (int i = 0, size = entries.size(); i < size; ++i) {
                        final var unit = entries.get(i).invocation();
                        final var admission = unit.admission();
                        if (!unit.proof().fresh()) {
                            continue;
                        }
                        if (unit.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate candidate) {
                            final net.minecraft.world.level.block.entity.BlockEntity be = candidate.chunk().getBlockEntities().get(admission.position());
                            final boolean instanceAllowed = (be == null
                                    && !net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isTransientSandboxedFallback(level, admission.position()))
                                    || (be != null && !be.isRemoved()
                                            && be.getLevel() == admission.level()
                                            && net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedBlockEntityInstance(admission.type(), be));
                            if (!instanceAllowed) {
                                final var ticker = admission.ticker();
                                net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(level, () -> {
                                    if (!ticker.isRemoved()) {
                                        ticker.tick();
                                    }
                                });
                                continue;
                            }
                        }
                        try (final var scope = RegionBlockEntityTickScope.open(admission, region)) {
                            this.runBlockEntityBody(admission.ticker(), graph, true);
                            this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                        } catch (Throwable throwable) {
                            if (RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)
                                    || this.engine.truncateIfBlockEntityBoundaryViolation(admission, throwable)) {
                                continue;
                            }
                            this.engine.demoteSandboxedBlockEntityOnError(admission, throwable);
                            continue;
                        }
                    }
                } finally {
                    this.engine.currentRegionTickMutations.remove();
                    this.engine.currentBlockEntityTickRandom.remove();
                }
            }, () -> {
                if (!mutations.isEmpty() && !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                    this.engine.applyRegionTickMutations(mutations);
                }
            }, now, gameTime);
        }
        return totalUnits;
    }

    private void tickIndexedSandboxBlockEntities(ServerLevel level, List<RegionBlockEntityTickChunk> chunks) {
        if (this.engine.shutdown || this.engine.regionizer.isLevelClosed(level)) {
            this.engine.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
            return;
        }
        final Map<RegionState, List<RegionBlockEntityTickChunk>> sandboxByRegion = new java.util.IdentityHashMap<>();
        final List<RegionBlockEntityTickAdmission> serialAdmissions = new ArrayList<>();
        final List<RegionFenceMetrics.Reason> serialReasons = new ArrayList<>();
        for (int i = 0, size = chunks.size(); i < size; ++i) {
            final RegionBlockEntityTickChunk chunk = chunks.get(i);
            final RegionBlockEntityTickChunkCache cache = chunk.cache();
            if (cache == null) {
                for (int a = 0, aSize = chunk.admissions().size(); a < aSize; ++a) {
                    serialAdmissions.add(chunk.admissions().get(a));
                    serialReasons.add(RegionFenceMetrics.Reason.CACHE_NULL);
                }
                continue;
            }
            final RegionState region = this.engine.regionizer.getOrCreate(chunk.region(this.engine.layout, level));
            if (region == null || region.isDead()) {
                for (int a = 0, aSize = chunk.admissions().size(); a < aSize; ++a) {
                    serialAdmissions.add(chunk.admissions().get(a));
                    serialReasons.add(RegionFenceMetrics.Reason.DEAD_REGION);
                }
                continue;
            }
            if (this.engine.overloadGovernor != null && this.engine.shouldSkipOverloadedRegion(level, region)) {
                this.engine.skippedBlockEntityTicks.addAndGet(chunk.admissions().size());
                continue;
            }
            if (!cache.sandboxedAdmissions().isEmpty()) {
                final var chunkPos = chunk.chunk().getPos();
                if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.chunkNeedsSynchronousSandboxSplit(
                        level, chunkPos.x, chunkPos.z)) {
                    final List<RegionBlockEntityTickAdmission> rawSandboxed = cache.sandboxedAdmissions();
                    boolean anySync = false;
                    for (int a = 0, aSize = rawSandboxed.size(); a < aSize; ++a) {
                        final RegionBlockEntityTickAdmission admission = rawSandboxed.get(a);
                        final BlockPos pos = admission.position();
                        if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isNearServerThreadEntity(level, pos)) {
                            serialAdmissions.add(admission);
                            serialReasons.add(RegionFenceMetrics.Reason.NEAR_ENTITY);
                            anySync = true;
                        } else if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isTransientSandboxedFallback(level, pos)) {
                            serialAdmissions.add(admission);
                            serialReasons.add(RegionFenceMetrics.Reason.TRANSIENT_COOLDOWN);
                            anySync = true;
                        }
                    }
                    if (!anySync || rawSandboxed.size() > 1) {
                        sandboxByRegion.computeIfAbsent(region, unused -> new ArrayList<>()).add(chunk);
                    }
                } else {
                    sandboxByRegion.computeIfAbsent(region, unused -> new ArrayList<>()).add(chunk);
                }
            }
            if (!cache.nonSandboxedAdmissions().isEmpty()) {
                final List<RegionBlockEntityTickAdmission> nonSandboxed = cache.nonSandboxedAdmissions();
                for (int a = 0, aSize = nonSandboxed.size(); a < aSize; ++a) {
                    serialAdmissions.add(nonSandboxed.get(a));
                    serialReasons.add(RegionFenceMetrics.Reason.NON_SANDBOXED);
                }
            }
        }
        if (this.engine.unifiedTickCoordinator.isUnifiedLoopActive(level)
                || this.engine.unifiedTickCoordinator.hasStagedWork(level)) {
            this.engine.unifiedTickCoordinator.dispatchUnifiedLevelTick(level, sandboxByRegion);
        } else if (!sandboxByRegion.isEmpty()) {
            final long now = System.nanoTime();
            final long gameTime = level.getGameTime();
            final boolean borderUpdates = this.engine.borderUpdatesEnabled();
            final List<RegionIndependentTickScheduler.AsyncBatchItem> beItems = new ArrayList<>(sandboxByRegion.size());
            for (final var entry : sandboxByRegion.entrySet()) {
                final RegionState region = entry.getKey();
                final List<RegionBlockEntityTickChunk> regionChunks = entry.getValue();
                final RandomSource random = RandomSource.create(
                        level.getSeed() ^ gameTime ^ ((long) region.coordinate().regionX() << 32) ^ region.coordinate().regionZ());
                final Queue<Runnable> mutations = new ConcurrentLinkedQueue<>();
                final Runnable body = () -> this.runUnifiedRegionBlockEntities(
                        level, region, regionChunks, random, mutations, borderUpdates);
                final Runnable commit = () -> {
                    if (!mutations.isEmpty() && !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level)) {
                        this.engine.applyRegionTickMutations(mutations);
                    }
                };
                beItems.add(new RegionIndependentTickScheduler.AsyncBatchItem(
                        region, "independent region tick", body, commit, true, now, gameTime));
            }
            this.engine.independentTickScheduler.submitBatchOptimized(beItems);
        }
        if (!serialAdmissions.isEmpty()) {
            final String dimensionId = level.dimension().location().toString();
            for (int i = 0, size = serialAdmissions.size(); i < size; ++i) {
                final RegionBlockEntityTickAdmission admission = serialAdmissions.get(i);
                final RegionFenceMetrics.Reason reason = serialReasons.get(i);
                final var ticker = admission.ticker();
                if (ticker.isRemoved()) {
                    continue;
                }
                this.engine.fallbackBlockEntityTicks.incrementAndGet();
                final long start = System.nanoTime();
                try {
                    ticker.tick();
                } finally {
                    final long elapsed = System.nanoTime() - start;
                    final RegionCoordinate coord = this.engine.layout.regionAt(level, admission.position());
                    this.engine.fenceMetrics.recordFence(dimensionId, coord.regionX(), coord.regionZ(), admission.type(), elapsed, reason);
                }
            }
        }
    }

    /**
     * Executes Stage 3 (or standalone independent region BE ticks) for {@code region} on the current
     * region worker thread.
     */
    void runUnifiedRegionBlockEntities(
            ServerLevel level,
            RegionState region,
            List<RegionBlockEntityTickChunk> regionChunks,
            @Nullable RandomSource random,
            Queue<Runnable> mutations,
            boolean borderUpdates) {
        if (random != null) {
            this.engine.currentBlockEntityTickRandom.set(random);
        }
        this.engine.currentRegionTickMutations.set(mutations);
        try {
            if (regionChunks.size() == 1) {
                final RegionBlockEntityTickChunk chunk = regionChunks.get(0);
                final List<RegionBlockEntityTickAdmission> admissions = chunk.cache().sandboxedAdmissions();
                if (!chunk.isCurrent(this.engine.layout, borderUpdates)) {
                    for (int a = 0, aSize = admissions.size(); a < aSize; ++a) {
                        final var ticker = admissions.get(a).ticker();
                        mutations.add(() -> {
                            if (!ticker.isRemoved()) {
                                ticker.tick();
                            }
                        });
                    }
                    return;
                }
                final var chunkBlockEntities = chunk.chunk().getBlockEntities();
                for (int a = 0, aSize = admissions.size(); a < aSize; ++a) {
                    this.runSingleSandboxedAdmission(level, region, chunkBlockEntities, admissions.get(a), mutations);
                }
            } else {
                record OrderedAdmission(
                        java.util.Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> chunkBlockEntities,
                        RegionBlockEntityTickAdmission admission) {}
                int total = 0;
                for (int c = 0, cSize = regionChunks.size(); c < cSize; ++c) {
                    total += regionChunks.get(c).cache().sandboxedAdmissions().size();
                }
                final List<OrderedAdmission> ordered = new ArrayList<>(total);
                for (int c = 0, cSize = regionChunks.size(); c < cSize; ++c) {
                    final RegionBlockEntityTickChunk chunk = regionChunks.get(c);
                    final List<RegionBlockEntityTickAdmission> admissions = chunk.cache().sandboxedAdmissions();
                    if (!chunk.isCurrent(this.engine.layout, borderUpdates)) {
                        for (int a = 0, aSize = admissions.size(); a < aSize; ++a) {
                            final var ticker = admissions.get(a).ticker();
                            mutations.add(() -> {
                                if (!ticker.isRemoved()) {
                                    ticker.tick();
                                }
                            });
                        }
                        continue;
                    }
                    final var chunkBlockEntities = chunk.chunk().getBlockEntities();
                    for (int a = 0, aSize = admissions.size(); a < aSize; ++a) {
                        ordered.add(new OrderedAdmission(chunkBlockEntities, admissions.get(a)));
                    }
                }
                ordered.sort(java.util.Comparator.comparingLong(entry -> entry.admission().sequence()));
                for (int i = 0, size = ordered.size(); i < size; ++i) {
                    final OrderedAdmission entry = ordered.get(i);
                    this.runSingleSandboxedAdmission(level, region, entry.chunkBlockEntities(), entry.admission(), mutations);
                }
            }
        } finally {
            this.engine.currentRegionTickMutations.remove();
            if (random != null) {
                this.engine.currentBlockEntityTickRandom.remove();
            }
        }
    }

    private void runSingleSandboxedAdmission(
            ServerLevel level,
            RegionState region,
            java.util.Map<BlockPos, net.minecraft.world.level.block.entity.BlockEntity> chunkBlockEntities,
            RegionBlockEntityTickAdmission admission,
            Queue<Runnable> mutations) {
        final var ticker = admission.ticker();
        if (ticker.isRemoved()) {
            return;
        }
        // Skip BEs already routed synchronously to serialAdmissions on this tick.
        if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isNearServerThreadEntity(level, admission.position())
                || net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isTransientSandboxedFallback(level, admission.position())) {
            return;
        }
        final net.minecraft.world.level.block.entity.BlockEntity be = chunkBlockEntities.get(admission.position());
        final boolean instanceAllowed = be == null
                || (!be.isRemoved()
                        && be.getLevel() == level
                        && net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedBlockEntityInstance(admission.type(), be));
        if (!instanceAllowed) {
            mutations.add(() -> {
                if (!ticker.isRemoved()) {
                    ticker.tick();
                }
            });
            return;
        }
        try (final var scope = RegionBlockEntityTickScope.open(admission, region)) {
            this.runBlockEntityBody(ticker, null, true);
            this.engine.blockEntityTicksOnWorkers.incrementAndGet();
        } catch (Throwable throwable) {
            if (RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)
                    || this.engine.truncateIfBlockEntityBoundaryViolation(admission, throwable)) {
                return;
            }
            this.engine.demoteSandboxedBlockEntityOnError(admission, throwable);
        }
    }

    private void deliverCanonicalMailbox(RegionBlockEntityTickBatch batch, RegionState region) {
        if (!batch.firstCanonicalMailboxDelivery(region)) return;
        region.beginOwnership();
        try {
            // Unknown message callbacks run entirely on the server before the whole-prefix fresh
            // checks. They never inherit a Native Permit or run concurrently with a body.
            this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                    batch.level(), region.coordinate(), region, null), this.engine.withRegionMailboxDelivery(batch.level(), region, () -> {}));
        } finally {
            region.endOwnership();
        }
    }

    private void runCanonicalNativeGroup(RegionBlockEntityTickBatch batch, RegionState region, RandomSource random,
            List<NativeEntry> entries, NativePrefixGate gate, @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        BlockEntityExecutionEngine.RegionGuard guard = null;
        boolean owner = false;
        try {
            try {
                if (!RegionWorkerScope.isNativeWorkerThread()) {
                    // A rejection is known before any body. It cannot block the submitter waiting for
                    // checks of groups that have not even been submitted yet.
                    gate.refuse(0);
                    return;
                }
                region.beginOwnership();
                owner = true;
                guard = batch.execution().regionOwner(region);
                this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                        batch.level(), region.coordinate(), region, null), () -> {
                    for (int i = 0; i < entries.size(); ++i) {
                        final var entry = entries.get(i);
                        if (entry.offset() >= gate.admitted.get()) break;
                        final var unit = entry.invocation();
                        final var admission = unit.admission();
                        if (!unit.proof().fresh() || !batch.tryReserveBlockUpdates()) {
                            gate.refuse(entry.offset());
                            break;
                        }
                        if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED) {
                            try (final var scope = RegionBlockEntityTickScope.open(admission, region)) {
                                if (!ParallelAdapterRuntime.admitBlockEntityTick(admission, unit.inspection().descriptor())
                                        || !unit.proof().fresh()) {
                                    gate.refuse(entry.offset());
                                    break;
                                }
                            }
                        }
                    }
                });
            } catch (Throwable throwable) {
                gate.failure.compareAndSet(null, throwable);
                gate.refuse(0);
            } finally {
                gate.checked.countDown();
            }
            gate.awaitChecks();
            if (gate.failure.get() != null || gate.admitted.get() == 0) return;
            final var authority = guard;
            this.engine.currentBlockEntityTickRandom.set(random);
            this.engine.currentRegionTickMutations.set(batch.regionTickMutations());
            this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, batch.level(),
                    region.coordinate(), region, null), () -> {
                        for (final var entry : entries) {
                            if (entry.offset() >= gate.admitted.get()) break;
                            final var unit = entry.invocation();
                            final var admission = unit.admission();
                            if (unit.proof() instanceof NativeBlockEntityPreparation.RegionSandboxedCandidate candidate) {
                                final net.minecraft.world.level.block.entity.BlockEntity be = candidate.chunk().getBlockEntities().get(admission.position());
                                final boolean instanceAllowed = (be == null
                                        && !net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isTransientSandboxedFallback(admission.level(), admission.position()))
                                        || (be != null && !be.isRemoved()
                                                && be.getLevel() == admission.level()
                                                && net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedBlockEntityInstance(admission.type(), be));
                                if (!instanceAllowed) {
                                    final var ticker = admission.ticker();
                                    net.neoforged.neoforge.server.threading.api.RegionAdapterApi.postGlobal(admission.level(), () -> {
                                        if (!ticker.isRemoved()) {
                                            ticker.tick();
                                        }
                                    });
                                    continue;
                                }
                                try (final var scope = RegionBlockEntityTickScope.open(admission, region)) {
                                    this.runBlockEntityBody(admission.ticker(), graph, true);
                                    this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                                } catch (Throwable throwable) {
                                    if (RegionWorkerChunkUnavailableException.isChunkUnavailable(throwable)
                                            || this.engine.truncateIfBlockEntityBoundaryViolation(admission, throwable)) {
                                        continue;
                                    }
                                    this.engine.demoteSandboxedBlockEntityOnError(admission, throwable);
                                    continue;
                                }
                                continue;
                            }
                            try (final var scope = RegionBlockEntityTickScope.open(admission, region);
                                    final var permit = batch.execution().permit(batch.execution().ticket(admission), authority,
                                            new Refresh(unit.inspection(), Outcome.CANDIDATE), null)) {
                                if (permit == null) throw new IllegalStateException("Invocation changed after closed-prefix validation");
                                this.runBlockEntityBody(admission.ticker(), graph, true);
                                if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED)
                                    ParallelAdapterRuntime.completeBlockEntityTick(admission, unit.inspection().descriptor());
                                permit.succeeded();
                                this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                            } catch (Throwable throwable) {
                                if (this.engine.truncateIfBlockEntityBoundaryViolation(admission, throwable)) {
                                    continue;
                                }
                                this.engine.recordWorkerTickFailure(batch, region.coordinate(), null, throwable);
                                break;
                            }
                        }
                    });
        } finally {
            this.engine.currentRegionTickMutations.remove();
            this.engine.currentBlockEntityTickRandom.remove();
            try {
                if (guard != null) guard.close();
            } finally {
                if (owner) region.endOwnership();
                // The coordinator retains the very same outer lease through physical join and commit.
            }
        }
    }

    private void commitInvocationEffects(BlockEntityExecutionBatch batch, long beforeOrdinal) {
        Throwable failure = null;
        for (final var effect : batch.execution().takeInvocationEffects(beforeOrdinal)) {
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> {
                final var admission = effect.admission();
                final var region = this.engine.regionizer.get(this.engine.layout.regionAt(batch.level(), admission.position()));
                if (region == null || !region.isRunning()) throw new IllegalStateException("Invocation commit lost its original outer region lease");
                region.beginOwnership();
                try {
                    this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                            batch.level(), region.coordinate(), region, null), effect.action());
                } finally {
                    region.endOwnership();
                }
            });
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    private void releaseCanonicalNativeRegions(RegionBlockEntityTickBatch batch) {
        Throwable failure = null;
        for (final var region : batch.heldRegions()) {
            // Drop only this batch's held reference, never release it again after a later re-acquisition.
            batch.forgetLease(region);
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.releaseRegionLease(region));
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    private static final AdapterDescriptor FURNACE_EXECUTION = new AdapterDescriptor(ResourceLocation.parse("forgia:furnace_waves"),
            Set.of(ResourceLocation.parse("minecraft:furnace"), ResourceLocation.parse("minecraft:blast_furnace"),
                    ResourceLocation.parse("minecraft:smoker")),
            Mode.RESOURCE_SCOPED);

    /** Execute current-operation resource waves; the historical shadow graph is diagnostics only. */
    private void tickFurnaceWorkWaves(ServerLevel level, List<RegionBlockEntityTickChunk> chunks, RegionTickBarrier barrier, boolean canonicalSequence) {
        final long resourcePhaseStart = this.engine.blockEntityResourcesEnabled ? System.nanoTime() : 0L;
        final FurnaceWorkBatch batch = new FurnaceWorkBatch(level, chunks,
                () -> !this.engine.regionizer.isLevelClosed(level), pos -> this.engine.regionizer.get(this.engine.layout.regionAt(level, pos)));
        batch.canonicalSequence(canonicalSequence);
        final TickBatchPublication publication = this.engine.publishTickBatch(batch, level);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            this.engine.skippedBlockEntityTicks.addAndGet(countBlockEntityTickers(chunks));
            batch.execution().close();
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            try {
                this.tickBlockEntityChunksInline(batch, chunks, null);
            } finally {
                batch.execution().close();
            }
            return;
        }
        batch.markExecutingOnCurrentThread();
        RegionBlockEntityWorkGraph.Phase graph = null;
        Throwable failure = null;
        try {
            final long prepareStart = System.nanoTime();
            graph = this.engine.blockEntityWorkGraph.begin(level, chunks);
            final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
            for (final var chunk : chunks) ordered.addAll(chunk.admissions());
            ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
            if (ordered.size() > RegionBlockEntityWorkGraph.MAX_UNITS) {
                this.engine.furnaceWorkMetrics.prepared(0, System.nanoTime() - prepareStart);
                this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.OVERSIZED_PHASE);
                for (final var admission : ordered) this.tickFurnaceSerial(batch, admission, graph);
            } else {
                final var resources = this.engine.blockEntityResourcesEnabled ? new FurnaceResourcePreparation(level, this.engine.layout) : null;
                final var preparation = new FurnaceWorkPreparation(level, this.engine.layout, resources != null);
                final var aliased = FurnaceWorkPreparation.aliased(ordered);
                final List<FurnaceWorkPreparation.Candidate> candidates = new ArrayList<>();
                final List<BlockEntityWorkPlanner.Unit> footprints = new ArrayList<>();
                final List<BlockEntityResourceOwnership.Claims> claims = resources == null ? List.of() : new ArrayList<>();
                long proofNanos = 0L;
                int admitted = 0;
                for (final var admission : ordered) {
                    final var candidate = batch.eligibleNow(admission) ? preparation.prepare(admission) : null;
                    final boolean eligible = candidate != null && !aliased.contains(candidate.source());
                    if (candidate == null) this.engine.furnaceWorkMetrics.refused(preparation.refusal());
                    else if (!eligible) this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.SHARED_INVENTORY);
                    candidates.add(eligible ? candidate : null);
                    batch.execution().inspect(admission, eligible
                            ? new Inspection(FURNACE_EXECUTION, resources == null ? Resources.FURNACE_LOCAL : Resources.FURNACE_CLAIMS, Outcome.CANDIDATE)
                            : Inspection.serialFence());
                    if (eligible) ++admitted;
                    if (resources == null) {
                        footprints.add(new BlockEntityWorkPlanner.Unit(
                                Set.of(new BlockEntityWorkPlanner.Resource(level, BlockEntityWorkPlanner.Kind.CHUNK, ChunkPos.asLong(admission.position()))),
                                Set.of(new BlockEntityWorkPlanner.Resource(level, BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, admission.position().asLong())), !eligible));
                    } else {
                        final long proofStart = System.nanoTime();
                        claims.add(eligible ? resources.captureForPlan(candidate) : resources.fence(admission));
                        proofNanos += System.nanoTime() - proofStart;
                    }
                }
                if (resources != null && resources.budgetExceeded()) {
                    for (int index = 0; index < ordered.size(); ++index) claims.set(index, resources.fence(ordered.get(index)));
                }
                final long planStart = resources == null ? 0L : System.nanoTime();
                final var resourcePlan = resources == null ? null : resources.plan(claims);
                final var plan = resourcePlan == null ? BlockEntityWorkPlanner.planExecution(footprints) : resourcePlan.dependencies();
                if (resourcePlan != null) {
                    for (int index = 0; index < candidates.size(); ++index) {
                        if (candidates.get(index) != null && !resourcePlan.claims().get(index).complete()) {
                            candidates.set(index, null);
                            --admitted;
                            this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.RESOURCE_PROOF);
                        }
                    }
                    this.engine.blockEntityResourceMetrics.prepared(admitted, proofNanos + System.nanoTime() - planStart);
                }
                this.engine.furnaceWorkMetrics.prepared(admitted, System.nanoTime() - prepareStart);
                for (final var wave : plan.waves()) {
                    if (wave.size() == 1 || candidates.get(wave.getFirst()) == null) {
                        if (candidates.get(wave.getFirst()) != null) this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.SINGLE_UNIT);
                        for (final int index : wave) this.tickFurnaceSerial(batch, ordered.get(index), graph);
                        continue;
                    }
                    final RegionBlockEntityWorkGraph.Phase sample = graph;
                    final List<FurnaceWorkResult> results = new ArrayList<>();
                    try {
                        batch.externalWaveActive(true);
                        this.engine.runRegionBatch(level, () -> this.executeFurnaceWave(batch, ordered, candidates, wave, results, sample, barrier,
                                resources, resourcePlan == null ? List.of() : resourcePlan.claims(), ordered));
                    } finally {
                        batch.externalWaveActive(false);
                    }
                    if (results.isEmpty()) {
                        this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.REVALIDATION);
                        for (final int index : wave) this.tickFurnaceSerial(batch, ordered.get(index), graph);
                    }
                    batch.reportTickFailures();
                }
            }
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            if (graph != null) failure = RegionThreadingEngine.runBatchCompletion(failure, graph::finish);
            failure = RegionThreadingEngine.runBatchCompletion(failure, batch.execution()::close);
            batch.completeExternalWaves();
            batch.finish();
            this.engine.activeTickBatches.remove(batch);
            batch.clearExecutingOnCurrentThread();
            if (resourcePhaseStart != 0L) this.engine.blockEntityResourceMetrics.finished(System.nanoTime() - resourcePhaseStart);
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
        batch.throwIfFailed();
    }

    /** Outer region exclusion spans preparation, actual bodies and canonical notification commits. */
    private void executeFurnaceWave(BlockEntityExecutionBatch batch, List<RegionBlockEntityTickAdmission> ordered,
            List<FurnaceWorkPreparation.Candidate> candidates, List<Integer> wave, List<FurnaceWorkResult> results,
            @Nullable RegionBlockEntityWorkGraph.Phase sample, RegionTickBarrier barrier,
            @Nullable FurnaceResourcePreparation resources, List<BlockEntityResourceOwnership.Claims> claims,
            List<RegionBlockEntityTickAdmission> aliasOrder) {
        final ServerLevel level = batch.level();
        final List<RegionState> leased = new ArrayList<>();
        final Map<RegionState, BlockEntityExecutionEngine.RegionGuard> executionRegions = new java.util.IdentityHashMap<>();
        BlockEntityResourceOwnership ownership = null;
        long revalidationStart = 0L;
        Throwable failure = null;
        try {
            this.engine.workerPool.preWake(Math.min(wave.size(), this.engine.workerPool.threadCount()));
            final Set<RegionState> regions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (final int index : wave) {
                final var region = this.engine.regionizer.getOrCreate(this.engine.layout.regionAt(level, ordered.get(index).position()));
                if (region == null || region.isDead()) throw new IllegalStateException("Furnace work region retired before leasing");
                regions.add(region);
            }
            for (final var region : RegionLeasePack.canonicalOrder(regions)) {
                if (!this.engine.acquireRegionLease(region, barrier)) throw new IllegalStateException("Furnace work region retired during leasing");
                leased.add(region);
                if (batch instanceof RegionBlockEntityTickBatch canonical) this.deliverCanonicalMailbox(canonical, region);
                else {
                    region.beginOwnership();
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level,
                                region.coordinate(), region, null), this.engine.withRegionMailboxDelivery(level, region, () -> {}));
                    } finally {
                        region.endOwnership();
                    }
                }
                executionRegions.put(region, batch.execution().subordinate(region,
                        resources == null ? region : resources.home(region.coordinate())));
            }
            revalidationStart = resources == null ? 0L : System.nanoTime();
            boolean current = !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level);
            final var preparation = new FurnaceWorkPreparation(level, this.engine.layout, resources != null);
            final var aliases = FurnaceWorkPreparation.aliased(aliasOrder);
            final List<BlockEntityResourceOwnership.Claims> freshClaims = resources == null ? List.of() : new ArrayList<>();
            final Map<BlockEntityResourceOwnership.SharedReads, BlockEntityResourceOwnership.SharedReads> compared = resources == null ? Map.of() : new java.util.IdentityHashMap<>();
            for (final int index : wave) {
                final var captured = candidates.get(index);
                final var verified = batch.eligibleNow(ordered.get(index)) ? preparation.prepare(ordered.get(index)) : null;
                current &= verified != null && !aliases.contains(captured.source())
                        && captured.source() == verified.source() && captured.chunk() == verified.chunk();
                if (resources != null) {
                    final var fresh = verified == null ? resources.fence(ordered.get(index)) : resources.recapture(captured, verified, claims.get(index));
                    freshClaims.add(fresh);
                    current &= claims.get(index).sameResources(fresh, compared);
                }
            }
            if (revalidationStart != 0L) {
                this.engine.blockEntityResourceMetrics.revalidated(System.nanoTime() - revalidationStart);
                revalidationStart = 0L;
            }
            List<BlockEntityResourceOwnership.Lease> grants = null;
            if (current && resources != null) {
                ownership = resources.underHeldRegions(leased, () -> !this.engine.shutdown && !this.engine.regionizer.isLevelClosed(level));
                final long reservationStart = System.nanoTime();
                grants = ownership.tryAcquireAll(freshClaims);
                this.engine.blockEntityResourceMetrics.reserved(grants == null ? -1 : grants.size(), System.nanoTime() - reservationStart);
                current = grants != null;
                if (!current) this.engine.furnaceWorkMetrics.refused(FurnaceWorkMetrics.Refusal.RESOURCE_PROOF);
            }
            if (current) {
                this.engine.furnaceWorkMetrics.wave();
                for (int offset = 0; offset < wave.size(); ++offset) {
                    final var candidate = candidates.get(wave.get(offset));
                    final var home = this.engine.regionizer.get(this.engine.layout.regionAt(level, candidate.admission().position()));
                    results.add(new FurnaceWorkResult(candidate, grants == null ? null : grants.get(offset),
                            batch.execution().ticket(candidate.admission()), executionRegions.get(home)));
                }
                this.submitFurnaceWave(batch, results, sample, barrier);
            }
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            if (revalidationStart != 0L) this.engine.blockEntityResourceMetrics.revalidated(System.nanoTime() - revalidationStart);
            for (final var execution : executionRegions.values()) failure = RegionThreadingEngine.runBatchCompletion(failure, execution::sealForCommit);
            failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.commitFurnaceWave(level, results, leased));
            if (ownership != null) {
                final var heldResources = ownership;
                failure = RegionThreadingEngine.runBatchCompletion(failure, heldResources::close);
            }
            for (int index = leased.size() - 1; index >= 0; --index) {
                final var region = leased.get(index);
                final var execution = executionRegions.get(region);
                if (execution != null) failure = RegionThreadingEngine.runBatchCompletion(failure, execution::close);
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> this.engine.releaseRegionLease(region));
            }
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    private void submitFurnaceWave(BlockEntityExecutionBatch batch, List<FurnaceWorkResult> results,
            @Nullable RegionBlockEntityWorkGraph.Phase sample, RegionTickBarrier barrier) {
        final RegionTaskBatch work = new RegionTaskBatch();
        final int lanes = Math.min(results.size(), this.engine.workerPool.threadCount());
        if (this.engine.workerPool.isPersistent()) {
            // Keep one batch scope per runner while allowing its unstarted furnace bodies to be stolen.
            // These bodies already share an admitted dependency wave and the coordinator's outer leases;
            // their notification commits retain the canonical results order after every runner joins.
            final var dispatcher = new RegionWorkStealingDispatcher(results.size(), lanes,
                    index -> this.runFurnaceWorkBody(batch, results.get(index), sample), this.engine.workerPool::recordBatchSteal);
            this.engine.workerPool.recordBatchDispatch(results.size(), lanes);
            for (int index = 0; index < lanes; ++index) {
                final int runner = index;
                work.add("vanilla furnace work runner", batch.groupWork(() -> dispatcher.run(runner)));
            }
        } else {
            final int perLane = (results.size() + lanes - 1) / lanes;
            for (int from = 0; from < results.size(); from += perLane) {
                final List<FurnaceWorkResult> lane = results.subList(from, Math.min(from + perLane, results.size()));
                work.add("vanilla furnace work lane", batch.groupWork(() -> {
                    for (final var result : lane) this.runFurnaceWorkBody(batch, result, sample);
                }));
            }
        }
        final long start = System.nanoTime();
        final var joined = work.submit(this.engine.workerPool);
        Throwable failure = null;
        try {
            barrier.await(() -> {
                if (this.engine.independentTickScheduler != null) {
                    this.engine.independentTickScheduler.drainCompletedCommits(batch.level(), 500_000L);
                }
                return joined.isComplete();
            });
        } catch (Throwable throwable) {
            failure = throwable;
        } finally {
            // No early release, even when managed wait throws or the calling thread is interrupted.
            joined.awaitCompletion();
            this.engine.furnaceWorkMetrics.rejected(joined.rejectedCount());
            this.engine.furnaceWorkMetrics.executed(System.nanoTime() - start);
        }
        try {
            joined.throwIfFailed();
        } catch (Throwable throwable) {
            failure = RegionThreadingEngine.accumulateFailure(failure, throwable);
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    private void commitFurnaceWave(ServerLevel level, List<FurnaceWorkResult> results, List<RegionState> leased) {
        final long start = System.nanoTime();
        Throwable failure = null;
        try {
            for (final var result : results) {
                if (!result.changed) continue;
                failure = RegionThreadingEngine.runBatchCompletion(failure, () -> {
                    final var source = result.candidate.source();
                    final var pos = source.getBlockPos();
                    final var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                    if (chunk != result.candidate.chunk() || source.isRemoved()
                            || chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) != source)
                        return;
                    final var region = this.engine.regionizer.get(this.engine.layout.regionAt(level, pos));
                    if (region == null || !leased.contains(region)) throw new IllegalStateException("Furnace commit lost its outer region lease");
                    region.beginOwnership();
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level,
                                region.coordinate(), region, null), source::setChanged);
                        this.engine.furnaceWorkMetrics.change();
                    } finally {
                        region.endOwnership();
                    }
                });
            }
        } finally {
            this.engine.furnaceWorkMetrics.committed(System.nanoTime() - start);
        }
        if (failure != null) RegionThreadingEngine.throwUnchecked(failure);
    }

    private void tickFurnaceSerial(BlockEntityExecutionBatch batch, RegionBlockEntityTickAdmission admission, @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        this.engine.furnaceWorkMetrics.serial();
        this.engine.fallbackBlockEntityTicks.incrementAndGet();
        this.tickBlockEntitySerial(batch, admission, graph);
    }

    private static final class FurnaceWorkResult {
        private final FurnaceWorkPreparation.Candidate candidate;
        @Nullable
        private final BlockEntityResourceOwnership.Lease resources;
        private final BlockEntityExecutionEngine.InvocationTicket ticket;
        private final BlockEntityExecutionEngine.RegionGuard execution;
        private boolean changed;

        private FurnaceWorkResult(FurnaceWorkPreparation.Candidate candidate, @Nullable BlockEntityResourceOwnership.Lease resources,
                BlockEntityExecutionEngine.InvocationTicket ticket, BlockEntityExecutionEngine.RegionGuard execution) {
            this.candidate = candidate;
            this.resources = resources;
            this.ticket = ticket;
            this.execution = execution;
        }
    }

    private void runFurnaceWorkBody(BlockEntityExecutionBatch batch, FurnaceWorkResult result, @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        final var candidate = result.candidate;
        final var admission = candidate.admission();
        final boolean nativeWorker = RegionWorkerScope.isNativeWorkerThread();
        FurnaceWorkScope scope = null;
        BlockEntityExecutionEngine.Permit permit = null;
        boolean completed = false;
        try {
            try (final var read = CoreModTickerVerifier.installed()
                    ? batch.execution().admissionRead(result.ticket, result.execution,
                            List.of(candidate.source(), candidate.chunk(), admission.ticker(), admission.binding().delegate(), candidate.source().getBlockPos()))
                    : null) {
                if (!admission.isCurrent() || candidate.source().isRemoved() || !candidate.chunk().isLoaded()) {
                    this.engine.furnaceWorkMetrics.skipped();
                    return;
                }
                permit = batch.execution().permit(result.ticket, result.execution,
                        new Refresh(result.ticket.inspection(), Outcome.CANDIDATE), result.resources);
            }
            if (permit == null) {
                this.engine.furnaceWorkMetrics.skipped();
                return;
            }
            scope = FurnaceWorkScope.open(admission, candidate.source(), candidate.chunk(), source -> {
                if (nativeWorker) this.engine.furnaceWorkMetrics.bodyStarted();
                final var observer = this.engine.furnaceBodyObserverForTesting;
                if (observer != null) observer.accept(source);
            }, () -> {
                if (nativeWorker) this.engine.furnaceWorkMetrics.bodyEnded();
            }, result.resources);
            if (graph == null) admission.ticker().tick();
            else graph.run(admission.ticker(), nativeWorker);
            if (scope.bodyStarted()) {
                if (nativeWorker) {
                    this.engine.furnaceWorkMetrics.completed();
                    this.engine.blockEntityTicksOnWorkers.incrementAndGet();
                } else this.engine.furnaceWorkMetrics.serial();
            } else this.engine.furnaceWorkMetrics.skipped();
            completed = true;
        } catch (Throwable throwable) {
            this.engine.furnaceWorkMetrics.failed();
            this.engine.recordWorkerTickFailure(batch, this.engine.layout.regionAt(batch.level(), admission.position()), null, throwable);
        } finally {
            try {
                if (scope != null) {
                    result.changed = scope.changed();
                    scope.close();
                }
                if (completed) permit.succeeded();
            } finally {
                if (permit != null) permit.close();
            }
        }
    }

    /**
     * Groups {@code pending} tickers by their authoritative region and takes the lease of every group.
     * <p>
     * Runs after the batch was published, so cleanup already waits for it and no lifecycle read gate may be
     * taken here. A region that dies while its lease is acquired is re-resolved on the next attempt.
     * Anything that cannot be resolved to a live, leasable region is recorded as a server-thread fallback.
     */
    private void leaseBlockEntityTickGroups(RegionBlockEntityTickBatch batch, List<RegionBlockEntityTickChunk> pending, RegionTickBarrier barrier) {
        List<RegionBlockEntityTickChunk> remaining = pending;
        int batchedTickers = 0;
        for (int attempt = 0; attempt < RegionThreadingEngine.MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping = new RegionTickGrouping<>();
            final List<RegionBlockEntityTickChunk> retry = new ArrayList<>();
            for (final RegionBlockEntityTickChunk chunk : remaining) {
                final RegionState region = this.engine.regionizer.getOrCreate(chunk.region(this.engine.layout, batch.level()));
                if (region == null || region.isDead()) {
                    retry.add(chunk);
                } else if (this.engine.shouldSkipOverloadedRegion(batch.level(), region)) {
                    this.engine.skippedBlockEntityTicks.addAndGet(chunk.admissions().size());
                } else {
                    grouping.add(region, chunk);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            // Split per chunk, not per region: a chunk whose tickers are all admitted keeps them on a worker, and
            // only a chunk with an unproven ticker runs on the server thread after the batch. The whole-region
            // rule this replaces made one modded block entity anywhere in a region serialize every other ticker
            // of that region - on a 178-mod pack that meant the block-entity slice never ran on a worker at all
            // (measured on the live server: 0 of 1.5M ticks). A fallback chunk is not ticked while the workers
            // run, so a worker that reads its block entity sees the state of the previous tick, exactly like the
            // interior rule's foreign read, and the fallback order inside a region stays the vanilla order.
            final RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> leasable = new RegionTickGrouping<>();
            for (final RegionState region : grouping.regions()) {
                for (final RegionBlockEntityTickChunk chunk : grouping.items(region)) {
                    if (chunk.allAdmitted()) {
                        final boolean boundary = chunk.cache() != null ? !chunk.cache().interiorChunk() : !this.engine.isInteriorRegionChunk(new ChunkPos(chunk.chunkKey()));
                        final RegionBlockEntityTickAttribution.FallbackReason refusal = boundary
                                ? this.boundaryBlockEntityRefusal(batch.level(), new ChunkPos(chunk.chunkKey()))
                                : null;
                        if (refusal != null) {
                            // Availability is a per-tick preflight, never a cached permission. Refuse the
                            // complete chunk before any inventory or block effect can run on a worker.
                            batch.addFallbackChunk(chunk);
                            this.engine.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
                            switch (refusal) {
                                case UNLOADED_NEIGHBORHOOD -> this.engine.blockEntityFallbackUnloadedNeighborhood.addAndGet(chunk.admissions().size());
                                case UNTRACKED_NEIGHBORHOOD -> this.engine.blockEntityFallbackUntrackedNeighborhood.addAndGet(chunk.admissions().size());
                                default -> this.engine.blockEntityFallbackBoundary.addAndGet(chunk.admissions().size());
                            }
                            RegionBlockEntityTickAttribution.recordFallback(refusal, chunk.admissions().size());
                        } else {
                            leasable.add(region, chunk);
                        }
                    } else {
                        batch.addFallbackChunk(chunk);
                        this.engine.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
                        for (final var admission : chunk.admissions()) {
                            switch (admission.decision()) {
                                case TYPE_NOT_DECLARED -> this.engine.blockEntityFallbackUndeclared.incrementAndGet();
                                case NOT_INTERIOR_CHUNK -> this.engine.blockEntityFallbackBoundary.incrementAndGet();
                                case ADMITTED, ADMITTED_WITH_BORDER_UPDATES, ADAPTER_GUARDED -> this.engine.blockEntityFallbackMixedSafe.incrementAndGet();
                            }
                        }
                        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION, chunk.admissions().size());
                    }
                }
            }
            if (leasable.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<RegionBlockEntityTickChunk> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            // The regions are leased in one canonical order and split into balanced packs, so one task of this
            // slice can hold several regions. A slice with fewer regions than workers is not packed and keeps the
            // unpacked submission of one task per region.
            for (final List<RegionState> pack : this.engine.regionPacks(leasable.regions())) {
                final List<RegionState> toLease = new ArrayList<>(pack.size());
                for (final RegionState region : pack) {
                    final List<RegionBlockEntityTickChunk> regionChunks = leasable.items(region);
                    if (batch.leases(region)) {
                        // An earlier group of this same batch already owns the lease. It cannot be
                        // acquired again and must never be awaited on this thread.
                        batch.group(region).ifPresent(group -> group.addChunks(regionChunks));
                        continue;
                    }
                    toLease.add(region);
                }
                if (toLease.isEmpty()) {
                    continue;
                }
                if (toLease.size() == 1) {
                    if (this.leaseBlockEntityGroup(batch, leasable, toLease.get(0), barrier, next)) {
                        leasedAny = true;
                        batchedTickers += this.countLeasedBlockEntityTickers(leasable, toLease.get(0));
                    }
                    continue;
                }
                final List<RegionState> leased = RegionLeasePack.tryAcquireAll(toLease, this.engine::releaseRegionLease);
                if (leased == null) {
                    // The pack could not take every region: it released the ones it took again, and every
                    // region of the pack is leased through the unpacked path, which waits for a region a worker
                    // still holds instead of failing on it. The fallback is counted.
                    this.engine.packedLeaseFallbacks.incrementAndGet();
                    for (final RegionState region : toLease) {
                        if (this.leaseBlockEntityGroup(batch, leasable, region, barrier, next)) {
                            leasedAny = true;
                            batchedTickers += this.countLeasedBlockEntityTickers(leasable, region);
                        }
                    }
                    continue;
                }
                leasedAny = true;
                for (final RegionState region : leased) {
                    batchedTickers += this.countLeasedBlockEntityTickers(leasable, region);
                }
                this.addPackedBlockEntityGroups(batch, leasable, leased);
            }
            remaining = next;
            if (!leasedAny) {
                // Every candidate region died during lease acquisition; fall back instead of spinning.
                break;
            }
        }

        for (final RegionBlockEntityTickChunk chunk : remaining) {
            batch.addFallbackChunk(chunk);
            this.engine.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
            RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNRESOLVABLE_REGION, chunk.admissions().size());
        }
        if (batchedTickers > 0 && this.engine.blockEntityTickWorkersNoticeLogged.compareAndSet(false, true)) {
            RegionThreadingEngine.LOGGER.info("Region block entity ticks are now executing on region workers: {} block entities", batchedTickers);
        }
    }

    /**
     * Checks the boundary protocol before any ticker starts. A loaded but untracked neighboring region is
     * insufficient: the fixpoint drops updates whose target has no live region, so that case must retain the
     * complete serial body too. Neither availability nor the mapping proof belongs in an admission cache.
     */
    @Nullable
    private RegionBlockEntityTickAttribution.FallbackReason boundaryBlockEntityRefusal(ServerLevel level, ChunkPos chunk) {
        if (!this.engine.borderUpdatesEnabled) {
            return RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION;
        }
        final ServerChunkCache chunks = level.getChunkSource();
        for (int dx = -1; dx <= 1; ++dx) {
            for (int dz = -1; dz <= 1; ++dz) {
                if (dx != 0 || dz != 0) {
                    // hasChunk only proves a holder has a FULL ticket; its load may still be pending.
                    // A currentlyLoading chunk returned by the main-thread lookup is not ready either.
                    final LevelChunk loaded = chunks.getChunkNow(chunk.x + dx, chunk.z + dz);
                    if (loaded == null || !loaded.isLoaded()) {
                        return RegionBlockEntityTickAttribution.FallbackReason.UNLOADED_NEIGHBORHOOD;
                    }
                }
                final RegionState neighbor = this.engine.regionizer.get(this.engine.layout.regionAt(level, chunk.x + dx, chunk.z + dz));
                if (neighbor == null || neighbor.isDead()) {
                    return RegionBlockEntityTickAttribution.FallbackReason.UNTRACKED_NEIGHBORHOOD;
                }
            }
        }
        return null;
    }

    /**
     * Leases one region of a block-entity batch and submits its group as its own task.
     * <p>
     * This is the unpacked path: it is used for a slice that is not packed, for a pack with one region and for
     * the fallback of a pack that could not take every region it needs.
     *
     * @return {@code true} when the region was leased and its group submitted, {@code false} when its chunks were
     *         recorded for the next attempt
     */
    private boolean leaseBlockEntityGroup(RegionBlockEntityTickBatch batch, RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, RegionState region, RegionTickBarrier barrier, List<RegionBlockEntityTickChunk> retry) {
        final List<RegionBlockEntityTickChunk> regionChunks = grouping.items(region);
        if (!this.engine.acquireRegionLease(region, barrier)) {
            retry.addAll(regionChunks);
            return false;
        }
        this.engine.addLeasedGroup(region, () -> {
            try {
                batch.addGroup(region, regionChunks,
                        group -> this.engine.withRegionMailboxDelivery(batch.level(), region, this.blockEntityTickGroup(group)));
            } catch (Throwable failure) {
                batch.forgetLease(region);
                throw failure;
            }
        });
        return true;
    }

    /** {@return the number of tickers one leased region of {@code grouping} hands to a worker} */
    private int countLeasedBlockEntityTickers(RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, RegionState region) {
        return countBlockEntityTickers(grouping.items(region));
    }

    /**
     * Registers the groups of one leased pack and submits them as one worker task.
     * <p>
     * The leases are already taken, so a failure while a group is built releases every lease of the pack
     * instead of leaving a region TICKING behind a group no worker will run, which would block shutdown and
     * level unload forever.
     */
    private void addPackedBlockEntityGroups(RegionBlockEntityTickBatch batch, RegionTickGrouping<RegionState, RegionBlockEntityTickChunk> grouping, List<RegionState> pack) {
        final List<Runnable> works = new ArrayList<>(pack.size());
        try {
            for (final RegionState region : pack) {
                final RegionBlockEntityTickBatch.Group group = batch.registerGroup(region, grouping.items(region));
                works.add(batch.packedWork(group, leased -> this.engine.withRegionMailboxDelivery(batch.level(), region, this.blockEntityTickGroup(leased))));
            }
        } catch (Throwable throwable) {
            for (final RegionState region : pack) {
                batch.forgetLease(region);
                this.engine.releaseRegionLease(region);
            }
            throw throwable;
        }
        this.engine.packedTasks.incrementAndGet();
        this.engine.packedRegions.addAndGet(pack.size());
        batch.addPackedTask("block entity tick pack of " + pack.size(), works);
    }

    /** {@return the number of tickers {@code chunks} hand to a worker group} */
    static int countBlockEntityTickers(Collection<RegionBlockEntityTickChunk> chunks) {
        int tickers = 0;
        for (final RegionBlockEntityTickChunk chunk : chunks) {
            tickers += chunk.admissions().size();
        }
        return tickers;
    }

    /** Observation only: invoked after production admission, never consulted to grant execution authority. */
    @org.jetbrains.annotations.VisibleForTesting
    public void setBlockEntityBodyObserverForTesting(@Nullable java.util.function.BiConsumer<TickingBlockEntity, Runnable> observer) {
        this.engine.blockEntityBodyObserverForTesting = observer;
    }

    private void runBlockEntityBody(TickingBlockEntity ticker, @Nullable RegionBlockEntityWorkGraph.Phase graph, boolean worker) {
        final Runnable body = () -> {
            if (graph == null) ticker.tick();
            else graph.run(ticker, worker);
        };
        final var observer = this.engine.blockEntityBodyObserverForTesting;
        if (observer == null) body.run();
        else observer.accept(ticker, body);
    }

    /** Narrow the real Level path; legacy explicit declarations do not issue this proof. */
    RegionBlockEntityTickAdmission narrowCanonicalBlockEntity(RegionBlockEntityTickAdmission admission) {
        if (!admission.isAdmitted()
                || this.engine.furnaceWorkWavesEnabled && LevelChunk.furnaceWorkSource(admission.ticker()) != null
                || NativeBlockEntityPreparation.prepareNative(admission) != null
                || AuditedBlockEntityPreparation.hasCandidate(admission))
            return admission;
        return new RegionBlockEntityTickAdmission(admission.level(), admission.ticker(), admission.position(), admission.type(),
                admission.binding(), RegionBlockEntityTickAdmission.Decision.TYPE_NOT_DECLARED, admission.sequence());
    }

    private static Inspection inspectNativeBlockEntity(RegionBlockEntityTickAdmission admission) {
        if (!admission.isAdmitted()) return Inspection.serialFence();
        final var descriptor = admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                ? ParallelAdapterRuntime.blockEntityTickDescriptor(admission)
                : new AdapterDescriptor(ResourceLocation.parse("forgia:declared_native_tick"),
                        Set.of(ResourceLocation.parse(admission.type())), Mode.REGION_OWNER);
        return descriptor == null ? Inspection.serialFence() : new Inspection(descriptor, Resources.REGION_LOCAL, Outcome.CANDIDATE);
    }

    private Runnable blockEntityTickGroup(RegionBlockEntityTickBatch.Group group) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final List<RegionBlockEntityTickChunk> chunks = group.chunks();
        final ServerLevel level = group.batch().level();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            final int[] boundaryTicked = { 0 };
            final boolean tickerTiming = RegionBlockEntityTickAttribution.isEnabled();
            final long cpuStart = tickerTiming ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() : 0L;
            int admitted = 0;
            boolean ownerStarted = false;
            BlockEntityExecutionEngine.RegionGuard execution = null;
            try {
                final List<RegionBlockEntityTickAdmission> tickers = this.orderBlockEntityGroup(group, chunks);
                admitted = tickers.size();
                region.beginOwnership();
                ownerStarted = true;
                execution = group.batch().execution().regionOwner(region);
                final var regionExecution = execution;
                // The group random is installed once for the whole worker tick loop, so a direct
                // level.random read of a ticker resolves this group's source through the level random
                // wrapper instead of the shared vanilla one, and the block-entity access gate of the
                // level answers for the block entities of this region.
                this.engine.currentBlockEntityTickRandom.set(random);
                this.engine.currentBlockEntityOperationGroup.set(group);
                // LevelTicks is server-owned storage: a scheduled tick a ticker starts through the
                // neighbor update of its own setChanged() is recorded here and applied by the publishing
                // server thread after the batch, never from a worker.
                this.engine.currentRegionTickMutations.set(group.batch().regionTickMutations());
                if (tickerTiming) {
                    // Everything up to the ownership lease is per-group setup: ordering the tickers, claiming
                    // the region and installing the group state. It is the group half of the wrapper.
                    RegionBlockEntityTickAttribution.recordGroupPrepare(System.nanoTime() - start);
                }
                try {
                    // The ownership lease covers exactly this worker tick loop: it is opened after the
                    // region lease and closed before it, so a worker task outside the loop never sees it.
                    final long ownershipStart = tickerTiming ? System.nanoTime() : 0L;
                    final RegionEntityOwnershipScope ownership = RegionEntityOwnershipScope.open(coordinate, group);
                    if (tickerTiming) {
                        RegionBlockEntityTickAttribution.recordOwnershipScope(System.nanoTime() - ownershipStart);
                    }
                    try {
                        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION, level, coordinate, region, null), () -> {
                            for (final RegionBlockEntityTickAdmission admission : tickers) {
                                final long tickerLoopStart = tickerTiming ? System.nanoTime() : 0L;
                                final TickingBlockEntity ticker = admission.ticker();
                                if (ticker.isRemoved()) {
                                    continue;
                                }
                                // Validate before the body: stale admissions are deferred exactly once, never
                                // recovered by throwing after a ticker could already have produced side effects.
                                if (admission.level() != level || !admission.isCurrent() || !region.isOwnedByCurrentThread()
                                        || group.batch().canonicalSequence() && NativeBlockEntityPreparation.prepare(admission) == null) {
                                    if (tickerTiming) {
                                        RegionBlockEntityTickAttribution.recordTickerAdmission(System.nanoTime() - tickerLoopStart);
                                    }
                                    group.batch().addFallback(admission);
                                    this.engine.fallbackBlockEntityTicks.incrementAndGet();
                                    RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION, 1);
                                    continue;
                                }
                                if (tickerTiming) {
                                    RegionBlockEntityTickAttribution.recordTickerAdmission(System.nanoTime() - tickerLoopStart);
                                }
                                if (RegionWorkerScope.isNativeWorkerThread()) {
                                    final long reserveStart = tickerTiming ? System.nanoTime() : 0L;
                                    if (!group.batch().tryReserveBlockUpdates()) {
                                        if (tickerTiming) {
                                            RegionBlockEntityTickAttribution.recordTickerReservation(System.nanoTime() - reserveStart);
                                        }
                                        // The batch cannot reserve the block-write effects of this ticker, so
                                        // the tick is refused before its body starts and runs on the server
                                        // thread after the batch, where its writes need no record.
                                        group.batch().addFallback(admission);
                                        this.engine.fallbackBlockEntityTicks.incrementAndGet();
                                        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.DEFERRED_UPDATE_BUDGET, 1);
                                        this.engine.countRefusedDeferredBlockUpdate(RegionTickSlice.BLOCK_ENTITY_TICK);
                                        continue;
                                    }
                                    if (tickerTiming) {
                                        RegionBlockEntityTickAttribution.recordTickerReservation(System.nanoTime() - reserveStart);
                                    }
                                    // The scope is opened and closed explicitly instead of by a try-with-resources
                                    // so the attribution can time the entry and the exit separately from the body.
                                    final long tickerStart = tickerTiming ? System.nanoTime() : 0L;
                                    final RegionBlockEntityTickScope scope = RegionBlockEntityTickScope.open(admission, region);
                                    final long bodyStart = tickerTiming ? System.nanoTime() : 0L;
                                    if (tickerTiming) {
                                        RegionBlockEntityTickAttribution.recordTickerScope(bodyStart - tickerStart);
                                    }
                                    try {
                                        try {
                                            final var ticket = group.batch().execution().inspect(admission, inspectNativeBlockEntity(admission));
                                            if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED
                                                    && !ParallelAdapterRuntime.admitBlockEntityTick(admission, ticket.inspection().descriptor())) {
                                                // No original body has started. Defer the complete invocation;
                                                // the adapter predicate has no authority to publish effects.
                                                group.batch().addFallback(admission);
                                                this.engine.fallbackBlockEntityTicks.incrementAndGet();
                                                RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.UNSAFE_REGION, 1);
                                                continue;
                                            }
                                            if (group.batch().canonicalSequence()) group.batch().execution().enableInvocationEffects(ticket);
                                            try (final var permit = group.batch().execution().permit(ticket, regionExecution,
                                                    new Refresh(ticket.inspection(), Outcome.CANDIDATE), null)) {
                                                if (permit == null) {
                                                    group.batch().addFallback(admission);
                                                    this.engine.fallbackBlockEntityTicks.incrementAndGet();
                                                    RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_ADMISSION, 1);
                                                    continue;
                                                }
                                                this.runBlockEntityBody(ticker, group.batch().workGraph(), true);
                                                if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADAPTER_GUARDED) {
                                                    ParallelAdapterRuntime.completeBlockEntityTick(admission, ticket.inspection().descriptor());
                                                }
                                                if (admission.decision() == RegionBlockEntityTickAdmission.Decision.ADMITTED_WITH_BORDER_UPDATES) {
                                                    ++boundaryTicked[0];
                                                }
                                                permit.succeeded();
                                            }
                                        } finally {
                                            if (tickerTiming) {
                                                RegionBlockEntityTickAttribution.recordTickerBody(System.nanoTime() - bodyStart);
                                            }
                                        }
                                    } catch (Throwable throwable) {
                                        if (this.engine.truncateIfBlockEntityBoundaryViolation(admission, throwable)) {
                                            continue;
                                        }
                                        // Once a ticker body started, a chunk refusal is a real failure. Its
                                        // earlier inventory/world/event effects cannot be rolled back or replayed.
                                        this.engine.recordWorkerTickFailure(group.batch(), coordinate, null, throwable);
                                        return;
                                    } finally {
                                        final long closeStart = tickerTiming ? System.nanoTime() : 0L;
                                        scope.close();
                                        if (tickerTiming) {
                                            final long closeEnd = System.nanoTime();
                                            RegionBlockEntityTickAttribution.recordTickerScope(closeEnd - closeStart);
                                            // The ticker's own tick, so the group wall time can be split into the
                                            // vanilla body and the wrapper the slice adds around it.
                                            RegionBlockEntityTickAttribution.recordTicker(closeEnd - tickerStart);
                                        }
                                    }
                                } else {
                                    // Executor rejection runs on the server thread and keeps the real load check.
                                    try (final var permit = group.batch().execution().beginInline(admission, regionExecution)) {
                                        this.tickBlockEntityInline(level, ticker, group.batch().workGraph());
                                        permit.succeeded();
                                    }
                                }
                                ++ticked[0];
                            }
                        });
                    } finally {
                        final long ownershipCloseStart = tickerTiming ? System.nanoTime() : 0L;
                        ownership.close();
                        if (tickerTiming) {
                            RegionBlockEntityTickAttribution.recordOwnershipScope(System.nanoTime() - ownershipCloseStart);
                        }
                    }
                } finally {
                    this.engine.currentRegionTickMutations.remove();
                    this.engine.currentBlockEntityTickRandom.remove();
                    this.engine.currentBlockEntityOperationGroup.remove();
                }
            } finally {
                final long groupEndStart = tickerTiming ? System.nanoTime() : 0L;
                try {
                    if (execution != null) execution.close();
                } finally {
                    if (ownerStarted) region.endOwnership();
                    if (!group.batch().canonicalSequence()) region.clearRunning();
                }
                if (tickerTiming) {
                    RegionBlockEntityTickAttribution.recordGroupEnd(System.nanoTime() - groupEndStart);
                }
                final long duration = System.nanoTime() - start;
                // The group reports its own wall time and size, so the measured window can tell a barrier
                // that waits for the slowest group from one that waits for work.
                RegionBlockEntityTickAttribution.recordGroup(duration,
                        tickerTiming ? RegionBlockEntityTickAttribution.currentThreadCpuNanos() - cpuStart : 0L,
                        admitted, ticked[0]);
                if (tickerTiming) {
                    group.batch().recordGroupDuration(duration);
                    group.batch().recordGroupStart(start);
                    group.batch().recordGroupEnd(start + duration);
                }
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("block entity batch " + coordinate, duration);
                this.engine.blockEntityTicksOnWorkers.addAndGet(ticked[0]);
                this.engine.boundaryBlockEntityTicksOnWorkers.addAndGet(boundaryTicked[0]);
                if (tickerTiming) {
                    // Retiring an empty region takes the regionizer write lock, which every finishing group of
                    // the batch wants at the same time: the one shared section a group enters after its tickers.
                    final long retireStart = System.nanoTime();
                    this.engine.regionizer.removeIfEmpty(region);
                    RegionBlockEntityTickAttribution.recordGroupRetire(System.nanoTime() - retireStart);
                } else {
                    this.engine.regionizer.removeIfEmpty(region);
                }
            }
        };
    }

    /**
     * Orders the tickers of a worker group's chunks like the level's own ticker list, and hands the chunks
     * whose snapshot went stale back to the server thread.
     * <p>
     * Each chunk's admissions are already in ticker order, so a group of one chunk needs no work. A group of
     * several chunks merges them by ticker sequence, which reproduces the order the level's ticker list would
     * have ticked that region in. A chunk that unloaded or changed its ticker set since the plan handed it
     * over is not ticked from its stale snapshot: its tickers are revalidated and ticked on the server thread
     * after the batch, and the stale snapshot is counted.
     */
    private List<RegionBlockEntityTickAdmission> orderBlockEntityGroup(RegionBlockEntityTickBatch.Group group, List<RegionBlockEntityTickChunk> chunks) {
        final RegionLayout layout = this.engine.layout;
        if (chunks.size() == 1) {
            final RegionBlockEntityTickChunk chunk = chunks.get(0);
            if (!chunk.isCurrent(layout, this.engine.borderUpdatesEnabled)) {
                this.deferStaleBlockEntityChunk(group, chunk);
                return List.of();
            }
            return chunk.admissions();
        }
        final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>(countBlockEntityTickers(chunks));
        for (final RegionBlockEntityTickChunk chunk : chunks) {
            if (!chunk.isCurrent(layout, this.engine.borderUpdatesEnabled)) {
                this.deferStaleBlockEntityChunk(group, chunk);
                continue;
            }
            ordered.addAll(chunk.admissions());
        }
        ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
        return ordered;
    }

    /**
     * Hands the tickers of a chunk whose snapshot went stale to the server-thread fallback of {@code group}.
     */
    private void deferStaleBlockEntityChunk(RegionBlockEntityTickBatch.Group group, RegionBlockEntityTickChunk chunk) {
        this.engine.staleBlockEntityChunkSnapshots.incrementAndGet();
        this.engine.fallbackBlockEntityTicks.addAndGet(chunk.admissions().size());
        RegionBlockEntityTickAttribution.recordFallback(RegionBlockEntityTickAttribution.FallbackReason.STALE_SNAPSHOT, chunk.admissions().size());
        group.batch().addFallbackChunk(chunk);
    }

    /**
     * Ticks the block-entity chunks whose region could not be leased safely, whose snapshot went stale before
     * the worker ticked it, and the tickers a worker handed back, on the server thread after the batch.
     */
    private void tickFallbackBlockEntities(RegionBlockEntityTickBatch batch) {
        if (batch.fallbackChunks().isEmpty() && batch.fallbackTickers().isEmpty()) {
            return;
        }
        final ServerLevel level = batch.level();
        final RegionThreadingEngine.BlockEntityFallbackPhase phase = this.openBlockEntityFallbackPhase(batch);
        try {
            if (batch.canonicalSequence()) {
                final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
                for (final var chunk : batch.fallbackChunks()) ordered.addAll(chunk.admissions());
                ordered.addAll(batch.fallbackTickers());
                ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
                for (final var admission : ordered) this.tickBlockEntitySerial(batch, admission, batch.workGraph());
            } else {
                for (final RegionBlockEntityTickChunk chunk : batch.fallbackChunks()) {
                    this.tickBlockEntityChunkInline(batch, chunk, batch.workGraph());
                }
                for (final RegionBlockEntityTickAdmission ticker : batch.fallbackTickers()) {
                    this.tickBlockEntitySerial(batch, ticker, batch.workGraph());
                }
            }
        } finally {
            if (phase != null) {
                this.engine.blockEntityFallbackPhase.remove();
                this.releaseBlockEntityFallbackPhase(phase);
            }
        }
    }

    @Nullable
    private RegionThreadingEngine.BlockEntityFallbackPhase openBlockEntityFallbackPhase(RegionBlockEntityTickBatch batch) {
        final MinecraftServer server = batch.level().getServer();
        if (!this.engine.blockEntityFallbackScopeEnabled || this.engine.shutdown || server == null || !server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()
                || this.engine.workerPool.isWorkerThread(Thread.currentThread()) || !batch.isExecutingOnCurrentThread()
                || !batch.isGroupsComplete() || this.engine.blockEntityFallbackPhase.get() != null || this.engine.blockEventPhase.get() != null
                || this.engine.currentContext().kind() == RegionThreadingContext.Kind.REGION
                || this.engine.currentContext().kind() == RegionThreadingContext.Kind.GLOBAL_REGION) {
            this.engine.blockEntityFallbackScopeRefusals.incrementAndGet();
            return null;
        }
        for (final RegionTickBatch active : this.engine.activeTickBatches) {
            if (active != batch) {
                this.engine.blockEntityFallbackScopeRefusals.incrementAndGet();
                return null;
            }
        }
        final AtomicBoolean busy = new AtomicBoolean();
        this.engine.regionizer.forEach(region -> {
            if (region.isRunning() || region.isWorkerSubmitted()) {
                busy.set(true);
            }
        });
        if (busy.get()) {
            this.engine.blockEntityFallbackScopeRefusals.incrementAndGet();
            return null;
        }
        final RegionThreadingEngine.BlockEntityFallbackPhase phase = new RegionThreadingEngine.BlockEntityFallbackPhase(batch.level());
        this.engine.blockEntityFallbackPhase.set(phase);
        this.engine.blockEntityFallbackPhases.incrementAndGet();
        return phase;
    }

    private void releaseBlockEntityFallbackPhase(RegionThreadingEngine.BlockEntityFallbackPhase phase) {
        final List<RegionState> leases = new ArrayList<>(phase.leases.values());
        phase.leases.clear();
        for (int index = leases.size() - 1; index >= 0; --index) {
            final RegionState region = leases.get(index);
            region.endOwnership();
            region.clearRunning();
            this.engine.regionizer.removeIfEmpty(region);
            this.engine.blockEntityFallbackReleases.incrementAndGet();
        }
    }

    void runInBlockEntityFallbackPhase(RegionThreadingEngine.BlockEntityFallbackPhase phase, BlockPos pos, Runnable task) {
        final RegionCoordinate coordinate = this.engine.layout.regionAt(phase.level, pos);
        RegionState region = phase.leases.get(coordinate);
        if (region == null) {
            // The batch protects level lifetime, but a cleanup may already hold the lifecycle write gate while
            // awaiting this batch. Never block on that gate from its server-thread completion.
            if (this.engine.shutdown) {
                task.run();
                return;
            }
            if (!this.engine.acquireLifecycleReadLock(true)) {
                this.engine.enqueueRegionTask(phase.level, pos, task);
                return;
            }
            try {
                region = this.engine.regionizer.getOrCreate(coordinate);
                if (region != null && !region.isDead() && region.tryMarkRunning()) {
                    region.beginOwnership();
                    phase.leases.put(coordinate, region);
                    this.engine.blockEntityFallbackLeases.incrementAndGet();
                } else {
                    region = null;
                }
            } finally {
                this.engine.lifecycleGate.readLock().unlock();
            }
            if (region == null) {
                // A manually drained or queued region task may own the region outside a published batch.
                // Keep the original dispatch behavior: queue behind it, never run concurrently without a lease.
                this.engine.enqueueRegionTask(phase.level, pos, task);
                return;
            }
        }
        this.engine.blockEntityFallbackCallbacks.incrementAndGet();
        if (this.engine.isOwnedByCurrentRegion(phase.level, pos)) {
            // Same-region reentrancy must retain the current neighbor collector and its cascade budget.
            task.run();
            return;
        }
        this.engine.runWithContext(new RegionThreadingEngine.BasicRegionThreadingContext(RegionThreadingContext.Kind.REGION,
                phase.level, coordinate, region, null), task);
    }

    /** Ticks the tickers of one chunk on the server thread, in their collected order. */
    private void tickBlockEntityChunksInline(BlockEntityExecutionBatch batch, List<RegionBlockEntityTickChunk> chunks,
            @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        final List<RegionBlockEntityTickAdmission> ordered = new ArrayList<>();
        for (final var chunk : chunks) ordered.addAll(chunk.admissions());
        if (batch.canonicalSequence()) ordered.sort(Comparator.comparingLong(RegionBlockEntityTickAdmission::sequence));
        for (final var admission : ordered) this.tickBlockEntitySerial(batch, admission, graph);
    }

    private void tickBlockEntityChunkInline(BlockEntityExecutionBatch batch, RegionBlockEntityTickChunk chunk,
            @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        for (final RegionBlockEntityTickAdmission admission : chunk.admissions()) this.tickBlockEntitySerial(batch, admission, graph);
    }

    private void tickBlockEntitySerial(BlockEntityExecutionBatch batch, RegionBlockEntityTickAdmission admission,
            @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        this.tickBlockEntitySerial(batch, admission, graph, null);
    }

    private void tickBlockEntitySerial(BlockEntityExecutionBatch batch, RegionBlockEntityTickAdmission admission,
            @Nullable RegionBlockEntityWorkGraph.Phase graph,
            @Nullable Boolean knownEligible) {
        final boolean eligible = knownEligible != null ? knownEligible.booleanValue() : batch.eligibleNow(admission);
        if (!eligible) return;
        final boolean canonical = batch.canonicalSequence();
        final long fenceStart = canonical ? System.nanoTime() : 0L;
        try (final var permit = batch.execution().beginSerial(admission)) {
            this.tickBlockEntityInline(batch.level(), admission.ticker(), graph, canonical);
            permit.succeeded();
        } finally {
            if (canonical) {
                final long elapsed = System.nanoTime() - fenceStart;
                final BlockPos pos = admission.ticker().getPos();
                final var chunkRegions = this.engine.activeCanonicalBeChunkRegions;
                RegionState region = chunkRegions != null ? chunkRegions.get(ChunkPos.asLong(pos)) : null;
                final int regionX;
                final int regionZ;
                if (region != null && !region.isDead()) {
                    final RegionCoordinate coord = region.coordinate();
                    regionX = coord.regionX();
                    regionZ = coord.regionZ();
                } else {
                    final RegionCoordinate coord = this.engine.layout.regionAt(batch.level(), pos);
                    regionX = coord.regionX();
                    regionZ = coord.regionZ();
                    region = this.engine.regionizer.get(coord);
                }
                final String dimId = this.engine.activeCanonicalBeDimensionId != null
                        ? this.engine.activeCanonicalBeDimensionId
                        : batch.level().dimension().location().toString();
                final String typeName = admission.ticker().getType();
                final String tickerClass = (typeName != null && !typeName.isEmpty())
                        ? typeName
                        : admission.ticker().getClass().getName();
                this.engine.fenceMetrics.recordFence(dimId, regionX, regionZ, tickerClass, elapsed);
                final var beCosts = this.engine.activeCanonicalBeRegionCosts;
                if (beCosts != null && region != null && !region.isDead()) {
                    beCosts.addTo(region, elapsed);
                }
            }
        }
    }

    private void tickBlockEntityInline(ServerLevel level, TickingBlockEntity ticker, @Nullable RegionBlockEntityWorkGraph.Phase graph) {
        this.tickBlockEntityInline(level, ticker, graph, false);
    }

    private void tickBlockEntityInline(ServerLevel level, TickingBlockEntity ticker, @Nullable RegionBlockEntityWorkGraph.Phase graph,
            boolean preverifiedEntitiesLoaded) {
        if (ticker.isRemoved()) {
            return;
        }
        if (this.engine.regionizer.isLevelClosed(level)) {
            // A fallback tick is only reached for a collected snapshot; once the level unloaded, the
            // ticker belongs to a retired identity and must not tick on the server thread either.
            this.engine.skippedBlockEntityTicks.incrementAndGet();
            return;
        }
        if (preverifiedEntitiesLoaded || level.areEntitiesLoaded(ChunkPos.asLong(ticker.getPos()))) {
            this.runBlockEntityBody(ticker, graph, false);
        }
    }

}
