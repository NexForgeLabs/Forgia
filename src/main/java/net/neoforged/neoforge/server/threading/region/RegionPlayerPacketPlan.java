/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Immutable original-packet constraints; augments, never replaces, complete production/state admission. */
final class RegionPlayerPacketPlan {
    private static final ThreadLocal<RegionPlayerPacketPlan> PREPARING = new ThreadLocal<>();
    @Nullable
    final Packet<?> packet;
    final ServerGamePacketListenerImpl listener;
    final ServerPlayer player;
    final double x, y, z, targetX, targetY, targetZ;
    final boolean movement;
    final boolean confirmation;
    @Nullable
    final net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> payload;

    private RegionPlayerPacketPlan(@Nullable Packet<?> packet, ServerGamePacketListenerImpl listener) {
        this(packet, listener, null);
    }

    private RegionPlayerPacketPlan(@Nullable Packet<?> packet, ServerGamePacketListenerImpl listener,
            @Nullable net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> payload) {
        this.packet = packet;
        this.listener = listener;
        this.payload = payload;
        this.player = listener.player;
        x = player.getX();
        y = player.getY();
        z = player.getZ();
        movement = packet instanceof ServerboundMovePlayerPacket;
        confirmation = packet != null && packet.getClass() == ServerboundAcceptTeleportationPacket.class;
        var move = movement ? (ServerboundMovePlayerPacket) packet : null;
        targetX = move == null ? x : move.getX(x);
        targetY = move == null ? y : move.getY(y);
        targetZ = move == null ? z : move.getZ(z);
    }

    static boolean known(Packet<?> packet) {
        Class<?> type = packet.getClass();
        return type == ServerboundSetCarriedItemPacket.class || type == ServerboundAcceptTeleportationPacket.class
                || type == ServerboundContainerClickPacket.class || type == ServerboundMovePlayerPacket.Pos.class
                || type == ServerboundMovePlayerPacket.PosRot.class || type == ServerboundMovePlayerPacket.Rot.class
                || type == ServerboundMovePlayerPacket.StatusOnly.class;
    }

    @Nullable
    static RegionPlayerPacketPlan capture(Packet<?> packet, ServerGamePacketListenerImpl listener) {
        if (!known(packet) || listener.player == null) return null;
        var plan = new RegionPlayerPacketPlan(packet, listener);
        return plan.current() ? plan : null;
    }

    @Nullable
    static RegionPlayerPacketPlan chat(ServerGamePacketListenerImpl listener) {
        var plan = new RegionPlayerPacketPlan(null, listener);
        return plan.current() ? plan : null;
    }

    @Nullable
    static RegionPlayerPacketPlan payload(Packet<?> packet, ServerGamePacketListenerImpl listener,
            net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<?> declaration) {
        if (!(packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) || listener.player == null) return null;
        var plan = new RegionPlayerPacketPlan(packet, listener, declaration);
        return plan.current() ? plan : null;
    }

    boolean current() {
        if (listener.player != player || player.connection != listener || player.getX() != x || player.getY() != y || player.getZ() != z) return false;
        if (payload != null) {
            return packet instanceof net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket custom
                    && payload.matches(custom.payload())
                    && net.neoforged.neoforge.network.registration.NetworkRegistry.isRegionPayloadHandler(payload.id(), payload);
        }
        try {
            if (packet == null) {
                // detectRateSpam adds 20. A synchronous disconnect/retirement must stay entirely
                // serial, never run while the original actor's worker state lease is parked.
                int spam = (int) field(listener, ServerGamePacketListenerImpl.class, "chatSpamTickCount");
                return spam >= 0 && spam <= 180;
            }
            if (packet instanceof ServerboundSetCarriedItemPacket selected) {
                int slot = selected.getSlot();
                return slot >= 0 && slot < 9 && !player.isUsingItem() && player.getUseItem().isEmpty()
                        && player.getInventory().getSelected().isEmpty() && player.getInventory().getItem(slot).isEmpty();
            }
            if (movement) {
                var move = (ServerboundMovePlayerPacket) packet;
                double dx = targetX - x, dz = targetZ - z;
                return Double.isFinite(targetX) && Double.isFinite(targetY) && Double.isFinite(targetZ)
                        && Float.isFinite(move.getYRot(player.getYRot())) && Float.isFinite(move.getXRot(player.getXRot()))
                        && targetY == y && y == Math.floor(y) && Math.abs(dx) <= .25 && Math.abs(dz) <= .25 && move.isOnGround()
                        && !player.isChangingDimension() && !player.isUsingItem() && player.fallDistance == 0
                        && player.moveDist + Math.sqrt(dx * dx + dz * dz) * .6 < (float) field(player, net.minecraft.world.entity.Entity.class, "nextStep")
                        && (int) field(listener, ServerGamePacketListenerImpl.class, "tickCount") > 0
                        && field(listener, ServerGamePacketListenerImpl.class, "awaitingPositionFromClient") == null
                        && (double) field(listener, ServerGamePacketListenerImpl.class, "lastGoodX") == x
                        && (double) field(listener, ServerGamePacketListenerImpl.class, "lastGoodY") == y
                        && (double) field(listener, ServerGamePacketListenerImpl.class, "lastGoodZ") == z
                        && Math.abs(targetX - (double) field(listener, ServerGamePacketListenerImpl.class, "firstGoodX")) <= 1
                        && Math.abs(targetZ - (double) field(listener, ServerGamePacketListenerImpl.class, "firstGoodZ")) <= 1;
            }
            if (confirmation) {
                var awaiting = field(listener, ServerGamePacketListenerImpl.class, "awaitingPositionFromClient");
                return !player.isChangingDimension() && awaiting instanceof Vec3 pos && pos.x == x && pos.y == y && pos.z == z
                        && ((ServerboundAcceptTeleportationPacket) packet).getId() == (int) field(listener, ServerGamePacketListenerImpl.class, "awaitingTeleport");
            }
            var click = (ServerboundContainerClickPacket) packet;
            var menu = player.containerMenu;
            var chest = RegionPlayerContainerPlan.exactChest(player);
            if (chest != null && click.getContainerId() == menu.containerId && click.getClickType() == ClickType.QUICK_MOVE
                    && click.getButtonNum() == 0 && click.getSlotNum() >= 0 && click.getSlotNum() < 27
                    && menu.getCarried().isEmpty() && click.getCarriedItem().isEmpty() && click.getChangedSlots().size() <= 63) {
                for (int i = 0; i < 27; i++) if (!plain(chest.getItem(i))) return false;
                return menu.slots.get(click.getSlotNum()).getClass() == Slot.class && plain(menu.slots.get(click.getSlotNum()).getItem())
                        && click.getChangedSlots().int2ObjectEntrySet().stream().allMatch(entry -> entry.getIntKey() >= 0
                                && entry.getIntKey() < 63 && menu.slots.get(entry.getIntKey()).getClass() == Slot.class && plain(entry.getValue()));
            }
            if (menu != player.inventoryMenu || menu.getClass() != InventoryMenu.class || click.getContainerId() != menu.containerId
                    || click.getClickType() != ClickType.PICKUP || click.getButtonNum() != 0 || click.getSlotNum() < 9 || click.getSlotNum() > 44
                    || !menu.getCarried().isEmpty() || click.getChangedSlots().size() > 1 || !plain(click.getCarriedItem()))
                return false;
            Slot slot = menu.slots.get(click.getSlotNum());
            return slot.getClass() == Slot.class && slot.container == player.getInventory() && plain(slot.getItem())
                    && slot.getContainerSlot() != player.getInventory().selected
                    && click.getChangedSlots().int2ObjectEntrySet().stream().allMatch(entry -> entry.getIntKey() == click.getSlotNum() && plain(entry.getValue()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
            return false;
        }
    }

    private static boolean plain(ItemStack stack) {
        return stack != null && (stack.isEmpty() || stack.getItem().getClass() == Item.class && RegionPlayerInventoryPlan.supportedStack(stack));
    }

    Preparation prepare() {
        if (PREPARING.get() != null) throw new IllegalStateException("Nested packet preparation");
        PREPARING.set(this);
        return new Preparation(this);
    }

    static boolean permitsAwaitingTeleport(ServerPlayer player) {
        var plan = PREPARING.get();
        return plan != null && plan.player == player && plan.confirmation && plan.current();
    }

    static boolean preparesMovement(ServerPlayer player) {
        var plan = PREPARING.get();
        return plan != null && plan.player == player && plan.movement;
    }

    static AABB bodyExtent(ServerPlayer player, AABB body) {
        var plan = PREPARING.get();
        return plan == null || plan.player != player || !plan.movement ? body : body.minmax(body.move(plan.targetX - plan.x, 0, plan.targetZ - plan.z));
    }

    static AABB blockExtent(ServerPlayer player, AABB blocks) {
        var plan = PREPARING.get();
        if (plan != null && plan.player == player && plan.packet instanceof ServerboundContainerClickPacket) {
            var chest = RegionPlayerContainerPlan.exactChest(player);
            // Original setChanged -> comparator-neighbor notification may inspect the second neighbor.
            if (chest != null) return blocks.minmax(new AABB(chest.getBlockPos()).inflate(2));
        }
        return blocks;
    }

    static final class Preparation implements AutoCloseable {
        private final RegionPlayerPacketPlan plan;

        Preparation(RegionPlayerPacketPlan plan) {
            this.plan = plan;
        }

        @Override
        public void close() {
            if (PREPARING.get() != plan) throw new IllegalStateException("Packet preparation stack mismatch");
            PREPARING.remove();
        }
    }

    private static Object field(Object object, Class<?> type, String name) throws ReflectiveOperationException {
        return RegionPlayerFieldAccess.read(object, type, name);
    }
}
