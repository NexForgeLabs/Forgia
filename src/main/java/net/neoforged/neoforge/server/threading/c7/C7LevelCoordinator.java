/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.apache.logging.log4j.LogManager;

/** Canonical collection after pending merge/onLoad. No C6 plan, grant, batch, adapter or counter is used. */
public final class C7LevelCoordinator {
    private C7LevelCoordinator() {}

    public static void execute(ServerLevel level, C7Engine engine, C7Options options, RegionWorkerExecutor workers,
            RegionTickBarrier barrier, C7Ownership ownership, BooleanSupplier alive) {
        final long collectionStart = System.nanoTime();
        final List<TickingBlockEntity> removed = new ArrayList<>();
        final Set<TickingBlockEntity> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<C7Task> tasks = new ArrayList<>();
        for (final var ticker : level.snapshotBlockEntityTickers()) {
            if (!identities.add(ticker)) continue;
            final String type = options.trackingDiagnostics() ? C7TypePolicy.type(ticker) : "<diagnostics-disabled>";
            tasks.add(new C7FurnaceTask(level, ticker, options, ownership, type, options.trackingDiagnostics() ? reason -> engine.recordRefusal(type, reason) : reason -> {}, () -> {
                if (!alive.getAsBoolean()) return false;
                if (ticker.isRemoved()) {
                    removed.add(ticker);
                    return false;
                }
                return level.tickRateManager().runsNormally() && level.shouldTickBlocksAt(ticker.getPos());
            }));
        }
        engine.recordCollectionNanos(System.nanoTime() - collectionStart);
        try {
            engine.execute(tasks, options, workers, barrier);
        } finally {
            if (!removed.isEmpty()) level.removeCollectedBlockEntityTickers(removed);
            if (options.debug()) LogManager.getLogger().info("C7 canonical BE phase: {}", engine.metrics());
        }
    }
}
