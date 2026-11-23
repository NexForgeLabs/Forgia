/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.neoforged.neoforge.server.threading.region.LowTpsProbe;
import org.junit.jupiter.api.Test;

final class LowTpsProbeTest {
    private static final long SEC = 1_000_000_000L;

    @Test
    void triggersOnlyAfterSustainedLowTpsAndRespectsResetAndCooldown() {
        final LowTpsProbe probe = new LowTpsProbe();
        final double threshold = 18.0D;
        final long duration = 30L * SEC;
        final long cooldown = 60L * SEC;

        // Healthy tick: does not trigger
        assertFalse(probe.evaluateSample(10L * SEC, 20.0D, threshold, duration, cooldown));

        // Drops below 18 at t=20s; still below at t=45s (25s < 30s) -> no trigger
        assertFalse(probe.evaluateSample(20L * SEC, 16.5D, threshold, duration, cooldown));
        assertFalse(probe.evaluateSample(45L * SEC, 15.0D, threshold, duration, cooldown));

        // Recovers briefly at t=46s -> resets timer
        assertFalse(probe.evaluateSample(46L * SEC, 18.2D, threshold, duration, cooldown));

        // Drops again at t=50s; at t=79s (29s) -> no trigger; at t=80s (30s) -> triggers!
        assertFalse(probe.evaluateSample(50L * SEC, 14.0D, threshold, duration, cooldown));
        assertFalse(probe.evaluateSample(79L * SEC, 14.0D, threshold, duration, cooldown));
        assertTrue(probe.evaluateSample(80L * SEC, 14.0D, threshold, duration, cooldown));

        // Remains low during 60s cooldown -> suppressed
        assertFalse(probe.evaluateSample(100L * SEC, 12.0D, threshold, duration, cooldown));
        assertFalse(probe.evaluateSample(139L * SEC, 12.0D, threshold, duration, cooldown));

        // Cooldown elapsed at t=140s -> triggers second report
        assertTrue(probe.evaluateSample(140L * SEC, 12.0D, threshold, duration, cooldown));
    }
}
