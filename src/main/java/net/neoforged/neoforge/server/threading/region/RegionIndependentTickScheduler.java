/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Phase 2: Folia-style independent per-region asynchronous tick clock scheduler.
 * <p>
 * Each active {@link RegionCoordinate} owns its own tick clock ({@link RegionClock}) with an independent
 * 50ms (20 TPS) target schedule and an atomic {@code inFlight} guard. When a region is due:
 * <ul>
 * <li>If it is idle, its complete region tick body is dispatched asynchronously to {@link RegionWorkerPool}
 *     without forcing the server thread or other regions to wait on a global barrier.</li>
 * <li>If a hotspot region is still running its previous tick on a worker ({@code inFlight == true}),
 *     it is skipped non-blockingly ({@link DispatchOutcome#BUSY_SKIPPED}) so fast regions continue running
 *     at 20 TPS unaffected by the slow region.</li>
 * <li>Completed worker ticks enqueue their main-thread side effects into a lock-free per-level commit queue
 *     drained non-blockingly by the server thread.</li>
 * <li>Saves, chunk unloads, and level shutdowns pin only the target region(s) via {@link #awaitRegionQuiescent}
 *     or {@link #awaitLevelQuiescent} while servicing pending commits to guarantee deadlock-free quiescence.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class RegionIndependentTickScheduler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int TPS_WINDOW_SIZE = 20;

    public enum DispatchOutcome {
        SUBMITTED,
        NOT_DUE,
        BUSY_SKIPPED,
        PINNED,
        REJECTED
    }

    public record RegionSnapshot(
            RegionCoordinate coordinate,
            long localTickCount,
            boolean inFlight,
            double ewmaMs,
            double observedTps,
            long skippedWhileBusy) {}

    public record Snapshot(
            boolean enabled,
            int trackedRegions,
            int inFlightRegions,
            long submittedTicks,
            long completedTicks,
            long skippedBusyTicks,
            long failedTicks,
            long pendingCommits,
            long drainedCommits,
            List<RegionSnapshot> slowestRegions) {}

    private static final class RegionClock {
        final RegionCoordinate coordinate;
        final AtomicBoolean inFlight = new AtomicBoolean();
        final AtomicInteger quiescencePins = new AtomicInteger();
        final Object completionMonitor = new Object();
        final AtomicLong skippedWhileBusy = new AtomicLong();
        final ConcurrentLinkedQueue<Runnable> pendingSubWaves = new ConcurrentLinkedQueue<>();
        final AtomicInteger pendingSubWaveCount = new AtomicInteger();
        volatile long lastDispatchGameTime = Long.MIN_VALUE;
        final long[] completionTimestamps = new long[TPS_WINDOW_SIZE];
        int completionCursor;
        int completionSamples;

        volatile long localTickCount;
        volatile long nextDueNanos;
        volatile long ewmaDurationNanos;
        volatile long ewmaEntityNanos;

        RegionClock(RegionCoordinate coordinate, long initialDueNanos) {
            this.coordinate = coordinate;
            this.nextDueNanos = initialDueNanos;
        }

        void recordEntityDuration(long durationNanos) {
            final long clamped = Math.max(1L, durationNanos);
            final long prev = this.ewmaEntityNanos;
            this.ewmaEntityNanos = prev == 0L ? clamped : (3L * prev + clamped + 2L) / 4L;
        }

        void recordCompletion(long durationNanos, long endNanos, long intervalNanos, int maxCatchUpTicks) {
            final long clamped = Math.max(1L, durationNanos);
            final long prev = this.ewmaDurationNanos;
            this.ewmaDurationNanos = prev == 0L ? clamped : (3L * prev + clamped + 2L) / 4L;
            ++this.localTickCount;

            final long scheduledNext = this.nextDueNanos + intervalNanos;
            final long maxLag = intervalNanos * Math.max(1, maxCatchUpTicks);
            if (endNanos - scheduledNext > maxLag) {
                // Smoothly realign a severely lagging region instead of spiraling in catch-up debt.
                this.nextDueNanos = endNanos;
            } else {
                this.nextDueNanos = scheduledNext;
            }

            synchronized (this.completionMonitor) {
                this.completionTimestamps[this.completionCursor] = endNanos;
                this.completionCursor = (this.completionCursor + 1) % TPS_WINDOW_SIZE;
                if (this.completionSamples < TPS_WINDOW_SIZE) {
                    ++this.completionSamples;
                }
                this.completionMonitor.notifyAll();
            }
        }

        double observedTps(long intervalNanos) {
            final int samples;
            final long oldest;
            final long newest;
            synchronized (this.completionMonitor) {
                samples = this.completionSamples;
                if (samples < 2) {
                    return this.ewmaDurationNanos <= intervalNanos ? 20.0D : Math.min(20.0D, 1_000_000_000.0D / this.ewmaDurationNanos);
                }
                final int newestIdx = (this.completionCursor - 1 + TPS_WINDOW_SIZE) % TPS_WINDOW_SIZE;
                final int oldestIdx = samples < TPS_WINDOW_SIZE ? 0 : this.completionCursor;
                newest = this.completionTimestamps[newestIdx];
                oldest = this.completionTimestamps[oldestIdx];
            }
            final long span = newest - oldest;
            if (span <= 0L) {
                return 20.0D;
            }
            final double tps = ((samples - 1) * 1_000_000_000.0D) / span;
            return Math.min(20.0D, tps);
        }

        RegionSnapshot snapshot(long intervalNanos) {
            return new RegionSnapshot(
                    this.coordinate,
                    this.localTickCount,
                    this.inFlight.get(),
                    this.ewmaDurationNanos / 1_000_000.0D,
                    this.observedTps(intervalNanos),
                    this.skippedWhileBusy.get());
        }
    }

    private final RegionWorkerPool workerPool;
    private final BiConsumer<RegionState, Runnable> regionContextRunner;
    private final Map<RegionCoordinate, RegionClock> clocks = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Queue<Runnable>> commitQueues = new ConcurrentHashMap<>();

    private final AtomicLong totalSubmitted = new AtomicLong();
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalSkippedBusy = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private final AtomicLong totalDrainedCommits = new AtomicLong();
    private final AtomicInteger inFlightCount = new AtomicInteger();

    private volatile boolean closed;
    private volatile long intervalNanosOverride = -1L;
    private volatile int maxCatchUpOverride = -1;

    RegionIndependentTickScheduler(RegionWorkerPool workerPool, BiConsumer<RegionState, Runnable> regionContextRunner) {
        this.workerPool = Objects.requireNonNull(workerPool, "workerPool");
        this.regionContextRunner = Objects.requireNonNull(regionContextRunner, "regionContextRunner");
    }

    public long tickIntervalNanos() {
        final long override = this.intervalNanosOverride;
        return override > 0L ? override : RegionThreadingConfig.independentRegionTickIntervalNanos();
    }

    public int maxCatchUpTicks() {
        final int override = this.maxCatchUpOverride;
        return override > 0 ? override : RegionThreadingConfig.independentRegionMaxCatchUpTicks();
    }

    @org.jetbrains.annotations.VisibleForTesting
    public void setTimingForTesting(long intervalNanos, int maxCatchUpTicks) {
        this.intervalNanosOverride = intervalNanos;
        this.maxCatchUpOverride = maxCatchUpTicks;
    }

    /**
     * Attempts to dispatch an independent asynchronous tick for {@code region} if its local clock is due.
     * <p>
     * Never blocks the caller. If {@code region} is still running its previous tick on a worker, increments
     * {@code skippedWhileBusy} and returns {@link DispatchOutcome#BUSY_SKIPPED}.
     */
    public DispatchOutcome submitIfDue(
            RegionState region,
            Runnable regionBody,
            @Nullable Runnable serverThreadCommit,
            long nowNanos) {
        return this.submitIfDue(region, regionBody, serverThreadCommit, nowNanos, -1L);
    }

    public DispatchOutcome submitIfDue(
            RegionState region,
            Runnable regionBody,
            @Nullable Runnable serverThreadCommit,
            long nowNanos,
            long coalesceGameTime) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(regionBody, "regionBody");
        if (this.closed || region.isDead()) {
            return DispatchOutcome.REJECTED;
        }
        final RegionCoordinate coord = region.coordinate();
        final RegionClock clock = this.clocks.computeIfAbsent(coord, c -> new RegionClock(c, nowNanos));
        if (clock.quiescencePins.get() > 0) {
            return DispatchOutcome.PINNED;
        }
        if (nowNanos < clock.nextDueNanos && (coalesceGameTime < 0L || clock.lastDispatchGameTime != coalesceGameTime)) {
            return DispatchOutcome.NOT_DUE;
        }
        if (!clock.inFlight.compareAndSet(false, true)) {
            // Coalesce same-game-tick BE sub-waves (split by a serial fence in the same Level.tickBlockEntities pass)
            // onto the already-running worker for this region instead of dropping them as BUSY_SKIPPED.
            if (coalesceGameTime >= 0L
                    && clock.lastDispatchGameTime == coalesceGameTime
                    && clock.pendingSubWaveCount.get() < 8
                    && clock.quiescencePins.get() == 0
                    && !this.closed) {
                clock.pendingSubWaveCount.incrementAndGet();
                clock.pendingSubWaves.add(regionBody);
                if (serverThreadCommit != null && coord.level() != null) {
                    this.enqueueCommit(coord.level(), serverThreadCommit);
                }
                if (clock.inFlight.get()) {
                    this.totalSubmitted.incrementAndGet();
                    return DispatchOutcome.SUBMITTED;
                }
                if (!clock.inFlight.compareAndSet(false, true)) {
                    this.totalSubmitted.incrementAndGet();
                    return DispatchOutcome.SUBMITTED;
                }
                regionBody = () -> {};
            } else {
                clock.skippedWhileBusy.incrementAndGet();
                this.totalSkippedBusy.incrementAndGet();
                return DispatchOutcome.BUSY_SKIPPED;
            }
        }
        if (coalesceGameTime >= 0L) {
            clock.lastDispatchGameTime = coalesceGameTime;
        }
        // Double-check quiescence pin after winning inFlight CAS.
        if (this.closed || clock.quiescencePins.get() > 0 || region.isDead()) {
            this.releaseInFlight(clock);
            return DispatchOutcome.PINNED;
        }
        if (!region.tryMarkRunning()) {
            clock.skippedWhileBusy.incrementAndGet();
            this.totalSkippedBusy.incrementAndGet();
            this.releaseInFlight(clock);
            return DispatchOutcome.BUSY_SKIPPED;
        }

        final long intervalNanos = this.tickIntervalNanos();
        final int maxCatchUp = this.maxCatchUpTicks();
        this.inFlightCount.incrementAndGet();
        final Runnable initialBody = regionBody;
        final boolean accepted = this.workerPool.execute("independent region tick " + coord, () -> {
            final long start = System.nanoTime();
            final boolean enteredWorkerScope = !RegionWorkerScope.isNativeWorkerThread();
            if (enteredWorkerScope) {
                RegionWorkerScope.enter();
            }
            try {
                region.beginOwnership();
                try {
                    this.regionContextRunner.accept(region, initialBody);
                    Runnable subWave;
                    while ((subWave = clock.pendingSubWaves.poll()) != null) {
                        clock.pendingSubWaveCount.decrementAndGet();
                        this.regionContextRunner.accept(region, subWave);
                    }
                } finally {
                    region.endOwnership();
                }
                if (serverThreadCommit != null && coord.level() != null) {
                    this.enqueueCommit(coord.level(), serverThreadCommit);
                }
                this.totalCompleted.incrementAndGet();
            } catch (Throwable throwable) {
                this.totalFailed.incrementAndGet();
                LOGGER.error("Independent region tick failed for {}", coord, throwable);
            } finally {
                if (enteredWorkerScope) {
                    RegionWorkerScope.exit();
                }
                final long end = System.nanoTime();
                final long duration = end - start;
                region.tickMetrics().recordTick(duration);
                clock.recordCompletion(duration, end, intervalNanos, maxCatchUp);
                region.clearRunning();
                this.inFlightCount.decrementAndGet();
                this.releaseInFlight(clock);
            }
        });

        if (!accepted) {
            region.clearRunning();
            this.inFlightCount.decrementAndGet();
            this.releaseInFlight(clock);
            return DispatchOutcome.REJECTED;
        }
        this.totalSubmitted.incrementAndGet();
        return DispatchOutcome.SUBMITTED;
    }

    /** Enqueues a main-thread commit task produced by an asynchronous region worker. */
    public void enqueueCommit(ServerLevel level, Runnable commitAction) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(commitAction, "commitAction");
        this.commitQueues.computeIfAbsent(level, ignored -> new ConcurrentLinkedQueue<>()).add(commitAction);
    }

    /**
     * Non-blockingly drains completed asynchronous region commits for {@code level} on the server thread.
     *
     * @param budgetNanos maximum wall time to spend draining (pass {@code <= 0} to drain all currently queued commits)
     * @return number of commits executed
     */
    public int drainCompletedCommits(ServerLevel level, long budgetNanos) {
        final Queue<Runnable> queue = this.commitQueues.get(level);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        final long deadline = budgetNanos > 0L ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
        int drained = 0;
        while (true) {
            final Runnable task = queue.poll();
            if (task == null) {
                break;
            }
            try {
                task.run();
            } catch (Throwable throwable) {
                LOGGER.error("Async region commit failed on level {}", level.dimension().location(), throwable);
            }
            ++drained;
            this.totalDrainedCommits.incrementAndGet();
            if (budgetNanos > 0L && System.nanoTime() >= deadline) {
                break;
            }
        }
        return drained;
    }

    /**
     * Pins a single region and waits until its currently in-flight asynchronous tick (if any) finishes.
     * Other regions continue running unaffected.
     *
     * @return a handle that MUST be closed to unpin the region
     */
    public AutoCloseable pinAndAwaitRegionQuiescent(RegionCoordinate coordinate) {
        Objects.requireNonNull(coordinate, "coordinate");
        final RegionClock clock = this.clocks.computeIfAbsent(coordinate, c -> new RegionClock(c, System.nanoTime()));
        clock.quiescencePins.incrementAndGet();
        this.awaitClockIdle(clock, coordinate.level());
        return () -> clock.quiescencePins.decrementAndGet();
    }

    /**
     * Pins all regions of {@code level}, waits for every in-flight tick of {@code level} to finish,
     * drains all remaining commits, and removes the level's clocks when {@code retire} is true.
     */
    public void awaitLevelQuiescent(ServerLevel level, boolean retire) {
        Objects.requireNonNull(level, "level");
        final List<RegionClock> levelClocks = new ArrayList<>();
        for (final RegionClock clock : this.clocks.values()) {
            if (clock.coordinate.level() == level) {
                clock.quiescencePins.incrementAndGet();
                levelClocks.add(clock);
            }
        }
        try {
            for (final RegionClock clock : levelClocks) {
                this.awaitClockIdle(clock, level);
            }
            this.drainCompletedCommits(level, 0L);
            if (retire) {
                for (final RegionClock clock : levelClocks) {
                    this.clocks.remove(clock.coordinate, clock);
                }
                this.commitQueues.remove(level);
            }
        } finally {
            if (!retire) {
                for (final RegionClock clock : levelClocks) {
                    clock.quiescencePins.decrementAndGet();
                }
            }
        }
    }

    /** Shuts down the scheduler, waits for all in-flight asynchronous region ticks, and drains remaining commits. */
    public void shutdown() {
        this.closed = true;
        for (final RegionClock clock : this.clocks.values()) {
            this.awaitClockIdle(clock, clock.coordinate.level());
        }
        for (final ServerLevel level : List.copyOf(this.commitQueues.keySet())) {
            this.drainCompletedCommits(level, 0L);
        }
        this.clocks.clear();
        this.commitQueues.clear();
    }

    public boolean isRegionInFlight(RegionCoordinate coordinate) {
        final RegionClock clock = this.clocks.get(coordinate);
        return clock != null && clock.inFlight.get();
    }

    public long localTickCount(RegionCoordinate coordinate) {
        final RegionClock clock = this.clocks.get(coordinate);
        return clock == null ? 0L : clock.localTickCount;
    }

    public long skippedWhileBusy(RegionCoordinate coordinate) {
        final RegionClock clock = this.clocks.get(coordinate);
        return clock == null ? 0L : clock.skippedWhileBusy.get();
    }

    public Snapshot snapshot(boolean enabled, int topLimit) {
        final long interval = this.tickIntervalNanos();
        final List<RegionSnapshot> list = new ArrayList<>(this.clocks.size());
        for (final RegionClock clock : this.clocks.values()) {
            list.add(clock.snapshot(interval));
        }
        list.sort(Comparator.comparingDouble(RegionSnapshot::ewmaMs).reversed());
        final List<RegionSnapshot> top = list.size() <= topLimit ? List.copyOf(list) : List.copyOf(list.subList(0, topLimit));
        long pending = 0L;
        for (final Queue<Runnable> q : this.commitQueues.values()) {
            pending += q.size();
        }
        return new Snapshot(
                enabled,
                this.clocks.size(),
                this.inFlightCount.get(),
                this.totalSubmitted.get(),
                this.totalCompleted.get(),
                this.totalSkippedBusy.get(),
                this.totalFailed.get(),
                pending,
                this.totalDrainedCommits.get(),
                top);
    }

    public void recordEntitySliceDuration(RegionCoordinate coordinate, long durationNanos) {
        if (this.closed) {
            return;
        }
        final RegionClock clock = this.clocks.computeIfAbsent(coordinate, coord -> new RegionClock(coord, System.nanoTime()));
        clock.recordEntityDuration(durationNanos);
    }

    public boolean shouldThrottleEntityTick(RegionCoordinate coordinate, long gameTime) {
        final RegionClock clock = this.clocks.get(coordinate);
        if (clock == null) {
            return false;
        }
        if (clock.inFlight.get()) {
            return true;
        }
        final long ewma = clock.ewmaEntityNanos;
        final long moderate = RegionThreadingConfig.entityTickThrottleModerateNanos();
        if (ewma <= moderate) {
            return false;
        }
        final long hash = (((long) coordinate.regionX()) * 341873128712L) ^ (((long) coordinate.regionZ()) * 132897987541L);
        if (ewma > RegionThreadingConfig.entityTickThrottleSevereNanos()) {
            return Math.floorMod(gameTime + hash, 3L) != 0L;
        }
        return ((gameTime ^ hash) & 1L) != 0L;
    }

    public boolean isHotEntityRegion(RegionCoordinate coordinate) {
        final RegionClock clock = this.clocks.get(coordinate);
        return clock != null && clock.ewmaEntityNanos > RegionThreadingConfig.hotRegionEntityThresholdNanos();
    }

    public boolean shouldStaggerSpawn(RegionCoordinate coordinate, long gameTime) {
        final RegionClock clock = this.clocks.get(coordinate);
        if (clock != null && clock.inFlight.get()) {
            return true;
        }
        final long hash = (((long) coordinate.regionX()) * 341873128712L) ^ (((long) coordinate.regionZ()) * 132897987541L);
        if (clock != null && clock.ewmaEntityNanos > 8_000_000L) {
            return ((gameTime ^ hash) & 3L) != 0L;
        }
        return ((gameTime ^ hash) & 1L) != 0L;
    }

    private void awaitClockIdle(RegionClock clock, @Nullable ServerLevel level) {
        boolean interrupted = false;
        while (clock.inFlight.get()) {
            if (level != null) {
                this.drainCompletedCommits(level, 1_000_000L);
            }
            synchronized (clock.completionMonitor) {
                if (!clock.inFlight.get()) {
                    break;
                }
                try {
                    clock.completionMonitor.wait(5L);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseInFlight(RegionClock clock) {
        clock.inFlight.set(false);
        synchronized (clock.completionMonitor) {
            clock.completionMonitor.notifyAll();
        }
    }
}
