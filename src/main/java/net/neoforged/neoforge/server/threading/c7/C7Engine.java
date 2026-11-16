/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;

/**
 * Independent detached-work scheduler. It owns no C6 execution state and creates no threads.
 * All live-world effects commit in canonical order, even if a later independent result is ready first.
 * A serial/invalid frontier discards speculative detached results and rebuilds the remaining graph.
 * A failing body/compute/commit is never retried. No wait timeout abandons an accepted runner.
 */
public final class C7Engine implements AutoCloseable {
    private final C7Metrics metrics = new C7Metrics();
    private final Set<Object> demoted = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Thread> computing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @org.jetbrains.annotations.Nullable
    private volatile Thread coordinator;
    private boolean closed;

    public C7Metrics.Snapshot metrics() {
        return this.metrics.snapshot();
    }

    public void recordCollectionNanos(long nanos) {
        this.metrics.collectionNanos.add(nanos);
    }

    public void recordRefusal(C7Refusal reason) {
        this.metrics.refusal(reason);
    }

    public void recordRefusal(String type, C7Refusal reason) {
        this.metrics.refusal(type, reason);
    }

    public void execute(List<? extends C7Task> tasks, C7Options options,
            RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        // Check BEFORE acquiring the phase monitor: a worker waiting for its own coordinator deadlocks.
        if (this.coordinator == Thread.currentThread() || this.computing.contains(Thread.currentThread()))
            throw new IllegalStateException("C7 invocation cannot recursively start a phase");
        synchronized (this) {
            this.executeLocked(tasks, options, executor, barrier);
        }
    }

    private void executeLocked(List<? extends C7Task> tasks, C7Options options,
            RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        if (this.closed) throw new IllegalStateException("C7 is closed");
        if (this.coordinator != null) throw new IllegalStateException("C7 phase reentrancy is forbidden");
        final Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final C7Task task : tasks) {
            if (!identities.add(java.util.Objects.requireNonNull(task.identity())))
                throw new IllegalArgumentException("Duplicate C7 invocation identity; no work started");
        }
        final long phaseStart = System.nanoTime();
        this.coordinator = Thread.currentThread();
        this.metrics.phases.increment();
        this.metrics.collected.add(tasks.size());
        for (final var task : tasks) this.metrics.type(task.type()).collected.increment();
        try {
            int cursor = 0;
            boolean serialTail = false;
            C7Refusal tailReason = C7Refusal.CONSERVATIVE_TAIL;
            while (cursor < tasks.size()) {
                final C7Task task = tasks.get(cursor);
                if (serialTail || tasks.size() > options.maxTasks()) {
                    this.serial(task, tasks.size() > options.maxTasks() ? C7Refusal.TASK_BUDGET : tailReason);
                    ++cursor;
                    continue;
                }
                final C7Footprint footprint = this.footprint(task, options);
                if (options.mode() == C7Options.Mode.SHADOW
                        || !footprint.complete() || this.demoted.contains(task.identity())) {
                    if (options.conservative() && (!footprint.complete() || this.demoted.contains(task.identity()))) serialTail = true;
                    // SHADOW still measures the bounded graph below, but never invokes detached work.
                    if (options.mode() == C7Options.Mode.SHADOW && tasks.size() <= options.maxTasks() && cursor == 0)
                        this.plan(tasks.stream().map(t -> this.footprint(t, options)).toList(), options);
                    if (options.mode() == C7Options.Mode.SHADOW) {
                        serialTail = true;
                        tailReason = C7Refusal.SHADOW_MODE;
                    }
                    this.serial(task, options.mode() == C7Options.Mode.SHADOW ? C7Refusal.SHADOW_MODE
                            : this.demoted.contains(task.identity()) ? C7Refusal.DEMOTED : task.serialReason());
                    ++cursor;
                    continue;
                }
                final List<C7Footprint> footprints = new ArrayList<>();
                footprints.add(footprint);
                int end = cursor + 1;
                while (end < tasks.size()) {
                    final C7Task candidate = tasks.get(end);
                    final C7Footprint proof = this.footprint(candidate, options);
                    if (!proof.complete() || this.demoted.contains(candidate.identity())) break;
                    footprints.add(proof);
                    ++end;
                }
                final C7DependencyGraph.Plan plan = this.plan(footprints, options);
                if (plan.waves().size() > options.maxWaves()) {
                    for (; cursor < end; ++cursor) this.serial(tasks.get(cursor), C7Refusal.WAVE_BUDGET);
                    continue;
                }
                cursor = this.segment(tasks, cursor, footprints, plan, options, executor, barrier);
                if (cursor < end && options.conservative()) serialTail = true;
            }
        } finally {
            this.metrics.phaseNanos.add(System.nanoTime() - phaseStart);
            this.coordinator = null;
        }
    }

    private C7Footprint footprint(C7Task task, C7Options options) {
        final long start = System.nanoTime();
        try {
            final C7Footprint proof = task.footprint();
            if (proof.resources().size() > options.maxResources() && options.trackingDiagnostics()) this.metrics.refusal(C7Refusal.RESOURCE_BUDGET);
            return bounded(proof, options);
        } finally {
            this.metrics.proofNanos.add(System.nanoTime() - start);
        }
    }

    private static C7Footprint bounded(C7Footprint footprint, C7Options options) {
        return footprint.resources().size() > options.maxResources() ? C7Footprint.unknown() : footprint;
    }

    private C7DependencyGraph.Plan plan(List<C7Footprint> footprints, C7Options options) {
        final long start = System.nanoTime();
        try {
            final var plan = C7DependencyGraph.build(footprints, options.maxWaveSize(), List.of());
            this.metrics.dependencies.add(plan.dependencies());
            this.metrics.conflicts.add(plan.conflicts());
            return plan;
        } catch (Throwable failure) {
            this.metrics.rejectedPlans.increment();
            throw failure;
        } finally {
            this.metrics.graphNanos.add(System.nanoTime() - start);
        }
    }

    private int segment(List<? extends C7Task> tasks, int base, List<C7Footprint> footprints,
            C7DependencyGraph.Plan plan, C7Options options, RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        final C7Task.Prepared[] prepared = new C7Task.Prepared[footprints.size()];
        final C7Task.Computation[] computations = new C7Task.Computation[footprints.size()];
        final Object[] results = new Object[footprints.size()];
        final byte[] ready = new byte[footprints.size()]; // 0=pending, 1=detached result, 2=serial, 3=skip
        final boolean[] onWorker = new boolean[footprints.size()];
        final C7Refusal[] reasons = new C7Refusal[footprints.size()];
        int cursor = 0;
        for (final List<Integer> wave : plan.waves()) {
            this.metrics.waves.increment();
            this.metrics.waveTasks.add(wave.size());
            this.metrics.maxWave.accumulateAndGet(wave.size(), Math::max);
            final List<Integer> compute = new ArrayList<>();
            final long prepareStart = System.nanoTime();
            String preparingType = "<internal>";
            try {
                for (final int index : wave) {
                    final C7Task task = tasks.get(base + index);
                    preparingType = task.type();
                    if (!task.current()) ready[index] = 3;
                    else {
                        prepared[index] = task.prepare();
                        if (prepared[index] == null) {
                            ready[index] = 2;
                            reasons[index] = task.serialReason();
                        } else { computations[index] = java.util.Objects.requireNonNull(prepared[index].computation()); compute.add(index); }
                    }
                }
            } catch (Throwable failure) {
                this.metrics.failed.increment();
                this.metrics.type(preparingType).failed.increment();
                throw failure;
            } finally {
                this.metrics.preparationNanos.add(System.nanoTime() - prepareStart);
            }
            if (compute.size() < 2) {
                for (final int index : compute) {
                    ready[index] = 2;
                    reasons[index] = C7Refusal.SINGLETON_WAVE;
                }
            } else {
                this.computeWave(tasks, base, footprints, computations, results, onWorker, compute, options, executor, barrier);
                for (final int index : compute) ready[index] = 1;
            }
            // Never commit a later invocation before an earlier one. This also makes a conservative
            // serial recovery safe if an unknown topology change invalidates a speculative snapshot.
            while (cursor < ready.length && ready[cursor] != 0) {
                final C7Task task = tasks.get(base + cursor);
                if (ready[cursor] == 3 || !this.current(task)) {
                    this.metrics.skipped.increment();
                    this.metrics.type(task.type()).skipped.increment();
                } else if (ready[cursor] == 2 || !this.valid(task, prepared[cursor])) {
                    if (ready[cursor] == 1) this.metrics.invalidated.increment();
                    this.serial(task, ready[cursor] == 1 ? C7Refusal.SNAPSHOT_INVALIDATED : reasons[cursor]);
                    // No effects of the remaining detached results have committed. Discard them and
                    // recapture at the new canonical cursor; never replay a live body or failed commit.
                    return base + cursor + 1;
                } else {
                    final long commitStart = System.nanoTime();
                    try {
                        prepared[cursor].commit(results[cursor]);
                        if (onWorker[cursor]) {
                            this.metrics.parallel.increment();
                            this.metrics.type(task.type()).parallel.increment();
                        } else {
                            this.metrics.serial.increment();
                            this.metrics.type(task.type()).serial.increment();
                            this.metrics.type(task.type()).serialReasons.get(C7Refusal.INLINE_EXECUTOR_REJECTION).increment();
                        }
                    } catch (Throwable failure) {
                        this.metrics.failed.increment();
                        this.metrics.type(task.type()).failed.increment();
                        this.demote(task.identity());
                        throw failure;
                    } finally {
                        this.metrics.commitNanos.add(System.nanoTime() - commitStart);
                    }
                }
                ++cursor;
            }
        }
        if (cursor != ready.length) throw new IllegalStateException("C7 graph left unresolved invocations");
        return base + cursor;
    }

    private record DetachedJob(int index, Object identity, String type, C7Footprint footprint, C7Task.Computation computation) {}

    private void computeWave(List<? extends C7Task> tasks, int base, List<C7Footprint> footprints,
            C7Task.Computation[] computations, Object[] results, boolean[] onWorker, List<Integer> indices,
            C7Options options, RegionWorkerExecutor executor, RegionTickBarrier barrier) {
        final List<DetachedJob> jobs = indices.stream().map(index -> new DetachedJob(index, tasks.get(base + index).identity(),
                tasks.get(base + index).type(), footprints.get(index), computations[index])).toList();
        final int lanes = Math.min(options.workers(), jobs.size());
        final AtomicInteger next = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch joined = new CountDownLatch(lanes);
        final Thread submitting = Thread.currentThread();
        final long executionStart = System.nanoTime();
        final long submitStart = executionStart;
        for (int lane = 0; lane < lanes; ++lane) {
            final AtomicBoolean claimed = new AtomicBoolean();
            final Runnable runner = () -> {
                // Even a misbehaving executor that accepts then throws/returns false cannot run twice.
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    int slot;
                    while (failure.get() == null && (slot = next.getAndIncrement()) < jobs.size()) {
                        final DetachedJob job = jobs.get(slot);
                        final int index = job.index();
                        final Object identity = job.identity();
                        final boolean worker = Thread.currentThread() != submitting;
                        final long start = System.nanoTime();
                        if (worker) {
                            this.computing.add(Thread.currentThread());
                            this.metrics.maxConcurrent.accumulateAndGet(active.incrementAndGet(), Math::max);
                        }
                        try (final var access = new C7AccessScope(job.footprint(), resource -> {
                            this.metrics.dynamic.increment();
                            if (options.trackingDiagnostics()) this.metrics.undeclared(resource);
                            this.demote(identity);
                        })) {
                            results[index] = job.computation().compute(access);
                            onWorker[index] = worker;
                        } catch (Throwable problem) {
                            this.metrics.failed.increment();
                            this.metrics.type(job.type()).failed.increment();
                            this.demote(identity);
                            failure.compareAndSet(null, problem);
                        } finally {
                            final long elapsed = System.nanoTime() - start;
                            if (elapsed > options.stragglerNanos()) this.demote(identity);
                            if (worker) {
                                this.metrics.workerNanos.add(elapsed);
                                active.decrementAndGet();
                                this.computing.remove(Thread.currentThread());
                            }
                        }
                    }
                } finally {
                    joined.countDown();
                }
            };
            boolean accepted;
            try {
                accepted = executor.execute("C7 detached BE wave", runner);
            } catch (Throwable rejected) {
                accepted = false;
            }
            if (!accepted) {
                this.metrics.rejected.increment();
                runner.run();
            }
        }
        this.metrics.schedulingNanos.add(System.nanoTime() - submitStart);
        try {
            barrier.await(() -> joined.getCount() == 0);
        } catch (Throwable waitFailure) {
            failure.compareAndSet(null, waitFailure);
        } finally {
            // Barrier failure/interruption is not permission to release ownership under running work.
            boolean interrupted = false;
            while (joined.getCount() != 0) {
                try {
                    joined.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            final long elapsed = System.nanoTime() - executionStart;
            this.metrics.executionNanos.add(elapsed);
            this.metrics.workerCapacityNanos.add(elapsed * lanes);
        }
        final Throwable problem = failure.get();
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
        if (problem != null) throw new IllegalStateException("C7 wave failed; joined and not replayed", problem);
    }

    private boolean current(C7Task task) {
        try {
            return task.current();
        } catch (Throwable failure) {
            this.metrics.failed.increment();
            this.metrics.type(task.type()).failed.increment();
            throw failure;
        }
    }

    private boolean valid(C7Task task, C7Task.Prepared prepared) {
        try {
            return prepared.valid();
        } catch (Throwable failure) {
            this.metrics.failed.increment();
            this.metrics.type(task.type()).failed.increment();
            this.demote(task.identity());
            throw failure;
        }
    }

    private void serial(C7Task task, C7Refusal reason) {
        if (!this.current(task)) {
            this.metrics.skipped.increment();
            this.metrics.type(task.type()).skipped.increment();
            return;
        }
        final long start = System.nanoTime();
        try {
            task.serial();
            this.metrics.serial.increment();
            this.metrics.type(task.type()).serial.increment();
            this.metrics.type(task.type()).serialReasons.get(reason).increment();
        } catch (Throwable failure) {
            this.metrics.failed.increment();
            this.metrics.type(task.type()).failed.increment();
            throw failure;
        } finally {
            this.metrics.executionNanos.add(System.nanoTime() - start);
        }
    }

    private void demote(Object identity) {
        if (this.demoted.add(identity)) this.metrics.demotions.increment();
    }

    @Override
    public void close() {
        if (this.coordinator == Thread.currentThread() || this.computing.contains(Thread.currentThread()))
            throw new IllegalStateException("Cannot close C7 from its own active invocation");
        synchronized (this) {
            this.closed = true;
            this.demoted.clear();
        }
    }
}
