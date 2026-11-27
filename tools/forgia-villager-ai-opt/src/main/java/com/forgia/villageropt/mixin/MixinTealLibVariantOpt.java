package com.forgia.villageropt.mixin;

import com.ninni.teallib.api.common.block.entity.VariantHolderBlockEntity;
import com.ninni.teallib.api.common.data.blockvariant.BlockVariantManager;
import com.ninni.teallib.api.common.network.BlockEntitySyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = VariantHolderBlockEntity.class, remap = false)
public abstract class MixinTealLibVariantOpt extends BlockEntity {
    @Shadow
    private ResourceLocation variant;

    protected MixinTealLibVariantOpt(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Shadow
    protected abstract void saveAdditional(CompoundTag tag, HolderLookup.Provider registries);

    /**
     * @author Forgia
     * @reason Avoid redundant double sync() during onLoad.
     */
    @Overwrite
    public void ensureVariantAssigned() {
        if (this.level != null && !this.level.isClientSide && !BlockVariantManager.isValidVariant((VariantHolderBlockEntity) (Object) this, this.variant)) {
            BlockVariantManager.assignNaturally((VariantHolderBlockEntity) (Object) this);
        }
    }

    /**
     * @author Forgia
     * @reason Mark current chunk unsaved and send tracking packet without calling updateNeighbourForOutputSignal across unloaded chunk boundaries during onLoad.
     */
    @Overwrite
    public void sync() {
        if (this.level == null) {
            return;
        }
        LevelChunk chunk = this.level.getChunkSource().getChunkNow(this.worldPosition.getX() >> 4, this.worldPosition.getZ() >> 4);
        if (chunk != null) {
            chunk.setUnsaved(true);
        }
        if (!this.level.isClientSide()) {
            CompoundTag tag = new CompoundTag();
            this.saveAdditional(tag, this.level.registryAccess());
            PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) this.level, new ChunkPos(this.worldPosition), new BlockEntitySyncPacket(this.worldPosition, tag));
        }
    }
}
