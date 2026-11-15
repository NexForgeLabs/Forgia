/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

/** Root-table growth runs concurrently with actual VM entry checks; no Core authority is issued. */
public final class ConcurrentRegistrationProbe {
    private static final class Target {
        private int value;
        private static int earlierEffects;

        public void write() {
            earlierEffects++;
            value++;
        }

        public int scalar() { return value; }
    }

    public static void main(String[] args) throws Exception {
        final Thread coordinator = Thread.currentThread();
        final RuntimeProof.AccessCheck check = (root, write) -> Thread.currentThread() == coordinator;
        final Target original = new Target();
        if (!RuntimeProof.protect(original, check)) throw new AssertionError("Initial root absent");
        final var roots = new ArrayList<Target>();
        roots.add(original);
        final var ready = new CountDownLatch(1);
        final var stop = new AtomicBoolean();
        final var refused = new AtomicInteger();
        final var failure = new AtomicReference<Throwable>();
        final Thread actor = new Thread(() -> {
            ready.countDown();
            try {
                while (!stop.get()) {
                    try {
                        original.write();
                        throw new AssertionError("Concurrent foreign entry accepted");
                    } catch (IllegalStateException expected) {
                        refused.incrementAndGet();
                    }
                }
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "C6-concurrent-root-registration-writer");
        actor.start();
        try {
            if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("Writer absent");
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            for (int index = 0; index < 32; index++) {
                final Target next = new Target();
                while (!RuntimeProof.protect(next, check)) {
                    if (System.nanoTime() > deadline || failure.get() != null) throw new AssertionError("Registration failed to settle", failure.get());
                    Thread.yield();
                }
                roots.add(next);
                final int before = refused.get();
                while (refused.get() == before) {
                    if (System.nanoTime() > deadline || failure.get() != null) throw new AssertionError("Concurrent checks stopped", failure.get());
                    Thread.yield();
                }
            }
        } finally {
            stop.set(true);
            actor.join(10000);
        }
        if (actor.isAlive() || failure.get() != null || roots.stream().anyMatch(root -> root.scalar() != 0) || Target.earlierEffects != 0)
            throw new AssertionError("Concurrent registration left effects", failure.get());
        original.write();
        if (original.scalar() != 1 || Target.earlierEffects != 1) throw new AssertionError("Owner control failed");
        System.out.println("CONCURRENT_ROOT_REGISTRATION_REFUSED roots=" + roots.size() + " denied=" + refused.get()
                + " protectedValues=0 earlierEffects=0 ownerValue=1 ownerEffects=1 noCoreLeaseIssued=true");
    }
}
