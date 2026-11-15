/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/** Value-only observation of the existing pool; counters are weakly consistent during task transitions. */
@ApiStatus.Internal
public record WorkerRuntimeSnapshot(int configured, int started, int busy, long submitted, long completed,
        long rejected, long failed, long queued, long queueWaitNanos, long executionNanos, long timedTasks,
        boolean closed, boolean joined, List<Worker> workers) {
    public WorkerRuntimeSnapshot {
        workers = List.copyOf(workers);
    }

    /** CPU includes the thread's scheduler/spin work; executionNanos is task wall time, including blocking. */
    public record Worker(String name, long id, boolean alive, String state, boolean busy,
            long completed, long failed, long queueWaitNanos, long executionNanos, long cpuNanos) {}
}
