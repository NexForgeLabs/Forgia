/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

/** Actual JNI field and method entry controls through the VM's real JNI function table. */
public final class JniAccessProbe {
    private int value;
    private static int earlierEffects;
    private static final ThreadLocal<Boolean> OWNED = ThreadLocal.withInitial(() -> false);

    public void complexWriter() {
        earlierEffects++;
        value = 991;
    }

    private static native void setIntField(Object target, String field, int value);

    private static native void invokeVoid(Object target, String method);

    @FunctionalInterface
    interface Attempt {
        void run() throws Throwable;
    }

    private static void refused(Attempt attempt, String route) throws Throwable {
        try {
            attempt.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(route + " escaped the original Core authority");
    }

    public static void main(String[] args) throws Throwable {
        if (!RuntimeProof.enforcementAvailable()) throw new AssertionError("service not ready");
        final JniAccessProbe target = new JniAccessProbe();
        final Thread coordinator = Thread.currentThread();
        if (!RuntimeProof.protect(target, (root, write) -> root == target && (OWNED.get() || Thread.currentThread() == coordinator)))
            throw new AssertionError("no root interception");
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread foreign = new Thread(() -> {
            try {
                refused(() -> setIntField(target, "value", 1234), "JNI field write");
                refused(() -> invokeVoid(target, "complexWriter"), "JNI method invocation");
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "unowned-jni-access");
        foreign.start();
        foreign.join(10000);
        if (foreign.isAlive()) throw new AssertionError("JNI access controls did not finish");
        if (failure.get() != null) throw new AssertionError("JNI access control failed", failure.get());
        if (target.value != 0 || earlierEffects != 0)
            throw new AssertionError("JNI route changed protected state: value=" + target.value + " effects=" + earlierEffects);
        OWNED.set(true);
        setIntField(target, "value", 7);
        if (target.value != 7) throw new AssertionError("Owner JNI field write did not participate in the original authority");
        invokeVoid(target, "complexWriter");
        if (target.value != 991 || earlierEffects != 1)
            throw new AssertionError("Owner JNI method invocation did not participate: value=" + target.value + " effects=" + earlierEffects);
        OWNED.set(false);
        System.out.println("JNI_ACCESS_REFUSED value=0 earlierEffects=0 ownerFieldWrite=7 ownerMethodCall=991 ownerEffects=1");
    }
}
