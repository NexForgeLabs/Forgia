/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.forgia.compat.create.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.transmission.ClutchBlockEntity;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.neoforged.forgia.compat.create.CreateAudit;
import net.neoforged.forgia.compat.create.mixin.KineticBlockEntityAccessor;
import net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter.Decision;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.Test;

class UnknownCreateHookIntegrationTest {
    @Test
    void genericAuditGateAlsoRejectsTheTransformedOriginalCanonicalTicker() {
        try (final var fixture = new CreateFixture()) {
            final var source = new ClutchBlockEntity(AllBlockEntityTypes.CLUTCH.get(), new BlockPos(17, 64, 17), AllBlocks.CLUTCH.getDefaultState());
            fixture.steady(source, 0);
            fixture.originalCreateTicker(source);
            final var thread = new AtomicReference<Thread>();
            fixture.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            final var audit = ParallelApi.forServer(fixture.server).orElseThrow().auditedBlockEntityTicks().getFirst();
            assertFalse(audit.verified());
            assertEquals("UNVERIFIED_TRANSFORMATION", audit.reason());
            fixture.canonicalTick();
            assertSame(fixture.main, thread.get());
            assertEquals(999, ((KineticBlockEntityAccessor) source).forgia$validationCountdown());
            assertEquals(0, fixture.status().workerCompletions());
        }
    }

    @Test
    void unknownActuallyMergedMixinRetainsTheCompleteOriginalSerialBody() {
        assertFalse(CreateAudit.verify());
        try (final var fixture = new CreateFixture()) {
            final var millstone = fixture.millstone(new BlockPos(17, 64, 17), 100, 64);
            final var thread = new AtomicReference<Thread>();
            fixture.tick(fixture.ticker(millstone, () -> {
                thread.set(Thread.currentThread());
                millstone.tick();
            }));
            assertSame(fixture.main, thread.get());
            assertEquals(96, millstone.timer);
            assertEquals(0, fixture.status().workerCompletions());
            assertEquals(1, fixture.status().reasons().get(Decision.UNVERIFIED_TRANSFORMATIONS));
        }
    }
}
