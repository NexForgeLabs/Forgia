/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionPlayerTickCoordinator;
import net.neoforged.neoforge.server.threading.region.RegionPlayerTickCoordinator.Phase;
import net.neoforged.neoforge.server.threading.region.RegionPlayerTickCoordinator.State;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Production ledger/identity/index controls; installed-server oracle separately covers actual tick bodies. */
class RegionPlayerTickCoordinatorTest {
    private RegionThreadingEngine engine;
    private MinecraftServer server;
    private final AtomicInteger tick = new AtomicInteger(7);
    private final Map<UUID, ServerPlayer> online = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger();

    @BeforeEach
    void setup() {
        Thread main = Thread.currentThread();
        this.server = mock(MinecraftServer.class);
        when(this.server.isSameThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(this.server.getTickCount()).thenAnswer(call -> this.tick.get());
        PlayerList players = mock(PlayerList.class);
        when(players.getPlayer(any(UUID.class))).thenAnswer(call -> this.online.get(call.getArgument(0)));
        when(this.server.getPlayerList()).thenReturn(players);
        this.engine = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.DYNAMIC_CLUSTER, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(this.engine);
        this.engine.configurePlayerTickPlanningForTesting(true);
    }

    @AfterEach
    void cleanup() {
        RegionThreadingManager.clear();
    }

    private ServerLevel level(ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(dimension);
        when(level.getServer()).thenReturn(this.server);
        when(level.getProfiler()).thenReturn(InactiveProfiler.INSTANCE);
        when(level.entityIdentityLedger()).thenReturn(new EntityIdentityLedger());
        doAnswer(call -> {
            Consumer<Entity> action = call.getArgument(0);
            this.online.values().stream().filter(player -> player.level() == level).forEach(action);
            return true;
        }).when(level).forEachEntityInTickListForRegionIndex(any());
        return level;
    }

    private ServerPlayer player(ServerLevel level, UUID uuid) throws Exception {
        ServerPlayer player = mock(ServerPlayer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, this.server);
        when(player.getUUID()).thenReturn(uuid);
        when(player.getId()).thenReturn(this.ids.incrementAndGet());
        when(player.level()).thenReturn(level);
        when(player.serverLevel()).thenReturn(level);
        when(player.blockPosition()).thenReturn(new BlockPos(8, 64, 8));
        when(player.getPassengers()).thenReturn(List.of());
        Connection connection = mock(Connection.class);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        listener.player = player;
        player.connection = listener;
        when(listener.getConnection()).thenReturn(connection);
        when(listener.isAcceptingMessages()).thenReturn(true);
        when(connection.getPacketListener()).thenReturn(listener);
        when(connection.isConnected()).thenReturn(true);
        this.online.put(uuid, player);
        this.publish(level, player);
        return player;
    }

    private void publish(ServerLevel level, ServerPlayer player) {
        EntityIdentityLedger ledger = level.entityIdentityLedger();
        ledger.admit(player);
        ledger.publishAdmitted(player);
        ledger.publishTracked(player);
        this.engine.entityTickStarted(level, player);
        this.engine.beginEntityTickPlan(level, entity -> true, condition -> assertTrue(condition.getAsBoolean()));
    }

    private RegionPlayerTickCoordinator.Plan prepare(ServerPlayer player, Phase phase) {
        var plan = this.engine.playerTicks().prepare(player, player.connection.getConnection(), phase);
        assertNotNull(plan);
        return plan;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void disconnectedCachedCallPreservesOriginalEligibilityUnlessItsBodyAlreadyStarted(boolean started) throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        Connection connection = player.connection.getConnection();
        var pass = this.engine.beginPlayerConnectionPass(List.of(connection));
        Class<?> callType = Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerTickDispatch$Call");
        var constructor = callType.getDeclaredConstructor(ServerPlayer.class, Phase.class, Runnable.class);
        constructor.setAccessible(true);
        var effects = new AtomicInteger();
        Object call = constructor.newInstance(player, Phase.CONNECTION, (Runnable) effects::incrementAndGet);
        var startedField = callType.getDeclaredField("started");
        startedField.setAccessible(true);
        startedField.setBoolean(call, started);
        var completedField = pass.getClass().getDeclaredField("completed");
        completedField.setAccessible(true);
        ((Map<Connection, Object>) completedField.get(pass)).put(connection, call);
        when(connection.isConnected()).thenReturn(false);
        boolean enterOriginalTickBranch = connection.isConnected() || pass.hasCompleted(connection);
        assertEquals(started, enterOriginalTickBranch);
        if (enterOriginalTickBranch) assertTrue(pass.tick(connection));
        assertFalse(pass.hasCompleted(connection));
        assertEquals(0, effects.get(), "Unstarted closed listeners are removed; started calls are consumed without replay");
        pass.close();
    }

    @Test
    void defaultAndDisabledHooksKeepOriginalCallerPath() throws Exception {
        assertFalse(ForgiaConfig.SERVER.enableRegionThreadingPlayerTicks.getDefault());
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        this.engine.configurePlayerTickPlanningForTesting(false);
        AtomicInteger effects = new AtomicInteger();
        assertNull(RegionThreadingHooks.beginPlayerEntityTick(player));
        assertFalse(RegionThreadingHooks.runPlayerConnectionTick(player.connection.getConnection()));
        assertEquals(0, effects.get(), "Disabled hooks leave the caller in charge of its original call");
        effects.incrementAndGet();
        assertEquals(1, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().phases().get(Phase.ENTITY).prepared());
    }

    @Test
    void missingOwnedStorageRunsCompleteSerialFallbackBeforeAnyPlan() throws Exception {
        RegionThreadingManager.clear();
        this.engine = new RegionThreadingEngine(2, false, false, true, false, false, false,
                RegionPolicy.DYNAMIC_CLUSTER, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        RegionThreadingManager.install(this.engine);
        this.engine.configurePlayerTickPlanningForTesting(true);
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        AtomicInteger effects = new AtomicInteger();
        this.engine.tickPlayerSerial(player, player.connection.getConnection(), Phase.ENTITY, effects::incrementAndGet);
        assertEquals(1, effects.get());
        var counts = this.engine.playerTicks().snapshot().phases().get(Phase.ENTITY);
        assertEquals(0, counts.prepared());
        assertEquals(0, counts.worker());
        assertEquals(1, counts.untrackedSerial());
        assertEquals(1L, counts.reasons().get(RegionPlayerTickCoordinator.Reason.DEPENDENCIES));
    }

    @Test
    void phasesAreIndependentAndConsumedInvocationCannotReplay() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        AtomicInteger effects = new AtomicInteger();
        for (Phase phase : Phase.values()) {
            var plan = this.prepare(player, phase);
            assertTrue(this.engine.playerTicks().admit(plan));
            assertTrue(this.engine.playerTicks().executeSerial(plan, effects::incrementAndGet));
            assertEquals(State.JOINED, plan.state());
            assertFalse(plan.retainsLiveBinding());
            assertThrows(IllegalStateException.class, () -> this.engine.playerTicks().executeSerial(plan, effects::incrementAndGet));
        }
        assertEquals(Phase.values().length, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().pending());
    }

    @Test
    void listenerReplacementInvalidatesAlreadyPreparedPlan() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var old = this.prepare(player, Phase.CONNECTION);
        ServerGamePacketListenerImpl listener = mock(ServerGamePacketListenerImpl.class);
        listener.player = player;
        Connection connection = player.connection.getConnection();
        when(listener.getConnection()).thenReturn(connection);
        when(listener.isAcceptingMessages()).thenReturn(true);
        player.connection = listener;
        when(connection.getPacketListener()).thenReturn(listener);
        assertFalse(this.engine.playerTicks().admit(old));
        assertFalse(old.retainsLiveBinding());
        assertTrue(this.prepare(player, Phase.CONNECTION).lifecycleGeneration() > old.lifecycleGeneration());
    }

    @Test
    void sameUuidRespawnCannotReuseOldInstancePlan() throws Exception {
        ServerLevel level = this.level(Level.OVERWORLD);
        UUID uuid = UUID.randomUUID();
        ServerPlayer oldPlayer = this.player(level, uuid);
        var old = this.prepare(oldPlayer, Phase.ENTITY);
        this.engine.removeRegionEntity(oldPlayer);
        level.entityIdentityLedger().publishDeparting(oldPlayer);
        level.entityIdentityLedger().publishGone(oldPlayer);
        ServerPlayer replacement = this.player(level, uuid);
        assertFalse(this.engine.playerTicks().admit(old));
        assertFalse(old.retainsLiveBinding());
        assertTrue(this.prepare(replacement, Phase.ENTITY).lifecycleGeneration() > old.lifecycleGeneration());
        assertNull(this.engine.playerTicks().prepare(oldPlayer, oldPlayer.connection.getConnection(), Phase.ENTITY));
    }

    @Test
    void crossLevelIdentityEpochIsNotComparedAsSessionGeneration() throws Exception {
        ServerLevel source = this.level(Level.NETHER);
        ServerLevel destination = this.level(Level.OVERWORLD);
        ServerPlayer player = this.player(source, UUID.randomUUID());
        for (int i = 0; i < 20; i++) {
            Entity dummy = mock(Entity.class);
            when(dummy.getUUID()).thenReturn(UUID.randomUUID());
            when(dummy.getId()).thenReturn(1000 + i);
            source.entityIdentityLedger().admit(dummy);
        }
        var old = this.prepare(player, Phase.CONNECTION);
        this.engine.removeRegionEntity(player);
        source.entityIdentityLedger().publishDeparting(player);
        source.entityIdentityLedger().publishGone(player);
        when(player.level()).thenReturn(destination);
        when(player.serverLevel()).thenReturn(destination);
        this.publish(destination, player);
        var fresh = this.prepare(player, Phase.CONNECTION);
        assertTrue(fresh.lifecycleGeneration() > old.lifecycleGeneration());
        assertFalse(this.engine.playerTicks().admit(old));
        assertTrue(this.engine.playerTicks().admit(fresh));
        assertTrue(this.engine.playerTicks().executeSerial(fresh, () -> {}));
    }

    @Test
    void sameRegionTeleportInvalidatesPreparedPlanBeforeAnyPositionChange() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var old = this.prepare(player, Phase.CONNECTION);
        assertTrue(this.engine.playerTicks().admit(old));
        RegionThreadingHooks.invalidatePlayerTickPlans(player);
        assertFalse(this.engine.playerTicks().executeSerial(old, () -> fail("Old teleport callback executed")));
        assertEquals(State.CANCELLED, old.state());
        var fresh = this.prepare(player, Phase.CONNECTION);
        assertTrue(fresh.lifecycleGeneration() > old.lifecycleGeneration());
        assertTrue(this.engine.playerTicks().admit(fresh));
        assertTrue(this.engine.playerTicks().executeSerial(fresh, () -> {}));
    }

    @Test
    void configurationTransitionBeforeStartCannotReuseGameListenerPlan() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.CONNECTION);
        assertTrue(this.engine.playerTicks().admit(plan));
        when(player.connection.isAcceptingMessages()).thenReturn(false);
        assertFalse(this.engine.playerTicks().executeSerial(plan, () -> fail("Configuration transition callback executed")));
        assertFalse(plan.started());
    }

    @Test
    void departingPublicationIsNotAdmissionEvenIfEntityStillReportsPresent() throws Exception {
        ServerLevel level = this.level(Level.OVERWORLD);
        ServerPlayer player = this.player(level, UUID.randomUUID());
        var plan = this.prepare(player, Phase.CONNECTION);
        assertTrue(this.engine.playerTicks().admit(plan));
        level.entityIdentityLedger().publishDeparting(player);
        assertFalse(this.engine.playerTicks().executeSerial(plan, () -> fail("Departing publication ran")));
        assertFalse(plan.started());
        assertNull(this.engine.playerTicks().prepare(player, player.connection.getConnection(), Phase.ENTITY));
    }

    @Test
    void numericIdMustMatchTheOriginalLedgerPublication() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(plan));
        int oldId = player.getId();
        when(player.getId()).thenReturn(oldId + 1000);
        assertFalse(this.engine.playerTicks().executeSerial(plan, () -> fail("Mismatched numeric identity ran")));
        assertNull(this.engine.playerTicks().prepare(player, player.connection.getConnection(), Phase.CONNECTION));
    }

    @Test
    void stateIsRecheckedAfterAdmissionBeforeFirstEffect() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.CONNECTION);
        assertTrue(this.engine.playerTicks().admit(plan));
        when(player.connection.getConnection().isConnected()).thenReturn(false);
        AtomicInteger effects = new AtomicInteger();
        assertFalse(this.engine.playerTicks().executeSerial(plan, effects::incrementAndGet));
        assertEquals(0, effects.get());
        assertEquals(State.REJECTED, plan.state());
        assertFalse(plan.started());
    }

    @Test
    void cancelBeforeStartDropsReferencesAndDoesNotExecute() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(plan));
        assertTrue(this.engine.playerTicks().cancel(plan));
        assertFalse(this.engine.playerTicks().cancel(plan));
        assertFalse(this.engine.playerTicks().executeSerial(plan, () -> fail("Cancelled body ran")));
        assertFalse(plan.retainsLiveBinding());
    }

    @Test
    void startedFailureIsTheOriginalThrowableAndCannotReplay() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(plan));
        RuntimeException original = new RuntimeException("after-effect");
        AtomicInteger effects = new AtomicInteger();
        assertSame(original, assertThrows(RuntimeException.class, () -> this.engine.playerTicks().executeSerial(plan, () -> {
            effects.incrementAndGet();
            assertFalse(this.engine.playerTicks().cancel(plan));
            throw original;
        })));
        assertThrows(IllegalStateException.class, () -> this.engine.playerTicks().executeSerial(plan, effects::incrementAndGet));
        assertEquals(1, effects.get());
        assertEquals(State.JOINED, plan.state());
        assertFalse(plan.retainsLiveBinding());
        assertEquals(1, this.engine.playerTicks().snapshot().phases().get(Phase.ENTITY).failed());
    }

    @Test
    void duplicatePhaseInSameTickDoesNotRunSecondEffect() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var first = this.prepare(player, Phase.ENTITY);
        var second = this.prepare(player, Phase.ENTITY);
        assertTrue(this.engine.playerTicks().admit(first));
        assertTrue(this.engine.playerTicks().admit(second));
        AtomicInteger effects = new AtomicInteger();
        assertTrue(this.engine.playerTicks().executeSerial(first, effects::incrementAndGet));
        assertFalse(this.engine.playerTicks().executeSerial(second, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test
    void historyIsBoundedAndShutdownRetiresPendingBindings() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        for (int i = 0; i < 300; i++) {
            this.tick.incrementAndGet();
            var plan = this.prepare(player, Phase.CONNECTION);
            assertTrue(this.engine.playerTicks().admit(plan));
            assertTrue(this.engine.playerTicks().executeSerial(plan, () -> {}));
        }
        assertEquals(256, this.engine.playerTicks().snapshot().recent().size());
        this.tick.incrementAndGet();
        var pending = this.prepare(player, Phase.CONNECTION);
        this.engine.shutdown();
        assertEquals(State.CANCELLED, pending.state());
        assertFalse(pending.retainsLiveBinding());
        assertEquals(0, this.engine.playerTicks().snapshot().owners());
    }

    @Test
    void originalWrapperRejectsLifecycleSelfWaitBeforeMutation() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        AtomicInteger effects = new AtomicInteger();
        this.engine.tickPlayerSerial(player, player.connection.getConnection(), Phase.CONNECTION, () -> {
            effects.incrementAndGet();
            assertThrows(IllegalStateException.class, this.engine::shutdown);
            assertThrows(IllegalStateException.class, () -> this.engine.clearLevelRegionTasks(player.serverLevel()));
        });
        assertEquals(1, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().pending());
        assertEquals(0, this.engine.playerTicks().snapshot().running());
    }

    @Test
    void foreignThreadCannotCaptureOrClaimEvenWithSameObjects() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var plan = this.prepare(player, Phase.ENTITY);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign = new Thread(() -> {
            try {
                this.engine.playerTicks().admit(plan);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        foreign.start();
        foreign.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(State.PREPARED, plan.state());
    }

    @Test
    void concurrentShutdownWaitsForPhysicalOriginalBodyReturn() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicBoolean retired = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            attempted.countDown();
            try {
                this.engine.shutdown();
                retired.set(true);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        AtomicInteger effects = new AtomicInteger();
        this.engine.tickPlayerSerial(player, player.connection.getConnection(), Phase.CONNECTION, () -> {
            effects.incrementAndGet();
            closer.start();
            try {
                assertTrue(attempted.await(1, TimeUnit.SECONDS));
                Thread.sleep(40);
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }
            assertFalse(retired.get(), "Cleanup cannot treat a still-running body as stopped");
            assertEquals(1, this.engine.playerTicks().snapshot().running());
            effects.incrementAndGet();
        });
        closer.join(3000);
        assertFalse(closer.isAlive());
        assertNull(failure.get());
        assertTrue(retired.get());
        assertEquals(2, effects.get());
        assertEquals(0, this.engine.playerTicks().snapshot().owners());
        assertEquals(0, this.engine.playerTicks().snapshot().pending());
    }

    @Test
    void levelRetirementInvalidatesUnstartedCapturedPlan() throws Exception {
        ServerLevel level = this.level(Level.OVERWORLD);
        ServerPlayer player = this.player(level, UUID.randomUUID());
        var plan = this.prepare(player, Phase.ENTITY);
        this.engine.clearLevelRegionTasks(level);
        assertFalse(this.engine.playerTicks().admit(plan));
        assertFalse(plan.retainsLiveBinding());
        assertEquals(0, this.engine.playerTicks().snapshot().owners());
    }

    @Test
    void nextTickExpiresUnstartedPlanWithoutKeepingItsPlayer() throws Exception {
        ServerPlayer player = this.player(this.level(Level.OVERWORLD), UUID.randomUUID());
        var old = this.prepare(player, Phase.ENTITY);
        this.tick.incrementAndGet();
        this.prepare(player, Phase.CONNECTION);
        assertEquals(State.CANCELLED, old.state());
        assertFalse(old.retainsLiveBinding());
    }
}
