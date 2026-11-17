/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.ApiStatus;

/** Immutable, finite spatial extent. Geometry alone is NOT a worker admission capability. */
@ApiStatus.Internal
public final class RegionPlayerFootprint {
    public static final int MAX_REGIONS = RegionLeasePack.MAX_REGIONS_PER_TASK;
    private final ServerLevel level;
    private final int regionChunks;
    private final AABB queries;
    private final AABB blocks;
    private final List<RegionCoordinate> regions;

    private RegionPlayerFootprint(ServerLevel level, int regionChunks, AABB queries, AABB blocks, List<RegionCoordinate> regions) {
        this.level = level;
        this.regionChunks = regionChunks;
        this.queries = queries;
        this.blocks = blocks;
        this.regions = List.copyOf(regions);
    }

    /**
     * Keeps the ORIGINAL query box. The section scan uses vanilla's -2/+2 X/Z and -4/+0 Y
     * search margin; those cells are leased too. No clamp and no arbitrary velocity halo.
     * Riding expansion is supplied as the full vehicle/player union, never as a smaller radius.
     */
    public static RegionPlayerFootprint of(ServerLevel level, int regionChunks, AABB queries, AABB blocks) {
        if (regionChunks <= 0 || !finite(queries) || !finite(blocks)) throw new IllegalArgumentException("Unbounded player footprint");
        AABB sections = new AABB(queries.minX - 2, queries.minY - 4, queries.minZ - 2, queries.maxX + 2, queries.maxY, queries.maxZ + 2);
        AABB extent = sections.minmax(blocks);
        int minX = Math.floorDiv(SectionPos.posToSectionCoord(extent.minX), regionChunks);
        int minZ = Math.floorDiv(SectionPos.posToSectionCoord(extent.minZ), regionChunks);
        int maxX = Math.floorDiv(SectionPos.posToSectionCoord(extent.maxX), regionChunks);
        int maxZ = Math.floorDiv(SectionPos.posToSectionCoord(extent.maxZ), regionChunks);
        long width = (long) maxX - minX + 1, depth = (long) maxZ - minZ + 1;
        if (width <= 0 || depth <= 0 || width > MAX_REGIONS || depth > MAX_REGIONS || width * depth > MAX_REGIONS)
            throw new IllegalArgumentException("Player footprint exceeds bounded region set");
        List<RegionCoordinate> regions = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) regions.add(new RegionCoordinate(level, x, z));
        return new RegionPlayerFootprint(level, regionChunks, queries, blocks, regions);
    }

    public ServerLevel level() {
        return this.level;
    }

    public int regionChunks() {
        return this.regionChunks;
    }

    public List<RegionCoordinate> regions() {
        return this.regions;
    }

    public AABB queryBounds() {
        return this.queries;
    }

    public AABB blockBounds() {
        return this.blocks;
    }

    public boolean containsRegion(ServerLevel level, int x, int z) {
        return this.level == level && this.regions.contains(new RegionCoordinate(level, x, z));
    }

    public boolean containsBlock(ServerLevel level, BlockPos pos) {
        // Vanilla block scans enumerate integer cell origins from floor(min) through floor(max).
        // Comparing an origin against fractional min would reject the first ORIGINAL collision cell.
        return level == this.level && pos.getX() >= Math.floor(this.blocks.minX) && pos.getX() <= Math.floor(this.blocks.maxX)
                && pos.getY() >= Math.floor(this.blocks.minY) && pos.getY() <= Math.floor(this.blocks.maxY)
                && pos.getZ() >= Math.floor(this.blocks.minZ) && pos.getZ() <= Math.floor(this.blocks.maxZ);
    }

    public boolean containsQuery(ServerLevel level, AABB box) {
        return level == this.level && finite(box) && contains(this.queries, box);
    }

    static boolean contains(AABB outer, AABB inner) {
        return inner.minX >= outer.minX && inner.minY >= outer.minY && inner.minZ >= outer.minZ
                && inner.maxX <= outer.maxX && inner.maxY <= outer.maxY && inner.maxZ <= outer.maxZ;
    }

    static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && Math.abs(box.minX) < 30_000_000 && Math.abs(box.maxX) < 30_000_000
                && Math.abs(box.minZ) < 30_000_000 && Math.abs(box.maxZ) < 30_000_000
                && box.minX <= box.maxX && box.minY <= box.maxY && box.minZ <= box.maxZ;
    }
}
