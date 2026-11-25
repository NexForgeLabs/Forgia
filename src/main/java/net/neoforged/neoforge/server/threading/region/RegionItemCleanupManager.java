/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ForgiaConfig;
import org.jetbrains.annotations.ApiStatus;

/**
 * Multi-region aware dropped-item cleanup manager.
 * <p>
 * Executes exclusively on the server thread at safe tick boundaries (after {@code endGlobalPhase} and
 * {@code drainWorkerServerTasks}, when no region worker holds any lease), so {@link ItemEntity#discard()}
 * is 100% thread-safe and triggers zero ownership violations.
 * <p>
 * Supports two complementary modes:
 * <ul>
 *   <li><strong>Scheduled global sweep</strong>: every {@code intervalSeconds}, removes only items whose
 *       dropped age is at least {@code minAgeSeconds} (skipping persistent {@code age == -32768} items,
 *       custom-named items when configured, and whitelisted item IDs), with an optional player broadcast
 *       warning {@code warningSeconds} before the sweep.</li>
 *   <li><strong>Per-region emergency cap</strong>: once per second per level, inspects each live
 *       {@link RegionState}; if a region exceeds {@code maxPerRegion} items, sweeps the oldest eligible
 *       items ({@code age >= minAgeSeconds}) in that overloaded region only.</li>
 * </ul>
 */
@ApiStatus.Internal
public final class RegionItemCleanupManager {
    public static final int TICKS_PER_SECOND = 20;
    public static final int PERSISTENT_ITEM_AGE = -32768;

    public record Config(
            boolean enabled,
            int intervalSeconds,
            int minAgeSeconds,
            int warningSeconds,
            int maxPerRegion,
            int emergencyHardCap,
            boolean ignoreNamedItems,
            Set<String> whitelist) {
        public int intervalTicks() {
            return Math.max(0, this.intervalSeconds) * TICKS_PER_SECOND;
        }

        public int minAgeTicks() {
            return Math.max(0, this.minAgeSeconds) * TICKS_PER_SECOND;
        }

        public int warningTicks() {
            return Math.max(0, this.warningSeconds) * TICKS_PER_SECOND;
        }
    }

    public record SweepResult(int clearedItems, int affectedRegions) {}

    public record Snapshot(
            boolean enabled,
            int intervalSeconds,
            int minAgeSeconds,
            int warningSeconds,
            int maxPerRegion,
            boolean ignoreNamedItems,
            int whitelistSize,
            long nextSweepSeconds,
            long scheduledSweeps,
            long regionCapSweeps,
            long manualSweeps,
            long totalClearedItems,
            int lastSweepClearedItems) {}

    private final LongAdder scheduledSweeps = new LongAdder();
    private final LongAdder regionCapSweeps = new LongAdder();
    private final LongAdder manualSweeps = new LongAdder();
    private final LongAdder totalClearedItems = new LongAdder();
    private volatile int lastSweepClearedItems;
    private int lastServerTickProcessed = -1;
    private int nextSweepServerTick = -1;
    private boolean warningSentForCurrentCycle;

    public RegionItemCleanupManager() {}

    public static Config loadConfig() {
        try {
            if (!ForgiaConfig.isServerConfigLoaded()) {
                return defaultDisabledConfig();
            }
            final var server = ForgiaConfig.SERVER;
            final List<? extends String> rawWhitelist = server.itemCleanupWhitelist.get();
            final Set<String> whitelist = rawWhitelist == null || rawWhitelist.isEmpty()
                    ? Set.of()
                    : Set.copyOf(new HashSet<>(rawWhitelist));
            return new Config(
                    server.enableItemCleanup.get(),
                    server.itemCleanupIntervalSeconds.get(),
                    server.itemCleanupMinAgeSeconds.get(),
                    server.itemCleanupWarningSeconds.get(),
                    server.itemCleanupMaxPerRegion.get(),
                    server.itemCleanupEmergencyHardCap.get(),
                    server.itemCleanupIgnoreNamedItems.get(),
                    whitelist);
        } catch (IllegalStateException ignored) {
            return defaultDisabledConfig();
        }
    }

    public static Config defaultDisabledConfig() {
        return new Config(false, 300, 60, 15, 256, 512, true, Set.of());
    }

    /**
     * Called on the server thread at the end of {@code level}'s tick (when all region workers are quiescent).
     */
    public void onEndLevelTick(ServerLevel level, Collection<RegionState> liveRegions) {
        final Config config = loadConfig();
        if (!config.enabled()) {
            this.nextSweepServerTick = -1;
            this.warningSentForCurrentCycle = false;
            return;
        }
        final MinecraftServer server = level.getServer();
        final int serverTick = server.getTickCount();

        // 1. Per-region cap check once per second per level (soft age-gated cap + emergency hard cap).
        if ((config.maxPerRegion() > 0 || config.emergencyHardCap() > 0)
                && (level.getGameTime() % TICKS_PER_SECOND) == 0L) {
            final SweepResult capResult = sweepOverloadedRegions(level, liveRegions, config.maxPerRegion(),
                    config.emergencyHardCap(), config.minAgeTicks(), config.ignoreNamedItems(), config.whitelist());
            if (capResult.clearedItems() > 0) {
                this.regionCapSweeps.increment();
                this.totalClearedItems.add(capResult.clearedItems());
                this.lastSweepClearedItems = capResult.clearedItems();
            }
        }

        // 2. Scheduled server-wide multi-region sweep (evaluated once per server tick across all levels).
        final int intervalTicks = config.intervalTicks();
        if (intervalTicks <= 0 || this.lastServerTickProcessed == serverTick) {
            return;
        }
        this.lastServerTickProcessed = serverTick;

        if (this.nextSweepServerTick < 0) {
            this.nextSweepServerTick = serverTick + intervalTicks;
            this.warningSentForCurrentCycle = false;
            return;
        }

        final int remainingTicks = this.nextSweepServerTick - serverTick;
        final int warningTicks = config.warningTicks();
        if (warningTicks > 0 && !this.warningSentForCurrentCycle && remainingTicks > 0 && remainingTicks <= warningTicks) {
            this.warningSentForCurrentCycle = true;
            final int secondsLeft = Math.max(1, (remainingTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
            broadcast(server, "[Forgia] 将在 " + secondsLeft + " 秒后清理掉落超过 "
                    + config.minAgeSeconds() + " 秒的地面掉落物");
        }

        if (remainingTicks <= 0) {
            this.nextSweepServerTick = serverTick + intervalTicks;
            this.warningSentForCurrentCycle = false;
            final SweepResult result = sweepAllLevels(server, liveRegions, config.minAgeTicks(),
                    config.ignoreNamedItems(), config.whitelist());
            this.scheduledSweeps.increment();
            this.totalClearedItems.add(result.clearedItems());
            this.lastSweepClearedItems = result.clearedItems();
            if (result.clearedItems() > 0) {
                broadcast(server, "[Forgia] 已清理 " + result.clearedItems() + " 个掉落超过 "
                        + config.minAgeSeconds() + " 秒的掉落物（覆盖 " + result.affectedRegions() + " 个区域）");
            }
        }
    }

    /**
     * Executes an immediate manual multi-region sweep on the server thread (invoked by {@code /forgia itemclear now}).
     */
    public SweepResult sweepNow(MinecraftServer server, Collection<RegionState> liveRegions, int minAgeSeconds) {
        final Config config = loadConfig();
        final int minAgeTicks = Math.max(0, minAgeSeconds) * TICKS_PER_SECOND;
        final SweepResult result = sweepAllLevels(server, liveRegions, minAgeTicks,
                config.ignoreNamedItems(), config.whitelist());
        this.manualSweeps.increment();
        this.totalClearedItems.add(result.clearedItems());
        this.lastSweepClearedItems = result.clearedItems();
        return result;
    }

    public Snapshot snapshot(MinecraftServer server) {
        final Config config = loadConfig();
        final long nextSeconds;
        if (!config.enabled() || this.nextSweepServerTick < 0) {
            nextSeconds = -1L;
        } else {
            nextSeconds = Math.max(0L, (this.nextSweepServerTick - server.getTickCount()) / TICKS_PER_SECOND);
        }
        return new Snapshot(
                config.enabled(),
                config.intervalSeconds(),
                config.minAgeSeconds(),
                config.warningSeconds(),
                config.maxPerRegion(),
                config.ignoreNamedItems(),
                config.whitelist().size(),
                nextSeconds,
                this.scheduledSweeps.sum(),
                this.regionCapSweeps.sum(),
                this.manualSweeps.sum(),
                this.totalClearedItems.sum(),
                this.lastSweepClearedItems);
    }

    public static boolean isEligible(
            ItemEntity item,
            int minAgeTicks,
            boolean ignoreNamedItems,
            Set<String> whitelist) {
        if (item == null || item.isRemoved()) {
            return false;
        }
        final int age = item.getAge();
        if (age == PERSISTENT_ITEM_AGE || age < minAgeTicks) {
            return false;
        }
        final ItemStack stack = item.getItem();
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        if (ignoreNamedItems && stack.has(DataComponents.CUSTOM_NAME)) {
            return false;
        }
        if (!whitelist.isEmpty()) {
            final ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemId != null && whitelist.contains(itemId.toString())) {
                return false;
            }
        }
        return true;
    }

    static SweepResult sweepOverloadedRegions(
            ServerLevel level,
            Collection<RegionState> liveRegions,
            int maxPerRegion,
            int minAgeTicks,
            boolean ignoreNamedItems,
            Set<String> whitelist) {
        return sweepOverloadedRegions(level, liveRegions, maxPerRegion, 0, minAgeTicks, ignoreNamedItems, whitelist);
    }

    static SweepResult sweepOverloadedRegions(
            ServerLevel level,
            Collection<RegionState> liveRegions,
            int maxPerRegion,
            int emergencyHardCap,
            int minAgeTicks,
            boolean ignoreNamedItems,
            Set<String> whitelist) {
        int totalCleared = 0;
        int affectedRegions = 0;
        for (final RegionState region : liveRegions) {
            if (region.isDead() || region.coordinate().level() != level) {
                continue;
            }
            int regionItems = 0;
            List<ItemEntity> agedCandidates = null;
            List<ItemEntity> freshCandidates = null;
            for (final var entry : region.entityTickEntries()) {
                final Entity entity = entry.entity();
                if (entity instanceof ItemEntity item && !item.isRemoved()) {
                    ++regionItems;
                    if (isEligible(item, minAgeTicks, ignoreNamedItems, whitelist)) {
                        if (agedCandidates == null) {
                            agedCandidates = new ArrayList<>();
                        }
                        agedCandidates.add(item);
                    } else if (emergencyHardCap > 0 && isEligible(item, 0, ignoreNamedItems, whitelist)) {
                        if (freshCandidates == null) {
                            freshCandidates = new ArrayList<>();
                        }
                        freshCandidates.add(item);
                    }
                }
            }
            int regionCleared = 0;
            // Phase A: Soft per-region cap removes oldest aged items (>= minAgeTicks).
            if (maxPerRegion > 0 && regionItems > maxPerRegion && agedCandidates != null && !agedCandidates.isEmpty()) {
                final int excess = regionItems - maxPerRegion;
                if (agedCandidates.size() > excess) {
                    agedCandidates.sort(Comparator.comparingInt(ItemEntity::getAge).reversed());
                }
                final int toRemove = Math.min(excess, agedCandidates.size());
                for (int i = 0; i < toRemove; ++i) {
                    final ItemEntity item = agedCandidates.get(i);
                    if (!item.isRemoved()) {
                        item.discard();
                        ++regionCleared;
                    }
                }
                regionItems -= regionCleared;
            }
            // Phase B: Emergency hard cap ignores minAgeTicks (still respects named + whitelisted + persistent items)
            // so sudden item explosions cannot flood main-thread entity tracking or GC.
            if (emergencyHardCap > 0 && regionItems > emergencyHardCap && freshCandidates != null && !freshCandidates.isEmpty()) {
                final int hardExcess = regionItems - emergencyHardCap;
                if (freshCandidates.size() > hardExcess) {
                    freshCandidates.sort(Comparator.comparingInt(ItemEntity::getAge).reversed());
                }
                final int toRemove = Math.min(hardExcess, freshCandidates.size());
                for (int i = 0; i < toRemove; ++i) {
                    final ItemEntity item = freshCandidates.get(i);
                    if (!item.isRemoved()) {
                        item.discard();
                        ++regionCleared;
                    }
                }
            }
            if (regionCleared > 0) {
                totalCleared += regionCleared;
                ++affectedRegions;
            }
        }
        return new SweepResult(totalCleared, affectedRegions);
    }

    static SweepResult sweepAllLevels(
            MinecraftServer server,
            Collection<RegionState> liveRegions,
            int minAgeTicks,
            boolean ignoreNamedItems,
            Set<String> whitelist) {
        int totalCleared = 0;
        int affectedRegions = 0;
        final Set<ServerLevel> coveredLevels = new HashSet<>();
        for (final RegionState region : liveRegions) {
            if (region.isDead()) {
                continue;
            }
            coveredLevels.add(region.coordinate().level());
            final List<ItemEntity> toDiscard = new ArrayList<>();
            for (final var entry : region.entityTickEntries()) {
                final Entity entity = entry.entity();
                if (entity instanceof ItemEntity item && isEligible(item, minAgeTicks, ignoreNamedItems, whitelist)) {
                    toDiscard.add(item);
                }
            }
            if (!toDiscard.isEmpty()) {
                int regionCleared = 0;
                for (final ItemEntity item : toDiscard) {
                    if (!item.isRemoved()) {
                        item.discard();
                        ++regionCleared;
                    }
                }
                if (regionCleared > 0) {
                    totalCleared += regionCleared;
                    ++affectedRegions;
                }
            }
        }
        // Fallback for any level that has no region entries yet.
        for (final ServerLevel level : server.getAllLevels()) {
            if (coveredLevels.contains(level)) {
                continue;
            }
            final List<ItemEntity> toDiscard = new ArrayList<>();
            for (final Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity item && isEligible(item, minAgeTicks, ignoreNamedItems, whitelist)) {
                    toDiscard.add(item);
                }
            }
            if (!toDiscard.isEmpty()) {
                int levelCleared = 0;
                for (final ItemEntity item : toDiscard) {
                    if (!item.isRemoved()) {
                        item.discard();
                        ++levelCleared;
                    }
                }
                if (levelCleared > 0) {
                    totalCleared += levelCleared;
                    ++affectedRegions;
                }
            }
        }
        return new SweepResult(totalCleared, affectedRegions);
    }

    private static void broadcast(MinecraftServer server, String message) {
        final var playerList = server.getPlayerList();
        if (playerList != null) {
            playerList.broadcastSystemMessage(Component.literal(message), false);
        }
    }
}
