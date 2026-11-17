/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.server.threading.region.RegionPlayerAdmissionPlan;
import org.junit.jupiter.api.Test;

class RegionPlayerExtensionSnapshotTest {
    private static Object call(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        var method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner instanceof Class<?> ? null : owner, args);
    }

    private record Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail) {}

    private static Check inspect(String name, Class<?>[] types, Object... args) throws Exception {
        var result = call(Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerExtensionSnapshot"), name, types, args);
        return new Check((RegionPlayerAdmissionPlan.Refusal) call(result, "refusal", new Class<?>[0]), (String) call(result, "detail", new Class<?>[0]));
    }

    private static class RegionPlayerExtensionSnapshot {
        static Check installed(Set<String> mods, Set<String> configs, Set<String> providers) throws Exception {
            return inspect("installed", new Class<?>[] { Set.class, Set.class, Set.class }, mods, configs, providers);
        }

        static Check listeners(IEventBus bus) throws Exception {
            return inspect("listeners", new Class<?>[] { IEventBus.class }, bus);
        }
    }

    private static class Policy {
        private final Object delegate;

        Policy() throws Exception {
            var constructor = Class.forName("net.neoforged.neoforge.server.threading.region.RegionEntityTickPolicy").getDeclaredConstructor();
            constructor.setAccessible(true);
            delegate = constructor.newInstance();
        }

        void declarePlayerRidingStack() throws Exception {
            call(delegate, "declarePlayerRidingStack", new Class<?>[0]);
        }

        void serverThreadOnly(Class<?> type, String reason) throws Exception {
            call(delegate, "serverThreadOnly", new Class<?>[] { Class.class, String.class }, type, reason);
        }

        void serverThreadOnlyType(EntityType<?> type, String reason) throws Exception {
            call(delegate, "serverThreadOnlyType", new Class<?>[] { EntityType.class, String.class }, type, reason);
        }

        Object reasonFor(Entity entity) throws Exception {
            return call(delegate, "reasonFor", new Class<?>[] { Entity.class }, entity);
        }

        Object playerReasonFor(Entity entity) throws Exception {
            return call(delegate, "playerReasonFor", new Class<?>[] { Entity.class }, entity);
        }
    }

    @Test
    void productionEnvironmentRequiresBothBaseModsAndRejectsEveryUnknownMod() throws Exception {
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of("neoforge.mixins.json", "mixinextras.init.mixins.json"), Set.of()).refusal());
        for (String mod : Set.of("testframework", "forgia_player_oracle", "create", "claimed_thread_safe")) {
            var result = RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge", mod), Set.of(), Set.of());
            assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD, result.refusal());
            assertEquals(mod, result.detail());
        }
        assertEquals(RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, RegionPlayerExtensionSnapshot.installed(Set.of(), Set.of(), Set.of()).refusal());
    }

    @Test
    void unknownMixinAndTransformationProviderRejectEvenWithoutUnknownModId() throws Exception {
        for (String config : Set.of("other.mixins.json", "oracle.mixins.json")) {
            assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of(config), Set.of()).refusal());
        }
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of(), Set.of("third.party.CoreMod")).refusal());
    }

    @Test
    void actualBusIncludesInheritedAndNonTickListenersAndRechecksUnregistration() throws Exception {
        var bus = BusBuilder.builder().build();
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<LivingBreatheEvent> breathe = event -> {
            throw new AssertionError("inspection must not run callbacks");
        };
        bus.addListener(LivingBreatheEvent.class, breathe);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(breathe);
        Consumer<PlayerEvent> player = event -> {};
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> bus.addListener(PlayerEvent.class, player));
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<AdvancementEvent.AdvancementProgressEvent> advancement = event -> {};
        bus.addListener(AdvancementEvent.AdvancementProgressEvent.class, advancement);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(advancement);
        Consumer<PlayerTickEvent.Post> post = event -> {};
        bus.addListener(PlayerTickEvent.Post.class, post);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(post);
        Consumer<Event> all = event -> {};
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> bus.addListener(Event.class, all));
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
    }

    @Test
    void onlyExactBuiltInEntityRegistrationIsApprovedAndConsumerOrSubclassStillRejects() throws Exception {
        var bus = BusBuilder.builder().build();
        var builtIn = new net.neoforged.neoforge.common.NeoForgeEventHandler();
        bus.register(builtIn);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Pre> unknown = event -> {
            throw new AssertionError("inspection must not invoke callbacks");
        };
        bus.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class, unknown);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(unknown);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(builtIn);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.register(new net.neoforged.neoforge.common.NeoForgeEventHandler() {}));
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
    }

    @Test
    void onlyOriginalInheritedRidingDeclarationCanBeBypassed() throws Exception {
        var policy = new Policy();
        policy.declarePlayerRidingStack();
        ServerPlayer player = mock(ServerPlayer.class);
        org.mockito.Mockito.doReturn(EntityType.PLAYER).when(player).getType();
        assertEquals("player_riding_stack", policy.reasonFor(player));
        assertEquals(null, policy.playerReasonFor(player));
        policy.serverThreadOnly(ServerPlayer.class, "exact_class");
        assertEquals("exact_class", policy.playerReasonFor(player));
        policy.serverThreadOnlyType(EntityType.PLAYER, "exact_type");
        assertEquals("exact_type", policy.playerReasonFor(player));
    }

    @Test
    void broadExplicitAndReplacedPlayerDeclarationsHavePriorityRegardlessOfReasonToken() throws Exception {
        ServerPlayer player = mock(ServerPlayer.class);
        var policy = new Policy();
        policy.declarePlayerRidingStack();
        policy.serverThreadOnly(Entity.class, "all_entities");
        assertEquals("all_entities", policy.playerReasonFor(player));
        var replaced = new Policy();
        replaced.declarePlayerRidingStack();
        replaced.serverThreadOnly(Player.class, "player_riding_stack");
        assertNotNull(replaced.playerReasonFor(player));
        when(player.isPassenger()).thenReturn(true);
        assertEquals("player_riding_stack", replaced.reasonFor(player));
        assertEquals("player_riding_stack", replaced.playerReasonFor(player));
    }
}
