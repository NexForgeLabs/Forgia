/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import net.neoforged.neoforge.server.threading.region.DynamicRegionTopology;
import net.neoforged.neoforge.server.threading.region.RegionBlockEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionChunkTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionEntityTickPlan;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionScopedEntityQuery;
import net.neoforged.neoforge.server.threading.region.RegionThreadingConfig;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionTickBarrier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class RegionUnifiedTickCoordinatorTest {
    private static final RegionTickBarrier PARKING_BARRIER = condition -> {
        while (!condition.getAsBoolean()) {
            LockSupport.parkNanos(100_000L);
        }
    };

    private static final BlockPos REGION_A_POS = new BlockPos(24, 64, 24);
    private final List<Entity> tickList = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextEntityId = new AtomicInteger(100);

    @AfterEach
    void cleanupProperties() {
        System.clearProperty("neoforge.regionthreading.unifiedRegionTickLoop");
        System.clearProperty("neoforge.regionthreading.singleRegionContraptionWorkers");
        System.clearProperty("neoforge.regionthreading.unifiedRegionEntityContainmentMarginBlocks");
        System.clearProperty("neoforge.regionthreading.sandboxAllBlockEntities");
        System.clearProperty("neoforge.regionthreading.asyncEntityTicks");
        RegionThreadingManager.clear();
        this.tickList.clear();
    }

    @Test
    void phase4ConfigDefaultsAreFalseAndMarginIsEight() {
        assertFalse(ForgiaConfig.SERVER.enableUnifiedRegionTickLoop.getDefault());
        assertFalse(ForgiaConfig.SERVER.enableSingleRegionContraptionWorkers.getDefault());
        assertEquals(8, ForgiaConfig.SERVER.unifiedRegionEntityContainmentMarginBlocks.getDefault());
        assertFalse(RegionThreadingConfig.unifiedRegionTickLoopEnabled());
        assertFalse(RegionThreadingConfig.singleRegionContraptionWorkersEnabled());
        assertEquals(8, RegionThreadingConfig.unifiedRegionEntityContainmentMarginBlocks());
    }

    @Test
    void unifiedTickLoopExecutesChunksThenEntitiesThenBlockEntitiesOnSameWorkerThread() throws Exception {
        System.setProperty("neoforge.regionthreading.unifiedRegionTickLoop", "true");
        System.setProperty("neoforge.regionthreading.sandboxAllBlockEntities", "true");
        System.setProperty("neoforge.regionthreading.asyncEntityTicks", "true");
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:unified_be");

        try (var f = new CanonicalBlockEntityTestSupport()) {
            // Enable chunks, entities, and block entities with REGION_OWNED entity storage
            f.engine = new RegionThreadingEngine(
                    2, false, false, true, true, false, true, false,
                    true, false, RegionLayout.DYNAMIC_CLUSTER_8X8, RegionEntityStorageMode.REGION_OWNED);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            RegionThreadingManager.install(f.engine);

            this.configureLevelForUnifiedTest(f);

            // Pre-populate 3x3 chunk neighborhood around REGION_A_POS (chunk 1, 1)
            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    f.chunk(new BlockPos((1 + dx) * 16 + 8, 64, (1 + dz) * 16 + 8));
                }
            }
            f.engine.addRegionSection(f.level, 0, 0);

            final List<String> stageOrder = new CopyOnWriteArrayList<>();
            final List<Thread> stageThreads = new CopyOnWriteArrayList<>();
            final LevelChunk targetChunk = f.chunk(REGION_A_POS);

            doAnswer(invocation -> {
                stageOrder.add("CHUNK");
                stageThreads.add(Thread.currentThread());
                return null;
            }).when(f.level).tickChunk(any(LevelChunk.class), anyInt());

            final Cow cow = this.mockEntity(Cow.class, f, REGION_A_POS, new AABB(23, 64, 23, 25, 66, 25));
            doAnswer(invocation -> {
                stageOrder.add("ENTITY");
                stageThreads.add(Thread.currentThread());
                return null;
            }).when(cow).tick();
            this.tickList.add(cow);

            f.script("UNIFIED_BE", REGION_A_POS, "test:unified_be", () -> {
                stageOrder.add("BLOCK_ENTITY");
                stageThreads.add(Thread.currentThread());
            });

            // 1. Stage chunks
            final RegionChunkTickPlan chunkPlan = f.engine.beginChunkTickPlan(f.level, 3, PARKING_BARRIER);
            assertNotNull(chunkPlan);
            chunkPlan.accept(targetChunk);
            chunkPlan.executeAndAwait();
            assertEquals(List.of(), stageOrder, "Chunks must be staged for the unified loop");

            // 2. Stage entities
            final RegionEntityTickPlan entityPlan = f.engine.beginEntityTickPlan(f.level, e -> true, PARKING_BARRIER);
            assertNotNull(entityPlan);
            entityPlan.executeAndAwait();
            assertTrue(f.engine.unifiedTickCoordinator().hasStagedWork(f.level),
                    "Entity and chunk work must be staged in RegionUnifiedTickCoordinator before BE dispatch");
            assertEquals(List.of(), stageOrder, "Worker wave must not submit until BE stage joins");

            // 3. Dispatch unified wave from tickBlockEntities
            final RegionBlockEntityTickPlan bePlan = f.engine.beginBlockEntityTickPlan(f.level, PARKING_BARRIER);
            assertNotNull(bePlan);
            bePlan.collect();
            bePlan.executeAndAwait();

            f.engine.independentTickScheduler().awaitLevelQuiescent(f.level, false);

            assertEquals(List.of("CHUNK", "ENTITY", "BLOCK_ENTITY"), stageOrder,
                    "Unified region tick loop must execute Chunks -> Entities -> BlockEntities in exact vanilla order");
            assertEquals(3, stageThreads.size());
            final Thread workerThread = stageThreads.get(0);
            assertTrue(workerThread.getName().startsWith("NeoForge Region Worker"),
                    "Unified loop must run on a native region worker thread: " + workerThread.getName());
            assertSame(workerThread, stageThreads.get(1), "Entity stage must run on the exact same worker thread as Chunk stage");
            assertSame(workerThread, stageThreads.get(2), "BlockEntity stage must run on the exact same worker thread as Entity stage");
            assertEquals(1L, f.engine.unifiedTickCoordinator().snapshot().unifiedWavesDispatched());
            assertEquals(1L, f.engine.unifiedTickCoordinator().snapshot().unifiedRegionsSubmitted());
        }
    }

    @Test
    void phase4BSingleRegionContainmentAdmitsInteriorMovingStructureAndRoutesBorderStructureToServerThread() throws Exception {
        System.setProperty("neoforge.regionthreading.unifiedRegionTickLoop", "true");
        System.setProperty("neoforge.regionthreading.singleRegionContraptionWorkers", "true");
        System.setProperty("neoforge.regionthreading.unifiedRegionEntityContainmentMarginBlocks", "8");
        System.setProperty("neoforge.regionthreading.sandboxAllBlockEntities", "true");
        System.setProperty("neoforge.regionthreading.asyncEntityTicks", "true");

        // Register cow entity type id as a region-contained moving structure for testing
        RegionAdapterApi.registerRegionContainedEntityType("minecraft:cow");
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:gantry_be");

        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.engine = new RegionThreadingEngine(
                    2, false, false, true, true, false, false, false,
                    true, false, RegionLayout.DYNAMIC_CLUSTER_8X8, RegionEntityStorageMode.REGION_OWNED);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            RegionThreadingManager.install(f.engine);

            this.configureLevelForUnifiedTest(f);

            for (int cx = 0; cx <= 7; ++cx) {
                for (int cz = 0; cz <= 7; ++cz) {
                    f.chunk(new BlockPos(cx * 16 + 8, 64, cz * 16 + 8));
                }
            }
            f.engine.addRegionSection(f.level, 0, 0);

            // 1. Interior contraption well inside region (0, 0) [0..128 blocks], at (48, 64, 48) with 8-block margin
            final BlockPos interiorPos = new BlockPos(48, 64, 48);
            final Cow interiorStructure = this.mockEntity(
                    Cow.class, f, interiorPos, new AABB(46, 64, 46, 50, 68, 50));
            doReturn(EntityType.COW).when(interiorStructure).getType();

            // 2. Border-straddling contraption at (125, 64, 48) -> 125 + 8 = 133 crosses region border 128!
            final BlockPos borderPos = new BlockPos(125, 64, 48);
            final Cow borderStructure = this.mockEntity(
                    Cow.class, f, borderPos, new AABB(123, 64, 46, 127, 68, 50));
            doReturn(EntityType.COW).when(borderStructure).getType();

            final List<String> order = new CopyOnWriteArrayList<>();
            final AtomicReference<Thread> interiorThread = new AtomicReference<>();
            final AtomicReference<Thread> borderThread = new AtomicReference<>();
            final AtomicReference<Thread> beThread = new AtomicReference<>();

            doAnswer(inv -> {
                order.add("INTERIOR_CONTRAPTION");
                interiorThread.set(Thread.currentThread());
                return null;
            }).when(interiorStructure).tick();

            doAnswer(inv -> {
                order.add("BORDER_CONTRAPTION");
                borderThread.set(Thread.currentThread());
                return null;
            }).when(borderStructure).tick();

            this.tickList.add(interiorStructure);
            this.tickList.add(borderStructure);

            f.script("GANTRY_BE", interiorPos, "test:gantry_be", () -> {
                order.add("GANTRY_BE");
                beThread.set(Thread.currentThread());
            });

            final RegionEntityTickPlan entityPlan = f.engine.beginEntityTickPlan(f.level, e -> true, PARKING_BARRIER);
            assertNotNull(entityPlan);
            entityPlan.executeAndAwait();

            // Border structure must have ticked immediately on the server thread during entity phase!
            assertSame(f.serverThread, borderThread.get(),
                    "Border-straddling structure must execute on the server thread with 0-tick handoff");
            assertTrue(RegionAdapterApi.isNearServerThreadEntity(f.level, borderPos),
                    "Border structure must publish its proximity lockstep box for nearby BEs");
            assertFalse(RegionAdapterApi.isNearServerThreadEntity(f.level, interiorPos),
                    "Interior structure on worker must not force interior BEs onto server thread");

            final RegionBlockEntityTickPlan bePlan = f.engine.beginBlockEntityTickPlan(f.level, PARKING_BARRIER);
            assertNotNull(bePlan);
            bePlan.collect();
            bePlan.executeAndAwait();

            f.engine.independentTickScheduler().awaitLevelQuiescent(f.level, false);

            assertNotNull(interiorThread.get(), "Interior structure must tick on region worker");
            assertTrue(interiorThread.get().getName().startsWith("NeoForge Region Worker"));
            assertSame(interiorThread.get(), beThread.get(),
                    "Interior moving structure and local BlockEntity must run on the exact same worker thread");
            assertEquals(List.of("BORDER_CONTRAPTION", "INTERIOR_CONTRAPTION", "GANTRY_BE"), order);
        }
    }

    @Test
    void phase4BStage2DeferredEntityAdditionIsImmediatelyVisibleToStage3BlockEntityQuery() throws Exception {
        System.setProperty("neoforge.regionthreading.unifiedRegionTickLoop", "true");
        System.setProperty("neoforge.regionthreading.sandboxAllBlockEntities", "true");
        System.setProperty("neoforge.regionthreading.asyncEntityTicks", "true");
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:query_be");

        try (var f = new CanonicalBlockEntityTestSupport()) {
            f.engine = new RegionThreadingEngine(
                    2, false, false, true, true, false, false, false,
                    true, false, RegionLayout.DYNAMIC_CLUSTER_8X8, RegionEntityStorageMode.REGION_OWNED);
            f.engine.setIndependentRegionTickEnabledForTesting(true);
            RegionThreadingManager.install(f.engine);

            this.configureLevelForUnifiedTest(f);

            for (int dx = -1; dx <= 1; ++dx) {
                for (int dz = -1; dz <= 1; ++dz) {
                    f.chunk(new BlockPos((1 + dx) * 16 + 8, 64, (1 + dz) * 16 + 8));
                }
            }
            f.engine.addRegionSection(f.level, 0, 0);

            final AABB queryBox = new AABB(20, 60, 20, 30, 70, 30);
            final Cow spawnerEntity = this.mockEntity(Cow.class, f, REGION_A_POS, new AABB(23, 64, 23, 25, 66, 25));
            final Cow freshlyQueuedChild = this.mockEntity(Cow.class, f, REGION_A_POS, new AABB(24, 64, 24, 26, 66, 26));
            when(freshlyQueuedChild.isAddedToLevel()).thenReturn(false);
            when(freshlyQueuedChild.getSelfAndPassengers()).thenReturn(java.util.stream.Stream.of(freshlyQueuedChild));

            doAnswer(inv -> {
                // Stage 2: defer addition of freshlyQueuedChild inside the region ownership lease
                assertTrue(net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope.deferEntityAddition(
                        f.level, freshlyQueuedChild));
                return null;
            }).when(spawnerEntity).tick();
            this.tickList.add(spawnerEntity);

            final List<Entity> queriedInStage3 = new CopyOnWriteArrayList<>();
            f.script("QUERY_BE", REGION_A_POS, "test:query_be", () -> {
                final List<Entity> found = RegionScopedEntityQuery.tryQuery(f.level, null, queryBox, e -> true);
                if (found != null) {
                    queriedInStage3.addAll(found);
                }
            });

            final RegionEntityTickPlan entityPlan = f.engine.beginEntityTickPlan(f.level, e -> true, PARKING_BARRIER);
            assertNotNull(entityPlan);
            entityPlan.executeAndAwait();

            final RegionBlockEntityTickPlan bePlan = f.engine.beginBlockEntityTickPlan(f.level, PARKING_BARRIER);
            assertNotNull(bePlan);
            bePlan.collect();
            bePlan.executeAndAwait();

            f.engine.independentTickScheduler().awaitLevelQuiescent(f.level, false);

            assertTrue(queriedInStage3.contains(freshlyQueuedChild),
                    "Stage 3 BlockEntity getEntities(AABB) query must immediately see entities deferred in Stage 2 of the same unified region tick");
        }
    }

    @Test
    void phase4CDynamicRegionTopologyPinDefersSplitUntilPinExpires() {
        final DynamicRegionTopology topology = new DynamicRegionTopology(3, 1, 10L);
        topology.addChunk(0, 0);
        topology.addChunk(8, 0);
        topology.reconcile(0L);
        assertEquals(1, topology.regionCount());

        // Unload section (1, 0) at tick 5 and pin sections (0, 0) and (1, 0) together until tick 50
        topology.removeChunk(8, 0);
        topology.pinAdjacentSectionsTogether(0, 0, 1, 0, 50L);

        // At tick 20 (past splitDelayTicks=10, but before pin expiry=50), section (1, 0) must stay pinned
        topology.reconcile(20L);
        assertTrue(topology.isSectionPinned(DynamicRegionTopology.sectionKey(1, 0), 20L));
        assertEquals(2, topology.activeSectionCount(), "Pinned section must not detach while cross-border pin is active");

        // At tick 55 (past pin expiry=50), reconcile detaches the empty section
        topology.reconcile(55L);
        assertFalse(topology.isSectionPinned(DynamicRegionTopology.sectionKey(1, 0), 55L));
        assertEquals(1, topology.activeSectionCount(), "Expired pin must allow normal section detachment");
    }

    private <T extends Entity> T mockEntity(Class<T> type, CanonicalBlockEntityTestSupport f, BlockPos pos, AABB box) {
        final T entity = mock(type);
        when(entity.level()).thenReturn(f.level);
        when(entity.blockPosition()).thenReturn(pos);
        when(entity.chunkPosition()).thenReturn(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
        when(entity.getBoundingBox()).thenReturn(box);
        when(entity.getDeltaMovement()).thenReturn(Vec3.ZERO);
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(this.nextEntityId.getAndIncrement());
        when(entity.isRemoved()).thenReturn(false);
        when(entity.getPassengers()).thenReturn(List.of());
        doAnswer(inv -> {
            entity.tick();
            return null;
        }).when(f.level).tickNonPassenger(entity);
        doAnswer(inv -> {
            final Consumer<Entity> c = inv.getArgument(0);
            final Entity e = inv.getArgument(1);
            c.accept(e);
            return null;
        }).when(f.level).guardEntityTick(any(), any());
        return entity;
    }

    private void configureLevelForUnifiedTest(CanonicalBlockEntityTestSupport f) {
        when(f.server.isSpawningAnimals()).thenReturn(true);
        when(f.server.areNpcsEnabled()).thenReturn(true);
        when(f.level.entitySectionRegionChunkSize()).thenReturn(RegionLayout.DYNAMIC_CLUSTER_8X8.regionChunkSize());
        final var chunkSource = f.level.getChunkSource();
        doAnswer(call -> f.chunks.containsKey(ChunkPos.asLong(call.getArgument(0), call.getArgument(1))))
                .when(chunkSource).hasChunk(anyInt(), anyInt());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            final Consumer<Entity> action = invocation.getArgument(0);
            for (final Entity entity : this.tickList) {
                action.accept(entity);
            }
            return Boolean.TRUE;
        }).when(f.level).forEachEntityInTickListForRegionIndex(any());
        doAnswer(invocation -> true).when(f.level).isInEntityTickingRange(anyLong());
    }
}
