/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

/** Hard bounds never relax completeness/ownership checks. No C7 pool is created. */
public record C7Options(Mode mode, int workers, int maxWaveSize, int maxWaves, int maxTasks,
        int maxResources, long stragglerNanos, boolean debug, boolean trackingDiagnostics, boolean conservative) {
    public enum Mode {
        SHADOW, PARALLEL
    }

    public C7Options {
        java.util.Objects.requireNonNull(mode);
        if (workers < 1 || maxWaveSize < 1 || maxWaves < 1 || maxTasks < 1 || maxResources < 1 || stragglerNanos < 1)
            throw new IllegalArgumentException("C7 limits must be positive");
    }
}
