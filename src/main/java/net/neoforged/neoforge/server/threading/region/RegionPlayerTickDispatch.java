/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.jetbrains.annotations.Nullable;

/** Complete original calls, subordinate to the existing engine/batch/pool and spatial/state capabilities. */
final class RegionPlayerTickDispatch implements RegionOwnershipLeaseGroup {
    static final class Call {
        final ServerPlayer player;
        final RegionPlayerTickCoordinator.Phase phase;
        final Runnable original;
        RegionPlayerAdmissionPlan admission;
        RegionPlayerTickCoordinator.Plan ticket;
        @Nullable
        Throwable failure;
        boolean started;

        Call(ServerPlayer player, RegionPlayerTickCoordinator.Phase phase, Runnable original) {
            this.player = player;
            this.phase = phase;
            this.original = original;
        }
    }

    /** At most one group per actual pool worker; no queued task is awaited by another running group. */
    static final class StartGate {
        final CountDownLatch checked;
        final int participants;
        final AtomicBoolean valid = new AtomicBoolean(true);

        StartGate(int groups) {
            this.checked = new CountDownLatch(groups);
            this.participants = groups;
        }

        void refuse() {
            this.valid.set(false);
            this.checked.countDown();
        }

        boolean ready() {
            this.checked.countDown();
            for (int spin = 0; spin < 256; ++spin) {
                if (this.checked.getCount() == 0) {
                    return this.valid.get();
                }
                Thread.onSpinWait();
            }
            boolean interrupted = false;
            while (this.checked.getCount() != 0) {
                try {
                    this.checked.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return this.valid.get();
        }
    }

    private final RegionThreadingEngine engine;
    final RegionTickBatch parent;
    final RegionDeferredEntitySectionUpdates sections;
    final RegionDeferredEntityRemovals removals;
    boolean localNetworkWave;
    boolean parallelTemplateValidation;
    @Nullable
    private long[] laneEnter, laneAfterVal, laneAfterGate, laneExit;

    void initLaneTimers(int lanes, long submitNanos) {
        this.laneEnter = new long[lanes];
        this.laneAfterVal = new long[lanes];
        this.laneAfterGate = new long[lanes];
        this.laneExit = new long[lanes];
    }

    void accumulateLaneTimers(RegionThreadingEngine.PlayerPrefixTiming timing, long submitted, long returned) {
        if (this.laneEnter == null) return;
        long maxWake = 0, maxVal = 0, maxGate = 0, maxLoop = 0, maxExit = 0;
        for (int i = 0; i < this.laneEnter.length; i++) {
            long e = this.laneEnter[i];
            if (e == 0) continue;
            long v = this.laneAfterVal[i];
            long g = this.laneAfterGate[i];
            long x = this.laneExit[i];
            if (e - submitted > maxWake) maxWake = e - submitted;
            if (v > e && v - e > maxVal) maxVal = v - e;
            if (g > v && g - v > maxGate) maxGate = g - v;
            if (x > g && x - g > maxLoop) maxLoop = x - g;
            if (x > maxExit) maxExit = x;
        }
        timing.workerWakeDelayNanos += maxWake;
        timing.validateLaneNanos += maxVal;
        timing.startGateWaitNanos += maxGate;
        timing.groupLoopNanos += maxLoop;
        if (maxExit > 0 && returned > maxExit) timing.barrierWakeNanos += returned - maxExit;
    }

    RegionPlayerTickDispatch(RegionThreadingEngine engine, RegionTickBatch parent) {
        this.engine = engine;
        this.parent = parent;
        this.sections = new RegionDeferredEntitySectionUpdates(parent);
        this.removals = new RegionDeferredEntityRemovals(parent);
    }

    /** A lane keeps separate leases/scopes for each conflict group; only submission is shared. */
    record Group(RegionPlayerSpatialLease lease, List<Call> calls) {}

    void runLane(List<Group> groups, StartGate startup, int lane, @Nullable AtomicInteger nextGroup) {
        final boolean timed = this.laneEnter != null && lane < this.laneEnter.length;
        if (timed) this.laneEnter[lane] = System.nanoTime();
        boolean laneExtensionsVerified = false;
        if (this.parallelTemplateValidation) {
            if (!validateLane(groups, startup, lane, timed)) return;
            laneExtensionsVerified = true;
        } else {
            if (RegionWorkerScope.isNativeWorkerThread() && verifyLaneExtensions(groups)) {
                laneExtensionsVerified = true;
            }
            if (timed) {
                long now = System.nanoTime();
                this.laneAfterVal[lane] = now;
                this.laneAfterGate[lane] = now;
            }
        }
        int index = lane;
        do {
            Group group = groups.get(index);
            StartGate gate = index == lane && !this.parallelTemplateValidation ? startup : null;
            Call last = group.calls().getLast();
            this.runGroup(group.lease(), group.calls(), gate, laneExtensionsVerified);
            if (last.failure != null || (!last.started && gate != null)) {
                if (timed) this.laneExit[lane] = System.nanoTime();
                return;
            }
            if (this.localNetworkWave && this.sections.pendingCount() == 0) {
                var connection = last.admission.state().connection();
                if (connection.isConnected()) this.engine.playerNetworkGate().resumeCompletedConnection(connection);
            }
            if (nextGroup == null) {
                if (timed) this.laneExit[lane] = System.nanoTime();
                return;
            }
            index = nextGroup.getAndIncrement();
        } while (index < groups.size());
        if (timed) this.laneExit[lane] = System.nanoTime();
    }

    private static boolean verifyLaneExtensions(List<Group> groups) {
        var phase = groups.getFirst().calls().getFirst().phase;
        boolean allLocalEntity = phase == RegionPlayerTickCoordinator.Phase.ENTITY;
        if (allLocalEntity) {
            for (int i = 0, size = groups.size(); i < size; i++) {
                var admission = groups.get(i).calls().getFirst().admission;
                if (admission == null || admission.spatial() == null || !admission.spatial().isLocalEntity()) {
                    allLocalEntity = false;
                    break;
                }
            }
        }
        var ext = phase == RegionPlayerTickCoordinator.Phase.CONNECTION_BODY
                ? RegionPlayerExtensionSnapshot.captureConnectionBody(false)
                : RegionPlayerExtensionSnapshot.capture(allLocalEntity);
        return ext.refusal() == RegionPlayerAdmissionPlan.Refusal.NONE;
    }

    private boolean validateLane(List<Group> groups, StartGate startup, int lane, boolean timed) {
        boolean validated = false;
        try {
            if (!RegionWorkerScope.isNativeWorkerThread()) return false;
            if (!verifyLaneExtensions(groups)) return false;
            for (int index = lane; index < groups.size(); index += startup.participants) {
                if (!groups.get(index).calls().getFirst().admission.state().validatePrefix(true)) return false;
            }
            if (timed) this.laneAfterVal[lane] = System.nanoTime();
            validated = true;
            boolean ok = startup.ready();
            if (timed) this.laneAfterGate[lane] = System.nanoTime();
            return ok;
        } finally {
            if (!validated) startup.refuse();
        }
    }

    private void runGroup(RegionPlayerSpatialLease lease, List<Call> calls, @Nullable StartGate gate) {
        this.runGroup(lease, calls, gate, false);
    }

    private void runGroup(RegionPlayerSpatialLease lease, List<Call> calls, @Nullable StartGate gate, boolean laneExtensionsVerified) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            if (gate != null) gate.refuse();
            return;
        }
        boolean checked = false;
        try (var spatial = lease.open()) {
            RegionPlayerStateScope first;
            try {
                first = lease.openPlayerState(calls.getFirst().admission.state(), laneExtensionsVerified);
            } catch (IllegalStateException refused) {
                if (gate != null) gate.refuse();
                checked = true;
                return;
            }
            try (first) {
                checked = true;
                if (gate != null && !gate.ready()) return;
                this.runOriginal(calls.getFirst());
            }
            if (calls.getFirst().failure != null || !calls.getFirst().started) return;
            for (int i = 1; i < calls.size(); i++) {
                Call call = calls.get(i);
                RegionPlayerStateScope state;
                try {
                    state = lease.openPlayerState(call.admission.state(), laneExtensionsVerified);
                } catch (IllegalStateException refused) {
                    return;
                }
                try (state) {
                    this.runOriginal(call);
                }
                if (call.failure != null) return;
            }
        } finally {
            if (!checked && gate != null) gate.refuse();
        }
    }

    private void runOriginal(Call call) {
        if (!this.engine.playerTicks().startWorker(call.ticket)) return;
        call.started = true;
        try {
            call.original.run();
        } catch (Throwable failure) {
            call.failure = failure;
        } finally {
            this.engine.playerTicks().workerReturned(call.ticket, call.failure);
        }
    }

    @Override
    public boolean owns(Entity entity) {
        return RegionPlayerSpatialLease.current() != null && RegionPlayerSpatialLease.current().owns(entity);
    }

    @Override
    public boolean acceptsDeferredSectionUpdates() {
        return this.sections.accepts();
    }

    @Override
    public boolean deferSectionUpdate(Entity entity, EntityInLevelCallback callback, long section) {
        return this.sections.defer(entity, callback, section);
    }

    @Override
    public boolean acceptsDeferredRemovals() {
        return this.removals.accepts();
    }

    @Override
    public boolean deferRemoval(Entity entity, EntityInLevelCallback callback, Entity.RemovalReason reason) {
        // Only the complete captured vanilla pickup may cross this seam. Player lifecycle,
        // arbitrary target removals and different callback identities remain forbidden.
        return (entity.getClass() == net.minecraft.world.entity.ExperienceOrb.class || entity.getClass() == net.minecraft.world.entity.item.ItemEntity.class)
                && reason == Entity.RemovalReason.DISCARDED && this.owns(entity) && RegionPlayerStateScope.allowsPickupRemoval(entity)
                && callback != EntityInLevelCallback.NULL && entity.getLevelCallback() == callback
                && this.removals.defer(entity, callback, reason);
    }

    @Override
    public boolean deferAddition(Entity entity, List<Entity> graph) {
        return false;
    }

    @Override
    public boolean deferPortalTransition(Entity entity) {
        return false;
    }

    static final class Lifecycle extends RegionTickBatch {
        Lifecycle(net.minecraft.server.level.ServerLevel level) {
            super(level, RegionTickSlice.ENTITY_TICK, 0);
            this.enableExternalWaves();
        }
    }
}
