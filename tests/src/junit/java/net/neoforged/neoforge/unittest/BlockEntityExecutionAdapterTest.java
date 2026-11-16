/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.server.threading.ParallelAdapterRuntime;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionAdapter;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Mode;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent original functions through the public protocol, real Level loop and real worker pool. */
@Timeout(90)
class BlockEntityExecutionAdapterTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos SECOND = new BlockPos(68, 64, 20);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void independentOriginalFunctionsOverlapEvenInTheSameRegion(boolean sameRegion) {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var a = source(f, FIRST, new Root(3));
            final var b = source(f, sameRegion ? FIRST.east() : SECOND, new Root(5));
            final var rendezvous = new CyclicBarrier(2);
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                assertTrue(Thread.currentThread() != f.serverThread);
                await(rendezvous);
                body.run();
            });
            f.levelPhase();
            assertEquals(3, a.counter);
            assertEquals(5, b.counter);
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertTrue(adapter.inspections.get() >= 4);
            assertEquals(1, adapter.closed.get());
            assertTrue(adapter.successful);
            f.clean();
        }
    }

    @Test
    void regionOwnerModeUsesTheSameProtocolWithTwoIndependentRegions() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.REGION_OWNER);
            source(f, FIRST, new Root(1));
            source(f, SECOND, new Root(1));
            final var rendezvous = new CyclicBarrier(2);
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                assertTrue(Thread.currentThread() != f.serverThread);
                await(rendezvous);
                body.run();
            });
            f.levelPhase();
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void regionOwnerEffectsCommitBeforeTheNextCompleteSerialFence() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.REGION_OWNER);
            source(f, FIRST, new Root(1));
            final var published = new AtomicInteger();
            f.script("fence", SECOND, "example:unknown", () -> assertEquals(1, published.get()));
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                if (ticker.getPos().equals(FIRST))
                    assertTrue(f.engine.queueServerTaskInCurrentBatch(published::incrementAndGet));
                body.run();
            });
            f.levelPhase();
            assertEquals(1, published.get());
            f.clean();
        }
    }

    @Test
    void executionAdaptersAndOriginalVanillaFurnaceWavesShareTheCanonicalPhase() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(true);
            register(f, Mode.RESOURCE_SCOPED);
            final var a = source(f, FIRST, new Root(2));
            final var b = source(f, FIRST.east(), new Root(3));
            f.furnace("F0", SECOND, 1);
            f.furnace("F1", SECOND.east(3), 1);
            f.levelPhase();
            assertEquals(2, a.counter);
            assertEquals(3, b.counter);
            assertEquals(1, f.furnaces.get("F0").getItem(2).getCount());
            assertEquals(1, f.furnaces.get("F1").getItem(2).getCount());
            assertEquals(4, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void sharedReadRootsPermitConcurrentBodies() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.RESOURCE_SCOPED);
            final var root = new Root(4);
            source(f, FIRST, root);
            source(f, SECOND, root);
            final var rendezvous = new CyclicBarrier(2);
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                await(rendezvous);
                body.run();
            });
            f.levelPhase();
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void sharedWritesSplitPrefixesAndCannotAcquireAnotherHome() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            adapter.writeRoot = true;
            final var root = new Root(2);
            final var a = source(f, FIRST, root);
            final var b = source(f, FIRST.east(), root);
            final var c = source(f, SECOND, root);
            final List<Boolean> workers = new ArrayList<>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                workers.add(Thread.currentThread() != f.serverThread);
                body.run();
            });
            f.levelPhase();
            assertEquals(List.of(true, true, false), workers);
            assertEquals(2, a.counter);
            assertEquals(2, b.counter);
            assertEquals(2, c.counter);
            f.clean();
        }
    }

    @Test
    void mailboxRootReplacementRefusesTheUnstartedBodyBeforeAnyWorker() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            adapter.firstInspection = () -> enqueue(f, () -> source.root = new Root(9));
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertSame(f.serverThread, thread.get());
            assertEquals(9, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void mailboxDependencyRevisionChangeIsNotHiddenByAnUnchangedRoot() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            adapter.dependencies = true;
            final var source = source(f, FIRST, new Root(1));
            adapter.firstInspection = () -> enqueue(f, () -> source.root.revision++);
            f.levelPhase();
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void callbackOrStartedBodyFailuresAlwaysCleanUpAndNeverReplay(boolean bodyFailure) {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            source.fail = bodyFailure;
            adapter.failInspection = !bodyFailure;
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(bodyFailure ? 1 : 0, source.counter);
            assertEquals(1, adapter.closed.get());
            assertFalse(adapter.successful);
            f.clean();
        }
    }

    @Test
    void preparationFailureStillCallsCleanupAndRetiresThePublishedPhase() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            adapter.failPrepare = true;
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(0, source.counter);
            assertEquals(1, adapter.closed.get());
            assertFalse(adapter.successful);
            f.clean();
        }
    }

    @Test
    void cleanupFailureDoesNotStrandScopesOrReplayTheOriginalBody() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            adapter.failCleanup = true;
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(1, source.counter);
            assertEquals(1, adapter.closed.get());
            f.clean();
        }
    }

    @Test
    void closingTheRegistrationDuringInspectionRefusesBeforeTheBodyAndStillCleansUp() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            adapter.firstInspection = () -> ParallelAdapterRuntime.close(f.server);
            f.levelPhase();
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(1, adapter.closed.get());
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void unloadAndShutdownWaitForActualScopedBodiesAndRejectWorkerSelfWait(boolean shutdown) throws InterruptedException {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            final var running = new CountDownLatch(1);
            final var returned = new AtomicBoolean();
            final var failure = new AtomicReference<Throwable>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                running.countDown();
                CanonicalBlockEntityTestSupport.await(f.lifecycleWriteHeld);
                assertFalse(returned.get(), "cleanup must await the actual original body and resource Scope");
                assertThrows(IllegalStateException.class, () -> f.engine.clearLevelRegionTasks(f.level));
                body.run();
            });
            final var cleanup = new Thread(() -> {
                try {
                    CanonicalBlockEntityTestSupport.await(running);
                    if (shutdown) f.engine.shutdown();
                    else f.engine.clearLevelRegionTasks(f.level);
                    returned.set(true);
                } catch (Throwable failed) {
                    failure.set(failed);
                }
            }, "execution-adapter-lifecycle");
            cleanup.start();
            try {
                f.levelPhase();
                cleanup.join(10_000);
                assertFalse(cleanup.isAlive());
                assertEquals(null, failure.get());
                assertTrue(returned.get());
                assertEquals(1, source.counter);
                f.clean();
            } finally {
                cleanup.join(10_000);
            }
        }
    }

    @Test
    void resourceScopedBodiesCannotPublishAnUncontractedServerContinuation() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            final var callbacks = new AtomicInteger();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                body.run();
                f.engine.queueServerTaskInCurrentBatch(callbacks::incrementAndGet);
            });
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(1, source.counter);
            assertEquals(0, callbacks.get());
            f.clean();
        }
    }

    @Test
    void rejectedWorkerSubmissionReturnsEachUnstartedOriginalBodyToTheSerialCursor() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, false);
            register(f, Mode.RESOURCE_SCOPED);
            final var a = source(f, FIRST, new Root(2));
            final var b = source(f, FIRST.east(), new Root(3));
            f.controlled.reject = true;
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                assertSame(f.serverThread, Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertEquals(2, a.counter);
            assertEquals(3, b.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void invalidFootprintsAndSerialDecisionsDoNotAuthorizeWorkers() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            final var source = source(f, FIRST, new Root(1));
            adapter.invalidFootprint = true;
            f.levelPhase();
            adapter.invalidFootprint = false;
            source.refused = true;
            f.levelPhase();
            assertEquals(2, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(2, adapter.closed.get());
            f.clean();
        }
    }

    @Test
    void aLargeCanonicalPhaseKeepsItsOriginalInvocationsAndUsesWorkersPastTheOldLimit() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.RESOURCE_SCOPED);
            final var submissions = new AtomicInteger();
            final var normal = f.barrier;
            f.barrier = done -> {
                if (f.controlled != null) {
                    final var queued = (java.util.Queue<?>) CanonicalBlockEntityTestSupport.get(f.controlled.getClass(), f.controlled, "queued");
                    submissions.addAndGet(queued.size());
                }
                normal.await(done);
            };
            final List<Counter> sources = new ArrayList<>();
            for (int index = 0; index < 2050; ++index)
                sources.add(source(f, new BlockPos(17 + index % 14, 64 + index / 196, 17 + index / 14 % 14), new Root(1)));
            f.levelPhase();
            for (final var source : sources) assertEquals(1, source.counter);
            assertEquals(2050, f.engine.diagnostics().blockEntityTicksOnWorkers());
            if (f.controlled != null) assertEquals(4, submissions.get(), "two bounded windows should submit two worker groups each");
            f.clean();
        }
    }

    @Test
    void aCompleteFenceBetweenWindowsForcesNewBranchAdmission() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, Mode.RESOURCE_SCOPED);
            for (int index = 0; index < 2048; ++index)
                source(f, new BlockPos(17 + index % 14, 64 + index / 196, 17 + index / 14 % 14), new Root(1));
            final var later = new AtomicReference<Counter>();
            f.script("fence", SECOND, "example:unknown", () -> {
                assertEquals(2048, f.engine.diagnostics().blockEntityTicksOnWorkers());
                later.get().refused = true;
            });
            later.set(source(f, FIRST.above(20), new Root(7)));
            f.levelPhase();
            assertEquals(7, later.get().counter);
            assertEquals(2048, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void phaseBudgetsDoNotRestartWhenIndependentPrefixesSplitAtSharedWrites() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, Mode.RESOURCE_SCOPED);
            adapter.writeRoot = true;
            adapter.extraRoots = true;
            final var shared = new Root(1);
            final List<Counter> sources = new ArrayList<>();
            for (int index = 0; index < 1100; ++index)
                sources.add(source(f, new BlockPos(17 + index % 14, 64 + index / 196, 17 + index / 14 % 14), shared));
            f.levelPhase();
            for (final var source : sources) assertEquals(1, source.counter);
            final long completed = f.engine.diagnostics().blockEntityTicksOnWorkers();
            assertTrue(completed > 0 && completed < sources.size(), "the one phase's logical binding budget must exhaust");
            f.clean();
        }
    }

    @Test
    void executionRegistrationIsExplicitAndSharesTheExistingTransaction() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            assertThrows(IllegalStateException.class, () -> ParallelAdapterRuntime.initialize(f.server, registrar -> {
                try {
                    registrar.registerBlockEntityTicks(1, ResourceLocation.parse("example:wrong"),
                            Set.of(ResourceLocation.parse("minecraft:sign")), new Adapter(Mode.RESOURCE_SCOPED));
                } catch (IllegalArgumentException expected) {
                    // A caught bad registration must still poison the whole transaction.
                }
            }));
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
        }
    }

    private static void enqueue(CanonicalBlockEntityTestSupport f, Runnable task) {
        assertEquals(net.neoforged.neoforge.server.threading.region.RegionMailbox.PostResult.ACCEPTED,
                f.engine.mailbox(f.level).post(net.neoforged.neoforge.server.threading.region.RegionLayout.FIXED_3X3.regionAt(f.level, FIRST), task));
    }

    private static Adapter register(CanonicalBlockEntityTestSupport f, Mode mode) {
        final var adapter = new Adapter(mode);
        ParallelAdapterRuntime.initialize(f.server, registrar -> {
            assertTrue(registrar.capabilities().contains(ParallelApi.Capability.BLOCK_ENTITY_EXECUTION_ADAPTERS));
            registrar.registerBlockEntityExecutionAdapter(1, ResourceLocation.parse("example:execution"),
                    Set.of(ResourceLocation.parse("minecraft:sign")), adapter);
        });
        return adapter;
    }

    private static Counter source(CanonicalBlockEntityTestSupport f, BlockPos pos, Root root) {
        final var source = new Counter(pos, root);
        source.setLevel(f.level);
        final String name = pos.toShortString();
        f.names.put(pos, name);
        f.world.states.put(pos, source.getBlockState());
        f.world.blockEntities.put(pos, source);
        try {
            final var bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
            bind.setAccessible(true);
            CanonicalBlockEntityTestSupport.invoke(bind, f, name, source, new Ticker());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return source;
    }

    private static final class Root {
        private final int value;
        private long revision;

        private Root(int value) {
            this.value = value;
        }
    }

    private static final class Counter extends BlockEntity {
        private Root root;
        private int counter;
        private boolean fail;
        private boolean refused;

        private Counter(BlockPos pos, Root root) {
            super(BlockEntityType.SIGN, pos, Blocks.OAK_SIGN.defaultBlockState());
            this.root = root;
        }
    }

    private static final class Ticker implements BlockEntityTicker<Counter> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, Counter source) {
            source.counter += source.root.value;
            if (source.fail) throw new IllegalStateException("Original tick failed after a write");
        }
    }

    private static final class Adapter implements BlockEntityExecutionAdapter {
        private final Mode mode;
        private final Thread coordinator = Thread.currentThread();
        private final AtomicInteger inspections = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private boolean successful;
        private boolean writeRoot;
        private boolean dependencies;
        private boolean failInspection;
        private boolean invalidFootprint;
        private boolean failPrepare;
        private boolean extraRoots;
        private final List<Object> extra = java.util.stream.IntStream.range(0, 62).mapToObj(index -> new Object()).toList();
        private boolean failCleanup;
        private Runnable firstInspection = () -> {};

        private Adapter(Mode mode) {
            this.mode = mode;
        }

        @Override
        public Mode executionMode() {
            return this.mode;
        }

        @Override
        public Audit audit() {
            final var implementations = new ArrayList<Implementation>();
            for (final Class<?> type : List.of(Adapter.class, Counter.class, Ticker.class, Root.class))
                implementations.add(new Implementation(type, fingerprint(type), Set.of()));
            return new Audit("example:execution-audit", "1", Set.of(Counter.class), Set.of(Ticker.class), implementations);
        }

        @Override
        public void prepareCanonical(net.minecraft.server.level.ServerLevel level) {
            if (this.failPrepare) throw new IllegalStateException("Preparation failed");
        }

        @Override
        public Proof inspect(BlockEntity source, Class<?> ticker) {
            assertSame(this.coordinator, Thread.currentThread());
            // Models an external mailbox arrival after this initial source observation.
            if (this.inspections.incrementAndGet() == 1) this.firstInspection.run();
            if (this.failInspection) throw new IllegalStateException("Inspection failed");
            final var counter = (Counter) source;
            if (this.invalidFootprint) return new Proof(List.of(new Storage(counter.getLevel(), Access.WRITE)));
            final List<Storage> roots = new ArrayList<>(List.of(new Storage(counter, Access.WRITE),
                    new Storage(counter.root, this.writeRoot ? Access.WRITE : Access.READ)));
            if (this.extraRoots) for (final var root : this.extra) roots.add(new Storage(root, Access.READ));
            return new Proof(roots);
        }

        @Override
        public Inspection inspectExecution(BlockEntity source, Class<?> ticker) {
            final var result = BlockEntityExecutionAdapter.super.inspectExecution(source, ticker);
            return !this.dependencies || result.proof() == null ? result
                    : new Inspection(result.proof(), result.decision(), List.of(new Dependency(((Counter) source).root, ((Counter) source).root.revision)));
        }

        @Override
        public Decision check(BlockEntity source) {
            assertSame(this.coordinator, Thread.currentThread());
            return ((Counter) source).refused ? Decision.CALLBACK_REQUIRED : Decision.ADMITTED;
        }

        @Override
        public void phaseClosed(boolean successful) {
            assertSame(this.coordinator, Thread.currentThread());
            this.closed.incrementAndGet();
            this.successful = successful;
            if (this.failCleanup) throw new IllegalStateException("Cleanup failed");
        }
    }

    private static String fingerprint(Class<?> type) {
        try (final var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
