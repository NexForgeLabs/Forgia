/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.server.threading.RegionThreadingHooks;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class RegionSandboxAdapterTest {
    private static final BlockPos REGION_A_POS = new BlockPos(20, 64, 20);
    private static final BlockPos REGION_B_POS = new BlockPos(148, 64, 20);

    private static final class CounterSavedData extends SavedData {
        volatile int value;

        static SavedData.Factory<CounterSavedData> factory() {
            return new SavedData.Factory<>(CounterSavedData::new, (tag, provider) -> {
                final CounterSavedData d = new CounterSavedData();
                d.value = tag.getInt("v");
                return d;
            }, null);
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("v", this.value);
            return tag;
        }
    }

    @Test
    void undeclaredSavedDataIsIsolatedPerRegionWhileSharedSavedDataIsBroadcastVisible() throws Exception {
        final Path tempDir = Files.createTempDirectory("forgia-saveddata-sandbox");
        final DimensionDataStorage storage = new DimensionDataStorage(tempDir.toFile(), null, null);
        final String localId = "mod_region_local_counter";
        final String sharedId = "mod_global_shared_boss";
        RegionAdapterApi.shareSavedData(sharedId);
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:sandbox_worker");

        try (var f = new CanonicalBlockEntityTestSupport()) {
            final AtomicReference<CounterSavedData> localA = new AtomicReference<>();
            final AtomicReference<CounterSavedData> localB = new AtomicReference<>();
            final AtomicReference<CounterSavedData> sharedA = new AtomicReference<>();
            final AtomicReference<CounterSavedData> sharedB = new AtomicReference<>();

            f.script("A", REGION_A_POS, "test:sandbox_worker", () -> {
                assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                final CounterSavedData loc = storage.computeIfAbsent(CounterSavedData.factory(), localId);
                loc.value = 10;
                localA.set(loc);

                final CounterSavedData sh = storage.computeIfAbsent(CounterSavedData.factory(), sharedId);
                sh.value = 99;
                sharedA.set(sh);
            });

            f.script("B", REGION_B_POS, "test:sandbox_worker", () -> {
                assertTrue(RegionThreadingHooks.isNativeRegionWorkerThread());
                final CounterSavedData loc = storage.computeIfAbsent(CounterSavedData.factory(), localId);
                loc.value = 20;
                localB.set(loc);

                final CounterSavedData sh = storage.computeIfAbsent(CounterSavedData.factory(), sharedId);
                sharedB.set(sh);
            });

            f.enable(false);
            f.levelPhase();

            assertNotNull(localA.get());
            assertNotNull(localB.get());
            assertNotSame(localA.get(), localB.get(), "undeclared SavedData must remain isolated per region");
            assertEquals(10, localA.get().value);
            assertEquals(20, localB.get().value);

            assertNotNull(sharedA.get());
            assertSame(sharedA.get(), sharedB.get(), "shared SavedData must resolve to the same global instance");
            assertEquals(99, sharedB.get().value);

            // Main thread sees the merged shared instance
            final CounterSavedData mainShared = storage.get(CounterSavedData.factory(), sharedId);
            assertSame(sharedA.get(), mainShared);
            f.clean();
        } finally {
            RegionAdapterApi.unshareSavedData(sharedId);
        }
    }

    @Test
    void crossRegionCapabilityQueryReturnsFalseUntilVirtualAffinityIsBound() {
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:cap_sandbox_worker");
        final BlockPos plotVirtualPos = new BlockPos(16000, 64, 16000);
        final ChunkPos plotVirtualChunk = new ChunkPos(plotVirtualPos);

        try (var f = new CanonicalBlockEntityTestSupport()) {
            final AtomicBoolean foreignAllowedBeforeBind = new AtomicBoolean(true);
            final AtomicBoolean localAllowed = new AtomicBoolean(false);
            final AtomicBoolean virtualAllowedAfterBind = new AtomicBoolean(false);

            f.script("A", REGION_A_POS, "test:cap_sandbox_worker", () -> {
                localAllowed.set(RegionThreadingHooks.canAccessBlockCapabilitySandboxed(
                        f.level, REGION_A_POS, "neoforge:item_handler"));
                foreignAllowedBeforeBind.set(RegionThreadingHooks.canAccessBlockCapabilitySandboxed(
                        f.level, plotVirtualPos, "neoforge:item_handler"));
                RegionAdapterApi.bindVirtualRegionAffinity(f.level, plotVirtualChunk, REGION_A_POS);
                virtualAllowedAfterBind.set(RegionThreadingHooks.canAccessBlockCapabilitySandboxed(
                        f.level, plotVirtualPos, "neoforge:item_handler"));
            });

            f.enable(false);
            f.levelPhase();

            assertTrue(localAllowed.get(), "in-region capability access must succeed");
            assertFalse(foreignAllowedBeforeBind.get(), "unmapped cross-region capability access must be sandbox-blocked without throwing");
            assertTrue(virtualAllowedAfterBind.get(), "virtual sub-level chunk bound to the host region must be allowed");
            f.clean();
        }
    }

    @Test
    void broadcastAndSendToRegionDeliverThroughRegionMailboxes() {
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:mailbox_sandbox_worker");
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final AtomicInteger broadcastHits = new AtomicInteger();
            final List<String> targetedOrder = new CopyOnWriteArrayList<>();

            f.script("A", REGION_A_POS, "test:mailbox_sandbox_worker", () -> {
                targetedOrder.add("tick-A");
            });
            f.script("B", REGION_B_POS, "test:mailbox_sandbox_worker", () -> {
                targetedOrder.add("tick-B");
            });

            f.enable(false);
            // Tick 1 materializes both regions
            f.levelPhase();
            assertEquals(2, targetedOrder.size());
            targetedOrder.clear();

            // Post a global broadcast to all active regions and a targeted message to region B
            final int accepted = RegionAdapterApi.broadcastGlobal(f.level, ctx -> broadcastHits.incrementAndGet());
            assertEquals(2, accepted);
            assertTrue(RegionAdapterApi.sendToRegion(f.level, REGION_B_POS, () -> targetedOrder.add("mailbox-B")));

            // Tick 2 delivers the region mailboxes before running the region ticks
            f.levelPhase();
            assertEquals(2, broadcastHits.get());
            assertTrue(targetedOrder.contains("mailbox-B"));
            f.clean();
        }
    }

    @Test
    void transientBoundaryFallbackCoolsDownAndEscalatesAtThreshold() throws Exception {
        try (var f = new CanonicalBlockEntityTestSupport()) {
            final net.minecraft.server.level.ServerLevel level = f.level;
            final BlockPos pos = new BlockPos(32, 64, 32);
            final String type = "test:transient_sandbox_be";
            final java.util.concurrent.atomic.AtomicLong gameTime = new java.util.concurrent.atomic.AtomicLong(100L);
            org.mockito.Mockito.when(level.getGameTime()).thenAnswer(inv -> gameTime.get());

            assertTrue(RegionAdapterApi.recordTransientBoundaryFallback(level, pos, type, 20L));
            assertTrue(RegionAdapterApi.isTransientSandboxedFallback(level, pos));

            gameTime.set(119L);
            assertTrue(RegionAdapterApi.isTransientSandboxedFallback(level, pos), "Still within 20-tick cooldown");
            gameTime.set(120L);
            assertFalse(RegionAdapterApi.isTransientSandboxedFallback(level, pos), "Cooldown expired, auto-returned to worker");

            // Repeating up to threshold (16 total) escalates to bounded exponential backoff for this coordinate
            for (int i = 2; i < 16; i++) {
                assertTrue(RegionAdapterApi.recordTransientBoundaryFallback(level, pos, type, 20L));
            }
            assertFalse(RegionAdapterApi.recordTransientBoundaryFallback(level, pos, type, 20L),
                    "16th boundary violation at pos must escalate to extended coordinate backoff");
            gameTime.set(300L);
            assertTrue(RegionAdapterApi.isTransientSandboxedFallback(level, pos),
                    "Escalated coordinate must stay in server-thread fallback beyond base 20-tick cooldown");
            gameTime.set(999_999L);
            assertFalse(RegionAdapterApi.isTransientSandboxedFallback(level, pos),
                    "Escalated coordinate must eventually re-probe worker after max backoff instead of leaking permanently");

            final BlockPos inlandPos = new BlockPos(96, 64, 96);
            assertFalse(RegionAdapterApi.isTransientSandboxedFallback(level, inlandPos),
                    "Inland coordinate of the same type must remain eligible for worker sandbox");
            assertTrue(RegionAdapterApi.recordTransientBoundaryFallback(level, inlandPos, type, 20L));
        }
    }

    @Test
    void fakePlayerLocalOperationsAreAllowedOnRegionWorkers() {
        RegionAdapterApi.admitRegionSandboxedBlockEntities("test:fake_player_sandbox_worker");
        final net.minecraft.server.PlayerAdvancements fakeAdvancements = org.mockito.Mockito.mock(net.minecraft.server.PlayerAdvancements.class);
        org.mockito.Mockito.when(fakeAdvancements.forgiaIsFakePlayer()).thenReturn(true);
        final net.neoforged.neoforge.common.util.FakePlayer fakePlayer = org.mockito.Mockito.mock(net.neoforged.neoforge.common.util.FakePlayer.class);
        final AtomicReference<Throwable> workerError = new AtomicReference<>();
        final AtomicBoolean ranOnWorker = new AtomicBoolean();
        try (var f = new CanonicalBlockEntityTestSupport()) {
            org.mockito.Mockito.when(fakePlayer.level()).thenReturn(f.level);
            org.mockito.Mockito.when(fakePlayer.isAddedToLevel()).thenReturn(false);
            org.mockito.Mockito.when(fakePlayer.getLevelCallback()).thenReturn(net.minecraft.world.level.entity.EntityInLevelCallback.NULL);

            f.script("A", REGION_A_POS, "test:fake_player_sandbox_worker", () -> {
                ranOnWorker.set(RegionThreadingHooks.isNativeRegionWorkerThread());
                try {
                    net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.checkAdvancements(fakeAdvancements);
                    net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.checkAward(fakeAdvancements, null);
                    net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.checkTrigger(null, fakePlayer);
                    net.neoforged.neoforge.server.threading.region.RegionPlayerStateScope.checkReward(null, fakePlayer);
                    assertFalse(RegionThreadingHooks.routeWorkerPlayerAction(fakePlayer, p -> {}));
                    RegionThreadingHooks.assertServerEntityStorageAccess(fakePlayer, "Entity.setPosRaw");
                    RegionThreadingHooks.assertServerEntityStorageAccess(fakePlayer, "ServerPlayer.teleportRelative");
                } catch (Throwable t) {
                    workerError.set(t);
                }
            });
            f.enable(false);
            f.levelPhase();
            assertTrue(ranOnWorker.get(), "callback must run on a native region worker");
            if (workerError.get() != null) {
                throw new AssertionError("FakePlayer operations on region worker threw unexpected exception", workerError.get());
            }
            f.clean();
        }
    }
}
