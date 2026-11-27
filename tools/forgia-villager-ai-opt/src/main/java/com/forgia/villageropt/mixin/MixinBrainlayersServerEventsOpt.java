package com.forgia.villageropt.mixin;

import com.brainlayers.Brainlayers;
import com.brainlayers.content.SchoolDeskBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = Brainlayers.ServerEvents.class, remap = false)
public abstract class MixinBrainlayersServerEventsOpt {

    /**
     * @author Forgia
     * @reason Replace every-tick 11x7x11 (847) level.getBlockEntity(pos) scan per player with chunk blockEntities map lookup.
     */
    @Overwrite
    private static Map<Holder<Enchantment>, Integer> nearbyDeskTemporaryEnchantments(ServerPlayer player) {
        LinkedHashMap<Holder<Enchantment>, Integer> enchantments = new LinkedHashMap<>();
        ServerLevel level = player.serverLevel();
        BlockPos center = player.blockPosition();
        int minX = center.getX() - 5;
        int maxX = center.getX() + 5;
        int minY = center.getY() - 3;
        int maxY = center.getY() + 3;
        int minZ = center.getZ() - 5;
        int maxZ = center.getZ() + 5;
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
                    if (!(blockEntity instanceof SchoolDeskBlockEntity desk)) {
                        continue;
                    }
                    BlockPos pos = desk.getBlockPos();
                    int px = pos.getX();
                    int py = pos.getY();
                    int pz = pos.getZ();
                    if (py < minY || py > maxY || px < minX || px > maxX || pz < minZ || pz > maxZ) {
                        continue;
                    }
                    for (SchoolDeskBlockEntity.DeskEnchantment entry : desk.activeEnchantments()) {
                        enchantments.merge(entry.enchantment(), entry.level(), Math::max);
                    }
                }
            }
        }
        return enchantments;
    }
}
