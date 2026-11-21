/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import java.util.List;
import net.minecraft.nbt.ListTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ArmBlockEntity.class, remap = false)
public interface ArmBlockEntityAccessor {
    @Accessor("updateInteractionPoints")
    boolean forgia$updateInteractionPoints();

    @Accessor("interactionPointTag")
    ListTag forgia$interactionPointTag();

    @Accessor("chasedPointProgress")
    float forgia$chasedPointProgress();

    @Accessor("chasedPointIndex")
    int forgia$chasedPointIndex();

    @Accessor("inputs")
    List<ArmInteractionPoint> forgia$inputs();
}
