/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlockEntity;
import com.simibubi.create.content.fluids.pipes.StraightPipeBlockEntity;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.crank.HandCrankBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity;
import com.simibubi.create.content.kinetics.flywheel.FlywheelBlockEntity;
import com.simibubi.create.content.kinetics.gantry.GantryShaftBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.steamEngine.PoweredShaftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import com.simibubi.create.content.kinetics.turntable.TurntableBlockEntity;
import com.simibubi.create.content.logistics.funnel.FunnelBlock;
import com.simibubi.create.content.logistics.funnel.FunnelBlockEntity;
import com.simibubi.create.content.logistics.itemHatch.ItemHatchBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.CapManipulationBehaviourBase;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.forgia.compat.create.CreateAudit;
import net.neoforged.forgia.compat.create.CreateTickAdmission;
import net.neoforged.forgia.compat.create.mixin.ArmBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.DeployerBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.EncasedFanBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.FunnelBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.forgia.compat.create.mixin.SmartBlockEntityAccessor;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real Create functions through the actual canonical Level entry, not the historical explicit driver. */
@Timeout(60)
class CreateCanonicalIntegrationTest {
    private static final BlockPos FIRST = new BlockPos(17, 64, 17);
    private static final BlockPos SECOND = new BlockPos(65, 64, 17);

    /** Simulated Create addon BlockEntity that inherits SplitShaftBlockEntity without overriding the tick closure. */
    public static final class AddonBrassGearboxBlockEntity extends SplitShaftBlockEntity {
        public AddonBrassGearboxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        @Override
        public float getRotationSpeedModifier(Direction face) {
            return 1.0f;
        }
    }

    /** Simulated Create addon BlockEntity that overrides tick() with custom unaudited logic and must be rejected. */
    public static final class UnauditedAddonOverrideBlockEntity extends SplitShaftBlockEntity {
        public UnauditedAddonOverrideBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
            super(type, pos, state);
        }

        @Override
        public void tick() {
            super.tick();
        }

        @Override
        public float getRotationSpeedModifier(Direction face) {
            return 1.0f;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void clutchesAndGearshiftsRunOriginalBodiesConcurrentlyThroughThePublicApi(boolean sameRegion) {
        try (final var fixture = new CreateFixture()) {
            final var first = clutch(fixture, FIRST);
            final var second = gearshift(fixture, sameRegion ? FIRST.east() : SECOND);
            fixture.originalCreateTicker(first);
            fixture.originalCreateTicker(second);
            final var audit = ParallelApi.forServer(fixture.server).orElseThrow().auditedBlockEntityTicks().getFirst();
            assertTrue(audit.verified(), audit.toString());
            final var rendezvous = new CyclicBarrier(2);
            final List<Thread> bodies = new CopyOnWriteArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                if (Thread.currentThread() != fixture.main) await(rendezvous);
                body.run();
            });
            fixture.canonicalTick();
            assertEquals(2, bodies.size());
            assertTrue(bodies.stream().noneMatch(thread -> thread == fixture.main), bodies.toString());
            assertTrue(bodies.get(0) != bodies.get(1));
            assertEquals(999, ((KineticBlockEntityAccessor) first).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) second).forgia$validationCountdown());
            assertEquals(2, fixture.status().workerCompletions());
            assertEquals(2, fixture.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void expandedCreateCoreKineticAndSmartBlockEntitiesRunConcurrentlyOnWorkers() {
        try (final var fixture = new CreateFixture()) {
            final var gantry = new GantryShaftBlockEntity(AllBlockEntityTypes.GANTRY_SHAFT.get(), FIRST, AllBlocks.GANTRY_SHAFT.getDefaultState());
            fixture.steady(gantry, 32);
            final var turntable = new TurntableBlockEntity(AllBlockEntityTypes.TURNTABLE.get(), FIRST.east(), AllBlocks.TURNTABLE.getDefaultState());
            fixture.steady(turntable, 32);
            final var poweredShaft = new PoweredShaftBlockEntity(AllBlockEntityTypes.POWERED_SHAFT.get(), FIRST.east(2), AllBlocks.POWERED_SHAFT.getDefaultState());
            fixture.steady(poweredShaft, 64);
            final var copycat = new CopycatBlockEntity(AllBlockEntityTypes.COPYCAT.get(), SECOND, AllBlocks.COPYCAT_STEP.getDefaultState());
            fixture.steadySmart(copycat);
            final var itemHatch = new ItemHatchBlockEntity(AllBlockEntityTypes.ITEM_HATCH.get(), SECOND.east(), AllBlocks.ITEM_HATCH.getDefaultState());
            fixture.steadySmart(itemHatch);

            fixture.originalCreateTicker(gantry);
            fixture.originalCreateTicker(turntable);
            fixture.originalCreateTicker(poweredShaft);
            fixture.originalCreateTicker(copycat);
            fixture.originalCreateTicker(itemHatch);

            final List<Thread> bodies = new CopyOnWriteArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                body.run();
            });
            fixture.canonicalTick();

            assertEquals(5, bodies.size());
            assertTrue(bodies.stream().noneMatch(thread -> thread == fixture.main), bodies.toString());
            assertEquals(999, ((KineticBlockEntityAccessor) gantry).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) turntable).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) poweredShaft).forgia$validationCountdown());
            assertEquals(999, ((SmartBlockEntityAccessor) copycat).forgia$lazyTickCounter());
            assertEquals(999, ((SmartBlockEntityAccessor) itemHatch).forgia$lazyTickCounter());
            assertEquals(5, fixture.status().workerCompletions());
            assertEquals(5, fixture.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void deployerAndMechanicalArmSteadyTransitRunsConcurrentlyOnWorkersAndReturnsToServerOnActionBoundary() {
        try (final var fixture = new CreateFixture()) {
            final var deployer = new DeployerBlockEntity(AllBlockEntityTypes.DEPLOYER.get(), FIRST, AllBlocks.DEPLOYER.getDefaultState());
            fixture.steady(deployer, 64);
            CreateFixture.set(DeployerBlockEntity.class, deployer, "timer", 500);

            final var arm = new ArmBlockEntity(AllBlockEntityTypes.MECHANICAL_ARM.get(), SECOND, AllBlocks.MECHANICAL_ARM.getDefaultState());
            fixture.steady(arm, 64);
            CreateFixture.set(ArmBlockEntity.class, arm, "updateInteractionPoints", false);
            CreateFixture.set(ArmBlockEntity.class, arm, "chasedPointProgress", 0.25f);

            fixture.originalCreateTicker(deployer);
            fixture.originalCreateTicker(arm);

            final var rendezvous = new CyclicBarrier(2);
            final List<Thread> bodies = new CopyOnWriteArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                if (Thread.currentThread() != fixture.main) await(rendezvous);
                body.run();
            });
            fixture.canonicalTick();

            assertEquals(2, bodies.size());
            assertTrue(bodies.stream().noneMatch(thread -> thread == fixture.main), bodies.toString());
            assertTrue(bodies.get(0) != bodies.get(1));
            assertEquals(372, ((DeployerBlockEntityAccessor) deployer).forgia$timer());
            assertEquals(0.3125f, ((ArmBlockEntityAccessor) arm).forgia$chasedPointProgress(), 1.0e-5f);
            assertEquals(2, fixture.status().workerCompletions());

            // Now move both to their action completion boundary (Deployer timer == 0, Arm progress + delta >= 1.0f)
            // and verify they fall back to the server thread for the world/inventory interaction tick.
            bodies.clear();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                body.run();
            });
            CreateFixture.set(DeployerBlockEntity.class, deployer, "timer", 0);
            CreateFixture.set(ArmBlockEntity.class, arm, "chasedPointProgress", 0.95f);
            fixture.canonicalTick();

            assertEquals(2, bodies.size());
            assertSame(fixture.main, bodies.get(0));
            assertSame(fixture.main, bodies.get(1));
            assertEquals(2, fixture.status().workerCompletions());
        }
    }

    @Test
    void pureAddonInheritorBlockEntityPassesAuditAndRunsConcurrentlyOnWorkers() {
        assertTrue(CreateAudit.isPureInheritedSmartBlockEntity(AddonBrassGearboxBlockEntity.class));
        assertFalse(CreateAudit.isPureInheritedSmartBlockEntity(UnauditedAddonOverrideBlockEntity.class));

        try (final var fixture = new CreateFixture()) {
            ParallelAdapterRuntime.close(fixture.server);
            ParallelAdapterRuntime.initialize(fixture.server, registrar -> registrar.registerBlockEntityExecutionAdapter(
                    BlockEntityExecutionAdapter.PROTOCOL_VERSION,
                    CreateTickAdmission.ID,
                    CreateTickAdmission.TYPES,
                    new CreateTickAdmission(Set.of(AddonBrassGearboxBlockEntity.class))));

            final var audit = ParallelApi.forServer(fixture.server).orElseThrow().auditedBlockEntityTicks().getFirst();
            assertTrue(audit.verified(), audit.toString());

            final var first = new AddonBrassGearboxBlockEntity(AllBlockEntityTypes.GEARBOX.get(), FIRST, AllBlocks.GEARBOX.getDefaultState());
            final var second = new AddonBrassGearboxBlockEntity(AllBlockEntityTypes.GEARBOX.get(), SECOND, AllBlocks.GEARBOX.getDefaultState());
            fixture.steady(first, 64);
            fixture.steady(second, 64);
            fixture.originalCreateTicker(first);
            fixture.originalCreateTicker(second);

            final var rendezvous = new CyclicBarrier(2);
            final List<Thread> bodies = new CopyOnWriteArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                if (Thread.currentThread() != fixture.main) await(rendezvous);
                body.run();
            });
            fixture.canonicalTick();

            assertEquals(2, bodies.size());
            assertTrue(bodies.stream().noneMatch(thread -> thread == fixture.main), bodies.toString());
            assertTrue(bodies.get(0) != bodies.get(1));
            assertEquals(999, ((KineticBlockEntityAccessor) (Object) first).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) (Object) second).forgia$validationCountdown());
            assertEquals(2, fixture.status().workerCompletions());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void moreThan2048RealCreateTickersKeepTheirOriginalBodiesAndRunOnWorkers() {
        try (final var fixture = new CreateFixture()) {
            final List<ClutchBlockEntity> sources = new ArrayList<>();
            for (int index = 0; index < 2050; ++index) {
                final var source = clutch(fixture, new BlockPos(17 + index % 14, 64 + index / 196, 17 + index / 14 % 14));
                sources.add(source);
                fixture.originalCreateTicker(source);
            }
            fixture.canonicalTick();
            for (final var source : sources)
                assertEquals(999, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(2050, fixture.status().workerCompletions());
            assertEquals(2050, fixture.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void particleCallbackBoundaryRunsTheWholeOriginalBodySeriallyThenBecomesEligibleAgain() {
        try (final var fixture = new CreateFixture()) {
            final var source = clutch(fixture, FIRST);
            fixture.originalCreateTicker(source);
            final List<Thread> bodies = new ArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                body.run();
            });
            fixture.canonicalTick();
            final var effects = ((KineticBlockEntityAccessor) source).forgia$effects();
            CreateFixture.set(effects.getClass(), effects, "particleSpawnCountdown", 1);
            fixture.canonicalTick();
            fixture.canonicalTick();
            assertEquals(3, bodies.size());
            assertTrue(bodies.get(0) != fixture.main);
            assertSame(fixture.main, bodies.get(1));
            assertTrue(bodies.get(2) != fixture.main);
            assertEquals(997, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(2, fixture.status().workerCompletions());
        }
    }

    @Test
    void dirtyNetworkBoundaryRunsSeriallyAndRefreshesTheNextCanonicalTick() {
        try (final var fixture = new CreateFixture()) {
            final var source = clutch(fixture, FIRST);
            fixture.originalCreateTicker(source);
            final List<Thread> bodies = new ArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                body.run();
            });
            fixture.canonicalTick();
            source.networkDirty = true;
            fixture.canonicalTick();
            assertTrue(!source.networkDirty, "the complete original network update must run");
            fixture.canonicalTick();
            assertEquals(3, bodies.size());
            assertTrue(bodies.get(0) != fixture.main);
            assertSame(fixture.main, bodies.get(1));
            assertTrue(bodies.get(2) != fixture.main);
            assertEquals(997, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(2, fixture.status().workerCompletions());
        }
    }

    @Test
    void steadyOriginalTraceMatchesSerialAtDifferentSpeeds() {
        try (final var fixture = new CreateFixture()) {
            final var source = clutch(fixture, FIRST);
            final var reference = clutch(fixture, SECOND);
            fixture.originalCreateTicker(source);
            for (int tick = 0; tick < 12; ++tick) {
                final float speed = new float[] { 0, 64, -128, 256 }[tick % 4];
                source.setSpeed(speed);
                reference.setSpeed(speed);
                fixture.canonicalTick();
                reference.tick();
                assertEquals(reference.getTheoreticalSpeed(), source.getTheoreticalSpeed());
                assertEquals(((KineticBlockEntityAccessor) reference).forgia$validationCountdown(),
                        ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
                assertEquals(reference.networkDirty, source.networkDirty);
                assertEquals(reference.preventSpeedUpdate, source.preventSpeedUpdate);
            }
            assertEquals(12, fixture.status().workerCompletions());
        }
    }

    @Test
    void aDifferentTickerFunctionCannotInheritTheSourceClassAudit() {
        try (final var fixture = new CreateFixture()) {
            final var source = clutch(fixture, FIRST);
            final var thread = new AtomicReference<Thread>();
            final var calls = new AtomicInteger();
            final BlockEntityTicker<ClutchBlockEntity> replacement = (level, pos, state, entity) -> {
                thread.set(Thread.currentThread());
                calls.incrementAndGet();
                entity.tick();
            };
            fixture.canonicalTicker(source, replacement);
            fixture.canonicalTick();
            assertSame(fixture.main, thread.get());
            assertEquals(1, calls.get());
            assertEquals(999, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(0, fixture.status().workerCompletions());
        }
    }

    @Test
    void aBehaviourAddedAfterACachedWorkerTickKeepsItsCompleteSerialCallback() {
        try (final var fixture = new CreateFixture()) {
            final var source = clutch(fixture, FIRST);
            fixture.originalCreateTicker(source);
            fixture.canonicalTick();
            final var calls = new AtomicInteger();
            source.attachBehaviourLate(new BlockEntityBehaviour(source) {
                private final BehaviourType<BlockEntityBehaviour> type = new BehaviourType<>();

                @Override
                public BehaviourType<?> getType() {
                    return this.type;
                }

                @Override
                public void tick() {
                    assertSame(fixture.main, Thread.currentThread());
                    calls.incrementAndGet();
                }
            });
            fixture.canonicalTick();
            assertEquals(1, calls.get());
            assertEquals(998, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(1, fixture.status().workerCompletions());
        }
    }

    @Test
    void unknownSubclassRemainsSerialEvenWithTheSameRegistryTypeAndFunction() {
        try (final var fixture = new CreateFixture()) {
            final var source = new ClutchBlockEntity(AllBlockEntityTypes.CLUTCH.get(), FIRST, AllBlocks.CLUTCH.getDefaultState()) {};
            fixture.steady(source, 0);
            fixture.originalCreateTicker(source);
            final var thread = new AtomicReference<Thread>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            fixture.canonicalTick();
            assertSame(fixture.main, thread.get());
            assertEquals(999, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(0, fixture.status().workerCompletions());
        }
    }

    @Test
    void completeSerialFenceChangesTheLaterSourcesAdmissionBeforeItsBody() {
        try (final var fixture = new CreateFixture()) {
            final var first = clutch(fixture, FIRST);
            final var later = gearshift(fixture, SECOND);
            fixture.originalCreateTicker(first);
            final var fence = new FurnaceBlockEntity(new BlockPos(113, 64, 17), Blocks.FURNACE.defaultBlockState());
            final List<String> order = new CopyOnWriteArrayList<>();
            final BlockEntityTicker<FurnaceBlockEntity> callback = (level, pos, state, entity) -> {
                assertSame(fixture.main, Thread.currentThread());
                order.add("fence");
                final var effects = ((KineticBlockEntityAccessor) later).forgia$effects();
                CreateFixture.set(effects.getClass(), effects, "particleSpawnCountdown", 1);
            };
            fixture.canonicalTicker(fence, callback);
            fixture.originalCreateTicker(later);
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                if (ticker.getPos().equals(FIRST)) {
                    assertTrue(Thread.currentThread() != fixture.main);
                    order.add("first");
                } else if (ticker.getPos().equals(SECOND)) {
                    assertSame(fixture.main, Thread.currentThread());
                    order.add("later");
                }
                body.run();
            });
            fixture.canonicalTick();
            assertEquals(List.of("first", "fence", "later"), order);
            assertEquals(999, ((KineticBlockEntityAccessor) first).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) later).forgia$validationCountdown());
            assertEquals(1, fixture.status().workerCompletions());
        }
    }

    @Test
    void top10CreateMachinesRunSteadyTicksConcurrentlyOnWorkers() {
        try (final var fixture = new CreateFixture()) {
            org.mockito.Mockito.when(fixture.level.getGameTime()).thenReturn(1L);
            // 1. Belt non-controller segment + empty Belt controller
            final var beltSegment = new BeltBlockEntity(AllBlockEntityTypes.BELT.get(), FIRST, AllBlocks.BELT.getDefaultState());
            fixture.steady(beltSegment, 32);
            beltSegment.beltLength = 3;
            beltSegment.setController(FIRST.east());
            CreateFixture.set(BeltBlockEntity.class, beltSegment, "itemHandler", new ItemStackHandler(1));

            final var beltController = new BeltBlockEntity(AllBlockEntityTypes.BELT.get(), FIRST.east(), AllBlocks.BELT.getDefaultState());
            fixture.steady(beltController, 32);
            beltController.beltLength = 3;
            beltController.setController(FIRST.east());
            CreateFixture.set(BeltBlockEntity.class, beltController, "itemHandler", new ItemStackHandler(1));
            final var beltInv = new BeltInventory(beltController);
            CreateFixture.set(BeltInventory.class, beltInv, "beltMovementPositive", beltController.getDirectionAwareBeltMovementSpeed() > 0.0f);
            CreateFixture.set(BeltBlockEntity.class, beltController, "inventory", beltInv);

            // 2. MechanicalCrafter in IDLE phase with matching redstone state (false == hasNeighborSignal)
            final var crafter = new MechanicalCrafterBlockEntity(AllBlockEntityTypes.MECHANICAL_CRAFTER.get(), FIRST.east(2), AllBlocks.MECHANICAL_CRAFTER.getDefaultState());
            fixture.steady(crafter, 64);
            CreateFixture.set(MechanicalCrafterBlockEntity.class, crafter, "wasPoweredBefore", false);
            for (final var behaviour : ((SmartBlockEntityAccessor) crafter).forgia$behaviours().values()) {
                if (behaviour instanceof InvManipulationBehaviour) {
                    CreateFixture.set(CapManipulationBehaviourBase.class, behaviour, "findNewNextTick", false);
                    CreateFixture.set(com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.class, behaviour, "lazyTickCounter", 10);
                }
            }

            // 3. Funnel in COLLECT mode and cooling-down EXTRACT mode
            final var collectFunnel = new FunnelBlockEntity(AllBlockEntityTypes.FUNNEL.get(), SECOND, AllBlocks.ANDESITE_FUNNEL.getDefaultState().setValue(FunnelBlock.EXTRACTING, false));
            fixture.steadySmart(collectFunnel);
            for (final var behaviour : ((SmartBlockEntityAccessor) collectFunnel).forgia$behaviours().values()) {
                if (behaviour instanceof InvManipulationBehaviour) {
                    CreateFixture.set(CapManipulationBehaviourBase.class, behaviour, "findNewNextTick", false);
                    CreateFixture.set(com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.class, behaviour, "lazyTickCounter", 10);
                }
            }

            final var extractFunnel = new FunnelBlockEntity(AllBlockEntityTypes.FUNNEL.get(), SECOND.east(), AllBlocks.ANDESITE_FUNNEL.getDefaultState().setValue(FunnelBlock.EXTRACTING, true));
            fixture.steadySmart(extractFunnel);
            CreateFixture.set(FunnelBlockEntity.class, extractFunnel, "extractionCooldown", 5);
            for (final var behaviour : ((SmartBlockEntityAccessor) extractFunnel).forgia$behaviours().values()) {
                if (behaviour instanceof InvManipulationBehaviour) {
                    CreateFixture.set(CapManipulationBehaviourBase.class, behaviour, "findNewNextTick", false);
                    CreateFixture.set(com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.class, behaviour, "lazyTickCounter", 10);
                }
            }

            // 4. FluidPipe, StraightPipe, and Pump in steady state
            final var fluidPipe = new FluidPipeBlockEntity(AllBlockEntityTypes.FLUID_PIPE.get(), SECOND.east(2), AllBlocks.FLUID_PIPE.getDefaultState());
            fixture.steadySmart(fluidPipe);

            final var straightPipe = new StraightPipeBlockEntity(AllBlockEntityTypes.GLASS_FLUID_PIPE.get(), SECOND.east(3), AllBlocks.GLASS_FLUID_PIPE.getDefaultState());
            fixture.steadySmart(straightPipe);

            final var pump = new PumpBlockEntity(AllBlockEntityTypes.MECHANICAL_PUMP.get(), SECOND.east(4), AllBlocks.MECHANICAL_PUMP.getDefaultState());
            fixture.steady(pump, 32);
            for (final var behaviour : ((SmartBlockEntityAccessor) pump).forgia$behaviours().values()) {
                if (behaviour instanceof com.simibubi.create.content.fluids.FluidTransportBehaviour) {
                    CreateFixture.set(com.simibubi.create.content.fluids.FluidTransportBehaviour.class, behaviour, "interfaces", java.util.Map.of());
                }
            }

            // 5. Spinning EncasedFan with empty AirCurrent and positive cooldowns
            final var fan = new EncasedFanBlockEntity(AllBlockEntityTypes.ENCASED_FAN.get(), SECOND.east(5), AllBlocks.ENCASED_FAN.getDefaultState());
            fixture.steady(fan, 64);
            CreateFixture.set(EncasedFanBlockEntity.class, fan, "airCurrentUpdateCooldown", 10);
            CreateFixture.set(EncasedFanBlockEntity.class, fan, "entitySearchCooldown", 4);
            CreateFixture.set(EncasedFanBlockEntity.class, fan, "updateAirFlow", false);
            fan.airCurrent.direction = Direction.NORTH;

            fixture.originalCreateTicker(beltSegment);
            fixture.originalCreateTicker(beltController);
            fixture.originalCreateTicker(crafter);
            fixture.originalCreateTicker(collectFunnel);
            fixture.originalCreateTicker(extractFunnel);
            fixture.originalCreateTicker(fluidPipe);
            fixture.originalCreateTicker(straightPipe);
            fixture.originalCreateTicker(pump);
            fixture.originalCreateTicker(fan);

            final List<Thread> bodies = new CopyOnWriteArrayList<>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                bodies.add(Thread.currentThread());
                body.run();
            });
            fixture.canonicalTick();

            assertEquals(9, bodies.size());
            assertTrue(bodies.stream().noneMatch(thread -> thread == fixture.main), bodies.toString());
            assertEquals(999, ((KineticBlockEntityAccessor) beltSegment).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) beltController).forgia$validationCountdown());
            assertEquals(999, ((KineticBlockEntityAccessor) crafter).forgia$validationCountdown());
            assertEquals(4, ((FunnelBlockEntityAccessor) extractFunnel).forgia$extractionCooldown());
            assertEquals(999, ((SmartBlockEntityAccessor) fluidPipe).forgia$lazyTickCounter());
            assertEquals(999, ((SmartBlockEntityAccessor) straightPipe).forgia$lazyTickCounter());
            assertEquals(999, ((KineticBlockEntityAccessor) pump).forgia$validationCountdown());
            assertEquals(9, ((EncasedFanBlockEntityAccessor) fan).forgia$airCurrentUpdateCooldown());
            assertEquals(3, ((EncasedFanBlockEntityAccessor) fan).forgia$entitySearchCooldown());
            assertEquals(9, fixture.status().workerCompletions());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    private static ClutchBlockEntity clutch(CreateFixture fixture, BlockPos position) {
        final var source = new ClutchBlockEntity(AllBlockEntityTypes.CLUTCH.get(), position, AllBlocks.CLUTCH.getDefaultState());
        fixture.steady(source, 0);
        return source;
    }

    private static GearshiftBlockEntity gearshift(CreateFixture fixture, BlockPos position) {
        final var source = new GearshiftBlockEntity(AllBlockEntityTypes.GEARSHIFT.get(), position, AllBlocks.GEARSHIFT.getDefaultState());
        fixture.steady(source, 0);
        return source;
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
