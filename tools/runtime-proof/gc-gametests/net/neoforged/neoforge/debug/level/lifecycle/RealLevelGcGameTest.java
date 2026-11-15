/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level.lifecycle;

import com.google.gson.GsonBuilder;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.neoforge.server.threading.region.EntityIdentityLedger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.RegionThreadingManager;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.CoreModWorkScope;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.ForEachTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/** Whole real-server collection with the shutdown Core engine and actual native callbacks retained. */
@ForEachTest(groups = "level.region.lifecyclegc")
public final class RealLevelGcGameTest {
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final List<Tracked> TRACKED = new CopyOnWriteArrayList<>();
    private static final List<Object> CALLBACKS = new ArrayList<>();
    private static final Set<String> UNLOADED = ConcurrentHashMap.newKeySet();
    private static final Set<String> CLOSED = ConcurrentHashMap.newKeySet();
    private static final List<Thread> WORKERS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Thread> SERVER_TO_JOIN = new AtomicReference<>();
    private static WeakReference<Object> originalModContainer;
    private static WeakReference<ClassLoader> originalModLoader;
    private static String originalModModule;
    private static RegionThreadingEngine retainedEngine;
    private static boolean exercised;

    private static final class Tracked extends WeakReference<Object> {
        final String name;
        Tracked(String name, Object object) {
            super(object, QUEUE);
            this.name = name;
        }
    }

    private static void track(String name, Object object) {
        if (object == null) throw new AssertionError("Missing actual runtime object: " + name);
        TRACKED.add(new Tracked(name, object));
    }

    @GameTest(batch = "regionLifecycleGc", timeoutTicks = 600)
    @EmptyTemplate(value = "15x10x15", floor = true)
    @TestHolder(description = "Exercise original Mod Workers before actual whole-server shutdown and real Level GC")
    static void originalWorkersBeforeWholeServerRetirement(final DynamicTest test) {
        test.onGameTest(helper -> {
            final var server = helper.getLevel().getServer();
            final var level = server.getLevel(Level.END);
            retainedEngine = (RegionThreadingEngine) RegionThreadingManager.getOrThrow();
            final var engine = retainedEngine;
            track("server", server);
            for (var realLevel : server.getAllLevels()) {
                if (realLevel.getClass() != ServerLevel.class) throw new AssertionError("Exact real ServerLevel required");
                track("level:" + realLevel.dimension().location(), realLevel);
            }
            NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
                if (event.getLevel() instanceof ServerLevel realLevel) UNLOADED.add(realLevel.dimension().location().toString());
            });
            final var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("piq_fc_arcade:furniture/oak_stool"));
            helper.assertTrue(level != null && block != Blocks.AIR, "Normally loaded real Level and original Mod required");
            final var a = new BlockPos(30106, 100, 30106);
            final var positions = List.of(a, a.offset(engine.diagnostics().regionChunkSize() * 16, 0, 0));
            helper.startSequence().thenExecute(() -> {
                for (var p : positions) {
                    level.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
                    level.setBlock(p, block.defaultBlockState(), 3);
                }
            }).thenWaitUntil(() -> {
                for (var p : positions) helper.assertTrue(level.getBlockEntity(p) != null && counter(level.getBlockEntity(p)) >= 2, "Original ticker must register and tick normally");
            }).thenExecute(() -> {
                final var modClass = level.getBlockEntity(positions.getFirst()).getClass();
                originalModContainer = new WeakReference<>(ModList.get().getModContainerById("piq_fc_arcade").orElseThrow());
                originalModLoader = new WeakReference<>(modClass.getClassLoader());
                originalModModule = modClass.getModule().getName();
                helper.assertTrue(ModList.get().isLoaded("piq_fc_arcade"), "Original Mod must load normally through FML");
                final var paired = new CountDownLatch(2);
                final var completed = new AtomicInteger();
                for (int index = 0; index < positions.size(); index++) {
                    final var p = positions.get(index);
                    setCounter(level.getBlockEntity(p), 1);
                    track("chunk:" + index, level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4));
                }
                engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                    final int index = positions.indexOf(ticker.getPos());
                    if (index < 0) {
                        body.run();
                        return;
                    }
                    if (!RegionThreadingHooks.isNativeRegionWorkerThread() || !BlockEntityResourceOwnership.Scope.active() || !CoreModWorkScope.active())
                        throw new AssertionError("Original Core Worker, resource and Mod scopes required");
                    WORKERS.add(Thread.currentThread());
                    final var source = LevelChunk.actualBlockEntitySource(ticker);
                    BlockEntityResourceOwnership.Scope.require(level, source, BlockEntityResourceOwnership.Access.WRITE);
                    track("source:" + index, source);
                    track("wrapper:" + index, ticker);
                    track("delegate:" + index, LevelChunk.captureCoreTickerFrame(ticker).delegate());
                    try {
                        final Class<?> kernel = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine");
                        final Object permit = ((ThreadLocal<?>) field(kernel, "CURRENT").get(null)).get();
                        final Object ticket = value(permit, "ticket");
                        track("permit:" + index, permit);
                        track("ticket:" + index, ticket);
                        track("manifest:" + index, value(ticket, "modManifest"));
                        track("claims:" + index, value(ticket, "modClaims"));
                        track("lease:" + index, value(permit, "resources"));
                        track("phase:" + index, value(ticket, "phase"));
                        track("modScope:" + index, ((ThreadLocal<?>) field(CoreModWorkScope.class, "CURRENT").get(null)).get());
                        track("resourceScope:" + index, ((ThreadLocal<?>) field(BlockEntityResourceOwnership.class, "CURRENT").get(null)).get());
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                    paired.countDown();
                    try {
                        if (!paired.await(10, TimeUnit.SECONDS)) throw new AssertionError("Original workers did not overlap");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    body.run();
                    completed.incrementAndGet();
                });
                try {
                    originalLevelPhase(level);
                } finally {
                    engine.setBlockEntityBodyObserverForTesting(null);
                }
                helper.assertTrue(completed.get() == 2, "Both original Worker bodies must physically return");
                for (var p : positions) helper.assertTrue(counter(level.getBlockEntity(p)) == 2, "Exactly one original increment; no illicit side effect or replay");
                helper.assertTrue(!CoreModWorkScope.active() && !BlockEntityResourceOwnership.Scope.active(), "Coordinator must have no remaining worker scope");
                try {
                    final Object preparation = value(engine, "coreModPreparation");
                    for (Object root : (List<?>) value(preparation, "roots")) {
                        final Object callback = value(root, "check");
                        if (callback != null) CALLBACKS.add(callback);
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
                helper.assertTrue(!CALLBACKS.isEmpty(), "Keep actual registered native access callbacks strongly reachable");
                exercised = true;
                System.out.println("LIFECYCLE_REAL_LEVEL_WORKERS originalBodies=2 counterDelta=2 physicalPhaseReturn=true callbacksRetained=" + CALLBACKS.size());
                // Leave sources and chunks to normal server shutdown; do not manufacture a Level boundary.
            }).thenSucceed();
        });
    }

    public static void observeAfterShutdown(GameTestServer server, int originalExitCode) {
        // Read existing terminal state, without transforming any integrity-checked Level method.
        for (var level : server.getAllLevels()) {
            try {
                final Object manager = value(level, "entityManager");
                final var ledger = (EntityIdentityLedger) value(manager, "identityLedger");
                final Object storage = value(value(manager, "permanentStorage"), "simpleRegionStorage");
                final Object io = value(storage, "worker");
                final var requested = (AtomicBoolean) value(io, "shutdownRequested");
                final var mailboxStatus = (AtomicInteger) value(value(io, "mailbox"), "status");
                require(ledger.isSealed() && requested.get() && (mailboxStatus.get() & 1) != 0, "Real Level close did not seal and physically close entity storage");
                CLOSED.add(level.dimension().location().toString());
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
        // Summary emission and all production stop/Level close/Core shutdown calls already returned.
        GameTestTicker.SINGLETON.clear();
        SERVER_TO_JOIN.set(Thread.currentThread());
        // Capture only the exit status. A terminated JDK21 Thread can retain its startup task/server.
        new Thread(() -> observe(originalExitCode), "lifecycle-post-server-gc-observer").start();
    }

    private static void observe(int originalExitCode) {
        final Map<String, Object> result = new LinkedHashMap<>();
        int exit = originalExitCode;
        result.put("originalGameTestExitCode", originalExitCode);
        result.put("boundary", "whole-server shutdown; original engine and actual access callbacks strongly retained");
        result.put("forcedFinalization", false);
        result.put("finalizationObserved", "not applicable; reference queue and weak clearing are the evidence");
        try {
            Thread serverThread = SERVER_TO_JOIN.getAndSet(null);
            serverThread.join(30000);
            require(!serverThread.isAlive(), "Original server thread did not physically join");
            serverThread = null;
            require(exercised && originalExitCode == 0, "Original runtime exercise failed or did not run");
            require(ServerLifecycleHooks.getCurrentServer() == null && RegionThreadingManager.currentOrNull() == null, "Canonical static server/engine provider still installed");
            require(WORKERS.stream().noneMatch(Thread::isAlive), "A real original Worker outlived Core shutdown");
            require(UNLOADED.equals(CLOSED) && CLOSED.size() == 3, "Every normal real Level must unload and close: " + UNLOADED + "/" + CLOSED);
            retainedEngine.shutdown(); // Repeated shutdown must remain safe and terminal.
            require(Boolean.TRUE.equals(value(retainedEngine, "shutdown")), "Retained engine not shut down");
            require(((Set<?>) value(retainedEngine, "activeTickBatches")).isEmpty(), "Published work still active");
            result.put("serverThreadJoined", true);
            result.put("workerThreadsJoined", WORKERS.size());
            result.put("actualLevelUnloadEvents", UNLOADED);
            result.put("actualLevelCloseTerminalStates", CLOSED);
            result.put("closeEvidence", "original entity identity ledger sealed, entity IOWorker shutdown requested and mailbox CLOSED bit observed after normal stopServer");
            result.put("retainedActualCallbacks", CALLBACKS.size());
            result.put("runtimeModBoundary", modBoundary());
            final long before = gcCount();
            final Set<String> enqueued = ConcurrentHashMap.newKeySet();
            final List<Map<String, Object>> observations = new ArrayList<>();
            int consecutiveCleared = 0;
            for (int attempt = 1; attempt <= 40 && consecutiveCleared < 3; attempt++) {
                System.gc();
                Reference<?> arrived = QUEUE.remove(250);
                while (arrived != null) {
                    enqueued.add(((Tracked) arrived).name);
                    arrived = QUEUE.poll();
                }
                final List<String> retained = TRACKED.stream().filter(ref -> !ref.refersTo(null)).map(ref -> ref.name).toList();
                consecutiveCleared = retained.isEmpty() && enqueued.size() == TRACKED.size() ? consecutiveCleared + 1 : 0;
                observations.add(Map.of("attempt", attempt, "gcCount", gcCount(), "weakStillRetained", retained, "queueArrivals", enqueued.size(), "consecutiveAllCleared", consecutiveCleared));
            }
            result.put("gcRequests", observations.size());
            result.put("gcCollectionsObserved", gcCount() - before);
            result.put("observations", observations);
            result.put("trackedReferences", TRACKED.stream().map(ref -> ref.name).toList());
            result.put("referenceQueueArrivals", enqueued);
            result.put("weakReferencesCleared", TRACKED.stream().allMatch(ref -> ref.refersTo(null)));
            result.put("retainedEngineRootDiagnostics", engineRoots());
            if (consecutiveCleared != 3) {
                try {
                    final var diagnostic = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
                    final String dump = System.getProperty("forgia.lifecycle.gc.result") + ".hprof";
                    diagnostic.dumpHeap(dump, true);
                    result.put("retainedHeapDump", dump);
                } catch (Throwable unavailable) {
                    result.put("heapDumpFailure", unavailable.toString());
                }
            }
            require(gcCount() > before, "GC requests were not accompanied by an observed collection");
            require(consecutiveCleared == 3, "Retired real runtime graph still reachable; inspect observations and retention diagnostics");
            int refused = 0;
            for (Object callback : CALLBACKS) {
                final var method = callback.getClass().getDeclaredMethod("allowed", Object.class, boolean.class);
                method.setAccessible(true);
                for (boolean write : new boolean[] {false, true}) {
                    require(Boolean.FALSE.equals(method.invoke(callback, new Object(), write)), "Old callback granted foreign authority after retirement");
                    refused++;
                }
            }
            result.put("oldCallbackForeignReadWriteRefusals", refused);
            result.put("engineStronglyRetainedThroughObservation", retainedEngine != null);
            result.put("verdict", "CERTIFIED");
            Reference.reachabilityFence(retainedEngine);
            Reference.reachabilityFence(CALLBACKS);
        } catch (Throwable failure) {
            exit = originalExitCode == 0 ? 1 : originalExitCode;
            result.put("verdict", "NOT CERTIFIED");
            result.put("failure", failure.toString());
            failure.printStackTrace();
        }
        result.put("observerExitCode", exit);
        try {
            Files.writeString(Path.of(System.getProperty("forgia.lifecycle.gc.result")), new GsonBuilder().setPrettyPrinting().create().toJson(result));
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = exit == 0 ? 1 : exit;
        }
        System.out.println("LIFECYCLE_REAL_LEVEL_GC " + result.get("verdict") + " originalExit=" + originalExitCode + " observerExit=" + exit);
        System.exit(exit);
    }

    private static Map<String, Object> modBoundary() {
        final var container = ModList.get().getModContainerById("piq_fc_arcade").orElseThrow();
        final var loader = FMLLoader.getGameLayer().findLoader(originalModModule);
        return Map.of("originalModStillLoadedAfterServerStop", ModList.get().isLoaded("piq_fc_arcade"),
                "sameOriginalContainer", originalModContainer.refersTo(container), "sameOriginalDefiningLoader", originalModLoader.refersTo(loader),
                "definingLoaderClass", loader.getClass().getName(), "module", originalModModule,
                "certificationBoundary", "Level and server retirement; Mod container/game layer remain loaded");
    }

    private static Map<String, Object> engineRoots() throws ReflectiveOperationException {
        final var roots = new LinkedHashMap<String, Object>();
        for (String name : List.of("workerServerTasks", "mailboxes", "levelTickPhases", "borderUpdates", "borderGameEvents", "regionsWithForeignReads", "chunkLoadAnswers", "seededEntityTickLevels", "activeTickBatches")) {
            Object root = value(retainedEngine, name);
            roots.put(name, root instanceof Map<?, ?> map ? map.size() : ((java.util.Collection<?>) root).size());
        }
        return roots;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static void originalLevelPhase(Level level) {
        try {
            final var method = Level.class.getDeclaredMethod("tickBlockEntities");
            method.setAccessible(true);
            method.invoke(level);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object value(Object object, String name) throws ReflectiveOperationException {
        return field(object.getClass(), name).get(object);
    }

    private static int counter(Object source) {
        try {
            return field(source.getClass(), "ticks").getInt(source);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setCounter(Object source, int counter) {
        try {
            field(source.getClass(), "ticks").setInt(source, counter);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
