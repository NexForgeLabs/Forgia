/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.region.DynamicRegionTopology;
import net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionTaskBatch;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionThreadingOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies Folia-style dynamic region merging, splitting, hysteresis, idempotency, and the empty-buffer
 * isolation invariant of {@link DynamicRegionTopology}.
 */
@Timeout(60)
class DynamicRegionTopologyTest {

    @Test
    void isolatedIslandsAndNegativeCoordinates() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 10L);
        // shift=3 -> 8x8 chunks per section
        // chunk (-1, -1) -> section (-1, -1)
        // chunk (16, 0) -> section (2, 0) -> separated from (-1, -1) by empty sections (0, *), (1, *)
        assertTrue(topology.addChunk(-1, -1));
        assertTrue(topology.addChunk(-8, -8)); // same section (-1, -1)
        assertTrue(topology.addChunk(16, 0));

        // Before reconcile, published snapshot is still empty (only updated at GLOBAL safe point)
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtChunk(-1, -1));

        final var res = topology.reconcile(1L);
        assertTrue(res.changed());
        assertEquals(2, topology.regionCount());
        assertEquals(2, topology.activeSectionCount());
        assertEquals(3, topology.loadedChunkCount());

        final int negRegion = topology.regionIdAtChunk(-1, -1);
        final int posRegion = topology.regionIdAtChunk(16, 0);
        assertNotEquals(DynamicRegionTopology.NO_REGION, negRegion);
        assertNotEquals(DynamicRegionTopology.NO_REGION, posRegion);
        assertNotEquals(negRegion, posRegion);
        assertEquals(negRegion, topology.regionIdAtChunk(-8, -8));
        assertTrue(topology.isBlockInRegion(new BlockPos(-1, 64, -1), negRegion));
        assertFalse(topology.isBlockInRegion(new BlockPos(16 * 16, 64, 0), negRegion));

        // Steady state: no changes -> UNCHANGED
        assertFalse(topology.reconcile(2L).changed());
    }

    @Test
    void diagonalAndOrthogonalMergeSmallIntoLarge() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 10L);
        // Region A (size 2): sections (0, 0) and (0, 1)
        topology.addChunk(0, 0);
        topology.addChunk(0, 8);
        // Region B (size 1): section (2, 2) -> distance 2 from (0, 1)
        topology.addChunk(16, 16);
        topology.reconcile(1L);

        final int regionA = topology.regionIdAtSection(0, 0);
        final int regionB = topology.regionIdAtSection(2, 2);
        assertNotEquals(regionA, regionB);
        assertEquals(2, topology.regionCount());

        // Bridge at section (1, 1): touches (0,0), (0,1) AND diagonally touches (2,2)
        topology.addChunk(8, 8);
        final var mergeRes = topology.reconcile(2L);
        assertTrue(mergeRes.changed());
        assertEquals(1, topology.regionCount());
        assertEquals(1, mergeRes.merges().size());
        assertEquals(regionB, mergeRes.merges().get(0).sourceRegionId());
        assertEquals(regionA, mergeRes.merges().get(0).targetRegionId());
        assertEquals(1L, topology.mergeCount());

        // Larger regionA survived and owns all 4 sections
        assertEquals(regionA, topology.regionIdAtSection(0, 0));
        assertEquals(regionA, topology.regionIdAtSection(0, 1));
        assertEquals(regionA, topology.regionIdAtSection(1, 1));
        assertEquals(regionA, topology.regionIdAtSection(2, 2));
    }

    @Test
    void threeWayMergeAtSingleBridgeSection() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(2, 1, 5L);
        // shift=2 -> 4x4 chunks per section
        // Three separate regions around section (0, 0):
        // R1 (size 2): (-1, -1) and (-2, -1)
        topology.addChunk(-4, -4);
        topology.addChunk(-8, -4);
        // R2 (size 1): (1, -1)
        topology.addChunk(4, -4);
        // R3 (size 1): (0, 1)
        topology.addChunk(0, 4);
        topology.reconcile(1L);

        assertEquals(3, topology.regionCount());
        final int r1 = topology.regionIdAtSection(-1, -1);

        // Activate (0, 0) -> touches R1, R2, R3 simultaneously
        topology.addChunk(0, 0);
        final var res = topology.reconcile(2L);
        assertTrue(res.changed());
        assertEquals(1, topology.regionCount());
        assertEquals(2, res.merges().size());
        assertEquals(r1, topology.regionIdAtSection(0, 0));
        assertEquals(r1, topology.regionIdAtSection(1, -1));
        assertEquals(r1, topology.regionIdAtSection(0, 1));
    }

    @Test
    void hysteresisPreventsThrashingAndSplitsAfterDelay() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 20L);
        // Chain of 3 sections: (0,0) - (1,0) - (2,0)
        // Make left side larger: (0,0) and (0,1)
        topology.addChunk(0, 0);
        topology.addChunk(0, 8);
        topology.addChunk(8, 0);
        topology.addChunk(16, 0);
        topology.reconcile(10L);

        final int originalId = topology.regionIdAtSection(0, 0);
        assertEquals(1, topology.regionCount());

        // Unload bridge (1, 0) at tick 10 -> stamped at tick 11
        topology.removeChunk(8, 0);
        assertFalse(topology.reconcile(11L).changed());
        assertEquals(1, topology.regionCount());
        assertEquals(1, topology.pendingEmptySectionCount());

        // Reload bridge before delay expires (tick 25 < 11 + 20) -> cancels split!
        topology.addChunk(8, 0);
        assertFalse(topology.reconcile(25L).changed());
        assertEquals(0, topology.pendingEmptySectionCount());
        assertEquals(0L, topology.splitCount());

        // Unload bridge again and wait full delay
        topology.removeChunk(8, 0);
        assertFalse(topology.reconcile(30L).changed()); // stamped at 30
        assertFalse(topology.reconcile(49L).changed()); // 49 - 30 = 19 < 20

        final var splitRes = topology.reconcile(50L); // 50 - 30 = 20 >= 20 -> split!
        assertTrue(splitRes.changed());
        assertEquals(2, topology.regionCount());
        assertEquals(1, splitRes.splits().size());
        assertEquals(originalId, splitRes.splits().get(0).sourceRegionId());
        assertEquals(1, splitRes.splits().get(0).newRegionIds().length);
        assertEquals(1L, topology.splitCount());

        // Larger component {(0,0), (0,1)} kept originalId
        assertEquals(originalId, topology.regionIdAtSection(0, 0));
        assertEquals(originalId, topology.regionIdAtSection(0, 1));
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(1, 0));

        final int newId = splitRes.splits().get(0).newRegionIds()[0];
        assertEquals(newId, topology.regionIdAtSection(2, 0));
        assertNotEquals(originalId, newId);
    }

    @Test
    void removingSectionFromConnectedRingDoesNotSplit() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 0L);
        // 2x2 square: (0,0), (1,0), (0,1), (1,1)
        topology.addChunk(0, 0);
        topology.addChunk(8, 0);
        topology.addChunk(0, 8);
        topology.addChunk(8, 8);
        topology.reconcile(1L);

        final int id = topology.regionIdAtSection(0, 0);
        topology.removeChunk(8, 8);
        final var res = topology.reconcile(2L);
        assertTrue(res.changed());
        assertEquals(1, topology.regionCount());
        assertTrue(res.splits().isEmpty());
        assertEquals(0L, topology.splitCount());
        assertEquals(id, topology.regionIdAtSection(0, 0));
        assertEquals(id, topology.regionIdAtSection(1, 0));
        assertEquals(id, topology.regionIdAtSection(0, 1));
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtSection(1, 1));
    }

    @Test
    void unloadingAllChunksRetiresRegionAndIdempotencyHolds() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 0L);
        // Duplicate add & stray remove
        assertTrue(topology.addChunk(0, 0));
        assertFalse(topology.addChunk(0, 0));
        assertFalse(topology.removeChunk(99, 99));

        // Add and remove before reconcile -> no phantom region
        assertTrue(topology.addChunk(64, 64));
        assertTrue(topology.removeChunk(64, 64));

        final var res1 = topology.reconcile(1L);
        assertTrue(res1.changed());
        assertEquals(1, topology.regionCount());
        final int id = topology.regionIdAtChunk(0, 0);

        // Unload last chunk
        assertTrue(topology.removeChunk(0, 0));
        final var res2 = topology.reconcile(2L);
        assertTrue(res2.changed());
        assertEquals(0, topology.regionCount());
        assertEquals(1, res2.retiredRegionIds().length);
        assertEquals(id, res2.retiredRegionIds()[0]);
        assertEquals(DynamicRegionTopology.NO_REGION, topology.regionIdAtChunk(0, 0));
    }

    @Test
    void randomizedFuzzAlwaysMaintainsBufferZoneIsolationInvariant() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(2, 1, 3L);
        final Random rng = new Random(0xF011A123L);
        // Operate in a 6x6 section space (-3..2) -> chunks (-12..11)
        for (int tick = 1; tick <= 300; ++tick) {
            for (int op = 0; op < 6; ++op) {
                final int cx = rng.nextInt(24) - 12;
                final int cz = rng.nextInt(24) - 12;
                if (rng.nextBoolean()) {
                    topology.addChunk(cx, cz);
                } else {
                    topology.removeChunk(cx, cz);
                }
            }
            topology.reconcile(tick);
            assertIsolationInvariant(topology);
        }

        topology.clear();
        assertEquals(0, topology.regionCount());
        assertEquals(0, topology.loadedChunkCount());
    }

    @Test
    void sectionPartitionsQueryMergedDynamicRegionWithoutMigrationAndIsolatesForeignRegions() {
        final RegionLayout layout = RegionLayout.dynamicCluster(3, 0L); // 8x8 chunks per section
        assertTrue(layout.isDynamicCluster());
        assertTrue(layout.supportsSectionPartitions());
        assertEquals(RegionEntityStorageMode.REGION_OWNED,
                RegionEntityStorageMode.forConfiguration(true, false, layout));

        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 0L);
        final FixedEntitySectionPartitions<Entity> partitions = new FixedEntitySectionPartitions<>(layout);

        // Place entities in section (0,0) [chunk 7,0], section (1,0) [chunk 8,0] (adjacent -> merged!),
        // and section (3,0) [chunk 24,0] (separated by empty section (2,0) -> distinct region!).
        final Entity e0 = mock(Entity.class);
        final Entity e1 = mock(Entity.class);
        final Entity eForeign = mock(Entity.class);
        addEntityToSection(partitions, SectionPos.asLong(7, 4, 0), e0);
        addEntityToSection(partitions, SectionPos.asLong(8, 4, 0), e1);
        addEntityToSection(partitions, SectionPos.asLong(24, 4, 0), eForeign);

        topology.addChunk(7, 0);
        topology.addChunk(8, 0);
        topology.addChunk(24, 0);
        topology.reconcile(1L);

        final int mergedRegion = topology.regionIdAtSection(0, 0);
        final int foreignRegion = topology.regionIdAtSection(3, 0);
        assertEquals(mergedRegion, topology.regionIdAtSection(1, 0));
        assertNotEquals(mergedRegion, foreignRegion);

        final List<String> accessedBuckets = new ArrayList<>();
        partitions.setBucketAccessObserverForTesting((rx, rz) -> accessedBuckets.add(rx + "," + rz));

        // Huge box covering all three sections: query for mergedRegion must see e0 AND e1 across the
        // section (0,0)/(1,0) border, and NEVER consult foreign bucket (3,0)!
        final AABB wideBox = new AABB(0, 0, 0, 26 * 16, 128, 16);
        final List<Entity> found = new ArrayList<>();
        partitions.forEachAccessibleNonEmptySectionInDynamicRegion(topology, mergedRegion, wideBox, section -> {
            section.getEntities().forEach(found::add);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });

        assertEquals(List.of(e0, e1), found);
        assertFalse(accessedBuckets.contains("3,0"), "Foreign region bucket must never be consulted");
        assertTrue(accessedBuckets.contains("0,0"));
        assertTrue(accessedBuckets.contains("1,0"));

        // Now add bridge section (2,0) -> all three merge with ZERO entity section migration!
        topology.addChunk(16, 0);
        topology.reconcile(2L);
        assertEquals(1, topology.regionCount());
        final int unifiedRegion = topology.regionIdAtSection(0, 0);

        found.clear();
        partitions.forEachAccessibleNonEmptySectionInDynamicRegion(topology, unifiedRegion, wideBox, section -> {
            section.getEntities().forEach(found::add);
            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
        assertEquals(List.of(e0, e1, eForeign), found);
    }

    @Test
    void dispatchCoalescesOnlyMappedCellsAndRecomputesAfterSplit() throws ReflectiveOperationException {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 0L);
        topology.addChunk(0, 0);
        topology.addChunk(8, 0);
        topology.addChunk(16, 0);
        topology.reconcile(1L);

        final var add = RegionTaskBatch.class.getDeclaredMethod("add", RegionCoordinate.class, String.class, long.class, Runnable.class);
        final var coalesce = RegionTaskBatch.class.getDeclaredMethod("groupByLoadedIsland", DynamicRegionTopology.class, int.class);
        add.setAccessible(true);
        coalesce.setAccessible(true);
        final ServerLevel level = mock(ServerLevel.class);
        final List<Integer> ran = new ArrayList<>();
        for (int expectedTasks : List.of(3, 4)) {
            final RegionTaskBatch batch = new RegionTaskBatch();
            // Put both untracked cells between the two peers in registration order.
            for (int cell : List.of(0, 4, 5, 2)) {
                final Runnable work = () -> ran.add(cell);
                add.invoke(batch, new RegionCoordinate(level, cell, 0), "cell " + cell, 0L, work);
            }
            coalesce.invoke(batch, topology, 4);
            assertEquals(4, batch.taskCount(), "Keep one task available per worker");
            coalesce.invoke(batch, topology, 2);
            assertEquals(expectedTasks, batch.taskCount());
            assertEquals(4, batch.groupCount());
            ran.clear();
            final var barrier = batch.submit((description, task) -> false);
            barrier.throwIfFailed();
            assertEquals(expectedTasks == 3 ? List.of(0, 2, 4, 5) : List.of(0, 4, 5, 2), ran);
            topology.removeChunk(8, 0);
            topology.reconcile(2L);
        }
    }

    @Test
    void weightedDispatchKeepsTwoHotSectionsInSeparateTasks() throws ReflectiveOperationException {
        final var topology = new DynamicRegionTopology(3, 1, 0L);
        final var batch = new RegionTaskBatch();
        final ServerLevel level = mock(ServerLevel.class);
        final var add = RegionTaskBatch.class.getDeclaredMethod("add", RegionCoordinate.class, String.class, long.class, Runnable.class);
        final var coalesce = RegionTaskBatch.class.getDeclaredMethod("groupByLoadedIsland", DynamicRegionTopology.class, int.class);
        add.setAccessible(true);
        coalesce.setAccessible(true);
        final long[] weights = { 9, 8, 1, 1 };
        final List<Integer> ran = new ArrayList<>();
        for (int cell = 0; cell < weights.length; ++cell) {
            topology.addChunk(cell * 8, 0);
            final int index = cell;
            final Runnable work = () -> ran.add(index);
            add.invoke(batch, new RegionCoordinate(level, cell, 0), "cell " + cell, weights[cell], work);
        }
        topology.reconcile(1L);
        coalesce.invoke(batch, topology, 2);
        final List<Runnable> submitted = new ArrayList<>();
        final var barrier = batch.submit((description, task) -> submitted.add(task));
        assertEquals(2, submitted.size());
        submitted.getFirst().run();
        assertEquals(List.of(0), ran);
        submitted.getLast().run();
        barrier.throwIfFailed();
        assertEquals(List.of(0, 1, 2, 3), ran);
    }

    @Test
    void engineLifecycleTracksAndReconcilesDynamicClusterTopology() {
        final RegionLayout layout = RegionLayout.dynamicCluster(3, 2L);
        final RegionThreadingOptions options = new RegionThreadingOptions(
                new RegionThreadingOptions.Workers(2, false, false, false, false),
                new RegionThreadingOptions.TickSlices(true, false, false, false, false),
                layout,
                RegionEntityStorageMode.forConfiguration(true, false, layout));
        final RegionThreadingEngine engine = new RegionThreadingEngine(options);
        final ServerLevel level = mock(ServerLevel.class);
        try {
            assertEquals(RegionPolicy.DYNAMIC_CLUSTER, engine.regionPolicy());
            engine.addRegionSection(level, 0, 0);
            engine.addRegionSection(level, 8, 0);   // adjacent section (1,0) -> merges with (0,0)
            engine.addRegionSection(level, 24, 0);  // distant section (3,0) -> separate region

            // Reconcile happens at beginLevelTick (GLOBAL safe point)
            engine.beginLevelTick(level);
            engine.endLevelTick(level);

            final DynamicRegionTopology topology = engine.dynamicTopologyOrNull(level);
            assertNotNull(topology);
            assertEquals(2, topology.regionCount());
            assertEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(1, 0));
            assertNotEquals(topology.regionIdAtSection(0, 0), topology.regionIdAtSection(3, 0));

            // Chunk loading later in the original level tick must be visible to the next slice.
            engine.beginLevelTick(level);
            engine.addRegionSection(level, 16, 0);
            assertEquals(2, topology.regionCount());
            engine.beginRegionPhase(level);
            assertEquals(1, topology.regionCount());
            engine.endRegionPhase(level);
            engine.endLevelTick(level);

            engine.clearLevelRegionTasks(level);
            engine.addRegionSection(level, 0, 0);
            assertNull(engine.dynamicTopologyOrNull(level));
        } finally {
            engine.shutdown();
        }
    }

    private static void addEntityToSection(FixedEntitySectionPartitions<Entity> partitions, long sectionId, Entity entity) {
        final EntitySection<Entity> section = partitions.getOrCreate(sectionId, id -> new EntitySection<>(Entity.class, Visibility.TICKING));
        section.add(entity);
    }

    private static void assertIsolationInvariant(DynamicRegionTopology topology) {
        final List<DynamicRegionTopology.RegionSnapshot> snapshots = topology.snapshotRegions();
        assertEquals(topology.regionCount(), snapshots.size());

        // Check every pair of sections belonging to DIFFERENT regions has Chebyshev distance > mergeRadius.
        for (int i = 0; i < snapshots.size(); ++i) {
            final var a = snapshots.get(i);
            for (final long keyA : a.sectionKeys()) {
                final int ax = DynamicRegionTopology.sectionXFromKey(keyA);
                final int az = DynamicRegionTopology.sectionZFromKey(keyA);
                assertEquals(a.regionId(), topology.regionIdAtSection(ax, az),
                        "Published map must match region snapshot");

                for (int j = i + 1; j < snapshots.size(); ++j) {
                    final var b = snapshots.get(j);
                    for (final long keyB : b.sectionKeys()) {
                        final int bx = DynamicRegionTopology.sectionXFromKey(keyB);
                        final int bz = DynamicRegionTopology.sectionZFromKey(keyB);
                        final int dist = Math.max(Math.abs(ax - bx), Math.abs(az - bz));
                        assertTrue(dist > topology.mergeRadius(),
                                "Distinct regions " + a.regionId() + " and " + b.regionId()
                                        + " violated buffer invariant at (" + ax + "," + az + ") vs (" + bx + "," + bz + ")");
                    }
                }
            }
        }
    }
}
