/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.concurrent.atomic.AtomicLong;

/**
 * On-demand grouped observations of existing runtime counters, never a second metrics store.
 * <p>
 * Source groups borrow the original counter identities once at engine construction. Only snapshot calls
 * read them; no counter, task, lease, world reference or executor is created or mutated here. Phase state
 * is traversed for that call only, and dispatch input is the pool's existing immutable value snapshot.
 */
final class RegionDiagnosticsCollector {
    record BorderSources(
            AtomicLong borderUpdatesRecorded,
            AtomicLong borderUpdatesApplied,
            AtomicLong borderUpdatesDelayed,
            AtomicLong borderUpdatesDropped,
            AtomicLong borderUpdatePasses,
            AtomicLong borderUpdateRegionsRerun,
            AtomicLong borderUpdateWorstPasses,
            AtomicLong borderUpdatesOverflowed,
            AtomicLong foreignChunkReads,
            AtomicLong readOfUnownedChunk,
            AtomicLong readOfVersionlessChunk,
            AtomicLong foreignReadInvalidatedPasses,
            AtomicLong foreignReadRegionReruns,
            AtomicLong foreignReadSeedsDropped,
            AtomicLong borderBlockWritesRecorded,
            AtomicLong borderBlockWritesApplied,
            AtomicLong borderBlockWritesOverflowed,
            AtomicLong borderBlockWritesDropped,
            AtomicLong readAfterDeferredBlockWrite) {}

    record BlockEventSources(
            AtomicLong blockEventPhases,
            AtomicLong blockEventPhaseLeases,
            AtomicLong blockEventPhaseReleases,
            AtomicLong blockEventPhaseEvents,
            AtomicLong blockEventPhaseFixpoints,
            AtomicLong blockEventPhaseLeaseFallbacks,
            AtomicLong blockEventPhaseNanos,
            AtomicLong blockEventPhaseMaxNanos) {}

    record GameEventSources(
            AtomicLong gameEventsPosted,
            AtomicLong gameEventSectionsLocal,
            AtomicLong gameEventSectionsDeferred,
            AtomicLong gameEventSectionsDelivered,
            AtomicLong gameEventSectionsRefused,
            AtomicLong gameEventSectionsDropped,
            AtomicLong gameEventSectionsDroppedNoRegion,
            AtomicLong gameEventSectionsDroppedNoChunk,
            AtomicLong gameEventDeliveryPasses) {}

    record SaveSources(
            AtomicLong levelSaves,
            AtomicLong levelSavesWithBatchInFlight,
            AtomicLong levelSavesWithTickDeferredWork,
            AtomicLong levelSavesWithCrossTickDeferredWork,
            AtomicLong levelSaveCrossTickDeferredEntries,
            AtomicLong levelSaveDirtyPoiColumns,
            AtomicLong levelSaveFlushedPoiColumns) {}

    private final BorderSources border;
    private final BlockEventSources blockEvents;
    private final GameEventSources gameEvents;
    private final SaveSources saves;
    private final AtomicLong[] applied;
    private final AtomicLong[] dropped;
    private final AtomicLong[] refused;
    private final AtomicLong[] failures;

    RegionDiagnosticsCollector(BorderSources border, BlockEventSources blockEvents, GameEventSources gameEvents, SaveSources saves,
            AtomicLong[] applied, AtomicLong[] dropped, AtomicLong[] refused, AtomicLong[] failures) {
        this.border = border;
        this.blockEvents = blockEvents;
        this.gameEvents = gameEvents;
        this.saves = saves;
        this.applied = applied;
        this.dropped = dropped;
        this.refused = refused;
        this.failures = failures;
    }

    RegionThreadingDiagnostics.BorderUpdateCounters borderUpdates() {
        return new RegionThreadingDiagnostics.BorderUpdateCounters(
                this.border.borderUpdatesRecorded().get(),
                this.border.borderUpdatesApplied().get(),
                this.border.borderUpdatesDelayed().get(),
                this.border.borderUpdatesDropped().get(),
                this.border.borderUpdatePasses().get(),
                this.border.borderUpdateRegionsRerun().get(),
                this.border.borderUpdateWorstPasses().get(),
                this.border.borderUpdatesOverflowed().get(),
                this.border.foreignChunkReads().get(),
                this.border.readOfUnownedChunk().get(),
                this.border.readOfVersionlessChunk().get(),
                this.border.foreignReadInvalidatedPasses().get(),
                this.border.foreignReadRegionReruns().get(),
                this.border.foreignReadSeedsDropped().get(),
                this.border.borderBlockWritesRecorded().get(),
                this.border.borderBlockWritesApplied().get(),
                this.border.borderBlockWritesOverflowed().get(),
                this.border.borderBlockWritesDropped().get(),
                this.border.readAfterDeferredBlockWrite().get());
    }

    RegionThreadingDiagnostics.BlockEventPhaseCounters blockEvents() {
        return new RegionThreadingDiagnostics.BlockEventPhaseCounters(
                this.blockEvents.blockEventPhases().get(),
                this.blockEvents.blockEventPhaseLeases().get(),
                this.blockEvents.blockEventPhaseReleases().get(),
                this.blockEvents.blockEventPhaseEvents().get(),
                this.blockEvents.blockEventPhaseFixpoints().get(),
                this.blockEvents.blockEventPhaseLeaseFallbacks().get(),
                this.blockEvents.blockEventPhaseNanos().get(),
                this.blockEvents.blockEventPhaseMaxNanos().get());
    }

    RegionThreadingDiagnostics.GameEventCounters gameEvents() {
        return new RegionThreadingDiagnostics.GameEventCounters(
                this.gameEvents.gameEventsPosted().get(),
                this.gameEvents.gameEventSectionsLocal().get(),
                this.gameEvents.gameEventSectionsDeferred().get(),
                this.gameEvents.gameEventSectionsDelivered().get(),
                this.gameEvents.gameEventSectionsRefused().get(),
                this.gameEvents.gameEventSectionsDropped().get(),
                this.gameEvents.gameEventSectionsDroppedNoRegion().get(),
                this.gameEvents.gameEventSectionsDroppedNoChunk().get(),
                this.gameEvents.gameEventDeliveryPasses().get());
    }

    RegionThreadingDiagnostics.SaveCounters saves() {
        return new RegionThreadingDiagnostics.SaveCounters(
                this.saves.levelSaves().get(),
                this.saves.levelSavesWithBatchInFlight().get(),
                this.saves.levelSavesWithTickDeferredWork().get(),
                this.saves.levelSavesWithCrossTickDeferredWork().get(),
                this.saves.levelSaveCrossTickDeferredEntries().get(),
                this.saves.levelSaveDirtyPoiColumns().get(),
                this.saves.levelSaveFlushedPoiColumns().get());
    }

    RegionThreadingDiagnostics.BlockUpdateCounters blockUpdates() {
        return new RegionThreadingDiagnostics.BlockUpdateCounters(values(this.applied), values(this.dropped), values(this.refused));
    }

    RegionThreadingDiagnostics.TickFailureCounters tickFailures() {
        return new RegionThreadingDiagnostics.TickFailureCounters(values(this.failures));
    }

    private static long[] values(AtomicLong[] counters) {
        final long[] values = new long[counters.length];
        for (int index = 0; index < counters.length; ++index) {
            values[index] = counters[index].get();
        }
        return values;
    }

    record PhaseCounts(long globalPhases, long regionPhases, long regionPhasesWithoutGlobalPhase, long abandonedGlobalSegments) {}

    static PhaseCounts phases(Iterable<LevelTickPhaseState> states) {
        long global = 0L;
        long region = 0L;
        long withoutGlobal = 0L;
        long abandoned = 0L;
        for (final LevelTickPhaseState state : states) {
            global += state.globalPhaseCount();
            region += state.regionPhaseCount();
            withoutGlobal += state.regionPhasesWithoutGlobalPhase();
            abandoned += state.abandonedGlobalSegments();
        }
        return new PhaseCounts(global, region, withoutGlobal, abandoned);
    }

    static RegionThreadingDiagnostics.WorkerDispatchCounters workerDispatch(RegionWorkerPool.DispatchSnapshot dispatch,
            long packedTasks, long packedRegions, long partialPackFallbacks, boolean persistentWorkers, boolean packedRegionLeases) {
        return new RegionThreadingDiagnostics.WorkerDispatchCounters(
                dispatch.submissions(),
                dispatch.submitNanos(),
                dispatch.wakeNanos(),
                dispatch.idleWakeUps(),
                dispatch.spinWakeUps(),
                dispatch.parkWakeUps(),
                dispatch.steals(),
                packedTasks,
                packedRegions,
                partialPackFallbacks,
                persistentWorkers,
                packedRegionLeases,
                dispatch.dispatchBatches(),
                dispatch.batchedTasks(),
                dispatch.batchRunners(),
                dispatch.batchSteals(),
                dispatch.batchStolenTasks());
    }
}
