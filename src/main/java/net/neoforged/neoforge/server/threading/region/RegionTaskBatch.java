/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One batch of region-scoped work that is submitted to the worker pool and awaited by the submitting
 * thread before it continues.
 * <p>
 * A group is the unit of region exclusivity: the caller adds one group per region, so the pool can run
 * different regions in parallel while a region can never run against itself. {@link #submit} returns a
 * {@link Barrier} that completes only after every submitted group finished or failed, which gives the
 * server thread a single point to wait on before it touches state the workers may be mutating.
 * <p>
 * A group the executor rejects or fails to accept runs on the submitting thread instead of being dropped,
 * so a closing pool can never strand the barrier. The first failure of any group is recorded and rethrown
 * by the submitting thread through {@link Barrier#throwIfFailed()} once the whole batch is complete; the
 * remaining failures are reported by the worker pool itself.
 * <p>
 * {@link #addPacked(String, List)} adds several groups as one task. The groups keep their own barrier
 * permit, their own failure record and their own release of the state they acquired - packing only shares
 * the submission and the completion of a task between them - so a packed group behaves exactly like a
 * submitted one, and a failing group neither stops the groups packed with it nor completes their permits.
 * <p>
 * With persistent workers, a batch larger than the pool is published as stealable ranges and submits at
 * most one runner per worker. Runners steal unstarted tasks, never the bodies or individual groups inside
 * an explicit pack. The executor path and custom executors keep individual task submission.
 */
@ApiStatus.Internal
public final class RegionTaskBatch {
    private static final Logger LOGGER = LogManager.getLogger();

    private final List<Group> groups = new ArrayList<>();

    /**
     * Adds one group of region work.
     *
     * @param description human readable description used by worker diagnostics
     * @param work        the work of a single region; it must release any state it acquired even when it fails
     */
    public void add(String description, Runnable work) {
        this.addPacked(description, List.of(work));
    }

    /**
     * Adds one task that runs several groups of the same slice on one worker.
     * <p>
     * Every group of the task keeps its own barrier permit, so the barrier still completes only after every
     * group of the batch finished, and the first failure still fails the batch. A failing group does not run
     * the groups after it twice and does not stop them: the task runs the remaining groups exactly once.
     */
    public void addPacked(String description, List<Runnable> groupWorks) {
        if (groupWorks.isEmpty()) {
            throw new IllegalArgumentException("A packed task must hold at least one group");
        }
        this.groups.add(new Group(description, List.copyOf(groupWorks), null, 0L));
    }

    void add(RegionCoordinate coordinate, String description, long estimatedNanos, Runnable work) {
        this.groups.add(new Group(description, List.of(work), coordinate, estimatedNanos));
    }

    /** Coalesces light tasks while keeping spare work available and bounding the cost of each pack. */
    void groupByLoadedIsland(DynamicRegionTopology topology, int workerCount) {
        if (workerCount <= 0) return;
        int mergeBudget = this.groups.size() - workerCount;
        if (mergeBudget <= 0) return;
        long knownTotal = 0L;
        long largest = 0L;
        int knownCount = 0;
        for (final Group group : this.groups) {
            if (group.estimatedNanos() > 0L) {
                knownTotal += group.estimatedNanos();
                largest = Math.max(largest, group.estimatedNanos());
                ++knownCount;
            }
        }
        final long coldEstimate = knownCount == 0 ? 1L : knownTotal / knownCount;
        final long total = knownTotal + (this.groups.size() - knownCount) * coldEstimate;
        final long packLimit = Math.max(largest, Math.ceilDiv(total, workerCount));
        final List<Group> grouped = new ArrayList<>(this.groups.size());
        final Int2IntOpenHashMap islandSlots = new Int2IntOpenHashMap();
        islandSlots.defaultReturnValue(-1);
        for (final Group group : this.groups) {
            final RegionCoordinate cell = group.coordinate();
            final int island = cell == null ? DynamicRegionTopology.NO_REGION : topology.regionIdAtSection(cell.regionX(), cell.regionZ());
            if (island == DynamicRegionTopology.NO_REGION || mergeBudget == 0) {
                grouped.add(group);
                continue;
            }
            final int slot = islandSlots.putIfAbsent(island, grouped.size());
            if (slot == -1) {
                grouped.add(group);
                continue;
            }
            final Group pack = grouped.get(slot);
            final long packCost = pack.estimatedNanos() == 0L ? coldEstimate : pack.estimatedNanos();
            final long cost = group.estimatedNanos() == 0L ? coldEstimate : group.estimatedNanos();
            if (packCost + cost > packLimit) {
                if (cost < packCost) {
                    islandSlots.put(island, grouped.size());
                }
                grouped.add(group);
                continue;
            }
            final List<Runnable> works = pack.works() instanceof ArrayList<Runnable> mutable ? mutable : new ArrayList<>(pack.works());
            works.addAll(group.works());
            grouped.set(slot, new Group("loaded island " + island, works, null, packCost + cost));
            --mergeBudget;
        }
        topology.recordCoalescedSubmissions(this.groups.size() - grouped.size());
        this.groups.clear();
        this.groups.addAll(grouped);
    }

    public boolean isEmpty() {
        return this.groups.isEmpty();
    }

    /** {@return the number of groups this batch waits for} */
    public int groupCount() {
        int groups = 0;
        for (final Group group : this.groups) {
            groups += group.works().size();
        }
        return groups;
    }

    /** {@return the number of tasks this batch submits; a packed task carries several groups} */
    public int taskCount() {
        return this.groups.size();
    }

    /**
     * Submits every group to {@code executor} and returns the barrier of this batch.
     * <p>
     * Groups the executor rejects are run on the calling thread before this method returns.
     */
    public Barrier submit(RegionWorkerExecutor executor) {
        final Barrier barrier = this.newBarrier();
        this.submit(executor, barrier);
        return barrier;
    }

    /**
     * {@return a barrier that completes when every group added so far finished}
     * <p>
     * Lets the caller publish the batch before it is submitted, so a concurrent shutdown cannot miss a
     * batch that already holds region leases. Groups must not be added after this call.
     */
    public Barrier newBarrier() {
        return new Barrier(this.groupCount());
    }

    /**
     * Submits every group to {@code executor} against {@code barrier}, which must come from
     * {@link #newBarrier()} of this batch.
     */
    public void submit(RegionWorkerExecutor executor, Barrier barrier) {
        if (barrier.groupCount() != this.groupCount()) {
            throw new IllegalStateException("Batch barrier was created for " + barrier.groupCount() + " groups but " + this.groupCount() + " were added");
        }
        this.submitGroups(executor, barrier);
    }

    /**
     * Submits every group against an open barrier from {@link Barrier#open()}.
     * <p>
     * The barrier keeps an owner permit until {@link Barrier#setupComplete()}, so a batch can publish
     * itself before it knows how many groups it will hold. Groups are registered one by one right before
     * they are submitted, so a group that finishes immediately can never complete the barrier before the
     * setup finished. All permits are registered before any runner can claim a task, including a task whose
     * original runner has not been submitted yet.
     */
    public void submitOpen(RegionWorkerExecutor executor, Barrier barrier) {
        this.submitOpenWithDispatcher(executor, barrier);
    }

    @org.jetbrains.annotations.Nullable
    RegionWorkStealingDispatcher submitOpenWithDispatcher(RegionWorkerExecutor executor, Barrier barrier) {
        if (this.groups.isEmpty()) return null;
        barrier.registerGroups(this.groupCount());
        return this.submitGroups(executor, barrier);
    }

    @org.jetbrains.annotations.Nullable
    private RegionWorkStealingDispatcher submitGroups(RegionWorkerExecutor executor, Barrier barrier) {
        if (executor instanceof RegionWorkerPool pool && pool.isPersistent() && this.groups.size() > pool.threadCount()) {
            this.orderByEstimatedCost(pool.threadCount());
            final var dispatcher = new RegionWorkStealingDispatcher(this.groups.size(), pool.threadCount(),
                    index -> runDispatchedGroup(barrier, this.groups.get(index)), pool::recordBatchSteal);
            pool.recordBatchDispatch(this.groups.size(), dispatcher.runnerCount());
            for (int index = 0; index < dispatcher.runnerCount(); ++index) {
                final int runner = index;
                submitTask(executor, barrier, "region batch runner " + runner, () -> dispatcher.run(runner));
            }
            return dispatcher;
        }
        if (executor instanceof RegionWorkerPool) {
            this.orderByEstimatedCost(1);
        }
        for (final Group group : this.groups) {
            submitTask(executor, barrier, group.description(), () -> runGroup(barrier, group));
        }
        return null;
    }

    /**
     * Orders groups longest-processing-time first when historical cost estimates are available.
     * <p>
     * For the range-based work-stealing dispatcher, groups are snake-distributed across runner ranges so every
     * runner starts on one of the heaviest tasks at its range front while lighter tasks sit at the stealable tail.
     */
    private void orderByEstimatedCost(int runners) {
        final int size = this.groups.size();
        if (size <= 1) {
            return;
        }
        boolean hasEstimate = false;
        for (int i = 0; i < size; ++i) {
            if (this.groups.get(i).estimatedNanos() > 0L) {
                hasEstimate = true;
                break;
            }
        }
        if (!hasEstimate) {
            return;
        }
        this.groups.sort((a, b) -> Long.compare(b.estimatedNanos(), a.estimatedNanos()));
        if (runners <= 1 || runners >= size) {
            return;
        }
        final Group[] arranged = new Group[size];
        final int[] cursors = new int[runners];
        final int[] limits = new int[runners];
        for (int r = 0; r < runners; ++r) {
            cursors[r] = (int) ((long) size * r / runners);
            limits[r] = (int) ((long) size * (r + 1) / runners);
        }
        int placed = 0;
        boolean forward = true;
        while (placed < size) {
            if (forward) {
                for (int r = 0; r < runners && placed < size; ++r) {
                    if (cursors[r] < limits[r]) {
                        arranged[cursors[r]++] = this.groups.get(placed++);
                    }
                }
            } else {
                for (int r = runners - 1; r >= 0 && placed < size; --r) {
                    if (cursors[r] < limits[r]) {
                        arranged[cursors[r]++] = this.groups.get(placed++);
                    }
                }
            }
            forward = !forward;
        }
        for (int i = 0; i < size; ++i) {
            this.groups.set(i, arranged[i]);
        }
    }

    private static void runDispatchedGroup(Barrier barrier, Group group) {
        try {
            runGroup(barrier, group);
        } catch (Throwable throwable) {
            // A runner drains multiple tasks and only rethrows its first failure. Keep every failing task's
            // description and failure visible rather than hiding them behind the runner's description.
            LOGGER.error("Region worker task failed: {}", group.description(), throwable);
            throwUnchecked(throwable);
        }
    }

    private static void runGroup(Barrier barrier, Group group) {
        Throwable failure = null;
        for (final Runnable work : group.works()) {
            try {
                work.run();
            } catch (Throwable throwable) {
                barrier.recordFailure(throwable);
                if (failure == null) failure = throwable;
            } finally {
                barrier.groupFinished();
            }
        }
        if (failure != null) {
            // All groups of this task released their permits. The dispatcher continues its remaining tasks
            // before surfacing the failure, just as an ordinary packed task continues its remaining groups.
            throwUnchecked(failure);
        }
    }

    private static void submitTask(RegionWorkerExecutor executor, Barrier barrier, String description, Runnable task) {
        if (!trySubmit(executor, description, task)) {
            barrier.recordRejected();
            try {
                task.run();
            } catch (Throwable failure) {
                // The task recorded its failure and released its permits. Finish submitting the other
                // leased groups before surfacing it through Barrier.throwIfFailed; stopping here strands
                // unsubmitted groups and can prevent an open barrier's setup permit from being released.
                barrier.recordFailure(failure);
            }
        }
    }

    private static void throwUnchecked(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region worker group failed", throwable);
    }

    /**
     * {@return true when {@code executor} accepted {@code task}}
     * <p>
     * An executor that throws instead of returning {@code false} still has to run the task on the
     * submitting thread: the work holds region leases and the barrier only completes when it ran.
     */
    private static boolean trySubmit(RegionWorkerExecutor executor, String description, Runnable task) {
        try {
            return executor.execute(description, task);
        } catch (Throwable throwable) {
            LOGGER.warn("Region worker executor failed to accept {}; running it on the submitting thread", description, throwable);
            return false;
        }
    }

    private record Group(String description, List<Runnable> works, @Nullable RegionCoordinate coordinate, long estimatedNanos) {}

    /**
     * Completion barrier of one submitted batch.
     */
    public static final class Barrier {
        private final AtomicInteger pendingGroups;
        private final CountDownLatch completed;
        private final int groupCount;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger rejectedGroups = new AtomicInteger();
        private volatile @Nullable Thread waiter;
        private boolean open;

        private Barrier(int groupCount) {
            this(groupCount, false);
        }

        private Barrier(int groupCount, boolean open) {
            if (groupCount < 0) {
                throw new IllegalArgumentException("groupCount must be >= 0");
            }
            this.pendingGroups = new AtomicInteger(groupCount);
            this.completed = new CountDownLatch(groupCount == 0 ? 0 : 1);
            this.groupCount = groupCount;
            this.open = open;
        }

        /**
         * {@return a barrier that starts with one owner permit and accepts groups through
         * {@link #registerGroup()} until {@link #setupComplete()} is called}
         * <p>
         * This lets a batch publish its completion latch before it acquires any region lease: the owner
         * permit keeps the barrier incomplete while the batch is still being set up, and each registered
         * group adds a permit of its own.
         */
        public static Barrier open() {
            return new Barrier(1, true);
        }

        /**
         * {@return the number of groups this barrier waits for}
         */
        public int groupCount() {
            return this.groupCount;
        }

        /**
         * {@return true when every group of the batch finished}
         */
        public boolean isComplete() {
            return this.pendingGroups.get() == 0;
        }

        /**
         * Waits until every group of the batch finished, preserving the interrupt status of the caller.
         */
        public void awaitCompletion() {
            if (this.pendingGroups.get() == 0) {
                return;
            }
            // Short batches (such as player waves) finish in ~0.2-0.9 ms; spinning briefly avoids
            // a ~40 us OS futex sleep/wake round-trip while still parking on any longer stall.
            final long spinDeadline = System.nanoTime() + 1_500_000L;
            do {
                for (int spin = 0; spin < 256; ++spin) {
                    if (this.pendingGroups.get() == 0) {
                        return;
                    }
                    Thread.onSpinWait();
                }
            } while (System.nanoTime() < spinDeadline);
            boolean interrupted = false;
            while (true) {
                try {
                    this.completed.await();
                    break;
                } catch (InterruptedException exception) {
                    // The batch cannot be abandoned: a worker may still be mutating region state.
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Registers one more group against an open barrier.
         */
        void registerGroup() {
            this.registerGroups(1);
        }

        /**
         * Registers {@code groups} more groups against an open barrier; one packed task registers all of its
         * groups before it is submitted, so it can never complete the barrier before its setup finished.
         */
        void registerGroups(int groups) {
            if (groups <= 0) {
                throw new IllegalArgumentException("groups must be > 0");
            }
            synchronized (this) {
                if (!this.open) {
                    throw new IllegalStateException("Barrier setup is already complete");
                }
                if (this.pendingGroups.get() <= 0) {
                    throw new IllegalStateException("Barrier is already complete");
                }
                this.pendingGroups.addAndGet(groups);
            }
        }

        /**
         * Releases the owner permit of an open barrier; no group may be registered afterwards.
         */
        void setupComplete() {
            synchronized (this) {
                if (!this.open) {
                    throw new IllegalStateException("Barrier setup is already complete");
                }
                this.open = false;
            }
            this.groupFinished();
        }

        /**
         * {@return the first failure of the batch, or {@code null} when no group failed}
         */
        @Nullable
        public Throwable failure() {
            return this.failure.get();
        }

        /**
         * {@return the number of rejected executor submissions run inline, including batch runners}
         * <p>
         * One rejected runner may drain several unstarted tasks. This is a submission count, not a count of
         * logical groups or bodies executed on the submitting thread.
         */
        public int rejectedCount() {
            return this.rejectedGroups.get();
        }

        /**
         * Rethrows the failure of the batch so the submitting thread reports it like any other tick failure.
         */
        public void throwIfFailed() {
            final Throwable throwable = this.failure.get();
            if (throwable == null) {
                return;
            }
            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (throwable instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Region worker task failed", throwable);
        }

        private void recordFailure(Throwable throwable) {
            this.failure.compareAndSet(null, throwable);
        }

        private void recordRejected() {
            this.rejectedGroups.incrementAndGet();
        }

        void setWaiter(@Nullable Thread waiter) {
            this.waiter = waiter;
        }

        void wakeWaiter() {
            final Thread waiting = this.waiter;
            if (waiting != null) {
                java.util.concurrent.locks.LockSupport.unpark(waiting);
            }
        }

        private void groupFinished() {
            if (this.pendingGroups.decrementAndGet() == 0) {
                this.completed.countDown();
                this.wakeWaiter();
            }
        }
    }
}
