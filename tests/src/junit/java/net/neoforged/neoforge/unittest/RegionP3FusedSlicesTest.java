/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.region.FusedRegionTickSession;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class RegionP3FusedSlicesTest {
    @Test
    void stageTakeAndDuplicateStageGuard() {
        final ServerLevel overworld = mockLevel("minecraft", "overworld");
        final RegionTickBarrier barrier = mock(RegionTickBarrier.class);
        final FusedRegionTickSession session = new FusedRegionTickSession();

        assertFalse(session.hasStagedChunks(overworld));
        assertNull(session.takeStagedChunks(overworld));

        session.stageChunks(overworld, List.of(), 3, barrier);
        assertTrue(session.hasStagedChunks(overworld));

        assertThrows(IllegalStateException.class, () -> session.stageChunks(overworld, List.of(), 3, barrier));

        final FusedRegionTickSession.PendingChunkWork taken = session.takeStagedChunks(overworld);
        assertNotNull(taken);
        assertEquals(3, taken.randomTickSpeed());
        assertTrue(taken.chunks().isEmpty());
        assertFalse(session.hasStagedChunks(overworld));
        assertNull(session.takeStagedChunks(overworld));

        session.recordFusedBatch(7);
        session.recordFusedBatch(5);
        session.recordEarlyFlush();
        session.recordBudgetFallback();

        final FusedRegionTickSession.Snapshot snapshot = session.snapshot();
        assertEquals(2L, snapshot.fusedBatches());
        assertEquals(12L, snapshot.fusedRegions());
        assertEquals(2L, snapshot.barriersSaved());
        assertEquals(1L, snapshot.earlyFlushes());
        assertEquals(1L, snapshot.budgetFallbacks());
        assertEquals(0L, snapshot.foreignReadFallbacks());
        assertEquals(0, snapshot.activeCooldownRegions());
    }

    @Test
    void foreignReadCooldownExpiresAndClearsPerLevel() {
        final ServerLevel overworld = mockLevel("minecraft", "overworld");
        final ServerLevel nether = mockLevel("minecraft", "the_nether");
        final RegionTickBarrier barrier = mock(RegionTickBarrier.class);
        final FusedRegionTickSession session = new FusedRegionTickSession();

        final RegionCoordinate hotOverworld = new RegionCoordinate(overworld, -37, -54);
        final RegionCoordinate hotNether = new RegionCoordinate(nether, 1, 2);

        assertFalse(session.isNonFusable(hotOverworld, 1000L));
        session.recordForeignReadInvalidation(hotOverworld, 1000L);
        session.recordForeignReadInvalidation(hotNether, 1000L);

        assertTrue(session.isNonFusable(hotOverworld, 1000L));
        assertTrue(session.isNonFusable(hotOverworld, 1000L + FusedRegionTickSession.FOREIGN_READ_COOLDOWN_TICKS - 1L));
        assertEquals(2L, session.snapshot().foreignReadFallbacks());
        assertEquals(2, session.snapshot().activeCooldownRegions());

        // Cooldown expires automatically at t + FOREIGN_READ_COOLDOWN_TICKS
        assertFalse(session.isNonFusable(hotOverworld, 1000L + FusedRegionTickSession.FOREIGN_READ_COOLDOWN_TICKS));
        assertEquals(1, session.snapshot().activeCooldownRegions());

        // Re-mark overworld and stage chunks, then clearLevel(overworld) should only clear overworld
        session.recordForeignReadInvalidation(hotOverworld, 2000L);
        session.stageChunks(overworld, List.of(), 3, barrier);
        session.stageChunks(nether, List.of(), 3, barrier);

        session.clearLevel(overworld);
        assertFalse(session.hasStagedChunks(overworld));
        assertFalse(session.isNonFusable(hotOverworld, 2000L));
        assertTrue(session.hasStagedChunks(nether));
        assertTrue(session.isNonFusable(hotNether, 1050L));
    }

    @Test
    void configDefaultsToFalse() {
        assertFalse(ForgiaConfig.SERVER.enableRegionThreadingFusedSlices.getDefault());
    }

    private static ServerLevel mockLevel(String namespace, String path) {
        final ServerLevel level = mock(ServerLevel.class);
        final ResourceKey<Level> key = ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(namespace, path));
        when(level.dimension()).thenReturn(key);
        return level;
    }
}
