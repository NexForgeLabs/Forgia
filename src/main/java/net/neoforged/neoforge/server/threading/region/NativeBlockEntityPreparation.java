/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.timings.TimeTracker;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** A fresh, closed original body branch, not a type declaration or an observed callback footprint. */
@ApiStatus.Internal
public final class NativeBlockEntityPreparation {
    private static final BlockEntityTicker<HopperBlockEntity> HOPPER = HopperBlockEntity::pushItemsTick;
    private static String runtimeRefusal;
    private static final boolean VERIFIED = unmodifiedMethods();

    private NativeBlockEntityPreparation() {}

    /** The exact original function issued to HopperBlock; a replacement function never inherits its proof. */
    public static BlockEntityTicker<HopperBlockEntity> hopperTicker() {
        return HOPPER;
    }

    private static boolean unmodifiedMethods() {
        try {
            for (final Class<?> outer : List.of(HopperBlockEntity.class, HopperBlock.class)) {
                for (Class<?> parent = outer; parent != null && parent != BlockEntity.class && parent != Object.class; parent = parent.getSuperclass()) {
                    for (final Class<?> type : parent.getNestMembers()) {
                        for (final var method : type.getDeclaredMethods()) {
                            if (method.getAnnotation(MixinMerged.class) != null) {
                                runtimeRefusal = "Transformed method: " + type.getName() + "." + method.getName();
                                return false;
                            }
                        }
                    }
                }
            }
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            runtimeRefusal = unavailable.toString();
            return false;
        }
    }

    /** No inventory, provider, loot, entity, neighbor, RNG, or operation callback is entered in this branch. */
    @Nullable
    static Candidate prepare(RegionBlockEntityTickAdmission admission) {
        final var nativeProof = prepareNative(admission);
        return nativeProof != null ? nativeProof : AuditedBlockEntityPreparation.prepare(admission);
    }

    @Nullable
    static Candidate prepareNative(RegionBlockEntityTickAdmission admission) {
        if (TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled() || !admission.isAdmitted()
                || !admission.isCurrent() || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(admission.type()))
            return null;
        if (VERIFIED) {
            final HopperBlockEntity source = isNativeValid(admission, null, null);
            if (source != null) {
                final var position = admission.position();
                final LevelChunk chunk = admission.level().getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
                if (isHopperChunkValid(admission, source, chunk)) {
                    return new HopperCandidate(admission, source, chunk);
                }
            }
        }
        if (net.neoforged.neoforge.server.threading.api.RegionAdapterApi.isRegionSandboxedBlockEntity(admission.type())) {
            final var position = admission.position();
            final var chunkSource = admission.level().getChunkSource();
            final LevelChunk chunk = chunkSource == null ? null : chunkSource.getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            if (chunk != null && chunk.isLoaded()) {
                final BlockEntity be = chunk.getBlockEntities().get(position);
                if (be == null || (!be.isRemoved()
                        && be.getLevel() == admission.level()
                        && net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedBlockEntityInstance(admission.type(), be))) {
                    return new RegionSandboxedCandidate(admission, be != null ? be : admission.ticker(), be, chunk);
                }
            }
        }
        return null;
    }

    @Nullable
    private static HopperBlockEntity isNativeValid(RegionBlockEntityTickAdmission admission,
            @Nullable HopperBlockEntity expectedSource, @Nullable LevelChunk expectedChunk) {
        if (!VERIFIED || TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled() || !admission.isAdmitted()
                || !admission.isCurrent() || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(admission.type()))
            return null;
        final HopperBlockEntity source = LevelChunk.nativeHopperSource(admission.ticker());
        if (source == null || (expectedSource != null && source != expectedSource)
                || source.getClass() != HopperBlockEntity.class || source.hasAttachments()
                || source.getLevel() != admission.level() || source.isRemoved())
            return null;
        final var border = admission.level().getWorldBorder();
        if (admission.level().getServer() == null || admission.level().getClass() != ServerLevel.class
                || border == null || border.getClass() != net.minecraft.world.level.border.WorldBorder.class)
            return null;
        final var clock = admission.level().getLevelData();
        if (clock == null || clock.getClass() != net.minecraft.world.level.storage.PrimaryLevelData.class) return null;
        if (expectedChunk != null && !isHopperChunkValid(admission, source, expectedChunk)) return null;
        return source;
    }

    private static boolean isHopperChunkValid(RegionBlockEntityTickAdmission admission, HopperBlockEntity source, @Nullable LevelChunk chunk) {
        if (chunk == null || chunk.getClass() != LevelChunk.class || !chunk.isLoaded() || !LevelChunk.furnaceWorkMatchesChunk(admission.ticker(), chunk))
            return false;
        final var position = admission.position();
        if (chunk.getBlockEntities().get(position) != source) return false;
        final var state = chunk.getBlockState(position);
        return state == source.getBlockState() && state.getBlock() == Blocks.HOPPER && source.getType().isValid(state)
                && source.nativeTickHasNoCallbacks(state);
    }

    sealed interface Candidate permits HopperCandidate, RegionSandboxedCandidate, AuditedBlockEntityPreparation.Candidate {
        Object source();

        boolean fresh();

        default boolean conflictsWith(Candidate other) {
            return other instanceof AuditedBlockEntityPreparation.Candidate audited ? audited.conflictsWith(this) : this.source() == other.source();
        }
    }

    private record HopperCandidate(RegionBlockEntityTickAdmission admission, HopperBlockEntity source, LevelChunk chunk) implements Candidate {
        @Override
        public boolean fresh() {
            return isNativeValid(this.admission, this.source, this.chunk) != null;
        }
    }

    record RegionSandboxedCandidate(
            RegionBlockEntityTickAdmission admission,
            Object source,
            @Nullable BlockEntity blockEntity,
            LevelChunk chunk) implements Candidate {
        @Override
        public boolean fresh() {
            if (!this.admission.isCurrent()
                    || !this.chunk.isLoaded()
                    || RegionTickCompatibility.serverThreadOnlyTypes().containsKey(this.admission.type())) {
                return false;
            }
            if (this.blockEntity != null) {
                return !this.blockEntity.isRemoved()
                        && this.blockEntity.getLevel() == this.admission.level()
                        && this.chunk.getBlockEntities().get(this.admission.position()) == this.blockEntity
                        && net.neoforged.neoforge.server.threading.api.RegionAdapterApi.canRunSandboxedBlockEntityInstance(
                                this.admission.type(), this.blockEntity);
            }
            return !this.admission.ticker().isRemoved();
        }
    }
}
