/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import net.neoforged.neoforge.server.threading.region.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies that jittered overload scheduling hits every residue {@code gameTime % N == k} in finite time,
 * preventing starvation of periodic vanilla/mod logic such as {@code gameTime % 20 == 0} or beacon {@code % 80 == 0}.
 */
class RegionOverloadResidueCoverageTest {
    private static final long BUDGET = 15_000_000L;

    @Test
    void jitteredSchedulingHitsEveryModuloResidueWithoutStarvation() {
        final int[] moduli = { 2, 3, 4, 5, 8, 10, 16, 20, 40, 60, 80, 100 };
        for (int period = 2; period <= 8; ++period) {
            final var config = new RegionOverloadGovernor.Config(true, BUDGET, 1, 100, 0.6, period, true, 65536);
            final var governor = new RegionOverloadGovernor(config);
            final var state = new RegionOverloadState();
            final long cost = BUDGET * period;

            final boolean[][] seen = new boolean[moduli.length][];
            for (int m = 0; m < moduli.length; ++m) {
                seen[m] = new boolean[moduli[m]];
            }

            int activeTicks = 0;
            final int totalTicks = 20_000;
            for (long t = 1; t <= totalTicks; ++t) {
                final var eval = governor.evaluate(state, 0xCAFEBABEL, 1337, 3, -5, t, cost, false);
                if (!eval.skipTick()) {
                    ++activeTicks;
                    for (int m = 0; m < moduli.length; ++m) {
                        seen[m][(int) (t % moduli[m])] = true;
                    }
                }
            }

            // Every residue k in [0, N) must have been hit
            for (int m = 0; m < moduli.length; ++m) {
                int hitCount = 0;
                for (final boolean hit : seen[m]) {
                    if (hit) ++hitCount;
                }
                assertEquals(moduli[m], hitCount,
                        "Expected full residue coverage for period=" + period + ", N=" + moduli[m]);
            }

            // Average dispatch interval should stay close to target period (within 10%)
            final double observedPeriod = (double) totalTicks / activeTicks;
            assertTrue(observedPeriod >= period * 0.90 && observedPeriod <= period * 1.10,
                    "Expected observed period ~" + period + " but got " + observedPeriod);
        }
    }
}
