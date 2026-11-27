/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.mobspawncontroller;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Conservative parallel admission; world-dependent configurations keep the original complete serial body. */
@Mod(MobSpawnControllerAdapter.MOD_ID)
public final class MobSpawnControllerAdapter {
    public static final String MOD_ID = "forgia_mobspawncontroller";
    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Explicit override property:
     * - {@code -Dforgia.mscOrderedSpawning=true}: force enable ordered natural spawning claim
     * - {@code -Dforgia.mscOrderedSpawning=false}: force disable claim
     * - unset: automatically claim when Forgia's async region spawning ({@code enableAsyncRegionSpawning}
     *   + {@code enableIndependentRegionTickClock}) is enabled in {@code config/forgia.toml}, so servers
     *   do not need JVM flags to unlock non-blocking async ordered natural spawning.
     */
    private static final String ORDERED_OPT_IN = "forgia.mscOrderedSpawning";

    public MobSpawnControllerAdapter() {
        NeoForge.EVENT_BUS.addListener(MobSpawnControllerAdapter::register);
        NeoForge.EVENT_BUS.addListener(MobSpawnControllerAdapter::stopped);
    }

    private static boolean shouldClaimNaturalSpawning() {
        final String explicit = System.getProperty(ORDERED_OPT_IN);
        if (explicit != null) {
            return Boolean.parseBoolean(explicit);
        }
        final boolean independentClock = ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableIndependentRegionTickClock.get()
                : ForgiaConfig.SERVER.enableIndependentRegionTickClock.getDefault();
        final boolean asyncSpawning = ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncRegionSpawning.get()
                : ForgiaConfig.SERVER.enableAsyncRegionSpawning.getDefault();
        return independentClock && asyncSpawning;
    }

    private static void register(RegisterParallelAdaptersEvent event) {
        event.registrar().register(ParallelApi.MAJOR_VERSION, RuleSnapshots.KEY, new ParallelApi.NaturalSpawnPreparation<RuleSnapshots.Snapshot>() {
            @Override
            public ParallelApi.Preparation<RuleSnapshots.Snapshot> preparePlan(ParallelApi.SpawnPlan plan) {
                return RuleSnapshots.prepareRules();
            }

            @Override
            public ParallelApi.Preparation<RuleSnapshots.Snapshot> prepare(ParallelApi.SpawnUnit unit) {
                return RuleSnapshots.prepare(unit);
            }
        });
        if (shouldClaimNaturalSpawning()) {
            LOGGER.info("Claiming MobSpawnController natural spawning for Forgia async/ordered region workers");
            event.registrar().claimNaturalSpawning(RuleSnapshots.KEY, "mobspawncontroller", RuleSnapshots::verify);
        }
    }

    private static void stopped(ServerStoppedEvent event) {
        RuleSnapshots.mutate(() -> {});
    }
}
