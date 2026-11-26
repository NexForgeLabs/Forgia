/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.createparallel;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
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

    private static final String[] CREATE_CONTRAPTION_ENTITY_TYPES = {
            "create:contraption",
            "create:stationary_contraption",
            "create:gantry_contraption",
            "create:elevator_contraption",
            "create:bearing_contraption",
            "create:piston_contraption"
    };

    public ForgiaCreateParallelCompat() {
        final Predicate<BlockEntity> kineticTopologySafe = be -> {
            if (be instanceof SmartBlockEntityAccessor smart && !smart.forgiaParallel$isInitialized()) {
                return false;
            }
            if (be instanceof KineticBlockEntity kbe) {
                // Note: KineticNetwork and TorquePropagator are ConcurrentHashMap-backed via mixins,
                // so networkDirty / updateSpeed / validationCountdown run directly in Stage 3 on the
                // owning Region Worker without bouncing SequencedGearshift or actuators out of phase.
                if (kbe instanceof HandCrankBlockEntity crank && crank.inUse > 0) {
                    return false;
                }
                if (kbe instanceof BeltBlockEntity belt && belt.beltLength <= 0) {
                    return false;
                }
            }
            return true;
        };

        RegionAdapterApi.admitRegionSandboxedNamespace(CREATE_NAMESPACES);
        RegionAdapterApi.registerRegionContainedEntityType(CREATE_CONTRAPTION_ENTITY_TYPES);
        // fxntstorage's StorageNetwork controllers/interfaces clear/rebuild NonNullList slot tables
        // during BE ticks; keep only those on server thread while allowing StorageBoxEntity etc. on workers.
        RegionAdapterApi.denyRegionSandboxedBlockEntityType(
                "fxntstorage:storage_controller_entity",
                "fxntstorage:storage_interface_entity",
                "fxntstorage:storage_interface_filtered_entity");
        for (final String ns : CREATE_NAMESPACES) {
            RegionAdapterApi.registerSandboxPredicate(ns, kineticTopologySafe);
        }
        RegionAdapterApi.shareSavedData("create_logistics", "create_tracks");
        GantryProbeCommand.register();
        LOGGER.info("Forgia Create Parallel Compat active: admitted Create namespaces and region-contained contraption types to Region Sandbox.");
    }
}
