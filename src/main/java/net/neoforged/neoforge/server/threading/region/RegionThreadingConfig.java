/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.common.NeoForgeConfig;

/**
 * Reads region threading settings, using the declared defaults until the server config is loaded.
 */
public final class RegionThreadingConfig {
    private RegionThreadingConfig() {}

    static boolean playerTicksRequested() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingPlayerTicks.get()
                : ForgiaConfig.SERVER.enableRegionThreadingPlayerTicks.getDefault();
    }

    static int maxImmediateTasksPerDrain() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.get()
                : ForgiaConfig.SERVER.regionThreadingMaxImmediateTasksPerDrain.getDefault();
    }

    static int maxScheduledTasksPerDrain() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.get()
                : ForgiaConfig.SERVER.regionThreadingMaxScheduledTasksPerDrain.getDefault();
    }

    static long longTickWarningNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingLongTickWarningNanos.get()
                : ForgiaConfig.SERVER.regionThreadingLongTickWarningNanos.getDefault();
    }

    /**
     * {@return true when a level created now partitions its POI storage per region}
     * <p>
     * The dedicated switch is off by default, and enabling the entity worker slice implies the storage: a
     * worker tick of a villager, iron golem, raider, bee, zombie or fox reaches the level's POI manager, and
     * the regionized storage is what makes that tick safe next to the server thread. A configuration that
     * enables entity worker ticks therefore gets the storage even with the dedicated switch off, instead of
     * silently running those ticks against the one shared vanilla manager.
     */
    static boolean poiStorageEnabled() {
        if (!ForgiaConfig.isServerConfigLoaded()) {
            return false;
        }
        return ForgiaConfig.SERVER.enableRegionThreadingPoi.get() || ForgiaConfig.SERVER.enableRegionThreadingEntityTicks.get();
    }

    /**
     * {@return true when a region context captures the neighbor updates that target another region}
     * <p>
     * The switch is off by default. When it is off the region context uses the vanilla collecting updater and
     * the level write paths keep their vanilla behavior, so the feature costs nothing on a server that does not
     * enable it.
     */
    static boolean borderUpdatesEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.get()
                : ForgiaConfig.SERVER.enableRegionThreadingBorderUpdates.getDefault();
    }

    /**
     * {@return true when a region context captures the game events whose listeners belong to another region}
     * <p>
     * The switch is off by default. When it is off the dispatch is exactly vanilla: the posting worker visits
     * every listener of the event's radius, including the listeners of other regions, and therefore writes a
     * foreign block entity while its owner may tick it - the documented racy baseline.
     */
    static boolean crossRegionGameEventsEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingGameEvents.get()
                : ForgiaConfig.SERVER.enableRegionThreadingGameEvents.getDefault();
    }

    static NeoForgeConfig.RegionThreadingOwnershipViolationMode ownershipViolationMode() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.get()
                : ForgiaConfig.SERVER.regionThreadingOwnershipViolationMode.getDefault();
    }

    static boolean callerRunsEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingCallerRuns.get()
                : ForgiaConfig.SERVER.enableRegionThreadingCallerRuns.getDefault();
    }

    static boolean parallelBorderFixpointEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingParallelBorderFixpoint.get()
                : ForgiaConfig.SERVER.enableRegionThreadingParallelBorderFixpoint.getDefault();
    }

    static boolean canonicalFenceTailEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableCanonicalFenceTail.get()
                : ForgiaConfig.SERVER.enableCanonicalFenceTail.getDefault();
    }

    static boolean fusedSlicesEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingFusedSlices.get()
                : ForgiaConfig.SERVER.enableRegionThreadingFusedSlices.getDefault();
    }

    static boolean independentRegionTickClockEnabled() {
        if (Boolean.getBoolean("neoforge.regionthreading.independentRegionTick")) {
            return true;
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableIndependentRegionTickClock.get()
                : ForgiaConfig.SERVER.enableIndependentRegionTickClock.getDefault();
    }

    static long independentRegionTickIntervalNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.independentRegionTickIntervalNanos.get()
                : ForgiaConfig.SERVER.independentRegionTickIntervalNanos.getDefault();
    }

    static int independentRegionMaxCatchUpTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.independentRegionMaxCatchUpTicks.get()
                : ForgiaConfig.SERVER.independentRegionMaxCatchUpTicks.getDefault();
    }

    public static boolean regionSandboxAllBlockEntitiesEnabled() {
        if (Boolean.getBoolean("neoforge.regionthreading.sandboxAllBlockEntities")) {
            return true;
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionSandboxAllBlockEntities.get()
                : ForgiaConfig.SERVER.enableRegionSandboxAllBlockEntities.getDefault();
    }

    public static boolean fluidTickWorkerExecutionEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.fluidTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableRegionThreadingFluidTicks.get()
                : ForgiaConfig.SERVER.enableRegionThreadingFluidTicks.getDefault();
    }

    public static long entityTickThrottleModerateNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.entityTickThrottleModerateNanos.get()
                : ForgiaConfig.SERVER.entityTickThrottleModerateNanos.getDefault();
    }

    public static long entityTickThrottleSevereNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.entityTickThrottleSevereNanos.get()
                : ForgiaConfig.SERVER.entityTickThrottleSevereNanos.getDefault();
    }

    public static long hotRegionEntityThresholdNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.hotRegionEntityThresholdNanos.get()
                : ForgiaConfig.SERVER.hotRegionEntityThresholdNanos.getDefault();
    }

    public static int hotRegionMobPathfindingCooldownTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.hotRegionMobPathfindingCooldownTicks.get()
                : ForgiaConfig.SERVER.hotRegionMobPathfindingCooldownTicks.getDefault();
    }

    public static int hotRegionCombatMobPathfindingCooldownTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.hotRegionCombatMobPathfindingCooldownTicks.get()
                : ForgiaConfig.SERVER.hotRegionCombatMobPathfindingCooldownTicks.getDefault();
    }

    public static long slowPathfindingLearnThresholdNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.slowPathfindingLearnThresholdNanos.get()
                : ForgiaConfig.SERVER.slowPathfindingLearnThresholdNanos.getDefault();
    }

    public static long severePathfindingLearnThresholdNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.severePathfindingLearnThresholdNanos.get()
                : ForgiaConfig.SERVER.severePathfindingLearnThresholdNanos.getDefault();
    }

    public static int sandboxTransientCooldownTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxTransientCooldownTicks.get()
                : ForgiaConfig.SERVER.sandboxTransientCooldownTicks.getDefault();
    }

    public static int sandboxPermanentDemotionThreshold() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxPermanentDemotionThreshold.get()
                : ForgiaConfig.SERVER.sandboxPermanentDemotionThreshold.getDefault();
    }

    public static boolean asyncRegionSpawningEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.asyncSpawning");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncRegionSpawning.get()
                : ForgiaConfig.SERVER.enableAsyncRegionSpawning.getDefault();
    }

    public static boolean skipUnloadedNeighborhoodSpawning() {
        final String prop = System.getProperty("neoforge.regionthreading.skipUnloadedNeighborhoodSpawning");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.skipUnloadedNeighborhoodSpawning.get()
                : ForgiaConfig.SERVER.skipUnloadedNeighborhoodSpawning.getDefault();
    }

    public static boolean asyncRegionEntityTicksEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.asyncEntityTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncRegionEntityTicks.get()
                : ForgiaConfig.SERVER.enableAsyncRegionEntityTicks.getDefault();
    }

    public static boolean selectiveAsyncEntityTicksEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.selectiveAsyncEntityTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                && ForgiaConfig.SERVER.enableSelectiveAsyncEntityTicks.get();
    }

    public static boolean asyncRegionFluidTicksEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.asyncFluidTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncRegionFluidTicks.get()
                : ForgiaConfig.SERVER.enableAsyncRegionFluidTicks.getDefault();
    }

    public static long independentRegionCommitDrainBudgetNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.independentRegionCommitDrainBudgetNanos.get()
                : ForgiaConfig.SERVER.independentRegionCommitDrainBudgetNanos.getDefault();
    }
}
