/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.List;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.border.BorderStatus;
import net.neoforged.neoforge.server.threading.RegionAwareRandomSource;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One pre-body production contract for each COMPLETE original ENTITY or CONNECTION invocation.
 * This plans permission only; it never invokes a tick or creates an executor/lease/identity/scope.
 */
@ApiStatus.Internal
public final class RegionPlayerAdmissionPlan {
    public enum Refusal {
        NONE, DEPENDENCIES, SERVER_ONLY_DECLARATION, UNKNOWN_MOD, UNKNOWN_TRANSFORM,
        UNKNOWN_LISTENER, EXTENSION_INSPECTION_UNAVAILABLE, UNKNOWN_LOCAL_CALLBACK,
        ATTACHMENTS, OPTIONAL_TICK_STATE, NETWORK_TRANSITION, NETWORK_PENDING_ACTIONS,
        ACTIVE_PROFILER, SPATIAL, STATE, TOUCH_CALLBACK
    }

    public record Capture(@Nullable RegionPlayerAdmissionPlan plan, Refusal refusal, String detail) {
        public Capture {
            if ((plan == null) == (refusal == Refusal.NONE)) throw new IllegalArgumentException("Explicit production admission result required");
        }
    }

    private final RegionThreadingEngine engine;
    private final RegionPlayerSpatialPlan spatial;
    private final RegionPlayerStatePlan state;
    private final List<Object> localObjects;
    @Nullable
    private final RegionPlayerPacketPlan declaredPayload;

    private RegionPlayerAdmissionPlan(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, RegionPlayerStatePlan state) {
        this(engine, spatial, state, null);
    }

    private RegionPlayerAdmissionPlan(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, RegionPlayerStatePlan state,
            @Nullable RegionPlayerPacketPlan declaredPayload) {
        this.engine = engine;
        this.spatial = spatial;
        this.state = state;
        this.localObjects = localObjects(spatial.player());
        this.declaredPayload = declaredPayload;
    }

    public RegionPlayerSpatialPlan spatial() {
        return spatial;
    }

    public RegionPlayerStatePlan state() {
        return state;
    }

    public RegionPlayerTickCoordinator.Phase phase() {
        return spatial.phase();
    }

    /** Publisher check immediately before binding/dispatch, after every original serial fence. */
    public boolean revalidate() {
        return state.revalidate();
    }

    List<Object> resources() {
        return localObjects;
    }

    private static List<Object> localObjects(ServerPlayer player) {
        return List.of(player.getEntityData(), player.getRandom(), player.getCombatTracker(), player.connection.chunkSender);
    }

    // Also called by the EXISTING state scope's consume(), before STARTED on the original worker.
    // The dispatcher must supply its original phase/lifecycle ticket and network quiescence as well.
    boolean current() {
        ServerPlayer player = spatial.player();
        if (!engine.playerCriterionStorageEnabled() || engine.playerServerOnlyReason(player) != null) return false;
        if (declaredPayload == null ? RegionPlayerExtensionSnapshot.capture().refusal() != Refusal.NONE : !declaredPayload.current()) return false;
        if (intrinsic(player).refusal() != Refusal.NONE) return false;
        List<Object> current = localObjects(player);
        for (int i = 0; i < localObjects.size(); i++) if (current.get(i) != localObjects.get(i)) return false;
        return touchCallbacksSupported(spatial);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        return capture(engine, player, phase, null);
    }

    // Only the engine's exact registered payload entry can use this callback promise. Ordinary
    // ENTITY/CONNECTION/vanilla-packet admission always retains the unknown-extension refusal.
    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            @Nullable RegionPlayerPacketPlan declaredPayload) {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Production capture requires the publishing thread");
        if (!engine.playerCriterionStorageEnabled()) return refused(Refusal.DEPENDENCIES, "player phase dependencies disabled");
        String declared = engine.playerServerOnlyReason(player);
        if (declared != null) return refused(Refusal.SERVER_ONLY_DECLARATION, declared);
        if (declaredPayload == null) {
            var extensions = RegionPlayerExtensionSnapshot.capture();
            if (extensions.refusal() != Refusal.NONE) return refused(extensions.refusal(), extensions.detail());
        } else if (declaredPayload.payload == null || declaredPayload.player != player || !declaredPayload.current()) {
            return refused(Refusal.UNKNOWN_LOCAL_CALLBACK, "stale or unregistered payload declaration");
        }
        var intrinsic = intrinsic(player);
        if (intrinsic.refusal() != Refusal.NONE) return refused(intrinsic.refusal(), intrinsic.detail());
        var geometry = engine.capturePlayerSpatialPlan(player, phase);
        if (geometry.plan() == null) return refused(Refusal.SPATIAL, geometry.refusal().name());
        // Original full query/body, synchronous XP and original outbound connection are retained.
        // Final vanilla XP removal uses the existing removal kernel at this prefix's physical join.
        if (!touchCallbacksSupported(geometry.plan())) return refused(Refusal.TOUCH_CALLBACK, "uncaptured touch or outbound recipient");
        var local = engine.capturePlayerStatePlan(geometry.plan());
        if (local.plan() == null) return refused(Refusal.STATE, local.refusal().name());
        var plan = new RegionPlayerAdmissionPlan(engine, geometry.plan(), local.plan(), declaredPayload);
        local.plan().bindAdmission(plan);
        return new Capture(plan, Refusal.NONE, "");
    }

    private static boolean touchCallbacksSupported(RegionPlayerSpatialPlan spatial) {
        ServerPlayer player = spatial.player();
        for (Entity target : spatial.members().keySet()) {
            if (target == player || !target.getBoundingBox().intersects(spatial.footprint().queryBounds())) continue;
            if (target.level() != player.level() || target.isRemoved() || target.hasAttachments()
                    || target.isPassenger() || !target.getPassengers().isEmpty())
                return false;
            try {
                if (target.getClass() == ExperienceOrb.class && target.getType() == EntityType.EXPERIENCE_ORB) {
                    if (((ExperienceOrb) target).getValue() <= 0 || (int) value(target, ExperienceOrb.class, "count") < 1) return false;
                } else if (target.getClass() == ItemEntity.class && target.getType() == EntityType.ITEM) {
                    var item = (ItemEntity) target;
                    if (!RegionPlayerInventoryPlan.supportedStack(item.getItem()) || item.getItem().isEmpty()
                            || value(item, ItemEntity.class, "thrower") != null || value(item, ItemEntity.class, "cachedThrower") != null
                            || (value(item, ItemEntity.class, "target") != null && !player.getUUID().equals(value(item, ItemEntity.class, "target"))))
                        return false;
                } else return false;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
            var chunkMap = player.serverLevel().getChunkSource().chunkMap;
            if (chunkMap == null || !chunkMap.forgiaHasExclusiveEntityWatcher(target, player.connection)) return false;
        }
        return true;
    }

    private static Capture refused(Refusal refusal, String detail) {
        return new Capture(null, refusal, detail);
    }

    /** Only data inspection: no tick, extension callback, menu invocation, attachment supplier or task. */
    static RegionPlayerExtensionSnapshot.Check intrinsic(ServerPlayer player) {
        try {
            if (player.getClass() != ServerPlayer.class || player.gameMode == null || player.gameMode.getClass() != ServerPlayerGameMode.class
                    || player.getInventory().getClass() != Inventory.class || player.getAttributes().getClass() != AttributeMap.class)
                return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "player/gameMode/inventory/attributes");
            if (player.getEntityData() == null || player.getRandom() == null || player.getCombatTracker() == null
                    || player.getRandom() == player.serverLevel().random
                    || !(player.serverLevel().random instanceof RegionAwareRandomSource))
                return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "synced data/random/combat tracker");
            if (player.hasAttachments() || player.serverLevel().hasAttachments()) return check(Refusal.ATTACHMENTS, "player/level attachments");
            if (player.server.getProfiler() != InactiveProfiler.INSTANCE || player.serverLevel().getProfiler() != InactiveProfiler.INSTANCE)
                return check(Refusal.ACTIVE_PROFILER, "shared profiler");
            if (!emptyStacks(value(player, LivingEntity.class, "lastHandItemStacks"))
                    || !emptyStacks(value(player, LivingEntity.class, "lastArmorItemStacks"))
                    || !((ItemStack) value(player, LivingEntity.class, "lastBodyItemStack")).isEmpty()
                    || player.isUsingItem() || player.isSprinting() || player.isShiftKeyDown() || player.isSwimming()
                    || player.isInWater() || player.isInLava() || player.isInFluidType() || player.isInPowderSnow || player.wasOnFire
                    || player.getAirSupply() < player.getMaxAirSupply() || player.getForcedPose() != null
                    || player.getLastHurtMob() != null || player.getLastHurtByMob() != null
                    || player.getSleepingPos().isPresent() || !player.activeLocationDependentEnchantments().isEmpty()
                    || !RegionPlayerDamagePlan.supportedFood(player)
                    || !Float.isFinite(player.getHealth()) || !Float.isFinite(player.getMaxHealth())
                    || !Float.isFinite(player.moveDist) || player.moveDist > (float) value(player, Entity.class, "nextStep")
                    || player.mainSupportingBlockPos.stream().anyMatch(pos -> !player.getBoundingBox().inflate(1, 1, 1).expandTowards(0, -1, 0).contains(pos.getCenter()))
                    || !((ItemStack) value(player, Player.class, "lastItemInMainHand")).isEmpty()
                    || !Float.isFinite(player.getXRot()) || !Float.isFinite(player.getYRot())
                    || player.getAttributeValue(Attributes.GRAVITY) != 0.08 || player.getScale() != 1.0F
                    || (float) value(player, LivingEntity.class, "appliedScale") != 1.0F
                    || player.getRemainingFireTicks() > 0
                    || (boolean) value(player.getCombatTracker(), net.minecraft.world.damagesource.CombatTracker.class, "inCombat")
                    || (boolean) value(player.getCombatTracker(), net.minecraft.world.damagesource.CombatTracker.class, "takingDamage"))
                return check(Refusal.OPTIONAL_TICK_STATE, "equipment/use/pose/fluid/combat/movement history");
            var border = player.serverLevel().getWorldBorder();
            if (border.getClass() != net.minecraft.world.level.border.WorldBorder.class || border.getStatus() != BorderStatus.STATIONARY
                    || !border.isWithinBounds(player.getBoundingBox().inflate(2)) || player.isInWall())
                return check(Refusal.OPTIONAL_TICK_STATE, "border/suffocation damage");
            var listener = player.connection;
            if (listener == null || listener.getClass() != ServerGamePacketListenerImpl.class || listener.chunkSender == null) return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "listener/chunk sender");
            Connection connection = listener.getConnection();
            if (connection == null || connection.getClass() != Connection.class) return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "connection");
            if (!listener.isAcceptingMessages() || connection.getPacketListener() != listener || !connection.isConnected()
                    || connection.isMemoryConnection() || value(connection, Connection.class, "bandwidthDebugMonitor") != null
                    || value(connection, Connection.class, "delayedDisconnect") != null
                    || value(connection, Connection.class, "disconnectionDetails") != null
                    || (boolean) value(connection, Connection.class, "handlingFault")
                    || (boolean) value(connection, Connection.class, "disconnectionHandled")
                    || (boolean) value(listener, ServerCommonPacketListenerImpl.class, "closed")
                    || (boolean) value(listener, ServerCommonPacketListenerImpl.class, "keepAlivePending")
                    || (boolean) value(listener, ServerGamePacketListenerImpl.class, "clientIsFloating")
                    || (boolean) value(listener, ServerGamePacketListenerImpl.class, "clientVehicleIsFloating")
                    || (value(listener, ServerGamePacketListenerImpl.class, "awaitingPositionFromClient") != null && !RegionPlayerPacketPlan.permitsAwaitingTeleport(player))
                    || player.server.getPlayerIdleTimeout() != 0)
                return check(Refusal.NETWORK_TRANSITION, "connection/teleport/keepalive/idle transition");
            Queue<?> pending = (Queue<?>) value(connection, Connection.class, "pendingActions");
            if (!pending.isEmpty()) return check(Refusal.NETWORK_PENDING_ACTIONS, "Connection.pendingActions");
            return RegionPlayerExtensionSnapshot.Check.ok();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return check(Refusal.EXTENSION_INSPECTION_UNAVAILABLE, failure.getClass().getName());
        }
    }

    private static boolean emptyStacks(Object values) {
        return values instanceof List<?> list && list.stream().allMatch(stack -> stack instanceof ItemStack item && item.isEmpty());
    }

    private static Object value(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
        return RegionPlayerFieldAccess.read(owner, type, name);
    }

    private static RegionPlayerExtensionSnapshot.Check check(Refusal refusal, String detail) {
        return new RegionPlayerExtensionSnapshot.Check(refusal, detail);
    }
}
