/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mojang.datafixers.DataFixer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionPolicy;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.neoforge.server.threading.region.RegionWorkerExecutor;
import net.neoforged.neoforge.server.threading.region.RegionWorkerScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original trigger/progress/reward component tests; these do not certify a complete player worker tick. */
@Timeout(30)
class PlayerCriterionListenersTest {
    @TempDir
    Path temporary;

    @BeforeEach
    void enableOwnerStorage() {
        RegionThreadingEngine engine = new RegionThreadingEngine(1, false, false, true, false, false, false,
                RegionPolicy.DYNAMIC_CLUSTER, RegionEntityStorageMode.REGION_OWNED);
        RegionThreadingManager.install(engine);
        engine.configurePlayerTickPlanningForTesting(true);
    }

    @AfterEach
    void retireEngine() {
        RegionThreadingManager.clear();
    }

    private record Fixture(ServerPlayer player, PlayerAdvancements advancements, ServerAdvancementManager manager,
            AdvancementHolder advancement, AtomicInteger experience) {}

    private static final class SelectingTrigger extends PlayerTrigger {
        void select(ServerPlayer player, Predicate<TriggerInstance> predicate) {
            this.trigger(player, predicate);
        }
    }

    private Fixture fixture(PlayerTrigger trigger) {
        var advancement = Advancement.Builder.advancement()
                .addCriterion("tick", trigger.createCriterion(new PlayerTrigger.TriggerInstance(Optional.empty())))
                .rewards(AdvancementRewards.Builder.experience(7))
                .build(ResourceLocation.fromNamespaceAndPath("forgia_player_test", "tick"));
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(level.getServer()).thenReturn(mock(MinecraftServer.class, org.mockito.Answers.RETURNS_DEEP_STUBS));
        when(player.serverLevel()).thenReturn(level);
        when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(Vec3.ZERO);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(level.getRandom()).thenReturn(RandomSource.create(9));
        ServerAdvancementManager manager = mock(ServerAdvancementManager.class);
        when(manager.tree()).thenReturn(new AdvancementTree());
        when(manager.getAllAdvancements()).thenReturn(List.of(advancement));
        AtomicInteger experience = new AtomicInteger();
        doAnswer(call -> {
            experience.addAndGet(call.getArgument(0));
            return null;
        }).when(player).giveExperiencePoints(org.mockito.ArgumentMatchers.anyInt());
        PlayerAdvancements advancements = new PlayerAdvancements(mock(DataFixer.class), mock(PlayerList.class), manager,
                this.temporary.resolve(UUID.randomUUID() + ".json"), player);
        when(player.getAdvancements()).thenReturn(advancements);
        return new Fixture(player, advancements, manager, advancement, experience);
    }

    @Test
    void disabledOwnersKeepOriginalMapAndCapturedModeSurvivesEngineRetirement() throws Exception {
        RegionThreadingManager.clear();
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture serial = this.fixture(trigger);
        assertFalse(serial.advancements.forgiaCriterionListeners().enabled());
        var playersField = net.minecraft.advancements.critereon.SimpleCriterionTrigger.class.getDeclaredField("players");
        playersField.setAccessible(true);
        var original = (java.util.Map<?, ?>) playersField.get(trigger);
        assertTrue(original.containsKey(serial.advancements));
        assertNull(serial.advancements.forgiaCriterionListeners().get(trigger));
        this.enableOwnerStorage();
        Fixture owned = this.fixture(trigger);
        assertTrue(owned.advancements.forgiaCriterionListeners().enabled());
        assertFalse(original.containsKey(owned.advancements));
        RegionThreadingManager.clear();
        trigger.trigger(serial.player);
        trigger.trigger(owned.player);
        assertEquals(7, serial.experience.get());
        assertEquals(7, owned.experience.get());
        assertFalse(original.containsKey(serial.advancements));
        assertNull(owned.advancements.forgiaCriterionListeners().get(trigger));
    }

    @Test
    void requestedModeWithoutEffectiveEntityAndStorageDependenciesKeepsOriginalMap() throws Exception {
        RegionThreadingManager.clear();
        RegionThreadingEngine engine = new RegionThreadingEngine(1, false, false);
        RegionThreadingManager.install(engine);
        engine.configurePlayerTickPlanningForTesting(true);
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture fixture = this.fixture(trigger);
        assertFalse(fixture.advancements.forgiaCriterionListeners().enabled());
        var field = net.minecraft.advancements.critereon.SimpleCriterionTrigger.class.getDeclaredField("players");
        field.setAccessible(true);
        assertTrue(((java.util.Map<?, ?>) field.get(trigger)).containsKey(fixture.advancements));
        trigger.trigger(fixture.player);
        assertEquals(7, fixture.experience.get());
        assertNull(fixture.advancements.forgiaCriterionListeners().get(trigger));
    }

    @Test
    void sharedGlobalTriggerUsesDifferentOriginalOwnerSetsAndRewardsOnce() {
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture first = this.fixture(trigger);
        Fixture second = this.fixture(trigger);
        var firstSet = first.advancements.forgiaCriterionListeners().get(trigger);
        var secondSet = second.advancements.forgiaCriterionListeners().get(trigger);
        assertNotSame(firstSet, secondSet);
        assertEquals(1, firstSet.size());
        assertEquals(1, secondSet.size());
        trigger.trigger(first.player);
        assertEquals(7, first.experience.get());
        assertEquals(0, second.experience.get());
        assertNull(first.advancements.forgiaCriterionListeners().get(trigger));
        assertEquals(1, second.advancements.forgiaCriterionListeners().get(trigger).size());
        trigger.trigger(first.player);
        assertEquals(7, first.experience.get(), "No replay after original progress unregistered the criterion");
        trigger.trigger(second.player);
        assertEquals(7, second.experience.get());
        assertTrue(first.advancements.getOrStartProgress(first.advancement).isDone());
        assertTrue(second.advancements.getOrStartProgress(second.advancement).isDone());
    }

    @Test
    void revokeAndReloadUseOriginalSynchronousProgressAndRegistration() {
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture fixture = this.fixture(trigger);
        trigger.trigger(fixture.player);
        assertEquals(7, fixture.experience.get());
        assertTrue(fixture.advancements.revoke(fixture.advancement, "tick"));
        assertEquals(1, fixture.advancements.forgiaCriterionListeners().get(trigger).size());
        trigger.trigger(fixture.player);
        assertEquals(14, fixture.experience.get());
        fixture.advancements.reload(fixture.manager);
        assertFalse(fixture.advancements.getOrStartProgress(fixture.advancement).isDone());
        assertEquals(1, fixture.advancements.forgiaCriterionListeners().get(trigger).size());
        trigger.trigger(fixture.player);
        assertEquals(21, fixture.experience.get());
        fixture.advancements.stopListening();
        assertNull(fixture.advancements.forgiaCriterionListeners().get(trigger));
    }

    @Test
    void listenerDuplicateAndRemovalRetainOriginalSetIdentityAndSemantics() {
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture fixture = this.fixture(trigger);
        var set = fixture.advancements.forgiaCriterionListeners().get(trigger);
        CriterionTrigger.Listener<PlayerTrigger.TriggerInstance> listener = set.iterator().next();
        trigger.addPlayerListener(fixture.advancements, listener);
        assertSame(set, fixture.advancements.forgiaCriterionListeners().get(trigger));
        assertEquals(1, set.size());
        trigger.removePlayerListener(fixture.advancements, listener);
        assertNull(fixture.advancements.forgiaCriterionListeners().get(trigger));
        trigger.removePlayerListener(fixture.advancements, listener);
        trigger.removePlayerListeners(fixture.advancements);
        trigger.addPlayerListener(fixture.advancements, listener);
        assertNotSame(set, fixture.advancements.forgiaCriterionListeners().get(trigger));
        trigger.trigger(fixture.player);
        assertEquals(7, fixture.experience.get());
    }

    @Test
    void predicateExceptionRemainsSynchronousAndDoesNotAwardOrLoseRegistration() {
        SelectingTrigger trigger = new SelectingTrigger();
        Fixture fixture = this.fixture(trigger);
        RuntimeException original = new RuntimeException("original predicate");
        AtomicInteger beforeFailure = new AtomicInteger();
        assertSame(original, assertThrows(RuntimeException.class, () -> trigger.select(fixture.player, instance -> {
            beforeFailure.incrementAndGet();
            throw original;
        })));
        assertEquals(1, beforeFailure.get());
        assertEquals(0, fixture.experience.get());
        assertEquals(1, fixture.advancements.forgiaCriterionListeners().get(trigger).size());
        trigger.select(fixture.player, instance -> true);
        assertEquals(7, fixture.experience.get());
    }

    @Test
    void reentrantPredicateRemovalPreservesOriginalIterationException() {
        SelectingTrigger trigger = new SelectingTrigger();
        Fixture fixture = this.fixture(trigger);
        var originalSet = fixture.advancements.forgiaCriterionListeners().get(trigger);
        var extra = new CriterionTrigger.Listener<>(new PlayerTrigger.TriggerInstance(Optional.empty()), fixture.advancement, "extra");
        trigger.addPlayerListener(fixture.advancements, extra);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(java.util.ConcurrentModificationException.class, () -> trigger.select(fixture.player, instance -> {
            calls.incrementAndGet();
            trigger.removePlayerListener(fixture.advancements, extra);
            // Remove the whole original set's entries, so the iterator's next step must observe the change.
            originalSet.clear();
            return true;
        }));
        assertEquals(1, calls.get());
        assertEquals(0, fixture.experience.get());
    }

    @Test
    void playerRebindKeepsTheOriginalAdvancementOwnerStorage() {
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture fixture = this.fixture(trigger);
        ServerPlayer newPlayer = mock(ServerPlayer.class);
        when(newPlayer.getAdvancements()).thenReturn(fixture.advancements);
        ServerLevel originalLevel = fixture.player.serverLevel();
        when(newPlayer.serverLevel()).thenReturn(originalLevel);
        when(newPlayer.level()).thenReturn(originalLevel);
        when(newPlayer.position()).thenReturn(Vec3.ZERO);
        AtomicInteger newExperience = new AtomicInteger();
        doAnswer(call -> {
            newExperience.addAndGet(call.getArgument(0));
            return null;
        }).when(newPlayer).giveExperiencePoints(org.mockito.ArgumentMatchers.anyInt());
        var set = fixture.advancements.forgiaCriterionListeners().get(trigger);
        fixture.advancements.setPlayer(newPlayer);
        assertSame(set, fixture.advancements.forgiaCriterionListeners().get(trigger));
        trigger.trigger(newPlayer);
        assertEquals(0, fixture.experience.get());
        assertEquals(7, newExperience.get());
    }

    @Test
    void thirtyTwoOriginalOwnersCanRegisterAndSelectWithoutSharedTriggerMutation() throws Exception {
        SelectingTrigger trigger = new SelectingTrigger();
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 32; i++) fixtures.add(this.fixture(trigger));
        CountDownLatch ready = new CountDownLatch(fixtures.size());
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    var listener = fixture.advancements.forgiaCriterionListeners().get(trigger).iterator().next();
                    for (int i = 0; i < 300; i++) {
                        trigger.removePlayerListener(fixture.advancements, listener);
                        assertNull(fixture.advancements.forgiaCriterionListeners().get(trigger));
                        trigger.addPlayerListener(fixture.advancements, listener);
                        AtomicInteger visits = new AtomicInteger();
                        trigger.select(fixture.player, instance -> {
                            visits.incrementAndGet();
                            return false;
                        });
                        assertEquals(1, visits.get());
                    }
                    assertEquals(0, fixture.experience.get());
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            threads.add(thread);
            thread.start();
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread thread : threads) thread.join(10000);
        assertTrue(threads.stream().noneMatch(Thread::isAlive));
        assertNull(failure.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void realNativeWorkersStillCannotTreatLocalStorageAsPermission(boolean persistent) throws Exception {
        PlayerTrigger trigger = CriteriaTriggers.TICK;
        Fixture fixture = this.fixture(trigger);
        var listener = fixture.advancements.forgiaCriterionListeners().get(trigger).iterator().next();
        AtomicInteger refusals = new AtomicInteger();
        CountDownLatch complete = new CountDownLatch(1);
        var constructor = Class.forName("net.neoforged.neoforge.server.threading.region.RegionWorkerPool").getDeclaredConstructor(int.class, boolean.class);
        constructor.setAccessible(true);
        try (AutoCloseable pool = (AutoCloseable) constructor.newInstance(1, persistent)) {
            RegionWorkerExecutor workers = (RegionWorkerExecutor) pool;
            assertTrue(workers.execute("player criterion storage refusal", () -> {
                try {
                    assertTrue(RegionWorkerScope.isNativeWorkerThread());
                    for (Runnable mutation : List.<Runnable>of(
                            () -> trigger.trigger(fixture.player),
                            () -> trigger.addPlayerListener(fixture.advancements, listener),
                            () -> trigger.removePlayerListener(fixture.advancements, listener),
                            () -> trigger.removePlayerListeners(fixture.advancements))) {
                        assertThrows(IllegalStateException.class, mutation::run);
                        refusals.incrementAndGet();
                    }
                } finally {
                    complete.countDown();
                }
            }));
            assertTrue(complete.await(5, TimeUnit.SECONDS));
        }
        assertEquals(4, refusals.get());
        assertEquals(0, fixture.experience.get());
        assertEquals(1, fixture.advancements.forgiaCriterionListeners().get(trigger).size());
    }
}
