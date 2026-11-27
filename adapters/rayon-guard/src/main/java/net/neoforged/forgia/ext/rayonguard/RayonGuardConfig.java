/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.ext.rayonguard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight TOML config for {@link ForgiaRayonGuard}.
 * <p>
 * Parsed directly during mod construction so {@code RAYON_NUM_THREADS} is applied before FML's config
 * phase or any native library (such as Sable's Rapier3D physics engine) initializes a Rayon thread pool.
 */
public final class RayonGuardConfig {
    public static final String FILE_NAME = "forgia-rayon-guard.toml";
    private static final Logger LOGGER = LoggerFactory.getLogger("ForgiaRayonGuard");

    private final boolean enabled;
    private final int rayonNumThreads;
    private final boolean overwriteExisting;

    public RayonGuardConfig(boolean enabled, int rayonNumThreads, boolean overwriteExisting) {
        this.enabled = enabled;
        this.rayonNumThreads = rayonNumThreads;
        this.overwriteExisting = overwriteExisting;
    }

    public static RayonGuardConfig defaults() {
        return new RayonGuardConfig(true, 4, false);
    }

    public boolean enabled() {
        return this.enabled;
    }

    /**
     * Configured thread count. {@code 0} means auto-compute from JVM available processors (capped at 4).
     */
    public int rayonNumThreads() {
        return this.rayonNumThreads;
    }

    public boolean overwriteExisting() {
        return this.overwriteExisting;
    }

    public int resolveEffectiveThreads(int availableProcessors) {
        if (this.rayonNumThreads > 0) {
            return this.rayonNumThreads;
        }
        return Math.max(1, Math.min(4, availableProcessors / 2));
    }

    public static RayonGuardConfig loadOrCreate(Path configDir) {
        final Path file = configDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeDefault(file);
            return defaults();
        }
        try {
            return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            LOGGER.warn("Failed to read {}; using defaults: {}", file, ex.toString());
            return defaults();
        }
    }

    static RayonGuardConfig parse(List<String> lines) {
        final RayonGuardConfig def = defaults();
        boolean enabled = def.enabled;
        int rayonNumThreads = def.rayonNumThreads;
        boolean overwriteExisting = def.overwriteExisting;

        for (final String raw : lines) {
            final int comment = raw.indexOf('#');
            final String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty() || line.startsWith("[")) {
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String value = stripQuotes(line.substring(eq + 1).trim());
            switch (key) {
                case "enabled" -> enabled = Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
                case "rayonNumThreads" -> {
                    try {
                        final int parsed = Integer.parseInt(value);
                        if (parsed >= 0 && parsed <= 256) {
                            rayonNumThreads = parsed;
                        } else {
                            LOGGER.warn("Ignoring out-of-range rayonNumThreads={} (expected 0..256)", parsed);
                        }
                    } catch (NumberFormatException ex) {
                        LOGGER.warn("Ignoring invalid rayonNumThreads='{}'", value);
                    }
                }
                case "overwriteExisting" -> overwriteExisting = Boolean.parseBoolean(value.toLowerCase(Locale.ROOT));
                default -> {}
            }
        }
        return new RayonGuardConfig(enabled, rayonNumThreads, overwriteExisting);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void writeDefault(Path file) {
        final String content = """
                # Forgia Rayon Guard configuration
                # Bounds native Rust Rayon thread pools (RAYON_NUM_THREADS) before native mod libraries initialize.
                # Changes take effect on the next server/client restart.

                # Master switch for setting RAYON_NUM_THREADS on startup.
                enabled = true

                # Target native Rayon worker thread count.
                # 0 = automatic: clamp(availableProcessors / 2, 1, 4).
                # Explicit positive values (e.g. 1, 2, 4) set RAYON_NUM_THREADS directly.
                rayonNumThreads = 4

                # If true, overwrite an existing RAYON_NUM_THREADS environment variable.
                # If false (recommended), honor any value already set by the host/panel/container.
                overwriteExisting = false
                """;
        try {
            final Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("Failed to write default config {}: {}", file, ex.toString());
        }
    }
}
