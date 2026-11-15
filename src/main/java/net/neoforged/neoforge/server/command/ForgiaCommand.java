/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.command;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RuntimeObservability;
import net.neoforged.neoforge.server.threading.region.RuntimeRegionSnapshot;
import org.jetbrains.annotations.ApiStatus;

/** Administrative views of the native tick ring and the existing canonical engine. */
@ApiStatus.Internal
public final class ForgiaCommand {
    private static final int PAGE_SIZE = 10;
    private static final List<String> METRICS = List.of("mspt", "avg", "p50", "p95", "p99", "entities", "blockentities", "chunks", "pending", "worker", "serial");

    private ForgiaCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("forgia").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    line(context.getSource(), "Usage: /forgia status|regions [page]|region <dimension> <x> <z>|region top <metric> [count]|workers|scheduler|performance [history [seconds]|json]|diagnostics");
                    return 1;
                })
                .then(Commands.literal("status").executes(context -> {
                    RegionThreadingHooks.diagnostics().ifPresentOrElse(
                            diagnostics -> RegionThreadingCommand.sendStatus(context.getSource(), diagnostics),
                            () -> line(context.getSource(), "Region threading is not installed. Native tick monitoring: /forgia performance"));
                    return 1;
                }))
                .then(Commands.literal("regions").executes(context -> regions(context.getSource(), 1, null, PAGE_SIZE))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> regions(context.getSource(), IntegerArgumentType.getInteger(context, "page"), null, PAGE_SIZE))))
                .then(Commands.literal("region")
                        .then(Commands.literal("top")
                                .then(Commands.argument("metric", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(METRICS, builder))
                                        .executes(context -> regions(context.getSource(), 1, StringArgumentType.getString(context, "metric"), PAGE_SIZE))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                                .executes(context -> regions(context.getSource(), 1, StringArgumentType.getString(context, "metric"), IntegerArgumentType.getInteger(context, "count"))))))
                        .then(Commands.argument("dimension", ResourceLocationArgument.id())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggestResource(context.getSource().getServer().levelKeys().stream().map(key -> key.location()), builder))
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> region(context.getSource(), ResourceLocationArgument.getId(context, "dimension").toString(),
                                                        IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z")))))))
                .then(Commands.literal("workers").executes(context -> workers(context.getSource())))
                .then(Commands.literal("scheduler").executes(context -> scheduler(context.getSource())))
                .then(performance())
                .then(Commands.literal("diagnostics").executes(context -> {
                    final var source = context.getSource();
                    performance(source, 10, false);
                    workers(source);
                    scheduler(source);
                    warnings(source);
                    return 1;
                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> performance() {
        return Commands.literal("performance").executes(context -> performance(context.getSource(), 10, false))
                .then(Commands.literal("json").executes(context -> performance(context.getSource(), 10, true)))
                .then(Commands.literal("history").executes(context -> history(context.getSource()))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("1", "10", "60", "300"), builder))
                                .executes(context -> performance(context.getSource(), IntegerArgumentType.getInteger(context, "seconds"), false))));
    }

    private static int history(CommandSourceStack source) {
        for (final int seconds : new int[] { 1, 10, 60, 300 }) performance(source, seconds, false);
        return 1;
    }

    private static int performance(CommandSourceStack source, int seconds, boolean json) {
        final var history = RuntimeObservability.history(source.getServer(), seconds);
        if (json) {
            final var output = new com.google.gson.JsonObject();
            output.addProperty("schemaVersion", 1);
            output.addProperty("enabled", ForgiaConfig.SERVER.enableRuntimeDiagnostics.get());
            output.addProperty("requestedSeconds", seconds);
            output.addProperty("samples", history.samples());
            output.addProperty("capacityLimited", history.capacityLimited());
            jsonNumber(output, "averageMs", history.averageMs());
            jsonNumber(output, "p50Ms", history.p50Ms());
            jsonNumber(output, "p95Ms", history.p95Ms());
            jsonNumber(output, "p99Ms", history.p99Ms());
            jsonNumber(output, "observedTps", history.observedTps());
            line(source, new GsonBuilder().serializeNulls().create().toJson(output));
            return 1;
        }
        line(source, "Native server ticks " + seconds + "s: samples=" + history.samples()
                + " avg=" + number(history.averageMs()) + "ms p50=" + number(history.p50Ms())
                + "ms p95=" + number(history.p95Ms()) + "ms p99=" + number(history.p99Ms())
                + "ms max=" + number(history.maxMs()) + "ms last=" + number(history.lastMs())
                + "ms TPS=" + number(history.observedTps()) + " overruns=" + history.overruns()
                + " coverage=" + number(history.coverageSeconds()) + "s capacityLimited=" + history.capacityLimited()
                + " sampling=" + ForgiaConfig.SERVER.enableRuntimeDiagnostics.get());
        if (seconds == 10) {
            final var jvm = RuntimeObservability.jvmSample(source.getServer());
            line(source, "JVM interval=" + number(jvm.intervalSeconds()) + "s mainCPU=" + number(jvm.mainCpuPercent())
                    + "% workerCPU=" + number(jvm.workerCpuPercentOfOneCore()) + "% of one core"
                    + " server+workerAllocation=" + number(jvm.allocatedMiBPerSecond()) + "MiB/s"
                    + " GC collections=" + count(jvm.gcCollections()) + " GC collectionTime=" + count(jvm.gcCollectionMillis())
                    + "ms (MXBean time; pause duration N/A) heap=" + (jvm.heapBytes() < 0 ? "N/A" : number(jvm.heapBytes() / 1048576.0) + "MiB"));
        }
        return 1;
    }

    private static int regions(CommandSourceStack source, int page, String metric, int limit) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return noEngine(source);
        if (metric != null && !METRICS.contains(metric)) {
            source.sendFailure(Component.literal("Unknown region metric '" + metric + "'. Valid metrics: " + String.join(", ", METRICS)));
            return 0;
        }
        final var regions = new ArrayList<>(engine.runtimeRegionSnapshots());
        if (metric != null && regions.stream().noneMatch(region -> Double.isFinite(metric(region, metric)))) {
            line(source, "Region metric " + metric + " is N/A; no measured values are available.");
            return 0;
        }
        if (metric == null) regions.sort(Comparator.comparing(RuntimeRegionSnapshot::id));
        else regions.sort(Comparator.<RuntimeRegionSnapshot>comparingDouble(region -> {
            final double value = metric(region, metric);
            return Double.isFinite(value) ? -value : Double.POSITIVE_INFINITY;
        }).thenComparing(RuntimeRegionSnapshot::id));
        final int pages = Math.max(1, (regions.size() + limit - 1) / limit);
        if (page > pages) {
            source.sendFailure(Component.literal("Region page " + page + " does not exist; pages=1.." + pages));
            return 0;
        }
        final int first = (page - 1) * limit;
        line(source, "Regions=" + regions.size() + " page=" + page + "/" + pages + " sort=" + (metric == null ? "id" : metric)
                + " coverage=instrumented leased groups; canonical BE and serial fallback time N/A");
        for (int index = first; index < Math.min(regions.size(), first + limit); ++index) {
            final var region = regions.get(index);
            final var ticks = region.ticks();
            line(source, region.id() + " current=" + measured(region, ticks.msptLast()) + "ms avg=" + measured(region, ticks.msptAvg())
                    + "ms p95=" + measured(region, ticks.msptP95()) + "ms chunks=" + region.loadedChunks()
                    + " entities=" + region.entities() + " players=" + count(region.players()) + " pending=" + region.pendingWork()
                    + " worker=" + number(region.workerNanos() / 1e6) + "ms serial=" + number(region.mainThreadNanos() / 1e6)
                    + "ms state=" + region.lifecycle());
        }
        return 1;
    }

    private static int region(CommandSourceStack source, String dimension, int x, int z) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return noEngine(source);
        for (final var region : engine.runtimeRegionSnapshots()) {
            final var ticks = region.ticks();
            if (!ticks.dimension().equals(dimension) || ticks.regionX() != x || ticks.regionZ() != z) continue;
            line(source, region.id() + " state=" + region.lifecycle() + " running=" + region.running() + " submitted=" + region.submitted());
            line(source, "Leased-group MSPT: current=" + measured(region, ticks.msptLast()) + " avg=" + measured(region, ticks.msptAvg())
                    + " p50=" + number(region.p50Ms()) + " p95=" + measured(region, ticks.msptP95()) + " p99=" + measured(region, ticks.msptP99())
                    + " max=" + measured(region, ticks.msptMax()) + " samples=" + ticks.samples() + " overruns=" + region.overruns()
                    + " groupTicks=" + region.groupTicks() + " window=last100ticks; canonical BE and serial fallback time N/A");
            line(source, "Workload: loadedChunks=" + region.loadedChunks() + " trackedEntities=" + region.entities() + " publishedPlayers=" + count(region.players())
                    + " blockEntities=N/A tickingChunks=N/A scheduledBlockTicks=N/A pendingRegionQueueEntries=" + region.pendingWork());
            line(source, "Lifetime instrumented groups: mainWall=" + number(region.mainThreadNanos() / 1e6) + "ms workerWall=" + number(region.workerNanos() / 1e6)
                    + "ms serialGroups=" + region.serialGroups() + " workerGroups=" + region.workerGroups()
                    + "; CPU/commitWait/ownershipContention/crossRegionOverhead/admissionRejections/serialFallbacks=N/A per region");
            return 1;
        }
        source.sendFailure(Component.literal("No active region " + dimension + "@" + x + "," + z + "; coordinates are region coordinates, not chunks or blocks."));
        return 0;
    }

    private static double metric(RuntimeRegionSnapshot region, String metric) {
        return switch (metric) {
            case "mspt" -> region.ticks().hasSamples() ? region.ticks().msptLast() : Double.NaN;
            case "avg" -> region.ticks().hasSamples() ? region.ticks().msptAvg() : Double.NaN;
            case "p50" -> region.p50Ms();
            case "p95" -> region.ticks().hasSamples() ? region.ticks().msptP95() : Double.NaN;
            case "p99" -> region.ticks().hasSamples() ? region.ticks().msptP99() : Double.NaN;
            case "chunks" -> region.loadedChunks();
            case "entities" -> region.entities();
            case "pending" -> region.pendingWork();
            case "worker" -> region.workerNanos();
            case "serial" -> region.mainThreadNanos();
            default -> Double.NaN;
        };
    }

    private static int workers(CommandSourceStack source) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return noEngine(source);
        final var pool = engine.workerRuntimeSnapshot();
        final var cpu = RuntimeObservability.jvmSample(source.getServer());
        line(source, "Workers: configured=" + pool.configured() + " alive=" + pool.started() + " busy=" + pool.busy()
                + " idle=" + (pool.started() - pool.busy()) + " queued=" + pool.queued() + " submitted=" + pool.submitted()
                + " completed=" + pool.completed() + " rejected=" + pool.rejected() + " failed=" + pool.failed()
                + " closed=" + pool.closed() + " joined=" + pool.joined());
        line(source, "CPU utilization=" + number(cpu.workerCpuPercentOfOneCore() / pool.configured())
                + "% of configured capacity; includes spin/scheduler CPU. Task wall includes blocking."
                + " sampledTaskWall=" + number(pool.executionNanos() / 1e6) + "ms queueWait=" + number(pool.queueWaitNanos() / 1e6)
                + "ms avgTaskWall=" + number(pool.timedTasks() == 0 ? Double.NaN : pool.executionNanos() / (double) pool.timedTasks() / 1e6)
                + "ms avgQueueWait=" + number(pool.timedTasks() == 0 ? Double.NaN : pool.queueWaitNanos() / (double) pool.timedTasks() / 1e6)
                + "ms timedTasks=" + pool.timedTasks() + " taskTimingEnabled=" + ForgiaConfig.SERVER.enableRuntimeDiagnostics.get());
        for (final var worker : pool.workers().subList(0, Math.min(20, pool.workers().size())))
            line(source, worker.name() + " state=" + worker.state() + " busy=" + worker.busy() + " completed=" + worker.completed()
                    + " failed=" + worker.failed() + " taskWall=" + number(worker.executionNanos() / 1e6)
                    + "ms CPU=" + (worker.cpuNanos() < 0 ? "N/A" : number(worker.cpuNanos() / 1e6) + "ms")
                    + " queueWait=" + number(worker.queueWaitNanos() / 1e6) + "ms");
        if (pool.workers().size() > 20) line(source, "Per-worker rows capped at 20 of " + pool.workers().size() + "; totals include every worker.");
        return 1;
    }

    private static int scheduler(CommandSourceStack source) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return noEngine(source);
        final var diagnostics = engine.diagnostics();
        final var pool = engine.workerRuntimeSnapshot();
        final var furnace = engine.furnaceWorkMetrics();
        final var resources = engine.blockEntityResourceMetrics();
        line(source, "Canonical scheduler: submitted=" + pool.submitted() + " completed=" + pool.completed() + " rejected=" + pool.rejected()
                + " failed=" + pool.failed() + " queue=" + pool.queued() + " activeRegionLeases=" + diagnostics.runningRegionCount()
                + " submittedRegions=" + diagnostics.submittedRegionCount() + " structuralChanges=" + diagnostics.structuralChangeCount());
        line(source, "Lifetime fallback bodies: entities=" + diagnostics.fallbackEntityTicks() + " blockEntities=" + diagnostics.fallbackBlockEntityTicks()
                + " scheduled=" + diagnostics.fallbackScheduledTicks() + " chunks=" + diagnostics.fallbackChunkTicks()
                + "; these are bodies, not rejected pool tasks or a common admission denominator.");
        line(source, "Canonical furnace phases=" + furnace.phases() + " prepare=" + number(furnace.preparationNanos() / 1e6)
                + "ms execute+join=" + number(furnace.executionNanos() / 1e6) + "ms commit=" + number(furnace.commitNanos() / 1e6)
                + "ms resourceReservationsIssued=" + resources.issuedLeases()
                + "; activeTickets/activeResourceReservations/cancellations/commitWait/lockContention=N/A (no constant-time counters)");
        return 1;
    }

    private static void warnings(CommandSourceStack source) {
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return;
        final var regions = engine.runtimeRegionSnapshots();
        final var pool = engine.workerRuntimeSnapshot();
        final var jvm = RuntimeObservability.jvmSample(source.getServer());
        if (regions.stream().anyMatch(region -> region.ticks().hasSamples() && region.ticks().msptLast() >= ForgiaConfig.SERVER.runtimeHighRegionMspt.get()))
            line(source, "HIGH_REGION_MSPT: a measured leased-group tick exceeds runtimeHighRegionMspt.");
        if (jvm.workerCpuPercentOfOneCore() / pool.configured() >= ForgiaConfig.SERVER.runtimeWorkerSaturationPercent.get())
            line(source, "WORKER_SATURATION: sampled worker CPU exceeds configured threshold; includes scheduler/spin work.");
        if (pool.queued() >= (long) pool.configured() * ForgiaConfig.SERVER.runtimeQueuePerWorker.get())
            line(source, "QUEUE_CONGESTION: pending tasks exceed runtimeQueuePerWorker at this snapshot.");
        if (jvm.mainCpuPercent() >= ForgiaConfig.SERVER.runtimeMainCpuPercent.get()
                && RuntimeObservability.history(source.getServer(), 10).averageMs() >= ForgiaConfig.SERVER.runtimeHighServerMspt.get())
            line(source, "MAIN_THREAD_BOTTLENECK: high sampled main CPU coincides with high mean server MSPT at the configured thresholds; this does not identify a root cause.");
        final var diagnostics = engine.diagnostics();
        final long fallback = diagnostics.fallbackEntityTicks() + diagnostics.fallbackBlockEntityTicks();
        final long total = fallback + diagnostics.entityTicksOnWorkers() + diagnostics.blockEntityTicksOnWorkers();
        if (total >= 100 && fallback * 100.0 / total >= ForgiaConfig.SERVER.runtimeSerialFallbackPercent.get())
            line(source, "HIGH_SERIAL_FALLBACK: lifetime entity/BE fallback ratio exceeds configured threshold; conservative mod fallback is expected.");
        final double avg = regions.stream().filter(region -> region.ticks().hasSamples()).mapToDouble(region -> region.ticks().msptAvg()).average().orElse(0);
        final double max = regions.stream().mapToDouble(region -> region.ticks().msptAvg()).max().orElse(0);
        if (avg > 0 && max >= 1 && max / avg >= ForgiaConfig.SERVER.runtimeRegionImbalanceRatio.get())
            line(source, "REGION_LOAD_IMBALANCE: measured mean region work is uneven; inspect /forgia region top avg.");
    }

    private static void jsonNumber(com.google.gson.JsonObject output, String key, double value) {
        if (Double.isFinite(value)) output.addProperty(key, value);
        else output.add(key, com.google.gson.JsonNull.INSTANCE);
    }

    private static String measured(RuntimeRegionSnapshot region, double value) {
        return region.ticks().hasSamples() ? number(value) : "N/A";
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "N/A";
    }

    private static String count(long value) {
        return value < 0 ? "N/A" : Long.toString(value);
    }

    private static void line(CommandSourceStack source, String line) {
        source.sendSuccess(() -> Component.literal(line), false);
    }

    private static int noEngine(CommandSourceStack source) {
        line(source, "Region threading is not installed; no region or worker measurements. Use /forgia performance for native server ticks.");
        return 0;
    }
}
