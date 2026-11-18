/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

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

/** Actual installed environment, never a mod's self-declared worker permission. */
final class RegionPlayerExtensionSnapshot {
    record Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail) {
        static Check ok() {
            return new Check(RegionPlayerAdmissionPlan.Refusal.NONE, "");
        }
    }

    // Both complete calls share this deliberately conservative union. ListenerList includes inherited
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
            "entity.player.AdvancementEvent$AdvancementProgressEvent");

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

    private RegionPlayerExtensionSnapshot() {}

    static Check capture() {
        try {
            ModList mods = ModList.get();
            ModuleLayer layer = FMLLoader.getGameLayer();
            if (mods == null || layer == null) return unavailable("loader not ready");
            Set<String> ids = new HashSet<>();
            mods.getMods().forEach(mod -> ids.add(mod.getModId()));
            Set<String> configs = new HashSet<>();
            for (var phase : List.of(MixinEnvironment.Phase.PREINIT, MixinEnvironment.Phase.INIT, MixinEnvironment.Phase.DEFAULT)) {
                configs.addAll(MixinEnvironment.getEnvironment(phase).getMixinConfigs());
            }
            Mixins.getConfigs().forEach(config -> configs.add(config.getName()));
            Set<String> providers = new HashSet<>();
            collectProviders(layer, new HashSet<>(), providers);
            Check installed = installed(ids, configs, providers);
            if (installed.refusal() != RegionPlayerAdmissionPlan.Refusal.NONE) return installed;
            return listeners(NeoForge.EVENT_BUS);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Missing inspection dependencies are a pre-body serial decision. No original tick is caught.
            return unavailable(failure.getClass().getName());
        }
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
        if (!mods.containsAll(Set.of("minecraft", "neoforge"))) return unavailable("base mods absent");
        for (String mod : mods.stream().sorted().toList()) {
            if (!mod.equals("minecraft") && !mod.equals("neoforge"))
                return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_MOD, mod);
        }
        for (String config : configs.stream().sorted().toList()) {
            if (!CONFIGS.contains(config)) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, config);
        }
        for (String provider : providers.stream().sorted().toList()) {
            if (!PROVIDERS.contains(provider)) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, provider);
        }
        return Check.ok();
    }

    static Check listeners(IEventBus bus) throws ReflectiveOperationException {
        if (bus.getClass() != EventBus.class) return unavailable("unknown event bus " + bus.getClass().getName());
        // Bus 8 has no public inspection API. Access is optional: an inaccessible/changed API refuses
        // the entire call. No listeners are invoked, removed, replaced or treated as approved callbacks.
        Method lookup = EventBus.class.getDeclaredMethod("getListenerList", Class.class);
        if (!lookup.trySetAccessible()) return unavailable("event bus inspection unavailable");
        Field registrations = EventBus.class.getDeclaredField("listeners");
        if (!registrations.trySetAccessible()) return unavailable("event registration inspection unavailable");
        Map<?, ?> owners = (Map<?, ?>) registrations.get(bus);
        // EventBus.register owns these exact registrations. The only built-in EntityTick.Pre
        // subscriber is NeoForgeEventHandler.onEntityTick, whose ServerPlayer branch returns without
        // publishing a mapping. Its EntityLeaveLevel handler removes the existing region mapping
        // on the original publisher after physical join. Neither approval extends to a consumer,
        // subclass or another callback.
        Set<Object> builtIn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (var entry : owners.entrySet()) {
            if (entry.getKey().getClass() == NeoForgeEventHandler.class) {
                for (Object registration : (List<?>) entry.getValue()) {
                    builtIn.add(registration);
                    // Bus 8 unwraps non-cancellable events in its dispatch cache. This final
                    // built-in wrapper's accessor only returns its exact registered handler.
                    if (registration.getClass() == SubscribeEventListener.class)
                        builtIn.add(((SubscribeEventListener) registration).getWithoutCheck());
                }
            }
        }
        List<String> found = new ArrayList<>();
        for (String name : EVENTS) {
            Class<?> event = Class.forName("net.neoforged.neoforge.event." + name);
            ListenerList list = (ListenerList) lookup.invoke(bus, event);
            for (var listener : list.getListeners()) {
                if ((!name.equals("tick.EntityTickEvent$Pre") && !name.equals("entity.EntityLeaveLevelEvent")) || !builtIn.contains(listener)) {
                    found.add(event.getName());
                    break;
                }
            }
        }
        return found.isEmpty() ? Check.ok() : new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, String.join(",", found));
    }

    private static Check unavailable(String detail) {
        return new Check(RegionPlayerAdmissionPlan.Refusal.EXTENSION_INSPECTION_UNAVAILABLE, detail);
    }
}
