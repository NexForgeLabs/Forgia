
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class PublicationRaceProbe {
    private int value;
    private static volatile boolean reserved;
    private static final ThreadLocal<Boolean> owner = ThreadLocal.withInitial(() -> false);

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("latch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private void original(int v) {
        value = v;
    }

    public static void main(String[] args) throws Exception {
        var target = new PublicationRaceProbe();
        var checkEntered = new CountDownLatch(1);
        var checkReturned = new CountDownLatch(1);
        var actorFailed = new AtomicReference<Throwable>();
        var arm = new AtomicBoolean(true);
        RuntimeProof.AccessCheck check = (root, write) -> {
            if (root != target) return false;
            if (write && arm.compareAndSet(true, false)) {
                checkEntered.countDown();
                await(checkReturned);
            }
            return owner.get() || !reserved;
        };
        if (!RuntimeProof.protect(target, check)) throw new AssertionError("protection absent");
        Thread actor = new Thread(() -> {
            try {
                target.original(17);
            } catch (Throwable e) {
                actorFailed.set(e);
            }
        }, "unparticipating-entry-race");
        actor.start();
        await(checkEntered);
        if (RuntimeProof.beginCoreReservation()) {
            RuntimeProof.endCoreReservation();
            throw new AssertionError("reservation crossed in-flight check");
        }
        checkReturned.countDown();
        actor.join(10000);
        if (actor.isAlive() || actorFailed.get() != null || target.value != 17) throw new AssertionError("initial complete write missing", actorFailed.get());
        if (!RuntimeProof.beginCoreReservation()) throw new AssertionError("reservation refused at quiescence available=" + RuntimeProof.available() + " enforcement=" + RuntimeProof.enforcementAvailable());
        reserved = true;
        Thread beforePublication = new Thread(() -> {
            try {
                target.original(21);
                actorFailed.set(new AssertionError("queued writer entered"));
            } catch (IllegalStateException expected) {}
        }, "writer-during-publication");
        beforePublication.start();
        beforePublication.join(10000);
        RuntimeProof.endCoreReservation();
        if (beforePublication.isAlive() || actorFailed.get() != null) throw new AssertionError("publication window", actorFailed.get());
        owner.set(true);
        if (target.value != 17) throw new AssertionError("queued write changed storage");
        target.original(31);
        owner.set(false);
        Thread afterScope = new Thread(() -> {
            try {
                target.original(37);
                actorFailed.set(new AssertionError("writer entered before resource release"));
            } catch (IllegalStateException expected) {}
        }, "writer-after-owner-scope");
        afterScope.start();
        afterScope.join(10000);
        if (afterScope.isAlive() || actorFailed.get() != null) throw new AssertionError("early release", actorFailed.get());
        if (!RuntimeProof.beginCoreReservation()) throw new AssertionError("release window");
        reserved = false;
        RuntimeProof.endCoreReservation();
        if (target.value != 31) throw new AssertionError("original storage mutation count");
        System.out.println("PUBLICATION_RACE_COMPONENT_PASS inFlightRefused=true publicationRefused=true activeOwnerWrites=true afterScopeBeforeReleaseRefused=true originalFinal=31 noCoreLeaseMintedByProbe=true");
    }
}
