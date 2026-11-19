/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Access;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Binding;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Identity;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Scope;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.SharedReads;
import net.neoforged.neoforge.server.threading.region.BlockEntityWorkPlanner;
import net.neoforged.neoforge.server.threading.region.RegionCoordinate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Resource/lifecycle tests; two lookup-hook boundary tests use mocked worlds, never historical grants. */
@Timeout(20)
class BlockEntityResourceOwnershipTest {
    private final Object level = new Object();
    private final Object region = new Object();
    private final Object x = new Object();
    private final Object y = new Object();

    private Binding binding(Object instance) {
        return new Binding(new Identity(this.level, instance), this.region, () -> true, null);
    }

    private Claims claims(Object instance, Access access) {
        return Claims.builder(this.level, this.region).claim(this.binding(instance), access).build();
    }

    private Owner ownership() {
        return new Owner(this.level, Map.of(this.region, () -> true), () -> true);
    }

    @Test
    void identityUsesActualDomainAndObjectAndIgnoresUserEquals() {
        final Object equalA = new String("same");
        final Object equalB = new String("same");
        assertEquals(equalA, equalB);
        final var first = new Identity(this.level, equalA);
        assertEquals(first, new Identity(this.level, equalA));
        assertEquals(first.hashCode(), new Identity(this.level, equalA).hashCode());
        assertNotEquals(first, new Identity(this.level, equalB));
        assertNotEquals(first, new Identity(new Object(), equalA));
        assertNotEquals(new BlockEntityWorkPlanner.Resource(this.level, equalA), new BlockEntityWorkPlanner.Resource(this.level, BlockEntityWorkPlanner.Kind.BLOCK_ENTITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockEntityWorkPlanner.Resource(this.level, BlockEntityWorkPlanner.Kind.INSTANCE, 0));
    }

    @Test
    void readsShareAndEveryWriteConflictPreservesDirectedOrder() {
        assertEquals(List.of(List.of(0, 1)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ))).dependencies().waves());
        for (final Access first : Access.values()) {
            for (final Access second : Access.values()) {
                if (first == Access.READ && second == Access.READ) continue;
                assertEquals(List.of(List.of(0), List.of(1)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, first), this.claims(this.x, second))).dependencies().waves());
            }
        }
        final var middle = Claims.builder(this.level, this.region).claim(this.binding(this.x), Access.WRITE).claim(this.binding(this.y), Access.WRITE).build();
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.WRITE), middle, this.claims(this.y, Access.WRITE))).dependencies().waves());
    }

    @Test
    void duplicateResourcesUpgradeToWriteButKeepEveryAttachmentValidator() {
        final var calls = new AtomicInteger();
        final var claims = Claims.builder(this.level, this.region)
                .claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
                    calls.incrementAndGet();
                    return true;
                }, null), Access.READ)
                .claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
                    calls.incrementAndGet();
                    return false;
                }, null), Access.WRITE).build();
        assertTrue(claims.complete());
        assertEquals(Set.of(new BlockEntityWorkPlanner.Resource(this.level, this.x)), claims.unit().writes());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(claims)));
            assertEquals(2, calls.get());
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))), "failed reservation must not leave partial occupancy");
        }
    }

    @Test
    void conflictsAreRefusedAtomicallyBeforeAnyLeaseEscapes() {
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.WRITE))));
            final var grants = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ)));
            assertNotNull(grants);
            assertEquals(2, grants.size());
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))), "no expanding a published wave");
        }
    }

    @Test
    void foreignDomainsRegionsAndMissingOuterExclusionNeverGrant() {
        final var foreignLevel = Claims.builder(new Object(), this.region).claim(this.binding(this.x), Access.WRITE).build();
        final var foreignRegion = Claims.builder(this.level, new Object()).claim(this.binding(this.x), Access.WRITE).build();
        assertFalse(foreignLevel.complete());
        assertFalse(foreignRegion.complete());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(foreignLevel)));
            assertNull(owner.tryAcquireAll(List.of(foreignRegion)));
        }
        try (final var owner = new Owner(this.level, Map.of(), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
        }
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> false), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
        }
    }

    @Test
    void globalReadsCannotBeConvertedToRegionWritesThroughAnAlias() {
        final var shared = new Binding(new Identity(this.level, this.x), null, () -> true, null);
        final var read = Claims.builder(this.level, this.region).claim(shared, Access.READ).build();
        assertTrue(read.complete());
        assertFalse(Claims.builder(this.level, this.region).claim(shared, Access.WRITE).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(shared, Access.READ).claim(this.binding(this.x), Access.WRITE).build().complete());
        final var plan = BlockEntityResourceOwnership.plan(List.of(read, this.claims(this.x, Access.WRITE)));
        assertTrue(plan.claims().stream().noneMatch(Claims::complete));
        assertEquals(List.of(List.of(0), List.of(1)), plan.dependencies().waves());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(read, this.claims(this.x, Access.WRITE))));
        }
    }

    @Test
    void inconsistentRegionHomesRefuseEvenReadAliasesAndDoNotWaitForLocks() {
        final Object otherRegion = new Object();
        final var first = this.claims(this.x, Access.READ);
        final var second = Claims.builder(this.level, otherRegion)
                .claim(new Binding(new Identity(this.level, this.x), otherRegion, () -> true, null), Access.READ).build();
        assertTrue(BlockEntityResourceOwnership.plan(List.of(first, second)).claims().stream().noneMatch(Claims::complete));
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true, otherRegion, () -> true), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(first, second)));
        }
    }

    @Test
    void unknownEmptyAndOverflowedProofsAreCompleteSerialFences() {
        final var builder = Claims.builder(this.level, this.region);
        for (int index = 0; index <= BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT; ++index) builder.claim(this.binding(new Object()), Access.READ);
        final var overflow = builder.build();
        assertFalse(overflow.complete());
        assertFalse(Claims.builder(this.level, this.region).build().complete());
        final var plan = BlockEntityResourceOwnership.plan(List.of(this.claims(this.x, Access.WRITE), overflow, this.claims(this.y, Access.READ)));
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), plan.dependencies().waves());
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(overflow)));
        }
    }

    @Test
    void sharedReadPublicationCannotBeUsedAsAWorldChunkPermission() {
        final var world = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        final var chunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        org.mockito.Mockito.doReturn(world).when(chunk).getLevel();
        final var shared = Claims.builder(world, this.region)
                .claim(new Binding(new Identity(world, chunk), null, () -> true, null), Access.READ).build();
        try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
            final var leases = owner.tryAcquireAll(List.of(shared));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, false));
            }
        }
        final var local = Claims.builder(world, this.region)
                .claim(new Binding(new Identity(world, chunk), this.region, () -> true, null), Access.READ).build();
        try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
            final var leases = owner.tryAcquireAll(List.of(local));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                assertTrue(Scope.checkChunk(chunk, false));
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, true));
            }
        }
    }

    @Test
    void liveBlockEntityLookupCannotUpgradeAReadClaimToMutableAccess() {
        final var world = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        final var position = new net.minecraft.core.BlockPos(17, 64, 17);
        for (final Access mode : Access.values()) {
            final var claims = Claims.builder(world, this.region)
                    .claim(new Binding(new Identity(world, this.x), this.region, () -> true, position), mode).build();
            try (final var owner = new Owner(world, Map.of(this.region, () -> true), () -> true)) {
                final var leases = owner.tryAcquireAll(List.of(claims));
                assertNotNull(leases);
                try (final var scope = leases.getFirst().enter()) {
                    if (mode == Access.READ) assertThrows(IllegalStateException.class, () -> Scope.checkReference(world, position));
                    else Scope.checkReference(world, position);
                    assertThrows(IllegalStateException.class, () -> Scope.checkReference(world, position.east()));
                }
            }
        }
    }

    @Test
    void aggregateProofBudgetCannotPublishAPartiallyVerifiedPhase() {
        final var builder = Claims.builder(this.level, this.region);
        for (int index = 0; index < BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT; ++index) builder.claim(this.binding(new Object()), Access.READ);
        final var claims = builder.build();
        assertTrue(claims.complete());
        final var inputs = java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE / BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT + 1, claims);
        assertThrows(IllegalArgumentException.class, () -> BlockEntityResourceOwnership.plan(inputs));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(inputs));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
        }
    }

    @Test
    void scopesAreReadWriteCheckedThreadBoundAndOneShotEvenAfterBodyFailure() {
        final var calls = new AtomicInteger();
        final var owner = this.ownership();
        final var grants = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ)));
        assertNotNull(grants);
        final var lease = grants.getFirst();
        assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.READ));
        assertThrows(UnsupportedOperationException.class, () -> {
            try (final var scope = lease.enter()) {
                Scope.require(this.level, this.x, Access.READ);
                assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                assertThrows(IllegalStateException.class, () -> Scope.require(new Object(), this.x, Access.READ));
                assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.y, Access.READ));
                assertThrows(NullPointerException.class, () -> Scope.require(this.level, this.x, null));
                assertThrows(IllegalStateException.class, lease::enter);
                calls.incrementAndGet();
                throw new UnsupportedOperationException("after effect");
            }
        });
        assertFalse(Scope.active());
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, lease::enter);
        owner.close();
        assertThrows(IllegalStateException.class, lease::enter);
    }

    @Test
    void readReadScopesOverlapAndCoordinatorCannotReleaseRunningResources() throws Exception {
        final var owner = this.ownership();
        final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ), this.claims(this.x, Access.READ)));
        assertNotNull(leases);
        final var entered = new CyclicBarrier(3);
        final var release = new CountDownLatch(1);
        try (final var executor = Executors.newFixedThreadPool(2)) {
            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (final var lease : leases) futures.add(executor.submit(() -> {
                try (final var scope = lease.enter()) {
                    Scope.require(this.level, this.x, Access.READ);
                    entered.await(5, TimeUnit.SECONDS);
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader not released");
                    Scope.require(this.level, this.x, Access.READ);
                }
                return null;
            }));
            try {
                entered.await(5, TimeUnit.SECONDS);
                assertThrows(IllegalStateException.class, owner::close);
            } finally {
                release.countDown();
            }
            for (final var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            owner.close();
        }
    }

    @Test
    void oneLeaseCannotExecuteOnTwoThreadsOrBeReplayedAfterJoin() throws Exception {
        final var effects = new AtomicInteger();
        final var start = new CyclicBarrier(2);
        try (final var owner = this.ownership(); final var executor = Executors.newFixedThreadPool(2)) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            final var lease = leases.getFirst();
            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 2; ++index) futures.add(executor.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                try (final var scope = lease.enter()) {
                    Scope.require(this.level, this.x, Access.WRITE);
                    effects.incrementAndGet();
                } catch (IllegalStateException refused) {
                    // The other runner owns or consumed this exact invocation.
                }
                return null;
            }));
            for (final var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(1, effects.get());
            assertThrows(IllegalStateException.class, lease::enter);
        }
    }

    @Test
    void staleBoundaryAndLifecycleCannotResurrectOldLeases() {
        final var loaded = new AtomicBoolean(true);
        final var live = new AtomicBoolean(false);
        try (final var owner = new Owner(this.level, Map.of(this.region, loaded::get), live::get)) {
            assertNull(owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE))));
            live.set(true);
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            loaded.set(false);
            assertThrows(IllegalStateException.class, leases.getFirst()::enter);
            loaded.set(true);
            assertThrows(IllegalStateException.class, leases.getFirst()::enter);
        }
    }

    @Test
    void shutdownRequestAfterPublicationDoesNotRevokeAnAcceptedBatch() {
        final var live = new AtomicBoolean(true);
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true), live::get)) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            live.set(false); // Existing outer batch publication is still held until join/commit.
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, this.x, Access.WRITE);
            }
        }
    }

    @Test
    void reservationAndReleaseCannotBeInvokedFromAnotherThread() throws Exception {
        try (final var owner = this.ownership(); final var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> owner.tryAcquireAll(List.of(this.claims(this.x, Access.READ))));
                assertThrows(IllegalStateException.class, owner::close);
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void scopeCannotBeInheritedOrClosedByAnUnrelatedThread() throws Exception {
        try (final var owner = this.ownership(); final var executor = Executors.newSingleThreadExecutor()) {
            final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
            assertNotNull(leases);
            final var scope = leases.getFirst().enter();
            try {
                executor.submit(() -> {
                    assertFalse(Scope.active());
                    assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                    assertThrows(IllegalStateException.class, scope::close);
                }).get(5, TimeUnit.SECONDS);
                Scope.require(this.level, this.x, Access.WRITE);
            } finally {
                scope.close();
            }
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(Scope.active());
        }
    }

    @Test
    void aThrowingAttachmentProofPublishesNoLeaseAndRetainsTheOriginalFailure() {
        final var failure = new IllegalStateException("unverifiable attachment");
        final var proof = Claims.builder(this.level, this.region).claim(new Binding(new Identity(this.level, this.x), this.region, () -> {
            throw failure;
        }, null), Access.WRITE).build();
        try (final var owner = this.ownership()) {
            assertEquals(failure, assertThrows(IllegalStateException.class, () -> owner.tryAcquireAll(List.of(proof))));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
            assertFalse(Scope.active());
        }
    }

    @Test
    void randomizedDirectedWavesMatchAnIndependentPairwiseOrderOracle() {
        final var random = new Random(20261113L);
        final Object[] resources = new Object[8];
        for (int index = 0; index < resources.length; ++index) resources[index] = new Object();
        for (int iteration = 0; iteration < 1000; ++iteration) {
            final List<Claims> inputs = new ArrayList<>();
            final List<BlockEntityWorkPlanner.Unit> units = new ArrayList<>();
            final int size = 1 + random.nextInt(30);
            for (int index = 0; index < size; ++index) {
                final var builder = Claims.builder(this.level, this.region);
                for (int count = 1 + random.nextInt(4); count > 0; --count) builder.claim(this.binding(resources[random.nextInt(resources.length)]), random.nextBoolean() ? Access.READ : Access.WRITE);
                final var claims = random.nextInt(8) == 0 ? Claims.fence(this.level, this.region) : builder.build();
                inputs.add(claims);
                units.add(claims.unit());
            }
            final int[] depth = new int[size];
            final List<List<Integer>> expected = new ArrayList<>();
            for (int after = 0; after < size; ++after) {
                final var b = units.get(after);
                for (int before = 0; before < after; ++before) {
                    final var a = units.get(before);
                    if (a.exclusive() || b.exclusive() || intersects(a.writes(), b.reads()) || intersects(a.writes(), b.writes()) || intersects(a.reads(), b.writes())) depth[after] = Math.max(depth[after], depth[before] + 1);
                }
                while (expected.size() <= depth[after]) expected.add(new ArrayList<>());
                expected.get(depth[after]).add(after);
            }
            assertEquals(expected, BlockEntityResourceOwnership.plan(inputs).dependencies().waves(), "seeded case " + iteration);
        }
    }

    @Test
    void sharedReadPublicationIsValidatedOncePerReservationNotCachedAcrossWaves() {
        final var calls = new AtomicInteger();
        final var valid = new AtomicBoolean(true);
        final var publication = SharedReads.capture(this.level, List.of(this.x, this.y), () -> {
            calls.incrementAndGet();
            return valid.get();
        });
        final var read = Claims.builder(this.level, this.region).sharedReads(publication).build();
        for (int wave = 0; wave < 2; ++wave) {
            try (final var owner = this.ownership()) {
                final var leases = owner.tryAcquireAll(List.of(read, read));
                assertNotNull(leases);
                for (final var lease : leases) {
                    try (final var scope = lease.enter()) {
                        Scope.require(this.level, this.x, Access.READ);
                        Scope.require(this.level, this.y, Access.READ);
                        assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
                    }
                }
            }
            assertEquals(wave + 1, calls.get());
        }
        valid.set(false);
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(read, read)));
        }
        assertEquals(3, calls.get(), "each new reservation must validate even an identical immutable footprint");
    }

    @Test
    void distinctSharedProofsAndEveryLegacyAttachmentValidatorAreRetained() {
        final var calls = new AtomicInteger();
        final var first = SharedReads.capture(this.level, List.of(this.x), () -> {
            calls.incrementAndGet();
            return true;
        });
        final var second = SharedReads.capture(this.level, List.of(this.x), () -> {
            calls.incrementAndGet();
            return false;
        });
        final var a = Claims.builder(this.level, this.region).sharedReads(first).build();
        final var b = Claims.builder(this.level, this.region).sharedReads(second).build();
        assertTrue(a.sameResources(b));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(a, a, b)));
            assertEquals(2, calls.get());
        }
        final var duplicateCalls = new AtomicInteger();
        final var binding = new Binding(new Identity(this.level, this.x), null, () -> {
            duplicateCalls.incrementAndGet();
            return true;
        }, null);
        final var expanded = Claims.builder(this.level, this.region).claim(binding, Access.READ).claim(binding, Access.READ).build();
        try (final var owner = this.ownership()) {
            assertNotNull(owner.tryAcquireAll(List.of(expanded, expanded)));
        }
        assertEquals(4, duplicateCalls.get(), "ordinary bindings are never silently converted into a group proof");
    }

    @Test
    void groupedReadsPreserveGlobalWriteAliasRefusalIncludingOtherHomesAndExpandedClaims() {
        final var shared = SharedReads.capture(this.level, List.of(this.x), () -> true);
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).claim(this.binding(this.x), Access.WRITE).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(this.binding(this.x), Access.WRITE).sharedReads(shared).build().complete());
        final Object otherRegion = new Object();
        final var write = Claims.builder(this.level, otherRegion)
                .claim(new Binding(new Identity(this.level, this.x), otherRegion, () -> true, null), Access.WRITE).build();
        final var independent = this.claims(this.y, Access.WRITE);
        final var plan = BlockEntityResourceOwnership.plan(List.of(read, independent, write, read));
        assertFalse(plan.claims().get(0).complete());
        assertTrue(plan.claims().get(1).complete());
        assertFalse(plan.claims().get(2).complete());
        assertFalse(plan.claims().get(3).complete());
        assertEquals(BlockEntityWorkPlanner.plan(plan.claims().stream().map(Claims::unit).toList()).waves(), plan.dependencies().waves());
        try (final var owner = new Owner(this.level, Map.of(this.region, () -> true, otherRegion, () -> true), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(read, write)));
            assertNull(owner.tryAcquireAll(List.of(write, read)), "conflict detection cannot depend on group visitation order");
            assertNotNull(owner.tryAcquireAll(List.of(read, read)));
        }
    }

    @Test
    void immutableGroupedAndExpandedSnapshotsHaveTheSamePermissions() {
        final List<Object> mutable = new ArrayList<>(List.of(this.x, this.y));
        final var shared = SharedReads.capture(this.level, mutable, () -> true);
        final var grouped = Claims.builder(this.level, this.region).sharedReads(shared).build();
        mutable.clear();
        mutable.add(new Object());
        final var expanded = Claims.builder(this.level, this.region)
                .claim(new Binding(new Identity(this.level, this.x), null, () -> true, null), Access.READ)
                .claim(new Binding(new Identity(this.level, this.y), null, () -> true, null), Access.READ).build();
        assertTrue(grouped.sameResources(expanded));
        assertTrue(expanded.sameResources(grouped));
        assertEquals(expanded.unit(), grouped.unit());
        final var changed = Claims.builder(this.level, this.region).sharedReads(SharedReads.capture(this.level, List.of(this.x), () -> true)).build();
        assertFalse(grouped.sameResources(changed));
        assertFalse(Claims.builder(new Object(), this.region).sharedReads(shared).build().complete());
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).sharedReads(shared).build().complete());
    }

    @Test
    void sharedGroupsStillChargeLogicalDuplicateUnitAndPhaseBudgets() {
        final var shared = SharedReads.capture(this.level, java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT, this.x), () -> true);
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        assertTrue(read.complete());
        assertFalse(Claims.builder(this.level, this.region).sharedReads(shared).claim(this.binding(this.y), Access.READ).build().complete());
        assertFalse(Claims.builder(this.level, this.region).claim(this.binding(this.y), Access.READ).sharedReads(shared).build().complete());
        final var inputs = java.util.Collections.nCopies(BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE / BlockEntityResourceOwnership.MAX_RESOURCES_PER_UNIT + 1, read);
        assertThrows(IllegalArgumentException.class, () -> BlockEntityResourceOwnership.plan(inputs));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(inputs));
            assertNotNull(owner.tryAcquireAll(List.of(read)));
        }
        final var oversized = SharedReads.capture(this.level, java.util.Collections.nCopies(Integer.MAX_VALUE, this.x), () -> true);
        assertFalse(Claims.builder(this.level, this.region).sharedReads(oversized).build().complete());
    }

    @Test
    void groupedReadsCannotGrantWorldChunkAccessAndStillCheckPostValidationBoundaries() {
        final var chunk = org.mockito.Mockito.mock(net.minecraft.world.level.chunk.LevelChunk.class);
        final var held = new AtomicBoolean(true);
        final var shared = SharedReads.capture(this.level, List.of(chunk), () -> {
            held.set(false);
            return true;
        });
        final var claims = Claims.builder(this.level, this.region).sharedReads(shared).build();
        try (final var owner = new Owner(this.level, Map.of(this.region, held::get), () -> true)) {
            assertNull(owner.tryAcquireAll(List.of(claims)), "all homes must still pass after all validators");
        }
        final var valid = Claims.builder(this.level, this.region).sharedReads(SharedReads.capture(this.level, List.of(chunk), () -> true)).build();
        try (final var owner = this.ownership()) {
            final var leases = owner.tryAcquireAll(List.of(valid));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, chunk, Access.READ);
                assertThrows(IllegalStateException.class, () -> Scope.checkChunk(chunk, false));
            }
        }
    }

    @Test
    void aThrowingSharedPublicationProofNeverLeavesPartialAuthority() {
        final var failure = new IllegalArgumentException("publication changed during proof");
        final var shared = SharedReads.capture(this.level, List.of(this.x), () -> {
            throw failure;
        });
        final var read = Claims.builder(this.level, this.region).sharedReads(shared).build();
        try (final var owner = this.ownership()) {
            assertEquals(failure, assertThrows(IllegalArgumentException.class, () -> owner.tryAcquireAll(List.of(read, read))));
            assertNotNull(owner.tryAcquireAll(List.of(this.claims(this.y, Access.WRITE))));
            assertFalse(Scope.active());
        }
    }

    @Test
    void groupedAndExpandedPlansMatchForAThousandSharedReadWriteAliasCases() {
        final var random = new Random(981371L);
        final List<Object> resources = List.of(new Object(), new Object(), new Object(), new Object());
        for (int iteration = 0; iteration < 1000; ++iteration) {
            final List<Object> reads = resources.subList(0, 1 + random.nextInt(resources.size()));
            final var shared = SharedReads.capture(this.level, reads, () -> true);
            final List<Claims> grouped = new ArrayList<>();
            final List<Claims> expanded = new ArrayList<>();
            for (int index = 0; index < 20; ++index) {
                final var a = Claims.builder(this.level, this.region);
                final var b = Claims.builder(this.level, this.region);
                if (random.nextBoolean()) {
                    a.sharedReads(shared);
                    for (final Object instance : reads) b.claim(new Binding(new Identity(this.level, instance), null, () -> true, null), Access.READ);
                }
                final Object resource = resources.get(random.nextInt(resources.size()));
                final Access mode = random.nextBoolean() ? Access.READ : Access.WRITE;
                a.claim(this.binding(resource), mode);
                b.claim(this.binding(resource), mode);
                if (random.nextInt(6) == 0) {
                    grouped.add(Claims.fence(this.level, this.region));
                    expanded.add(Claims.fence(this.level, this.region));
                } else {
                    grouped.add(a.build());
                    expanded.add(b.build());
                }
            }
            final var groupedPlan = BlockEntityResourceOwnership.plan(grouped);
            final var expandedPlan = BlockEntityResourceOwnership.plan(expanded);
            assertEquals(expandedPlan.claims().stream().map(Claims::complete).toList(), groupedPlan.claims().stream().map(Claims::complete).toList());
            assertEquals(expandedPlan.dependencies().waves(), groupedPlan.dependencies().waves(), "seeded shared case " + iteration);
            assertEquals(BlockEntityWorkPlanner.plan(groupedPlan.claims().stream().map(Claims::unit).toList()).waves(), groupedPlan.dependencies().waves());
        }
    }

    @Test
    void publishedClaimsStayDetachedWhenTheirBuilderGrowsUpgradesAndBuildsAgain() {
        final var calls = new AtomicInteger();
        final var firstBinding = new Binding(new Identity(this.level, this.x), this.region, () -> {
            calls.incrementAndGet();
            return true;
        }, null);
        final var builder = Claims.builder(this.level, this.region).claim(firstBinding, Access.READ);
        final Claims before = builder.build();
        builder.claim(firstBinding, Access.WRITE);
        for (int index = 0; index < 20; ++index) builder.claim(this.binding(new Object()), Access.READ);
        final Claims after = builder.build();
        assertFalse(before.sameResources(after));
        assertEquals(Set.of(new BlockEntityWorkPlanner.Resource(this.level, this.x)), before.unit().reads());
        assertTrue(before.unit().writes().isEmpty());
        try (final var owner = this.ownership()) {
            final var leases = owner.tryAcquireAll(List.of(before));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, this.x, Access.READ);
                assertThrows(IllegalStateException.class, () -> Scope.require(this.level, this.x, Access.WRITE));
            }
        }
        assertEquals(1, calls.get());
        try (final var owner = this.ownership()) {
            final var leases = owner.tryAcquireAll(List.of(after));
            assertNotNull(leases);
            try (final var scope = leases.getFirst().enter()) {
                Scope.require(this.level, this.x, Access.WRITE);
            }
        }
        assertEquals(3, calls.get(), "normalization must preserve both old attachment occurrences");
        builder.claim(new Binding(new Identity(new Object(), this.x), this.region, () -> true, null), Access.READ);
        assertFalse(builder.build().complete());
        assertTrue(before.complete());
        assertTrue(after.complete());
    }

    @Test
    void arrayAndIndexedClaimsKeepReferenceIdentityAtSmallLargeAndMaximumSizes() {
        for (final int count : new int[] { 1, 8, 9, 64, 512 }) {
            final List<Object> objects = new ArrayList<>();
            final var builder = Claims.builder(this.level, this.region);
            for (int index = 0; index < count; ++index) {
                final Object instance = new IdentityOnly();
                objects.add(instance);
                builder.claim(this.binding(instance), index % 2 == 0 ? Access.READ : Access.WRITE);
            }
            final Claims local = builder.build();
            assertTrue(local.complete());
            final var publication = SharedReads.capture(this.level, objects, () -> true);
            final Claims shared = Claims.builder(this.level, this.region).sharedReads(publication).build();
            objects.add(new IdentityOnly());
            for (final Claims claims : List.of(local, shared)) {
                try (final var owner = this.ownership()) {
                    final var leases = owner.tryAcquireAll(List.of(claims));
                    assertNotNull(leases);
                    try (final var scope = leases.getFirst().enter()) {
                        for (int index = 0; index < count; ++index) {
                            final Object instance = objects.get(index);
                            Scope.require(this.level, instance, Access.READ);
                            if (claims == local && index % 2 != 0) Scope.require(this.level, instance, Access.WRITE);
                            else assertThrows(IllegalStateException.class, () -> Scope.require(this.level, instance, Access.WRITE));
                        }
                        assertThrows(IllegalStateException.class, () -> Scope.require(this.level, objects.getLast(), Access.READ));
                    }
                }
            }
        }
    }

    @Test
    void collidingPairHashesWrapWithoutAliasingOrLeavingPartialReservations() {
        final List<Object> resources = new ArrayList<>();
        while (resources.size() < 12) {
            final Object instance = new IdentityOnly();
            final int hash = 31 * System.identityHashCode(this.level) + System.identityHashCode(instance);
            if (((hash ^ (hash >>> 16)) & 127) == 127) resources.add(instance);
        }
        final var builder = Claims.builder(this.level, this.region);
        for (final Object instance : resources) builder.claim(this.binding(instance), Access.READ);
        final Claims reads = builder.build();
        final Claims writer = this.claims(resources.get(5), Access.WRITE);
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), BlockEntityResourceOwnership.plan(List.of(reads, writer, reads)).dependencies().waves());
        final Claims grouped = Claims.builder(this.level, this.region).sharedReads(SharedReads.capture(this.level, resources, () -> true)).build();
        assertTrue(reads.sameResources(grouped));
        try (final var owner = this.ownership()) {
            assertNull(owner.tryAcquireAll(List.of(reads, writer)));
            final var leases = owner.tryAcquireAll(List.of(reads, grouped));
            assertNotNull(leases);
            for (final var lease : leases) {
                try (final var scope = lease.enter()) {
                    for (final Object instance : resources) Scope.require(this.level, instance, Access.READ);
                    assertThrows(IllegalStateException.class, () -> Scope.require(this.level, resources.get(5), Access.WRITE));
                    assertThrows(IllegalStateException.class, () -> Scope.require(this.level, new IdentityOnly(), Access.READ));
                }
            }
        }
    }

    @Test
    void positionedBindingsRejectAliasesAtFirstAndSubsequentPositionsAndDetachMutableCoordinates() {
        final var mutable = new net.minecraft.core.BlockPos.MutableBlockPos(17, 64, 17);
        final Binding first = new Binding(new Identity(this.level, this.x), this.region, () -> true, mutable);
        mutable.move(1, 0, 0);
        assertEquals(new net.minecraft.core.BlockPos(17, 64, 17), first.position());
        final Binding duplicate = new Binding(new Identity(this.level, this.y), this.region, () -> true, first.position());
        assertFalse(Claims.builder(this.level, this.region).claim(first, Access.READ).claim(duplicate, Access.READ).build().complete());
        final Binding other = new Binding(new Identity(this.level, new Object()), this.region, () -> true, mutable);
        final Binding otherAlias = new Binding(new Identity(this.level, new Object()), this.region, () -> true, mutable);
        assertFalse(Claims.builder(this.level, this.region).claim(first, Access.READ).claim(other, Access.READ).claim(otherAlias, Access.READ).build().complete());
        assertTrue(Claims.builder(this.level, this.region).claim(first, Access.READ).claim(other, Access.READ).claim(first, Access.READ).build().complete());
    }

    @Test
    void fullLogicalBudgetWithDuplicatedBindingsKeepsEveryValidatorAndTheWideWave() {
        final var calls = new AtomicInteger();
        final var binding = new Binding(new Identity(this.level, this.x), this.region, () -> {
            calls.incrementAndGet();
            return true;
        }, null);
        final var builder = Claims.builder(this.level, this.region);
        for (int index = 0; index < 512; ++index) builder.claim(binding, Access.READ);
        final Claims proof = builder.build();
        final List<Claims> phase = java.util.Collections.nCopies(256, proof);
        final var plan = BlockEntityResourceOwnership.plan(phase);
        assertEquals(1, plan.dependencies().waves().size());
        assertEquals(256, plan.dependencies().maxWidth());
        try (final var owner = this.ownership()) {
            assertNotNull(owner.tryAcquireAll(phase));
        }
        assertEquals(BlockEntityResourceOwnership.MAX_BINDINGS_PER_PHASE, calls.get());
        assertThrows(IllegalArgumentException.class, () -> BlockEntityResourceOwnership.plan(java.util.Collections.nCopies(257, proof)));
    }

    @Test
    void packedPhaseMatchesReferenceAliasAndDiagnosticPlannerAcrossDomainsHomesAndFormats() {
        final Random random = new Random(20261116L);
        final Object[] domains = { this.level, new IdentityOnly() };
        final Object[] homes = { this.region, new IdentityOnly(), new IdentityOnly() };
        final List<Object> objects = new ArrayList<>();
        for (int index = 0; index < 32; ++index) objects.add(new IdentityOnly());
        for (int iteration = 0; iteration < 2000; ++iteration) {
            final List<ReferenceClaim> inputs = new ArrayList<>();
            final List<Claims> claims = new ArrayList<>();
            for (int index = random.nextInt(30); index > 0; --index) {
                final Object domain = domains[random.nextInt(domains.length)];
                final Object home = homes[random.nextInt(homes.length)];
                final var builder = Claims.builder(domain, home);
                final List<Binding> bindings = new ArrayList<>();
                final Set<Identity> writes = new java.util.HashSet<>();
                final List<Object> shared = new ArrayList<>();
                if (random.nextBoolean()) {
                    for (int count = random.nextInt(12); count > 0; --count) shared.add(objects.get(random.nextInt(objects.size())));
                    builder.sharedReads(SharedReads.capture(domain, shared, () -> true));
                }
                for (int count = 1 + random.nextInt(12); count > 0; --count) {
                    final boolean global = random.nextInt(6) == 0;
                    final Access mode = !global && random.nextBoolean() ? Access.WRITE : Access.READ;
                    final Binding binding = new Binding(new Identity(domain, objects.get(random.nextInt(objects.size()))), global ? null : home, () -> true, null);
                    builder.claim(binding, mode);
                    bindings.add(binding);
                    if (mode == Access.WRITE) writes.add(binding.identity());
                }
                final Claims proof = random.nextInt(8) == 0 ? Claims.fence(domain, home) : builder.build();
                inputs.add(new ReferenceClaim(proof, domain, bindings, writes, shared));
                claims.add(proof);
            }
            final Map<Identity, Object> seenHomes = new java.util.HashMap<>();
            final Set<Identity> global = new java.util.HashSet<>();
            final Set<Identity> writes = new java.util.HashSet<>();
            final Set<Identity> invalid = new java.util.HashSet<>();
            for (final ReferenceClaim input : inputs) {
                if (!input.claims.complete()) continue;
                writes.addAll(input.writes);
                for (final Object instance : input.shared) global.add(new Identity(input.domain, instance));
                for (final Binding binding : input.bindings) {
                    if (binding.home() == null) global.add(binding.identity());
                    else {
                        final Object before = seenHomes.putIfAbsent(binding.identity(), binding.home());
                        if (before != null && before != binding.home()) invalid.add(binding.identity());
                    }
                }
            }
            for (final Identity identity : global) if (writes.contains(identity)) invalid.add(identity);
            final List<BlockEntityWorkPlanner.Unit> expectedUnits = new ArrayList<>();
            final var plan = BlockEntityResourceOwnership.plan(claims);
            for (int index = 0; index < inputs.size(); ++index) {
                final var input = inputs.get(index);
                final boolean accepted = input.claims.complete() && input.bindings.stream().noneMatch(binding -> invalid.contains(binding.identity()))
                        && input.shared.stream().noneMatch(instance -> invalid.contains(new Identity(input.domain, instance)));
                assertEquals(accepted, plan.claims().get(index).complete(), "reference alias case " + iteration);
                expectedUnits.add(accepted ? input.claims.unit() : new BlockEntityWorkPlanner.Unit(Set.of(), Set.of(), true));
            }
            final var expected = BlockEntityWorkPlanner.plan(expectedUnits);
            assertEquals(expected.waves(), plan.dependencies().waves(), "reference directed case " + iteration);
            assertEquals(expected.maxWidth(), plan.dependencies().maxWidth());
        }
    }

    private record ReferenceClaim(Claims claims, Object domain, List<Binding> bindings, Set<Identity> writes, List<Object> shared) {}

    private static final class IdentityOnly {
        @Override
        public int hashCode() {
            throw new AssertionError("Resource tables must use reference identity");
        }

        @Override
        public boolean equals(Object other) {
            throw new AssertionError("Resource tables must not call user equals");
        }
    }

    @Test
    void mixedHomeWavesExecuteTheSameStateAsCanonicalSerialIncludingUnknownFences() throws Exception {
        final Object[] homes = { new Object(), new Object(), new Object(), new Object() };
        final Object[] resources = java.util.stream.IntStream.range(0, 16).mapToObj(index -> new Object()).toArray();
        final Object publication = new Object();
        final Map<Object, java.util.function.BooleanSupplier> held = new java.util.IdentityHashMap<>();
        for (final Object home : homes) held.put(home, () -> true);
        final Random random = new Random(813584L);
        try (final var executor = Executors.newFixedThreadPool(4)) {
            for (int phase = 0; phase < 80; ++phase) {
                final int[] expected = new int[resources.length];
                final int[] actual = new int[resources.length];
                final List<Claims> claims = new ArrayList<>();
                final List<int[]> operations = new ArrayList<>();
                final SharedReads shared = SharedReads.capture(this.level, List.of(publication), () -> true);
                for (int index = 0; index < 48; ++index) {
                    final int home = random.nextInt(homes.length);
                    final int read = home * 4 + random.nextInt(4);
                    final int write = home * 4 + random.nextInt(4);
                    final boolean fence = index % 13 == 7;
                    operations.add(new int[] { read, write, index + 1, fence ? 1 : 0 });
                    final var builder = Claims.builder(this.level, homes[home]);
                    if (!fence) {
                        builder.sharedReads(shared)
                                .claim(new Binding(new Identity(this.level, resources[read]), homes[home], () -> true, null), Access.READ)
                                .claim(new Binding(new Identity(this.level, resources[write]), homes[home], () -> true, null), Access.WRITE);
                    }
                    claims.add(builder.build());
                    expected[write] = expected[read] * 31 + index + 1;
                }
                final var plan = BlockEntityResourceOwnership.plan(claims);
                for (final var wave : plan.dependencies().waves()) {
                    if (!claims.get(wave.getFirst()).complete()) {
                        assertEquals(1, wave.size());
                        final int[] op = operations.get(wave.getFirst());
                        actual[op[1]] = actual[op[0]] * 31 + op[2];
                        continue;
                    }
                    try (final var owner = new Owner(this.level, held, () -> true)) {
                        final var leases = owner.tryAcquireAll(wave.stream().map(claims::get).toList());
                        assertNotNull(leases);
                        final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                        for (int offset = 0; offset < wave.size(); ++offset) {
                            final int[] op = operations.get(wave.get(offset));
                            final var lease = leases.get(offset);
                            futures.add(executor.submit(() -> {
                                try (final var scope = lease.enter()) {
                                    Scope.require(this.level, publication, Access.READ);
                                    Scope.require(this.level, resources[op[0]], Access.READ);
                                    Scope.require(this.level, resources[op[1]], Access.WRITE);
                                    Thread.yield();
                                    actual[op[1]] = actual[op[0]] * 31 + op[2];
                                }
                            }));
                        }
                        for (final var future : futures) future.get(5, TimeUnit.SECONDS);
                        for (final var lease : leases) assertThrows(IllegalStateException.class, lease::enter);
                    }
                }
                org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, "mixed execution phase " + phase);
            }
        }
    }

    @Test
    void sourceMapMutationAfterConstructionCannotAffectDefensivelyCopiedOwnership() {
        final Object otherRegion = new Object();
        final Map<Object, BooleanSupplier> sourceMap = new HashMap<>();
        sourceMap.put(this.region, () -> true);
        final Map<Object, BooleanSupplier> sourceIdentity = new IdentityHashMap<>();
        sourceIdentity.put(this.region, () -> true);

        for (final Map<Object, BooleanSupplier> source : List.of(sourceMap, sourceIdentity)) {
            try (final var owner = new Owner(this.level, source, () -> true)) {
                final Map<?, ?> backing = boundariesField(owner.delegate);
                assertNotSame(source, backing);
                assertInstanceOf(IdentityHashMap.class, backing);
                source.put(this.region, () -> false);
                source.put(otherRegion, () -> true);
                source.clear();
                assertEquals(1, backing.size());
                assertNull(owner.tryAcquireAll(List.of(
                        Claims.builder(this.level, otherRegion).claim(new Binding(new Identity(this.level, this.x), otherRegion, () -> true, null), Access.WRITE).build())));
                final var leases = owner.tryAcquireAll(List.of(this.claims(this.x, Access.WRITE)));
                assertNotNull(leases);
                try (final var scope = leases.getFirst().enter()) {
                    Scope.require(this.level, this.x, Access.WRITE);
                }
            }
        }
    }

    @Test
    void noConstructorExposesOrAcceptsTrustedExternalMapBypass() throws Exception {
        assertEquals(0, BlockEntityResourceOwnership.class.getConstructors().length);
        final Object scratch = newScratch();
        for (final var constructor : BlockEntityResourceOwnership.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            assertFalse(Modifier.isProtected(constructor.getModifiers()));
            for (final Class<?> param : constructor.getParameterTypes()) {
                assertNotEquals(boolean.class, param, "No constructor may accept a trustBoundaries boolean");
                assertNotEquals(Boolean.class, param, "No constructor may accept a trustBoundaries Boolean");
            }
            if (List.of(constructor.getParameterTypes()).contains(Map.class)) {
                constructor.setAccessible(true);
                final Map<Object, BooleanSupplier> external = new IdentityHashMap<>();
                external.put(this.region, () -> true);
                final BlockEntityResourceOwnership created = constructor.getParameterCount() == 3
                        ? (BlockEntityResourceOwnership) constructor.newInstance(this.level, external, (BooleanSupplier) () -> true)
                        : (BlockEntityResourceOwnership) constructor.newInstance(this.level, external, (BooleanSupplier) () -> true, scratch);
                try (created) {
                    final Map<?, ?> backing = boundariesField(created);
                    assertNotSame(external, backing);
                    assertInstanceOf(IdentityHashMap.class, backing);
                }
            }
        }
    }

    @Test
    void identitySemanticsNullValidationAndDuplicateAliasHandlingAreEnforced() {
        final String keyA = new String("region-home");
        final String keyB = new String("region-home");
        assertEquals(keyA, keyB);
        assertNotSame(keyA, keyB);
        final Map<Object, BooleanSupplier> unmodifiable = Map.of(keyA, () -> true);
        try (final var owner = new Owner(this.level, unmodifiable, () -> true)) {
            assertNotSame(unmodifiable, boundariesField(owner.delegate));
            assertNull(owner.tryAcquireAll(List.of(
                    Claims.builder(this.level, keyB).claim(new Binding(new Identity(this.level, this.x), keyB, () -> true, null), Access.READ).build())));
            final var leases = owner.tryAcquireAll(List.of(
                    Claims.builder(this.level, keyA).claim(new Binding(new Identity(this.level, this.x), keyA, () -> true, null), Access.READ).build()));
            assertNotNull(leases);
        }

        final IdentityOnly identityRegion = new IdentityOnly();
        final Map<Object, BooleanSupplier> identityOnlyMap = new IdentityHashMap<>();
        identityOnlyMap.put(identityRegion, () -> true);
        try (final var owner = new Owner(this.level, identityOnlyMap, () -> true)) {
            final var leases = owner.tryAcquireAll(List.of(
                    Claims.builder(this.level, identityRegion).claim(new Binding(new Identity(this.level, this.x), identityRegion, () -> true, null), Access.READ).build()));
            assertNotNull(leases);
        }

        final Map<Object, BooleanSupplier> nullKey = new IdentityHashMap<>();
        nullKey.put(null, () -> true);
        assertThrows(NullPointerException.class, () -> new Owner(this.level, nullKey, () -> true));

        final Map<Object, BooleanSupplier> nullValue = new IdentityHashMap<>();
        nullValue.put(this.region, null);
        assertThrows(NullPointerException.class, () -> new Owner(this.level, nullValue, () -> true));
    }

    @Test
    void coreHeldRegionsFactoryIsCoreControlledIsolatedAndRejectsForeignOrDuplicateHomes() throws Exception {
        final ServerLevel serverLevel = mock(ServerLevel.class, withSettings().stubOnly());
        final ServerLevel foreignLevel = mock(ServerLevel.class, withSettings().stubOnly());
        final Object regionA = newRegionState(new RegionCoordinate(serverLevel, 0, 0));
        final Object regionB = newRegionState(new RegionCoordinate(serverLevel, 1, 0));
        final Object foreignRegion = newRegionState(new RegionCoordinate(foreignLevel, 0, 0));
        markRegionRunning(regionA);
        markRegionRunning(regionB);
        markRegionRunning(foreignRegion);
        final Object homeA = new Object();
        final Object homeB = new Object();
        final List<Object> leased = new ArrayList<>(List.of(regionA, regionB));
        final Function<RegionCoordinate, Object> mapper = coord -> coord.regionX() == 0 ? homeA : homeB;

        try (final var owner = newForHeldRegions(serverLevel, leased, mapper, () -> true, newScratch())) {
            leased.clear();
            final Map<?, ?> backing = boundariesField(owner);
            assertInstanceOf(IdentityHashMap.class, backing);
            assertEquals(2, backing.size());
            final var claimsA = Claims.builder(serverLevel, homeA)
                    .claim(new Binding(new Identity(serverLevel, this.x), homeA, () -> true, null), Access.WRITE).build();
            final var claimsB = Claims.builder(serverLevel, homeB)
                    .claim(new Binding(new Identity(serverLevel, this.y), homeB, () -> true, null), Access.WRITE).build();
            final var grants = reflectTryAcquireAll(owner, List.of(claimsA, claimsB));
            assertNotNull(grants);
            assertEquals(2, grants.size());
        }

        assertThrows(IllegalStateException.class,
                () -> newForHeldRegions(serverLevel, List.of(regionA, foreignRegion), mapper, () -> true, newScratch()));
        assertThrows(IllegalStateException.class,
                () -> newForHeldRegions(serverLevel, List.of(regionA, regionB), coord -> homeA, () -> true, newScratch()));
        assertThrows(NullPointerException.class,
                () -> newForHeldRegions(serverLevel, List.of(regionA), coord -> null, () -> true, newScratch()));
    }

    @Test
    void concurrentWorkerReadsKeepBoundariesImmutableUntilAllWorkersPhysicallyComplete() throws Exception {
        final ServerLevel serverLevel = mock(ServerLevel.class, withSettings().stubOnly());
        final Object regionA = newRegionState(new RegionCoordinate(serverLevel, 0, 0));
        final Object regionB = newRegionState(new RegionCoordinate(serverLevel, 1, 0));
        markRegionRunning(regionA);
        markRegionRunning(regionB);
        final Object homeA = new Object();
        final Object homeB = new Object();
        final List<Object> leased = new ArrayList<>(List.of(regionA, regionB));
        final Function<RegionCoordinate, Object> mapper = coord -> coord.regionX() == 0 ? homeA : homeB;

        final var owner = newForHeldRegions(serverLevel, leased, mapper, () -> true, newScratch());
        try (final var executor = Executors.newFixedThreadPool(2)) {
            leased.clear();
            final var backing = boundariesField(owner);
            final var claimsA = Claims.builder(serverLevel, homeA)
                    .claim(new Binding(new Identity(serverLevel, this.x), homeA, () -> true, null), Access.WRITE).build();
            final var claimsB = Claims.builder(serverLevel, homeB)
                    .claim(new Binding(new Identity(serverLevel, this.y), homeB, () -> true, null), Access.WRITE).build();
            final var grants = reflectTryAcquireAll(owner, List.of(claimsA, claimsB));
            assertNotNull(grants);

            final var workersEntered = new CyclicBarrier(3);
            final var allowWorkersExit = new CountDownLatch(1);
            final var futureA = executor.submit(() -> {
                try (final var scope = reflectEnter(grants.get(0))) {
                    workersEntered.await(5, TimeUnit.SECONDS);
                    Scope.require(serverLevel, this.x, Access.WRITE);
                    assertTrue(allowWorkersExit.await(5, TimeUnit.SECONDS));
                    Scope.require(serverLevel, this.x, Access.WRITE);
                }
                return null;
            });
            final var futureB = executor.submit(() -> {
                try (final var scope = reflectEnter(grants.get(1))) {
                    workersEntered.await(5, TimeUnit.SECONDS);
                    Scope.require(serverLevel, this.y, Access.WRITE);
                    assertTrue(allowWorkersExit.await(5, TimeUnit.SECONDS));
                    Scope.require(serverLevel, this.y, Access.WRITE);
                }
                return null;
            });

            workersEntered.await(5, TimeUnit.SECONDS);
            assertEquals(2, backing.size());
            assertThrows(IllegalStateException.class, owner::close);
            assertEquals(2, backing.size(), "failed close during active worker scopes must not mutate boundaries");

            allowWorkersExit.countDown();
            futureA.get(5, TimeUnit.SECONDS);
            futureB.get(5, TimeUnit.SECONDS);

            owner.close();
            assertEquals(0, backing.size());
            assertNull(reflectTryAcquireAll(owner, List.of(claimsA)));
            assertThrows(IllegalStateException.class, () -> reflectEnter(grants.get(0)));
        }
    }

    private static Map<?, ?> boundariesField(BlockEntityResourceOwnership ownership) {
        return reflect(() -> {
            final var field = BlockEntityResourceOwnership.class.getDeclaredField("boundaries");
            field.setAccessible(true);
            return (Map<?, ?>) field.get(ownership);
        });
    }

    private static Object newScratch() {
        return reflect(() -> {
            final Class<?> scratchType = Class.forName(BlockEntityResourceOwnership.class.getName() + "$Scratch");
            final var constructor = scratchType.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        });
    }

    private static Object newRegionState(RegionCoordinate coordinate) {
        return reflect(() -> {
            final Class<?> regionStateType = Class.forName("net.neoforged.neoforge.server.threading.region.RegionState");
            final var constructor = regionStateType.getDeclaredConstructor(RegionCoordinate.class);
            constructor.setAccessible(true);
            return constructor.newInstance(coordinate);
        });
    }

    private static void markRegionRunning(Object regionState) {
        reflect(() -> {
            final var method = regionState.getClass().getDeclaredMethod("tryMarkRunning");
            method.setAccessible(true);
            assertTrue((Boolean) method.invoke(regionState));
            return null;
        });
    }

    private static BlockEntityResourceOwnership newForHeldRegions(
            ServerLevel level,
            List<?> leased,
            Function<RegionCoordinate, Object> homeForCoordinate,
            BooleanSupplier live,
            Object scratch) {
        return reflect(() -> {
            final Class<?> scratchType = Class.forName(BlockEntityResourceOwnership.class.getName() + "$Scratch");
            final var method = BlockEntityResourceOwnership.class.getDeclaredMethod(
                    "forHeldRegions", ServerLevel.class, List.class, Function.class, BooleanSupplier.class, scratchType);
            method.setAccessible(true);
            return (BlockEntityResourceOwnership) method.invoke(null, level, leased, homeForCoordinate, live, scratch);
        });
    }

    @SuppressWarnings("unchecked")
    private static List<BlockEntityResourceOwnership.Lease> reflectTryAcquireAll(BlockEntityResourceOwnership ownership, List<Claims> claims) {
        return reflect(() -> {
            final var method = BlockEntityResourceOwnership.class.getDeclaredMethod("tryAcquireAll", List.class);
            method.setAccessible(true);
            return (List<BlockEntityResourceOwnership.Lease>) method.invoke(ownership, claims);
        });
    }

    private static Scope reflectEnter(BlockEntityResourceOwnership.Lease lease) {
        return reflect(() -> {
            final var method = BlockEntityResourceOwnership.Lease.class.getDeclaredMethod("enter");
            method.setAccessible(true);
            return (Scope) method.invoke(lease);
        });
    }

    // Tests reflect the internal grant/entry gates instead of exporting a permission-minting test API.
    private static final class Owner implements AutoCloseable {
        private final BlockEntityResourceOwnership delegate;

        Owner(Object domain, Map<Object, java.util.function.BooleanSupplier> regions, java.util.function.BooleanSupplier live) {
            this.delegate = reflect(() -> {
                final var constructor = BlockEntityResourceOwnership.class.getDeclaredConstructor(Object.class, Map.class, java.util.function.BooleanSupplier.class);
                constructor.setAccessible(true);
                return constructor.newInstance(domain, regions, live);
            });
        }

        List<Lease> tryAcquireAll(List<Claims> claims) {
            final List<?> granted = reflect(() -> {
                final var method = BlockEntityResourceOwnership.class.getDeclaredMethod("tryAcquireAll", List.class);
                method.setAccessible(true);
                return (List<?>) method.invoke(this.delegate, claims);
            });
            return granted == null ? null : granted.stream().map(value -> new Lease((BlockEntityResourceOwnership.Lease) value)).toList();
        }

        @Override
        public void close() {
            this.delegate.close();
        }
    }

    private record Lease(BlockEntityResourceOwnership.Lease delegate) {
        Scope enter() {
            return reflect(() -> {
                final var method = BlockEntityResourceOwnership.Lease.class.getDeclaredMethod("enter");
                method.setAccessible(true);
                return (Scope) method.invoke(this.delegate);
            });
        }
    }

    @FunctionalInterface
    private interface Reflection<T> {
        T run() throws ReflectiveOperationException;
    }

    private static <T> T reflect(Reflection<T> operation) {
        try {
            return operation.run();
        } catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            if (exception.getCause() instanceof Error error) throw error;
            throw new AssertionError(exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static boolean intersects(Set<?> first, Set<?> second) {
        return first.stream().anyMatch(second::contains);
    }
}
