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
        ServerPlayer player = spatial.player();
        Object a0 = player.getEntityData(), a1 = player.getRandom(), a2 = player.getCombatTracker(), a3 = player.connection.chunkSender;
        List<Object> reused = state.reuseTemplateAdmissionResources(a0, a1, a2, a3);
        this.localObjects = reused != null ? reused : List.of(a0, a1, a2, a3);
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
    boolean current(boolean beforeBody) {
        return current(beforeBody, false);
    }

    boolean current(boolean beforeBody, boolean laneExtensionsVerified) {
        ServerPlayer player = spatial.player();
        if (!engine.playerCriterionStorageEnabled() || engine.playerServerOnlyReason(player) != null) return false;
        // Publisher binding must still prove the claimed objects. On the ordinary sealed path,
        // the worker repeats the full callback and intrinsic-state checks just before effects.
        if (beforeBody || declaredPayload != null) {
            if (declaredPayload == null) {
                if (!laneExtensionsVerified && RegionPlayerExtensionSnapshot.capture().refusal() != Refusal.NONE) return false;
            } else if (!declaredPayload.current()) {
                return false;
            }
            if (intrinsic(player, spatial.isLocalEntity(), spatial).refusal() != Refusal.NONE
                    || (!spatial.isLocalEntity() && !touchCallbacksSupported(spatial))) return false;
        }
        return player.connection != null && player.getEntityData() == localObjects.get(0) && player.getRandom() == localObjects.get(1)
                && player.getCombatTracker() == localObjects.get(2) && player.connection.chunkSender == localObjects.get(3);
    }

    @Nullable
    static RegionPlayerAdmissionPlan tryCaptureLocalEntity(RegionThreadingEngine engine, ServerPlayer player) {
        if (player.forgiaPlayerStateTemplate(RegionPlayerTickCoordinator.Phase.ENTITY.ordinal()) == null
                || engine.playerServerOnlyReason(player) != null) return null;
        var spatial = RegionPlayerSpatialPlan.captureLocalEntity(engine, player);
        if (spatial == null || player.getEntityData() == null || player.getRandom() == null || player.getCombatTracker() == null
                || player.connection == null || player.connection.chunkSender == null) return null;
        var state = RegionPlayerStatePlan.rebindTick(engine, spatial, true);
        if (state == null) return null;
        var plan = new RegionPlayerAdmissionPlan(engine, spatial, state, null);
        state.bindAdmission(plan);
        return plan;
    }

    @Nullable
    static RegionPlayerAdmissionPlan tryCaptureLocalConnection(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerSpatialPlan spatial) {
        if (engine.playerServerOnlyReason(player) != null
                || player.getEntityData() == null || player.getRandom() == null || player.getCombatTracker() == null
                || player.connection == null || player.connection.chunkSender == null) return null;
        var state = RegionPlayerStatePlan.rebindTick(engine, spatial, true);
        if (state == null) return null;
        var plan = new RegionPlayerAdmissionPlan(engine, spatial, state, null);
        state.bindAdmission(plan);
        return plan;
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase) {
        return capture(engine, player, phase, null);
    }

    // Only the engine's exact registered payload entry can use this callback promise. Ordinary
    // ENTITY/CONNECTION/vanilla-packet admission always retains the unknown-extension refusal.
    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            @Nullable RegionPlayerPacketPlan declaredPayload) {
        return capture(engine, player, phase, declaredPayload, null);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            @Nullable RegionPlayerPacketPlan declaredPayload, @Nullable RegionPlayerExtensionSnapshot.Check prefixEnvironment) {
        return capture(engine, player, phase, declaredPayload, prefixEnvironment, null);
    }

    static Capture capture(RegionThreadingEngine engine, ServerPlayer player, RegionPlayerTickCoordinator.Phase phase,
            @Nullable RegionPlayerPacketPlan declaredPayload, @Nullable RegionPlayerExtensionSnapshot.Check prefixEnvironment,
            @Nullable RegionPlayerSpatialPlan.Capture preparedGeometry) {
        if (!player.server.isSameThread() || RegionWorkerScope.isNativeWorkerThread()) throw new IllegalStateException("Production capture requires the publishing thread");
        if (!engine.playerCriterionStorageEnabled()) return refused(Refusal.DEPENDENCIES, "player phase dependencies disabled");
        String declared = engine.playerServerOnlyReason(player);
        if (declared != null) return refused(Refusal.SERVER_ONLY_DECLARATION, declared);
        if (declaredPayload == null) {
            var extensions = prefixEnvironment == null ? RegionPlayerExtensionSnapshot.capture() : prefixEnvironment;
            if (extensions.refusal() != Refusal.NONE) return refused(extensions.refusal(), extensions.detail());
        } else if (declaredPayload.payload == null || declaredPayload.player != player || !declaredPayload.current()) {
            return refused(Refusal.UNKNOWN_LOCAL_CALLBACK, "stale or unregistered payload declaration");
        }
        if (prefixEnvironment != null && preparedGeometry != null && preparedGeometry.plan() != null && declaredPayload == null) {
            var spatial = preparedGeometry.plan();
            // A cached template has no recipients, callbacks or shared mutations. Use the
            // current claim identities now, then fully recheck intrinsic state on the worker.
            if (player.getEntityData() != null && player.getRandom() != null && player.getCombatTracker() != null
                    && player.connection != null && player.connection.chunkSender != null) {
                var rebound = RegionPlayerStatePlan.rebindTick(engine, spatial, true);
                if (rebound != null) return admitted(engine, spatial, rebound, null);
            }
        }
        var intrinsic = intrinsic(player);
        if (intrinsic.refusal() != Refusal.NONE) return refused(intrinsic.refusal(), intrinsic.detail());
        var geometry = preparedGeometry == null ? RegionPlayerSpatialPlan.capture(engine, player, phase, declaredPayload == null) : preparedGeometry;
        if (geometry.plan() == null) return refused(Refusal.SPATIAL, geometry.refusal().name());
        // Original full query/body, synchronous XP and original outbound connection are retained.
        // Final vanilla XP removal uses the existing removal kernel at this prefix's physical join.
        if (!touchCallbacksSupported(geometry.plan())) return refused(Refusal.TOUCH_CALLBACK, "uncaptured touch or outbound recipient");
        var local = prefixEnvironment == null ? RegionPlayerStatePlan.captureFresh(engine, geometry.plan()) : RegionPlayerStatePlan.captureTick(engine, geometry.plan());
        if (local.plan() == null) return refused(Refusal.STATE, local.refusal().name());
        return admitted(engine, geometry.plan(), local.plan(), declaredPayload);
    }

    private static Capture admitted(RegionThreadingEngine engine, RegionPlayerSpatialPlan spatial, RegionPlayerStatePlan state,
            @Nullable RegionPlayerPacketPlan declaredPayload) {
        var plan = new RegionPlayerAdmissionPlan(engine, spatial, state, declaredPayload);
        state.bindAdmission(plan);
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

    // Stable VarHandle metadata for the hot path. Initialization failure is caught by intrinsic
    // and sends the original invocation to the publisher as before.
    private static final class IntrinsicFields {
        static final java.lang.invoke.VarHandle LAST_HAND = handle(LivingEntity.class, "lastHandItemStacks");
        static final java.lang.invoke.VarHandle LAST_ARMOR = handle(LivingEntity.class, "lastArmorItemStacks");
        static final java.lang.invoke.VarHandle LAST_BODY = handle(LivingEntity.class, "lastBodyItemStack");
        static final java.lang.invoke.VarHandle LAST_MAIN_HAND = handle(Player.class, "lastItemInMainHand");
        static final java.lang.invoke.VarHandle NEXT_STEP = handle(Entity.class, "nextStep");
        static final java.lang.invoke.VarHandle APPLIED_SCALE = handle(LivingEntity.class, "appliedScale");
        static final java.lang.invoke.VarHandle IN_COMBAT = handle(net.minecraft.world.damagesource.CombatTracker.class, "inCombat");
        static final java.lang.invoke.VarHandle TAKING_DAMAGE = handle(net.minecraft.world.damagesource.CombatTracker.class, "takingDamage");
        static final java.lang.invoke.VarHandle BANDWIDTH_MONITOR = handle(Connection.class, "bandwidthDebugMonitor");
        static final java.lang.invoke.VarHandle DELAYED_DISCONNECT = handle(Connection.class, "delayedDisconnect");
        static final java.lang.invoke.VarHandle DISCONNECTION_DETAILS = handle(Connection.class, "disconnectionDetails");
        static final java.lang.invoke.VarHandle HANDLING_FAULT = handle(Connection.class, "handlingFault");
        static final java.lang.invoke.VarHandle DISCONNECTION_HANDLED = handle(Connection.class, "disconnectionHandled");
        static final java.lang.invoke.VarHandle CLOSED = handle(ServerCommonPacketListenerImpl.class, "closed");
        static final java.lang.invoke.VarHandle KEEP_ALIVE_PENDING = handle(ServerCommonPacketListenerImpl.class, "keepAlivePending");
        static final java.lang.invoke.VarHandle CLIENT_FLOATING = handle(ServerGamePacketListenerImpl.class, "clientIsFloating");
        static final java.lang.invoke.VarHandle VEHICLE_FLOATING = handle(ServerGamePacketListenerImpl.class, "clientVehicleIsFloating");
        static final java.lang.invoke.VarHandle AWAITING_POSITION = handle(ServerGamePacketListenerImpl.class, "awaitingPositionFromClient");
        static final java.lang.invoke.VarHandle PENDING_ACTIONS = handle(Connection.class, "pendingActions");

        private static java.lang.invoke.VarHandle handle(Class<?> type, String name) {
            try {
                java.lang.reflect.Field field = RegionPlayerFieldAccess.resolve(type, name);
                return java.lang.invoke.MethodHandles.privateLookupIn(field.getDeclaringClass(), java.lang.invoke.MethodHandles.lookup())
                        .unreflectVarHandle(field);
            } catch (ReflectiveOperationException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    /** Only data inspection: no tick, extension callback, menu invocation, attachment supplier or task. */
    static RegionPlayerExtensionSnapshot.Check intrinsic(ServerPlayer player) {
        return intrinsic(player, false, null);
    }

    private static boolean supportingBlockWithinBounds(ServerPlayer player) {
        if (player.mainSupportingBlockPos.isEmpty()) return true;
        var pos = player.mainSupportingBlockPos.get();
        var box = player.getBoundingBox();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;
        return cx >= box.minX - 1.0 && cx < box.maxX + 1.0
                && cy >= box.minY - 2.0 && cy < box.maxY + 1.0
                && cz >= box.minZ - 1.0 && cz < box.maxZ + 1.0;
    }

    private static RegionPlayerExtensionSnapshot.Check intrinsic(ServerPlayer player, boolean localEntity, @Nullable RegionPlayerSpatialPlan spatial) {
        try {
            if (player.getClass() != ServerPlayer.class || player.gameMode == null || player.gameMode.getClass() != ServerPlayerGameMode.class
                    || player.getInventory().getClass() != Inventory.class || player.getAttributes().getClass() != AttributeMap.class)
                return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "player/gameMode/inventory/attributes");
            if (player.getEntityData() == null || player.getRandom() == null || player.getCombatTracker() == null
                    || player.getRandom() == player.serverLevel().random
                    || !(player.serverLevel().random instanceof RegionAwareRandomSource))
                return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "synced data/random/combat tracker");
            boolean levelHasAttachments = RegionWorkerScope.isNativeWorkerThread()
                    ? player.serverLevel().forgiaHasAttachmentsForAdmission()
                    : player.serverLevel().hasAttachments();
            if (player.hasAttachments() || levelHasAttachments) return check(Refusal.ATTACHMENTS, "player/level attachments");
            if (player.server.getProfiler() != InactiveProfiler.INSTANCE || player.serverLevel().getProfiler() != InactiveProfiler.INSTANCE)
                return check(Refusal.ACTIVE_PROFILER, "shared profiler");
            // The local ENTITY specialization only runs ServerPlayer.tick. Motion, breathing,
            // equipment callbacks and the damage path belong to the CONNECTION doTick body.
            if (!localEntity && (!emptyStacks(IntrinsicFields.LAST_HAND.get(player))
                    || !emptyStacks(IntrinsicFields.LAST_ARMOR.get(player))
                    || !((ItemStack) IntrinsicFields.LAST_BODY.get(player)).isEmpty()
                    || player.isUsingItem() || player.isSprinting() || player.isShiftKeyDown() || player.isSwimming()
                    || player.isInWater() || player.isInLava() || player.isInFluidType() || player.isInPowderSnow || player.wasOnFire
                    || player.getAirSupply() < player.getMaxAirSupply() || player.getForcedPose() != null
                    || player.getLastHurtMob() != null || player.getLastHurtByMob() != null
                    || player.getSleepingPos().isPresent() || !player.activeLocationDependentEnchantments().isEmpty()
                    || !RegionPlayerDamagePlan.supportedFood(player)
                    || !Float.isFinite(player.getHealth()) || !Float.isFinite(player.getMaxHealth())
                    || !Float.isFinite(player.moveDist) || player.moveDist > (float) IntrinsicFields.NEXT_STEP.get(player)
                    || !supportingBlockWithinBounds(player)
                    || !((ItemStack) IntrinsicFields.LAST_MAIN_HAND.get(player)).isEmpty()
                    || !Float.isFinite(player.getXRot()) || !Float.isFinite(player.getYRot())
                    || player.getAttributeValue(Attributes.GRAVITY) != 0.08 || player.getScale() != 1.0F
                    || (float) IntrinsicFields.APPLIED_SCALE.get(player) != 1.0F
                    || player.getRemainingFireTicks() > 0
                    || (boolean) IntrinsicFields.IN_COMBAT.get(player.getCombatTracker())
                    || (boolean) IntrinsicFields.TAKING_DAMAGE.get(player.getCombatTracker())))
                return check(Refusal.OPTIONAL_TICK_STATE, "equipment/use/pose/fluid/combat/movement history");
            if (!localEntity) {
                var border = player.serverLevel().getWorldBorder();
                if (border.getClass() != net.minecraft.world.level.border.WorldBorder.class || border.getStatus() != BorderStatus.STATIONARY
                        || !border.isWithinBounds(player.getBoundingBox().inflate(2))
                        || (spatial != null ? spatial.isEyeInWall() : player.isInWall())) {
                    return check(Refusal.OPTIONAL_TICK_STATE, "border/suffocation damage");
                }
            }
            var listener = player.connection;
            if (listener == null || listener.getClass() != ServerGamePacketListenerImpl.class || listener.chunkSender == null) return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "listener/chunk sender");
            Connection connection = listener.getConnection();
            if (connection == null || connection.getClass() != Connection.class) return check(Refusal.UNKNOWN_LOCAL_CALLBACK, "connection");
            if (!listener.isAcceptingMessages() || connection.getPacketListener() != listener || !connection.isConnected()
                    || connection.isMemoryConnection() || IntrinsicFields.BANDWIDTH_MONITOR.get(connection) != null
                    || IntrinsicFields.DELAYED_DISCONNECT.get(connection) != null
                    || IntrinsicFields.DISCONNECTION_DETAILS.get(connection) != null
                    || (boolean) IntrinsicFields.HANDLING_FAULT.get(connection)
                    || (boolean) IntrinsicFields.DISCONNECTION_HANDLED.get(connection)
                    || (boolean) IntrinsicFields.CLOSED.get(listener)
                    || (boolean) IntrinsicFields.KEEP_ALIVE_PENDING.get(listener)
                    || (boolean) IntrinsicFields.CLIENT_FLOATING.get(listener)
                    || (boolean) IntrinsicFields.VEHICLE_FLOATING.get(listener)
                    || (IntrinsicFields.AWAITING_POSITION.get(listener) != null && !RegionPlayerPacketPlan.permitsAwaitingTeleport(player))
                    || player.server.getPlayerIdleTimeout() != 0)
                return check(Refusal.NETWORK_TRANSITION, "connection/teleport/keepalive/idle transition");
            Queue<?> pending = (Queue<?>) IntrinsicFields.PENDING_ACTIONS.get(connection);
            if (!pending.isEmpty()) return check(Refusal.NETWORK_PENDING_ACTIONS, "Connection.pendingActions");
            return RegionPlayerExtensionSnapshot.Check.ok();
        } catch (RuntimeException | LinkageError failure) {
            return check(Refusal.EXTENSION_INSPECTION_UNAVAILABLE, failure.getClass().getName());
        }
    }

    private static boolean emptyStacks(Object values) {
        if (!(values instanceof List<?> list)) return false;
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof ItemStack stack) || !stack.isEmpty()) return false;
        }
        return true;
    }

    private static Object value(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
        return RegionPlayerFieldAccess.read(owner, type, name);
    }

    private static RegionPlayerExtensionSnapshot.Check check(Refusal refusal, String detail) {
        return new RegionPlayerExtensionSnapshot.Check(refusal, detail);
    }
}
