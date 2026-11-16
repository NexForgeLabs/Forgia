/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

/**
 * Internal audited operation contract, NOT an opt-in API for arbitrary mod callbacks.
 * prepare/current/commit/serial execute on the coordinator. Work must contain ONLY detached state.
 * Unknown operations return an incomplete footprint and execute as exclusive serial fences.
 */
public interface C7Task {
    Object identity();

    /** Immutable diagnostic label; no user/world callback may be invoked to resolve it on a worker. */
    default String type() {
        return "<internal>";
    }

    default C7Refusal serialReason() {
        return C7Refusal.UNKNOWN_ACCESS;
    }

    C7Footprint footprint();

    boolean current();

    /** Called at the canonical dependency frontier; null means original serial invocation. */
    @org.jetbrains.annotations.Nullable
    Prepared prepare();

    void serial();

    @FunctionalInterface
    interface Computation {
        /** No live BE, Level, capability, inventory, callback or mutable global may be accessed here. */
        Object compute(C7AccessScope access);
    }

    interface Prepared {
        /** Coordinator creates the detached kernel; validation/commit closures never go to a worker. */
        Computation computation();

        /** Revalidate all captured input and lifetime state. No writes, no callbacks. */
        boolean valid();

        /** Canonical commit. Throwing is fatal for this phase and is NEVER retried. */
        void commit(Object result);
    }
}
