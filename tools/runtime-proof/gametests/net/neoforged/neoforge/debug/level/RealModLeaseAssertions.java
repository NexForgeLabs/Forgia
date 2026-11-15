/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.debug.level;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership;

/** Actual post-admission observation only. This fixture never creates a Permit, Lease or Claims. */
final class RealModLeaseAssertions {
    private RealModLeaseAssertions() {}

    static Object[] capture() {
        try {
            final var current = (ThreadLocal<?>) field(BlockEntityResourceOwnership.class, "CURRENT").get(null);
            final Object scope = current.get();
            if (scope == null) throw new AssertionError("Actual resource scope absent");
            final Object lease = field(scope.getClass(), "lease").get(scope);
            final Object owner = field(lease.getClass(), "owner").get(lease);
            final Class<?> kernel = Class.forName("net.neoforged.neoforge.server.threading.region.BlockEntityExecutionEngine");
            final Object permit = ((ThreadLocal<?>) field(kernel, "CURRENT").get(null)).get();
            if (permit == null) throw new AssertionError("Original Kernel Permit absent");
            final Object ticket = field(permit.getClass(), "ticket").get(permit);
            final Object inspection = field(ticket.getClass(), "inspection").get(ticket);
            if (!"RUNNING".equals(field(ticket.getClass(), "state").get(ticket).toString())) throw new AssertionError("Original ticket not running");
            if (!"MOD_CLAIMS".equals(call(inspection, "resources").toString())) throw new AssertionError("Foreign resource description");
            if (field(permit.getClass(), "resources").get(permit) != lease || field(ticket.getClass(), "modClaims").get(ticket) != field(lease.getClass(), "claims").get(lease))
                throw new AssertionError("Kernel and actual Lease do not share this Ticket's exact Claims");
            return new Object[] { scope, lease, owner, ticket };
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    static void assertRunningExclusion(Object[] actual) {
        final var failure = new AtomicReference<Throwable>();
        final var finished = new CountDownLatch(1);
        final Thread foreign = new Thread(() -> {
            try {
                expectRefusal(actual[1], "enter");
                expectRefusal(actual[0], "close");
                expectRefusal(actual[2], "close");
                if (BlockEntityResourceOwnership.Scope.active()) throw new AssertionError("Foreign thread inherited resource authority");
            } catch (Throwable problem) {
                failure.set(problem);
            } finally {
                finished.countDown();
            }
        }, "C6-actual-Mod-Lease-reentry-control");
        foreign.start();
        try {
            if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("Actual Lease control did not finish");
            foreign.join(10000);
            if (foreign.isAlive() || failure.get() != null) throw new AssertionError("Actual running Mod Lease exclusion failed", failure.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    static void assertRetired(Object[] actual) {
        try {
            if (actual == null) throw new AssertionError("No actual Mod invocation captured");
            expectRefusal(actual[1], "enter");
            expectRefusal(actual[0], "close");
            expectRefusal(actual[2], "close");
            final Object ticket = actual[3];
            if (!"SUCCEEDED".equals(field(ticket.getClass(), "state").get(ticket).toString())
                    || !"RETIRED".equals(field(ticket.getClass(), "adapterProof").get(ticket).toString())
                    || field(ticket.getClass(), "modManifest").get(ticket) != null || field(ticket.getClass(), "modClaims").get(ticket) != null)
                throw new AssertionError("The original completed Ticket retained Mod proof or execution authority");
            System.out.println("C6_REAL_MOD_LEASE originalClaimsIdentity=true foreignEnterRefused=true foreignScopeCloseRefused=true foreignOwnerCloseRefused=true afterJoinReenterRefused=true duplicateReleaseRefused=true originalTicketSucceededAndRetired=true proofReferencesCleared=true");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void expectRefusal(Object actual, String name) throws ReflectiveOperationException {
        try {
            call(actual, name);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof IllegalStateException) return;
            throw wrapped;
        }
        throw new AssertionError("Original consumed/running/foreign authority was accepted: " + name);
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object call(Object actual, String name) throws ReflectiveOperationException {
        final Method method = actual.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(actual);
    }
}
