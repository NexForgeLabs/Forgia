/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

/** Installed environment for complete player ticks/vanilla packets; not an opt-in payload declaration. */
final class RegionPlayerExtensionSnapshot {
    record Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail) {
        static Check ok() {
            return OK;
        }
    }

    // Both complete calls and audited packets/services share this deliberately conservative union. ListenerList includes inherited
    // registrations (including Event/EntityEvent/PlayerEvent), not just listeners on a concrete event.
    private static final List<String> EVENTS = List.of(
            "tick.EntityTickEvent$Pre", "tick.EntityTickEvent$Post",
            "tick.PlayerTickEvent$Pre", "tick.PlayerTickEvent$Post",
            "entity.living.LivingBreatheEvent", "entity.living.LivingDrownEvent",
            "entity.EntityInvulnerabilityCheckEvent", "entity.living.LivingIncomingDamageEvent",
            "entity.living.LivingShieldBlockEvent", "entity.living.LivingDamageEvent$Pre", "entity.living.LivingDamageEvent$Post",
            "VanillaGameEvent", "PlayLevelSoundEvent$AtPosition", "PlayLevelSoundEvent$AtEntity",
            "entity.living.LivingHealEvent", "entity.living.LivingEquipmentChangeEvent",
            "entity.living.LivingEvent$LivingJumpEvent", "entity.living.LivingFallEvent",
            "entity.EntityEvent$Size", "entity.player.PlayerFlyableFallEvent",
            "entity.player.PlayerXpEvent$XpChange", "entity.player.PlayerXpEvent$LevelChange",
            "entity.player.ItemEntityPickupEvent$Pre", "entity.player.ItemEntityPickupEvent$Post",
            "entity.player.PlayerXpEvent$PickupXp", "entity.EntityLeaveLevelEvent", "entity.player.AdvancementEvent$AdvancementEarnEvent",
            "entity.player.AdvancementEvent$AdvancementProgressEvent",
            "ServerChatEvent", "ItemStackedOnOtherEvent",
            "level.ChunkWatchEvent$Watch", "level.ChunkWatchEvent$UnWatch", "level.ChunkTicketLevelUpdatedEvent",
            "entity.player.PlayerEvent$StartTracking", "entity.player.PlayerEvent$StopTracking",
            "entity.EntityEvent$EnteringSection", "level.BlockEvent$NeighborNotifyEvent");

    private static final Set<String> SERVICES = Set.of(
            "cpw.mods.modlauncher.api.ITransformationService",
            "cpw.mods.modlauncher.serviceapi.ILaunchPluginService",
            "net.neoforged.neoforgespi.coremod.ICoreMod");
    private static final Set<String> PROVIDERS = Set.of(
            "net.neoforged.fml.loading.FMLServiceProvider",
            "net.neoforged.fml.loading.log4j.SLF4JFixerLaunchPluginService",
            "net.neoforged.fml.common.asm.RuntimeDistCleaner",
            "net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender",
            "net.neoforged.accesstransformer.ml.AccessTransformerService",
            "org.spongepowered.asm.launch.MixinTransformationService",
            "org.spongepowered.asm.launch.MixinLaunchPlugin",
            "org.spongepowered.asm.launch.MixinTransformationServiceLegacy",
            "org.spongepowered.asm.launch.MixinLaunchPluginLegacy",
            "net.neoforged.neoforge.coremods.NeoForgeCoreMod");
    private static final Set<String> CONFIGS = Set.of("neoforge.mixins.json", "mixinextras.init.mixins.json");

    private static final Check OK = new Check(RegionPlayerAdmissionPlan.Refusal.NONE, "");
    private static final Set<String> BASE_MODS = Set.of("minecraft", "neoforge");
    private static final List<MixinEnvironment.Phase> PHASES = List.of(MixinEnvironment.Phase.PREINIT, MixinEnvironment.Phase.INIT, MixinEnvironment.Phase.DEFAULT);
    // Module membership, parent layers and module descriptors are immutable after definition.
    private record ProviderSnapshot(ModuleLayer layer, Set<String> names, @org.jetbrains.annotations.Nullable String firstUnknown) {}
    private static volatile ProviderSnapshot providerSnapshot;

    private record BusAccess(Method lookup, Field registrations, Class<?>[] events, boolean[] allowsBuiltIn) {}
    private static volatile BusAccess busAccess;
    // Bus 8 installs each event's ListenerList with computeIfAbsent and never replaces it.
    // The list's live dispatch array still changes on registration/unregistration, including ancestors.
    private record BusLists(WeakReference<IEventBus> bus, ListenerList[] lists) {}
    private static volatile BusLists busLists;
    private record VerifiedListeners(WeakReference<IEventBus> bus, Object[] arrays, Check result) {}
    private static volatile VerifiedListeners verifiedListeners;

    private RegionPlayerExtensionSnapshot() {}

    static Check capture() {
        try {
            ModList mods = ModList.get();
            ModuleLayer layer = FMLLoader.getGameLayer();
            if (mods == null || layer == null) return unavailable("loader not ready");
            boolean minecraft = false, neoforge = false;
            String unknown = null;
            for (var mod : mods.getMods()) {
                String id = mod.getModId();
                if (id.equals("minecraft")) minecraft = true;
                else if (id.equals("neoforge")) neoforge = true;
                else if (unknown == null || id.compareTo(unknown) < 0) unknown = id;
            }
            if (!minecraft || !neoforge) return unavailable("base mods absent");
            if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD, unknown);
            for (var phase : PHASES) {
                for (String name : MixinEnvironment.getEnvironment(phase).getMixinConfigs()) unknown = unknownConfig(unknown, name);
            }
            for (var config : Mixins.getConfigs()) unknown = unknownConfig(unknown, config.getName());
            if (unknown == null) unknown = firstUnknownProvider(layer);
            if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unknown);
            return listeners(NeoForge.EVENT_BUS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Missing inspection dependencies are a pre-body serial decision. No original tick is caught.
            return unavailable(failure.getClass().getName());
        }
    }

    private static String unknownConfig(String first, String name) {
        return !CONFIGS.contains(name) && (first == null || name.compareTo(first) < 0) ? name : first;
    }

    @org.jetbrains.annotations.Nullable
    private static String firstUnknownProvider(ModuleLayer layer) {
        ProviderSnapshot snapshot = providerSnapshot;
        if (snapshot != null && snapshot.layer() == layer) return snapshot.firstUnknown();
        Set<String> providers = new HashSet<>();
        collectProviders(layer, new HashSet<>(), providers);
        Set<String> immutable = Set.copyOf(providers);
        String firstUnknown = firstUnknown(immutable, PROVIDERS);
        snapshot = new ProviderSnapshot(layer, immutable, firstUnknown);
        providerSnapshot = snapshot;
        return firstUnknown;
    }

    private static void collectProviders(ModuleLayer layer, Set<ModuleLayer> visited, Set<String> providers) {
        if (!visited.add(layer)) return;
        for (Module module : layer.modules()) {
            for (var service : module.getDescriptor().provides()) {
                if (SERVICES.contains(service.service())) providers.addAll(service.providers());
            }
        }
        layer.parents().forEach(parent -> collectProviders(parent, visited, providers));
    }

    /** Pure evaluation of observations; no configured mod id, hash or test fixture grants permission. */
    static Check installed(Set<String> mods, Set<String> configs, Set<String> providers) {
        if (!mods.containsAll(BASE_MODS)) return unavailable("base mods absent");
        String unknown = firstUnknown(mods, BASE_MODS);
        if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD, unknown);
        unknown = firstUnknown(configs, CONFIGS);
        if (unknown == null) unknown = firstUnknown(providers, PROVIDERS);
        if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unknown);
        return Check.ok();
    }

    @org.jetbrains.annotations.Nullable
    private static String firstUnknown(Set<String> actual, Set<String> known) {
        String first = null;
        for (String name : actual) {
            if (!known.contains(name) && (first == null || name.compareTo(first) < 0)) first = name;
        }
        return first;
    }

    private static BusAccess busAccess() throws ReflectiveOperationException {
        BusAccess access = busAccess;
        if (access != null) return access;
        Method lookup = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
        Field registrations = EventBus.class.getDeclaredField("listeners");
        if (!lookup.trySetAccessible() || !registrations.trySetAccessible()) throw new IllegalAccessException("Event bus inspection unavailable");
        int count = EVENTS.size();
        Class<?>[] events = new Class<?>[count];
        boolean[] allowsBuiltIn = new boolean[count];
        for (int i = 0; i < count; i++) {
            String name = EVENTS.get(i);
            events[i] = Class.forName("net.neoforged.neoforge.event." + name);
            allowsBuiltIn[i] = name.equals("tick.EntityTickEvent$Pre") || name.equals("entity.EntityLeaveLevelEvent");
        }
        access = new BusAccess(lookup, registrations, events, allowsBuiltIn);
        busAccess = access;
        return access;
    }

    private static ListenerList[] lists(IEventBus bus, BusAccess access) throws ReflectiveOperationException {
        BusLists cached = busLists;
        if (cached != null && cached.bus().get() == bus) return cached.lists();
        ListenerList[] lists = new ListenerList[access.events().length];
        for (int i = 0; i < lists.length; i++) lists[i] = (ListenerList) access.lookup().invoke(bus, access.events()[i]);
        busLists = new BusLists(new WeakReference<>(bus), lists);
        return lists;
    }

    static Check listeners(IEventBus bus) throws ReflectiveOperationException {
        if (bus.getClass() != EventBus.class) return unavailable("unknown event bus " + bus.getClass().getName());
        // Cache reflection metadata only. Owner registrations and inherited dispatch lists are live.
        // ListenerList.getListeners() returns a fresh array whenever any listener is added or removed,
        // so comparing every live array reference still observes all live registrations.
        BusAccess access = busAccess();
        ListenerList[] lists = lists(bus, access);
        VerifiedListeners verified = verifiedListeners;
        if (verified != null && verified.bus().get() == bus) {
            Object[] prev = verified.arrays();
            boolean same = true;
            for (int index = 0; index < lists.length; index++) {
                if (lists[index].getListeners() != prev[index]) {
                    same = false;
                    break;
                }
            }
            if (same) return verified.result();
        }
        Map<?, ?> owners = (Map<?, ?>) access.registrations().get(bus);
        Object[] snapshot = new Object[lists.length];
        boolean[] allowsBuiltIn = access.allowsBuiltIn();
        List<String> found = null;
        for (int index = 0; index < lists.length; index++) {
            var live = lists[index].getListeners();
            snapshot[index] = live;
            if (live.length == 0) continue;
            boolean builtInAllowed = allowsBuiltIn[index];
            for (var listener : live) {
                if (!builtInAllowed || !isBuiltInListener(owners, listener)) {
                    if (found == null) found = new ArrayList<>();
                    found.add(access.events()[index].getName());
                    break;
                }
            }
        }
        Check result = found == null ? Check.ok() : new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, String.join(",", found));
        verifiedListeners = new VerifiedListeners(new WeakReference<>(bus), snapshot, result);
        return result;
    }

    private static boolean isBuiltInListener(Map<?, ?> owners, Object listener) {
        // Bus 8 uses the registered wrapper for cancellable events and its exact unchecked
        // delegate for other dispatch arrays. Check the live owner map in both cases.
        for (var entry : owners.entrySet()) {
            if (entry.getKey().getClass() != NeoForgeEventHandler.class) continue;
            for (Object registration : (List<?>) entry.getValue()) {
                if (registration == listener || registration instanceof SubscribeEventListener wrapper && wrapper.getWithoutCheck() == listener) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Check unavailable(String detail) {
        return new Check(RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, detail);
    }
}
