/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ForgiaConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records deduplicated Region Sandbox demotions and boundary-violation stack traces to
 * {@code logs/forgia-sandbox-demotions.jsonl} asynchronously so workers never block on disk I/O.
 */
public final class SandboxDemotionProbe {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxDemotionProbe.class);
    private static final Path DEFAULT_LOG_PATH = Path.of("logs", "forgia-sandbox-demotions.jsonl");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_UNIQUE_SIGNATURES = 256;
    private static final int MAX_STACK_FRAMES = 14;

    private static final Set<String> RECORDED_SIGNATURES = ConcurrentHashMap.newKeySet();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "forgia-sandbox-demotion-probe");
        t.setDaemon(true);
        return t;
    });

    private SandboxDemotionProbe() {}

    public static void record(
            ServerLevel level,
            BlockPos pos,
            String blockEntityType,
            String category,
            boolean pinnedOrDemoted,
            Throwable throwable) {
        if (!isEnabled()) {
            return;
        }
        final List<String> frames = extractFrames(throwable);
        final String topFrame = frames.isEmpty() ? "<no-stack>" : frames.get(0);
        final String signature = blockEntityType + "|" + category + "|" + pinnedOrDemoted + "|" + topFrame;
        if (RECORDED_SIGNATURES.size() >= MAX_UNIQUE_SIGNATURES || !RECORDED_SIGNATURES.add(signature)) {
            return;
        }
        final Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("type", blockEntityType);
        entry.put("dimension", level.dimension().location().toString());
        entry.put("pos", List.of(pos.getX(), pos.getY(), pos.getZ()));
        entry.put("category", category);
        entry.put("pinnedOrDemoted", pinnedOrDemoted);
        entry.put("exception", throwable.getClass().getName() + (throwable.getMessage() != null ? ": " + throwable.getMessage() : ""));
        entry.put("stack", frames);
        final String line = GSON.toJson(entry) + System.lineSeparator();
        WRITER.execute(() -> writeLine(DEFAULT_LOG_PATH, line));
    }

    private static boolean isEnabled() {
        try {
            return ForgiaConfig.SERVER_SPEC.isLoaded() && ForgiaConfig.SERVER.enableSandboxDemotionProbe.get();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static List<String> extractFrames(Throwable throwable) {
        final List<String> frames = new ArrayList<>(MAX_STACK_FRAMES);
        for (final StackTraceElement element : throwable.getStackTrace()) {
            final String cls = element.getClassName();
            if (cls.startsWith("net.neoforged.neoforge.server.threading.region.RegionOwnershipViolationHandler")
                    || cls.startsWith("net.neoforged.neoforge.server.threading.region.RegionWorkerBoundaryException")) {
                continue;
            }
            frames.add(cls + "#" + element.getMethodName() + ":" + element.getLineNumber());
            if (frames.size() >= MAX_STACK_FRAMES) {
                break;
            }
        }
        return frames;
    }

    private static void writeLine(@Nullable Path path, String line) {
        if (path == null) {
            return;
        }
        try {
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.debug("Failed to append sandbox demotion probe entry to {}: {}", path, e.getMessage());
        }
    }

    public static void resetForTesting() {
        RECORDED_SIGNATURES.clear();
    }
}
