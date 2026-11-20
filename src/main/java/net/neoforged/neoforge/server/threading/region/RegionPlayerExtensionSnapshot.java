/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.MixinService;

/** Installed environment for complete player ticks/vanilla packets; not an opt-in payload declaration. */
final class RegionPlayerExtensionSnapshot {
    record Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail, boolean hasExtraMods) {
        Check(RegionPlayerAdmissionPlan.Refusal refusal, String detail) {
            this(refusal, detail, false);
        }

        static Check ok() {
            return OK;
        }

        static Check ok(boolean hasExtraMods) {
            return hasExtraMods ? OK_WITH_MODS : OK;
        }
    }

    /** Pure observation of one mixin config for target-class evaluation and unit testing. */
    record MixinObservation(String name, boolean prepared, boolean hasPlugin, @org.jetbrains.annotations.Nullable Set<String> targets) {}

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
            "level.ChunkWatchEvent$Watch", "level.ChunkWatchEvent$Sent", "level.ChunkWatchEvent$UnWatch", "level.ChunkTicketLevelUpdatedEvent",
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
    private static final List<String> PROTECTED_MIXIN_TARGET_PREFIXES = List.of(
            "net.minecraft.", "com.mojang.", "net.neoforged.",
            "java.", "javax.", "sun.", "jdk.",
            "org.spongepowered.", "cpw.mods.");
    private static final String MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    private static final Check OK = new Check(RegionPlayerAdmissionPlan.Refusal.NONE, "", false);
    private static final Check OK_WITH_MODS = new Check(RegionPlayerAdmissionPlan.Refusal.NONE, "", true);
    private static final Set<String> BASE_MODS = Set.of("minecraft", "neoforge");
    private static final List<MixinEnvironment.Phase> PHASES = List.of(MixinEnvironment.Phase.PREINIT, MixinEnvironment.Phase.INIT, MixinEnvironment.Phase.DEFAULT);
    // Module membership, parent layers and module descriptors are immutable after definition.
    private record ProviderSnapshot(ModuleLayer layer, Set<String> names, @org.jetbrains.annotations.Nullable String firstUnknown) {}
    private static volatile ProviderSnapshot providerSnapshot;

    // Mixin config JSON resources and their declared mixin classes are immutable after mod discovery.
    private static final Map<String, Boolean> verifiedModOnlyConfigs = new ConcurrentHashMap<>();

    private enum BuiltInOwner {
        NONE,
        NEOFORGE_EVENT_HANDLER,
        ATTACHMENT_SYNC
    }

    private record BusAccess(Method lookup, Field registrations, Class<?>[] events, BuiltInOwner[] builtInOwners) {}
    private static volatile BusAccess busAccess;
    // Bus 8 installs each event's ListenerList with computeIfAbsent and never replaces it.
    // The list's live dispatch array still changes on registration/unregistration, including ancestors.
    private record BusLists(WeakReference<IEventBus> bus, ListenerList[] lists) {}
    private static volatile BusLists busLists;
    private enum ListenerProfile {
        FULL,
        LOCAL_ENTITY,
        CONNECTION_BODY,
        CONNECTION_BODY_GAME_EVENT
    }

    private record ModListScan(WeakReference<ModList> mods, int size, boolean basePresent, boolean hasExtraMods) {}
    private static volatile ModListScan modListScan;

    private record VerifiedListeners(WeakReference<IEventBus> bus, Object[] arrays, Check result) {}
    private static volatile VerifiedListeners verifiedListeners;
    private static volatile VerifiedListeners verifiedLocalEntityListeners;
    private static volatile VerifiedListeners verifiedConnectionBodyListeners;
    private static volatile VerifiedListeners verifiedConnectionBodyGameEventListeners;

    private RegionPlayerExtensionSnapshot() {}

    static Check capture() {
        return captureProfile(ListenerProfile.FULL);
    }

    static Check capture(boolean localEntity) {
        return captureProfile(localEntity ? ListenerProfile.LOCAL_ENTITY : ListenerProfile.FULL);
    }

    static Check captureConnectionBody(boolean mayEmitGameEvent) {
        return captureProfile(mayEmitGameEvent ? ListenerProfile.CONNECTION_BODY_GAME_EVENT : ListenerProfile.CONNECTION_BODY);
    }

    private static Check captureProfile(ListenerProfile profile) {
        try {
            ModList mods = ModList.get();
            ModuleLayer layer = FMLLoader.getGameLayer();
            if (mods == null || layer == null) return unavailable("loader not ready");
            ModListScan scan = scanModList(mods);
            if (!scan.basePresent()) return unavailable("base mods absent");
            boolean hasExtraMods = scan.hasExtraMods();
            String unknown = firstUnknownMixinConfig(mods);
            if (unknown == null) unknown = firstUnknownProvider(layer);
            if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unknown, hasExtraMods);
            Check listenerCheck = listeners(NeoForge.EVENT_BUS, profile);
            if (listenerCheck.refusal() == RegionPlayerAdmissionPlan.Refusal.NONE) return Check.ok(hasExtraMods);
            return new Check(listenerCheck.refusal(), listenerCheck.detail(), hasExtraMods);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            // Missing inspection dependencies are a pre-body serial decision. No original tick is caught.
            return unavailable(failure.getClass().getName());
        }
    }

    private static ModListScan scanModList(ModList mods) {
        int size = mods.size();
        ModListScan cached = modListScan;
        if (cached != null && cached.mods().get() == mods && cached.size() == size) {
            return cached;
        }
        boolean minecraft = false, neoforge = false, hasExtraMods = false;
        for (var mod : mods.getMods()) {
            String id = mod.getModId();
            if (id.equals("minecraft")) minecraft = true;
            else if (id.equals("neoforge")) neoforge = true;
            else hasExtraMods = true;
        }
        ModListScan scan = new ModListScan(new WeakReference<>(mods), size, minecraft && neoforge, hasExtraMods);
        modListScan = scan;
        return scan;
    }

    @org.jetbrains.annotations.Nullable
    private static String firstUnknownMixinConfig(ModList mods) {
        String unknown = null;
        for (Config handle : Mixins.getConfigs()) {
            String name = handle.getName();
            if (CONFIGS.contains(name)) continue;
            IMixinConfig config = handle.getConfig();
            if ((config != null && config.getPlugin() != null) || !modOwnedTargetsByName(mods, name)) {
                unknown = earlierName(unknown, name);
            }
        }
        for (var phase : PHASES) {
            for (String name : MixinEnvironment.getEnvironment(phase).getMixinConfigs()) {
                if (CONFIGS.contains(name)) continue;
                if (!modOwnedTargetsByName(mods, name)) {
                    unknown = earlierName(unknown, name);
                }
            }
        }
        return unknown;
    }

    private static String earlierName(@org.jetbrains.annotations.Nullable String current, String candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static boolean modOwnedTargetsByName(ModList mods, @org.jetbrains.annotations.Nullable String configName) {
        if (configName == null || configName.isEmpty()) return false;
        Boolean cached = verifiedModOnlyConfigs.get(configName);
        if (cached != null) return cached;
        boolean safe = inspectMixinConfigResource(mods, configName);
        verifiedModOnlyConfigs.put(configName, safe);
        return safe;
    }

    private static boolean inspectMixinConfigResource(ModList mods, String configName) {
        try {
            JsonObject root;
            try (InputStream stream = openResource(mods, configName)) {
                if (stream == null) return false;
                JsonElement parsed = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) return false;
                root = parsed.getAsJsonObject();
            }
            if (hasNonEmptyString(root, "plugin") || hasNonEmptyString(root, "parent")) return false;
            String pkg = hasNonEmptyString(root, "package") ? root.get("package").getAsString() : "";
            List<String> mixinClasses = new ArrayList<>();
            collectMixinEntries(root, "mixins", pkg, mixinClasses);
            collectMixinEntries(root, "server", pkg, mixinClasses);
            if (FMLEnvironment.dist == null || !FMLEnvironment.dist.isDedicatedServer()) {
                collectMixinEntries(root, "client", pkg, mixinClasses);
            }
            if (mixinClasses.isEmpty()) return true;
            if (pkg.isEmpty()) return false;
            for (String className : mixinClasses) {
                ClassNode node = readClassNode(mods, className);
                if (node == null) return false;
                Set<String> targets = extractMixinTargets(node);
                if (targets == null || targets.isEmpty() || !allModOwnedTargets(targets)) return false;
            }
            return true;
        } catch (Exception | LinkageError failure) {
            return false;
        }
    }

    @org.jetbrains.annotations.Nullable
    private static InputStream openResource(ModList mods, String resourcePath) throws java.io.IOException {
        String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        String[] parts = normalized.split("/");
        var modFiles = mods.getModFiles();
        if (modFiles != null) {
            for (var modFileInfo : modFiles) {
                if (modFileInfo == null || modFileInfo.getFile() == null) continue;
                java.nio.file.Path path = modFileInfo.getFile().findResource(parts);
                if (path != null && java.nio.file.Files.isRegularFile(path)) {
                    return java.nio.file.Files.newInputStream(path);
                }
            }
        }
        IMixinService service = MixinService.getService();
        if (service != null) {
            InputStream stream = service.getResourceAsStream(normalized);
            if (stream != null) return stream;
        }
        ClassLoader loader = RegionPlayerExtensionSnapshot.class.getClassLoader();
        return loader == null ? null : loader.getResourceAsStream(normalized);
    }

    @org.jetbrains.annotations.Nullable
    private static ClassNode readClassNode(ModList mods, String className) throws Exception {
        String classResource = className.replace('.', '/') + ".class";
        try (InputStream stream = openResource(mods, classResource)) {
            if (stream != null) {
                ClassNode node = new ClassNode();
                new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            }
        }
        IMixinService service = MixinService.getService();
        var bytecode = service == null ? null : service.getBytecodeProvider();
        return bytecode == null ? null : bytecode.getClassNode(className, false, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static boolean hasNonEmptyString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && !element.getAsString().isEmpty();
    }

    private static void collectMixinEntries(JsonObject root, String key, String pkg, List<String> destination) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) return;
        JsonArray array = element.getAsJsonArray();
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Non-string mixin entry in " + key);
            }
            String rel = entry.getAsString();
            destination.add(pkg.isEmpty() ? rel : pkg + "." + rel);
        }
    }

    @org.jetbrains.annotations.Nullable
    private static Set<String> extractMixinTargets(ClassNode node) {
        AnnotationNode mixinAnn = findAnnotation(node.visibleAnnotations, MIXIN_ANNOTATION_DESC);
        if (mixinAnn == null) mixinAnn = findAnnotation(node.invisibleAnnotations, MIXIN_ANNOTATION_DESC);
        if (mixinAnn == null || mixinAnn.values == null) return null;
        Set<String> targets = new HashSet<>();
        for (int i = 0; i + 1 < mixinAnn.values.size(); i += 2) {
            Object key = mixinAnn.values.get(i);
            Object val = mixinAnn.values.get(i + 1);
            if ("value".equals(key) && val instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Type type) targets.add(type.getClassName());
                    else return null;
                }
            } else if ("targets".equals(key) && val instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof String s) targets.add(s);
                    else return null;
                }
            }
        }
        return targets;
    }

    @org.jetbrains.annotations.Nullable
    private static AnnotationNode findAnnotation(@org.jetbrains.annotations.Nullable List<AnnotationNode> annotations, String desc) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (desc.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static boolean allModOwnedTargets(@org.jetbrains.annotations.Nullable Set<String> targets) {
        if (targets == null) return false;
        for (String target : targets) {
            if (target == null || target.isEmpty()) return false;
            String normalized = target.replace('/', '.');
            for (int i = 0, size = PROTECTED_MIXIN_TARGET_PREFIXES.size(); i < size; i++) {
                if (normalized.startsWith(PROTECTED_MIXIN_TARGET_PREFIXES.get(i))) return false;
            }
        }
        return true;
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
        boolean hasExtraMods = firstUnknown(mods, BASE_MODS) != null;
        String unknown = firstUnknown(configs, CONFIGS);
        if (unknown == null) unknown = firstUnknown(providers, PROVIDERS);
        if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unknown, hasExtraMods);
        return Check.ok(hasExtraMods);
    }

    /** Pure evaluation of observations including target-class metadata for non-whitelisted mixin configs. */
    static Check installedObservations(Set<String> mods, Collection<MixinObservation> configs, Set<String> providers) {
        if (!mods.containsAll(BASE_MODS)) return unavailable("base mods absent");
        boolean hasExtraMods = firstUnknown(mods, BASE_MODS) != null;
        String unknown = null;
        for (MixinObservation config : configs) {
            if (CONFIGS.contains(config.name())) continue;
            if (!config.prepared() || config.hasPlugin() || !allModOwnedTargets(config.targets())) {
                unknown = earlierName(unknown, config.name());
            }
        }
        if (unknown == null) unknown = firstUnknown(providers, PROVIDERS);
        if (unknown != null) return new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_TRANSFORM, unknown, hasExtraMods);
        return Check.ok(hasExtraMods);
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
        BuiltInOwner[] builtInOwners = new BuiltInOwner[count];
        for (int i = 0; i < count; i++) {
            String name = EVENTS.get(i);
            events[i] = Class.forName("net.neoforged.neoforge.event." + name);
            if (name.equals("tick.EntityTickEvent$Pre") || name.equals("entity.EntityLeaveLevelEvent")) {
                builtInOwners[i] = BuiltInOwner.NEOFORGE_EVENT_HANDLER;
            } else if (name.equals("level.ChunkWatchEvent$Sent")) {
                builtInOwners[i] = BuiltInOwner.ATTACHMENT_SYNC;
            } else {
                builtInOwners[i] = BuiltInOwner.NONE;
            }
        }
        access = new BusAccess(lookup, registrations, events, builtInOwners);
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
        return listeners(bus, ListenerProfile.FULL);
    }

    static Check listeners(IEventBus bus, boolean localEntity) throws ReflectiveOperationException {
        return listeners(bus, localEntity ? ListenerProfile.LOCAL_ENTITY : ListenerProfile.FULL);
    }

    static Check listenersConnectionBody(IEventBus bus, boolean mayEmitGameEvent) throws ReflectiveOperationException {
        return listeners(bus, mayEmitGameEvent ? ListenerProfile.CONNECTION_BODY_GAME_EVENT : ListenerProfile.CONNECTION_BODY);
    }

    private static Check listeners(IEventBus bus, ListenerProfile profile) throws ReflectiveOperationException {
        if (bus.getClass() != EventBus.class) return unavailable("unknown event bus " + bus.getClass().getName());
        // Cache reflection metadata only. Owner registrations and inherited dispatch lists are live.
        // ListenerList.getListeners() returns a fresh array whenever any listener is added or removed,
        // so comparing every live array reference still observes all live registrations.
        BusAccess access = busAccess();
        ListenerList[] lists = lists(bus, access);
        VerifiedListeners verified = switch (profile) {
            case FULL -> verifiedListeners;
            case LOCAL_ENTITY -> verifiedLocalEntityListeners;
            case CONNECTION_BODY -> verifiedConnectionBodyListeners;
            case CONNECTION_BODY_GAME_EVENT -> verifiedConnectionBodyGameEventListeners;
        };
        if (verified != null && verified.bus().get() == bus) {
            Object[] prev = verified.arrays();
            boolean same = true;
            for (int index = 0; index < lists.length; index++) {
                if (profileIncludesEvent(profile, EVENTS.get(index)) && lists[index].getListeners() != prev[index]) {
                    same = false;
                    break;
                }
            }
            if (same) return verified.result();
        }
        Map<?, ?> owners = (Map<?, ?>) access.registrations().get(bus);
        Object[] snapshot = new Object[lists.length];
        BuiltInOwner[] builtInOwners = access.builtInOwners();
        List<String> found = null;
        for (int index = 0; index < lists.length; index++) {
            if (!profileIncludesEvent(profile, EVENTS.get(index))) continue;
            var live = lists[index].getListeners();
            snapshot[index] = live;
            if (live.length == 0) continue;
            BuiltInOwner builtIn = builtInOwners[index];
            for (var listener : live) {
                if (builtIn == BuiltInOwner.NONE || !isBuiltInListener(owners, listener, builtIn)) {
                    if (found == null) found = new ArrayList<>();
                    found.add(access.events()[index].getName());
                    break;
                }
            }
        }
        Check result = found == null ? Check.ok() : new Check(RegionPlayerAdmissionPlan.Refusal.UNKNOWN_LISTENER, String.join(",", found));
        var observed = new VerifiedListeners(new WeakReference<>(bus), snapshot, result);
        switch (profile) {
            case FULL -> verifiedListeners = observed;
            case LOCAL_ENTITY -> verifiedLocalEntityListeners = observed;
            case CONNECTION_BODY -> verifiedConnectionBodyListeners = observed;
            case CONNECTION_BODY_GAME_EVENT -> verifiedConnectionBodyGameEventListeners = observed;
        }
        return result;
    }

    private static boolean profileIncludesEvent(ListenerProfile profile, String event) {
        return switch (profile) {
            case FULL -> true;
            case LOCAL_ENTITY -> localEntityEvent(event);
            case CONNECTION_BODY -> connectionBodyEvent(event, false);
            case CONNECTION_BODY_GAME_EVENT -> connectionBodyEvent(event, true);
        };
    }

    // The local ENTITY profile has no block break, movement, inventory change, menu click or
    // network handler. Its synchronous TICK rewards remain guarded by the state plan.
    private static boolean localEntityEvent(String event) {
        return switch (event) {
            case "ServerChatEvent", "ItemStackedOnOtherEvent", "VanillaGameEvent",
                    "tick.PlayerTickEvent$Pre", "tick.PlayerTickEvent$Post",
                    "entity.living.LivingBreatheEvent", "entity.living.LivingDrownEvent" -> false;
            default -> true;
        };
    }

    // CONNECTION_BODY executes only ServerPlayer.doTick() -> Player.tick() -> LivingEntity.tick().
    // EntityTickEvent, ServerChatEvent, ItemStackedOnOtherEvent, ChunkWatchEvent, PlayerEvent tracking,
    // EnteringSection (stationary), and NeighborNotifyEvent are not reachable from this body.
    // VanillaGameEvent is included whenever the admitted body may emit a game event (e.g. starvation damage).
    private static boolean connectionBodyEvent(String event, boolean mayEmitGameEvent) {
        return switch (event) {
            case "tick.EntityTickEvent$Pre", "tick.EntityTickEvent$Post",
                    "ServerChatEvent", "ItemStackedOnOtherEvent",
                    "level.ChunkWatchEvent$Watch", "level.ChunkWatchEvent$Sent", "level.ChunkWatchEvent$UnWatch",
                    "level.ChunkTicketLevelUpdatedEvent", "entity.player.PlayerEvent$StartTracking",
                    "entity.player.PlayerEvent$StopTracking", "entity.EntityEvent$EnteringSection",
                    "level.BlockEvent$NeighborNotifyEvent" -> false;
            case "VanillaGameEvent" -> mayEmitGameEvent;
            default -> true;
        };
    }
    private static boolean isBuiltInListener(Map<?, ?> owners, Object listener, BuiltInOwner builtIn) {
        // Bus 8 uses the registered wrapper for cancellable events and its exact unchecked
        // delegate for other dispatch arrays. Check the live owner map in both cases.
        for (var entry : owners.entrySet()) {
            Object key = entry.getKey();
            boolean matchesOwner = switch (builtIn) {
                case NEOFORGE_EVENT_HANDLER -> key.getClass() == NeoForgeEventHandler.class;
                case ATTACHMENT_SYNC -> key == AttachmentSync.class;
                case NONE -> false;
            };
            if (!matchesOwner) continue;
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
