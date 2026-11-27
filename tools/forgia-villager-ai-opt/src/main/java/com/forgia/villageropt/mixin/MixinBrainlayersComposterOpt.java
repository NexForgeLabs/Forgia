package com.forgia.villageropt.mixin;

import com.brainlayers.content.AdvancedComposterBlockEntity;
import com.brainlayers.content.AdvancedComposterVillagerManager;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = AdvancedComposterVillagerManager.class, remap = false)
public abstract class MixinBrainlayersComposterOpt {

    /**
     * @author Forgia
     * @reason Replace 33x33x5 (5445) level.getBlockEntity(pos) brute-force scan with chunk blockEntities map lookup on already-loaded chunks.
     */
    @Overwrite
    private static AdvancedComposterBlockEntity findNearest(Level level, BlockPos origin, Predicate<AdvancedComposterBlockEntity> filter) {
        AdvancedComposterBlockEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int minX = ox - 16;
        int maxX = ox + 16;
        int minY = oy - 2;
        int maxY = oy + 2;
        int minZ = oz - 16;
        int maxZ = oz + 16;
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof AdvancedComposterBlockEntity composter)) {
                        continue;
                    }
                    BlockPos pos = composter.getBlockPos();
                    int px = pos.getX();
                    int py = pos.getY();
                    int pz = pos.getZ();
                    if (py < minY || py > maxY || px < minX || px > maxX || pz < minZ || pz > maxZ) {
                        continue;
                    }
                    double distance = origin.distSqr(pos);
                    if (distance < nearestDistance && filter.test(composter)) {
                        nearestDistance = distance;
                        nearest = composter;
                    }
                }
            }
        }
        return nearest;
    }
}
