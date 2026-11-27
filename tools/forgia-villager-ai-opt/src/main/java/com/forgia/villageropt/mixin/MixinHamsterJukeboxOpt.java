package com.forgia.villageropt.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.dawson.adorablehamsterpets.config.Configs;
import net.dawson.adorablehamsterpets.entity.custom.DanceStyle;
import net.dawson.adorablehamsterpets.entity.custom.HamsterEntity;
import net.dawson.adorablehamsterpets.flute.DanceSongMatcher;
import net.dawson.adorablehamsterpets.util.HamsterAIUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = HamsterAIUtil.class, remap = false)
public abstract class MixinHamsterJukeboxOpt {

    /**
     * @author Forgia
     * @reason Replace Manhattan radius 8x4x8 BlockPos scan with loaded chunk blockEntities lookup for JukeboxBlockEntity.
     */
    @Overwrite
    public static DanceStyle getNearbyDanceStyle(HamsterEntity hamster) {
        Level world = hamster.level();
        BlockPos origin = hamster.blockPosition();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        int minChunkX = (ox - 8) >> 4;
        int maxChunkX = (ox + 8) >> 4;
        int minChunkZ = (oz - 8) >> 4;
        int maxChunkZ = (oz + 8) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof JukeboxBlockEntity jbe)) {
                        continue;
                    }
                    BlockPos p = jbe.getBlockPos();
                    int dx = Math.abs(p.getX() - ox);
                    int dy = Math.abs(p.getY() - oy);
                    int dz = Math.abs(p.getZ() - oz);
                    if (dx > 8 || dy > 4 || dz > 8 || (dx + dy + dz) > 8) {
                        continue;
                    }
                    if (!jbe.getSongPlayer().isPlaying() || jbe.getSongPlayer().getSong() == null) {
                        continue;
                    }
                    JukeboxSong song = jbe.getSongPlayer().getSong();
                    ItemStack discStack = jbe.getTheItem();
                    String songDesc = song.description().getString().toLowerCase(Locale.ROOT);
                    String itemName = discStack.getHoverName().getString().toLowerCase(Locale.ROOT);
                    String itemKey = discStack.getDescriptionId().toLowerCase(Locale.ROOT);
                    ItemLore lore = discStack.get(DataComponents.LORE);
                    ArrayList<String> searchableText = new ArrayList<>(List.of(songDesc, itemName, itemKey));
                    if (lore != null) {
                        for (Component line : lore.lines()) {
                            searchableText.add(line.getString());
                        }
                    }
                    DanceStyle style = DanceSongMatcher.classify(
                        Configs.AHP_ITEMS.slowDancingMusicDiscStrings,
                        Configs.AHP_ITEMS.dancingMusicDiscStrings,
                        searchableText
                    );
                    if (style != DanceStyle.NONE) {
                        return style;
                    }
                }
            }
        }
        return DanceStyle.NONE;
    }
}
