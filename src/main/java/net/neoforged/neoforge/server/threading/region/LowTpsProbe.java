/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

/**
 * Low-overhead sustained-low-TPS probe. Runs once per wall second on the server thread via
 * {@link RuntimeObservability} and records a structured bottleneck snapshot to both the server
 * log and {@code logs/forgia-low-tps-probe.jsonl} whenever global TPS remains below the
 * configured threshold continuously for the configured duration.
 */
@ApiStatus.Internal
public final class LowTpsProbe {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final Path DEFAULT_LOG_PATH = Path.of("logs", "forgia-low-tps-probe.jsonl");

    private long lastCheckNanos;
    private long lowTpsStartedNanos;
    private long lastReportNanos;

    public void onSecondSample(
            MinecraftServer server,
            long nowNanos,
            RuntimeTickHistory history,
            RuntimeObservability.JvmSample jvmSample) {
        if (!isEnabled()) {
            this.reset();
            return;
        }
        if (this.lastCheckNanos != 0L && nowNanos - this.lastCheckNanos < 1_000_000_000L) {
            return;
        }
        this.lastCheckNanos = nowNanos;

        // Use a 5-second rolling TPS window so single-tick jitter does not flap the timer,
        // while still reacting promptly when sustained degradation starts.
        final RuntimeTickHistory.Summary recent = history.snapshot(nowNanos, 5);
        if (recent.samples() < 10 || Double.isNaN(recent.observedTps())) {
            return;
        }

        final double threshold = thresholdTps();
        final long durationNanos = durationSeconds() * 1_000_000_000L;
        final long cooldownNanos = cooldownSeconds() * 1_000_000_000L;
        if (!this.evaluateSample(nowNanos, recent.observedTps(), threshold, durationNanos, cooldownNanos)) {
            return;
        }

        this.recordReport(server, nowNanos, history, jvmSample, threshold, durationSeconds(), topCount());
    }

    /**
     * Pure state-machine evaluation for deterministic unit testing.
     *
     * @return {@code true} when a sustained low-TPS report should be emitted now.
     */
    public boolean evaluateSample(
            long nowNanos,
            double rollingTps,
            double thresholdTps,
            long durationNanos,
            long cooldownNanos) {
        if (Double.isNaN(rollingTps) || rollingTps >= thresholdTps) {
            this.lowTpsStartedNanos = 0L;
            return false;
        }
        if (this.lowTpsStartedNanos == 0L) {
            this.lowTpsStartedNanos = nowNanos;
            return false;
        }
        if (nowNanos - this.lowTpsStartedNanos < durationNanos) {
            return false;
        }
        if (this.lastReportNanos != 0L && nowNanos - this.lastReportNanos < cooldownNanos) {
            return false;
        }
        this.lastReportNanos = nowNanos;
        return true;
    }

    void reset() {
        this.lastCheckNanos = 0L;
        this.lowTpsStartedNanos = 0L;
        this.lastReportNanos = 0L;
    }

    private void recordReport(
            MinecraftServer server,
            long nowNanos,
            RuntimeTickHistory history,
            RuntimeObservability.JvmSample jvm,
            double thresholdTps,
            int windowSeconds,
            int topCount) {
        final RuntimeTickHistory.Summary window = history.snapshot(nowNanos, Math.min(300, Math.max(5, windowSeconds)));
        final JsonObject report = new JsonObject();
        report.addProperty("timestamp", Instant.now().toString());
        report.addProperty("thresholdTps", thresholdTps);
        report.addProperty("sustainedSeconds", windowSeconds);
        report.addProperty("globalTps", round2(window.observedTps()));
        report.addProperty("avgMspt", round2(window.averageMs()));
        report.addProperty("p95Mspt", round2(window.p95Ms()));
        report.addProperty("maxMspt", round2(window.maxMs()));
        report.addProperty("overruns", window.overruns());

        if (!Double.isNaN(jvm.mainCpuPercent())) {
            final JsonObject jvmObj = new JsonObject();
            jvmObj.addProperty("mainCpuPercent", round2(jvm.mainCpuPercent()));
            jvmObj.addProperty("workerCpuPercentOfOneCore", round2(jvm.workerCpuPercentOfOneCore()));
            jvmObj.addProperty("allocatedMiBPerSecond", round2(jvm.allocatedMiBPerSecond()));
            jvmObj.addProperty("gcCollections", jvm.gcCollections());
            jvmObj.addProperty("gcCollectionMillis", jvm.gcCollectionMillis());
            jvmObj.addProperty("heapMiB", jvm.heapBytes() / (1024L * 1024L));
            report.add("jvm", jvmObj);
        }

        final StringBuilder console = new StringBuilder();
        console.append(String.format(
                Locale.ROOT,
                "[Forgia LowTPS Probe] Global TPS=%.2f (<%.1f for %ds) avg=%.2fms p95=%.2fms max=%.2fms",
                window.observedTps(), thresholdTps, windowSeconds, window.averageMs(), window.p95Ms(), window.maxMs()));

        if (RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine) {
            final var analysis = RegionBottleneckAnalysis.analyze(
                    window,
                    engine.runtimeRegionSnapshots(),
                    highRegionMspt(),
                    highServerMspt(),
                    regionImbalanceRatio());
            report.addProperty("verdict", analysis.verdict().name());
            report.addProperty("summary", analysis.summary());
            report.addProperty("estimatedSerialAndMainMspt", round2(analysis.estimatedSerialAndMainMs()));
            console.append(" | verdict=").append(analysis.verdict().name())
                    .append(" estMainSerial=").append(String.format(Locale.ROOT, "%.2fms", analysis.estimatedSerialAndMainMs()));

            final JsonArray regionsArr = new JsonArray();
            final int regionLimit = Math.min(topCount, analysis.rankedRegions().size());
            if (regionLimit > 0) {
                console.append(" | topRegions=[");
            }
            for (int i = 0; i < regionLimit; i++) {
                final var r = analysis.rankedRegions().get(i);
                final JsonObject obj = new JsonObject();
                obj.addProperty("id", r.id());
                obj.addProperty("avgMspt", round2(r.msptAvg()));
                obj.addProperty("p95Mspt", round2(r.msptP95()));
                obj.addProperty("maxMspt", round2(r.msptMax()));
                obj.addProperty("globalSharePercent", round2(r.shareOfGlobalMsptPercent()));
                obj.addProperty("entities", r.entities());
                obj.addProperty("chunks", r.loadedChunks());
                obj.addProperty("players", r.players());
                regionsArr.add(obj);
                if (i > 0) console.append(", ");
                console.append(String.format(Locale.ROOT, "%s(avg=%.2fms,max=%.2fms,ent=%d)",
                        r.id(), r.msptAvg(), r.msptMax(), r.entities()));
            }
            if (regionLimit > 0) {
                console.append("]");
            }
            report.add("topRegions", regionsArr);

            final List<RegionMainThreadPhaseMetrics.PhaseSnapshot> sortedPhases = engine.mainThreadPhaseSnapshots()
                    .stream()
                    .filter(p -> p.calls() > 0L)
                    .sorted(Comparator.comparingDouble(RegionMainThreadPhaseMetrics.PhaseSnapshot::avgPerCallMs).reversed())
                    .limit(topCount)
                    .toList();
            final JsonArray phasesArr = new JsonArray();
            if (!sortedPhases.isEmpty()) {
                console.append(" | topMainPhases=[");
            }
            for (int i = 0; i < sortedPhases.size(); i++) {
                final var p = sortedPhases.get(i);
                final JsonObject obj = new JsonObject();
                obj.addProperty("phase", p.phase().label());
                obj.addProperty("avgMs", round2(p.avgPerCallMs()));
                obj.addProperty("maxMs", round2(p.maxMs()));
                obj.addProperty("totalMs", round2(p.totalMs()));
                obj.addProperty("calls", p.calls());
                phasesArr.add(obj);
                if (i > 0) console.append(", ");
                console.append(String.format(Locale.ROOT, "%s(avg=%.2fms,max=%.2fms)",
                        p.phase().label(), p.avgPerCallMs(), p.maxMs()));
            }
            if (!sortedPhases.isEmpty()) {
                console.append("]");
            }
            report.add("topMainPhases", phasesArr);

            final List<RegionSliceCriticalPathMetrics.SliceSnapshot> sortedSlices = engine.criticalPathSnapshots()
                    .stream()
                    .filter(s -> s.batches() > 0L)
                    .sorted(Comparator.comparingDouble(RegionSliceCriticalPathMetrics.SliceSnapshot::avgCriticalPathMs).reversed())
                    .limit(topCount)
                    .toList();
            final JsonArray slicesArr = new JsonArray();
            if (!sortedSlices.isEmpty()) {
                console.append(" | topSlices=[");
            }
            for (int i = 0; i < sortedSlices.size(); i++) {
                final var s = sortedSlices.get(i);
                final JsonObject obj = new JsonObject();
                obj.addProperty("slice", s.slice().name());
                obj.addProperty("critAvgMs", round2(s.avgCriticalPathMs()));
                obj.addProperty("critMaxMs", round2(s.maxCriticalPathMs()));
                obj.addProperty("barrierAvgMs", round2(s.avgBarrierWaitMs()));
                obj.addProperty("slowestRegion", s.slowestRegionId());
                slicesArr.add(obj);
                if (i > 0) console.append(", ");
                console.append(String.format(Locale.ROOT, "%s(barrier=%.2fms,critMax=%.2fms)",
                        s.slice().name(), s.avgBarrierWaitMs(), s.maxCriticalPathMs()));
            }
            if (!sortedSlices.isEmpty()) {
                console.append("]");
            }
            report.add("topSlices", slicesArr);

            final var fenceSnapshot = engine.fenceMetricsSnapshot(topCount);
            final JsonArray fencesArr = new JsonArray();
            if (!fenceSnapshot.topEntries().isEmpty()) {
                console.append(" | topFences=[");
            }
            for (int i = 0; i < fenceSnapshot.topEntries().size(); i++) {
                final var f = fenceSnapshot.topEntries().get(i);
                final JsonObject obj = new JsonObject();
                obj.addProperty("ticker", f.tickerClass());
                obj.addProperty("region", f.regionId());
                obj.addProperty("count", f.count());
                obj.addProperty("totalMs", round2(f.totalMs()));
                obj.addProperty("maxMs", round2(f.maxMs()));
                fencesArr.add(obj);
                if (i > 0) console.append(", ");
                console.append(String.format(Locale.ROOT, "%s@%s(total=%.1fms,max=%.2fms)",
                        f.tickerClass(), f.regionId(), f.totalMs(), f.maxMs()));
            }
            if (!fenceSnapshot.topEntries().isEmpty()) {
                console.append("]");
            }
            report.add("topSerialFences", fencesArr);
        }

        LOGGER.warn("{}", console);
        final String jsonLine = GSON.toJson(report) + System.lineSeparator();
        Util.ioPool().execute(() -> {
            try {
                final Path parent = DEFAULT_LOG_PATH.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(
                        DEFAULT_LOG_PATH,
                        jsonLine,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND);
            } catch (Exception e) {
                LOGGER.debug("Failed to append low-TPS probe report to {}", DEFAULT_LOG_PATH, e);
            }
        });
    }

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return -1.0D;
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static boolean isEnabled() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.enableLowTpsProbe.get()
                : ForgiaConfig.SERVER.enableLowTpsProbe.getDefault();
    }

    private static double thresholdTps() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.lowTpsProbeThreshold.get()
                : ForgiaConfig.SERVER.lowTpsProbeThreshold.getDefault();
    }

    private static int durationSeconds() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.lowTpsProbeDurationSeconds.get()
                : ForgiaConfig.SERVER.lowTpsProbeDurationSeconds.getDefault();
    }

    private static int cooldownSeconds() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.lowTpsProbeCooldownSeconds.get()
                : ForgiaConfig.SERVER.lowTpsProbeCooldownSeconds.getDefault();
    }

    private static int topCount() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.lowTpsProbeTopCount.get()
                : ForgiaConfig.SERVER.lowTpsProbeTopCount.getDefault();
    }

    private static int highRegionMspt() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.runtimeHighRegionMspt.get()
                : ForgiaConfig.SERVER.runtimeHighRegionMspt.getDefault();
    }

    private static int highServerMspt() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.runtimeHighServerMspt.get()
                : ForgiaConfig.SERVER.runtimeHighServerMspt.getDefault();
    }

    private static int regionImbalanceRatio() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.runtimeRegionImbalanceRatio.get()
                : ForgiaConfig.SERVER.runtimeRegionImbalanceRatio.getDefault();
    }
}
