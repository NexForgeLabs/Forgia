/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.ext.rayonguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RayonGuardTest {
    @Test
    void configParseAndAutoResolve() {
        final RayonGuardConfig parsed = RayonGuardConfig.parse(List.of(
                "# comment",
                "enabled = true",
                "rayonNumThreads = 0 # auto",
                "overwriteExisting = true"));
        assertTrue(parsed.enabled());
        assertEquals(0, parsed.rayonNumThreads());
        assertTrue(parsed.overwriteExisting());
        assertEquals(4, parsed.resolveEffectiveThreads(10));
        assertEquals(2, parsed.resolveEffectiveThreads(4));
        assertEquals(1, parsed.resolveEffectiveThreads(1));
    }

    @Test
    void nativeEnvSetsAndHonorsOverwriteFlag() {
        final String key = "FORGIA_RAYON_GUARD_TEST_VAR";
        final NativeEnv.ApplyResult first = NativeEnv.setNativeEnv(key, "4", true);
        assertTrue(first.applied(), first.detail());
        assertEquals("4", NativeEnv.getNativeEnv(key));

        final NativeEnv.ApplyResult kept = NativeEnv.setNativeEnv(key, "8", false);
        assertFalse(kept.applied());
        assertEquals("4", NativeEnv.getNativeEnv(key));

        final NativeEnv.ApplyResult overwritten = NativeEnv.setNativeEnv(key, "2", true);
        assertTrue(overwritten.applied(), overwritten.detail());
        assertEquals("2", NativeEnv.getNativeEnv(key));
    }

    @Test
    void loadOrCreateWritesDefaultConfigAndApplies(@TempDir Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve(RayonGuardConfig.FILE_NAME);
        assertFalse(Files.exists(configFile));
        final RayonGuardConfig cfg = RayonGuardConfig.loadOrCreate(tempDir);
        assertTrue(Files.exists(configFile));
        assertTrue(cfg.enabled());
        assertEquals(4, cfg.rayonNumThreads());
        assertFalse(cfg.overwriteExisting());
    }
}
