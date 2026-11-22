/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.processing.FanProcessingType;
import java.util.List;
import net.minecraft.world.entity.Entity;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AirCurrent.class, remap = false)
public interface AirCurrentAccessor {
    @Accessor("caughtEntities")
    List<Entity> forgia$caughtEntities();

    @Accessor("affectedItemHandlers")
    List<Pair<TransportedItemStackHandlerBehaviour, FanProcessingType>> forgia$affectedItemHandlers();
}
