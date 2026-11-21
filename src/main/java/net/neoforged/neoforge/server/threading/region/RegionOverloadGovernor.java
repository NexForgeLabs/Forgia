/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.RandomSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-region overload isolation governor for {@link RegionPolicy#DYNAMIC_CLUSTER}.
 * <p>
 * Tracks each region's aggregate slice EWMA cost ({@link RegionState#dispatchCosts()}). When a region
 * continuously exceeds {@link Config#budgetNanos()} for {@link Config#enterTicks()} ticks, it enters
 * {@link RegionOverloadState.Status#THROTTLED} and is dispatched only once every {@code period} ticks
 * (with deterministic jitter so modulo schedules like {@code gameTime % 20 == 0} or vanilla beacon
 * {@code gameTime % 80 == 0} never starve). All slices of a region share a single decision per
 * {@code gameTime}, preserving intra-region vanilla tick causality while isolating lagging regions from
 * global server TPS.
 */
@ApiStatus.Internal
public final class RegionOverloadGovernor {
    private static final Logger LOGGER = LogManager.getLogger();

    public record Config(
            boolean enabled,
            long budgetNanos,
            int enterTicks,
            int exitTicks,
            double exitRatio,
            int maxPeriod,
            boolean throttlePlayerRegions,
            int maxCatchUpScheduledTicks) {
        public static final long DEFAULT_BUDGET_NANOS = 15_000_000L;
        public static final int DEFAULT_ENTER_TICKS = 20;
        public static final int DEFAULT_EXIT_TICKS = 100;
        public static final double DEFAULT_EXIT_RATIO = 0.6;
        public static final int DEFAULT_MAX_PERIOD = 8;
        public static final boolean DEFAULT_THROTTLE_PLAYER_REGIONS = true;
        public static final int DEFAULT_MAX_CATCH_UP_SCHEDULED_TICKS = 65_536;

        public static Config disabled() {
            return new Config(
                    false,
                    DEFAULT_BUDGET_NANOS,
                    DEFAULT_ENTER_TICKS,
                    DEFAULT_EXIT_TICKS,
                    DEFAULT_EXIT_RATIO,
                    DEFAULT_MAX_PERIOD,
                    DEFAULT_THROTTLE_PLAYER_REGIONS,
                    DEFAULT_MAX_CATCH_UP_SCHEDULED_TICKS);
        }

        public Config {
            budgetNanos = Math.max(1_000_000L, budgetNanos);
            enterTicks = Math.max(1, enterTicks);
            exitTicks = Math.max(1, exitTicks);
            exitRatio = Math.clamp(exitRatio, 0.1, 0.95);
            maxPeriod = Math.clamp(maxPeriod, 2, 64);
            maxCatchUpScheduledTicks = Math.max(1, maxCatchUpScheduledTicks);
        }

        public long exitThresholdNanos() {
            return Math.max(1L, (long) (this.budgetNanos * this.exitRatio));
        }
    }

    public enum Transition {
        NONE,
        ENTERED_THROTTLED,
        EXITED_THROTTLED
    }

    public record Evaluation(boolean skipTick, Transition transition, int period, long nextActiveGameTime) {}

    private final Config config;

    public RegionOverloadGovernor(Config config) {
        this.config = config;
    }

    public Config config() {
        return this.config;
    }

    public boolean isEnabled() {
        return this.config.enabled();
    }

    /**
     * Evaluates (or returns the cached decision for) whether {@code region} should be skipped on
     * {@code level.getGameTime()}.
     */
    boolean shouldSkip(ServerLevel level, RegionState region, boolean hasPlayers) {
        if (!this.config.enabled()) {
            return false;
        }
        final long gameTime = level.getGameTime();
        final RegionOverloadState state = region.overloadState();
        if (state.lastEvaluatedGameTime() == gameTime) {
            return state.skipCurrentTick();
        }
        final long totalCostNanos = totalDispatchCostNanos(region);
        final RegionCoordinate coord = region.coordinate();
        final Evaluation eval = this.evaluate(
                state,
                level.getSeed(),
                level.dimension().location().hashCode(),
                coord.regionX(),
                coord.regionZ(),
                gameTime,
                totalCostNanos,
                hasPlayers);
        if (eval.transition() == Transition.ENTERED_THROTTLED) {
            LOGGER.warn("Region {} entered overload THROTTLED mode: ewma={}ms > budget={}ms, period=1/{} (next active tick={})",
                    coord,
                    String.format(java.util.Locale.ROOT, "%.2f", totalCostNanos / 1_000_000.0),
                    String.format(java.util.Locale.ROOT, "%.2f", this.config.budgetNanos() / 1_000_000.0),
                    eval.period(),
                    eval.nextActiveGameTime());
        } else if (eval.transition() == Transition.EXITED_THROTTLED) {
            LOGGER.info("Region {} exited overload THROTTLED mode back to NORMAL: ewma={}ms (skippedTicks={})",
                    coord,
                    String.format(java.util.Locale.ROOT, "%.2f", totalCostNanos / 1_000_000.0),
                    state.skippedTicks());
        }
        return eval.skipTick();
    }

    /**
     * Pure deterministic evaluation entry point used both at runtime and in unit tests.
     */
    public Evaluation evaluate(
            RegionOverloadState state,
            long levelSeed,
            int dimensionHash,
            int regionX,
            int regionZ,
            long gameTime,
            long totalCostNanos,
            boolean hasPlayers) {
        if (!this.config.enabled()) {
            return new Evaluation(false, Transition.NONE, 1, gameTime);
        }
        if (state.lastEvaluatedGameTime() == gameTime) {
            return new Evaluation(state.skipCurrentTick(), Transition.NONE, state.period(), state.nextActiveGameTime());
        }
        if (gameTime < state.lastEvaluatedGameTime()) {
            // Game time moved backwards (e.g., reset in a test); avoid stranding nextActiveGameTime in the far future.
            state.setNextActiveGameTime(gameTime);
        }
        state.setLastEvaluatedGameTime(gameTime);
        state.setLastTotalCostNanos(totalCostNanos);

        if (hasPlayers && !this.config.throttlePlayerRegions()) {
            final Transition transition = state.status() == RegionOverloadState.Status.THROTTLED
                    ? Transition.EXITED_THROTTLED
                    : Transition.NONE;
            resetToNormal(state);
            return new Evaluation(false, transition, 1, gameTime);
        }

        if (state.status() == RegionOverloadState.Status.NORMAL) {
            if (totalCostNanos > this.config.budgetNanos()) {
                final int streak = state.overBudgetStreak() + 1;
                state.setOverBudgetStreak(streak);
                state.setUnderBudgetStreak(0);
                if (streak >= this.config.enterTicks()) {
                    final int period = computePeriod(totalCostNanos, this.config.budgetNanos(), this.config.maxPeriod());
                    final long nextActive = gameTime + computeJitteredStep(period, levelSeed, dimensionHash, regionX, regionZ, gameTime);
                    state.setStatus(RegionOverloadState.Status.THROTTLED);
                    state.setPeriod(period);
                    state.setOverBudgetStreak(0);
                    state.setUnderBudgetStreak(0);
                    state.setNextActiveGameTime(nextActive);
                    state.incrementThrottledTransitions();
                    state.setSkipCurrentTick(true);
                    state.incrementSkippedTicks();
                    return new Evaluation(true, Transition.ENTERED_THROTTLED, period, nextActive);
                }
            } else {
                state.setOverBudgetStreak(0);
            }
            state.setSkipCurrentTick(false);
            return new Evaluation(false, Transition.NONE, 1, gameTime);
        }

        // Currently THROTTLED
        if (gameTime < state.nextActiveGameTime()) {
            state.setSkipCurrentTick(true);
            state.incrementSkippedTicks();
            return new Evaluation(true, Transition.NONE, state.period(), state.nextActiveGameTime());
        }

        // Active tick for a THROTTLED region: evaluate recovery using the latest EWMA cost.
        final long exitThreshold = this.config.exitThresholdNanos();
        if (totalCostNanos < exitThreshold) {
            final int underStreak = state.underBudgetStreak() + state.period();
            state.setUnderBudgetStreak(underStreak);
            state.setOverBudgetStreak(0);
            if (underStreak >= this.config.exitTicks()) {
                resetToNormal(state);
                return new Evaluation(false, Transition.EXITED_THROTTLED, 1, gameTime);
            }
        } else {
            state.setUnderBudgetStreak(0);
        }

        final int period = computePeriod(totalCostNanos, this.config.budgetNanos(), this.config.maxPeriod());
        final long nextActive = gameTime + computeJitteredStep(period, levelSeed, dimensionHash, regionX, regionZ, gameTime);
        state.setPeriod(period);
        state.setNextActiveGameTime(nextActive);
        state.setSkipCurrentTick(false);
        return new Evaluation(false, Transition.NONE, period, nextActive);
    }

    private static void resetToNormal(RegionOverloadState state) {
        state.setStatus(RegionOverloadState.Status.NORMAL);
        state.setPeriod(1);
        state.setOverBudgetStreak(0);
        state.setUnderBudgetStreak(0);
        state.setNextActiveGameTime(0L);
        state.setSkipCurrentTick(false);
    }

    public static int computePeriod(long totalCostNanos, long budgetNanos, int maxPeriod) {
        if (totalCostNanos <= budgetNanos) {
            return 2;
        }
        final long ratio = Math.ceilDiv(totalCostNanos, budgetNanos);
        return (int) Math.clamp(ratio, 2L, maxPeriod);
    }

    /**
     * Computes a deterministic jittered step around {@code period} with mean {@code period} and
     * support {@code [period - floor(period/2), period + floor(period/2)]} (clamped at >= 1).
     * Because both {@code period - 1} and {@code period} are always in the support and coprime,
     * every modulo schedule {@code gameTime % N == k} is hit in finite time with zero starvation.
     */
    static int computeJitteredStep(int period, long levelSeed, int dimensionHash, int regionX, int regionZ, long gameTime) {
        if (period <= 1) {
            return 1;
        }
        final int maxJitter = period / 2;
        final int span = maxJitter * 2 + 1;
        long mix = RandomSupport.mixStafford13(levelSeed ^ gameTime);
        mix = RandomSupport.mixStafford13(mix ^ dimensionHash);
        mix = RandomSupport.mixStafford13(mix ^ ChunkPos.asLong(regionX, regionZ));
        final int offset = Math.floorMod(mix, span) - maxJitter;
        return Math.max(1, period + offset);
    }

    static long totalDispatchCostNanos(RegionState region) {
        final AtomicLongArray costs = region.dispatchCostsOrNull();
        if (costs == null) {
            return 0L;
        }
        long sum = 0L;
        for (int i = 0, len = costs.length(); i < len; ++i) {
            sum += costs.get(i);
        }
        return sum;
    }
}
