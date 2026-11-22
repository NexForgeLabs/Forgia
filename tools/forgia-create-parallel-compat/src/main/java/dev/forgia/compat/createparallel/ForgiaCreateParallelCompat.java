/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.forgia.compat.createparallel.mixin.KineticBlockEntityAccessor;
import dev.forgia.compat.createparallel.mixin.SmartBlockEntityAccessor;
import java.util.function.Predicate;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("forgia_create_parallel_compat")
public final class ForgiaCreateParallelCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("forgia_create_parallel_compat");
    private static final String[] CREATE_NAMESPACES = {
            "create",
            "createaddition",
            "create_connected",
            "create_enchantment_industry",
            "create_dragons_plus",
            "createdieselgenerators",
            "createpropulsion",
            "create_biotech",
            "copycats"
    };

    public ForgiaCreateParallelCompat() {
        final Predicate<BlockEntity> kineticTopologySafe = be -> {
            if (be instanceof SmartBlockEntityAccessor smart && !smart.forgiaParallel$isInitialized()) {
                return false;
            }
            if (be instanceof KineticBlockEntity kbe) {
                if (kbe.networkDirty
                        || (be instanceof KineticBlockEntityAccessor kAcc
                                && (kAcc.forgiaParallel$updateSpeed() || kAcc.forgiaParallel$validationCountdown() <= 1))) {
                    return false;
                }
                // Orphaned spinning kinetic component (e.g., after power source removal): route to server thread
                // so Create can run full cross-region kinetic detachment/revalidation cleanly.
                if (be instanceof KineticBlockEntityAccessor kAcc
                        && kAcc.forgiaParallel$speed() != 0.0F
                        && kAcc.forgiaParallel$source() == null
                        && !(kbe instanceof GeneratingKineticBlockEntity)) {
                    return false;
                }
                if (kbe instanceof GeneratingKineticBlockEntity gen && gen.reActivateSource) {
                    return false;
                }
                if (kbe instanceof HandCrankBlockEntity crank && crank.inUse > 0) {
                    return false;
                }
            }
            return true;
        };

        RegionAdapterApi.admitRegionSandboxedNamespace(CREATE_NAMESPACES);
        for (final String ns : CREATE_NAMESPACES) {
            RegionAdapterApi.registerSandboxPredicate(ns, kineticTopologySafe);
        }
        RegionAdapterApi.shareSavedData("create_logistics", "create_tracks");
        LOGGER.info("Forgia Create Parallel Compat active: admitted Create namespaces to Region Sandbox with kinetic topology guard.");
    }
}
