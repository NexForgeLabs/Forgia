/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.DoorControlBehaviour;
import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.AllArmInteractionPointTypes;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.content.kinetics.turntable.TurntableBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.LargeWaterWheelBlockEntity;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlockEntity;
import com.simibubi.create.content.logistics.crate.CrateBlockEntity;
import com.simibubi.create.content.logistics.crate.CreativeCrateBlockEntity;
import com.simibubi.create.content.logistics.funnel.BeltFunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.logistics.itemHatch.ItemHatchBlockEntity;
import com.simibubi.create.content.logistics.tableCloth.TableClothFilteringBehaviour;
import com.simibubi.create.content.redstone.diodes.BrassDiodeScrollValueBehaviour;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import com.simibubi.create.foundation.blockEntity.behaviour.edgeInteraction.EdgeInteractionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.BulkScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.lang.invoke.SerializedLambda;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.compat.create.mixin.AirCurrentAccessor;
import net.neoforged.forgia.compat.create.mixin.ArmBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.BeltBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.BeltInventoryAccessor;
import net.neoforged.forgia.compat.create.mixin.CapManipulationBehaviourBaseAccessor;
import net.neoforged.forgia.compat.create.mixin.DeployerBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.EncasedFanBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.FluidTankBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.FluidTransportBehaviourAccessor;
import net.neoforged.forgia.compat.create.mixin.FunnelBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.ItemVaultBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
import net.neoforged.forgia.compat.create.mixin.MechanicalCrafterBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.PipeConnectionAccessor;
import net.neoforged.forgia.compat.create.mixin.PumpBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import org.jetbrains.annotations.Nullable;

/** Original ticks only. Network inspection is quiescent; workers read their instance and frozen ids only. */
public final class CreateTickAdmission implements BlockEntityExecutionAdapter {
    public static final ResourceLocation ID = ResourceLocation.parse("forgia_create:steady_ticks");
    public static final ResourceLocation ADDON_ID = ResourceLocation.parse("forgia_create:addon_steady_ticks");
    private static final boolean ARM_VERIFIED = CreateAudit.isOptionalLeafVerified(ArmBlockEntity.class);
    private static final boolean GENERATING_VERIFIED = CreateAudit.isOptionalLeafVerified(GeneratingKineticBlockEntity.class);
    private static final boolean WATER_WHEEL_VERIFIED =
            GENERATING_VERIFIED && CreateAudit.isOptionalLeafVerified(WaterWheelBlockEntity.class);
    private static final boolean LARGE_WATER_WHEEL_VERIFIED =
            WATER_WHEEL_VERIFIED && CreateAudit.isOptionalLeafVerified(LargeWaterWheelBlockEntity.class);
    private static final boolean FLUID_TANK_VERIFIED = CreateAudit.isOptionalLeafVerified(FluidTankBlockEntity.class);
    private static final boolean ENCASED_FAN_VERIFIED = CreateAudit.isOptionalLeafVerified(EncasedFanBlockEntity.class);
    private static final boolean AIR_CURRENT_VERIFIED =
            ENCASED_FAN_VERIFIED && CreateAudit.isOptionalLeafVerified(AirCurrent.class);
    private static final boolean BELT_VERIFIED = CreateAudit.isOptionalLeafVerified(BeltBlockEntity.class);
    private static final boolean BELT_INVENTORY_VERIFIED =
            BELT_VERIFIED && CreateAudit.isOptionalLeafVerified(BeltInventory.class);
    private static final boolean FLUID_TRANSPORT_VERIFIED =
            CreateAudit.isOptionalLeafVerified(FluidTransportBehaviour.class)
                    && CreateAudit.isOptionalLeafVerified(PipeConnection.class)
                    && CreateAudit.isOptionalLeafVerified(FlowSource.class)
                    && CreateAudit.isOptionalLeafVerified(FlowSource.Blocked.class)
                    && CreateAudit.isOptionalLeafVerified(FlowSource.OtherPipe.class)
                    && CreateAudit.isOptionalLeafVerified(FlowSource.FluidHandler.class);
    private static final boolean FLUID_PIPE_VERIFIED = FLUID_TRANSPORT_VERIFIED
            && CreateAudit.isOptionalLeafVerified(FluidPipeBlockEntity.class)
            && CreateAudit.isOptionalLeafVerified(CreateAudit.STANDARD_PIPE_FLUID_TRANSPORT_BEHAVIOUR);
    private static final boolean STRAIGHT_PIPE_VERIFIED = FLUID_TRANSPORT_VERIFIED
            && CreateAudit.isOptionalLeafVerified(StraightPipeBlockEntity.class)
            && CreateAudit.isOptionalLeafVerified(StraightPipeBlockEntity.StraightPipeFluidTransportBehaviour.class);
    private static final boolean PUMP_VERIFIED = FLUID_TRANSPORT_VERIFIED
            && CreateAudit.isOptionalLeafVerified(PumpBlockEntity.class)
            && CreateAudit.isOptionalLeafVerified(CreateAudit.PUMP_FLUID_TRANSFER_BEHAVIOUR);
    private static final boolean FUNNEL_VERIFIED = CreateAudit.isOptionalLeafVerified(FunnelBlockEntity.class);
    private static final Set<Class<?>> FLUID_TRANSPORT_BEHAVIOURS = Set.of(
            CreateAudit.STANDARD_PIPE_FLUID_TRANSPORT_BEHAVIOUR,
            StraightPipeBlockEntity.StraightPipeFluidTransportBehaviour.class,
            CreateAudit.PUMP_FLUID_TRANSFER_BEHAVIOUR);
    public static final Set<ResourceLocation> TYPES;
    private static final Set<Class<?>> CORE_LOCAL_BODIES;
    private static final Set<Class<?>> CORE_CLASSES;
    static {
        final Set<ResourceLocation> types = new java.util.LinkedHashSet<>(Set.of(
                type("simple_kinetic"), type("encased_shaft"), type("encased_cogwheel"),
                type("encased_large_cogwheel"), type("gearbox"), type("millstone"),
                type("clutch"), type("gearshift"),
                type("gantry_shaft"), type("turntable"),
                type("powered_shaft"),
                type("deployer"),
                type("mechanical_roller"), type("chassis"), type("contraption_controls"),
                type("copycat"), type("creative_crate"), type("item_hatch"),
                type("item_vault"), type("mechanical_crafter")));
        final Set<Class<?>> localBodies = new java.util.LinkedHashSet<>(Set.of(
                KineticBlockEntity.class, SimpleKineticBlockEntity.class,
                BracketedKineticBlockEntity.class, GearboxBlockEntity.class,
                ClutchBlockEntity.class, GearshiftBlockEntity.class,
                GantryShaftBlockEntity.class, TurntableBlockEntity.class,
                PoweredShaftBlockEntity.class,
                DeployerBlockEntity.class,
                RollerBlockEntity.class,
                ChassisBlockEntity.class, ContraptionControlsBlockEntity.class,
                CopycatBlockEntity.class, CrateBlockEntity.class,
                CreativeCrateBlockEntity.class, ItemHatchBlockEntity.class,
                ItemVaultBlockEntity.class, MechanicalCrafterBlockEntity.class));
        final Set<Class<?>> coreClasses = new java.util.LinkedHashSet<>(Set.of(
                KineticBlockEntity.class, SimpleKineticBlockEntity.class,
                BracketedKineticBlockEntity.class, GearboxBlockEntity.class,
                MillstoneBlockEntity.class,
                ClutchBlockEntity.class,
                GearshiftBlockEntity.class, GantryShaftBlockEntity.class,
                TurntableBlockEntity.class,
                PoweredShaftBlockEntity.class,
                DeployerBlockEntity.class,
                RollerBlockEntity.class, ChassisBlockEntity.class,
                ContraptionControlsBlockEntity.class, CopycatBlockEntity.class,
                CrateBlockEntity.class, CreativeCrateBlockEntity.class,
                ItemHatchBlockEntity.class,
                ItemVaultBlockEntity.class, MechanicalCrafterBlockEntity.class));
        if (BELT_VERIFIED) {
            types.add(type("belt"));
            localBodies.add(BeltBlockEntity.class);
            coreClasses.add(BeltBlockEntity.class);
        }
        if (FLUID_PIPE_VERIFIED) {
            types.add(type("fluid_pipe"));
            types.add(type("encased_fluid_pipe"));
            localBodies.add(FluidPipeBlockEntity.class);
            coreClasses.add(FluidPipeBlockEntity.class);
        }
        if (STRAIGHT_PIPE_VERIFIED) {
            types.add(type("glass_fluid_pipe"));
            localBodies.add(StraightPipeBlockEntity.class);
            coreClasses.add(StraightPipeBlockEntity.class);
        }
        if (PUMP_VERIFIED) {
            types.add(type("mechanical_pump"));
            localBodies.add(PumpBlockEntity.class);
            coreClasses.add(PumpBlockEntity.class);
        }
        if (FUNNEL_VERIFIED) {
            types.add(type("funnel"));
            localBodies.add(FunnelBlockEntity.class);
            coreClasses.add(FunnelBlockEntity.class);
        }
        if (FLUID_TANK_VERIFIED) {
            types.add(type("fluid_tank"));
            localBodies.add(FluidTankBlockEntity.class);
            coreClasses.add(FluidTankBlockEntity.class);
        }
        if (ENCASED_FAN_VERIFIED) {
            types.add(type("encased_fan"));
            localBodies.add(EncasedFanBlockEntity.class);
            coreClasses.add(EncasedFanBlockEntity.class);
        }
        if (WATER_WHEEL_VERIFIED) {
            types.add(type("water_wheel"));
            coreClasses.add(WaterWheelBlockEntity.class);
        }
        if (LARGE_WATER_WHEEL_VERIFIED) {
            types.add(type("large_water_wheel"));
            coreClasses.add(LargeWaterWheelBlockEntity.class);
        }
        if (ARM_VERIFIED) {
            types.add(type("mechanical_arm"));
            localBodies.add(ArmBlockEntity.class);
            coreClasses.add(ArmBlockEntity.class);
        }
        TYPES = Set.copyOf(types);
        CORE_LOCAL_BODIES = Set.copyOf(localBodies);
        CORE_CLASSES = Set.copyOf(coreClasses);
    }
    /**
     * Exact audited behaviours that do not override {@code tick()} or {@code lazyTick()} and therefore
     * execute only the local scalar {@code lazyTickCounter} countdown on their own instance.
     */
    private static final Set<Class<?>> PURE_LOCAL_BEHAVIOURS = Set.of(
            BracketedBlockEntityBehaviour.class,
            AdvancementBehaviour.class,
            DoorControlBehaviour.class,
            FluidManipulationBehaviour.class,
            BeltProcessingBehaviour.class,
            DirectBeltInputBehaviour.class,
            TransportedItemStackHandlerBehaviour.class,
            KineticScrollValueBehaviour.class,
            TableClothFilteringBehaviour.class,
            BrassDiodeScrollValueBehaviour.class,
            LinkBehaviour.class,
            EdgeInteractionBehaviour.class,
            FilteringBehaviour.class,
            VersionedInventoryTrackerBehaviour.class,
            BulkScrollValueBehaviour.class,
            ScrollOptionBehaviour.class,
            ScrollValueBehaviour.class,
            CreateAudit.CHASSIS_SCROLL_BEHAVIOUR);
    /**
     * All behaviours allowed during topology inspection (includes {@link DirectBeltInputBehaviour} for
     * explicit driver compatibility on Millstone).
     */
    private static final Set<Class<?>> BEHAVIOURS;
    static {
        final Set<Class<?>> all = new HashSet<>(PURE_LOCAL_BEHAVIOURS);
        all.add(InvManipulationBehaviour.class);
        BEHAVIOURS = Set.copyOf(all);
    }

    private static final int MAX_NETWORKS = 4096;
    private static final int MAX_MEMBERS = 65536;
    private final boolean verified;
    private final Set<Class<?>> localBodies;
    private final Set<Class<?>> classes;
    private volatile boolean canonicalPreparation;
    @Nullable
    private volatile Prepared prepared;

    private record Prepared(ResourceKey<Level> dimension, long gameTime, Set<Long> stableNetworks) {
        Prepared {
            stableNetworks = Set.copyOf(stableNetworks);
        }
    }

    private static final Field CRAFTER_PHASE_FIELD = optionalDeclaredField(MechanicalCrafterBlockEntity.class, "phase");
    private static final Field FLOW_SOURCE_OTHER_PIPE_CACHED_FIELD = optionalDeclaredField(FlowSource.OtherPipe.class, "cached");
    private static final Field FLOW_SOURCE_FLUID_HANDLER_CACHE_FIELD = optionalDeclaredField(FlowSource.FluidHandler.class, "fluidHandlerCache");
    private static final Field SABLE_AIR_CURRENT_SUBLEVEL_FIELD = optionalDeclaredField(AirCurrent.class, "sable$subLevelReference");
    private static final Field BEHAVIOUR_LAZY_TICK_COUNTER_FIELD = optionalDeclaredField(
            com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.class, "lazyTickCounter");

    @Nullable
    private static Field optionalDeclaredField(Class<?> owner, String name) {
        try {
            final var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            return null;
        }
    }

    private static boolean invManipulationNeedsCallback(InvManipulationBehaviour inv, @Nullable Level level) {
        if (((CapManipulationBehaviourBaseAccessor) inv).forgia$findNewNextTick()
                || level == null
                || level.getGameTime() % 64L == 0L
                || BEHAVIOUR_LAZY_TICK_COUNTER_FIELD == null) {
            return true;
        }
        try {
            return BEHAVIOUR_LAZY_TICK_COUNTER_FIELD.getInt(inv) <= 1;
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    public CreateTickAdmission() {
        this(Set.of());
    }

    public CreateTickAdmission(Set<Class<?>> extraInheritedSources) {
        final Set<Class<?>> verifiedExtras = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean allExtrasVerified = true;
        for (final Class<?> extra : Objects.requireNonNull(extraInheritedSources)) {
            if (!CreateAudit.isPureInheritedSmartBlockEntity(extra)) {
                allExtrasVerified = false;
                break;
            }
            verifiedExtras.add(extra);
        }
        this.verified = allExtrasVerified && CreateAudit.verify();
        final Set<Class<?>> mergedLocal = Collections.newSetFromMap(new IdentityHashMap<>());
        mergedLocal.addAll(CORE_LOCAL_BODIES);
        mergedLocal.addAll(verifiedExtras);
        this.localBodies = Collections.unmodifiableSet(mergedLocal);
        final Set<Class<?>> mergedClasses = Collections.newSetFromMap(new IdentityHashMap<>());
        mergedClasses.addAll(CORE_CLASSES);
        mergedClasses.addAll(verifiedExtras);
        this.classes = Collections.unmodifiableSet(mergedClasses);
    }

    /**
     * Discovers registered addon {@link BlockEntityType}s in {@link BuiltInRegistries#BLOCK_ENTITY_TYPE}
     * whose concrete {@link BlockEntity} implementation is a pure inheritor of Create Core's audited
     * {@link SmartBlockEntity} / {@link KineticBlockEntity} hierarchy.
     */
    public static Map<ResourceLocation, Class<?>> discoverAddonInheritedTypes() {
        final Map<ResourceLocation, Class<?>> discovered = new LinkedHashMap<>();
        for (final var entry : BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet()) {
            final ResourceLocation id = entry.getKey().location();
            if ("create".equals(id.getNamespace()) || TYPES.contains(id)) {
                continue;
            }
            final Class<?> beClass = resolveBlockEntityClass(entry.getValue());
            if (beClass != null && CreateAudit.isPureInheritedSmartBlockEntity(beClass)) {
                discovered.put(id, beClass);
            }
        }
        return Map.copyOf(discovered);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Class<?> resolveBlockEntityClass(BlockEntityType<?> type) {
        if (type == null) return null;
        // 1. Inspect SerializedLambda if the BlockEntitySupplier is a serializable method reference, or
        //    inspect validBlocks by instantiating a detached probe instance when possible.
        try {
            for (final Field field : BlockEntityType.class.getDeclaredFields()) {
                if (Set.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    final Object raw = field.get(type);
                    if (raw instanceof Set<?> validBlocks && !validBlocks.isEmpty()) {
                        final Object first = validBlocks.iterator().next();
                        if (first instanceof Block block) {
                            final BlockEntity instance = type.create(BlockPos.ZERO, block.defaultBlockState());
                            if (instance != null) {
                                if (instance instanceof SmartBlockEntity smart && hasUnsupportedProbeBehaviour(smart)) {
                                    return null;
                                }
                                return instance.getClass();
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to lambda reflection if constructor requires live world state.
        }
        try {
            for (final Field field : BlockEntityType.class.getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(field.getType()) && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    final Object factory = field.get(type);
                    if (factory != null) {
                        final Method writeReplace = factory.getClass().getDeclaredMethod("writeReplace");
                        writeReplace.setAccessible(true);
                        if (writeReplace.invoke(factory) instanceof SerializedLambda lambda) {
                            return Class.forName(lambda.getImplClass().replace('/', '.'), false, CreateTickAdmission.class.getClassLoader());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean hasUnsupportedProbeBehaviour(SmartBlockEntity smart) {
        if (!(smart instanceof SmartBlockEntityAccessor accessor)) {
            return false;
        }
        final var behaviours = accessor.forgia$behaviours();
        if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class) {
            return true;
        }
        final boolean kinetic = smart instanceof KineticBlockEntity;
        for (final var behaviour : behaviours.values()) {
            if (behaviour.blockEntity != smart) {
                return true;
            }
            if (behaviour instanceof InvManipulationBehaviour) {
                continue;
            }
            if (FLUID_TRANSPORT_VERIFIED && behaviour instanceof FluidTransportBehaviour transport
                    && FLUID_TRANSPORT_BEHAVIOURS.contains(transport.getClass())) {
                continue;
            }
            final boolean allowed = kinetic
                    ? BEHAVIOURS.contains(behaviour.getClass())
                    : PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass());
            if (!allowed) {
                return true;
            }
        }
        return false;
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
                            || !Objects.equals(member.network, entry.getKey()) || this.topology(member) != Decision.ADMITTED) {
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
    public Mode executionMode() {
        return Mode.RESOURCE_SCOPED;
    }

    @Override
    public void phaseClosed(boolean successful) {
        this.prepared = null;
        this.canonicalPreparation = false;
    }

    @Override
    public Audit audit() {
        return CreateAudit.nativeAudit(this.localBodies);
    }

    @Override
    @Nullable
    public Proof inspect(BlockEntity blockEntity, Class<?> tickerImplementation) {
        if (!this.verified || blockEntity == null || !this.localBodies.contains(blockEntity.getClass())
                || tickerImplementation != SmartBlockEntityTicker.class || blockEntity.hasAttachments())
            return null;
        if (blockEntity instanceof KineticBlockEntity kinetic) {
            final var behaviours = ((SmartBlockEntityAccessor) kinetic).forgia$behaviours();
            if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class || this.topology(kinetic) != Decision.ADMITTED)
                return null;
            final var storage = new ArrayList<Storage>();
            storage.add(new Storage(kinetic, Access.WRITE));
            storage.add(new Storage(behaviours, Access.READ));
            storage.add(new Storage(((KineticBlockEntityAccessor) kinetic).forgia$effects(), Access.WRITE));
            for (final var behaviour : behaviours.values()) {
                if (behaviour.blockEntity != kinetic) return null;
                if (behaviour instanceof InvManipulationBehaviour inv) {
                    if (invManipulationNeedsCallback(inv, kinetic.getLevel())) {
                        return null;
                    }
                    storage.add(new Storage(behaviour, Access.WRITE));
                } else if (FLUID_TRANSPORT_VERIFIED && behaviour instanceof FluidTransportBehaviour transport
                        && FLUID_TRANSPORT_BEHAVIOURS.contains(transport.getClass())) {
                    if (!appendFluidTransportStorage(transport, storage)) return null;
                } else if (PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass())) {
                    storage.add(new Storage(behaviour, Access.WRITE));
                } else {
                    return null;
                }
            }
            if (kinetic instanceof ArmBlockEntity arm) {
                final var inputs = ((ArmBlockEntityAccessor) arm).forgia$inputs();
                if (inputs == null) return null;
                storage.add(new Storage(inputs, Access.READ));
                final int index = ((ArmBlockEntityAccessor) arm).forgia$chasedPointIndex();
                if (index >= 0 && index < inputs.size()) {
                    final ArmInteractionPoint target = inputs.get(index);
                    if (target != null) {
                        storage.add(new Storage(target, Access.READ));
                    }
                }
            } else if (kinetic instanceof BeltBlockEntity belt && belt.isController()) {
                final BeltInventory inv = ((BeltBlockEntityAccessor) belt).forgia$inventory();
                if (inv == null || inv.getClass() != BeltInventory.class) return null;
                final var invAcc = (BeltInventoryAccessor) inv;
                storage.add(new Storage(inv, Access.WRITE));
                storage.add(new Storage(invAcc.forgia$items(), Access.READ));
                storage.add(new Storage(invAcc.forgia$toInsert(), Access.READ));
                storage.add(new Storage(invAcc.forgia$toRemove(), Access.READ));
                if (belt.passengers != null) {
                    storage.add(new Storage(belt.passengers, Access.READ));
                }
            } else if (kinetic instanceof EncasedFanBlockEntity fan && fan.getSpeed() != 0.0F) {
                final AirCurrent ac = fan.airCurrent;
                if (ac == null || ac.getClass() != AirCurrent.class) return null;
                final var acAcc = (AirCurrentAccessor) ac;
                storage.add(new Storage(ac, Access.READ));
                storage.add(new Storage(acAcc.forgia$caughtEntities(), Access.READ));
                storage.add(new Storage(acAcc.forgia$affectedItemHandlers(), Access.READ));
            } else if (kinetic instanceof PumpBlockEntity pump) {
                final var sides = ((PumpBlockEntityAccessor) pump).forgia$sidesToUpdate();
                if (sides == null) return null;
                storage.add(new Storage(sides, Access.READ));
                storage.add(new Storage(sides.getFirst(), Access.READ));
                storage.add(new Storage(sides.getSecond(), Access.READ));
            }
            return new Proof(storage);
        }
        if (blockEntity instanceof SmartBlockEntity smart) {
            final var behaviours = ((SmartBlockEntityAccessor) smart).forgia$behaviours();
            if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class || this.smartTopology(smart) != Decision.ADMITTED)
                return null;
            final var storage = new ArrayList<Storage>();
            storage.add(new Storage(smart, Access.WRITE));
            storage.add(new Storage(behaviours, Access.READ));
            for (final var behaviour : behaviours.values()) {
                if (behaviour.blockEntity != smart) return null;
                if (behaviour instanceof InvManipulationBehaviour inv) {
                    if (invManipulationNeedsCallback(inv, smart.getLevel())) {
                        return null;
                    }
                    storage.add(new Storage(behaviour, Access.WRITE));
                } else if (FLUID_TRANSPORT_VERIFIED && behaviour instanceof FluidTransportBehaviour transport
                        && FLUID_TRANSPORT_BEHAVIOURS.contains(transport.getClass())) {
                    if (!appendFluidTransportStorage(transport, storage)) return null;
                } else if (PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass())) {
                    storage.add(new Storage(behaviour, Access.WRITE));
                } else {
                    return null;
                }
            }
            if (smart instanceof FunnelBlockEntity funnel) {
                final var flap = ((FunnelBlockEntityAccessor) funnel).forgia$flap();
                if (flap == null) return null;
                storage.add(new Storage(flap, Access.WRITE));
            }
            return new Proof(storage);
        }
        return null;
    }

    @Override
    public void prepareCanonical(ServerLevel level) {
        this.canonicalPreparation = true;
        // Only localBodies return an audited proof. Their closed branch never
        // dereferences a kinetic network, so no world-wide network snapshot is needed or retained.
        // Millstone/water-wheel admission stays in the historical explicit driver.
        this.prepared = null;
    }

    @Override
    public Decision check(BlockEntity blockEntity) {
        if (!this.verified) return Decision.UNVERIFIED_TRANSFORMATIONS;
        if (blockEntity == null || !this.classes.contains(blockEntity.getClass())) return Decision.UNKNOWN_CLASS;
        if (!(blockEntity instanceof KineticBlockEntity kinetic)) {
            if (blockEntity instanceof SmartBlockEntity smart) {
                return this.smartTopology(smart);
            }
            return Decision.UNKNOWN_CLASS;
        }
        final Decision local = this.topology(kinetic);
        if (local != Decision.ADMITTED) return local;
        if (this.canonicalPreparation && this.localBodies.contains(kinetic.getClass())) return Decision.ADMITTED;
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

    private Decision smartTopology(SmartBlockEntity smart) {
        if (!this.classes.contains(smart.getClass())) return Decision.UNKNOWN_CLASS;
        if (!((SmartBlockEntityAccessor) smart).forgia$isInitialized()) return Decision.INITIALIZING;
        final var behaviours = ((SmartBlockEntityAccessor) smart).forgia$behaviours();
        if (behaviours == null || behaviours.getClass() != Reference2ObjectArrayMap.class) return Decision.UNKNOWN_BEHAVIOUR;
        for (final var behaviour : behaviours.values()) {
            if (behaviour.blockEntity != smart) return Decision.UNKNOWN_BEHAVIOUR;
            if (behaviour instanceof InvManipulationBehaviour inv) {
                if (invManipulationNeedsCallback(inv, smart.getLevel())) {
                    return Decision.CALLBACK_REQUIRED;
                }
            } else if (FLUID_TRANSPORT_VERIFIED && behaviour instanceof FluidTransportBehaviour transport
                    && FLUID_TRANSPORT_BEHAVIOURS.contains(transport.getClass())) {
                final Decision transportDecision = checkFluidTransport(transport);
                if (transportDecision != Decision.ADMITTED) return transportDecision;
            } else if (!PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass())) {
                return Decision.UNKNOWN_BEHAVIOUR;
            }
        }
        if (smart instanceof ItemVaultBlockEntity vault) {
            final var accessor = (ItemVaultBlockEntityAccessor) vault;
            final var lastPos = accessor.forgia$lastKnownPos();
            if (lastPos == null || !lastPos.equals(vault.getBlockPos()) || accessor.forgia$updateConnectivity()) {
                return Decision.TOPOLOGY_CHANGE;
            }
        }
        if (smart instanceof FluidTankBlockEntity tank) {
            if (tank.isController()) return Decision.CALLBACK_REQUIRED;
            final var accessor = (FluidTankBlockEntityAccessor) tank;
            final var lastPos = accessor.forgia$lastKnownPos();
            if (lastPos == null || !lastPos.equals(tank.getBlockPos())
                    || accessor.forgia$updateConnectivity() || accessor.forgia$updateCapability()) {
                return Decision.TOPOLOGY_CHANGE;
            }
            if (accessor.forgia$syncCooldown() > 0 || accessor.forgia$fluidLevel() != null) {
                return Decision.CALLBACK_REQUIRED;
            }
        }
        if (smart instanceof FunnelBlockEntity funnel) {
            return funnel(funnel);
        }
        return Decision.ADMITTED;
    }

    /** The whole known component must stay topology-inert, including members that retain a serial body. */
    private Decision topology(KineticBlockEntity kinetic) {
        if (!this.classes.contains(kinetic.getClass())) return Decision.UNKNOWN_CLASS;
        if (!((SmartBlockEntityAccessor) kinetic).forgia$isInitialized()) return Decision.INITIALIZING;
        final var fields = (KineticBlockEntityAccessor) kinetic;
        if (kinetic.needsSpeedUpdate() || kinetic.networkDirty || fields.forgia$validationCountdown() <= 0
                || kinetic instanceof GeneratingKineticBlockEntity generator && generator.reActivateSource
                || kinetic instanceof WaterWheelBlockEntity && ((SmartBlockEntityAccessor) kinetic).forgia$lazyTickCounter() <= 0
                || kinetic instanceof HandCrankBlockEntity crank && crank.inUse > 0) {
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
            if (behaviour.blockEntity != kinetic) return Decision.UNKNOWN_BEHAVIOUR;
            if (behaviour instanceof InvManipulationBehaviour inv) {
                if (invManipulationNeedsCallback(inv, kinetic.getLevel())) {
                    return Decision.CALLBACK_REQUIRED;
                }
            } else if (FLUID_TRANSPORT_VERIFIED && behaviour instanceof FluidTransportBehaviour transport
                    && FLUID_TRANSPORT_BEHAVIOURS.contains(transport.getClass())) {
                final Decision transportDecision = checkFluidTransport(transport);
                if (transportDecision != Decision.ADMITTED) return transportDecision;
            } else if (!BEHAVIOURS.contains(behaviour.getClass())) {
                return Decision.UNKNOWN_BEHAVIOUR;
            }
        }
        if (kinetic instanceof MillstoneBlockEntity millstone && !plainInventories(millstone)) return Decision.SHARED_RESOURCE;
        if (kinetic instanceof DeployerBlockEntity deployer) {
            final Decision deployerDecision = deployer(deployer);
            if (deployerDecision != Decision.ADMITTED) return deployerDecision;
        }
        if (kinetic instanceof ArmBlockEntity arm) {
            final Decision armDecision = arm(arm);
            if (armDecision != Decision.ADMITTED) return armDecision;
        }
        if (kinetic instanceof MechanicalCrafterBlockEntity crafter) {
            final Decision crafterDecision = crafter(crafter);
            if (crafterDecision != Decision.ADMITTED) return crafterDecision;
        }
        if (kinetic instanceof BeltBlockEntity belt) {
            final Decision beltDecision = belt(belt);
            if (beltDecision != Decision.ADMITTED) return beltDecision;
        }
        if (kinetic instanceof EncasedFanBlockEntity fan) {
            final Decision fanDecision = fan(fan);
            if (fanDecision != Decision.ADMITTED) return fanDecision;
        }
        if (kinetic instanceof PumpBlockEntity pump) {
            final Decision pumpDecision = pump(pump);
            if (pumpDecision != Decision.ADMITTED) return pumpDecision;
        }
        return Decision.ADMITTED;
    }

    private static Decision checkFluidTransport(FluidTransportBehaviour transport) {
        final var accessor = (FluidTransportBehaviourAccessor) transport;
        final var interfaces = accessor.forgia$interfaces();
        if (interfaces == null) {
            return transport.getClass() == CreateAudit.PUMP_FLUID_TRANSFER_BEHAVIOUR
                    ? Decision.CALLBACK_REQUIRED
                    : Decision.ADMITTED;
        }
        final var phase = accessor.forgia$phase();
        if (phase == FluidTransportBehaviour.UpdatePhase.WAIT_FOR_PUMPS) return Decision.ADMITTED;
        if (phase != FluidTransportBehaviour.UpdatePhase.IDLE) return Decision.CALLBACK_REQUIRED;
        for (final PipeConnection conn : interfaces.values()) {
            if (conn == null || conn.getClass() != PipeConnection.class) return Decision.SHARED_RESOURCE;
            if (conn.hasFlow() || conn.hasPressure()) return Decision.CALLBACK_REQUIRED;
            final Optional<FlowSource> optSource = ((PipeConnectionAccessor) conn).forgia$source();
            if (optSource == null || optSource.isEmpty()) return Decision.CALLBACK_REQUIRED;
            final FlowSource src = optSource.get();
            final Class<?> srcClass = src.getClass();
            if (srcClass == FlowSource.Blocked.class) {
                continue;
            }
            if (srcClass == FlowSource.FluidHandler.class) {
                if (FLOW_SOURCE_FLUID_HANDLER_CACHE_FIELD == null) return Decision.CALLBACK_REQUIRED;
                try {
                    if (FLOW_SOURCE_FLUID_HANDLER_CACHE_FIELD.get(src) == null) return Decision.CALLBACK_REQUIRED;
                } catch (IllegalAccessException e) {
                    return Decision.CALLBACK_REQUIRED;
                }
                continue;
            }
            if (srcClass == FlowSource.OtherPipe.class) {
                if (FLOW_SOURCE_OTHER_PIPE_CACHED_FIELD == null) return Decision.CALLBACK_REQUIRED;
                try {
                    final Object raw = FLOW_SOURCE_OTHER_PIPE_CACHED_FIELD.get(src);
                    if (!(raw instanceof WeakReference<?> ref)) return Decision.CALLBACK_REQUIRED;
                    final Object target = ref.get();
                    if (!(target instanceof FluidTransportBehaviour other)
                            || other.blockEntity == null
                            || other.blockEntity.isRemoved()) {
                        return Decision.CALLBACK_REQUIRED;
                    }
                } catch (IllegalAccessException e) {
                    return Decision.CALLBACK_REQUIRED;
                }
                continue;
            }
            return Decision.CALLBACK_REQUIRED;
        }
        return Decision.ADMITTED;
    }

    private static boolean appendFluidTransportStorage(FluidTransportBehaviour transport, ArrayList<Storage> storage) {
        storage.add(new Storage(transport, Access.WRITE));
        final var interfaces = ((FluidTransportBehaviourAccessor) transport).forgia$interfaces();
        if (interfaces != null) {
            storage.add(new Storage(interfaces, Access.READ));
            for (final PipeConnection conn : interfaces.values()) {
                if (conn == null || conn.getClass() != PipeConnection.class) return false;
                storage.add(new Storage(conn, Access.WRITE));
            }
        }
        return true;
    }

    private static Decision funnel(FunnelBlockEntity funnel) {
        final BlockState state = funnel.getBlockState();
        if (!FunnelBlock.isFunnel(state)) return Decision.CALLBACK_REQUIRED;
        final var accessor = (FunnelBlockEntityAccessor) funnel;
        if (accessor.forgia$flap() == null) return Decision.SHARED_RESOURCE;
        if (state.getOptionalValue(BlockStateProperties.POWERED).orElse(false)) {
            return Decision.ADMITTED;
        }
        if (state.getBlock() instanceof BeltFunnelBlock) {
            final var shape = state.getValue(BeltFunnelBlock.SHAPE);
            if (shape == BeltFunnelBlock.Shape.PULLING) return Decision.ADMITTED;
            if (shape == BeltFunnelBlock.Shape.PUSHING) {
                return accessor.forgia$extractionCooldown() > 0 ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
            }
            return Decision.CALLBACK_REQUIRED;
        }
        if (state.getBlock() instanceof FunnelBlock) {
            if (!state.getValue(FunnelBlock.EXTRACTING)) return Decision.ADMITTED;
            return accessor.forgia$extractionCooldown() > 0 ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
        }
        return Decision.CALLBACK_REQUIRED;
    }

    private static Decision crafter(MechanicalCrafterBlockEntity crafter) {
        if (CRAFTER_PHASE_FIELD == null) return Decision.CALLBACK_REQUIRED;
        final String phaseName;
        try {
            final Object phase = CRAFTER_PHASE_FIELD.get(crafter);
            if (!(phase instanceof Enum<?> enumPhase)) return Decision.CALLBACK_REQUIRED;
            phaseName = enumPhase.name();
        } catch (IllegalAccessException e) {
            return Decision.CALLBACK_REQUIRED;
        }
        if ("ACCEPTING".equals(phaseName)) return Decision.ADMITTED;
        if (((SmartBlockEntityAccessor) crafter).forgia$lazyTickCounter() <= 1) return Decision.CALLBACK_REQUIRED;
        final Level level = crafter.getLevel();
        if (level == null) return Decision.CALLBACK_REQUIRED;
        final var accessor = (MechanicalCrafterBlockEntityAccessor) crafter;
        if (accessor.forgia$wasPoweredBefore() != level.hasNeighborSignal(crafter.getBlockPos())) {
            return Decision.CALLBACK_REQUIRED;
        }
        if ("IDLE".equals(phaseName) || "WAITING".equals(phaseName)) {
            return Decision.ADMITTED;
        }
        if ("ASSEMBLING".equals(phaseName) || "EXPORTING".equals(phaseName)) {
            final int speed = crafter.getCountDownSpeed();
            return accessor.forgia$countDown() >= speed ? Decision.ADMITTED : Decision.CALLBACK_REQUIRED;
        }
        return Decision.CALLBACK_REQUIRED;
    }

    private static Decision belt(BeltBlockEntity belt) {
        final var accessor = (BeltBlockEntityAccessor) belt;
        if (accessor.forgia$beltLength() <= 0
                || accessor.forgia$itemHandler() == null
                || !AllBlocks.BELT.has(belt.getBlockState())) {
            return Decision.CALLBACK_REQUIRED;
        }
        if (!belt.isController()) {
            return Decision.ADMITTED;
        }
        if (!BELT_INVENTORY_VERIFIED) return Decision.CALLBACK_REQUIRED;
        final BeltInventory inv = accessor.forgia$inventory();
        if (inv == null || inv.getClass() != BeltInventory.class) return Decision.CALLBACK_REQUIRED;
        final var invAcc = (BeltInventoryAccessor) inv;
        if (invAcc.forgia$lazyClientItem() != null
                || !invAcc.forgia$toInsert().isEmpty()
                || !invAcc.forgia$toRemove().isEmpty()
                || !invAcc.forgia$items().isEmpty()) {
            return Decision.CALLBACK_REQUIRED;
        }
        final float speed = belt.isOverStressed() ? 0.0f : belt.getTheoreticalSpeed();
        if (speed != 0.0f) {
            if (invAcc.forgia$beltMovementPositive() != (belt.getDirectionAwareBeltMovementSpeed() > 0.0f)) {
                return Decision.CALLBACK_REQUIRED;
            }
            if (belt.passengers != null && !belt.passengers.isEmpty()) {
                return Decision.CALLBACK_REQUIRED;
            }
        }
        return Decision.ADMITTED;
    }

    private static Decision fan(EncasedFanBlockEntity fan) {
        final var accessor = (EncasedFanBlockEntityAccessor) fan;
        if (accessor.forgia$airCurrentUpdateCooldown() <= 0 || accessor.forgia$updateAirFlow()) {
            return Decision.CALLBACK_REQUIRED;
        }
        if (fan.getSpeed() == 0.0F) {
            return Decision.ADMITTED;
        }
        if (!AIR_CURRENT_VERIFIED || accessor.forgia$entitySearchCooldown() <= 0) {
            return Decision.CALLBACK_REQUIRED;
        }
        final AirCurrent ac = fan.airCurrent;
        if (ac == null || ac.getClass() != AirCurrent.class || ac.source != fan || ac.direction == null) {
            return Decision.CALLBACK_REQUIRED;
        }
        if (SABLE_AIR_CURRENT_SUBLEVEL_FIELD != null) {
            try {
                if (SABLE_AIR_CURRENT_SUBLEVEL_FIELD.get(ac) == null) {
                    return Decision.CALLBACK_REQUIRED;
                }
            } catch (IllegalAccessException e) {
                return Decision.CALLBACK_REQUIRED;
            }
        }
        final var acAcc = (AirCurrentAccessor) ac;
        if (!acAcc.forgia$caughtEntities().isEmpty() || !acAcc.forgia$affectedItemHandlers().isEmpty()) {
            return Decision.CALLBACK_REQUIRED;
        }
        return Decision.ADMITTED;
    }

    private static Decision pump(PumpBlockEntity pump) {
        final var accessor = (PumpBlockEntityAccessor) pump;
        if (accessor.forgia$scheduleFlip()) return Decision.CALLBACK_REQUIRED;
        final var sides = accessor.forgia$sidesToUpdate();
        if (sides == null || sides.getFirst().isTrue() || sides.getSecond().isTrue()) {
            return Decision.CALLBACK_REQUIRED;
        }
        return Decision.ADMITTED;
    }

    private static Decision deployer(DeployerBlockEntity deployer) {
        final float speed = deployer.isOverStressed() ? 0.0f : deployer.getTheoreticalSpeed();
        if (speed == 0.0f) return Decision.ADMITTED;
        final var accessor = (DeployerBlockEntityAccessor) deployer;
        final var player = accessor.forgia$player();
        if (player != null && (( net.neoforged.forgia.compat.create.mixin.DeployerFakePlayerAccessor) player).forgia$blockBreakingProgress() != null) {
            return Decision.CALLBACK_REQUIRED;
        }
        if (accessor.forgia$timer() > 0) {
            return Decision.ADMITTED;
        }
        return Decision.CALLBACK_REQUIRED;
    }

    private static Decision arm(ArmBlockEntity arm) {
        if (((SmartBlockEntityAccessor) arm).forgia$lazyTickCounter() <= 1) {
            return Decision.CALLBACK_REQUIRED;
        }
        final var accessor = (ArmBlockEntityAccessor) arm;
        if (accessor.forgia$updateInteractionPoints() && accessor.forgia$interactionPointTag() != null) {
            return Decision.TOPOLOGY_CHANGE;
        }
        final float speed = arm.isOverStressed() ? 0.0f : arm.getTheoreticalSpeed();
        final float delta = Math.min(256.0f, Math.abs(speed)) / 1024.0f;
        if (accessor.forgia$chasedPointProgress() + delta >= 1.0f) {
            return Decision.CALLBACK_REQUIRED;
        }
        final var inputs = accessor.forgia$inputs();
        if (inputs == null) return Decision.SHARED_RESOURCE;
        final int index = accessor.forgia$chasedPointIndex();
        if (index >= 0 && index < inputs.size()) {
            final ArmInteractionPoint target = inputs.get(index);
            if (target instanceof AllArmInteractionPointTypes.BeltPoint) {
                return Decision.CALLBACK_REQUIRED;
            }
        }
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
