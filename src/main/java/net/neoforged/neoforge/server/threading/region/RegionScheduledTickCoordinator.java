/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread orchestration, leasing, worker group execution, and fallback handling for the
 * scheduled block/fluid tick slice, behind the existing engine entry point.
 * <p>
 * The host bridges to the original publication, leasing, worker pool, context, and ordered handoff.
 * There is no independent lifecycle, executor, or permission source here.
 */
final class RegionScheduledTickCoordinator {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGION_LEASE_ATTEMPTS = 4;

    interface Host {
        int blockUpdateEntryBudget();

        boolean unclassifiedWorkerCallbacksForTesting();

        RegionScheduledTickMetrics metrics(ScheduledTickPhase phase);

        TickBatchPublication publish(RegionScheduledTickBatch<?> batch);

        boolean isLevelClosed(ServerLevel level);

        @Nullable
        RegionState regionAt(ServerLevel level, BlockPos pos);

        int maxCatchUpScheduledTicks();

        boolean shouldSkipOverloadedRegion(ServerLevel level, RegionState region);

        <T> boolean rescheduleThrottledScheduledTick(ServerLevel level, ScheduledTickPhase phase, ScheduledTick<T> tick, long targetGameTime);

        boolean acquireRegionLease(RegionState region, RegionTickBarrier barrier);

        void addLeasedGroup(RegionState region, Runnable registration);

        Runnable withRegionMailboxDelivery(ServerLevel level, RegionState region, Runnable body);

        void submitAndAwait(RegionScheduledTickBatch<?> batch, RegionTickBarrier barrier);

        void runInRegionContext(ServerLevel level, RegionCoordinate coordinate, RegionState region, Runnable body);

        void setCurrentScheduledTickRandom(@Nullable RandomSource random);

        void setCurrentRegionTickMutations(@Nullable Queue<Runnable> mutations);

        void setCurrentPropagationOrigin(@Nullable BlockPos pos);

        void recordWorkerTickFailure(RegionTickBatch batch, RegionCoordinate coordinate, Throwable throwable);

        void removeIfEmpty(RegionState region);

        void countSkippedTicks(int count);

        void countFallbackTick();

        void countTicksOnWorkers(int count);

        void countRefusedDeferredBlockUpdates(int count);

        void applyDeferredBlockUpdates(RegionTickBatch batch);

        void applyRegionTickMutations(Queue<Runnable> mutations);

        void retireBatch(RegionTickBatch batch);

        RegionLayout layout();

        Set<Fluid> demotedFluids();

        void demoteFluid(Fluid fluid, Throwable failure);
    }

    private final Host host;
    private final AtomicBoolean workersNoticeLogged = new AtomicBoolean();

    RegionScheduledTickCoordinator(Host host) {
        this.host = host;
    }

    <T> void executeAndAwait(ServerLevel level, ScheduledTickPhase phase, List<ScheduledTick<T>> ticks,
            BiConsumer<BlockPos, T> callback, BiConsumer<BlockPos, T> fallbackCallback,
            Consumer<ScheduledTick<T>> beginTick, RegionTickBarrier barrier) {
        if (ticks.isEmpty()) {
            return;
        }

        final RegionScheduledTickMetrics metrics = this.host.metrics(phase);
        final int budget = this.host.blockUpdateEntryBudget();
        final RegionScheduledTickBatch<T> batch = new RegionScheduledTickBatch<>(
                level, phase, budget, this.host.unclassifiedWorkerCallbacksForTesting());
        final TickBatchPublication publication = this.host.publish(batch);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            this.host.countSkippedTicks(ticks.size());
            metrics.skipped.add(ticks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            for (final ScheduledTick<T> tick : ticks) {
                beginTick.accept(tick);
                try {
                    fallbackCallback.accept(tick.pos(), tick.type());
                    metrics.serial.increment();
                } catch (Throwable failure) {
                    metrics.failures.increment();
                    throw failure;
                }
            }
            return;
        }
        batch.markExecutingOnCurrentThread();
        final int admittedTickCount = Math.min(ticks.size(),
                budget / RegionTickSlice.SCHEDULED_TICK.reservationPerUnit());
        final List<ScheduledTick<T>> admittedTicks = admittedTickCount == ticks.size() ? ticks : ticks.subList(0, admittedTickCount);
        if (admittedTickCount < ticks.size()) {
            batch.addFallback(ticks.subList(admittedTickCount, ticks.size()));
            this.host.countRefusedDeferredBlockUpdates(ticks.size() - admittedTickCount);
        }
        final List<ScheduledTick<T>> dispatchTicks;
        if (phase == ScheduledTickPhase.FLUID && !this.host.unclassifiedWorkerCallbacksForTesting()) {
            final List<ScheduledTick<T>> inlandTicks = new ArrayList<>(admittedTicks.size());
            final List<ScheduledTick<T>> borderTicks = new ArrayList<>();
            RegionScheduledTickAdmission.partitionFluidTicks(
                    level, this.host.layout(), admittedTicks, this.host.demotedFluids(), inlandTicks, borderTicks);
            if (!borderTicks.isEmpty()) {
                batch.addFallback(borderTicks);
            }
            dispatchTicks = inlandTicks;
        } else {
            dispatchTicks = admittedTicks;
        }
        Throwable failure = null;
        final long dispatchStart = System.nanoTime();
        try {
            try {
                this.leaseGroups(batch, dispatchTicks, callback, beginTick, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                try {
                    this.host.submitAndAwait(batch, barrier);
                } catch (Throwable throwable) {
                    if (failure == null) {
                        failure = throwable;
                    }
                }
            }
        } finally {
            metrics.dispatchAwaitNanos.add(System.nanoTime() - dispatchStart);
            final long completionStart = System.nanoTime();
            failure = runCompletion(failure, () -> this.host.applyDeferredBlockUpdates(batch));
            failure = runCompletion(failure, () -> this.tickFallbacks(batch, fallbackCallback, beginTick));
            failure = runCompletion(failure, () -> this.host.applyRegionTickMutations(batch.scheduledTickMutations()));
            batch.finish();
            this.host.retireBatch(batch);
            batch.clearExecutingOnCurrentThread();
            metrics.completionNanos.add(System.nanoTime() - completionStart);
        }

        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Region tick batch failed", failure);
        }
        batch.throwIfFailed();
    }

    private <T> void leaseGroups(RegionScheduledTickBatch<T> batch, List<ScheduledTick<T>> pending,
            BiConsumer<BlockPos, T> callback, Consumer<ScheduledTick<T>> beginTick, RegionTickBarrier barrier) {
        List<ScheduledTick<T>> remaining = pending;
        int batchedTicks = 0;
        for (int attempt = 0; attempt < MAX_REGION_LEASE_ATTEMPTS && !remaining.isEmpty(); attempt++) {
            final RegionTickGrouping<RegionState, ScheduledTick<T>> grouping = new RegionTickGrouping<>();
            final List<ScheduledTick<T>> retry = new ArrayList<>();
            int rescheduledCount = 0;
            final int maxReschedule = this.host.maxCatchUpScheduledTicks();
            for (final ScheduledTick<T> tick : remaining) {
                final RegionState region = this.host.regionAt(batch.level(), tick.pos());
                if (region == null || region.isDead()) {
                    retry.add(tick);
                } else if (this.host.shouldSkipOverloadedRegion(batch.level(), region)
                        && rescheduledCount < maxReschedule
                        && this.host.rescheduleThrottledScheduledTick(batch.level(), batch.phase(), tick, region.overloadState().nextActiveGameTime())) {
                    ++rescheduledCount;
                    this.host.countSkippedTicks(1);
                } else {
                    grouping.add(region, tick);
                }
            }
            if (grouping.isEmpty()) {
                remaining = retry;
                break;
            }

            final List<ScheduledTick<T>> next = new ArrayList<>(retry);
            boolean leasedAny = false;
            for (final RegionState region : grouping.regions()) {
                if (batch.leases(region)) {
                    batch.group(region).ifPresent(group -> group.addTicks(grouping.items(region)));
                    continue;
                }
                if (!this.host.acquireRegionLease(region, barrier)) {
                    next.addAll(grouping.items(region));
                    continue;
                }
                leasedAny = true;
                batchedTicks += grouping.size(region);
                this.host.addLeasedGroup(region, () -> batch.addGroup(region, grouping.items(region), batch.nextRandomSeed(region.coordinate()),
                        group -> this.host.withRegionMailboxDelivery(batch.level(), region, this.groupBody(group, callback, beginTick))));
            }
            remaining = next;
            if (!leasedAny) {
                break;
            }
        }

        if (!remaining.isEmpty()) {
            batch.addFallback(remaining);
        }
        if (batchedTicks > 0 && this.workersNoticeLogged.compareAndSet(false, true)) {
            LOGGER.info("Region scheduled {} ticks are now executing on region workers: {} ticks", batch.phase().description(), batchedTicks);
        }
    }

    private <T> Runnable groupBody(RegionScheduledTickBatch.Group<T> group, BiConsumer<BlockPos, T> callback, Consumer<ScheduledTick<T>> beginTick) {
        final RegionState region = group.region();
        final RegionCoordinate coordinate = region.coordinate();
        final ServerLevel level = group.level();
        final List<ScheduledTick<T>> ticks = group.ticks();
        final RandomSource random = group.random();
        return () -> {
            final long start = System.nanoTime();
            final int[] ticked = { 0 };
            try {
                region.beginOwnership();
                this.host.setCurrentScheduledTickRandom(random);
                this.host.setCurrentRegionTickMutations(group.batch().scheduledTickMutations());
                try {
                    this.host.runInRegionContext(level, coordinate, region, () -> {
                        int attempted = 0;
                        final RegionScheduledTickMetrics metrics = this.host.metrics(group.batch().phase());
                        for (final ScheduledTick<T> tick : ticks) {
                            ++attempted;
                            final long callbackStart = System.nanoTime();
                            try {
                                this.host.setCurrentPropagationOrigin(tick.pos());
                                try {
                                    beginTick.accept(tick);
                                    callback.accept(tick.pos(), tick.type());
                                    if (RegionWorkerScope.isNativeWorkerThread()) metrics.workers.increment();
                                    else metrics.serial.increment();
                                } finally {
                                    this.host.setCurrentPropagationOrigin(null);
                                }
                            } catch (Throwable throwable) {
                                metrics.failures.increment();
                                if (group.batch().phase() == ScheduledTickPhase.FLUID && !(throwable instanceof Error)) {
                                    if (tick.type() instanceof Fluid fluid) {
                                        this.host.demoteFluid(fluid, throwable);
                                    }
                                    group.batch().addWorkerDemotedFallback(tick);
                                    for (int rem = attempted; rem < ticks.size(); ++rem) {
                                        group.batch().addWorkerDemotedFallback(ticks.get(rem));
                                    }
                                    return;
                                }
                                metrics.skipped.add(ticks.size() - attempted);
                                this.host.countSkippedTicks(ticks.size() - attempted);
                                this.host.recordWorkerTickFailure(group.batch(), coordinate, throwable);
                                return;
                            } finally {
                                if (RegionWorkerScope.isNativeWorkerThread()) metrics.workerNanos.add(System.nanoTime() - callbackStart);
                            }
                            ++ticked[0];
                        }
                    });
                } finally {
                    this.host.setCurrentScheduledTickRandom(null);
                    this.host.setCurrentRegionTickMutations(null);
                }
            } finally {
                region.endOwnership();
                region.clearRunning();
                final long duration = System.nanoTime() - start;
                region.tickMetrics().recordTick(duration);
                RegionTickWatchdog.warnIfLongTick("scheduled tick batch " + coordinate, duration);
                this.host.countTicksOnWorkers(ticked[0]);
                this.host.removeIfEmpty(region);
            }
        };
    }

    private <T> void tickFallbacks(RegionScheduledTickBatch<T> batch, BiConsumer<BlockPos, T> fallbackCallback, Consumer<ScheduledTick<T>> beginTick) {
        final ServerLevel level = batch.level();
        final RegionScheduledTickMetrics metrics = this.host.metrics(batch.phase());
        for (final ScheduledTick<T> tick : batch.fallbackTicks()) {
            if (this.host.isLevelClosed(level)) {
                this.host.countSkippedTicks(1);
                metrics.skipped.increment();
                continue;
            }
            this.host.countFallbackTick();
            beginTick.accept(tick);
            try {
                fallbackCallback.accept(tick.pos(), tick.type());
                metrics.serial.increment();
            } catch (Throwable failure) {
                metrics.failures.increment();
                throw failure;
            }
        }
        ScheduledTick<T> demotedTick;
        while ((demotedTick = batch.workerDemotedFallbackTicks().poll()) != null) {
            if (this.host.isLevelClosed(level)) {
                this.host.countSkippedTicks(1);
                metrics.skipped.increment();
                continue;
            }
            this.host.countFallbackTick();
            try {
                fallbackCallback.accept(demotedTick.pos(), demotedTick.type());
                metrics.serial.increment();
            } catch (Throwable failure) {
                metrics.failures.increment();
                throw failure;
            }
        }
    }

    @Nullable
    private static Throwable runCompletion(@Nullable Throwable previousFailure, Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (previousFailure == null) {
                return throwable;
            }
            if (previousFailure != throwable) {
                previousFailure.addSuppressed(throwable);
            }
            return previousFailure;
        }
        return previousFailure;
    }
}
