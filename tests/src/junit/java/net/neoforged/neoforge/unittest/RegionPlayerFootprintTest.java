/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionPlayerConflictPlan;
import net.neoforged.neoforge.server.threading.region.RegionPlayerFootprint;
import org.junit.jupiter.api.Test;

class RegionPlayerFootprintTest {
    private final ServerLevel level = mock(ServerLevel.class);

    private RegionPlayerFootprint footprint(double x, double z) {
        AABB body = new AABB(x - .3, 64, z - .3, x + .3, 65.8, z + .3);
        return RegionPlayerFootprint.of(this.level, 3, body.inflate(1, .5, 1), body.inflate(1, 2, 1));
    }

    private RegionPlayerConflictPlan.Entry entry(Entity player, double x, double z, List<Entity> targets) {
        return new RegionPlayerConflictPlan.Entry(player, new RegionCoordinate(this.level, (int) Math.floor(x / 48), (int) Math.floor(z / 48)), this.footprint(x, z), targets);
    }

    @Test
    void exactPositiveAndNegativeCornersIncludeAllSearchBuckets() {
        assertEquals(List.of(new RegionCoordinate(this.level, 0, 0), new RegionCoordinate(this.level, 0, 1), new RegionCoordinate(this.level, 1, 0), new RegionCoordinate(this.level, 1, 1)), footprint(47.5, 47.5).regions());
        assertEquals(List.of(new RegionCoordinate(this.level, -2, -2), new RegionCoordinate(this.level, -2, -1), new RegionCoordinate(this.level, -1, -2), new RegionCoordinate(this.level, -1, -1)), footprint(-48.5, -48.5).regions());
    }

    @Test
    void sectionSearchPaddingLeasesNeighbourEvenWhenOriginalQueryDoesNotCross() {
        var f = footprint(45.5, 8);
        assertTrue(f.queryBounds().maxX < 48);
        assertTrue(f.containsRegion(this.level, 1, 0));
        assertFalse(f.containsQuery(this.level, f.queryBounds().inflate(.01)));
        assertFalse(f.containsBlock(this.level, new BlockPos(900, 64, 8)));
    }

    @Test
    void originalIntegerBlockScanIncludesFlooredFractionalMinimaAtPositiveAndNegativePositions() {
        for (double coordinate : new double[] { 24.5, -9.5, -48.5 }) {
            var f = footprint(coordinate, coordinate);
            var box = f.blockBounds();
            var min = BlockPos.containing(box.minX, box.minY, box.minZ);
            var max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
            for (var cell : BlockPos.betweenClosed(min, max)) assertTrue(f.containsBlock(this.level, cell), cell.toString());
            assertFalse(f.containsBlock(this.level, min.below()));
            assertFalse(f.containsBlock(this.level, min.west()));
            assertFalse(f.containsBlock(this.level, max.east()));
        }
    }

    @Test
    void geometryNeverGrantsAnotherLevelOrAnUnboundedRequest() {
        var f = footprint(8, 8);
        var other = mock(ServerLevel.class);
        assertFalse(f.containsRegion(other, 0, 0));
        assertFalse(f.containsQuery(other, f.queryBounds()));
        assertThrows(IllegalArgumentException.class, () -> RegionPlayerFootprint.of(this.level, 0, f.queryBounds(), f.blockBounds()));
        assertThrows(IllegalArgumentException.class, () -> RegionPlayerFootprint.of(this.level, 3, new AABB(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1), f.blockBounds()));
        assertThrows(IllegalArgumentException.class, () -> RegionPlayerFootprint.of(this.level, 3, new AABB(0, 0, 0, 10000, 10000, 10000), f.blockBounds()));
    }

    @Test
    void callerSuppliedRidingUnionIsNeverShrunk() {
        AABB player = new AABB(47, 64, 8, 48, 66, 9), vehicle = new AABB(45, 63, 7, 50, 65, 10);
        AABB query = player.minmax(vehicle).inflate(1, 0, 1);
        var f = RegionPlayerFootprint.of(this.level, 3, query, query.inflate(1));
        assertEquals(query, f.queryBounds());
        assertTrue(f.containsQuery(this.level, query));
    }

    @Test
    void sharedTargetMergesOriginalObjectsAndRetainsInputOrder() {
        Entity a = mock(Entity.class), b = mock(Entity.class), target = mock(Entity.class);
        var entries = List.of(entry(a, 8, 8, List.of(target)), entry(b, 104, 8, List.of(target)));
        var prefix = RegionPlayerConflictPlan.prefix(entries);
        assertEquals(2, prefix.nextIndex());
        assertEquals(1, prefix.groups().size());
        assertEquals(entries, prefix.groups().getFirst().entries());
        assertTrue(prefix.groups().getFirst().reasons().contains(RegionPlayerConflictPlan.Conflict.SHARED_TARGET));
    }

    @Test
    void transitiveHaloConflictIsOneOrderedGroup() {
        Entity a = mock(Entity.class), b = mock(Entity.class), c = mock(Entity.class);
        var entries = List.of(entry(a, 45.5, 8, List.of()), entry(b, 94, 8, List.of()), entry(c, 142, 8, List.of()));
        var prefix = RegionPlayerConflictPlan.prefix(entries);
        assertEquals(1, prefix.groups().size());
        assertEquals(entries, prefix.groups().getFirst().entries());
    }

    @Test
    void sameRegionNeverSplitsButIndependentRegionsRemainSeparate() {
        var prefix = RegionPlayerConflictPlan.prefix(List.of(entry(mock(Entity.class), 8, 8, List.of()), entry(mock(Entity.class), 20, 8, List.of()), entry(mock(Entity.class), 152, 8, List.of())));
        assertEquals(2, prefix.groups().size());
        assertEquals(2, prefix.groups().getFirst().entries().size());
        assertTrue(prefix.groups().getFirst().reasons().contains(RegionPlayerConflictPlan.Conflict.SAME_REGION));
    }

    @Test
    void serialFencePreventsSuffixCaptureAndDimensionOverlap() {
        Entity a = mock(Entity.class), b = mock(Entity.class);
        var first = entry(a, 8, 8, List.of());
        var fence = new RegionPlayerConflictPlan.Entry(b, first.home(), null, List.of());
        var suffix = entry(mock(Entity.class), 152, 8, List.of());
        var prefix = RegionPlayerConflictPlan.prefix(List.of(first, fence, suffix));
        assertEquals(1, prefix.nextIndex());
        assertEquals(List.of(first), prefix.groups().getFirst().entries());
        var other = mock(ServerLevel.class);
        var box = new AABB(0, 0, 0, 1, 1, 1);
        var foreign = new RegionPlayerConflictPlan.Entry(b, new RegionCoordinate(other, 0, 0), RegionPlayerFootprint.of(other, 3, box, box), List.of());
        assertEquals(1, RegionPlayerConflictPlan.prefix(List.of(first, foreign)).nextIndex());
    }

    @Test
    void multiRegionSectionVisitPreservesGlobalSignedPackedOrder() {
        var partitions = new net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions<Entity>(net.neoforged.neoforge.server.threading.region.RegionLayout.FIXED_3X3);
        var boxes = new AABB(-4, 60, -4, 52, 70, 52);
        var ids = List.of(net.minecraft.core.SectionPos.asLong(0, 4, 3), net.minecraft.core.SectionPos.asLong(0, 4, 0),
                net.minecraft.core.SectionPos.asLong(-1, 4, -1), net.minecraft.core.SectionPos.asLong(3, 4, 0));
        var sections = new java.util.IdentityHashMap<net.minecraft.world.level.entity.EntitySection<Entity>, Long>();
        for (long id : ids) {
            var section = partitions.getOrCreate(id, key -> new net.minecraft.world.level.entity.EntitySection<>(Entity.class, net.minecraft.world.level.entity.Visibility.TICKING));
            section.add(mock(Entity.class));
            sections.put(section, id);
        }
        var coordinates = List.of(new RegionCoordinate(this.level, 1, 0), new RegionCoordinate(this.level, 0, 1), new RegionCoordinate(this.level, 0, 0), new RegionCoordinate(this.level, -1, -1));
        var visited = new java.util.ArrayList<Long>();
        partitions.forEachAccessibleNonEmptySectionInRegions(coordinates, boxes, section -> {
            visited.add(sections.get(section));
            return net.minecraft.util.AbortableIterationConsumer.Continuation.CONTINUE;
        });
        assertEquals(ids.stream().sorted().toList(), visited);
        visited.clear();
        partitions.forEachAccessibleNonEmptySectionInRegions(coordinates, boxes, section -> {
            visited.add(sections.get(section));
            return net.minecraft.util.AbortableIterationConsumer.Continuation.ABORT;
        });
        assertEquals(ids.stream().sorted().limit(1).toList(), visited);
    }

    @Test
    void oversizedSharedComponentBecomesFenceBeforeItsFirstBody() {
        Entity target = mock(Entity.class);
        var entries = new java.util.ArrayList<RegionPlayerConflictPlan.Entry>();
        entries.add(entry(mock(Entity.class), 8, 8, List.of()));
        for (int i = 1; i <= 17; i++) entries.add(entry(mock(Entity.class), i * 96 + 8, 8, List.of(target)));
        var prefix = RegionPlayerConflictPlan.prefix(entries);
        assertEquals(1, prefix.nextIndex());
        assertTrue(prefix.oversizedConflict());
        assertEquals(1, prefix.groups().size());
    }

    @Test
    void explicitSharedResourceMergesDistantPlayersAndEqualityDoesNotAlias() {
        var a = entry(mock(Entity.class), 8, 8, List.of());
        var b = entry(mock(Entity.class), 104, 8, List.of());
        Object shared = new Object();
        var first = new RegionPlayerConflictPlan.Entry(a.player(), a.home(), a.footprint(), a.targets(), List.of(shared));
        var second = new RegionPlayerConflictPlan.Entry(b.player(), b.home(), b.footprint(), b.targets(), List.of(shared));
        var prefix = RegionPlayerConflictPlan.prefix(List.of(first, second));
        assertEquals(1, prefix.groups().size());
        assertTrue(prefix.groups().getFirst().reasons().contains(RegionPlayerConflictPlan.Conflict.SHARED_RESOURCE));
        var equalFirst = new RegionPlayerConflictPlan.Entry(a.player(), a.home(), a.footprint(), a.targets(), List.of(new String("equal")));
        var equalSecond = new RegionPlayerConflictPlan.Entry(b.player(), b.home(), b.footprint(), b.targets(), List.of(new String("equal")));
        assertEquals(2, RegionPlayerConflictPlan.prefix(List.of(equalFirst, equalSecond)).groups().size());
    }
}
