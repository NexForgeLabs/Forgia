/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ScheduledTick;
import net.neoforged.neoforge.server.threading.api.ScheduledTickStatus.SerialReason;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * First production scheduled callback profile: vanilla lamp turn-off in an inert, region-contained footprint.
 * <p>
 * RedstoneLampBlock.tick reads neighboring signals (a conductor can query direct signals one block farther),
 * toggles only its own LIT state with flags=2 and consumes no RNG. The radius-two cube therefore contains
 * its signal reads and immediate shape updates. Only exact built-in air/stone/glass/redstone-block/lamp states
 * are accepted there: none can extend a shape cascade, access entities, schedule ticks or consume RNG through
 * this call path. A whole phase containing any other scheduled type or unsafe footprint stays in the original
 * serial LevelTicks loop, including its live queue and clearArea semantics. This is deliberately not a generic
 * claim that redstone, fluid ticks or modded callbacks are safe.
 */
@ApiStatus.Internal
final class RegionScheduledTickAdmission {
    private static final int FLUID_INLAND_RADIUS = 5;

    private RegionScheduledTickAdmission() {}

    @Nullable
    static SerialReason refusal(ServerLevel level, RegionLayout layout, ScheduledTickPhase phase,
            List<? extends ScheduledTick<?>> ticks, int budget) {
        return refusal(level, layout, phase, ticks, budget, false);
    }

    @Nullable
    static SerialReason refusal(ServerLevel level, RegionLayout layout, ScheduledTickPhase phase,
            List<? extends ScheduledTick<?>> ticks, int budget, boolean fluidParallelEnabled) {
        if (phase == ScheduledTickPhase.FLUID) {
            if (!fluidParallelEnabled) {
                return SerialReason.FLUID_CALLBACK;
            }
            return null;
        }
        if (phase != ScheduledTickPhase.BLOCK) return SerialReason.FLUID_CALLBACK;
        for (int i = 0, size = ticks.size(); i < size; ++i) {
            if (ticks.get(i).type() != Blocks.REDSTONE_LAMP) return SerialReason.UNCLASSIFIED_CALLBACK;
        }
        if (!layout.supportsSectionPartitions()) return SerialReason.NON_FIXED_LAYOUT;
        if (ticks.size() > budget) return SerialReason.DEFERRED_UPDATE_BUDGET;
        final Long2ObjectOpenHashMap<LevelChunk> loaded = new Long2ObjectOpenHashMap<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0, size = ticks.size(); i < size; ++i) {
            final BlockPos position = ticks.get(i).pos();
            final RegionCoordinate owner = layout.regionAt(level, position);
            for (int dx = -2; dx <= 2; ++dx) {
                for (int dz = -2; dz <= 2; ++dz) {
                    final int x = position.getX() + dx;
                    final int z = position.getZ() + dz;
                    final int chunkX = x >> 4;
                    final int chunkZ = z >> 4;
                    if (!layout.isInRegion(level, chunkX, chunkZ, owner)) return SerialReason.FOREIGN_FOOTPRINT;
                    final long key = ChunkPos.asLong(chunkX, chunkZ);
                    LevelChunk chunk = loaded.get(key);
                    if (chunk == null) {
                        chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                        if (chunk == null || !chunk.isLoaded()) return SerialReason.UNLOADED_FOOTPRINT;
                        loaded.put(key, chunk);
                    }
                    for (int dy = -2; dy <= 2; ++dy) {
                        cursor.set(x, position.getY() + dy, z);
                        final Block block = chunk.getBlockState(cursor).getBlock();
                        if (!inert(block)) return SerialReason.UNSAFE_NEIGHBOR;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Partitions fluid ticks into inland region-safe ticks (safe to run in parallel on region workers)
     * and border/unloaded/demoted ticks (safe to execute serially on the server thread after the worker wave).
     * <p>
     * Vanilla flowing fluids search up to 4 blocks horizontally for slopes and write at most 1 block outward,
     * so checking the +/-5 block horizontal bounding corners guarantees all reads and writes remain strictly
     * inside one region and loaded chunks.
     */
    static <T> void partitionFluidTicks(ServerLevel level, RegionLayout layout, List<ScheduledTick<T>> ticks,
            Set<Fluid> demotedFluids, List<ScheduledTick<T>> inlandOut, List<ScheduledTick<T>> borderOut) {
        final Long2ObjectOpenHashMap<Boolean> loadedChunkCache = new Long2ObjectOpenHashMap<>();
        for (int i = 0, size = ticks.size(); i < size; ++i) {
            final ScheduledTick<T> tick = ticks.get(i);
            final T type = tick.type();
            if (type instanceof Fluid fluid
                    && (demotedFluids.contains(fluid)
                            || !net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedFluid(level, tick.pos(), fluid))) {
                borderOut.add(tick);
                continue;
            }
            if (isInlandFluidFootprint(level, layout, tick.pos(), loadedChunkCache)) {
                inlandOut.add(tick);
            } else {
                borderOut.add(tick);
            }
        }
    }

    private static boolean isInlandFluidFootprint(ServerLevel level, RegionLayout layout, BlockPos pos,
            Long2ObjectOpenHashMap<Boolean> loadedChunkCache) {
        final int minChunkX = (pos.getX() - FLUID_INLAND_RADIUS) >> 4;
        final int maxChunkX = (pos.getX() + FLUID_INLAND_RADIUS) >> 4;
        final int minChunkZ = (pos.getZ() - FLUID_INLAND_RADIUS) >> 4;
        final int maxChunkZ = (pos.getZ() + FLUID_INLAND_RADIUS) >> 4;
        final RegionCoordinate owner = layout.regionAt(level, pos);
        if (minChunkX != maxChunkX || minChunkZ != maxChunkZ) {
            if (!layout.isInRegion(level, minChunkX, minChunkZ, owner)
                    || !layout.isInRegion(level, maxChunkX, minChunkZ, owner)
                    || !layout.isInRegion(level, minChunkX, maxChunkZ, owner)
                    || !layout.isInRegion(level, maxChunkX, maxChunkZ, owner)) {
                return false;
            }
        }
        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                final long key = ChunkPos.asLong(cx, cz);
                Boolean loaded = loadedChunkCache.get(key);
                if (loaded == null) {
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    loaded = chunk != null && chunk.isLoaded();
                    loadedChunkCache.put(key, loaded);
                }
                if (!loaded) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean inert(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR
                || block == Blocks.STONE || block == Blocks.GLASS || block == Blocks.REDSTONE_BLOCK || block == Blocks.REDSTONE_LAMP;
    }
}
