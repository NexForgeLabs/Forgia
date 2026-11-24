/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Server-thread orchestration of the chunk tick slice, behind the existing engine entry point.
 * <p>
 * The host bridges to the original publication, leasing, worker pool and ordered handoff. There is no
 * independent lifecycle, executor or permission source here. Chunk admission and worker bodies retain
 * their existing implementations; this first extraction separates phase sequencing from the engine.
 */
final class RegionChunkTickCoordinator {
    interface Host {
        int blockUpdateEntryBudget();

        TickBatchPublication publish(RegionChunkTickBatch batch);

        void countSkippedChunks(int count);

        void tickOnServerThread(ServerLevel level, RegionChunkTickEntry chunk, int randomTickSpeed);

        void leaseGroups(RegionChunkTickBatch batch, List<RegionChunkTickEntry> chunks, RegionTickBarrier barrier);

        /** Returns only after all submitted work has physically joined, including on failure. */
        void submitAndAwait(RegionChunkTickBatch batch, RegionTickBarrier barrier);

        /** Performs the original ordered effects, finishes the original batch and retires its marker. */
        @Nullable
        Throwable complete(RegionChunkTickBatch batch, @Nullable Throwable failure);

        /**
         * Attempts to dispatch chunk ticks asynchronously via the independent region tick scheduler.
         * Returns {@code true} when async dispatch handled the batch so no synchronous barrier is needed.
         */
        default boolean tryExecuteAsync(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed) {
            return false;
        }
    }

    private final Host host;

    RegionChunkTickCoordinator(Host host) {
        this.host = host;
    }

    void executeAndAwait(ServerLevel level, List<RegionChunkTickEntry> chunks, int randomTickSpeed, RegionTickBarrier barrier) {
        RegionChunkTickAttribution.endPlanSegment();
        if (chunks.isEmpty()) {
            return;
        }
        if (!RegionChunkTickAttribution.isEnabled() && this.host.tryExecuteAsync(level, chunks, randomTickSpeed)) {
            return;
        }

        final RegionChunkTickBatch batch = new RegionChunkTickBatch(level, randomTickSpeed, this.host.blockUpdateEntryBudget());
        final TickBatchPublication publication = this.host.publish(batch);
        if (publication == TickBatchPublication.LEVEL_CLOSED) {
            // A retired level must not tick even the chunks collected before retirement.
            this.host.countSkippedChunks(chunks.size());
            return;
        }
        if (publication == TickBatchPublication.SHUTDOWN) {
            // Shutdown refuses publication, but still executes collected work through the original inline path.
            for (final RegionChunkTickEntry chunk : chunks) {
                this.host.tickOnServerThread(level, chunk, randomTickSpeed);
            }
            return;
        }

        batch.markExecutingOnCurrentThread();
        final boolean attribution = RegionChunkTickAttribution.isEnabled();
        Throwable failure = null;
        try {
            final long leaseStart = attribution ? System.nanoTime() : 0L;
            try {
                this.host.leaseGroups(batch, chunks, barrier);
            } catch (Throwable throwable) {
                failure = throwable;
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordLease(System.nanoTime() - leaseStart);
                }
            }
            final long awaitStart = attribution ? System.nanoTime() : 0L;
            try {
                this.host.submitAndAwait(batch, barrier);
            } catch (Throwable throwable) {
                if (failure == null) {
                    failure = throwable;
                }
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordAwait(System.nanoTime() - awaitStart);
                }
            }
        } finally {
            final long handoffStart = attribution ? System.nanoTime() : 0L;
            try {
                failure = this.host.complete(batch, failure);
            } finally {
                if (attribution) {
                    RegionChunkTickAttribution.recordHandoff(System.nanoTime() - handoffStart);
                }
            }
        }

        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Region tick batch failed", failure);
        }
        batch.throwIfFailed();
    }
}
