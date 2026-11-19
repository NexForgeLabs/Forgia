/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.capabilities;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Holder for capability listeners associated to a level.
 */
// For now, we don't invalidate the cache when the level is unloaded but the chunk unload events do not fire.
// This can be re-evaluated in the future.
@ApiStatus.Internal
public class CapabilityListenerHolder {
    /**
     * Map of chunk pos -> block pos -> listeners map.
     *
     * <p>We have a two-level map to efficiently invalidate entire chunks at once.
     */
    private final Long2ReferenceMap<Long2ReferenceMap<Set<ListenerReference>>> byChunkThenBlock = new Long2ReferenceOpenHashMap<>();
    /**
     * Reference queue for the listener references.
     * This allows us to listen to reference garbage collection, and remove empty entries from {@link #byChunkThenBlock}.
     */
    private final ReferenceQueue<ICapabilityInvalidationListener> queue = new ReferenceQueue<>();

    private final Object lock = new Object();

    /**
     * Adds a listener.
     */
    public void addListener(BlockPos pos, ICapabilityInvalidationListener listener) {
        pos = pos.immutable();
        synchronized (this.lock) {
            var chunkHolder = byChunkThenBlock.computeIfAbsent(ChunkPos.asLong(pos), l -> new Long2ReferenceOpenHashMap<>());
            var listenersSet = chunkHolder.computeIfAbsent(pos.asLong(), l -> new ObjectOpenHashSet<>());

            var reference = new ListenerReference(queue, pos, listener);
            if (!listenersSet.add(reference)) {
                // Clear the reference immediately if it was a duplicate.
                reference.clear();
            }
        }
    }

    /**
     * Invalidates listeners at a specific block position.
     */
    public void invalidatePos(BlockPos pos) {
        final ListenerReference[] snapshot;
        synchronized (this.lock) {
            var chunkHolder = this.byChunkThenBlock.get(ChunkPos.asLong(pos));
            var listeners = chunkHolder == null ? null : chunkHolder.get(pos.asLong());
            if (listeners == null) {
                return;
            }
            snapshot = listeners.toArray(ListenerReference[]::new);
        }
        for (var ref : snapshot) {
            this.invalidate(ref);
        }
    }

    /**
     * Invalidates listeners at a specific chunk position.
     */
    public void invalidateChunk(ChunkPos chunkPos) {
        final List<ListenerReference> snapshot = new ArrayList<>();
        synchronized (this.lock) {
            var chunkHolder = this.byChunkThenBlock.get(chunkPos.toLong());
            if (chunkHolder == null) {
                return;
            }
            for (var listeners : chunkHolder.values()) {
                snapshot.addAll(listeners);
            }
        }
        for (var ref : snapshot) {
            this.invalidate(ref);
        }
    }

    private void invalidate(ListenerReference ref) {
        final ICapabilityInvalidationListener listener;
        synchronized (this.lock) {
            listener = ref.get();
            if (listener == null) {
                this.remove(ref);
                return;
            }
            if (ref.notifyingThread != null) {
                // A reentrant invalidation is already covered. A concurrent one requests a follow-up
                // without waiting, so callbacks that invalidate each other's targets cannot deadlock.
                ref.pending |= ref.notifyingThread != Thread.currentThread();
                return;
            }
            ref.notifyingThread = Thread.currentThread();
        }

        try {
            while (true) {
                final boolean keep = listener.onInvalidate();
                synchronized (this.lock) {
                    if (!keep) {
                        this.remove(ref);
                    } else if (ref.pending) {
                        ref.pending = false;
                        continue;
                    }
                    ref.notifyingThread = null;
                    ref.pending = false;
                    return;
                }
            }
        } catch (RuntimeException | Error failure) {
            synchronized (this.lock) {
                ref.notifyingThread = null;
                ref.pending = false;
            }
            throw failure;
        }
    }

    /** Removes this exact registration under the holder lock, including from stale snapshots. */
    private void remove(ListenerReference ref) {
        // Cleared references compare by reference identity only.
        ref.clear();
        long chunkKey = ChunkPos.asLong(ref.pos);
        var chunkHolder = this.byChunkThenBlock.get(chunkKey);
        if (chunkHolder == null) {
            return;
        }
        long blockKey = ref.pos.asLong();
        var listeners = chunkHolder.get(blockKey);
        if (listeners != null && listeners.remove(ref) && listeners.isEmpty()) {
            chunkHolder.remove(blockKey);
            if (chunkHolder.isEmpty()) {
                this.byChunkThenBlock.remove(chunkKey);
            }
        }
    }

    /**
     * Poll the reference queue, and remove garbage-collected listener references entries from {@link #byChunkThenBlock}.
     */
    public void clean() {
        synchronized (this.lock) {
            ListenerReference ref;
            while ((ref = (ListenerReference) this.queue.poll()) != null) {
                this.remove(ref);
            }
        }
    }

    private static class ListenerReference extends WeakReference<ICapabilityInvalidationListener> {
        private final BlockPos pos;
        private final int listenerHashCode;
        @org.jetbrains.annotations.Nullable
        private Thread notifyingThread;
        private boolean pending;

        private ListenerReference(ReferenceQueue<ICapabilityInvalidationListener> queue, BlockPos pos, ICapabilityInvalidationListener listener) {
            super(listener, queue);
            this.pos = pos;
            this.listenerHashCode = System.identityHashCode(listener);
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || obj instanceof ListenerReference otherRef
                    && this.listenerHashCode == otherRef.listenerHashCode && this.refersToLiveListener(otherRef);
        }

        private boolean refersToLiveListener(ListenerReference other) {
            var listener = this.get();
            return listener != null && other.refersTo(listener);
        }

        @Override
        public int hashCode() {
            return listenerHashCode;
        }
    }
}
