/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Core-created original-wrapper evidence, subordinate to the original Ticket and entered Scope. */
sealed interface BoundBlockEntityInvocation permits CoreModPreparation.Manifest, ExecutionBlockEntityPreparation.Manifest {
    RegionBlockEntityTickAdmission admission();

    BlockEntity source();

    BlockState state();

    boolean workerCurrent();
}
