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
    private static volatile int fallbackDepth;
    @Nullable
    private RegionPlayerStatePlan plan;
    @Nullable
    private RegionPlayerSpatialLease lease;
    private final Thread worker;

    private RegionPlayerStateScope(RegionPlayerSpatialLease lease, RegionPlayerStatePlan plan, Thread worker) {
        this.lease = lease;
        this.plan = plan;
        this.worker = worker;
    }

    @Nullable
    private static RegionPlayerStateScope currentSlot() {
        final Thread current = Thread.currentThread();
        if (current instanceof RegionWorkerThread worker) {
            return worker.playerStateScope;
        }
        return fallbackDepth != 0 ? CURRENT.get() : null;
    }

    private static synchronized void incrementFallback() {
        ++fallbackDepth;
    }

    private static synchronized void decrementFallback() {
        --fallbackDepth;
    }

    static RegionPlayerStateScope open(RegionPlayerSpatialLease lease, RegionPlayerStatePlan plan) {
        return open(lease, plan, false);
    }

    static RegionPlayerStateScope open(RegionPlayerSpatialLease lease, RegionPlayerStatePlan plan, boolean laneExtensionsVerified) {
        final Thread current = Thread.currentThread();
        if (currentSlot() != null || RegionPlayerSpatialLease.current() != lease || !lease.hasStatePlan(plan)
                || !lease.owns(plan.player()) || !lease.geometryCurrent(plan.spatial(), laneExtensionsVerified) || !plan.consume(laneExtensionsVerified))
            throw new IllegalStateException("No current original player state/lease binding");
        RegionPlayerStateScope scope = new RegionPlayerStateScope(lease, plan, current);
        if (current instanceof RegionWorkerThread wt) {
            wt.playerStateScope = scope;
        } else {
            incrementFallback();
            CURRENT.set(scope);
        }
        return scope;
    }

    @Nullable
    public static RegionPlayerStateScope current() {
        RegionPlayerStateScope scope = currentSlot();
        return scope != null && scope.isCurrent() ? scope : null;
    }

    private boolean isCurrent() {
        return this.worker == Thread.currentThread() && this.plan != null && this.lease != null
                && RegionPlayerSpatialLease.current() == this.lease && this.plan.currentOwner(this.lease);
    }

    boolean bindsPlayer(ServerPlayer player) {
        return this.plan != null && this.plan.player() == player;
    }

    /** Reuses the existing level-random router for incidental vanilla draws (even empty shoulders). */
    @Nullable
    static RandomSource random() {
        RegionPlayerStateScope scope = current();
        return scope == null ? null : scope.plan.player().getRandom();
    }

    private static RegionPlayerStatePlan require() {
        RegionPlayerStateScope scope = current();
        if (scope == null) throw RegionWorkerBoundaryException.playerState("require", "No live owner-local player state permission");
        return scope.plan;
    }

    /** Keeps ordinary non-player worker contracts untouched; active player scopes cannot access foreign state. */
    public static void checkLocal(Object object) {
        RegionPlayerStateScope scope = currentSlot();
        if (scope != null && (!scope.isCurrent() || !scope.plan.owns(object))) throw RegionWorkerBoundaryException.playerState("checkLocal", "Foreign player-local state object");
    }

    /** A consumed isolated player cannot introduce a complex stack during its original tick. */
    public static boolean hasOnlyPlainLocalInventory(ServerPlayer player) {
        var scope = current();
        return scope != null && scope.plan.player() == player && scope.plan.hasOnlyPlainLocalInventory();
    }

    /** Native access to advancement storage always needs this exact original owner, even with no scope. */
    public static void checkAdvancements(PlayerAdvancements owner) {
        if (RegionWorkerScope.isNativeWorkerThread() && require().player().getAdvancements() != owner) throw RegionWorkerBoundaryException.playerState("checkAdvancements", "Foreign advancement owner");
    }

    public static void checkListenerStorage(PlayerCriterionListeners storage) {
        if (RegionWorkerScope.isNativeWorkerThread() && !require().owns(storage)) throw RegionWorkerBoundaryException.playerState("checkListenerStorage", "Foreign criterion listener owner");
    }

    public static void checkTrigger(SimpleCriterionTrigger<?> trigger, ServerPlayer player) {
        if (RegionWorkerScope.isNativeWorkerThread()) {
            var plan = require();
            if (plan.player() != player || !plan.allowsTrigger(trigger)) throw RegionWorkerBoundaryException.playerState("checkTrigger", "Uncaptured player criterion trigger");
        }
    }

    public static void checkAward(PlayerAdvancements owner, AdvancementHolder holder) {
        checkAdvancements(owner);
        if (RegionWorkerScope.isNativeWorkerThread() && !require().allowsAward(holder)) throw RegionWorkerBoundaryException.playerState("checkAward", "Uncaptured synchronous award/reward");
    }

    public static void checkReward(AdvancementRewards reward, ServerPlayer player) {
        if (RegionWorkerScope.isNativeWorkerThread() || currentSlot() != null) {
            var plan = require();
            if (plan.player() != player || !plan.allowsReward(reward)) throw RegionWorkerBoundaryException.playerState("checkReward", "Uncaptured synchronous reward");
        }
    }

    public static void checkExperience(Player player, int amount) {
        if (currentSlot() != null) {
            var plan = require();
            if (plan.player() != player || !plan.takeExperience(amount)) throw RegionWorkerBoundaryException.playerState("checkExperience", "XP result exceeds captured local reward branch");
        }
    }

    public static boolean emptyObjectives(Scoreboard scoreboard, ObjectiveCriteria criterion, Object holder) {
        if (currentSlot() == null) return false;
        if (!require().allowsScoreboard(scoreboard, criterion, holder)) throw RegionWorkerBoundaryException.playerState("emptyObjectives", "Player scoreboard mutation requires a complete serial fence");
        return require().emptyScoreboard(scoreboard, criterion, holder); // Nonempty captured objectives retain the complete ORIGINAL traversal/consumer/dirty callback.
    }

    public static net.minecraft.world.level.saveddata.maps.MapItemSavedData mapData(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.saveddata.maps.MapId id) {
        return require().mapData(level, id);
    }

    public static void checkDamage(net.minecraft.world.entity.Entity target, net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (currentSlot() != null) require().checkDamage(target, source, amount);
    }

    public static void checkMapCarrier(net.minecraft.world.level.saveddata.maps.MapItemSavedData data, Player player) {
        if (currentSlot() != null) require().checkMapCarrier(data, player);
    }

    public static void checkObjective(Scoreboard board, Object holder, net.minecraft.world.scores.Objective objective) {
        if (currentSlot() != null && !require().allowsObjective(board, holder, objective))
            throw RegionWorkerBoundaryException.playerState("checkObjective", "Uncaptured player/objective scoreboard mutation");
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> Stat<T> stat(StatType<T> type, T value) {
        if (currentSlot() == null) return null;
        Stat<?> stat = require().stat(type, value);
        if (stat == null) throw RegionWorkerBoundaryException.playerState("stat", "Uncaptured global stat cache lookup");
        return (Stat<T>) stat;
    }

    static boolean allowsPickupRemoval(net.minecraft.world.entity.Entity entity) {
        var scope = current();
        return scope != null && scope.plan.allowsPickupRemoval(entity);
    }

    public static void rejectLifecycle(String operation) {
        if (RegionWorkerScope.isNativeWorkerThread()) throw RegionWorkerBoundaryException.playerLifecycle(operation, "Player lifecycle remains server-thread-only: " + operation);
    }

    public static boolean hasThreadBinding() {
        return currentSlot() != null;
    }

    /** The body may send its original local health, experience and cooldown packets synchronously. */
    public static boolean sendConnectionBodyPacket(net.minecraft.network.Connection connection,
            net.minecraft.network.protocol.Packet<?> packet, @Nullable net.minecraft.network.PacketSendListener listener, boolean flush) {
        var scope = currentSlot();
        if (scope == null || scope.plan.phase() != RegionPlayerTickCoordinator.Phase.CONNECTION_BODY) return false;
        var plan = require();
        if (!RegionPlayerTickCoordinator.executingWorker(plan.player(), RegionPlayerTickCoordinator.Phase.CONNECTION_BODY)
                || plan.connection() != connection || listener != null
                || !(packet instanceof net.minecraft.network.protocol.game.ClientboundSetHealthPacket
                        || packet instanceof net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
                        || packet instanceof net.minecraft.network.protocol.game.ClientboundCooldownPacket)) {
            throw RegionWorkerBoundaryException.playerState("sendConnectionBodyPacket", "Uncaptured player outbound packet");
        }
        RegionTickBatch.current().runOnServerThread(plan.player().serverLevel(), () -> {
            connection.send(packet, null, flush);
            return null;
        });
        return true;
    }

    public static void rejectServicePump() {
        if (currentSlot() != null) throw RegionWorkerBoundaryException.playerState("rejectServicePump", "Owner-local player branch cannot pump arbitrary server work");
    }

    @Override
    public void close() {
        if (plan == null) return;
        final Thread currentThread = Thread.currentThread();
        if (worker != currentThread || currentSlot() != this) throw new IllegalStateException("Player state scope must physically return on its worker");
        if (currentThread instanceof RegionWorkerThread wt) {
            wt.playerStateScope = null;
        } else {
            CURRENT.remove();
            decrementFallback();
        }
        plan = null;
        lease = null;
    }
}
