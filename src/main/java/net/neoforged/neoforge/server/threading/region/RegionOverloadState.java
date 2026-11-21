/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * Per-region overload isolation state maintained by {@link RegionOverloadGovernor} on the server thread.
 * <p>
 * Decisions are cached per {@code gameTime} so every tick slice of a given tick observes the exact same
 * skip/run outcome for the region, preserving intra-region causal order.
 */
@ApiStatus.Internal
public final class RegionOverloadState {
    public enum Status {
        NORMAL,
        THROTTLED
    }

    public record Snapshot(
            String regionId,
            Status status,
            int period,
            long lastTotalCostNanos,
            long skippedTicks,
            long throttledTransitions,
            long nextActiveGameTime,
            int overBudgetStreak,
            int underBudgetStreak) {
        public double lastTotalCostMs() {
            return this.lastTotalCostNanos / 1_000_000.0;
        }
    }

    private volatile Status status = Status.NORMAL;
    private volatile int period = 1;
    private int overBudgetStreak;
    private int underBudgetStreak;
    private long lastEvaluatedGameTime = Long.MIN_VALUE;
    private boolean skipCurrentTick;
    private volatile long nextActiveGameTime;
    private volatile long skippedTicks;
    private volatile long throttledTransitions;
    private volatile long lastTotalCostNanos;

    public RegionOverloadState() {}

    public Status status() {
        return this.status;
    }

    public int period() {
        return this.period;
    }

    public long nextActiveGameTime() {
        return this.nextActiveGameTime;
    }

    public long skippedTicks() {
        return this.skippedTicks;
    }

    public long throttledTransitions() {
        return this.throttledTransitions;
    }

    public long lastTotalCostNanos() {
        return this.lastTotalCostNanos;
    }

    int overBudgetStreak() {
        return this.overBudgetStreak;
    }

    int underBudgetStreak() {
        return this.underBudgetStreak;
    }

    long lastEvaluatedGameTime() {
        return this.lastEvaluatedGameTime;
    }

    boolean skipCurrentTick() {
        return this.skipCurrentTick;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    void setPeriod(int period) {
        this.period = period;
    }

    void setOverBudgetStreak(int overBudgetStreak) {
        this.overBudgetStreak = overBudgetStreak;
    }

    void setUnderBudgetStreak(int underBudgetStreak) {
        this.underBudgetStreak = underBudgetStreak;
    }

    void setLastEvaluatedGameTime(long lastEvaluatedGameTime) {
        this.lastEvaluatedGameTime = lastEvaluatedGameTime;
    }

    void setSkipCurrentTick(boolean skipCurrentTick) {
        this.skipCurrentTick = skipCurrentTick;
    }

    void setNextActiveGameTime(long nextActiveGameTime) {
        this.nextActiveGameTime = nextActiveGameTime;
    }

    void incrementSkippedTicks() {
        ++this.skippedTicks;
    }

    void incrementThrottledTransitions() {
        ++this.throttledTransitions;
    }

    void setLastTotalCostNanos(long lastTotalCostNanos) {
        this.lastTotalCostNanos = lastTotalCostNanos;
    }

    Snapshot snapshot(String regionId) {
        return new Snapshot(
                regionId,
                this.status,
                this.period,
                this.lastTotalCostNanos,
                this.skippedTicks,
                this.throttledTransitions,
                this.nextActiveGameTime,
                this.overBudgetStreak,
                this.underBudgetStreak);
    }
}
