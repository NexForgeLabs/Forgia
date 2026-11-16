/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.List;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

/** Reject recognized transformed callbacks. This is not a sandbox or proof against arbitrary bytecode agents. */
final class C7RuntimeAudit {
    private static String reason = "unavailable";
    private static final boolean VERIFIED = verify();
    private static final boolean METADATA_VERIFIED = metadataVerified();

    private C7RuntimeAudit() {}

    static boolean verified() {
        return VERIFIED;
    }

    static String reason() {
        return reason;
    }

    static boolean canReadBindingMetadata() {
        return METADATA_VERIFIED;
    }

    private static boolean metadataVerified() {
        try {
            for (final var type : List.of(net.minecraft.world.level.block.entity.BlockEntity.class,
                    net.minecraft.world.level.chunk.LevelChunk.class))
                for (final var nested : type.getNestMembers())
                    for (final var method : nested.getDeclaredMethods()) if (method.getAnnotation(MixinMerged.class) != null) return false;
            return true;
        } catch (LinkageError | RuntimeException refused) {
            return false;
        }
    }

    private static boolean verify() {
        try {
            final var classes = List.of(AbstractFurnaceBlockEntity.class,
                    net.minecraft.world.level.block.entity.FurnaceBlockEntity.class,
                    net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity.class,
                    net.minecraft.world.level.block.entity.SmokerBlockEntity.class,
                    net.minecraft.world.level.block.entity.BaseContainerBlockEntity.class,
                    net.minecraft.world.level.block.entity.BlockEntity.class,
                    net.minecraft.world.Container.class, net.minecraft.world.item.ItemStack.class,
                    net.minecraft.world.level.block.entity.BlockEntityType.class,
                    net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class,
                    net.minecraft.world.level.block.state.BlockState.class, net.minecraft.world.level.block.state.StateHolder.class,
                    net.minecraft.util.Mth.class,
                    net.minecraft.world.level.block.AbstractFurnaceBlock.class,
                    net.minecraft.world.level.chunk.LevelChunk.class, net.minecraft.world.level.Level.class,
                    net.minecraft.server.level.ServerLevel.class);
            for (final var type : classes) for (final var nested : type.getNestMembers())
                for (final var method : nested.getDeclaredMethods()) {
                    final var merged = method.getAnnotation(MixinMerged.class);
                    if (merged == null) continue;
                    // This exact registration-only accessor is outside the ticking closure. No
                    // other helper or transformed reached method inherits this exception.
                    if (nested == net.minecraft.world.level.block.entity.BlockEntityType.class
                            && method.getName().equals("neoforge$setValidBlocks") && method.getReturnType() == void.class
                            && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[] { java.util.Set.class })
                            && merged.mixin().equals("net.neoforged.neoforge.mixins.BlockEntityTypeAccessor"))
                        continue;
                    reason = method.toString();
                    return false;
                }
            reason = "verified";
            return true;
        } catch (LinkageError | RuntimeException refused) {
            reason = refused.toString();
            return false;
        }
    }
}
