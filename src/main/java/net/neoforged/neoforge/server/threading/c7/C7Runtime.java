/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;

/** One selection at the canonical Level entry, before either independent execution path is entered. */
public final class C7Runtime {
    private C7Runtime() {}

    public static BlockEntityExecutionSystem system() {
        return ForgiaConfig.isServerConfigLoaded() ? ForgiaConfig.SERVER.blockEntityExecutionSystem.get() : BlockEntityExecutionSystem.C6;
    }

    public static boolean enabled() {
        return ForgiaConfig.isServerConfigLoaded() && ForgiaConfig.SERVER.enableC7.get();
    }

    public static boolean tick(ServerLevel level, RegionTickBarrier barrier) {
        if (!enabled()) return false;
        if (!(RegionThreadingManager.currentOrNull() instanceof RegionThreadingEngine engine)) return false;
        final var config = ForgiaConfig.SERVER;
        final var options = new C7Options(config.c7ExecutionMode.get(), config.c7Workers.get(), config.c7MaxWaveSize.get(),
                config.c7MaxWaves.get(), config.c7MaxTasks.get(), config.c7MaxResources.get(), config.c7StragglerNanos.get(),
                config.c7Debug.get(), config.c7TrackingDiagnostics.get(), config.c7Conservative.get());
        return engine.tickC7BlockEntities(level, barrier, options);
    }
}
