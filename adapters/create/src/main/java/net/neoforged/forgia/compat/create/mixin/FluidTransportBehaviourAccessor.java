/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.mixin;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import java.util.Map;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FluidTransportBehaviour.class, remap = false)
public interface FluidTransportBehaviourAccessor {
    @Accessor("interfaces")
    Map<Direction, PipeConnection> forgia$interfaces();

    @Accessor("phase")
    FluidTransportBehaviour.UpdatePhase forgia$phase();
}
