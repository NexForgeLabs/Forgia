/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.function.Supplier;

/** Checked detached access. Observations diagnose violations; they NEVER authorize future work. */
public final class C7AccessScope implements AutoCloseable {
    public static final class UndeclaredAccessException extends IllegalStateException {
        UndeclaredAccessException() {
            super("C7 undeclared resource access; wave aborted without commit");
        }
    }

    private final C7Footprint declared;
    private final java.util.function.Consumer<C7Resource> violation;
    private final Thread owner = Thread.currentThread();
    private boolean closed;

    C7AccessScope(C7Footprint declared, java.util.function.Consumer<C7Resource> violation) {
        this.declared = declared;
        this.violation = violation;
    }

    public <T> T read(C7Resource resource, Supplier<T> read) {
        this.check(resource, C7Footprint.Access.READ);
        return read.get();
    }

    public void write(C7Resource resource, Runnable write) {
        this.check(resource, C7Footprint.Access.WRITE);
        write.run();
    }

    private void check(C7Resource resource, C7Footprint.Access access) {
        if (this.closed || Thread.currentThread() != this.owner || !this.declared.permits(resource, access)) {
            this.violation.accept(resource);
            throw new UndeclaredAccessException();
        }
    }

    @Override
    public void close() {
        this.closed = true;
    }
}
