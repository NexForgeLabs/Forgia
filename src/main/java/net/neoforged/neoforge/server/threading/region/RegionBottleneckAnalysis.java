/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Pure analysis of global vs per-region MSPT/TPS and automatic bottleneck attribution. */
@ApiStatus.Internal
public record RegionBottleneckAnalysis(
        double globalAvgMs,
        double globalP95Ms,
        double globalTps,
        int globalSamples,
        double estimatedSerialAndMainMs,
        double meanRegionAvgMs,
        double imbalanceRatio,
        BottleneckType verdict,
        String summary,
        @Nullable RegionImpact topRegion,
        List<RegionImpact> rankedRegions) {

    public enum BottleneckType {
        NO_DATA,
        HEALTHY,
        REGION_DOMINATED,
        MIXED_REGION_AND_MAIN,
        MAIN_THREAD_SERIAL
    }

    public record RegionImpact(
            String id,
            double msptLast,
            double msptAvg,
            double msptP95,
            double msptMax,
            double capacityTps,
            double observedTps,
            double shareOfGlobalMsptPercent,
            double shareOfBudgetPercent,
            int entities,
            int loadedChunks,
            int players,
            long pendingWork) {}

    public static double capacityTps(RegionTickSnapshot ticks) {
        if (!ticks.hasSamples()) return Double.NaN;
        final double avg = ticks.msptAvg();
        if (!Double.isFinite(avg) || avg < 0.0) return Double.NaN;
        return avg <= 50.0 ? 20.0 : Math.min(20.0, 1000.0 / avg);
    }

    public static RegionBottleneckAnalysis analyze(
            RuntimeTickHistory.Summary global,
            List<RuntimeRegionSnapshot> regions,
            double highRegionMsptThreshold,
            double highServerMsptThreshold,
            double imbalanceRatioThreshold) {
        final double globalAvg = global.averageMs();
        final double globalP95 = global.p95Ms();
        final double globalTps = global.observedTps();

        final List<RegionImpact> ranked = new ArrayList<>();
        double sumRegionAvg = 0.0;
        int sampledRegions = 0;

        for (final var region : regions) {
            final var ticks = region.ticks();
            if (!ticks.hasSamples()) continue;
            final double avg = ticks.msptAvg();
            if (!Double.isFinite(avg)) continue;
            sumRegionAvg += avg;
            sampledRegions++;
            final double capTps = capacityTps(ticks);
            final double obsTps = ticks.ticksPerSecond();
            final double globalShare = Double.isFinite(globalAvg) && globalAvg > 0.0 ? (avg / globalAvg) * 100.0 : Double.NaN;
            final double budgetShare = (avg / 50.0) * 100.0;
            ranked.add(new RegionImpact(
                    region.id(),
                    ticks.msptLast(),
                    avg,
                    ticks.msptP95(),
                    ticks.msptMax(),
                    capTps,
                    obsTps,
                    globalShare,
                    budgetShare,
                    region.entities(),
                    region.loadedChunks(),
                    region.players(),
                    region.pendingWork()));
        }

        ranked.sort(Comparator.comparingDouble(RegionImpact::msptAvg).reversed()
                .thenComparing(Comparator.comparingDouble(RegionImpact::msptP95).reversed())
                .thenComparing(RegionImpact::id));

        final RegionImpact top = ranked.isEmpty() ? null : ranked.getFirst();
        final double meanRegionAvg = sampledRegions > 0 ? sumRegionAvg / sampledRegions : 0.0;
        final double imbalance = (meanRegionAvg > 0.0 && top != null) ? (top.msptAvg() / meanRegionAvg) : 1.0;
        final double estimatedMain = Double.isFinite(globalAvg)
                ? Math.max(0.0, globalAvg - (top != null ? top.msptAvg() : 0.0))
                : Double.NaN;

        if (global.samples() == 0 || !Double.isFinite(globalAvg)) {
            return new RegionBottleneckAnalysis(
                    globalAvg, globalP95, globalTps, global.samples(),
                    estimatedMain, meanRegionAvg, imbalance,
                    BottleneckType.NO_DATA,
                    "Insufficient global tick samples to diagnose bottlenecks.",
                    top, List.copyOf(ranked));
        }

        final boolean lagging = globalAvg >= highServerMsptThreshold || (Double.isFinite(globalTps) && globalTps < 19.5);
        if (!lagging) {
            final String msg = top == null
                    ? "Server is healthy (TPS=" + fmt(globalTps) + ", MSPT=" + fmt(globalAvg) + "ms)."
                    : "Server is healthy (TPS=" + fmt(globalTps) + ", MSPT=" + fmt(globalAvg) + "ms); busiest region is "
                            + top.id() + " (" + fmt(top.msptAvg()) + "ms, capTPS=" + fmt(top.capacityTps()) + ").";
            return new RegionBottleneckAnalysis(
                    globalAvg, globalP95, globalTps, global.samples(),
                    estimatedMain, meanRegionAvg, imbalance,
                    BottleneckType.HEALTHY, msg, top, List.copyOf(ranked));
        }

        if (top != null && (top.msptAvg() >= highRegionMsptThreshold || top.msptAvg() >= globalAvg * 0.5)) {
            final String msg = "Region " + top.id() + " is the primary bottleneck (avg=" + fmt(top.msptAvg())
                    + "ms, capTPS=" + fmt(top.capacityTps()) + ", " + fmt(top.shareOfGlobalMsptPercent())
                    + "% of global MSPT), dragging global TPS to " + fmt(globalTps) + ".";
            return new RegionBottleneckAnalysis(
                    globalAvg, globalP95, globalTps, global.samples(),
                    estimatedMain, meanRegionAvg, imbalance,
                    BottleneckType.REGION_DOMINATED, msg, top, List.copyOf(ranked));
        }

        if (top != null && top.msptAvg() >= 10.0 && (imbalance >= imbalanceRatioThreshold || top.msptAvg() >= 15.0)) {
            final String msg = "Hotspot region " + top.id() + " (avg=" + fmt(top.msptAvg()) + "ms, "
                    + fmt(top.shareOfGlobalMsptPercent()) + "% of global MSPT, imbalance=" + fmt(imbalance)
                    + "x) combined with main-thread/serial work (~" + fmt(estimatedMain)
                    + "ms) is dragging global TPS to " + fmt(globalTps) + ".";
            return new RegionBottleneckAnalysis(
                    globalAvg, globalP95, globalTps, global.samples(),
                    estimatedMain, meanRegionAvg, imbalance,
                    BottleneckType.MIXED_REGION_AND_MAIN, msg, top, List.copyOf(ranked));
        }

        final String msg = "Main-thread/serial work (~" + fmt(estimatedMain)
                + "ms) is the primary bottleneck; all sampled regions are light"
                + (top != null ? " (slowest=" + top.id() + " at " + fmt(top.msptAvg()) + "ms)." : ".");
        return new RegionBottleneckAnalysis(
                globalAvg, globalP95, globalTps, global.samples(),
                estimatedMain, meanRegionAvg, imbalance,
                BottleneckType.MAIN_THREAD_SERIAL, msg, top, List.copyOf(ranked));
    }

    private static String fmt(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.ROOT, "%.2f", value) : "N/A";
    }
}
