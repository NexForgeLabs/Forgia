/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.ext.rayonguard;

import java.nio.file.Path;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional Forgia extension that configures native {@code RAYON_NUM_THREADS} during early mod construction,
 * before native physics/Rust libraries initialize their global Rayon thread pool.
 */
@Mod(ForgiaRayonGuard.MOD_ID)
public final class ForgiaRayonGuard {
    public static final String MOD_ID = "forgia_rayon_guard";
    public static final String ENV_RAYON_NUM_THREADS = "RAYON_NUM_THREADS";
    private static final Logger LOGGER = LoggerFactory.getLogger("ForgiaRayonGuard");

    public ForgiaRayonGuard() {
        applyFromConfigDir(FMLPaths.CONFIGDIR.get());
    }

    static NativeEnv.ApplyResult applyFromConfigDir(Path configDir) {
        final RayonGuardConfig config = RayonGuardConfig.loadOrCreate(configDir);
        if (!config.enabled()) {
            LOGGER.info("Disabled by config ({}); leaving {} unchanged.",
                    RayonGuardConfig.FILE_NAME, ENV_RAYON_NUM_THREADS);
            return new NativeEnv.ApplyResult(false, NativeEnv.getNativeEnv(ENV_RAYON_NUM_THREADS), "", "disabled");
        }
        final int jvmProcessors = Runtime.getRuntime().availableProcessors();
        final int targetThreads = config.resolveEffectiveThreads(jvmProcessors);
        final NativeEnv.ApplyResult result = NativeEnv.setNativeEnv(
                ENV_RAYON_NUM_THREADS, Integer.toString(targetThreads), config.overwriteExisting());
        if (result.applied()) {
            LOGGER.info("Set native {}={} (previous={}, jvmProcessors={}, config={})",
                    ENV_RAYON_NUM_THREADS,
                    result.effectiveValue(),
                    result.previousValue() == null ? "<unset>" : result.previousValue(),
                    jvmProcessors,
                    RayonGuardConfig.FILE_NAME);
        } else {
            LOGGER.info("Did not modify native {}: {} (effective={}, jvmProcessors={})",
                    ENV_RAYON_NUM_THREADS,
                    result.detail(),
                    result.effectiveValue().isEmpty() ? "<unset>" : result.effectiveValue(),
                    jvmProcessors);
        }
        return result;
    }
}
