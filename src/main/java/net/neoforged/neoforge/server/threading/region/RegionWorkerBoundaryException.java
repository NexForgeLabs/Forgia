/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Worker-boundary rejection thrown when a native region worker attempts an operation that belongs
 * exclusively to the server thread or to a bounded player-local state scope.
 * <p>
 * Extending {@link IllegalStateException} preserves the existing contract for callers and tests that
 * observe the rejection directly, while allowing {@link RegionThreadingEngine} to distinguish a
 * threading-boundary guard from ordinary game or mod bugs and fall the offending class back to the
 * server thread instead of crashing the server.
 */
@ApiStatus.Internal
public final class RegionWorkerBoundaryException extends IllegalStateException {
    private static final int MAX_CAUSE_DEPTH = 32;

    private final String category;
    private final String operation;

    private RegionWorkerBoundaryException(String category, String operation, String message) {
        super(message);
        this.category = category;
        this.operation = operation;
    }

    public static RegionWorkerBoundaryException storageAccess(String operation, String message) {
        return new RegionWorkerBoundaryException("storage_access", operation, message);
    }

    public static RegionWorkerBoundaryException playerState(String operation, String message) {
        return new RegionWorkerBoundaryException("player_state", operation, message);
    }

    public static RegionWorkerBoundaryException playerLifecycle(String operation, String message) {
        return new RegionWorkerBoundaryException("player_lifecycle", operation, message);
    }

    public String category() {
        return this.category;
    }

    public String operation() {
        return this.operation;
    }

    /**
     * {@return the first {@link RegionWorkerBoundaryException} in {@code throwable}'s cause chain, or
     * {@code null} when none is present}
     */
    @Nullable
    public static RegionWorkerBoundaryException findBoundaryViolation(@Nullable Throwable throwable) {
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof RegionWorkerBoundaryException boundary) {
                return boundary;
            }
            final Throwable cause = current.getCause();
            if (cause == current) {
                return null;
            }
            current = cause;
        }
        return null;
    }

    public static boolean isBoundaryViolation(@Nullable Throwable throwable) {
        return findBoundaryViolation(throwable) != null;
    }
}
