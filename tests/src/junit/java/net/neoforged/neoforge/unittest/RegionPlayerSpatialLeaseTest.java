/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
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
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
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
                when(chunk.getBlockState(any())).thenAnswer(read -> this.level.getBlockState(read.getArgument(0)));
                when(chunk.getFluidState(any(BlockPos.class))).thenAnswer(read -> this.level.getFluidState(read.getArgument(0)));
                when(chunk.getBlockEntity(any(), eq(LevelChunk.EntityCreationType.IMMEDIATE))).thenAnswer(read -> this.level.getBlockEntity(read.getArgument(0)));
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
    void newlyUnsafeBlockInLeasedChunkRefusesBeforeBody() throws Exception {
        var result = (RegionPlayerSpatialPlan.Capture) invoke(RegionPlayerSpatialPlan.class, "capture",
                new Class<?>[] { RegionThreadingEngine.class, ServerPlayer.class, RegionPlayerTickCoordinator.Phase.class, boolean.class },
                this.engine, this.player, RegionPlayerTickCoordinator.Phase.CONNECTION, true);
        var plan = result.plan();
        assertNotNull(plan);
        BlockPos feet = this.player.blockPosition();
        var chunk = this.chunks.get(ChunkPos.asLong(feet));
        var section = new LevelChunkSection(new PalettedContainer<>(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), null);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[] { section });
        when(chunk.getSectionIndex(anyInt())).thenReturn(0);
        section.setBlockState(feet.getX() & 15, feet.getY() & 15, feet.getZ() & 15, Blocks.WATER.defaultBlockState());
        assertNull(reserve(plan));
        assertEquals(0, invoke(this.batch, "playerSpatialReservationCount", new Class<?>[] {}));
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

    private AutoCloseable installedBaseEnvironment(String... extraMods) {
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
        var modInfos = new java.util.ArrayList<net.neoforged.neoforgespi.language.IModInfo>();
        for (String id : java.util.stream.Stream.concat(java.util.stream.Stream.of("minecraft", "neoforge"), java.util.Arrays.stream(extraMods)).toList()) {
            var info = mock(net.neoforged.neoforgespi.language.IModInfo.class);
            when(info.getModId()).thenReturn(id);
            modInfos.add(info);
        }
        when(list.getMods()).thenReturn(modInfos);
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
        productionLocalState(net.minecraft.advancements.AdvancementRewards.Builder.experience(7).build());
    }

    private void productionLocalState(net.minecraft.advancements.AdvancementRewards reward) throws Exception {
        localState(reward);
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
        assertTrue(refused.refusal() == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM
                || refused.refusal() == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER
                || refused.refusal() == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, refused.toString());
        var original = new java.util.concurrent.atomic.AtomicInteger();
        this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.CONNECTION, original::incrementAndGet);
        assertEquals(1, original.get());
        this.engine.configurePlayerTickPlanningForTesting(false);
        assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.DEPENDENCIES,
                this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).refusal());
    }

    @Test
    void extraModIdsWithCleanSurfaceAdmitWorkerPrefixAndRecordDiagnostic() throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        packetWorkerEnvironment(() -> {});
        try (var installed = installedBaseEnvironment("example_mod", "another_clean_mod")) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.ENTITY);
            assertNotNull(capture.plan(), capture.toString());
            var ranOnWorker = new java.util.concurrent.atomic.AtomicBoolean();
            Object call = construct("RegionPlayerTickDispatch$Call", new Class<?>[] { ServerPlayer.class, RegionPlayerTickCoordinator.Phase.class, Runnable.class },
                    this.player, RegionPlayerTickCoordinator.Phase.ENTITY, (Runnable) () -> ranOnWorker.set(RegionWorkerScope.isNativeWorkerThread()));
            assertEquals(1, invoke(this.engine, "dispatchPlayerPrefix", new Class<?>[] { List.class }, List.of(call)));
            assertTrue(ranOnWorker.get(), "Player tick should execute on a native region worker with clean extra mods");
            assertEquals(1L, this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.ENTITY).worker());
            assertEquals(1L, this.engine.playerTickDiagnostics(this.server).get("admittedWithMods"));
        }
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

    @Test
    void stableStateTemplateReusesObservationsButRebuildsAfterAnInventoryChange() throws Exception {
        var local = localState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        local.advancements().forgiaCriterionListeners().removeAll(net.minecraft.advancements.CriteriaTriggers.TICK);
        doCallRealMethod().when(this.player).forgiaPlayerStateTemplate(anyInt());
        doCallRealMethod().when(this.player).forgiaPlayerStateTemplate(anyInt(), org.mockito.ArgumentMatchers.nullable(RegionPlayerStatePlan.Template.class));
        var signature = new Class<?>[] { RegionThreadingEngine.class, RegionPlayerSpatialPlan.class };
        var first = (RegionPlayerStatePlan.Capture) invoke(RegionPlayerStatePlan.class, "captureTick", signature, this.engine, capture());
        assertEquals(RegionPlayerStatePlan.Refusal.NONE, first.refusal());
        assertNotNull(this.player.forgiaPlayerStateTemplate(first.plan().phase().ordinal()));
        var second = (RegionPlayerStatePlan.Capture) invoke(RegionPlayerStatePlan.class, "captureTick", signature, this.engine, capture());
        assertEquals(RegionPlayerStatePlan.Refusal.NONE, second.refusal());
        assertNotSame(first.plan(), second.plan());
        assertSame(field(first.plan(), "inventory"), field(second.plan(), "inventory"));
        when(this.player.getInventory().getItem(8)).thenReturn(new ItemStack(Items.DIAMOND));
        var changed = (RegionPlayerStatePlan.Capture) invoke(RegionPlayerStatePlan.class, "captureTick", signature, this.engine, capture());
        assertEquals(RegionPlayerStatePlan.Refusal.NONE, changed.refusal());
        assertNotSame(field(second.plan(), "inventory"), field(changed.plan(), "inventory"));
        this.engine.invalidatePlayerTickPlans(this.player);
        assertNull(this.player.forgiaPlayerStateTemplate(changed.plan().phase().ordinal()));
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

    @Test
    void isolatedResourceProofRejectsAChangedAlias() throws Exception {
        var claims = construct("RegionPlayerPrefixClaims", new Class<?>[0]);
        var signature = new Class<?>[] { RegionPlayerTickCoordinator.Phase.class, List.class };
        var phase = RegionPlayerTickCoordinator.Phase.CONNECTION;
        Object first = new Object();
        var safe = List.of(List.of(first, first), List.of(new Object()));
        assertTrue((boolean) invoke(claims, "tryAcquire", signature, phase, safe));
        assertEquals(2, invoke(claims, "count", new Class<?>[0]));
        invoke(claims, "release", new Class<?>[0]);
        assertTrue((boolean) invoke(claims, "tryAcquire", signature, phase, safe));
        invoke(claims, "release", new Class<?>[0]);
        assertFalse((boolean) invoke(claims, "tryAcquire", signature, phase, List.of(safe.getFirst(), List.of(first))));
        assertFalse((boolean) invoke(claims, "tryAcquire", signature, phase, List.of(safe.getFirst(), safe.getFirst())));
        assertEquals(0, invoke(claims, "count", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void partiallyRejectedLanePreventsLaterGroupsFromStartingBeforeSerialFallback(boolean persistent, boolean throwsOnSubmit) throws Exception {
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
            var startup = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, 2);
            Class<?>[] signature = { RegionPlayerSpatialLease.class, List.class };
            var group = construct("RegionPlayerTickDispatch$Group", signature, lease, List.of(call));
            var rejected = construct("RegionPlayerTickDispatch$Group", signature, null, List.of(call));
            var groups = List.of(group, rejected, group, rejected); // No later group may reach its lease.
            var nextGroup = new java.util.concurrent.atomic.AtomicInteger(2);
            var work = new net.neoforged.neoforge.server.threading.region.RegionTaskBatch();
            for (int i = 0; i < 2; i++) {
                int lane = i;
                Runnable run = () -> {
                    try {
                        invoke(dispatch, "runLane", new Class<?>[] { List.class, startup.getClass(), int.class, java.util.concurrent.atomic.AtomicInteger.class }, groups, startup, lane, nextGroup);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                };
                work.add("player lane " + i, (Runnable) invoke(this.batch, "groupWork", new Class<?>[] { Runnable.class }, run));
            }
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
            assertEquals(0, ((java.util.concurrent.CountDownLatch) field(startup, "checked")).getCount());
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean) field(startup, "valid")).get());
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
        var initialRefusal = this.engine.capturePlayerAdmissionPlan(this.player, phase).refusal();
        assertTrue(initialRefusal == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM
                || initialRefusal == net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER,
                "Expected transform or listener refusal, got " + initialRefusal);
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
        String key = phase.name() + ":" + initialRefusal.name();
        long before = this.engine.playerAdmissionRefusals().get(key);
        assertEquals(0, invoke(this.engine, "dispatchPlayerPrefix", new Class<?>[] { List.class }, List.of(call)));
        assertEquals(before + 1, this.engine.playerAdmissionRefusals().get(key), "The actual prefix reached extension refusal before any freeze");
        assertEquals(0, freezes.get());
        assertEquals(0, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().phases().get(phase).worker());
        this.engine.tickPlayerSerial(this.player, this.player.connection.getConnection(), phase, effects::incrementAndGet);
        assertEquals(1, effects.get(), "The entire original serial callback still executes once");
    }

    private void packetWorkerEnvironment(Runnable before) throws Exception {
        packetWorkerEnvironment(before, false);
    }

    private void packetWorkerEnvironment(Runnable before, boolean reject) throws Exception {
        Object actual = field(this.engine, "workerPool");
        Object observed = mock(actual.getClass(), invocation -> {
            Method method = invocation.getMethod();
            method.setAccessible(true);
            if (method.getName().equals("execute")) {
                if (reject) return false;
                Runnable task = invocation.getArgument(1);
                return method.invoke(actual, invocation.getArgument(0), (Runnable) () -> {
                    try (var installed = installedBaseEnvironment()) {
                        before.run();
                        task.run();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
            return method.invoke(actual, invocation.getArguments());
        });
        field(RegionThreadingEngine.class, this.engine, "workerPool", observed);
    }

    private Inventory packetLocalState() throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        Inventory inventory = new Inventory(this.player);
        when(this.player.getInventory()).thenReturn(inventory);
        when(this.player.getUseItem()).thenReturn(ItemStack.EMPTY);
        when(this.player.getUsedItemHand()).thenReturn(net.minecraft.world.InteractionHand.MAIN_HAND);
        doCallRealMethod().when(this.player).stopUsingItem();
        doCallRealMethod().when(this.player).resetLastActionTime();
        return inventory;
    }

    private record DeclaredPayload(int value) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        private static final Type<DeclaredPayload> TYPE = new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("declared_payload_test", "local"));
        @Override
        public Type<DeclaredPayload> type() { return TYPE; }
    }

    @SuppressWarnings("unchecked")
    private AutoCloseable declarePayload(net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<DeclaredPayload> handler) throws Exception {
        var f = net.neoforged.neoforge.network.registration.NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");
        f.setAccessible(true);
        var registry = (Map<net.minecraft.network.ConnectionProtocol, Map<net.minecraft.resources.ResourceLocation, net.neoforged.neoforge.network.registration.PayloadRegistration<?>>>) f.get(null);
        var plays = registry.get(net.minecraft.network.ConnectionProtocol.PLAY);
        var prior = plays.put(DeclaredPayload.TYPE.id(), new net.neoforged.neoforge.network.registration.PayloadRegistration<>(DeclaredPayload.TYPE,
                net.minecraft.network.codec.StreamCodec.unit(new DeclaredPayload(0)), handler, List.of(net.minecraft.network.ConnectionProtocol.PLAY),
                java.util.Optional.of(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), "1", true));
        return () -> { if (prior == null) plays.remove(DeclaredPayload.TYPE.id()); else plays.put(DeclaredPayload.TYPE.id(), prior); };
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,success", "true,success", "false,failure", "true,failure", "false,reject", "true,reject", "false,off", "true,off" })
    void declaredPayloadRunsOnlyExactHandlerUnderOriginalLeaseAndNeverApprovesModTicks(boolean persistent, String outcome) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        when(this.player.connection.getPlayer()).thenReturn(this.player);
        if (outcome.equals("reject")) packetWorkerEnvironment(() -> {}, true);
        if (outcome.equals("off")) this.engine.configurePlayerTickPlanningForTesting(false);
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        var escapedContext = new AtomicReference<net.neoforged.neoforge.network.handling.IRegionPayloadContext>();
        var failure = new IllegalArgumentException("declared side effect then throw");
        boolean expectedWorker = !outcome.equals("reject") && !outcome.equals("off");
        var declaration = new net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<>(DeclaredPayload.TYPE, DeclaredPayload.class, (payload, context) -> {
            assertEquals(expectedWorker, RegionWorkerScope.isNativeWorkerThread());
            assertEquals(expectedWorker, context.isRegionWorker());
            assertSame(this.player, context.player());
            assertEquals(expectedWorker, RegionPlayerStateScope.current() != null);
            assertEquals(expectedWorker, this.engine.playerNetworkGate().snapshot().frozen());
            assertEquals(73, payload.value());
            bodies.incrementAndGet();
            escapedContext.set(context);
            if (outcome.equals("failure")) throw failure;
        });
        var packet = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(new DeclaredPayload(73));
        var context = new net.neoforged.neoforge.network.handling.ServerPayloadContext(this.player.connection, DeclaredPayload.TYPE.id());
        try (var registered = declarePayload(declaration);
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
            // Intentionally no installedBaseEnvironment(): unknown test extensions cannot authorize ticks.
            if (outcome.equals("failure")) assertSame(failure, assertThrows(IllegalArgumentException.class, () -> declaration.handle((DeclaredPayload) packet.payload(), context)));
            else declaration.handle((DeclaredPayload) packet.payload(), context);
        }
        assertEquals(1, bodies.get());
        assertThrows(IllegalStateException.class, () -> escapedContext.get().player());
        assertNull(RegionPlayerStateScope.current());
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertTrue(((Set<?>) field(this.engine, "activeTickBatches")).isEmpty());
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
        assertEquals(expectedWorker ? 1 : 0, snapshot.workers());
        assertEquals(outcome.equals("failure") ? 1 : 0, snapshot.failed());
        assertTrue(this.engine.playerTicks().snapshot().phases().values().stream().allMatch(counts -> counts.worker() == 0));
        if (!outcome.equals("off")) {
            assertNull(this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.ENTITY).plan(), "Payload declaration cannot approve an unknown mod's full player tick");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "success", "failure", "unregistered", "missingReceipt", "retire", "close", "oldInstance", "configuration", "disconnect" })
    void declaredPayloadOriginalQueueCancelsOldSessionsAndCleansReferences(String outcome) throws Exception {
        packetLocalState();
        var listener = this.player.connection;
        var connection = listener.getConnection();
        when(listener.getPlayer()).thenReturn(this.player);
        doReturn(this.server).when(listener).getMainThreadEventLoop();
        var queue = new ArrayList<Runnable>();
        doAnswer(call -> {
            Runnable task = call.getArgument(0);
            queue.add(task);
            return null;
        }).when(this.server).executeIfPossible(any(Runnable.class));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var sentinel = new IllegalArgumentException("expected declared queued failure");
        var declaration = new net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<>(DeclaredPayload.TYPE, DeclaredPayload.class, (payload, context) -> {
            assertEquals(!outcome.equals("unregistered") && !outcome.equals("missingReceipt"), context.isRegionWorker());
            calls.incrementAndGet();
            if (outcome.equals("failure")) throw sentinel;
        });
        var packet = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(new DeclaredPayload(73));
        var context = new net.neoforged.neoforge.network.handling.ServerPayloadContext(listener, DeclaredPayload.TYPE.id());
        try (var registration = declarePayload(declaration)) {
            var escaped = new AtomicReference<Throwable>();
            Thread netty = new Thread(() -> {
                try (var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(outcome.equals("missingReceipt") ? null :
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(connection, packet, 1))) {
                    declaration.handle((DeclaredPayload) packet.payload(), context);
                } catch (Throwable failure) { escaped.set(failure); }
            }, "declared payload original receive");
            netty.start(); netty.join(5000);
            assertFalse(netty.isAlive()); assertNull(escaped.get());
            assertEquals(0, calls.get()); assertEquals(1, queue.size());
            switch (outcome) {
                case "retire" -> invoke(this.engine, "retirePlayerPackets", new Class<?>[] { Connection.class }, connection);
                case "close" -> invoke(field(this.engine, "playerPackets"), "close", new Class<?>[0]);
                case "oldInstance" -> listener.player = mock(ServerPlayer.class);
                case "configuration" -> when(connection.getPacketListener()).thenReturn(mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class));
                case "disconnect" -> when(connection.isConnected()).thenReturn(false);
                case "unregistered" -> registration.close();
            }
            queue.forEach(Runnable::run);
            assertEquals(Set.of("success", "failure", "unregistered", "missingReceipt").contains(outcome) ? 1 : 0, calls.get());
            if (!outcome.equals("missingReceipt")) queue.forEach(Runnable::run);
            assertEquals(Set.of("success", "failure", "unregistered", "missingReceipt").contains(outcome) ? 1 : 0, calls.get(), "No replay after return, failure or cancellation");
            var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
            assertEquals(0, invoke(snapshot.tasks(), "pending", new Class<?>[0]));
            assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
            assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "success", "retire", "close", "configuration", "rejection", "stopped" })
    void declaredMainEscapeWaitsForPhysicalJoinAndCannotRetainRetiredPlayer(String outcome) throws Exception {
        packetLocalState();
        var listener = this.player.connection;
        when(listener.getPlayer()).thenReturn(this.player);
        var tasks = new ArrayList<Runnable>();
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        var future = new AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
        var rejection = new java.util.concurrent.RejectedExecutionException("original executor rejected MAIN escape");
        doAnswer(call -> {
            assertTrue(RegionWorkerScope.isNativeWorkerThread());
            if (outcome.equals("rejection")) throw rejection;
            Runnable task = call.getArgument(0);
            tasks.add(task);
            return null;
        }).when(this.server).executeIfPossible(any(Runnable.class));
        if (outcome.equals("stopped")) {
            // Real MinecraftServer.scheduleExecutables returns false once stopped; submit then
            // inlines on the native worker. The declared MAIN escape must reject before that path.
            when(this.server.isStopped()).thenReturn(true);
            doCallRealMethod().when(this.server).submit(any(Runnable.class));
            doCallRealMethod().when(this.server).scheduleExecutables();
            doCallRealMethod().when(this.server).executeIfPossible(any(Runnable.class));
        }
        var declaration = new net.neoforged.neoforge.network.handling.RegionThreadPayloadHandler<>(DeclaredPayload.TYPE, DeclaredPayload.class, (payload, context) -> {
            bodies.incrementAndGet();
            assertTrue(context.isRegionWorker());
            future.set(context.enqueueWork(main -> {
                assertSame(this.publishing, Thread.currentThread());
                assertNull(RegionPlayerStateScope.current());
                assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
                assertSame(this.player, main.player());
                callbacks.incrementAndGet();
            }));
            assertFalse(future.get().isDone());
            assertEquals(0, callbacks.get());
        });
        var packet = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(new DeclaredPayload(73));
        try (var registration = declarePayload(declaration);
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(listener.getConnection(), packet, 1))) {
            Runnable entry = () -> declaration.handle((DeclaredPayload) packet.payload(), new net.neoforged.neoforge.network.handling.ServerPayloadContext(listener, DeclaredPayload.TYPE.id()));
            if (outcome.equals("stopped")) assertThrows(java.util.concurrent.RejectedExecutionException.class, entry::run);
            else if (outcome.equals("rejection")) assertSame(rejection, assertThrows(java.util.concurrent.RejectedExecutionException.class, entry::run));
            else entry.run();
            assertEquals(1, bodies.get());
            if (outcome.equals("retire")) invoke(this.engine, "retirePlayerPackets", new Class<?>[] { Connection.class }, listener.getConnection());
            if (outcome.equals("close")) invoke(field(this.engine, "playerPackets"), "close", new Class<?>[0]);
            if (outcome.equals("configuration")) when(listener.getConnection().getPacketListener()).thenReturn(mock(net.minecraft.server.network.ServerConfigurationPacketListenerImpl.class));
            tasks.forEach(Runnable::run);
            assertEquals(outcome.equals("success") ? 1 : 0, callbacks.get());
            if (!outcome.equals("rejection") && !outcome.equals("stopped")) assertTrue(future.get().isDone());
            var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
            assertEquals(0, invoke(snapshot.tasks(), "pending", new Class<?>[0]));
        }
    }

    private Runnable enqueueOriginalPacket(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket packet) throws Exception {
        List<Runnable> queued = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            queued.add(call.getArgument(0));
            return null;
        }).when(this.server).executeIfPossible(any());
        when(this.player.connection.shouldHandleMessage(packet)).thenReturn(true);
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread netty = new Thread(() -> {
            try (var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
                packet.handle(this.player.connection);
            } catch (Throwable failure) {
                escaped.set(failure);
            }
        }, "original packet receive test");
        netty.start();
        netty.join(5000);
        assertFalse(netty.isAlive());
        assertSame(net.minecraft.server.RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD, escaped.get());
        assertEquals(1, queued.size());
        return queued.getFirst();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void ownerPacketRetainsOriginalQueuePositionAndPublisherErrorBoundary(boolean persistent, boolean fail) throws Exception {
        if (persistent) initialize(true);
        Inventory inventory = packetLocalState();
        packetWorkerEnvironment(() -> {});
        var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var sentinel = new IllegalArgumentException("after original selected slot");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var errors = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            // The first receive entry only schedules; the complete re-entry must be the original worker.
            if (RegionWorkerScope.isNativeWorkerThread()) calls.incrementAndGet();
            call.callRealMethod();
            if (RegionWorkerScope.isNativeWorkerThread()) {
                assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.isCurrent(packet, this.player.connection));
                assertFalse(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.isCurrent(
                        new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1), this.player.connection));
                assertEquals(1, inventory.selected);
                if (fail) throw sentinel;
            }
            return null;
        }).when(this.player.connection).handleSetCarriedItem(packet);
        doAnswer(call -> {
            assertSame(this.publishing, Thread.currentThread());
            assertSame(sentinel, call.getArgument(1));
            assertNull(RegionPlayerStateScope.current());
            assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
            assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
            errors.incrementAndGet();
            return null;
        }).when(this.player.connection).onPacketError(eq(packet), any());
        Runnable queued = enqueueOriginalPacket(packet);
        assertEquals(0, inventory.selected, "Normal receive thread cannot change selected slot");
        try (var installed = installedBaseEnvironment()) {
            queued.run();
        }
        assertEquals(1, calls.get(), "A started original packet is never replayed on publisher");
        assertEquals(fail ? 1 : 0, errors.get());
        assertEquals(1, inventory.selected, "The next original queue task observes the complete effect");
        assertFalse(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.isCurrent(packet, this.player.connection));
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertTrue(((Set<?>) field(this.engine, "activeTickBatches")).isEmpty());
        assertTrue(this.engine.playerTicks().snapshot().phases().values().stream().allMatch(counts -> counts.started() == 0));
        var snapshots = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
        assertEquals(1, snapshots.workers());
        assertEquals(fail ? 1 : 0, snapshots.failed());
        assertEquals(1, snapshots.recent().size());
        assertTrue(snapshots.recent().getFirst().returnNanos() >= snapshots.recent().getFirst().startNanos());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,target", "true,target", "false,listener", "true,listener" })
    void ownerPacketLateChangesRefuseBeforeOriginalHandler(boolean persistent, String change) throws Exception {
        if (persistent) initialize(true);
        Inventory inventory = packetLocalState();
        packetWorkerEnvironment(() -> {
            if (change.equals("target")) inventory.setItem(1, new ItemStack(net.minecraft.world.item.Items.DIAMOND));
            else this.player.connection.player = mock(ServerPlayer.class);
        });
        var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        try (var installed = installedBaseEnvironment()) {
            assertEquals(false, invoke(this.engine, "tryRunPlayerPacket",
                    new Class<?>[] { net.minecraft.network.protocol.Packet.class, ServerGamePacketListenerImpl.class, Runnable.class },
                    packet, this.player.connection, (Runnable) bodies::incrementAndGet));
        }
        assertEquals(0, bodies.get());
        assertEquals(0, inventory.selected);
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "unknownMod", "occupied", "invalid", "off", "unknownPacket" })
    void unapprovedOwnerPacketsRefuseWithoutNetworkFreeze(String kind) throws Exception {
        Inventory inventory = packetLocalState();
        if (kind.equals("occupied")) inventory.setItem(1, new ItemStack(net.minecraft.world.item.Items.DIAMOND));
        if (kind.equals("off")) this.engine.configurePlayerTickPlanningForTesting(false);
        var freezes = new java.util.concurrent.atomic.AtomicInteger();
        var boundary = mock(net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate.class, call -> {
            if (call.getMethod().getName().equals("freeze")) freezes.incrementAndGet();
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        field(RegionThreadingEngine.class, this.engine, "playerNetworkGate", boundary);
        var listener = this.player.connection;
        java.util.function.BooleanSupplier run = () -> kind.equals("unknownPacket")
                ? net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(1), listener)
                : net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(kind.equals("invalid") ? 9 : 1), listener);
        if (kind.equals("unknownMod")) assertFalse(run.getAsBoolean());
        else try (var installed = installedBaseEnvironment()) {
            assertFalse(run.getAsBoolean());
        }
        assertEquals(0, freezes.get());
        assertEquals(0, inventory.selected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void deferredNetworkPacketUsesCurrentOwnerWithoutSecondEnqueue(boolean persistent, boolean fail) throws Exception {
        if (persistent) initialize(true);
        Inventory inventory = packetLocalState();
        packetWorkerEnvironment(() -> {});
        var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var sentinel = new IllegalArgumentException("after original deferred packet effect");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertTrue(RegionWorkerScope.isNativeWorkerThread());
            calls.incrementAndGet();
            call.callRealMethod();
            if (fail) throw sentinel;
            return null;
        }).when(this.player.connection).handleSetCarriedItem(packet);
        doAnswer(call -> {
            throw new AssertionError("Deferred original callback cannot enqueue behind later packets");
        }).when(this.server).executeIfPossible(any());
        var dispatch = Connection.class.getDeclaredMethod("genericsFtw", net.minecraft.network.protocol.Packet.class, net.minecraft.network.PacketListener.class);
        dispatch.setAccessible(true);
        try (var installed = installedBaseEnvironment();
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
            if (fail) assertSame(sentinel, assertThrows(InvocationTargetException.class, () -> dispatch.invoke(null, packet, this.player.connection)).getCause());
            else dispatch.invoke(null, packet, this.player.connection);
        }
        assertEquals(1, calls.get());
        assertEquals(1, inventory.selected);
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void rejectedOwnerPacketRunsOriginalServerHandlerOnceAfterLeaseCleanup(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        Inventory inventory = packetLocalState();
        packetWorkerEnvironment(() -> {}, true);
        var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var bodies = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (Thread.currentThread() == this.publishing) {
                assertNull(RegionPlayerStateScope.current());
                assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
                assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
                bodies.incrementAndGet();
            }
            return call.callRealMethod();
        }).when(this.player.connection).handleSetCarriedItem(packet);
        Runnable queued = enqueueOriginalPacket(packet);
        try (var installed = installedBaseEnvironment()) {
            queued.run();
        }
        assertEquals(1, bodies.get());
        assertEquals(1, inventory.selected);
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
        assertEquals(0, snapshot.workers());
        assertEquals(1, snapshot.refused());
        assertFalse(snapshot.recent().getFirst().started());
    }

    private void packetMovementState() throws Exception {
        packetLocalState();
        when(this.level.noCollision(eq(this.player), any(AABB.class))).thenReturn(true);
        for (String name : List.of("firstGoodX", "lastGoodX")) field(ServerGamePacketListenerImpl.class, this.player.connection, name, this.player.getX());
        for (String name : List.of("firstGoodY", "lastGoodY")) field(ServerGamePacketListenerImpl.class, this.player.connection, name, this.player.getY());
        for (String name : List.of("firstGoodZ", "lastGoodZ")) field(ServerGamePacketListenerImpl.class, this.player.connection, name, this.player.getZ());
        field(ServerGamePacketListenerImpl.class, this.player.connection, "tickCount", 1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void originalMovementTrackingServiceSynchronouslyReturnsOnPublisherWithoutQueuePump(boolean persistent, boolean fail) throws Exception {
        if (persistent) initialize(true);
        packetMovementState();
        packetWorkerEnvironment(() -> {});
        var map = mock(net.minecraft.server.level.ChunkMap.class);
        field(ServerChunkCache.class, this.level.getChunkSource(), "chunkMap", map);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var sentinel = new IllegalArgumentException("original tracking failed");
        doAnswer(call -> {
            assertSame(this.publishing, Thread.currentThread());
            assertNull(RegionPlayerStateScope.current());
            assertTrue(this.engine.playerNetworkGate().snapshot().frozen());
            assertEquals(1, sequence.getAndIncrement());
            if (fail) throw sentinel;
            return null;
        }).when(map).move(this.player);
        var packet = new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(47.75, 64, 8.5, true);
        Runnable original = () -> {
            assertTrue(RegionWorkerScope.isNativeWorkerThread());
            assertEquals(0, sequence.getAndIncrement());
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.moveTrackingOnPublisher(map, this.player));
            assertEquals(2, sequence.getAndIncrement());
        };
        try (var installed = installedBaseEnvironment()) {
            if (fail) {
                var thrown = assertThrows(IllegalArgumentException.class, () -> invoke(this.engine, "tryRunPlayerPacket",
                        new Class<?>[] { net.minecraft.network.protocol.Packet.class, ServerGamePacketListenerImpl.class, Runnable.class }, packet, this.player.connection, original));
                assertSame(sentinel, thrown);
            } else assertEquals(true, invoke(this.engine, "tryRunPlayerPacket",
                    new Class<?>[] { net.minecraft.network.protocol.Packet.class, ServerGamePacketListenerImpl.class, Runnable.class }, packet, this.player.connection, original));
        }
        assertEquals(fail ? 2 : 3, sequence.get());
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void exactOriginalTeleportConfirmationUpdatesListenerInsideCurrentOwner(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        packetMovementState();
        when(this.player.forgiaStationarySpatialInputs()).thenReturn(false); // Vanilla teleport may clear onGround before the matching ack.
        packetWorkerEnvironment(() -> {});
        var packet = new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(41);
        field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingTeleport", 41);
        field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingPositionFromClient", this.player.position());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertTrue(RegionWorkerScope.isNativeWorkerThread());
            calls.incrementAndGet();
            return call.callRealMethod();
        }).when(this.player.connection).handleAcceptTeleportPacket(packet);
        try (var installed = installedBaseEnvironment();
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(packet, this.player.connection));
        }
        assertEquals(1, calls.get());
        assertNull(field(this.player.connection, "awaitingPositionFromClient"));
        assertEquals(this.player.getX(), field(this.player.connection, "lastGoodX"));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalLocalInventoryClickRetainsSynchronousCursorAndResourceResult(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        Inventory inventory = packetLocalState();
        inventory.setItem(9, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5));
        var menu = new net.minecraft.world.inventory.InventoryMenu(inventory, true, this.player);
        field(net.minecraft.world.entity.player.Player.class, this.player, "inventoryMenu", menu);
        this.player.containerMenu = menu;
        when(this.level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        packetWorkerEnvironment(() -> {});
        var changed = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack>();
        changed.put(9, ItemStack.EMPTY);
        var packet = new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(0, 0, 9, 0,
                net.minecraft.world.inventory.ClickType.PICKUP, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 5), changed);
        doCallRealMethod().when(this.player.connection).handleContainerClick(packet);
        try (var installed = installedBaseEnvironment();
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(packet, this.player.connection));
        }
        assertEquals(5, menu.getCarried().getCount());
        assertTrue(inventory.getItem(9).isEmpty());
        assertEquals(0, this.player.totalExperience);
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalReceiveSequenceRejectsReplayedReceiptAndRetiresWithoutLiveConnection(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        packetWorkerEnvironment(() -> {});
        var first = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var second = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(2);
        doCallRealMethod().when(this.player.connection).handleSetCarriedItem(any());
        var router = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.class;
        try (var installed = installedBaseEnvironment()) {
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(first, this.player.connection,
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), first, 7)));
            assertFalse(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(second, this.player.connection,
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), second, 7)));
            assertEquals(1, this.player.getInventory().selected);
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(second, this.player.connection,
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), second, 9)));
        }
        assertEquals(2, this.player.getInventory().selected);
        net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate.retireConnection(this.player.connection.getConnection());
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
        assertEquals(0, snapshot.connections());
        assertEquals(2, snapshot.workers());
        assertEquals(List.of(7L, 7L, 9L), snapshot.recent().stream().map(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Execution::receiveSequence).toList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,false", "false,true", "true,false", "true,true" })
    void originalFilteredChatCompletionRunsOnceWithSynchronousPublisherBroadcastOrCancels(boolean persistent, boolean cancel) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        packetWorkerEnvironment(() -> {});
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var broadcasts = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.minecraft.server.network.FilteredText> original = text -> {
            assertTrue(RegionWorkerScope.isNativeWorkerThread());
            assertEquals("original", text.raw());
            calls.incrementAndGet();
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatBroadcastOnPublisher(this.player.connection,
                    net.minecraft.network.chat.PlayerChatMessage.unsigned(this.player.getUUID(), text.raw()), () -> {
                        assertSame(this.publishing, Thread.currentThread());
                        assertNull(RegionPlayerStateScope.current());
                        broadcasts.incrementAndGet();
                    }));
            assertEquals(1, broadcasts.get());
        };
        java.util.function.Consumer<net.minecraft.server.network.FilteredText> callback;
        try (var installed = installedBaseEnvironment()) {
            callback = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatCompletion(this.player.connection, original);
            org.junit.jupiter.api.Assertions.assertNotSame(original, callback);
            if (cancel) net.neoforged.neoforge.server.threading.region.RegionPlayerNetworkGate.retireConnection(this.player.connection.getConnection());
            callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
            callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
        }
        assertEquals(cancel ? 0 : 1, calls.get());
        assertEquals(cancel ? 0 : 1, broadcasts.get());
        assertNull(field(callback, "original"));
        assertNull(field(callback, "listener"));
        assertNull(field(callback, "player"));
        assertNull(field(callback, "connection"));
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(field(this.engine, "playerPackets"), "snapshot", new Class<?>[0]);
        assertEquals(0, snapshot.chatPending());
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void sharedSingleChestQuickMoveUsesCompleteOriginalPacketAndConservesItems(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        var chest = singleChest();
        var menu = playerChestMenu(chest);
        when(this.level.enabledFeatures()).thenReturn(net.minecraft.world.flag.FeatureFlags.VANILLA_SET);
        packetWorkerEnvironment(() -> {});
        var changed = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<ItemStack>();
        var packet = new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(1, menu.getStateId(), 0, 0,
                net.minecraft.world.inventory.ClickType.QUICK_MOVE, ItemStack.EMPTY, changed);
        doCallRealMethod().when(this.player.connection).handleContainerClick(packet);
        try (var installed = installedBaseEnvironment();
                var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                        net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1))) {
            assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(packet, this.player.connection));
        }
        assertTrue(chest.getItem(0).isEmpty());
        assertEquals(11, this.player.getInventory().items.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(menu.getCarried().isEmpty());
        assertEquals(0, this.player.totalExperience);
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "far", "vertical", "nan", "airborne", "lastGood", "dimension", "step", "awaiting", "collision" })
    void unboundedOrStaleMovementRefusesBeforeAnyOriginalEffect(String change) throws Exception {
        packetMovementState();
        net.minecraft.network.protocol.Packet<?> packet = switch (change) {
            case "far" -> new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(49, 64, 8.5, true);
            case "vertical" -> new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(47.75, 65, 8.5, true);
            case "nan" -> new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(Double.NaN, 64, 8.5, true);
            case "airborne" -> new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(47.75, 64, 8.5, false);
            default -> new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(47.75, 64, 8.5, true);
        };
        switch (change) {
            case "collision" -> {
                packetWorkerEnvironment(() -> {});
                when(this.level.noCollision(eq(this.player), any(AABB.class))).thenReturn(false);
            }
            case "lastGood" -> field(ServerGamePacketListenerImpl.class, this.player.connection, "lastGoodX", 40D);
            case "dimension" -> when(this.player.isChangingDimension()).thenReturn(true);
            case "step" -> this.player.moveDist = 100;
            case "awaiting" -> field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingPositionFromClient", this.player.position());
        }
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        try (var installed = installedBaseEnvironment()) {
            assertEquals(false, invoke(this.engine, "tryRunPlayerPacket",
                    new Class<?>[] { net.minecraft.network.protocol.Packet.class, ServerGamePacketListenerImpl.class, Runnable.class }, packet, this.player.connection, (Runnable) effects::incrementAndGet));
        }
        assertEquals(0, effects.get());
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "id", "destination", "dimension" })
    void unknownTeleportConfirmationKeepsCompleteOriginalSerialPath(String change) throws Exception {
        packetMovementState();
        field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingTeleport", 41);
        field(ServerGamePacketListenerImpl.class, this.player.connection, "awaitingPositionFromClient", change.equals("destination") ? this.player.position().add(1, 0, 0) : this.player.position());
        if (change.equals("dimension")) when(this.player.isChangingDimension()).thenReturn(true);
        var packet = new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(change.equals("id") ? 40 : 41);
        try (var installed = installedBaseEnvironment()) {
            assertEquals(false, invoke(this.engine, "tryRunPlayerPacket",
                    new Class<?>[] { net.minecraft.network.protocol.Packet.class, ServerGamePacketListenerImpl.class, Runnable.class }, packet, this.player.connection, (Runnable) () -> {
                        throw new AssertionError("Unapproved confirmation started");
                    }));
        }
        assertNotNull(field(this.player.connection, "awaitingPositionFromClient"));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "packet", "listener", "player", "connection", "zero", "negative" })
    void staleReceiveEnvelopeCannotAcquireCurrentPlayerPermission(String change) throws Exception {
        packetLocalState();
        var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1);
        var received = new net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Received(
                change.equals("connection") ? mock(Connection.class) : this.player.connection.getConnection(),
                change.equals("packet") ? new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1) : packet,
                change.equals("listener") ? mock(ServerGamePacketListenerImpl.class) : this.player.connection,
                change.equals("player") ? mock(ServerPlayer.class) : this.player,
                change.equals("zero") ? 0 : change.equals("negative") ? -1 : 1);
        try (var installed = installedBaseEnvironment()) {
            assertFalse(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.tryHandle(packet, this.player.connection, received));
        }
        assertEquals(0, this.player.getInventory().selected);
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertTrue(((Set<?>) field(this.engine, "activeTickBatches")).isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,failure", "true,failure", "false,close", "true,close", "false,stale", "true,stale", "false,thread", "true,thread" })
    void filteredContinuationFailureShutdownStaleIdentityAndWrongThreadReleaseReferences(boolean persistent, String outcome) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        packetWorkerEnvironment(() -> {});
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var sentinel = new IllegalArgumentException("original broadcast after effect");
        var router = field(this.engine, "playerPackets");
        java.util.function.Consumer<net.minecraft.server.network.FilteredText> callback;
        try (var installed = installedBaseEnvironment()) {
            callback = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatCompletion(this.player.connection, text -> {
                calls.incrementAndGet();
                if (outcome.equals("stale")) {
                    assertSame(this.publishing, Thread.currentThread());
                    assertFalse(RegionWorkerScope.isNativeWorkerThread());
                } else {
                    assertTrue(RegionWorkerScope.isNativeWorkerThread());
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatBroadcastOnPublisher(this.player.connection,
                            net.minecraft.network.chat.PlayerChatMessage.unsigned(this.player.getUUID(), text.raw()), () -> {
                                assertSame(this.publishing, Thread.currentThread());
                                throw sentinel;
                            });
                }
            });
            if (outcome.equals("close")) invoke(router, "close", new Class<?>[0]);
            if (outcome.equals("stale")) when(this.player.connection.getConnection().getPacketListener()).thenReturn(mock(ServerGamePacketListenerImpl.class));
            if (outcome.equals("thread")) when(this.server.isSameThread()).thenReturn(false);
            if (outcome.equals("failure")) assertSame(sentinel, assertThrows(IllegalArgumentException.class, () -> callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"))));
            else if (outcome.equals("thread")) assertThrows(IllegalStateException.class, () -> callback.accept(net.minecraft.server.network.FilteredText.passThrough("original")));
            else callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
            callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
        }
        assertEquals(outcome.equals("failure") || outcome.equals("stale") ? 1 : 0, calls.get());
        for (String name : List.of("engine", "issuer", "listener", "player", "connection", "original", "receipt")) assertNull(field(callback, name), name);
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(router, "snapshot", new Class<?>[0]);
        assertEquals(0, snapshot.chatPending());
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    @Test
    void originalPacketUtilsExecutorDoesNotAccumulateEmptyTasksAfterExplicitTerminal() throws Exception {
        packetLocalState();
        var queued = new CopyOnWriteArrayList<Runnable>();
        doAnswer(call -> {
            queued.add(call.getArgument(0));
            return null;
        }).when(this.server).executeIfPossible(any());
        doCallRealMethod().when(this.player.connection).handleSetCarriedItem(any());
        var failure = new AtomicReference<Throwable>();
        Thread receiver = new Thread(() -> {
            try {
                for (int ordinal = 1; ordinal <= 4200; ordinal++) {
                    var packet = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(0);
                    try (var arrival = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.receive(
                            net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, ordinal))) {
                        assertSame(net.minecraft.server.RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD,
                                assertThrows(net.minecraft.server.RunningOnDifferentThreadException.class, () -> packet.handle(this.player.connection)));
                    }
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "original PacketUtils bounded receive");
        receiver.start();
        receiver.join(10000);
        assertFalse(receiver.isAlive());
        assertNull(failure.get());
        assertEquals(4097, queued.size(), "4096 accepted bodies plus one terminal; later packets cannot enqueue empty executor tasks");
        var router = field(this.engine, "playerPackets");
        invoke(router, "close", new Class<?>[0]);
        for (Runnable task : queued) task.run();
        var snapshot = (net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.Snapshot) invoke(router, "snapshot", new Class<?>[0]);
        assertEquals(0, invoke(snapshot.tasks(), "pending", new Class<?>[0]));
        assertEquals(0, invoke(snapshot.tasks(), "terminals", new Class<?>[0]));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,spam", "true,spam", "false,signed", "true,signed" })
    void unauditedChatDisconnectAndSignedRecipientPathsRemainOriginalSerial(boolean persistent, String kind) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        packetWorkerEnvironment(() -> {});
        var worker = new java.util.concurrent.atomic.AtomicBoolean();
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Consumer<net.minecraft.server.network.FilteredText> original = value -> {
            worker.set(RegionWorkerScope.isNativeWorkerThread());
            effects.incrementAndGet();
        };
        var packet = mock(net.minecraft.network.protocol.game.ServerboundChatPacket.class);
        if (kind.equals("signed")) when(packet.signature()).thenReturn(new net.minecraft.network.chat.MessageSignature(new byte[256]));
        else field(ServerGamePacketListenerImpl.class, this.player.connection, "chatSpamTickCount", 181);
        try (var installed = installedBaseEnvironment()) {
            var callback = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatCompletion(this.player.connection,
                    net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.received(this.player.connection.getConnection(), packet, 1), original);
            if (kind.equals("signed")) assertSame(original, callback, "Signed chat can disconnect an uncaptured recipient while actor lease is parked");
            callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
        }
        assertEquals(1, effects.get());
        assertFalse(worker.get(), "A callback that can synchronously retire a connection cannot enter this bounded worker protocol");
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void unsignedChatAtExactNonDisconnectSpamBoundaryRetainsOriginalCounter(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        packetLocalState();
        packetWorkerEnvironment(() -> {});
        field(ServerGamePacketListenerImpl.class, this.player.connection, "chatSpamTickCount", 180);
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        try (var installed = installedBaseEnvironment()) {
            var callback = net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatCompletion(this.player.connection, value -> {
                assertTrue(RegionWorkerScope.isNativeWorkerThread());
                effects.incrementAndGet();
                assertTrue(net.neoforged.neoforge.server.threading.region.RegionPlayerPacketRouter.chatBroadcastOnPublisher(this.player.connection,
                        net.minecraft.network.chat.PlayerChatMessage.unsigned(this.player.getUUID(), value.raw()), () -> {
                            assertSame(this.publishing, Thread.currentThread());
                            try {
                                invoke(this.player.connection, "detectRateSpam", new Class<?>[0]);
                            } catch (Exception problem) {
                                throw new IllegalStateException(problem);
                            }
                        }));
            });
            callback.accept(net.minecraft.server.network.FilteredText.passThrough("original"));
        }
        assertEquals(1, effects.get());
        assertEquals(200, field(this.player.connection, "chatSpamTickCount"));
        assertFalse(this.engine.playerNetworkGate().snapshot().frozen());
        assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
    }

    private net.minecraft.server.level.ChunkMap xpWatcherMap(Entity orb,
            net.minecraft.server.network.ServerPlayerConnection... watchers) throws Exception {
        var map = mock(net.minecraft.server.level.ChunkMap.class);
        field(net.minecraft.server.level.ChunkMap.class, map, "level", this.level);
        Class<?> type = Class.forName("net.minecraft.server.level.ChunkMap$TrackedEntity");
        Object tracked = mock(type);
        field(type, tracked, "entity", orb);
        field(type, tracked, "seenBy", new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(List.of(watchers)));
        var entities = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
        entities.put(orb.getId(), tracked);
        field(net.minecraft.server.level.ChunkMap.class, map, "entityMap", entities);
        field(ServerChunkCache.class, this.level.getChunkSource(), "chunkMap", map);
        when(map.forgiaHasExclusiveEntityWatcher(any(), any())).thenCallRealMethod();
        return map;
    }

    private net.minecraft.world.entity.ExperienceOrb mergedXpOrb(int count, int value) throws Exception {
        var orb = mock(net.minecraft.world.entity.ExperienceOrb.class);
        field(net.minecraft.world.entity.ExperienceOrb.class, orb, "count", count);
        field(net.minecraft.world.entity.ExperienceOrb.class, orb, "value", value);
        when(orb.getValue()).thenCallRealMethod();
        doReturn(net.minecraft.world.entity.EntityType.EXPERIENCE_ORB).when(orb).getType();
        this.add(orb, 48.5, 8.5, new AABB(48.25, 64, 8.25, 48.75, 64.5, 8.75));
        xpWatcherMap(orb, this.player.connection);
        return orb;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalMergedXpPickupRunsInsideNativeOwnerAndFinalPickupHasCapturedPermission(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var orb = mergedXpOrb(2, 7);
        doCallRealMethod().when(orb).playerTouch(this.player);
        when(this.player.getItemBySlot(any())).thenReturn(ItemStack.EMPTY);
        doCallRealMethod().when(this.player).take(orb, 1);
        var packets = new java.util.concurrent.atomic.AtomicInteger();
        var chunkSource = this.level.getChunkSource();
        doAnswer(call -> {
            assertNotNull(RegionPlayerStateScope.current());
            assertSame(orb, call.getArgument(0));
            var packet = (net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket) call.getArgument(1);
            assertEquals(orb.getId(), packet.getItemId());
            assertEquals(this.player.getId(), packet.getPlayerId());
            packets.incrementAndGet();
            return null;
        }).when(chunkSource).broadcast(eq(orb), org.mockito.ArgumentMatchers.<net.minecraft.network.protocol.Packet<?>>any());
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.NONE, capture.refusal(), capture.detail());
            try (var lease = reserve(capture.plan().spatial())) {
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(capture.plan().state())));
                worker(() -> {
                    try (var observedBase = installedBaseEnvironment(); var space = lease.open(); var owner = lease.openPlayerState(capture.plan().state())) {
                        orb.playerTouch(this.player);
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            assertEquals(7, this.player.totalExperience);
            assertEquals(1, field(orb, "count"));
            assertEquals(1, packets.get());
            assertTrue(this.effects.isEmpty(), "Non-final original pickup has no storage removal");
            var finalPickup = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(finalPickup.plan(), finalPickup.detail());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "zero", "negative", "attachment", "riding", "foreignWatcher", "noWatcher", "staleTracker", "type", "xpBound", "newCandidate" })
    void xpTouchAndOutboundChangesInvalidateUnstartedFullPlan(String change) throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var orb = mergedXpOrb(2, 7);
        try (var installed = installedBaseEnvironment()) {
            var plan = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).plan();
            assertNotNull(plan);
            switch (change) {
                case "zero" -> field(net.minecraft.world.entity.ExperienceOrb.class, orb, "count", 0);
                case "negative" -> field(net.minecraft.world.entity.ExperienceOrb.class, orb, "count", -1);
                case "attachment" -> when(orb.hasAttachments()).thenReturn(true);
                case "riding" -> when(orb.isPassenger()).thenReturn(true);
                case "foreignWatcher" -> xpWatcherMap(orb, this.player.connection, mock(ServerGamePacketListenerImpl.class));
                case "noWatcher" -> xpWatcherMap(orb);
                case "staleTracker" -> {
                    var map = xpWatcherMap(orb, this.player.connection);
                    ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) field(map, "entityMap")).clear();
                }
                case "type" -> doReturn(net.minecraft.world.entity.EntityType.ITEM).when(orb).getType();
                case "xpBound" -> field(net.minecraft.world.entity.ExperienceOrb.class, orb, "value", 8);
                case "newCandidate" -> mergedXpOrb(2, 1);
                default -> throw new AssertionError(change);
            }
            assertFalse(plan.revalidate());
            assertEquals(0, this.player.totalExperience);
            assertEquals(0, this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.CONNECTION).worker());
        }
    }

    @Test
    void xpPickupListenerRemainsUnknownAndCaptureNeverInvokesIt() throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        mergedXpOrb(2, 7);
        try (var installed = installedBaseEnvironment()) {
            assertNotNull(this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION).plan());
            var events = new java.util.concurrent.atomic.AtomicInteger();
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.PlayerXpEvent.PickupXp> callback = event -> events.incrementAndGet();
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.player.PlayerXpEvent.PickupXp.class, callback);
            try {
                var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
                assertEquals(net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, capture.refusal());
                assertEquals(0, events.get());
            } finally {
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(callback);
            }
        }
    }

    @Test
    void xpEnteringOriginalQueryAfterCaptureCannotBypassCapturedBudget() throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var orb = mergedXpOrb(2, 3);
        var oldMap = this.level.getChunkSource().chunkMap;
        var candidate = mergedXpOrb(2, 3);
        var map = this.level.getChunkSource().chunkMap;
        var records = (it.unimi.dsi.fastutil.ints.Int2ObjectMap) field(map, "entityMap");
        records.putAll((it.unimi.dsi.fastutil.ints.Int2ObjectMap) field(oldMap, "entityMap"));
        when(candidate.getBoundingBox()).thenReturn(new AABB(50, 64, 8.25, 50.5, 64.5, 8.75));
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            var originalBox = orb.getBoundingBox();
            when(candidate.getBoundingBox()).thenReturn(originalBox);
            assertFalse(capture.plan().revalidate(), "New touch candidate was never part of the captured XP budget");
            assertEquals(0, this.player.totalExperience);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({ "false,normal", "true,normal", "false,failure", "true,failure", "false,shutdown", "true,shutdown" })
    void finalXpStorageCommitsOnceAfterPhysicalReturnIncludingFailureAndShutdown(boolean persistent, String outcome) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var orb = mergedXpOrb(1, 7);
        var callback = mock(EntityInLevelCallback.class);
        field(Entity.class, orb, "levelCallback", callback);
        when(orb.getLevelCallback()).thenCallRealMethod();
        when(orb.isRemoved()).thenCallRealMethod();
        doCallRealMethod().when(orb).playerTouch(this.player);
        doCallRealMethod().when(orb).discard();
        doCallRealMethod().when(orb).remove(any());
        doCallRealMethod().when(orb).setRemoved(any());
        when(this.player.getItemBySlot(any())).thenReturn(ItemStack.EMPTY);
        var callbacks = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertSame(this.publishing, Thread.currentThread());
            assertNull(RegionPlayerStateScope.current());
            assertEquals(1, this.engine.playerTicks().snapshot().running(), "Only the outer serial publisher remains");
            assertEquals(1, this.engine.playerTicks().snapshot().phases().get(RegionPlayerTickCoordinator.Phase.CONNECTION).joined());
            assertTrue(orb.isRemoved());
            assertEquals(7, this.player.totalExperience);
            callbacks.incrementAndGet();
            field(Entity.class, orb, "levelCallback", EntityInLevelCallback.NULL);
            return null;
        }).when(callback).onRemove(Entity.RemovalReason.DISCARDED);
        RuntimeException sentinel = new RuntimeException("after committed original XP");
        var shutdownFailure = new AtomicReference<Throwable>();
        Thread stopper = new Thread(() -> {
            try {
                this.engine.shutdown();
            } catch (Throwable failure) {
                shutdownFailure.set(failure);
            }
        }, "shutdown after committed XP");
        Object dispatch;
        assertEquals(true, invoke(this.engine.playerNetworkGate(), "freeze", new Class<?>[] { MinecraftServer.class }, this.server));
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            // The publisher fence is the other original phase, not another invocation of this phase.
            this.parent = this.engine.beginPlayerSerial(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
            assertNotNull(this.parent);
            this.batch = ((Set<?>) field(this.engine, "activeTickBatches")).iterator().next();
            dispatch = construct("RegionPlayerTickDispatch", new Class<?>[] { RegionThreadingEngine.class, Class.forName(PKG + "RegionTickBatch") }, this.engine, this.batch);
            Class<?> effectsType = Class.forName(PKG + "RegionOwnershipLeaseGroup");
            try (var lease = (RegionPlayerSpatialLease) invoke(this.engine, "reservePlayerSpatial",
                    new Class<?>[] { Class.forName(PKG + "RegionTickBatch"), effectsType, List.class }, this.batch, dispatch, List.of(capture.plan().spatial()))) {
                assertNotNull(lease);
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(capture.plan().state())));
                var ticket = this.engine.playerTicks().prepare(this.player, this.player.connection.getConnection(), RegionPlayerTickCoordinator.Phase.CONNECTION);
                assertTrue(this.engine.playerTicks().admit(ticket));
                Object call = dispatchCall(capture.plan().state(), ticket, () -> {
                    orb.playerTouch(this.player);
                    assertEquals(0, callbacks.get());
                    if (outcome.equals("failure")) throw sentinel;
                });
                Object gate = construct("RegionPlayerTickDispatch$StartGate", new Class<?>[] { int.class }, 1);
                worker(() -> {
                    try (var observed = installedBaseEnvironment()) {
                        dispatchGroup(dispatch, lease, call, gate).run();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                assertTrue(ticket.started());
                assertSame(outcome.equals("failure") ? sentinel : null, field(call, "failure"));
                assertEquals(0, callbacks.get());
                assertTrue(orb.isRemoved());
                assertEquals(0, field(orb, "count"));
                assertEquals(7, this.player.totalExperience);
                invoke(this.engine.playerTicks(), "joinWorker", new Class<?>[] { RegionPlayerTickCoordinator.Plan.class }, ticket);
                assertFalse(ticket.retainsLiveBinding());
            }
            if (outcome.equals("shutdown")) {
                stopper.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!(boolean) field(this.engine, "shutdown") && System.nanoTime() < deadline) Thread.yield();
                assertEquals(true, field(this.engine, "shutdown"));
                assertTrue(stopper.isAlive(), "Shutdown must await the publisher's committed-effects fence");
            }
            try {
                Object removals = field(dispatch, "removals");
                assertEquals(1, invoke(removals, "pendingCount", new Class<?>[0]));
                Object drain = invoke(removals, "drain", new Class<?>[0]);
                invoke(this.engine, "applyDeferredRemovals", new Class<?>[] { ServerLevel.class, drain.getClass(), boolean.class, boolean.class }, this.level, drain, false, true);
                assertEquals(1, callbacks.get());
                // A stale repeated drain cannot run the original storage callback a second time.
                invoke(this.engine, "applyDeferredRemovals", new Class<?>[] { ServerLevel.class, drain.getClass(), boolean.class, boolean.class }, this.level, drain, false, true);
                assertEquals(1, callbacks.get());
                assertEquals(0, invoke(removals, "pendingCount", new Class<?>[0]));
            } finally {
                this.parent.close();
                this.parent = null;
                if (outcome.equals("shutdown")) assertTrue(stopper.isAlive(), "Shutdown still awaits the original frozen boundary");
                invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
                if (stopper.isAlive()) stopper.join(5000);
            }
            assertFalse(stopper.isAlive());
            assertNull(shutdownFailure.get(), "Original shutdown must complete after committed effects and thaw");
            assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        } finally {
            if (this.engine.playerNetworkGate().snapshot().frozen()) invoke(this.engine.playerNetworkGate(), "thaw", new Class<?>[0]);
        }
    }

    private Inventory materialInventory() {
        Inventory inventory = new Inventory(this.player);
        inventory.selected = 8;
        inventory.setItem(9, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1));
        when(this.player.getInventory()).thenReturn(inventory);
        return inventory;
    }

    private net.minecraft.world.entity.item.ItemEntity materialPickup(int count) throws Exception {
        var item = mock(net.minecraft.world.entity.item.ItemEntity.class);
        doReturn(net.minecraft.world.entity.EntityType.ITEM).when(item).getType();
        when(item.getItem()).thenReturn(new ItemStack(net.minecraft.world.item.Items.DIAMOND, count));
        this.add(item, 48.5, 8.5, new AABB(48.25, 64, 8.25, 48.75, 64.5, 8.75));
        xpWatcherMap(item, this.player.connection);
        return item;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void plainBackpackInventoryAndOriginalItemMergeRunInsideNativeState(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        Inventory inventory = materialInventory();
        var item = materialPickup(5);
        doCallRealMethod().when(item).playerTouch(this.player);
        field(ServerPlayer.class, this.player, "stats", this.player.getStats());
        doCallRealMethod().when(this.player).awardStat(any(net.minecraft.stats.Stat.class), anyInt());
        try (var installed = installedBaseEnvironment()) {
            var result = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(result.plan(), result.detail());
            try (var lease = reserve(result.plan().spatial())) {
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(result.plan().state())));
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open(); var owner = lease.openPlayerState(result.plan().state())) {
                        inventory.tick();
                        item.playerTouch(this.player);
                        assertEquals(6, inventory.getItem(9).getCount());
                        assertTrue(inventory.getItem(8).isEmpty());
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            assertEquals(6, inventory.getItem(9).getCount());
            assertEquals(5, item.getItem().getCount(), "Original pickup restores removed entity's display count");
            assertEquals(5, this.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP.get(net.minecraft.world.item.Items.DIAMOND)));
            org.mockito.Mockito.verify(item).discard();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "selected", "component", "unknownItem", "capacity", "thrower", "target", "count", "newCandidate", "foreignWatcher", "criterion" })
    void materialPickupChangesRefuseBeforeOriginalEffects(String change) throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        Inventory inventory = materialInventory();
        var item = materialPickup(5);
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            switch (change) {
                case "selected" -> inventory.selected = 9;
                case "component" -> inventory.getItem(9).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("unknown component"));
                case "unknownItem" -> inventory.setItem(9, new ItemStack(net.minecraft.world.item.Items.FILLED_MAP));
                case "capacity" -> inventory.getItem(9).setCount(64);
                case "thrower" -> field(net.minecraft.world.entity.item.ItemEntity.class, item, "thrower", UUID.randomUUID());
                case "target" -> field(net.minecraft.world.entity.item.ItemEntity.class, item, "target", UUID.randomUUID());
                case "count" -> item.getItem().setCount(6);
                case "newCandidate" -> materialPickup(1);
                case "foreignWatcher" -> {
                    Object tracked = ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) field(this.level.getChunkSource().chunkMap, "entityMap")).get(item.getId());
                    ((Set) field(tracked, "seenBy")).add(mock(ServerGamePacketListenerImpl.class));
                }
                case "criterion" -> {
                    var trigger = net.minecraft.advancements.CriteriaTriggers.INVENTORY_CHANGED;
                    var holder = net.minecraft.advancements.Advancement.Builder.advancement().addCriterion("inventory", trigger.createCriterion(new net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance(java.util.Optional.empty(), net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance.Slots.ANY, List.of()))).build(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("state_test", "inventory"));
                    trigger.addPlayerListener(this.player.getAdvancements(), new net.minecraft.advancements.critereon.SimpleCriterionTrigger.Listener(holder.value().criteria().get("inventory").triggerInstance(), holder, "inventory"));
                }
                default -> throw new AssertionError(change);
            }
            assertFalse(capture.plan().revalidate());
            assertEquals(0, this.player.getStats().getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP.get(net.minecraft.world.item.Items.DIAMOND)));
        }
    }

    private net.minecraft.world.level.block.entity.ChestBlockEntity singleChest() {
        var chest = org.mockito.Mockito.spy(new net.minecraft.world.level.block.entity.ChestBlockEntity(new BlockPos(48, 64, 10), Blocks.CHEST.defaultBlockState()));
        org.mockito.Mockito.doNothing().when(chest).startOpen(any());
        chest.setLevel(this.level);
        chest.setItem(0, new ItemStack(net.minecraft.world.item.Items.DIAMOND, 11));
        var pos = chest.getBlockPos();
        when(this.level.getBlockEntity(pos)).thenReturn(chest);
        doAnswer(call -> {
            BlockPos at = call.getArgument(0);
            return at.equals(pos) ? chest.getBlockState() : at.getY() < 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }).when(this.level).getBlockState(any());
        when(this.level.hasChunkAt(pos)).thenReturn(true);
        when(this.player.canInteractWithBlock(eq(pos), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
        return chest;
    }

    private net.minecraft.world.inventory.ChestMenu playerChestMenu(net.minecraft.world.level.block.entity.ChestBlockEntity chest) throws Exception {
        Inventory inventory = new Inventory(this.player);
        inventory.selected = 8;
        when(this.player.getInventory()).thenReturn(inventory);
        var menu = new net.minecraft.world.inventory.ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x3, 1, inventory, chest, 3);
        var listener = mock(net.minecraft.world.inventory.ContainerListener.class);
        var synchronizer = mock(net.minecraft.world.inventory.ContainerSynchronizer.class);
        menu.addSlotListener(listener);
        menu.setSynchronizer(synchronizer);
        field(ServerPlayer.class, this.player, "containerListener", listener);
        field(ServerPlayer.class, this.player, "containerSynchronizer", synchronizer);
        this.player.containerMenu = menu;
        return menu;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void exactSingleChestOriginalValidityAndSlotSynchronizationRunInNativeState(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var chest = singleChest();
        // Opening callbacks are fixture work; native tick must only run the complete menu's original reads/sync.
        var menu = playerChestMenu(chest);
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            assertTrue(capture.plan().spatial().footprint().containsBlock(this.level, chest.getBlockPos()));
            var local = capture.plan().state();
            Object shared = invoke(local, "sharedResources", new Class<?>[0]);
            assertTrue(((List<?>) shared).contains(chest));
            var sync = (net.minecraft.world.inventory.ContainerSynchronizer) field(this.player, "containerSynchronizer");
            // This server fence establishes a legitimate unsent slot result without mutating the captured chest stack identity.
            field(net.minecraft.world.inventory.AbstractContainerMenu.class, menu, "remoteSlots", net.minecraft.core.NonNullList.withSize(63, ItemStack.EMPTY));
            capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            try (var lease = reserve(capture.plan().spatial())) {
                local = capture.plan().state();
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(local)));
                var captured = local;
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open(); var owner = lease.openPlayerState(captured)) {
                        assertTrue(menu.stillValid(this.player));
                        menu.broadcastChanges();
                        assertEquals(11, menu.getSlot(0).getItem().getCount());
                        assertEquals(11, chest.getItem(0).getCount());
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            org.mockito.Mockito.verify(sync).sendSlotChange(eq(menu), eq(0), org.mockito.ArgumentMatchers.argThat(stack -> stack.is(net.minecraft.world.item.Items.DIAMOND) && stack.getCount() == 11));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "replaced", "removed", "attachment", "loot", "customStack", "carried", "slot", "range", "synchronizer" })
    void singleChestLateChangesRejectWholeCallBeforeAnyMenuBody(String change) throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var chest = singleChest();
        var menu = playerChestMenu(chest);
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            switch (change) {
                case "replaced" -> when(this.level.getBlockEntity(chest.getBlockPos())).thenReturn(null);
                case "removed" -> chest.setRemoved();
                case "attachment" -> when(chest.hasAttachments()).thenReturn(true);
                case "loot" -> field(net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity.class, chest, "lootTable", net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("test", "unknown")));
                case "customStack" -> chest.getItem(0).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("unknown"));
                case "carried" -> menu.setCarried(new ItemStack(net.minecraft.world.item.Items.DIAMOND));
                case "slot" -> menu.slots.set(0, new net.minecraft.world.inventory.Slot(new net.minecraft.world.SimpleContainer(1), 0, 0, 0));
                case "range" -> when(this.player.canInteractWithBlock(eq(chest.getBlockPos()), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(false);
                case "synchronizer" -> menu.setSynchronizer(mock(net.minecraft.world.inventory.ContainerSynchronizer.class));
                default -> throw new AssertionError(change);
            }
            assertFalse(capture.plan().revalidate());
            assertEquals(0, this.player.totalExperience);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void twoPlayersClaimExactSharedChestOnlyInsideTheirOneOrderedLease(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        ServerPlayer first = this.player;
        var chest = singleChest();
        var firstMenu = playerChestMenu(chest);
        ServerPlayer other = mock(ServerPlayer.class, org.mockito.AdditionalAnswers.delegatesTo(first));
        field(ServerPlayer.class, other, "server", this.server);
        field(ServerPlayer.class, other, "gameMode", new ServerPlayerGameMode(other));
        when(other.serverLevel()).thenReturn(this.level);
        when(other.getCamera()).thenReturn(other);
        var connection = mock(Connection.class);
        var listener = mock(ServerGamePacketListenerImpl.class);
        listener.player = other;
        other.connection = listener;
        when(listener.getConnection()).thenReturn(connection);
        when(listener.isAcceptingMessages()).thenReturn(true);
        when(connection.getPacketListener()).thenReturn(listener);
        when(connection.isConnected()).thenReturn(true);
        this.player = other;
        this.add(other, 47.5, 12.5, new AABB(47.2, 64, 12.2, 47.8, 65.8, 12.8));
        when(this.server.getPlayerList().getPlayer(other.getUUID())).thenReturn(other);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        when(other.canInteractWithBlock(eq(chest.getBlockPos()), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(true);
        var secondMenu = playerChestMenu(chest);
        try (var installed = installedBaseEnvironment()) {
            var secondCapture = this.engine.capturePlayerAdmissionPlan(other, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(secondCapture.plan(), secondCapture.detail());
            this.player = first;
            var firstCapture = this.engine.capturePlayerAdmissionPlan(first, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(firstCapture.plan(), firstCapture.detail());
            var a = firstCapture.plan().state();
            var b = secondCapture.plan().state();
            assertTrue(a.conflictsWith(b));
            var entries = List.of((net.neoforged.neoforge.server.threading.region.RegionPlayerConflictPlan.Entry) invoke(a, "conflictEntry", new Class<?>[0]),
                    (net.neoforged.neoforge.server.threading.region.RegionPlayerConflictPlan.Entry) invoke(b, "conflictEntry", new Class<?>[0]));
            var prefix = net.neoforged.neoforge.server.threading.region.RegionPlayerConflictPlan.prefix(entries);
            assertEquals(1, prefix.groups().size());
            assertEquals(entries, prefix.groups().getFirst().entries());
            this.parent = this.engine.beginPlayerSerial(first, first.connection.getConnection(), RegionPlayerTickCoordinator.Phase.ENTITY);
            assertNotNull(this.parent);
            this.batch = ((Set<?>) field(this.engine, "activeTickBatches")).iterator().next();
            Class<?> effectsType = Class.forName(PKG + "RegionOwnershipLeaseGroup");
            Object effects = Proxy.newProxyInstance(effectsType.getClassLoader(), new Class<?>[] { effectsType }, (proxy, m, args) -> m.getReturnType() == boolean.class ? true : null);
            try (var lease = (RegionPlayerSpatialLease) invoke(this.engine, "reservePlayerSpatial", new Class<?>[] { Class.forName(PKG + "RegionTickBatch"), effectsType, List.class },
                    this.batch, effects, List.of(firstCapture.plan().spatial(), secondCapture.plan().spatial()))) {
                assertNotNull(lease);
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(a, b)));
                var order = new ArrayList<ServerPlayer>();
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open()) {
                        try (var state = lease.openPlayerState(a)) {
                            assertTrue(firstMenu.stillValid(first));
                            firstMenu.broadcastChanges();
                            order.add(first);
                        }
                        try (var state = lease.openPlayerState(b)) {
                            assertTrue(secondMenu.stillValid(other));
                            secondMenu.broadcastChanges();
                            order.add(other);
                        }
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
                assertEquals(List.of(first, other), order);
                assertEquals(11, chest.getItem(0).getCount());
            }
            assertEquals(0, invoke(this.engine, "playerStateClaimCount", new Class<?>[0]));
        }
    }

    private net.minecraft.server.ServerScoreboard playerScoreboard() throws Exception {
        var board = new net.minecraft.server.ServerScoreboard(this.server);
        invoke(board, "createData", new Class<?>[0]);
        when(this.player.getScoreboard()).thenReturn(board);
        return board;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalNonDisplayedScoreAndOriginalSaveDirtyCallbackAreSynchronousInNativeState(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var board = playerScoreboard();
        var criterion = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME);
        var objective = board.addObjective("original_time", criterion, net.minecraft.network.chat.Component.literal("Time"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        var data = (net.minecraft.world.scores.ScoreboardSaveData) field(board, "forgiaPlayerSaveData");
        data.setDirty(false);
        field(ServerPlayer.class, this.player, "stats", this.player.getStats());
        doCallRealMethod().when(this.player).awardStat(any(net.minecraft.stats.Stat.class), anyInt());
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            assertTrue(((List<?>) invoke(capture.plan().state(), "sharedResources", new Class<?>[0])).contains(board));
            try (var lease = reserve(capture.plan().spatial())) {
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(capture.plan().state())));
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open(); var state = lease.openPlayerState(capture.plan().state())) {
                        this.player.awardStat(criterion, 1);
                        assertEquals(1, board.getOrCreatePlayerScore(this.player, objective, true).get());
                        assertTrue(data.isDirty(), "Original exact saved-data callback runs before the award returns");
                        assertThrows(IllegalStateException.class, () -> board.getOrCreatePlayerScore(mock(ServerPlayer.class), objective, true));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
            assertEquals(1, this.player.getStats().getValue(criterion));
            assertEquals(1, board.getOrCreatePlayerScore(this.player, objective, true).get());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "tracked", "dirtyListener", "autoDisplay", "objectiveRemoved", "boardReplaced" })
    void nonDisplayedObjectiveLateChangesRefuseBeforeOriginalScoreWrite(String change) throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var board = playerScoreboard();
        var objective = board.addObjective("original_time", net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME), net.minecraft.network.chat.Component.literal("Time"), net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.INTEGER, false, null);
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            switch (change) {
                case "tracked" -> board.startTrackingObjective(objective);
                case "dirtyListener" -> board.addDirtyListener(() -> {
                    throw new AssertionError("Unknown callback must not execute during validation");
                });
                case "autoDisplay" -> objective.setDisplayAutoUpdate(true);
                case "objectiveRemoved" -> board.removeObjective(objective);
                case "boardReplaced" -> when(this.player.getScoreboard()).thenReturn(new net.minecraft.world.scores.Scoreboard());
                default -> throw new AssertionError(change);
            }
            assertFalse(capture.plan().revalidate());
            assertEquals(0, this.player.totalExperience);
        }
    }

    private net.minecraft.world.level.saveddata.maps.MapItemSavedData backpackMap() throws Exception {
        var id = new net.minecraft.world.level.saveddata.maps.MapId(0);
        var data = net.minecraft.world.level.saveddata.maps.MapItemSavedData.createFresh(47.5, 8.5, (byte) 0, true, false, Level.OVERWORLD);
        var inventory = new Inventory(this.player);
        inventory.selected = 8;
        var stack = new ItemStack(net.minecraft.world.item.Items.FILLED_MAP);
        stack.set(net.minecraft.core.component.DataComponents.MAP_ID, id);
        inventory.setItem(9, stack);
        when(this.player.getInventory()).thenReturn(inventory);
        when(this.player.getName()).thenReturn(net.minecraft.network.chat.Component.literal("OriginalMapOwner"));
        when(this.server.overworld()).thenReturn(this.level);
        var storage = mock(net.minecraft.world.level.storage.DimensionDataStorage.class);
        when(this.level.getDataStorage()).thenReturn(storage);
        when(storage.get(any(), eq(id.key()))).thenReturn(data);
        when(this.level.getMapData(any())).thenCallRealMethod();
        return data;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalBackpackMapTickAndUpdatePacketUseRealCapturedDataOnNativeWorker(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var map = backpackMap();
        byte[] colors = map.colors.clone();
        var id = new net.minecraft.world.level.saveddata.maps.MapId(0);
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            assertTrue(((List<?>) invoke(capture.plan().state(), "sharedResources", new Class<?>[0])).contains(map));
            try (var lease = reserve(capture.plan().spatial())) {
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(capture.plan().state())));
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open(); var state = lease.openPlayerState(capture.plan().state())) {
                        assertSame(map, this.level.getMapData(id));
                        this.player.getInventory().tick();
                        assertNotNull(((net.minecraft.world.item.MapItem) net.minecraft.world.item.Items.FILLED_MAP).getUpdatePacket(this.player.getInventory().getItem(9), this.level, this.player));
                        assertTrue(map.forgiaHasLocalPlayerCarrier(this.player));
                        org.junit.jupiter.api.Assertions.assertArrayEquals(colors, map.colors, "Non-selected original map branch has no terrain update");
                        assertThrows(IllegalStateException.class, () -> this.level.getMapData(new net.minecraft.world.level.saveddata.maps.MapId(99)));
                        assertThrows(IllegalStateException.class, () -> map.tickCarriedBy(mock(ServerPlayer.class), this.player.getInventory().getItem(9)));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "foreignCarrier", "colors", "binding", "selected", "custom", "secondMap", "missing", "mapId" })
    void realMapLateChangesRejectWholeCallBeforeOriginalInventoryTick(String change) throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        var map = backpackMap();
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.detail());
            switch (change) {
                case "mapId" -> this.player.getInventory().getItem(9).set(net.minecraft.core.component.DataComponents.MAP_ID, new net.minecraft.world.level.saveddata.maps.MapId(1));
                case "foreignCarrier" -> map.getHoldingPlayer(mock(ServerPlayer.class));
                case "colors" -> map.colors = new byte[16384];
                case "binding" -> when(this.level.getDataStorage().get(any(), eq(new net.minecraft.world.level.saveddata.maps.MapId(0).key()))).thenReturn(net.minecraft.world.level.saveddata.maps.MapItemSavedData.createFresh(47.5, 8.5, (byte) 0, true, false, Level.OVERWORLD));
                case "selected" -> this.player.getInventory().selected = 9;
                case "custom" -> this.player.getInventory().getItem(9).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("unknown"));
                case "secondMap" -> this.player.getInventory().setItem(10, this.player.getInventory().getItem(9).copy());
                case "missing" -> when(this.level.getDataStorage().get(any(), eq(new net.minecraft.world.level.saveddata.maps.MapId(0).key()))).thenReturn(null);
                default -> throw new AssertionError(change);
            }
            assertFalse(capture.plan().revalidate());
            assertEquals(0, this.player.totalExperience);
        }
    }

    private net.minecraft.world.damagesource.DamageSource starvationState() throws Exception {
        productionLocalState(net.minecraft.advancements.AdvancementRewards.EMPTY);
        when(this.server.getPlayerList().getPlayers()).thenReturn(List.of(this.player));
        when(this.player.gameMode.getGameModeForPlayer()).thenReturn(net.minecraft.world.level.GameType.SURVIVAL);
        field(net.minecraft.world.entity.LivingEntity.class, this.player, "damageContainers", new java.util.Stack<>());
        var type = new net.minecraft.world.damagesource.DamageType("starve", net.minecraft.world.damagesource.DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER, 0);
        net.minecraft.core.Holder<net.minecraft.world.damagesource.DamageType> holder = mock(net.minecraft.core.Holder.class);
        when(holder.value()).thenReturn(type);
        when(holder.is(net.minecraft.world.damagesource.DamageTypes.STARVE)).thenReturn(true);
        for (var tag : List.of(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR, net.minecraft.tags.DamageTypeTags.BYPASSES_EFFECTS, net.minecraft.tags.DamageTypeTags.NO_KNOCKBACK)) when(holder.is(tag)).thenReturn(true);
        var source = new net.minecraft.world.damagesource.DamageSource(holder);
        var sources = mock(net.minecraft.world.damagesource.DamageSources.class);
        when(sources.starve()).thenReturn(source);
        when(this.player.damageSources()).thenReturn(sources);
        xpWatcherMap(this.player);
        when(this.level.getChunkSource().chunkMap.forgiaHasNoEntityWatchers(any())).thenCallRealMethod();
        var tag = new CompoundTag();
        tag.putInt("foodLevel", 0);
        tag.putInt("foodTickTimer", 79);
        tag.putFloat("foodSaturationLevel", 0);
        tag.putFloat("foodExhaustionLevel", 0);
        this.player.getFoodData().readAdditionalSaveData(tag);
        this.engine.addRegionSection(this.level, 0, -3);
        this.engine.addRegionSection(this.level, 3, -3);
        for (int x = -3; x < 6; x++) for (int z = -3; z < 3; z++) {
            var chunk = this.level.getChunkSource().getChunkNow(x, z);
            var registries = new HashMap<Integer, net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry>();
            when(chunk.getListenerRegistry(anyInt())).thenAnswer(call -> registries.computeIfAbsent(call.getArgument(0), y -> new net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry(this.level, y, ignored -> {})));
        }
        return source;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void originalFoodTimerUsesOneCapturedSynchronousDamageAndFullEventLease(boolean persistent) throws Exception {
        if (persistent) initialize(true);
        var source = starvationState();
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.refusal() + ":" + capture.detail());
            var footprint = capture.plan().spatial().footprint();
            assertTrue(footprint.containsRegion(this.level, 0, -1));
            assertTrue(footprint.containsRegion(this.level, 1, -1));
            assertEquals(this.player.getBoundingBox().inflate(1, .5, 1), footprint.queryBounds(), "Effect lease must never replace the original pickup query");
            var damages = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                RegionPlayerStateScope.checkDamage(this.player, call.getArgument(0), call.getArgument(1));
                damages.incrementAndGet();
                return true;
            }).when(this.player).hurt(eq(source), eq(1f));
            try (var lease = reserve(capture.plan().spatial())) {
                assertEquals(RegionPlayerStatePlan.Refusal.NONE, lease.bindPlayerState(List.of(capture.plan().state())));
                worker(() -> {
                    try (var base = installedBaseEnvironment(); var space = lease.open(); var state = lease.openPlayerState(capture.plan().state())) {
                        this.player.getFoodData().tick(this.player);
                        assertEquals(1, damages.get());
                        assertThrows(IllegalStateException.class, () -> RegionPlayerStateScope.checkDamage(this.player, source, 1));
                        assertThrows(IllegalStateException.class, () -> RegionPlayerStateScope.checkDamage(mock(ServerPlayer.class), source, 1));
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                });
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = { "foreignPlayer", "watcher", "lowHealth", "absorption", "invulnerable", "spawnProtection", "climbable", "food", "saturation", "timer", "damageSource", "listener", "pendingRegistry", "registryIdentity" })
    void starvationLateChangesRefuseBeforeOriginalDamageAndUnknownCallbacks(String change) throws Exception {
        var source = starvationState();
        try (var installed = installedBaseEnvironment()) {
            var capture = this.engine.capturePlayerAdmissionPlan(this.player, RegionPlayerTickCoordinator.Phase.CONNECTION);
            assertNotNull(capture.plan(), capture.refusal() + ":" + capture.detail());
            switch (change) {
                case "foreignPlayer" -> when(this.server.getPlayerList().getPlayers()).thenReturn(List.of(this.player, mock(ServerPlayer.class)));
                case "watcher" -> {
                    Object tracked = ((it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) field(this.level.getChunkSource().chunkMap, "entityMap")).get(this.player.getId());
                    ((Set) field(tracked, "seenBy")).add(mock(ServerGamePacketListenerImpl.class));
                }
                case "lowHealth" -> when(this.player.getHealth()).thenReturn(19f);
                case "absorption" -> when(this.player.getAbsorptionAmount()).thenReturn(1f);
                case "invulnerable" -> when(this.player.isInvulnerable()).thenReturn(true);
                case "spawnProtection" -> field(ServerPlayer.class, this.player, "spawnInvulnerableTime", 1);
                case "climbable" -> when(this.player.getLastClimbablePos()).thenReturn(java.util.Optional.of(new BlockPos(200, 64, 0)));
                case "food" -> this.player.getFoodData().setFoodLevel(1);
                case "saturation" -> this.player.getFoodData().setSaturation(1);
                case "timer" -> field(net.minecraft.world.food.FoodData.class, this.player.getFoodData(), "tickTimer", 80);
                case "damageSource" -> when(this.player.damageSources().starve()).thenReturn(new net.minecraft.world.damagesource.DamageSource(net.minecraft.core.Holder.direct(new net.minecraft.world.damagesource.DamageType("unknown", 0))));
                case "listener" -> {
                    var chunk = this.level.getChunkSource().getChunkNow(2, -1);
                    Object registry = chunk.getListenerRegistry(3);
                    ((List) field(registry, "listeners")).add(mock(net.minecraft.world.level.gameevent.GameEventListener.class));
                }
                case "pendingRegistry" -> {
                    Object registry = this.level.getChunkSource().getChunkNow(2, -1).getListenerRegistry(3);
                    ((List) field(registry, "listenersToAdd")).add(mock(net.minecraft.world.level.gameevent.GameEventListener.class));
                }
                case "registryIdentity" -> when(this.level.getChunkSource().getChunkNow(2, -1).getListenerRegistry(3)).thenReturn(new net.minecraft.world.level.gameevent.EuclideanGameEventListenerRegistry(this.level, 3, ignored -> {}));
                default -> throw new AssertionError(change);
            }
            assertFalse(capture.plan().revalidate());
            org.mockito.Mockito.verify(this.player, org.mockito.Mockito.never()).hurt(any(), org.mockito.ArgumentMatchers.anyFloat());
        }
    }
}
