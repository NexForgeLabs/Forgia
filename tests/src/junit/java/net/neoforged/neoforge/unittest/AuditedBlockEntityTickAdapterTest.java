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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
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
import net.neoforged.neoforge.server.threading.api.AuditedBlockEntityTickAdapter;
import net.neoforged.neoforge.server.threading.api.ParallelApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** An independent example integration through the real Level loop, without any target-mod dependency. */
@Timeout(60)
class AuditedBlockEntityTickAdapterTest {
    private static final BlockPos FIRST = new BlockPos(20, 64, 20);
    private static final BlockPos SECOND = new BlockPos(68, 64, 20);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void independentAdapterRunsOriginalFunctionsOnTwoRegionWorkers(boolean furnaceWaves) {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(furnaceWaves);
            final var adapter = register(f, false, false);
            final var a = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var b = source(f, "B", SECOND, new Root(1), new CounterTicker());
            final var rendezvous = new CyclicBarrier(2);
            final List<Thread> threads = new CopyOnWriteArrayList<>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                threads.add(Thread.currentThread());
                if (Thread.currentThread() != f.serverThread) await(rendezvous);
                body.run();
            });
            f.levelPhase();
            assertEquals(2, threads.size());
            assertTrue(threads.stream().noneMatch(thread -> thread == f.serverThread));
            assertTrue(threads.get(0) != threads.get(1));
            assertEquals(1, a.counter);
            assertEquals(1, b.counter);
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertTrue(adapter.inspections.get() > 0);
            assertEquals(1, adapter.canonicalPreparations.get());
            f.clean();
        }
    }

    @Test
    void auditedNativeAndProtectedFurnacePrefixesShareOneCanonicalPhase() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(true);
            final var adapter = register(f, false, false);
            final var a = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var b = source(f, "B", SECOND, new Root(1), new CounterTicker());
            f.furnace("F1", new BlockPos(116, 64, 20), 0);
            f.furnace("F2", new BlockPos(164, 64, 20), 0);
            f.levelPhase();
            assertEquals(1, a.counter);
            assertEquals(1, b.counter);
            assertEquals(1, adapter.canonicalPreparations.get());
            assertEquals(4, f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(2, f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(1, CanonicalBlockEntityTestSupport.get(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.class, f.furnaces.get("F1"), "cookingProgress"));
            assertEquals(1, CanonicalBlockEntityTestSupport.get(net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.class, f.furnaces.get("F2"), "cookingProgress"));
            f.clean();
        }
    }

    @Test
    void sharedReadRootStillAllowsIndependentSourceWrites() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, false, false);
            final var root = new Root(3);
            final var a = source(f, "A", FIRST, root, new CounterTicker());
            final var b = source(f, "B", SECOND, root, new CounterTicker());
            final var rendezvous = new CyclicBarrier(2);
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                assertTrue(Thread.currentThread() != f.serverThread);
                await(rendezvous);
                body.run();
            });
            f.levelPhase();
            assertEquals(3, a.counter);
            assertEquals(3, b.counter);
            assertEquals(2, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void writeClaimOnSharedRootSeparatesPrefixesEvenForTheSameRegion(boolean sameRegion) {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, false);
            register(f, true, false);
            final var root = new Root(1);
            final var a = source(f, "A", FIRST, root, new CounterTicker());
            final var b = source(f, "B", sameRegion ? FIRST.offset(4, 0, 0) : SECOND, root, new CounterTicker());
            final var normal = f.barrier;
            final List<Integer> queued = new ArrayList<>();
            f.barrier = done -> {
                final var tasks = (java.util.Queue<?>) CanonicalBlockEntityTestSupport.get(f.controlled.getClass(), f.controlled, "queued");
                if (!tasks.isEmpty()) queued.add(tasks.size());
                normal.await(done);
            };
            f.levelPhase();
            assertEquals(List.of(1, 1), queued, "a conflicting WRITE cannot enter a shared prevalidated prefix");
            assertEquals(1, a.counter);
            assertEquals(1, b.counter);
            f.clean();
        }
    }

    @Test
    void changedFootprintRefusesBeforeBodyAndUsesTheWholeSerialInvocationOnce() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false, false);
            register(f, false, false);
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var normal = f.barrier;
            f.barrier = done -> {
                source.root = new Root(7);
                normal.await(done);
            };
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertSame(f.serverThread, thread.get());
            assertEquals(7, source.counter, "the original function runs once against the current root");
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void changedImplementationNeverInvokesTheAdaptersInspectionOnAWorker() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, false, true);
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertEquals(0, adapter.inspections.get());
            assertSame(f.serverThread, thread.get());
            assertEquals(1, source.counter);
            final var status = ParallelApi.forServer(f.server).orElseThrow().auditedBlockEntityTicks().getFirst();
            assertFalse(status.verified());
            assertEquals("IMPLEMENTATION_CHANGED", status.reason());
            f.clean();
        }
    }

    @Test
    void aReplacementFunctionWithTheSameSourceRetainsTheCompleteSerialBody() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, false, false);
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker() {});
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertSame(f.serverThread, thread.get());
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void functionInstanceStorageRequiresARicherContractBeforeAnyWorkerAdmission() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = new CounterAdapter(false, false);
            adapter.statefulTicker = true;
            ParallelAdapterRuntime.initialize(f.server, registrar -> registrar.registerBlockEntityTicks(1,
                    ResourceLocation.parse("example:stateful"), Set.of(ResourceLocation.parse("minecraft:sign")), adapter));
            final var source = source(f, "A", FIRST, new Root(1), new StatefulCounterTicker());
            f.levelPhase();
            final var status = ParallelApi.forServer(f.server).orElseThrow().auditedBlockEntityTicks().getFirst();
            assertFalse(status.verified());
            assertEquals("STATEFUL_TICKER_UNSUPPORTED", status.reason());
            assertEquals(0, adapter.inspections.get());
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4 })
    void incompleteOrIllegalFootprintsRetainTheOriginalSerialInvocation(int invalidFootprint) {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, false, false);
            adapter.invalidFootprint = invalidFootprint;
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertSame(f.serverThread, thread.get());
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void registrationRetiredDuringInspectionCannotAuthorizeTheBody() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, false, false);
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            adapter.inspectObserver = () -> ParallelAdapterRuntime.close(f.server);
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
            assertSame(f.serverThread, thread.get());
            assertEquals(1, source.counter);
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void failureAfterAnOriginalWorkerWriteIsReportedWithoutSerialReplay() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            register(f, false, false);
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            source.fail = true;
            final var starts = new AtomicInteger();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                assertTrue(Thread.currentThread() != f.serverThread);
                starts.incrementAndGet();
                body.run();
            });
            assertThrows(RuntimeException.class, f::levelPhase);
            assertEquals(1, starts.get());
            assertEquals(1, source.counter, "the already-written invocation must never run serially again");
            assertEquals(1, f.engine.diagnostics().tickFailures().total());
            f.clean();
        }
    }

    @Test
    void adapterInspectionStartsOnlyInsideThePublishedOriginalBatch() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var adapter = register(f, false, false);
            source(f, "A", FIRST, new Root(1), new CounterTicker());
            adapter.inspectObserver = () -> {
                final var active = (java.util.Collection<?>) CanonicalBlockEntityTestSupport.get(
                        f.engine.getClass(), f.engine, "activeTickBatches");
                assertFalse(active.isEmpty(), "collection must not invoke an adapter before lifecycle publication");
            };
            f.levelPhase();
            assertTrue(adapter.inspections.get() > 0);
            assertEquals(1, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void legacyPredicateDoesNotSilentlyBecomeAnAuditedBodyContract() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            final var checks = new AtomicInteger();
            ParallelAdapterRuntime.initialize(f.server, registrar -> registrar.registerBlockEntityTicks(1,
                    ResourceLocation.parse("example:legacy"), Set.of(ResourceLocation.parse("minecraft:sign")), source -> {
                        checks.incrementAndGet();
                        return net.neoforged.neoforge.server.threading.api.BlockEntityTickAdapter.Decision.ADMITTED;
                    }));
            final var source = source(f, "A", FIRST, new Root(1), new CounterTicker());
            final var thread = new AtomicReference<Thread>();
            f.engine.setBlockEntityBodyObserverForTesting((ticker, body) -> {
                thread.set(Thread.currentThread());
                body.run();
            });
            f.levelPhase();
            assertSame(f.serverThread, thread.get());
            assertEquals(1, source.counter);
            assertEquals(0, checks.get());
            assertEquals(0, f.engine.diagnostics().blockEntityTicksOnWorkers());
            f.clean();
        }
    }

    @Test
    void reentrantAuditCannotOverwriteADuplicateRegistration() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(f.server,
                    registrar -> registrar.registerBlockEntityTicks(1, ResourceLocation.parse("example:reentrant"),
                            Set.of(ResourceLocation.parse("minecraft:sign")), new ReentrantAdapter(registrar, false))));
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
        }
    }

    @Test
    void caughtNestedAuditRegistrationFailureStillPreventsPublication() {
        try (final var f = new CanonicalBlockEntityTestSupport()) {
            f.enable(false);
            assertThrows(IllegalArgumentException.class, () -> ParallelAdapterRuntime.initialize(f.server,
                    registrar -> registrar.registerBlockEntityTicks(1, ResourceLocation.parse("example:reentrant"),
                            Set.of(ResourceLocation.parse("minecraft:sign")), new ReentrantAdapter(registrar, true))));
            assertTrue(ParallelApi.forServer(f.server).isEmpty());
        }
    }

    @Test
    void genericCoreTypesHaveNoTargetModDependencies() throws Exception {
        for (final String name : List.of("net.neoforged.neoforge.server.threading.AuditedTickImplementation",
                "net.neoforged.neoforge.server.threading.region.AuditedBlockEntityPreparation",
                AuditedBlockEntityTickAdapter.class.getName())) {
            final Class<?> type = Class.forName(name);
            try (final var input = type.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                final String constants = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("com/simibubi/create"), name);
                assertFalse(constants.contains("forgia/compat/create"), name);
            }
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.simibubi.create.Create"));
    }

    private static CounterAdapter register(CanonicalBlockEntityTestSupport f, boolean writeRoot, boolean invalidFingerprint) {
        final var adapter = new CounterAdapter(writeRoot, invalidFingerprint);
        ParallelAdapterRuntime.initialize(f.server, registrar -> {
            assertTrue(registrar.capabilities().contains(ParallelApi.Capability.AUDITED_BLOCK_ENTITY_TICKS));
            registrar.registerBlockEntityTicks(ParallelApi.MAJOR_VERSION, ResourceLocation.parse("example:counter"),
                    Set.of(ResourceLocation.parse("minecraft:sign")), adapter);
        });
        return adapter;
    }

    private static CounterSource source(CanonicalBlockEntityTestSupport f, String name, BlockPos pos, Root root, CounterTicker function) {
        final var source = new CounterSource(pos, root);
        source.setLevel(f.level);
        f.names.put(pos, name);
        f.world.states.put(pos, source.getBlockState());
        f.world.blockEntities.put(pos, source);
        try {
            final var bind = CanonicalBlockEntityTestSupport.class.getDeclaredMethod("bound", String.class, BlockEntity.class, BlockEntityTicker.class);
            bind.setAccessible(true);
            CanonicalBlockEntityTestSupport.invoke(bind, f, name, source, function);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
        return source;
    }

    private record Root(int value) {}

    private static final class CounterSource extends BlockEntity {
        private Root root;
        private int counter;
        private boolean fail;

        private CounterSource(BlockPos pos, Root root) {
            super(BlockEntityType.SIGN, pos, Blocks.OAK_SIGN.defaultBlockState());
            this.root = root;
        }
    }

    private static class CounterTicker implements BlockEntityTicker<CounterSource> {
        @Override
        public void tick(Level level, BlockPos pos, BlockState state, CounterSource source) {
            source.counter += source.root.value();
            if (source.fail) throw new IllegalStateException("example original ticker failed after its write");
        }
    }

    private static final class StatefulCounterTicker extends CounterTicker {
        private int unclaimedFunctionStorage;
    }

    private static final class CounterAdapter implements AuditedBlockEntityTickAdapter {
        private final boolean writeRoot;
        private final boolean invalidFingerprint;
        private final AtomicInteger inspections = new AtomicInteger();
        private final AtomicInteger canonicalPreparations = new AtomicInteger();
        private Runnable inspectObserver = () -> {};
        private int invalidFootprint;
        private boolean statefulTicker;

        private CounterAdapter(boolean writeRoot, boolean invalidFingerprint) {
            this.writeRoot = writeRoot;
            this.invalidFingerprint = invalidFingerprint;
        }

        @Override
        public Audit audit() {
            final var implementations = new ArrayList<Implementation>();
            for (final Class<?> type : List.of(CounterAdapter.class, CounterSource.class, CounterTicker.class, Root.class)) {
                implementations.add(new Implementation(type, this.invalidFingerprint && type == CounterTicker.class ? "0".repeat(64) : fingerprint(type), Set.of()));
            }
            if (this.statefulTicker) implementations.add(new Implementation(StatefulCounterTicker.class, fingerprint(StatefulCounterTicker.class), Set.of()));
            return new Audit("example:counter-audit", "1", Set.of(CounterSource.class),
                    Set.of(this.statefulTicker ? StatefulCounterTicker.class : CounterTicker.class), implementations);
        }

        @Override
        public void prepareCanonical(net.minecraft.server.level.ServerLevel level) {
            this.canonicalPreparations.incrementAndGet();
        }

        @Override
        public Proof inspect(BlockEntity source, Class<?> tickerImplementation) {
            this.inspectObserver.run();
            this.inspections.incrementAndGet();
            final var counter = (CounterSource) source;
            if (this.invalidFootprint == 1) return new Proof(List.of(new Storage(counter.root, Access.READ)));
            if (this.invalidFootprint == 2) return new Proof(List.of(new Storage(counter, Access.READ)));
            if (this.invalidFootprint == 3) return new Proof(List.of(new Storage(counter, Access.WRITE), new Storage(counter.getLevel(), Access.WRITE)));
            if (this.invalidFootprint == 4) return new Proof(List.of(new Storage(counter, Access.WRITE), new Storage(counter, Access.READ)));
            return new Proof(List.of(new Storage(counter, Access.WRITE), new Storage(counter.root, this.writeRoot ? Access.WRITE : Access.READ)));
        }

        @Override
        public Decision check(BlockEntity source) {
            return Decision.ADMITTED;
        }
    }

    private record ReentrantAdapter(ParallelApi.Registrar registrar, boolean catchesInvalid) implements AuditedBlockEntityTickAdapter {
        @Override
        public Audit audit() {
            try {
                this.registrar.registerBlockEntityTicks(this.catchesInvalid ? 99 : 1, ResourceLocation.parse("example:reentrant"),
                        Set.of(ResourceLocation.parse("minecraft:sign")), source -> Decision.ADMITTED);
            } catch (IllegalArgumentException failure) {
                if (!this.catchesInvalid) throw failure;
            }
            return new CounterAdapter(false, false).audit();
        }

        @Override
        public Proof inspect(BlockEntity source, Class<?> tickerImplementation) {
            throw new AssertionError("An invalid transaction must never invoke an inspection");
        }

        @Override
        public Decision check(BlockEntity source) {
            return Decision.ADMITTED;
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
