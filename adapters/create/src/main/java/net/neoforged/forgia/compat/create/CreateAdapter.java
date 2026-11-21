/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.RegisterParallelAdaptersEvent;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;

/** Standalone server adapter. No changes to the installed Create jar or global engine switches. */
@Mod(CreateAdapter.MOD_ID)
public final class CreateAdapter {
    public static final String MOD_ID = "forgia_create";
    private static final int MAX_TYPES_PER_ADAPTER = 128;

    public CreateAdapter() {
        NeoForge.EVENT_BUS.addListener(CreateAdapter::register);
    }

    private static void register(RegisterParallelAdaptersEvent event) {
        if (!event.registrar().capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_EXECUTION_ADAPTERS)) {
            throw new IllegalStateException("Forgia Create requires the BLOCK_ENTITY_EXECUTION_ADAPTERS capability");
        }
        final Map<ResourceLocation, Class<?>> addonInherited = CreateTickAdmission.discoverAddonInheritedTypes();
        if (addonInherited.isEmpty()) {
            event.registrar().registerBlockEntityExecutionAdapter(BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                    CreateTickAdmission.ID, CreateTickAdmission.TYPES, new CreateTickAdmission());
            LogUtils.getLogger().info("Forgia Create registered resource-scoped original ticks for {} Create Core types; initialization, topology, recipe and unknown behaviour callbacks retain full serial ticks",
                    CreateTickAdmission.TYPES.size());
            return;
        }
        if (CreateTickAdmission.TYPES.size() + addonInherited.size() <= MAX_TYPES_PER_ADAPTER) {
            final Set<ResourceLocation> mergedTypes = new LinkedHashSet<>(CreateTickAdmission.TYPES);
            mergedTypes.addAll(addonInherited.keySet());
            final Set<Class<?>> extraSources = new HashSet<>(addonInherited.values());
            event.registrar().registerBlockEntityExecutionAdapter(BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                    CreateTickAdmission.ID, Set.copyOf(mergedTypes), new CreateTickAdmission(extraSources));
        } else {
            event.registrar().registerBlockEntityExecutionAdapter(BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                    CreateTickAdmission.ID, CreateTickAdmission.TYPES, new CreateTickAdmission());
            final Set<ResourceLocation> batchTypes = new LinkedHashSet<>();
            final Set<Class<?>> batchSources = new HashSet<>();
            int batchIndex = 0;
            for (final var entry : addonInherited.entrySet()) {
                batchTypes.add(entry.getKey());
                batchSources.add(entry.getValue());
                if (batchTypes.size() == MAX_TYPES_PER_ADAPTER) {
                    final ResourceLocation id = batchIndex == 0
                            ? CreateTickAdmission.ADDON_ID
                            : ResourceLocation.fromNamespaceAndPath(MOD_ID, "addon_steady_ticks_" + batchIndex);
                    event.registrar().registerBlockEntityExecutionAdapter(BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                            id, Set.copyOf(batchTypes), new CreateTickAdmission(Set.copyOf(batchSources)));
                    batchTypes.clear();
                    batchSources.clear();
                    batchIndex++;
                }
            }
            if (!batchTypes.isEmpty()) {
                final ResourceLocation id = batchIndex == 0
                        ? CreateTickAdmission.ADDON_ID
                        : ResourceLocation.fromNamespaceAndPath(MOD_ID, "addon_steady_ticks_" + batchIndex);
                event.registrar().registerBlockEntityExecutionAdapter(BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                        id, Set.copyOf(batchTypes), new CreateTickAdmission(Set.copyOf(batchSources)));
            }
        }
        LogUtils.getLogger().info("Forgia Create registered resource-scoped original ticks for {} Create Core types and {} pure addon inheritor types; initialization, topology, recipe and unknown behaviour callbacks retain full serial ticks",
                CreateTickAdmission.TYPES.size(), addonInherited.size());
    }
}
