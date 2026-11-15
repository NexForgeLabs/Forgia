/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static net.neoforged.neoforge.unittest.CoreModExecutionProofTest.call;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;
import net.neoforged.neoforge.server.threading.region.RegionEntityStorageMode;
import net.neoforged.neoforge.server.threading.region.RegionLayout;
import net.neoforged.neoforge.server.threading.region.RegionThreadingEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Actual Core callback references must not pin a removed Engine/Level or grant a foreign thread. */
@Timeout(30)
class CoreModWeakOwnerTest {
    private static Object authority(Object engine, Object level, Object home) throws Exception {
        final Class<?> type = Class.forName("net.neoforged.neoforge.server.threading.region.CoreModPreparation$AccessAuthority");
        final Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(engine, level, home);
    }

    private static boolean allowed(Object check, Object root, boolean write) throws Exception {
        final Method method = check.getClass().getDeclaredMethod("allowed", Object.class, boolean.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(check, root, write);
    }

    @Test
    void aClasspathBridgeDoesNotEnableTheOptionalVmOrCoreWorkerAdmission() throws Exception {
        final Class<?> runtime = Class.forName("net.neoforged.forgia.runtimeproof.RuntimeProof");
        assertNotNull(runtime.getClassLoader(), "This test must not install a bootstrap service");
        assertFalse((Boolean) runtime.getMethod("available").invoke(null));
        assertFalse((Boolean) runtime.getMethod("enforcementAvailable").invoke(null));
        final Class<?> verifier = Class.forName("net.neoforged.neoforge.server.threading.region.CoreModTickerVerifier");
        for (final String name : List.of("installed", "available")) {
            final Method method = verifier.getDeclaredMethod(name);
            method.setAccessible(true);
            assertFalse((Boolean) method.invoke(null));
        }
    }

    @Test
    void retiredWeakOwnerKeepsOnlyOriginalCoordinatorSerialCompatibility() throws Exception {
        final Retained retained = retireOwner();
        // refersTo does not promote either weak referent into a live GC root during polling.
        for (int attempt = 0; attempt < 100 && (!retained.engine.refersTo(null) || !retained.level.refersTo(null)); attempt++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(retained.engine.get(), "retained native callback must not pin the old Engine");
        assertNull(retained.level.get(), "retained native callback must not pin the old Level");
        final Object root = new Object();
        assertTrue(allowed(retained.check, root, false));
        assertTrue(allowed(retained.check, root, true));
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread foreign = new Thread(() -> {
            try {
                assertFalse(allowed(retained.check, root, false));
                assertFalse(allowed(retained.check, root, true));
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "C6-retired-owner-foreign-access");
        foreign.start();
        foreign.join(10000);
        assertFalse(foreign.isAlive());
        assertNull(failure.get());
        System.out.println("C6_WEAK_OWNER_GC engineCollected=true levelCollected=true originalSerialCompatible=true foreignReadWriteRefused=true");
    }

    @Test
    void weakOwnerFallbackCannotBypassARunningHomeOrAnEnteredResourceScope() throws Exception {
        final Class<?> regionType = Class.forName("net.neoforged.neoforge.server.threading.region.RegionState");
        final Object home = org.mockito.Mockito.mock(regionType);
        final Method running = regionType.getDeclaredMethod("isRunning");
        running.setAccessible(true);
        org.mockito.Mockito.when((Boolean) running.invoke(home)).thenReturn(true);
        final Object check = authority(null, null, home);
        final Object root = new Object();
        assertFalse(allowed(check, root, false));
        assertFalse(allowed(check, root, true));
        org.mockito.Mockito.when((Boolean) running.invoke(home)).thenReturn(false);
        assertTrue(allowed(check, root, true));
        final var fixture = new BlockEntityResourceOwnershipTest();
        final Object owner = call(fixture, "ownership");
        try {
            final Object claims = call(fixture, "claims", root, BlockEntityResourceOwnership.Access.WRITE);
            final var leases = (List<?>) call(owner, "tryAcquireAll", List.of(claims));
            try (var scope = (BlockEntityResourceOwnership.Scope) call(leases.getFirst(), "enter")) {
                assertFalse(allowed(check, root, false));
                assertFalse(allowed(check, root, true));
            }
            assertTrue(allowed(check, root, true));
        } finally {
            call(owner, "close");
        }
    }

    private static Retained retireOwner() throws Exception {
        // The broad invocation fixture installs observer/stubbing closures that retain its whole graph.
        // This test isolates the production callback with a real shutdown Engine and an unstubbed Level.
        final var engine = new RegionThreadingEngine(2, false, false, false, true, false, false, false,
                false, false, RegionLayout.FIXED_3X3, RegionEntityStorageMode.MAIN_THREAD_ONLY);
        final var level = org.mockito.Mockito.mock(ServerLevel.class);
        try {
            final Object check = authority(engine, level, null);
            return new Retained(check, new WeakReference<>(engine), new WeakReference<>(level));
        } finally {
            engine.shutdown();
            org.mockito.Mockito.framework().clearInlineMock(level);
        }
    }

    private record Retained(Object check, WeakReference<RegionThreadingEngine> engine, WeakReference<Object> level) {}
}
