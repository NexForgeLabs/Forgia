/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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
        final AtomicBoolean valid = new AtomicBoolean(true);

        StartGate(int groups) {
            this.checked = new CountDownLatch(groups);
        }

        void refuse() {
            this.valid.set(false);
            this.checked.countDown();
        }

        boolean ready() {
            this.checked.countDown();
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

    RegionPlayerTickDispatch(RegionThreadingEngine engine, RegionTickBatch parent) {
        this.engine = engine;
        this.parent = parent;
        this.sections = new RegionDeferredEntitySectionUpdates(parent);
        this.removals = new RegionDeferredEntityRemovals(parent);
    }

    void runGroup(RegionPlayerSpatialLease lease, List<Call> calls, StartGate gate) {
        if (!RegionWorkerScope.isNativeWorkerThread()) {
            gate.refuse();
            return;
        }
        boolean checked = false;
        try (var spatial = lease.open()) {
            RegionPlayerStateScope first;
            try {
                first = lease.openPlayerState(calls.getFirst().admission.state());
            } catch (IllegalStateException refused) {
                gate.refuse();
                checked = true;
                return;
            }
            try (first) {
                checked = true;
                if (!gate.ready()) return;
                this.runOriginal(calls.getFirst());
            }
            if (calls.getFirst().failure != null || !calls.getFirst().started) return;
            for (int i = 1; i < calls.size(); i++) {
                Call call = calls.get(i);
                RegionPlayerStateScope state;
                try {
                    state = lease.openPlayerState(call.admission.state());
                } catch (IllegalStateException refused) {
                    return;
                }
                try (state) {
                    this.runOriginal(call);
                }
                if (call.failure != null) return;
            }
        } finally {
            if (!checked) gate.refuse();
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
