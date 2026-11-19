/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Worker pool reserved for native region execution.
 * <p>
 * The pool has two dispatch paths. The default path hands a task to a fixed thread pool; the persistent path
 * gives every worker its own deque instead: the submitting thread pushes a task onto the deque of an idle worker,
 * or of the worker with the least work pending when none is idle, that worker pops its own tasks from the back of
 * its deque, and a worker that runs out of its own work steals from the front of another worker's deque. The
 * owner end and the thief end are opposite, so an idle worker takes the task that has waited longest while the
 * owner keeps the task it pushed last, and a worker with nothing to do never waits for the worker that happens to
 * be busy with the slowest region of the batch. The persistent path removes the shared queue lock, the executor's
 * worker publication and most of the wake-up latency of a per-tick batch, which is what a light workload pays for
 * in full. The executor path is kept and is used whenever the persistent path is not installed, so a shutdown or
 * a configuration that cannot use it falls back to the unchanged behaviour. Larger persistent batches also
 * use {@link RegionWorkStealingDispatcher}: one runner per worker drains and steals ranges of unstarted
 * tasks, avoiding a queue node and worker-selection scan for every independent region task.
 * <p>
 * The dispatch handshake is spin-then-park. A worker spins on the sequence number of its own deque, then scans
 * the deques of the other workers once per spin period, and only then parks with a bounded park. A submission
 * advances a pool-wide work epoch and unparks one parked worker (the worker it pushed to, or another one that
 * then steals the task), and a worker reads that epoch after it published its parked flag, so an offer that
 * races the park decision is either seen by the worker that decided to park or by the scan of the submitter.
 * No wake-up is lost and an idle worker is never woken without work to take.
 * <p>
 * {@link #close()} only returns after every worker thread terminated. Work that was accepted before the close
 * is always executed (it holds region leases and batch barriers that other threads wait for), and an interrupt
 * of the closing thread only escalates to interrupting the workers without abandoning the wait. A task that
 * neither returns nor reacts to interrupts therefore blocks shutdown instead of letting the server continue
 * while a worker can still mutate level state. A submission that arrives after the close is refused and runs on
 * the submitting thread instead.
 */
final class RegionWorkerPool implements RegionWorkerExecutor, AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Spin iterations a persistent worker runs before it parks. A batch is submitted and awaited by the server
     * thread, so a worker that just finished a group of the previous batch finds the next one while spinning;
     * an idle worker parks after at most this many iterations and costs no CPU.
     */
    private static final int SPIN_ATTEMPTS = 256;
    /** Longest a parked worker sleeps before it re-checks its own and the other deques. */
    private static final long PARK_NANOS = 1_000_000L;
    /**
     * Bounded parks an idle worker runs before it parks without a timeout. An idle worker of a pool that is no
     * longer used - a replaced engine, a shut-down level - costs no CPU at all after this many checks, and a
     * submission always unparks it, so no wake-up is ever lost.
     */
    private static final int BOUNDED_PARKS = 1024;

    private final ExecutorService executor;
    @Nullable
    private final Worker[] workers;
    private final Set<Thread> workerThreads = ConcurrentHashMap.newKeySet();
    private final int threadCount;
    private final AtomicInteger nextWorker = new AtomicInteger();
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final java.util.concurrent.atomic.LongAdder completedTasks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder failedTasks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder rejectedTasks = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder queueWaitNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder executionNanos = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder timedTasks = new java.util.concurrent.atomic.LongAdder();
    private final Map<Thread, RuntimeCounters> runtimeByThread = new ConcurrentHashMap<>();
    private final ThreadLocal<RuntimeCounters> localRuntime = ThreadLocal.withInitial(() -> {
        // Fixed executors can replace a failed worker. Retain only live per-worker rows; pool lifetime
        // totals above are independent of those rows and are never reset by replacement.
        this.runtimeByThread.keySet().removeIf(thread -> !thread.isAlive());
        final RuntimeCounters counters = new RuntimeCounters();
        this.runtimeByThread.put(Thread.currentThread(), counters);
        return counters;
    });
    private volatile boolean timingEnabled = true;
    /**
     * Counts every task a submitter offered to a worker.
     * <p>
     * A worker that is about to park reads this epoch after it published its parked flag and before it re-checks
     * the deques. An offer either advanced the epoch (so the worker looks for work again instead of parking) or
     * happened before that read, in which case the submitter's scan of the parked flags observes the worker and
     * unparks it: the two threads cannot both miss each other, so a wake-up is never lost and no worker parks
     * through an offer.
     */
    private final AtomicLong workEpoch = new AtomicLong();
    /**
     * Number of submissions that observed the open pool and have not finished enqueuing yet. {@link #close()}
     * sets the closed flag and then waits for this to reach zero, so a task can never be enqueued after the
     * workers were told to stop draining.
     */
    private final AtomicInteger admissions = new AtomicInteger();
    private final AtomicLong submissions = new AtomicLong();
    private final AtomicLong submitNanos = new AtomicLong();
    private final AtomicLong wakeNanos = new AtomicLong();
    private final AtomicLong idleWakeUps = new AtomicLong();
    private final AtomicLong spinWakeUps = new AtomicLong();
    private final AtomicLong parkWakeUps = new AtomicLong();
    private final AtomicLong steals = new AtomicLong();
    private final AtomicLong dispatchBatches = new AtomicLong();
    private final AtomicLong batchedTasks = new AtomicLong();
    private final AtomicLong batchRunners = new AtomicLong();
    private final AtomicLong batchSteals = new AtomicLong();
    private final AtomicLong batchStolenTasks = new AtomicLong();
    private volatile boolean closed;

    /**
     * Creates a pool that hands its work to a fixed thread pool.
     */
    RegionWorkerPool(int threadCount) {
        this(threadCount, false);
    }

    /**
     * Creates a pool with the selected dispatch path.
     *
     * @param persistentWorkers {@code true} to dispatch through the per-worker deques of a persistent worker
     *                          pool instead of a shared executor queue
     */
    RegionWorkerPool(int threadCount, boolean persistentWorkers) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("threadCount must be > 0");
        }
        this.threadCount = threadCount;
        if (persistentWorkers) {
            this.executor = null;
            this.workers = new Worker[threadCount];
            // Every worker is created before any of them starts: a worker scans the deques of the other workers
            // as soon as it runs, so the array it reads must be complete.
            for (int index = 0; index < threadCount; ++index) {
                this.workers[index] = new Worker(index);
            }
            for (final Worker worker : this.workers) {
                worker.thread.start();
            }
        } else {
            this.executor = Executors.newFixedThreadPool(threadCount, new RegionThreadFactory(this.workerThreads));
            this.workers = null;
        }
    }

    int threadCount() {
        return this.threadCount;
    }

    /** {@return true when this pool dispatches through its persistent workers} */
    boolean isPersistent() {
        return this.workers != null;
    }

    /**
     * {@return true when {@code thread} is one of this pool's worker threads}
     * <p>
     * Lifecycle transitions and blocking region drains use this to fail fast instead of waiting for work
     * that only the calling worker could complete.
     */
    boolean isWorkerThread(Thread thread) {
        return this.workerThreads.contains(thread);
    }

    @Override
    public boolean execute(String description, Runnable task) {
        if (this.closed) {
            this.rejectedTasks.increment();
            LOGGER.warn("Rejected region worker task after shutdown: {}", description);
            return false;
        }
        if (this.workers != null) {
            return this.executePersistent(description, task);
        }
        final long start = System.nanoTime();
        this.admissions.incrementAndGet();
        try {
            if (this.closed) {
                this.rejectedTasks.increment();
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
            try {
                this.pendingTasks.incrementAndGet();
                final long queuedAt = this.timingEnabled ? start : 0L;
                this.executor.execute(() -> this.runTask(description, task, queuedAt, false));
                this.submissions.incrementAndGet();
                return true;
            } catch (RejectedExecutionException exception) {
                // close() may race with an admission that already observed the open flag; the caller runs the
                // rejected work on its own thread instead of losing it.
                this.pendingTasks.decrementAndGet();
                this.rejectedTasks.increment();
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
        } finally {
            this.admissions.decrementAndGet();
            this.submitNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * Pushes {@code task} onto the deque of the worker with the least work pending and wakes one parked worker.
     * <p>
     * The task is enqueued before the sequence number of the deque is published, so a worker that observes the new
     * sequence always finds the task; the owner pops the task from the back of its deque and every other worker
     * pops it from the front, and all of those polls are atomic, so a task is never run twice and never dropped.
     * A submission that races {@link #close()} is refused: the admission counter keeps the enqueue and the stop
     * of the drain loop ordered, so no task is enqueued after the workers were told to stop.
     */
    private boolean executePersistent(String description, Runnable task) {
        final long start = System.nanoTime();
        this.admissions.incrementAndGet();
        try {
            if (this.closed) {
                this.rejectedTasks.increment();
                LOGGER.warn("Rejected region worker task after shutdown: {}", description);
                return false;
            }
            // A task is offered to an idle worker, or to the deque with the least work pending when none is
            // idle: a saturated pool puts it behind the shortest queue instead of pinning it to a worker that may
            // be busy with a region many times slower than the task itself, and work stealing then closes
            // whatever imbalance is left between the workers while the batch runs.
            final Worker target = this.selectTargetWorker();
            // A task that is handed to a worker that runs nothing and has nothing pending measures the wake-up:
            // the submitter observed an idle worker, so the delay to the start of the body is the handshake. A
            // task that waits behind other work measures that work instead, which the barrier wait and the steal
            // counter report, so it is not counted as a wake-up. The submission time travels with the task, so the
            // wake-up latency of one task is measurable even when several tasks wait in the same deque.
            final SubmittedTask submitted = new SubmittedTask(description, task, start, target.isIdle(), target.index);
            this.pendingTasks.incrementAndGet();
            target.tasks.offerLast(submitted);
            target.published.incrementAndGet();
            // Publish the offer before the wake-up: the epoch covers a worker that is deciding to park, and the
            // parked-flag scan covers one that already is. Both are needed: either one alone can be read one
            // moment too early.
            this.workEpoch.incrementAndGet();
            this.unparkOneParked(target);
            this.submissions.incrementAndGet();
            return true;
        } finally {
            this.admissions.decrementAndGet();
            this.submitNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * {@return the worker to push the next task onto}
     * <p>
     * An idle worker - one that runs nothing and has nothing pending - is preferred, because it starts the task
     * immediately and is the only worker that needs a wake-up: that is the hand-off the pool controls. A pool
     * whose workers all run something puts the task behind the shortest deque instead, and work stealing
     * balances whatever is left while the batch runs. The rotating start spreads the choices of an idle pool
     * over the workers instead of always using the first one.
     */
    private Worker selectTargetWorker() {
        final Worker[] all = this.workers;
        final int start = Math.floorMod(this.nextWorker.getAndIncrement(), all.length);
        Worker best = all[start];
        long bestPending = best.pending();
        if (bestPending == 0L && best.isIdle()) {
            return best;
        }
        for (int offset = 1; offset < all.length; ++offset) {
            final Worker candidate = all[(start + offset) % all.length];
            final long pending = candidate.pending();
            if (pending < bestPending) {
                best = candidate;
                bestPending = pending;
            }
            if (pending == 0L && candidate.isIdle()) {
                // The first idle worker of the rotating scan; a busy worker with an empty deque (pending 0 but
                // still running its last task) is not preferred over it.
                return candidate;
            }
        }
        return best;
    }

    /**
     * Wakes the worker the task was pushed to when it is parked, or another parked worker that then steals it.
     * <p>
     * At most one worker is unparked per submission: the target starts the task itself, and a second worker is
     * only woken when the target keeps running, so a saturated batch costs the submitting thread no wake-up
     * syscall at all.
     */
    private void unparkOneParked(Worker target) {
        if (target.parked) {
            LockSupport.unpark(target.thread);
            return;
        }
        for (final Worker worker : this.workers) {
            if (worker != target && worker.parked) {
                LockSupport.unpark(worker.thread);
                return;
            }
        }
    }

    /**
     * Hints that up to {@code count} persistent workers will receive a short batch shortly
     * (e.g. after publisher admission), overlapping OS wake-up latency with server-thread setup.
     */
    void preWake(int count) {
        if (this.workers == null || this.closed || count <= 0) {
            return;
        }
        final long deadline = System.nanoTime() + 450_000L;
        int woken = 0;
        for (final Worker worker : this.workers) {
            if (woken >= count) {
                break;
            }
            worker.preWakeDeadlineNanos = deadline;
            if (worker.parked) {
                LockSupport.unpark(worker.thread);
            }
            ++woken;
        }
    }

    /** {@return true when any worker still has a task pushed onto its deque that nobody took} */
    private boolean anyPending() {
        for (final Worker worker : this.workers) {
            if (worker.pending() != 0L) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs one accepted task on the calling worker thread with the native worker scope installed.
     */
    private void runTask(String description, Runnable task, long queuedAt, boolean swallowFailure) {
        final Thread current = Thread.currentThread();
        final RuntimeCounters counters;
        if (current instanceof RegionWorkerThread wt) {
            RuntimeCounters cached = (RuntimeCounters) wt.poolRuntime;
            if (cached == null) {
                cached = this.localRuntime.get();
                wt.poolRuntime = cached;
            }
            counters = cached;
        } else {
            counters = this.localRuntime.get();
        }
        final long start = queuedAt == 0L ? 0L : System.nanoTime();
        this.pendingTasks.decrementAndGet();
        counters.busy = true;
        final long wait = start == 0L ? 0L : Math.max(0L, start - queuedAt);
        RegionWorkerScope.enter();
        try {
            task.run();
        } catch (Throwable throwable) {
            ++counters.failed;
            this.failedTasks.increment();
            LOGGER.error("Region worker task failed: {}", description, throwable);
            if (!swallowFailure) throw throwable;
        } finally {
            RegionWorkerScope.exit();
            if (start != 0L) {
                final long duration = System.nanoTime() - start;
                counters.executionNanos += duration;
                counters.queueWaitNanos += wait;
                this.queueWaitNanos.add(wait);
                this.executionNanos.add(duration);
                this.timedTasks.increment();
            }
            ++counters.completed;
            this.completedTasks.increment();
            counters.busy = false;
        }
    }

    void setRuntimeTimingEnabled(boolean enabled) {
        this.timingEnabled = enabled;
    }

    WorkerRuntimeSnapshot runtimeSnapshot() {
        final var bean = ManagementFactory.getThreadMXBean();
        final var rows = new ArrayList<WorkerRuntimeSnapshot.Worker>();
        int busy = 0;
        boolean joined = this.closed;
        int started = 0;
        for (final Thread thread : this.workerThreads) {
            final RuntimeCounters counters = this.runtimeByThread.get(thread);
            final boolean alive = thread.isAlive();
            if (alive) ++started;
            joined &= !alive;
            if (counters != null && counters.busy) ++busy;
            if (!alive) continue;
            rows.add(new WorkerRuntimeSnapshot.Worker(thread.getName(), thread.threadId(), true,
                    thread.getState().name(), counters != null && counters.busy,
                    counters == null ? 0 : counters.completed, counters == null ? 0 : counters.failed,
                    counters == null ? 0 : counters.queueWaitNanos, counters == null ? 0 : counters.executionNanos,
                    bean.isThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getThreadCpuTime(thread.threadId()) : -1L));
        }
        rows.sort(Comparator.comparingLong(WorkerRuntimeSnapshot.Worker::id));
        return new WorkerRuntimeSnapshot(this.threadCount, started, busy, this.submissions.get(),
                this.completedTasks.sum(), this.rejectedTasks.sum(), this.failedTasks.sum(),
                Math.max(0, this.pendingTasks.get()), this.queueWaitNanos.sum(), this.executionNanos.sum(), this.timedTasks.sum(),
                this.closed, joined, rows);
    }

    private static final class RuntimeCounters {
        volatile boolean busy;
        volatile long completed;
        volatile long failed;
        volatile long queueWaitNanos;
        volatile long executionNanos;
    }

    void recordBatchDispatch(int tasks, int runners) {
        this.dispatchBatches.incrementAndGet();
        this.batchedTasks.addAndGet(tasks);
        this.batchRunners.addAndGet(runners);
    }

    void recordBatchSteal(int tasks) {
        this.batchSteals.incrementAndGet();
        this.batchStolenTasks.addAndGet(tasks);
    }

    /**
     * {@return a snapshot of the dispatch counters of this pool}
     * <p>
     * {@code submitNanos} is the wall time the submitting threads spent handing tasks to the pool.
     * {@code wakeNanos} is the wall time between that hand-off and the start of the task body, counted only for
     * the tasks a submitter handed to an idle worker and that worker then ran itself - that is the wake-up the pool
     * controls - and {@code idleWakeUps} counts them. A task an idle worker was handed and another one stole is not
     * a wake-up: it waited for the thief's own task first, which is what {@code steals} reports. A task that waited behind a running task of the same worker measures
     * that task's execution instead, which the batch barrier wait reports. {@code spinWakeUps} and
     * {@code parkWakeUps} split the task starts into the ones a spinning worker took and the ones a parked worker
     * took. {@code steals} counts the tasks a worker took from the deque of another worker, so the dynamic
     * balancing of the persistent path is observable without timing it.
     */
    DispatchSnapshot dispatchSnapshot() {
        return new DispatchSnapshot(this.submissions.get(), this.submitNanos.get(), this.wakeNanos.get(),
                this.idleWakeUps.get(), this.spinWakeUps.get(), this.parkWakeUps.get(), this.steals.get(),
                this.dispatchBatches.get(), this.batchedTasks.get(), this.batchRunners.get(), this.batchSteals.get(), this.batchStolenTasks.get());
    }

    /** {@return true when this pool dispatches through per-worker deques} */
    boolean persistentWorkers() {
        return this.workers != null;
    }

    @Override
    public void close() {
        if (this.workerThreads.contains(Thread.currentThread())) {
            throw new IllegalStateException("Cannot close the region worker pool from a region worker thread");
        }
        // Serialize external close callers, but check worker origin before acquiring the monitor: a worker
        // must fail fast even while another thread holds the monitor and waits for that worker to finish.
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
            // No accepted submission may finish enqueuing after the workers stop draining.
            while (this.admissions.get() != 0) Thread.onSpinWait();
            if (this.workers != null) this.closePersistent();
            else this.closeExecutor();
        }
    }

    private void closePersistent() {
        for (final Worker worker : this.workers) {
            worker.stopping = true;
            LockSupport.unpark(worker.thread);
        }
        boolean interrupted = false;
        for (final Worker worker : this.workers) {
            while (true) {
                try {
                    worker.thread.join();
                    break;
                } catch (InterruptedException exception) {
                    // The pool cannot be abandoned: a worker may still be mutating region state. Ask the
                    // running tasks to stop and keep waiting for the workers to finish.
                    interrupted = true;
                    worker.thread.interrupt();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeExecutor() {
        this.executor.shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (this.executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException exception) {
                // Interrupt running work without discarding accepted queue entries: shutdownNow would
                // remove tasks whose batch permits and region leases still have to be released.
                interrupted = true;
                for (final Thread worker : this.workerThreads) worker.interrupt();
            }
        }
        // Executor termination is published by the last worker just before its Thread actually returns.
        // Join the original platform threads as well: a diagnostic or lifecycle consumer must not observe
        // a supposedly joined pool whose final worker is still unwinding.
        for (final Thread worker : this.workerThreads) {
            while (true) {
                try {
                    worker.join();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                    worker.interrupt();
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One task pushed onto a worker deque, with the time, the description and the worker of the hand-off.
     *
     * @param idleSubmission whether the target worker was idle when the task was pushed
     * @param target         the index of the worker the task was pushed to
     */
    private record SubmittedTask(String description, Runnable work, long submittedNanos, boolean idleSubmission, int target) {}

    /**
     * Dispatch counters of one pool.
     */
    record DispatchSnapshot(long submissions, long submitNanos, long wakeNanos, long idleWakeUps, long spinWakeUps, long parkWakeUps, long steals,
            long dispatchBatches, long batchedTasks, long batchRunners, long batchSteals, long batchStolenTasks) {}

    /**
     * One persistent worker: its thread, the deque it owns and the sequence number that publishes a task.
     * <p>
     * The deque has two consumer ends. The owner polls the back for its own work and every other worker polls the
     * front, so the tasks that have waited longest are the ones that get stolen. {@link #published} counts the
     * tasks pushed onto the deque, so the worker can spin on a plain counter instead of polling the deque and the
     * thieves can tell an empty deque from a loaded one with two reads: it polls only when the count it has not
     * taken yet is non-zero, and the count is published after the enqueue, so a non-zero count always means a
     * task is waiting.
     */
    private final class Worker {
        private final int index;
        private final ConcurrentLinkedDeque<SubmittedTask> tasks = new ConcurrentLinkedDeque<>();
        private final AtomicLong published = new AtomicLong();
        /** Tasks taken from this deque, by the owner and by thieves, so {@code published - taken} is its load. */
        private final AtomicLong taken = new AtomicLong();
        private final Thread thread;
        private volatile boolean stopping;
        /** Whether this worker is running a task. */
        private volatile boolean busy;
        /** Whether this worker is parked; a submission that cannot hand its task to a running worker wakes one. */
        private volatile boolean parked;
        /** Optional short spin deadline set by {@link #preWake(int)} to overlap OS wake-up with publisher prep. */
        private volatile long preWakeDeadlineNanos;
        /** Rotating start of the theft scan, so a worker does not always steal from the same neighbour. */
        private int stealCursor;

        private Worker(int index) {
            this.index = index;
            this.thread = new RegionWorkerThread(this::run, "NeoForge Region Worker #" + (index + 1));
            this.thread.setDaemon(true);
            RegionWorkerPool.this.workerThreads.add(this.thread);
        }

        /** {@return true when this worker runs nothing and has no task pushed onto its deque} */
        private boolean isIdle() {
            return !this.busy && this.pending() == 0L;
        }

        /** {@return the number of tasks pushed onto this deque that no worker took yet} */
        private long pending() {
            final long pending = this.published.get() - this.taken.get();
            // A thief takes a task before the submitter published its sequence number, which can make the
            // difference negative for a moment; that deque is empty for a reader of the counters.
            return pending < 0L ? 0L : pending;
        }

        private void run() {
            int spins = 0;
            int idleParks = 0;
            while (true) {
                SubmittedTask task = this.takeOwn();
                if (task == null && (spins == 0 || spins >= SPIN_ATTEMPTS)) {
                    // The task a worker just finished is the moment the pool is most likely to be imbalanced:
                    // scan the other deques before spinning, and once per spin period afterwards.
                    task = this.steal();
                }
                if (task != null) {
                    this.preWakeDeadlineNanos = 0L;
                    if (spins < SPIN_ATTEMPTS) {
                        RegionWorkerPool.this.spinWakeUps.incrementAndGet();
                    } else {
                        RegionWorkerPool.this.parkWakeUps.incrementAndGet();
                    }
                    if (task.idleSubmission() && task.target() == this.index) {
                        // Only the worker the task was pushed to measures the handshake. A task a thief takes
                        // waited for the thief's own work first, which is not a wake-up.
                        RegionWorkerPool.this.idleWakeUps.incrementAndGet();
                        RegionWorkerPool.this.wakeNanos.addAndGet(System.nanoTime() - task.submittedNanos());
                    }
                    spins = 0;
                    idleParks = 0;
                    this.runTask(task);
                    continue;
                }
                if (this.stopping) {
                    // The close waits for this thread; a task pushed before the stop is still drained, because
                    // the admission counter is only final after every admission finished enqueuing. No worker
                    // may leave while a deque still holds a task, because the barrier waits for it.
                    if (!RegionWorkerPool.this.anyPending()) {
                        return;
                    }
                    // Drain what is left without spinning; another worker may take it instead.
                    spins = SPIN_ATTEMPTS;
                    continue;
                }
                ++spins;
                if (spins < SPIN_ATTEMPTS) {
                    Thread.onSpinWait();
                    continue;
                }
                final long wakeDeadline = this.preWakeDeadlineNanos;
                if (wakeDeadline != 0L) {
                    if (System.nanoTime() < wakeDeadline) {
                        Thread.onSpinWait();
                        continue;
                    }
                    this.preWakeDeadlineNanos = 0L;
                }
                // Publish the parked state and then re-check every source before parking: the epoch read after
                // the publication catches a submission that is being pushed right now, and the parked scan of
                // that submission catches this worker if the read missed it. A lost wake-up is impossible.
                this.parked = true;
                try {
                    final long epoch = RegionWorkerPool.this.workEpoch.get();
                    if (RegionWorkerPool.this.anyPending() || RegionWorkerPool.this.workEpoch.get() != epoch) {
                        continue;
                    }
                    if (idleParks < BOUNDED_PARKS) {
                        ++idleParks;
                        LockSupport.parkNanos(this, PARK_NANOS);
                    } else {
                        LockSupport.park(this);
                    }
                    Thread.interrupted();
                } finally {
                    this.parked = false;
                }
            }
        }

        /**
         * {@return the newest task of this worker's own deque, or {@code null} when it has none}
         * <p>
         * The owner pops the task it pushed last, so the tasks that stay behind are the ones the thieves take
         * from the front: the two ends never fight over the same task.
         */
        @Nullable
        private SubmittedTask takeOwn() {
            if (this.published.get() == this.taken.get()) {
                return null;
            }
            final SubmittedTask task = this.tasks.pollLast();
            if (task != null) {
                this.taken.incrementAndGet();
            }
            return task;
        }

        /**
         * {@return the oldest task of another worker's deque, or {@code null} when every other deque is empty}
         * <p>
         * Every worker scans the same deques, so the poll is the only operation that has to be atomic: exactly
         * one thief wins a task and the losers find the next one or an empty deque.
         */
        @Nullable
        private SubmittedTask steal() {
            final Worker[] all = RegionWorkerPool.this.workers;
            if (all.length == 1) {
                return null;
            }
            final int first = 1 + Math.floorMod(this.stealCursor++, all.length - 1);
            for (int offset = 0; offset < all.length - 1; ++offset) {
                final int distance = 1 + (first - 1 + offset) % (all.length - 1);
                final Worker victim = all[(this.index + distance) % all.length];
                if (victim.pending() == 0L) {
                    continue;
                }
                final SubmittedTask task = victim.tasks.pollFirst();
                if (task != null) {
                    victim.taken.incrementAndGet();
                    RegionWorkerPool.this.steals.incrementAndGet();
                    return task;
                }
            }
            return null;
        }

        /**
         * Runs one task of any deque and keeps the worker alive when the task fails.
         */
        private void runTask(SubmittedTask task) {
            this.busy = true;
            try {
                RegionWorkerPool.this.runTask(task.description(), task.work(),
                        RegionWorkerPool.this.timingEnabled ? task.submittedNanos() : 0L, true);
            } finally {
                this.busy = false;
            }
        }
    }

    private static final class RegionThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final Set<Thread> workerThreads;

        private RegionThreadFactory(Set<Thread> workerThreads) {
            this.workerThreads = workerThreads;
        }

        @Override
        public Thread newThread(Runnable task) {
            final Thread thread = new RegionWorkerThread(task, "NeoForge Region Worker #" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            this.workerThreads.add(thread);
            return thread;
        }
    }
}
