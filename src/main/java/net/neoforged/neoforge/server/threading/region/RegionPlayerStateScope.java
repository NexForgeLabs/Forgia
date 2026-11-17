/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Narrow owner-local capability subordinate to the original spatial lease and its published batch. */
@ApiStatus.Internal
public final class RegionPlayerStateScope implements AutoCloseable {
    private static final ThreadLocal<RegionPlayerStateScope> CURRENT = new ThreadLocal<>();
    @Nullable
    private RegionPlayerStatePlan plan;
    @Nullable
    private RegionPlayerSpatialLease lease;
    private final Thread worker;

    private RegionPlayerStateScope(RegionPlayerSpatialLease lease, RegionPlayerStatePlan plan) {
        this.lease = lease;
        this.plan = plan;
        this.worker = Thread.currentThread();
    }

    static RegionPlayerStateScope open(RegionPlayerSpatialLease lease, RegionPlayerStatePlan plan) {
        if (CURRENT.get() != null || RegionPlayerSpatialLease.current() != lease || !lease.hasStatePlan(plan)
                || !lease.owns(plan.player()) || !lease.geometryCurrent(plan.spatial()) || !plan.consume())
            throw new IllegalStateException("No current original player state/lease binding");
        RegionPlayerStateScope scope = new RegionPlayerStateScope(lease, plan);
        CURRENT.set(scope);
        return scope;
    }

    @Nullable
    public static RegionPlayerStateScope current() {
        RegionPlayerStateScope scope = CURRENT.get();
        return scope != null && scope.worker == Thread.currentThread() && scope.plan != null && scope.lease != null
                && RegionPlayerSpatialLease.current() == scope.lease && scope.plan.currentOwner() ? scope : null;
    }

    /** Reuses the existing level-random router for incidental vanilla draws (even empty shoulders). */
    @Nullable
    static RandomSource random() {
        RegionPlayerStateScope scope = current();
        return scope == null ? null : scope.plan.player().getRandom();
    }

    private static RegionPlayerStatePlan require() {
        RegionPlayerStateScope scope = current();
        if (scope == null) throw new IllegalStateException("No live owner-local player state permission");
        return scope.plan;
    }

    /** Keeps ordinary non-player worker contracts untouched; active player scopes cannot access foreign state. */
    public static void checkLocal(Object object) {
        if (CURRENT.get() != null && !require().owns(object)) throw new IllegalStateException("Foreign player-local state object");
    }

    /** Native access to advancement storage always needs this exact original owner, even with no scope. */
    public static void checkAdvancements(PlayerAdvancements owner) {
        if (RegionWorkerScope.isNativeWorkerThread() && require().player().getAdvancements() != owner) throw new IllegalStateException("Foreign advancement owner");
    }

    public static void checkListenerStorage(PlayerCriterionListeners storage) {
        if (RegionWorkerScope.isNativeWorkerThread() && !require().owns(storage)) throw new IllegalStateException("Foreign criterion listener owner");
    }

    public static void checkTrigger(SimpleCriterionTrigger<?> trigger, ServerPlayer player) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            var plan = require();
            if (plan.player() != player || !plan.allowsTrigger(trigger)) throw new IllegalStateException("Uncaptured player criterion trigger");
        }
    }

    public static void checkAward(PlayerAdvancements owner, AdvancementHolder holder) {
        checkAdvancements(owner);
        if (RegionWorkerScope.isNativeWorkerThread() && !require().allowsAward(holder)) throw new IllegalStateException("Uncaptured synchronous award/reward");
    }

    public static void checkReward(AdvancementRewards reward, ServerPlayer player) {
        if (RegionWorkerScope.isNativeWorkerThread() || CURRENT.get() != null) {
            var plan = require();
            if (plan.player() != player || !plan.allowsReward(reward)) throw new IllegalStateException("Uncaptured synchronous reward");
        }
    }

    public static void checkExperience(Player player, int amount) {
        if (CURRENT.get() != null) {
            var plan = require();
            if (plan.player() != player || !plan.takeExperience(amount)) throw new IllegalStateException("XP result exceeds captured local reward branch");
        }
    }

    public static boolean emptyObjectives(Scoreboard scoreboard, ObjectiveCriteria criterion, Object holder) {
        if (CURRENT.get() == null) return false;
        if (!require().emptyScoreboard(scoreboard, criterion, holder)) throw new IllegalStateException("Player scoreboard mutation requires a complete serial fence");
        return true; // Original immutable empty answer: consumer has ZERO invocations, no delayed write.
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> Stat<T> stat(StatType<T> type, T value) {
        if (CURRENT.get() == null) return null;
        Stat<?> stat = require().stat(type, value);
        if (stat == null) throw new IllegalStateException("Uncaptured global stat cache lookup");
        return (Stat<T>) stat;
    }

    public static void rejectLifecycle(String operation) {
        if (RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Player lifecycle remains server-thread-only: " + operation);
    }

    public static boolean hasThreadBinding() {
        return CURRENT.get() != null;
    }

    public static void rejectServicePump() {
        if (CURRENT.get() != null) throw new IllegalStateException("Owner-local player branch cannot pump arbitrary server work");
    }

    @Override
    public void close() {
        if (plan == null) return;
        if (worker != Thread.currentThread() || CURRENT.get() != this) throw new IllegalStateException("Player state scope must physically return on its worker");
        CURRENT.remove();
        plan = null;
        lease = null;
    }
}
