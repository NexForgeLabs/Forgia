/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.neoforged.forgia.compat.create.mixin.TorquePropagatorAccessor;
import net.neoforged.neoforge.server.threading.c7.C7Options;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual transformed Create classes/original bodies, mocked world services. No C7 parallel admission claim. */
@Timeout(60)
class C7CreateFallbackIntegrationTest {
    @Test
    void actualCreateBodiesStaySerialWithoutEnteringTheC6Adapter() {
        try (final var fixture = new CreateFixture()) {
            final var first = fixture.millstone(new BlockPos(17, 64, 17), 100, 64);
            final var second = fixture.millstone(new BlockPos(20, 64, 17), 100, 128);
            final var threads = new ArrayList<Thread>();
            final var a = fixture.ticker(first, () -> { threads.add(Thread.currentThread()); first.tick(); });
            final var b = fixture.ticker(second, () -> { threads.add(Thread.currentThread()); second.tick(); });
            org.mockito.Mockito.when(fixture.level.snapshotBlockEntityTickers()).thenReturn(List.of(a, b));
            CreateFixture.set(RegionThreadingEngine.class, fixture.engine, "workerExecutionEnabled", true);
            final var before = fixture.status();
            final var options = new C7Options(C7Options.Mode.PARALLEL, 2, 64, 64, 4096, 64, Long.MAX_VALUE, false, true, false);
            assertEquals(true, fixture.engine.tickC7BlockEntities(fixture.level, CreateFixture.BARRIER, options));
            assertEquals(List.of(fixture.main, fixture.main), threads);
            assertEquals(96, first.timer); assertEquals(92, second.timer);
            assertEquals(2, fixture.engine.c7Metrics().serial()); assertEquals(0, fixture.engine.c7Metrics().parallel());
            assertEquals(before, fixture.status(), "C7 must not open or mutate the C6 adapter session/counters");
            assertEquals(0, fixture.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }

    @Test
    void sharedKineticNetworkIsNotMistakenForTwoIndependentPositionResources() {
        try (final var fixture = new CreateFixture()) {
            final var first = fixture.millstone(new BlockPos(17, 64, 17), 100, 64);
            final var second = fixture.millstone(new BlockPos(65, 64, 17), 100, 64);
            final var networks = TorquePropagatorAccessor.forgia$networks().get(fixture.level);
            networks.remove(second.network);
            second.network = first.network;
            final var shared = networks.get(first.network);
            shared.members.put(second, 0f);
            final var a = fixture.ticker(first, first::tick); final var b = fixture.ticker(second, second::tick);
            org.mockito.Mockito.when(fixture.level.snapshotBlockEntityTickers()).thenReturn(List.of(a, b));
            CreateFixture.set(RegionThreadingEngine.class, fixture.engine, "workerExecutionEnabled", true);
            final var options = new C7Options(C7Options.Mode.PARALLEL, 2, 64, 64, 4096, 64, Long.MAX_VALUE, false, true, false);
            assertEquals(true, fixture.engine.tickC7BlockEntities(fixture.level, CreateFixture.BARRIER, options));
            assertEquals(96, first.timer); assertEquals(96, second.timer);
            assertSame(shared, networks.get(first.network)); assertSame(shared, networks.get(second.network));
            assertEquals(2, fixture.engine.c7Metrics().serial()); assertEquals(0, fixture.engine.c7Metrics().parallel());
            assertEquals(0, fixture.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, fixture.engine.diagnostics().ownershipViolationCount());
        }
    }
}
