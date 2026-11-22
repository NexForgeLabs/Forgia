/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.ForgiaConfig;
import org.jetbrains.annotations.ApiStatus;

/**
 * Per-entity-type pathfinding cooldown throttle for {@link net.minecraft.world.entity.ai.navigation.PathNavigation}.
 * <p>
 * Thread-safe and allocation-free on the hot path so region workers can read cooldown rules concurrently
 * without contention. Rules are configured in {@code config/forgia.toml} via
 * {@code enableMobPathfindingThrottle}, {@code mobPathfindingCooldowns} (e.g. {@code ["primal:shark=40"]}),
 * and {@code mobPathfindingDefaultCooldownTicks}.
 */
@ApiStatus.Internal
public final class MobPathfindingThrottle {
    private static volatile List<? extends String> cachedRawEntries;
    private static volatile Map<EntityType<?>, Integer> cachedByEntityType = Collections.emptyMap();

    private MobPathfindingThrottle() {}

    /**
     * Returns the configured minimum tick interval between full {@code PathFinder.findPath} calls
     * for {@code mob}, or {@code 0} when throttling is disabled or not configured for this mob.
     */
    public static int cooldownTicksFor(Mob mob) {
        if (mob == null || !ForgiaConfig.SERVER_SPEC.isLoaded()) {
            return 0;
        }
        if (!ForgiaConfig.SERVER.enableMobPathfindingThrottle.get()) {
            return 0;
        }
        final List<? extends String> raw = ForgiaConfig.SERVER.mobPathfindingCooldowns.get();
        final Map<EntityType<?>, Integer> byType = resolveCooldownMap(raw);
        final Integer specific = byType.get(mob.getType());
        if (specific != null) {
            return specific;
        }
        return ForgiaConfig.SERVER.mobPathfindingDefaultCooldownTicks.get();
    }

    /**
     * Pure helper to check whether a new pathfinding request at {@code nowTick} should be throttled
     * given {@code lastPathfindTick} and {@code cooldownTicks}.
     */
    public static boolean shouldThrottle(long lastPathfindTick, long nowTick, int cooldownTicks) {
        if (cooldownTicks <= 0 || lastPathfindTick == Long.MIN_VALUE) {
            return false;
        }
        final long elapsed = nowTick - lastPathfindTick;
        return elapsed >= 0L && elapsed < cooldownTicks;
    }

    /**
     * Parses entries of the form {@code "namespace:path=ticks"} into a map of registry ID to positive cooldown ticks.
     */
    public static Map<ResourceLocation, Integer> parseCooldowns(List<? extends String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<ResourceLocation, Integer> parsed = new HashMap<>();
        for (final String raw : entries) {
            if (raw == null) {
                continue;
            }
            final String trimmed = raw.trim();
            final int eq = trimmed.indexOf('=');
            if (eq <= 0 || eq == trimmed.length() - 1) {
                continue;
            }
            final String idPart = trimmed.substring(0, eq).trim();
            final String ticksPart = trimmed.substring(eq + 1).trim();
            final ResourceLocation id = ResourceLocation.tryParse(idPart);
            if (id == null) {
                continue;
            }
            try {
                final int ticks = Integer.parseInt(ticksPart);
                if (ticks > 0) {
                    parsed.put(id, Math.min(ticks, 1200));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed numeric entries.
            }
        }
        return parsed;
    }

    /**
     * Validates a single config list entry ({@code "namespace:path=positiveInt"}).
     */
    public static boolean isValidEntry(Object element) {
        if (!(element instanceof String s)) {
            return false;
        }
        final String trimmed = s.trim();
        final int eq = trimmed.indexOf('=');
        if (eq <= 0 || eq == trimmed.length() - 1) {
            return false;
        }
        if (ResourceLocation.tryParse(trimmed.substring(0, eq).trim()) == null) {
            return false;
        }
        try {
            return Integer.parseInt(trimmed.substring(eq + 1).trim()) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Map<EntityType<?>, Integer> resolveCooldownMap(List<? extends String> raw) {
        if (raw == cachedRawEntries) {
            return cachedByEntityType;
        }
        final Map<ResourceLocation, Integer> parsed = parseCooldowns(raw);
        if (parsed.isEmpty()) {
            cachedByEntityType = Collections.emptyMap();
            cachedRawEntries = raw;
            return cachedByEntityType;
        }
        final IdentityHashMap<EntityType<?>, Integer> resolved = new IdentityHashMap<>(parsed.size());
        for (final Map.Entry<ResourceLocation, Integer> entry : parsed.entrySet()) {
            BuiltInRegistries.ENTITY_TYPE.getOptional(entry.getKey())
                    .ifPresent(type -> resolved.put(type, entry.getValue()));
        }
        cachedByEntityType = resolved;
        cachedRawEntries = raw;
        return resolved;
    }
}
