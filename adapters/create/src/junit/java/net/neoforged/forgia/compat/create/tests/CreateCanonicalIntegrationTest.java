/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import com.simibubi.create.content.kinetics.transmission.GearshiftBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
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
