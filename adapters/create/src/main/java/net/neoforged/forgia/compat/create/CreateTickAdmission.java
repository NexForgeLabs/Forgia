/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter;
import org.jetbrains.annotations.Nullable;

/** Original ticks only. Network inspection is quiescent; workers read their instance and frozen ids only. */
public final class CreateTickAdmission implements AuditedBlockEntityTickAdapter {
    public static final ResourceLocation ID = ResourceLocation.parse("forgia_create:steady_ticks");
    public static final Set<ResourceLocation> TYPES = Set.of(
            type("simple_kinetic"), type("encased_shaft"), type("encased_cogwheel"),
            type("encased_large_cogwheel"), type("gearbox"), type("millstone"),
            type("water_wheel"), type("large_water_wheel"), type("clutch"), type("gearshift"));
    private static final Set<Class<?>> LOCAL_BODIES = Set.of(KineticBlockEntity.class, SimpleKineticBlockEntity.class,
            BracketedKineticBlockEntity.class, GearboxBlockEntity.class, ClutchBlockEntity.class, GearshiftBlockEntity.class);
    private static final Set<Class<?>> CLASSES = Set.of(KineticBlockEntity.class, SimpleKineticBlockEntity.class,
            BracketedKineticBlockEntity.class, GearboxBlockEntity.class, MillstoneBlockEntity.class,
            WaterWheelBlockEntity.class, LargeWaterWheelBlockEntity.class, ClutchBlockEntity.class, GearshiftBlockEntity.class);
    private static final Set<Class<?>> BEHAVIOURS = Set.of(BracketedBlockEntityBehaviour.class,
            DirectBeltInputBehaviour.class, AdvancementBehaviour.class);
    private static final int MAX_NETWORKS = 4096;
    private static final int MAX_MEMBERS = 65536;
    private final boolean verified;
    private volatile boolean canonicalPreparation;
    @Nullable
    private volatile Prepared prepared;

    private record Prepared(ResourceKey<Level> dimension, long gameTime, Set<Long> stableNetworks) {
        Prepared {
            stableNetworks = Set.copyOf(stableNetworks);
        }
    }

    public CreateTickAdmission() {
        this.verified = CreateAudit.verify();
    }

    private static ResourceLocation type(String name) {
        return ResourceLocation.fromNamespaceAndPath("create", name);
    }

    @Override
    public void prepare(ServerLevel level) {
        this.canonicalPreparation = false;
        this.prepared = null;
        if (!this.verified) return;
        final var networks = TorquePropagatorAccessor.forgia$networks().get(level);
        final Set<Long> stable = new HashSet<>();
        final Set<Long> aliasedNetworks = new HashSet<>();
        final var inventories = new IdentityHashMap<ItemStackHandler, Long>();
        int members = 0;
        if (networks != null && networks.size() <= MAX_NETWORKS) {
            for (final var entry : networks.entrySet()) {
                final KineticNetwork network = entry.getValue();
                if (network == null || network.getClass() != KineticNetwork.class) continue;
                members += network.members.size();
                if (members > MAX_MEMBERS) {
                    stable.clear();
                    break;
                }
                if (!network.initialized || !Objects.equals(entry.getKey(), network.id)
                        || network.getSize() != network.members.size()
                        || !network.members.keySet().containsAll(network.sources.keySet()))
                    continue;
                boolean complete = true;
                for (final KineticBlockEntity member : network.members.keySet()) {
                    if (member == null || member.isRemoved() || member.getLevel() != level
                            || !Objects.equals(member.network, entry.getKey()) || topology(member) != Decision.ADMITTED) {
                        complete = false;
                        break;
                    }
                    if (member instanceof MillstoneBlockEntity millstone) {
                        for (final var inventory : new ItemStackHandler[] { millstone.inputInv, millstone.outputInv }) {
                            final Long previous = inventories.putIfAbsent(inventory, entry.getKey());
                            if (previous != null) {
                                aliasedNetworks.add(previous);
                                aliasedNetworks.add(entry.getKey());
                            }
                        }
                    }
                    final var pos = member.getBlockPos();
                    final LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
                    if (chunk == null || !chunk.isLoaded() || chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.CHECK) != member) {
                        complete = false;
                        break;
                    }
                }
                if (complete) stable.add(entry.getKey());
            }
        }
        stable.removeAll(aliasedNetworks);
        this.prepared = new Prepared(level.dimension(), level.getGameTime(), stable);
    }

    @Override
    public Audit audit() {
        return CreateAudit.nativeAudit(LOCAL_BODIES);
    }

    @Override
    @Nullable
    public Proof inspect(BlockEntity blockEntity, Class<?> tickerImplementation) {
        if (!this.verified || blockEntity == null || !LOCAL_BODIES.contains(blockEntity.getClass())
                || tickerImplementation != SmartBlockEntityTicker.class || blockEntity.hasAttachments())
            return null;
        final var kinetic = (KineticBlockEntity) blockEntity;
        final var behaviours = ((SmartBlockEntityAccessor) kinetic).forgia$behaviours();
        if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class || topology(kinetic) != Decision.ADMITTED)
            return null;
        final var storage = new ArrayList<Storage>();
        storage.add(new Storage(kinetic, Access.WRITE));
        storage.add(new Storage(behaviours, Access.READ));
        storage.add(new Storage(((KineticBlockEntityAccessor) kinetic).forgia$effects(), Access.WRITE));
        for (final var behaviour : behaviours.values()) {
            // These exact behaviours inherit only the local scalar lazy-counter branch. Other
            // behaviours may retain legacy admission but cannot inherit this stronger contract.
            if (behaviour.getClass() != BracketedBlockEntityBehaviour.class && behaviour.getClass() != AdvancementBehaviour.class)
                return null;
            storage.add(new Storage(behaviour, Access.WRITE));
        }
        return new Proof(storage);
    }

    @Override
    public void prepareCanonical(ServerLevel level) {
        this.canonicalPreparation = true;
        // Only LOCAL_BODIES currently return an audited proof. Their closed branch never
        // dereferences a kinetic network, so no world-wide network snapshot is needed or retained.
        // Millstone/water-wheel admission stays in the historical explicit driver.
        this.prepared = null;
    }

    @Override
    public Decision check(BlockEntity blockEntity) {
        if (!this.verified) return Decision.UNVERIFIED_TRANSFORMATIONS;
        if (blockEntity == null || !CLASSES.contains(blockEntity.getClass())) return Decision.UNKNOWN_CLASS;
        final KineticBlockEntity kinetic = (KineticBlockEntity) blockEntity;
        final Decision local = topology(kinetic);
        if (local != Decision.ADMITTED) return local;
        if (this.canonicalPreparation && LOCAL_BODIES.contains(kinetic.getClass())) return Decision.ADMITTED;
        if (kinetic.network != null) {
            final Prepared snapshot = this.prepared;
            if (snapshot == null || !snapshot.dimension().equals(kinetic.getLevel().dimension())
                    || snapshot.gameTime() != kinetic.getLevel().getGameTime()
                    || !snapshot.stableNetworks().contains(kinetic.network))
                return Decision.SHARED_RESOURCE;
        } else if (kinetic.source != null || kinetic.getTheoreticalSpeed() != 0) {
            return Decision.SHARED_RESOURCE;
        }
        if (kinetic instanceof MillstoneBlockEntity millstone) return millstone(millstone);
        return Decision.ADMITTED;
    }

    /** The whole known component must stay topology-inert, including members that retain a serial body. */
    private static Decision topology(KineticBlockEntity kinetic) {
        if (!CLASSES.contains(kinetic.getClass())) return Decision.UNKNOWN_CLASS;
        if (!((SmartBlockEntityAccessor) kinetic).forgia$isInitialized()) return Decision.INITIALIZING;
        final var fields = (KineticBlockEntityAccessor) kinetic;
        if (kinetic.needsSpeedUpdate() || kinetic.networkDirty || fields.forgia$validationCountdown() <= 0
                || kinetic instanceof GeneratingKineticBlockEntity generator && generator.reActivateSource
                || kinetic instanceof WaterWheelBlockEntity && ((SmartBlockEntityAccessor) kinetic).forgia$lazyTickCounter() <= 0) {
            return Decision.TOPOLOGY_CHANGE;
        }
        final KineticEffectHandler effects = fields.forgia$effects();
        if (effects == null || effects.getClass() != KineticEffectHandler.class
                || ((KineticEffectHandlerAccessor) effects).forgia$owner() != kinetic)
            return Decision.SHARED_RESOURCE;
        if (((KineticEffectHandlerAccessor) effects).forgia$particleCountdown() == 1) return Decision.CALLBACK_REQUIRED;
        final var behaviours = ((SmartBlockEntityAccessor) kinetic).forgia$behaviours();
        if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class) return Decision.UNKNOWN_BEHAVIOUR;
        for (final var behaviour : behaviours.values()) {
            if (!BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != kinetic) return Decision.UNKNOWN_BEHAVIOUR;
        }
        if (kinetic instanceof MillstoneBlockEntity millstone && !plainInventories(millstone)) return Decision.SHARED_RESOURCE;
        return Decision.ADMITTED;
    }

    private static boolean plainInventories(MillstoneBlockEntity millstone) {
        return millstone.inputInv != null && millstone.outputInv != null
                && millstone.inputInv.getClass() == ItemStackHandler.class && millstone.outputInv.getClass() == ItemStackHandler.class
                && millstone.inputInv != millstone.outputInv;
    }

    private static Decision millstone(MillstoneBlockEntity millstone) {
        // The plan excludes frozen ticks. Avoid getSpeed(), which would consult the level tick manager.
        final float speed = millstone.isOverStressed() ? 0.0f : millstone.getTheoreticalSpeed();
        if (speed == 0.0f) return Decision.ADMITTED;
        for (int slot = 0; slot < millstone.outputInv.getSlots(); ++slot) {
            if (millstone.outputInv.getStackInSlot(slot).getCount() == millstone.outputInv.getSlotLimit(slot)) return Decision.ADMITTED;
        }
        if (millstone.timer > 0) {
            final int decrement = Mth.clamp((int) Math.abs(speed / 16.0f), 1, 512);
            return millstone.timer > decrement ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
        }
        return millstone.inputInv.getStackInSlot(0).isEmpty() ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
    }
}
