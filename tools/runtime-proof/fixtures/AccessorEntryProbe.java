/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

/** An accessor-like name is not evidence that a method has no earlier side effects. */
public final class AccessorEntryProbe {
    private static final class Target {
        int value;
        static int earlierEffects;

        public int getType() {
            earlierEffects++;
            value++;
            return value;
        }

        public int scalar() {
            return value;
        }
    }

    public static void main(String[] args) throws Exception {
        final Target target = new Target();
        final Thread coordinator = Thread.currentThread();
        if (!RuntimeProof.protect(target, (root, write) -> Thread.currentThread() == coordinator))
            throw new AssertionError("Root interception absent");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread foreign = new Thread(() -> {
            try {
                boolean refused = false;
                try {
                    target.getType();
                } catch (IllegalStateException expected) {
                    refused = true;
                }
                if (!refused) throw new AssertionError("Foreign method entry accepted");
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "unowned-accessor-name-writer");
        foreign.start();
        foreign.join(10000);
        if (foreign.isAlive() || failure.get() != null || target.scalar() != 0 || Target.earlierEffects != 0)
            throw new AssertionError("Accessor-name method crossed entry boundary: value=" + target.value
                    + " earlierEffects=" + Target.earlierEffects, failure.get());
        System.out.println("ACCESSOR_NAME_REFUSED_BEFORE_EFFECTS value=0 earlierEffects=0 scalarFieldWatch=true noCoreLeaseIssued=true");
    }
}
