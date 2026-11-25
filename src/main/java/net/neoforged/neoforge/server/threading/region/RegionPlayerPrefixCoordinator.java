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
 * Coordinates isolated player packet operations, player prefix wave dispatch, connection-body ticks,
 * and player world tick sequencing.
 * <p>
 * Extracted from {@link RegionThreadingEngine} while keeping public nested handles
 * ({@link RegionThreadingEngine.PlayerConnectionPass}, {@link RegionThreadingEngine.PlayerTickInvocation},
 * {@link RegionThreadingEngine.PlayerPrefixTiming}) on the engine facade for binary compatibility.
 */
@ApiStatus.Internal
final class RegionPlayerPrefixCoordinator {
    private final RegionThreadingEngine engine;

    RegionPlayerPrefixCoordinator(RegionThreadingEngine engine) {
        this.engine = engine;
    }

    boolean tryRunPlayerPacket(net.minecraft.network.protocol.Packet<?> packet,
            net.minecraft.server.network.ServerGamePacketListenerImpl listener, Runnable original) {
        return this.tryRunPlayerOperation(packet, listener, original, false, null);
    }

    boolean tryRunPlayerChatCompletion(net.minecraft.server.network.ServerGamePacketListenerImpl listener, Runnable original) {
        return this.tryRunPlayerOperation(null, listener, original, true, null);
    }

    boolean tryRunPlayerPayload(net.minecraft.network.protocol.Packet<?> packet,
            net.minecraft.server.network.ServerGamePacketListenerImpl listener,
            net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> declaration, Runnable original) {
        return this.tryRunPlayerOperation(packet, listener, original, false, declaration);
    }

    private boolean tryRunPlayerOperation(@Nullable net.minecraft.network.protocol.Packet<?> packet,
            net.minecraft.server.network.ServerGamePacketListenerImpl listener, Runnable original, boolean chatCompletion,
            @Nullable net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> payload) {
        ServerPlayer player = listener.player;
        if (!this.engine.playerCriterionStorageEnabled() || !player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()
                || (!chatCompletion && payload == null && (packet == null || !RegionPlayerPacketPlan.known(packet))) || !this.engine.activeTickBatches.isEmpty()
                || this.engine.regionWorkAdmission.isOwner() || !this.engine.regionWorkAdmission.tryEnter(this.engine::playerDispatchQuiescent))
            return this.engine.playerPackets.refusePayload(payload, "DEPENDENCIES_OR_BUSY");
        RegionPlayerTickDispatch.Lifecycle parent = null;
        RegionPlayerSpatialLease lease = null;
        RegionPlayerPacketRouter.Call call = null;
        RegionPlayerPacketPlan.Preparation preparation = null;
        RegionPlayerTickDispatch effects = null;
        boolean frozen = false;
        boolean regionPhase = false;
        long sequence = 0;
        try {
            // Preserve explicit NETWORK handlers of unknown extensions on their original thread.
            var environment = payload == null ? RegionPlayerExtensionSnapshot.capture() : null;
            if (environment != null && environment.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) return false;
            var packetPlan = payload != null ? RegionPlayerPacketPlan.payload(packet, listener, payload)
                    : chatCompletion ? RegionPlayerPacketPlan.chat(listener) : RegionPlayerPacketPlan.capture(packet, listener);
            if (packetPlan == null) return this.engine.playerPackets.refusePayload(payload, "UNREGISTERED_OR_STALE_PAYLOAD");
            if (!this.engine.playerNetworkGate.freezePreservingNetworkThread(player.server))
                return this.engine.playerPackets.refusePayload(payload, "NETWORK_CALLBACKS");
            frozen = true;
            preparation = packetPlan.prepare();
            this.engine.playerRegions.publishAtPlayerStage(this.engine, player);
            var capture = payload == null ? this.engine.capturePlayerAdmissionPlan(player, RegionPlayerTickCoordinator.Phase.CONNECTION, environment, null)
                    : RegionPlayerAdmissionPlan.capture(this.engine, player, RegionPlayerTickCoordinator.Phase.CONNECTION, packetPlan);
            if (capture.plan() == null) return this.engine.playerPackets.refusePayload(payload, "ADMISSION_" + capture.refusal().name());
            parent = new RegionPlayerTickDispatch.Lifecycle(player.serverLevel());
            if (this.engine.publishTickBatch(parent, player.serverLevel()) != TickBatchPublication.PUBLISHED) {
                parent.finish();
                parent = null;
                return this.engine.playerPackets.refusePayload(payload, "BATCH_PUBLICATION");
            }
            parent.markExecutingOnCurrentThread();
            effects = new RegionPlayerTickDispatch(this.engine, parent);
            lease = this.engine.reserveBoundPlayerState(parent, effects, List.of(capture.plan().state()), false);
            if (lease == null)
                return this.engine.playerPackets.refusePayload(payload, "LEASE_OR_STATE_REVALIDATION");
            call = new RegionPlayerPacketRouter.Call(packet, listener, capture.plan(), packetPlan, original);
            sequence = this.engine.playerPackets.next();
            if (!chatCompletion && !this.engine.playerPackets.consume(call.received)) return this.engine.playerPackets.refusePayload(payload, "STALE_RECEIVE");
            var invocation = call;
            var ownership = lease;
            var work = new RegionTaskBatch();
            work.add("original player packet", parent.groupWork(() -> invocation.run(ownership)));
            parent.externalWaveActive(true);
            this.engine.beginRegionPhase(parent.level());
            regionPhase = true;
            var joined = work.submit(this.engine.workerPool);
            call.await(joined); // Only exact synchronous packet services; no server/chunk queue pump.
            joined.throwIfFailed();
            if (call.failure != null) RegionThreadingEngine.throwUnchecked(call.failure); // Original PacketUtils catches on publisher.
            return call.started || this.engine.playerPackets.refusePayload(payload, "PRE_BODY_REVALIDATION_OR_REJECTION");
        } catch (Throwable problem) {
            if (call != null && call.failure != null && call.failure != problem) {
                RegionThreadingEngine.accumulateFailure(call.failure, problem);
                RegionThreadingEngine.throwUnchecked(call.failure);
            }
            RegionThreadingEngine.throwUnchecked(problem);
            return false; // throwUnchecked always throws.
        } finally {
            Throwable cleanup = null;
            try {
                if (regionPhase) this.engine.levelTickPhaseState(parent.level()).endRegionPhase();
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            try {
                if (parent != null) parent.externalWaveActive(false);
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            try {
                if (lease != null) lease.close();
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            try {
                if (call != null) this.engine.playerPackets.finish(sequence, call);
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            try {
                if (effects != null) {
                    this.engine.applyDeferredRemovals(parent.level(), effects.removals.drain(), effects.removals.isSeamClosed(), true);
                    this.engine.applyDeferredSectionUpdates(parent.level(), effects.sections.drain(), effects.sections.isSeamClosed());
                }
                if (call != null && call.started) this.engine.playerRegions.publishAtPlayerStage(this.engine, player);
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            try {
                if (parent != null) {
                    parent.completeExternalWaves();
                    parent.finish();
                }
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            } finally {
                try {
                    if (parent != null) {
                        this.engine.activeTickBatches.remove(parent);
                        parent.clearExecutingOnCurrentThread();
                    }
                } finally {
                    try {
                        if (preparation != null) preparation.close();
                    } finally {
                        try {
                            if (frozen) this.engine.playerNetworkGate.thaw();
                        } catch (Throwable problem) {
                            cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                        } finally {
                            try {
                                this.engine.regionWorkAdmission.exit();
                            } catch (Throwable problem) {
                                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                            }
                        }
                    }
                }
            }
            if (cleanup != null) {
                if (call != null && call.failure != null) RegionThreadingEngine.accumulateFailure(call.failure, cleanup);
                else RegionThreadingEngine.throwUnchecked(cleanup);
            }
        }
    }


    boolean isIsolatedPlayerPrefix(List<RegionPlayerTickDispatch.Call> calls) {
        // The captured region contains only its player. Distinct singleton footprints prove
        // spatial independence, while reserveBoundPlayerState still checks all mutable aliases.
        var regions = this.engine.isolatedRegionScratch;
        regions.clear();
        try {
            for (int i = 0, size = calls.size(); i < size; i++) {
                var state = calls.get(i).admission.state();
                var footprint = state.spatial().footprint().regions();
                if (!state.permitsLocalNetworkReturn() || footprint.size() != 1 || !regions.add(footprint.getFirst())) return false;
            }
            return true;
        } finally {
            regions.clear();
        }
    }

    /** One bounded contiguous prefix. Every acquired capability is released after real physical join. */
    int dispatchPlayerPrefix(List<RegionPlayerTickDispatch.Call> ordered) {
        return this.dispatchPlayerPrefix(ordered, false);
    }

    int dispatchPlayerPrefix(List<RegionPlayerTickDispatch.Call> ordered, boolean skipIneligible) {
        if (ordered.isEmpty() || !this.engine.playerCriterionStorageEnabled()) return 0;
        final ServerPlayer first = ordered.getFirst().player;
        if (!first.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Player dispatch requires its original server publisher");
        final var firstPhase = ordered.getFirst().phase;
        final RegionThreadingEngine.PlayerPrefixTiming timing = this.engine.playerPrefixTiming == null ? null : this.engine.playerPrefixTiming[firstPhase.ordinal()];
        final long begin = timing == null ? 0 : System.nanoTime();
        long captured = 0, submitted = 0, returned = 0;
        int groupsSubmitted = 0;
        boolean enteredAdmission = false;
        if (this.engine.regionWorkAdmission.isOwner()) {
            // CONNECTION_BODY runs from inside ServerGamePacketListenerImpl.tick() while the server thread
            // already holds the serial CONNECTION admission fence; verify no workers are active.
            if (firstPhase != RegionPlayerTickCoordinator.Phase.CONNECTION_BODY || !this.engine.isInteractionCompletionQuiescent()) return 0;
        } else {
            if (!this.engine.regionWorkAdmission.tryEnter(this.engine::playerDispatchQuiescent)) return 0;
            enteredAdmission = true;
        }
        RegionTickBatch parent = null;
        boolean ownParent = false;
        boolean frozen = false;
        boolean regionPhase = false;
        boolean prefixClaimsHeld = false;
        final List<RegionPlayerSpatialLease> leases = new ArrayList<>();
        final List<RegionPlayerTickDispatch.Call> calls = new ArrayList<>();
        RegionPlayerTickDispatch effects = null;
        Throwable dispatchFailure = null;
        try {
            // Unknown extensions keep their ORIGINAL receive-thread contract as well as serial ticks.
            // Freezing even a subsequently refused prefix could move an explicit NETWORK payload to
            // the server queue. Inspect the global environment before creating that boundary; the full
            // per-call capture/worker consume below still rechecks every admission condition.
            boolean entityPhase = firstPhase == RegionPlayerTickCoordinator.Phase.ENTITY;
            boolean connectionBodyPhase = firstPhase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY;
            var environment = connectionBodyPhase
                    ? RegionPlayerExtensionSnapshot.captureConnectionBody(false)
                    : RegionPlayerExtensionSnapshot.capture();
            var localEnvironment = entityPhase && environment.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE
                    ? RegionPlayerExtensionSnapshot.capture(true) : environment;
            if (localEnvironment.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) {
                this.engine.capturePlayerAdmissionPlan(first, firstPhase, localEnvironment, null);
                return 0;
            }
            for (RegionTickBatch active : this.engine.activeTickBatches) {
                if (active.level() != first.serverLevel() || !active.isExecutingOnCurrentThread() || !active.isGroupsComplete()) return 0;
                if (parent != null) return 0;
                parent = active;
            }
            if (parent == null) {
                parent = new RegionPlayerTickDispatch.Lifecycle(first.serverLevel());
                if (this.engine.publishTickBatch(parent, first.serverLevel()) != TickBatchPublication.PUBLISHED) return 0;
                ownParent = true;
                parent.markExecutingOnCurrentThread();
            }
            // Overlap persistent worker OS wake-up (~90 us) with server-thread admission & leasing.
            if (ordered.size() > 1) {
                int preWakeCount = entityPhase && ordered.size() > 2 ? Math.max(2, (ordered.size() + 3) / 4) : ordered.size();
                this.engine.workerPool.preWake(Math.min(preWakeCount, this.engine.workerPool.threadCount()));
            }
            // The worker rechecks connection observations after closing the original network gate.
            int inspected = 0;
            for (RegionPlayerTickDispatch.Call call : ordered) {
                if (calls.size() == RegionThreadingEngine.PLAYER_PREFIX_LIMIT || call.player.serverLevel() != first.serverLevel() || call.phase != firstPhase) break;
                ++inspected;
                this.engine.playerRegions.publishAtPlayerStage(this.engine, call.player);
                var localCapture = entityPhase ? RegionPlayerAdmissionPlan.captureLocalEntity(this.engine, call.player) : null;
                var admission = localCapture == null ? null : localCapture.plan();
                if (admission == null) {
                    if (localEnvironment.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) {
                        this.engine.recordPlayerAdmissionCapture(call.phase, java.util.Objects.requireNonNull(localCapture));
                        if (skipIneligible) continue;
                        break;
                    }
                    if (entityPhase) {
                        var nonLocalIntrinsic = RegionPlayerAdmissionPlan.intrinsic(call.player, false, null, call.phase);
                        if (nonLocalIntrinsic.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) {
                            this.engine.recordPlayerAdmissionCapture(call.phase,
                                    new RegionPlayerAdmissionPlan.Capture(null, nonLocalIntrinsic.refusal(), nonLocalIntrinsic.detail()));
                            if (skipIneligible) continue;
                            break;
                        }
                    }
                    var geometry = RegionPlayerSpatialPlan.capture(this.engine, call.player, call.phase, true, true);
                    if (geometry.plan() != null
                            && (call.phase == RegionPlayerTickCoordinator.Phase.CONNECTION
                                    || (call.phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY
                                            && !RegionPlayerAdmissionPlan.mayEmitGameEvent(call.player)))) {
                        admission = RegionPlayerAdmissionPlan.tryCaptureLocalConnection(this.engine, call.player, geometry.plan());
                    }
                    if (admission == null) {
                        admission = this.engine.capturePlayerAdmissionPlan(call.player, call.phase, localEnvironment, geometry).plan();
                    }
                }
                if (admission == null) {
                    if (skipIneligible) continue;
                    break;
                }
                call.admission = admission;
                calls.add(call);
            }
            if (calls.isEmpty()) return 0;
            if (timing != null) captured = System.nanoTime();
            effects = new RegionPlayerTickDispatch(this.engine, parent);
            final List<RegionPlayerTickDispatch.Group> groups = new ArrayList<>(calls.size());
            boolean isolated = isIsolatedPlayerPrefix(calls);
            if (isolated) {
                if (!this.engine.playerStateClaims.isEmpty() || !this.engine.canReservePlayerSpatial(parent, first)) return 0;
                prefixClaimsHeld = this.engine.playerPrefixClaims.tryAcquire(calls);
                if (!prefixClaimsHeld) return 0;
                for (var call : calls) {
                    var lease = RegionPlayerSpatialLease.acquireBound(this.engine, parent, effects, List.of(call.admission.state()), true, true);
                    if (lease == null) return 0;
                    leases.add(lease);
                    groups.add(new RegionPlayerTickDispatch.Group(lease, List.of(call)));
                }
            } else {
                for (var call : calls) {
                    if (!call.admission.state().validatePrefix(false)) return 0;
                }
                final var entries = new ArrayList<RegionPlayerConflictPlan.Entry>(calls.size());
                final var byEntry = new java.util.IdentityHashMap<RegionPlayerConflictPlan.Entry, RegionPlayerTickDispatch.Call>(calls.size());
                for (var call : calls) {
                    var entry = call.admission.state().conflictEntry();
                    entries.add(entry);
                    byEntry.put(entry, call);
                }
                final var prefix = RegionPlayerConflictPlan.prefix(entries);
                if (prefix.nextIndex() == 0) return 0;
                calls.subList(prefix.nextIndex(), calls.size()).clear();
                for (var group : prefix.groups()) {
                    var members = new ArrayList<RegionPlayerTickDispatch.Call>(group.entries().size());
                    var states = new ArrayList<RegionPlayerStatePlan>(group.entries().size());
                    for (var entry : group.entries()) {
                        var call = byEntry.get(entry);
                        members.add(call);
                        states.add(call.admission.state());
                    }
                    var lease = this.engine.reserveBoundPlayerState(parent, effects, states, true);
                    if (lease == null) return 0;
                    leases.add(lease);
                    groups.add(new RegionPlayerTickDispatch.Group(lease, members));
                }
            }
            final RegionTaskBatch work = new RegionTaskBatch();
            final int maxLanes = entityPhase && isolated && groups.size() > 2
                    ? Math.max(2, (groups.size() + 3) / 4)
                    : this.engine.workerPool.threadCount();
            final int lanes = Math.min(groups.size(), Math.min(maxLanes, this.engine.workerPool.threadCount()));
            final var startup = new RegionPlayerTickDispatch.StartGate(lanes);
            final var nextGroup = lanes < groups.size() ? new java.util.concurrent.atomic.AtomicInteger(lanes) : null;
            final var dispatch = effects;
            boolean needsParallelValidation = false;
            if (isolated) {
                for (int i = 0, size = calls.size(); i < size; i++) {
                    var adm = calls.get(i).admission;
                    if (adm.state().needsTemplateValidation() || adm.spatial().needsTerrainValidation()) {
                        needsParallelValidation = true;
                        break;
                    }
                }
            }
            dispatch.parallelTemplateValidation = needsParallelValidation;
            dispatch.independentGroups = skipIneligible && isolated && entityPhase;
            // A group may resume its exact connection after returning only if every group owns
            // a single local-only player and has no captured target or shared state.
            boolean localWave = groups.size() == calls.size();
            if (localWave && !isolated) {
                for (int i = 0, size = calls.size(); i < size; i++) {
                    if (!calls.get(i).admission.state().permitsLocalNetworkReturn()) {
                        localWave = false;
                        break;
                    }
                }
            }
            // Keep the body’s direct network callbacks frozen through the publisher join and cleanup.
            dispatch.localNetworkWave = localWave && !connectionBodyPhase;
            for (int i = 0; i < lanes; i++) {
                final int lane = i;
                work.add("complete player " + firstPhase, parent.groupWork(() -> dispatch.runLane(groups, startup, lane, nextGroup), startup::refuse));
            }
            boolean networkReady;
            long freezeStart = timing == null ? 0 : System.nanoTime();
            if (dispatch.localNetworkWave) {
                var connections = new ArrayList<net.minecraft.network.Connection>(calls.size());
                for (int i = 0, size = calls.size(); i < size; i++) {
                    connections.add(calls.get(i).admission.state().connection());
                }
                networkReady = this.engine.playerNetworkGate.freezeLocalWave(first.server, connections);
            } else {
                networkReady = this.engine.playerNetworkGate.freezePreservingNetworkThread(first.server);
            }
            if (timing != null) timing.networkFreezeNanos += System.nanoTime() - freezeStart;
            if (!networkReady) return 0;
            frozen = true;
            if (!this.engine.playerTicks.admitWorkerPrefix(calls)) return 0;
            if (localEnvironment.hasExtraMods()) this.engine.playerTicksAdmittedWithMods.addAndGet(calls.size());
            parent.externalWaveActive(true);
            this.engine.beginRegionPhase(parent.level());
            regionPhase = true;
            if (timing != null) {
                submitted = System.nanoTime();
                groupsSubmitted = groups.size();
                dispatch.initLaneTimers(lanes, submitted);
            }
            if (connectionBodyPhase) {
                parent.submitAndJoinPlayerConnectionBody(work, this.engine.workerPool);
            } else {
                var joined = work.submit(this.engine.workerPool);
                joined.awaitCompletion();
                joined.throwIfFailed();
            }
            if (timing != null) returned = System.nanoTime();
            return skipIneligible ? inspected : calls.size();
        } catch (Throwable failure) {
            dispatchFailure = failure;
            Throwable original = calls.stream().filter(call -> call.failure != null).map(call -> call.failure).findFirst().orElse(null);
            if (original != null) {
                dispatchFailure = RegionThreadingEngine.accumulateFailure(original, failure);
                RegionThreadingEngine.throwUnchecked(dispatchFailure);
            }
            throw failure;
        } finally {
            long cleanupStart = timing == null ? 0 : System.nanoTime();
            Throwable cleanup = null;
            try {
                if (regionPhase) this.engine.levelTickPhaseState(parent.level()).endRegionPhase();
            } catch (Throwable problem) {
                cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
            }
            if (parent != null) parent.externalWaveActive(false);
            try {
                if (!calls.isEmpty()) {
                    Throwable finishFailure = this.engine.playerTicks.finishWorkerPrefix(calls);
                    if (finishFailure != null) cleanup = RegionThreadingEngine.accumulateFailure(cleanup, finishFailure);
                }
                for (var lease : leases) {
                    try {
                        lease.close();
                    } catch (Throwable problem) {
                        cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                    }
                }
                if (effects != null) {
                    try {
                        // A started pickup already transferred XP and marked its entity removed. Unlike
                        // cancellable future work this committed storage callback must run even after
                        // stop-new-admission. Shutdown/level retirement await this original batch fence.
                        this.engine.applyDeferredRemovals(parent.level(), effects.removals.drain(), effects.removals.isSeamClosed(), true);
                    } catch (Throwable problem) {
                        cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                    }
                    try {
                        this.engine.applyDeferredSectionUpdates(parent.level(), effects.sections.drain(), effects.sections.isSeamClosed());
                    } catch (Throwable problem) {
                        cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                    }
                }
            } finally {
                try {
                    if (prefixClaimsHeld) this.engine.playerPrefixClaims.release();
                    if (ownParent) {
                        parent.completeExternalWaves();
                        parent.finish();
                    }
                } catch (Throwable problem) {
                    cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                } finally {
                    if (ownParent) {
                        this.engine.activeTickBatches.remove(parent);
                        parent.clearExecutingOnCurrentThread();
                    }
                    try {
                        if (frozen) this.engine.playerNetworkGate.thaw();
                    } catch (Throwable problem) {
                        cleanup = RegionThreadingEngine.accumulateFailure(cleanup, problem);
                    } finally {
                        if (enteredAdmission) this.engine.regionWorkAdmission.exit();
                    }
                }
            }
            if (timing != null && captured == 0) timing.refusedBeforeCaptureNanos += cleanupStart - begin;
            if (returned != 0) {
                timing.waves++;
                timing.calls += calls.size();
                timing.groups += groupsSubmitted;
                timing.admissionNanos += captured - begin;
                timing.leaseNanos += submitted - captured;
                timing.submitAndJoinNanos += returned - submitted;
                timing.cleanupNanos += System.nanoTime() - returned;
                for (var call : calls) timing.workerBodyNanosSum += call.ticket.workerBodyNanos();
                if (effects != null) effects.accumulateLaneTimers(timing, submitted, returned);
            }
            if (cleanup != null) {
                Throwable original = dispatchFailure != null ? dispatchFailure
                        : calls.stream().filter(call -> call.failure != null).map(call -> call.failure).findFirst().orElse(null);
                if (original != null) RegionThreadingEngine.accumulateFailure(original, cleanup);
                else RegionThreadingEngine.throwUnchecked(cleanup);
            }
        }
    }

    /**
     * Controlled {@code CONNECTION_BODY} entry called at the exact {@code this.engine.player.doTick()} site
     * inside {@code ServerGamePacketListenerImpl.tick()}. Preserves complete {@code Connection.tick()}
     * ordering and lifecycle on the server thread while executing {@code originalDoTick} on a region worker.
     */
    @ApiStatus.Internal
    public boolean tryRunPlayerConnectionBodyTick(ServerPlayer player, Runnable originalDoTick) {
        if (!this.engine.playerTickPlanningRequested || this.engine.shutdown) return false;
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) {
            throw new IllegalStateException("Player connection body tick requires the original server thread");
        }
        if (!this.preparePlayerConnectionBody(player)) return false;
        var call = new RegionPlayerTickDispatch.Call(player, RegionPlayerTickCoordinator.Phase.CONNECTION_BODY, originalDoTick);
        int consumed = this.dispatchPlayerPrefix(List.of(call));
        if (consumed == 0) {
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    player.isPassenger() || !player.getPassengers().isEmpty()
                            ? RegionPlayerTickCoordinator.Reason.RIDING
                            : RegionPlayerTickCoordinator.Reason.CALLBACK_CONTRACT_OPEN);
            return false;
        }
        if (call.failure != null) RegionThreadingEngine.throwUnchecked(call.failure);
        if (!call.started) {
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    RegionPlayerTickCoordinator.Reason.STALE);
            return false;
        }
        return true;
    }

    private boolean preparePlayerConnectionBody(ServerPlayer player) {
        var timing = this.engine.playerPrefixTiming == null ? null : this.engine.playerPrefixTiming[RegionPlayerTickCoordinator.Phase.CONNECTION_BODY.ordinal()];
        if (timing == null) return checkPlayerConnectionBodyPreflight(player);
        long start = System.nanoTime();
        try {
            boolean eligible = checkPlayerConnectionBodyPreflight(player);
            if (!eligible) timing.preflightRefusals++;
            return eligible;
        } finally {
            timing.preflightCalls++;
            timing.preflightNanos += System.nanoTime() - start;
        }
    }

    private boolean checkPlayerConnectionBodyPreflight(ServerPlayer player) {
        if (player instanceof net.neoforged.neoforge.common.util.FakePlayer || player.connection == null) {
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    player instanceof net.neoforged.neoforge.common.util.FakePlayer
                            ? RegionPlayerTickCoordinator.Reason.FAKE_PLAYER
                            : RegionPlayerTickCoordinator.Reason.NOT_CURRENT_PLAYER);
            return false;
        }
        if (!this.engine.playerCriterionStorageEnabled()) {
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    RegionPlayerTickCoordinator.Reason.DEPENDENCIES);
            return false;
        }
        var spatialRefusal = RegionPlayerSpatialPlan.intrinsicRefusal(player, RegionPlayerTickCoordinator.Phase.CONNECTION_BODY, true);
        if (spatialRefusal != RegionPlayerSpatialPlan.Refusal.NONE) {
            this.engine.playerAdmissionRefusals.computeIfAbsent("CONNECTION_BODY:SPATIAL", key -> new AtomicLong()).incrementAndGet();
            this.engine.playerAdmissionLastRefusal.put("CONNECTION_BODY", "SPATIAL:" + spatialRefusal.name());
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    spatialRefusal == RegionPlayerSpatialPlan.Refusal.RIDING
                            ? RegionPlayerTickCoordinator.Reason.RIDING
                            : RegionPlayerTickCoordinator.Reason.CALLBACK_CONTRACT_OPEN);
            return false;
        }
        var intrinsicRefusal = RegionPlayerAdmissionPlan.intrinsic(player, false, null, RegionPlayerTickCoordinator.Phase.CONNECTION_BODY);
        if (intrinsicRefusal.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) {
            this.engine.playerAdmissionRefusals.computeIfAbsent("CONNECTION_BODY:" + intrinsicRefusal.refusal().name(), key -> new AtomicLong()).incrementAndGet();
            this.engine.playerAdmissionLastRefusal.put("CONNECTION_BODY", intrinsicRefusal.refusal() + ":" + intrinsicRefusal.detail());
            this.engine.playerTicks.recordUntrackedSerial(RegionPlayerTickCoordinator.Phase.CONNECTION_BODY,
                    RegionPlayerTickCoordinator.Reason.CALLBACK_CONTRACT_OPEN);
            return false;
        }
        return true;
    }

    int dispatchPlayerConnectionBodyPrefix(List<RegionPlayerTickDispatch.Call> calls) {
        return this.dispatchPlayerPrefix(calls);
    }

    void tickPlayerWorldSequence(RegionEntityTickBatch parent, List<Entity> entities) {
        for (int index = 0; index < entities.size();) {
            final List<RegionPlayerTickDispatch.Call> calls = new ArrayList<>();
            for (int next = index; next < entities.size() && calls.size() < RegionThreadingEngine.PLAYER_PREFIX_LIMIT; next++) {
                if (!(entities.get(next) instanceof ServerPlayer player) || player.getClass() != ServerPlayer.class
                        || player.isRemoved() || player.level() != parent.level() || player.isPassenger() || !player.getPassengers().isEmpty()
                        || !parent.level().isInEntityTickingRange(player.chunkPosition().toLong())
                        || RegionEntityTickActivation.isFrozen(player, parent.level().tickRateManager() == null || parent.level().tickRateManager().runsNormally()))
                    break;
                calls.add(new RegionPlayerTickDispatch.Call(player, RegionPlayerTickCoordinator.Phase.ENTITY,
                        () -> parent.level().guardEntityTick(parent.level()::tickNonPassenger, player)));
            }
            if (calls.isEmpty()) {
                this.engine.tickEntityServerSide(parent.level(), entities.get(index++));
                continue;
            }
            int consumed = this.dispatchPlayerPrefix(calls, true);
            if (consumed == 0) {
                for (var call : calls) {
                    this.engine.tickEntityServerSide(parent.level(), call.player);
                }
                index += calls.size();
                continue;
            }
            for (var call : calls.subList(0, consumed)) {
                if (call.failure != null) RegionThreadingEngine.throwUnchecked(call.failure);
                if (!call.started) this.engine.tickEntityServerSide(parent.level(), call.player);
            }
            index += consumed;
        }
    }

}
