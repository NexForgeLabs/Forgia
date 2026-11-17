/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.FixedEntitySectionPartitions;
import net.neoforged.neoforge.server.threading.region.RegionAwareEntityGetter;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import net.neoforged.neoforge.server.threading.region.RegionEntityOwnershipScope;
import net.neoforged.neoforge.server.threading.region.RegionEntityQueryCounters;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionPlayerSpatialLease;
import net.neoforged.neoforge.server.threading.region.RegionPlayerSpatialPlan;
import net.neoforged.neoforge.server.threading.region.RegionPlayerStatePlan;
import net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope;
import net.neoforged.neoforge.server.threading.region.RegionPlayerTickCoordinator;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionScopedEntityQuery;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual original engine/pool/batch/scope/query/partitions; no test permissions in production. */
@Timeout(30)
class RegionPlayerSpatialLeaseTest {
    private static final String PKG = "net.neoforged.neoforge.server.threading.region.";
    private RegionThreadingEngine engine;
    private MinecraftServer server;
    private ServerLevel level;
    private ServerPlayer player;
    private final List<Entity> entities = new ArrayList<>();
    private final Map<Long, LevelChunk> chunks = new HashMap<>();
    private FixedEntitySectionPartitions<Entity> partitions;
    private Thread publishing;
    private int nextId;
    private final List<String> effects = new CopyOnWriteArrayList<>();
    private RegionThreadingEngine.PlayerTickInvocation parent;
    private Object batch;

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                var m = owner.getDeclaredMethod(name, parameters);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Object invoke(Object owner, String name, Class<?>[] parameters, Object... args) throws Exception {
        try {
            return method(owner instanceof Class<?> c ? c : owner.getClass(), name, parameters).invoke(owner instanceof Class<?> ? null : owner, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Error e) throw e;
            if (error.getCause() instanceof RuntimeException e) throw e;
            throw error;
        }
    }

    private static void field(Class<?> type, Object object, String name, Object value) throws Exception {
        var f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(object, value);
    }

    private static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    @BeforeEach
    void setup() throws Exception {
        initialize(false);
    }

    private void initialize(boolean persistent) throws Exception {
        RegionThreadingManager.clear();
        this.entities.clear();
        this.chunks.clear();
        this.effects.clear();
        this.parent = null;
        this.publishing = Thread.currentThread();
        this.server = mock(MinecraftServer.class);
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == this.publishing);
        when(this.server.getTickCount()).thenReturn(100);
        this.engine = new RegionThreadingEngine(2, false, false, true, false, false, false, false, persistent, false, RegionLayout.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(this.engine);
        this.engine.configurePlayerTickPlanningForTesting(true);
        this.level = mock(ServerLevel.class);
        when(this.level.getServer()).thenReturn(this.server);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.entitySectionRegionChunkSize()).thenReturn(3);
        when(this.level.entityIdentityLedger()).thenReturn(new EntityIdentityLedger());
        when(this.level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(this.level.getPartEntities()).thenReturn(List.of());
        when(this.level.getMinBuildHeight()).thenReturn(-64);
        when(this.level.getMaxBuildHeight()).thenReturn(320);
        when(this.level.getBlockState(any())).thenAnswer(call -> ((BlockPos) call.getArgument(0)).getY() < 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        when(this.level.getFluidState(any())).thenReturn(Fluids.EMPTY.defaultFluidState());
        ServerChunkCache cache = mock(ServerChunkCache.class);
        when(this.level.getChunkSource()).thenReturn(cache);
        when(cache.getChunkNow(anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), z = call.getArgument(1);
            return this.chunks.computeIfAbsent(ChunkPos.asLong(x, z), key -> {
                var chunk = mock(LevelChunk.class);
                when(chunk.getPos()).thenReturn(new ChunkPos(x, z));
                when(chunk.getLevel()).thenReturn(this.level);
                return chunk;
            });
        });
        this.partitions = new FixedEntitySectionPartitions<>(RegionLayout.FIXED_3X3);
        doAnswer(call -> {
            Consumer<Entity> visitor = call.getArgument(0);
            this.entities.forEach(visitor);
            return true;
        }).when(this.level).forEachEntityInTickListForRegionIndex(any());
        when(this.level.getEntities(any(Entity.class), any(AABB.class), any())).thenAnswer(call -> {
            Entity excluded = call.getArgument(0);
            AABB box = call.getArgument(1);
            return this.entities.stream().filter(e -> e != excluded && e.getBoundingBox().intersects(box)).toList();
        });
        doAnswer(call -> {
            this.partitions.forEachAccessibleNonEmptySectionInRegions(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        })
                .when(this.level).forEachAccessibleNonEmptySectionInRegions(anyList(), any(), any());
        this.player = mock(ServerPlayer.class);
        field(ServerPlayer.class, this.player, "server", this.server);
        when(this.player.serverLevel()).thenReturn(this.level);
        when(this.player.getHealth()).thenReturn(20f);
        when(this.player.getMaxHealth()).thenReturn(20f);
        when(this.player.isAlive()).thenReturn(true);
        when(this.player.getPose()).thenReturn(Pose.STANDING);
        when(this.player.getCamera()).thenReturn(this.player);
        when(this.player.getEyeY()).thenReturn(65.62);
        when(this.player.getAbilities()).thenReturn(new net.minecraft.world.entity.player.Abilities());
        when(this.player.getActiveEffects()).thenReturn(List.of());
        when(this.player.getShoulderEntityLeft()).thenReturn(new CompoundTag());
        when(this.player.getShoulderEntityRight()).thenReturn(new CompoundTag());
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContainerSize()).thenReturn(41);
        when(inventory.getItem(anyInt())).thenReturn(ItemStack.EMPTY);
        when(this.player.getInventory()).thenReturn(inventory);
        ServerPlayerGameMode gameMode = mock(ServerPlayerGameMode.class);
        field(ServerPlayer.class, this.player, "gameMode", gameMode);
        when(this.player.forgiaStationarySpatialInputs()).thenReturn(true);
        Connection connection = mock(Connection.class);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        listener.player = this.player;
        this.player.connection = listener;
        when(listener.getConnection()).thenReturn(connection);
        when(listener.isAcceptingMessages()).thenReturn(true);
        when(connection.getPacketListener()).thenReturn(listener);
        when(connection.isConnected()).thenReturn(true);
        this.add(this.player, 47.5, 8.5, new AABB(47.2, 64, 8.2, 47.8, 65.8, 8.8));
        PlayerList players = mock(PlayerList.class);
        when(players.getPlayer(this.player.getUUID())).thenReturn(this.player);
        when(this.server.getPlayerList()).thenReturn(players);
        this.engine.addRegionSection(this.level, 0, 0);
        this.engine.addRegionSection(this.level, 3, 0);
        this.engine.beginEntityTickPlan(this.level, e -> true, c -> assertTrue(c.getAsBoolean()));
    }

    @AfterEach
    void cleanup() {
        if (this.parent != null) this.parent.close();
        RegionThreadingManager.clear();
    }

    private void add(Entity entity, double x, double z, AABB box) {
        when(entity.getUUID()).thenReturn(UUID.randomUUID());
        when(entity.getId()).thenReturn(++this.nextId);
        when(entity.level()).thenReturn(this.level);
        when(entity.getX()).thenReturn(x);
        when(entity.getY()).thenReturn(64d);
        when(entity.getZ()).thenReturn(z);
        when(entity.getBoundingBox()).thenReturn(box);
        when(entity.blockPosition()).thenReturn(BlockPos.containing(x, 64, z));
        when(entity.getPassengers()).thenReturn(List.of());
        when(entity.getLevelCallback()).thenReturn(EntityInLevelCallback.NULL);
        this.entities.add(entity);
        var ledger = this.level.entityIdentityLedger();
        ledger.admit(entity);
        ledger.publishAdmitted(entity);
        ledger.publishTracked(entity);
        this.engine.entityTickStarted(this.level, entity);
        if (entity instanceof ServerPlayer player) {
            try {
                invoke(this.engine, "publishPlayerRegion", new Class<?>[] { ServerLevel.class, ServerPlayer.class, RegionCoordinate.class }, this.level, player,
                        RegionLayout.FIXED_3X3.regionAt(this.level, entity.blockPosition()));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
        long section = SectionPos.asLong(SectionPos.blockToSectionCoord((int) Math.floor(x)), 4, SectionPos.blockToSectionCoord((int) Math.floor(z)));
        this.partitions.getOrCreate(section, key -> new EntitySection<>(Entity.class, Visibility.TICKING)).add(entity);
    }

    private RegionPlayerSpatialPlan capture() {
        var capture = this.engine.capturePlayerSpatialPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
        assertEquals(RegionPlayerSpatialPlan.Refusal.NONE, capture.refusal());
        assertNotNull(capture.plan());
        return capture.plan();
    }

    private RegionPlayerSpatialLease reserve(RegionPlayerSpatialPlan plan) throws Exception {
        if (this.parent == null) {
            this.parent = this.engine.beginPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(this.parent);
            var batches = (Set<?>) field(this.engine, "activeTickBatches");
            this.batch = batches.iterator().next();
        }
        Class<?> effectsType = Class.forName(PKG + "RegionOwnershipLeaseGroup");
        Object effects = Proxy.newProxyInstance(effectsType.getClassLoader(), new Class<?>[] { effectsType }, (proxy, m, args) -> {
            if (m.getName().startsWith("accepts")) return true;
            if (m.getName().startsWith("defer")) {
                this.effects.add(m.getName());
                return true;
            }
            if (m.getName().equals("owns")) return true;
            return null;
        });
        return (RegionPlayerSpatialLease) invoke(this.engine, "reservePlayerSpatial", new Class<?>[] { Class.forName(PKG + "RegionTickBatch"), effectsType, List.class }, this.batch, effects, List.of(plan));
    }

    private void worker(Runnable work) throws Exception {
        RegionWorkerExecutor pool = (RegionWorkerExecutor) field(this.engine, "workerPool");
        Runnable wrapped = (Runnable) invoke(this.batch, "groupWork", new Class<?>[] { Runnable.class }, work);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        assertTrue(pool.execute("player complete spatial permission component", () -> {
            try {
                wrapped.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() instanceof Error e) throw e;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void completeQueryReturnsForeignPlayerAndTargetsWithoutClampOrSnapshotNull(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        ServerPlayer neighbour = mock(ServerPlayer.class);
        this.add(neighbour, 48.1, 8.5, new AABB(47.8, 64, 8.2, 48.4, 65.8, 8.8));
        var plan = this.capture();
        try (var lease = this.reserve(plan)) {
            assertNotNull(lease);
            this.worker(() -> {
                try (var scope = lease.open()) {
                    var answer = RegionScopedEntityQuery.tryQuery(this.level, this.player, plan.footprint().queryBounds(), e -> true);
                    assertEquals(List.of(neighbour), answer);
                    assertSame(neighbour, answer.getFirst());
                    var getter = new RegionAwareEntityGetter(this.level, mock(net.minecraft.world.level.entity.LevelEntityGetter.class));
                    assertSame(neighbour, getter.get(neighbour.getId()));
                    assertSame(neighbour, getter.get(neighbour.getUUID()));
                    assertThrows(IllegalStateException.class, () -> getter.get(99999));
                    assertThrows(IllegalStateException.class, () -> RegionScopedEntityQuery.tryGetAll(this.level));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.playersOfLevel(this.level));
                }
            });
        }
        assertNull(RegionPlayerSpatialLease.current());
        assertEquals(0, this.engine.diagnostics().entityQueriesTruncatedByLease());
    }

    @Test
    void negativeCornerCompleteQueryAndMutationUseAllFourActualLeases() throws Exception {
        when(this.player.getX()).thenReturn(-48.5);
        when(this.player.getZ()).thenReturn(-48.5);
        when(this.player.blockPosition()).thenReturn(new BlockPos(-49, 64, -49));
        when(this.player.getBoundingBox()).thenReturn(new AABB(-48.8, 64, -48.8, -48.2, 65.8, -48.2));
        for (int x : new int[] { -6, -3 }) for (int z : new int[] { -6, -3 }) this.engine.addRegionSection(this.level, x, z);
        invoke(this.engine, "publishPlayerRegion", new Class<?>[] { ServerLevel.class, ServerPlayer.class, RegionCoordinate.class }, this.level, this.player, new RegionCoordinate(this.level, -2, -2));
        ServerPlayer target = mock(ServerPlayer.class);
        this.add(target, -47.9, -47.9, new AABB(-48.2, 64, -48.2, -47.6, 65.8, -47.6));
        this.partitions = new FixedEntitySectionPartitions<>(RegionLayout.FIXED_3X3);
        for (Entity entity : this.entities) {
            BlockPos p = entity.blockPosition();
            long id = SectionPos.asLong(SectionPos.blockToSectionCoord(p.getX()), 4, SectionPos.blockToSectionCoord(p.getZ()));
            this.partitions.getOrCreate(id, key -> new EntitySection<>(Entity.class, Visibility.TICKING)).add(entity);
        }
        var plan = capture();
        assertEquals(4, plan.footprint().regions().size());
        try (var lease = reserve(plan)) {
            this.worker(() -> {
                try (var scope = lease.open()) {
                    assertEquals(List.of(target), RegionScopedEntityQuery.tryQuery(this.level, this.player, plan.footprint().queryBounds(), entity -> true));
                    assertTrue(this.engine.isOwnedByCurrentRegion(target));
                    RegionThreadingHooks.assertPlayerTargetMutation(target, "negative corner target");
                    RegionThreadingHooks.assertPlayerBlockAccess(this.level, new BlockPos(-48, 64, -48));
                }
            });
        }
        assertNull(RegionPlayerSpatialLease.current());
    }

    @Test
    void queryExpansionRejectsBeforeAnyPredicateAndTypedMaxKeepsOriginalOrder() throws Exception {
        ServerPlayer target = mock(ServerPlayer.class);
        this.add(target, 48.1, 8.5, new AABB(47.8, 64, 8.2, 48.4, 65.8, 8.8));
        var plan = capture();
        try (var lease = reserve(plan)) {
            this.worker(() -> {
                try (var scope = lease.open()) {
                    java.util.concurrent.atomic.AtomicInteger predicates = new java.util.concurrent.atomic.AtomicInteger();
                    assertThrows(IllegalStateException.class, () -> RegionScopedEntityQuery.tryQuery(this.level, null, plan.footprint().queryBounds().inflate(.01), e -> {
                        predicates.incrementAndGet();
                        return true;
                    }));
                    assertEquals(0, predicates.get());
                    assertThrows(IllegalStateException.class, () -> this.partitions.forEachAccessibleNonEmptySectionInRegion(0, 0, plan.footprint().queryBounds(), section -> {
                        predicates.incrementAndGet();
                        return AbortableIterationConsumer.Continuation.CONTINUE;
                    }));
                    assertEquals(0, predicates.get());
                    List<Entity> answer = new ArrayList<>();
                    assertTrue(RegionScopedEntityQuery.tryQuery(this.level, EntityTypeTest.forClass(Entity.class), plan.footprint().queryBounds(), e -> true, answer, 1));
                    assertEquals(List.of(this.player), answer);
                }
            });
        }
    }

    @Test
    void allRegionsGrantBlocksChunksAndTargetsButKeepGlobalAndForeignGuards() throws Exception {
        ServerPlayer target = mock(ServerPlayer.class);
        this.add(target, 48.1, 8.5, new AABB(47.8, 64, 8.2, 48.4, 65.8, 8.8));
        var plan = capture();
        LevelChunk directChunk = mock(LevelChunk.class);
        field(LevelChunk.class, directChunk, "level", this.level);
        doCallRealMethod().when(directChunk).getBlockState(any(BlockPos.class));
        doCallRealMethod().when(directChunk).setBlockState(any(BlockPos.class), any(), anyBoolean());
        try (var lease = reserve(plan)) {
            this.worker(() -> {
                try (var scope = lease.open()) {
                    assertThrows(IllegalStateException.class, () -> directChunk.getBlockState(new BlockPos(48, 90, 8)));
                    assertThrows(IllegalStateException.class, () -> directChunk.setBlockState(new BlockPos(48, 90, 8), Blocks.DIRT.defaultBlockState(), false));
                    assertTrue(this.engine.isOwnedByCurrentRegion(this.level, new BlockPos(48, 64, 8)));
                    RegionThreadingHooks.assertPlayerBlockAccess(this.level, new BlockPos(48, 64, 8));
                    RegionThreadingHooks.assertPlayerTargetMutation(target, "pickup target");
                    doCallRealMethod().when(target).setDeltaMovement(any(Vec3.class));
                    when(target.getDeltaMovement()).thenCallRealMethod();
                    target.setDeltaMovement(new Vec3(1, 0, 0));
                    assertEquals(new Vec3(1, 0, 0), target.getDeltaMovement());
                    Entity foreign = mock(Entity.class);
                    doCallRealMethod().when(foreign).setDeltaMovement(any(Vec3.class));
                    when(foreign.getDeltaMovement()).thenCallRealMethod();
                    assertThrows(IllegalStateException.class, () -> foreign.setDeltaMovement(new Vec3(1, 0, 0)));
                    assertNull(foreign.getDeltaMovement());
                    assertNotNull(RegionThreadingHooks.playerSpatialChunk(this.level, 3, 0));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.playerSpatialChunk(this.level, 90, 90));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.assertPlayerBlockAccess(this.level, new BlockPos(48, 90, 8)));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.assertPlayerTargetMutation(mock(Entity.class), "foreign target"));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.assertServerEntityStorageAccess("global storage remains refused"));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.recordUnservedRangeQuery("must not truncate", RegionEntityQueryCounters.Operation.GET_IN_BOX));
                    assertThrows(IllegalStateException.class, () -> lease.close());
                }
            });
        }
        assertFalse(this.engine.isOwnedByCurrentRegion(this.level, new BlockPos(48, 64, 8)));
        assertNull(RegionThreadingHooks.playerSpatialChunk(this.level, 3, 0));
    }

    @Test
    void sectionAndRemovalUseOriginalEffectsAndRejectExpandedSectionBeforeRecording() throws Exception {
        var plan = capture();
        try (var lease = reserve(plan)) {
            this.worker(() -> {
                try (var scope = lease.open()) {
                    when(this.player.isAddedToLevel()).thenReturn(true);
                    assertTrue(RegionEntityOwnershipScope.grantsEntityMutation(this.level, this.player, "Entity.setPosRaw"));
                    assertTrue(RegionEntityOwnershipScope.deferEntitySectionUpdate(this.player, EntityInLevelCallback.NULL, 48, 64, 8));
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.deferEntitySectionUpdate(this.player, EntityInLevelCallback.NULL, 50, 64, 8));
                    assertThrows(IllegalStateException.class, () -> RegionEntityOwnershipScope.deferEntitySectionUpdate(this.player, EntityInLevelCallback.NULL, 900, 64, 8));
                    EntityInLevelCallback callback = mock(EntityInLevelCallback.class);
                    assertTrue(RegionEntityOwnershipScope.deferEntityRemoval(this.player, callback, Entity.RemovalReason.DISCARDED));
                    assertFalse(RegionEntityOwnershipScope.grantsEntityMutation(this.level, mock(Entity.class), "Entity.setPosRaw"));
                }
            });
        }
        assertEquals(List.of("deferSectionUpdate", "deferRemoval"), this.effects);
    }

    @Test
    void oneBusyLeaseRollsBackAllNewRegionsAndBatchReservation() throws Exception {
        var plan = capture();
        try (var initial = reserve(plan)) {
            assertNotNull(initial);
        }
        var coordinate = plan.footprint().regions().getLast();
        Object region = invoke(this.engine, "regionStateFor", new Class<?>[] { RegionCoordinate.class }, coordinate);
        assertTrue((boolean) invoke(region, "tryMarkRunning", new Class<?>[] {}));
        try {
            assertNull(reserve(plan));
            assertEquals(0, invoke(this.batch, "playerSpatialReservationCount", new Class<?>[] {}));
            Object first = invoke(this.engine, "regionStateFor", new Class<?>[] { RegionCoordinate.class }, plan.footprint().regions().getFirst());
            assertFalse((boolean) invoke(first, "isRunning", new Class<?>[] {}));
        } finally {
            invoke(region, "clearRunning", new Class<?>[] {});
        }
    }

    @Test
    void missingLoadedNeighbourRejectsWholeCaptureBeforeOriginalBody() {
        when(this.level.getChunkSource().getChunkNow(eq(3), anyInt())).thenReturn(null);
        assertEquals(RegionPlayerSpatialPlan.Refusal.UNLOADED_CHUNK, this.engine.capturePlayerSpatialPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
    }

    @Test
    void parentCannotFinishWhileSpatialReservationRetainsAuthority() throws Exception {
        var plan = capture();
        try (var lease = reserve(plan)) {
            assertEquals(1, invoke(this.batch, "playerSpatialReservationCount", new Class<?>[] {}));
            assertThrows(IllegalStateException.class, () -> invoke(this.batch, "finish", new Class<?>[] {}));
            assertThrows(IllegalStateException.class, this.parent::close);
            assertEquals(1, ((Set<?>) field(this.engine, "activeTickBatches")).size());
        }
        assertEquals(0, invoke(this.batch, "playerSpatialReservationCount", new Class<?>[] {}));
    }

    @Test
    void retainedPlanFromAnotherEngineDoesNotGrantAuthority() throws Exception {
        var plan = capture();
        RegionThreadingEngine foreign = new RegionThreadingEngine(1, false, false, true, false, false, false, RegionPolicy.FIXED_3X3, RegionEntityStorageMode.REGION_OWNED);
        try {
            assertThrows(IllegalStateException.class, () -> invoke(foreign, "reservePlayerSpatial", new Class<?>[] { Class.forName(PKG + "RegionTickBatch"), Class.forName(PKG + "RegionOwnershipLeaseGroup"), List.class }, null, null, List.of(plan)));
        } finally {
            foreign.shutdown();
        }
    }

    @Test
    void unknownBlockOrItemOrMovementKeepsCompleteSerialReason() {
        when(this.player.forgiaStationarySpatialInputs()).thenReturn(false);
        assertEquals(RegionPlayerSpatialPlan.Refusal.MOVEMENT_UNBOUNDED, this.engine.capturePlayerSpatialPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
        when(this.player.forgiaStationarySpatialInputs()).thenReturn(true);
        doReturn(Blocks.WATER.defaultBlockState()).when(this.level).getBlockState(any());
        assertEquals(RegionPlayerSpatialPlan.Refusal.CALLBACK_SPATIAL_BOUND_UNKNOWN, this.engine.capturePlayerSpatialPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
    }

    @Test
    void movingGroundOldOwnerIsRefusedRatherThanTrustedForPlayerPermission() {
        Entity target = mock(Entity.class);
        this.add(target, 49, 8.5, new AABB(48.8, 64, 8.3, 49.2, 64.2, 8.7));
        when(target.blockPosition()).thenReturn(new BlockPos(47, 64, 8));
        assertEquals(RegionPlayerSpatialPlan.Refusal.OWNER_PUBLICATION_PENDING, this.engine.capturePlayerSpatialPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
    }

    @Test
    void callbackAdmissionStillClosedAndNoScopeCanBeOpenedOnPublishingThread() throws Exception {
        var plan = capture();
        try (var lease = reserve(plan)) {
            assertThrows(IllegalStateException.class, lease::open);
        }
        assertEquals(0, this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.CONNECTION).worker());
    }

    @Test
    void mutationBetweenCaptureAndReserveRejectsBeforeAnyLease() throws Exception {
        var plan = capture();
        when(this.player.forgiaStationarySpatialInputs()).thenReturn(false);
        assertNull(reserve(plan));
    }

    @Test
    void newTargetOrMissingChunkAfterCaptureRejectsUnstartedReservation() throws Exception {
        var plan = capture();
        ServerPlayer extra = mock(ServerPlayer.class);
        this.add(extra, 48.1, 8.5, new AABB(47.8, 64, 8.2, 48.4, 65.8, 8.8));
        assertNull(reserve(plan));
    }

    @Test
    void chunkReplacementAfterCaptureRejectsUnstartedReservation() throws Exception {
        var plan = capture();
        when(this.level.getChunkSource().getChunkNow(eq(3), eq(0))).thenReturn(null);
        assertNull(reserve(plan));
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path stateDirectory;

    private AutoCloseable installedBaseEnvironment() {
        ModuleLayer observedLayer = net.neoforged.fml.loading.FMLLoader.getGameLayer();
        assertNotNull(observedLayer, "real loader layer is a prerequisite; no fake service-provider permission");
        var mods = org.mockito.Mockito.mockStatic(net.neoforged.fml.ModList.class);
        var loader = org.mockito.Mockito.mockStatic(net.neoforged.fml.loading.FMLLoader.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var environments = org.mockito.Mockito.mockStatic(org.spongepowered.asm.mixin.MixinEnvironment.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var mixins = org.mockito.Mockito.mockStatic(org.spongepowered.asm.mixin.Mixins.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var environment = mock(org.spongepowered.asm.mixin.MixinEnvironment.class);
        when(environment.getMixinConfigs()).thenReturn(List.of("neoforge.mixins.json", "mixinextras.init.mixins.json"));
        for (var phase : List.of(org.spongepowered.asm.mixin.MixinEnvironment.Phase.PREINIT, org.spongepowered.asm.mixin.MixinEnvironment.Phase.INIT, org.spongepowered.asm.mixin.MixinEnvironment.Phase.DEFAULT)) {
            environments.when(() -> org.spongepowered.asm.mixin.MixinEnvironment.getEnvironment(phase)).thenReturn(environment);
        }
        mixins.when(org.spongepowered.asm.mixin.Mixins::getConfigs).thenReturn(java.util.Set.of());
        var list = mock(net.neoforged.fml.ModList.class);
        var minecraft = mock(net.neoforged.neoforgespi.language.IModInfo.class);
        var neoforge = mock(net.neoforged.neoforgespi.language.IModInfo.class);
        when(minecraft.getModId()).thenReturn("minecraft");
        when(neoforge.getModId()).thenReturn("neoforge");
        when(list.getMods()).thenReturn(List.of(minecraft, neoforge));
        mods.when(net.neoforged.fml.ModList::get).thenReturn(list);
        loader.when(net.neoforged.fml.loading.FMLLoader::getGameLayer).thenReturn(observedLayer);
        return () -> {
            mixins.close();
            environments.close();
            loader.close();
            mods.close();
        };
    }

    private void productionLocalState() throws Exception {
        localState(net.minecraft.advancements.AdvancementRewards.Builder.experience(7).build());
        doReturn(net.minecraft.world.entity.EntityType.PLAYER).when(this.player).getType();
        when(this.server.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(this.player.getAirSupply()).thenReturn(300);
        when(this.player.getMaxAirSupply()).thenReturn(300);
        when(this.player.getCombatTracker()).thenReturn(new net.minecraft.world.damagesource.CombatTracker(this.player));
        when(this.player.getEntityData()).thenReturn(mock(net.minecraft.network.syncher.SynchedEntityData.class));
        when(this.player.getRandom()).thenReturn(net.minecraft.util.RandomSource.create());
        when(this.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY)).thenReturn(.08);
        when(this.player.getScale()).thenReturn(1f);
        field(net.minecraft.world.entity.LivingEntity.class, this.player, "appliedScale", 1f);
        when(this.player.getSleepingPos()).thenReturn(java.util.Optional.empty());
        when(this.player.activeLocationDependentEnchantments()).thenReturn(java.util.Map.of());
        when(this.level.getWorldBorder()).thenReturn(new net.minecraft.world.level.border.WorldBorder());
        field(Level.class, this.level, "random", new net.neoforged.neoforge.server.threading.RegionAwareRandomSource(net.minecraft.util.RandomSource.create()));
        field(net.minecraft.world.entity.player.Player.class, this.player, "lastItemInMainHand", ItemStack.EMPTY);
        this.player.mainSupportingBlockPos = java.util.Optional.empty();
        field(Entity.class, this.player, "nextStep", 1f);
        field(ServerGamePacketListenerImpl.class, this.player.connection, "chunkSender", new net.minecraft.server.network.PlayerChunkSender(false));
        field(net.minecraft.world.entity.LivingEntity.class, this.player, "lastHandItemStacks", net.minecraft.core.NonNullList.withSize(2, ItemStack.EMPTY));
        field(net.minecraft.world.entity.LivingEntity.class, this.player, "lastArmorItemStacks", net.minecraft.core.NonNullList.withSize(4, ItemStack.EMPTY));
        field(net.minecraft.world.entity.LivingEntity.class, this.player, "lastBodyItemStack", ItemStack.EMPTY);
        field(Connection.class, this.player.connection.getConnection(), "pendingActions", new java.util.concurrent.ConcurrentLinkedQueue<>());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RegionPlayerTickCoordinator.Phase.class)
    void completeProductionCaptureCombinesBothPhasesAndRejectsLateUnknownCallbacks(RegionPlayerTickCoordinator.Phase phase) throws Exception {
        productionLocalState();
        try (var installed = installedBaseEnvironment()) {
            var result = this.engine.capturePlayerAdmissionPlan(this.player, phase);
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.NONE, result.refusal(), result.detail());
            var plan = result.plan();
            assertNotNull(plan);
            assertSame(this.player, plan.state().player());
            assertEquals(phase, plan.phase());
            assertTrue(plan.revalidate());
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingBreatheEvent> unknown = event -> {
                throw new AssertionError("must run only in original serial body");
            };
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingBreatheEvent.class, unknown);
            try {
                assertFalse(plan.revalidate());
                var refused = this.engine.capturePlayerAdmissionPlan(this.player, phase);
                assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, refused.refusal());
                assertTrue(refused.detail().contains("LivingBreatheEvent"));
                assertNull(refused.plan());
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(unknown);
            }
            assertTrue(plan.revalidate());
            this.engine.serverThreadOnlyEntityType(net.minecraft.world.entity.EntityType.PLAYER, "operator_player_pin");
            assertFalse(plan.revalidate());
            var declared = this.engine.capturePlayerAdmissionPlan(this.player, phase);
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.SERVER_ONLY_DECLARATION, declared.refusal());
            assertEquals("operator_player_pin", declared.detail());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RegionPlayerTickCoordinator.Phase.class)
    void unknownAdvancementCallbackKeepsOriginalSynchronousAwardOnCompleteSerialPath(RegionPlayerTickCoordinator.Phase phase) throws Exception {
        productionLocalState();
        var storage = this.player.getAdvancements().forgiaCriterionListeners();
        var revision = storage.revision();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementProgressEvent> callback = event -> {
            assertSame(this.publishing, Thread.currentThread());
            assertSame(this.player, event.getEntity());
            calls.incrementAndGet();
        };
        try (var installed = installedBaseEnvironment()) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementProgressEvent.class, callback);
            try {
                var refused = this.engine.capturePlayerAdmissionPlan(this.player, phase);
                assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, refused.refusal());
                assertEquals(revision, storage.revision());
                assertEquals(0, this.player.totalExperience);
                assertEquals(0, calls.get());
                this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), phase,
                        () -> net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player));
                assertEquals(1, calls.get());
                assertEquals(7, this.player.totalExperience);
                assertTrue(storage.revision() > revision);
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(callback);
            }
        }
    }

    @Test
    void productionCaptureRejectsUnknownInstalledEnvironmentWithoutReplacingOriginalSerialCall() throws Exception {
        productionLocalState();
        var refused = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
        assertNull(refused.plan());
        assertTrue(refused.refusal() == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD
                || refused.refusal() == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, refused.toString());
        var original = new java.util.concurrent.atomic.AtomicInteger();
        this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.CONNECTION, original::incrementAndGet);
        assertEquals(1, original.get());
        this.engine.configurePlayerTickPlanningForTesting(false);
        assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.DEPENDENCIES,
                this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
    }

    @Test
    void pendingConnectionActionAndCachedEquipmentRejectBeforeAnyOriginalEffect() throws Exception {
        productionLocalState();
        try (var installed = installedBaseEnvironment()) {
            var plan = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(plan.plan(), plan.toString());
            @SuppressWarnings("unchecked")
            Queue<java.util.function.Consumer<Connection>> actions = (Queue<java.util.function.Consumer<Connection>>) field(this.player.connection.getConnection(), "pendingActions");
            var effects = new java.util.concurrent.atomic.AtomicInteger();
            actions.add(connection -> effects.incrementAndGet());
            assertFalse(plan.plan().revalidate());
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.NETWORK_PENDING_ACTIONS,
                    this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
            assertEquals(0, effects.get());
            actions.clear();
            field(net.minecraft.world.entity.LivingEntity.class, this.player, "lastBodyItemStack", new ItemStack(net.minecraft.world.item.Items.STONE));
            assertFalse(plan.plan().revalidate());
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.OPTIONAL_TICK_STATE,
                    this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "target", "gravity", "scale", "pendingScale", "food", "support", "border", "movingBorder", "suffocation", "step", "attachment", "item", "effect", "closed" })
    void optionalPathsInvalidateBothOriginalPhasePlansBeforeSideEffects(String condition) throws Exception {
        productionLocalState();
        try (var installed = installedBaseEnvironment()) {
            var entity = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.ENTITY).plan();
            var connection = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).plan();
            assertNotNull(entity);
            assertNotNull(connection);
            var expected = net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.OPTIONAL_TICK_STATE;
            switch (condition) {
                case "target" -> when(this.player.getLastHurtMob()).thenReturn(mock(net.minecraft.world.entity.LivingEntity.class));
                case "gravity" -> when(this.player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY)).thenReturn(-1d);
                case "scale" -> when(this.player.getScale()).thenReturn(16f);
                case "pendingScale" -> field(net.minecraft.world.entity.LivingEntity.class, this.player, "appliedScale", .0625f);
                case "food" -> this.player.getFoodData().setFoodLevel(1);
                case "support" -> this.player.mainSupportingBlockPos = java.util.Optional.of(new BlockPos(10000, 63, 0));
                case "border" -> this.level.getWorldBorder().setCenter(100000000, 0);
                case "movingBorder" -> this.level.getWorldBorder().lerpSizeBetween(10000, 1, 60000);
                case "suffocation" -> when(this.player.isInWall()).thenReturn(true);
                case "step" -> this.player.moveDist = 2;
                case "attachment" -> {
                    when(this.player.hasAttachments()).thenReturn(true);
                    expected = net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.ATTACHMENTS;
                }
                case "item" -> {
                    when(this.player.getInventory().getItem(0)).thenReturn(new ItemStack(net.minecraft.world.item.Items.STONE));
                    expected = net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.SPATIAL;
                }
                case "effect" -> {
                    when(this.player.getActiveEffects()).thenReturn(List.of(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SPEED)));
                    expected = net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.SPATIAL;
                }
                case "closed" -> {
                    field(net.minecraft.server.network.ServerCommonPacketListenerImpl.class, this.player.connection, "closed", true);
                    expected = net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.NETWORK_TRANSITION;
                }
                default -> throw new AssertionError(condition);
            }
            assertFalse(entity.revalidate());
            assertFalse(connection.revalidate());
            for (var phase : RegionPlayerTickCoordinator.Phase.values()) {
                var refused = this.engine.capturePlayerAdmissionPlan(this.player, phase);
                assertNull(refused.plan());
                assertEquals(expected, refused.refusal(), refused.detail());
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), phase, calls::incrementAndGet);
                assertEquals(1, calls.get());
                assertTrue(this.engine.playerAdmissionRefusals().get(phase.name() + ":" + expected.name()) >= 1);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void levelRandomUsesCapturedPlayerSourceOnlyInsideExistingStateScope(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState();
        var spatial = capture();
        var local = statePlan(spatial);
        var playerRandom = mock(net.minecraft.util.RandomSource.class);
        when(playerRandom.nextInt(200)).thenReturn(37);
        when(this.player.getRandom()).thenReturn(playerRandom);
        var vanilla = mock(net.minecraft.util.RandomSource.class);
        when(vanilla.nextInt(200)).thenReturn(91);
        var levelRandom = new net.neoforged.neoforge.server.threading.RegionAwareRandomSource(vanilla);
        assertEquals(91, levelRandom.nextInt(200));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            this.worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    assertSame(playerRandom, this.engine.currentGroupRandom());
                    assertEquals(37, levelRandom.nextInt(200));
                }
                assertNull(this.engine.currentGroupRandom());
            });
        }
        assertEquals(91, levelRandom.nextInt(200));
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false, ENTITY", "true, ENTITY", "false, CONNECTION", "true, CONNECTION" })
    void boundProductionPlanRechecksUnknownListenerOnNativeWorkerBeforeConsuming(boolean persistent, RegionPlayerTickCoordinator.Phase phase) throws Exception {
        if (persistent) initialize(true);
        productionLocalState();
        try (var installed = installedBaseEnvironment()) {
            var captured = this.engine.capturePlayerAdmissionPlan(this.player, phase);
            assertNotNull(captured.plan(), captured.toString());
            var plan = captured.plan();
            try (var lease = reserve(plan.spatial())) {
                assertNotNull(lease);
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(plan.state())));
                var callbacks = new java.util.concurrent.atomic.AtomicInteger();
                java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingBreatheEvent> unknown = event -> callbacks.incrementAndGet();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingBreatheEvent.class, unknown);
                try {
                    this.worker(() -> {
                        // Mockito observations are thread-local; production always inspects the loader.
                        try (var workerEnvironment = installedBaseEnvironment(); var space = lease.open()) {
                            assertThrows(IllegalStateException.class, () -> lease.openPlayerState(plan.state()));
                            assertFalse(net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.hasThreadBinding());
                            assertEquals(0, callbacks.get());
                            assertEquals(0, this.player.totalExperience);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
                } finally {
                    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(unknown);
                }
                // A rejected pre-body open has not consumed the state or run any original effect.
                assertTrue(plan.revalidate());
                this.worker(() -> {
                    try (var workerEnvironment = installedBaseEnvironment(); var space = lease.open(); var owner = lease.openPlayerState(plan.state())) {
                        net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
                        assertEquals(7, this.player.totalExperience);
                        assertThrows(IllegalStateException.class, () -> lease.openPlayerState(plan.state()));
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                assertEquals(0, callbacks.get());
                assertFalse(plan.revalidate());
            }
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertNull(net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.current());
        assertEquals(0, this.engine.playerTicks().snapshot().phases().get(phase).worker());
    }

    @Test
    void productionAdmissionRequiresServerThread() throws Exception {
        productionLocalState();
        when(this.server.isSameThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.ENTITY));
    }

    private record LocalState(net.minecraft.server.PlayerAdvancements advancements, net.minecraft.advancements.AdvancementHolder holder,
            net.minecraft.server.ServerAdvancementManager manager, net.minecraft.world.scores.Scoreboard scoreboard) {}

    private LocalState localState(net.minecraft.advancements.AdvancementRewards rewards) throws Exception {
        var trigger = net.minecraft.advancements.CriteriaTriggers.TICK;
        var holder = net.minecraft.advancements.Advancement.Builder.advancement()
                .addCriterion("tick", trigger.createCriterion(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty())))
                .rewards(rewards).build(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("state_test", "tick"));
        var manager = mock(net.minecraft.server.ServerAdvancementManager.class);
        when(manager.tree()).thenReturn(new net.minecraft.advancements.AdvancementTree());
        when(manager.getAllAdvancements()).thenReturn(List.of(holder));
        var reloadable = mock(net.minecraft.server.ReloadableServerRegistries.Holder.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(this.server.reloadableRegistries()).thenReturn(reloadable);
        when(this.level.getRandom()).thenReturn(net.minecraft.util.RandomSource.create(1));
        when(this.player.position()).thenReturn(new Vec3(47.5, 64, 8.5));
        var advancements = new net.minecraft.server.PlayerAdvancements(mock(com.mojang.datafixers.DataFixer.class), this.server.getPlayerList(), manager, stateDirectory.resolve(UUID.randomUUID() + ".json"), this.player);
        when(this.player.getAdvancements()).thenReturn(advancements);
        when(this.level.getGameRules()).thenReturn(new net.minecraft.world.level.GameRules());
        when(this.level.getDifficulty()).thenReturn(net.minecraft.world.Difficulty.NORMAL);
        var food = new net.minecraft.world.food.FoodData();
        when(this.player.getFoodData()).thenReturn(food);
        var attributes = new net.minecraft.world.entity.ai.attributes.AttributeMap(net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder().add(net.minecraft.world.entity.ai.attributes.Attributes.LUCK, 0).build());
        when(this.player.getAttributes()).thenReturn(attributes);
        var stats = new net.minecraft.stats.ServerStatsCounter(this.server, stateDirectory.resolve(UUID.randomUUID() + ".stats").toFile());
        when(this.player.getStats()).thenReturn(stats);
        var cooldown = new net.minecraft.world.item.ServerItemCooldowns(this.player);
        when(this.player.getCooldowns()).thenReturn(cooldown);
        var menu = mock(net.minecraft.world.inventory.InventoryMenu.class);
        when(menu.forgiaPlayerLocalContainers()).thenReturn(List.of());
        field(net.minecraft.world.entity.player.Player.class, this.player, "inventoryMenu", menu);
        this.player.containerMenu = menu;
        when(this.player.forgiaHasLocalStateBindings()).thenReturn(true);
        when(this.player.forgiaHasSimpleSharedState()).thenReturn(true);
        var board = new net.minecraft.world.scores.Scoreboard();
        when(this.player.getScoreboard()).thenReturn(board);
        doCallRealMethod().when(this.player).giveExperiencePoints(anyInt());
        doCallRealMethod().when(this.player).giveExperienceLevels(anyInt());
        when(this.player.getXpNeededForNextLevel()).thenCallRealMethod();
        return new LocalState(advancements, holder, manager, board);
    }

    private RegionPlayerStatePlan statePlan(RegionPlayerSpatialPlan spatial) {
        var result = this.engine.capturePlayerStatePlan(spatial);
        assertEquals(RegionPlayerStatePlan.Refusal.NONE, result.refusal());
        return result.plan();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalTickCriterionAwardXpAndLocalStatsRunInsideRealSpatialOwner(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        var state = localState(net.minecraft.advancements.AdvancementRewards.Builder.experience(7).build());
        var spatial = capture();
        var local = statePlan(spatial);
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            this.worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
                    assertTrue(state.advancements.getOrStartProgress(state.holder).isDone());
                    assertEquals(7, this.player.totalExperience);
                    assertEquals(1, this.player.experienceLevel);
                    net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
                    net.minecraft.advancements.CriteriaTriggers.ENTER_BLOCK.trigger(this.player, Blocks.AIR.defaultBlockState());
                    assertEquals(7, this.player.totalExperience);
                    var stat = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME);
                    this.player.getStats().increment(this.player, stat, 1);
                    assertEquals(1, this.player.getStats().getValue(stat));
                    state.scoreboard.forAllObjectives(stat, this.player, score -> {
                        throw new AssertionError("empty criteria invoked consumer");
                    });
                    this.player.getFoodData().tick(this.player);
                    this.player.getCooldowns().tick();
                    assertThrows(IllegalStateException.class, () -> RegionThreadingHooks.assertServerEntityStorageAccess("global guard unchanged"));
                    assertThrows(IllegalStateException.class, () -> this.engine.runOnServerThread(this.level, () -> true));
                    assertThrows(IllegalStateException.class, () -> lease.openPlayerState(local));
                }
            });
            this.worker(() -> {
                try (var space = lease.open()) {
                    assertThrows(IllegalStateException.class, () -> net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player));
                    assertThrows(IllegalStateException.class, () -> lease.openPlayerState(local));
                }
            });
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[] {}));
        assertNull(RegionPlayerStateScope.current());
        assertTrue(state.advancements.revoke(state.holder, "tick"));
        net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
        assertEquals(14, this.player.totalExperience);
    }

    @Test
    void functionRewardHasExplicitPreBodyRefusalButOriginalSerialAwardStillRunsSynchronously() throws Exception {
        var function = mock(net.minecraft.commands.CacheableFunction.class);
        var functions = mock(net.minecraft.server.ServerFunctionManager.class);
        var commandFunction = mock(net.minecraft.commands.functions.CommandFunction.class);
        when(this.server.getFunctions()).thenReturn(functions);
        when(function.get(functions)).thenReturn(java.util.Optional.of(commandFunction));
        var source = mock(net.minecraft.commands.CommandSourceStack.class, org.mockito.Answers.RETURNS_SELF);
        when(this.player.createCommandSourceStack()).thenReturn(source);
        java.util.concurrent.atomic.AtomicInteger commands = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertEquals(7, this.player.totalExperience);
            commands.incrementAndGet();
            return null;
        }).when(functions).execute(any(net.minecraft.commands.functions.CommandFunction.class), any(net.minecraft.commands.CommandSourceStack.class));
        var reward = new net.minecraft.advancements.AdvancementRewards(7, List.of(), List.of(), java.util.Optional.of(function));
        var state = localState(reward);
        var spatial = capture();
        assertEquals(RegionPlayerStatePlan.Refusal.GLOBAL_REWARD, this.engine.capturePlayerStatePlan(spatial).refusal());
        assertEquals(0, this.player.totalExperience);
        assertEquals(0, commands.get());
        net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
        assertEquals(7, this.player.totalExperience);
        assertEquals(1, commands.get());
        assertTrue(state.advancements.getOrStartProgress(state.holder).isDone());
        net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
        assertEquals(1, commands.get());
    }

    @Test
    void scoreboardObjectiveAndSharedMenuRefuseBeforeOriginalBody() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        state.scoreboard.addObjective("played", net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME), net.minecraft.network.chat.Component.literal("played"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        assertEquals(RegionPlayerStatePlan.Refusal.SCOREBOARD_OBJECTIVES, this.engine.capturePlayerStatePlan(capture()).refusal());
        when(this.player.forgiaHasLocalStateBindings()).thenReturn(false);
        assertEquals(RegionPlayerStatePlan.Refusal.SHARED_MENU, this.engine.capturePlayerStatePlan(capture()).refusal());
    }

    @Test
    void localOwnerIsolationAndLifecycleRetirementInvalidateOldScope() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var foreign = new net.neoforged.neoforge.server.threading.region.PlayerCriterionListeners(true);
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            this.worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    assertThrows(IllegalStateException.class, () -> foreign.get(net.minecraft.advancements.CriteriaTriggers.TICK));
                    assertThrows(IllegalStateException.class, () -> state.advancements.setPlayer(this.player));
                    assertThrows(IllegalStateException.class, () -> state.advancements.reload(state.manager));
                }
            });
        }
        var spatial2 = capture();
        var local2 = statePlan(spatial2);
        state.advancements.setPlayer(this.player);
        try (var lease = reserve(spatial2)) {
            assertEquals(RegionPlayerStatePlan.Refusal.STALE_STATE, lease.bindPlayerState(List.of(local2)));
        }
    }

    @Test
    void originalListenerSetRevisionRejectsStaleStateBeforeDispatch() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var listener = state.advancements.forgiaCriterionListeners().get(net.minecraft.advancements.CriteriaTriggers.TICK).iterator().next();
        net.minecraft.advancements.CriteriaTriggers.TICK.removePlayerListener(state.advancements, listener);
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.STALE_STATE, lease.bindPlayerState(List.of(local)));
        }
    }

    @Test
    void registeredEnterBlockCallbackRefusesCompleteConnectionBeforeItsOriginalBody() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var instance = new net.minecraft.advancements.critereon.EnterBlockTrigger.TriggerInstance(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        var listener = new net.minecraft.advancements.CriterionTrigger.Listener<>(instance, state.holder, "tick");
        net.minecraft.advancements.CriteriaTriggers.ENTER_BLOCK.addPlayerListener(state.advancements, listener);
        assertEquals(RegionPlayerStatePlan.Refusal.UNKNOWN_CRITERION, this.engine.capturePlayerStatePlan(capture()).refusal());
        assertEquals(0, this.player.totalExperience);
    }

    @Test
    void unknownCriterionPredicatePreventsWorkerBeforeAnyPredicateEvaluation() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var instance = new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.of(net.minecraft.advancements.critereon.ContextAwarePredicate.create()));
        var extra = new net.minecraft.advancements.CriterionTrigger.Listener<>(instance, state.holder, "unknown");
        net.minecraft.advancements.CriteriaTriggers.TICK.addPlayerListener(state.advancements, extra);
        assertEquals(RegionPlayerStatePlan.Refusal.UNKNOWN_CRITERION, this.engine.capturePlayerStatePlan(capture()).refusal());
    }

    @Test
    void authorizedCriterionPredicateExceptionAndHashSetReentryAreOriginalAndSynchronous() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.Builder.experience(7).build());
        var trigger = net.minecraft.advancements.CriteriaTriggers.TICK;
        var extra = new net.minecraft.advancements.CriterionTrigger.Listener<>(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty()), state.holder, "extra");
        trigger.addPlayerListener(state.advancements, extra);
        var spatial = capture();
        var local = statePlan(spatial);
        RuntimeException sentinel = new RuntimeException("original predicate sentinel");
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            this.worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    var originalSet = state.advancements.forgiaCriterionListeners().get(trigger);
                    java.util.function.Predicate<net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance> throwing = instance -> {
                        throw sentinel;
                    };
                    RuntimeException failure = assertThrows(RuntimeException.class, () -> invoke(trigger, "trigger", new Class<?>[] { ServerPlayer.class, java.util.function.Predicate.class }, this.player, throwing));
                    assertSame(sentinel, failure);
                    assertEquals(0, this.player.totalExperience);
                    assertEquals(2, originalSet.size());
                    java.util.function.Predicate<net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance> reentrant = instance -> {
                        originalSet.clear();
                        return true;
                    };
                    assertThrows(java.util.ConcurrentModificationException.class, () -> invoke(trigger, "trigger", new Class<?>[] { ServerPlayer.class, java.util.function.Predicate.class }, this.player, reentrant));
                    assertEquals(0, this.player.totalExperience);
                }
            });
        }
    }

    @Test
    void localResourceAliasConflictIsDetectedByOriginalObjectIdentity() throws Exception {
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var first = statePlan(spatial);
        var second = statePlan(spatial);
        assertTrue(first.conflictsWith(second));
        assertSame(first.player(), second.player());
        var foreignFood = new net.minecraft.world.food.FoodData();
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(first)));
            this.worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(first)) {
                    assertThrows(IllegalStateException.class, () -> foreignFood.tick(this.player));
                    var attribute = new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.LUCK, instance -> {});
                    assertThrows(IllegalStateException.class, () -> attribute.setBaseValue(4));
                    assertThrows(IllegalStateException.class, () -> this.player.getScoreboard().forAllObjectives(net.minecraft.world.scores.criteria.ObjectiveCriteria.DUMMY, this.player, score -> {}));
                }
            });
        }
    }

    @Test
    void twoOriginalPlayersWithAliasedStatsRefuseEntireStateBinding() throws Exception {
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        ServerPlayer original = this.player;
        var firstSpatial = capture();
        var first = statePlan(firstSpatial);
        var sharedStats = original.getStats();
        ServerPlayer other = mock(ServerPlayer.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        field(ServerPlayer.class, other, "server", this.server);
        field(ServerPlayer.class, other, "gameMode", new ServerPlayerGameMode(other));
        when(other.serverLevel()).thenReturn(this.level);
        when(other.getCamera()).thenReturn(other);
        Connection connection = mock(Connection.class);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        listener.player = other;
        other.connection = listener;
        when(listener.getConnection()).thenReturn(connection);
        when(listener.isAcceptingMessages()).thenReturn(true);
        when(connection.getPacketListener()).thenReturn(listener);
        when(connection.isConnected()).thenReturn(true);
        this.player = other;
        this.add(other, 47.5, 8.5, new AABB(47.2, 64, 8.2, 47.8, 65.8, 8.8));
        when(this.server.getPlayerList().getPlayer(other.getUUID())).thenReturn(other);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        when(other.getStats()).thenReturn(sharedStats);
        var secondSpatial = capture();
        var second = statePlan(secondSpatial);
        assertTrue(first.conflictsWith(second));
        this.player = original;
        firstSpatial = capture();
        first = statePlan(firstSpatial);
        this.parent = this.engine.beginPlayerSerial(original, original.connection.getConnection(), RegionPlayerTickCoordinator.Phase.CONNECTION);
        assertNotNull(this.parent);
        this.batch = ((Set<?>) field(this.engine, "activeTickBatches")).iterator().next();
        Class<?> effectsType = Class.forName(PKG + "RegionOwnershipLeaseGroup");
        Object effect = Proxy.newProxyInstance(effectsType.getClassLoader(), new Class<?>[] { effectsType }, (proxy, m, args) -> m.getReturnType() == boolean.class ? true : null);
        var lease = (RegionPlayerSpatialLease) invoke(this.engine, "reservePlayerSpatial", new Class<?>[] { Class.forName(PKG + "RegionTickBatch"), effectsType, List.class }, this.batch, effect, List.of(firstSpatial, secondSpatial));
        try (lease) {
            assertNotNull(lease);
            assertEquals(RegionPlayerStatePlan.Refusal.SHARED_PLAYER_RESOURCE, lease.bindPlayerState(List.of(first, second)));
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[] {}));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> staleStateCases() {
        return java.util.stream.Stream.of("food", "attributes", "menu", "listener", "rawListener", "reward", "objective", "xp", "optional", "connection")
                .flatMap(change -> java.util.stream.Stream.of(false, true).map(bound -> org.junit.jupiter.params.provider.Arguments.of(change, bound)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("staleStateCases")
    void capturedStateChangesRefuseBeforeBindingAndBeforeWorkerBody(String change, boolean bound) throws Exception {
        var recipes = new ArrayList<net.minecraft.resources.ResourceLocation>();
        var state = localState(new net.minecraft.advancements.AdvancementRewards(7, List.of(), recipes, java.util.Optional.empty()));
        var spatial = capture();
        var local = statePlan(spatial);
        try (var lease = reserve(spatial)) {
            if (bound) assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            switch (change) {
                case "food" -> when(this.player.getFoodData()).thenReturn(new net.minecraft.world.food.FoodData());
                case "attributes" -> when(this.player.getAttributes()).thenReturn(new net.minecraft.world.entity.ai.attributes.AttributeMap(net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder().build()));
                case "menu" -> when(this.player.inventoryMenu.forgiaPlayerLocalContainers()).thenReturn(List.of(new Object()));
                case "listener" -> net.minecraft.advancements.CriteriaTriggers.TICK.addPlayerListener(state.advancements,
                        new net.minecraft.advancements.CriterionTrigger.Listener<>(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty()), state.holder, "changed"));
                case "rawListener" -> {
                    var listeners = state.advancements.forgiaCriterionListeners().get(net.minecraft.advancements.CriteriaTriggers.TICK);
                    listeners.clear();
                    listeners.add(new net.minecraft.advancements.CriterionTrigger.Listener<>(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty()), state.holder, "replacement"));
                }
                case "reward" -> recipes.add(net.minecraft.resources.ResourceLocation.withDefaultNamespace("stick"));
                case "objective" -> state.scoreboard.addObjective("changed", net.minecraft.world.scores.criteria.ObjectiveCriteria.HEALTH, net.minecraft.network.chat.Component.literal("changed"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
                case "xp" -> this.player.experienceLevel = 4;
                case "optional" -> when(this.player.forgiaHasSimpleSharedState()).thenReturn(false);
                case "connection" -> when(this.player.connection.isAcceptingMessages()).thenReturn(false);
                default -> throw new AssertionError(change);
            }
            assertFalse(local.revalidate());
            if (bound) {
                worker(() -> {
                    try (var space = lease.open()) {
                        assertThrows(IllegalStateException.class, () -> lease.openPlayerState(local));
                        assertNull(RegionPlayerStateScope.current());
                    }
                });
            } else {
                assertEquals(RegionPlayerStatePlan.Refusal.STALE_STATE, lease.bindPlayerState(List.of(local)));
            }
            assertEquals(0, this.player.totalExperience);
            assertFalse(state.advancements.getOrStartProgress(state.holder).isDone());
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[] {}));
    }

    private net.minecraft.world.inventory.InventoryMenu originalLocalMenu() throws Exception {
        Inventory inventory = new Inventory(this.player);
        when(this.player.getInventory()).thenReturn(inventory);
        var menu = new net.minecraft.world.inventory.InventoryMenu(inventory, true, this.player);
        field(net.minecraft.world.entity.player.Player.class, this.player, "inventoryMenu", menu);
        this.player.containerMenu = menu;
        var listener = mock(net.minecraft.world.inventory.ContainerListener.class);
        var sync = mock(net.minecraft.world.inventory.ContainerSynchronizer.class);
        field(ServerPlayer.class, this.player, "containerListener", listener);
        field(ServerPlayer.class, this.player, "containerSynchronizer", sync);
        menu.setSynchronizer(sync);
        menu.addSlotListener(listener);
        when(this.player.forgiaHasLocalStateBindings()).thenCallRealMethod();
        assertTrue(this.player.forgiaHasLocalStateBindings());
        return menu;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "slot", "container", "synchronizer", "listener", "carried", "result" })
    void originalMenuChangesInvalidateBoundStateBeforeWorkerBody(String change) throws Exception {
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var menu = originalLocalMenu();
        var spatial = capture();
        var local = statePlan(spatial);
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            switch (change) {
                case "slot" -> menu.slots.set(9, new net.minecraft.world.inventory.Slot(this.player.getInventory(), 9, 0, 0));
                case "container" -> menu.slots.set(9, new net.minecraft.world.inventory.Slot(new net.minecraft.world.SimpleContainer(41), 9, 0, 0));
                case "synchronizer" -> menu.setSynchronizer(mock(net.minecraft.world.inventory.ContainerSynchronizer.class));
                case "listener" -> menu.addSlotListener(mock(net.minecraft.world.inventory.ContainerListener.class));
                case "carried" -> menu.setCarried(new ItemStack(net.minecraft.world.item.Items.STICK));
                case "result" -> menu.slots.get(0).container.setItem(0, new ItemStack(net.minecraft.world.item.Items.STICK));
                default -> throw new AssertionError(change);
            }
            assertFalse(local.revalidate());
            worker(() -> {
                try (var space = lease.open()) {
                    assertThrows(IllegalStateException.class, () -> lease.openPlayerState(local));
                }
            });
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[] {}));
    }

    @Test
    void originalMenuAndCapturedRewardsRemainSynchronousButForeignRewardHasNoPermission() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.Builder.experience(7).build());
        originalLocalMenu();
        var spatial = capture();
        var local = statePlan(spatial);
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            worker(() -> {
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    var foreign = net.minecraft.advancements.AdvancementRewards.Builder.experience(1).build();
                    assertThrows(IllegalStateException.class, () -> foreign.grant(this.player));
                    assertEquals(0, this.player.totalExperience);
                    this.player.inventoryMenu.broadcastChanges();
                    net.minecraft.advancements.CriteriaTriggers.TICK.trigger(this.player);
                    assertTrue(state.advancements.getOrStartProgress(state.holder).isDone());
                    assertEquals(7, this.player.totalExperience);
                    assertThrows(IllegalStateException.class, () -> this.player.giveExperiencePoints(1));
                    assertEquals(7, this.player.totalExperience);
                    assertThrows(IllegalStateException.class, () -> state.scoreboard.addObjective("worker", net.minecraft.world.scores.criteria.ObjectiveCriteria.HEALTH, net.minecraft.network.chat.Component.literal("worker"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null));
                }
            });
        }
        assertNull(state.scoreboard.getObjective("worker"));
    }

    @Test
    void locationListenerRefusesConnectionEvenImmediatelyBeforeItsPeriodicTick() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        this.player.tickCount = 19;
        net.minecraft.advancements.CriteriaTriggers.LOCATION.addPlayerListener(state.advancements,
                new net.minecraft.advancements.CriterionTrigger.Listener<>(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty()), state.holder, "location"));
        assertEquals(RegionPlayerStatePlan.Refusal.UNKNOWN_CRITERION, this.engine.capturePlayerStatePlan(capture()).refusal());
        assertEquals(0, this.player.totalExperience);
    }

    @Test
    void mutableAdvancementCriteriaAndRequirementsInvalidateTheCapturedAward() throws Exception {
        var state = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var criteria = new HashMap<>(state.holder.value().criteria());
        var requirements = new ArrayList<List<String>>();
        requirements.add(new ArrayList<>(List.of("tick")));
        var original = state.holder.value();
        var advancement = new net.minecraft.advancements.Advancement(original.parent(), original.display(), original.rewards(), criteria,
                new net.minecraft.advancements.AdvancementRequirements(requirements), false, java.util.Optional.empty());
        var holder = new net.minecraft.advancements.AdvancementHolder(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("state_test", "mutable"), advancement);
        net.minecraft.advancements.CriteriaTriggers.TICK.addPlayerListener(state.advancements,
                new net.minecraft.advancements.CriterionTrigger.Listener<>(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty()), holder, "tick"));
        var spatial = capture();
        var local = statePlan(spatial);
        requirements.getFirst().add("changed");
        assertFalse(local.revalidate());
        requirements.getFirst().removeLast();
        assertTrue(local.revalidate());
        criteria.put("tick", net.minecraft.advancements.CriteriaTriggers.TICK.createCriterion(new net.minecraft.advancements.critereon.PlayerTrigger.TriggerInstance(java.util.Optional.empty())));
        assertFalse(local.revalidate());
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.STALE_STATE, lease.bindPlayerState(List.of(local)));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void playerWorkerFailureIsRecordedOnceAndJoinedOnlyAfterPhysicalScopeExit(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertNotNull(ticket);
        assertTrue(this.engine.playerTicks().admit(ticket));
        var network = this.engine.playerNetworkGate();
        assertEquals(true, invoke(network, "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        RuntimeException original = new RuntimeException("original player worker failure");
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            this.worker(() -> {
                try (var space = lease.open(); var state = lease.openPlayerState(local)) {
                    assertEquals(true, invoke(this.engine.playerTicks(), "startWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket));
                    assertTrue(RegionPlayerTickCoordinator.executingWorker(this.player, RegionPlayerTickCoordinator.Phase.ENTITY));
                    effects.incrementAndGet();
                    invoke(this.engine.playerTicks(), "workerReturned", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class, Throwable.class }, ticket, original);
                    assertFalse(RegionPlayerTickCoordinator.executingWorker(this.player, RegionPlayerTickCoordinator.Phase.ENTITY));
                    assertEquals(false, invoke(this.engine.playerTicks(), "startWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket));
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            var beforeJoin = this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.ENTITY);
            assertEquals(1, beforeJoin.worker());
            assertEquals(1, beforeJoin.failed());
            assertEquals(0, beforeJoin.joined());
            assertEquals(1, effects.get());
            assertThrows(IllegalStateException.class, () -> this.engine.playerTicks().executeSerial(ticket, effects::incrementAndGet));
            invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            assertEquals(1, this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.ENTITY).joined());
            assertFalse(ticket.retainsLiveBinding());
        } finally {
            invoke(network, "thaw", new Class<?>[0]);
        }
        assertNull(RegionPlayerStateScope.current());
        assertNull(RegionPlayerSpatialLease.current());
    }

    private Object construct(String name, Class<?>[] signature, Object... args) throws Exception {
        var constructor = Class.forName(PKG + name).getDeclaredConstructor(signature);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private Object dispatchCall(RegionPlayerStatePlan local, RegionPlayerTickCoordinator.Plan ticket, Runnable original) throws Exception {
        Object call = construct("RegionPlayerTickDispatch$Call", new Class<?>[] { ServerPlayer.class, RegionPlayerTickCoordinator.Phase.class, Runnable.class },
                this.player, ticket.phase(), original);
        var admission = mock(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.class);
        when(admission.state()).thenReturn(local);
        field(call.getClass(), call, "admission", admission);
        field(call.getClass(), call, "ticket", ticket);
        return call;
    }

    private Runnable dispatchGroup(Object dispatch, RegionPlayerSpatialLease lease, Object call, Object gate) {
        return () -> {
            try {
                invoke(dispatch, "runGroup", new Class<?>[] { RegionPlayerSpatialLease.class, List.class, gate.getClass() }, lease, List.of(call), gate);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void partiallyRejectedDispatchJoinsNativeGroupBeforeSerialFallbackWithoutStartingAnyBody(boolean persistent, boolean throwsOnSubmit) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(ticket));
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            Object dispatch = construct("RegionPlayerTickDispatch", new Class<?>[] { RegionThreadingEngine.class, Class.forName(PKG + "RegionTickBatch") }, this.engine, this.batch);
            Object call = dispatchCall(local, ticket, bodies::incrementAndGet);
            Object gate = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, 2);
            var work = new net.neoforged.neoforge.server.threading.region.RegionTaskBatch();
            work.add("accepted native player group", (Runnable) invoke(this.batch, "groupWork", new Class<?>[] { Runnable.class }, dispatchGroup(dispatch, lease, call, gate)));
            // RegionTaskBatch's original rejection path executes this group on the publisher. It must
            // vote refusal without opening any lease or running the original body, and unblock group 1.
            work.add("rejected player group", dispatchGroup(dispatch, null, call, gate));
            var pool = (RegionWorkerExecutor) field(this.engine, "workerPool");
            var submissions = new java.util.concurrent.atomic.AtomicInteger();
            var joined = work.submit((description, task) -> {
                if (submissions.getAndIncrement() == 0) return pool.execute(description, task);
                if (throwsOnSubmit) throw new java.util.concurrent.RejectedExecutionException("expected partial player submission");
                return false;
            });
            joined.awaitCompletion();
            joined.throwIfFailed();
            assertEquals(1, joined.rejectedCount());
            assertEquals(0, bodies.get());
            assertFalse(ticket.started());
            this.engine.playerTicks().cancel(ticket);
            assertFalse(ticket.retainsLiveBinding());
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        bodies.incrementAndGet(); // Original serial fallback may run once only after the native join.
        assertEquals(1, bodies.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void dispatchPreservesOriginalFailureAndNeverReplaysStartedCall(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(ticket));
        RuntimeException sentinel = new RuntimeException("original dispatch body effect then failure");
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            Object dispatch = construct("RegionPlayerTickDispatch", new Class<?>[] { RegionThreadingEngine.class, Class.forName(PKG + "RegionTickBatch") }, this.engine, this.batch);
            Object call = dispatchCall(local, ticket, () -> {
                bodies.incrementAndGet();
                throw sentinel;
            });
            Object gate = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, 1);
            int parentRunning = this.engine.playerTicks().snapshot().running();
            worker(dispatchGroup(dispatch, lease, call, gate));
            assertEquals(true, field(call, "started"));
            assertSame(sentinel, field(call, "failure"));
            assertEquals(1, bodies.get());
            assertEquals(parentRunning + 1, this.engine.playerTicks().snapshot().running());
            assertThrows(IllegalStateException.class, () -> this.engine.playerTicks().executeSerial(ticket, bodies::incrementAndGet));
            invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            assertFalse(ticket.retainsLiveBinding());
            assertEquals(parentRunning, this.engine.playerTicks().snapshot().running());
            assertEquals(1, bodies.get());
        } finally {
            invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
        }
        assertNull(RegionPlayerSpatialLease.current());
        assertNull(RegionPlayerStateScope.current());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void dispatchStateOpenRefusalHappensBeforeStartedAndReleasesBoundClaims(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(ticket));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            Object dispatch = construct("RegionPlayerTickDispatch", new Class<?>[] { RegionThreadingEngine.class, Class.forName(PKG + "RegionTickBatch") }, this.engine, this.batch);
            Object call = dispatchCall(local, ticket, () -> {
                throw new AssertionError("Stale body must not start");
            });
            Object gate = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, 1);
            this.player.totalExperience = 29;
            worker(dispatchGroup(dispatch, lease, call, gate));
            assertFalse(ticket.started());
            assertNull(field(call, "failure"));
            this.engine.playerTicks().cancel(ticket);
            assertFalse(ticket.retainsLiveBinding());
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void physicalSocketLossDoesNotRevokeAnAlreadyStartedPlayersCapturedState(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(ticket));
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            worker(() -> {
                Throwable failure = null;
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    assertEquals(true, invoke(this.engine.playerTicks(), "startWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket));
                    try {
                        // A remote close changes channel.isOpen before its gated channelInactive runs.
                        when(this.player.connection.getConnection().isConnected()).thenReturn(false);
                        when(this.player.connection.isAcceptingMessages()).thenReturn(false);
                        assertNotNull(RegionPlayerStateScope.current());
                        this.player.getFoodData().tick(this.player);
                        this.player.getCooldowns().tick();
                    } catch (Throwable problem) {
                        failure = problem;
                        throw problem;
                    } finally {
                        invoke(this.engine.playerTicks(), "workerReturned", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class, Throwable.class }, ticket, failure);
                    }
                } catch (Exception problem) {
                    throw new IllegalStateException(problem);
                }
            });
            invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            assertFalse(ticket.retainsLiveBinding());
        } finally {
            if (ticket.started() && ticket.retainsLiveBinding()) invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void shutdownKeepsStartedStateAndNetworkBoundaryUntilRealJoinThenCancelsRetains(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var spatial = capture();
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(ticket));
        var queue = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
        doAnswer(call -> {
            queue.add(call.getArgument(0));
            return null;
        }).when(this.server).execute(any(Runnable.class));
        var packet = io.netty.buffer.Unpooled.buffer(1);
        var callbackEffects = new java.util.concurrent.atomic.AtomicInteger();
        var shutdownFailure = new AtomicReference<Throwable>();
        Thread stopper = new Thread(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable failure) {
                shutdownFailure.set(failure);
            }
        }, "external player shutdown");
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            worker(() -> {
                Throwable failure = null;
                try (var space = lease.open(); var owner = lease.openPlayerState(local)) {
                    assertEquals(true, invoke(this.engine.playerTicks(), "startWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket));
                    try {
                        stopper.start();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!(boolean) field(this.engine, "shutdown") && System.nanoTime() < deadline) Thread.yield();
                        assertEquals(true, field(this.engine, "shutdown"));
                        assertTrue(stopper.isAlive(), "Shutdown waits for this actual original owner scope");
                        var networkFailure = new AtomicReference<Throwable>();
                        Thread netty = new Thread(() -> {
                            try (var gate = net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate.enter(
                                    this.player.connection.getConnection(), callbackEffects::incrementAndGet, packet)) {
                                if (gate != null) callbackEffects.incrementAndGet();
                            } catch (Throwable problem) {
                                networkFailure.set(problem);
                            } finally {
                                packet.release();
                            }
                        }, "Netty shutdown callback");
                        netty.start();
                        netty.join(5000);
                        assertFalse(netty.isAlive());
                        assertNull(networkFailure.get());
                        assertEquals(0, callbackEffects.get(), "Shutdown must not bypass the still-frozen gate");
                        assertEquals(1, packet.refCnt(), "Deferred packet remains retained until actual join");
                        assertNotNull(RegionPlayerStateScope.current(), "Stop-new-admission must not revoke the started scope");
                        this.player.getFoodData().tick(this.player);
                        this.player.getCooldowns().tick();
                    } catch (Throwable problem) {
                        failure = problem;
                        throw problem;
                    } finally {
                        invoke(this.engine.playerTicks(), "workerReturned", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class, Throwable.class }, ticket, failure);
                    }
                } catch (Exception problem) {
                    throw new IllegalStateException(problem);
                }
            });
            invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
        } finally {
            if (ticket.started() && ticket.retainsLiveBinding()) invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
            this.parent.close();
            this.parent = null;
            stopper.join(5000);
        }
        assertFalse(stopper.isAlive());
        assertNull(shutdownFailure.get());
        assertEquals(0, packet.refCnt());
        assertEquals(0, this.engine.playerNetworkGate().snapshot().pending());
        assertTrue(this.engine.playerNetworkGate().snapshot().closed());
        assertEquals(1, this.engine.playerNetworkGate().snapshot().cancelled());
        for (Runnable envelope : queue) envelope.run();
        assertEquals(0, callbackEffects.get());
    }

    @Test
    void scopeFailureKeepsOriginalThrowableAndClosesAllPermissions() throws Exception {
        var plan = capture();
        RuntimeException original = new RuntimeException("original-body-sentinel");
        try (var lease = reserve(plan)) {
            var failure = assertThrows(IllegalStateException.class, () -> worker(() -> {
                try (var scope = lease.open()) {
                    throw original;
                }
            }));
            assertSame(original, failure.getCause());
            worker(() -> assertNull(RegionPlayerSpatialLease.current()));
        }
        assertNull(RegionPlayerSpatialLease.current());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,ENTITY", "true,ENTITY", "false,CONNECTION", "true,CONNECTION" })
    void levelUnloadWaitsForActivePlayerScopeAndPublisherRetirement(boolean persistent, RegionPlayerTickCoordinator.Phase phase) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var geometry = this.engine.capturePlayerSpatialPlan(this.player, phase);
        assertNotNull(geometry.plan(), geometry.toString());
        var spatial = geometry.plan();
        var local = statePlan(spatial);
        if (phase == RegionPlayerTickCoordinator.Phase.CONNECTION) {
            this.parent = this.engine.beginPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
            assertNotNull(this.parent);
            this.batch = ((Set<?>) field(this.engine, "activeTickBatches")).iterator().next();
        }
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), phase);
        assertTrue(this.engine.playerTicks().admit(ticket));
        var attempted = new CountDownLatch(1);
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread unloader = new Thread(() -> {
            attempted.countDown();
            try {
                this.engine.clearLevelRegionTasks(this.level);
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                returned.countDown();
            }
        }, "external active-player level unload");
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            worker(() -> {
                try (var space = lease.open(); var state = lease.openPlayerState(local)) {
                    assertEquals(true, invoke(this.engine.playerTicks(), "startWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket));
                    unloader.start();
                    assertTrue(attempted.await(5, TimeUnit.SECONDS));
                    assertFalse(returned.await(100, TimeUnit.MILLISECONDS), "Unloading must await physical return and publisher cleanup");
                    assertNotNull(RegionPlayerStateScope.current());
                    this.player.getFoodData().tick(this.player);
                    this.player.getCooldowns().tick();
                    assertThrows(IllegalStateException.class, lease::close);
                    invoke(this.engine.playerTicks(), "workerReturned", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class, Throwable.class }, ticket, null);
                } catch (Exception problem) {
                    throw new IllegalStateException(problem);
                }
            });
            assertEquals(1, returned.getCount(), "Scope return alone must not retire a still-published parent");
            invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            assertFalse(ticket.retainsLiveBinding());
        } finally {
            invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
            this.parent.close();
            this.parent = null;
            unloader.join(5000);
        }
        assertFalse(unloader.isAlive());
        assertNull(failure.get());
        assertEquals(0, returned.getCount());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertEquals(0, this.engine.playerTicks().snapshot().owners());
        assertEquals(0, this.engine.playerTicks().snapshot().pending());
        assertEquals(0, this.engine.playerTicks().snapshot().running());
        assertEquals(true, invoke(this.engine, "isLevelClosed", new Class<?>[] { ServerLevel.class }, this.level));
        assertNull(this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), phase));
        assertNull(RegionPlayerStateScope.current());
        assertNull(RegionPlayerSpatialLease.current());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,ENTITY,open", "true,ENTITY,open", "false,CONNECTION,open", "true,CONNECTION,open",
            "false,ENTITY,reject", "true,ENTITY,reject", "false,CONNECTION,reject", "true,CONNECTION,reject",
            "false,ENTITY,throwSubmit", "true,ENTITY,throwSubmit", "false,CONNECTION,throwSubmit", "true,CONNECTION,throwSubmit",
            "false,ENTITY,bodyFailure", "true,ENTITY,bodyFailure", "false,CONNECTION,bodyFailure", "true,CONNECTION,bodyFailure",
            "false,ENTITY,cancel", "true,ENTITY,cancel", "false,CONNECTION,cancel", "true,CONNECTION,cancel"
    })
    void bothPhaseDispatchFailuresJoinBeforeFallbackAndKeepOriginalThrowable(boolean persistent, RegionPlayerTickCoordinator.Phase phase, String condition) throws Exception {
        if (persistent) initialize(true);
        localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        if (phase == RegionPlayerTickCoordinator.Phase.CONNECTION) {
            this.parent = this.engine.beginPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
            this.batch = ((Set<?>) field(this.engine, "activeTickBatches")).iterator().next();
        }
        var spatial = this.engine.capturePlayerSpatialPlan(this.player, phase).plan();
        assertNotNull(spatial);
        var local = statePlan(spatial);
        var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), phase);
        assertTrue(this.engine.playerTicks().admit(ticket));
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        RuntimeException original = new RuntimeException("both-phase original after effect");
        boolean bodyFailure = condition.equals("bodyFailure");
        boolean partial = condition.equals("reject") || condition.equals("throwSubmit");
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var lease = reserve(spatial)) {
            assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
            Object dispatch = construct("RegionPlayerTickDispatch", new Class<?>[] { RegionThreadingEngine.class, Class.forName(PKG + "RegionTickBatch") }, this.engine, this.batch);
            Object call = dispatchCall(local, ticket, () -> {
                bodies.incrementAndGet();
                throw original;
            });
            Object gate = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, partial ? 2 : 1);
            if (condition.equals("open")) this.player.totalExperience = 29;
            if (condition.equals("cancel")) assertTrue(this.engine.playerTicks().cancel(ticket));
            var work = new net.neoforged.neoforge.server.threading.region.RegionTaskBatch();
            work.add("original native phase group", (Runnable) invoke(this.batch, "groupWork", new Class<?>[] { Runnable.class }, dispatchGroup(dispatch, lease, call, gate)));
            if (partial) work.add("unstarted refused phase group", dispatchGroup(dispatch, null, call, gate));
            var pool = (RegionWorkerExecutor) field(this.engine, "workerPool");
            var submitted = new java.util.concurrent.atomic.AtomicInteger();
            var joined = work.submit((description, task) -> {
                if (submitted.getAndIncrement() == 0) return pool.execute(description, task);
                if (condition.equals("throwSubmit")) throw new java.util.concurrent.RejectedExecutionException("both-phase partial submission");
                return false;
            });
            joined.awaitCompletion();
            joined.throwIfFailed();
            assertEquals(partial ? 1 : 0, joined.rejectedCount());
            assertEquals(bodyFailure, ticket.started());
            assertEquals(bodyFailure ? 1 : 0, bodies.get());
            if (bodyFailure) {
                assertSame(original, field(call, "failure"));
                assertThrows(IllegalStateException.class, () -> this.engine.playerTicks().executeSerial(ticket, bodies::incrementAndGet));
                invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
            } else {
                assertNull(field(call, "failure"));
                this.engine.playerTicks().cancel(ticket);
            }
            assertFalse(ticket.retainsLiveBinding());
        } finally {
            invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
            this.parent.close();
            this.parent = null;
        }
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        if (!bodyFailure) this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), phase, bodies::incrementAndGet);
        assertEquals(1, bodies.get());
        assertEquals(0, this.engine.playerTicks().snapshot().pending());
        assertEquals(0, this.engine.playerTicks().snapshot().running());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "keepalive", "floating", "vehicleFloating", "awaitingTeleport", "delayedDisconnect", "disconnectDetails", "handlingFault", "handledDisconnect", "memoryConnection",
            "sleep", "sprinting", "sneaking", "swimming", "inWater", "inLava", "air", "fire", "forcedPose", "nonFiniteHealth", "nonFiniteRotation", "usingItem", "levelAttachment", "profiler", "camera", "dead", "removed", "idle"
    })
    void remainingOriginalBranchesRefuseBothPlansBeforeCallbacks(String condition) throws Exception {
        productionLocalState();
        try (var installed = installedBaseEnvironment()) {
            var entity = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.ENTITY).plan();
            var connection = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).plan();
            assertNotNull(entity);
            assertNotNull(connection);
            switch (condition) {
                case "keepalive" -> field(net.minecraft.server.network.ServerCommonPacketListenerImpl.class, this.player.connection, "keepAlivePending", true);
                case "floating" -> field(ServerGamePacketListenerImpl.class, this.player.connection, "clientIsFloating", true);
                case "vehicleFloating" -> field(ServerGamePacketListenerImpl.class, this.player.connection, "clientVehicleIsFloating", true);
                case "awaitingTeleport" -> field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingPositionFromClient", new Vec3(24, 64, 8));
                case "delayedDisconnect" -> field(Connection.class, this.player.connection.getConnection(), "delayedDisconnect", new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal("pending")));
                case "disconnectDetails" -> field(Connection.class, this.player.connection.getConnection(), "disconnectionDetails", new net.minecraft.network.DisconnectionDetails(net.minecraft.network.chat.Component.literal("closed")));
                case "handlingFault" -> field(Connection.class, this.player.connection.getConnection(), "handlingFault", true);
                case "handledDisconnect" -> field(Connection.class, this.player.connection.getConnection(), "disconnectionHandled", true);
                case "memoryConnection" -> when(this.player.connection.getConnection().isMemoryConnection()).thenReturn(true);
                case "sleep" -> when(this.player.getSleepingPos()).thenReturn(java.util.Optional.of(new BlockPos(47, 64, 8)));
                case "sprinting" -> when(this.player.isSprinting()).thenReturn(true);
                case "sneaking" -> when(this.player.isShiftKeyDown()).thenReturn(true);
                case "swimming" -> when(this.player.isSwimming()).thenReturn(true);
                case "inWater" -> when(this.player.isInWater()).thenReturn(true);
                case "inLava" -> when(this.player.isInLava()).thenReturn(true);
                case "air" -> when(this.player.getAirSupply()).thenReturn(0);
                case "fire" -> when(this.player.getRemainingFireTicks()).thenReturn(1);
                case "forcedPose" -> when(this.player.getForcedPose()).thenReturn(Pose.SWIMMING);
                case "nonFiniteHealth" -> when(this.player.getHealth()).thenReturn(Float.NaN);
                case "nonFiniteRotation" -> when(this.player.getYRot()).thenReturn(Float.NaN);
                case "usingItem" -> when(this.player.isUsingItem()).thenReturn(true);
                case "levelAttachment" -> when(this.level.hasAttachments()).thenReturn(true);
                case "camera" -> {
                    var foreignCamera = mock(Entity.class);
                    when(this.player.getCamera()).thenReturn(foreignCamera);
                }
                case "dead" -> when(this.player.isAlive()).thenReturn(false);
                case "removed" -> when(this.player.isRemoved()).thenReturn(true);
                case "idle" -> when(this.server.getPlayerIdleTimeout()).thenReturn(1);
                case "profiler" -> {
                    var activeProfiler = new net.minecraft.util.profiling.ActiveProfiler(System::nanoTime, () -> 0, false);
                    when(this.server.getProfiler()).thenReturn(activeProfiler);
                }
                default -> throw new AssertionError(condition);
            }
            assertFalse(entity.revalidate());
            assertFalse(connection.revalidate());
            for (var phase : RegionPlayerTickCoordinator.Phase.values()) {
                var refused = this.engine.capturePlayerAdmissionPlan(this.player, phase);
                assertNull(refused.plan(), refused.toString());
                org.junit.jupiter.api.Assertions.assertNotEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.NONE, refused.refusal());
                assertEquals(0, this.engine.playerTicks().snapshot().phases().get(phase).worker());
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(RegionPlayerTickCoordinator.Phase.class)
    void unknownExtensionRefusesBeforeFreezingOriginalNetworkThreadCallbacks(RegionPlayerTickCoordinator.Phase phase) throws Exception {
        productionLocalState();
        assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD,
                this.engine.capturePlayerAdmissionPlan(this.player, phase).refusal());
        var freezes = new java.util.concurrent.atomic.AtomicInteger();
        var boundary = mock(net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate.class, invocation -> {
            if (invocation.getMethod().getName().equals("freeze")) {
                freezes.incrementAndGet();
                throw new AssertionError("Unknown NETWORK handlers must stay on their original receive thread");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        field(RegionThreadingEngine.class, this.engine, "playerNetworkGate", boundary);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        Object call = construct("RegionPlayerTickDispatch$Call", new Class<?>[] { ServerPlayer.class, RegionPlayerTickCoordinator.Phase.class, Runnable.class },
                this.player, phase, (Runnable) effects::incrementAndGet);
        long before = this.engine.playerAdmissionRefusals().get(phase.name() + ":UNKNOWN_MOD");
        assertEquals(0, invoke(this.engine, "dispatchPlayerPrefix", new Class<?>[] { List.class }, List.of(call)));
        assertEquals(before + 1, this.engine.playerAdmissionRefusals().get(phase.name() + ":UNKNOWN_MOD"), "The actual prefix reached extension refusal before any freeze");
        assertEquals(0, freezes.get());
        assertEquals(0, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().phases().get(phase).worker());
        this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), phase, effects::incrementAndGet);
        assertEquals(1, effects.get(), "The entire original serial callback still executes once");
    }
}
