/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CanonicalBlockEntityTestSupport.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Component races through the actual unload callback; these mocked-world controls are not real-Level GC. */
@Tag("canonical-be-contract")
@Timeout(60)
class LifecycleRetirementRaceTest {
    @ParameterizedTest
    @ValueSource(strings = { "queued", "queued-rebind", "running", "releasing" })
    void repeatedUnloadAndShutdownJoinThePublishedPhaseAndOldCallbacksCannotWrite(String state) throws InterruptedException {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final var a = new BlockPos(20, 64, 20);
            final var b = new BlockPos(68, 64, 20);
            final boolean releasing = state.equals("releasing");
            if (releasing) {
                f.furnace("A", a, 1);
                f.furnace("B", b, 1);
            } else {
                f.script("A", a, "minecraft:hopper", () -> {});
                f.script("B", b, "minecraft:hopper", () -> {});
            }
            f.enable(releasing, false);
            final var parked = new CountDownLatch(state.equals("running") ? 2 : 1);
            final var release = new CountDownLatch(1);
            final var shutdownAttempt = new CountDownLatch(1);
            final var unloadReturned = new AtomicBoolean();
            final var shutdownReturned = new AtomicBoolean();
            final var failure = new AtomicReference<Throwable>();
            final List<Runnable> oldCallbacks = new CopyOnWriteArrayList<>();
            final var interceptedWait = new AtomicBoolean();
            final var handler = new NeoForgeEventHandler();
            if (state.equals("running")) f.onStart = id -> {
                parked.countDown();
                await(release);
            };
            if (releasing) f.onChange = id -> {
                if (!id.equals("A")) return;
                parked.countDown();
                await(f.lifecycleWriteHeld);
                await(shutdownAttempt);
                assertFalse(unloadReturned.get());
                assertFalse(shutdownReturned.get());
                release.countDown();
            };
            final var normal = f.barrier;
            f.barrier = done -> {
                if (Thread.currentThread() == f.serverThread && interceptedWait.compareAndSet(false, true)) {
                    oldCallbacks.addAll(f.controlled.queued);
                    assertFalse(oldCallbacks.isEmpty(), "Retain the actual original pool callbacks");
                    if (state.startsWith("queued")) parked.countDown();
                    if (state.equals("running")) f.controlled.dispatchAll();
                    if (!releasing) {
                        await(parked);
                        await(f.lifecycleWriteHeld);
                        await(shutdownAttempt);
                        assertFalse(unloadReturned.get());
                        assertFalse(shutdownReturned.get());
                        if (state.startsWith("queued")) for (var cell : f.cells.values()) assertEquals(0, cell.get(), "No queued callback may run early");
                        if (state.equals("queued-rebind")) {
                            for (var ticker : f.tickers.values()) {
                                final var oldFrame = LevelChunk.captureCoreTickerFrame(ticker);
                                try {
                                    final var rebind = ticker.getClass().getDeclaredMethod("rebind", TickingBlockEntity.class);
                                    rebind.setAccessible(true);
                                    rebind.invoke(ticker, oldFrame.delegate());
                                } catch (ReflectiveOperationException error) {
                                    throw new AssertionError(error);
                                }
                                assertFalse(oldFrame.current(), "Even same-delegate rebind during pending unload must revoke captured binding");
                            }
                        }
                        release.countDown();
                    }
                }
                normal.await(done);
            };
            final var unload = new Thread(() -> {
                try {
                    await(parked);
                    handler.onDimensionUnload(new LevelEvent.Unload(f.level));
                    handler.onDimensionUnload(new LevelEvent.Unload(f.level));
                    unloadReturned.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "lifecycle-repeat-unload");
            final var shutdown = new Thread(() -> {
                try {
                    await(f.lifecycleWriteHeld);
                    shutdownAttempt.countDown();
                    f.engine.shutdown();
                    f.engine.shutdown();
                    shutdownReturned.set(true);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            }, "lifecycle-shutdown-during-unload");
            unload.start();
            shutdown.start();
            try {
                f.levelPhase();
                unload.join(10000);
                shutdown.join(10000);
                assertFalse(unload.isAlive());
                assertFalse(shutdown.isAlive());
                assertNull(failure.get());
                assertTrue(unloadReturned.get() && shutdownReturned.get());
                if (releasing) for (var furnace : f.furnaces.values()) assertEquals(1, furnace.getItem(2).getCount(), "Each original commit once");
                else for (var cell : f.cells.values()) assertEquals(1, cell.get(), "Each original body once");
                final var before = f.state();
                final var replayAttempts = new AtomicInteger();
                final var replayFailure = new AtomicReference<Throwable>();
                final var late = new Thread(() -> {
                    for (Runnable callback : oldCallbacks) {
                        // The original pool wrapper reports a rejected callback itself. Attempt every
                        // retained callback even if an individual refusal escapes the wrapper.
                        try {
                            callback.run();
                        } catch (Throwable problem) {
                            problem.printStackTrace(System.out);
                            if (!(problem instanceof IllegalStateException)) replayFailure.compareAndSet(null, problem);
                        } finally {
                            replayAttempts.incrementAndGet();
                        }
                    }
                }, "lifecycle-old-callback-after-retirement");
                late.start();
                late.join(10000);
                assertFalse(late.isAlive());
                assertEquals(oldCallbacks.size(), replayAttempts.get(), "No stale callback attempt may be silently unprocessed");
                assertNull(replayFailure.get());
                assertEquals(before, f.state(), "Old actual callbacks must have zero forbidden side effects");
                assertNull(f.engine.beginBlockEntityTickPlan(f.level, f.barrier), "Old Level identity must not regain phase authority");
                f.clean();
                System.out.println("LIFECYCLE_RETIREMENT_RACE state=" + state + " repeatedUnload=true shutdownDuringCleanup=true physicalJoin=true oldCallbackSideEffects=0");
            } finally {
                release.countDown();
                unload.join(10000);
                shutdown.join(10000);
            }
        }
    }
}
