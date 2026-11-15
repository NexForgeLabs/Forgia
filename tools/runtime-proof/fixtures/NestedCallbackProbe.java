
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class NestedCallbackProbe {
    private static final class Target {
        int value;
        static int earlierEffects;

        void originalWriter() {
            earlierEffects++;
            value++;
        }
    }

    private static final class Trigger {
        int value;

        void invoke() {
            value++;
        }
    }

    public static void main(String[] args) throws Exception {
        final Target target = new Target();
        final Trigger trigger = new Trigger();
        final Thread coordinator = Thread.currentThread();
        final AtomicBoolean armed = new AtomicBoolean();
        if (!RuntimeProof.protect(target, (root, write) -> Thread.currentThread() == coordinator)) throw new AssertionError("Target guard absent");
        if (!RuntimeProof.protect(trigger, (root, write) -> {
            if (armed.compareAndSet(true, false)) target.originalWriter();
            return true;
        })) throw new AssertionError("Trigger guard absent");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread actor = new Thread(() -> {
            try {
                boolean directDenied = false;
                try {
                    target.originalWriter();
                } catch (IllegalStateException expected) {
                    directDenied = true;
                }
                if (!directDenied) throw new AssertionError("Direct unowned writer was accepted");
                armed.set(true);
                boolean nestedDenied = false;
                try {
                    trigger.invoke();
                } catch (IllegalStateException expected) {
                    nestedDenied = true;
                }
                if (!nestedDenied) throw new AssertionError("An untrusted access callback bypassed a different protected root");
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "untrusted-nested-access-callback");
        actor.start();
        actor.join(10000);
        if (actor.isAlive()) throw new AssertionError("Nested callback did not finish");
        if (failure.get() != null || target.value != 0 || Target.earlierEffects != 0)
            throw new AssertionError("Nested callback altered protected state/earlier effects: value=" + target.value + " effects=" + Target.earlierEffects, failure.get());
        System.out.println("UNTRUSTED_NESTED_CALLBACK_REFUSED_BEFORE_METHOD_EFFECTS originalValue=0 earlierEffects=0 noCoreLeaseIssued=true");
    }
}
