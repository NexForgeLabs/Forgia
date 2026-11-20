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

    private record Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail, boolean hasExtraMods) {}

    private static Check inspect(String name, Class<?>[] types, Object... args) throws Exception {
        var result = call(Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerExtensionSnapshot"), name, types, args);
        return new Check(
                (RegionPlayerAdmissionPlan.Refusal) call(result, "refusal", new Class<?>[0]),
                (String) call(result, "detail", new Class<?>[0]),
                (boolean) call(result, "hasExtraMods", new Class<?>[0]));
    }

    private static Object mixinObservation(String name, boolean prepared, boolean hasPlugin, Set<String> targets) throws Exception {
        var type = Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerExtensionSnapshot$MixinObservation");
        var ctor = type.getDeclaredConstructor(String.class, boolean.class, boolean.class, Set.class);
        ctor.setAccessible(true);
        return ctor.newInstance(name, prepared, hasPlugin, targets);
    }

    private static class RegionPlayerExtensionSnapshot {
        static Check installed(Set<String> mods, Set<String> configs, Set<String> providers) throws Exception {
            return inspect("installed", new Class<?>[] { Set.class, Set.class, Set.class }, mods, configs, providers);
        }

        static Check installedObservations(Set<String> mods, java.util.Collection<?> configs, Set<String> providers) throws Exception {
            return inspect("installedObservations", new Class<?>[] { Set.class, java.util.Collection.class, Set.class }, mods, configs, providers);
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
    void productionEnvironmentRequiresBothBaseModsAndAllowsExtraModIdsWhenSurfaceIsClean() throws Exception {
        var vanilla = RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of("neoforge.mixins.json", "mixinextras.init.mixins.json"), Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, vanilla.refusal());
        assertEquals(false, vanilla.hasExtraMods());
        for (String mod : Set.of("testframework", "forgia_player_oracle", "create", "claimed_thread_safe")) {
            var result = RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge", mod), Set.of(), Set.of());
            assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, result.refusal());
            assertEquals(true, result.hasExtraMods());
        }
        assertEquals(RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, RegionPlayerExtensionSnapshot.installed(Set.of(), Set.of(), Set.of()).refusal());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft"), Set.of(), Set.of()).refusal());
    }

    @Test
    void unknownMixinAndTransformationProviderRejectEvenWithoutUnknownModId() throws Exception {
        for (String config : Set.of("other.mixins.json", "oracle.mixins.json")) {
            assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of(config), Set.of()).refusal());
        }
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, RegionPlayerExtensionSnapshot.installed(Set.of("minecraft", "neoforge"), Set.of(), Set.of("third.party.CoreMod")).refusal());
    }

    @Test
    void nonWhitelistedMixinConfigsAreEvaluatedByTargetClassAndPluginState() throws Exception {
        Set<String> mods = Set.of("minecraft", "neoforge", "examplemod");
        // Mod-owned targets only (dot and internal slash notation) -> admitted.
        var modOnly = RegionPlayerExtensionSnapshot.installedObservations(
                mods,
                java.util.List.of(mixinObservation("examplemod.mixins.json", true, false, Set.of("com.example.mod.Foo", "com/example/mod/Bar"))),
                Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, modOnly.refusal());
        assertEquals(true, modOnly.hasExtraMods());

        // Prepared config with empty target set (e.g. client-only mixins on dedicated server) -> admitted.
        var emptyPrepared = RegionPlayerExtensionSnapshot.installedObservations(
                mods,
                java.util.List.of(mixinObservation("clientonly.mixins.json", true, false, Set.of())),
                Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, emptyPrepared.refusal());

        // Protected package prefixes -> refused.
        for (String target : Set.of(
                "net.minecraft.server.level.ServerPlayer",
                "net/minecraft/world/entity/player/Player",
                "com.mojang.authlib.GameProfile",
                "net.neoforged.neoforge.common.NeoForge",
                "java.lang.String",
                "org.spongepowered.asm.mixin.Mixins",
                "cpw.mods.modlauncher.Launcher")) {
            var refused = RegionPlayerExtensionSnapshot.installedObservations(
                    mods,
                    java.util.List.of(mixinObservation("touching.mixins.json", true, false, Set.of("com.example.mod.Safe", target))),
                    Set.of());
            assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, refused.refusal(), "Expected refusal for target " + target);
            assertEquals("touching.mixins.json", refused.detail());
        }

        // Plugin present -> refused.
        var withPlugin = RegionPlayerExtensionSnapshot.installedObservations(
                mods,
                java.util.List.of(mixinObservation("plugin.mixins.json", true, true, Set.of("com.example.mod.Foo"))),
                Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, withPlugin.refusal());

        // Unprepared config -> refused (fail-closed).
        var unprepared = RegionPlayerExtensionSnapshot.installedObservations(
                mods,
                java.util.List.of(mixinObservation("unprepared.mixins.json", false, false, Set.of("com.example.mod.Foo"))),
                Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unprepared.refusal());

        // Null target set -> refused (fail-closed).
        var unreadable = RegionPlayerExtensionSnapshot.installedObservations(
                mods,
                java.util.List.of(mixinObservation("unreadable.mixins.json", true, false, null)),
                Set.of());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unreadable.refusal());
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
    void localEntityProfileDoesNotAdmitAnEntityTickListener() throws Exception {
        var bus = BusBuilder.builder().build();
        Consumer<PlayerTickEvent.Post> playerTick = event -> {};
        bus.addListener(PlayerTickEvent.Post.class, playerTick);
        var signature = new Class<?>[] { IEventBus.class, boolean.class };
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listeners", signature, bus, true).refusal());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, inspect("listeners", signature, bus, false).refusal());
        Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Post> entityTick = event -> {};
        bus.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Post.class, entityTick);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, inspect("listeners", signature, bus, true).refusal());
        bus.unregister(entityTick);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listeners", signature, bus, true).refusal());
    }

    @Test
    void connectionBodyListenerProfileScopingForVanillaGameEventAndItemStackedOnOther() throws Exception {
        var bus = BusBuilder.builder().build();
        var connBodySig = new Class<?>[] { IEventBus.class, boolean.class };
        Consumer<net.neoforged.neoforge.event.ItemStackedOnOtherEvent> stacked = event -> {};
        bus.addListener(net.neoforged.neoforge.event.ItemStackedOnOtherEvent.class, stacked);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listenersConnectionBody", connBodySig, bus, false).refusal());
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listenersConnectionBody", connBodySig, bus, true).refusal());

        Consumer<net.neoforged.neoforge.event.VanillaGameEvent> gameEvent = event -> {};
        bus.addListener(net.neoforged.neoforge.event.VanillaGameEvent.class, gameEvent);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listenersConnectionBody", connBodySig, bus, false).refusal());
        var refusedGameEvent = inspect("listenersConnectionBody", connBodySig, bus, true);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, refusedGameEvent.refusal());
        org.junit.jupiter.api.Assertions.assertTrue(refusedGameEvent.detail().contains("VanillaGameEvent"));

        Consumer<PlayerTickEvent.Pre> playerTick = event -> {};
        bus.addListener(PlayerTickEvent.Pre.class, playerTick);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, inspect("listenersConnectionBody", connBodySig, bus, false).refusal());
        bus.unregister(playerTick);
        bus.unregister(gameEvent);
        bus.unregister(stacked);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, inspect("listenersConnectionBody", connBodySig, bus, true).refusal());
    }

    @Test
    void onlyExactBuiltInEntityRegistrationIsApprovedAndConsumerOrSubclassStillRejects() throws Exception {
        var bus = BusBuilder.builder().build();
        var builtIn = new net.neoforged.neoforge.common.NeoForgeEventHandler();
        bus.register(builtIn);
        bus.register(net.neoforged.neoforge.attachment.AttachmentSync.class);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<net.neoforged.neoforge.event.tick.EntityTickEvent.Pre> unknown = event -> {
            throw new AssertionError("inspection must not invoke callbacks");
        };
        bus.addListener(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre.class, unknown);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(unknown);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent> unknownLeave = event -> {
            throw new AssertionError("inspection must not invoke removal callbacks");
        };
        bus.addListener(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent.class, unknownLeave);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(unknownLeave);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        Consumer<net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent> unknownSent = event -> {
            throw new AssertionError("inspection must not invoke chunk sent callbacks");
        };
        bus.addListener(net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent.class, unknownSent);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(unknownSent);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(net.neoforged.neoforge.attachment.AttachmentSync.class);
        bus.unregister(builtIn);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> bus.register(new net.neoforged.neoforge.common.NeoForgeEventHandler() {}));
        assertEquals(RegionPlayerAdmissionPlan.Refusal.NONE, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
    }

    @Test
    void customModAttributeSubclassesAreRejectedByPlayerStatePlan() throws Exception {
        net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> ranged = net.minecraft.core.Holder.direct(new net.minecraft.world.entity.ai.attributes.RangedAttribute("test.ranged", 1.0, 0.0, 10.0));
        net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> custom = net.minecraft.core.Holder.direct(new net.minecraft.world.entity.ai.attributes.Attribute("test.custom", 1.0) {});
        var safeSupplier = net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder().add(ranged).build();
        var safeMap = new net.minecraft.world.entity.ai.attributes.AttributeMap(safeSupplier);
        safeMap.getInstance(ranged);
        var checkMethod = Class.forName("net.neoforged.neoforge.server.threading.region.RegionPlayerStatePlan")
                .getDeclaredMethod("knownPlayerAttributes", net.minecraft.world.entity.ai.attributes.AttributeMap.class);
        checkMethod.setAccessible(true);
        assertEquals(true, checkMethod.invoke(null, safeMap));

        var customSupplier = net.minecraft.world.entity.ai.attributes.AttributeSupplier.builder().add(ranged).add(custom).build();
        var customMap = new net.minecraft.world.entity.ai.attributes.AttributeMap(customSupplier);
        customMap.getInstance(ranged);
        customMap.getInstance(custom);
        assertEquals(false, checkMethod.invoke(null, customMap));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "ServerChatEvent", "ItemStackedOnOtherEvent", "level.ChunkWatchEvent$Watch", "level.ChunkWatchEvent$Sent", "level.ChunkWatchEvent$UnWatch",
            "level.ChunkTicketLevelUpdatedEvent", "entity.player.PlayerEvent$StartTracking", "entity.player.PlayerEvent$StopTracking",
            "entity.EntityEvent$EnteringSection", "level.BlockEvent$NeighborNotifyEvent"
    })
    @SuppressWarnings({ "rawtypes", "unchecked" })
    void packetAndSynchronousPublisherServiceListenersRefuseBeforeOwnerEffects(String name) throws Exception {
        var bus = BusBuilder.builder().build();
        Class event = Class.forName("net.neoforged.neoforge.event." + name);
        Consumer<Event> unknown = value -> {
            throw new AssertionError("Capture cannot invoke a callback");
        };
        bus.addListener(event, unknown);
        assertEquals(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, RegionPlayerExtensionSnapshot.listeners(bus).refusal());
        bus.unregister(unknown);
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
