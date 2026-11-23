/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Immutable chunk-to-region mapping for one {@link RegionPolicy} and one region edge length in chunks.
 * <p>
 * {@link RegionPolicy#DYNAMIC_CLUSTER} partitions chunks into dynamic section clusters (default 8x8 chunks,
 * configurable via {@code regionThreadingDynamicSectionShift}) with hysteresis-protected merge and split.
 * {@link RegionPolicy#DYNAMIC} keeps the legacy 16x16-chunk grid with merge and split recalculation.
 */
public final class RegionLayout {
    public static final int DYNAMIC_REGION_CHUNK_SHIFT = 4;
    /** Edge length in chunks of the legacy dynamic grid. */
    public static final int DYNAMIC_REGION_CHUNK_SIZE = 1 << DYNAMIC_REGION_CHUNK_SHIFT;
    /** Edge length in blocks of one chunk, the unit the chunk coordinates of a position are taken in. */
    private static final int CHUNK_BLOCK_SIZE = 16;
    public static final RegionLayout DYNAMIC = new RegionLayout(
            RegionPolicy.DYNAMIC,
            DYNAMIC_REGION_CHUNK_SIZE,
            DYNAMIC_REGION_CHUNK_SHIFT,
            0L);
    public static final RegionLayout DYNAMIC_CLUSTER_8X8 = new RegionLayout(
            RegionPolicy.DYNAMIC_CLUSTER,
            1 << DynamicRegionTopology.DEFAULT_SECTION_SHIFT,
            DynamicRegionTopology.DEFAULT_SECTION_SHIFT,
            DynamicRegionTopology.DEFAULT_SPLIT_DELAY_TICKS);

    private final RegionPolicy policy;
    private final int regionChunkSize;
    private final int dynamicSectionShift;
    private final long dynamicSplitDelayTicks;

    private RegionLayout(RegionPolicy policy, int regionChunkSize, int dynamicSectionShift, long dynamicSplitDelayTicks) {
        this.policy = policy;
        this.regionChunkSize = regionChunkSize;
        this.dynamicSectionShift = dynamicSectionShift;
        this.dynamicSplitDelayTicks = dynamicSplitDelayTicks;
    }

    /**
     * {@return the layout of {@code policy} with the default edge length}
     */
    public static RegionLayout of(RegionPolicy policy) {
        return switch (policy) {
            case DYNAMIC -> DYNAMIC;
            case DYNAMIC_CLUSTER -> DYNAMIC_CLUSTER_8X8;
        };
    }

    /**
     * {@return the layout of {@code policy} with all configured grid parameters}
     */
    public static RegionLayout of(RegionPolicy policy, int dynamicSectionShift, long dynamicSplitDelayTicks) {
        return switch (policy) {
            case DYNAMIC -> DYNAMIC;
            case DYNAMIC_CLUSTER -> dynamicCluster(dynamicSectionShift, dynamicSplitDelayTicks);
        };
    }

    /**
     * {@return a Folia-style dynamic cluster layout whose underlying static section edge length is
     * {@code 1 << sectionShift} chunks}
     */
    public static RegionLayout dynamicCluster(int sectionShift, long splitDelayTicks) {
        if (sectionShift < DynamicRegionTopology.MIN_SECTION_SHIFT || sectionShift > DynamicRegionTopology.MAX_SECTION_SHIFT) {
            throw new IllegalArgumentException("Dynamic section shift must be in "
                    + DynamicRegionTopology.MIN_SECTION_SHIFT + ".." + DynamicRegionTopology.MAX_SECTION_SHIFT + ": " + sectionShift);
        }
        if (splitDelayTicks < 0L) {
            throw new IllegalArgumentException("Dynamic split delay ticks must be >= 0: " + splitDelayTicks);
        }
        if (sectionShift == DynamicRegionTopology.DEFAULT_SECTION_SHIFT
                && splitDelayTicks == DynamicRegionTopology.DEFAULT_SPLIT_DELAY_TICKS) {
            return DYNAMIC_CLUSTER_8X8;
        }
        return new RegionLayout(RegionPolicy.DYNAMIC_CLUSTER, 1 << sectionShift, sectionShift, splitDelayTicks);
    }

    public RegionPolicy policy() {
        return this.policy;
    }

    public boolean isDynamicCluster() {
        return this.policy == RegionPolicy.DYNAMIC_CLUSTER;
    }

    /**
     * {@return {@code true} when entity sections can be statically partitioned into buckets indexed by
     * {@link #regionX(int)}/{@link #regionZ(int)}}
     */
    public boolean supportsSectionPartitions() {
        return this.policy == RegionPolicy.DYNAMIC_CLUSTER;
    }

    public int dynamicSectionShift() {
        return this.dynamicSectionShift;
    }

    public long dynamicSplitDelayTicks() {
        return this.dynamicSplitDelayTicks;
    }

    /**
     * {@return the edge length in chunks of one region/section coordinate}
     */
    public int regionChunkSize() {
        return this.regionChunkSize;
    }

    public int regionX(int chunkX) {
        return switch (this.policy) {
            case DYNAMIC_CLUSTER -> chunkX >> this.dynamicSectionShift;
            case DYNAMIC -> chunkX >> DYNAMIC_REGION_CHUNK_SHIFT;
        };
    }

    public int regionZ(int chunkZ) {
        return switch (this.policy) {
            case DYNAMIC_CLUSTER -> chunkZ >> this.dynamicSectionShift;
            case DYNAMIC -> chunkZ >> DYNAMIC_REGION_CHUNK_SHIFT;
        };
    }

    public RegionCoordinate regionAt(ServerLevel level, int chunkX, int chunkZ) {
        return new RegionCoordinate(level, this.regionX(chunkX), this.regionZ(chunkZ));
    }

    public RegionCoordinate regionAt(ServerLevel level, BlockPos pos) {
        return this.regionAt(level, Math.floorDiv(pos.getX(), CHUNK_BLOCK_SIZE), Math.floorDiv(pos.getZ(), CHUNK_BLOCK_SIZE));
    }

    /**
     * {@return true when {@code pos} of {@code level} belongs to {@code coordinate}}
     * <p>
     * The non-allocating counterpart of {@code regionAt(level, pos).equals(coordinate)}. The per-ticker
     * ownership check of the block-entity worker slice calls it once per ticker, so it must not create a
     * {@link RegionCoordinate} per call.
     */
    public boolean isInRegion(ServerLevel level, BlockPos pos, RegionCoordinate coordinate) {
        return this.isInRegion(level, Math.floorDiv(pos.getX(), CHUNK_BLOCK_SIZE), Math.floorDiv(pos.getZ(), CHUNK_BLOCK_SIZE), coordinate);
    }

    /**
     * {@return true when the chunk column {@code (chunkX, chunkZ)} of {@code level} belongs to
     * {@code coordinate}}
     * <p>
     * The chunk-coordinate counterpart of {@link #isInRegion(ServerLevel, BlockPos, RegionCoordinate)}, for a
     * caller that already has the column - the game event dispatcher decides per column whether a worker may
     * visit it.
     */
    public boolean isInRegion(ServerLevel level, int chunkX, int chunkZ, RegionCoordinate coordinate) {
        return coordinate.level() == level
                && this.regionX(chunkX) == coordinate.regionX()
                && this.regionZ(chunkZ) == coordinate.regionZ();
    }
}
