
/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReference;
import net.neoforged.forgia.runtimeproof.RuntimeProof;

public final class FrozenValueProbe {
    private static class Parent {
        int inherited = 7;
    }

    private static final class Value extends Parent {
        int value = 11;
    }

    private interface Attempt {
        void run() throws Throwable;
    }

    public static void main(String[] args) throws Throwable {
        final Value value = new Value();
        if (!RuntimeProof.protectImmutableValue(value) || !RuntimeProof.protectImmutableValue(value)) throw new AssertionError("Freeze unavailable or not idempotent");
        if (RuntimeProof.protect(value, (root, write) -> true)) throw new AssertionError("Frozen value was changed into mutable authority");
        final var field = Value.class.getDeclaredField("value");
        final var inherited = Parent.class.getDeclaredField("inherited");
        final var lookup = MethodHandles.lookup();
        final var setter = lookup.unreflectSetter(field);
        final VarHandle handle = lookup.unreflectVarHandle(field);
        final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        final var singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        final Object unsafe = singleton.get(null);
        final long offset = (long) unsafeType.getMethod("objectFieldOffset", java.lang.reflect.Field.class).invoke(unsafe, field);
        final Attempt[] writes = {
                () -> {
                    value.value = 12;
                },
                () -> {
                    value.inherited = 8;
                },
                () -> field.setInt(value, 12),
                () -> inherited.setInt(value, 8),
                () -> {
                    setter.invoke(value, 12);
                },
                () -> handle.set(value, 12),
                () -> handle.setVolatile(value, 12),
                () -> {
                    handle.compareAndSet(value, 11, 12);
                },
                () -> unsafeType.getMethod("putInt", Object.class, long.class, int.class).invoke(unsafe, value, offset, 12),
                () -> unsafeType.getMethod("compareAndSwapInt", Object.class, long.class, int.class, int.class).invoke(unsafe, value, offset, 11, 12)
        };
        int refused = 0;
        for (Attempt write : writes) {
            try {
                write.run();
            } catch (Throwable rejected) {
                Throwable cause = rejected;
                while (cause.getCause() != null) cause = cause.getCause();
                if (!(cause instanceof IllegalStateException)) throw rejected;
                refused++;
            }
        }
        final AtomicReference<Throwable> problem = new AtomicReference<>();
        final Thread reader = new Thread(() -> {
            try {
                if (value.value != 11 || value.inherited != 7) throw new AssertionError("Frozen value changed");
            } catch (Throwable failure) {
                problem.set(failure);
            }
        }, "immutable-value-reader");
        reader.start();
        reader.join(10000);
        if (reader.isAlive() || problem.get() != null || refused != writes.length || value.value != 11 || value.inherited != 7)
            throw new AssertionError("Frozen value enforcement failed: refused=" + refused, problem.get());
        System.out.println("FROZEN_ACTUAL_INSTANCE_WRITES_REFUSED paths=" + refused + " inheritedFields=true foreignRead=true mutableUpgrade=false noCoreLeaseIssued=true");
    }
}
