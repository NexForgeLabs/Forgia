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

    public static int sandboxEntityProximityLockstepRadius() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxEntityProximityLockstepRadius.get()
                : ForgiaConfig.SERVER.sandboxEntityProximityLockstepRadius.getDefault();
    }

    public static int sandboxSideEffectEscalationTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxSideEffectEscalationTicks.get()
                : ForgiaConfig.SERVER.sandboxSideEffectEscalationTicks.getDefault();
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

    public static boolean unifiedRegionTickLoopEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.unifiedRegionTickLoop");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableUnifiedRegionTickLoop.get()
                : ForgiaConfig.SERVER.enableUnifiedRegionTickLoop.getDefault();
    }

    public static boolean singleRegionContraptionWorkersEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.singleRegionContraptionWorkers");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableSingleRegionContraptionWorkers.get()
                : ForgiaConfig.SERVER.enableSingleRegionContraptionWorkers.getDefault();
    }

    public static int unifiedRegionEntityContainmentMarginBlocks() {
        final String prop = System.getProperty("neoforge.regionthreading.unifiedRegionEntityContainmentMarginBlocks");
        if (prop != null) {
            try {
                return Math.max(1, Math.min(32, Integer.parseInt(prop.trim())));
            } catch (NumberFormatException ignored) {
            }
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.unifiedRegionEntityContainmentMarginBlocks.get()
                : ForgiaConfig.SERVER.unifiedRegionEntityContainmentMarginBlocks.getDefault();
    }

    public static int sandboxMaxInstanceBackoffTicks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxMaxInstanceBackoffTicks.get()
                : ForgiaConfig.SERVER.sandboxMaxInstanceBackoffTicks.getDefault();
    }

    public static int sandboxHopperPlayerSafeRadiusBlocks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.sandboxHopperPlayerSafeRadiusBlocks.get()
                : ForgiaConfig.SERVER.sandboxHopperPlayerSafeRadiusBlocks.getDefault();
    }

    public static int autosaveMaxQuiesceWaitMillis() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.autosaveMaxQuiesceWaitMillis.get()
                : ForgiaConfig.SERVER.autosaveMaxQuiesceWaitMillis.getDefault();
    }

    public static long blockEventsPerRegionBudgetNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.blockEventsPerRegionBudgetNanos.get()
                : ForgiaConfig.SERVER.blockEventsPerRegionBudgetNanos.getDefault();
    }

    public static int maxSyncNavigationRecomputesPerTick() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.maxSyncNavigationRecomputesPerTick.get()
                : ForgiaConfig.SERVER.maxSyncNavigationRecomputesPerTick.getDefault();
    }

    public static boolean asyncInlandBlockScheduledTicksEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.asyncInlandBlockScheduledTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncInlandBlockScheduledTicks.get()
                : ForgiaConfig.SERVER.enableAsyncInlandBlockScheduledTicks.getDefault();
    }

    public static int inlandBlockScheduledTickMarginBlocks() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.inlandBlockScheduledTickMarginBlocks.get()
                : ForgiaConfig.SERVER.inlandBlockScheduledTickMarginBlocks.getDefault();
    }

    public static long blockScheduledTickSerialBudgetNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.blockScheduledTickSerialBudgetNanos.get()
                : ForgiaConfig.SERVER.blockScheduledTickSerialBudgetNanos.getDefault();
    }

    public static boolean slimeWorkerTicksEnabled() {
        final String prop = System.getProperty("neoforge.regionthreading.slimeWorkerTicks");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableSlimeWorkerTicks.get()
                : ForgiaConfig.SERVER.enableSlimeWorkerTicks.getDefault();
    }

    public static boolean highDensityEntityTrackerThrottleEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableHighDensityEntityTrackerThrottle.get()
                : ForgiaConfig.SERVER.enableHighDensityEntityTrackerThrottle.getDefault();
    }

    public static int entityTrackerDenseChunkThreshold() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.entityTrackerDenseChunkThreshold.get()
                : ForgiaConfig.SERVER.entityTrackerDenseChunkThreshold.getDefault();
    }

    public static int entityTrackerSevereChunkThreshold() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.entityTrackerSevereChunkThreshold.get()
                : ForgiaConfig.SERVER.entityTrackerSevereChunkThreshold.getDefault();
    }

    public static boolean fastChunkPaletteSerializationEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableFastChunkPaletteSerialization.get()
                : ForgiaConfig.SERVER.enableFastChunkPaletteSerialization.getDefault();
    }

    public static boolean asyncChunkLoadDeserializeEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncChunkLoadDeserialize.get()
                : ForgiaConfig.SERVER.enableAsyncChunkLoadDeserialize.getDefault();
    }

    public static long chunkUnloadTickBudgetNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.chunkUnloadTickBudgetNanos.get()
                : ForgiaConfig.SERVER.chunkUnloadTickBudgetNanos.getDefault();
    }

    public static boolean chunkUnloadSpinGuardEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableChunkUnloadSpinGuard.get()
                : ForgiaConfig.SERVER.enableChunkUnloadSpinGuard.getDefault();
    }

    public static int chunkUnloadBacklogMaxPerTick() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.chunkUnloadBacklogMaxPerTick.get()
                : ForgiaConfig.SERVER.chunkUnloadBacklogMaxPerTick.getDefault();
    }

    public static long incrementalAutosaveBudgetNanos() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.incrementalAutosaveBudgetNanos.get()
                : ForgiaConfig.SERVER.incrementalAutosaveBudgetNanos.getDefault();
    }

    public static boolean asyncPlayerDataSaveEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncPlayerDataSave.get()
                : ForgiaConfig.SERVER.enableAsyncPlayerDataSave.getDefault();
    }

    public static boolean parallelWorldGenEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableParallelWorldGen.get()
                : ForgiaConfig.SERVER.enableParallelWorldGen.getDefault();
    }

    public static int parallelWorldGenThreads() {
        int configured = ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.parallelWorldGenThreads.get()
                : ForgiaConfig.SERVER.parallelWorldGenThreads.getDefault();
        if (configured > 0) {
            return configured;
        }
        int cpus = Runtime.getRuntime().availableProcessors();
        return Math.max(2, Math.min(8, cpus / 2));
    }

    public static boolean nonBlockingStructureLookupsEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableNonBlockingStructureLookups.get()
                : ForgiaConfig.SERVER.enableNonBlockingStructureLookups.getDefault();
    }

    public static boolean asyncAdvancementSaveEnabled() {
        return ForgiaConfig.isServerConfigLoaded()
                ? ForgiaConfig.SERVER.enableAsyncAdvancementSave.get()
                : ForgiaConfig.SERVER.enableAsyncAdvancementSave.getDefault();
    }
}
