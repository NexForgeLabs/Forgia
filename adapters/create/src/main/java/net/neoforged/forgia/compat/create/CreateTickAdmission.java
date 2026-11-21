/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create;

import com.simibubi.create.content.contraptions.actors.contraptionControls.ContraptionControlsBlockEntity;
import com.simibubi.create.content.contraptions.actors.roller.RollerBlockEntity;
import com.simibubi.create.content.contraptions.chassis.ChassisBlockEntity;
import com.simibubi.create.content.decoration.bracket.BracketedBlockEntityBehaviour;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.decoration.slidingDoor.DoorControlBehaviour;
import com.simibubi.create.content.fluids.transfer.FluidManipulationBehaviour;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticEffectHandler;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.behaviour.TransportedItemStackHandlerBehaviour;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crank.ValveHandleBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
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
import com.simibubi.create.content.logistics.itemHatch.ItemHatchBlockEntity;
import com.simibubi.create.content.logistics.tableCloth.TableClothFilteringBehaviour;
import com.simibubi.create.content.redstone.diodes.BrassDiodeScrollValueBehaviour;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.advancement.AdvancementBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntityTicker;
import com.simibubi.create.foundation.blockEntity.behaviour.edgeInteraction.EdgeInteractionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.VersionedInventoryTrackerBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.BulkScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.forgia.compat.create.mixin.ArmBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.DeployerBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticEffectHandlerAccessor;
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
                type("copycat"), type("creative_crate"), type("item_hatch")));
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
                CreativeCrateBlockEntity.class, ItemHatchBlockEntity.class));
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
                ItemHatchBlockEntity.class));
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
        all.add(DirectBeltInputBehaviour.class);
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
                if (!PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != kinetic)
                    return null;
                storage.add(new Storage(behaviour, Access.WRITE));
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
                if (!PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != smart)
                    return null;
                storage.add(new Storage(behaviour, Access.WRITE));
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
            if (!PURE_LOCAL_BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != smart)
                return Decision.UNKNOWN_BEHAVIOUR;
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
            if (!BEHAVIOURS.contains(behaviour.getClass()) || behaviour.blockEntity != kinetic) return Decision.UNKNOWN_BEHAVIOUR;
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
