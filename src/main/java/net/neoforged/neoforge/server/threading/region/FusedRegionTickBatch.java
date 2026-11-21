/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * A fused per-region tick batch that executes {@link RegionTickSlice#CHUNK_TICK} followed immediately by
 * {@link RegionTickSlice#ENTITY_TICK} for each region under a single region lease, single worker task,
 * and single barrier.
 * <p>
 * Extends {@link RegionEntityTickBatch} so every existing deferred storage queue (block updates, section
 * updates, entity additions/removals, portal transitions, and region tick mutations) is shared seamlessly
 * across both fused stages, adding only the chunk-tick lightning strike queue and chunk fallback list.
 */
@ApiStatus.Internal
final class FusedRegionTickBatch extends RegionEntityTickBatch {
    private final int randomTickSpeed;
    private final Queue<BlockPos> lightningStrikes = new ConcurrentLinkedQueue<>();
    private final Queue<RegionChunkTickEntry> fallbackChunks = new ConcurrentLinkedQueue<>();
    private final Queue<Runnable> regionTickMutations = new ConcurrentLinkedQueue<>();

    FusedRegionTickBatch(ServerLevel level, int randomTickSpeed, int blockUpdateEntryBudget) {
        super(level, blockUpdateEntryBudget);
        this.randomTickSpeed = randomTickSpeed;
    }

    int randomTickSpeed() {
        return this.randomTickSpeed;
    }

    Queue<BlockPos> lightningStrikes() {
        return this.lightningStrikes;
    }

    Queue<Runnable> regionTickMutations() {
        return this.regionTickMutations;
    }

    void addFallbackChunk(RegionChunkTickEntry chunk) {
        this.fallbackChunks.add(chunk);
    }

    void addFallbackChunks(List<RegionChunkTickEntry> chunks) {
        this.fallbackChunks.addAll(chunks);
    }

    List<RegionChunkTickEntry> drainFallbackChunks() {
        if (this.fallbackChunks.isEmpty()) {
            return List.of();
        }
        final List<RegionChunkTickEntry> drained = new ArrayList<>();
        RegionChunkTickEntry entry;
        while ((entry = this.fallbackChunks.poll()) != null) {
            drained.add(entry);
        }
        return drained;
    }

    /**
     * Adds one chunk-only region group (a region with staged chunks but no active self-driven entity group)
     * into this same fused barrier.
     */
    void addChunkOnlyGroup(RegionState region, java.util.function.Function<RegionOwnershipLeaseGroup, Runnable> work) {
        final RegionOwnershipLeaseGroup group = new ChunkOnlyGroup(this, region);
        this.addTask(region, "fused chunk-only tick " + region.coordinate(), this.groupWork(work.apply(group)));
    }

    private record ChunkOnlyGroup(FusedRegionTickBatch batch, RegionState region) implements RegionOwnershipLeaseGroup {
        @Override
        public boolean owns(net.minecraft.world.entity.Entity entity) {
            return entity.level() == this.batch.level()
                    && RegionEntityOwnershipScope.isInsideRegion(this.region.coordinate(), this.batch.level(), entity.blockPosition());
        }

        @Override
        public boolean acceptsDeferredRemovals() {
            return !this.batch.isRemovalSeamClosed();
        }

        @Override
        public boolean deferRemoval(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.entity.EntityInLevelCallback callback, net.minecraft.world.entity.Entity.RemovalReason reason) {
            return this.batch.deferRemoval(entity, callback, reason);
        }

        @Override
        public boolean acceptsDeferredSectionUpdates() {
            return this.batch.acceptsDeferredSectionUpdates();
        }

        @Override
        public boolean deferSectionUpdate(net.minecraft.world.entity.Entity entity, net.minecraft.world.level.entity.EntityInLevelCallback callback, long targetSection) {
            return this.batch.deferSectionUpdate(entity, callback, targetSection);
        }

        @Override
        public boolean deferAddition(net.minecraft.world.entity.Entity entity, List<net.minecraft.world.entity.Entity> graph) {
            return this.batch.deferAddition(entity, graph);
        }

        @Override
        public boolean deferPortalTransition(net.minecraft.world.entity.Entity entity) {
            return this.batch.deferPortalTransition(entity);
        }
    }
}
