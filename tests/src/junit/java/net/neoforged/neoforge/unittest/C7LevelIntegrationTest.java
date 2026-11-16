/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.server.threading.c7.BlockEntityExecutionSystem;
import net.neoforged.neoforge.server.threading.c7.C7FurnaceProgram;
import net.neoforged.neoforge.server.threading.c7.C7Options;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(60)
class C7LevelIntegrationTest {
    private static final BlockPos A = new BlockPos(20, 64, 20);
    private static final BlockPos B = A.east(3);

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void canonicalLevelUsesOnlyC7WithoutTouchingC6State(boolean persistent) {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, persistent)) {
            test.clock("a", A, 100, 12, 200);
            test.clock("b", B, 100, 18, 200);
            ForgiaConfig.SERVER.c7Debug.set(true);
            assertSame(C7FurnaceProgram.ticker(), net.minecraft.world.level.chunk.LevelChunk.actualBlockEntityFunction(test.f.tickers.get("a")));
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(2, test.f.engine.c7Metrics().parallel());
            assertEquals(0, test.f.engine.c7Metrics().serial());
            assertEquals(2, test.f.engine.c7Metrics().types().get("minecraft:furnace").parallel());
            assertEquals(0, test.f.engine.diagnostics().blockEntityTicksOnWorkers());
            assertEquals(0, test.f.engine.furnaceWorkMetrics().nativeCompletions());
            assertEquals(0, test.f.engine.blockEntityResourceMetrics().issuedLeases());
            assertTrue(test.f.observedTickets.isEmpty());
            assertTrue(test.f.observedBatches.isEmpty());
            test.f.clean();
            test.f.probes();
        }
    }

    @ParameterizedTest
    @EnumSource(BlockEntityExecutionSystem.class)
    void allThreeDriversTickEachInvocationExactlyOnce(BlockEntityExecutionSystem system) {
        try (final var test = new C7TestSupport(system, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 1, 200);
            test.clock("b", B, 100, 1, 200);
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(system == BlockEntityExecutionSystem.C7 ? 2 : 0, test.f.engine.c7Metrics().collected());
            if (system != BlockEntityExecutionSystem.C6) assertEquals(0, test.f.engine.diagnostics().blockEntityTicksOnWorkers());
            test.f.clean();
        }
    }

    @Test
    void selectedButDisabledC7UsesSerialNotC6() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            ForgiaConfig.SERVER.enableC7.set(false);
            C7TestSupport.clearCaches();
            test.clock("a", A, 100, 1, 200);
            test.clock("b", B, 100, 1, 200);
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(0, test.f.engine.c7Metrics().collected());
            assertEquals(0, test.f.engine.diagnostics().blockEntityTicksOnWorkers());
        }
    }

    @Test
    void duplicateLevelMembershipCannotDoubleTickTheSameTicker() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 1, 200);
            test.clock("b", B, 100, 1, 200);
            test.f.level.addBlockEntityTicker(test.f.tickers.get("a"));
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(2, test.f.engine.c7Metrics().collected());
            test.f.clean();
        }
    }

    @Test
    void shadowBuildsGraphButKeepsOriginalSerialInvocations() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.SHADOW, true)) {
            test.clock("a", A, 100, 1, 200);
            test.clock("b", B, 100, 1, 200);
            test.f.levelPhase();
            assertEquals(2, test.f.engine.c7Metrics().serial());
            assertEquals(0, test.f.engine.c7Metrics().parallel());
            assertTrue(test.f.engine.c7Metrics().graphNanos() > 0);
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
        }
    }

    @Test
    void supportedBranchesMatchOriginalSerialStateTickForTick() {
        final List<java.util.Map<String, String>> reference = new ArrayList<>();
        for (final var system : List.of(BlockEntityExecutionSystem.DISABLED, BlockEntityExecutionSystem.C7)) {
            try (final var test = new C7TestSupport(system, C7Options.Mode.PARALLEL, true)) {
                for (int index = 0; index < 8; ++index) test.clock("f" + index, A.east(index * 3),
                        index < 4 ? 4 : 0, index * 3, 200);
                for (int tick = 0; tick < 12; ++tick) {
                    test.f.levelPhase();
                    if (system == BlockEntityExecutionSystem.DISABLED) reference.add(test.f.state());
                    else assertEquals(reference.get(tick), test.f.state(), "tick " + tick);
                }
                if (system == BlockEntityExecutionSystem.C7) {
                    assertTrue(test.f.engine.c7Metrics().parallel() > 0);
                    assertTrue(test.f.engine.c7Metrics().serial() > 0, "extinguishing must retain its complete original body");
                }
            }
        }
    }

    @Test
    void blastFurnaceAndSmokerUseTheSameAuditedCompleteClockBranch() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 12, 200);
            test.clock("b", B, 100, 18, 200);
            final var blast = new net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity(A, Blocks.BLAST_FURNACE.defaultBlockState());
            final var smoker = new net.minecraft.world.level.block.entity.SmokerBlockEntity(B, Blocks.SMOKER.defaultBlockState());
            for (final var furnace : List.of(blast, smoker))
                CanonicalBlockEntityTestSupport.set(AbstractFurnaceBlockEntity.class, furnace, "litTime", 100);
            test.rebind("a", blast);
            test.rebind("b", smoker);
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(2, test.f.engine.c7Metrics().parallel());
            test.f.clean();
        }
    }

    @Test
    void unknownActuallyMergedFurnaceMixinKeepsC7Serial() {
        assertTrue(Boolean.getBoolean("forgia.furnace.testUnverifiedHook"), "isolated merged-hook control required");
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 12, 200);
            test.clock("b", B, 100, 18, 200);
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(0, test.f.engine.c7Metrics().parallel());
            assertEquals(2, test.f.engine.c7Metrics().serial());
            assertTrue(test.f.engine.c7Metrics().proofRefusals().containsKey(net.neoforged.neoforge.server.threading.c7.C7Refusal.UNVERIFIED_RUNTIME));
            test.f.clean();
        }
    }

    @Test
    void cookingAndHoppersRemainOriginalSerialFences() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.f.furnace("cooking", A, 0);
            test.f.script("hopper", B, "minecraft:hopper", () -> {});
            test.f.levelPhase();
            assertEquals(2, test.f.engine.c7Metrics().serial());
            assertEquals(0, test.f.engine.c7Metrics().parallel());
            assertEquals(99, test.lit("cooking"));
            assertEquals(1L, test.f.engine.c7Metrics().types().get("minecraft:furnace").serialReasons().get(net.neoforged.neoforge.server.threading.c7.C7Refusal.UNSUPPORTED_BRANCH));
            assertEquals(1L, test.f.engine.c7Metrics().types().get("minecraft:hopper").serialReasons().get(net.neoforged.neoforge.server.threading.c7.C7Refusal.INVENTORY_OR_TRANSFER_NOT_SUPPORTED));
            final var hopper = test.f.world.blockEntities.get(B);
            assertEquals(9999, CanonicalBlockEntityTestSupport.get(net.minecraft.world.level.block.entity.HopperBlockEntity.class, hopper, "cooldownTime"));
        }
    }

    @Test
    void serialUnknownCallbackCanReplaceTheNextBindingWithoutAStaleTick() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            final var replacement = new FurnaceBlockEntity(A, Blocks.FURNACE.defaultBlockState());
            CanonicalBlockEntityTestSupport.set(AbstractFurnaceBlockEntity.class, replacement, "litTime", 100);
            test.f.foreign("fence", A.west(3), () -> test.rebind("a", replacement));
            test.clock("a", A, 100, 0, 200);
            test.clock("b", B, 100, 0, 200);
            final var retired = test.f.furnaces.get("a");
            test.f.levelPhase();
            assertEquals(100, CanonicalBlockEntityTestSupport.get(AbstractFurnaceBlockEntity.class, retired, "litTime"));
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(1, test.f.cells.get("fence").get());
            test.f.clean();
        }
    }

    @Test
    void removalAtJoinSkipsUncommittedWorkAndPrunesMembership() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 0, 200);
            test.clock("b", B, 100, 0, 200);
            test.f.barrier = done -> {
                while (!done.getAsBoolean()) Thread.onSpinWait();
                test.f.furnaces.get("a").setRemoved();
            };
            test.f.levelPhase();
            assertEquals(100, test.lit("a"));
            assertEquals(99, test.lit("b"));
            assertEquals(1, test.f.engine.c7Metrics().skipped());
            assertEquals(1, test.f.level.snapshotBlockEntityTickers().size());
            test.f.clean();
        }
    }

    @Test
    void unloadAtJoinDoesNotCommitOrFallBackToAnUnloadedChunk() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 0, 200);
            test.clock("b", B, 100, 0, 200);
            test.f.barrier = done -> {
                while (!done.getAsBoolean()) Thread.onSpinWait();
                doReturn(false).when(test.f.chunk(A)).isLoaded();
                test.f.eligible.put(net.minecraft.world.level.ChunkPos.asLong(A), false);
            };
            test.f.levelPhase();
            assertEquals(100, test.lit("a"));
            assertEquals(100, test.lit("b"));
            assertEquals(2, test.f.engine.c7Metrics().skipped());
            test.f.clean();
        }
    }

    @Test
    void reloadedChunkAndNewInstancesDoNotInheritOldSnapshots() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.clock("a", A, 100, 0, 200);
            test.clock("b", B, 100, 0, 200);
            test.f.levelPhase();
            final var retired = test.f.furnaces.get("a");
            retired.setRemoved();
            test.f.level.removeCollectedBlockEntityTickers(List.of(test.f.tickers.get("a")));
            test.f.chunks.remove(net.minecraft.world.level.ChunkPos.asLong(A));
            test.clock("a", A, 100, 0, 200);
            test.f.levelPhase();
            assertEquals(99, test.lit("a"));
            assertEquals(99, CanonicalBlockEntityTestSupport.get(AbstractFurnaceBlockEntity.class, retired, "litTime"));
            test.f.clean();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void externalUnloadOrShutdownWaitsUntilTheC7LifetimeEnds(boolean shutdown) throws Exception {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            final var entered = new CountDownLatch(1);
            final var attempting = new CountDownLatch(1);
            final var returned = new AtomicBoolean();
            final var failure = new AtomicReference<Throwable>();
            test.clock("a", A, 100, 0, 200);
            test.clock("b", B, 100, 0, 200);
            test.f.foreign("fence", A.east(6), () -> {
                entered.countDown();
                CanonicalBlockEntityTestSupport.await(attempting);
                assertFalse(returned.get(), "cleanup must not retire a phase still holding its lifetime gate");
            });
            final var cleanup = new Thread(() -> {
                try {
                    CanonicalBlockEntityTestSupport.await(entered);
                    attempting.countDown();
                    if (shutdown) test.f.engine.shutdown();
                    else test.f.engine.clearLevelRegionTasks(test.f.level);
                    returned.set(true);
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "c7-lifecycle-cleanup");
            cleanup.start();
            try {
                test.f.levelPhase();
            } finally {
                cleanup.join(10000);
            }
            assertFalse(cleanup.isAlive());
            assertNull(failure.get());
            assertTrue(returned.get());
            assertEquals(99, test.lit("a"));
            assertEquals(99, test.lit("b"));
            test.f.clean();
        }
    }

    @Test
    void lifecycleSelfWaitIsRejectedInsideTheActualCanonicalCallback() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            test.f.foreign("self", A, () -> {
                assertThrows(IllegalStateException.class, test.f.engine::shutdown);
                assertThrows(IllegalStateException.class, () -> test.f.engine.clearLevelRegionTasks(test.f.level));
                assertThrows(IllegalStateException.class, test.f::levelPhase);
            });
            test.f.levelPhase();
            assertEquals(1, test.f.cells.get("self").get());
            test.f.clean();
        }
    }

    @Test
    void exceptionAfterMutationDoesNotReplayAndStillFinalizesTheLevelPhase() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            final var writes = new AtomicInteger();
            test.f.foreign("throws", A, () -> {
                writes.incrementAndGet();
                throw new IllegalStateException("after mutation");
            });
            assertThrows(IllegalStateException.class, test.f::levelPhase);
            assertEquals(1, writes.get());
            assertEquals(1, test.f.engine.c7Metrics().failed());
            assertEquals(false, CanonicalBlockEntityTestSupport.get(Level.class, test.f.level, "tickingBlockEntities"));
            test.f.clean();
        }
    }

    @Test
    void manyMachinesInsideOneRegionAndAcrossChunksTickExactlyOnce() {
        try (final var test = new C7TestSupport(BlockEntityExecutionSystem.C7, C7Options.Mode.PARALLEL, true)) {
            for (int i = 0; i < 256; ++i) test.clock("f" + i, new BlockPos(16 + i % 32, 64, 16 + i / 32), 100, i, 200);
            test.f.levelPhase();
            for (int i = 0; i < 256; ++i) assertEquals(99, test.lit("f" + i));
            assertEquals(256, test.f.engine.c7Metrics().parallel());
            assertEquals(0, test.f.engine.c7Metrics().serial());
            test.f.clean();
        }
    }
}
