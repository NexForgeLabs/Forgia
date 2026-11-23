/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import net.neoforged.neoforge.server.threading.region.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegionOverloadGovernorTest {
    private static final long BUDGET = 15_000_000L; // 15 ms

    @Test
    void disabledGovernorNeverSkips() {
        final var governor = new RegionOverloadGovernor(RegionOverloadGovernor.Config.disabled());
        final var state = new RegionOverloadState();
        for (long t = 0; t < 200; ++t) {
            final var eval = governor.evaluate(state, 42L, 7, 1, 2, t, BUDGET * 10, false);
            assertFalse(eval.skipTick());
            assertEquals(RegionOverloadGovernor.Transition.NONE, eval.transition());
            assertEquals(RegionOverloadState.Status.NORMAL, state.status());
        }
    }

    @Test
    void entersThrottledAfterEnterTicksAndRecoversWithHysteresis() {
        final var config = new RegionOverloadGovernor.Config(true, BUDGET, 5, 20, 0.6, 8, true, 65536);
        final var governor = new RegionOverloadGovernor(config);
        final var state = new RegionOverloadState();

        // 4 over-budget ticks -> still NORMAL
        for (long t = 1; t <= 4; ++t) {
            final var eval = governor.evaluate(state, 123L, 1, 0, 0, t, BUDGET * 3, false);
            assertFalse(eval.skipTick());
            assertEquals(RegionOverloadState.Status.NORMAL, state.status());
        }

        // 5th consecutive over-budget tick -> enters THROTTLED with period = ceil(3) = 3
        final var enter = governor.evaluate(state, 123L, 1, 0, 0, 5, BUDGET * 3, false);
        assertTrue(enter.skipTick());
        assertEquals(RegionOverloadGovernor.Transition.ENTERED_THROTTLED, enter.transition());
        assertEquals(RegionOverloadState.Status.THROTTLED, state.status());
        assertEquals(3, state.period());
        assertEquals(1L, state.throttledTransitions());
        assertTrue(enter.nextActiveGameTime() > 5L);

        // Cost in hysteresis band (between 0.6*BUDGET and BUDGET): stays THROTTLED, does not flap back to NORMAL
        final long midCost = (long) (BUDGET * 0.8);
        for (long t = 6; t <= 60; ++t) {
            governor.evaluate(state, 123L, 1, 0, 0, t, midCost, false);
            assertEquals(RegionOverloadState.Status.THROTTLED, state.status());
        }
        assertTrue(state.skippedTicks() > 0L);

        // Cost drops below 0.6 * BUDGET -> accumulates recovery credit and exits back to NORMAL
        final long lowCost = (long) (BUDGET * 0.3);
        boolean exited = false;
        for (long t = 61; t <= 200; ++t) {
            final var eval = governor.evaluate(state, 123L, 1, 0, 0, t, lowCost, false);
            if (eval.transition() == RegionOverloadGovernor.Transition.EXITED_THROTTLED) {
                exited = true;
                assertFalse(eval.skipTick());
                assertEquals(RegionOverloadState.Status.NORMAL, state.status());
                assertEquals(1, state.period());
                break;
            }
        }
        assertTrue(exited, "Region should recover to NORMAL after sustained low cost");
    }

    @Test
    void cachesDecisionAcrossMultipleSlicesInSameTick() {
        final var config = new RegionOverloadGovernor.Config(true, BUDGET, 1, 20, 0.6, 8, true, 65536);
        final var governor = new RegionOverloadGovernor(config);
        final var state = new RegionOverloadState();

        // Tick 10: first slice triggers THROTTLED and skips
        final var firstSlice = governor.evaluate(state, 99L, 3, 2, -1, 10L, BUDGET * 4, false);
        assertTrue(firstSlice.skipTick());
        final long skippedAfterFirst = state.skippedTicks();

        // Subsequent slices in the SAME tick 10 must observe the exact same skip=true without double-counting
        for (int s = 0; s < 5; ++s) {
            final var sameTick = governor.evaluate(state, 99L, 3, 2, -1, 10L, BUDGET * 4, false);
            assertTrue(sameTick.skipTick());
            assertEquals(RegionOverloadGovernor.Transition.NONE, sameTick.transition());
        }
        assertEquals(skippedAfterFirst, state.skippedTicks());

        // Jump to nextActiveGameTime: all slices on the active tick observe skip=false
        final long activeTick = firstSlice.nextActiveGameTime();
        for (int s = 0; s < 5; ++s) {
            final var activeSlice = governor.evaluate(state, 99L, 3, 2, -1, activeTick, BUDGET * 4, false);
            assertFalse(activeSlice.skipTick());
        }
    }

    @Test
    void adaptivePeriodScalesAndCapsAtMaxPeriod() {
        assertEquals(2, RegionOverloadGovernor.computePeriod(BUDGET, BUDGET, 8));
        assertEquals(2, RegionOverloadGovernor.computePeriod(BUDGET + 1, BUDGET, 8));
        assertEquals(3, RegionOverloadGovernor.computePeriod(BUDGET * 3, BUDGET, 8));
        assertEquals(8, RegionOverloadGovernor.computePeriod(BUDGET * 20, BUDGET, 8));
    }

    @Test
    void playerRegionExemptionRespectsConfig() {
        final var exemptConfig = new RegionOverloadGovernor.Config(true, BUDGET, 1, 20, 0.6, 8, false, 65536);
        final var governor = new RegionOverloadGovernor(exemptConfig);
        final var state = new RegionOverloadState();

        // Without players -> throttled immediately
        final var throttled = governor.evaluate(state, 1L, 1, 0, 0, 1L, BUDGET * 4, false);
        assertTrue(throttled.skipTick());
        assertEquals(RegionOverloadState.Status.THROTTLED, state.status());

        // Player enters -> immediately released back to NORMAL when throttlePlayerRegions=false
        final var released = governor.evaluate(state, 1L, 1, 0, 0, 2L, BUDGET * 4, true);
        assertFalse(released.skipTick());
        assertEquals(RegionOverloadGovernor.Transition.EXITED_THROTTLED, released.transition());
        assertEquals(RegionOverloadState.Status.NORMAL, state.status());
    }

    @Test
    void engineActivatesGovernorOnlyInDynamicClusterMode() {
        final var enabledConfig = new RegionOverloadGovernor.Config(true, BUDGET, 20, 100, 0.6, 8, true, 65536);
        final var workers = new RegionThreadingOptions.Workers(2, false, false, false, false);
        final var ticks = new RegionThreadingOptions.TickSlices(false, false, false, false, false);

        final var legacyDynamicEngine = new RegionThreadingEngine(new RegionThreadingOptions(
                workers, ticks, RegionLayout.of(RegionPolicy.DYNAMIC), RegionEntityStorageMode.MAIN_THREAD_ONLY, enabledConfig));
        try {
            assertFalse(legacyDynamicEngine.isOverloadGovernorActive(), "DYNAMIC must ignore overload governor");
        } finally {
            legacyDynamicEngine.shutdown();
        }

        final var dynamicEngine = new RegionThreadingEngine(new RegionThreadingOptions(
                workers, ticks, RegionLayout.dynamicCluster(3, 40L), RegionEntityStorageMode.MAIN_THREAD_ONLY, enabledConfig));
        try {
            assertTrue(dynamicEngine.isOverloadGovernorActive(), "DYNAMIC_CLUSTER must activate overload governor when enabled");
        } finally {
            dynamicEngine.shutdown();
        }

        final var disabledDynamic = new RegionThreadingEngine(new RegionThreadingOptions(
                workers, ticks, RegionLayout.dynamicCluster(3, 40L), RegionEntityStorageMode.MAIN_THREAD_ONLY, RegionOverloadGovernor.Config.disabled()));
        try {
            assertFalse(disabledDynamic.isOverloadGovernorActive(), "Disabled config must keep overload governor inactive");
        } finally {
            disabledDynamic.shutdown();
        }
    }
}
