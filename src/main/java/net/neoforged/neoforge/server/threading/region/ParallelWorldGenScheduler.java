/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * C2ME-style parallel world-generation scheduler and spatial area locker.
 *
 * <p>Replaces {@link net.minecraft.server.level.ChunkMap}'s single-threaded {@code worldgen}
 * {@code ProcessorMailbox} with a bounded multi-worker {@link ProcessorHandle} and guards
 * overlapping {@link ChunkStep} writes using canonical-ordered striped {@link Semaphore} permits
 * (ownership-agnostic so async {@link CompletableFuture} completions can release safely on any thread).
 */
public final class ParallelWorldGenScheduler {
    private static final int STRIPE_COUNT = 4096;
    private static final int STRIPE_MASK = STRIPE_COUNT - 1;
    private static final int MAX_LOCK_RADIUS = 4;

    private final Semaphore[] areaStripes;

    public ParallelWorldGenScheduler() {
        this.areaStripes = new Semaphore[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; ++i) {
            this.areaStripes[i] = new Semaphore(1);
        }
    }

    /**
     * Creates a concurrent {@link ProcessorHandle} that runs up to {@code maxConcurrency}
     * worldgen tasks simultaneously on {@code executor}.
     */
    public static ProcessorHandle<Runnable> createConcurrentHandle(Executor executor, String name, int maxConcurrency) {
        final int limit = Math.max(1, maxConcurrency);
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        final AtomicInteger active = new AtomicInteger();
        return new ProcessorHandle<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void tell(Runnable task) {
                queue.add(task);
                this.trySchedule();
            }

            private void trySchedule() {
                while (active.get() < limit && !queue.isEmpty()) {
                    final int current = active.get();
                    if (current >= limit || !active.compareAndSet(current, current + 1)) {
                        continue;
                    }
                    final Runnable next = queue.poll();
                    if (next == null) {
                        active.decrementAndGet();
                        if (queue.isEmpty()) {
                            return;
                        }
                        continue;
                    }
                    executor.execute(() -> {
                        try {
                            next.run();
                        } finally {
                            active.decrementAndGet();
                            this.trySchedule();
                        }
                    });
                }
            }

            @Override
            public void close() {
                queue.clear();
            }
        };
    }

    /**
     * Executes {@code work} while holding canonical-ordered spatial permits covering
     * {@code [center.x - r, center.x + r] x [center.z - r, center.z + r]} where {@code r}
     * is {@link ChunkStep#blockStateWriteRadius()}.
     */
    public CompletableFuture<ChunkAccess> runWithAreaLock(
            ChunkPos center,
            ChunkStep step,
            Supplier<CompletableFuture<ChunkAccess>> work) {
        final ChunkStatus target = step.targetStatus();
        if (target == ChunkStatus.EMPTY
                || target == ChunkStatus.INITIALIZE_LIGHT
                || target == ChunkStatus.LIGHT
                || target == ChunkStatus.FULL) {
            return work.get();
        }
        final int radius = Math.min(MAX_LOCK_RADIUS, Math.max(0, step.blockStateWriteRadius()));
        final int[] stripes = collectSortedUniqueStripes(center.x, center.z, radius);
        acquireAll(stripes);
        final CompletableFuture<ChunkAccess> future;
        try {
            future = work.get();
        } catch (Throwable throwable) {
            releaseAll(stripes);
            throw throwable;
        }
        if (future == null) {
            releaseAll(stripes);
            return null;
        }
        return future.whenComplete((chunk, error) -> releaseAll(stripes));
    }

    private void acquireAll(int[] stripes) {
        for (int i = 0; i < stripes.length; ++i) {
            this.areaStripes[stripes[i]].acquireUninterruptibly();
        }
    }

    private void releaseAll(int[] stripes) {
        for (int i = stripes.length - 1; i >= 0; --i) {
            this.areaStripes[stripes[i]].release();
        }
    }

    private static int[] collectSortedUniqueStripes(int cx, int cz, int radius) {
        if (radius == 0) {
            return new int[] { stripeIndex(cx, cz) };
        }
        final int side = radius * 2 + 1;
        final int[] raw = new int[side * side];
        int n = 0;
        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                raw[n++] = stripeIndex(cx + dx, cz + dz);
            }
        }
        Arrays.sort(raw, 0, n);
        int unique = 0;
        for (int i = 0; i < n; ++i) {
            if (i == 0 || raw[i] != raw[i - 1]) {
                raw[unique++] = raw[i];
            }
        }
        return unique == raw.length ? raw : Arrays.copyOf(raw, unique);
    }

    private static int stripeIndex(int chunkX, int chunkZ) {
        long h = ChunkPos.asLong(chunkX, chunkZ);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return (int) (h & STRIPE_MASK);
    }
}
