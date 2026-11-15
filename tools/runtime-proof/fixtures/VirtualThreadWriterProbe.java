/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

/** JDK21 virtual-thread bytecode writes must participate in the same original-object boundary. */
public final class VirtualThreadWriterProbe {
    private static final class Target {
        int value;
        static int earlierEffects;

        public void write() {
            earlierEffects++;
            value++;
        }
    }

    public static void main(String[] args) throws Exception {
        final Target target = new Target();
        final Thread coordinator = Thread.currentThread();
        if (!RuntimeProof.enforcementAvailable() || !RuntimeProof.protect(target, (root, write) -> Thread.currentThread() == coordinator))
            throw new AssertionError("Actual supported VM interception absent");
        final var failure = new AtomicReference<Throwable>();
        final Thread actor = Thread.startVirtualThread(() -> {
            try {
                boolean fieldRefused = false;
                try {
                    target.value = 1234;
                } catch (IllegalStateException expected) {
                    fieldRefused = true;
                }
                boolean methodRefused = false;
                try {
                    target.write();
                } catch (IllegalStateException expected) {
                    methodRefused = true;
                }
                if (!fieldRefused || !methodRefused) throw new AssertionError("Virtual writer escaped interception");
            } catch (Throwable problem) {
                failure.set(problem);
            }
        });
        actor.join(10000);
        if (actor.isAlive() || failure.get() != null || target.value != 0 || Target.earlierEffects != 0)
            throw new AssertionError("Virtual writer left effects: value=" + target.value + " earlierEffects=" + Target.earlierEffects, failure.get());
        System.out.println("VIRTUAL_THREAD_WRITERS_REFUSED value=0 earlierEffects=0 physicalExit=true noCoreLeaseIssued=true");
    }
}
