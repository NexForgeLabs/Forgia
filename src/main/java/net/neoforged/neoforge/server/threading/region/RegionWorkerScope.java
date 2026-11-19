/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Marks the thread that currently executes work of a {@link RegionWorkerPool}.
 * <p>
 * The marker is a static thread local owned by the pool, not by the installed engine or the
 * {@code RegionThreadingManager}. A worker that is still unwinding after the manager was cleared must
 * still be recognized as a native worker, so the entity storage guards never depend on a successful
 * engine lookup. The scope is entered around every task the pool runs and cleared in a {@code finally}
 * block, including when the task fails.
 */
@ApiStatus.Internal
public final class RegionWorkerScope {
    private static final ThreadLocal<Boolean> NATIVE_WORKER = new ThreadLocal<>();
    private static volatile int fallbackDepth;

    private RegionWorkerScope() {}

    /**
     * {@return true when the current thread executes work of a region worker pool}
     */
    public static boolean isNativeWorkerThread() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            return worker.inWorkerScope;
        }
        return fallbackDepth != 0 && Boolean.TRUE.equals(NATIVE_WORKER.get());
    }

    static void enter() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.inWorkerScope = true;
            return;
        }
        synchronized (RegionWorkerScope.class) {
            if (!Boolean.TRUE.equals(NATIVE_WORKER.get())) {
                NATIVE_WORKER.set(Boolean.TRUE);
                ++fallbackDepth;
            }
        }
    }

    static void exit() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            worker.inWorkerScope = false;
            return;
        }
        synchronized (RegionWorkerScope.class) {
            if (Boolean.TRUE.equals(NATIVE_WORKER.get())) {
                NATIVE_WORKER.remove();
                --fallbackDepth;
            }
        }
    }
}
